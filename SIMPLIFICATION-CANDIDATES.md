# Pixiv-Shaft-Desktop Simplification 候选清单

> **状态**：已删除会改变已有功能、历史数据或网络行为的候选；剩余条目仅保留当前源码零引用、局部等价替换，或已有明确行为约束的简化项。含多轮交叉验证与误报纠正。首批已提交，后续按下方批次推进。
> **硬约束**：功能等价——不伤害任何已有功能（ECH 传输、QUIC 回退、反墙图片链路均必须保留）；每批改动要求全部测试绿。
> **手段优先级**：删（死代码/死参数/死查询）> 换（已有依赖等价物）> 收敛（相似实现合并）> 去抽象（去掉预防性抽象/配置层）。
> 本文件同时作为执行计划；代码按小批次独立提交，未完成的候选不能因为出现在清单中就直接实施。

---

## 执行计划（按提交拆分）

目标是让每个提交都能单独编译、测试、回滚，并把行为边界限制在一个区域内。先完成低风险机械项，再处理需要手工回归或专门测试的收敛；不把数据库 schema、网络协议、图片链路和 UI 大重构放进同一个提交。

| 批次 | 范围 | 提交内容 | 验收门槛 | 状态 |
|---|---|---|---|---|
| P1 | search + 明确死 UI | B1.1、B1.3、F1.1-F1.4、F8（**仅删除 `isR18` import，保留 `visibleNovels`**） | `:app:compileKotlin` + `:app:test`；调用点复查 | **已完成**：`1cccdd8` |
| P2a | search + novel detail 小收敛 | B1.2、B2.4、B2.6 | `:app:compileKotlin` + `:app:test`；函数引用和请求参数复查 | **已完成**：`3a84a16` |
| P2b | detail / comment 小收敛 | C7、C8、C10、C12 | 编译 + 相关 app 测试；评论补偿路径和全屏图片手工回归 | **已完成**：`d1eb350`（C6 调查后保留，不进入实施） |
| P3 | component + profile 机械收敛 | D3、D7、D9、E2、E3、E6、E9、E13（D1 已完成 `be951ca`、F2.3-F2.4 已完成 `d5155ad`） | 编译 + `:app:test`；UI 改动只做等价替换 | **已完成**：`7cac54d` |
| P4 | download 局部简化 | A1.2、A2.2-A2.8 | 下载、动图、小说系列测试；确认取消、重试、临时文件和未知 kind 行为不变 | **已完成**：`262228c` |
| P5 | models / net / store 的低风险清理 | G1.3、G1.6、G2.9 | 先做调用图复查，再跑对应模块测试；不改变 ECH、QUIC、图片 DNS/TLS 和持久化格式 | **已完成**：`3062b4e` |
| P6 | 中风险重复收敛 | 待执行：A1.1、B2.3、C4、D2、D6、E1、E4-E5、E7-E8、E10-E11、E14、F5、G2.3；已完成：B3.1、C1、C3、C9、D5；暂缓：B2.1 | 每个主题单独提交；先补测试，再改代码；需要手工回归的项目不得合并为一个大提交 | **进行中** |

### 不进入实施

搜索分页替换、手写分页/滚动触发器的全局统一、touchpad（触控板）手势状态机合并、历史迁移删除、JSON fallback（回退）删除、ECH/QUIC 或反墙图片链路改动，以及会改变导航消费时序的通用抽象均已从候选中删除。它们要么不是行为等价，要么会增加现有功能的回归面。

### 每批通用流程

1. 先用精确符号搜索确认生产调用者、测试调用者和动态/反射风险。
2. 只修改一个批次，完成编译和该批次对应测试；发现“未使用”误判就从计划中删除，而不是强行改代码。
3. 检查 `git diff --check`，确认提交只包含该批次文件，再创建独立提交。
4. 提交后更新本计划的状态和验证结果；下一批不依赖未提交的大范围重构。

---

## 0. 交叉验证结论（对 agent 报告的修正）

