import 'package:flutter/material.dart';

/// 一页数据：条目加下一页游标。
typedef FeedPage<T> = ({List<T> items, String nextUrl});

/// 分页网格。滚到底部时自动取下一页，与现有版本的列表行为一致。
class PagedGrid<T> extends StatefulWidget {
  const PagedGrid({
    super.key,
    required this.loadFirst,
    required this.loadNext,
    required this.itemBuilder,
    required this.delegate,
    this.emptyLabel = '没有内容',
    this.padding = const EdgeInsets.all(12),
  });

  final Future<FeedPage<T>> Function() loadFirst;
  final Future<FeedPage<T>> Function(String nextUrl) loadNext;
  final Widget Function(BuildContext context, T item, int index) itemBuilder;
  final SliverGridDelegate delegate;
  final String emptyLabel;
  final EdgeInsets padding;

  @override
  State<PagedGrid<T>> createState() => PagedGridState<T>();
}

class PagedGridState<T> extends State<PagedGrid<T>> {
  final ScrollController _scroll = ScrollController();
  List<T> _items = const [];
  String _nextUrl = '';
  bool _loading = true;
  bool _loadingMore = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _scroll.addListener(_onScroll);
    reload();
  }

  @override
  void dispose() {
    _scroll.removeListener(_onScroll);
    _scroll.dispose();
    super.dispose();
  }

  void _onScroll() {
    if (!_scroll.hasClients) return;
    // 距底部还有一屏时就开始取下一页，滚动过程中不会出现空白等待。
    if (_scroll.position.pixels >= _scroll.position.maxScrollExtent - 400) {
      _loadMore();
    }
  }

  /// 重新从第一页加载。
  Future<void> reload() async {
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
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _error = '$error';
        _loading = false;
      });
    }
  }

  Future<void> _loadMore() async {
    if (_nextUrl.isEmpty || _loadingMore || _loading) return;
    setState(() => _loadingMore = true);
    try {
      final page = await widget.loadNext(_nextUrl);
      if (!mounted) return;
      setState(() {
        _items = [..._items, ...page.items];
        _nextUrl = page.nextUrl;
        _loadingMore = false;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() => _loadingMore = false);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('加载更多失败：$error')),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_error != null) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              SelectableText('加载失败：$_error'),
              const SizedBox(height: 12),
              FilledButton(onPressed: reload, child: const Text('重试')),
            ],
          ),
        ),
      );
    }
    if (_items.isEmpty) {
      return Center(child: Text(widget.emptyLabel));
    }
    return GridView.builder(
      controller: _scroll,
      padding: widget.padding,
      gridDelegate: widget.delegate,
      itemCount: _items.length,
      itemBuilder: (context, index) => widget.itemBuilder(context, _items[index], index),
    );
  }
}
