# 核心浏览体验补全设计

> 日期: 2026-07-07
> 状态: Draft → 待用户审核
> 前置文档: `2026-07-04-pixiv-shaft-macos-design-v2.md`（总体设计）

## 背景

Pixiv-Shaft-Desktop 已完成 Plans 1-9（QUIC 网络、存储、认证、核心 UI、小说阅读器、Ugoira、打包、设置/Profile）。登录流程跑通，图片和个人资料已修复。

与原版 Shaft 的功能对比显示，桌面端仅覆盖约 15-20% 的用户可见功能。本设计文档覆盖**核心浏览体验**这一批次，共 8 个 Plan（Plan 10-17），目标是让"看图"这个核心流程完整。

## 范围

### 本批次覆盖的 8 个功能

| Plan | 功能 | 复杂度 |
|------|------|--------|
| 10 | 下拉刷新 + Tab 回顶 | 低 |
| 11 | 页码指示器 | 低 |
| 12 | 双击缩放 + 全屏切换 | 中 |
| 13 | 作品描述（HTML caption）渲染 | 中 |
| 14 | 图片信息 + 原图链接 | 中 |
| 15 | 收藏/取消收藏 | 中高 |
| 16 | 关注/取关作者 | 中高 |
| 17 | 查看他人主页 | 高 |

### 排序逻辑

先简后繁：前 4 个纯 UI 改动快速推进，后 4 个逐步引入 API 调用。Plan 17 放最后，可复用 Plan 15/16 已建好的收藏/关注逻辑。

### 本批次不做（Deferred）

以下功能不在本批次范围内，记录在此防止遗忘，后续批次逐步补上。

#### Plan 内部暂缓的子功能

| 来源 Plan | 暂缓子功能 | 原因 |
|-----------|-----------|------|
| 15 | 按标签收藏弹窗（选择具体标签分类） | UI 复杂，需独立的标签选择对话框组件 |
| 16 | 无 | — |
| 17 | 关注列表/粉丝列表子页面 | 需要新 UserListScreen + 分页，独立 Plan |
| 17 | 漫画/小说 tab | 需要额外的 API 调用和列表渲染 |
| 17 | 用户背景图毛玻璃 | 纯视觉优化，非核心 |
| 17 | IllustCard 作者名点击跳转 | 与卡片点击冲突，需重构卡片布局 |

#### 后续批次的大功能块

| 功能块 | 说明 | 估计复杂度 |
|--------|------|-----------|
| **下载管理** | 单张/批量下载、文件名模板、aria2、存储位置、队列管理 UI、下载限制 | 极高（从 0 到 1） |
| **小说阅读器增强** | 字号/行距/主题、翻页模式、书签、章节导航、正文搜索、导出、系列导航、本地 TXT | 高 |
| **设置补全** | 常规（11 项）、界面（12 项）、个性化（15 项）、缓存管理、备份还原 | 高 |
| **发现页功能** | Pixivision、Prime Tags、Pinned Tags、最新作品、精华列、以图搜图、画廊 | 中高 |
| **动态 Tab** | 关注画师最新作品流、推荐用户、全部/公开/非公开过滤 | 中高 |
| **R18 Tab** | R18 日/周/男/女/AI 榜（需设置开关控制可见） | 中 |
| **社交功能** | 评论查看/发表、通知中心、举报 | 中 |
| **屏蔽管理** | 屏蔽标签/用户/作品列表 + 导出导入 | 中 |
| **搜索增强** | 搜索类型切换（标签/ID/小说/URL）、搜索建议、长按历史操作 | 中 |
| **多账号管理** | 多账号切换/添加 | 中 |
| **AI 功能** | 超分辨率、抠图、漫画 OCR 翻译 | 极高 |
| **幻灯片播放** | 从列表长按启动，自动轮播 | 低 |
| **社区功能** | 聊天室、广场 | 中（且原版需非 google play 渠道） |
| **备份还原** | 本地 JSON 备份 + 云端同步 | 中 |
| **版本更新检查** | GitHub Release 检查 + 下载 | 低 |
| **长按列表弹窗** | 屏蔽/下载/批量下载/评论/幻灯片 | 中 |
| **漫画专有阅读器** | 翻页模式 + 竖向滚动 + 缩略图/书签/系列 Sheet | 高 |
| **侧边抽屉导航** | 20+ 入口的侧边栏 | 中 |
| **我 Tab** | 个人功能入口网格 | 中 |
| **图片大图页** | 独立的全屏图片查看器（二级详情），含 AI 菜单、下载进度、音量键翻页 | 高 |

