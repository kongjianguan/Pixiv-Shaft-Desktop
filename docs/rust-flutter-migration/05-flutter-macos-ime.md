# Flutter macOS 日语输入法（IME）支持情况调研

调研对象：Flutter macOS 嵌入层对 `NSTextInputClient` 的实现，以及 unconfirmed text（marked text / 组合文字）在日语输入场景下的正确性。

判定依据全部来自 flutter/engine 源码与 flutter/flutter issue 的一手数据，查证时间 2026-09-23。

---

## 0. 结论

**日语输入在 Flutter macOS 上，自 Flutter 3.35.0（2025-08-14 发布）起可以正常工作。**

曾经存在的最严重问题（确定转换时后半句重复）已经修复。剩余未修问题都不阻塞核心功能，且都有明确规避手段。

关键前提：**必须用 ≥ 3.35.0 的版本**。低于此版本搜狗/系统日语输入法会导致文字重复，直接影响搜索框与评论框。

---

## 1. 引擎 macOS 嵌入层的 NSTextInputClient 实现（已读源码，非推断）

文件路径：`engine/src/flutter/shell/platform/darwin/macos/framework/Source/FlutterTextInputPlugin.mm`

注：flutter/engine 仓库已迁移进 monorepo，只读等在 `flutter-team-archive/engine` 里的归档副本会拿到 982 行的旧版本；现行路径是 `flutter/flutter` 仓库的 `engine/src/flutter/...`，1067 行。

### 1.1 实现完整度

| NSTextInputClient 方法 | 状态 | 说明 |
|---|---|---|
| `insertText:replacementRange:` | **完整** | 含 2025-06 的日文 IME 修复分支 |
| `setMarkedText:selectedRange:replacementRange:` | **完整** | 处理 NSString 与 NSAttributedString，范围已 clamp |
| `markedRange` | **完整** | 直接读 `_activeModel->composing_range()` |
| `selectedRange` | **完整** | 读写都支持 |
| `hasMarkedText` | **完整** | |
| `unmarkText` | **完整** | `CommitComposing` + `EndComposing` |
| `attributedSubstringForProposedRange:actualRange:` | **完整** | 有长度 clamp |
| `validAttributesForMarkedText` | **返回空数组** | 不接受富文本属性，marked text 无下划线/高亮之外的样式 |
| `firstRectForCharacterRange:actualRange:` | **部分实现** | 只返回 caret 矩形，忽略传入 range |
| `characterIndexForPoint:` | **从未实现** | 源码写着 `// TODO(cbracken): Implement.` + `return 0` |
| `doCommandBySelector:` | **完整** | |

结论：**协议主体实现完整，不属于「从来没实现」**。marked text 的显示、候选词、确定、取消都有对应代码路径。

### 1.2 候选词窗口位置

`firstRectForCharacterRange:` 是 AppKit 用来摆放候选词窗口（候选词窗口）的回调。当前实现：

```objc
- (NSRect)firstRectForCharacterRange:(NSRange)range actualRange:(NSRangePointer)actualRange {
  // This only determines position of caret instead of any arbitrary range, but it's enough
  // to properly position accent selection popup
  return !_originalViewController.viewLoaded || CGRectEqualToRect(_caretRect, CGRectNull)
             ? CGRectZero
             : [self screenRectFromFrameworkTransform:_caretRect];
}
```

它不按传入的 range 计算，只回 caret 矩形。对 CJK 输入法这实际上够用（候选词跟随光标），但严格说不符合协议语义。

### 1.3 marked text 的内部模型

共享 C++ 实现 `shell/platform/common/text_input_model.cc`，macOS/iOS/Windows/Linux 共用：

```cpp
bool TextInputModel::SetSelection(const TextRange& range) {
  if (composing_ && !range.collapsed()) {
    return false;   // 组合期间不允许设非折叠选区
  }
  ...
}
```

`AddText` 在 `composing_` 为真时会先删掉整个 composing range 再插入，这正是后文 §2.1 那个 bug 的机制来源。

---

## 2. 已修复：日语确定时的重复字（P2，历史上最严重）

### [flutter/flutter#160935](https://github.com/flutter/flutter/issues/160935) — Japanese IME input is not working properly on macOS

- 状态：**已修复**，`r: fixed`，2025-06-21 关闭
- 标签：`a: text input`、`platform-macos`、`a: internationalization`、`P2`、`found in release 3.27/3.28`
- 复现：输入 `きょうはいえにかえります`（IME 切成三段：きょうは / いえに / かえります），空格转换后回车确定
- 期望：`今日は家に帰ります`
- 实际：`今日は家に帰ります家に帰ります`（后半长短语重复）

