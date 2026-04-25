import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:falcon_one_demo/app_ui_keys.dart';
import 'package:falcon_one_demo/data/call_service.dart';
import 'package:falcon_one_demo/models/w1_recording.dart';
import 'package:falcon_one_demo/services/upload_service.dart';
import 'package:falcon_one_demo/services/w1_service.dart';
import 'package:falcon_one_demo/w1/w1_classic_bluetooth.dart';
import 'package:falcon_one_demo/widgets/w1_recording_import_sheet.dart';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:geolocator/geolocator.dart' as geo;
import 'package:get/get.dart';
import 'package:mapbox_maps_flutter/mapbox_maps_flutter.dart';
import 'package:permission_handler/permission_handler.dart';

/// Inline upload card state (no duplicate snackbars for upload result).
enum IncidentUploadUiPhase {
  idle,
  uploading,
  success,
  error,
}

class MapController extends GetxController {
  MapboxMap? mapboxMap;

  CallService? _callService;
  final RxBool _fallbackSpeakerMuted = false.obs;
  final RxBool _fallbackMicrophoneMuted = false.obs;
  Worker? _remoteLocationsWorker;
  Worker? _localLocationWorker;
  Worker? _satelliteCountWorker;
  bool _incidentGpsCameraApplied = false;

  /// True when [CallService] is registered (satellite / participant counts are live).
  final RxBool hasCallTelemetry = false.obs;

  StreamSubscription<geo.Position>? _gpsPositionSubscription;

  static const String officerCode = 'off-001';

  final RxBool isSpeakerOn = true.obs;
  final RxBool isMicOn = true.obs;

  final RxDouble incidentLatitude = 0.0.obs;
  final RxDouble incidentLongitude = 0.0.obs;
  final RxBool incidentGpsReady = false.obs;

  /// Selected video file (null until user picks one).
  final Rxn<File> selectedVideo = Rxn<File>();

  final RxBool isUploading = false.obs;
  final RxBool isPickingVideo = false.obs;

  final Rx<IncidentUploadUiPhase> uploadUiPhase = IncidentUploadUiPhase.idle.obs;
  final Rxn<String> lastUploadedIncidentId = Rxn<String>();
  final RxString lastUploadedApiStatus = ''.obs;
  final RxString uploadErrorDetail = ''.obs;

  /// When the current [selectedVideo] was chosen (shown in upload panel).
  final Rxn<DateTime> videoSelectedAt = Rxn<DateTime>();

  W1Service get w1Service => Get.find<W1Service>();

  /// W1 file server base URL; set with [setW1BaseUrl] / [W1Service.setBaseUrl].
  String? get w1BaseUrl => w1Service.baseUrl.value;

  final RxList<W1Recording> recordings = <W1Recording>[].obs;
  final RxBool isFetchingRecordings = false.obs;
  final RxBool isDownloading = false.obs;
  final Rxn<W1Recording> w1ActiveRecording = Rxn<W1Recording>();
  final RxString w1ImportError = ''.obs;

  /// W1 `/status` LIVE recording flag (HTTP polling).
  final RxBool isW1Recording = false.obs;
  final RxBool isCheckingStatus = false.obs;
  final RxString w1StatusMessage = 'Connecting to W1...'.obs;

  bool _w1LastRecordingPoll = false;
  bool _w1EverSawRecordingTrue = false;
  bool _w1RecordingStopSnackShown = false;
  Timer? _w1StatusPollTimer;
  VoidCallback? _w1ClassicStatusListener;
  VoidCallback? _w1ClassicConnectionListener;
  VoidCallback? _w1ClassicChannelListener;
  int _w1StatusWaitTicks = 0;
  bool _w1StatusTimeoutNotified = false;
  String _lastW1UserError = '';

  /// Compatibility entrypoint; production path auto-discovers URL from Bluetooth STATUS.
  void setW1BaseUrl(String ip, int port) => w1Service.setBaseUrl(ip, port);

  /// Nullable GPS for API / UI (null until fix acquired).
  double? get lat => incidentGpsReady.value ? incidentLatitude.value : null;

  double? get lng => incidentGpsReady.value ? incidentLongitude.value : null;