| # | 结论 | 证据 |
|---|------|------|
| V1 | `selectByStatus`（DownloadQueue.sq）是死 SQL，可删 | 全仓库 .kt 零引用（仅生成代码） |
| V2 | SearchScreenModel 的 `commitInput` / `resetActiveFilter` / `suggestionsForCurrentInput` 是死函数，可删 | 全仓库零调用点 |
| V3 | NovelDetailScreenModel 的 `toggleBookmark/toggleFollow(restrict)` 参数是死的（UI 用函数引用），可硬编码 "public" | NovelDetailScreen 两处均 `screenModel::xxx` |
| V4 | **纠正误报**：IllustDetailScreenModel 的 restrict 是**活参数**（UI 传 "public"/"private"，公开/私密收藏是真实功能） | IllustDetailScreen.kt:522/558/640/649 |
| V5 | **纠正误报**：UserDetailScreenModel.toggleFollow 的 restrict 是**活参数**（UI 传值） | UserDetailScreen.kt:87 `toggleFollow(it)` |
| V6 | 7 处 `toggleNovelBookmark` 已全部收敛到共享 util，**不构成候选**（负结果） | NovelBookmarkToggle.kt + 各调用点委托 |
| V7 | 当前 checkout 的基线测试命令成功（Gradle 任务为 `UP-TO-DATE`） | `./gradlew test --no-daemon` BUILD SUCCESSFUL |
| V8 | restrict 死参数**仅 NovelDetailScreenModel 一处**（NovelSeries 根本无此参数；Illust/UserDetail 是活参数） | 三处函数签名 + 调用点核对 |
| V9 | NovelDetailScreenModel.toggleBookmark 是共享 util `toggleNovelBookmark` 的**重复实现**（7 个调用点都走 util，唯独详情页自写） | 两处代码同构比对 |

---

## A. download 区域（来源：审查 agent S1，已定稿）

### A1 收敛 · 中风险（最高价值）

| # | 位置 | 现状 | 方案 | 风险 | 测试 |
|---|------|------|------|------|------|
| A1.1 | DownloadManager.kt `enqueueIllust`:111-174 / `enqueueUgoira`:192-244 / `enqueueNovelTask`:282-309 / `enqueueNovelMerge`:417-476 | 4 份「已有任务刷新」块 + 4 份近乎相同的 `DownloadTaskRecord` 插入块，逐字重复（约文件 12%） | 收敛为 `refreshExistingTask(...)` 与 `insertTask(...)` 两个私有 helper；**`refreshExistingNovelTask` 的 DOWNLOADING 分支（:328-364）必须独立**（状态重置条件 `!=QUEUED` 与其余 `!=QUEUED&&!=DOWNLOADING` 不同） | 中 | 部分（图片 5/小说 4/动图 1 测重入队；**merge 重入队无直接测试，改动需补**） |
| A1.2 [已完成] | DownloadManager.kt `fetchNovelText`:924-945 与 `fetchSeriesPage`:813-835 | 两个函数逐行同构（runningCalls 注册 + runInterruptible + 取消转换），注释自称「同款」 | 泛型 `executeCancellableCall(taskId, call, extract)` helper，两处收成 ~5 行 | 低-中 | 有（两路径各有测试） |

### A2 收敛/删 · 低风险

| # | 位置 | 现状 | 方案 | 风险 |
|---|------|------|------|------|
| A2.1 [已完成] | DownloadManager.kt `pause`:484-501 / `cancel`:515-534 | 两函数除目标状态与是否删 .part 外完全一致 | `stopTask(id, target, deleteTemp)` helper（保留双取消竞态），`delete()` 也可复用 | 低 |
| A2.2 [已完成] | DownloadManager.kt `pageUrl`:1057-1074 | 单页与 meta_pages 缺失分支各有相同 `original ?: large ?: medium` 回退链 | 提取 `fallbackUrl(illust)` | 低 |
| A2.3 [已完成] | DownloadManager.kt `retry`:511-513 | `retry` 是 `resume` 纯别名 | 删 `retry`，DownloadScreen.kt:172 改调 `resume`（文案不变） | 低 |
| A2.4 [已完成] | store/.../DownloadQueue.sq:32-33 `selectByStatus` | 死 SQL（见 V1） | 删查询并重新生成 SQLDelight 代码 | 低 |
| A2.5 [已完成] | DownloadManager.kt `enqueueNovelTask`:286 | 锁内二次 `queue.all()` 查询（276 行已取 all，中间无 DB 写） | 改传已有的 `all` | 低 |
| A2.6 [已完成] | DownloadManager.kt `runTask`:628,644 / `taskKind`:1076-1078 / DownloadTask.kt `from`:66-67 | `taskKind` 调 2 次；kind unknown 回退在 `taskKind` 与 `from` 各写一份 | runTask 局部化 `val kind`；kind 解析收敛为单一函数 | 低 |
| A2.7 [已完成] | DownloadManager.kt `delete`:542-547 vs `deleteTempIfExists`:1046-1049 | delete 内联了相同 temp 删除逻辑 | delete 内改用 `deleteTempIfExists` | 低 |
| A2.8 [已完成] | DownloadManager.kt `runningJob`:1036 | 单行包装仅被 `cancelRunningJob` 调用 | 内联 | 低 |

### A3 明确保留（有意 fallback，不列为候选）

Range 重启循环、ATOMIC_MOVE 回退、moveOrDeleteTemp 失败删旧、novel DOWNLOADING 取消重排、完成态短路 + reDownload 跳过、双重 frames 检查、runCatching 脏数据防御、协调器 500ms 轮询兜底。

