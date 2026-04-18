import 'package:falcon_one_demo/bindings/map_binding.dart';
import 'package:falcon_one_demo/views/map/map.dart';
import 'package:falcon_one_demo/w1/w1_debug_screen.dart';
import 'package:flutter/material.dart';
import 'package:get/get.dart';

class FalconOneDemoApp extends StatelessWidget {
  const FalconOneDemoApp({super.key});

  // This widget is the root of your application.
  @override
  Widget build(BuildContext context) {
    return GetMaterialApp(
      title: 'Flutter Demo',
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(
          seedColor: Colors.red,
          brightness: Brightness.dark,
        ),
      ),
      debugShowCheckedModeBanner: false,
      getPages: [
        GetPage(name: '/', page: () => MapView(), binding: MapBinding()),
        GetPage(name: '/w1', page: () => const W1DebugScreen()),
      ],
    );
  }
}
