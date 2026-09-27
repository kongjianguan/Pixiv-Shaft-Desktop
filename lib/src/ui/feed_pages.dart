import 'dart:async';

import 'package:flutter/material.dart';
import 'package:pixiv_shaft/src/rust/api/illust.dart';
import 'package:pixiv_shaft/src/settings/app_settings.dart';
import 'package:pixiv_shaft/src/ui/illust_detail_page.dart';
import 'package:pixiv_shaft/src/ui/novel_pages.dart';
import 'package:pixiv_shaft/src/ui/r18_page.dart';
import 'package:pixiv_shaft/src/ui/widgets/illust_card.dart';
import 'package:pixiv_shaft/src/ui/widgets/paged_grid.dart';

/// 推荐：与旧版一致的推荐、漫画、小说、最新四个作品流。
class RecommendedPage extends StatefulWidget {
  const RecommendedPage({super.key});

  @override
  State<RecommendedPage> createState() => _RecommendedPageState();
}

class _RecommendedPageState extends State<RecommendedPage>
    with SingleTickerProviderStateMixin {
  late final TabController _tabs = TabController(length: 4, vsync: this);
  Timer? _hideTimer;
  bool _showTabs = true;

  @override
  void dispose() {
    _hideTimer?.cancel();
    _tabs.dispose();
    super.dispose();
  }

  void _revealTabs() {
    _hideTimer?.cancel();
    if (!_showTabs) setState(() => _showTabs = true);
  }

  void _scheduleHideTabs() {
    _hideTimer?.cancel();
    _hideTimer = Timer(const Duration(milliseconds: 100), () {
      if (mounted) setState(() => _showTabs = false);
    });
  }

  @override
  Widget build(BuildContext context) {
    return Stack(
      children: [
        Positioned.fill(
          child: TabBarView(
            controller: _tabs,
            children: [
              _illustFeed(() => fetchHomePage(illustType: 'illust')),
              _illustFeed(() => fetchHomePage(illustType: 'manga')),
              const NovelFeedPage(),
              _illustFeed(fetchLatestPage),
            ],
          ),
        ),
        Positioned(
          top: 0,
          left: 0,
          right: 0,
          height: 20,
          child: MouseRegion(onEnter: (_) => _revealTabs()),
        ),
        Positioned(
          top: 8,
          left: 0,
          right: 0,
          child: Center(
            child: AnimatedSlide(
              duration: const Duration(milliseconds: 150),
              offset: _showTabs ? Offset.zero : const Offset(0, -1.5),
              child: AnimatedOpacity(
                duration: const Duration(milliseconds: 150),
                opacity: _showTabs ? 1 : 0,
                child: MouseRegion(
                  onEnter: (_) => _revealTabs(),
                  onExit: (_) => _scheduleHideTabs(),
                  child: Material(
                    elevation: 8,
                    borderRadius: BorderRadius.circular(18),
                    color: Theme.of(context).colorScheme.surface,
                    child: TabBar(
                      controller: _tabs,
                      isScrollable: true,
                      tabAlignment: TabAlignment.start,
                      dividerColor: Colors.transparent,
                      indicatorSize: TabBarIndicatorSize.tab,
                      indicator: BoxDecoration(
                        color: Theme.of(context).colorScheme.primaryContainer,
                        borderRadius: BorderRadius.circular(14),
                      ),
                      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 4),
                      tabs: const [
                        Tab(text: '推荐'),
                        Tab(text: '漫画'),
                        Tab(text: '小说'),
                        Tab(text: '最新'),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }

  Widget _illustFeed(Future<IllustPage> Function() loadFirst) {
    return PagedGrid<IllustSummary>(
      masonry: true,
      padding: const EdgeInsets.all(4),
      loadFirst: () async {
        final page = await loadFirst();
        return (items: page.illusts, nextUrl: page.nextUrl);
      },
      loadNext: (nextUrl) async {
        final page = await fetchNextIllustPage(nextUrl: nextUrl);
        return (items: page.illusts, nextUrl: page.nextUrl);
      },
      itemBuilder: (context, illust, index) => IllustCard(
        illust: illust,
        titleMaxLines: AppSettings.instance.workTitleMaxLines,
        onTap: () => _open(context, illust),
      ),
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
    return AnimatedBuilder(
      animation: AppSettings.instance,
      builder: (context, _) => _FeedScaffold(
        title: '发现',
        onReload: _reload,
        action: AppSettings.instance.showR18
            ? IconButton(
                onPressed: () => Navigator.of(context).push(
                  MaterialPageRoute<void>(builder: (_) => const R18Page()),
                ),
                icon: const Icon(Icons.explicit),
                tooltip: 'R18 排行',
              )
            : null,
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
    this.action,
  });

  final String title;
  final Widget child;
  final VoidCallback onReload;
  final Widget? header;
  final Widget? action;

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
              if (action != null) action!,
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
