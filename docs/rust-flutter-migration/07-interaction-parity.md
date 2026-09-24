# 交互与 UI 功能对等性核对

本文只回答一个问题：**换成 Rust + Flutter 之后，现有的每一项交互与 UI 功能是保持、优化还是退化。**
退化的项记录回退做法。所有结论落到具体文件与行号；未在本机或 CI 实测的标注【假设】。

判定图例：保持 = 功能等价；优化 = 能力变强；退化 = 能力变弱（附回退做法，见 [§8](#8-判定为退化的项与处理决定)）。

---

## 0. 判定汇总

| 项 | 判定 | 关键依据 |
|---|---|---|
| `FeedPager` 触控板横滑翻页 | **退化**（可补偿） | 引擎跳过 momentum 阶段，现有判定的两条触发路径失效一条 |
| `FeedPager` 轴向判定 / 快速滑动距离阈值 | **保持** | `PointerPanZoomUpdateEvent.panDelta` 语义等价 |
| `ZoomableImage` 捏合缩放（视口中心锚点） | **保持** | 引擎 `magnifyWithEvent:` → `scale` |
| `ZoomableImage` Ctrl+滚轮（光标锚点） | **保持** | `PointerScrollEvent` 仍走鼠标通道 |
| `ZoomableImage` 普通滚轮平移 | **保持** | 同上 |
| `ZoomableImage` 单击切全屏 / 非 active 重置 | **保持** | `GestureDetector.onTap` |
| macOS 系统应用菜单「设置」 | **保持** | `MainMenu.xib` |
| `Main.kt`「前往」菜单 + R18 动态项 | **保持** | `PlatformMenuBar` 支持运行时重建（已查证源码） |
| 系统托盘 | **保持** | `tray_manager`，`isIconTemplate` 是一等公民 |
| 窗口背景 / live resize 防白闪 | **保持** | `macos_window_utils` |
| 小说阅读器（进度/字体/行距/段距/主题/章节） | **保持** | 纯 Dart 状态 + 滚动控制器 |
| Ugoira 播放 | **优化** | Rust 侧按需解码，消除全量解码内存峰值 |
| 作品简介 HTML 渲染 | **优化** | 成熟库替代 157 行手写解析器 |
| 剪贴板读取（搜索建议） | **保持** | `super_clipboard` / `pasteboard` |
| 导入导出文件对话框 | **保持** | `file_selector`（官方，macOS 支持保存位置） |
| Finder 显示（实为打开父目录） | **优化** | 可升级为 Finder 内真正选中文件 |
| 浏览器打开 | **保持** | `url_launcher` |
| Keychain | **保持** | 归 Rust 后端，见 01-net-layer.md |

---

## 1. FeedPager 触控板手势状态机（`ui/component/FeedPager.kt`，313 行）

这是本次核对里唯一判定为**退化**的一项。先把现有机制拆清楚，再说哪一段会失效。

### 1.1 现有机制的六个常量与三段状态

常量（第 45-50 行）：

| 常量 | 值 | 作用 |
|---|---|---|
| `TRACKPAD_SCROLL_MULTIPLIER` | `1f` | 缩放系数，实际无效果 |
| `TRACKPAD_GESTURE_IDLE_MS` | `120L` | 空闲超时后强制结算手势 |
| `TRACKPAD_HORIZONTAL_RATIO` | `0.55f` | 轴向判定：横向分量 ≥ 纵向 ×0.55 判为横向 |
| `TRACKPAD_AXIS_DECISION_DISTANCE` | `2f` | 累积位移达到 2px 才做轴向判定 |
| `FAST_SWIPE_VELOCITY` | `1200f` | 快速滑动速度阈值（px/s） |
| `FAST_SWIPE_MIN_DISTANCE` | `0.06f` | 快速滑动的最小位移（视口宽度占比） |

状态变量（第 97-108 行）：`intent`（`ScrollIntent` 三态）、`physicalGestureActive`、`ignoreMomentum`、`decisionX/Y`、`accumulatedX`、`appliedX`、`lastEventTimeNanos`、`peakHorizontalVelocity`、`startPage`、`idleJob`。

### 1.2 手势结算有两条触发路径

`settleHorizontalGesture()`（第 131-151 行）判定翻页：

- `distanceSwitch`：`abs(accumulatedX) >= pagerWidth * 0.12f`
- `velocitySwitch`：`abs(accumulatedX) >= pagerWidth * 0.06f` **且** `abs(peakHorizontalVelocity) >= 1200f` **且** `accumulatedX * peakHorizontalVelocity > 0f`（同向）
- 命中其一则 `animateScrollToPage(startPage ± 1)`，否则回弹到 `startPage`

调用它的地方有两处（第 211-223 行）：

1. **`phase.isFinished(event.phase)`** —— 手指抬起（`NSEventPhaseEnded`/`Cancelled`），立即结算并置 `ignoreMomentum = true`
2. **`event.phase == phase.NONE`** —— 起一个 `idleJob`，延迟 `120ms` 后结算

第二条路径是给**鼠标滚轮**兜底的：鼠标滚轮事件没有 gesture phase（恒为 `NSEventPhaseNone`），不会有 `Ended`，只能靠空闲超时结算。

### 1.3 退化的具体位置

引擎源码 `FlutterViewController.mm` 第 653-670 行的 `dispatchGestureEvent:`：

```objc
if (event.phase == NSEventPhaseBegan || event.phase == NSEventPhaseMayBegin) { ... kPanZoomStart }
else if (event.phase == NSEventPhaseChanged) { ... kPanZoomUpdate }
else if (event.phase == NSEventPhaseEnded || event.phase == NSEventPhaseCancelled) { ... kPanZoomEnd }
else if (event.phase == NSEventPhaseNone && event.momentumPhase == NSEventPhaseNone) { ... kHover }
else {
  // Skip momentum update events, the framework will generate scroll momentum.
}
```

三条结论：

1. **`kPanZoomEnd` 会照常送达** —— `NSEventPhaseEnded` 分支存在。所以**路径 1（手指抬起结算）保持有效**，主交互不受影响。
2. **momentum 阶段被完全丢弃** —— 引擎 `Skip momentum update events`，改由框架自己生成惯性。现有代码第 157-161 行那段「读到 `momentumPhase != NONE` 就按 `ignoreMomentum` 吞掉」的逻辑，**其输入数据源在 Flutter 侧根本不存在**。
3. **`event.phase == NSEventPhaseNone` 的语义变了** —— 在原生侧，phase==None 且 momentumPhase==None 就是鼠标滚轮；在 Flutter 侧，触控板的 `PointerPanZoom` 序列里不出现 `phase==None` 的 update，它只出现在 `PointerScrollEvent`（鼠标滚轮）通道。

**净影响**：判定并未整体失效，失去的是**「触控板手势结束后靠空闲超时兜底」这条冗余路径**。具体场景：

- 场景 A（主路径，无影响）：触控板双指横滑后抬手 → `kPanZoomEnd` → 结算。保持。
- 场景 B（受影响）：**触控板惯性滑动**。用户快速一甩后立刻抬手，原生侧靠 `momentumPhase` 判定「惯性是否还在跑」，现在引擎直接不发这些事件。若 `peakHorizontalVelocity` 在抬手那一刻的采样还没达到 1200px/s，`velocitySwitch` 不命中，就只能靠 `distanceSwitch`（12% 视口宽度）兜底 —— 短促快甩会翻页失败。
- 场景 C（受影响）：`ignoreMomentum` 的用途是「手势已结算后，把随后涌来的惯性事件吞掉，避免二次翻页」。Flutter 侧惯性由框架生成、且不会送到同一个 `Listener`，这个二次触发风险**自动消失**，是免费的好处。
- 场景 D（无影响）：鼠标滚轮横滑 → `PointerScrollEvent` 通道 → 需要空闲超时兜底。这条路径仍存在。

### 1.4 回退做法（本次不采用，保留备用）

本次决定把结算交给 Flutter 原生滚动物理，因此下面这套代码结构不实施，仅作为回退路径保留。触发回退的条件见 [§8](#8-判定为退化的项与处理决定)。

退化只在场景 B。回退方式是把「速度采样」从「依赖原生 momentum 尾部事件」改成「在 `kPanZoomEnd` 到达时做一次末速度估算」，并把空闲超时作为通用兜底（不再区分 phase）。

具体改法：

1. **`Listener` 同时挂三个回调**：`onPointerPanZoomStart`（重置状态、记 `startPage`）、`onPointerPanZoomUpdate`（累积 `panDelta.dx/dy`、采样瞬时速度）、`onPointerPanZoomEnd`（结算）。
2. **末速度改用滑动窗口**：保留最近 3~4 个 update 的 `(panDelta.dx, timeStamp)`，在 `onPointerPanZoomEnd` 时用「窗口内总位移 ÷ 窗口总时长」作为 `peakHorizontalVelocity`，取代「单帧瞬时速度取最大值」。单帧瞬时速度在事件间隔极短时会被放大，末速度窗口法比现在更稳。
3. **空闲超时改为对所有输入生效**：`onPointerPanZoomUpdate` 里 `Timer(Duration(milliseconds: 120), settle)`，每次 update 重置。这样鼠标滚轮与触控板共用一套兜底，路径 2 被统一保留。
4. **`ignoreMomentum` 直接删除** —— 引擎不发 momentum，该变量与第 157-161 行整段消失。

### 1.5 回退时的常量调参清单

| 常量 | 是否必须调 | 说明 |
|---|---|---|
| `TRACKPAD_AXIS_DECISION_DISTANCE = 2f` | **必须调** | 单位是**逻辑像素**。`panDelta` 与原生 `scrollingDeltaX` 的数值范围不同（原生已乘 `contentsScale`，见引擎第 764 行 `event.scrollingDeltaX * self.flutterView.layer.contentsScale`）。在 Retina 上原生值是物理像素、Flutter `panDelta` 是逻辑像素，两者相差 2 倍。该阈值需实测后定为 1~4 之间的某个值。 |
| `TRACKPAD_HORIZONTAL_RATIO = 0.55f` | 不必调 | 这是两个分量的**比值**，无量纲，与单位换算无关。 |
| `FAST_SWIPE_VELOCITY = 1200f` | **必须调** | 单位 px/s。因上述单位差异 + 改用末速度窗口法（窗口平均必然低于单帧峰值），阈值需要下调。建议先在 CI/真机上采样一组真实甩动数据再定。 |
| `FAST_SWIPE_MIN_DISTANCE = 0.06f` | 不必调 | 视口宽度占比，无量纲。 |
| `TRACKPAD_GESTURE_IDLE_MS = 120L` | 不必调 | 时间量，与像素单位无关。 |
| `TRACKPAD_SCROLL_MULTIPLIER = 1f` | 直接删 | 乘 1，无效果。 |
| 距离阈值 `0.12f`（第 132 行硬编码） | 不必调 | 视口宽度占比，无量纲。 |

调参验证方式（仅在触发回退后需要）：在真机上跑一次，采集 `panDelta` 与 `timeStamp` 序列落盘，离线回放不同阈值下的翻页命中率，选「短促快甩能翻页、慢速小幅拖动不误翻」的取值。这一项无法靠静态推断定值。

---

## 2. ZoomableImage（`ui/component/ZoomableImage.kt`，198 行）

逐项核对，全部**保持**。

| 现有行为 | 行号 | Flutter 对等实现 | 判定 |
|---|---|---|---|
| 捏合缩放，锚点视口中心 `vw/2, vh/2`，factor 限 0.5~2 | 95-108（`TrackpadGestureBridge.setMagnifyHandler`） | 引擎 `magnifyWithEvent:`（第 1057 行）→ `_mouseState.scale += event.magnification` → `flutterEvent.scale = pow(2.0, scale)`（第 767-773 行）。用 `GestureDetector(onScaleUpdate:)` 读累计 `scale`，锚点自行取视口中心 | 保持 |
| 捏合后缩放范围 0.5~5 | 99 行 `coerceIn(0.5f, 5f)` | `clamp(0.5, 5.0)` | 保持 |
| Ctrl+滚轮缩放，锚点光标 `cursorPos`，`1f - delta.y * 0.02f` | 163-167 | `Listener.onPointerSignal` 收 `PointerScrollEvent`，`event.position` 即光标位置；引擎保证鼠标滚轮走这条通道（第 779 行 `phase != kPanZoomStart && event.type == NSEventTypeScrollWheel` → `kFlutterPointerSignalKindScroll`） | 保持 |
| 普通滚轮平移 `delta * 30f` | 169-171 | 同上通道，`scrollDelta.dx/dy * 30` | 保持 |
| `clampAxis` 边界约束（缩放后尺寸小于视口时反向约束） | 49-53 | Dart 平移，逻辑 1:1 | 保持 |
| 单击切换全屏 `detectTapGestures(onTap)` | 119-124 | `GestureDetector(onTap:)` | 保持 |
| `active=false` 时重置为 1 倍 | 83-89 | `didUpdateWidget` 里重置 `TransformationController` | 保持 |
| `detectTransformGestures` 拖拽平移 | 127-147 | `GestureDetector(onScaleUpdate)` 的 `focalPointDelta` | 保持 |

**比现在更好的两点**：

1. 引擎把鼠标滚轮（`PointerScrollEvent`）与触控板（`PointerPanZoom`）分成两条独立通道，现有第 160 行用 `event.keyboardModifiers.isCtrlPressed` 去猜测「这次 scroll 到底是捏合还是滚轮」的试探逻辑可以删除，判定从启发式变成确定式。
2. 捏合拿到的是**累计** `scale`，现有代码拿的是增量 `magnification`，做锚点换算时不需要自己维护累加状态。

**一个待验证项**：`InteractiveViewer` 的默认手势参数与本项目「以指定点为锚点」的语义是否一致【假设】。建议不用 `InteractiveViewer`，直接 `GestureDetector` + `Transform`，与现有 Kotlin 逻辑一一对应。

---

## 3. macOS 原生集成四项

### 3.1 系统应用菜单「设置」（`platform/AppMenu.kt`，181 行）—— **保持**

现有实现：`objc_allocateClassPair` 造 `PixivShaftMenuExecutor` 类（第 118 行）+ `class_addMethod` 挂 `javaSettingsClicked:`（第 139 行）+ `performSelectorOnMainThread` 甩主线程（第 91 行）插入 `mainMenu.itemAtIndex:0.submenu` 的 index 1（第 152-165 行），失败后由 `Main.kt` 第 138 行重试。

Flutter 侧：模板自带可编辑的 `macos/Runner/Base.lproj/MainMenu.xib`。我拉取并解析确认其结构为 `<menu title="Main Menu" systemMenu="main">` 下挂 `<menu key="submenu" title="APP_NAME" systemMenu="apple">`，内含 About / Preferences… / Services / Hide / Hide Others / Show All / Quit。用 Xcode 打开往该子菜单拖 `NSMenuItem`、sent action 连到 `AppDelegate` 的 `@IBAction`、快捷键 ⌘, 在 IB 里设即可。

**判定：保持，且消除「主线程时序 + 重试」这个现存复杂度。** nib 在 `awakeFromNib` 之前加载完成，不存在主菜单为 nil 的时序窗口。

### 3.2 `Main.kt`「前往」菜单与 R18 动态项 —— **保持**

你问的这条我实际查证了源码，给确定答案：**`PlatformMenuBar` 支持运行时动态增删菜单项，不必走 `MainMenu.xib` + MethodChannel。**

证据链（`packages/flutter/lib/src/widgets/platform_menu_bar.dart`，1061 行）：

1. `PlatformMenuBar` 是 `StatefulWidget`，`menus` 是构造参数（第 457、476 行）
2. `_PlatformMenuBarState.didUpdateWidget`（第 515-528 行）：

```dart
void didUpdateWidget(PlatformMenuBar oldWidget) {
  super.didUpdateWidget(oldWidget);
  final newDescendants = <PlatformMenuItem>[
    for (final PlatformMenuItem item in widget.menus) ...<PlatformMenuItem>[
      item, ...item.descendants,
    ],
  ];
  if (!listEquals(newDescendants, descendants)) {
    descendants = newDescendants;
    _updateMenu();   // → platformMenuDelegate.setMenus(widget.menus)
  }
}
```

3. 引擎侧 `FlutterMenuPlugin.mm` 第 389-408 行 `setMenus:` 收到后**整体重建** `NSApp.mainMenu`：清 `_menuDelegates`、`[[NSMenu alloc] init]`、遍历表示逐项 `addItem:`、最后 `NSApp.mainMenu = newMenu`

4. 官方注释明确要求改菜单时传**新的 List 对象**（`menus` 文档第 470-476 行：Widget 不可变，直接 `menus.add(...)` 会导致行为错误）

也就是说：`showR18` 变化时只要重建一次 `menus` 列表（`PlatformMenuBar(menus: [...])`），`didUpdateWidget` 会检测到差异并整体重刷原生菜单。「R18 排行」项的动态显示/隐藏**完全等价**，`Main.kt` 第 172-178 行那个 `val showR18 by ...collectAsState(); if (showR18) { Item("R18 排行") }` 的写法可以直接照搬到 Dart。

代价：整体重建意味着每次 `setMenus` 都会重造全部菜单项对象。仅在设置变更时触发，不是热路径，可接受。

**需要注意的一点**：`PlatformMenuBar` 重建的是**整个** `NSApp.mainMenu`，因此使用它的同时**不能再依赖 `MainMenu.xib` 的自定义项** —— 第 234-236 行 `clearMenus()` 和第 407 行 `NSApp.mainMenu = newMenu` 会覆盖掉 nib 里的内容。二者只能选一个。

**推荐**：既然「前往」菜单和「设置」项都要做，统一走 `PlatformMenuBar`（Dart 里声明式描述全部菜单），不用 `MainMenu.xib`。这样菜单与 Dart 状态在同一处，动态项、快捷键、`PlatformProvidedMenuItem`（about / quit / hide / servicesSubmenu 等 12 种系统项，见引擎 `platform_provided_menu.h`）都由框架处理，比 nib + MethodChannel 双向同步更一致。

### 3.3 系统托盘（`platform/TrayManager.kt`，143 行）—— **保持**

| 现有 | 行号 | Flutter |
|---|---|---|
| `SystemTray` + `TrayIcon` + `PopupMenu` | 33-50 | `tray_manager` 0.7.0：`TrayIcon.create()` + `Menu.create()` + `MenuItem.createWithLabelAndType` |
| Show / 分隔符 / Exit | 35-41 | `menu.addItem(...)` + `menu.addSeparator()` |
| 点击回调 | 36、41、47 | `item.addListener((e) { if (e is MenuItemClickedEvent) ... })` |
| 反射摘 `useTemplateImages` 静态字段 FINAL 位强制改值 | 74-97 | `trayIcon.isIconTemplate = true` —— 一等公民 API |
| 运行时用 AWT 画 128×128 图标 | 99-142 | 改用静态 PNG 资源 + `isIconTemplate` |

**判定：保持。** 反射改 JDK 私有静态字段这一整段（typo 风险随 JDK 版本上升）消失，模板图标从反射 hack 变成正常 API，属于稳定性改善。

### 3.4 窗口背景（`platform/WindowBackgroundBridge.kt`，68 行）—— **保持**

现有：反射拿 `getNSWindowPtr()`（第 27-30 行），JNA 设 `NSColor.controlBackgroundColor`（第 32-43 行）与 `contentView.layer.backgroundColor`（第 45-67 行），防 live resize 时新暴露区域闪白。

Flutter：`macos_window_utils` 1.9.1 提供 `WindowManipulator` 设置窗口 material、`NSVisualEffectView` 增删、`makeTitlebarTransparent()`、`enableFullSizeContentView()`、`NSWindowDelegate`（含 `windowDidEndLiveResize` 等 30+ 事件）。

**判定：保持。** 且 `NSWindowDelegate` 能直接监听 `windowWillStartLiveResize` / `windowDidEndLiveResize`，可以把「resize 期间暂停重排」这类优化做成显式逻辑，现在做不到。

---

## 4. 小说阅读器 —— **保持**

逐项核对（`ui/screen/novel/` 7 个文件 2016 行 + `ui/novel/` 5 个文件 1238 行）：

| 功能 | 现有实现 | Flutter 对等 | 判定 |
|---|---|---|---|
| 阅读进度保存 | `NovelReaderScreen.kt` 第 99 行 `progress`，第 136 行恢复、第 145-148 行 `settings.setReaderProgress(novelId, progress)` 落盘 | `ScrollController` 监听 → 归一化进度 → 存 Rust 侧 settings | 保持 |
| 回跳到上次位置 | 第 242-247 行 `tokenIndexAtOrAfter` + `animateScrollToItem` | `ScrollController` 配 `Scrollable.ensureVisible` 或按 token 索引跳转 | 保持 |
| 字号 | `NovelReaderStyle.fontSizeSp` | `TextStyle.fontSize` | 保持 |
| 行距 | `lineSpacing`（第 182 行 `fontSizeSp * lineSpacing`) | `TextStyle.height` | 保持 |
| 段距 | `paragraphSpacing: Dp`（第 115 行 `Arrangement.spacedBy`) | `ListView` 的 item 间距 | 保持 |
| 主题（4 套预设） | `NovelReaderThemePreset`：`SYSTEM` / `PAPER` / `NIGHT` / `SAGE`，各 5 色（第 29-70 行） | Dart 枚举 + `Color` 常量，1:1 | 保持 |
| 章节切换 / 目录抽屉 | `buildChapterOutline`（`ContentParser.kt` 第 233 行） | 同上，纯 Dart | 保持 |
| `[jump:N]` 跳页 | `resolveJumpTarget`（第 291 行） | 同上 | 保持 |
| 首行缩进 | `TextIndent(firstLine = fontSizeSp*2.sp)`（第 183 行） | Flutter `TextStyle` 无首行缩进，用 `WidgetSpan` 包 `SizedBox` 实现【假设】 | 保持（实现方式变） |
| Ruby 振り仮名 | 现有仅缩小到 0.7 倍内联（`NovelContent.kt` 第 166-172 行），**未做真上标排版** | 同样降级处理即可；若要真排版需自定义 `RenderBox` 做上下两层 | 保持，且可优化 |

