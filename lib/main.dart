import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:pixiv_shaft/src/rust/api/image.dart';
import 'package:pixiv_shaft/src/rust/frb_generated.dart';

/// 骨架阶段的验证目标：一张已知可用的作品图片。
/// 接入真实接口后由作品数据提供地址，这里只是为了打通「Rust 取图 → 界面显示」。
const String _probeImageUrl =
    'https://i.pximg.net/c/600x1200_90_webp/img-master/img/2026/03/17/00/37/51/142389693_p0_master1200.jpg';

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
      home: const ImageProbeScreen(),
    );
  }
}

/// 从 Rust 侧取回一张图片并显示，用于验证反墙取图链路端到端可用。
class ImageProbeScreen extends StatefulWidget {
  const ImageProbeScreen({super.key});

  @override
  State<ImageProbeScreen> createState() => _ImageProbeScreenState();
}

class _ImageProbeScreenState extends State<ImageProbeScreen> {
  late Future<Uint8List> _image;

  @override
  void initState() {
    super.initState();
    _image = _fetchAndReport();
  }

  /// 取图并打印结果。构建流水线在正式包里抓取这行输出，
  /// 用来确认关闭沙盒后打包出来的应用确实能联网取图。
  Future<Uint8List> _fetchAndReport() async {
    try {
      final bytes = await fetchImage(url: _probeImageUrl);
      print('[probe] 取回 ${bytes.length} 字节');
      return bytes;
    } catch (error) {
      print('[probe] 取回失败：$error');
      rethrow;
    }
  }

  void _reload() {
    setState(() => _image = _fetchAndReport());
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('PixivShaft'),
        actions: [
          IconButton(
            onPressed: _reload,
            icon: const Icon(Icons.refresh),
            tooltip: '重新取图',
          ),
        ],
      ),
      body: Center(
        child: FutureBuilder<Uint8List>(
          future: _image,
          builder: (context, snapshot) {
            if (snapshot.connectionState != ConnectionState.done) {
              return const CircularProgressIndicator();
            }
            if (snapshot.hasError) {
              return Padding(
                padding: const EdgeInsets.all(24),
                child: SelectableText('取图失败：${snapshot.error}'),
              );
            }
            final bytes = snapshot.data!;
            return Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Flexible(child: Image.memory(bytes)),
                Padding(
                  padding: const EdgeInsets.all(12),
                  child: Text('取回 ${bytes.length} 字节'),
                ),
              ],
            );
          },
        ),
      ),
    );
  }
}
