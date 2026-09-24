# UI 层迁移评估：Compose Desktop → Flutter Desktop

评估对象：Pixiv-Shaft-Desktop 的 `:app` 模块（主代码 19236 行，其中 `ui/` 目录 16558 行；Kotlin + Compose Multiplatform 1.7.3）。
目标方案：Rust（后端）+ Flutter（UI），运行平台 macOS 桌面端，单平台。

本文所有结论都对应到具体文件与具体 API 调用。凡未在本机实际运行验证的部分，一律标注【假设】。

---

## 0. 结论速览

| 功能 | 现有实现 | Flutter macOS 桌面端 | 性质 |
|---|---|---|---|
| 触控板捏合缩放 | JNA + NSEvent 本地 monitor（`TrackpadGestureBridge.kt`） | **引擎原生支持**，`magnifyWithEvent:` → `PointerPanZoom` → `onScale` | 直接翻译，且比现在简单 |
| 触控板滚动翻页 | 同上，读 `scrollingDeltaX/Y` + `phase`/`momentumPhase` | 引擎原生支持，`PointerPanZoomUpdateEvent.panDelta` | 直接翻译 |
| macOS 系统应用菜单 | JNA + `class_addMethod` + AppKit 主线程（`AppMenu.kt`） | **macos/Runner 目录下有可编辑的 `MainMenu.xib`**，直接摆菜单项 | 直接翻译，比现在简单很多 |
| 系统托盘 | AWT `SystemTray` + 反射改私有静态字段（`TrayManager.kt`） | `tray_manager` 包，支持 `isIconTemplate` | 直接翻译 |
| 窗口背景/毛玻璃 | JNA 改 `NSWindow.backgroundColor`（`WindowBackgroundBridge.kt`） | `macos_window_utils` 包 | 直接翻译 |
| 小说标记语法解析 | 纯 Kotlin 正则（`ContentParser.kt` / `InlineMarkup.kt`） | Dart 正则，逻辑 1:1 搬运 | 直接翻译 |
| 小说富文本渲染 | Compose `ClickableText` + `buildAnnotatedString` | Flutter `RichText` + `WidgetSpan` | 重新设计（Ruby 振り仮名要自己排版） |
| 小说 HTML 内嵌 JSON 解析 | 手写 JS-JSON 容错解析器（`NovelWebParser.kt`） | Dart 搬运 | 直接翻译 |
| Ugoira 播放 | 下载 zip → Skia 解码 → `delay()` 逐帧 | `ui.instantiateImageCodec` / 逐帧 `Image` | 直接翻译 |
| Ugoira 转 GIF | Java AWT `ImageIO` 写 GIF（`UgoiraGifEncoder.kt`） | Dart `image` 包写动画 GIF，或交 Rust | 直接翻译（有风险，见 §5） |
| 下载队列 | Kotlin 协程 + SQLDelight（`DownloadManager.kt`，1196 行） | 归后端 Rust 承担，Flutter 只做 UI | 不属于 UI 层 |
| 文件名模板 | 纯字符串处理（`DownloadTemplate.kt`） | Dart 搬运（UTF-8 字节截断逻辑要重写） | 直接翻译 |
| 图片加载 | Coil 3 + 自定义 OkHttp（Referer + 无 SNI TLS） | **无对等能力**，需 Rust 侧承担 HTTP | 必须自写（Rust 插件） |
| 40 个页面 | Voyager + ScreenModel | `go_router` / `Riverpod` 等 | 翻译，量大 |

三个「要命的坑」在本文中的排序：图片加载链路（§6）> 触控板手势（§2）> 系统菜单栏（§1）。
注意这与直觉相反：系统菜单栏在 Flutter 侧反而**变简单了**。

---

## 1. macOS 系统应用菜单（AppMenu.kt，181 行）

### 1.1 现有实现做了什么

`app/src/main/kotlin/ceui/pixiv/platform/AppMenu.kt` 往系统应用菜单（Apple logo 旁边那个以应用名命名的菜单）里插一个「设置」项。文件头注释记录了两个硬约束：

- 主菜单只能在主线程改，否则 AppKit 抛出 `NSInternalInconsistencyException`。JVM 的 EDT 不是 Cocoa 主线程，`dispatch_get_main_queue` 没有导出符号，所以 dispatch 路线走不通。
- 菜单项的 action 必须是 Objective-C 方法，不能是 C 函数指针。

现有解法是动态造一个 ObjC 类：

- `objc_allocateClassPair(cls("NSObject"), "PixivShaftMenuExecutor", 0)`（第 118 行）
- `class_addMethod(execClass, sel("javaSettingsClicked:"), CallbackReference.getFunctionPointer(cbSettings), "v@:@")`（第 139 行）
- 用 `performSelectorOnMainThread:withObject:waitUntilDone:` 把插入动作甩到 AppKit 主线程（第 91 行）
- 插入位置：`NSApplication.sharedApplication.mainMenu.itemAtIndex:0.submenu`，`insertItem:atIndex:1`（第 152-165 行）

`Main.kt` 第 131 行调用，且第 138 行有 `if (AppMenu.isInstalled) return@LaunchedEffect` 的重试逻辑——因为主菜单在启动早期可能还是 nil。

### 1.2 Flutter 侧对等能力

**结论：原生支持，而且不需要写任何 JNA/ObjC 代码。**