  void toggleSpeaker() {
    isSpeakerOn.toggle();
    update();
  }

  void toggleMic() {
    isMicOn.toggle();
    update();
  }

  void _resetUploadUiState() {
    uploadUiPhase.value = IncidentUploadUiPhase.idle;
    lastUploadedIncidentId.value = null;
    lastUploadedApiStatus.value = '';
    uploadErrorDetail.value = '';
  }

  /// Polls `GET /status` (via [W1Service.getStatusRaw]); updates [isW1Recording] and [w1StatusMessage].
  Future<void> fetchW1Status() async {
    if (w1BaseUrl == null || w1BaseUrl!.isEmpty) {
      return;
    }

    isCheckingStatus.value = true;
    try {
      final raw = await w1Service.getStatusRaw();
      final decoded = jsonDecode(raw);
      if (decoded is! Map) {
        w1StatusMessage.value = '⚠️ Unable to reach device';
        return;
      }
      final map = Map<String, dynamic>.from(decoded);
      final recording = _parseBoolField(map['recording']);

      String? batteryLabel;
      final batt = map['battery'];
      if (batt != null) {
        batteryLabel = batt.toString();
      }

      final prev = _w1LastRecordingPoll;

      if (recording) {
        if (!prev) {
          _w1RecordingStopSnackShown = false;
        }
        _w1EverSawRecordingTrue = true;
        isW1Recording.value = true;
        w1StatusMessage.value = batteryLabel != null && batteryLabel.isNotEmpty
            ? '🔴 Recording in progress — please wait · $batteryLabel'
            : '🔴 Recording in progress — please wait';
      } else {
        isW1Recording.value = false;
        if (prev) {
          w1StatusMessage.value = '✅ Recording complete — tap ⏱ to load video';
          if (!_w1RecordingStopSnackShown) {
            _w1RecordingStopSnackShown = true;
            _safeSnackBar(
              'W1',
              'Recording finished — ready to load',
              backgroundColor: const Color(0xFF2E7D32),
              colorText: Colors.white,
            );
          }
        } else if (!_w1EverSawRecordingTrue) {
          w1StatusMessage.value = 'W1 Connected';
        } else {
          w1StatusMessage.value = '✅ Recording complete — tap ⏱ to load video';
        }
      }

      _w1LastRecordingPoll = recording;
    } catch (e, st) {
      debugPrint('fetchW1Status: $e\n$st');
      w1StatusMessage.value = '⚠️ Unable to reach device';
    } finally {
      isCheckingStatus.value = false;
    }
  }

  static bool _parseBoolField(Object? value) {
    if (value is bool) return value;
    if (value is String) {
      final s = value.trim().toLowerCase();
      return s == 'true' || s == '1' || s == 'yes' || s == 'on';
    }
    if (value is num) return value != 0;
    return false;
  }

  void _onClassicStatusUpdated() {
    final status = W1ClassicBluetooth.instance.latestStatus.value;
    if (status == null) {
      if (W1ClassicBluetooth.instance.isW1Bonded.value) {
        w1StatusMessage.value = 'W1 connected but device did not return file server info';
      }
      return;
    }
    _w1StatusWaitTicks = 0;
    _w1StatusTimeoutNotified = false;
    final ip = status['file_server_ip']?.toString().trim() ?? '';
    final rawPort = status['file_server_port'];
    final int? port = switch (rawPort) {
      int v => v,
      String v => int.tryParse(v.trim()),
      _ => null,
    };
    if (ip.isEmpty || port == null) {
      const msg = 'Unable to get W1 file server details';
      w1StatusMessage.value = msg;
      _notifyW1Error(msg);
      return;
    }
    w1Service.setBaseUrl(ip, port);
    debugPrint('[W1] Base URL configured http://$ip:$port');
    if (!isW1Recording.value) {
      w1StatusMessage.value = 'W1 Connected';
    }
    unawaited(fetchW1Status());
  }

  void _onClassicConnectionUpdated() {
    final w1 = W1ClassicBluetooth.instance;
    if (w1.isW1Bonded.value && !w1.isConnectionOpen.value && (w1BaseUrl == null || w1BaseUrl!.isEmpty)) {
      w1StatusMessage.value = 'W1 paired';
    }
  }

