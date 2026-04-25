import 'package:falcon_one_demo/components/panel_button.dart';
import 'package:falcon_one_demo/controllers/map_controller.dart';
import 'package:falcon_one_demo/mapbox_config.dart';
import 'package:falcon_one_demo/widgets/incident_upload_status_panel.dart';
import 'package:flutter/material.dart';
import 'package:flutter_liquid_glass/liquid_glass.dart';
import 'package:get/get.dart';
import 'package:mapbox_maps_flutter/mapbox_maps_flutter.dart';

final LiquidGlassConfig glassConfig = LiquidGlassConfig(
  border: Border.all(color: Colors.white12),
  baseColor: Colors.black,
  opacity: 0.4,
  enableSpecularHighlight: false,
  blurAmount: 15.0,
  borderRadius: BorderRadius.circular(30.0),
  frostIntensity: 0.1,
);

/// Bottom glass control panel height + margin from screen bottom.
const double _kControlPanelHeight = 250;
const double _kControlPanelBottom = 20;
const double _kUploadPanelGap = 12;

double get _kUploadPanelBottomFromScreen =>
    _kControlPanelBottom + _kControlPanelHeight + _kUploadPanelGap;

class MapView extends GetView<MapController> {
  const MapView({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Stack(
        children: [
          _buildMap(),
          _buildUploadStatusPanel(),
          _buildTopBar(context),
          _buildButtonPanel(),
        ],
      ),
    );
  }

  /// Layer 1: map only (no debug / metadata overlays).
  Widget _buildMap() {
    if (!mapboxAccessTokenConfigured) {
      return Container(
        color: const Color(0xFF121212),
        alignment: Alignment.center,
        padding: const EdgeInsets.all(24),
        child: const Text(
          'Mapbox token missing.\n\n'
          'Edit assets/mapbox.env and set:\n'
          'MAPBOX_ACCESS_TOKEN=pk.your_public_token\n\n'
          'Or run with:\n'
          'flutter run --dart-define=MAPBOX_ACCESS_TOKEN=pk....',
          textAlign: TextAlign.center,
          style: TextStyle(color: Colors.white70, height: 1.4),
        ),
      );
    }
    return MapWidget(
      onMapCreated: controller.onMapCreated,
      styleUri: "mapbox://styles/fiddlie-ed/cmc9h7ar2035801sm6361cdtc",
      cameraOptions: CameraOptions(
        zoom: 1.5,
        pitch: 0.0,
        center: Point(coordinates: Position(0.0, 0.0)),
        padding: MbxEdgeInsets(bottom: 200.0, top: 0.0, left: 0.0, right: 0.0),
      ),
    );
  }

  /// Layer 2: floating upload / status card (hidden until a video is selected).
  Widget _buildUploadStatusPanel() {
    return Positioned(
      left: 20,
      right: 20,
      bottom: _kUploadPanelBottomFromScreen,
      child: const IncidentUploadStatusPanel(),
    );
  }

