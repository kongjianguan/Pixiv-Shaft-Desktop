# Desktop vs 原 Shaft 功能差距分析（2026-08 实地检查）

[English](feature-gap-analysis.md) | 中文

> 检查基线：原 Shaft `c5c044f`（classic 分支），Desktop 工作区（含未提交的 ComicScreen 漫画入口页）。
> 结论：**Desktop 核心浏览链路已全面对齐甚至超越原版**；差距集中在「社交/管理类功能」与「AI 增值功能」两块。
>
> 补充事实：
> - Desktop 未提交的 `ComicScreen.kt` 与原 Shaft `ComicTopFeedFragment` 是同一功能（comic.pixiv.net Top 页：banners + recent_updated_official_works，`Client.comicApi.getComicTop()`），Desktop 已对齐入口页；真正的阅读器（原版 `ComicReaderV3`，复用插画 meta_pages，无需新 API）仍未做。
> - 原 Shaft 的 HotWorks/EventHistory 等部分推荐内容依赖自建服务端 shaft-api-v2，Desktop 无服务端，这些项需自建服务或换官方 API。
> - 原 Shaft 仍在活跃开发（c5c044f → d1a5212，2026-08），新增了 ws-chat、actionqueue、image-host、nana7mi 借号搜索等；以后比较差距以实际代码为准。
>
> 原版漫画阅读器细节（Desktop 开发漫画阅读器时的直接参照）：
> - 双阅读模式：分页（PagedViewport）/ 卷轴 Webtoon（WebtoonViewport）
> - 阅读方向 LTR/RTL、适配模式（宽度/屏幕/原图）、翻页动画（滑动/封面/深度/书本）、亮度（系统/自定义）+ 护眼暖色
> - 页级书签（Room）、页码缩略图导航、系列列表 + 上下话跳转、阅读进度 + 统计、页面预取（ComicPagePrefetcher）
> - 数据源复用插画 API（IllustsBean 的 preview/original URL），不需要新的漫画专用 API

## 一、Desktop 已对齐或超越原版的功能

| 功能域 | 结论 |
|---|---|
| 网络与登录 | Desktop 用 Netty QUIC（纯 Kotlin）替代原版 Cronet，反墙链路完整 |
| 搜索 | **超越原版**：热度预览（非会员可用）、收藏数阈值、AI 屏蔽、R18 范围、横竖图、分辨率、正文长度筛选等，比原版 v3 筛选更全 |
| 评论 | 对齐：发表/回复/删除/贴纸（原版 stamp） |
| 动态/推荐/发现 | 基本对齐；R18 排行（含 AI）Desktop 有独立入口，原版无 |
| 小说 | 阅读器基础对齐（进度/字号/行距/主题）；Desktop 有系列合并导出 TXT/MD |
| 下载 | 持久化队列、暂停/继续/重试、文件名模板（字节级截断）、Finder 定位 —— 对齐且平台化更好 |
| 收藏/追更 | 收藏标签浏览、小说标记、追更（漫画/小说）已实现 |
| 浏览历史 | 3 tab + 搜索过滤 + 多选删除 + 导入导出 —— 对齐 |
| 平台能力 | 系统菜单栏/托盘/快捷键/触控板手势/全屏 —— 原版没有（平台差异） |

## 二、Desktop 缺失、原版已有的功能（按优先级）

### P0 高价值（用户日常使用、实现成本可控）

1. **屏蔽系统（mute/block）** — 原版：MutedObjectsFeedFragment / MutedTagsFeedFragment / MutedUserFeedFragment / MuteTagSheet / PixivBlockOperate
   - 屏蔽用户/标签/作品，管理页，屏蔽后各列表过滤
   - Desktop 现状：完全没有
   - 可行性：Pixiv 有官方 block API；标签屏蔽需本地过滤（原版也是本地 + 服务端结合）
2. **收藏时选择标签** — 原版：SelectTagBottomSheet（收藏时弹 MD3 bottom sheet 选/建标签）
   - Desktop 现状：收藏只有公开/私密，无标签选择
   - 可行性：Pixiv 官方 API 支持 bookmark tag；原版 SelectTagBottomSheet 直接可参照
