import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:pixiv_shaft/src/rust/api/image.dart';
import 'package:pixiv_shaft/src/rust/api/illust.dart';

/// 推荐插画列表。图片字节经 Rust 侧的反墙链路取回。
class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  late Future<List<IllustSummary>> _illusts = fetchRecommendedIllusts();

  void _reload() {
    setState(() => _illusts = fetchRecommendedIllusts());
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
            tooltip: '重新加载',
          ),
        ],
      ),
      body: FutureBuilder<List<IllustSummary>>(
        future: _illusts,
        builder: (context, snapshot) {
          if (snapshot.connectionState != ConnectionState.done) {
            return const Center(child: CircularProgressIndicator());
          }
          if (snapshot.hasError) {
            return Center(
              child: Padding(
                padding: const EdgeInsets.all(24),
                child: SelectableText('加载推荐失败：${snapshot.error}'),
              ),
            );
          }
          final illusts = snapshot.data!;
          if (illusts.isEmpty) {
            return const Center(child: Text('没有取到推荐作品'));
          }
          return GridView.builder(
            padding: const EdgeInsets.all(8),
            gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
              maxCrossAxisExtent: 220,
              crossAxisSpacing: 8,
              mainAxisSpacing: 8,
            ),
            itemCount: illusts.length,
            itemBuilder: (context, index) => IllustCard(illust: illusts[index]),
          );
        },
      ),
    );
  }
}

class IllustCard extends StatelessWidget {
  const IllustCard({super.key, required this.illust});

  final IllustSummary illust;

  @override
  Widget build(BuildContext context) {
    return Card(
      clipBehavior: Clip.antiAlias,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Expanded(child: IllustImage(imageUrl: illust.imageUrl)),
          Padding(
            padding: const EdgeInsets.all(8),
            child: Text(
              illust.title,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
            ),
          ),
        ],
      ),
    );
  }
}

/// 单张作品图。字节由 Rust 侧用不发送 SNI 的 TLS 取回。
class IllustImage extends StatefulWidget {
  const IllustImage({super.key, required this.imageUrl});

  final String imageUrl;

  @override
  State<IllustImage> createState() => _IllustImageState();
}

class _IllustImageState extends State<IllustImage> {
  late Future<Uint8List> _bytes = fetchImage(url: widget.imageUrl);

  @override
  void didUpdateWidget(covariant IllustImage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.imageUrl != widget.imageUrl) {
      _bytes = fetchImage(url: widget.imageUrl);
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
            fit: BoxFit.cover,
            width: double.infinity,
          );
        }
        if (snapshot.hasError) {
          return Center(
            child: Padding(
              padding: const EdgeInsets.all(8),
              child: Text(
                '图片加载失败',
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