  void _onClassicChannelStatusUpdated() {
    final s = W1ClassicBluetooth.instance.status.value?.trim() ?? '';
    if (s.isEmpty) return;
    if (s.startsWith('Connect failed') ||
        s.startsWith('Input stream closed') ||
        s.startsWith('Classic Bluetooth is only supported')) {
      const msg = 'Unable to communicate with W1';
      w1StatusMessage.value = msg;
      _notifyW1Error(msg);
    }
  }

  void _notifyW1Error(String message) {
    if (_lastW1UserError == message) return;
    _lastW1UserError = message;
    _safeSnackBar('W1', message, backgroundColor: Colors.red.shade900, colorText: Colors.white);
  }

  Future<void> _ensureClassicStatusChannel() async {
    final hasPerm = await _ensureW1BluetoothPermissions();
    if (!hasPerm) {
      const msg = 'Unable to communicate with W1';
      w1StatusMessage.value = msg;
      _notifyW1Error(msg);
      return;
    }
    try {
      await W1ClassicBluetooth.instance.ensureStatusChannel();
    } catch (e, st) {
      debugPrint('_ensureClassicStatusChannel: $e\n$st');
      const msg = 'Unable to communicate with W1';
      w1StatusMessage.value = msg;
      _notifyW1Error(msg);
      return;
    }

    final w1 = W1ClassicBluetooth.instance;
    if (w1.isConnectionOpen.value && w1.latestStatus.value == null) {
      _w1StatusWaitTicks += 1;
      if (_w1StatusWaitTicks >= 2 && !_w1StatusTimeoutNotified) {
        _w1StatusTimeoutNotified = true;
        const msg = 'W1 connected but device did not return file server info';
        w1StatusMessage.value = msg;
        _notifyW1Error(msg);
      }
    } else {
      _w1StatusWaitTicks = 0;
      _w1StatusTimeoutNotified = false;
    }
  }

  Future<bool> _ensureW1BluetoothPermissions() async {
    if (!Platform.isAndroid) return false;
    final scan = await Permission.bluetoothScan.request();
    final connect = await Permission.bluetoothConnect.request();
    return scan.isGranted && connect.isGranted;
  }

  /// W1: clock opens sheet, then [fetchLatestRecording] runs `GET /recordings/latest` → download → [selectedVideo].
  void onTimerClick() {
    if (isW1Recording.value) return;
    w1ImportError.value = '';
    w1ActiveRecording.value = null;
    Get.bottomSheet<void>(
      const W1RecordingImportSheet(),
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      enableDrag: true,
    );
    unawaited(fetchLatestRecording());
  }

  /// `GET /recordings` — optional; not used by the clock flow.
  Future<void> fetchRecordings() async {
    w1ImportError.value = '';
    if (w1BaseUrl == null || w1BaseUrl!.isEmpty) {
      w1ImportError.value = 'Connecting to W1...';
      return;
    }
    isFetchingRecordings.value = true;
    try {
      final list = await w1Service.getRecordings();
      recordings.assignAll(list);
    } finally {
      isFetchingRecordings.value = false;
    }
  }

