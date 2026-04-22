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

  static const EventChannel _bleScanEvents = EventChannel(
    'com.example.falcon_one_demo/w1_ble_scan',
  );

  static Stream<Map<dynamic, dynamic>> stateStream() {
    return _events.receiveBroadcastStream().map((event) {
      if (event is Map) return Map<dynamic, dynamic>.from(event);
      return <dynamic, dynamic>{};
    });
  }

  /// Native BLE scan→connect UI phases (`phase`, `detail`, optional `attempt` / `maxAttempts` / `userHint`).
  static Stream<Map<dynamic, dynamic>> bleScanUiStream() {
    return _bleScanEvents.receiveBroadcastStream().map((event) {
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

  /// Native GATT: LE scan (15 s) for [macAddress] or [localName], then teardown + 500 ms → connectGatt (TRANSPORT_LE).
  /// [localName] defaults to `SSJ-ZXAN9A1`; pass empty string for MAC-only scan match.
  static Future<void> connectW1Ble({
    required String macAddress,
    String localName = 'SSJ-ZXAN9A1',
  }) async {
    await _method.invokeMethod<void>('connectW1Ble', <String, dynamic>{
      'macAddress': macAddress,
      'localName': localName,
    });
  }

  /// Same scan→connect path with relaxed bonded warning (debug).
  static Future<void> forceBleSafeConnect({
    required String macAddress,
    String localName = 'SSJ-ZXAN9A1',
  }) async {
    await _method.invokeMethod<void>('forceBleSafeConnect', <String, dynamic>{
      'macAddress': macAddress,
      'localName': localName,
    });
  }
}
