import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:pixiv_shaft/src/rust/api/discovery.dart';
import 'package:pixiv_shaft/src/rust/api/illust.dart';
import 'package:pixiv_shaft/src/rust/api/novel.dart';
import 'package:pixiv_shaft/src/rust/api/search.dart';
import 'package:pixiv_shaft/src/rust/api/store.dart';
import 'package:pixiv_shaft/src/rust/api/user.dart';
import 'package:pixiv_shaft/src/settings/app_settings.dart';
import 'package:pixiv_shaft/src/ui/illust_detail_page.dart';
import 'package:pixiv_shaft/src/ui/novel_pages.dart';
import 'package:pixiv_shaft/src/ui/search_filter_sheet.dart';
import 'package:pixiv_shaft/src/ui/user_page.dart';
import 'package:pixiv_shaft/src/ui/widgets/illust_card.dart';
import 'package:pixiv_shaft/src/ui/widgets/paged_grid.dart';
import 'package:pixiv_shaft/src/ui/widgets/rust_image.dart';
import 'package:url_launcher/url_launcher.dart';

/// 搜索入口保留插画、小说、用户三个结果页，以及搜索前的标签与历史记录。
class SearchPage extends StatefulWidget {
  const SearchPage({super.key});

  @override
  State<SearchPage> createState() => _SearchPageState();
}

class _SearchPageState extends State<SearchPage> with SingleTickerProviderStateMixin {
  final TextEditingController _input = TextEditingController();
  late final TabController _tabs = TabController(length: 3, vsync: this);
  Timer? _suggestionTimer;
  int _suggestionGeneration = 0;
  int _optionsGeneration = 0;
  List<SearchSuggestion> _suggestions = const [];
  String? _suggestionError;
  Future<List<SearchRecord>> _history = listSearches(recentLimit: 50);
  Future<List<TrendingTag>> _trending = fetchTrendingTags();
  SearchOptions? _options;
  String? _optionsError;
  String? _clipboardSuggestion;
  final List<String> _tags = [];
  final List<Map<String, String>> _filters = [{}, {}];
  final List<int> _refreshTicks = [0, 0, 0];
  final Set<int> _visited = {0};
  String _word = '';
  int _tab = 0;

  @override
  void initState() {
    super.initState();
    _input.addListener(_onInputChanged);
    _readClipboard();
  }

  @override
  void dispose() {
    _suggestionTimer?.cancel();
    _input.removeListener(_onInputChanged);
    _input.dispose();
    _tabs.dispose();
    super.dispose();
  }

  Future<void> _readClipboard() async {
    final data = await Clipboard.getData('text/plain');
    final value = data?.text?.trim() ?? '';
    if (!mounted || value.isEmpty) return;
    if (Uri.tryParse(value)?.hasScheme == true || int.tryParse(value) != null) {
      setState(() => _clipboardSuggestion = value);
    }
  }

  void _onInputChanged() {
    final value = _input.text.trim();
    _suggestionTimer?.cancel();
    final generation = ++_suggestionGeneration;
    if (value.isEmpty) {
      setState(() {
        _suggestions = const [];
        _suggestionError = null;
      });
      return;
    }
    _suggestionTimer = Timer(const Duration(milliseconds: 300), () async {
      try {
        final suggestions = await fetchSearchSuggestions(word: value);
        if (!mounted || generation != _suggestionGeneration) return;
        setState(() {
          _suggestions = suggestions;
          _suggestionError = null;
        });
      } catch (error) {
        if (!mounted || generation != _suggestionGeneration) return;
        setState(() {
          _suggestions = const [];
          _suggestionError = '$error';
        });
      }
    });
  }

  Future<void> _loadOptions(String word) async {
    final generation = ++_optionsGeneration;
    final target = _tab == 2
        ? 'partial_match_for_tags'
        : (_filters[_tab]['search_target'] ?? 'partial_match_for_tags');
    try {
      final options = await fetchSearchOptions(word: word, target: target);
      if (!mounted || generation != _optionsGeneration) return;
      setState(() {
        _options = options;
        _optionsError = null;
      });
    } catch (error) {
      if (!mounted || generation != _optionsGeneration) return;
      setState(() => _optionsError = '$error');
    }
  }

  String get _queryText => [..._tags, _input.text.trim()]
      .where((part) => part.isNotEmpty)
      .join(' ');

