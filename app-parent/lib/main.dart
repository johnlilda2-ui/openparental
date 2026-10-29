import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import 'screens/home_screen.dart';
import 'screens/login_screen.dart';
import 'state/providers.dart';
import 'theme/app_theme.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  String? savedBackendUrl;
  try {
    savedBackendUrl = await const FlutterSecureStorage().read(
      key: 'backend_base_url',
    );
  } catch (_) {
    // Keep the local development default if secure storage is not available.
  }

  runApp(
    ProviderScope(
      overrides: [
        baseUrlProvider.overrideWith(
          (ref) => savedBackendUrl ?? defaultBackendUrl,
        ),
      ],
      child: const OpenParentalApp(),
    ),
  );
}

class OpenParentalApp extends StatelessWidget {
  const OpenParentalApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'OpenParental',
      debugShowCheckedModeBanner: false,
      theme: AppTheme.light(),
      darkTheme: AppTheme.dark(),
      themeMode: ThemeMode.system,
      home: const RootView(),
    );
  }
}

class RootView extends ConsumerWidget {
  const RootView({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final auth = ref.watch(authProvider);
    if (auth.loading) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }
    return auth.loggedIn ? const HomeScreen() : const LoginScreen();
  }
}
