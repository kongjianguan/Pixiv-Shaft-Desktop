import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:pixiv_shaft/src/settings/app_settings.dart';
import 'package:pixiv_shaft/src/ui/browse_history_page.dart';
import 'package:pixiv_shaft/src/ui/discover_page.dart';
import 'package:pixiv_shaft/src/ui/dynamic_page.dart';
import 'package:pixiv_shaft/src/ui/feed_pages.dart';
import 'package:pixiv_shaft/src/ui/login_screen.dart';
import 'package:pixiv_shaft/src/ui/pixivision_page.dart';
import 'package:pixiv_shaft/src/ui/r18_page.dart';
import 'package:pixiv_shaft/src/ui/search_profile_pages.dart';
import 'package:pixiv_shaft/src/ui/settings_page.dart';

/// 作品流使用完整窗口；主要页面从 macOS 系统菜单切换。
class HomeShell extends StatefulWidget {
  const HomeShell({super.key});

  @override
  State<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends State<HomeShell> {
  int _index = 0;
  final Set<int> _visited = {0};
  final List<int> _refreshTicks = List.filled(5, 0);
  late bool _lastR18;

  @override
  void initState() {
    super.initState();
    _lastR18 = AppSettings.instance.showR18;
    AppSettings.instance.addListener(_onSettingsChanged);
  }

  @override
  void dispose() {
    AppSettings.instance.removeListener(_onSettingsChanged);
    super.dispose();
  }

  void _onSettingsChanged() {
    final showR18 = AppSettings.instance.showR18;
    if (showR18 == _lastR18) return;
    _lastR18 = showR18;
    setState(() {
      for (final index in _visited) {
        _refreshTicks[index]++;
      }
    });
  }

  void _selectPage(int index) {
    Navigator.of(context).popUntil((route) => route.isFirst);
    if (_index == index) {
      _refresh();
      return;
    }
    setState(() {
      _index = index;
      _visited.add(index);
    });
  }

  void _refresh() {
    Navigator.of(context).popUntil((route) => route.isFirst);
    setState(() => _refreshTicks[_index]++);
  }

  void _openSettings() {
    Navigator.of(context).popUntil((route) => route.isFirst);
    Navigator.of(context).push(
      MaterialPageRoute<void>(builder: (_) => const SettingsPage()),
    );
  }

  void _openR18() {
    Navigator.of(context).popUntil((route) => route.isFirst);
    Navigator.of(context).push(
      MaterialPageRoute<void>(builder: (_) => const R18Page()),
    );
  }

  void _openHistory() {
    Navigator.of(context).popUntil((route) => route.isFirst);
    Navigator.of(context).push(
      MaterialPageRoute<void>(builder: (_) => const BrowseHistoryPage()),
    );
  }

  void _openPixivision() {
    Navigator.of(context).popUntil((route) => route.isFirst);
    Navigator.of(context).push(
      MaterialPageRoute<void>(builder: (_) => const PixivisionPage()),
    );
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: AppSettings.instance,
      builder: (context, _) => PlatformMenuBar(
        menus: [
          PlatformMenu(
            label: 'PixivShaft',
            menus: [
              PlatformMenuItem(
                label: '关于 PixivShaft',
                onSelected: () => showAboutDialog(context: context),
              ),
              PlatformMenuItemGroup(members: [
                PlatformMenuItem(
                  label: '设置',
                  shortcut: const SingleActivator(
                    LogicalKeyboardKey.comma,
                    meta: true,
                  ),
                  onSelected: _openSettings,
                ),
              ]),
              if (PlatformProvidedMenuItem.hasMenu(PlatformProvidedMenuItemType.quit))
                const PlatformProvidedMenuItem(type: PlatformProvidedMenuItemType.quit),
            ],
          ),
          PlatformMenu(
            label: '前往',
            menus: [
              PlatformMenuItem(
                label: '推荐',
                shortcut: const SingleActivator(LogicalKeyboardKey.digit1, meta: true),
                onSelected: () => _selectPage(0),
              ),
              PlatformMenuItem(
                label: '发现',
                shortcut: const SingleActivator(LogicalKeyboardKey.digit2, meta: true),
                onSelected: () => _selectPage(1),
              ),
              PlatformMenuItem(
                label: '搜索',
                shortcut: const SingleActivator(LogicalKeyboardKey.digit3, meta: true),
                onSelected: () => _selectPage(2),
              ),
              PlatformMenuItem(
                label: '我的',
                shortcut: const SingleActivator(LogicalKeyboardKey.digit4, meta: true),
                onSelected: () => _selectPage(4),
              ),
              PlatformMenuItem(
                label: '动态',
                shortcut: const SingleActivator(LogicalKeyboardKey.digit5, meta: true),
                onSelected: () => _selectPage(3),
              ),
              PlatformMenuItemGroup(members: [
                PlatformMenuItem(
                  label: '浏览历史',
                  shortcut: const SingleActivator(LogicalKeyboardKey.keyY, meta: true),
                  onSelected: _openHistory,
                ),
              ]),
              if (AppSettings.instance.showR18)
                PlatformMenuItem(label: 'R18 排行', onSelected: _openR18),
              PlatformMenuItemGroup(members: [
                PlatformMenuItem(label: 'Pixivision', onSelected: _openPixivision),
              ]),
            ],
          ),
          PlatformMenu(
            label: '显示',
            menus: [
              PlatformMenuItem(
                label: '刷新当前页面',
                shortcut: const SingleActivator(LogicalKeyboardKey.keyR, meta: true),
                onSelected: _refresh,
              ),
            ],
          ),
        ],
        child: Scaffold(
          body: Stack(
            children: [
              for (final index in _visited)
                Offstage(
                  offstage: index != _index,
                  child: TickerMode(
                    enabled: index == _index,
                    child: KeyedSubtree(
                      key: ValueKey('page-$index-${_refreshTicks[index]}'),
                      child: _page(index),
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _page(int index) {
    switch (index) {
      case 0:
        return const RecommendedPage();
      case 1:
        return const DiscoverPage();
      case 2:
        return const SearchPage();
      case 3:
        return const DynamicPage();
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