---

## 各 Plan 详细设计

### Plan 10：下拉刷新 + Tab 回顶

**改动文件**：
- `app/.../screen/recommend/RecommendScreenModel.kt` — 加 `refresh()`
- `app/.../screen/discover/DiscoverScreenModel.kt` — 加 `refresh()`
- `app/.../screen/search/SearchScreenModel.kt` — 加 `refresh()`
- `app/.../screen/profile/ProfileScreenModel.kt` — 加 `refresh()`
- `app/.../MainScreen.kt` — Tab 再次点击 → ScrollToTop 事件
- 各 Screen 的列表 — 用 `PullToRefreshBox` 包裹

**设计**：
- Compose Material3 1.7.3 提供 `PullToRefreshBox`，包裹各页面的 LazyColumn/Grid
- ScreenModel 重构 init 逻辑，拆成 `loadInitial()` + `refresh()` + `fetchData()`：
  - `init` → `loadInitial()`：设 `UiState.Loading`（全屏占位，无数据）→ 调 `fetchData()`
  - `refresh()`：设 `_isRefreshing = true`（保留旧数据，仅转圈）→ 调 `fetchData()` → `_isRefreshing = false`
  - `fetchData()`：共享的 API 调用 + state 更新逻辑，不碰 Loading/Refreshing 标志
  - 错误处理：首次加载失败 → `UiState.Error`；刷新失败 → 保留旧 `UiState.Success` 数据
- 每个 ScreenModel 加 `_isRefreshing: MutableStateFlow<Boolean>` + 暴露 `isRefreshing`
- Tab 点击逻辑：MainScreen 记录 `currentTab`，再次点击同一 Tab → 各 Screen 通过 `LazyListState.scrollToItem(0)` 回顶；若已在顶部 → 触发 `refresh()`
- 传递方式：用 `CompositionLocal` 或 Voyager 的 `Screen` 参数传递 `scrollToTop` 事件

**Task 分解**：
1. 各 ScreenModel 重构：抽 `fetchData()` + `loadInitial()` + `refresh()` + `isRefreshing`
2. MainScreen Tab 点击逻辑：记录上次 Tab，再次点击发事件
3. 各列表页加 `PullToRefreshBox` 包裹（绑定 `isRefreshing` + `onRefresh`）
4. ProfileScreen 加下拉刷新

---

### Plan 11：页码指示器

**改动文件**：
- `app/.../screen/detail/IllustDetailScreen.kt`

**设计**：
- 在 `IllustDetailContent` 的 image gallery item 中，多页分支（`imageUrls.size > 1`）的 `HorizontalPager` **外部、下方**加页码行
- **关键**：页码必须在 Pager content lambda **外部**，作为 Column 兄弟节点，否则会跟随 Pager 横向滚走
- 正确结构：
  ```kotlin
  item {
      Column {
          HorizontalPager(state = pagerState) { page ->
              ZoomableImage(...)  // 只有图片在 pager 内
          }
          Text("第 ${pagerState.currentPage + 1} / ${imageUrls.size} P")  // pager 外部，固定
      }
  }
  ```
- 单图和 Ugoira 分支不受影响
- 用 `collectAsState()` 收集 `pagerState.currentPage`

**Task 分解**：
1. IllustDetailContent 多页分支加页码行

### IllustDetailScreen 最终 item 布局

各 Plan 完成后，IllustDetailScreen 的 LazyColumn item 顺序：

```
1. Image gallery（HorizontalPager / ZoomableImage / UgoiraPlayer）  [已有]
2. 图片信息行（分辨率 + 页数 + 日期）                                 [Plan 14]
3. Title + Author + Stats + Follow 按钮                              [已有 + Plan 16]
4. Caption（HTML 描述）                                               [Plan 13]
5. Tags                                                               [已有]
6. Related works header                                               [已有]
7. Related works content                                              [已有]
```

TopAppBar actions（右侧从左到右）：
```
[原图链接 OpenInNew]  [收藏 ❤]                                       [Plan 14 + Plan 15]
```

