import 'package:flutter/material.dart';
import 'package:pixiv_shaft/src/rust/api/novel.dart';
import 'package:pixiv_shaft/src/settings/app_settings.dart';
import 'package:pixiv_shaft/src/ui/user_page.dart';
import 'package:pixiv_shaft/src/ui/widgets/illust_card.dart';
import 'package:pixiv_shaft/src/ui/widgets/layout.dart';
import 'package:pixiv_shaft/src/ui/widgets/rust_image.dart';

/// 小说网格。封面、标题、作者与字数。
class NovelGrid extends StatelessWidget {
  const NovelGrid({
    super.key,
    required this.novels,
    required this.onOpen,
    this.emptyLabel = '没有内容',
  });

  final List<NovelSummary> novels;
  final void Function(NovelSummary novel) onOpen;
  final String emptyLabel;

  @override
  Widget build(BuildContext context) {
    if (novels.isEmpty) {
      return Center(child: Text(emptyLabel));
    }
    final settings = AppSettings.instance;
    return AnimatedBuilder(
      animation: settings,
      builder: (context, _) => GridView.builder(
        padding: const EdgeInsets.all(12),
        gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
          crossAxisCount: gridColumns(
            context,
            settings.novelMaxColumnWidth,
            settings.novelMaxColumns,
          ),
          crossAxisSpacing: 12,
          mainAxisSpacing: 12,
          childAspectRatio: 0.62,
        ),
        itemCount: novels.length,
        itemBuilder: (context, index) {
          final novel = novels[index];
          return Card(
            clipBehavior: Clip.antiAlias,
            child: InkWell(
              onTap: () => onOpen(novel),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Expanded(child: RustImage(url: novel.coverUrl)),
                  Padding(
                    padding: const EdgeInsets.fromLTRB(10, 8, 10, 10),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          novel.title,
                          maxLines: settings.novelTitleMaxLines,
                          overflow: TextOverflow.ellipsis,
                          style: Theme.of(context).textTheme.bodyMedium,
                        ),
                        const SizedBox(height: 2),
                        Text(
                          novel.authorName,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: Theme.of(context).textTheme.bodySmall?.copyWith(
                                color: Theme.of(context).colorScheme.outline,
                              ),
                        ),
                        if (novel.textLength > 0)
                          Text(
                            '${novel.textLength} 字',
                            style: Theme.of(context).textTheme.bodySmall,
                          ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
          );
        },
      ),
    );
  }
}

/// 小说详情：信息、系列章节与收藏。
class NovelDetailPage extends StatefulWidget {
  const NovelDetailPage({super.key, required this.novelId, this.initialTitle});

  final int novelId;
  final String? initialTitle;

  @override
  State<NovelDetailPage> createState() => _NovelDetailPageState();
}

class _NovelDetailPageState extends State<NovelDetailPage> {
  late Future<NovelSummary> _novel = fetchNovelDetail(novelId: widget.novelId);
  Future<NovelSeries>? _series;
  bool _bookmarked = false;
  bool _busy = false;

  Future<NovelSummary> _load() async {
    final novel = await fetchNovelDetail(novelId: widget.novelId);
    _bookmarked = novel.isBookmarked;
    if (novel.seriesId != 0) {
      _series = fetchNovelSeries(seriesId: novel.seriesId);
    }
    return novel;
  }

  @override
  void initState() {
    super.initState();
    _novel = _load();
  }

