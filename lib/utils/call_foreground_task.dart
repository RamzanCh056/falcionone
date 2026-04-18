import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_foreground_task/flutter_foreground_task.dart';

/// Callback invoked by the native foreground service entrypoint.
@pragma('vm:entry-point')
void callForegroundTaskStartCallback() {
  FlutterForegroundTask.setTaskHandler(_CallForegroundTaskHandler());
}

/// Minimal task handler that keeps the foreground service alive.
class _CallForegroundTaskHandler extends TaskHandler {
  @override
  Future<void> onStart(DateTime timestamp, TaskStarter starter) async {}

  @override
  void onRepeatEvent(DateTime timestamp) {}

  @override
  Future<void> onDestroy(DateTime timestamp, bool isTimeout) async {}
}

class CallForegroundTaskManager {
  static bool _initialized = false;

  /// Ensures the foreground task plugin is configured.
  static Future<void> ensureInitialized() async {
    if (_initialized) {
      return;
    }

    FlutterForegroundTask.init(
      androidNotificationOptions: AndroidNotificationOptions(
        channelId: 'falcon_call_activity',
        channelName: 'Falcon Call Activity',
        channelDescription:
            'Maintains the ongoing Falcon call when the app is backgrounded.',
        channelImportance: NotificationChannelImportance.HIGH,
        priority: NotificationPriority.MAX,
        showWhen: true,
        visibility: NotificationVisibility.VISIBILITY_PUBLIC,
        playSound: false,
      ),
      iosNotificationOptions: const IOSNotificationOptions(
        showNotification: true,
        playSound: false,
      ),
      foregroundTaskOptions: ForegroundTaskOptions(
        eventAction: ForegroundTaskEventAction.repeat(60000),
        allowWakeLock: true,
        allowWifiLock: true,
      ),
    );

    _initialized = true;
  }

  /// Requests the runtime permissions needed to show a foreground notification.
  static Future<void> ensureNotificationPermissions() async {
    final NotificationPermission current =
        await FlutterForegroundTask.checkNotificationPermission();
    if (current == NotificationPermission.granted) {
      return;
    }

    try {
      await FlutterForegroundTask.requestNotificationPermission();
    } on PlatformException catch (error) {
      // Android allows only one permission dialog at a time; overlapping
      // requests or user dismissal surface as cancellation — do not crash.
      if (error.code == 'PermissionRequestCancelledException') {
        debugPrint(
          'CallForegroundTask: notification permission not granted ($error)',
        );
        return;
      }
      rethrow;
    }
  }

  static Future<void> startOrUpdate({required String channelName}) async {
    if ((!Platform.isAndroid && !Platform.isIOS) || kIsWeb) {
      return;
    }

    await ensureInitialized();

    final bool running = await FlutterForegroundTask.isRunningService;
    final String title = 'Falcon call connected';
    final String text = 'Channel: $channelName';

    if (running) {
      await FlutterForegroundTask.updateService(
        notificationTitle: title,
        notificationText: text,
      );
      return;
    }

    await FlutterForegroundTask.startService(
      serviceId: 902,
      notificationTitle: title,
      notificationText: text,
      serviceTypes: const <ForegroundServiceTypes>[
        ForegroundServiceTypes.microphone,
        ForegroundServiceTypes.location,
      ],
      callback: callForegroundTaskStartCallback,
    );
  }

  static Future<void> stop() async {
    if ((!Platform.isAndroid && !Platform.isIOS) || kIsWeb) {
      return;
    }

    if (await FlutterForegroundTask.isRunningService) {
      await FlutterForegroundTask.stopService();
    }
  }
}
