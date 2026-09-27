import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:pixiv_shaft/src/rust/frb_generated.dart';
import 'package:pixiv_shaft/src/ui/widgets/rust_image.dart';

const imageUrl =
    'https://i.pximg.net/c/600x1200_90_webp/img-master/img/2026/03/17/00/37/51/142389693_p0_master1200.jpg';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('真实图片经过 Rust 请求并由 macOS Flutter 显示', (tester) async {
    await RustLib.init();
    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: SizedBox(
            width: 300,
            height: 300,
            child: RustImage(url: imageUrl),
          ),
        ),
      ),
    );

    final loaded = await tester.runAsync(() async {
      final context = tester.element(find.byType(RustImage));
      await precacheImage(const RustImageProvider(imageUrl), context);
      return true;
    });

    expect(loaded, isTrue);
    await tester.pumpAndSettle();
    expect(find.text('图片加载失败'), findsNothing);
    expect(find.byType(Image), findsOneWidget);
  });
}