  /// Layer 3: top bar (title + actions) + W1 status banner when URL is set.
  Widget _buildTopBar(BuildContext context) {
    final theme = Theme.of(context);
    return Positioned(
      top: 0,
      left: 20.0,
      right: 20.0,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          LiquidGlassContainer(
            config: glassConfig,
            child: SafeArea(
              minimum: const EdgeInsets.only(top: 10.0),
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 16.0, horizontal: 8.0),
                child: Row(
                  children: [
                    Text(
                      'Falcon',
                      style: theme.textTheme.titleMedium?.copyWith(
                        color: Colors.white,
                        fontWeight: FontWeight.w700,
                        letterSpacing: 0.5,
                      ),
                    ),
                    const SizedBox(width: 12),
                    Image.asset(
                      'assets/images/aeria-logo.png',
                      height: 28.0,
                      errorBuilder: (_, __, ___) => const SizedBox.shrink(),
                    ),
                    const Spacer(),
                    Obx(() {
                      final recording = controller.isW1Recording.value;
                      return IconButton(
                        tooltip: recording
                            ? 'Wait until recording finishes'
                            : 'Recording history',
                        onPressed: recording ? null : () => controller.onTimerClick(),
                        icon: Icon(
                          Icons.schedule,
                          size: 22,
                          color: recording ? Colors.white38 : Colors.white,
                        ),
                      );
                    }),
                    Obx(() {
                      final hasVideo = controller.selectedVideo.value != null;
                      final busy = controller.isUploading.value;
                      final done =
                          controller.uploadUiPhase.value == IncidentUploadUiPhase.success;
                      if (!hasVideo) return const SizedBox.shrink();
                      return IconButton(
                        tooltip: busy
                            ? 'Uploading…'
                            : done
                            ? 'Already uploaded'
                            : 'Quick upload',
                        onPressed: (busy || done) ? null : () => controller.uploadVideo(),
                        icon: busy
                            ? const SizedBox(
                                width: 22,
                                height: 22,
                                child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                              )
                            : const Icon(Icons.cloud_upload_outlined, size: 22),
                      );
                    }),
                    IconButton(
                      tooltip: 'W1 device transfer (debug)',
                      icon: const Icon(Icons.downloading_rounded, size: 22),
                      onPressed: () => Get.toNamed('/w1'),
                    ),
                  ],
                ),
              ),
            ),
          ),
          Obx(() {
            final url = controller.w1BaseUrl;
            final checking = controller.isCheckingStatus.value;
            final recording = controller.isW1Recording.value;
            final msg = controller.w1StatusMessage.value;
            final noUrl = url == null || url.isEmpty;
            final unreachable = msg.startsWith('⚠️');

            final Color bg;
            final Color fg;
            final String primary;
            if (noUrl) {
              bg = Colors.blueGrey.withValues(alpha: 0.25);
              fg = Colors.blueGrey.shade100;
              primary = 'Connecting to W1...';
            } else if (unreachable) {
              bg = Colors.orange.withValues(alpha: 0.2);
              fg = Colors.orange.shade200;
              primary = msg;
            } else if (recording) {
              bg = Colors.red.withValues(alpha: 0.2);
              fg = Colors.red.shade200;
              primary = msg;
            } else {
              bg = Colors.green.withValues(alpha: 0.2);
              fg = Colors.green.shade200;
              primary = msg;
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
                    primary,
                    style: TextStyle(
                      color: fg,
                      fontWeight: FontWeight.bold,
                      fontSize: 13,
                    ),
                  ),
                  if (!noUrl && checking)
                    Padding(
                      padding: const EdgeInsets.only(top: 4),
                      child: Text(
                        'Checking status…',
                        style: TextStyle(
                          color: Colors.white.withValues(alpha: 0.45),
                          fontSize: 11,
                          fontWeight: FontWeight.w400,
                        ),
                      ),
                    ),
                ],
              ),
            );
          }),
        ],
      ),
    );
  }

  /// Layer 4: bottom controls (unchanged layout).
  Widget _buildButtonPanel() {
    return Positioned(
      bottom: _kControlPanelBottom,
      left: 20.0,
      right: 20.0,
      child: LiquidGlassContainer(
        config: glassConfig,
        height: _kControlPanelHeight,
        padding: const EdgeInsets.all(15.0),
        child: Row(
          spacing: 10.0,
          children: [
            Expanded(
              child: Column(
                spacing: 10.0,
                children: [
                  Expanded(
                    child: Obx(
                      () => PanelButton(
                        iconData: controller.isSpeakerOn.value ? Icons.volume_up : Icons.volume_off,
                        onTap: controller.toggleSpeaker,
                      ),
                    ),
                  ),
                  Expanded(
                    child: Obx(
                      () {
                        final uploading = controller.isUploading.value;
                        final picking = controller.isPickingVideo.value;
                        final uploadDone =
                            controller.uploadUiPhase.value == IncidentUploadUiPhase.success;
                        final pickBusy = picking || uploading;
                        final uploadLongPressBusy = picking || uploading || uploadDone;
                        return Tooltip(
                          message: picking
                              ? 'Opening picker...'
                              : uploading
                              ? 'Uploading...'
                              : uploadDone
                              ? 'Tap: pick another video • Long press disabled after upload'
                              : 'Tap: pick video • Long press: upload',
                          child: Stack(
                            alignment: Alignment.center,
                            children: [
                              PanelButton(
                                iconData: Icons.camera_alt,
                                onTap: pickBusy ? null : () => controller.pickVideo(),
                                onLongPress:
                                    uploadLongPressBusy ? null : () => controller.uploadVideo(),
                              ),
                              if (pickBusy)
                                const SizedBox(
                                  width: 26,
                                  height: 26,
                                  child: CircularProgressIndicator(
                                    strokeWidth: 2.5,
                                    color: Colors.white,
                                  ),
                                ),
                            ],
                          ),
                        );
                      },
                    ),
                  ),
                ],
              ),
            ),
            Expanded(
              child: Obx(
                () => PanelButton(
                  iconData: controller.isMicOn.value ? Icons.mic : Icons.mic_off,
                  onTap: controller.toggleMic,
                ),
              ),
            ),
            Expanded(
              child: Padding(
                padding: const EdgeInsets.only(left: 10.0),
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  spacing: 20.0,
                  children: [
                    Row(
                      spacing: 10.0,
                      children: [
                        const Icon(Icons.battery_3_bar),
                        Obx(() => Text(controller.batteryDisplay.value)),
                      ],
                    ),
                    Row(
                      spacing: 10.0,
                      children: [
                        const Icon(Icons.people),
                        Obx(() {
                          if (!controller.hasCallTelemetry.value) {
                            return const Text('N/A');
                          }
                          return Text('${controller.numUsers.value}');
                        }),
                      ],
                    ),
                    Row(
                      spacing: 10.0,
                      children: [
                        const Icon(Icons.signal_wifi_4_bar),
                        Obx(() => Text(controller.signalDisplay.value)),
                      ],
                    ),
                    Row(
                      spacing: 10.0,
                      children: [
                        const Icon(Icons.satellite_alt),
                        Obx(() {
                          if (!controller.hasCallTelemetry.value) {
                            return const Text('N/A');
                          }
                          return Text('${controller.numSatellites.value}');
                        }),
                      ],
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