  void _acceptSuggestion(SearchSuggestion suggestion) {
    _tags.add(suggestion.tag);
    _input.clear();
    _runKeyword(_tags.join(' '));
  }

  void _removeTag(String tag) {
    _tags.remove(tag);
    if (_tags.isEmpty && _input.text.trim().isEmpty) {
      _clearQuery();
    } else {
      _runKeyword(_queryText);
    }
  }

  void _clearQuery() {
    _suggestionTimer?.cancel();
    _suggestionGeneration++;
    _input.clear();
    setState(() {
      _tags.clear();
      _word = '';
      _suggestions = const [];
      _options = null;
      _optionsError = null;
      _trending = fetchTrendingTags();
    });
  }

  void _submit(String value) {
    final query = value.trim();
    if (query.isEmpty) return;
    final uri = Uri.tryParse(query);
    if (uri != null && (uri.scheme == 'http' || uri.scheme == 'https')) {
      _openUrl(uri);
      return;
    }
    final numericId = int.tryParse(query);
    if (numericId != null) {
      _openNumeric(numericId, query);
      return;
    }
    _runKeyword(query);
  }

  Future<void> _openUrl(Uri uri) async {
    final segments = uri.pathSegments;
    if (uri.host == 'pixiv.net' || uri.host.endsWith('.pixiv.net')) {
      final artworkIndex = segments.indexOf('artworks');
      if (artworkIndex >= 0 && segments.length > artworkIndex + 1) {
        final id = int.tryParse(segments[artworkIndex + 1]);
        if (id != null) {
          _openIllust(id);
          return;
        }
      }
      final userIndex = segments.indexOf('users');
      if (userIndex >= 0 && segments.length > userIndex + 1) {
        final id = int.tryParse(segments[userIndex + 1]);
        if (id != null) {
          _openUser(id);
          return;
        }
      }
      final novelIndex = segments.indexOf('novel');
      if (novelIndex >= 0) {
        final id = int.tryParse(uri.queryParameters['id'] ??
            (segments.length > novelIndex + 1 ? segments.last : ''));
        if (id != null) {
          _openNovel(id);
          return;
        }
      }
    }
    final opened = await launchUrl(uri, mode: LaunchMode.externalApplication);
    if (!opened) throw StateError('无法打开链接：$uri');
  }

