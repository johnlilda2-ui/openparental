class BackendUrlPolicy {
  static const _localDevelopmentHosts = <String>{
    '10.0.2.2', // Android emulator -> host machine
    'localhost',
    '127.0.0.1',
  };

  /// Requires HTTPS remotely. Plain HTTP is allowed only for local development.
  static String normalize(String candidate) {
    final raw = candidate.trim();
    final uri = Uri.tryParse(raw);
    if (uri == null || !uri.isAbsolute || !uri.hasAuthority || uri.host.isEmpty) {
      throw const FormatException(
        'Enter a valid backend URL, including https://',
      );
    }
    if (uri.userInfo.isNotEmpty) {
      throw const FormatException(
        'Backend URL must not contain a username or password',
      );
    }
    if (uri.hasQuery || uri.hasFragment) {
      throw const FormatException(
        'Backend URL must not contain a query string or fragment',
      );
    }
    final localHttp =
        uri.scheme == 'http' && _localDevelopmentHosts.contains(uri.host);
    if (uri.scheme != 'https' && !localHttp) {
      throw const FormatException(
        'HTTPS is required for remote backends. HTTP is allowed only for localhost or the Android emulator host 10.0.2.2.',
      );
    }

    final path = uri.path.endsWith('/') ? uri.path : '${uri.path}/';
    return uri.replace(path: path).toString();
  }
}