全屏模式（Plan 12）时 TopAppBar 隐藏，底部半透明浮层仅显示：返回 + 页码。

---

### Plan 12：双击缩放 + 全屏切换

**改动文件**：
- `app/.../component/ZoomableImage.kt` — 手势改造
- `app/.../screen/detail/IllustDetailScreen.kt` — 全屏 state

**设计**：

**ZoomableImage 改造**：
- 加参数 `onToggleFullscreen: (() -> Unit)? = null`
- 手势处理改为：
  ```kotlin
  PointerInput {
      detectTapGestures(
          onTap = { onToggleFullscreen?.invoke() },
          onDoubleTap = {
              // 切换 1x ↔ 2x
              scale = if (scale < 1.5f) 2f else 1f
              // 用 animateFloatAsState 平滑过渡
          }
      )
      // 保留 detectTransformGestures 用于双指缩放+拖拽
  }
  ```
- 双击缩放用 `Animatable` 做平滑动画过渡
- 双击时以点击位置为缩放中心（可选，初版以中心缩放即可）

**IllustDetailScreen 全屏**：
- 加 `var isFullscreen by remember { mutableStateOf(false) }`
- `isFullscreen = true` 时：
  - `Scaffold` 的 `topBar` 传 `{ }`（隐藏 TopAppBar）
  - 图片 gallery 占满 `padding = PaddingValues(0.dp)`
  - 底部加半透明浮层：返回按钮 + 页码
- `isFullscreen = false` 时：恢复正常布局
- `ZoomableImage` 的 `onToggleFullscreen` 回调切换 `isFullscreen`

**Task 分解**：
1. ZoomableImage 加 `onToggleFullscreen` 参数 + 双击/单击手势
2. IllustDetailScreen 加 `isFullscreen` state
3. 全屏时隐藏 TopAppBar + 加半透明浮层

---

### Plan 13：作品描述渲染

**改动文件**：
- 新建 `app/.../component/CaptionText.kt`
- `app/.../screen/detail/IllustDetailScreen.kt` — 加 caption item

**设计**：

**`parseHtml(html: String): AnnotatedString`** 函数：
支持的标签：
| 标签 | 渲染效果 |
|------|---------|
| `<br>` / `<br/>` | 换行 |
| `<a href="URL">text</a>` | 蓝色 + 可点击 `LinkAnnotation` |
| `<b>` / `<strong>` | `SpanStyle(fontWeight = Bold)` |
| `<i>` / `<em>` | `SpanStyle(fontStyle = Italic)` |
| `<s>` | `SpanStyle(textDecoration = Strikethrough)` |
| `<u>` | `SpanStyle(textDecoration = Underline)` |

支持的 HTML 实体：
| 实体 | 字符 |
|------|------|
| `&amp;` | & |
| `&lt;` | < |
| `&gt;` | > |
| `&quot;` | " |
| `&#39;` | ' |
| `&nbsp;` | 空格 |

**`CaptionText` composable**：
```kotlin
@Composable
fun CaptionText(html: String?, modifier: Modifier = Modifier) {
    if (html.isNullOrBlank()) return
    val annotated = remember(html) { parseHtml(html) }
    Text(annotated, modifier = modifier, style = MaterialTheme.typography.bodySmall)
}
```

**IllustDetailScreen 集成**：
在 title+author item 后、tags 前，加（见上方布局 item 4）：
```kotlin
item { CaptionText(html = illust.caption, modifier = Modifier.padding(16.dp)) }
```

**实现策略**：
- 不引入 Jsoup 依赖
- 用正则逐步替换标签为 AnnotatedString 的 `withStyle` / `withAnnotation` 块
- 或用简单的状态机解析（遇到 `<` 切到标签模式，遇到 `>` 切回文本模式）

**Task 分解**：
1. 新建 `CaptionText.kt`：`parseHtml` + `CaptionText` composable
2. IllustDetailContent 加 caption item

---

### Plan 14：图片信息 + 原图链接

**改动文件**：
- `app/.../screen/detail/IllustDetailScreen.kt`
- 新建 `app/.../util/DesktopUtils.kt`（或 `BrowserUtils.kt`）

**设计**：

**关键发现**：`IllustDetailScreen` 已经在加载原图（`meta_single_page.original_image_url` / `meta_pages[].image_urls.original`）。所以"查看原图"不是加载更高清的图，而是提供图片信息 + 原图外链。