**判定：全部保持。** 唯一实现差异是首行缩进（`TextIndent` → `WidgetSpan`），功能等价。Ruby 若做真排版属于超出当前能力的优化，非必需。

---

## 5. Ugoira 播放 —— **优化**

`UgoiraPlayer.kt` 第 88-105 行 `decodeUgoiraZip`：

```kotlin
return sortedEntries.map { (_, data) ->
    SkiaImage.makeFromEncoded(data).toComposeImageBitmap()
}
```

zip 里**每一帧都无条件解码**，没有帧数上限、没有内存预算。Pixiv Ugoira 常见 100~300 帧，单帧 1080p JPEG 解码后 RGBA 约 8MB，200 帧就是 1.6GB 常驻内存。这是现有实现的一个真实缺陷。

Rust 侧按需解码**属于优化**，理由是数据链路本来就变了：

1. zip 由 Rust 下载并解压（图片链路已下沉，见 02-ui-layer.md §6）
2. 解码也在 Rust 侧完成，按**显示尺寸下采样**后传给 Dart，避免整图进 Dart 堆
3. 播放时只保留一个滑动窗口（当前帧前 N 帧 + 后 N 帧），其余丢弃

**判定：优化。** 消除大动图的内存峰值，同时下采样减少 Dart 堆 GC 压力。播放逻辑本身（`delay(frames[i].delay)` 循环切帧，第 62-76 行）保持不变。