### A4 待验证（?）

- 协调器 500ms 轮询（:86-88 `withTimeoutOrNull(COORDINATOR_WAIT_MS)`）在 CONFLATED channel 语义下疑似冗余，但属「未来忘发 wake」兜底 → **建议保留**，仅加注释。

---

## B. search / novel / comic 区域（来源：审查 agent S3 + 交叉验证，已定稿）

### B1 删 · 低风险（确定性收益）

| # | 位置 | 现状 | 方案 | 风险 |
|---|------|------|------|------|
| B1.1 [已完成] | SearchScreenModel.kt:149-155 / :258-260 / :332 | `commitInput` / `resetActiveFilter` / `suggestionsForCurrentInput` 全仓库无调用点（V2） | 直接删 | 低 |
| B1.2 [已完成] | NovelDetailScreenModel.kt:58,83 | `toggleBookmark/toggleFollow` 的 restrict 参数从未被非默认值调用（V3） | 删参数硬编码 "public"（与 toggleNovelBookmark util 一致） | 低 |
| B1.3 [已完成] | SearchFilter.kt:18-22 | `queryValue()` 与 `apiValue` 完全同值，4 处调用混用两个名字 | 全部改用 `apiValue`，删 `queryValue()` | 低 |

### B2 收敛 · 中价值

| # | 位置 | 现状 | 方案 | 风险 |
|---|------|------|------|------|
| B2.1 [暂缓/不实施] | SearchScreenModel.kt:375-414 / :421-464 | `popularPreview`/`searchIllustManga` 与 `popularPreviewNovel`/`searchNovel` 两两参数列表逐字相同（17~19 个命名参数），约 80 行重复 | 函数引用收敛：`val api = if (sort==PopularPreview) appApi::popularPreview else appApi::searchIllustManga` | 低-中 |
| B2.3 [已完成] | NovelSeriesScreenModel.kt:231,271,321 | `chaptersForDownload(seriesTotal) ?: loadAllChapters()` 写两遍（含缓存写回），`downloadAllSeparate` 第三处直接用 `loadAllChapters()` 不写缓存 | 提取 `fetchAllChapters(total)` 统一三选一策略 | 低-中 |
| B2.4 [已完成] | SearchScreenModel.kt:619-657 | `setState`/`setError`/`setErrorIfNeeded` 等六个函数做同一份 tab 路由；`setError` 恒等于 `setState(tab, Error(msg))` | setError 收敛为一行，setErrorIfNeeded 复用 setState | 低 |
| B2.6 [已完成] | SearchScreen.kt:119-128 | 手写回顶 LaunchedEffect 与共享 `ScrollToTopOnEvent`（FeedScaffold.kt:81-94）逐行等价 | 替换为共享组件 | 低 |

### B3 微项 / 一致性

| # | 位置 | 现状 | 方案 | 风险 |
|---|------|------|------|------|
| B3.1 [已完成] | SearchScreenModel.kt:477-500 | 私有 `visibleIllusts`/`visibleNovels` 结构平行（R18 + AI 双开关），与 util.visibleNovels 语义不同 | 本区域内合并为带谓词的单个泛型过滤函数（**不上提 util**） | 中 |
| B3.2 [已完成] | ComicScreen.kt:100-101 / SearchScreenModel.kt:112-114,:504 vs :38 | 无意义 `remember(data)`；init 重复 `setQueryParts`；`effectiveR18Mode` 用 AppContainer.settingsStore 而类内已有 `settings` 字段 | 分别修掉 | 低 |

### B4 待验证（?）

- B4.1 init 重复 `setQueryParts`（:113 与 :191）逻辑等价，确认无时序依赖后删。

### B5 测试行为约束（改动不可触碰）

- NovelSeriesScreenModelTest：R18/visible 过滤 + 首页全 R18 自动翻页 + `latestNovel` 随开关重算。
- SearchFilterTest：`shouldLoadSearchMore` 空页触发、`fromApiValue` 回退、`serverValue` 映射、`activeCount` 计数。

---

## C. detail / comment 区域（来源：审查 agent，已定稿）

