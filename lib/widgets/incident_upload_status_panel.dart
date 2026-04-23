import 'dart:io';

import 'package:falcon_one_demo/controllers/map_controller.dart';
import 'package:flutter/material.dart';
import 'package:get/get.dart';

/// Floating card above the bottom control panel: video, GPS, toggles, upload (single source of truth for upload UX).
class IncidentUploadStatusPanel extends GetView<MapController> {
  const IncidentUploadStatusPanel({super.key});

  static const double _radius = 14;

  String _displayFileName(File file) {
    final p = file.path;
    final sep = Platform.pathSeparator;
    final i = p.lastIndexOf(sep);
    return i >= 0 ? p.substring(i + 1) : p;
  }

  @override
  Widget build(BuildContext context) {
    return Obx(() {
      final micOn = controller.isMicOn.value;
      final speakerOn = controller.isSpeakerOn.value;
      final gpsReady = controller.incidentGpsReady.value;
      final uploading = controller.isUploading.value;
      final phase = controller.uploadUiPhase.value;
      final selectedAt = controller.videoSelectedAt.value;
      final file = controller.selectedVideo.value;
      final incidentId = controller.lastUploadedIncidentId.value;
      final apiStatus = controller.lastUploadedApiStatus.value;
      final errorText = controller.uploadErrorDetail.value;

      if (file == null) {
        return const SizedBox.shrink();
      }

      final theme = Theme.of(context);
      final lat = controller.incidentLatitude.value;
      final lng = controller.incidentLongitude.value;
      final gpsLine = gpsReady
          ? '${lat.toStringAsFixed(5)}, ${lng.toStringAsFixed(5)}'
          : 'GPS: acquiring…';

      final uploadDisabled =
          uploading || phase == IncidentUploadUiPhase.success;

      return Material(
        color: Colors.transparent,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
          decoration: BoxDecoration(
            color: Colors.black.withValues(alpha: 0.75),
            borderRadius: BorderRadius.circular(_radius),
            border: Border.all(color: Colors.white.withValues(alpha: 0.12)),
            boxShadow: [
              BoxShadow(
                color: Colors.black.withValues(alpha: 0.35),
                blurRadius: 16,
                offset: const Offset(0, 6),
              ),
            ],
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  Icon(Icons.video_file_outlined, size: 20, color: theme.colorScheme.primary),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      _displayFileName(file),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: theme.textTheme.titleSmall?.copyWith(
                        color: Colors.white,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 10),
              _row(context, Icons.location_on_outlined, gpsLine),
              const SizedBox(height: 6),
              Row(
                children: [
                  Expanded(
                    child: _row(context, Icons.mic_none, micOn ? 'Mic: ON' : 'Mic: OFF'),
                  ),
                  Expanded(
                    child: _row(
                      context,
                      Icons.volume_up_outlined,
                      speakerOn ? 'Speaker: ON' : 'Speaker: OFF',
                    ),
                  ),
                ],
              ),
              if (selectedAt != null) ...[
                const SizedBox(height: 8),
                Text(
                  'Selected: ${_formatTs(selectedAt)}',
                  style: theme.textTheme.labelSmall?.copyWith(color: Colors.white54),
                ),
              ],
              if (phase == IncidentUploadUiPhase.success && incidentId != null) ...[
                const SizedBox(height: 14),
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Icon(Icons.check_circle, size: 22, color: Colors.greenAccent.shade200),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            'Uploaded successfully',
                            style: theme.textTheme.titleSmall?.copyWith(
                              color: Colors.white,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                          const SizedBox(height: 6),
                          Text(
                            'Incident ID: $incidentId',
                            style: theme.textTheme.bodySmall?.copyWith(color: Colors.white70),
                          ),
                          const SizedBox(height: 4),
                          Text(
                            'Status: ${apiStatus.isNotEmpty ? apiStatus : 'PENDING'}',
                            style: theme.textTheme.bodySmall?.copyWith(color: Colors.white70),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ],
              if (phase == IncidentUploadUiPhase.error && errorText.isNotEmpty) ...[
                const SizedBox(height: 12),
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Icon(Icons.error_outline, size: 20, color: Colors.redAccent.shade100),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        errorText,
                        style: theme.textTheme.bodySmall?.copyWith(
                          color: Colors.redAccent.shade100,
                          height: 1.35,
                        ),
                      ),
                    ),
                  ],
                ),
              ],
              const SizedBox(height: 14),
              FilledButton.icon(
                onPressed: uploadDisabled ? null : () => controller.uploadVideo(),
                icon: uploading
                    ? const SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(
                          strokeWidth: 2,
                          color: Colors.white,
                        ),
                      )
                    : Icon(
                        phase == IncidentUploadUiPhase.success
                            ? Icons.check
                            : Icons.cloud_upload_outlined,
                        size: 20,
                      ),
                label: Text(
                  uploading
                      ? 'Uploading…'
                      : phase == IncidentUploadUiPhase.success
                      ? 'Uploaded'
                      : 'Upload incident',
                ),
                style: FilledButton.styleFrom(
                  padding: const EdgeInsets.symmetric(vertical: 12),
                  backgroundColor: phase == IncidentUploadUiPhase.success
                      ? Colors.white.withValues(alpha: 0.12)
                      : theme.colorScheme.primary.withValues(alpha: 0.9),
                  disabledBackgroundColor: Colors.white.withValues(alpha: 0.08),
                  foregroundColor: Colors.white,
                  disabledForegroundColor: Colors.white54,
                ),
              ),
            ],
          ),
        ),
      );
    });
  }

  Widget _row(BuildContext context, IconData icon, String text) {
    return Row(
      children: [
        Icon(icon, size: 16, color: Colors.white60),
        const SizedBox(width: 6),
        Expanded(
          child: Text(
            text,
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            style: Theme.of(context).textTheme.bodySmall?.copyWith(color: Colors.white70),
          ),
        ),
      ],
    );
  }

  String _formatTs(DateTime t) {
    final l = t.toLocal();
    return '${l.year}-${l.month.toString().padLeft(2, '0')}-${l.day.toString().padLeft(2, '0')} '
        '${l.hour.toString().padLeft(2, '0')}:${l.minute.toString().padLeft(2, '0')}';
  }
}
