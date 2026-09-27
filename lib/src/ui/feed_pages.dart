import 'dart:async';

import 'package:flutter/material.dart';
import 'package:pixiv_shaft/src/rust/api/illust.dart';
import 'package:pixiv_shaft/src/settings/app_settings.dart';
import 'package:pixiv_shaft/src/ui/illust_detail_page.dart';
import 'package:pixiv_shaft/src/ui/novel_pages.dart';
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
  static const _pageLabels = ['推荐', '漫画', '小说', '最新'];
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
              duration: const Duration(milliseconds: 300),
              curve: Curves.easeOutCubic,
              offset: _showTabs ? Offset.zero : const Offset(0, -1),
              child: AnimatedOpacity(
                duration: const Duration(milliseconds: 300),
                opacity: _showTabs ? 1 : 0,
                child: ExcludeSemantics(
                  excluding: !_showTabs,
                  child: IgnorePointer(
                    ignoring: !_showTabs,
                    child: MouseRegion(
                      onEnter: (_) => _revealTabs(),
                      onExit: (_) => _scheduleHideTabs(),
                      child: Material(
                        elevation: 8,
                        color: Theme.of(context)
                            .colorScheme
                            .surface
                            .withValues(alpha: 0.98),
                        borderRadius: BorderRadius.circular(18),
                        clipBehavior: Clip.antiAlias,
                        child: AnimatedBuilder(
                          animation: _tabs,
                          builder: (context, _) {
                            final colors = Theme.of(context).colorScheme;
                            return Padding(
                              padding: const EdgeInsets.symmetric(
                                horizontal: 6,
                                vertical: 4,
                              ),
                              child: Row(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  for (var index = 0;
                                      index < _pageLabels.length;
                                      index++) ...[
                                    if (index > 0) const SizedBox(width: 2),
                                    Semantics(
                                      selected: _tabs.index == index,
                                      child: Material(
                                        color: _tabs.index == index
                                            ? colors.primaryContainer
                                            : Colors.transparent,
                                        borderRadius: BorderRadius.circular(14),
                                        child: InkWell(
                                          borderRadius: BorderRadius.circular(14),
                                          onTap: () {
                                            _revealTabs();
                                            _tabs.animateTo(index);
                                          },
                                          child: Padding(
                                            padding: const EdgeInsets.symmetric(
                                              horizontal: 14,
                                              vertical: 6,
                                            ),
                                            child: Text(
                                              _pageLabels[index],
                                              style: Theme.of(context)
                                                  .textTheme
                                                  .labelLarge
                                                  ?.copyWith(
                                                    color: _tabs.index == index
                                                        ? colors.onPrimaryContainer
                                                        : colors.onSurfaceVariant,
                                                  ),
                                            ),
                                          ),
                                        ),
                                      ),
                                    ),
                                  ],
                                ],
                              ),
                            );
                          },
                        ),
                      ),
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
