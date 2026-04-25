import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter_bluetooth_serial/flutter_bluetooth_serial.dart';

/// Classic Bluetooth (RFCOMM) for W1 — bonded device only, no BLE/GATT.
class W1ClassicBluetooth {
  W1ClassicBluetooth._();
  static final W1ClassicBluetooth instance = W1ClassicBluetooth._();

  static const String w1DeviceName = 'DSJ-ZXAN9A1';

  BluetoothConnection? _connection;
  StreamSubscription<Uint8List>? _inputSub;
  final StringBuffer _rxBuffer = StringBuffer();

  /// Last status line for UI.
  final ValueNotifier<String?> status = ValueNotifier<String?>(null);
  final ValueNotifier<Map<String, dynamic>?> latestStatus = ValueNotifier<Map<String, dynamic>?>(null);
  final ValueNotifier<bool> isW1Bonded = ValueNotifier<bool>(false);
  final ValueNotifier<bool> isConnectionOpen = ValueNotifier<bool>(false);

  /// In-memory lines for the debug screen (also printed to debugPrint).
  final List<String> logLines = <String>[];

  /// Optional hook (e.g. [State.setState]) to refresh UI when [logLines] change.
  VoidCallback? onLogLinesChanged;

  void _log(String event, [Map<String, Object?> fields = const {}]) {
    final parts = fields.entries.map((e) => '${e.key}=${e.value}').join(' ');
    final line = parts.isEmpty ? event : '$event $parts';
    logLines.add(line);
    if (logLines.length > 500) {
      logLines.removeRange(0, logLines.length - 500);
    }
    debugPrint('[W1] $line');
    onLogLinesChanged?.call();
  }

  String _hexPreview(Uint8List data, {int max = 48}) {
    if (data.isEmpty) return '';
    final n = data.length > max ? max : data.length;
    return data.sublist(0, n).map((b) => b.toRadixString(16).padLeft(2, '0')).join();
  }

  String _utf8Preview(Uint8List data, {int maxChars = 120}) {
    if (data.isEmpty) return '';
    try {
      final s = utf8.decode(data, allowMalformed: true);
      final cleaned = s.replaceAll(RegExp(r'[\x00-\x08\x0b\x0c\x0e-\x1f]'), '.');
      return cleaned.length > maxChars ? '${cleaned.substring(0, maxChars)}...' : cleaned;
    } catch (e) {
      return '(decode: $e)';
    }
  }

  String _rawUtf8(Uint8List data) {
    try {
      return utf8.decode(data, allowMalformed: true);
    } catch (e) {
      return '(decode: $e)';
    }
  }

  /// Finds bonded W1 (paired in OS settings).
  Future<BluetoothDevice?> getBondedW1() async {
    final bonded = await FlutterBluetoothSerial.instance.getBondedDevices();
    _log('is device already paired?', {'count': bonded.length});
    BluetoothDevice? device;
    for (final d in bonded) {
      final n = d.name?.trim();
      if (n != null && n.toUpperCase() == w1DeviceName.toUpperCase()) {
        device = d;
        break;
      }
    }
    isW1Bonded.value = device != null;
    if (device != null) {
      _log('bonded_device_found', {'name': device.name ?? '', 'address': device.address});
    }
    _log('paired_device_lookup', {
      'found': isW1Bonded.value,
      'name': device?.name ?? '',
      'address': device?.address ?? '',
      'is device already connected?': _deviceConnectedFlag(device),
    });
    return device;
  }

  String _deviceConnectedFlag(BluetoothDevice? device) {
    if (device == null) return 'false';
    try {
      final v = (device as dynamic).isConnected;
      return (v == null) ? 'unknown' : '$v';
    } catch (_) {
      return 'unknown';
    }
  }

  /// Opens RFCOMM and requests STATUS using already paired device (no app pairing UX).
  Future<void> ensureStatusChannel() async {
    _log('ensureStatusChannel_called');
    if (!Platform.isAndroid) return;
    final existing = _connection;
    if (existing != null && existing.isConnected) {
      isConnectionOpen.value = true;
      _log('Bluetooth connected');
      _requestStatus();
      return;
    }
    final device = await getBondedW1();
    if (device == null) {
      _log('bonded_device_not_found', {'wantedName': w1DeviceName});
      status.value = 'W1 not paired';
      return;
    }
    _log('before_connectBondedW1');
    await connectBondedW1();
    _log('after_connectBondedW1');
  }

  /// Request Bluetooth on, list bonded devices, find [w1DeviceName], [BluetoothConnection.toAddress].
  Future<void> connectBondedW1() async {
    if (!Platform.isAndroid) {
      _log('connect_failure', {'reason': 'classic_bt_android_only'});
      status.value = 'Classic Bluetooth is only supported on Android';
      throw UnsupportedError('W1 classic BT: Android only');
    }

    await disconnect();

    _log('connect_started', {'phase': 'getBondedDevices'});
    final bonded = await getBondedW1();
    final device = bonded;

    if (device == null) {
      _log('connect_failure', {
        'reason': 'device_not_bonded',
        'wantedName': w1DeviceName,
      });
      status.value = 'Failed: pair "$w1DeviceName" in Android Bluetooth settings first';
      throw StateError('Bonded device "$w1DeviceName" not found');
    }

    _log('connect_started', {
      'phase': 'BluetoothConnection.toAddress',
      'address': device.address,
      'name': device.name ?? '',
    });

    try {
      _log('socket_connect_attempt', {'address': device.address});
      _connection = await BluetoothConnection.toAddress(device.address);
      isConnectionOpen.value = true;
      _log('socket_connect_success', {'address': device.address});
      _log('Bluetooth connected', {'address': device.address});
      status.value = 'W1 paired';

      _inputSub = _connection!.input!.listen(
        (Uint8List data) {
          _log('Raw response full', {'payload': _rawUtf8(data)});
          _log('Raw response', {'utf8': _utf8Preview(data), 'hex': _hexPreview(data)});
          _appendAndParseStatus(data);
        },
        onError: (Object e, StackTrace st) {
          _log('response_listener_error', {'error': e.toString()});
        },
        onDone: () {
          _log('response_listener_done', {});
          isConnectionOpen.value = false;
          status.value = 'Input stream closed';
        },
        cancelOnError: false,
      );
      _requestStatus();
    } catch (e, st) {
      _log('socket_connect_failure', {'error': e.toString()});
      _log('connect_failure', {'error': e.toString()});
      status.value = 'Connect failed: $e';
      debugPrintStack(stackTrace: st);
      rethrow;
    }
  }

