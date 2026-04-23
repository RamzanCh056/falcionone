import 'package:falcon_one_demo/app_ui_keys.dart';
import 'package:falcon_one_demo/bindings/map_binding.dart';
import 'package:falcon_one_demo/screens/main_screen.dart';
import 'package:falcon_one_demo/w1/w1_debug_screen.dart';
import 'package:flutter/material.dart';
import 'package:get/get.dart';

class FalconOneDemoApp extends StatelessWidget {
  const FalconOneDemoApp({super.key});

  // This widget is the root of your application.
  @override
  Widget build(BuildContext context) {
    return GetMaterialApp(
      navigatorKey: Get.key,
      scaffoldMessengerKey: AppUiKeys.scaffoldMessenger,
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
        GetPage(name: '/', page: () => const MainScreen(), binding: MapBinding()),
        GetPage(name: '/w1', page: () => const W1DebugScreen()),
      ],
    );
  }
}
