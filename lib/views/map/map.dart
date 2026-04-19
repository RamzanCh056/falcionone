import 'package:falcon_one_demo/components/panel_button.dart';
import 'package:falcon_one_demo/controllers/map_controller.dart';
import 'package:falcon_one_demo/mapbox_config.dart';
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

class MapView extends GetView<MapController> {
  const MapView({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Stack(children: [_buildMap(), _buildTopBar(), _buildButtonPanel()]),
    );
  }

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
        zoom: 16.0,
        pitch: 60.0,
        center: Point(coordinates: Position(0.031085, 51.501435)),
        padding: MbxEdgeInsets(bottom: 200.0, top: 0.0, left: 0.0, right: 0.0),
      ),
    );
  }

  Widget _buildTopBar() {
    return Positioned(
      top: 0,
      left: 20.0,
      right: 20.0,
      child: LiquidGlassContainer(
        config: glassConfig,
        child: SafeArea(
          minimum: EdgeInsets.only(top: 10.0),
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: 20.0),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Image.asset(
                  'assets/images/aeria-logo.png',
                  height: 30.0,
                  errorBuilder: (_, __, ___) => const Text('Falcon', style: TextStyle(fontWeight: FontWeight.w600)),
                ),
                const Spacer(),
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
    );
  }

  Widget _buildButtonPanel() {
    return Positioned(
      bottom: 20.0,
      left: 20.0,
      right: 20.0,
      child: LiquidGlassContainer(
        config: glassConfig,
        height: 250.0,
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
                        iconData: controller.isSpeakerMuted.value
                            ? Icons.volume_off
                            : Icons.volume_up,
                        onTap: () async {
                          await controller.toggleSpeakerMute();
                        },
                      ),
                    ),
                  ),
                  Expanded(
                    child: PanelButton(
                      iconData: Icons.camera_alt,
                      onTap: () {},
                    ),
                  ),
                ],
              ),
            ),
            Expanded(
              child: Obx(
                () => PanelButton(
                  iconData: controller.isMicrophoneMuted.value
                      ? Icons.mic_off
                      : Icons.mic,
                  onTap: () async {
                    await controller.toggleMicrophoneMute();
                  },
                ),
              ),
            ),
            Expanded(
              child: Padding(
                padding: EdgeInsets.only(left: 10.0),
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  spacing: 20.0,
                  children: [
                    Row(
                      spacing: 10.0,
                      children: [
                        Icon(Icons.battery_3_bar),
                        Obx(() => Text("${controller.batteryLevel.value}%")),
                      ],
                    ),
                    Row(
                      spacing: 10.0,
                      children: [
                        Icon(Icons.people),
                        Obx(() => Text(controller.numUsers.value.toString())),
                      ],
                    ),
                    Row(
                      spacing: 10.0,
                      children: [
                        Icon(Icons.signal_wifi_4_bar),
                        Obx(() => Text(controller.signalStatus.value)),
                      ],
                    ),
                    Row(
                      spacing: 10.0,
                      children: [
                        Icon(Icons.satellite_alt),
                        Obx(
                          () => Text(controller.numSatellites.value.toString()),
                        ),
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
