import 'dart:io';

import 'package:flutter/services.dart';

/// Native BleeqUp SDK bridge (Android). W1 must not use this channel.
///
/// Method channel: `bleequp_sdk` — Flutter → native commands.
/// Event channel: `bleequp_sdk/events` — native → Flutter (`event` + payload keys).
class BleeqUpBridge {
  BleeqUpBridge._();

  static const MethodChannel methodChannel = MethodChannel('bleequp_sdk');
  static const EventChannel eventChannel = EventChannel('bleequp_sdk/events');

  static bool get isSupported => Platform.isAndroid;

  static Stream<dynamic> events() {
    if (!isSupported) {
      return const Stream<dynamic>.empty();
    }
    return eventChannel.receiveBroadcastStream();
  }

  static Future<T?> invoke<T>(String method, [Object? arguments]) async {
    if (!isSupported) {
      throw UnsupportedError('BleeqUp SDK bridge is only available on Android.');
    }
    return methodChannel.invokeMethod<T>(method, arguments);
  }
}
