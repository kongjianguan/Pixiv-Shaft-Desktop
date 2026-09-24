import 'package:flutter/material.dart';
import 'package:pixiv_shaft/src/rust/api/auth.dart';
import 'package:pixiv_shaft/src/rust/api/illust.dart';
import 'package:pixiv_shaft/src/rust/api/store.dart';
import 'package:pixiv_shaft/src/ui/illust_detail_page.dart';
import 'package:pixiv_shaft/src/ui/widgets/illust_card.dart';

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

/// 我的：收藏插画与退出登录。
class ProfilePage extends StatefulWidget {
  const ProfilePage({super.key, required this.onLoggedOut});

  final VoidCallback onLoggedOut;

  @override
  State<ProfilePage> createState() => _ProfilePageState();
}

class _ProfilePageState extends State<ProfilePage> {
  late Future<List<IllustSummary>> _bookmarks = fetchBookmarkedIllusts();

  void _reload() {
    setState(() => _bookmarks = fetchBookmarkedIllusts());
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
          padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
          child: Row(
            children: [
              Expanded(
                child: Text('我的收藏', style: Theme.of(context).textTheme.titleLarge),
              ),
              IconButton(
                onPressed: _reload,
                icon: const Icon(Icons.refresh),
                tooltip: '重新加载',
              ),
              TextButton(onPressed: _logout, child: const Text('退出登录')),
            ],
          ),
        ),
        Expanded(
          child: AsyncSection<List<IllustSummary>>(
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
        ),
      ],
    );
  }
}
