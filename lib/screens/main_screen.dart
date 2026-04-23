import 'package:falcon_one_demo/views/map/map.dart';
import 'package:flutter/material.dart';

/// Primary map + glass control panel (incident video / GPS / upload wiring lives in [MapView] / [MapController]).
class MainScreen extends StatelessWidget {
  const MainScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return const MapView();
  }
}
