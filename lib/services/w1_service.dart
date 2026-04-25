import 'dart:convert';
import 'dart:io';

import 'package:falcon_one_demo/models/w1_recording.dart';
import 'package:get/get.dart';
import 'package:http/http.dart' as http;
import 'package:path_provider/path_provider.dart';

/// HTTP client for W1 bodycam file server (NanoHTTPD on device Wi‑Fi).
class W1Service extends GetxService {
  final RxnString baseUrl = RxnString();

  /// Sets `http://<ip>:<port>` (no trailing slash).
  void setBaseUrl(String ip, int port) {
    final host = ip.trim();
    if (host.isEmpty) return;
    final safePort = port <= 0 || port > 65535 ? 8080 : port;
    baseUrl.value = 'http://$host:$safePort';
  }

  void clearBaseUrl() {
    baseUrl.value = null;
  }

  String? get currentBaseUrl => baseUrl.value;

  Uri _requireBaseUri() {
    final raw = baseUrl.value?.trim();
    if (raw == null || raw.isEmpty) {
      throw StateError('W1 is not connected yet; waiting for Bluetooth STATUS.');
    }
    return Uri.parse(raw);
  }

  Uri _resolvePath(String path) {
    final base = _requireBaseUri();
    final p = path.startsWith('/') ? path : '/$path';
    return base.resolve(p);
  }

  /// `GET /status` — optional health check.
  Future<String> getStatusRaw() async {
    try {
      final uri = _resolvePath('/status');
      final response = await http.get(uri).timeout(const Duration(seconds: 15));
      if (response.statusCode != 200) {
        throw HttpException('HTTP ${response.statusCode}', uri: uri);
      }
      return response.body;
    } on SocketException catch (e) {
      throw W1ClientException('Network error: ${e.message}');
    } on HttpException {
      rethrow;
    } catch (e) {
      throw W1ClientException(e.toString());
    }
  }

  /// `GET /recordings`
  Future<List<W1Recording>> getRecordings() async {
    try {
      final uri = _resolvePath('/recordings');
      final response = await http.get(uri).timeout(const Duration(seconds: 30));
      if (response.statusCode != 200) {
        throw HttpException('List failed HTTP ${response.statusCode}', uri: uri);
      }
      final decoded = jsonDecode(response.body);
      if (decoded is! List) {
        throw const FormatException('Expected JSON array from /recordings');
      }
      return decoded
          .map((e) {
            if (e is! Map) {
              throw FormatException('Invalid recording entry: $e');
            }
            return W1Recording.fromJson(Map<String, dynamic>.from(e));
          })
          .toList(growable: false);
    } on SocketException catch (e) {
      throw W1ClientException('Network error: ${e.message}');
    } on FormatException {
      rethrow;
    } catch (e) {
      if (e is HttpException) rethrow;
      throw W1ClientException(e.toString());
    }
  }

  /// `GET /recordings/latest` — falls back to parsing a one-element array.
  Future<W1Recording?> getLatestRecording() async {
    try {
      final uri = _resolvePath('/recordings/latest');
      final response = await http.get(uri).timeout(const Duration(seconds: 30));
      if (response.statusCode == 404) {
        return null;
      }
      if (response.statusCode != 200) {
        throw HttpException('Latest failed HTTP ${response.statusCode}', uri: uri);
      }
      final body = response.body.trim();
      if (body.isEmpty) return null;

      final decoded = jsonDecode(body);
      if (decoded is Map) {
        return W1Recording.fromJson(Map<String, dynamic>.from(decoded));
      }
      if (decoded is List && decoded.isNotEmpty) {
        final first = decoded.first;
        if (first is Map) {
          return W1Recording.fromJson(Map<String, dynamic>.from(first));
        }
      }
      return null;
    } on SocketException catch (e) {
      throw W1ClientException('Network error: ${e.message}');
    } catch (e) {
      if (e is HttpException || e is FormatException) rethrow;
      throw W1ClientException(e.toString());
    }
  }

  /// `GET /recordings/{filename}` or path from [recording.effectiveDownloadPath].
  Future<File> downloadRecording(W1Recording recording) async {
    try {
      final path = recording.effectiveDownloadPath;
      if (path.isEmpty) {
        throw StateError('Recording has no URL or filename');
      }
      final uri = _resolvePath(path);
      final client = http.Client();
      try {
        final request = http.Request('GET', uri);
        final streamed = await client.send(request).timeout(const Duration(minutes: 10));
        if (streamed.statusCode != 200) {
          throw HttpException('Download failed HTTP ${streamed.statusCode}', uri: uri);
        }

        final dir = await getApplicationDocumentsDirectory();
        final folder = Directory('${dir.path}/w1_recordings');
        await folder.create(recursive: true);

        final safeName = _safeFileName(recording.filename);
        final outFile = File('${folder.path}/$safeName');

        final sink = outFile.openWrite();
        try {
          await for (final chunk in streamed.stream) {
            sink.add(chunk);
          }
          await sink.flush();
        } finally {
          await sink.close();
        }

        if (!await outFile.exists() || await outFile.length() == 0) {
          throw StateError('Downloaded file is missing or empty');
        }
        return outFile;
      } finally {
        client.close();
      }
    } on SocketException catch (e) {
      throw W1ClientException('Network error: ${e.message}');
    } catch (e) {
      if (e is HttpException || e is StateError) rethrow;
      throw W1ClientException(e.toString());
    }
  }

  static String _safeFileName(String name) {
    final base = name.trim().isEmpty ? 'recording_${DateTime.now().millisecondsSinceEpoch}.mp4' : name.trim();
    return base.replaceAll(RegExp(r'[^a-zA-Z0-9._-]'), '_');
  }
}

/// Network / parsing failures from [W1Service].
class W1ClientException implements Exception {
  W1ClientException(this.message);
  final String message;
  @override
  String toString() => message;
}