  Future<void> _toggleBookmark(NovelSummary novel) async {
    setState(() => _busy = true);
    try {
      if (_bookmarked) {
        await removeNovelBookmark(novelId: novel.id);
      } else {
        await addNovelBookmark(novelId: novel.id, restrict: 'public');
      }
      if (mounted) setState(() => _bookmarked = !_bookmarked);
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('收藏操作失败：$error')),
        );
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _reload() {
    setState(() => _novel = _load());
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.initialTitle ?? '小说详情'),
        actions: [
          FutureBuilder<NovelSummary>(
            future: _novel,
            builder: (context, snapshot) => IconButton(
              onPressed: snapshot.hasData && !_busy
                  ? () => _toggleBookmark(snapshot.data!)
                  : null,
              icon: Icon(_bookmarked ? Icons.favorite : Icons.favorite_border),
              tooltip: _bookmarked ? '取消收藏' : '收藏',
            ),
          ),
        ],
      ),
      body: AsyncSection<NovelSummary>(
        future: _novel,
        onRetry: _reload,
        errorLabel: '加载小说失败',
        builder: (context, novel) => SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  SizedBox(
                    width: 140,
                    height: 200,
                    child: ClipRRect(
                      borderRadius: BorderRadius.circular(8),
                      child: RustImage(url: novel.coverUrl),
                    ),
                  ),
                  const SizedBox(width: 16),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(novel.title, style: Theme.of(context).textTheme.titleMedium),
                        const SizedBox(height: 6),
                        InkWell(
                          onTap: novel.authorId == 0
                              ? null
                              : () => Navigator.of(context).push(
                                    MaterialPageRoute<void>(
                                      builder: (_) => UserPage(
                                        userId: novel.authorId,
                                        initialName: novel.authorName,
                                      ),
                                    ),
                                  ),
                          child: Text(
                            novel.authorName,
                            style: Theme.of(context).textTheme.bodySmall?.copyWith(
                                  color: Theme.of(context).colorScheme.primary,
                                ),
                          ),
                        ),
                        const SizedBox(height: 10),
                        Text(
                          '${novel.textLength} 字 · 收藏 ${novel.totalBookmarks} · 浏览 ${novel.totalView}',
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                        if (novel.createDate.isNotEmpty)
                          Text(
                            novel.createDate,
                            style: Theme.of(context).textTheme.bodySmall,
                          ),
                        if (novel.tags.isNotEmpty) ...[
                          const SizedBox(height: 10),
                          Wrap(
                            spacing: 6,
                            runSpacing: 6,
                            children: [
                              for (final tag in novel.tags)
                                Chip(
                                  label: Text(tag),
                                  visualDensity: VisualDensity.compact,
                                  materialTapTargetSize:
                                      MaterialTapTargetSize.shrinkWrap,
                                ),
                            ],
                          ),
                        ],
                      ],
                    ),
                  ),
                ],
              ),
              if (novel.caption.isNotEmpty) ...[
                const SizedBox(height: 16),
                SelectableText(novel.caption),
              ],
              if (_series != null) ...[
                const SizedBox(height: 24),
                _SeriesSection(
                  series: _series!,
                  currentNovelId: novel.id,
                  onOpen: (chapter) => Navigator.of(context).pushReplacement(
                    MaterialPageRoute<void>(
                      builder: (_) => NovelDetailPage(
                        novelId: chapter.id,
                        initialTitle: chapter.title,
                      ),
                    ),
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

/// 系列信息与章节列表。
class _SeriesSection extends StatefulWidget {
  const _SeriesSection({
    required this.series,
    required this.currentNovelId,
    required this.onOpen,
  });

  final Future<NovelSeries> series;
  final int currentNovelId;
  final void Function(NovelSummary chapter) onOpen;

  @override
  State<_SeriesSection> createState() => _SeriesSectionState();
}

class _SeriesSectionState extends State<_SeriesSection> {
  late Future<NovelSeries> _series = widget.series;

  Future<void> _loadMore(NovelSeries current) async {
    if (current.nextUrl.isEmpty) return;
    final next = await fetchNextSeries(nextUrl: current.nextUrl);
    setState(() {
      _series = Future.value(NovelSeries(
        id: current.id,
        title: current.title,
        caption: current.caption,
        isConcluded: current.isConcluded,
        isWatched: current.isWatched,
        chapters: [...current.chapters, ...next.chapters],
        nextUrl: next.nextUrl,
      ));
    });
  }

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<NovelSeries>(
      future: _series,
      builder: (context, snapshot) {
        if (snapshot.connectionState != ConnectionState.done) {
          return const Center(child: CircularProgressIndicator());
        }
        final series = snapshot.data;
        if (series == null) {
          return const Text('系列信息加载失败');
        }
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    '系列：${series.title}',
                    style: Theme.of(context).textTheme.titleSmall,
                  ),
                ),
                if (series.isWatched) const Chip(label: Text('追更中')),
                if (series.isConcluded) const Chip(label: Text('已完结')),
              ],
            ),
            const SizedBox(height: 8),
            for (final chapter in series.chapters)
              ListTile(
                dense: true,
                selected: chapter.id == widget.currentNovelId,
                title: Text(chapter.title),
                subtitle: Text('${chapter.textLength} 字'),
                onTap: () => widget.onOpen(chapter),
              ),
            if (series.nextUrl.isNotEmpty)
              Center(
                child: TextButton(
                  onPressed: () => _loadMore(series),
                  child: const Text('加载更多章节'),
                ),
              ),
          ],
        );
      },
    );
  }
}

/// 推荐页里的小说一栏。
class NovelFeedPage extends StatefulWidget {
  const NovelFeedPage({super.key});

  @override
  State<NovelFeedPage> createState() => _NovelFeedPageState();
}

class _NovelFeedPageState extends State<NovelFeedPage> {
  late Future<List<NovelSummary>> _novels = fetchRecommendedNovels();

  void _reload() {
    setState(() => _novels = fetchRecommendedNovels());
  }

  @override
  Widget build(BuildContext context) {
    return AsyncSection<List<NovelSummary>>(
      future: _novels,
      onRetry: _reload,
      errorLabel: '加载推荐小说失败',
      builder: (context, novels) => NovelGrid(
        novels: novels,
        emptyLabel: '没有取到推荐小说',
        onOpen: (novel) => Navigator.of(context).push(
          MaterialPageRoute<void>(
            builder: (_) => NovelDetailPage(novelId: novel.id, initialTitle: novel.title),
          ),
        ),
      ),
    );
  }
}