---

## 6. 作品简介 HTML 渲染（`ui/component/CaptionText.kt`，157 行）—— **优化**

现有能力（已确认）：

- 支持标签：`<br>`（第 41 行）、`<b>`/`<strong>`（第 46 行）、`<i>`/`<em>`（第 52 行）、`<s>`/`<del>`（第 58 行）、`<u>`（第 64 行）、`<a href>`（第 81 行）
- 支持实体：`&amp;` `&lt;` `&gt;` `&quot;` `&#39;` `&nbsp;`（第 141-149 行）
- **未知标签直接跳到 `>` 丢弃**（第 123-132 行）
- 缺陷：`<a>` 解析依赖 `html.indexOf("href=\"", i)`，若 `<a>` 上还有其他属性且顺序不同（如 `<a target="_blank" href=...>`）仍能找到 href，但若属性用单引号则失败；`</a>` 缺失时 `aborted = true` 直接 break（第 99-105 行），**丢弃剩余全部文本**
- 缺陷：闭合标签按后进先出的顺序弹出（第 74-77 行），若 HTML 标签交叉嵌套（`<b><i></b></i>`），样式层级会错乱
- 缺陷：文本节点**逐字符 `append`**（第 133-136 行），长简介性能差

换成成熟 HTML 渲染库（如 `flutter_html`）**属于优化**：

