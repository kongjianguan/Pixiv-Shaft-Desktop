import 'package:flutter/material.dart';
import 'package:pixiv_shaft/src/rust/api/discovery.dart';
import 'package:pixiv_shaft/src/rust/api/illust.dart';
import 'package:pixiv_shaft/src/rust/api/novel.dart';
import 'package:pixiv_shaft/src/settings/app_settings.dart';
import 'package:pixiv_shaft/src/ui/illust_detail_page.dart';
import 'package:pixiv_shaft/src/ui/novel_pages.dart';
import 'package:pixiv_shaft/src/ui/pixivision_page.dart';
import 'package:pixiv_shaft/src/ui/widgets/illust_card.dart';
import 'package:pixiv_shaft/src/ui/widgets/layout.dart';
import 'package:pixiv_shaft/src/ui/widgets/novel_card.dart';
import 'package:pixiv_shaft/src/ui/widgets/paged_grid.dart';
import 'package:pixiv_shaft/src/ui/widgets/rust_image.dart';

const _illustModes = <(String, String)>[
  ('day', 'Daily'),
  ('week', 'Weekly'),
  ('month', 'Monthly'),
  ('day_male', 'Male'),
  ('day_female', 'Female'),
  ('week_original', 'Original'),
  ('week_rookie', 'Rookie'),
  ('day_r18', 'R18 Daily'),
  ('week_r18', 'R18 Weekly'),
];

const _novelModes = <(String, String)>[
  ('day', 'Daily'),
  ('week', 'Weekly'),
  ('day_male', 'Male'),
  ('day_female', 'Female'),
  ('week_rookie', 'Rookie'),
  ('day_r18', 'R18 Daily'),
];

/// 热门标签、插画和小说排行、Pixivision 预览共用一个纵向页面。
class DiscoverPage extends StatefulWidget {
  const DiscoverPage({super.key});

  @override
  State<DiscoverPage> createState() => _DiscoverPageState();
}

class _DiscoverPageState extends State<DiscoverPage> {
  String _illustMode = 'day';
  String _novelMode = 'day';
  DateTime? _illustDate;
  DateTime? _novelDate;

  @override
  void initState() {
    super.initState();
    AppSettings.instance.addListener(_syncR18);
  }

  @override
  void dispose() {
    AppSettings.instance.removeListener(_syncR18);
    super.dispose();
  }

  void _syncR18() {
    if (AppSettings.instance.showR18) return;
    if (_illustMode.contains('r18') || _novelMode.contains('r18')) {
      setState(() {
        if (_illustMode.contains('r18')) _illustMode = 'day';
        if (_novelMode.contains('r18')) _novelMode = 'day';
      });
    }
  }