| # | 位置 | 现状 | 方案 | 风险 | 测试 |
|---|------|------|------|------|------|
| C1 [已完成] | CommentsController.kt:149-176 / 285-307 / 365-393 | `submit()` 与 `sendStamp()` 结构几乎全同（mutex、submitting 检查、post、applyPostedComment、cancelReply、错误处理），仅 draftText vs `""`+stampId 不同；API.kt:361-375 的 post 接口本就带可选 `stamp_id` | 合并为 `submitInternal(draftText, stampId)` + `postCommentRequest(...)`（null 字段 Retrofit 省略，wire 不变），submit/sendStamp 变薄壳 | 低 | 有（CommentsControllerTest） |
| C2 | IllustDetailScreen.kt:744-784 vs 831-917 | 全屏与普通模式的图片区是同一三分支（gif/多页/单图）各写一遍，约 90 行近重复，仅 zoomActive、页码指示器位置、ugora 错误处理不同 | 提取共享 `ArtworkDisplay(...)`，差异参数化 | 中 | 无（手势路径无测试，需手工回归） |
| C3 [已完成] | CommentsController.kt:340-363 vs 433-443 | `ensurePagerLoaded()` 复制了 `loadComments()` 中「拉首页 + pager.refresh + merge + hasMore」中间段 | 提取 `fetchFirstPageIntoPager()` 供两处调用 | 低 | 部分 |
| C4 | CommentsController.kt:72-77 / 459-469 / 270-274 | `replyNextUrls: Map<Long,String?>` 与 `_hasMoreReplies: Set<Long>` 是同一信息的两个并行结构，两处都要同步维护（deleteComment 已出现漏同步隐患类） | `replyNextUrls` 改 `MutableStateFlow`，hasMoreReplies 变派生值，只维护一份 | 中 | 部分 |
| C6 | IllustDetailScreenModel.kt:54 / 92 / 161-162 | `_userId` 字段是 `illustState.user.id` 的冗余拷贝 | **不实施**：已验证该字段为独立可变状态（toggleFollow 在详情加载完成前可被调用），与 state 不同步 | 低 | 无 |
| C7 [已完成] | CommentsSection.kt:377-381 / 404-407 | 打开表情面板（默认颜文字 tab）就调 `loadStamps()`，切贴纸 tab 再调一次——首次调用常为浪费 | 只在切到贴纸 tab 时调 | 低 | 无 |
| C8 [已完成] | CommentsController.kt:194/219/261 | 三处 `commentsPager.items.value...map{it.id}.toSet()` | 提取 `topLevelCommentIds()`（低价值，可选） | 低 | — |

### C 区补充（第二轮审查，互证 + 新增）

| # | 位置 | 现状 | 方案 | 风险 |
|---|------|------|------|------|
| C9 [已完成] | IllustDetailScreenModel.kt:30 / 184-204 | `relatedPager` 用完整 Pager 只用到 refresh/items（hasNext/loadMore/generation 全未用，非分页数据） | 换普通字段 `rawRelated: List<Illust>` + 现成 `visibleItems` 过滤；republishIfLoaded 同改 | 低（同 scope 单线程无竞态；R18 重过滤不变） |
| C10 [已完成] | CommentsController.kt:89-100（init）与 :325-336（retrySelfUserId） | init 的 launch+try/catch+resolveSelfUserId 与 retrySelfUserId 完全同构（init 时 _selfUserId 必为 null，守卫恒通过） | init 改为一行 `retrySelfUserId()` | 低 |
| C11 [已完成] | IllustDetailScreen.kt:839-843 / 871-875 / 902-906 | 普通模式三个分支各自写灰底 Box（0xFFE0E0E0），重复 3 次 | 灰底移入 ArtworkFrame 内层 Box，删 3 个包装 | 低（视觉等价） |
| C12 [已完成] | IllustDetailScreen.kt:696-698 | private 函数唯一调用点全参显式传入，3 个默认值从未被使用 | 删默认值 | 低 |

**C 区保留约束**：

1. **CommentFullScreenModel 用 ScreenModel 而非 remember 是必须的**（Voyager 覆盖屏幕 composition 销毁，remember 重建会重新发加载请求）——不列为候选（负结果）。
2. ensurePagerLoaded 补偿路径（首次加载失败→发表→重拉）无测试，C3 合并后建议补测或人工验证。

## D. component 区域（来源：审查 agent，已定稿）

| # | 位置 | 现状 | 方案 | 风险 | 测试 |
|---|------|------|------|------|------|
| D1 [已完成] | CommentsSection.kt:206-223 / 227-242 / 639-654 | 「加载更多」按钮在 LazyColumn 内 / 空列表+hasMore / 加载更多回复 三份完全相同的 TextButton+进度圈+Text | 提取 `LoadMoreButton(loading, text, onClick)` | 低 | 无（纯 UI） |
| D2 | CommentsSection.kt:520-658 vs 660-728 | `CommentRow` 与 `ChildCommentRow` 结构相似（hover、头像+名字+日期、正文、回复/删除），仅尺寸/操作行不同 | 先提取共享「用户头部行+正文」子 composable，操作行保留差异 | 中 | 无 |
| D3 [已完成] | NovelGrid.kt:30-38 vs WorkFeedGrid.kt:53-67 | 内联列数计算与 `calculateWorkFeedColumns` 逐行相同 | 复用该函数（重命名 `calculateResponsiveColumnCount`） | 低 | 无 |
| D4 | NovelCard.kt 必填回调空传 | BrowseHistoryScreen.kt:296-302 传 `onUserClick={}`/`onToggleBookmark={}`（历史列表无此交互，死 UI） | NovelCard 回调改可空/默认（涉及跨区域调用点） | 低 | 无 |