  /// Clock flow: `GET /recordings/latest` → download to app documents → [selectedVideo] + sheet UI.
  Future<void> fetchLatestRecording() async {
    w1ImportError.value = '';
    w1ActiveRecording.value = null;
    selectedVideo.value = null;
    videoSelectedAt.value = null;

    if (w1BaseUrl == null || w1BaseUrl!.isEmpty) {
      const msg = 'Connecting to W1...';
      w1ImportError.value = msg;
      return;
    }

    isFetchingRecordings.value = true;
    W1Recording? latest;
    try {
      latest = await w1Service.getLatestRecording();
    } catch (e, st) {
      debugPrint('fetchLatestRecording GET: $e\n$st');
      const msg = 'Unable to fetch latest recording';
      w1ImportError.value = msg;
      _safeSnackBar('W1', msg, backgroundColor: Colors.red.shade900, colorText: Colors.white);
      return;
    } finally {
      isFetchingRecordings.value = false;
    }

    if (latest == null) {
      const msg = 'No latest recording (empty device or HTTP 404).';
      w1ImportError.value = msg;
      _safeSnackBar('W1', msg, backgroundColor: Colors.orange.shade900, colorText: Colors.white);
      return;
    }

    w1ActiveRecording.value = latest;
    isDownloading.value = true;
    try {
      final file = await w1Service.downloadRecording(latest);
      selectedVideo.value = file;
      videoSelectedAt.value = DateTime.now();
      _resetUploadUiState();
      await uploadVideo();
    } catch (e, st) {
      debugPrint('fetchLatestRecording download: $e\n$st');
      const msg = 'Recording download failed';
      w1ImportError.value = msg;
      w1ActiveRecording.value = null;
      _safeSnackBar('W1', msg, backgroundColor: Colors.red.shade900, colorText: Colors.white);
    } finally {
      isDownloading.value = false;
    }
  }

  /// Same as latest + download; kept for callers that want only a [File].
  Future<File?> downloadLatestRecording() async {
    await fetchLatestRecording();
    return selectedVideo.value;
  }

  /// Upload incident using [selectedVideo] (already set by [fetchLatestRecording]).
  Future<void> uploadW1DownloadedRecording() async {
    final file = selectedVideo.value;
    if (file == null || !await file.exists()) {
      _safeSnackBar(
        'Upload',
        'No recording file. Use the clock icon to download from W1 first.',
        backgroundColor: Colors.red.shade800,
        colorText: Colors.white,
      );
      return;
    }
    videoSelectedAt.value = DateTime.now();
    _resetUploadUiState();
    await uploadVideo();
  }

  void onMapCreated(MapboxMap map) {
    mapboxMap = map;

    if (mapboxMap == null) return;

    // Single on-map position indicator: Mapbox follows device GPS (no extra circle markers).
    mapboxMap!.location.updateSettings(
      LocationComponentSettings(enabled: true, pulsingEnabled: true),
    );

    mapboxMap!.logo.updateSettings(LogoSettings(enabled: false));
    mapboxMap!.attribution.updateSettings(AttributionSettings(enabled: false));

    mapboxMap!.scaleBar.updateSettings(ScaleBarSettings(enabled: false));

    unawaited(_onMapReadyIncidentLayer());
  }

  Future<void> _onMapReadyIncidentLayer() async {
    await _applyIncidentGpsToMap();
  }

