import 'package:flutter/material.dart';
import 'package:pixiv_shaft/src/rust/api/illust.dart';
import 'package:pixiv_shaft/src/rust/api/novel.dart';
import 'package:pixiv_shaft/src/rust/api/user.dart';
import 'package:pixiv_shaft/src/settings/app_settings.dart';
import 'package:pixiv_shaft/src/ui/browse_history_page.dart';
import 'package:pixiv_shaft/src/ui/illust_detail_page.dart';
import 'package:pixiv_shaft/src/ui/novel_pages.dart';
import 'package:pixiv_shaft/src/ui/settings_page.dart';
import 'package:pixiv_shaft/src/ui/user_list_page.dart';
import 'package:pixiv_shaft/src/ui/widgets/illust_card.dart';
import 'package:pixiv_shaft/src/ui/widgets/layout.dart';
import 'package:pixiv_shaft/src/ui/widgets/novel_card.dart';
import 'package:pixiv_shaft/src/ui/widgets/paged_grid.dart';
import 'package:pixiv_shaft/src/ui/widgets/rust_image.dart';

/// 我的：资料、用户关系、插画与小说收藏、创作和浏览历史。
class ProfilePage extends StatefulWidget {
  const ProfilePage({super.key});

  @override
  State<ProfilePage> createState() => _ProfilePageState();
}

class _ProfilePageState extends State<ProfilePage> with SingleTickerProviderStateMixin {
  late final TabController _tabs = TabController(length: 4, vsync: this);
  late Future<UserProfile> _profile = _loadProfile();
  final Set<int> _worksVisited = {0};
  int _worksType = 0;
  int _refresh = 0;

  Future<UserProfile> _loadProfile() async {
    final userId = await selfUserId();
    if (userId <= 0) throw StateError('无法读取当前用户');
    return fetchUserDetail(userId: userId);
  }

  @override
  void dispose() {
    _tabs.dispose();
    super.dispose();
  }

  void _reload() {
    setState(() {
      _profile = _loadProfile();
      _refresh++;
    });
  }

  void _openIllust(IllustSummary illust) {
    Navigator.of(context).push(MaterialPageRoute<void>(
      builder: (_) => IllustDetailPage(illustId: illust.id, initialTitle: illust.title),
    ));
  }

  void _openNovel(NovelSummary novel) {
    Navigator.of(context).push(MaterialPageRoute<void>(
      builder: (_) => NovelDetailPage(novelId: novel.id, initialTitle: novel.title),
    ));
  }

  void _openUserList(int userId, int tab) {
    Navigator.of(context).push(MaterialPageRoute<void>(
      builder: (_) => UserListScreen(userId: userId, initialTab: tab),
    ));
  }

  Widget _illustFeed(Future<IllustPage> Function() loadFirst, String emptyLabel) {
    return PagedGrid<IllustSummary>(
      masonry: true,
      padding: const EdgeInsets.all(4),
      emptyLabel: emptyLabel,
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
        onTap: () => _openIllust(illust),
      ),
    );
  }

  Widget _novelFeed(Future<NovelPage> Function() loadFirst, String emptyLabel) {
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
        emptyLabel: emptyLabel,
        loadFirst: () async {
          final page = await loadFirst();
          return (items: page.novels, nextUrl: page.nextUrl);
        },
        loadNext: (nextUrl) async {
          final page = await fetchNextNovelPage(nextUrl: nextUrl);
          return (items: page.novels, nextUrl: page.nextUrl);
        },
        itemBuilder: (context, novel, index) => NovelCard(
          novel: novel,
          titleMaxLines: AppSettings.instance.novelTitleMaxLines,
          onTap: () => _openNovel(novel),
        ),
      ),
    );
  }

  Widget _works() {
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
          child: Row(children: [
            FilterChip(
              selected: _worksType == 0,
              onSelected: (_) => setState(() => _worksType = 0),
              label: const Text('插画'),
            ),
            const SizedBox(width: 8),
            FilterChip(
              selected: _worksType == 1,
              onSelected: (_) => setState(() {
                _worksType = 1;
                _worksVisited.add(1);
              }),
              label: const Text('小说'),
            ),
          ]),
        ),
        Expanded(
          child: Stack(children: [
            for (final type in _worksVisited)
              Offstage(
                offstage: type != _worksType,
                child: KeyedSubtree(
                  key: ValueKey('created-$type-$_refresh'),
                  child: type == 0
                      ? _illustFeed(() async {
                          final userId = await selfUserId();
                          return fetchUserIllusts(userId: userId, illustType: 'illust');
                        }, '暂无已发布插画')
                      : _novelFeed(() async {
                          final userId = await selfUserId();
                          return fetchUserNovels(userId: userId);
                        }, '没有可显示的已发布小说'),
                ),
              ),
          ]),
        ),
      ],
    );
  }

  Widget _header() {
    return FutureBuilder<UserProfile>(
      future: _profile,
      builder: (context, snapshot) {
        final profile = snapshot.data;
        return Column(children: [
          Padding(
            padding: const EdgeInsets.all(16),
            child: Row(children: [
              SizedBox(
                width: 64,
                height: 64,
                child: ClipOval(
                  child: profile == null
                      ? const Icon(Icons.person_outline, size: 48)
                      : RustImage(url: profile.avatarUrl, errorLabel: ''),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(profile?.name ?? '我的', style: Theme.of(context).textTheme.titleMedium),
                  if (profile != null) Text('@${profile.account}'),
                  if (profile != null)
                    Text('插画 ${profile.totalIllusts} · 漫画 ${profile.totalManga} · 小说 ${profile.totalNovels}'),
                  if (snapshot.hasError)
                    TextButton(onPressed: _reload, child: const Text('资料加载失败，重试')),
                ],
              )),
              IconButton(
                tooltip: '设置',
                onPressed: () => Navigator.of(context).push(
                  MaterialPageRoute<void>(builder: (_) => const SettingsPage()),
                ),
                icon: const Icon(Icons.settings_outlined),
              ),
            ]),
          ),
          if (profile != null)
            SizedBox(
              height: 48,
              child: ListView(
                scrollDirection: Axis.horizontal,
                padding: const EdgeInsets.symmetric(horizontal: 16),
                children: [
                  ActionChip(
                    label: const Text('关注中'),
                    onPressed: () => _openUserList(profile.id, 0),
                  ),
                  const SizedBox(width: 8),
                  ActionChip(
                    label: const Text('粉丝'),
                    onPressed: () => _openUserList(profile.id, 1),
                  ),
                  const SizedBox(width: 8),
                  ActionChip(
                    label: const Text('好P友'),
                    onPressed: () => _openUserList(profile.id, 2),
                  ),
                ],
              ),
            ),
        ]);
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    return Column(children: [
      _header(),
      TabBar(
        controller: _tabs,
        tabs: const [
          Tab(text: '插画收藏'),
          Tab(text: '小说收藏'),
          Tab(text: '我的作品'),
          Tab(text: '历史'),
        ],
      ),
      Expanded(
        child: TabBarView(controller: _tabs, children: [
          KeyedSubtree(
            key: ValueKey('bookmarked-illust-$_refresh'),
            child: _illustFeed(fetchBookmarkedIllusts, '没有可显示的插画收藏'),
          ),
          KeyedSubtree(
            key: ValueKey('bookmarked-novel-$_refresh'),
            child: _novelFeed(fetchBookmarkedNovels, '没有可显示的小说收藏'),
          ),
          _works(),
          const BrowseHistoryView(),
        ]),
      ),
    ]);
  }
}
