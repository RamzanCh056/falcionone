import 'dart:async';
import 'dart:io';

import 'package:falcon_one_demo/w1/w1_classic_bluetooth.dart';
import 'package:falcon_one_demo/w1/w1_platform.dart';
import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';

/// Developer / QA screen for the W1 transfer pipeline (classic BT + Wi‑Fi → file).
class W1DebugScreen extends StatefulWidget {
  const W1DebugScreen({super.key});

  @override
  State<W1DebugScreen> createState() => _W1DebugScreenState();
}

class _W1DebugScreenState extends State<W1DebugScreen> {
  StreamSubscription<Map<dynamic, dynamic>>? _sub;
  StreamSubscription<Map<dynamic, dynamic>>? _bleScanSub;
  Map<dynamic, dynamic> _state = <dynamic, dynamic>{};
  Map<String, dynamic> _bleScanUi = <String, dynamic>{};
  final List<String> _logs = <String>[];
  final TextEditingController _url = TextEditingController(text: 'http://10.0.2.2:8765');
  final TextEditingController _recordingId = TextEditingController(text: 'rec-mock-1');
  final TextEditingController _bleMac = TextEditingController(text: '74:43:8F:7E:D2:A4');
  final TextEditingController _bleLocalName = TextEditingController(text: 'SSJ-ZXAN9A1');

  @override
  void initState() {
    super.initState();
    W1ClassicBluetooth.instance.onLogLinesChanged = () {
      if (mounted) setState(() {});
    };
    _sub = W1Platform.stateStream().listen((event) {
      setState(() => _state = event);
    });
    _bleScanSub = W1Platform.bleScanUiStream().listen((event) {
      if (!mounted) return;
      setState(() {
        final m = <String, dynamic>{};
        for (final MapEntry<dynamic, dynamic> e in event.entries) {
          m['${e.key}'] = e.value;
        }
        _bleScanUi = m;
      });
    });
  }

  @override
  void dispose() {
    W1ClassicBluetooth.instance.onLogLinesChanged = null;
    _sub?.cancel();
    _bleScanSub?.cancel();
    _url.dispose();
    _recordingId.dispose();
    _bleMac.dispose();
    _bleLocalName.dispose();
    super.dispose();
  }

  Future<void> _refreshLogs() async {
    final lines = await W1Platform.getRecentLogs(limit: 150);
    setState(() {
      _logs
        ..clear()
        ..addAll(lines.reversed);
    });
  }

  /// Android 12+ requires [Permission.bluetoothScan] and [Permission.bluetoothConnect] at runtime.
  Future<bool> _ensureAndroidBlePermissions() async {
    if (!Platform.isAndroid) return true;
    final statuses = await <Permission>[
      Permission.bluetoothScan,
      Permission.bluetoothConnect,
    ].request();
    final scan = statuses[Permission.bluetoothScan] ?? PermissionStatus.denied;
    final connect = statuses[Permission.bluetoothConnect] ?? PermissionStatus.denied;
    if (scan.isGranted && connect.isGranted) return true;
    if (scan.isPermanentlyDenied || connect.isPermanentlyDenied) {
      await openAppSettings();
    }
    return false;
  }

  /// GATT connect: BT permissions plus location (helps on OEMs where scan/connect is gated on location).
  Future<bool> _ensureAndroidGattPermissions() async {
    if (!Platform.isAndroid) return true;
    final statuses = await <Permission>[
      Permission.bluetoothScan,
      Permission.bluetoothConnect,
      Permission.locationWhenInUse,
    ].request();
    final scan = statuses[Permission.bluetoothScan] ?? PermissionStatus.denied;
    final connect = statuses[Permission.bluetoothConnect] ?? PermissionStatus.denied;
    final loc = statuses[Permission.locationWhenInUse] ?? PermissionStatus.denied;
    if (scan.isGranted && connect.isGranted && loc.isGranted) return true;
    if (scan.isPermanentlyDenied ||
        connect.isPermanentlyDenied ||
        loc.isPermanentlyDenied) {
      await openAppSettings();
    }
    return false;
  }