3. **小说阅读器增强** — 原版：全文搜索（ReaderSearchOverlay，支持正则）、阅读器内书签（しおり）、批注/笔记（NoteEditor）、本地小说库（LocalLibraryFragment + TextDecoder）、自定义字体
   - Desktop 现状：只有服务端「小说标记」进度同步；无阅读器内搜索/笔记/本地库
   - 可行性：全文搜索/书签/笔记都是本地 SQLDelight 的事，成本可控
4. **通知中心** — 原版：NotificationPagerFragment（通知 + 资讯两 tab）
   - Desktop 现状：无
   - 可行性：Pixiv 官方通知 API；macOS 可用系统通知中心转发
5. **多账号管理** — 原版：SessionManager（MMKV 多账号存储 + 切换）
   - Desktop 现状：单账号（Keychain 单 token）
   - 可行性：Keychain 按账号分条目存 token 即可；切换时换 AppContainer 的 token
6. **长按列表操作菜单** — 原版：IllustCardMenu / 长按呼出操作菜单
   - Desktop 现状：列表卡片无长按菜单（只有详情页内操作）
   - 可行性：Compose 长按 + DropdownMenu，纯 UI 成本

### P1 中价值（体验增强）

7. **actionqueue 持久化写操作队列** — 原版独立模块：收藏/关注入队 → 串行最小间隔发送 → 撞 429 整队冷却重试 → 重启续发
   - Desktop 现状：点一次发一次，连点易被 429
   - 可行性：纯 Kotlin + SQLDelight，与平台无关，可整模块借鉴设计
8. **EXIF/XMP 标签写入** — 原版：ExifKeywordWriter 下载后把作品标签写进 JPEG XMP dc:subject（digiKam/Lightroom/访达可搜）
   - Desktop 现状：无（只有 GIF 帧延迟元数据）
   - 可行性：Java 有 imageio 可写 XMP APP1；macOS 上访达/Photos 都能用
9. **导出下载直链** — 原版：DownloadExportLinks 导出 i.pximg.net img-original 直链 txt（第三方下载器/IDM 重抓）
   - Desktop 现状：无
   - 可行性：数据都有，导出个 txt 即可
10. **下载设置细化** — 原版：覆盖策略（跳过/重命名/覆盖）、页码起始、默认清晰度（原图/预览）、最大并发数（默认 1、上限 5）、小说默认导出格式（txt/epub/pdf/md）、小说信息头（作者/标题等）、文件名模板 13 变量 + 条件块（Desktop 目前 9 变量）、系列合并导出 PDF/EPUB（Desktop 只有 TXT/MD）
    - Desktop 现状：有模板 + 路径，其余无
    - 可行性：并发数/覆盖策略是下载管理器参数，低成本；EPUB 导出可参照原版自研 writer
11. **搜索体验细节** — 原版：搜索默认排序设置、搜索结果过滤已收藏（delete_star_illust）、排行过滤已收藏、借号搜索（nana7mi 会话，55 分钟自动刷新）
    - Desktop 现状：无
    - 可行性：Pixiv API 参数级；借号搜索依赖外部账号池，慎用
12. **收藏/下载联动** — 原版：收藏后自动关注（auto_follow_after_star）、收藏后自动下载（auto_download_after_star）、下载后自动收藏（download_auto_post_like）
    - Desktop 现状：无
    - 可行性：设置开关 + 动作串联，低成本
13. **稍后再看（watchlater）** — 原版：插画 + 小说两 tab（共读 general_table WATCH_LATER）+ 「播放全部」接入幻灯片
    - Desktop 现状：无（有追更）
    - 可行性：本地表 + UI，低成本
14. **批量操作中心** — 原版：BulkSelectV3Fragment / NovelBulkSelectV3Fragment（勾选多作品 → 批量收藏/关注/下载入队 + 进度弹窗）
    - Desktop 现状：只有浏览历史多选删除
    - 可行性：Compose 选择模式 + 复用现有动作，中等成本
15. **评论翻译** — 原版：长按评论 → 翻译成界面语言弹窗（Google / 自配 AI 引擎）
    - Desktop 现状：无
    - 可行性：调 Google 翻译网页接口即可，低成本
16. **更多发现内容** — 原版：活动/精选（FeatureFeedFragment）、画师排行（ArtistRank）、收藏排行（BookmarkRank）、浏览量榜/壁纸榜/年代榜/标签榜、热门作品（HotWorks）、站长推荐、操作记录（行为历史）、敏感内容警示门
    - Desktop 现状：发现页只有排行 + 热门标签 + Pixivision
    - 可行性：官方 API 能拿到排行类；自建榜依赖 shaft-api-v2，需先有服务端
