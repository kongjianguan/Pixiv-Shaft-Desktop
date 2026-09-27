import 'package:flutter/material.dart';
import 'package:pixiv_shaft/src/rust/api/illust.dart';
import 'package:pixiv_shaft/src/rust/api/user.dart';
import 'package:pixiv_shaft/src/settings/app_settings.dart';
import 'package:pixiv_shaft/src/ui/illust_detail_page.dart';
import 'package:pixiv_shaft/src/ui/user_list_page.dart';
import 'package:pixiv_shaft/src/ui/widgets/illust_card.dart';
import 'package:pixiv_shaft/src/ui/widgets/paged_grid.dart';
import 'package:pixiv_shaft/src/ui/widgets/rust_image.dart';

/// 作者页面：资料、作品列表与关注操作。
class UserPage extends StatefulWidget {
  const UserPage({super.key, required this.userId, this.initialName});

  final int userId;
  final String? initialName;

  @override
  State<UserPage> createState() => _UserPageState();
}

class _UserPageState extends State<UserPage> with SingleTickerProviderStateMixin {
  late final TabController _tabs = TabController(length: 2, vsync: this);
  late Future<UserProfile> _profile = _fetchProfile();
  int _refresh = 0;
  bool _following = false;
  bool _working = false;

  Future<UserProfile> _fetchProfile() async {
    final profile = await fetchUserDetail(userId: widget.userId);
    if (mounted) setState(() => _following = profile.isFollowed);
    return profile;
  }

  Future<void> _toggleFollow() async {
    setState(() => _working = true);
    try {
      if (_following) {
        await unfollowUser(userId: widget.userId);
      } else {
        await followUser(userId: widget.userId, restrict: 'public');
      }
      if (mounted) setState(() => _following = !_following);
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('操作失败：$error')),
        );
      }
    } finally {
      if (mounted) setState(() => _working = false);
    }
  }

  void _reload() {
    setState(() {
      _profile = _fetchProfile();
      _refresh++;
    });
  }

  @override
  void dispose() {
    _tabs.dispose();
    super.dispose();
  }

  Widget _feed(Future<IllustPage> Function() loadFirst, String emptyLabel) {
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
        onTap: () => Navigator.of(context).push(MaterialPageRoute<void>(
          builder: (_) => IllustDetailPage(
            illustId: illust.id,
            initialTitle: illust.title,
          ),
        )),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(widget.initialName ?? '作者')),
      body: Column(
        children: [
          AsyncSection<UserProfile>(
            future: _profile,
            onRetry: _reload,
            errorLabel: '加载资料失败',
            builder: (context, profile) => Padding(
              padding: const EdgeInsets.all(16),
              child: Row(
                children: [
                  SizedBox(
                    width: 72,
                    height: 72,
                    child: ClipOval(
                      child: RustImage(
                        url: profile.avatarUrl,
                        errorLabel: '',
                      ),
                    ),
                  ),
                  const SizedBox(width: 16),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          profile.name,
                          style: Theme.of(context).textTheme.titleMedium,
                        ),
                        Text(
                          '@${profile.account}',
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                        const SizedBox(height: 4),
                        Text(
                          '插画 ${profile.totalIllusts} · 漫画 ${profile.totalManga} · 小说 ${profile.totalNovels}',
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                      ],
                    ),
                  ),
                  FilledButton.tonal(
                    onPressed: _working ? null : _toggleFollow,
                    child: Text(_following ? '已关注' : '关注'),
                  ),
                  const SizedBox(width: 8),
                  IconButton(
                    onPressed: () => Navigator.of(context).push(
                      MaterialPageRoute<void>(
                        builder: (_) => UserListScreen(userId: widget.userId),
                      ),
                    ),
                    icon: const Icon(Icons.people_outline),
                    tooltip: '关注的人与粉丝',
                  ),
                ],
              ),
            ),
          ),
          const Divider(height: 1),
          TabBar(
            controller: _tabs,
            tabs: const [Tab(text: '作品'), Tab(text: '收藏')],
          ),
          Expanded(
            child: TabBarView(
              controller: _tabs,
              children: [
                KeyedSubtree(
                  key: ValueKey('works-$_refresh'),
                  child: _feed(
                    () => fetchUserIllusts(userId: widget.userId, illustType: 'illust'),
                    '该作者没有公开作品',
                  ),
                ),
                KeyedSubtree(
                  key: ValueKey('bookmarks-$_refresh'),
                  child: _feed(
                    () => fetchUserBookmarks(userId: widget.userId),
                    '该作者没有公开收藏',
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