  Future<void> _openNumeric(int id, String value) async {
    await showDialog<void>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('识别到数字 ID'),
        content: const Text('请选择要打开的对象类型，或继续按关键词搜索。'),
        actions: [
          TextButton(
            onPressed: () {
              Navigator.pop(dialogContext);
              _runKeyword(value);
            },
            child: const Text('关键词'),
          ),
          TextButton(
            onPressed: () {
              Navigator.pop(dialogContext);
              _openNovel(id);
            },
            child: const Text('小说'),
          ),
          TextButton(
            onPressed: () {
              Navigator.pop(dialogContext);
              _openUser(id);
            },
            child: const Text('用户'),
          ),
          FilledButton(
            onPressed: () {
              Navigator.pop(dialogContext);
              _openIllust(id);
            },
            child: const Text('作品'),
          ),
        ],
      ),
    );
  }

  void _runKeyword(String query) {
    final normalized = query
        .trim()
        .split(RegExp(r'\s+'))
        .where((part) => part.isNotEmpty)
        .toSet()
        .join(' ');
    if (normalized.isEmpty) return;
    _suggestionTimer?.cancel();
    _suggestionGeneration++;
    _input.clear();
    setState(() {
      _tags
        ..clear()
        ..addAll(normalized.split(' ').toSet());
      _word = normalized;
      _suggestions = const [];
      _options = null;
      _optionsError = null;
      _visited.add(_tab);
      _refreshTicks[_tab]++;
    });
    _loadOptions(normalized);
    _saveHistory(normalized);
  }

  Future<void> _saveHistory(String word) async {
    await recordSearch(keyword: word, searchType: 0);
    if (mounted) setState(() => _history = listSearches(recentLimit: 50));
  }

  void _selectTab(int tab) {
    if (tab == _tab) return;
    setState(() {
      _tab = tab;
      _visited.add(tab);
      _options = null;
      _optionsError = null;
    });
    if (_word.isNotEmpty) _loadOptions(_word);
  }

  void _refresh() {
    if (_word.isEmpty) {
      setState(() {
        _history = listSearches(recentLimit: 50);
        _trending = fetchTrendingTags();
      });
    } else {
      setState(() => _refreshTicks[_tab]++);
    }
  }

  Future<void> _openFilter() async {
    final next = await showModalBottomSheet<Map<String, String>>(
      context: context,
      isScrollControlled: true,
      constraints: const BoxConstraints(maxWidth: 780),
      builder: (context) => FractionallySizedBox(
        heightFactor: 0.85,
        child: SearchFilterSheet(
          isNovel: _tab == 1,
          initialFilter: _filters[_tab],
          options: _options,
        ),
      ),
    );
    if (!mounted || next == null) return;
    setState(() {
      _filters[_tab] = next;
      _refreshTicks[_tab]++;
    });
    if (_word.isNotEmpty) _loadOptions(_word);
  }

  Map<String, String> _queryParameters(Map<String, String> filter) {
    final params = <String, String>{
      'word': _word,
      'sort': filter['sort'] ?? 'date_desc',
      'search_target': filter['search_target'] ?? 'partial_match_for_tags',
      'merge_plain_keyword_results': 'true',
      'include_translated_tag_results': 'true',
      'search_ai_type': filter['_ai'] == 'exclude' ? '0' : '1',
    };
    for (final entry in filter.entries) {
      if (!entry.key.startsWith('_')) params[entry.key] = entry.value;
    }
    switch (filter['_resolution']) {
      case 'above3000':
        params['width_min'] = '3000';
        params['height_min'] = '3000';
      case 'between1000and2999':
        params['width_min'] = '1000';
        params['width_max'] = '2999';
        params['height_min'] = '1000';
        params['height_max'] = '2999';
      case 'below1000':
        params['width_max'] = '999';
        params['height_max'] = '999';
    }
    final unit = filter['_length_unit'] ?? 'text_length';
    if (filter['_length_min']?.isNotEmpty == true) params['${unit}_min'] = filter['_length_min']!;
    if (filter['_length_max']?.isNotEmpty == true) params['${unit}_max'] = filter['_length_max']!;
    return params;
  }

  String _searchPath(String kind, Map<String, String> filter) {
    final params = _queryParameters(filter);
    final prefix = params['sort'] == 'popular_preview'
        ? '/v1/search/popular-preview/$kind'
        : '/v1/search/$kind';
    return Uri(path: prefix, queryParameters: params).toString();
  }

  bool _visible(int xRestrict, int aiType, Map<String, String> filter) {
    final r18 = AppSettings.instance.showR18 ? (filter['_r18'] ?? 'all') : 'safe';
    if (r18 == 'safe' && xRestrict > 0) return false;
    if (r18 == 'only' && xRestrict <= 0) return false;
    final ai = filter['_ai'] ?? 'all';
    if (ai == 'exclude' && aiType == 2) return false;
    if (ai == 'only' && aiType != 2) return false;
    return true;
  }

  void _openIllust(int id, [String? title]) => Navigator.of(context).push(
        MaterialPageRoute<void>(
          builder: (_) => IllustDetailPage(illustId: id, initialTitle: title),
        ),
      );

  void _openNovel(int id, [String? title]) => Navigator.of(context).push(
        MaterialPageRoute<void>(
          builder: (_) => NovelDetailPage(novelId: id, initialTitle: title),
        ),
      );

  void _openUser(int id, [String? name]) => Navigator.of(context).push(
        MaterialPageRoute<void>(
          builder: (_) => UserPage(userId: id, initialName: name),
        ),
      );

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        if (_tags.isNotEmpty)
          SizedBox(
            height: 42,
            child: ListView.separated(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: 8),
              itemCount: _tags.length,
              separatorBuilder: (_, _) => const SizedBox(width: 8),
              itemBuilder: (context, index) {
                final tag = _tags[index];
                return InputChip(
                  label: Text(tag),
                  onDeleted: () => _removeTag(tag),
                );
              },
            ),
          ),
        Padding(
          padding: const EdgeInsets.all(8),
          child: Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _input,
                  onSubmitted: (_) => _submit(_queryText),
                  decoration: InputDecoration(
                    hintText: '搜索标签、标题或作者',
                    border: const OutlineInputBorder(),
                    prefixIcon: const Icon(Icons.search),
                    suffixIcon: _input.text.isEmpty
                        ? null
                        : IconButton(
                            tooltip: '清除输入',
                            onPressed: _input.clear,
                            icon: const Icon(Icons.close),
                          ),
                  ),
                ),
              ),
              if (_tab != 2)
                IconButton(
                  tooltip: '筛选',
                  onPressed: _openFilter,
                  icon: const Icon(Icons.filter_alt_outlined),
                ),
              IconButton(
                tooltip: '搜索',
                onPressed: () => _submit(_queryText),
                icon: const Icon(Icons.arrow_forward),
              ),
              if (_word.isNotEmpty && _input.text.isEmpty)
                IconButton(
                  tooltip: '清除搜索',
                  onPressed: _clearQuery,
                  icon: const Icon(Icons.close),
                ),
            ],
          ),
        ),
        if (_suggestions.isNotEmpty)
          SizedBox(
            height: 180,
            child: ListView.builder(
              itemCount: _suggestions.length,
              itemBuilder: (context, index) {
                final suggestion = _suggestions[index];
                return ListTile(
                  dense: true,
                  leading: const Icon(Icons.search),
                  title: Text(suggestion.tag),
                  subtitle: suggestion.translatedName.isEmpty
                      ? null
                      : Text(suggestion.translatedName),
                  onTap: () => _acceptSuggestion(suggestion),
                );
              },
            ),
          ),
        if (_suggestionError != null)
          TextButton(
            onPressed: _onInputChanged,
            child: const Text('搜索建议加载失败，重试'),
          ),
        if (_optionsError != null && _word.isNotEmpty && _tab != 2)
          TextButton(
            onPressed: () => _loadOptions(_word),
            child: const Text('筛选选项加载失败，重试'),
          ),
        TabBar(
          controller: _tabs,
          onTap: _selectTab,
          tabs: const [Tab(text: '插画'), Tab(text: '小说'), Tab(text: '用户')],
        ),
        if (_tab != 2)
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
            child: Row(
              children: [
                for (final (value, label) in searchSorts)
                  Padding(
                    padding: const EdgeInsets.only(right: 8),
                    child: FilterChip(
                      label: Text(label),
                      selected: (_filters[_tab]['sort'] ?? 'date_desc') == value,
                      onSelected: (_) => setState(() {
                        _filters[_tab]['sort'] = value;
                        _refreshTicks[_tab]++;
                      }),
                    ),
                  ),
                if (_filters[_tab].isNotEmpty)
                  Text('已筛选 ${_filters[_tab].length} 项'),
              ],
            ),
          ),
        Expanded(
          child: _word.isEmpty
              ? _landing()
              : Stack(
                  children: [
                    for (final tab in _visited)
                      Offstage(
                        offstage: tab != _tab,
                        child: TickerMode(
                          enabled: tab == _tab,
                          child: KeyedSubtree(
                            key: ValueKey('$tab-${_refreshTicks[tab]}-$_word'),
                            child: _resultsFor(tab),
                          ),
                        ),
                      ),
                  ],
                ),
        ),
      ],
    );
  }

  Widget _landing() {
    return ListView(
      padding: const EdgeInsets.all(12),
      children: [
        if (_clipboardSuggestion != null)
          ListTile(
            title: Text('剪贴板内容：$_clipboardSuggestion', maxLines: 1),
            trailing: Wrap(children: [
              TextButton(
                onPressed: () => _submit(_clipboardSuggestion!),
                child: const Text('使用'),
              ),
              IconButton(
                tooltip: '关闭',
                onPressed: () => setState(() => _clipboardSuggestion = null),
                icon: const Icon(Icons.close),
              ),
            ]),
          ),
        Text('热门标签', style: Theme.of(context).textTheme.titleSmall),
        FutureBuilder<List<TrendingTag>>(
          future: _trending,
          builder: (context, snapshot) {
            if (snapshot.hasError) {
              return TextButton(
                onPressed: _refresh,
                child: const Text('热门标签加载失败，重试'),
              );
            }
            if (!snapshot.hasData) return const LinearProgressIndicator();
            return SizedBox(
              height: 55,
              child: ListView.separated(
                scrollDirection: Axis.horizontal,
                itemCount: snapshot.data!.length > 15 ? 15 : snapshot.data!.length,
                separatorBuilder: (_, _) => const SizedBox(width: 8),
                itemBuilder: (context, index) {
                  final tag = snapshot.data![index];
                  return ActionChip(
                    label: Text(tag.translatedName.isEmpty ? tag.tag : tag.translatedName),
                    onPressed: () => _runKeyword(tag.tag),
                  );
                },
              ),
            );
          },
        ),
        const SizedBox(height: 8),
        FutureBuilder<List<SearchRecord>>(
          future: _history,
          builder: (context, snapshot) {
            if (snapshot.hasError) {
              return TextButton(
                onPressed: _refresh,
                child: const Text('搜索记录加载失败，重试'),
              );
            }
            if (!snapshot.hasData) return const LinearProgressIndicator();
            final pinned = snapshot.data!.where((entry) => entry.pinned).toList();
            final recent = snapshot.data!.where((entry) => !entry.pinned).toList();
            if (pinned.isEmpty && recent.isEmpty) {
              return const Text('还没有搜索记录');
            }
            return Column(children: [
              if (pinned.isNotEmpty) _historyGroup('置顶搜索', pinned, true),
              if (recent.isNotEmpty) _historyGroup('最近搜索', recent, false),
            ]);
          },
        ),
      ],
    );
  }

  Widget _historyGroup(String title, List<SearchRecord> entries, bool pinned) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(children: [
          Expanded(child: Text(title, style: Theme.of(context).textTheme.titleSmall)),
          TextButton(
            onPressed: () async {
              await clearSearchesGroup(pinned: pinned);
              if (mounted) setState(() => _history = listSearches(recentLimit: 50));
            },
            child: const Text('清空'),
          ),
        ]),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            for (final entry in entries)
              InputChip(
                label: Text(entry.keyword),
                onPressed: () => _runKeyword(entry.keyword),
                onDeleted: () async {
                  await deleteSearch(id: entry.id);
                  if (mounted) setState(() => _history = listSearches(recentLimit: 50));
                },
                deleteIcon: const Icon(Icons.delete_outline, size: 18),
                avatar: Tooltip(
                  message: entry.pinned ? '取消置顶' : '置顶',
                  child: InkWell(
                    onTap: () async {
                      await setSearchPinned(id: entry.id, pinned: !entry.pinned);
                      if (mounted) setState(() => _history = listSearches(recentLimit: 50));
                    },
                    child: Icon(
                      entry.pinned ? Icons.push_pin : Icons.push_pin_outlined,
                      size: 16,
                    ),
                  ),
                ),
              ),
          ],
        ),
      ],
    );
  }

  Widget _resultsFor(int tab) {
    if (tab == 0) {
      final filter = _filters[0];
      return PagedGrid<IllustSummary>(
        masonry: true,
        padding: const EdgeInsets.all(4),
        emptyLabel: '没有找到插画',
        loadFirst: () async {
          final page = await fetchIllustPage(path: _searchPath('illust', filter));
          return (
            items: page.illusts.where((item) => _visible(item.xRestrict, item.aiType, filter)).toList(),
            nextUrl: page.nextUrl,
          );
        },
        loadNext: (nextUrl) async {
          final page = await fetchNextIllustPage(nextUrl: nextUrl);
          return (
            items: page.illusts.where((item) => _visible(item.xRestrict, item.aiType, filter)).toList(),
            nextUrl: page.nextUrl,
          );
        },
        itemBuilder: (context, illust, index) => IllustCard(
          illust: illust,
          titleMaxLines: AppSettings.instance.workTitleMaxLines,
          onTap: () => _openIllust(illust.id, illust.title),
        ),
      );
    }
    if (tab == 1) {
      final filter = _filters[1];
      return _PagedSearchList<NovelSummary>(
        emptyLabel: '没有找到小说',
        loadFirst: () async {
          final page = await fetchNovelPage(path: _searchPath('novel', filter));
          return (
            items: page.novels.where((item) => _visible(item.xRestrict, item.aiType, filter)).toList(),
            nextUrl: page.nextUrl,
          );
        },
        loadNext: (nextUrl) async {
          final page = await fetchNextNovelPage(nextUrl: nextUrl);
          return (
            items: page.novels.where((item) => _visible(item.xRestrict, item.aiType, filter)).toList(),
            nextUrl: page.nextUrl,
          );
        },
        itemBuilder: (context, novel) => Card(
          clipBehavior: Clip.antiAlias,
          child: InkWell(
            onTap: () => _openNovel(novel.id, novel.title),
            child: Padding(
              padding: const EdgeInsets.all(10),
              child: Row(children: [
                SizedBox(width: 75, height: 100, child: RustImage(url: novel.coverUrl)),
                const SizedBox(width: 12),
                Expanded(child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(novel.title, maxLines: 2, overflow: TextOverflow.ellipsis),
                    Text(novel.authorName),
                    Text('${novel.textLength} 字'),
                  ],
                )),
              ]),
            ),
          ),
        ),
      );
    }
    return _PagedSearchList<UserPreview>(
      emptyLabel: '没有找到用户',
      loadFirst: () async {
        final page = await searchUsers(word: _word);
        return (items: page.users, nextUrl: page.nextUrl);
      },
      loadNext: (nextUrl) async {
        final page = await fetchNextUsers(nextUrl: nextUrl);
        return (items: page.users, nextUrl: page.nextUrl);
      },
      itemBuilder: (context, user) => Card(
        child: ListTile(
          leading: SizedBox(
            width: 48,
            height: 48,
            child: ClipOval(child: RustImage(url: user.avatarUrl)),
          ),
          title: Text(user.name),
          subtitle: Text('@${user.account}'),
          trailing: user.latestIllustUrl.isEmpty
              ? null
              : SizedBox(width: 72, height: 72, child: RustImage(url: user.latestIllustUrl)),
          onTap: () => _openUser(user.id, user.name),
        ),
      ),
    );
  }
}