### D 区补充（第二轮审查，互证 + 新增）

| # | 位置 | 现状 | 方案 | 风险 |
|---|------|------|------|------|
| D5 [已完成] | RecommendScreen.kt:443-496（NovelTabContent） | 整段手写 BoxWithConstraints + 列数公式 + LazyVerticalStaggeredGrid + NovelCard，与 `NovelGrid` **逐字节同构**（spacing/contentPadding/公式/key 全一致） | NovelTabContent 改为 `NovelGrid(gridState, items){...}`（gridState/回顶/trigger 留在调用处） | 低-中 |
| D6 | RankingFeed.kt:223-375 | `RankingFeedScreenModel` 的 type 构造期固定却持双 pager+双 state，7 处 `when(type)` 分发（fetchCurrent/hasVisibleContent/setSuccess/setError/currentState/republishIfLoaded/loadMore）——约一半模型表面积是死的 | 收敛为单 pager+单 state，when 分发坍缩；UI 侧 when(type) 因卡片类型不同保留；**同步改 RankingFeedScreenModelTest 的 novelState 读法** | 中 |
| D7 [已完成] | RankingFeed.kt:113-124 | RankingFeedScaffold 的 LocalScrollToTop 分支与 `ScrollToTopOnEvent` 函数体相同，仅多 `refreshTick==null` 守卫 | `if (refreshTick==null) ScrollToTopOnEvent(gridState, onScrolledToTop=onRefresh)`，守卫提外层 | 低 |
| D9 [已完成] | UgoiraPlayer.kt:92-117 | `decodeUgoiraZip` 的 sortedFrames/frameMap 计算后从未使用，`frameMetadata` 参数只喂死代码；61 行 `currentBitmap!=null && bitmaps.isNotEmpty()` 第二条件冗余 | 删死局部变量 + 未用参数（private 函数，行为逐字节等价） | 低 |

**D 区核查结论**：CaptionText/CommonComponents/IllustCard/NovelCard/ShimmerEffect/WorkFeedGrid 无死代码；ZoomableImage 双 handler 缩放逻辑有部分重复但属手势敏感区（待验证）；RankingFeed 的 refreshTick 三层协议**判断为合理复杂度**（注释给足理由），不列候选。

## E. collection / dynamic / profile / user 区域（部分定稿，待补充）

| # | 位置 | 现状 | 方案 | 风险 | 测试 |
|---|------|------|------|------|------|
| E1 | WatchlistScreen.kt:293-433 / BookmarkedListScreen.kt:160-293 / BookmarkTagsScreen.kt:195-315 | 三个模型都是「双 Pager + 双 loadingMore + 成对 fetch/publish/hasVisible」；BookmarkedList 的 type 构造期固定导致另一套 Pager 永远死着；BookmarkTags 两个 Pager 类型相同 | 照抄区域内的正确范式 **PixivisionScreenModel**（`CategoryFeed(pager,state,isRefreshing,loadingMore)` + feeds map），成对字段折叠成 List 按 index 取用 | 中 | Watchlist/BookmarkedList 有测试；BookmarkTags 无 |
| E2 [已完成] | NovelMarkersScreen.kt:107-110 | Screen 层 `filter{runCatching{it.novel;it.novel_marker}.isSuccess}` 与模型层 `visibleMarkedNovels` 重复过滤（数据已过模型过滤，Screen 层永远空转） | 删 Screen 层 filter | 低 | 有（模型侧测试） |
| E3 [已完成] | WatchlistScreen.kt:261 | `WatchlistRowDate(value)=value.orEmpty()` 平凡 PascalCase 包装 | 调用点直接 `.orEmpty()`，删包装（保留 runCatching 注释） | 低 | 无 |
| E4 | UserDetailScreenModel.kt:27-28 / 71-97 | `illustPager`/`bookmarkPager` 只调用 refresh+items.value，全模型无 loadMore——Pager 机制是死机器 | 换普通列表直接发布，删两个 Pager | 低-中 | 无 |
| E5 | ProfileScreenModel.kt:240-266 | 4 对 `publishX`/`hasVisibleX`（8 个函数，仅 pager/state/filter 不同） | 收敛为 `publish(pager, stateFlow, filter)` + `hasVisible(...)` | 中 | 有 |
| E6 [已完成] | ProfileScreen.kt:110-141 | Loading 与 Error 分支渲染完全相同（同 avatar、都不渲染名字） | 合并为 `is Loading, is Error ->` | 低 | 无 |
| E7 | ProfileScreen.kt:212-354 | 四个 tab 内容块重复 Loading/Error/Success+items+FeedLoadMoreTrigger 结构，仅卡片类型与 key 前缀不同 | 收敛为 `ProfileTabContent(state, onRefresh, loadMoreKey, card)` | 中 | 无（UI 层） |
| E8 | ProfileScreenModel.kt:310-329 vs BrowseHistoryScreenModel.kt:266-288 | 「读 DB 历史 → Gson 反序列化 → R18 过滤」两处独立实现（loadHistory 每次 new Gson()） | 收敛为共享「payloadJson→Display」解码+过滤辅助 | 中 | 两侧都有测试 |
| E9 [已完成] | UserDetailScreenModel.kt:60-61 vs BrowseHistoryRecorder.kt:24 | `if (user.id>0L) user else user.copy(id=user.user_id)` 逐字重复 2 处 | 收敛为 `User.normalized()` 扩展 | 低 | 无 |
| E10 [已完成] | BookmarkTagsScreen.kt:274-278 + UserListScreen.kt:216-220 + CommentsController.kt:421-429 | `SelfUserIdResolver.resolve{...}` 逐字重复 3 处；ProfileScreenModel 的 `getSelfProfile()` 同时消费完整资料，保留其 ID 提取 | SelfUserIdResolver 里加 `suspend fun Client.resolveSelfUserId()`，3 个纯 ID 调用点统一使用 | 低 | 有 |

