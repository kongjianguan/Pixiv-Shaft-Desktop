import 'package:file_selector/file_selector.dart';
import 'package:flutter/material.dart';
import 'package:pixiv_shaft/src/settings/app_settings.dart';

/// 设置页。改动即时写回数据库并生效。
class SettingsPage extends StatefulWidget {
  const SettingsPage({super.key});

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  final AppSettings _settings = AppSettings.instance;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('设置')),
      // 改动后设置容器会通知，界面据此重建，滑块与开关跟着更新。
      body: AnimatedBuilder(
        animation: _settings,
        builder: (context, _) => _body(context),
      ),
    );
  }

  Widget _body(BuildContext context) {
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
          _Section(
            title: '外观',
            children: [
              SegmentedButton<ThemeMode>(
                segments: const [
                  ButtonSegment(value: ThemeMode.system, label: Text('跟随系统')),
                  ButtonSegment(value: ThemeMode.light, label: Text('浅色')),
                  ButtonSegment(value: ThemeMode.dark, label: Text('深色')),
                ],
                selected: {_settings.themeMode},
                onSelectionChanged: (value) => _settings.setThemeMode(value.first),
              ),
              const SizedBox(height: 12),
              const Align(alignment: Alignment.centerLeft, child: Text('主题色彩')),
              const SizedBox(height: 8),
              Wrap(
                spacing: 10,
                runSpacing: 10,
                children: [
                  for (var index = 0; index < themeSeedColors.length; index++)
                    _ColorDot(
                      color: themeSeedColors[index],
                      selected: _settings.themeColorIndex == index,
                      onTap: () => _settings.setThemeColorIndex(index),
                    ),
                ],
              ),
            ],
          ),
          _Section(
            title: '内容',
            children: [
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                title: const Text('记录浏览历史'),
                subtitle: const Text('关闭后不再写入浏览记录'),
                value: _settings.saveBrowseHistory,
                onChanged: _settings.setSaveBrowseHistory,
              ),
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                title: const Text('显示 R18 内容'),
                value: _settings.showR18,
                onChanged: _settings.setShowR18,
              ),
            ],
          ),
          _Section(
            title: '作品流',
            children: [
              _SliderRow(
                label: '单列最大宽度',
                value: _settings.workMaxColumnWidth,
                min: 220,
                max: 720,
                divisions: 25,
                suffix: 'dp',
                onChanged: (value) => _settings.setWorkLayout(maxColumnWidth: value),
              ),
              _SliderRow(
                label: '最大列数',
                value: _settings.workMaxColumns.toDouble(),
                min: 1,
                max: 8,
                divisions: 7,
                suffix: '列',
                onChanged: (value) =>
                    _settings.setWorkLayout(maxColumns: value.round()),
              ),
              _SliderRow(
                label: '标题行数',
                value: _settings.workTitleMaxLines.toDouble(),
                min: 1,
                max: 5,
                divisions: 4,
                suffix: '行',
                onChanged: (value) =>
                    _settings.setWorkLayout(titleMaxLines: value.round()),
              ),
            ],
          ),
          _Section(
            title: '小说流',
            children: [
              _SliderRow(
                label: '单列最大宽度',
                value: _settings.novelMaxColumnWidth,
                min: 260,
                max: 720,
                divisions: 23,
                suffix: 'dp',
                onChanged: (value) => _settings.setNovelLayout(maxColumnWidth: value),
              ),
              _SliderRow(
                label: '最大列数',
                value: _settings.novelMaxColumns.toDouble(),
                min: 1,
                max: 8,
                divisions: 7,
                suffix: '列',
                onChanged: (value) =>
                    _settings.setNovelLayout(maxColumns: value.round()),
              ),
              _SliderRow(
                label: '标题行数',
                value: _settings.novelTitleMaxLines.toDouble(),
                min: 1,
                max: 5,
                divisions: 4,
                suffix: '行',
                onChanged: (value) =>
                    _settings.setNovelLayout(titleMaxLines: value.round()),
              ),
            ],
          ),
          _Section(
            title: '下载',
            children: [
              const Text('所有下载文件默认保存在该目录下，模板中的路径相对此目录'),
              const SizedBox(height: 8),
              Row(
                children: [
                  Expanded(
                    child: TextFormField(
                      initialValue: _settings.downloadRootPath,
                      decoration: const InputDecoration(
                        labelText: '下载目录',
                        border: OutlineInputBorder(),
                      ),
                      onFieldSubmitted: _settings.setDownloadRootPath,
                    ),
                  ),
                  const SizedBox(width: 8),
                  OutlinedButton(
                    onPressed: _chooseDownloadDirectory,
                    child: const Text('选择…'),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              _TemplateRow(
                label: '插画文件名模板',
                value: _settings.illustFileNameTemplate,
                onSubmitted: _settings.setIllustFileNameTemplate,
              ),
              _TemplateRow(
                label: '动图文件名模板',
                value: _settings.ugoiraFileNameTemplate,
                onSubmitted: _settings.setUgoiraFileNameTemplate,
              ),
              _TemplateRow(
                label: '小说文件名模板',
                value: _settings.novelFileNameTemplate,
                onSubmitted: _settings.setNovelFileNameTemplate,
              ),
            ],
          ),
        ],
      );
  }

  Future<void> _chooseDownloadDirectory() async {
    final chosen = await getDirectoryPath(
      initialDirectory: _settings.downloadRootPath.isEmpty
          ? null
          : _settings.downloadRootPath,
      confirmButtonText: '选择',
    );
    if (chosen != null && chosen.isNotEmpty) {
      await _settings.setDownloadRootPath(chosen);
      if (mounted) setState(() {});
    }
  }
}

class _Section extends StatelessWidget {
  const _Section({required this.title, required this.children});

  final String title;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.only(bottom: 16),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(title, style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 12),
            ...children,
          ],
        ),
      ),
    );
  }
}

class _ColorDot extends StatelessWidget {
  const _ColorDot({
    required this.color,
    required this.selected,
    required this.onTap,
  });

  final Color color;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      customBorder: const CircleBorder(),
      child: Container(
        width: 32,
        height: 32,
        decoration: BoxDecoration(
          color: color,
          shape: BoxShape.circle,
          border: selected
              ? Border.all(color: Theme.of(context).colorScheme.onSurface, width: 3)
              : null,
        ),
      ),
    );
  }
}

class _SliderRow extends StatelessWidget {
  const _SliderRow({
    required this.label,
    required this.value,
    required this.min,
    required this.max,
    required this.divisions,
    required this.suffix,
    required this.onChanged,
  });

  final String label;
  final double value;
  final double min;
  final double max;
  final int divisions;
  final String suffix;
  final ValueChanged<double> onChanged;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        SizedBox(width: 120, child: Text(label)),
        Expanded(
          child: Slider(
            value: value.clamp(min, max),
            min: min,
            max: max,
            divisions: divisions,
            label: '${value.round()} $suffix',
            onChanged: onChanged,
          ),
        ),
        SizedBox(
          width: 64,
          child: Text('${value.round()} $suffix', textAlign: TextAlign.end),
        ),
      ],
    );
  }
}

class _TemplateRow extends StatelessWidget {
  const _TemplateRow({
    required this.label,
    required this.value,
    required this.onSubmitted,
  });

  final String label;
  final String value;
  final ValueChanged<String> onSubmitted;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: TextFormField(
        initialValue: value,
        decoration: InputDecoration(
          labelText: label,
          border: const OutlineInputBorder(),
        ),
        onFieldSubmitted: onSubmitted,
      ),
    );
  }
}