**图片信息行**：
在 gallery item 后、title+author item 前，加（见上方布局 item 2）：
```kotlin
item {
    Row(horizontalArrangement = spacedBy(12.dp)) {
        Text("${illust.width}×${illust.height}")
        Text("| ${illust.page_count}P")
        Text("| ${illust.create_date?.take(10) ?: ""}")
    }
}
```

**原图链接按钮**：
TopAppBar `actions` 加 `OpenInNew` 图标：
```kotlin
actions = {
    val originalUrl = illust.maxUrl()
    if (originalUrl != null) {
        IconButton(onClick = { openInBrowser(originalUrl) }) {
            Icon(Icons.Default.OpenInNew, "Open original")
        }
    }
}
```

**`openInBrowser` 工具函数**：
```kotlin
fun openInBrowser(url: String) {
    val desktop = java.awt.Desktop.getDesktop()
    if (desktop.isSupported(Desktop.Action.BROWSE)) {
        desktop.browse(URI(url))
    }
}
```

**Task 分解**：
1. 新建 `DesktopUtils.kt`：`openInBrowser` 函数
2. IllustDetailContent 加图片信息行
3. IllustDetailScreen TopAppBar actions 加原图链接按钮

---

### Plan 15：收藏/取消收藏

**改动文件**：
- `app/.../screen/detail/IllustDetailScreenModel.kt`
- `app/.../screen/detail/IllustDetailScreen.kt`

**设计**：

**ScreenModel**：
```kotlin
private val _isBookmarked = MutableStateFlow<Boolean?>(null)
val isBookmarked: StateFlow<Boolean?> = _isBookmarked

// 在 illust 加载成功后同步
fun onIllustLoaded(illust: Illust) {
    _isBookmarked.value = illust.is_bookmarked
}

fun toggleBookmark(restrict: String = "public") {
    val current = _isBookmarked.value ?: return
    val illustId = _illustId
    screenModelScope.launch {
        // 乐观更新
        _isBookmarked.value = !current
        try {
            if (current) {
                client.appApi.removeBookmark(illustId)
            } else {
                client.appApi.postBookmark(illustId, restrict)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 回滚
            _isBookmarked.value = current
        }
    }
}
```

**UI**：
TopAppBar `actions` 加心形按钮（在 Plan 14 的原图按钮旁边）：
```kotlin
val isBookmarked by screenModel.isBookmarked.collectAsState()
IconButton(
    onClick = { screenModel.toggleBookmark("public") },
    modifier = Modifier.combinedClickable(
        onClick = { screenModel.toggleBookmark("public") },
        onLongClick = { screenModel.toggleBookmark("private") }
    )
) {
    Icon(
        if (isBookmarked == true) Icons.Filled.Favorite
        else Icons.Outlined.FavoriteBorder,
        contentDescription = "Bookmark",
        tint = if (isBookmarked == true) Color.Red else LocalContentColor.current
    )
}
```

**Task 分解**：
1. IllustDetailScreenModel 加 `isBookmarked` state + `toggleBookmark` 方法
2. IllustDetailScreen TopAppBar actions 加心形按钮
3. 长按手势绑定非公开收藏

---

### Plan 16：关注/取关作者

**改动文件**：
- `app/.../screen/detail/IllustDetailScreenModel.kt`
- `app/.../screen/detail/IllustDetailScreen.kt`

**设计**：

**ScreenModel**：
```kotlin
private val _isFollowing = MutableStateFlow<Boolean?>(null)
val isFollowing: StateFlow<Boolean?> = _isFollowing

fun onIllustLoaded(illust: Illust) {
    _isFollowing.value = illust.user?.is_followed
}

fun toggleFollow(restrict: String = "public") {
    val current = _isFollowing.value ?: return
    val userId = _userId // 从已加载的 illust 中获取
    screenModelScope.launch {
        _isFollowing.value = !current
        try {
            if (current) {
                client.appApi.postUnFollow(userId)
            } else {
                client.appApi.postFollow(userId, restrict)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _isFollowing.value = current
        }
    }
}
```

