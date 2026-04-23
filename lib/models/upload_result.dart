import 'dart:convert';

/// Result of POST [https://nexus.aeriaone.com/api/incidents/upload/].
class UploadResult {
  const UploadResult({
    required this.isSuccess,
    this.id,
    this.status,
    this.httpStatus,
    this.errorMessage,
    this.rawBody,
  });

  final bool isSuccess;
  final String? id;
  final String? status;
  final int? httpStatus;
  final String? errorMessage;
  final String? rawBody;

  factory UploadResult.success({
    required int httpStatus,
    required String id,
    required String status,
    String? rawBody,
  }) {
    return UploadResult(
      isSuccess: true,
      httpStatus: httpStatus,
      id: id,
      status: status,
      rawBody: rawBody,
    );
  }

  factory UploadResult.failure({
    required int? httpStatus,
    required String message,
    String? rawBody,
  }) {
    return UploadResult(
      isSuccess: false,
      httpStatus: httpStatus,
      errorMessage: message,
      rawBody: rawBody,
    );
  }

  static UploadResult fromHttpResponse(int statusCode, String body) {
    if (statusCode == 200 || statusCode == 201) {
      try {
        final map = jsonDecode(body) as Map<String, dynamic>?;
        if (map != null) {
          final id = map['id']?.toString();
          final st = map['status']?.toString();
          if (id != null && id.isNotEmpty) {
            return UploadResult.success(
              httpStatus: statusCode,
              id: id,
              status: st ?? 'UNKNOWN',
              rawBody: body,
            );
          }
        }
      } catch (_) {}
      return UploadResult.failure(
        httpStatus: statusCode,
        message: 'Unexpected success body',
        rawBody: body,
      );
    }
    return UploadResult.failure(
      httpStatus: statusCode,
      message: body.isNotEmpty ? body : 'HTTP $statusCode',
      rawBody: body,
    );
  }
}
