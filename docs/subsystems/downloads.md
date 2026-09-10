# Downloads

English | [中文](downloads.zh.md)

## Purpose

The downloads subsystem turns image, Ugoira, novel, and novel-series export requests into persistent background tasks with pause, resume, retry, cancellation, and progress reporting.

## Ownership and dependencies

[`DownloadManager`](../../app/src/main/kotlin/ceui/pixiv/download/DownloadManager.kt) coordinates task admission, execution, HTTP calls, and state publication. [`DownloadQueueStore`](../../store/src/main/kotlin/ceui/pixiv/store/DownloadQueueStore.kt) persists records in the SQLDelight `download_queue` table.

The manager uses the image `OkHttpClient` for artwork downloads and the typed application API for novel content and series metadata. `SettingsStore` supplies the root path and filename templates.

## Core concepts

`DownloadTaskKind` distinguishes `IMAGE`, `UGOIRA`, `NOVEL`, and `NOVEL_SERIES`. `DownloadStatus` distinguishes queued, active, paused, completed, failed, and canceled work. Each image page is an independent task; Ugoira uses metadata and produces a GIF; a novel series can produce a merged TXT or Markdown export.

The manager writes to a sibling `.part` file and atomically moves the completed file into the final output path. On startup it resets records left in `DOWNLOADING` to `QUEUED`, allowing the coordinator to retry them.

## API and extension points

Add a new download kind to `DownloadTaskKind`, the SQLDelight record mapping, task execution dispatch, output naming, and the download UI together. Keep filename rendering and path validation in `DownloadTemplate`; callers supply work metadata rather than assembling paths manually.

Use `DownloadQueueStore` for durable state updates. UI controls call the manager's enqueue, pause, resume, retry, cancel, delete, and clear-completed operations and observe its `StateFlow<List<DownloadTask>>`.

## Failure behavior

The coordinator records an error message and marks a task `FAILED` when execution fails. Cancellation and pause cancel the active coroutine and HTTP call before writing the final paused or canceled state, preventing a late network failure from overwriting the user action.

Completed output is never replaced accidentally by a normal enqueue. A deliberate re-download sets the record's re-download flag and refreshes metadata, output paths, and CDN URLs before execution.

## Verification

Run the focused download tests or the full suite with `./gradlew test`. Manual verification should cover a multi-page image, a Ugoira GIF, a novel, a merged series export, pause and resume, retry after failure, application restart, and output path collision handling.
