
import 'dart:convert';
import 'dart:math';

import 'package:falcon_one_demo/app.dart';
import 'package:falcon_one_demo/data/call_service.dart';
import 'package:falcon_one_demo/mapbox_config.dart';
import 'package:falcon_one_demo/utils/call_foreground_task.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:flutter_foreground_task/flutter_foreground_task.dart';
import 'package:get/get.dart';
import 'package:http/http.dart' as http;
import 'package:mapbox_maps_flutter/mapbox_maps_flutter.dart';
import 'package:permission_handler/permission_handler.dart';

const String _agoraAppId = String.fromEnvironment(
  'AGORA_APP_ID',
  defaultValue: '20f398f58c6541d881d050d4b6955d9b',
);
const String _agoraChannelId = String.fromEnvironment(
  'AGORA_CHANNEL',
  defaultValue: 'falcon_group_channel',
);
const String _agoraTokenServer = String.fromEnvironment(
  'AGORA_TOKEN_SERVER',
  defaultValue: 'https://agora-token-service-production-ba0dd.up.railway.app',
);

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  FlutterForegroundTask.initCommunicationPort();

  try {
    await dotenv.load(fileName: 'assets/mapbox.env');
  } catch (e, st) {
    debugPrint('Could not load assets/mapbox.env ($e). Using dart-define only.');
    debugPrint('$st');
  }

  await SystemChrome.setPreferredOrientations([
    DeviceOrientation.portraitUp,
    DeviceOrientation.portraitDown,
  ]);

  await CallForegroundTaskManager.ensureInitialized();

  await initializeServices();

  final audioOk = await initializeAudio();
  if (!audioOk) {
    debugPrint(
      'Audio / Agora did not start (mic, network, or token server). '
      'Map and W1 screens still work; grant mic permission and check network to enable voice.',
    );
  }

  runApp(WithForegroundTask(child: const FalconOneDemoApp()));
}

/// Mapbox token: `--dart-define=MAPBOX_ACCESS_TOKEN=...` wins over [assets/mapbox.env].
Future<void> initializeServices() async {
  const fromDefine = String.fromEnvironment('MAPBOX_ACCESS_TOKEN');
  final fromFile = dotenv.env['MAPBOX_ACCESS_TOKEN']?.trim() ?? '';
  final token = fromDefine.isNotEmpty ? fromDefine : fromFile;

  mapboxAccessTokenConfigured = token.isNotEmpty;
  if (!mapboxAccessTokenConfigured) {
    debugPrint(
      'Mapbox: no token. Set MAPBOX_ACCESS_TOKEN in assets/mapbox.env on this machine, '
      'or run: flutter run --dart-define=MAPBOX_ACCESS_TOKEN=pk.your_public_token',
    );
  } else {
    MapboxOptions.setAccessToken(token);
  }

  await Permission.locationWhenInUse.request();
}

Future<bool> initializeAudio() async {
  debugPrint('Requesting microphone permission...');
  final microphoneStatus = await Permission.microphone.request();

  if (!microphoneStatus.isGranted) {
    debugPrint('Microphone permission not granted; audio call disabled.');
    return false;
  }

  // Must run after other runtime permission dialogs finish so Android does not
  // reject with "Can request only one set of permissions at a time".
  await CallForegroundTaskManager.ensureNotificationPermissions();

  if (_agoraAppId.isEmpty) {
    debugPrint('AGORA_APP_ID is not set; audio call service not started.');
    return false;
  }

  final localUid = _generateLocalUid();

  final token = await _fetchAgoraToken(
    baseUrl: _agoraTokenServer,
    channelId: _agoraChannelId,
    uid: localUid,
  );

  final config = AgoraCallConfig(
    appId: _agoraAppId,
    channelId: _agoraChannelId,
    token: token,
    localUid: localUid,
  );

  debugPrint(
    'Starting CallService with config: '
    'appId=${config.appId}, channelId=${config.channelId}, '
    'token=${config.token != null ? '[REDACTED]' : 'null'}',
  );
  try {
    await Get.putAsync<CallService>(
      () async => CallService(config: config).init(),
      permanent: true,
    );
  } catch (error, stackTrace) {
    debugPrint('Failed to start CallService: $error');
    debugPrint('$stackTrace');
    return false;
  }

  return true;
}

Future<String?> _fetchAgoraToken({
  required String baseUrl,
  required String channelId,
  required int uid,
}) async {
  final uri = Uri.parse(baseUrl).resolve('getToken');

  try {
    final response = await http.post(
      uri,
      headers: const {
        'Content-Type': 'application/json',
        'Accept': 'application/json',
      },
      body: jsonEncode(<String, dynamic>{
        'tokenType': 'rtc',
        'channel': channelId,
        'role': 'publisher',
        'uid': uid.toString(),
        // Allow default expiration on the service (1 hour) which is fine for sessions.
      }),
    );
    if (response.statusCode != 200) {
      debugPrint(
        'Agora token server error: HTTP ${response.statusCode} ${response.reasonPhrase}',
      );
      return null;
    }

    final payload = jsonDecode(response.body) as Map<String, dynamic>;
    final token = payload['rtcToken'] as String? ?? payload['token'] as String?;

    if (token == null || token.isEmpty) {
      debugPrint('Agora token server returned empty token payload');
      return null;
    }

    return token;
  } catch (error, stackTrace) {
    debugPrint('Failed to fetch Agora token: $error');
    debugPrint('$stackTrace');
    return null;
  }
}

int _generateLocalUid() {
  // Generate a stable-ish random UID within the valid 32-bit range expected by Agora.
  final rng = Random();
  return rng.nextInt(0x7FFFFFFF);
}