同一 bug 的其他报告：
- [#164361](https://github.com/flutter/flutter/issues/164361) — Japanese text composing region gets duplicated，`r: duplicate`（2025-02 关）
- [#149379](https://github.com/flutter/flutter/issues/149379) — 日文确定后出现重复字，**issue 本身仍 open**，但 2025-12-29 有评论指出与 #160935 同源已被修复；issue 未关属于清理遗漏

### 修复内容

| 项 | 值 |
|---|---|
| 引擎 PR | [flutter-team-archive/engine#57286](https://github.com/flutter-team-archive/engine/pull/57286)（`fix: Problem with Japanease IME on macOS`，作者 hidea） |
| 框架 PR | [flutter/flutter#166291](https://github.com/flutter/flutter/pull/166291)，2025-06-21 merged |
| merge commit | `453d113161edf2e0bbcda0590ca721b7d30c07e5` |

补丁只有一处，加在 `insertText:replacementRange:`：

```objc
else if (_activeModel->composing() &&
         !(_activeModel->composing_range() == _activeModel->selection())) {
  // 日语 IME 确定时，string 要替换的是整个 composing_range。
  // selection 只是 composing_range 的一部分时，AddText 会先按 selection 处理，
  // 导致转换确定不正确。此处把光标强制回到 composing_range 起点。
  flutter::TextRange composing_range = _activeModel->composing_range();
  _activeModel->SetSelection(flutter::TextRange(composing_range.start()));
}
```

### 生效版本（逐个 tag 拉取源码比对，非推测）

| Flutter 版本 | 修复是否在内 |
|---|---|
| 3.32.0 | 否 |
| 3.33.0 | 否 |
| 3.34.0 | 否 |
| **3.35.0** | **是**（tag commit 日期 2025-08-14） |
| 3.38.0 / 3.44.0 | 是 |

与 merge 时间 2025-06-21 吻合：**首个含此修复的 stable 是 3.35.0**。

---

## 3. 仍然 open 的问题

### 3.1 [#190525](https://github.com/flutter/flutter/issues/190525) — 确定转换的回车同时触发快捷键（P2，open，2026-09-10 更新）

- 状态：**仍有 open bug**
- 标签：`a: text input`、`platform-macos`、`P2`、`team-text-input`、`found in release 3.44`
- 现象：日语输入时用回车确定转换，回车**同时**被框架快捷键系统收到。绑定「回车发送」的聊天/评论输入框会导致每次确定都误发送。Windows 无此问题（被 IME 吃掉的键是 `VK_PROCESSKEY`，不会进快捷键系统）。
- 根因（评论中 LongCatIsLooong 的判断）：`DefaultTextEditingShortcuts` 挂在 widget 树根部，应用层覆盖 Enter 会连同行文编辑默认快捷键一起覆盖。
- **workaround（已验证可用）**：回车处理里判断 `controller.value.composing.isValid`，为真时跳过提交。
- 另一个官方建议：用 `DefaultTextEditingShortcuts` 包住 TextField，让覆盖不影响到文本框。
- 修复 PR [#190538](https://github.com/flutter/flutter/pull/190538) 已提交（`[macOS] Offer key events to the IME before the framework while composing`），但作者自述处于 open-PR 上限而标为 draft，**尚未合入**。

对本项目的影响：**评论输入框如果有「回车发送」这类绑定，必须加 composing 判断**。

### 3.2 [#190704](https://github.com/flutter/flutter/issues/190704) — 组合期间切换输入法源会 SIGABRT 崩溃（P2，`c: crash`）

- 状态：**issue 已关闭（2026-08-27），但修复尚未进入任何 stable**
- 标签：`a: text input`、`c: crash`、`platform-macos`、`P2`、`found in release 3.41`
- 现象：使用第三方中文输入法（腾讯 WeType）时，切换输入法源的过程中 IME 通过 XPC 送 `setMarkedText:`，若引擎 composing range 与文本 buffer 不同步，`TextInputModel::UpdateComposingText` 中未做边界检查的 `std::u16string::replace` 抛出 `std::out_of_range`，主线程 `SIGABRT`，**整个进程终止**。
- 维护者 cbracken 在评论里确认防护已合入：「I've landed a fix (or at least crash prevention) on master branch.」

**这个必须特别说明**：该保护目前只在 `main`/`beta` 上。逐 tag 比对确认：

| 分支/版本 | `ClampedTo` 保护 |
|---|---|
| stable（3.47） | **无** |
| 3.41.0 / 3.44.0 / 3.47.0 | **无** |
| beta | 有 |
| main | 有 |

也就是说，当前 stable 上这个崩溃防护**还没发布**。触发条件是第三方输入法（不是 macOS 系统日语输入法），日常日语输入不触发。

### 3.3 长期未修 / 边缘场景

| Issue | 标题 | 状态 | 说明 |
|---|---|---|---|
| [#149379](https://github.com/flutter/flutter/issues/149379) | 日文确定后重复字 | open | 实质已被 #166291 修复，issue 未关（2025-12-29 评论已指出） |
| [#153065](https://github.com/flutter/flutter/issues/153065) | 日语建议面板（下箭头选词）产出异常 | open | 只在启用 `DeltaTextInputClient`（delta 模型）时报「old text from delta doesn't match」。**用普通 TextField / TextEditingController 不走这条路径，不受影响** |
| [#142493](https://github.com/flutter/flutter/issues/142493) | 关闭「即时转换」的假名输入出怪字 | open | cbracken 承认行为确实错误。非默认设置 |
| [#153895](https://github.com/flutter/flutter/issues/153895) | 长按 u 弹出重音面板后 ESC 取消产生非法字符 | open | 拉丁字母重音场景，与日语无关 |
| [#91861](https://github.com/flutter/flutter/issues/91861) | IME 退格没有忽略装饰空格 | open | 2021 起无人处理 |
| [#124966](https://github.com/flutter/flutter/issues/124966) | 输入中文时 `updateEditingValue` 不回调 | open | 2023 年起，可能已被后续 composing 修复覆盖，**未验证** |
| [#128565](https://github.com/flutter/flutter/issues/128565) | `isComposingRangeValid` 跨平台不一致 | open | framework 层 |

---

## 4. 从未实现

- **`characterIndexForPoint:`** — `FlutterTextInputPlugin.mm` 里 `return 0` 加 TODO 注释。影响鼠标点击 marked text 时的字符定位，AppKit 依赖较少，日语输入不走这条。
- **`validAttributesForMarkedText`** — 返回 `@[]`，不接受任何富文本属性。这是能力上的限制：marked text 无法携带自定义样式（如分段高亮不同转换候选）。
- **IME reconversion（再转换）** — [#150460](https://github.com/flutter/flutter/issues/150460) `Expose low level IME interactions`，跨平台都没实现。日语里「选中已确定文字再按转换键重新转换」不可用。

---

## 5. 对比：Compose Desktop / Java AWT

作为对照。**Compose Desktop 在 macOS IME 上同样有历史问题，且历史上更严重；当前（1.7+）已基本修好。**

### 5.1 架构差异

Compose Desktop 底层是 Java AWT/Swing（`ComposeWindow` 继承 `JFrame`），文本输入走 JDK 的 `sun.lwawt.macosx.CInputMethod` / `AWTInputMethod` 桥接 AppKit `NSTextInputClient`。也就是说，Compose 把 IME 处理**委托给 JDK**，自己不管 marked text；Flutter 是**自己在 embedder 里实现整套协议**。

这带来一个结构性差异：Flutter 的问题是它自己写的代码有 bug（所以能被社区定位到具体行并 patch），JDK 的问题要等 OpenJDK/JetBrains Runtime 修。

### 5.2 JetBrains issue（全部 **已修复**）

| Issue | 标题 | 状态 |
|---|---|---|
| [#3221](https://github.com/JetBrains/compose-multiplatform/issues/3221) | Compose for Desktop: TextField can't input Chinese!（候选框位置不对，选词无法输入） | 已修复，2024-12 关 |
| [#2628](https://github.com/JetBrains/compose-multiplatform/issues/2628) | 用 JBR 时 BasicTextField 完全无法输入中文（`p:critical`） | 已修复，2023-10 关 |
| [#3838](https://github.com/JetBrains/compose-multiplatform/issues/3838) | 输入法候选框偏移很大 | 已修复，YouTrack [CMP-3838](https://youtrack.jetbrains.com/issue/CMP-3838) 已 resolved |
| [#3839](https://github.com/JetBrains/compose-multiplatform/issues/3839) | 无 TextField 聚焦时输入法仍激活并弹候选框 | 已修复，CMP-3839 resolved |
| [#2758](https://github.com/JetBrains/compose-multiplatform/issues/2758) | 输入法下方向键移动光标不生效 | 已修复 |
| [#4621](https://github.com/JetBrains/compose-multiplatform/issues/4621) | macOS 韩文输入时 Cmd+A/Cmd+C 要按两次 | 已修复 |
| [#4625](https://github.com/JetBrains/compose-multiplatform/issues/4625) | 候选词界面停留时点另一个 TextField 崩溃 | 已修复 |
| [#1670](https://github.com/JetBrains/compose-multiplatform/issues/1670) | IME 候选窗口位置错误（Windows） | 已修复 |

关键修复 commit：[`a435648`](https://github.com/JetBrains/compose-multiplatform-core/commit/a4356483955c2b08a1588fe86ae925e29a0ab07b) `Fix Input methods on JBR, disable input methods when we lose focus`，以及 PR [#2118](https://github.com/JetBrains/compose-multiplatform-core/pull/2118) `Fix InputMethodSession.getTextLocation`、[#2122](https://github.com/JetBrains/compose-multiplatform-core/pull/2122)。

JetBrains 自 2024-08 把 issue 迁到 YouTrack，**当前 open 的 macOS Compose IME 阻塞性 issue：未能查到**（未验证：YouTrack 搜索接口受限，仅按已知 GitHub issue 编号逐个核对）。

### 5.3 OpenJDK 侧

| Bug ID | 标题 | 状态 |
|---|---|---|
| [JDK-8264728](https://bugs.openjdk.org/browse/JDK-8264728) | 中文 IME 候选框不跟随 JTextArea 光标，总在窗口下方 | **Open**（2021-03 至今，Linux 报告，macOS 未确认） |
| [JDK-8263490](https://bugs.openjdk.org/browse/JDK-8263490) | JPasswordField 输入法激活时崩溃 | Fixed |
| [JDK-8273055](https://bugs.openjdk.org/browse/JDK-8273055) / [8273079](https://bugs.openjdk.org/browse/JDK-8273079) / [8275501](https://bugs.openjdk.org/browse/JDK-8275501) | 同上多个崩溃 | Fixed |
| [JDK-8272806](https://bugs.openjdk.org/browse/JDK-8272806) | 切换输入法时 "Apple AWT Internal Exception" | Fixed |
| [JDK-8235248](https://bugs.openjdk.org/browse/JDK-8235248) | InputMethod 已确定文字未传给 passive component | **Open**（2020 起） |
| [JDK-8074882](https://bugs.openjdk.org/browse/JDK-8074882) | Input Method API 不支持 replacement range | **Open**（2015 起，11 年未实现） |

### 5.4 对照小结

两者都经历过一轮「IME 支持很差 → 集中修复」的过程，时间点也接近（Compose 在 2023–2024，Flutter 的核心修复在 2025-06）。

差别在于：Compose/JDK 的修复由 JetBrains 自上而下交付并且已经收尾；Flutter 的核心修复由外部贡献者（hidea）提交，Apple 系 embeddder（macOS）已修，**iOS 侧的同类 bug 还没修**（[#187636](https://github.com/flutter/flutter/issues/187636)，ATOK 输入法下同样的重复字）。

对本项目（只做 macOS）而言这个差别不影响结论。

---

## 6. 给迁移决策的具体建议

1. **版本锁定 ≥ 3.35.0**。这条是硬约束。低于 3.35.0 的 Flutter，日语输入在搜索框和评论框里会重复字。

2. **评论/搜索输入框若绑定回车提交，必须加 composing 守卫**（对应 §3.1 的 #190525）：
   ```dart
   if (controller.value.composing.isValid) return;  // IME 转换确定中
   ```
   或用 `DefaultTextEditingShortcuts` 包裹 TextField 避免覆盖默认编辑快捷键。

3. **不要用自实现的 `DeltaTextInputClient`**。标准 `TextField` + `TextEditingController` 走的是非 delta 路径，绕开 §3.3 里 #153065 那类问题。

4. 第三方输入法（搜狗/百度/WeType）比 macOS 系统日语输入法更容易触发边缘崩溃路径（§3.2）。当前 stable 尚未包含崩溃防护；如需兜底可以等含该 clamp 的版本，或评估在 macOS Runner 层加保护。**这一条对本项目的实际影响：低**，因为 §3.2 的崩溃需要「组合中切换输入法源」这个特定时序。

5. **上线前必须用 macOS 系统日语输入法（罗马字 + 假名两种）实测**，重点验证：多段长句 + 空格逐步转换 + 回车确定、中途退格、候选词面板上下选词、确定后再选词重转。这些是本调研唯一无法用源码替代验证的部分。

---

## 7. 未验证部分

- 未在真机安装 Flutter 并实际运行 TextField 做日语输入测试，所有行为结论来自源码 + issue 一手数据 + release 比对。
- #190538 的 draft PR 实际合入时间未知。
- IME reconversion（选中已确定文字重新转换）在 macOS 上的具体表现未验证；依据 #150460 判断为从未实现。
- Compose Desktop 在 YouTrack 上是否仍有未迁移登记的 macOS IME issue，未能穷举验证。
