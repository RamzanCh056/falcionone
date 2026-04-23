import 'dart:convert';
import 'dart:io';

import 'package:falcon_one_demo/models/upload_result.dart';
import 'package:http/http.dart' as http;

/// Multipart upload to Nexus incidents API.
class UploadService {
  UploadService({
    this.baseUrl = 'https://nexus.aeriaone.com/api/incidents/upload/',
    this.officerCode = 'off-001',
  });

  final String baseUrl;
  final String officerCode;

  /// Uploads [file] with [metadata] map JSON-encoded as `raw_metadata`.
  Future<UploadResult> uploadVideo(File file, Map<String, dynamic> metadata) async {
    if (!await file.exists()) {
      return UploadResult.failure(
        httpStatus: null,
        message: 'File does not exist',
      );
    }

    final uri = Uri.parse(baseUrl);
    final request = http.MultipartRequest('POST', uri);
    request.fields['officer_code'] = officerCode;
    request.fields['raw_metadata'] = jsonEncode(metadata);
    request.files.add(
      await http.MultipartFile.fromPath(
        'video_file',
        file.path,
        filename: file.uri.pathSegments.isNotEmpty ? file.uri.pathSegments.last : 'video.mp4',
      ),
    );

    final client = http.Client();
    try {
      final streamed = await client.send(request);
      final response = await http.Response.fromStream(streamed);
      return UploadResult.fromHttpResponse(response.statusCode, response.body);
    } on SocketException catch (e) {
      return UploadResult.failure(httpStatus: null, message: e.message);
    } on HttpException catch (e) {
      return UploadResult.failure(httpStatus: null, message: e.message);
    } catch (e) {
      return UploadResult.failure(httpStatus: null, message: e.toString());
    } finally {
      client.close();
    }
  }
}
