import 'package:flutter/material.dart';
import 'package:flutter_staggered_grid_view/flutter_staggered_grid_view.dart';
import 'package:pixiv_shaft/src/rust/api/illust.dart';
import 'package:pixiv_shaft/src/settings/app_settings.dart';
import 'package:pixiv_shaft/src/ui/widgets/layout.dart';
import 'package:pixiv_shaft/src/ui/widgets/rust_image.dart';

/// 作品列表里的一张卡片。
class IllustCard extends StatelessWidget {
  const IllustCard({
    super.key,
    required this.illust,
    this.onTap,
    this.titleMaxLines = 1,
  });

  final IllustSummary illust;
  final VoidCallback? onTap;
  final int titleMaxLines;

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: EdgeInsets.zero,
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            AspectRatio(
              aspectRatio: illust.width > 0 && illust.height > 0
                  ? illust.width / illust.height
                  : 1,
              child: Stack(
                fit: StackFit.expand,
                children: [
                  RustImage(url: illust.imageUrl),
                  if (illust.pageCount > 1)
                    Positioned(
                      right: 8,
                      top: 8,
                      child: _Badge(label: '${illust.pageCount}P'),
                    ),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.all(8),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    illust.title,
                    maxLines: titleMaxLines,
                    overflow: TextOverflow.ellipsis,
                    style: Theme.of(context).textTheme.bodyMedium,
                  ),
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          illust.authorName,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                      ),
                      Text(
                        '♥ ${illust.totalBookmarks}',
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                    ],
                  ),
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
///
/// 列宽与列数取自设置，改设置后立即重排。
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
    final settings = AppSettings.instance;
    return AnimatedBuilder(
      animation: settings,
      builder: (context, _) => LayoutBuilder(
        builder: (context, constraints) => MasonryGridView.builder(
          padding: const EdgeInsets.all(4),
          gridDelegate: SliverSimpleGridDelegateWithFixedCrossAxisCount(
            crossAxisCount: responsiveColumns(
              constraints.maxWidth,
              settings.workMaxColumnWidth,
              settings.workMaxColumns,
            ),
          ),
          mainAxisSpacing: 4,
          crossAxisSpacing: 4,
          itemCount: illusts.length,
          itemBuilder: (context, index) => IllustCard(
            illust: illusts[index],
            titleMaxLines: settings.workTitleMaxLines,
            onTap: () => onOpen(illusts[index]),
          ),
        ),
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
