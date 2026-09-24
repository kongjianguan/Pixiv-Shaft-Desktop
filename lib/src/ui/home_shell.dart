import 'package:flutter/material.dart';
import 'package:pixiv_shaft/src/ui/feed_pages.dart';
import 'package:pixiv_shaft/src/ui/login_screen.dart';
import 'package:pixiv_shaft/src/ui/search_profile_pages.dart';

/// 主界面骨架：左侧导航栏加内容区。
///
/// 导航入口与现有版本一致：推荐、发现、搜索、我的。动态与设置尚未迁移，
/// 迁移完成后再加入。
class HomeShell extends StatefulWidget {
  const HomeShell({super.key});

  @override
  State<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends State<HomeShell> {
  int _index = 0;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Row(
        children: [
          NavigationRail(
            selectedIndex: _index,
            onDestinationSelected: (index) => setState(() => _index = index),
            labelType: NavigationRailLabelType.all,
            destinations: const [
              NavigationRailDestination(
                icon: Icon(Icons.home_outlined),
                selectedIcon: Icon(Icons.home),
                label: Text('推荐'),
              ),
              NavigationRailDestination(
                icon: Icon(Icons.star_outline),
                selectedIcon: Icon(Icons.star),
                label: Text('发现'),
              ),
              NavigationRailDestination(
                icon: Icon(Icons.search_outlined),
                selectedIcon: Icon(Icons.search),
                label: Text('搜索'),
              ),
              NavigationRailDestination(
                icon: Icon(Icons.person_outline),
                selectedIcon: Icon(Icons.person),
                label: Text('我的'),
              ),
            ],
          ),
          const VerticalDivider(width: 1),
          Expanded(child: _page()),
        ],
      ),
    );
  }

  Widget _page() {
    switch (_index) {
      case 0:
        return const RecommendedPage();
      case 1:
        return const RankingPage();
      case 2:
        return const SearchPage();
      default:
        return ProfilePage(onLoggedOut: _afterLogout);
    }
  }

  void _afterLogout() {
    Navigator.of(context).pushReplacement(
      MaterialPageRoute<void>(builder: (_) => const LoginScreen()),
    );
  }
}