Flutter 的 macOS 项目模板自带一个标准的 `MainMenu.xib`，位于
`packages/flutter_tools/templates/app/macos.tmpl/Runner/Base.lproj/MainMenu.xib`。我实际拉取并解析了这个文件，它的结构是：

- `<menu title="Main Menu" systemMenu="main">`
- 其下第一个子菜单是 `<menu key="submenu" title="APP_NAME" systemMenu="apple">`，即那个系统应用菜单
- 该子菜单内已含 `About APP_NAME`、`Preferences…`、`Services`、`Hide APP_NAME`、`Hide Others`、`Show All`、`Quit APP_NAME` 项
- 其余子菜单：`Edit`、`Find`、`Spelling`、`Substitutions`、`Transformations`、`Speech`、`View`（含 `Enter Full Screen`）、`Window`、`Help`

也就是说：**Flutter macOS 应用默认就有一份可编辑的 nib 菜单文件，用 Xcode 打开往 `systemMenu="apple"` 那个 menu 里拖一个 `NSMenuItem`、把它的 sent action 连到 `AppDelegate` 的 `@IBAction` 即可**，快捷键（⌘,）在 Interface Builder 里直接设。这比现在的 `class_addMethod` 方案简单一个数量级，而且没有「主线程时序」问题——nib 在 `awakeFromNib` 之前就已加载完成。

需要动态增删菜单项时的备选：

