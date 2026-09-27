import 'package:flutter/material.dart';
import 'package:pixiv_shaft/src/rust/api/auth.dart';
import 'package:pixiv_shaft/src/rust/api/illust.dart';
import 'package:pixiv_shaft/src/rust/api/novel.dart';
import 'package:pixiv_shaft/src/rust/api/store.dart';
import 'package:pixiv_shaft/src/rust/api/user.dart';
import 'package:pixiv_shaft/src/ui/browse_history_page.dart';
import 'package:pixiv_shaft/src/ui/illust_detail_page.dart';
import 'package:pixiv_shaft/src/ui/novel_pages.dart';
import 'package:pixiv_shaft/src/ui/settings_page.dart';
import 'package:pixiv_shaft/src/ui/widgets/illust_card.dart';
import 'package:pixiv_shaft/src/ui/widgets/rust_image.dart';

/// 搜索：关键词搜索插画，未输入时展示搜索记录。
class SearchPage extends StatefulWidget {
  const SearchPage({super.key});

  @override
  State<SearchPage> createState() => _SearchPageState();
}

class _SearchPageState extends State<SearchPage> {
  final TextEditingController _input = TextEditingController();
  Future<List<SearchRecord>> _history = listSearches(recentLimit: 20);
  Future<List<IllustSummary>>? _results;
  String _keyword = '';

  @override
  void dispose() {
    _input.dispose();
    super.dispose();
  }

  Future<void> _search(String keyword) async {
    final trimmed = keyword.trim();
    if (trimmed.isEmpty) return;
    _input.text = trimmed;
    setState(() {
      _keyword = trimmed;
      _results = searchIllusts(word: trimmed);
    });
    await recordSearch(keyword: trimmed, searchType: 0);
    if (mounted) {
      setState(() => _history = listSearches(recentLimit: 20));
    }
  }

  Future<void> _clearHistory() async {
    await clearSearches();
    if (mounted) {
      setState(() => _history = listSearches(recentLimit: 20));
    }
  }

  @override
  Widget build(BuildContext context) {
    final results = _results;
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
          child: TextField(
            controller: _input,
            onSubmitted: _search,
            decoration: InputDecoration(
              hintText: '搜索插画',
              border: const OutlineInputBorder(),
              prefixIcon: const Icon(Icons.search),
              suffixIcon: IconButton(
                icon: const Icon(Icons.arrow_forward),
                tooltip: '搜索',
                onPressed: () => _search(_input.text),
              ),
            ),
          ),
        ),
        Expanded(
          child: results == null
              ? _historyList(context)
              : AsyncSection<List<IllustSummary>>(
                  future: results,
                  onRetry: () => _search(_keyword),
                  errorLabel: '搜索失败',
                  builder: (context, illusts) => IllustGrid(
                    illusts: illusts,
                    emptyLabel: '没有匹配的作品',
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
        ),
      ],
    );
  }

  Widget _historyList(BuildContext context) {
    return FutureBuilder<List<SearchRecord>>(
      future: _history,
      builder: (context, snapshot) {
        final entries = snapshot.data ?? const <SearchRecord>[];
        if (entries.isEmpty) {
          return const Center(child: Text('还没有搜索记录'));
        }
        return Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text('搜索记录', style: Theme.of(context).textTheme.titleSmall),
                  ),
                  TextButton(onPressed: _clearHistory, child: const Text('清空')),
                ],
              ),
              const SizedBox(height: 8),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  for (final entry in entries)
                    ActionChip(
                      label: Text(entry.keyword),
                      onPressed: () => _search(entry.keyword),
                    ),
                ],
              ),
            ],
          ),
        );
      },
    );
  }
}

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
  late Future<List<NovelSummary>> _novels = fetchBookmarkedNovels();
  late Future<List<IllustSummary>> _works = _loadWorks();

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
    _tabs.dispose();
    super.dispose();
  }

  void _reload() {
    setState(() {
      _profile = _loadProfile();
      _bookmarks = fetchBookmarkedIllusts();
      _novels = fetchBookmarkedNovels();
      _works = _loadWorks();
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
              AsyncSection<List<NovelSummary>>(
                future: _novels,
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
              AsyncSection<List<IllustSummary>>(
                future: _works,
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
