import 'package:flutter/material.dart';
import 'package:pixiv_shaft/src/rust/api/comment.dart';
import 'package:pixiv_shaft/src/rust/api/user.dart';
import 'package:pixiv_shaft/src/ui/widgets/rust_image.dart';

/// 作品详情页下方的评论区。
///
/// 评论分页用接口返回的 `next_url` 作为游标，与现有版本一致。
class CommentsSection extends StatefulWidget {
  const CommentsSection({super.key, required this.illustId});

  final int illustId;

  @override
  State<CommentsSection> createState() => _CommentsSectionState();
}

class _CommentsSectionState extends State<CommentsSection> {
  final TextEditingController _input = TextEditingController();

  List<CommentEntry> _comments = const [];
  String _nextUrl = '';
  bool _loading = true;
  bool _loadingMore = false;
  bool _posting = false;
  String? _error;
  int _selfId = 0;
  int _replyTo = 0;
  String _replyToName = '';

  @override
  void initState() {
    super.initState();
    _loadFirst();
    _loadSelf();
  }

  @override
  void dispose() {
    _input.dispose();
    super.dispose();
  }

  Future<void> _loadSelf() async {
    try {
      final id = await selfUserId();
      if (mounted) setState(() => _selfId = id);
    } catch (_) {
      // 取不到自己的 id 只影响能否删除自己的评论，不影响浏览。
    }
  }

  Future<void> _loadFirst() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final page = await fetchIllustComments(illustId: widget.illustId);
      if (!mounted) return;
      setState(() {
        _comments = page.comments;
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
    if (_nextUrl.isEmpty || _loadingMore) return;
    setState(() => _loadingMore = true);
    try {
      final page = await fetchNextComments(nextUrl: _nextUrl);
      if (!mounted) return;
      setState(() {
        _comments = [..._comments, ...page.comments];
        _nextUrl = page.nextUrl;
        _loadingMore = false;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() => _loadingMore = false);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('加载更多评论失败：$error')),
      );
    }
  }

  Future<void> _post() async {
    final body = _input.text.trim();
    if (body.isEmpty) return;
    setState(() => _posting = true);
    try {
      await addIllustComment(
        illustId: widget.illustId,
        body: body,
        parentCommentId: _replyTo,
      );
      _input.clear();
      _replyTo = 0;
      _replyToName = '';
      await _loadFirst();
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('发表评论失败：$error')),
        );
      }
    } finally {
      if (mounted) setState(() => _posting = false);
    }
  }

  Future<void> _delete(CommentEntry entry) async {
    try {
      await deleteIllustComment(commentId: entry.id);
      await _loadFirst();
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('删除评论失败：$error')),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('评论', style: Theme.of(context).textTheme.titleSmall),
        const SizedBox(height: 8),
        if (_loading)
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 24),
            child: Center(child: CircularProgressIndicator()),
          )
        else if (_error != null)
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              SelectableText('加载评论失败：$_error'),
              const SizedBox(height: 8),
              FilledButton(onPressed: _loadFirst, child: const Text('重试')),
            ],
          )
        else if (_comments.isEmpty)
          const Text('还没有评论')
        else
          for (final entry in _comments)
            _CommentRow(
              entry: entry,
              canDelete: _selfId != 0 && entry.authorId == _selfId,
              onDelete: () => _delete(entry),
              onReply: () => setState(() {
                _replyTo = entry.id;
                _replyToName = entry.authorName;
              }),
            ),
        if (_nextUrl.isNotEmpty)
          Center(
            child: TextButton(
              onPressed: _loadingMore ? null : _loadMore,
              child: Text(_loadingMore ? '正在加载…' : '加载更多'),
            ),
          ),
        const SizedBox(height: 12),
        if (_replyTo != 0)
          Row(
            children: [
              Expanded(child: Text('回复 $_replyToName')),
              TextButton(
                onPressed: () => setState(() {
                  _replyTo = 0;
                  _replyToName = '';
                }),
                child: const Text('取消回复'),
              ),
            ],
          ),
        Row(
          crossAxisAlignment: CrossAxisAlignment.end,
          children: [
            Expanded(
              child: TextField(
                controller: _input,
                maxLines: 3,
                minLines: 1,
                decoration: InputDecoration(
                  hintText: _replyTo == 0 ? '发表评论' : '回复 $_replyToName',
                  border: const OutlineInputBorder(),
                ),
              ),
            ),
            const SizedBox(width: 8),
            FilledButton(
              onPressed: _posting ? null : _post,
              child: Text(_posting ? '发送中…' : '发送'),
            ),
          ],
        ),
      ],
    );
  }
}

class _CommentRow extends StatelessWidget {
  const _CommentRow({
    required this.entry,
    required this.canDelete,
    required this.onDelete,
    required this.onReply,
  });

  final CommentEntry entry;
  final bool canDelete;
  final VoidCallback onDelete;
  final VoidCallback onReply;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 32,
            height: 32,
            child: ClipOval(
              child: RustImage(url: entry.avatarUrl, errorLabel: ''),
            ),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        entry.authorName,
                        style: Theme.of(context).textTheme.bodySmall?.copyWith(
                              color: Theme.of(context).colorScheme.outline,
                            ),
                      ),
                    ),
                    Text(
                      entry.date,
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                  ],
                ),
                const SizedBox(height: 2),
                SelectableText(entry.body),
                Row(
                  children: [
                    TextButton(
                      onPressed: onReply,
                      child: const Text('回复'),
                    ),
                    if (canDelete)
                      TextButton(
                        onPressed: onDelete,
                        child: const Text('删除'),
                      ),
                  ],
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