### E 区补充（第二轮审查，互证 + 新增）

| # | 位置 | 现状 | 方案 | 风险 |
|---|------|------|------|------|
| E11 | BookmarkedListScreenModel.kt:152-294 与 DynamicScreenModel.kt:27-173 | 两个模型**近乎逐行同构**（双 Pager+双 StateFlow+loadMore→loadMoreUntil+hasVisible/setSuccess/setError/currentState+updateNovelBookmark+republishIfLoaded，约 150-200 行），仅 API 与参数不同 | 抽泛型「双轨 Pager 模型」基类/辅助（参数化 fetchIllust/fetchNovel 两个挂起函数），两模型只留差异 | 中（两模型各有测试可作护栏） |
| E13 [已完成] | BookmarkedList:260-264 / Watchlist:419-427 / Profile:244-266 / Dynamic:125-129 / NovelMarkers:267-268 等 | 8+ 份 `(_state.value as? UiState.Success)?.data?.isNotEmpty() == true` | UiState 上加 `fun UiState<*>.hasVisibleContent()` 扩展 | 低 |
| E14 | ProfileScreenModel.kt:132-191 | loadBookmarks/loadNovelBookmarks/loadCreatedWorks 四份同形（置 Loading→api→refresh→publish→loadMoreUntil→Error） | 抽 `loadChannel(stateFlow, pager, apiCall, publish, hasVisible, errorMsg)`（loadMoreWith 已有先例） | 中 |

**E 区关键待验证/结论**：

- **R18/visible 过滤无绕过**（核实结论）：Watchlist novel tab 用 `visibleItems` 而非 `visibleNovels`，但 `WatchlistNovelItem` **没有 `visible` 字段**（只有 x_restrict），AGENTS.md 规则只约束 `ceui.loxia.Novel`——此处合规，不算绕过。
- NovelMarkers UI 过滤（E2）删除前提：`publishItems()` 是 state 唯一生产者（当前确认无其他写 `_state` 路径）。
- loadMore 守卫 `if (!hasNext || !loadingMore.compareAndSet(false,true)) return` **全 app 18 处**；Profile 的 `loadMoreWith` 是现成收敛样板，其余 7 处（BookmarkedList:210/BookmarkTags:254/NovelMarkers:233/Watchlist:348/Dynamic:84/NiceFriend:141/UserList:200）可复用它。
- 手写 derivedStateOf 滚动触发全 app **15 处**。

## F. 其余 app UI + 核心区域（来源：审查 agent，已定稿）

### F1 删死代码 · 低风险

| # | 位置 | 现状 | 方案 | 风险 |
|---|------|------|------|------|
| F1.1 [已完成] | Main.kt:51 / MainScreen.kt:91 | `MainNavigationTarget.HISTORY` 全仓无人赋值（grep 证实），when 分支是 `-> Unit` 空操作 | 删枚举值 + 删分支 | 低 |
| F1.2 [已完成] | TrackpadGestureBridge.kt:179-191 | `onMagnify(block, event)` 无任何调用者（grep 证实），注释自称有测试但仓库没有 | 删函数 | 低 |
| F1.3 [已完成] | AuthState.kt:5 | `LoggedIn(userId: Long?=null)` 的 userId 全仓无人读取（唯一构造点 `LoggedIn()`）——预防性字段 | 改 `data object LoggedIn` | 低 |
| F1.4 [已完成] | R18Screen.kt:97-98 | `R18ScreenModel` 只含 `val modes = R18RankingMode.entries`，纯常量透传无状态 | 删模型，Screen 直接用 entries | 低 |

