import 'dart:async';

import 'package:falcon_one_demo/platform/bleequp_bridge.dart';
import 'package:flutter/foundation.dart';
import 'package:get/get.dart';

/// Glasses connection lifecycle (SDK / Bluetooth), not HTTP.
enum GlassesConnectionState {
  disconnected,
  connecting,
  connected,
}

/// BleeqUp glasses: MethodChannel commands + EventChannel fan-out streams.
///
/// Does not use [W1Service] or any W1 HTTP code.
class GlassesService extends GetxService {
  final StreamController<GlassesConnectionState> _connectionState =
      StreamController<GlassesConnectionState>.broadcast();
  final StreamController<bool> _recordingState = StreamController<bool>.broadcast();
  final StreamController<double> _downloadProgress = StreamController<double>.broadcast();
  final StreamController<String> _videoReady = StreamController<String>.broadcast();
  final StreamController<String> _nativeErrors = StreamController<String>.broadcast();
  final StreamController<Map<String, String>> _deviceFound =
      StreamController<Map<String, String>>.broadcast();

  Stream<GlassesConnectionState> get connectionState => _connectionState.stream;
  Stream<bool> get recordingState => _recordingState.stream;
  Stream<double> get downloadProgress => _downloadProgress.stream;

  /// Emits [fileName] when native reports `onVideoReady` (before Wi‑Fi / download).
  Stream<String> get videoReady => _videoReady.stream;

  /// Native `onError` or malformed payloads.
  Stream<String> get nativeErrors => _nativeErrors.stream;

  /// BLE scan hits (`name`, `address`).
  Stream<Map<String, String>> get deviceFound => _deviceFound.stream;

  StreamSubscription<dynamic>? _eventSub;

  @override
  void onInit() {
    super.onInit();
    if (!BleeqUpBridge.isSupported) {
      debugPrint('[GlassesService] BleeqUp bridge not supported on this platform.');
      return;
    }
    _eventSub = BleeqUpBridge.events().listen(
      _onNativeEvent,
      onError: (Object e, StackTrace st) => debugPrint('[GlassesService] event stream: $e\n$st'),
    );
  }

  @override
  void onClose() {
    _eventSub?.cancel();
    _eventSub = null;
    if (!_connectionState.isClosed) _connectionState.close();
    if (!_recordingState.isClosed) _recordingState.close();
    if (!_downloadProgress.isClosed) _downloadProgress.close();
    if (!_videoReady.isClosed) _videoReady.close();
    if (!_nativeErrors.isClosed) _nativeErrors.close();
    if (!_deviceFound.isClosed) _deviceFound.close();
    super.onClose();
  }

  void _onNativeEvent(dynamic raw) {
    if (raw is! Map) return;
    final map = Map<Object?, Object?>.from(raw);
    final event = map['event']?.toString();
    if (event == null) return;

    switch (event) {
      case 'onConnected':
        _connectionState.add(GlassesConnectionState.connected);
        break;
      case 'onDisconnected':
        _connectionState.add(GlassesConnectionState.disconnected);
        break;
      case 'onRecordingStarted':
        _recordingState.add(true);
        break;
      case 'onRecordingStopped':
        _recordingState.add(false);
        break;
      case 'onVideoReady':
        final name = map['fileName']?.toString() ?? map['file']?.toString() ?? '';
        if (name.isNotEmpty) {
          _videoReady.add(name);
        }
        break;
      case 'onDownloadProgress':
        final p = map['progress'];
        if (p is num) {
          _downloadProgress.add(p.clamp(0.0, 1.0).toDouble());
        }
        break;
      case 'onDownloadComplete':
        final p = map['path']?.toString() ?? map['filePath']?.toString();
        if (p != null && p.isNotEmpty) {
          _downloadProgress.add(1.0);
        }
        break;
      case 'onError':
        final msg = map['message']?.toString() ?? 'Unknown glasses error';
        _nativeErrors.add(msg);
        break;
      case 'onDeviceFound':
        final name = map['name']?.toString() ?? '';
        final address = map['address']?.toString() ?? '';
        if (address.isNotEmpty) {
          _deviceFound.add(<String, String>{'name': name, 'address': address});
        }
        break;
      case 'onWifiOn':
        // Optional hook for UI; native Wi‑Fi is ready for HTTP download leg.
        break;
      default:
        debugPrint('[GlassesService] unhandled event: $event');
    }
  }

  Future<void> initSdk(String apiKey) async {
    await BleeqUpBridge.invoke<void>('initSdk', <String, dynamic>{'apiKey': apiKey});
  }

  Future<void> startScan() async {
    await BleeqUpBridge.invoke<void>('startScan');
  }

  /// Optional [address] for BLE MAC when native/SDK requires it.
  Future<void> connectDevice({String? address}) async {
    await BleeqUpBridge.invoke<void>(
      'connectDevice',
      <String, dynamic>{if (address != null && address.isNotEmpty) 'address': address},
    );
  }

  Future<void> startRecording() async {
    await BleeqUpBridge.invoke<void>('startRecording');
  }

  Future<void> stopRecording() async {
    await BleeqUpBridge.invoke<void>('stopRecording');
  }

  Future<void> turnOnWifi(String password) async {
    await BleeqUpBridge.invoke<void>(
      'turnOnWifi',
      <String, dynamic>{'password': password},
    );
  }

  /// Returns local file path when native completes transfer.
  Future<String?> downloadFile(String fileName, {int fileSize = 0}) async {
    final res = await BleeqUpBridge.invoke<Map<dynamic, dynamic>>(
      'downloadFile',
      <String, dynamic>{'fileName': fileName, 'fileSize': fileSize},
    );
    if (res == null) return null;
    final path = res['path']?.toString();
    return path != null && path.isNotEmpty ? path : null;
  }

  Future<void> turnOffWifi() async {
    await BleeqUpBridge.invoke<void>('turnOffWifi');
  }
}
