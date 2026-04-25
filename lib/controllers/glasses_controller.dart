import 'dart:async';
import 'dart:io';

import 'package:falcon_one_demo/platform/bleequp_bridge.dart';
import 'package:falcon_one_demo/services/glasses_service.dart';
import 'package:flutter/material.dart';
import 'package:get/get.dart';
import 'package:permission_handler/permission_handler.dart';

/// BleeqUp glasses UX state — isolated from [MapController] / W1.
class GlassesController extends GetxController {
  GlassesController({GlassesService? service}) : _glasses = service ?? Get.find<GlassesService>();

  final GlassesService _glasses;

  static const String _apiKey = String.fromEnvironment('BLEEQUP_API_KEY', defaultValue: '');

  final RxBool isConnected = false.obs;
  final RxBool isRecording = false.obs;
  final RxBool isDownloading = false.obs;
  final RxDouble downloadProgress = 0.0.obs;
  final RxString statusMessage = 'Connecting to Glasses...'.obs;
  final Rxn<String> lastVideoPath = Rxn<String>();

  final RxBool downloadFailed = false.obs;
  final RxBool isPreparingVideo = false.obs;
  final RxBool isScanning = false.obs;

  final RxList<Map<String, String>> discoveredDevices = <Map<String, String>>[].obs;

  bool _recordingActionInFlight = false;
  bool _downloadPipelineInFlight = false;
  String? _pendingFileNameForRetry;

  final RxString wifiPassword = ''.obs;

  StreamSubscription<GlassesConnectionState>? _connSub;
  StreamSubscription<bool>? _recSub;
  StreamSubscription<double>? _progSub;
  StreamSubscription<String>? _videoSub;
  StreamSubscription<String>? _errSub;
  StreamSubscription<Map<String, String>>? _devSub;

  @override
  void onInit() {
    super.onInit();
    _wireStreams();
  }

  void _wireStreams() {
    _connSub = _glasses.connectionState.listen(_onConnection);
    _recSub = _glasses.recordingState.listen((bool on) {
      isRecording.value = on;
      if (on) {
        statusMessage.value = '🔴 Recording in progress...';
      }
    });
    _progSub = _glasses.downloadProgress.listen((double p) {
      downloadProgress.value = p;
      if (isDownloading.value) {
        final pct = (p * 100).round();
        statusMessage.value = 'Downloading video... $pct%';
      }
    });
    _videoSub = _glasses.videoReady.listen(_onVideoReadyFromSdk);
    _errSub = _glasses.nativeErrors.listen((String msg) {
      downloadFailed.value = true;
      statusMessage.value = '⚠️ $msg';
      isDownloading.value = false;
      isPreparingVideo.value = false;
      _downloadPipelineInFlight = false;
    });
    _devSub = _glasses.deviceFound.listen((Map<String, String> m) {
      final addr = m['address'] ?? '';
      if (addr.isEmpty) return;
      if (discoveredDevices.any((e) => e['address'] == addr)) return;
      discoveredDevices.add(Map<String, String>.from(m));
    });
  }

  void _onConnection(GlassesConnectionState s) {
    if (s == GlassesConnectionState.connected) {
      isConnected.value = true;
      if (!isRecording.value && !isDownloading.value && !isPreparingVideo.value) {
        if (lastVideoPath.value != null) {
          statusMessage.value = '✅ Video ready';
        } else {
          statusMessage.value = 'Glasses connected';
        }
      }
    } else {
      isConnected.value = false;
      isRecording.value = false;
      isDownloading.value = false;
      isPreparingVideo.value = false;
      downloadProgress.value = 0;
      _downloadPipelineInFlight = false;
      _recordingActionInFlight = false;
      lastVideoPath.value = null;
      downloadFailed.value = false;
      discoveredDevices.clear();
      statusMessage.value = 'Connecting to Glasses...';
    }
  }

  Future<bool> _ensureGlassesPermissions() async {
    if (!Platform.isAndroid) return false;
    final perms = <Permission>[
      Permission.bluetoothScan,
      Permission.bluetoothConnect,
      Permission.locationWhenInUse,
      Permission.nearbyWifiDevices,
    ];
    final statuses = await perms.request();
    final denied = statuses.entries.where((e) => e.value.isDenied || e.value.isPermanentlyDenied);
    return denied.isEmpty;
  }

  /// Tap **Connect glasses**: init SDK → BLE scan → pick device in sheet → connect.
  Future<void> beginConnectFlow() async {
    if (!BleeqUpBridge.isSupported) {
      statusMessage.value = 'Glasses require Android';
      return;
    }
    if (_apiKey.isEmpty) {
      statusMessage.value = '⚠️ Set BLEEQUP_API_KEY (--dart-define)';
      return;
    }
    final ok = await _ensureGlassesPermissions();
    if (!ok) {
      statusMessage.value = '⚠️ Bluetooth / location / Wi‑Fi permission required';
      return;
    }
    discoveredDevices.clear();
    isScanning.value = true;
    downloadFailed.value = false;
    statusMessage.value = 'Connecting to Glasses...';
    try {
      await _glasses.initSdk(_apiKey);
      await _glasses.startScan();
      _openDevicePickerSheet();
    } catch (e, st) {
      debugPrint('beginConnectFlow: $e\n$st');
      downloadFailed.value = true;
      statusMessage.value = '⚠️ Scan failed: $e';
    } finally {
      isScanning.value = false;
    }
  }

