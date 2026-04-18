import 'dart:async';

import 'package:falcon_one_demo/data/call_service.dart';
import 'package:flutter/foundation.dart';
import 'package:get/get.dart';
import 'package:mapbox_maps_flutter/mapbox_maps_flutter.dart';

class MapController extends GetxController {
  // The MapboxMap instance, once created.
  MapboxMap? mapboxMap;

  CallService? _callService;
  final RxBool _fallbackSpeakerMuted = false.obs;
  final RxBool _fallbackMicrophoneMuted = false.obs;
  CircleAnnotationManager? _circleAnnotationManager;
  final Map<int, CircleAnnotation> _userAnnotations = {};
  Worker? _remoteLocationsWorker;
  Worker? _localLocationWorker;
  Worker? _satelliteCountWorker;
  bool _hasPositionedInitialCamera = false;
  bool _isRefreshingAnnotations = false;
  bool _refreshAnnotationsQueued = false;

  /// Called when the Mapbox map is created to configure initial settings.
  ///
  /// [map] The MapboxMap instance.
  /// Configures the Mapbox map once the widget has been created and prepares annotations.
  void onMapCreated(MapboxMap map) {
    mapboxMap = map;

    if (mapboxMap == null) return;

    mapboxMap!.location.updateSettings(
      LocationComponentSettings(enabled: true, pulsingEnabled: true),
    );

    mapboxMap!.logo.updateSettings(LogoSettings(enabled: false));
    mapboxMap!.attribution.updateSettings(AttributionSettings(enabled: false));

    mapboxMap!.scaleBar.updateSettings(ScaleBarSettings(enabled: false));

    final localLocation = _ensureCallService()?.localLocationRx.value;
    if (localLocation != null) {
      unawaited(_positionCameraOver(localLocation));
    }

    if (_circleAnnotationManager == null) {
      unawaited(
        mapboxMap!.annotations
            .createCircleAnnotationManager()
            .then((manager) async {
              _circleAnnotationManager = manager;
              await manager.setCircleEmissiveStrength(1.0);
              await manager.setCirclePitchAlignment(CirclePitchAlignment.MAP);
              await _refreshParticipantAnnotations();
            })
            .catchError((error, stackTrace) {
              debugPrint('Failed to create annotation manager: $error');
              debugPrint('$stackTrace');
            }),
      );
    } else {
      final manager = _circleAnnotationManager;
      if (manager != null) {
        unawaited(manager.setCircleEmissiveStrength(1.0));
        unawaited(manager.setCirclePitchAlignment(CirclePitchAlignment.MAP));
      }
      unawaited(_refreshParticipantAnnotations());
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
  RxInt numUsers = 3.obs;
  RxInt batteryLevel = 87.obs;
  RxString signalStatus = "Good".obs;

  MapController();

  /// Prepare service bindings and subscribe to shared audio/location state.
  @override
  void onInit() {
    super.onInit();
    final service = _ensureCallService();
    if (service != null) {
      _attachCallService(service);
    }
  }

  /// Tear down observers and annotation resources when the controller is disposed.
  @override
  void onClose() {
    _remoteLocationsWorker?.dispose();
    _localLocationWorker?.dispose();
    _satelliteCountWorker?.dispose();
    final manager = _circleAnnotationManager;
    if (manager != null) {
      unawaited(manager.deleteAll());
    }
    _circleAnnotationManager = null;
    _userAnnotations.clear();
    super.onClose();
  }

  /// Toggles the speaker mute flag for the current user.
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

  /// Toggles the microphone mute flag for the current user.
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

  /// Lazily resolves the shared [CallService] instance from GetX.
  CallService? _ensureCallService() {
    if (_callService != null) {
      return _callService;
    }

    if (Get.isRegistered<CallService>()) {
      try {
        _callService = Get.find<CallService>();
        _attachCallService(_callService!);
      } catch (error, stackTrace) {
        debugPrint('Failed to locate CallService: $error');
        debugPrint('$stackTrace');
      }
    }

    return _callService;
  }

  /// Subscribes to remote and local location streams so the map stays in sync.
  void _attachCallService(CallService service) {
    _remoteLocationsWorker ??= ever<Map<int, ParticipantLocation>>(
      service.remoteLocationsRx,
      (_) {
        unawaited(_refreshParticipantAnnotations());
      },
    );

    _localLocationWorker ??= ever<ParticipantLocation?>(
      service.localLocationRx,
      (location) {
        if (location != null) {
          unawaited(_positionCameraOver(location));
        }
        unawaited(_refreshParticipantAnnotations());
      },
    );

    numSatellites.value = service.satelliteCountRx.value;
    _satelliteCountWorker ??= ever<int>(service.satelliteCountRx, (count) {
      numSatellites.value = count;
    });
  }

  /// Rebuilds the set of circle annotations to reflect the latest participant locations.
  Future<void> _refreshParticipantAnnotations() async {
    if (_isRefreshingAnnotations) {
      _refreshAnnotationsQueued = true;
      return;
    }

    _isRefreshingAnnotations = true;
    try {
      do {
        _refreshAnnotationsQueued = false;

        final manager = _circleAnnotationManager;
        final service = _callService;

        if (manager == null || service == null) {
          return;
        }

        final Map<int, ParticipantLocation> desired =
            <int, ParticipantLocation>{}..addAll(service.remoteLocationsRx);
        final ParticipantLocation? localLocation =
            service.localLocationRx.value;
        if (localLocation != null) {
          desired[localLocation.uid] = localLocation;
        }

        final List<int> staleUids = _userAnnotations.keys
            .where((uid) => !desired.containsKey(uid))
            .toList(growable: false);
        for (final uid in staleUids) {
          final annotation = _userAnnotations.remove(uid);
          if (annotation != null) {
            await manager.delete(annotation);
          }
        }

        const int remoteCircleColor = 0xFFFF0000; // Red for remote users
        const double remoteCircleRadius = 6.0;

        for (final entry in desired.entries) {
          final uid = entry.key;
          final location = entry.value;
          final bool isLocal =
              localLocation != null && uid == localLocation.uid;

          if (isLocal) {
            continue;
          }

          final Point geometry = Point(
            coordinates: Position(location.longitude, location.latitude),
          );

          final existing = _userAnnotations[uid];
          if (existing == null) {
            final CircleAnnotationOptions options = CircleAnnotationOptions(
              geometry: geometry,
              circleColor: remoteCircleColor,
              circleRadius: remoteCircleRadius,
              circleStrokeColor: 0xFFFFFFFF,
              circleStrokeWidth: 1.5,
              circleOpacity: 1.0,
            );
            final CircleAnnotation created = await manager.create(options);
            _userAnnotations[uid] = created;
          } else {
            existing.geometry = geometry;
            existing.circleColor = remoteCircleColor;
            existing.circleRadius = remoteCircleRadius;
            existing.circleStrokeColor = 0xFFFFFFFF;
            existing.circleStrokeWidth = 1.5;
            existing.circleOpacity = 1.0;
            await manager.update(existing);
            _userAnnotations[uid] = existing;
          }
        }

        numUsers.value = desired.length;
      } while (_refreshAnnotationsQueued);
    } finally {
      _isRefreshingAnnotations = false;
    }
  }

  /// Positions the map camera over the supplied location once when the map loads.
  Future<void> _positionCameraOver(ParticipantLocation location) async {
    if (_hasPositionedInitialCamera) {
      return;
    }

    final map = mapboxMap;
    if (map == null) {
      return;
    }

    final cameraOptions = CameraOptions(
      center: Point(
        coordinates: Position(location.longitude, location.latitude),
      ),
      zoom: 16.0,
      pitch: 60.0,
      padding: MbxEdgeInsets(bottom: 200.0, top: 0.0, left: 0.0, right: 0.0),
    );

    try {
      await map.setCamera(cameraOptions);
      _hasPositionedInitialCamera = true;
    } catch (error, stackTrace) {
      debugPrint('Failed to set initial camera position: $error');
      debugPrint('$stackTrace');
    }
  }
}