  Future<void> _selectDate({required bool novel}) async {
    final current = novel ? _novelDate : _illustDate;
    final today = DateUtils.dateOnly(DateTime.now());
    final selected = await showDatePicker(
      context: context,
      initialDate: current ?? today,
      firstDate: DateTime(2007),
      lastDate: today,
    );
    if (!mounted || selected == null) return;
    setState(() {
      if (novel) {
        _novelDate = selected;
      } else {
        _illustDate = selected;
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: AppSettings.instance,
      builder: (context, _) => ListView(
        children: [
          const _SectionTitle('Trending Tags'),
          const SizedBox(height: 155, child: _TrendingRow()),
          const _SectionTitle('Illust Ranking'),
          _modes(
            _illustModes,
            selectedMode: _illustMode,
            selectedDate: _illustDate,
            onMode: (mode) => setState(() => _illustMode = mode),
            onDate: () => _selectDate(novel: false),
            onClearDate: () => setState(() => _illustDate = null),
          ),
          SizedBox(
            height: 400,
            child: _RankingPanel(
              key: ValueKey('illust-$_illustMode-$_illustDate'),
              mode: _illustMode,
              date: _illustDate,
            ),
          ),
          const _SectionTitle('Novel Ranking'),
          _modes(
            _novelModes,
            selectedMode: _novelMode,
            selectedDate: _novelDate,
            onMode: (mode) => setState(() => _novelMode = mode),
            onDate: () => _selectDate(novel: true),
            onClearDate: () => setState(() => _novelDate = null),
          ),
          SizedBox(
            height: 400,
            child: _RankingPanel(
              key: ValueKey('novel-$_novelMode-$_novelDate'),
              mode: _novelMode,
              date: _novelDate,
              novel: true,
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 8, 0),
            child: Row(
              children: [
                const Expanded(child: Text('Pixivision 特辑')),
                TextButton(
                  onPressed: () => Navigator.of(context).push(
                    MaterialPageRoute<void>(builder: (_) => const PixivisionPage()),
                  ),
                  child: const Text('查看全部'),
                ),
              ],
            ),
          ),
          const SizedBox(height: 165, child: _SpotlightPreview()),
          const SizedBox(height: 24),
        ],
      ),
    );
  }

  Widget _modes(
    List<(String, String)> modes, {
    required String selectedMode,
    required DateTime? selectedDate,
    required ValueChanged<String> onMode,
    required VoidCallback onDate,
    required VoidCallback onClearDate,
  }) {
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      child: Row(
        children: [
          for (final (mode, label) in modes)
            if (AppSettings.instance.showR18 || !mode.contains('r18'))
              Padding(
                padding: const EdgeInsets.only(right: 8),
                child: FilterChip(
                  selected: selectedMode == mode,
                  label: Text(label),
                  onSelected: (_) => onMode(mode),
                ),
              ),
          if (selectedDate == null)
            ActionChip(label: const Text('选择日期'), onPressed: onDate)
          else
            InputChip(
              label: Text(_dateText(selectedDate)),
              onPressed: onDate,
              onDeleted: onClearDate,
            ),
        ],
      ),
    );
  }
}

String _dateText(DateTime date) =>
    '${date.year.toString().padLeft(4, '0')}-${date.month.toString().padLeft(2, '0')}-${date.day.toString().padLeft(2, '0')}';

class _SectionTitle extends StatelessWidget {
  const _SectionTitle(this.title);

  final String title;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
      child: Text(title, style: Theme.of(context).textTheme.titleSmall),
    );
  }
}

class _TrendingRow extends StatefulWidget {
  const _TrendingRow();

  @override
  State<_TrendingRow> createState() => _TrendingRowState();
}

class _TrendingRowState extends State<_TrendingRow> {
  late Future<List<TrendingTag>> _tags = fetchTrendingTags();

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<List<TrendingTag>>(
      future: _tags,
      builder: (context, snapshot) {
        if (snapshot.hasError) {
          return Center(
            child: TextButton(
              onPressed: () => setState(() => _tags = fetchTrendingTags()),
              child: Text('热门标签加载失败：${snapshot.error}，重试'),
            ),
          );
        }
        if (!snapshot.hasData) return const Center(child: CircularProgressIndicator());
        return ListView.separated(
          scrollDirection: Axis.horizontal,
          padding: const EdgeInsets.symmetric(horizontal: 16),
          itemCount: snapshot.data!.length,
          separatorBuilder: (_, _) => const SizedBox(width: 8),
          itemBuilder: (context, index) {
            final tag = snapshot.data![index];
            return SizedBox(
              width: 120,
              child: Column(
                children: [
                  SizedBox(height: 120, child: RustImage(url: tag.thumbnailUrl)),
                  Text('#${tag.tag}', maxLines: 1, overflow: TextOverflow.ellipsis),
                ],
              ),
            );
          },
        );
      },
    );
  }
}

class _RankingPanel extends StatelessWidget {
  const _RankingPanel({super.key, required this.mode, required this.date, this.novel = false});

  final String mode;
  final DateTime? date;
  final bool novel;

