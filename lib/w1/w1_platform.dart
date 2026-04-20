import 'dart:async';

import 'package:flutter/services.dart';

/// Native W1 transfer pipeline (Kotlin engine).
class W1Platform {
  W1Platform._();

  static const MethodChannel _method = MethodChannel(
    'com.example.falcon_one_demo/w1',
  );

  static const EventChannel _events = EventChannel(
    'com.example.falcon_one_demo/w1_state',
  );

  static Stream<Map<dynamic, dynamic>> stateStream() {
    return _events.receiveBroadcastStream().map((event) {
      if (event is Map) return Map<dynamic, dynamic>.from(event);
      return <dynamic, dynamic>{};
    });
  }

  static Future<void> configure({required String wifiBaseUrl}) async {
    await _method.invokeMethod<void>('configure', <String, dynamic>{
      'wifiBaseUrl': wifiBaseUrl,
    });
  }

  static Future<void> useBuiltInMockWifi({required bool enabled, String? wifiBaseUrl}) async {
    await _method.invokeMethod<void>('useBuiltInMockWifi', <String, dynamic>{
      'enabled': enabled,
      if (wifiBaseUrl != null) 'wifiBaseUrl': wifiBaseUrl,
    });
  }

  static Future<void> simulateRecordingComplete({
    required String recordingId,
    bool force = false,
  }) async {
    await _method.invokeMethod<void>('simulateRecordingComplete', <String, dynamic>{
      'recordingId': recordingId,
      'force': force,
    });
  }

  static Future<void> resumePending() async {
    await _method.invokeMethod<void>('resumePending');
  }

  static Future<void> resetDisplay() async {
    await _method.invokeMethod<void>('resetDisplay');
  }

  static Future<void> startRealBle() async {
    await _method.invokeMethod<void>('startRealBle');
  }

  static Future<void> stopRealBle() async {
    await _method.invokeMethod<void>('stopRealBle');
  }

  /// GATT connect by MAC and log all services/characteristics (Android).
  /// [macAddress] defaults on the native side if omitted.
  static Future<void> connectW1({String? macAddress}) async {
    await _method.invokeMethod<void>(
      'connectW1',
      <String, dynamic>{
        if (macAddress != null) 'macAddress': macAddress,
      },
    );
  }

  /// Android: from scan buffer, try GATT to top 3 unnamed devices with RSSI > -70 (strongest first).
  static Future<void> runAnonymousBleProbe() async {
    await _method.invokeMethod<void>('runAnonymousBleProbe');
  }

  static Future<List<String>> getRecentLogs({int limit = 200}) async {
    final Object? raw = await _method.invokeMethod<Object?>('getRecentLogs', <String, dynamic>{
      'limit': limit,
    });
    if (raw is List) {
      return raw.map((e) => e.toString()).toList(growable: false);
    }
    return const <String>[];
  }

  static Future<String?> exportLogs() async {
    return _method.invokeMethod<String>('exportLogs');
  }
}
