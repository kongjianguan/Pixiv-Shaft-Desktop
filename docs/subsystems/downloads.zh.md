# 下载

[English](downloads.md) | 中文

## 职责

下载子系统把插画、Ugoira、小说和小说系列导出请求转换为持久化后台任务，并提供暂停、继续、重试、取消和进度报告。

## 所有权与依赖

[`DownloadManager`](../../app/src/main/kotlin/ceui/pixiv/download/DownloadManager.kt) 负责任务接收、执行、HTTP 调用和状态发布。[`DownloadQueueStore`](../../store/src/main/kotlin/ceui/pixiv/store/DownloadQueueStore.kt) 把记录持久化到 SQLDelight 的 `download_queue` 表。

管理器使用图片 `OkHttpClient` 下载作品，使用类型化应用 API 获取小说内容和系列元数据。`SettingsStore` 提供根路径和文件名模板。

## 核心概念

`DownloadTaskKind` 区分 `IMAGE`、`UGOIRA`、`NOVEL` 和 `NOVEL_SERIES`。`DownloadStatus` 区分排队、执行中、暂停、完成、失败和取消的任务。每个插画页面都是独立任务；Ugoira 使用元数据生成 GIF；小说系列可以生成合并的 TXT 或 Markdown 导出文件。

管理器先写入同级 `.part` 文件，再将完成文件原子移动到最终输出路径。启动时，它会把处于 `DOWNLOADING` 状态的记录重置为 `QUEUED`，让协调器可以重试这些任务。

## API 与扩展点

新增下载类型时，需要同时更新 `DownloadTaskKind`、SQLDelight 记录映射、任务执行分发、输出命名和下载 UI。文件名渲染和路径验证必须保留在 `DownloadTemplate` 中；调用方提供工作元数据，不要手工拼接路径。

使用 `DownloadQueueStore` 更新持久化状态。UI 控件调用管理器的 enqueue、pause、resume、retry、cancel、delete 和 clear-completed 操作，并观察它的 `StateFlow<List<DownloadTask>>`。

## 失败行为

执行失败时，协调器记录错误消息，并将任务标记为 `FAILED`。取消和暂停会先取消活动协程与 HTTP 调用，再写入最终暂停或取消状态，防止延迟到达的网络错误覆盖用户操作。

普通 enqueue 不会意外替换已完成的输出。明确的重新下载会设置记录的 re-download 标志，并在执行前刷新元数据、输出路径和 CDN URL。

## 验证

使用 `./gradlew test` 运行定向下载测试或完整测试套件。手动验证应覆盖多页插画、Ugoira GIF、小说、合并系列导出、暂停与继续、失败后重试、应用重启，以及输出路径冲突处理。