  @override
  Widget build(BuildContext context) {
    final dateValue = date == null ? null : _dateText(date!);
    if (!novel) {
      return PagedGrid<IllustSummary>(
        masonry: true,
        padding: const EdgeInsets.all(4),
        loadFirst: () async {
          final page = await fetchRankingPage(mode: mode, date: dateValue);
          return (items: page.illusts, nextUrl: page.nextUrl);
        },
        loadNext: (nextUrl) async {
          final page = await fetchNextIllustPage(nextUrl: nextUrl);
          return (items: page.illusts, nextUrl: page.nextUrl);
        },
        itemBuilder: (context, illust, index) => IllustCard(
          illust: illust,
          titleMaxLines: AppSettings.instance.workTitleMaxLines,
          onTap: () => Navigator.of(context).push(
            MaterialPageRoute<void>(
              builder: (_) => IllustDetailPage(
                illustId: illust.id,
                initialTitle: illust.title,
              ),
            ),
          ),
        ),
      );
    }
    return LayoutBuilder(
      builder: (context, constraints) => PagedGrid<NovelSummary>(
        delegate: SliverGridDelegateWithFixedCrossAxisCount(
          crossAxisCount: responsiveColumns(
            constraints.maxWidth,
            AppSettings.instance.novelMaxColumnWidth,
            AppSettings.instance.novelMaxColumns,
          ),
          crossAxisSpacing: 4,
          mainAxisSpacing: 4,
          childAspectRatio: 0.65,
        ),
        padding: const EdgeInsets.all(4),
        loadFirst: () async {
          final page = await fetchNovelRankingPage(mode: mode, date: dateValue);
          return (items: page.novels, nextUrl: page.nextUrl);
        },
        loadNext: (nextUrl) async {
          final page = await fetchNextNovelPage(nextUrl: nextUrl);
          return (items: page.novels, nextUrl: page.nextUrl);
        },
        itemBuilder: (context, novel, index) => NovelCard(
          novel: novel,
          titleMaxLines: AppSettings.instance.novelTitleMaxLines,
          onTap: () => Navigator.of(context).push(
            MaterialPageRoute<void>(
              builder: (_) => NovelDetailPage(
                novelId: novel.id,
                initialTitle: novel.title,
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _SpotlightPreview extends StatefulWidget {
  const _SpotlightPreview();

  @override
  State<_SpotlightPreview> createState() => _SpotlightPreviewState();
}

class _SpotlightPreviewState extends State<_SpotlightPreview> {
  late Future<SpotlightPage> _articles = fetchSpotlightPage(category: 'illust');

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<SpotlightPage>(
      future: _articles,
      builder: (context, snapshot) {
        if (snapshot.hasError) {
          return Center(
            child: TextButton(
              onPressed: () => setState(() => _articles = fetchSpotlightPage(category: 'illust')),
              child: Text('Pixivision 加载失败：${snapshot.error}，重试'),
            ),
          );
        }
        if (!snapshot.hasData) return const Center(child: CircularProgressIndicator());
        final articles = snapshot.data!.articles.take(10).toList();
        if (articles.isEmpty) return const Center(child: Text('暂无文章'));
        return ListView.separated(
          scrollDirection: Axis.horizontal,
          padding: const EdgeInsets.symmetric(horizontal: 16),
          itemCount: articles.length,
          separatorBuilder: (_, _) => const SizedBox(width: 8),
          itemBuilder: (context, index) {
            final article = articles[index];
            return SizedBox(
              width: 180,
              child: InkWell(
                onTap: () => openSpotlightArticle(article),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    SizedBox(height: 110, child: RustImage(url: article.thumbnailUrl)),
                    const SizedBox(height: 6),
                    Text(article.title, maxLines: 2, overflow: TextOverflow.ellipsis),
                  ],
                ),
              ),
            );
          },
        );
      },
    );
  }
}