  /// Uses root [ScaffoldMessenger] only (no Get.snackbar).
  void _safeSnackBar(
    String title,
    String message, {
    Color? backgroundColor,
    Color? colorText,
    Duration duration = const Duration(seconds: 4),
  }) {
    final fg = colorText ?? Colors.white;
    final bg = backgroundColor ?? const Color(0xFF323232);

    void showWithMessenger(ScaffoldMessengerState messenger) {
      messenger.hideCurrentSnackBar();
      messenger.showSnackBar(
        SnackBar(
          behavior: SnackBarBehavior.floating,
          margin: const EdgeInsets.fromLTRB(12, 0, 12, 88),
          duration: duration,
          backgroundColor: bg,
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                title,
                style: TextStyle(fontWeight: FontWeight.w700, color: fg, fontSize: 15),
              ),
              const SizedBox(height: 4),
              Text(
                message,
                style: TextStyle(color: fg.withValues(alpha: 0.92), fontSize: 13),
              ),
            ],
          ),
        ),
      );
    }

    void attempt(int frame) {
      final messenger = AppUiKeys.scaffoldMessenger.currentState;
      if (messenger != null) {
        showWithMessenger(messenger);
        return;
      }
      if (frame >= 16) {
        debugPrint('[toast] $title — $message');
        return;
      }
      WidgetsBinding.instance.addPostFrameCallback((_) => attempt(frame + 1));
    }

    WidgetsBinding.instance.addPostFrameCallback((_) => attempt(0));
  }

  Future<void> pickVideo() async {
    if (isUploading.value || isPickingVideo.value) return;
    debugPrint('VIDEO PICK START');
    isPickingVideo.value = true;
    update();
    try {
      final result = await FilePicker.platform.pickFiles(
        type: FileType.video,
        allowMultiple: false,
        withReadStream: false,
        withData: true,
      );
      if (result == null || result.files.isEmpty) {
        debugPrint('VIDEO PICK CANCELLED (no selection)');
        return;
      }
      final f = result.files.single;
      File? file;

      final path = f.path;
      if (path != null && path.isNotEmpty) {
        file = File(path);
        if (!await file.exists()) {
          file = null;
        }
      }

      if (file == null && f.bytes != null && f.bytes!.isNotEmpty) {
        final name = f.name.isNotEmpty ? f.name : 'video_${DateTime.now().millisecondsSinceEpoch}.mp4';
        final safe = name.replaceAll(RegExp(r'[^a-zA-Z0-9._-]'), '_');
        final out = File('${Directory.systemTemp.path}${Platform.pathSeparator}incident_$safe');
        await out.writeAsBytes(f.bytes!, flush: true);
        file = out;
      }

      if (file == null || !await file.exists()) {
        _safeSnackBar('Video', 'Could not access selected file', backgroundColor: Colors.red.shade800, colorText: Colors.white);
        return;
      }

      selectedVideo.value = file;
      videoSelectedAt.value = DateTime.now();
      _resetUploadUiState();
      debugPrint('VIDEO PICK SUCCESS → ${file.path}');
      update();
      WidgetsBinding.instance.addPostFrameCallback((_) {
        _safeSnackBar('Video', 'Video selected');
      });
    } catch (e, st) {
      debugPrint('pickVideo ERROR: $e\n$st');
      _safeSnackBar('Video', 'Picker failed: $e', backgroundColor: Colors.red.shade800, colorText: Colors.white);
    } finally {
      isPickingVideo.value = false;
      update();
      debugPrint('VIDEO PICK END (finally)');
    }
  }

  Future<void> uploadVideo() async {
    if (isUploading.value) return;
    if (uploadUiPhase.value == IncidentUploadUiPhase.success) return;

    final file = selectedVideo.value;
    if (file == null || !await file.exists()) {
      _safeSnackBar(
        'Upload',
        'No video selected. Tap camera to choose a file.',
        backgroundColor: Colors.red.shade800,
        colorText: Colors.white,
      );
      return;
    }

    debugPrint('UPLOAD START → ${file.path}');
    uploadUiPhase.value = IncidentUploadUiPhase.uploading;
    uploadErrorDetail.value = '';
    isUploading.value = true;
    update();
    try {
      final upload = Get.find<UploadService>();
      final metadata = buildIncidentMetadata();
      final res = await upload.uploadVideo(file, metadata);
      if (res.isSuccess) {
        final id = res.id?.trim();
        if (id == null || id.isEmpty) {
          uploadErrorDetail.value = 'Server returned success without incident id';
          uploadUiPhase.value = IncidentUploadUiPhase.error;
        } else {
          lastUploadedIncidentId.value = id;
          final st = res.status?.trim();
          lastUploadedApiStatus.value = (st != null && st.isNotEmpty) ? st : 'PENDING';
          uploadUiPhase.value = IncidentUploadUiPhase.success;
          debugPrint('UPLOAD SUCCESS id=$id status=${lastUploadedApiStatus.value}');
        }
      } else {
        uploadErrorDetail.value = res.errorMessage ?? 'Video upload failed';
        uploadUiPhase.value = IncidentUploadUiPhase.error;
        _safeSnackBar('Upload', 'Video upload failed', backgroundColor: Colors.red.shade800, colorText: Colors.white);
        debugPrint('UPLOAD FAILED http=${res.httpStatus} ${res.errorMessage}');
      }
    } catch (e, st) {
      debugPrint('UPLOAD ERROR: $e\n$st');
      uploadErrorDetail.value = e.toString();
      uploadUiPhase.value = IncidentUploadUiPhase.error;
      _safeSnackBar('Upload', 'Video upload failed', backgroundColor: Colors.red.shade800, colorText: Colors.white);
    } finally {
      isUploading.value = false;
      update();
      debugPrint('UPLOAD END (finally)');
    }
  }

  Map<String, dynamic> buildIncidentMetadata() {
    final lat = incidentLatitude.value;
    final lon = incidentLongitude.value;
    final loc = incidentGpsReady.value ? '$lat,$lon' : 'N/A';
    return <String, dynamic>{
      'device': 'W1',
      'timestamp': DateTime.now().toIso8601String(),
      'location': loc,
    };
  }

  Future<void> _loadIncidentGps() async {
    try {
      debugPrint('GPS FETCH START');
      var perm = await geo.Geolocator.checkPermission();
      if (perm == geo.LocationPermission.denied) {
        perm = await geo.Geolocator.requestPermission();
      }
      if (perm == geo.LocationPermission.denied || perm == geo.LocationPermission.deniedForever) {
        debugPrint('GPS permission denied');
        return;
      }
      if (!await geo.Geolocator.isLocationServiceEnabled()) {
        debugPrint('GPS services disabled');
        return;
      }
      final pos = await geo.Geolocator.getCurrentPosition();
      incidentLatitude.value = pos.latitude;
      incidentLongitude.value = pos.longitude;
      incidentGpsReady.value = true;
      update();
      debugPrint('GPS FETCH SUCCESS ${pos.latitude},${pos.longitude}');
      await _applyIncidentGpsToMap();
      _startGpsPositionStream();
    } catch (e, st) {
      debugPrint('GPS FETCH ERROR: $e\n$st');
    }
  }

  void _startGpsPositionStream() {
    _gpsPositionSubscription?.cancel();
    _gpsPositionSubscription = geo.Geolocator.getPositionStream(
      locationSettings: const geo.LocationSettings(
        accuracy: geo.LocationAccuracy.high,
        distanceFilter: 10,
      ),
    ).listen(
      (geo.Position pos) {
        incidentLatitude.value = pos.latitude;
        incidentLongitude.value = pos.longitude;
        incidentGpsReady.value = true;
      },
      onError: (Object e) => debugPrint('GPS stream: $e'),
    );
  }

  Future<void> _applyIncidentGpsToMap() async {
    if (_incidentGpsCameraApplied || !incidentGpsReady.value) return;
    final map = mapboxMap;
    if (map == null) return;

    final cameraOptions = CameraOptions(
      center: Point(
        coordinates: Position(incidentLongitude.value, incidentLatitude.value),
      ),
      zoom: 16.0,
      pitch: 60.0,
      padding: MbxEdgeInsets(bottom: 200.0, top: 0.0, left: 0.0, right: 0.0),
    );
    try {
      await map.setCamera(cameraOptions);
      _incidentGpsCameraApplied = true;
    } catch (error, stackTrace) {
      debugPrint('GPS camera: $error');
      debugPrint('$stackTrace');
    }
  }

  RxBool get isSpeakerMuted {
    final service = _ensureCallService();
    if (service != null) {
      return service.speakerMutedRx;
    }
    return _fallbackSpeakerMuted;
  }

  RxBool get isMicrophoneMuted {
    final service = _ensureCallService();
    if (service != null) {
      return service.microphoneMutedRx;
    }
    return _fallbackMicrophoneMuted;
  }

  RxInt numSatellites = 0.obs;
  RxInt numUsers = 0.obs;
  /// Shown in status row — no device battery API wired yet.
  final RxString batteryDisplay = 'N/A'.obs;
  /// Shown in status row — no network signal API wired yet.
  final RxString signalDisplay = 'N/A'.obs;

  MapController();

  @override
  void onInit() {
    super.onInit();
    final service = _ensureCallService();
    if (service != null) {
      hasCallTelemetry.value = true;
      _attachCallService(service);
    }
    unawaited(_loadIncidentGps());

    _w1StatusPollTimer?.cancel();
    _w1StatusPollTimer = Timer.periodic(const Duration(seconds: 3), (_) {
      unawaited(_ensureClassicStatusChannel());
      unawaited(fetchW1Status());
    });
    _w1ClassicStatusListener = _onClassicStatusUpdated;
    _w1ClassicConnectionListener = _onClassicConnectionUpdated;
    _w1ClassicChannelListener = _onClassicChannelStatusUpdated;
    W1ClassicBluetooth.instance.latestStatus.addListener(_w1ClassicStatusListener!);
    W1ClassicBluetooth.instance.isW1Bonded.addListener(_w1ClassicConnectionListener!);
    W1ClassicBluetooth.instance.isConnectionOpen.addListener(_w1ClassicConnectionListener!);
    W1ClassicBluetooth.instance.status.addListener(_w1ClassicChannelListener!);
    unawaited(_ensureClassicStatusChannel());
    _onClassicStatusUpdated();
    _onClassicConnectionUpdated();
    _onClassicChannelStatusUpdated();
    unawaited(fetchW1Status());
  }

  @override
  void onClose() {
    _w1StatusPollTimer?.cancel();
    _w1StatusPollTimer = null;
    if (_w1ClassicStatusListener != null) {
      W1ClassicBluetooth.instance.latestStatus.removeListener(_w1ClassicStatusListener!);
      _w1ClassicStatusListener = null;
    }
    if (_w1ClassicConnectionListener != null) {
      W1ClassicBluetooth.instance.isW1Bonded.removeListener(_w1ClassicConnectionListener!);
      W1ClassicBluetooth.instance.isConnectionOpen.removeListener(_w1ClassicConnectionListener!);
      _w1ClassicConnectionListener = null;
    }
    if (_w1ClassicChannelListener != null) {
      W1ClassicBluetooth.instance.status.removeListener(_w1ClassicChannelListener!);
      _w1ClassicChannelListener = null;
    }
    _remoteLocationsWorker?.dispose();
    _localLocationWorker?.dispose();
    _satelliteCountWorker?.dispose();
    _gpsPositionSubscription?.cancel();
    _gpsPositionSubscription = null;
    super.onClose();
  }

  Future<void> toggleSpeakerMute() async {
    final service = _ensureCallService();
    if (service == null) {
      debugPrint('CallService not available; speaker toggle ignored');
      return;
    }

    final targetMuted = !service.isSpeakerMuted;

    try {
      await service.setSpeakerMuted(muted: targetMuted);
    } catch (error, stackTrace) {
      debugPrint('Speaker toggle failed: $error');
      debugPrint('$stackTrace');
    }
  }

  Future<void> toggleMicrophoneMute() async {
    final service = _ensureCallService();
    if (service == null) {
      debugPrint('CallService not available; microphone toggle ignored');
      return;
    }

    final targetMuted = !service.isMicrophoneMuted;

    try {
      await service.setMicrophoneMuted(muted: targetMuted);
    } catch (error, stackTrace) {
      debugPrint('Microphone toggle failed: $error');
      debugPrint('$stackTrace');
    }
  }

  CallService? _ensureCallService() {
    if (_callService != null) {
      return _callService;
    }

    if (Get.isRegistered<CallService>()) {
      try {
        _callService = Get.find<CallService>();
        hasCallTelemetry.value = true;
        _attachCallService(_callService!);
      } catch (error, stackTrace) {
        debugPrint('Failed to locate CallService: $error');
        debugPrint('$stackTrace');
      }
    }

    return _callService;
  }

  void _attachCallService(CallService service) {
    _remoteLocationsWorker ??= ever<Map<int, ParticipantLocation>>(
      service.remoteLocationsRx,
      (_) => _updateParticipantCount(),
    );

    _localLocationWorker ??= ever<ParticipantLocation?>(
      service.localLocationRx,
      (_) => _updateParticipantCount(),
    );

    numSatellites.value = service.satelliteCountRx.value;
    _satelliteCountWorker ??= ever<int>(service.satelliteCountRx, (count) {
      numSatellites.value = count;
    });
    _updateParticipantCount();
  }

  void _updateParticipantCount() {
    final service = _callService;
    if (service == null) {
      numUsers.value = 0;
      return;
    }
    final Map<int, ParticipantLocation> desired =
        <int, ParticipantLocation>{}..addAll(service.remoteLocationsRx);
    final ParticipantLocation? local = service.localLocationRx.value;
    if (local != null) {
      desired[local.uid] = local;
    }
    numUsers.value = desired.length;
  }
}