- 支持完整 HTML 子集与自定义标签扩展
- 正确处理嵌套与未闭合标签（容错解析，不会因缺 `</a>` 丢掉剩余文本）
- 链接点击、图片、列表、代码块等可由渲染器扩展
- 未知标签不会静默丢内容

**判定：优化。** 现有 157 行手写解析器可以直接删除。需确认所选库在 macOS 桌面端的支持情况【假设，需在 CI/真机验证渲染效果与链接点击】。

---

## 7. 系统集成五项

| 功能 | 现有实现 | Flutter 对等 | 判定 |
|---|---|---|---|
| 剪贴板读取（搜索建议） | `SearchScreenModel.kt` 第 571 行 `Toolkit.getDefaultToolkit().systemClipboard`，读 `stringFlavor` | `super_clipboard` 或 `pasteboard` 包，读纯文本 | 保持 |
| 导入导出文件对话框 | `BrowseHistoryScreenModel.kt` 第 249/255/283 行 `java.awt.FileDialog`（`LOAD`/`SAVE`） | `file_selector`（官方 flutter.dev 出版，439 likes，555k downloads）：`openFile()` / `getSaveLocation()` / `getDirectoryPath()`，macOS 支持「选择保存位置」与「选择目录」 | 保持 |
| Finder 中显示 | `DownloadScreen.kt` 第 222-231 行 `revealInFinder` —— 实际是 `Desktop.getDesktop().open(parent)`，**只打开所在文件夹，并未在 Finder 中选中文件** | 官方包无「在 Finder 中选中」能力；用 `NSWorkspace.selectFile:inFileViewerRootedAtPath:` 实现，需在 `macos/Runner` 写十几行 Swift + MethodChannel，或走 Rust 侧调 AppKit | **优化**（可升级为真正选中文件） |
| 浏览器打开 | `DesktopUtils.kt` 第 6-16 行 `Desktop.getDesktop().browse(URI)`，`LoginScreen` 第 67 行等 7 处调用 | `url_launcher` 的 `launchUrl()` | 保持 |
| Keychain | `security add-generic-password` / `find-generic-password` 命令行 | 归 Rust 后端（`security-framework` crate 或 `keyring`），见 01-net-layer.md | 保持 |

