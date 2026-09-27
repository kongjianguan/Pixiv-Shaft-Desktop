import 'package:flutter/material.dart';
import 'package:pixiv_shaft/src/rust/api/auth.dart';
import 'package:pixiv_shaft/src/rust/api/illust.dart';
import 'package:pixiv_shaft/src/rust/api/novel.dart';
import 'package:pixiv_shaft/src/rust/api/user.dart';
import 'package:pixiv_shaft/src/ui/browse_history_page.dart';
import 'package:pixiv_shaft/src/ui/illust_detail_page.dart';
import 'package:pixiv_shaft/src/ui/novel_pages.dart';
import 'package:pixiv_shaft/src/ui/settings_page.dart';
import 'package:pixiv_shaft/src/ui/widgets/illust_card.dart';
import 'package:pixiv_shaft/src/ui/widgets/rust_image.dart';

/// 我的：资料、收藏、创作与浏览历史。
class ProfilePage extends StatefulWidget {
  const ProfilePage({super.key, required this.onLoggedOut});

  final VoidCallback onLoggedOut;

  @override
  State<ProfilePage> createState() => _ProfilePageState();
}

class _ProfilePageState extends State<ProfilePage>
    with SingleTickerProviderStateMixin {
  late final TabController _tabs = TabController(length: 4, vsync: this);
  late Future<UserProfile> _profile = _loadProfile();
  late Future<List<IllustSummary>> _bookmarks = fetchBookmarkedIllusts();
  Future<List<NovelSummary>>? _novels;
  Future<List<IllustSummary>>? _works;

  @override
  void initState() {
    super.initState();
    _tabs.addListener(_loadSelectedTab);
  }

  void _loadSelectedTab() {
    if (_tabs.index == 1 && _novels == null) {
      setState(() => _novels = fetchBookmarkedNovels());
    }
    if (_tabs.index == 2 && _works == null) {
      setState(() => _works = _loadWorks());
    }
  }

  Future<UserProfile> _loadProfile() async {
    final userId = await selfUserId();
    if (userId <= 0) throw StateError('无法读取当前用户');
    return fetchUserDetail(userId: userId);
  }

  Future<List<IllustSummary>> _loadWorks() async {
    final profile = await _profile;
    return fetchUserIllusts(userId: profile.id, illustType: 'illust');
  }

  @override
  void dispose() {
    _tabs.removeListener(_loadSelectedTab);
    _tabs.dispose();
    super.dispose();
  }

  void _reload() {
    setState(() {
      _profile = _loadProfile();
      if (_tabs.index == 0) _bookmarks = fetchBookmarkedIllusts();
      if (_tabs.index == 1) _novels = fetchBookmarkedNovels();
      if (_tabs.index == 2) _works = _loadWorks();
    });
  }

  Future<void> _logout() async {
    await logout();
    widget.onLoggedOut();
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.all(16),
          child: FutureBuilder<UserProfile>(
            future: _profile,
            builder: (context, snapshot) {
              final profile = snapshot.data;
              return Row(
                children: [
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
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          profile?.name ?? '我的',
                          style: Theme.of(context).textTheme.titleMedium,
                        ),
                        if (profile != null) ...[
                          Text('@${profile.account}'),
                          Text('插画 ${profile.totalIllusts} · 漫画 ${profile.totalManga} · 小说 ${profile.totalNovels}'),
                        ],
                        if (snapshot.hasError)
                          TextButton(onPressed: _reload, child: const Text('资料加载失败，重试')),
                      ],
                    ),
                  ),
                  IconButton(
                    onPressed: () => Navigator.of(context).push(
                      MaterialPageRoute<void>(builder: (_) => const SettingsPage()),
                    ),
                    icon: const Icon(Icons.settings_outlined),
                    tooltip: '设置',
                  ),
                  TextButton(onPressed: _logout, child: const Text('退出登录')),
                ],
              );
            },
          ),
        ),
        Center(
          child: TabBar(
            controller: _tabs,
            isScrollable: true,
            tabAlignment: TabAlignment.start,
            tabs: const [
              Tab(text: '插画收藏'),
              Tab(text: '小说收藏'),
              Tab(text: '我的作品'),
              Tab(text: '历史'),
            ],
          ),
        ),
        Expanded(
          child: TabBarView(
            controller: _tabs,
            children: [
              AsyncSection<List<IllustSummary>>(
                future: _bookmarks,
                onRetry: _reload,
                errorLabel: '加载收藏失败',
                builder: (context, illusts) => IllustGrid(
                  illusts: illusts,
                  emptyLabel: '还没有收藏',
                  onOpen: (illust) => Navigator.of(context).push(
                    MaterialPageRoute<void>(
                      builder: (_) => IllustDetailPage(
                        illustId: illust.id,
                        initialTitle: illust.title,
                      ),
                    ),
                  ),
                ),
              ),
              if (_novels == null)
                const SizedBox.shrink()
              else AsyncSection<List<NovelSummary>>(
                future: _novels!,
                onRetry: _reload,
                errorLabel: '加载收藏小说失败',
                builder: (context, novels) => NovelGrid(
                  novels: novels,
                  emptyLabel: '还没有收藏小说',
                  onOpen: (novel) => Navigator.of(context).push(
                    MaterialPageRoute<void>(
                      builder: (_) => NovelDetailPage(
                        novelId: novel.id,
                        initialTitle: novel.title,
                      ),
                    ),
                  ),
                ),
              ),
              if (_works == null)
                const SizedBox.shrink()
              else AsyncSection<List<IllustSummary>>(
                future: _works!,
                onRetry: _reload,
                errorLabel: '加载我的作品失败',
                builder: (context, illusts) => IllustGrid(
                  illusts: illusts,
                  emptyLabel: '还没有公开作品',
                  onOpen: (illust) => Navigator.of(context).push(
                    MaterialPageRoute<void>(
                      builder: (_) => IllustDetailPage(
                        illustId: illust.id,
                        initialTitle: illust.title,
                      ),
                    ),
                  ),
                ),
              ),
              const BrowseHistoryView(),
            ],
          ),
        ),
      ],
    );
  }
}
