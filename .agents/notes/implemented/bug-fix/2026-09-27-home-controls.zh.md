# Agent Note: 桌面首页控件

Status: implemented

[English](2026-09-27-home-controls.md) | 中文

## Problem

占满窗口的作品流需要沿用 Compose 客户端紧凑、容易发现的内容切换控件。应用还需要系统状态栏入口，以便重新打开隐藏的主窗口或退出进程。

## Decision

Flutter 首页在顶部居中放置四个按内容宽度排列的胶囊按钮，沿用 Compose 的间距、文字样式、选中颜色和圆角容器。按钮初始可见，鼠标离开后隐藏，移到窗口顶部时重新出现。按钮与页面滑动共用现有的 `TabController` 选择状态。主窗口加载完成时，macOS Runner 安装窗口事件处理对象和 AppKit 模板状态栏图标。图标带圆形 P 标记，提供 Show 和 Exit 操作。关闭主窗口时只隐藏窗口，保留 Flutter 页面状态。应用运行期间保留状态栏图标。

## Alternatives considered

**Flutter `TabBar`。** 默认标签高度、内边距和选中标记的几何尺寸与 Compose 的紧凑控件不同。

**`tray_manager` 软件包。** macOS Runner 已经负责主窗口的生命周期。AppKit 可以在同一处创建状态栏图标与菜单，无须增加 Dart 与原生窗口之间的协调路径。

## Consequences

状态栏图标在应用启动时出现，重新打开窗口后继续保留。Show 可以恢复隐藏的窗口，Exit 可以退出应用。顶部鼠标悬停区域高二十个逻辑像素；胶囊控件隐藏后释放鼠标输入。

## Verification

macOS Flutter 集成测试检查四个标签、紧凑的居中布局、选择状态和鼠标悬停时的可见性。正式构建编译 AppKit 状态栏图标并打包应用。
