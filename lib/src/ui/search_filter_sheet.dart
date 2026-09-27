import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:pixiv_shaft/src/rust/api/search.dart';
import 'package:pixiv_shaft/src/settings/app_settings.dart';

const searchSorts = <(String, String)>[
  ('popular_preview', '热度预览'),
  ('date_desc', '最新'),
  ('date_asc', '最旧'),
  ('popular_desc', '热度'),
];

/// 搜索选项保留为 API 参数，以下划线开头的键只用于客户端过滤。
class SearchFilterSheet extends StatefulWidget {
  const SearchFilterSheet({
    super.key,
    required this.isNovel,
    required this.initialFilter,
    required this.options,
  });

  final bool isNovel;
  final Map<String, String> initialFilter;
  final SearchOptions? options;

  @override
  State<SearchFilterSheet> createState() => _SearchFilterSheetState();
}

class _SearchFilterSheetState extends State<SearchFilterSheet> {
  late final Map<String, String> _draft = {...widget.initialFilter};
  int _resetGeneration = 0;

  @override
  void initState() {
    super.initState();
    if (!AppSettings.instance.showR18) _draft['_r18'] = 'safe';
  }

  void _set(String key, String? value) {
    setState(() {
      if (value == null || value.isEmpty) {
        _draft.remove(key);
      } else {
        _draft[key] = value;
      }
    });
  }

  Future<void> _pickDate(String key) async {
    final today = DateUtils.dateOnly(DateTime.now());
    final current = DateTime.tryParse(_draft[key] ?? '');
    final selected = await showDatePicker(
      context: context,
      initialDate: current ?? today,
      firstDate: DateTime(2007),
      lastDate: today,
    );
    if (!mounted || selected == null) return;
    _set(key, '${selected.year.toString().padLeft(4, '0')}-'
        '${selected.month.toString().padLeft(2, '0')}-'
        '${selected.day.toString().padLeft(2, '0')}');
  }

  Widget _section(String title) => Padding(
        padding: const EdgeInsets.only(top: 10),
        child: Text(title, style: Theme.of(context).textTheme.titleSmall),
      );

