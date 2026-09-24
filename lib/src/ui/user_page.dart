import 'package:flutter/material.dart';
import 'package:pixiv_shaft/src/rust/api/illust.dart';
import 'package:pixiv_shaft/src/rust/api/user.dart';
import 'package:pixiv_shaft/src/ui/illust_detail_page.dart';
import 'package:pixiv_shaft/src/ui/user_list_page.dart';
import 'package:pixiv_shaft/src/ui/widgets/illust_card.dart';
import 'package:pixiv_shaft/src/ui/widgets/rust_image.dart';

/// 作者页面：资料、作品列表与关注操作。
class UserPage extends StatefulWidget {
  const UserPage({super.key, required this.userId, this.initialName});

  final int userId;
  final String? initialName;

  @override
  State<UserPage> createState() => _UserPageState();
}

class _UserPageState extends State<UserPage> {
  late Future<UserProfile> _profile = fetchUserDetail(userId: widget.userId);
  late Future<List<IllustSummary>> _illusts =
      fetchUserIllusts(userId: widget.userId, illustType: 'illust');
  bool _following = false;
  bool _working = false;

  @override
  void initState() {
    super.initState();
    _profile.then((profile) {
      if (mounted) setState(() => _following = profile.isFollowed);
    });
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
      _profile = fetchUserDetail(userId: widget.userId);
      _illusts = fetchUserIllusts(userId: widget.userId, illustType: 'illust');
    });
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
          Expanded(
            child: AsyncSection<List<IllustSummary>>(
              future: _illusts,
              onRetry: _reload,
              errorLabel: '加载作品失败',
              builder: (context, illusts) => IllustGrid(
                illusts: illusts,
                emptyLabel: '该作者没有公开作品',
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
      ),
    );
  }
}