「Finder 中显示」这一项说明：现有实现本身处于降级状态（只打开所在文件夹，未选中文件）。迁移时若沿用这一状态，`url_launcher` 或 Rust 侧 `open` 即可**保持**；若顺手补上 `NSWorkspace.selectFile:inFileViewerRootedAtPath:`，则变为**优化**。两条路都不阻塞，属可选项。

---

## 8. 判定为退化的项与处理决定

全文只有一项退化：

| 退化项 | 具体表现 |
|---|---|
| `FeedPager` 失去「触控板手势结束后靠空闲超时兜底」的冗余结算路径 | 短促快甩抬手时，若末速度采样未达 1200px/s 且位移未达视口 12%，翻页失败 |

**处理决定：交给 Flutter 原生滚动物理，不采用补偿。** `PointerPanZoom` 事件体系的设计动机就是让触控板滚动产生惯性，相关识别器由手势竞技场仲裁、惯性由 `PageScrollPhysics` 产生，因此 `peakHorizontalVelocity` 采样、`settleHorizontalGesture` 与 `ignoreMomentum` 三段整体删除。详见 [00-conclusion.md §4](00-conclusion.md#4-触控板翻页的处理决定)。

**回退条件**：真机上若判定「横向滑动与纵向滚动共存时的裁定」「短促快甩能否翻页」「慢速拖动是否回弹」三条中任一条不可接受，则在同一部件内加 `Listener` 接管 `onPointerPanZoomStart/Update/End` 并手动驱动结算。此时下面两个常量需要按真机数据重新定值：

- `TRACKPAD_AXIS_DECISION_DISTANCE`（现 2f）：原生 `scrollingDeltaX` 已乘 `contentsScale`（物理像素），Flutter `panDelta` 是逻辑像素，Retina 上差 2 倍
- `FAST_SWIPE_VELOCITY`（现 1200f）：同上单位差异，加上若改用窗口平均速度则必然低于单帧峰值，阈值需下调

其余所有项判定为保持或优化，无需补偿。
