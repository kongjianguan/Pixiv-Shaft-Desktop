import 'package:flutter/material.dart';
import 'package:macos_window_utils/macos_window_utils.dart';
import 'package:pixiv_shaft/src/rust/api/auth.dart';
import 'package:pixiv_shaft/src/rust/frb_generated.dart';
import 'package:pixiv_shaft/src/settings/app_settings.dart';
import 'package:pixiv_shaft/src/ui/home_shell.dart';
import 'package:pixiv_shaft/src/ui/login_screen.dart';
import 'package:pixiv_shaft/src/ui/window_chrome.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  PaintingBinding.instance.imageCache.maximumSizeBytes = 128 * 1024 * 1024;
  await WindowManipulator.initialize();
  final titlebarHeight = await WindowManipulator.getTitlebarHeight();
  await RustLib.init();
  // 主题与布局参数取自数据库，启动时读一次。
  await AppSettings.instance.load();
  runApp(PixivShaftApp(initialTitlebarHeight: titlebarHeight));
}

class PixivShaftApp extends StatelessWidget {
  const PixivShaftApp({super.key, required this.initialTitlebarHeight});

  final double initialTitlebarHeight;

  @override
  Widget build(BuildContext context) {
    final settings = AppSettings.instance;
    return AnimatedBuilder(
      animation: settings,
      builder: (context, _) => MaterialApp(
        title: 'PixivShaft',
        theme: settings.theme(Brightness.light),
        darkTheme: settings.theme(Brightness.dark),
        themeMode: settings.themeMode,
        builder: (context, child) => WindowChrome(
          initialTitlebarHeight: initialTitlebarHeight,
          child: child!,
        ),
        home: const AuthGate(),
      ),
    );
  }
}

/// 启动时按登录态决定进入登录页还是主界面。
/// 登录态来自钥匙串，因此重启后不需要重新授权。
class AuthGate extends StatefulWidget {
  const AuthGate({super.key});

  @override
  State<AuthGate> createState() => _AuthGateState();
}

class _AuthGateState extends State<AuthGate> {
  late final Future<bool> _loggedIn = isLoggedIn();

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<bool>(
      future: _loggedIn,
      builder: (context, snapshot) {
        if (snapshot.connectionState != ConnectionState.done) {
          return const Scaffold(
            body: Center(child: CircularProgressIndicator()),
          );
        }
        if (snapshot.data == true) {
          return const HomeShell();
        }
        return const LoginScreen();
      },
    );
  }
}