  /// Sends a small UTF-8 line and logs it (for RFCOMM send/receive verification).
  void sendLogSample() {
    final c = _connection;
    if (c == null || !c.isConnected) {
      _log('classic_send_skipped', {'reason': 'not_connected'});
      return;
    }
    const payload = 'W1_PING\n';
    try {
      c.output.add(Uint8List.fromList(utf8.encode(payload)));
      _log('classic_send', {'bytes': payload.length, 'utf8': payload.trim()});
    } catch (e) {
      _log('classic_send_error', {'error': e.toString()});
    }
  }

  void _requestStatus() {
    final c = _connection;
    if (c == null || !c.isConnected) {
      _log('status_request_skipped', {'reason': 'not_connected'});
      return;
    }
    const payload = 'STATUS\n';
    try {
      c.output.add(Uint8List.fromList(utf8.encode(payload)));
      _log('Sending STATUS');
    } catch (e) {
      _log('status_request_error', {'error': e.toString()});
    }
  }

  void _appendAndParseStatus(Uint8List data) {
    final incoming = utf8.decode(data, allowMalformed: true);
    _rxBuffer.write(incoming);
    final text = _rxBuffer.toString();
    final extracted = _extractStatusJson(text) ?? _extractAnyJson(text);
    if (extracted == null) {
      if (text.length > 8192) {
        _rxBuffer
          ..clear()
          ..write(text.substring(text.length - 2048));
      }
      return;
    }
    final jsonPayload = extracted.$1.trim();
    if (jsonPayload.isEmpty) return;
    try {
      final decoded = jsonDecode(jsonPayload);
      if (decoded is Map) {
        final map = _normalizeStatusMap(Map<String, dynamic>.from(decoded));
        latestStatus.value = map;
        status.value = 'W1 connected';
        final ip = map['file_server_ip']?.toString().trim() ?? '';
        final port = map['file_server_port']?.toString().trim() ?? '';
        _log('Parsed payload', {'payload': jsonPayload});
        _log('file_server_ip exists', {'exists': ip.isNotEmpty});
        _log('file_server_port exists', {'exists': port.isNotEmpty});
        _log('Extracted IP', {'value': ip});
        _log('Extracted Port', {'value': port});
      }
    } catch (e) {
      _log('status_parse_error', {'error': e.toString()});
      status.value = 'W1 paired but file server info not received';
    } finally {
      final cut = extracted.$2;
      final remain = (cut >= text.length) ? '' : text.substring(cut);
      _rxBuffer
        ..clear()
        ..write(remain);
    }
  }

  (String, int)? _extractStatusJson(String text) {
    final marker = RegExp(r'status\s*:\s*', caseSensitive: false);
    final m = marker.firstMatch(text);
    if (m == null) return null;
    final startSearch = m.end;
    var open = text.indexOf('{', startSearch);
    if (open < 0) return null;
    var depth = 0;
    for (var i = open; i < text.length; i++) {
      final ch = text.codeUnitAt(i);
      if (ch == 123) depth++; // {
      if (ch == 125) depth--; // }
      if (depth == 0) {
        return (text.substring(open, i + 1), i + 1);
      }
    }
    return null;
  }

  (String, int)? _extractAnyJson(String text) {
    final open = text.indexOf('{');
    if (open < 0) return null;
    var depth = 0;
    for (var i = open; i < text.length; i++) {
      final ch = text.codeUnitAt(i);
      if (ch == 123) depth++;
      if (ch == 125) depth--;
      if (depth == 0) {
        return (text.substring(open, i + 1), i + 1);
      }
    }
    return null;
  }

  Map<String, dynamic> _normalizeStatusMap(Map<String, dynamic> map) {
    Map<String, dynamic> source = map;
    for (final k in const ['status', 'STATUS', 'payload', 'data', 'result']) {
      final nested = source[k];
      if (nested is Map) {
        source = Map<String, dynamic>.from(nested);
        break;
      }
    }

    final ip = source['file_server_ip'] ?? source['fileServerIp'] ?? source['ip'] ?? source['host'];
    final port = source['file_server_port'] ?? source['fileServerPort'] ?? source['port'];
    if (ip != null) source['file_server_ip'] = ip;
    if (port != null) source['file_server_port'] = port;
    return source;
  }

  Future<void> disconnect() async {
    await _inputSub?.cancel();
    _inputSub = null;
    try {
      await _connection?.close();
    } catch (_) {}
    _connection = null;
    isConnectionOpen.value = false;
    _rxBuffer.clear();
    latestStatus.value = null;
    _log('classic_disconnected', {});
    status.value = 'Disconnected';
  }
}
