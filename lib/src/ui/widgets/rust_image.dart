import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:pixiv_shaft/src/rust/api/image.dart';

/// 经 Rust 侧反墙链路取回的图片。
///
/// 图片域名的地址是固定的，TLS 不发送 SNI，都由 Rust 侧处理，
/// 界面层只负责把字节显示出来。
class RustImage extends StatefulWidget {
  const RustImage({
    super.key,
    required this.url,
    this.fit = BoxFit.cover,
    this.errorLabel = '图片加载失败',
  });

  final String url;
  final BoxFit fit;
  final String errorLabel;

  @override
  State<RustImage> createState() => _RustImageState();
}

class _RustImageState extends State<RustImage> {
  late Future<Uint8List> _bytes = fetchImage(url: widget.url);

  @override
  void didUpdateWidget(covariant RustImage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.url != widget.url) {
      _bytes = fetchImage(url: widget.url);
    }
  }

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<Uint8List>(
      future: _bytes,
      builder: (context, snapshot) {
        if (snapshot.hasData) {
          return Image.memory(
            snapshot.data!,
            fit: widget.fit,
            width: double.infinity,
            height: double.infinity,
            gaplessPlayback: true,
          );
        }
        if (snapshot.hasError) {
          return Center(
            child: Padding(
              padding: const EdgeInsets.all(8),
              child: Text(
                widget.errorLabel,
                textAlign: TextAlign.center,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ),
          );
        }
        return const Center(child: CircularProgressIndicator());
      },
    );
  }
}
