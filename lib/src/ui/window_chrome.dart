import 'package:flutter/material.dart';
import 'package:macos_window_utils/macos_window_utils.dart';

/// 把原生标题栏高度交给 Flutter 的安全区域；窗口尺寸变化后重新读取。
class WindowChrome extends StatefulWidget {
  const WindowChrome({
    super.key,
    required this.initialTitlebarHeight,
    required this.child,
  });

  final double initialTitlebarHeight;
  final Widget child;

  @override
  State<WindowChrome> createState() => _WindowChromeState();
}

class _WindowChromeState extends State<WindowChrome> with WidgetsBindingObserver {
  late double _titlebarHeight;
  int _requestGeneration = 0;

  @override
  void initState() {
    super.initState();
    _titlebarHeight = widget.initialTitlebarHeight;
    WidgetsBinding.instance.addObserver(this);
    WidgetsBinding.instance.addPostFrameCallback((_) => _readTitlebarHeight());
  }

  @override
  void dispose() {
    _requestGeneration++;
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeMetrics() {
    _readTitlebarHeight();
  }

  Future<void> _readTitlebarHeight() async {
    final generation = ++_requestGeneration;
    final height = await WindowManipulator.getTitlebarHeight();
    if (!mounted || generation != _requestGeneration || height == _titlebarHeight) {
      return;
    }
    setState(() => _titlebarHeight = height);
  }

  @override
  Widget build(BuildContext context) {
    final media = MediaQuery.of(context);
    final safeTop = _titlebarHeight > media.padding.top
        ? _titlebarHeight
        : media.padding.top;
    return MediaQuery(
      data: media.copyWith(
        padding: media.padding.copyWith(top: safeTop),
        viewPadding: media.viewPadding.copyWith(top: safeTop),
      ),
      child: widget.child,
    );
  }
}