| 包 | 版本 / 发布时间 | 能力 | 判断 |
|---|---|---|---|
| [`mac_menu_bar`](https://pub.dev/packages/mac_menu_bar) | 0.0.5，2 个月前发布 | 拦截 Cut/Copy/Paste/Select All，支持 `addSubmenu(parentMenuId:'main')`、`addMenuItem(menuId:'View', ...)`、`SingleActivator` 快捷键 | 能用，但只有 5 likes / 160 pub points / 189 downloads，属于极早期包。**不建议作为主路径**，只作为「运行时动态加项」的补充 |
| [`enhanced_platform_menu`](https://pub.dev/packages/enhanced_platform_menu/versions/0.2.1) | 0.2.1 | 扩展 Flutter 官方 platform_menu | 未细查【假设】 |
| `menubar`（google/flutter-desktop-embedding） | 已归档 | — | 已废弃，不要用 |

**推荐路径**：静态菜单项（「设置」「我的」这类固定项）写进 `MainMenu.xib`；菜单点击回调通过 `AppDelegate` 里的 `FlutterMethodChannel` 发给 Dart。这是最稳的一条路，不依赖任何第三方包。

### 1.3 工作量

| 项 | 行数 | 性质 |
|---|---|---|
| `AppMenu.kt` 删除 | -181 | 直接删除 |
| `MainMenu.xib` 里摆 1-2 个菜单项 + `AppDelegate` 里加一个 method channel 转发 | +80（Swift + Dart 各一半） | 自己写原生代码，但量很小 |

净减少约 100 行。这一项**不是风险**。

---

## 2. 触控板手势（ZoomableImage.kt 198 行 + TrackpadGestureBridge.kt 184 行）

这是看图软件的核心交互，也是我重点核实的一项。

### 2.1 现有实现做了什么

`ZoomableImage.kt` 有三条并存的输入路径：

1. `detectTapGestures`（第 119 行）——单击切换全屏
2. `detectTransformGestures`（第 127 行）——鼠标拖拽 + 触摸板双指
3. `awaitPointerEventScope { while(true) { awaitPointerEvent() ... } }`（第 151 行）——监听 `PointerEventType.Scroll`，用 `event.keyboardModifiers.isCtrlPressed` 判定是否捏合（第 160 行），Ctrl+scroll 走缩放（第 163-167 行），普通 scroll 在 `scale > 1f` 时走平移（第 168-172 行）

关键：`ZoomableImage.kt` 第 95-108 行注册了 `TrackpadGestureBridge.setMagnifyHandler`，走的是真正的 `NSEvent.magnification`。

`TrackpadGestureBridge.kt` 的做法（文件头注释说明了原因：Compose Desktop 1.7.x / skiko-awt 只注册 Mouse/MouseMotion/MouseWheel 监听器，AWT 直接丢弃 `NSEventTypeMagnify`）：

- 手工在 `Memory` 里拼一个 Objective-C block 结构体（第 51-76 行）：`isa = *_NSConcreteGlobalBlock`、`flags = 0x10000000`（`BLOCK_IS_GLOBAL`）、`invoke = JNA Callback` 桩、descriptor 里写 `size = 32`
- `[NSEvent addLocalMonitorForEventsMatchingMask:handler:]`，mask = `(1<<22) | (1<<30)`，即 `NSEventTypeScrollWheel | NSEventTypeMagnify`（第 85-91 行）
- `onEvent`（第 150 行）按 `event.type` 分发：30（magnify）读 `magnification`；22（scroll）读 `scrollingDeltaX/Y`、`phase`、`momentumPhase`，返回值决定是否吞掉事件

`TrackpadGestureBridge` 还被 `FeedPager.kt:95` 和 `IllustDetailScreen.kt:271` 使用，用于触控板横向翻页。

### 2.2 Flutter 侧对等能力

**结论：引擎原生支持，不需要任何 JNA / block 拼装。**

我拉取了 Flutter 引擎 macOS 嵌入层源码
`shell/platform/darwin/macos/framework/Source/FlutterViewController.mm`（1097 行）核实：

```objc
- (void)magnifyWithEvent:(NSEvent*)event {   // 第 1057 行
  [self dispatchGestureEvent:event];
}
- (void)rotateWithEvent:(NSEvent*)event {    // 第 1061 行
  [self dispatchGestureEvent:event];
}
- (void)scrollWheel:(NSEvent*)event {        // 第 1054 行
  [self dispatchGestureEvent:event];
}
```

`dispatchGestureEvent:`（第 653 行）按 `event.phase` 映射为 `kPanZoomStart` / `kPanZoomUpdate` / `kPanZoomEnd`。
`dispatchMouseEvent:phase:`（第 671 行）在处理 `kPanZoomUpdate` 时（第 763-773 行）：

```objc
if (event.type == NSEventTypeScrollWheel) {
  _mouseState.delta_x += event.scrollingDeltaX * self.flutterView.layer.contentsScale;
  _mouseState.delta_y += event.scrollingDeltaY * self.flutterView.layer.contentsScale;
} else if (event.type == NSEventTypeMagnify) {
  _mouseState.scale += event.magnification;
}
flutterEvent.pan_x = _mouseState.delta_x;
flutterEvent.pan_y = _mouseState.delta_y;
flutterEvent.scale = pow(2.0, _mouseState.scale);   // 归一化到 0→∞
```

并发出的 `deviceKind = kFlutterPointerDeviceKindTrackpad`，`device = kPointerPanZoomDeviceId`（第 746-748 行）。
引擎还处理了惯性取消：`touchesBeganWithEvent:` 里发 `kFlutterPointerSignalKindScrollInertiaCancel`（第 1073-1095 行）。

框架侧也确认支持。我拉取了 `packages/flutter/lib/src/gestures/scale.dart`，`ScaleGestureRecognizer` 有 `isPointerPanZoomAllowed`（第 529 行）、`addAllowedPointerPanZoom`（第 532 行）、`_pointerPanZooms`（第 450 行），并在 `handleEvent` 里处理 `PointerPanZoomStartEvent` / `Update` / `End`（第 568-582 行）。`GestureRecognizer` 基类有 `addPointerPanZoom`（recognizer.dart 第 210 行）。

官方行为变更文档
[Trackpad gestures can trigger GestureRecognizer](https://docs.flutter.dev/release/breaking-changes/trackpad-gestures)
（自 Flutter 3.3.0 stable 起）说明：`Listener` 新增 `onPointerPanZoomStart` / `Update` / `End`；`PointerPanZoomUpdateEvent` 带 `pan`（累计平移）、`panDelta`（增量）、`scale`（累计缩放）、`rotation`；`GestureDetector` 会自动把 trackpad 手势喂给 recognizer 并触发 `onScale`。

### 2.3 迁移对应关系

| 现在（Compose） | 迁移后（Flutter） | 说明 |
|---|---|---|
| `TrackpadGestureBridge` 手工拼 block + `addLocalMonitorForEventsMatchingMask` | **整段删除** | 引擎已做 |
| `setMagnifyHandler` + `zoomTo(newScale, vw/2, vh/2)` | `GestureDetector(onScaleUpdate: ...)` | 引擎给的是累计 `scale`，比 `magnification` 增量更好用 |
| `awaitPointerEventScope` 里的 Ctrl+scroll 判定 | `Listener.onPointerSignal` 里的 `PointerScrollEvent` | 引擎保证鼠标滚轮走 `PointerScrollEvent`、触控板走 `PointerPanZoom`，**两者不再混淆**，不需要 `isCtrlPressed` 这种试探 |
| `TrackpadGestureBridge.ScrollEvent` 的 `phase` / `momentumPhase`（`FeedPager.kt:155`） | `PointerPanZoomStartEvent` / `Update` / `End` 三段 | 语义等价；`panDelta` 已是像素级 |
| `clampAxis` / `zoomTo` / `clampAndApply`（第 49-81 行） | Dart 平移，逻辑 1:1 | 纯数学，直接搬 |

### 2.4 工作量与残留风险

| 项 | 行数 | 性质 |
|---|---|---|
| `TrackpadGestureBridge.kt` 删除 | -184 | 直接删除 |
| `ZoomableImage.kt` → Dart `ZoomableImage` widget | 198 → ~150 | 重新设计（手势状态机换成 `GestureDetector` + `TransformationController`） |
| `FeedPager` / `IllustDetailScreen` 的 scroll handler | -100 左右 | 直接删除（改为 `Listener` 三段回调） |

残留风险（低）：

- `InteractiveViewer` 是 Flutter 自带的缩放/平移组件，但它的默认手势参数与本项目「以光标为锚点」的 `zoomTo(newScale, cursorPos.x, cursorPos.y)` 语义不完全一致【假设，未实测】。建议不用 `InteractiveViewer`，直接自己用 `GestureDetector` + `Transform` 实现，逻辑与现有 Kotlin 一一对应。
- 引擎把 magnify 的 `scale` 归一化为 `pow(2, magnification)`，给出的是**累计值**。现有 `TrackpadGestureBridge` 拿的是增量 `magnification`。换算方式要改，但这是纯数学。
- 引擎会**跳过** momentum 阶段的 update 事件（注释：`Skip momentum update events, the framework will generate scroll momentum`），惯性由框架自己生成。现有 `TrackpadGestureBridge` 是自己读 `momentumPhase` 处理的。若 `FeedPager` 的翻页手感依赖原生惯性数据，需要实测调参。

**这一项从「最难」变成「中等」，Flutter 侧明显更好。**

---

## 3. 系统托盘（TrayManager.kt，143 行）

### 3.1 现有实现

用 AWT `SystemTray` + `TrayIcon` + `PopupMenu`（第 33-50 行），菜单是 "Show" / 分隔符 / "Exit"。
图标是运行时用 `BufferedImage` 画的：黑底圆 + 透明 "P" 挖空（第 99-142 行），128×128 画完让系统缩小。

踩坑记录在 `enableTemplateImages()`（第 74-97 行）：为了让 macOS 按亮/暗菜单栏自动着色，需要打开 template image 模式；但现代 JDK 里 AWT 的 peer 不再暴露 `NSImage` 字段，只能：

1. 设系统属性 `apple.awt.enableTemplateImages=true`
2. `Class.forName("sun.lwawt.macosx.CTrayIcon")`
3. 反射摘掉 `useTemplateImages` 静态字段的 `FINAL` 位再强行改值

### 3.2 Flutter 侧

[`tray_manager`](https://pub.dev/packages/tray_manager) 0.7.0（4 天前发布，LeanFlutter 出版，289 likes，231k downloads，macOS/Linux/Windows 全支持）。新版基于 `nativeapi`（一个 C++ 核心库的 Flutter binding）。

对应 API：

| 现在 | 迁移后 |
|---|---|
| `SystemTray.getSystemTray().add(icon)` | `TrayIcon.create()` + `trayIcon.setVisible(true)` |
| `PopupMenu` + `MenuItem("Show")` | `Menu.create()` + `MenuItem.createWithLabelAndType('Show Window', MenuItemType.normal)` + `menu.addSeparator()` |
| `addActionListener { onShow() }` | `item.addListener((event) { if (event is MenuItemClickedEvent) ... })` |
| 反射改 `useTemplateImages` | `trayIcon.isIconTemplate = true` — **一等公民 API，无需反射** |
| 用 AWT 画图标 | `ImageAsset.fromAsset('images/tray_icon.png')`，或 `Image.fromFile` / `Image.fromBase64` |

注意：0.6 起有 breaking change，老 API 要 import `package:tray_manager/legacy.dart`，且该桥接层已标 `@Deprecated`「将在后续版本移除」。新项目直接用新 API。

图标素材：现在运行时画，迁移后建议改成一张 PNG 资源（黑色 + alpha，设 `isIconTemplate = true`），省掉 43 行绘图代码。

### 3.3 工作量

143 行 → 约 50 行 Dart + 1 张 PNG。直接翻译，无风险。

---

## 4. 窗口背景（WindowBackgroundBridge.kt，68 行）

现有实现通过反射拿 AWT peer 的 `getNSWindowPtr()`（第 27-30 行），再 JNA 调 `setBackgroundColor:`（`NSColor.controlBackgroundColor`）和 `contentView.layer.backgroundColor`。目的是防止 live resize 时新暴露区域闪白。

Flutter 侧用 [`macos_window_utils`](https://pub.dev/packages/macos_window_utils) 1.9.1（8 个月前发布，macosui.dev 出版，85 likes）：

- `WindowManipulator.makeTitlebarTransparent()` / `enableFullSizeContentView()`
- 设置窗口 material（`NSVisualEffectViewMaterial.windowBackground`）
- 加/改 `NSVisualEffectView` 子视图（毛玻璃）
- `TitlebarSafeArea` / `TransparentMacOSSidebar` 配套 widget
- `NSWindowDelegate`（可监听 resize / fullscreen / move 等 30+ 个事件）
- `NSAppPresentationOptions` 控制全屏时自动隐藏工具栏/菜单栏

68 行 → 约 20 行。无风险。

---

## 5. 小说阅读器（ui/novel/，1238 行）

### 5.1 ContentParser.kt（308 行）—— 直接翻译

纯 Kotlin 字符串/正则处理，无任何平台依赖。逐项可搬：

| 现有 | 说明 |
|---|---|
| `pixivImageRegex = \[pixivimage:(\d+)(?:-(\d+))?]`（第 14 行） | 插图标记，`-N` 是页码 |
| `uploadedImageRegex`（第 13 行） | 上传图 |
| `chapterRegex = \[chapter:(.+?)]`（第 15 行） | 章节标题 |
| `jumpRegex = \[jump:(\d+)]`（第 16 行） | 跳页，1-indexed |
| `NEWPAGE_TAG = "[newpage]"`（第 18 行） | 分页符 |
| `htmlBreakRegex = <br\b[^>]*>`（第 17 行） | 代理返回体里的 HTML 换行，替换成等长空白以保持 offset（第 48-50 行） |
| `cleanChapterTitle`（第 31 行） | 去掉章节标题里包围数字的直/弯引号对（用户反馈的坑），保留 `John's` 这类合法撇号 |
| `tokenize`（第 44 行） | 逐行分词 |
| `splitInlineJumps`（第 138 行） | 行内 `[jump:N]`（CYOA 分支小说）拆成 Paragraph + Jump 交替 |
| `coalesceParagraphBreaks`（第 206 行） | 段落后的第一个 BlankLine 吞掉，避免段间距翻倍 |
| `buildChapterOutline`（第 233 行） | 生成目录抽屉，混排 chapter 与 newpage 派生的「分页 N」，首个内容前补「前言」/「分页 1」 |
| `resolveJumpTarget`（第 291 行） | `[jump:N]` 目标 → 源码字符 offset |

Dart 的 `RegExp` 基于 ECMAScript 语法，与 Kotlin `Regex`（Java 语法）在**环视（lookaround）**上有一处需要核对：`danglingChapterQuoteRegex`（第 27 行）用了正向环视 `(?<=[\u3400-\u4DBF\u4E00-\u9FFF])['‘’](?=\s|[，。！？；：、,.!?;:]|$)`。

已核实：Dart 的 `RegExp` **支持 lookbehind**。Dart SDK 有专门的测试文件 `tests/corelib/regexp/lookbehind_test.dart`（移植自 V8 的同类测试），内含 `(?<=a)`、`(?<=a\wc)`、`(?<=a[a-z]{2})`、`(?<=^abc)def` 等 20+ 个正向后顾用例并断言匹配成功。dart-lang/sdk#34935（要求补齐 lookbehinds / property escapes / named groups）已于 2019-04-29 关闭。

所以该正则可以**原样搬运**，不需要改写。这一节没有正则层面的障碍。

### 5.2 InlineMarkup.kt（106 行）—— 直接翻译

- `JumpUriParser`：`\[\[jumpuri:([^>]+)>([^]]+)]]` → 可点击链接（第 42 行）
- `RubyParser`：`\[\[rb:([^>]+)>([^]]+)]]` → 振り仮名（第 49 行）
- `InlineMarkupProcessor.process`（第 70 行）：收集所有 parser 的匹配、按位置排序、重映射 span 偏移到清洗后文本。第 72 行有 `if (!raw.contains("[[")) return` 的快路径。

Dart 直接搬，无平台依赖。

### 5.3 NovelWebParser.kt（381 行）—— 直接翻译

这是 `/webview/v2/novel` 返回的 HTML 里嵌的 `window.pixiv = {...}` 的容错解析器。纯字符串处理：

- `scriptRegex` 抽 `<script>` 内容（第 19 行）
- `pixivMarker` 匹配 `Object.defineProperty(window,'pixiv',`（第 23 行）
- `parseValueExpression`（第 96 行）处理 `JSON.parse('...')` 包装
- `findJsonObjectEnd`（第 358 行）括号配对，忽略字符串内的括号
- `normalizeJavaScriptJson`（第 238 行）去掉 JS 注释、单引号字符串、尾逗号、`undefined` → `null`
- `decodeJavaScriptString`（第 292 行）手工解 JS 字符串转义（含 `\uXXXX`）
- `parseWebNovelTolerantly`（第 156 行）字段级容错转换，保证字段类型变了仍能显示正文

Dart 搬，注意：Dart 没有 Gson，`JsonReader` 的 LENIENT 模式（第 139-141 行）不存在。迁移后应先跑 `normalizeJavaScriptJson` 再交给 `dart:convert` 的 `jsonDecode`，这点比 Kotlin 侧更简单（因为归一化已经把 JS 语法清干净了）。`parseWebNovelTolerantly` 需要手写字段级读取（约 80 行）。

### 5.4 NovelContent.kt（271 行）—— 重新设计

这一层是渲染，Compose 与 Flutter 的抽象不同：

| 现有（Compose） | 迁移后（Flutter） |
|---|---|
| `LazyColumn` + `itemsIndexed`（第 104-138 行） | `ListView.builder`（列表虚拟化语义一致） |
| `rememberLazyListState` + `animateScrollToItem`（第 65 行） | `ScrollController` + `animateTo` |
| `snapshotFlow { Triple(firstVisibleItemIndex, ...) }.distinctUntilChanged().collect`（第 80-100 行）算阅读进度 | `ScrollController.addListener` 里读 `position.pixels / position.maxScrollExtent` |
| `ClickableText` + `buildAnnotatedString` + `addStringAnnotation("URL", ...)`（第 151-189 行） | `RichText` + `TextSpan` + `Recognizer`（`TapGestureRecognizer`） |
| `TextIndent(firstLine = fontSizeSp*2.sp)` 首行缩进（第 183 行） | Flutter `TextStyle` 无首行缩进；用 `WidgetSpan` 包一个 `SizedBox` 或自定义 `TextPainter`【假设】 |
| Ruby 渲染：只把 ruby 文本**缩小到 0.7 倍、跟在正文后面**（第 166-172 行，没有真正的上标排版） | 同样降级处理即可（`TextStyle(fontSize: 0.7x)` + `WidgetSpan` 上标），或引入 [`flutter_furigana_text`](https://pub.dev/packages/flutter_furigana_text)（0.0.4，14 个月前发布，**只有 3 likes / 40 downloads**，极不成熟，不建议依赖） |
| `ImageItem` 用 `AsyncImage`（第 216 行） | 走 Rust 侧取图（见 §6） |

判断：**Ruby 振り仮名的真正排版（ruby 字符上标于基文本之上、整词不换行断开）需要自己写**。但是现有实现根本没有做真排版（只是缩小字号内联），所以「等价实现」的门槛很低——照搬现在的降级方案即可，工作量不增加。若想做得比现在好，则要用 `WidgetSpan` + 自定义 `RenderBox` 做上下两层布局，约 +150 行。

另外：Flutter 的 `RichText` 配合 `WidgetSpan` 时，跨 inline widget 的**文本选择**行为与纯文本不同（`WidgetSpan` 内不可选）【假设，未实测】。现有实现的 `ClickableText` 只支持点击链接、不支持选择，所以这个差异不影响功能对等。

### 5.5 工作量

| 文件 | 行数 | 性质 |
|---|---|---|
| `ContentParser.kt` | 308 → ~310 | 直接翻译（正则全部可原样搬） |
| `NovelWebParser.kt` | 381 → ~400 | 直接翻译（去掉 Gson，加字段级读取） |
| `InlineMarkup.kt` | 106 → ~110 | 直接翻译 |
| `NovelContent.kt` | 271 → ~300 | 重新设计 |
| `NovelReaderStyle.kt` / `ContentToken.kt` / `NovelImageResolver.kt` | 172 → ~180 | 直接翻译 |

合计 1238 → ~1310 行。持平略增，主要是 Dart 没有 Gson。

---

## 6. 图片加载（ImageLoaderFactory.kt + CoilFactoryBridge.java）—— **最大的坑**

### 6.1 现有实现

`ImageLoaderFactory.buildImageClient()` 里：

1. 拦截器加 `Referer: https://app-api.pixiv.net/` 和伪装 UA（不带 Referer 会 403）
2. 直连模式下：`sslSocketFactory(RubySSLSocketFactory(), TrustAllCertManager())` —— **无 SNI TLS**（不发送 SNI 以绕过 GFW 探测）+ 信任自签证书
3. `hostnameVerifier { _, _ -> true }`
4. `dns(HttpDns(settings, StdoutLogger))` —— CloudFlare DoH 解析 pximg.net 绕过 DNS 污染
5. `protocols(listOf(HTTP_1_1))`
6. 磁盘缓存 256MB（`~/Library/Caches/PixivShaft/images`）+ 内存缓存 128MB

`CoilFactoryBridge.java` 只是为了让 Kotlin 2.1.20 能解析 Coil 的 `@JvmName` companion 扩展而存在的 Java 桥接。

### 6.2 Flutter 侧

**没有对等能力。** 逐项拆：

| 需求 | Flutter / Dart 侧 | 结论 |
|---|---|---|
| 自定义 header（Referer / UA） | `Image.network(url, headers: {...})` 原生支持 | ✅ 简单 |
| 磁盘 + 内存缓存 | `cached_network_image` + `flutter_cache_manager` | ✅ 有现成方案 |
| **禁用 TLS SNI** | **Dart `dart:io` HttpClient 无此 API**。[dart-lang/sdk#44122](https://github.com/dart-lang/sdk/issues/44122)「Custom SNI support in HttpClient」自 2020 年起**仍然 open**（最后更新 2024-08，3 条评论） | ❌ Dart 侧无解 |
| 信任自签证书 | `HttpClient.badCertificateCallback` 可以放行 | ✅ 但只是绕过校验，不等于不发 SNI |
| HttpDns（DoH） | Dart 可以自己发 DoH 请求拿 IP，但 `HttpClient` 没有「指定 IP + 保留 Host 头」的干净入口（需自定义 `connectionFactory`，能力受限） | ⚠️ 勉强 |
| QUIC（API 层） | Dart 无成熟 QUIC 客户端 | ❌ 必须 Rust |

### 6.3 结论：图片链路必须整体下沉到 Rust

这是本文档最重要的架构结论。Flutter 的 `Image` widget 无法承载项目的反墙需求，必须改成：

- Rust 侧用 `reqwest`/`rustls` 承担全部图片 HTTP：无 SNI（`rustls` 的 `ClientConfig` 可以配 `enable_sni: false`）、DoH、Referer、连接复用、磁盘缓存
- 通过 `flutter_rust_bridge` 暴露给 Dart：
  - 最简单形态：`Future<Uint8List> fetchImageBytes(String url)`，Dart 侧 `Image.memory(...)`
  - 更好形态：Rust 返回本地缓存文件路径，Dart 用 `Image.file(...)`，避免大 byte array 跨 FFI 边界拷贝（Ugoira 一帧可能几 MB）

代价是**必须自己实现 Coil 的那部分**：内存 LRU 缓存、磁盘缓存、请求去重/合并、ImageView 复用时的取消语义。这部分在 Coil 里是白拿的，在 Rust 侧要自己写，粗估 300-500 行 Rust【假设】。

`flutter_rust_bridge` 2.13.0（31 天前发布，663 likes，官方 Flutter Favorite，支持 macOS）：macOS 上编译成 dylib 并被 `macos/Runner.xcodeproj` 链接进 app bundle。这条路径成熟。

### 6.4 工作量

| 项 | 行数 | 性质 |
|---|---|---|
| `ImageLoaderFactory.kt` + `CoilFactoryBridge.java` | -60 | 删除 |
| Rust 图片客户端（无 SNI + DoH + Referer + 磁盘缓存 + 内存缓存） | +400~500（Rust） | **自己写原生代码** |
| Dart 侧图片 widget 封装（走 frb 拿 bytes/文件路径） | +200 | 自己写 |
| `flutter_rust_bridge` 桥接配置 | +100 | 一次性 |

---

## 7. Ugoira 动图（UgoiraPlayer.kt 105 行 + UgoiraGifEncoder.kt 99 行）

### 7.1 播放（直接翻译）

现有实现：`LaunchedEffect` 里用 OkHttp 下载 zip（第 46 行）→ `ZipInputStream` 逐项解压出 `.jpg`/`.png`（第 88-101 行）→ `SkiaImage.makeFromEncoded(data).toComposeImageBitmap()`（第 103 行）→ `delay(frame.delay)` 循环切 `currentIndex`（第 62-76 行）。

Flutter 侧：

- zip 解压：Dart 有 `archive` 包；或直接让 Rust 解（Rust 侧反正要接管下载，顺手解压更省一次跨边界传输）——**推荐后者**
- 解码：`ui.instantiateImageCodec(bytes)` 或 `ui.decodeImageFromList`，得到 `ui.Image`
- 播放：与 Kotlin 完全同构，`Future.delayed(Duration(milliseconds: frames[i].delay))` 循环 + `setState` 换帧

需要注意：现有实现把**所有帧一次性全部解码进内存**（第 102 行 `sortedEntries.map { ... }` 无上限）。大 Ugoira（上百帧 × 1080p）会吃几个 GB。迁移时建议改成按需解码 + 窗口缓存，不过这一项属于改进，迁移本身并不要求。

105 行 → ~120 行 Dart。直接翻译。

### 7.2 转 GIF（有风险）

现有 `UgoiraGifEncoder.kt` 用 Java AWT `ImageIO`：

- `ImageIO.getImageWritersBySuffix("gif")`（第 19 行）
- `writer.prepareWriteSequence(null)` / `writeToSequence` / `endWriteSequence`（第 28/38/57 行）
- 手写 GIF 元数据树：`GraphicControlExtension` 的 `delayTime`（单位 10ms，`(delayMs+5)/10`，第 75 行）、`disposalMethod=none`；`ApplicationExtension` 的 NETSCAPE 2.0 循环扩展（第 77-84 行）

Dart 侧方案：

| 方案 | 判断 |
|---|---|
| [`image`](https://pub.dev/packages/image) 包（4.10.1） | **已核实支持动画 GIF**。读取其 `lib/src/formats/gif_encoder.dart`（495 行）：`GifEncoder.addFrame(Image image, {int? duration})`（第 56 行）、按 `image.loopCount` 写 `repeat`（第 154 行）、对动画帧循环 `addFrame(f, duration: f.frameDuration ~/ 10)`（第 157 行，毫秒转 10ms 单位，与 Java 版 `(delayMs+5)/10` 语义一致） |
| Rust `gif` crate / `image` crate | 成熟可靠，`image` crate 的 `GifEncoder` 直接吃 `Frame` 序列带 delay |

考虑到 §6 已经要把图片下沉到 Rust，**GIF 编码一并放 Rust 侧最省事**（帧数据本来就在 Rust 手里，不必再传回 Dart）。推荐 Rust `image` crate 的 `GifEncoder`。若留在 Dart 侧，`image` 包也被证实具备该能力，可作为退路。

99 行 Kotlin → ~80 行 Rust。直接翻译，但要实测输出 GIF 的帧延迟是否与 Java 版一致。

---

## 8. 下载（download/，1576 行）

`DownloadManager.kt` 1196 行，含：

- `enqueueIllust` / `enqueueUgoira` / `enqueueNovel` / `enqueueNovelSeriesChapter` / `enqueueNovelMerge`（第 94/150/194/200/399 行）
- `refreshExistingTask`（第 251 行）—— 已存在任务的刷新判定，小说用 `novelChapterMatches`（第 385 行）比对 metadataJson
- `pause` / `resume` / `cancel` / `delete` / `clearCompleted`（第 446-489 行）
- `launchQueuedTasks` / `runTask`（第 509/534 行）—— 并发队列
- `downloadToTemp` + `moveIntoPlace`（第 603/939 行）—— 先写临时文件再原子移动
- `downloadNovelMerge`（第 717 行）—— 系列合并导出 TXT/MD
- `convertUgoira`（第 880 行）—— 转 GIF
- `outputPath` / `ugoiraOutputPath` / `novelOutputPath` / `uniqueOutputPath`（第 1010-1131 行）

**判断：这一层不属于 UI，应整体归 Rust 后端。** Flutter 侧只保留 UI（进度列表、暂停/继续/重试按钮、Finder 定位），大约 300 行 Dart。

`DownloadTemplate.kt`（148 行）是纯字符串处理：

- 变量 `{title} {id} {author} {author_id} {page} {ext} {series} {series_order} {chapters}`
- 单遍替换避免值里的 `{id}` 被二次扫描（第 56 行）
- 按 `/` 分段清洗，防路径穿越（`.` / `..` → `_`，第 118 行）
- **UTF-8 字节级截断**（第 127-140 行）：macOS `NAME_MAX` 限制的是 255 字节，CJK 一字 3 字节，按码点截断仍会超限导致「File name too long」。按 `codePointAt` + `charCount` 逐码点累加字节数，emoji 代理对不会被切碎

Dart 搬运注意：Dart 的 `String` 是 UTF-16 码元序列，`codeUnitAt` 给的是 UTF-16 码元。`ContentParser` 里也有同样的 `codePointAt` 用法。Dart 有 `String.characters` 包或 `runes` 可处理代理对。字节数用 `utf8.encode(s).length`。这一小段必须小心重写并加单测（这是踩过坑的逻辑）。

---

## 9. 页面层（ui/screen/，40 个文件）

| 目录 | 文件数 | 行数 |
|---|---|---|
| novel | 7 | 2016 |
| detail | 2 | 1255 |
| collection | 4 | 1241 |
| search | 4 | 1724 |
| settings | 3 | 801 |
| profile | 2 | 703 |
| dynamic | 3 | 681 |
| user | 3 | 544 |
| discover | 2 | 512 |
| comment | 2 | 538 |
| recommend | 2 | 338 |
| pixivision | 1 | 283 |
| comic | 1 | 233 |
| download | 1 | 229 |
| login | 2 | 203 |
| r18 | 1 | 93 |
| **合计** | **40** | **~10294** |

另有 `ui/component/`、novel 等共 72 个 kt 文件。

Compose 与 Flutter 都是声明式 UI，`@Composable` → `Widget build()` 的对应关系清晰：

| Compose | Flutter |
|---|---|
| `LazyColumn` / `LazyVerticalGrid` | `ListView.builder` / `GridView.builder` |
| `remember { mutableStateOf }` | `StatefulWidget` + `setState`，或 Riverpod |
| `LaunchedEffect` | `initState` + `Future` / `WidgetsBinding.instance.addPostFrameCallback` |
| Voyager `navigator.push(Screen())` | `go_router` / `Navigator.push` |
| ScreenModel（三段式 `loadInitial` → `fetchData` → `refresh`） | `ChangeNotifier` / `AsyncNotifier`（Riverpod） |
| `UiState sealed class: Loading/Success/Error` | `AsyncValue`（Riverpod 直接对应） |
| `Pager` 通用分页（`next_url` + Gson） | Dart 侧同样实现，或下沉 Rust |

页面层没有平台依赖（除了 `AsyncImage` 要换成走 Rust 的图片组件），是**纯体力活**。

---

## 10. 其他需要查证的点

以下未逐一核实，标注为待验证：

- **多窗口**：Flutter 官方多窗口支持仍在推进中，社区方案是 [`desktop_multi_window`](https://pub.dev/packages/desktop_multi_window)（LeanFlutter）。本项目目前是单窗口（全屏/详情页都在同一个 window 内切换），**暂不需要多窗口**，此项不构成阻塞。
- **日语 IME 输入**：macOS 上 Flutter `TextField` 的 marked text（未确定文字）处理是否有问题未在本次核实。项目里有搜索框、评论输入框，日文假名输入是刚需。**这一项建议在动手前先写一个最小 Flutter macOS demo 用日语输入法实测**——如果 IME 有问题，会直接影响评论和搜索功能。
- **DMG 打包与公证**：Compose 侧用 `./gradlew :app:packageDmg`（jpackage，需要手动加 `java.sql` + `jdk.unsupported` 模块）。Flutter 侧产出 `macos/Runner.xcarchive` → `.app` → DMG，公证流程走 Xcode 标准路径，需要 Apple Developer 账号。未核实具体工具链【假设】。
- **Swift Package Manager**：较新的 Flutter 版本支持用 SPM 管理 macOS 插件依赖（官方文档有 Swift Package Manager 章节）。若自写原生插件，可以走 SPM，不必用 CocoaPods。

---

## 11. 工作量汇总

| 项 | 现在行数 | 迁移后行数 | 性质 |
|---|---|---|---|
| AppMenu（系统菜单） | 181 | ~80 | 自写原生（Swift，量小，**比现在简单**） |
| TrackpadGestureBridge | 184 | 0 | **删除**，引擎原生 |
| ZoomableImage | 198 | ~150 | 重新设计（手势状态机） |
| FeedPager / IllustDetailScreen 手势部分 | ~100 | ~60 | 重新设计 |
| TrayManager | 143 | ~50 | 直接翻译 |
| WindowBackgroundBridge | 68 | ~20 | 直接翻译 |
| novel/（5 个文件） | 1238 | ~1310 | 直接翻译 + 渲染层重新设计 |
| UgoiraPlayer | 105 | ~120 | 直接翻译 |
| UgoiraGifEncoder | 99 | ~80（Rust） | 直接翻译 |
| 图片加载 | 60 | +700（Rust 400~500 + Dart 200） | **自写原生（Rust）** |
| DownloadTemplate | 148 | ~160 | 直接翻译 |
| DownloadManager | 1196 | 归 Rust 后端 | 不属 UI 层 |
| ui/screen/ 40 个页面 | ~10294 | ~11000 | 翻译（体力） |
| 其余 component / theme / 导航 | ~7000 | ~7500 | 翻译（体力） |

**主要风险排序**：

1. **图片加载链路（§6）**——Dart 无法禁用 TLS SNI（dart-lang/sdk#44122 仍 open），必须把整条图片链路下沉到 Rust 并自己重写 Coil 的缓存/去重/取消语义。这是唯一的架构级障碍，也是最大的一块自写代码。
2. **触控板手势（§2）**——结论是好消息：Flutter 引擎在 `FlutterViewController.mm` 里实现了 `magnifyWithEvent:`，通过 `PointerPanZoom` 事件把真实的 `NSEvent.magnification` 送到框架，`GestureDetector` 直接可消费。现有的 184 行 JNA block 拼装代码可以整体删除。残留工作是手势状态机重写与手感调参。
3. **系统菜单栏（§1）**——同样不是坑：Flutter macOS 模板自带可编辑的 `MainMenu.xib`，菜单项在 Xcode 里摆好、连线到 `AppDelegate` 即可，比现在的 `class_addMethod` + 主线程时序重试简单得多。第三方包（`mac_menu_bar` 等）都太早期，不建议使用。
4. **日语 IME（§10）**——未验证，建议动手前先用最小 demo 实测。
