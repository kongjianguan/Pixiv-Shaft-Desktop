import 'package:flutter/material.dart';
import 'package:pixiv_shaft/src/rust/api/user.dart';
import 'package:pixiv_shaft/src/ui/user_page.dart';
import 'package:pixiv_shaft/src/ui/widgets/rust_image.dart';

/// 用户列表：关注中、粉丝、好P友。
class UserListScreen extends StatefulWidget {
  const UserListScreen({super.key, required this.userId, this.initialTab = 0});

  final int userId;
  final int initialTab;

  @override
  State<UserListScreen> createState() => _UserListScreenState();
}

class _UserListScreenState extends State<UserListScreen> with SingleTickerProviderStateMixin {
  late final TabController _tabs = TabController(
    length: 3,
    vsync: this,
    initialIndex: widget.initialTab.clamp(0, 2),
  );

  @override
  void dispose() {
    _tabs.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('用户'),
        bottom: TabBar(
          controller: _tabs,
          tabs: const [
            Tab(text: '关注中'),
            Tab(text: '粉丝'),
            Tab(text: '好P友'),
          ],
        ),
      ),
      body: TabBarView(
        controller: _tabs,
        children: [
          _UserList(
            key: const ValueKey('following'),
            load: () => fetchFollowingUsers(
              userId: widget.userId,
              restrict: 'public',
            ),
          ),
          _UserList(
            key: const ValueKey('follower'),
            load: () => fetchFollowerUsers(userId: widget.userId),
          ),
          _UserList(
            key: const ValueKey('mypixiv'),
            load: () => fetchMypixivUsers(userId: widget.userId),
          ),
        ],
      ),
    );
  }
}

class _UserList extends StatefulWidget {
  const _UserList({super.key, required this.load});

  final Future<UserListPage> Function() load;

  @override
  State<_UserList> createState() => _UserListState();
}

class _UserListState extends State<_UserList> {
  List<UserPreview> _users = const [];
  String _nextUrl = '';
  bool _loading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final page = await widget.load();
      if (!mounted) return;
      setState(() {
        _users = page.users;
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
    if (_nextUrl.isEmpty) return;
    try {
      final page = await fetchNextUsers(nextUrl: _nextUrl);
      if (!mounted) return;
      setState(() {
        _users = [..._users, ...page.users];
        _nextUrl = page.nextUrl;
      });
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('加载更多失败：$error')),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_error != null) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            SelectableText('加载失败：$_error'),
            const SizedBox(height: 12),
            FilledButton(onPressed: _load, child: const Text('重试')),
          ],
        ),
      );
    }
    if (_users.isEmpty) {
      return const Center(child: Text('没有内容'));
    }
    return ListView.separated(
      itemCount: _users.length + (_nextUrl.isEmpty ? 0 : 1),
      separatorBuilder: (_, __) => const Divider(height: 1),
      itemBuilder: (context, index) {
        if (index >= _users.length) {
          return Center(
            child: TextButton(onPressed: _loadMore, child: const Text('加载更多')),
          );
        }
        final user = _users[index];
        return ListTile(
          leading: SizedBox(
            width: 44,
            height: 44,
            child: ClipOval(child: RustImage(url: user.avatarUrl, errorLabel: '')),
          ),
          title: Text(user.name),
          subtitle: Text('@${user.account}'),
          trailing: user.isFollowed ? const Icon(Icons.check, size: 18) : null,
          onTap: () => Navigator.of(context).push(
            MaterialPageRoute<void>(
              builder: (_) => UserPage(userId: user.id, initialName: user.name),
            ),
          ),
        );
      },
    );
  }
}
