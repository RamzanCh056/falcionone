import 'dart:async';
import 'dart:io';

import 'package:falcon_one_demo/w1/w1_platform.dart';
import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';

/// Developer / QA screen for the W1 transfer pipeline (BLE → Wi‑Fi → file).
class W1DebugScreen extends StatefulWidget {
  const W1DebugScreen({super.key});

  @override
  State<W1DebugScreen> createState() => _W1DebugScreenState();
}

class _W1DebugScreenState extends State<W1DebugScreen> {
  StreamSubscription<Map<dynamic, dynamic>>? _sub;
  Map<dynamic, dynamic> _state = <dynamic, dynamic>{};
  final List<String> _logs = <String>[];
  final TextEditingController _url = TextEditingController(text: 'http://10.0.2.2:8765');
  final TextEditingController _recordingId = TextEditingController(text: 'rec-mock-1');

  @override
  void initState() {
    super.initState();
    _sub = W1Platform.stateStream().listen((event) {
      setState(() => _state = event);
    });
  }

  @override
  void dispose() {
    _sub?.cancel();
    _url.dispose();
    _recordingId.dispose();
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

  /// BLE GATT connect: also request location (many OEMs need Location ON for reliable BLE).
  Future<bool> _ensureAndroidBleConnectAndLocation() async {
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
                onPressed: () async {
                  final ok = await _ensureAndroidBlePermissions();
                  if (!context.mounted) return;
                  if (!ok) {
                    ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(
                        content: Text(
                          'Bluetooth scan/connect permission required. '
                          'Allow both in Settings if you denied permanently.',
                        ),
                      ),
                    );
                    return;
                  }
                  await W1Platform.startRealBle();
                  if (!context.mounted) return;
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(content: Text('Real BLE scan started')),
                  );
                },
                child: const Text('Start real BLE'),
              ),
              OutlinedButton(
                onPressed: () async {
                  final ok = await _ensureAndroidBleConnectAndLocation();
                  if (!context.mounted) return;
                  if (!ok) {
                    ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(
                        content: Text(
                          'Bluetooth scan/connect and location permission required for GATT. '
                          'Enable system Location if it is off.',
                        ),
                      ),
                    );
                    return;
                  }
                  await W1Platform.connectW1(macAddress: '74:43:8F:7E:D2:A4');
                  if (!context.mounted) return;
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(content: Text('GATT connect requested — check W1 logs')),
                  );
                },
                child: const Text('Connect W1'),
              ),
              OutlinedButton(
                onPressed: () async {
                  final ok = await _ensureAndroidBlePermissions();
                  if (!context.mounted) return;
                  if (!ok) {
                    ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(
                        content: Text(
                          'Bluetooth scan/connect permission required for BLE probe.',
                        ),
                      ),
                    );
                    return;
                  }
                  await W1Platform.runAnonymousBleProbe();
                  if (!context.mounted) return;
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(
                      content: Text(
                        'Anonymous BLE probe started (scan buffer, top 3 by RSSI). See W1 logs.',
                      ),
                    ),
                  );
                },
                child: const Text('Probe unnamed BLE'),
              ),
              OutlinedButton(
                onPressed: () => W1Platform.stopRealBle(),
                child: const Text('Stop real BLE'),
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
          Text('Recent log lines', style: Theme.of(context).textTheme.titleSmall),
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
