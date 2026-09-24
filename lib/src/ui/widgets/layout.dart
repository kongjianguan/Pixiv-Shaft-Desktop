import 'package:flutter/material.dart';

/// 按设置里的单列宽度与最大列数，算出当前窗口宽度下应排几列。
int gridColumns(BuildContext context, double maxColumnWidth, int maxColumns) {
  final width = MediaQuery.of(context).size.width;
  if (maxColumnWidth <= 0) return maxColumns.clamp(1, 8);
  final byWidth = (width / maxColumnWidth).floor();
  return byWidth.clamp(1, maxColumns.clamp(1, 8));
}