**UI**：
在作者信息行（avatar + name）右侧加按钮：
```kotlin
val isFollowing by screenModel.isFollowing.collectAsState()
if (isFollowing == true) {
    Button(onClick = { screenModel.toggleFollow("public") }) {
        Text("Following")
    }
} else {
    OutlinedButton(onClick = { screenModel.toggleFollow("public") }) {
        Text("Follow")
    }
}
// 长按 = 非公开关注（combinedClickable）
```

**与 Plan 17 的衔接**：作者头像/名字点击 → `navigator.push(UserDetailScreen(user.id))`（Plan 17 实现）。关注按钮是独立交互元素。

**Task 分解**：
1. IllustDetailScreenModel 加 `isFollowing` state + `toggleFollow` 方法
2. IllustDetailContent 作者行加 Follow/Following 按钮
3. 长按手势绑定非公开关注

---

### Plan 17：查看他人主页

**改动文件**：
- 新建 `app/.../screen/user/UserDetailScreen.kt`
- 新建 `app/.../screen/user/UserDetailScreenModel.kt`
- `app/.../screen/detail/IllustDetailScreen.kt` — 作者点击接线

**设计**：

**UserDetailScreenModel**：
```kotlin
class UserDetailScreenModel(private val userId: Long) : ScreenModel {
    private val client = AppContainer.client
    private val illustPager = Pager<IllustResponse, Illust>(...)
    private val bookmarkPager = Pager<IllustResponse, Illust>(...)

    val userDetailState: StateFlow<UiState<UserDetailResponse>>
    val illustsState: StateFlow<UiState<List<Illust>>>
    val bookmarksState: StateFlow<UiState<List<Illust>>>
    val isFollowing: StateFlow<Boolean?>

    init { loadAll() }

    fun loadAll() {
        // 1. getUserDetail(userId) → userDetailState + isFollowing
        // 2. getUserCreatedIllusts(userId, "illust") → illustsState
        // 3. getUserBookmarkedIllusts(userId, "public") → bookmarksState
    }

    fun toggleFollow(restrict: String = "public") { ... }
}
```

**UserDetailScreen 布局**：
```
Scaffold(topBar = TopAppBar("用户名", ←返回)) {
    LazyColumn {
        item { 用户头部 }  // avatar + name + account + premium
        item { 统计行 }    // 投稿N 收藏M 关注K 粉丝L
        item { Follow按钮 }
        item { ProfileBean信息 } // job/region/twitter
        item { TabRow(Illusts / Bookmarks) }
        when (selectedTab) {
            0 -> items(illusts) { IllustCard(...) }
            1 -> items(bookmarks) { IllustCard(...) }
        }
    }
}
```

**导航接线**：
IllustDetailScreen 的作者行（avatar + name）加 `clickable`：
```kotlin
Row(
    modifier = Modifier.clickable {
        illust.user?.id?.let { navigator.push(UserDetailScreen(it)) }
    }
) {
    UserAvatar(...)
    Text(user.name)
}
```

**复用**：
- `IllustCard` 组件直接复用
- `Pager` 分页逻辑复用
- Follow 逻辑与 Plan 16 一致（从 ScreenModel 调 API）

**Task 分解**：
1. 新建 `UserDetailScreenModel`：加载 user detail + illusts + bookmarks + follow
2. 新建 `UserDetailScreen`：头部 + 统计 + Follow + TabRow + 列表
3. IllustDetailScreen 作者行加 `clickable` → `navigator.push(UserDetailScreen(userId))`

---

## 技术约束

- **JDK 21** via Homebrew（`JAVA_HOME=/opt/homebrew/opt/openjdk@21`）
- **Bun** 作为包管理器（不涉及 npm/yarn）
- **Compose Multiplatform 1.7.3** + Kotlin 2.1.20
- **Voyager** 导航框架
- **Coil 3** 图片加载
- **SQLDelight** 本地存储
- 所有 API 调用走 `AppContainer.client`（含 QUIC 直连 + Token 刷新）
- 图片请求走 `SingletonImageLoader`（含 Referer 头 + 无 SNI TLS + HttpDns）
- DMG 打包需包含 `java.sql` + `jdk.unsupported` 模块

## 验证方式

每个 Plan 完成后：
1. `./gradlew :app:compileKotlin` 编译通过
2. `./gradlew :app:run` 运行验证功能
3. 关键 Plan（15/16/17）验证 API 调用成功
4. 最终 `./gradlew :app:packageDmg` 打 DMG 安装验证
