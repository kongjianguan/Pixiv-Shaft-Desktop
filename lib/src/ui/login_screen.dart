import 'package:flutter/material.dart';
import 'package:pixiv_shaft/src/rust/api/auth.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:pixiv_shaft/src/ui/home_shell.dart';

/// Pixiv 授权登录。
///
/// 沿用现有版本的真实流程：应用生成授权地址并用系统浏览器打开，用户登录后浏览器
/// 跳回回调地址并在地址里带上 code，用户把地址粘回这里换 token。
class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key});

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  final TextEditingController _controller = TextEditingController();
  AuthSession? _session;
  String? _error;
  bool _exchanging = false;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _startLogin() async {
    final session = await startLogin();
    final uri = Uri.parse(session.authUrl);
    if (await canLaunchUrl(uri)) {
      await launchUrl(uri);
    } else {
      setState(() => _error = '无法打开系统浏览器，请手动复制授权地址');
    }
    setState(() => _session = session);
  }

  Future<void> _submit() async {
    final session = _session;
    if (session == null) {
      setState(() => _error = '请先点击登录');
      return;
    }
    setState(() {
      _exchanging = true;
      _error = null;
    });
    try {
      await completeLogin(
        pasted: _controller.text,
        codeVerifier: session.codeVerifier,
      );
      if (!mounted) return;
      await Navigator.of(context).pushReplacement(
        MaterialPageRoute<void>(builder: (_) => const HomeShell()),
      );
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _exchanging = false;
        _error = '换取 token 失败：$error';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('PixivShaft')),
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 520),
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                FilledButton(
                  onPressed: _startLogin,
                  child: const Text('使用 Pixiv 登录'),
                ),
                if (_session != null) ...[
                  const SizedBox(height: 16),
                  const Text('已打开浏览器。登录后把回调地址粘贴到下面：'),
                  const SizedBox(height: 8),
                  TextField(
                    controller: _controller,
                    decoration: const InputDecoration(
                      labelText: '回调地址或授权码',
                      border: OutlineInputBorder(),
                    ),
                  ),
                  const SizedBox(height: 12),
                  FilledButton(
                    onPressed: _exchanging ? null : _submit,
                    child: Text(_exchanging ? '正在换取 token…' : '提交'),
                  ),
                ],
                if (_error != null) ...[
                  const SizedBox(height: 16),
                  SelectableText(
                    _error!,
                    style: TextStyle(color: Theme.of(context).colorScheme.error),
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}
