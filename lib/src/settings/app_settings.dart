import 'package:flutter/material.dart';
import 'package:pixiv_shaft/src/rust/api/store.dart';

/// 主题色候选。与现有版本的 `themeColorIndex` 一一对应。
const themeSeedColors = <Color>[
  Color(0xFF0096FA),
  Color(0xFF7B61FF),
  Color(0xFF00A0A0),
  Color(0xFF2E7D32),
  Color(0xFFB8860B),
  Color(0xFFE65100),
  Color(0xFFD32F2F),
  Color(0xFFC2185B),
  Color(0xFF5D4037),
  Color(0xFF455A64),
];

/// 界面用到的设置。启动时从 SQLite 读出，改动后立即写回并通知界面重建。
///
/// 只包含新版本真正会用的项。现有版本里那些控制传输通路选择的开关
/// （直连、加密 DNS、图片源）在新版本里没有对应物：通路固定为 ECH 优先、
/// 失败回退 QUIC，图片地址固定，因此不在这里保留。
class AppSettings extends ChangeNotifier {
  static final AppSettings instance = AppSettings._();
  AppSettings._();

  ThemeMode themeMode = ThemeMode.system;
  int themeColorIndex = 0;
  bool saveBrowseHistory = true;
  bool showR18 = false;

  double workMaxColumnWidth = 360;
  int workMaxColumns = 4;
  int workTitleMaxLines = 1;

  double novelMaxColumnWidth = 360;
  int novelMaxColumns = 4;
  int novelTitleMaxLines = 2;

  int readerFontSize = 18;
  double readerLineSpacing = 1.5;
  int readerParagraphSpacing = 12;
  String readerTheme = 'light';

  String downloadRootPath = '';
  String illustFileNameTemplate = '{illustId}_p{pageIndex}';
  String ugoiraFileNameTemplate = '{illustId}';
  String novelFileNameTemplate = '{novelId}_{novelTitle}';

  ThemeData theme(Brightness brightness) {
    final seed = themeSeedColors[themeColorIndex % themeSeedColors.length];
    return ThemeData(
      colorSchemeSeed: seed,
      brightness: brightness,
      useMaterial3: true,
    );
  }

  /// 从数据库读出全部设置。启动时调用一次。
  Future<void> load() async {
    themeMode = _modeOf(await setting(key: 'themeMode'));
    themeColorIndex = await _intSetting('themeColorIndex');
    saveBrowseHistory = await _boolSetting('saveBrowseHistory');
    showR18 = await _boolSetting('isShowR18');

    workMaxColumnWidth = (await _intSetting('workFeedMaxColumnWidthDp')).toDouble();
    workMaxColumns = await _intSetting('workFeedMaxColumns');
    workTitleMaxLines = await _intSetting('workTitleMaxLines');

    novelMaxColumnWidth = (await _intSetting('novelFeedMaxColumnWidthDp')).toDouble();
    novelMaxColumns = await _intSetting('novelFeedMaxColumns');
    novelTitleMaxLines = await _intSetting('novelTitleMaxLines');

    readerFontSize = await _intSetting('readerFontSizeSp');
    readerLineSpacing = double.tryParse(await setting(key: 'readerLineSpacing')) ?? 1.5;
    readerParagraphSpacing = await _intSetting('readerParagraphSpacingDp');
    readerTheme = await setting(key: 'readerTheme');

    downloadRootPath = await setting(key: 'downloadRootPath');
    illustFileNameTemplate = await setting(key: 'illustFileNameTemplate');
    ugoiraFileNameTemplate = await setting(key: 'ugoiraFileNameTemplate');
    novelFileNameTemplate = await setting(key: 'novelFileNameTemplate');

    notifyListeners();
  }

  Future<void> setThemeMode(ThemeMode value) async {
    themeMode = value;
    await setSetting(key: 'themeMode', value: _modeName(value));
    notifyListeners();
  }

  Future<void> setThemeColorIndex(int value) async {
    themeColorIndex = value;
    await setSetting(key: 'themeColorIndex', value: '$value');
    notifyListeners();
  }

  Future<void> setSaveBrowseHistory(bool value) async {
    saveBrowseHistory = value;
    await setSetting(key: 'saveBrowseHistory', value: '$value');
    notifyListeners();
  }

  Future<void> setShowR18(bool value) async {
    showR18 = value;
    await setSetting(key: 'isShowR18', value: '$value');
    notifyListeners();
  }

  Future<void> setWorkLayout({
    double? maxColumnWidth,
    int? maxColumns,
    int? titleMaxLines,
  }) async {
    if (maxColumnWidth != null) {
      workMaxColumnWidth = maxColumnWidth;
      await setSetting(key: 'workFeedMaxColumnWidthDp', value: '${maxColumnWidth.round()}');
    }
    if (maxColumns != null) {
      workMaxColumns = maxColumns;
      await setSetting(key: 'workFeedMaxColumns', value: '$maxColumns');
    }
    if (titleMaxLines != null) {
      workTitleMaxLines = titleMaxLines;
      await setSetting(key: 'workTitleMaxLines', value: '$titleMaxLines');
    }
    notifyListeners();
  }

  Future<void> setNovelLayout({
    double? maxColumnWidth,
    int? maxColumns,
    int? titleMaxLines,
  }) async {
    if (maxColumnWidth != null) {
      novelMaxColumnWidth = maxColumnWidth;
      await setSetting(
        key: 'novelFeedMaxColumnWidthDp',
        value: '${maxColumnWidth.round()}',
      );
    }
    if (maxColumns != null) {
      novelMaxColumns = maxColumns;
      await setSetting(key: 'novelFeedMaxColumns', value: '$maxColumns');
    }
    if (titleMaxLines != null) {
      novelTitleMaxLines = titleMaxLines;
      await setSetting(key: 'novelTitleMaxLines', value: '$titleMaxLines');
    }
    notifyListeners();
  }

  Future<void> setDownloadRootPath(String value) async {
    downloadRootPath = value;
    await setSetting(key: 'downloadRootPath', value: value);
    notifyListeners();
  }

  Future<void> setIllustFileNameTemplate(String value) async {
    illustFileNameTemplate = value;
    await setSetting(key: 'illustFileNameTemplate', value: value);
    notifyListeners();
  }

  Future<void> setUgoiraFileNameTemplate(String value) async {
    ugoiraFileNameTemplate = value;
    await setSetting(key: 'ugoiraFileNameTemplate', value: value);
    notifyListeners();
  }

  Future<void> setNovelFileNameTemplate(String value) async {
    novelFileNameTemplate = value;
    await setSetting(key: 'novelFileNameTemplate', value: value);
    notifyListeners();
  }

  Future<int> _intSetting(String key) async =>
      int.tryParse(await setting(key: key)) ?? 0;

  Future<bool> _boolSetting(String key) async => await setting(key: key) == 'true';

  static ThemeMode _modeOf(String value) {
    switch (value) {
      case 'light':
        return ThemeMode.light;
      case 'dark':
        return ThemeMode.dark;
      default:
        return ThemeMode.system;
    }
  }

  static String _modeName(ThemeMode mode) {
    switch (mode) {
      case ThemeMode.light:
        return 'light';
      case ThemeMode.dark:
        return 'dark';
      case ThemeMode.system:
        return 'system';
    }
  }
}
