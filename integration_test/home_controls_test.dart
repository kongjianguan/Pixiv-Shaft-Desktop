import 'dart:ui' show PointerDeviceKind;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:macos_window_utils/macos_window_utils.dart';
import 'package:pixiv_shaft/src/rust/frb_generated.dart';
import 'package:pixiv_shaft/src/ui/feed_pages.dart';
import 'package:pixiv_shaft/src/ui/login_screen.dart';
import 'package:pixiv_shaft/src/ui/window_chrome.dart';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  setUpAll(() async {
    await WindowManipulator.initialize();
    await RustLib.init();
  });

  testWidgets('首页四个悬浮标签保持紧凑布局并响应选择和顶部悬停', (tester) async {
    final titlebarHeight = await WindowManipulator.getTitlebarHeight();
    expect(titlebarHeight, greaterThan(0));
    await tester.pumpWidget(MaterialApp(
      builder: (context, child) => WindowChrome(
        initialTitlebarHeight: titlebarHeight,
        child: child!,
      ),
      home: const RecommendedPage(active: true),
    ));

    final labels = ['推荐', '漫画', '小说', '最新'];
    for (final label in labels) {
      expect(find.text(label), findsOneWidget);
    }

    final first = tester.getRect(find.text(labels.first));
    final last = tester.getRect(find.text(labels.last));
    expect(first.top, lessThan(titlebarHeight));
    expect(last.right - first.left, lessThan(260));
    expect((first.left + last.right) / 2,
        closeTo(tester.getSize(find.byType(RecommendedPage)).width / 2, 2));

    for (final label in labels.skip(1)) {
      await tester.tap(find.text(label));
      await tester.pump(const Duration(milliseconds: 350));
      final selectedColor =
          Theme.of(tester.element(find.text(label))).colorScheme.primaryContainer;
      expect(
        find.ancestor(
          of: find.text(label),
          matching: find.byWidgetPredicate(
            (widget) => widget is Material && widget.color == selectedColor,
          ),
        ),
        findsOneWidget,
      );
    }

    final mouse = await tester.createGesture(kind: PointerDeviceKind.mouse);
    await mouse.addPointer(location: tester.getCenter(find.text('最新')));
    await mouse.moveTo(const Offset(500, 400));
    await tester.pump(const Duration(milliseconds: 120));
    await tester.pump(const Duration(milliseconds: 300));
    final opacity = find.ancestor(
      of: find.text('最新'),
      matching: find.byType(AnimatedOpacity),
    );
    expect(tester.widget<AnimatedOpacity>(opacity).opacity, 0);

    await mouse.moveTo(const Offset(400, 1));
    await tester.pump(const Duration(milliseconds: 300));
    expect(tester.widget<AnimatedOpacity>(opacity).opacity, 1);
    await mouse.removePointer();
  });

  testWidgets('登录页标题位于原生窗口按钮下方', (tester) async {
    final titlebarHeight = await WindowManipulator.getTitlebarHeight();
    await tester.pumpWidget(MaterialApp(
      builder: (context, child) => WindowChrome(
        initialTitlebarHeight: titlebarHeight,
        child: child!,
      ),
      home: const LoginScreen(),
    ));
    await tester.pump();
    expect(tester.getRect(find.text('PixivShaft')).top,
        greaterThanOrEqualTo(titlebarHeight));
  });
}
