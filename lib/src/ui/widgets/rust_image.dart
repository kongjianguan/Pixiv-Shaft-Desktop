import 'dart:async';
import 'dart:ui' as ui;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:pixiv_shaft/src/rust/api/image.dart';

/// 图片地址作为缓存键，同一张图片在列表和详情之间可以复用 Flutter 的图片缓存。
@immutable
class RustImageProvider extends ImageProvider<RustImageProvider> {
  const RustImageProvider(this.url);

  final String url;

  @override
  Future<RustImageProvider> obtainKey(ImageConfiguration configuration) =>
      SynchronousFuture<RustImageProvider>(this);

  @override
  ImageStreamCompleter loadImage(
    RustImageProvider key,
    ImageDecoderCallback decode,
  ) {
    return MultiFrameImageStreamCompleter(
      codec: _load(key.url, decode),
      scale: 1,
      debugLabel: key.url,
    );
  }

  Future<ui.Codec> _load(String url, ImageDecoderCallback decode) async {
    try {
      if (url.isEmpty) throw StateError('图片地址为空');
      final bytes = await fetchImage(url: url);
      if (bytes.isEmpty) throw StateError('图片内容为空：$url');
      return decode(await ui.ImmutableBuffer.fromUint8List(bytes));
    } catch (_) {
      scheduleMicrotask(() => PaintingBinding.instance.imageCache.evict(this));
      rethrow;
    }
  }

  @override
  bool operator ==(Object other) =>
      other is RustImageProvider && other.url == url;

  @override
  int get hashCode => url.hashCode;
}

/// 经 Rust 侧图片链路取回并由 Flutter 解码显示。
class RustImage extends StatelessWidget {
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
  Widget build(BuildContext context) {
    return Image(
      image: RustImageProvider(url),
      fit: fit,
      width: double.infinity,
      height: double.infinity,
      gaplessPlayback: true,
      frameBuilder: (context, child, frame, synchronouslyLoaded) {
        if (frame != null || synchronouslyLoaded) return child;
        return const Center(child: CircularProgressIndicator());
      },
      errorBuilder: (context, error, stackTrace) {
        debugPrint('图片加载失败 $url：$error');
        return Center(
          child: Tooltip(
            message: '$error',
            child: Padding(
              padding: const EdgeInsets.all(8),
              child: Text(
                errorLabel.isEmpty ? '◻' : errorLabel,
                textAlign: TextAlign.center,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ),
          ),
        );
      },
    );
  }
}