  Widget _choices(String key, List<(String, String)> choices, {String? defaultValue}) {
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: Row(
        children: [
          for (final (value, label) in choices)
            Padding(
              padding: const EdgeInsets.only(right: 8),
              child: FilterChip(
                label: Text(label),
                selected: (_draft[key] ?? defaultValue) == value,
                onSelected: (_) => _set(key, value == 'none' ? null : value),
              ),
            ),
        ],
      ),
    );
  }

  Widget _toggle(String key, String label) => FilterChip(
        label: Text(label),
        selected: _draft[key] == 'true',
        onSelected: (selected) => _set(key, selected ? 'true' : null),
      );

  @override
  Widget build(BuildContext context) {
    final options = widget.options;
    final isNovel = widget.isNovel;
    final languages = isNovel ? options?.novelLanguages : options?.illustLanguages;
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
        child: Column(
          children: [
            Row(
              children: [
                Expanded(child: Text('搜索条件', style: Theme.of(context).textTheme.titleLarge)),
                IconButton(
                  tooltip: '重置筛选',
                  onPressed: () => setState(() {
                    _draft.clear();
                    _resetGeneration++;
                    if (!AppSettings.instance.showR18) _draft['_r18'] = 'safe';
                  }),
                  icon: const Icon(Icons.refresh),
                ),
              ],
            ),
            Expanded(
              child: ListView(
                children: [
                  _section('排序'),
                  _choices('sort', searchSorts, defaultValue: 'date_desc'),
                  _section('检索范围'),
                  _choices(
                    'search_target',
                    isNovel
                        ? const [
                            ('partial_match_for_tags', '标签部分匹配'),
                            ('exact_match_for_tags', '标签完全匹配'),
                            ('text', '正文'),
                            ('keyword', '关键词'),
                          ]
                        : const [
                            ('partial_match_for_tags', '标签部分匹配'),
                            ('exact_match_for_tags', '标签完全匹配'),
                            ('title_and_caption', '标题和简介'),
                          ],
                    defaultValue: 'partial_match_for_tags',
                  ),
                  _section('收藏数'),
                  _choices('bookmark_num_min', const [
                    ('none', '不限'),
                    ('100', '100+'),
                    ('500', '500+'),
                    ('1000', '1000+'),
                    ('5000', '5000+'),
                    ('10000', '10000+'),
                  ], defaultValue: 'none'),
                  _section('投稿期间'),
                  Row(
                    children: [
                      ActionChip(
                        label: Text(_draft['start_date'] ?? '起始日期'),
                        onPressed: () => _pickDate('start_date'),
                      ),
                      const SizedBox(width: 8),
                      ActionChip(
                        label: Text(_draft['end_date'] ?? '截止日期'),
                        onPressed: () => _pickDate('end_date'),
                      ),
                      if (_draft.containsKey('start_date') || _draft.containsKey('end_date'))
                        IconButton(
                          tooltip: '清除日期',
                          onPressed: () => setState(() {
                            _draft.remove('start_date');
                            _draft.remove('end_date');
                          }),
                          icon: const Icon(Icons.close),
                        ),
                    ],
                  ),
                  _section('语言'),
                  if (languages == null || languages.isEmpty)
                    const Text('搜索后可加载语言选项')
                  else
                    _choices('lang', [
                      ('none', '所有语种'),
                      for (final language in languages)
                        (language.value, language.label),
                    ], defaultValue: 'none'),
                  _section(AppSettings.instance.showR18 ? 'AI 与 R-18' : 'AI'),
                  _choices('_ai', const [
                    ('all', '全部作品'),
                    ('exclude', '屏蔽 AI'),
                    ('only', '仅 AI'),
                  ], defaultValue: 'all'),
                  if (AppSettings.instance.showR18)
                    _choices('_r18', const [
                      ('all', '全部'),
                      ('safe', '仅全年龄'),
                      ('only', '仅 R-18'),
                    ], defaultValue: 'all'),
                  if (isNovel) ...[
                    _section('小说类型'),
                    if (options == null || options.genres.isEmpty)
                      const Text('搜索后可加载小说类型')
                    else
                      _choices('genre', [
                        ('none', '全部类型'),
                        for (final genre in options.genres)
                          (genre.value, genre.label),
                      ], defaultValue: 'none'),
                    _section('小说条件'),
                    Wrap(spacing: 8, children: [
                      _toggle('is_original_only', '仅原创'),
                      _toggle('is_replaceable_only', '支持单词置换'),
                    ]),
                    _section('正文长度'),
                    _choices('_length_unit', const [
                      ('text_length', '文字数'),
                      ('word_count', '单词数'),
                      ('reading_time', '阅读分钟'),
                    ], defaultValue: 'text_length'),
                    Row(children: [
                      Expanded(
                        child: TextFormField(
                          key: ValueKey('min-$_resetGeneration'),
                          initialValue: _draft['_length_min'],
                          decoration: const InputDecoration(labelText: '下限'),
                          keyboardType: TextInputType.number,
                          inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                          onChanged: (value) => _set('_length_min', value),
                        ),
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: TextFormField(
                          key: ValueKey('max-$_resetGeneration'),
                          initialValue: _draft['_length_max'],
                          decoration: const InputDecoration(labelText: '上限'),
                          keyboardType: TextInputType.number,
                          inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                          onChanged: (value) => _set('_length_max', value),
                        ),
                      ),
                    ]),
                  ] else ...[
                    _section('作品类别'),
                    _choices('content_type', const [
                      ('none', '插画、漫画、动图'),
                      ('illust_and_ugoira', '插画、动图'),
                      ('illust', '插画'),
                      ('ugoira', '动图'),
                      ('manga', '漫画'),
                    ], defaultValue: 'none'),
                    _section('长宽比'),
                    _choices('ratio_pattern', const [
                      ('none', '所有纵横比'),
                      ('landscape', '横图'),
                      ('portrait', '竖图'),
                      ('square', '正方形'),
                    ], defaultValue: 'none'),
                    _section('分辨率'),
                    _choices('_resolution', const [
                      ('none', '全部清晰度'),
                      ('above3000', '3000px 以上'),
                      ('between1000and2999', '1000px - 2999px'),
                      ('below1000', '999px 以下'),
                    ], defaultValue: 'none'),
                    _section('制图工具'),
                    if (options == null || options.tools.isEmpty)
                      const Text('搜索后可加载制图工具')
                    else
                      _choices('tool', [
                        ('none', '所有工具'),
                        for (final tool in options.tools) (tool, tool),
                      ], defaultValue: 'none'),
                  ],
                ],
              ),
            ),
            FilledButton(
              onPressed: () => Navigator.of(context).pop(_draft),
              child: const Text('应用筛选'),
            ),
          ],
        ),
      ),
    );
  }
}
