import 'package:flutter/material.dart';
import 'package:pixiv_shaft/src/rust/api/illust.dart';
import 'package:pixiv_shaft/src/rust/api/novel.dart';
import 'package:pixiv_shaft/src/rust/api/user.dart';
import 'package:pixiv_shaft/src/settings/app_settings.dart';
import 'package:pixiv_shaft/src/ui/illust_detail_page.dart';
import 'package:pixiv_shaft/src/ui/novel_pages.dart';
import 'package:pixiv_shaft/src/ui/user_page.dart';
import 'package:pixiv_shaft/src/ui/widgets/illust_card.dart';
import 'package:pixiv_shaft/src/ui/widgets/layout.dart';
import 'package:pixiv_shaft/src/ui/widgets/novel_card.dart';
import 'package:pixiv_shaft/src/ui/widgets/paged_grid.dart';
import 'package:pixiv_shaft/src/ui/widgets/rust_image.dart';

/// 动态：关注的插画与小说、好P友作品、推荐用户。
class DynamicPage extends StatefulWidget {
  const DynamicPage({super.key});

  @override
  State<DynamicPage> createState() => _DynamicPageState();
}

class _DynamicPageState extends State<DynamicPage>
    with SingleTickerProviderStateMixin {
  late final TabController _tabs = TabController(length: 3, vsync: this);

  @override
  void dispose() {
    _tabs.dispose();
    super.dispose();
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
                child: Text('动态', style: Theme.of(context).textTheme.titleLarge),
              ),
              TabBar(
                controller: _tabs,
                isScrollable: true,
                tabAlignment: TabAlignment.start,
                tabs: const [
                  Tab(text: '关注'),
                  Tab(text: '好P友'),
                  Tab(text: '推荐用户'),
                ],
              ),
            ],
          ),
        ),
        Expanded(
          child: TabBarView(
            controller: _tabs,
            children: const [
              _FollowFeed(),
              _NiceFriendFeed(),
              _RecommendedUsers(),
            ],
          ),
        ),
      ],
    );
  }
}

/// 关注的插画与小说。公开与私人各为一组，与现有版本的筛选一致。
class _FollowFeed extends StatefulWidget {
  const _FollowFeed();

  @override
  State<_FollowFeed> createState() => _FollowFeedState();
}

class _FollowFeedState extends State<_FollowFeed> {
  String _restrict = 'public';
  bool _novels = false;

  @override
  Widget build(BuildContext context) {
    final settings = AppSettings.instance;
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
          child: Row(
            children: [
              SegmentedButton<bool>(
                segments: const [
                  ButtonSegment(value: false, label: Text('插画')),
                  ButtonSegment(value: true, label: Text('小说')),
                ],
                selected: {_novels},
                onSelectionChanged: (value) => setState(() => _novels = value.first),
              ),
              const SizedBox(width: 12),
              SegmentedButton<String>(
                segments: const [
                  ButtonSegment(value: 'public', label: Text('公开')),
                  ButtonSegment(value: 'private', label: Text('私人')),
                ],
                selected: {_restrict},
                onSelectionChanged: (value) => setState(() => _restrict = value.first),
              ),
            ],
          ),
        ),
        Expanded(
          child: _novels
              ? PagedGrid<NovelSummary>(
                  // 换筛选条件时重建，从第一页重新取。
                  key: ValueKey('novel-$_restrict'),
                  delegate: SliverGridDelegateWithFixedCrossAxisCount(
                    crossAxisCount: gridColumns(
                      context,
                      settings.novelMaxColumnWidth,
                      settings.novelMaxColumns,
                    ),
                    crossAxisSpacing: 12,
                    mainAxisSpacing: 12,
                    childAspectRatio: 0.62,
                  ),
                  loadFirst: () async {
                    final page = await fetchFollowNovels(restrict: _restrict);
                    return (items: page.novels, nextUrl: page.nextUrl);
                  },
                  loadNext: (nextUrl) async {
                    final page = await fetchNextNovelPage(nextUrl: nextUrl);
                    return (items: page.novels, nextUrl: page.nextUrl);
                  },
                  emptyLabel: '关注的作者还没有新小说',
                  itemBuilder: (context, novel, index) => NovelCard(
                    novel: novel,
                    titleMaxLines: settings.novelTitleMaxLines,
                    onTap: () => Navigator.of(context).push(
                      MaterialPageRoute<void>(
                        builder: (_) => NovelDetailPage(
                          novelId: novel.id,
                          initialTitle: novel.title,
                        ),
                      ),
                    ),
                  ),
                )
              : PagedGrid<IllustSummary>(
                  key: ValueKey('illust-$_restrict'),
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
                    final page = await fetchFollowIllusts(restrict: _restrict);
                    return (items: page.illusts, nextUrl: page.nextUrl);
                  },
                  loadNext: (nextUrl) async {
                    final page = await fetchNextIllustPage(nextUrl: nextUrl);
                    return (items: page.illusts, nextUrl: page.nextUrl);
                  },
                  emptyLabel: '关注的作者还没有新作品',
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
        ),
      ],
    );
  }
}

/// 好P友的作品流。
class _NiceFriendFeed extends StatelessWidget {
  const _NiceFriendFeed();

  @override
  Widget build(BuildContext context) {
    final settings = AppSettings.instance;
    return PagedGrid<IllustSummary>(
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
        final page = await fetchNiceFriendIllusts();
        return (items: page.illusts, nextUrl: page.nextUrl);
      },
      loadNext: (nextUrl) async {
        final page = await fetchNextIllustPage(nextUrl: nextUrl);
        return (items: page.illusts, nextUrl: page.nextUrl);
      },
      emptyLabel: '好P友还没有作品',
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
    );
  }
}

/// 推荐用户货架。
class _RecommendedUsers extends StatefulWidget {
  const _RecommendedUsers();

  @override
  State<_RecommendedUsers> createState() => _RecommendedUsersState();
}

class _RecommendedUsersState extends State<_RecommendedUsers> {
  late Future<UserListPage> _users = fetchRecommendedUsers();

  void _reload() {
    setState(() => _users = fetchRecommendedUsers());
  }

  @override
  Widget build(BuildContext context) {
    return AsyncSection<UserListPage>(
      future: _users,
      onRetry: _reload,
      errorLabel: '加载推荐用户失败',
      builder: (context, page) {
        if (page.users.isEmpty) {
          return const Center(child: Text('没有推荐用户'));
        }
        return ListView.separated(
          itemCount: page.users.length,
          separatorBuilder: (_, __) => const Divider(height: 1),
          itemBuilder: (context, index) {
            final user = page.users[index];
            return ListTile(
              leading: SizedBox(
                width: 44,
                height: 44,
                child: ClipOval(child: RustImage(url: user.avatarUrl, errorLabel: '')),
              ),
              title: Text(user.name),
              subtitle: Text('@${user.account}'),
              trailing: user.isFollowed ? const Icon(Icons.check, size: 18) : null,
              onTap: () => Navigator.of(context).push(
                MaterialPageRoute<void>(
                  builder: (_) => UserPage(userId: user.id, initialName: user.name),
                ),
              ),
            );
          },
        );
      },
    );
  }
}