### F2 收敛 · 低风险（机械替换）

| # | 位置 | 现状 | 方案 | 风险 |
|---|------|------|------|------|
| F2.3 [已完成] | ShaftTheme.kt:77-103 | dark/light colorScheme 传完全相同的 12 个命名参数，~26 行重复 | `(if(dark) darkColorScheme() else lightColorScheme()).copy(primary=…12 项…)` | 低 |
| F2.4 [已完成] | PagerTest.kt:31-56 | 自带 fakeClient() 与 testutil/FakeClient.kt:17 重复 | testutil 提供无 api 变体，PagerTest 复用 | 低 |

### F 区补充（第二轮审查，互证 + 新增）

| # | 位置 | 现状 | 方案 | 风险 |
|---|------|------|------|------|
| F5 [已完成] | image/ImageLoaderFactory.kt:22-47 + di/AppContainer.kt:79-80 | `create()` 建 client#1 给 Coil，`createImageClient()` 又建完全相同的 client#2 给 DownloadManager——双 OkHttpClient（双 Dispatcher 线程池 + 双连接池） | 先建一次 client，create() 接收已建 client（**确认 Coil fetcher 不修改传入 client 配置**） | 低-中 |
| F6 [已完成] | ui/history/BrowseHistoryScreenModel.kt:215-222 + store/BrowseHistoryStore.kt:65 | `clearCurrentTab()` 全仓库零调用（UI 只有 clearAll）；唯一实现 `deleteType` 随之变死（仅 store 测试在用） | 删 clearCurrentTab + deleteType（连带删测试用例） | 低 |
| F8 [已完成] | RecommendScreenModel.kt:13,15 | `import isR18` 无使用；`visibleNovels` 虽与类内成员同名，但带参调用依赖该 import（编译验证） | 只删 `isR18` import，保留 `visibleNovels` | 低 |

**F 区核查结论（纠正/确认）**：
- **Pager 实际使用面 16 处**（RankingFeed×2、CommentsController、Pixivision、Recommend×4、Search×3、Dynamic×2、Profile×4 等），是既定范式——**不删不推广**（先前「只 4 处」的观察有误）。
- **SettingsStore 无死配置项**（逐项 grep 全部配置键均有消费方）；只剩 G2.3 的同模板样板收敛。
- **AppMenu JNA 复杂度是被迫的**（无 AWT/Swing 替代；`Desktop.setPreferencesHandler` 会改菜单文案/位置/快捷键，属行为变化）——不列候选。
- TrayManager 每次关窗 `tray.add` 新 icon 不 remove 旧的——疑似 bug（多轮隐藏后托盘图标重复），仅记录非 simplification。

### F4 待验证（?）

- F4.1 RecommendScreenModel.loadMoreNovel / NiceFriendScreenModel.loadMore 缺 `loadMoreUntil`（其余模型都有）——更像行为不一致（潜在 bug）而非简化点，需确认是否有意为之。
- F4.2 BrowseHistoryScreenModel.loadFirst/loadMore 的「跳过全空页」while 循环 ~25 行重复，游标/generation 竞态微妙且有测试锁定，收敛价值中等风险中等，暂缓。
- F4.4 Main.kt 顶部 8 个全局 mutableStateOf 请求计数器：收敛到 SharedFlow 风险高收益不确定，**不建议动**。
- F4.5 NovelCard.kt:162-171 `toRelativeNovelTime` 的「0 天前」疑似显示 bug——属 bug 修复非 simplification，仅记录。

## G. models / net / store / 构建区域（来源：审查 agent，已定稿）

### G1 确定性删除 · 低风险（已验证当前源码零引用）

