import 'package:flutter/material.dart';
import 'package:pixiv_shaft/src/rust/api/illust.dart';
import 'package:pixiv_shaft/src/rust/api/store.dart';
import 'package:pixiv_shaft/src/ui/widgets/illust_card.dart';
import 'package:pixiv_shaft/src/ui/widgets/rust_image.dart';

/// 插画详情：多页浏览、缩放、作品信息与相关作品。
class IllustDetailPage extends StatefulWidget {
  const IllustDetailPage({super.key, required this.illustId, this.initialTitle});

  final int illustId;
  final String? initialTitle;

  @override
  State<IllustDetailPage> createState() => _IllustDetailPageState();
}

class _IllustDetailPageState extends State<IllustDetailPage> {
  late final Future<IllustDetail> _detail = _load();
  final PageController _pages = PageController();
  int _currentPage = 0;

  Future<IllustDetail> _load() async {
    final detail = await fetchIllustDetail(illustId: widget.illustId);
    // 是否记录浏览由设置决定，与现有版本一致。
    if (await setting(key: 'saveBrowseHistory') == 'true') {
      await recordBrowse(
        contentType: 'illust',
        targetId: detail.id,
        payloadJson: '{"id":${detail.id},"title":${_jsonString(detail.title)}}',
      );
    }
    return detail;
  }

  @override
  void dispose() {
    _pages.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(widget.initialTitle ?? '作品详情')),
      body: AsyncSection<IllustDetail>(
        future: _detail,
        onRetry: () => setState(() {}),
        errorLabel: '加载详情失败',
        builder: (context, detail) => _content(context, detail),
      ),
    );
  }

  Widget _content(BuildContext context, IllustDetail detail) {
    return Column(
      children: [
        Expanded(
          child: Stack(
            children: [
              // 横向翻页交给 Flutter 的原生滚动物理处理触控板手势。
              PageView.builder(
                controller: _pages,
                itemCount: detail.imageUrls.length,
                onPageChanged: (index) => setState(() => _currentPage = index),
                itemBuilder: (context, index) => InteractiveViewer(
                  maxScale: 6,
                  child: RustImage(url: detail.imageUrls[index], fit: BoxFit.contain),
                ),
              ),
              if (detail.imageUrls.length > 1)
                Positioned(
                  right: 16,
                  bottom: 16,
                  child: DecoratedBox(
                    decoration: BoxDecoration(
                      color: Colors.black.withValues(alpha: 0.6),
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                      child: Text(
                        '${_currentPage + 1} / ${detail.imageUrls.length}',
                        style: const TextStyle(color: Colors.white, fontSize: 12),
                      ),
                    ),
                  ),
                ),
            ],
          ),
        ),
        SizedBox(
          height: 240,
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(detail.title, style: Theme.of(context).textTheme.titleMedium),
                const SizedBox(height: 4),
                Text(
                  detail.authorName,
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        color: Theme.of(context).colorScheme.outline,
                      ),
                ),
                const SizedBox(height: 12),
                Wrap(
                  spacing: 8,
                  runSpacing: 4,
                  children: [
                    _Meta(label: '${detail.width}×${detail.height}'),
                    _Meta(label: '收藏 ${detail.totalBookmarks}'),
                    _Meta(label: '浏览 ${detail.totalView}'),
                    if (detail.isBookmarked) const _Meta(label: '已收藏'),
                  ],
                ),
                if (detail.tags.isNotEmpty) ...[
                  const SizedBox(height: 12),
                  Wrap(
                    spacing: 6,
                    runSpacing: 6,
                    children: [
                      for (final tag in detail.tags)
                        Chip(
                          label: Text(tag),
                          visualDensity: VisualDensity.compact,
                          materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                        ),
                    ],
                  ),
                ],
                if (detail.caption.isNotEmpty) ...[
                  const SizedBox(height: 12),
                  SelectableText(_stripHtml(detail.caption)),
                ],
              ],
            ),
          ),
        ),
      ],
    );
  }
}

/// 作品简介在接口里是 HTML，这里先去掉标签留下可读文本。
String _stripHtml(String html) {
  final withoutTags = html.replaceAll(RegExp(r'<[^>]*>'), ' ');
  return withoutTags
      .replaceAll('&amp;', '&')
      .replaceAll('&lt;', '<')
      .replaceAll('&gt;', '>')
      .replaceAll('&quot;', '"')
      .replaceAll('&#39;', "'")
      .replaceAll(RegExp(r'\s+'), ' ')
      .trim();
}

String _jsonString(String value) {
  final escaped = value
      .replaceAll(r'\', r'\\')
      .replaceAll('"', r'\"')
      .replaceAll('\n', r'\n');
  return '"$escaped"';
}

class _Meta extends StatelessWidget {
  const _Meta({required this.label});

  final String label;

  @override
  Widget build(BuildContext context) {
    return Text(label, style: Theme.of(context).textTheme.bodySmall);
  }
}
