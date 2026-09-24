import 'package:flutter/material.dart';
import 'package:pixiv_shaft/src/rust/api/illust.dart';
import 'package:pixiv_shaft/src/ui/widgets/rust_image.dart';

/// 作品列表里的一张卡片。
class IllustCard extends StatelessWidget {
  const IllustCard({super.key, required this.illust, this.onTap});

  final IllustSummary illust;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return Card(
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Expanded(
              child: Stack(
                fit: StackFit.expand,
                children: [
                  RustImage(url: illust.imageUrl),
                  if (illust.pageCount > 1)
                    Positioned(
                      right: 6,
                      top: 6,
                      child: _Badge(label: '${illust.pageCount} 页'),
                    ),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(10, 8, 10, 10),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    illust.title,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: Theme.of(context).textTheme.bodyMedium,
                  ),
                  if (illust.authorName.isNotEmpty) ...[
                    const SizedBox(height: 2),
                    Text(
                      illust.authorName,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                            color: Theme.of(context).colorScheme.outline,
                          ),
                    ),
                  ],
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _Badge extends StatelessWidget {
  const _Badge({required this.label});

  final String label;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: Colors.black.withValues(alpha: 0.6),
        borderRadius: BorderRadius.circular(4),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
        child: Text(
          label,
          style: const TextStyle(color: Colors.white, fontSize: 11),
        ),
      ),
    );
  }
}

/// 作品网格。列表为空或出错时给出对应提示。
class IllustGrid extends StatelessWidget {
  const IllustGrid({
    super.key,
    required this.illusts,
    required this.onOpen,
    this.emptyLabel = '没有内容',
  });

  final List<IllustSummary> illusts;
  final void Function(IllustSummary illust) onOpen;
  final String emptyLabel;

  @override
  Widget build(BuildContext context) {
    if (illusts.isEmpty) {
      return Center(child: Text(emptyLabel));
    }
    return GridView.builder(
      padding: const EdgeInsets.all(12),
      gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
        maxCrossAxisExtent: 240,
        crossAxisSpacing: 12,
        mainAxisSpacing: 12,
        childAspectRatio: 0.68,
      ),
      itemCount: illusts.length,
      itemBuilder: (context, index) => IllustCard(
        illust: illusts[index],
        onTap: () => onOpen(illusts[index]),
      ),
    );
  }
}

/// 拉取中、出错、成功三种状态的通用外壳。
class AsyncSection<T> extends StatelessWidget {
  const AsyncSection({
    super.key,
    required this.future,
    required this.builder,
    required this.onRetry,
    this.errorLabel = '加载失败',
  });

  final Future<T> future;
  final Widget Function(BuildContext context, T data) builder;
  final VoidCallback onRetry;
  final String errorLabel;

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<T>(
      future: future,
      builder: (context, snapshot) {
        if (snapshot.connectionState != ConnectionState.done) {
          return const Center(child: CircularProgressIndicator());
        }
        if (snapshot.hasError) {
          return Center(
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  SelectableText('$errorLabel：${snapshot.error}'),
                  const SizedBox(height: 12),
                  FilledButton(onPressed: onRetry, child: const Text('重试')),
                ],
              ),
            ),
          );
        }
        return builder(context, snapshot.data as T);
      },
    );
  }
}