17. **用户域增强** — 原版：画师主页高级搜索（全量标签面板）、作品按标签筛选、约稿方案浏览（RequestPlanFeedFragment）
    - Desktop 现状：用户主页只有 Illusts/Bookmarks 两 tab
    - 可行性：中等成本，用户主页信息密度提升明显
18. **广场（Plaza）** — 原版：PlazaFragment 帖子流 + 发帖（可附至多 9 个作品 id）+ 帖子详情 + 评论
    - Desktop 现状：无；依赖自建服务端，暂缓

### P2 低优先级 / 平台差异

19. **反向搜图** — 原版：ReverseImage.java（SauceNAO/TinEye/IQDB/Ascii2D）
20. **幻灯片播放** — 原版：SlideshowActivity（黑底全屏、交叉淡化 + Ken Burns、预载）
21. **AI 全家桶** — 原版：RealESRGAN/CUGAN 放大、Rembg 抠图、漫画 OCR（MangaOcr/ComicTextDetector）、漫画翻译全套（气泡检测→OCR→翻译→擦拭→渲染）、图片翻译、RIFE 动图插帧、NLLB 离线翻译
    - Desktop 可行性：macOS 上可考虑用 CoreML 或调本地服务；成本高，可暂缓
22. **多语言** — 原版支持中/英/日/韩/俄/土/繁
23. **更新检查** — 原版 AppUpdateChecker（GitHub Releases + 版本历史 + 跳过版本）；Desktop 可借鉴（GitHub API 检查新 DMG）
24. **Aria2 远程下载** — 原版 JSON-RPC 转发下载到 NAS；macOS 上 aria2 常见，可借鉴
25. **同义词词典** — 原版 SynonymDict（标签同义词归并、收藏时自动勾选同义词、内置词典、导入导出）
26. **置顶标签 / Prime 标签 / 精华列** — 原版 PinnedTags / PrimeTags（内置 202 个精选标签 JSON）/ FeatureFeed（本地策展）
27. **网络诊断** — 原版 NetworkTestFragment；Desktop 可借鉴（QUIC/DoH 状态自检，对反墙链路排查很有用）
28. **聊天（ws-chat）** — 依赖自建 shaft-api-v2 服务端 + WebSocket，Desktop 暂无服务端，暂缓
29. **Fanbox 浏览** — 原版 FanboxHomeFeed 走 fanbox 官方 API（创作者/投稿双 tab + 原生帖子详情 + WebView 桥绕 403）；Desktop 只有链接
30. **编辑个人资料 / 作业环境 / 会员页** — 原版账号设置（FragmentEditAccount 等）；Desktop 无
31. **全量数据备份/恢复** — 原版 BackupUtils 导出 JSON（可选含浏览历史）+ 流式恢复；Desktop 只有浏览历史导入导出
32. **云服务（pixshaft/moon）** — 原版：邮箱账号加密备份、Moon 云设置同步、UID 在线上报 —— 全部依赖自建服务端，Desktop 暂缓；**云端历史同步：明确不做（已决定）**，浏览历史仅保留本地记录 + 本地 JSON 导入导出
33. **小组件 / Android 通知 / SAF** — Android 专属，不适用
34. **WebView 内嵌 / Pixiv Web 首页（Street）** — 平台差异，Desktop 用系统浏览器合理

## 三、借鉴路线建议

1. 第一批（P0）：屏蔽系统 → 收藏标签选择 → 小说阅读器增强 → 通知中心 → 多账号 → 长按菜单
2. 第二批（P1）：actionqueue 防 429 队列 → EXIF 标签 → 下载设置细化 → 稍后再看 → 评论翻译 → 批量操作
3. 第三批（P2）：更新检查（GitHub API）、网络诊断、反向搜图、幻灯片、漫画阅读器细节（书签/缩略图导航/双模式）
4. 暂缓：AI 全家桶、聊天、广场、热门作品/自建榜单（依赖自建服务端或成本高）；云历史同步明确不做（浏览历史保持本地 + JSON 导入导出）

> 注：漫画阅读器（Desktop 正在开发）建议直接参照原版 ComicReaderV3 的 Paged/Webtoon 双模式 + 页级书签 + 阅读进度设计；数据源复用插画 API 即可，不需要新接口。