class _PagedSearchList<T> extends StatefulWidget {
  const _PagedSearchList({
    required this.loadFirst,
    required this.loadNext,
    required this.itemBuilder,
    required this.emptyLabel,
  });

  final Future<FeedPage<T>> Function() loadFirst;
  final Future<FeedPage<T>> Function(String nextUrl) loadNext;
  final Widget Function(BuildContext, T) itemBuilder;
  final String emptyLabel;

  @override
  State<_PagedSearchList<T>> createState() => _PagedSearchListState<T>();
}

class _PagedSearchListState<T> extends State<_PagedSearchList<T>> {
  final ScrollController _scroll = ScrollController();
  List<T> _items = const [];
  String _nextUrl = '';
  String? _error;
  bool _loading = true;
  bool _loadingMore = false;

  @override
  void initState() {
    super.initState();
    _scroll.addListener(_onScroll);
    _loadFirst();
  }

  @override
  void dispose() {
    _scroll.removeListener(_onScroll);
    _scroll.dispose();
    super.dispose();
  }

  void _onScroll() {
    if (_scroll.hasClients &&
        _scroll.position.pixels >= _scroll.position.maxScrollExtent - 400) {
      _loadMore();
    }
  }

  Future<void> _loadFirst() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final page = await widget.loadFirst();
      if (!mounted) return;
      setState(() {
        _items = page.items;
        _nextUrl = page.nextUrl;
        _loading = false;
      });
      _checkNext();
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _error = '$error';
        _loading = false;
      });
    }
  }

  Future<void> _loadMore() async {
    if (_loading || _loadingMore || _nextUrl.isEmpty) return;
    final nextUrl = _nextUrl;
    setState(() {
      _loadingMore = true;
      _error = null;
    });
    try {
      final page = await widget.loadNext(nextUrl);
      if (!mounted) return;
      setState(() {
        _items = [..._items, ...page.items];
        _nextUrl = page.nextUrl;
        _loadingMore = false;
      });
      _checkNext();
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _error = '$error';
        _loadingMore = false;
      });
    }
  }

  void _checkNext() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || _nextUrl.isEmpty || _loadingMore) return;
      if (_items.isEmpty ||
          (_scroll.hasClients && _scroll.position.maxScrollExtent <= 400)) {
        _loadMore();
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) return const Center(child: CircularProgressIndicator());
    if (_items.isEmpty && _error != null) {
      return Center(
        child: TextButton(onPressed: _loadFirst, child: Text('搜索失败：$_error，重试')),
      );
    }
    if (_items.isEmpty) return Center(child: Text(widget.emptyLabel));
    return ListView.builder(
      controller: _scroll,
      padding: const EdgeInsets.all(10),
      itemCount: _items.length + 1,
      itemBuilder: (context, index) {
        if (index < _items.length) return widget.itemBuilder(context, _items[index]);
        if (_error != null) {
          return TextButton(onPressed: _loadMore, child: Text('加载更多失败：$_error，重试'));
        }
        return _loadingMore
            ? const Center(child: CircularProgressIndicator())
            : const SizedBox.shrink();
      },
    );
  }
}
