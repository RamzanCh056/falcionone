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

  /// Last status line for UI.
  final ValueNotifier<String?> status = ValueNotifier<String?>(null);

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
    debugPrint('[W1Classic] $line');
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

  /// Request Bluetooth on, list bonded devices, find [w1DeviceName], [BluetoothConnection.toAddress].
  Future<void> connectBondedW1() async {
    if (!Platform.isAndroid) {
      _log('connect_failure', {'reason': 'classic_bt_android_only'});
      status.value = 'Classic Bluetooth is only supported on Android';
      throw UnsupportedError('W1 classic BT: Android only');
    }

    await disconnect();

    _log('connect_started', {'phase': 'getBondedDevices'});
    final bonded = await FlutterBluetoothSerial.instance.getBondedDevices();
    _log('bonded_devices', {'count': bonded.length});

    BluetoothDevice? device;
    for (final d in bonded) {
      final n = d.name?.trim();
      if (n != null && n.toUpperCase() == w1DeviceName.toUpperCase()) {
        device = d;
        break;
      }
    }

    if (device == null) {
      _log('connect_failure', {
        'reason': 'device_not_bonded',
        'wantedName': w1DeviceName,
        'bondedNames': bonded.map((d) => d.name ?? '(null)').join(', '),
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
      _connection = await BluetoothConnection.toAddress(device.address);
      _log('connect_success', {'address': device.address});
      status.value = 'Connected ${device.address}';

      _inputSub = _connection!.input!.listen(
        (Uint8List data) {
          _log('classic_receive', {
            'bytes': data.length,
            'hex': _hexPreview(data),
            'utf8': _utf8Preview(data),
          });
        },
        onError: (Object e, StackTrace st) {
          _log('classic_receive_error', {'error': e.toString()});
        },
        onDone: () {
          _log('classic_input_done', {});
          status.value = 'Input stream closed';
        },
        cancelOnError: false,
      );
    } catch (e, st) {
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

  Future<void> disconnect() async {
    await _inputSub?.cancel();
    _inputSub = null;
    try {
      await _connection?.close();
    } catch (_) {}
    _connection = null;
    _log('classic_disconnected', {});
    status.value = 'Disconnected';
  }
}
