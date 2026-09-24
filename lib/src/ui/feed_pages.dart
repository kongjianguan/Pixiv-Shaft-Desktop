import 'package:flutter/material.dart';
import 'package:pixiv_shaft/src/rust/api/illust.dart';
import 'package:pixiv_shaft/src/ui/illust_detail_page.dart';
import 'package:pixiv_shaft/src/ui/novel_pages.dart';
import 'package:pixiv_shaft/src/ui/widgets/illust_card.dart';

/// 推荐：插画与小说两栏。
class RecommendedPage extends StatefulWidget {
  const RecommendedPage({super.key});

  @override
  State<RecommendedPage> createState() => _RecommendedPageState();
}

class _RecommendedPageState extends State<RecommendedPage>
    with SingleTickerProviderStateMixin {
  late final TabController _tabs = TabController(length: 2, vsync: this);
  late Future<List<IllustSummary>> _illusts = fetchRecommendedIllusts();

  @override
  void dispose() {
    _tabs.dispose();
    super.dispose();
  }

  void _reload() {
    setState(() => _illusts = fetchRecommendedIllusts());
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 16, 16, 0),
          child: Row(
            children: [
              Expanded(
                child: Text('推荐', style: Theme.of(context).textTheme.titleLarge),
              ),
              TabBar(
                controller: _tabs,
                isScrollable: true,
                tabAlignment: TabAlignment.start,
                tabs: const [Tab(text: '插画'), Tab(text: '小说')],
              ),
            ],
          ),
        ),
        Expanded(
          child: TabBarView(
            controller: _tabs,
            children: [
              AsyncSection<List<IllustSummary>>(
                future: _illusts,
                onRetry: _reload,
                errorLabel: '加载推荐失败',
                builder: (context, illusts) => IllustGrid(
                  illusts: illusts,
                  emptyLabel: '没有取到推荐作品',
                  onOpen: (illust) => _open(context, illust),
                ),
              ),
              const NovelFeedPage(),
            ],
          ),
        ),
      ],
    );
  }

  void _open(BuildContext context, IllustSummary illust) {
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => IllustDetailPage(illustId: illust.id, initialTitle: illust.title),
      ),
    );
  }
}

/// 发现：按模式取排行。
class RankingPage extends StatefulWidget {
  const RankingPage({super.key});

  @override
  State<RankingPage> createState() => _RankingPageState();
}

class _RankingPageState extends State<RankingPage> {
  static const _modes = <String, String>{
    'day': '今日',
    'week': '本周',
    'month': '本月',
    'day_manga': '今日漫画',
    'day_r18': '今日 R18',
  };

  String _mode = 'day';
  late Future<List<IllustSummary>> _illusts = fetchRanking(mode: _mode);

  void _select(String mode) {
    setState(() {
      _mode = mode;
      _illusts = fetchRanking(mode: mode);
    });
  }

  void _reload() {
    setState(() => _illusts = fetchRanking(mode: _mode));
  }

  @override
  Widget build(BuildContext context) {
    return _FeedScaffold(
      title: '发现',
      onReload: _reload,
      header: SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 12),
        child: Row(
          children: [
            for (final entry in _modes.entries) ...[
              ChoiceChip(
                label: Text(entry.value),
                selected: _mode == entry.key,
                onSelected: (_) => _select(entry.key),
              ),
              const SizedBox(width: 8),
            ],
          ],
        ),
      ),
      child: AsyncSection<List<IllustSummary>>(
        future: _illusts,
        onRetry: _reload,
        errorLabel: '加载排行失败',
        builder: (context, illusts) => IllustGrid(
          illusts: illusts,
          emptyLabel: '该模式下没有内容',
          onOpen: (illust) => Navigator.of(context).push(
            MaterialPageRoute<void>(
              builder: (_) => IllustDetailPage(illustId: illust.id, initialTitle: illust.title),
            ),
          ),
        ),
      ),
    );
  }
}

/// 顶部标题栏加刷新按钮，可选一行头部内容。
class _FeedScaffold extends StatelessWidget {
  const _FeedScaffold({
    required this.title,
    required this.child,
    required this.onReload,
    this.header,
  });

  final String title;
  final Widget child;
  final VoidCallback onReload;
  final Widget? header;

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
          child: Row(
            children: [
              Expanded(
                child: Text(title, style: Theme.of(context).textTheme.titleLarge),
              ),
              IconButton(
                onPressed: onReload,
                icon: const Icon(Icons.refresh),
                tooltip: '重新加载',
              ),
            ],
          ),
        ),
        if (header != null) ...[header!, const SizedBox(height: 8)],
        Expanded(child: child),
      ],
    );
  }
}
