import 'package:flutter_test/flutter_test.dart';
import 'package:openparental/api/backend_url_policy.dart';

void main() {
  group('BackendUrlPolicy', () {
    test('normalizes an HTTPS remote server', () {
      expect(
        BackendUrlPolicy.normalize(' https://api.example.com/v1 '),
        'https://api.example.com/v1/',
      );
    });

    test('allows the Android emulator host for local development', () {
      expect(
        BackendUrlPolicy.normalize('http://10.0.2.2:3000'),
        'http://10.0.2.2:3000/',
      );
    });

    test('rejects remote HTTP to protect bearer tokens', () {
      expect(
        () => BackendUrlPolicy.normalize('http://api.example.com'),
        throwsFormatException,
      );
    });

    test('rejects URLs containing credentials, queries, or fragments', () {
      expect(
        () => BackendUrlPolicy.normalize('https://user:pass@api.example.com'),
        throwsFormatException,
      );
      expect(
        () => BackendUrlPolicy.normalize('https://api.example.com?next=x'),
        throwsFormatException,
      );
      expect(
        () => BackendUrlPolicy.normalize('https://api.example.com#fragment'),
        throwsFormatException,
      );
    });

    test('rejects missing schemes and unsupported protocols', () {
      expect(
        () => BackendUrlPolicy.normalize('api.example.com'),
        throwsFormatException,
      );
      expect(
        () => BackendUrlPolicy.normalize('ftp://api.example.com'),
        throwsFormatException,
      );
    });
  });
}
