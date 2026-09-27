import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:pixiv_shaft/src/rust/api/store.dart';
import 'package:pixiv_shaft/src/ui/illust_detail_page.dart';

class BrowseHistoryPage extends StatelessWidget {
  const BrowseHistoryPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text('浏览历史')),
      body: BrowseHistoryView(),
    );
  }
}

/// 按浏览时间分页读取本地记录，并提供删除入口。
class BrowseHistoryView extends StatefulWidget {
  const BrowseHistoryView({super.key});

  @override
  State<BrowseHistoryView> createState() => _BrowseHistoryViewState();
}

class _BrowseHistoryViewState extends State<BrowseHistoryView> {
  static const _pageSize = 50;
  final ScrollController _scroll = ScrollController();
  final List<BrowseRecord> _records = [];
  bool _loading = false;
  bool _hasMore = true;
  String? _error;
  int _generation = 0;

  @override
  void initState() {
    super.initState();
    _scroll.addListener(_onScroll);
    _loadMore();
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

  Future<void> _loadMore() async {
    if (_loading || !_hasMore) return;
    setState(() {
      _loading = true;
      _error = null;
    });
    final generation = _generation;
    try {
      final page = await listBrowse(
        contentType: 'illust',
        limit: _pageSize,
        offset: _records.length,
      );
      if (!mounted || generation != _generation) return;
      setState(() {
        _records.addAll(page);
        _hasMore = page.length == _pageSize;
        _loading = false;
      });
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted &&
            _scroll.hasClients &&
            _scroll.position.maxScrollExtent <= 400) {
          _loadMore();
        }
      });
    } catch (error) {
      if (!mounted || generation != _generation) return;
      setState(() {
        _error = '$error';
        _loading = false;
      });
    }
  }

  void _reload() {
    setState(() {
      _generation++;
      _records.clear();
      _hasMore = true;
      _loading = false;
      _error = null;
    });
    _loadMore();
  }

  Future<void> _remove(BrowseRecord record) async {
    await deleteBrowse(contentType: 'illust', targetId: record.targetId);
    if (mounted) _reload();
  }

  Future<void> _clear() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('清空浏览历史？'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('清空'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    await clearBrowse();
    if (mounted) _reload();
  }

  String _title(BrowseRecord record) {
    final payload = jsonDecode(record.payloadJson) as Map<String, dynamic>;
    return payload['title'] as String;
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
          child: Row(
            children: [
              const Expanded(child: Text('浏览历史')),
              IconButton(
                onPressed: _reload,
                tooltip: '刷新',
                icon: const Icon(Icons.refresh),
              ),
              TextButton(
                onPressed: _records.isEmpty ? null : _clear,
                child: const Text('清空'),
              ),
            ],
          ),
        ),
        Expanded(
          child: _records.isEmpty && _loading
              ? const Center(child: CircularProgressIndicator())
              : _records.isEmpty && _error == null
                  ? const Center(child: Text('还没有浏览记录'))
                  : ListView.builder(
                      controller: _scroll,
                      itemCount: _records.length + 1,
                      itemBuilder: (context, index) {
                        if (index == _records.length) {
                          if (_error != null) {
                            return Center(
                              child: TextButton(
                                onPressed: _loadMore,
                                child: Text('加载失败：$_error，点击重试'),
                              ),
                            );
                          }
                          return _loading
                              ? const Center(child: CircularProgressIndicator())
                              : const SizedBox.shrink();
                        }
                        final record = _records[index];
                        return ListTile(
                          title: Text(_title(record)),
                          subtitle: Text('作品 ${record.targetId}'),
                          onTap: () => Navigator.of(context).push(
                            MaterialPageRoute<void>(
                              builder: (_) => IllustDetailPage(
                                illustId: record.targetId,
                                initialTitle: _title(record),
                              ),
                            ),
                          ),
                          trailing: IconButton(
                            onPressed: () => _remove(record),
                            tooltip: '删除记录',
                            icon: const Icon(Icons.delete_outline),
                          ),
                        );
                      },
                    ),
        ),
      ],
    );
  }
}
