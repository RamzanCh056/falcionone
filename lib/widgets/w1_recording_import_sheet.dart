import 'dart:io';

import 'package:falcon_one_demo/controllers/map_controller.dart';
import 'package:falcon_one_demo/models/w1_recording.dart';
import 'package:flutter/material.dart';
import 'package:get/get.dart';

/// Bottom sheet: W1 latest recording metadata, GPS, audio toggles, and upload.
class W1RecordingImportSheet extends GetView<MapController> {
  const W1RecordingImportSheet({super.key});

  String _dateLine(W1Recording? r) {
    if (r == null) return '—';
    final t = r.createdAt;
    if (t == null) return 'Time: unknown';
    return 'Recorded: ${t.toUtc().toIso8601String()}';
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return SafeArea(
      top: false,
      child: Container(
        padding: const EdgeInsets.fromLTRB(16, 8, 8, 20),
        decoration: BoxDecoration(
          color: const Color(0xFF0E0E0E),
          borderRadius: const BorderRadius.vertical(top: Radius.circular(20)),
          border: Border.all(color: Colors.white.withValues(alpha: 0.08)),
        ),
        child: Obx(() {
          final loading = controller.isFetchingRecordings.value || controller.isDownloading.value;
          final err = controller.w1ImportError.value;
          final rec = controller.w1ActiveRecording.value;
          final file = controller.selectedVideo.value;
          final gpsReady = controller.incidentGpsReady.value;
          final lat = controller.incidentLatitude.value;
          final lng = controller.incidentLongitude.value;
          final micOn = controller.isMicOn.value;
          final speakerOn = controller.isSpeakerOn.value;
          final speakerMuted = controller.isSpeakerMuted.value;
          final micMuted = controller.isMicrophoneMuted.value;

          return Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(
                      'Recording History',
                      style: theme.textTheme.titleMedium?.copyWith(
                        color: Colors.white,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                  IconButton(
                    tooltip: 'Close',
                    icon: const Icon(Icons.close, color: Colors.white70),
                    onPressed: () => Get.back<void>(),
                  ),
                ],
              ),
              if (loading)
                const Padding(
                  padding: EdgeInsets.symmetric(vertical: 32),
                  child: Center(
                    child: Column(
                      children: [
                        CircularProgressIndicator(strokeWidth: 2.5),
                        SizedBox(height: 16),
                        Text(
                          'Contacting W1…',
                          style: TextStyle(color: Colors.white70),
                        ),
                      ],
                    ),
                  ),
                )
              else if (err.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.fromLTRB(0, 8, 8, 16),
                  child: Text(
                    err,
                    style: const TextStyle(color: Colors.white70, height: 1.4),
                  ),
                )
              else if (rec != null && file != null)
                _ReadyCard(
                  theme: theme,
                  recording: rec,
                  file: file,
                  dateLine: _dateLine(rec),
                  gpsLine: gpsReady
                      ? 'GPS: ${lat.toStringAsFixed(5)}, ${lng.toStringAsFixed(5)}'
                      : 'GPS: N/A (still acquiring)',
                  micLine: 'Mic: ${micOn ? 'on' : 'off'}${micMuted ? ' (call muted)' : ''}',
                  speakerLine:
                      'Speaker: ${speakerOn ? 'on' : 'off'}${speakerMuted ? ' (call muted)' : ''}',
                  uploading: controller.isUploading.value,
                  onUpload: () async {
                    await controller.uploadW1DownloadedRecording();
                  },
                )
              else
                const Padding(
                  padding: EdgeInsets.symmetric(vertical: 24),
                  child: Text(
                    'No data',
                    style: TextStyle(color: Colors.white54),
                  ),
                ),
            ],
          );
        }),
      ),
    );
  }
}

class _ReadyCard extends StatelessWidget {
  const _ReadyCard({
    required this.theme,
    required this.recording,
    required this.file,
    required this.dateLine,
    required this.gpsLine,
    required this.micLine,
    required this.speakerLine,
    required this.uploading,
    required this.onUpload,
  });

  final ThemeData theme;
  final W1Recording recording;
  final File file;
  final String dateLine;
  final String gpsLine;
  final String micLine;
  final String speakerLine;
  final bool uploading;
  final Future<void> Function() onUpload;

  @override
  Widget build(BuildContext context) {
    final name = recording.filename.isNotEmpty ? recording.filename : file.uri.pathSegments.last;
    return Padding(
      padding: const EdgeInsets.only(right: 8, bottom: 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(
            name,
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            style: theme.textTheme.titleSmall?.copyWith(
              color: Colors.white,
              fontWeight: FontWeight.w600,
            ),
          ),
          const SizedBox(height: 8),
          Text(dateLine, style: const TextStyle(color: Colors.white70, fontSize: 13, height: 1.35)),
          const SizedBox(height: 6),
          Text(gpsLine, style: const TextStyle(color: Colors.white70, fontSize: 13, height: 1.35)),
          const SizedBox(height: 6),
          Text(micLine, style: const TextStyle(color: Colors.white70, fontSize: 13, height: 1.35)),
          const SizedBox(height: 4),
          Text(speakerLine, style: const TextStyle(color: Colors.white70, fontSize: 13, height: 1.35)),
          if (recording.sizeMb.isNotEmpty) ...[
            const SizedBox(height: 6),
            Text('Size: ${recording.sizeMb} MB', style: const TextStyle(color: Colors.white38, fontSize: 12)),
          ],
          const SizedBox(height: 20),
          FilledButton.icon(
            onPressed: uploading
                ? null
                : () async {
                    await onUpload();
                  },
            icon: uploading
                ? const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                  )
                : const Icon(Icons.cloud_upload_outlined, size: 20),
            label: Text(uploading ? 'Uploading…' : 'Upload Incident'),
          ),
        ],
      ),
    );
  }
}
