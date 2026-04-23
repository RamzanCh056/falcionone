import 'package:flutter/material.dart';

/// Root [ScaffoldMessenger] for toasts that must work after system UI (file picker, etc.).
/// [Get.snackbar] is not used — it relies on GetX overlay and breaks under some wrappers.
abstract final class AppUiKeys {
  static final GlobalKey<ScaffoldMessengerState> scaffoldMessenger =
      GlobalKey<ScaffoldMessengerState>();
}
