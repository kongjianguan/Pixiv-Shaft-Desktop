import 'package:flutter/material.dart';
import 'package:pixiv_shaft/src/rust/api/discovery.dart';
import 'package:pixiv_shaft/src/ui/widgets/rust_image.dart';
import 'package:url_launcher/url_launcher.dart';

Future<void> openSpotlightArticle(SpotlightArticle article) async {
  final uri = Uri.parse('https://www.pixivision.net').resolve(article.articleUrl);
  final opened = await launchUrl(uri, mode: LaunchMode.externalApplication);
  if (!opened) throw StateError('无法打开文章：$uri');
}

class PixivisionPage extends StatefulWidget {
  const PixivisionPage({super.key});

  @override
  State<PixivisionPage> createState() => _PixivisionPageState();
}

class _PixivisionPageState extends State<PixivisionPage> {
  int _refresh = 0;

  @override
  Widget build(BuildContext context) {
    return DefaultTabController(
      length: 2,
      child: Scaffold(
        appBar: AppBar(
          title: const Text('Pixivision'),
          actions: [
            IconButton(
              tooltip: '刷新',
              onPressed: () => setState(() => _refresh++),
              icon: const Icon(Icons.refresh),
            ),
          ],
          bottom: const TabBar(tabs: [Tab(text: '插画'), Tab(text: '漫画')]),
        ),
        body: TabBarView(
          children: [
            SpotlightArticleList(key: ValueKey('illust-$_refresh'), category: 'illust'),
            SpotlightArticleList(key: ValueKey('manga-$_refresh'), category: 'manga'),
          ],
        ),
      ),
    );
  }
}

/// 每个分类独立维护文章游标和滚动位置。
class SpotlightArticleList extends StatefulWidget {
  const SpotlightArticleList({super.key, required this.category});

  final String category;

  @override
  State<SpotlightArticleList> createState() => _SpotlightArticleListState();
}

class _SpotlightArticleListState extends State<SpotlightArticleList> {
  final ScrollController _scroll = ScrollController();
  final List<SpotlightArticle> _articles = [];
  String _nextUrl = '';
  String? _error;
  bool _loading = false;
  bool _hasLoaded = false;

  @override
  void initState() {
    super.initState();
    _scroll.addListener(_onScroll);
    _load();
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
      _load();
    }
  }

  Future<void> _load() async {
    if (_loading || (_hasLoaded && _nextUrl.isEmpty)) return;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final page = _hasLoaded
          ? await fetchNextSpotlightPage(nextUrl: _nextUrl)
          : await fetchSpotlightPage(category: widget.category);
      if (!mounted) return;
      setState(() {
        _articles.addAll(page.articles);
        _nextUrl = page.nextUrl;
        _hasLoaded = true;
        _loading = false;
      });
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted &&
            _scroll.hasClients &&
            _scroll.position.maxScrollExtent <= 400) {
          _load();
        }
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _error = '$error';
        _loading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_articles.isEmpty && _loading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_articles.isEmpty && _hasLoaded) {
      return const Center(child: Text('暂无文章'));
    }
    return ListView.builder(
      controller: _scroll,
      padding: const EdgeInsets.all(12),
      itemCount: _articles.length + 1,
      itemBuilder: (context, index) {
        if (index == _articles.length) {
          if (_error != null) {
            return Center(
              child: TextButton(onPressed: _load, child: Text('加载失败：$_error，重试')),
            );
          }
          return _loading
              ? const Center(child: CircularProgressIndicator())
              : const SizedBox.shrink();
        }
        final article = _articles[index];
        return Padding(
          padding: const EdgeInsets.only(bottom: 12),
          child: Card(
            clipBehavior: Clip.antiAlias,
            child: InkWell(
              onTap: () => openSpotlightArticle(article),
              child: Padding(
                padding: const EdgeInsets.all(10),
                child: Row(
                  children: [
                    SizedBox(
                      width: 120,
                      height: 90,
                      child: RustImage(url: article.thumbnailUrl),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(article.title, maxLines: 2, overflow: TextOverflow.ellipsis),
                          if (article.subcategoryLabel.isNotEmpty)
                            Text(article.subcategoryLabel),
                          if (article.publishDate.isNotEmpty)
                            Text(article.publishDate),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        );
      },
    );
  }
}