  Future<void> _runW1BleConnect({required bool forceSafe}) async {
    if (!Platform.isAndroid) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('BLE GATT is Android-only.')),
      );
      return;
    }
    final ok = await _ensureAndroidGattPermissions();
    if (!mounted) return;
    if (!ok) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text(
            'Bluetooth + location needed for GATT. Allow in Settings if denied permanently.',
          ),
        ),
      );
      return;
    }
    try {
      final mac = _bleMac.text.trim();
      final local = _bleLocalName.text.trim();
      if (forceSafe) {
        await W1Platform.forceBleSafeConnect(macAddress: mac, localName: local);
      } else {
        await W1Platform.connectW1Ble(macAddress: mac, localName: local);
      }
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('BLE scan→connect started — watch status below')),
      );
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('$e')));
    }
  }

  Widget _bleScanStatusCard(BuildContext context) {
    final phase = _bleScanUi['phase']?.toString() ?? '';
    const visiblePhases = <String>{
      'preparing',
      'scanning',
      'diagnostic',
      'connecting',
      'connected',
      'exhausted',
    };
    if (!visiblePhases.contains(phase)) return const SizedBox.shrink();
    final detail = _bleScanUi['detail']?.toString() ?? '';
    final hint = _bleScanUi['userHint']?.toString();
    final attempt = _bleScanUi['attempt'];
    final maxA = _bleScanUi['maxAttempts'];
    final exhausted = phase == 'exhausted';
    final Color bg = exhausted
        ? Theme.of(context).colorScheme.errorContainer.withValues(alpha: 0.35)
        : phase == 'connected'
        ? Theme.of(context).colorScheme.primaryContainer.withValues(alpha: 0.4)
        : Theme.of(context).colorScheme.surfaceContainerHighest;

    return Card(
      color: bg,
      margin: const EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('BLE scan / connect', style: Theme.of(context).textTheme.titleSmall),
            const SizedBox(height: 6),
            if (detail.isNotEmpty) Text(detail),
            if (hint != null && hint.isNotEmpty && hint != detail) ...[
              const SizedBox(height: 4),
              Text(hint),
            ],
            if (attempt != null && maxA != null) ...[
              const SizedBox(height: 4),
              Text('Attempt $attempt of $maxA', style: Theme.of(context).textTheme.bodySmall),
            ],
            if (exhausted) ...[
              const SizedBox(height: 12),
              FilledButton.icon(
                onPressed: () => _runW1BleConnect(forceSafe: false),
                icon: const Icon(Icons.refresh),
                label: const Text('Retry scan → connect'),
              ),
            ],
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final phase = _state['phase']?.toString() ?? '—';
    final pipeline = _state['pipelineState']?.toString() ?? '—';
    final detail = _state['detail']?.toString() ?? '';
    final progress = (_state['progress'] as num?)?.toDouble() ?? 0;
    final path = _state['localPath']?.toString();
    final err = _state['error']?.toString();

    return Scaffold(
      appBar: AppBar(title: const Text('W1 transfer (debug)')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Text('Phase: $phase', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 4),
          Text('Pipeline: $pipeline', style: Theme.of(context).textTheme.bodySmall),
          const SizedBox(height: 4),
          Text(detail),
          const SizedBox(height: 8),
          LinearProgressIndicator(value: progress.clamp(0, 1)),
          if (path != null && path.isNotEmpty) ...[
            const SizedBox(height: 12),
            SelectableText('Saved: $path'),
          ],
          if (err != null && err.isNotEmpty) ...[
            const SizedBox(height: 8),
            Text('Error: $err', style: TextStyle(color: Theme.of(context).colorScheme.error)),
          ],
          const Divider(height: 32),
          TextField(
            controller: _bleMac,
            decoration: const InputDecoration(
              labelText: 'W1 BLE MAC (GATT)',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _bleLocalName,
            decoration: const InputDecoration(
              labelText: 'W1 BLE local name (empty = MAC-only scan match)',
              border: OutlineInputBorder(),
            ),
          ),
          if (Platform.isAndroid) _bleScanStatusCard(context),
          const SizedBox(height: 12),
          TextField(
            controller: _url,
            decoration: const InputDecoration(
              labelText: 'Wi-Fi base URL (real device / mock server)',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _recordingId,
            decoration: const InputDecoration(
              labelText: 'Recording id (must match server / mock)',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 16),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              FilledButton(
                onPressed: () async {
                  await W1Platform.configure(wifiBaseUrl: _url.text.trim());
                  if (!context.mounted) return;
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(content: Text('Configured Wi-Fi URL')),
                  );
                },
                child: const Text('Save URL'),
              ),
              FilledButton.tonal(
                onPressed: () async {
                  await W1Platform.useBuiltInMockWifi(enabled: true);
                  if (!context.mounted) return;
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(content: Text('Built-in mock Wi-Fi on — use recording id rec-mock-1')),
                  );
                },
                child: const Text('Mock Wi-Fi'),
              ),
              FilledButton.tonal(
                onPressed: () async {
                  await W1Platform.useBuiltInMockWifi(
                    enabled: false,
                    wifiBaseUrl: _url.text.trim().isEmpty ? null : _url.text.trim(),
                  );
                  if (!context.mounted) return;
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(content: Text('HTTP Wi-Fi client restored')),
                  );
                },
                child: const Text('Real HTTP'),
              ),
              FilledButton(
                onPressed: () async {
                  await W1Platform.simulateRecordingComplete(
                    recordingId: _recordingId.text.trim(),
                  );
                },
                child: const Text('Simulate BLE complete'),
              ),
              OutlinedButton(
                onPressed: () => W1Platform.resumePending(),
                child: const Text('Resume pending'),
              ),
              OutlinedButton(
                onPressed: () => W1Platform.resetDisplay(),
                child: const Text('Reset UI'),
              ),
              OutlinedButton(
                onPressed: () => _runW1BleConnect(forceSafe: false),
                child: const Text('Connect W1 BLE (GATT)'),
              ),
              OutlinedButton(
                onPressed: () => _runW1BleConnect(forceSafe: true),
                child: const Text('Force BLE Safe Connect'),
              ),
              OutlinedButton(
                onPressed: () async {
                  if (!Platform.isAndroid) {
                    ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(content: Text('Classic Bluetooth is Android-only.')),
                    );
                    return;
                  }
                  final ok = await _ensureAndroidBlePermissions();
                  if (!context.mounted) return;
                  if (!ok) {
                    ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(
                        content: Text(
                          'Bluetooth permissions required. Allow in Settings if denied permanently.',
                        ),
                      ),
                    );
                    return;
                  }
                  try {
                    await W1ClassicBluetooth.instance.connectBondedW1();
                    if (!context.mounted) return;
                    setState(() {});
                    ScaffoldMessenger.of(context).showSnackBar(
                      SnackBar(content: Text(W1ClassicBluetooth.instance.status.value ?? 'Connected')),
                    );
                  } catch (e) {
                    if (!context.mounted) return;
                    ScaffoldMessenger.of(context).showSnackBar(
                      SnackBar(content: Text('$e')),
                    );
                  }
                },
                child: const Text('Connect Classic W1'),
              ),
              OutlinedButton(
                onPressed: () async {
                  W1ClassicBluetooth.instance.sendLogSample();
                  if (!context.mounted) return;
                  setState(() {});
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(content: Text('Send sample logged — see debug console / classic log')),
                  );
                },
                child: const Text('Classic send sample'),
              ),
              OutlinedButton(
                onPressed: () async {
                  await W1ClassicBluetooth.instance.disconnect();
                  if (!context.mounted) return;
                  setState(() {});
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(content: Text('Classic Bluetooth disconnected')),
                  );
                },
                child: const Text('Disconnect Classic W1'),
              ),
              OutlinedButton(
                onPressed: _refreshLogs,
                child: const Text('Load logs'),
              ),
              OutlinedButton(
                onPressed: () async {
                  final p = await W1Platform.exportLogs();
                  if (!context.mounted) return;
                  ScaffoldMessenger.of(context).showSnackBar(
                    SnackBar(content: Text(p ?? 'Export failed')),
                  );
                },
                child: const Text('Export logs'),
              ),
            ],
          ),
          const SizedBox(height: 24),
          Text('Classic Bluetooth (RFCOMM) log', style: Theme.of(context).textTheme.titleSmall),
          const SizedBox(height: 8),
          ...W1ClassicBluetooth.instance.logLines.reversed.map(
            (l) => Padding(
              padding: const EdgeInsets.only(bottom: 4),
              child: SelectableText(l, style: const TextStyle(fontSize: 11, fontFamily: 'monospace')),
            ),
          ),
          const SizedBox(height: 24),
          Text('Native W1 log lines', style: Theme.of(context).textTheme.titleSmall),
          const SizedBox(height: 8),
          ..._logs.map(
            (l) => Padding(
              padding: const EdgeInsets.only(bottom: 4),
              child: SelectableText(l, style: const TextStyle(fontSize: 11, fontFamily: 'monospace')),
            ),
          ),
        ],
      ),
    );
  }
}