| # | 位置 | 现状 | 方案 | 风险 |
|---|------|------|------|------|
| G1.1 | models/src/main/java/ceui/lisa/models/ | **36 个死 Java bean（约 2338 行）**，存活链仅 NovelBean/UserBean/NovelDetail/IllustsBean 及其直接依赖（Starable/UserContainer/TagsBean/ImageUrlsBean/ProfileImageUrlsBean/MetaPagesBean/MetaSinglePageBean/Deduplicatable/SeriesBean.kt/ModelObject.kt）；其余（AccountEditResponse、CommentBean/CommentHolder/CommentStamp/ReplyCommentBean、Error500/500Obj/BodyBean/Response/Response2、GifResponse/UgoiraMetadataBean/FramesBean、MutedHistory/MutedUsersBean、Preset/ProfilePresetsBean、Live/UserModel/UserState/UserHolder/UserPreviewsBean、SpotlightArticlesBean、HitoResponse、IllustSearchResponse、NovelSearchResponse/NovelSeriesItem/MangaSeriesItem 等）全零引用，**仍被使用的 `ProfileBean` 不在删除范围内** | **只删除列出的 36 个死类及其死依赖，不删除存活链中的类** | 低 |
| G1.2 | net/src/main/kotlin/ceui/pixiv/net/api/API.kt | 8 个零调用方法：getIdpUrls / getInfoLatest / getInfoList / getNotificationList / getNotificationViewMore / getUserProfile / getIllustSeries / postFlagIllust | 删方法 + 对应死 model 文件（InfoResponse.kt、NotificationResponse.kt、IdpUrlsResponse.kt、UserResponse.kt 共 121 行）+ `Models.kt` 的死 `Profile`/`ProfilePublicity`/`Workspace`（不要删除仍被使用的 `ProfileBean`） | 低 |
| G1.3 [已完成] | Params.java（约 222 行 / 90 常量） | 唯一使用点是 API.kt:44 的 TYPE_PUBLIC | 只留 TYPE_PUBLIC | 低 |
| G1.5 [已完成] | store/src/main/sqldelight/ | `RemoteKey.sq` **整文件**死；`SearchHistory.sq` 的 selectRecentSearches 死；IllustHistory 3 个查询仅测试用，**表本身仍保留给历史迁移** | 只删 RemoteKey 文件和 SearchHistory 的死查询；IllustHistory 表及迁移相关查询保留 | 低 |
| G1.6 [已完成] | net 杂项 | `CloudFlareDns.kt` 死文件；WALKTHROUGH_PATH、ALIDNS_DOH_POINT、getModeOrdinal、StubTokenRefresher 死符号；CloudFlareDNSResponse 未读字段 | 删 | 低 |

### G2 收敛 / 去抽象 · 中风险

| # | 位置 | 现状 | 方案 | 风险 |
|---|------|------|------|------|
| G2.3 | SettingsStore | 12 个同模板 int 设置 | helper 收敛（约 120→40 行）；**不得改变设置 key、默认值、Flow 类型和重启生效语义** | 中 |
| G2.9 [已完成] | impl/Defaults.kt:15-30,8-13 | FileTokenStore/InMemorySettings 仅测试使用却放 main 源码集 | **只移动这两个测试替身**到 `net/src/test`；保留 `StdoutLogger`/`DefaultLanguageProvider` 等生产实现 | 低 |

### G3 重要约束与待验证

- **决策点：8 个死 API 方法**（G1.2）对应 AGENTS.md 中优先级待办「通知中心、推荐用户页面」——若计划实现，API 签名应保留（只删对应死模型会在实现时重建）；若不计划，整链删除。
- **约束（ECH）**：`PixivHosts.shouldEch == shouldQuic` 是语义别名（注释声明与 rust/ech PIXIV_HOSTS 对齐）——**保留，不合并**。
- 待验证：app 的 `runtimeOnly netty native` 是否冗余（传递性）；CI 注释与 `updateEchPrebuilt` 实际行为（覆盖 prebuilt、无 diff 校验）不符；WatchlistNovelItem 的 `field!!` NPE 隐患（属 bug 非 simplification，记录）。

---

## 跨区域约束与低风险候选

1. **R18/visible 过滤**：搜索页私有 `visibleNovels`/`visibleIllusts`（AI 过滤 + 按 filter 的 R18）与 util.visibleNovels（全局开关）语义不同，不可直接替换。
2. **Ugoira zip 解压两处**：`DownloadManager.extractUgoiraFrames` 与 `UgoiraPlayer.decodeUgoiraZip` 同构，但目标不同，合并收益有限。
3. **测试侧假 Call 重复**：`inertApi`/`fakeApi`/`novelCall`/`novelSeriesCall` 三个测试文件重复定义，可收敛到 testutil，但测试是行为约束，需谨慎。
4. **收藏图标按钮重复**：IllustDetailScreen.kt:556-574 与 NovelDetailScreen.kt:115-131 同一段 Favorite/FavoriteBorder + tint 的 IconButton，可提取 `BookmarkIconButton(isBookmarked, onClick)`。
5. **三种「骨架」并存**：RankingFeed（自带一套 R18 过滤+分页）、RecommendScreen（自带）、DynamicFeedScaffold（FeedScaffold 薄包装，合理）；暂不统一，避免把不同分页协议强行塞进一个抽象。
