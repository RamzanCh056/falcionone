/// One row from W1 NanoHTTPD `GET /recordings` or `GET /recordings/latest`.
class W1Recording {
  const W1Recording({
    required this.filename,
    required this.sizeMb,
    required this.createdAt,
    required this.url,
  });

  final String filename;
  final String sizeMb;
  final DateTime? createdAt;

  /// Relative path from device base URL, e.g. `/recordings/REC_....mp4`.
  final String url;

  factory W1Recording.fromJson(Map<String, dynamic> json) {
    final filename = _readString(json, const ['filename', 'name', 'file']);
    final url = _readString(json, const ['url', 'path', 'href']);
    final sizeMb = _readString(json, const ['size_mb', 'sizeMb', 'size']);
    final createdRaw = _readString(json, const ['created_at', 'createdAt', 'timestamp']);
    DateTime? createdAt;
    if (createdRaw.isNotEmpty) {
      createdAt = DateTime.tryParse(createdRaw);
    }
    var fn = filename;
    if (fn.isEmpty && url.isNotEmpty) {
      final i = url.lastIndexOf('/');
      fn = i >= 0 ? url.substring(i + 1) : url;
    }
    var rel = url;
    if (rel.isEmpty && fn.isNotEmpty) {
      rel = '/recordings/$fn';
    }
    return W1Recording(
      filename: fn,
      sizeMb: sizeMb,
      createdAt: createdAt,
      url: rel,
    );
  }

  static String _readString(Map<String, dynamic> json, List<String> keys) {
    for (final k in keys) {
      final v = json[k];
      if (v == null) continue;
      if (v is String) return v.trim();
      return v.toString().trim();
    }
    return '';
  }

  /// Builds a download path segment: prefer [url], else `/recordings/[filename]`.
  String get effectiveDownloadPath {
    if (url.isNotEmpty) {
      return url.startsWith('/') ? url : '/$url';
    }
    if (filename.isEmpty) return '';
    return '/recordings/$filename';
  }
}
