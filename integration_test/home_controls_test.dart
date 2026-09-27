import 'dart:ui' show PointerDeviceKind;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:pixiv_shaft/src/rust/frb_generated.dart';
import 'package:pixiv_shaft/src/ui/feed_pages.dart';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('首页四个悬浮标签保持紧凑布局并响应选择和顶部悬停', (tester) async {
    await RustLib.init();
    await tester.pumpWidget(const MaterialApp(home: RecommendedPage()));

    final labels = ['推荐', '漫画', '小说', '最新'];
    for (final label in labels) {
      expect(find.text(label), findsOneWidget);
    }

    final first = tester.getRect(find.text(labels.first));
    final last = tester.getRect(find.text(labels.last));
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
}
