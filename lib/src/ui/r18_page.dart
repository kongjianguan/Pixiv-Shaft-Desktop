import 'package:flutter/material.dart';
import 'package:pixiv_shaft/src/rust/api/illust.dart';
import 'package:pixiv_shaft/src/settings/app_settings.dart';
import 'package:pixiv_shaft/src/ui/illust_detail_page.dart';
import 'package:pixiv_shaft/src/ui/widgets/illust_card.dart';
import 'package:pixiv_shaft/src/ui/widgets/layout.dart';
import 'package:pixiv_shaft/src/ui/widgets/paged_grid.dart';

const _modes = <(String, String)>[
  ('day_r18', '今日 R18'),
  ('week_r18', '本周 R18'),
  ('day_male_r18', '男性向'),
  ('day_female_r18', '女性向'),
  ('day_r18_ai', 'AI'),
];

/// 成人内容排行。关闭 R18 开关时，连同路由一起移除。
class R18Page extends StatefulWidget {
  const R18Page({super.key});

  @override
  State<R18Page> createState() => _R18PageState();
}

class _R18PageState extends State<R18Page> {
  final _settings = AppSettings.instance;

  @override
  void initState() {
    super.initState();
    _settings.addListener(_onSettingsChanged);
  }

  @override
  void dispose() {
    _settings.removeListener(_onSettingsChanged);
    super.dispose();
  }

  void _onSettingsChanged() {
    if (!mounted || _settings.showR18) return;
    final route = ModalRoute.of(context);
    if (route != null) Navigator.of(context).removeRoute(route);
  }

  @override
  Widget build(BuildContext context) {
    if (!_settings.showR18) return const SizedBox.shrink();
    return DefaultTabController(
      length: _modes.length,
      child: Scaffold(
        appBar: AppBar(
          title: const Text('R18 排行'),
          bottom: TabBar(
            isScrollable: true,
            tabs: [for (final (_, label) in _modes) Tab(text: label)],
          ),
        ),
        body: TabBarView(
          children: [
            for (final (mode, _) in _modes) _RankingFeed(mode: mode),
          ],
        ),
      ),
    );
  }
}

class _RankingFeed extends StatelessWidget {
  const _RankingFeed({required this.mode});

  final String mode;

  @override
  Widget build(BuildContext context) {
    final settings = AppSettings.instance;
    return AnimatedBuilder(
      animation: settings,
      builder: (context, _) => PagedGrid<IllustSummary>(
        key: ValueKey(mode),
        delegate: SliverGridDelegateWithFixedCrossAxisCount(
          crossAxisCount: gridColumns(
            context,
            settings.workMaxColumnWidth,
            settings.workMaxColumns,
          ),
          crossAxisSpacing: 12,
          mainAxisSpacing: 12,
          childAspectRatio: 0.68,
        ),
        loadFirst: () async {
          final page = await fetchNextIllustPage(
            nextUrl: '/v1/illust/ranking?mode=$mode&filter=for_ios',
          );
          return (items: page.illusts, nextUrl: page.nextUrl);
        },
        loadNext: (nextUrl) async {
          final page = await fetchNextIllustPage(nextUrl: nextUrl);
          return (items: page.illusts, nextUrl: page.nextUrl);
        },
        emptyLabel: '该模式下没有内容',
        itemBuilder: (context, illust, index) => IllustCard(
          illust: illust,
          titleMaxLines: settings.workTitleMaxLines,
          onTap: () => Navigator.of(context).push(
            MaterialPageRoute<void>(
              builder: (_) => IllustDetailPage(
                illustId: illust.id,
                initialTitle: illust.title,
              ),
            ),
          ),
        ),
      ),
    );
  }
}
