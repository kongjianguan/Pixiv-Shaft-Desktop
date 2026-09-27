import 'package:flutter/material.dart';

/// 按设置里的单列宽度与最大列数，算出当前窗口宽度下应排几列。
int gridColumns(BuildContext context, double maxColumnWidth, int maxColumns) {
  return responsiveColumns(
    MediaQuery.sizeOf(context).width,
    maxColumnWidth,
    maxColumns,
  );
}

/// 与旧版作品流一致：尽量填满可用宽度，同时限制单列的最小宽度。
int responsiveColumns(
  double width,
  double maxColumnWidth,
  int maxColumns, {
  double minColumnWidth = 280,
  double spacing = 4,
}) {
  final desired = ((width + spacing) / (maxColumnWidth + spacing)).ceil();
  final allowed = ((width + spacing) / (minColumnWidth + spacing)).floor();
  return desired.clamp(1, maxColumns).clamp(1, allowed < 1 ? 1 : allowed);
}
