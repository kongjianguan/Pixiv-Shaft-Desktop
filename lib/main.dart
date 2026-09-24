import 'package:flutter/material.dart';
import 'package:pixiv_shaft/src/rust/api/auth.dart';
import 'package:pixiv_shaft/src/rust/frb_generated.dart';
import 'package:pixiv_shaft/src/ui/home_shell.dart';
import 'package:pixiv_shaft/src/ui/login_screen.dart';

Future<void> main() async {
  await RustLib.init();
  runApp(const PixivShaftApp());
}

class PixivShaftApp extends StatelessWidget {
  const PixivShaftApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'PixivShaft',
      theme: ThemeData(colorSchemeSeed: Colors.blue, useMaterial3: true),
      home: const AuthGate(),
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
