import 'package:falcon_one_demo/controllers/glasses_controller.dart';
import 'package:flutter/material.dart';
import 'package:get/get.dart';

/// BleeqUp glasses status — separate from W1 banner; uses [GlassesController] only.
class GlassesStatusBanner extends GetView<GlassesController> {
  const GlassesStatusBanner({super.key});

  @override
  Widget build(BuildContext context) {
    return Obx(() {
      final connected = controller.isConnected.value;
      final recording = controller.isRecording.value;
      final prep = controller.isPreparingVideo.value;
      final dl = controller.isDownloading.value;
      final failed = controller.downloadFailed.value;
      final hasFile = controller.lastVideoPath.value != null;

      final Color bg;
      final Color fg;
      if (failed) {
        bg = Colors.orange.withValues(alpha: 0.22);
        fg = Colors.orange.shade200;
      } else if (recording) {
        bg = Colors.red.withValues(alpha: 0.2);
        fg = Colors.red.shade200;
      } else if (prep) {
        bg = Colors.amber.withValues(alpha: 0.18);
        fg = Colors.amber.shade100;
      } else if (dl) {
        bg = Colors.indigo.withValues(alpha: 0.2);
        fg = Colors.indigo.shade100;
      } else if (hasFile) {
        bg = Colors.green.withValues(alpha: 0.2);
        fg = Colors.green.shade200;
      } else if (!connected) {
        bg = Colors.deepPurple.withValues(alpha: 0.2);
        fg = Colors.deepPurple.shade100;
      } else {
        bg = Colors.teal.withValues(alpha: 0.18);
        fg = Colors.teal.shade100;
      }

      return Container(
        padding: const EdgeInsets.all(8),
        margin: const EdgeInsets.only(top: 8),
        decoration: BoxDecoration(
          color: bg,
          borderRadius: BorderRadius.circular(8),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              controller.statusMessage.value,
              style: TextStyle(
                color: fg,
                fontWeight: FontWeight.bold,
                fontSize: 13,
              ),
            ),
            if (controller.canRetryDownload)
              Padding(
                padding: const EdgeInsets.only(top: 6),
                child: TextButton(
                  onPressed: () => controller.retryLastDownload(),
                  child: const Text('Retry download'),
                ),
              ),
          ],
        ),
      );
    });
  }
}