  void _openDevicePickerSheet() {
    Get.bottomSheet<void>(
      SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Text(
                'Select glasses',
                style: TextStyle(color: Colors.white, fontSize: 18, fontWeight: FontWeight.w600),
              ),
              const SizedBox(height: 12),
              SizedBox(
                height: 280,
                child: Obx(() {
                  if (discoveredDevices.isEmpty) {
                    return const Center(
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          CircularProgressIndicator(strokeWidth: 2.5),
                          SizedBox(height: 12),
                          Text('Searching for devices…', style: TextStyle(color: Colors.white70)),
                        ],
                      ),
                    );
                  }
                  return ListView.separated(
                    itemCount: discoveredDevices.length,
                    separatorBuilder: (_, __) => const Divider(height: 1, color: Colors.white24),
                    itemBuilder: (context, i) {
                      final d = discoveredDevices[i];
                      final name = d['name'] ?? '';
                      final addr = d['address'] ?? '';
                      return ListTile(
                        title: Text(
                          name.isEmpty ? addr : name,
                          style: const TextStyle(color: Colors.white),
                        ),
                        subtitle: Text(addr, style: const TextStyle(color: Colors.white54, fontSize: 12)),
                        onTap: () => connectToAddress(addr),
                      );
                    },
                  );
                }),
              ),
              TextButton(onPressed: () => Get.back<void>(), child: const Text('Cancel')),
            ],
          ),
        ),
      ),
      backgroundColor: const Color(0xFF1A1A1A),
      isScrollControlled: true,
    );
  }

  Future<void> connectToAddress(String address) async {
    if (address.isEmpty) return;
    try {
      statusMessage.value = 'Connecting to Glasses...';
      await _glasses.connectDevice(address: address);
      try {
        Get.back<void>();
      } catch (_) {}
    } catch (e, st) {
      debugPrint('connectToAddress: $e\n$st');
      downloadFailed.value = true;
      statusMessage.value = '⚠️ Connect failed: $e';
    }
  }

  Future<void> startRecording() async {
    if (_recordingActionInFlight || isRecording.value) return;
    if (!isConnected.value) {
      statusMessage.value = 'Connect glasses first';
      return;
    }
    if (isDownloading.value || isPreparingVideo.value || _downloadPipelineInFlight) {
      return;
    }
    _recordingActionInFlight = true;
    try {
      await _glasses.startRecording();
    } catch (e, st) {
      debugPrint('startRecording: $e\n$st');
      statusMessage.value = '⚠️ Start recording failed: $e';
    } finally {
      _recordingActionInFlight = false;
    }
  }

  Future<void> stopRecording() async {
    if (_recordingActionInFlight || !isRecording.value) return;
    _recordingActionInFlight = true;
    try {
      await _glasses.stopRecording();
    } catch (e, st) {
      debugPrint('stopRecording: $e\n$st');
      statusMessage.value = '⚠️ Stop recording failed: $e';
    } finally {
      _recordingActionInFlight = false;
    }
  }

  Future<void> _onVideoReadyFromSdk(String fileName) async {
    if (_downloadPipelineInFlight) return;
    _downloadPipelineInFlight = true;
    _pendingFileNameForRetry = fileName;
    downloadFailed.value = false;
    lastVideoPath.value = null;
    downloadProgress.value = 0;

    try {
      statusMessage.value = 'Turning WiFi ON...';
      isPreparingVideo.value = true;
      await _glasses.turnOnWifi(wifiPassword.value);

      statusMessage.value = 'Preparing video...';
      await Future<void>.delayed(const Duration(milliseconds: 150));

      isDownloading.value = true;
      downloadProgress.value = 0;

      final path = await _glasses.downloadFile(fileName, fileSize: 0);
      if (path == null || path.isEmpty) {
        throw StateError('Download returned no path');
      }

      await _glasses.turnOffWifi();

      lastVideoPath.value = path;
      statusMessage.value = '✅ Video ready';
      downloadFailed.value = false;
    } catch (e, st) {
      debugPrint('_onVideoReadyFromSdk: $e\n$st');
      downloadFailed.value = true;
      statusMessage.value = '⚠️ WiFi or download failed: $e';
      try {
        await _glasses.turnOffWifi();
      } catch (_) {}
    } finally {
      isDownloading.value = false;
      isPreparingVideo.value = false;
      _downloadPipelineInFlight = false;
    }
  }

  Future<void> retryLastDownload() async {
    final name = _pendingFileNameForRetry;
    if (name == null || name.isEmpty) return;
    downloadFailed.value = false;
    await _onVideoReadyFromSdk(name);
  }

  void setWifiPassword(String password) {
    wifiPassword.value = password;
  }

  bool get canRetryDownload =>
      downloadFailed.value && (_pendingFileNameForRetry?.isNotEmpty ?? false);

  @override
  void onClose() {
    _connSub?.cancel();
    _recSub?.cancel();
    _progSub?.cancel();
    _videoSub?.cancel();
    _errSub?.cancel();
    _devSub?.cancel();
    super.onClose();
  }
}
