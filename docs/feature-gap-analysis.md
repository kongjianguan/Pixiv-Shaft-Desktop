# Desktop vs. Original Shaft Feature Gap Analysis (2026-08 Field Review)

English | [中文](feature-gap-analysis.zh.md)

> Baseline: original Shaft `c5c044f` (`classic` branch), and the Desktop workspace, including the uncommitted ComicScreen manga entry page.
> Conclusion: **Desktop's core browsing path now matches or exceeds the original**; the remaining gap is concentrated in social/management features and AI enhancements.
>
> Additional facts:
> - The uncommitted Desktop `ComicScreen.kt` and the original Shaft `ComicTopFeedFragment` implement the same feature, the comic.pixiv.net Top page with `banners` and `recent_updated_official_works` from `Client.comicApi.getComicTop()`. Desktop has the entry page; the actual reader, the original `ComicReaderV3`, still needs to be implemented. It can reuse illustration `meta_pages` and needs no new API.
> - Some original Shaft recommendation content, such as HotWorks and EventHistory, depends on the self-hosted shaft-api-v2 service. Desktop has no such service, so these items require a service or an official API replacement.
> - Original Shaft remains under active development (`c5c044f` -> `d1a5212`, 2026-08), adding `ws-chat`, `actionqueue`, `image-host`, and nana7mi account-pool search. Future comparisons should use the actual code as the baseline.
>
> Original manga reader details for direct reference during Desktop implementation:
> - Two reading modes: paged (`PagedViewport`) and scrolling Webtoon (`WebtoonViewport`)
> - Reading direction LTR/RTL, fit mode (width/screen/original), page-turn animations (slide/cover/depth/book), brightness (system/custom), and warm eye-protection color
> - Page-level bookmarks (`Room`), page-thumbnail navigation, series list with previous/next chapter jumps, reading progress and statistics, and page prefetch (`ComicPagePrefetcher`)
> - Reuse the illustration API as the data source (`IllustsBean` preview/original URLs); no new manga-specific API is required

## 1. Features matching or exceeding the original

| Area | Result |
|---|---|
| Network and login | Desktop uses Netty QUIC in pure Kotlin instead of the original Cronet, with the full direct-connect path. |
| Search | **Exceeds the original**: popularity preview without membership, bookmark thresholds, AI filtering, R18 scope, orientation, resolution, and text-length filters, with a broader filter set than original v3. |
| Comments | Matches the original: create, reply, delete, and stickers (`stamp` in the original). |
| Feed/recommend/discover | Broadly matches; Desktop has a dedicated R18 ranking entry, including AI, which the original does not. |
| Novels | Basic reader behavior matches for progress, font size, line spacing, and theme; Desktop also merges series exports to TXT/MD. |
| Downloads | Persistent queue, pause/resume/retry, byte-limited filename templates, and Finder location support match the original with better platform integration. |
| Bookmarks/follow-up | Bookmark tag browsing, novel markers, and manga/novel follow-up are implemented. |
| Browsing history | Three tabs, search filtering, multi-select deletion, and import/export match the original. |
| Platform capabilities | System menu bar, tray, shortcuts, trackpad gestures, and full-screen mode are platform-specific capabilities absent from the original. |

## 2. Features in the original but missing from Desktop

### P0 High value (daily use, manageable cost)

1. **Mute/block system**: the original has `MutedObjectsFeedFragment`, `MutedTagsFeedFragment`, `MutedUserFeedFragment`, `MuteTagSheet`, and `PixivBlockOperate`.
   - Scope: block users, tags, and works; provide a management page; filter every list after blocking.
   - Desktop status: not implemented.
   - Feasibility: Pixiv has an official block API; tag blocking needs local filtering, as in the original's combined local and service-side approach.
2. **Choose tags while bookmarking**: the original uses `SelectTagBottomSheet` to select or create tags in an MD3 bottom sheet.
   - Desktop status: bookmarks support only public/private scope, not tag selection.
   - Feasibility: the official Pixiv API supports bookmark tags, and the original `SelectTagBottomSheet` is a direct reference.
3. **Novel reader enhancements**: full-text search with regular expressions (`ReaderSearchOverlay`), in-reader bookmarks (`しおり`), annotations/notes (`NoteEditor`), a local novel library (`LocalLibraryFragment` + `TextDecoder`), and custom fonts.
   - Desktop status: only server-side novel-marker progress sync exists; in-reader search, notes, and a local library are missing.
   - Feasibility: full-text search, bookmarks, and notes are local SQLDelight features with manageable cost.
4. **Notification center**: the original has `NotificationPagerFragment` with notification and information tabs.
   - Desktop status: not implemented.
   - Feasibility: use the official Pixiv notification API and forward notifications through the macOS notification center.
5. **Multiple accounts**: the original uses `SessionManager` for MMKV account storage and switching.
   - Desktop status: one account with a single Keychain token.
   - Feasibility: store tokens under separate Keychain entries per account and swap the `AppContainer` token on account change.
6. **Long-press list actions**: the original has `IllustCardMenu` and opens an action menu on long press.
   - Desktop status: list cards have no long-press menu; only detail pages expose actions.
   - Feasibility: Compose long press plus `DropdownMenu`, with UI-only cost.

### P1 Medium value (experience improvements)

7. **Persistent `actionqueue` write queue**: the original queues bookmarks/follows, sends them serially with a minimum interval, cools and retries the whole queue on 429, and resumes after restart.
   - Desktop status: each click sends immediately, so rapid clicks can trigger 429.
   - Feasibility: a pure Kotlin + SQLDelight module, independent of platform, can follow the original design.
8. **EXIF/XMP tag writing**: the original `ExifKeywordWriter` writes work tags into JPEG XMP `dc:subject` after download, making them searchable by digiKam, Lightroom, and Finder.
   - Desktop status: not implemented; only GIF frame-delay metadata exists.
   - Feasibility: Java ImageIO can write XMP APP1; Finder and Photos can use it on macOS.
9. **Export download links**: the original `DownloadExportLinks` exports `i.pximg.net` `img-original` direct links to TXT for third-party download tools or IDM.
   - Desktop status: not implemented.
   - Feasibility: the data already exists; export it as a TXT file.
10. **More download settings**: overwrite policy (skip/rename/overwrite), page-number start, default quality (original/preview), maximum concurrency (default 1, maximum 5), novel export format (txt/epub/pdf/md), novel metadata headers, 13 filename-template variables plus conditional blocks, and merged series export to PDF/EPUB. Desktop currently has 9 variables and TXT/MD series export.
    - Desktop status: template and path exist; the other settings are missing.
    - Feasibility: concurrency and overwrite policy are low-cost download-manager parameters; EPUB can follow the original writer design.
11. **Search details**: default search ordering, filtering already-bookmarked results (`delete_star_illust`), filtering already-bookmarked rankings, and account-pool search through nana7mi sessions with 55-minute refresh.
    - Desktop status: not implemented.
    - Feasibility: most are Pixiv API parameters; account-pool search depends on an external account pool and should be treated cautiously.
12. **Bookmark/download automation**: auto-follow after bookmarking (`auto_follow_after_star`), auto-download after bookmarking (`auto_download_after_star`), and auto-bookmark after downloading (`download_auto_post_like`).
    - Desktop status: not implemented.
    - Feasibility: settings toggles plus an action chain, with low implementation cost.
13. **Watch later**: the original has illustration and novel tabs backed by `general_table WATCH_LATER`, plus a play-all action connected to slideshows.
    - Desktop status: not implemented; follow-up exists.
    - Feasibility: a local table and UI, with low implementation cost.
14. **Bulk operation center**: the original uses `BulkSelectV3Fragment` and `NovelBulkSelectV3Fragment` to select works, batch bookmark/follow/download them, and show progress.
    - Desktop status: only browsing-history multi-select deletion exists.
    - Feasibility: Compose selection mode plus existing actions, with medium cost.
15. **Comment translation**: the original long-presses a comment and shows a translation popup using Google or a configured AI engine.
    - Desktop status: not implemented.
    - Feasibility: a Google Translate web endpoint would be low cost.
16. **More discover content**: activities/featured content (`FeatureFeedFragment`), artist ranking, bookmark ranking, view/wallpaper/year/tag rankings, popular works (`HotWorks`), curator recommendations, activity history, and a sensitive-content warning gate.
    - Desktop status: Discover has rankings, popular tags, and Pixivision only.
    - Feasibility: official API endpoints can provide ranking categories; self-hosted rankings depend on shaft-api-v2 and require a service first.
17. **User-domain enhancements**: advanced artist-page search with a full tag panel, tag filtering on works, and request-plan browsing (`RequestPlanFeedFragment`).
    - Desktop status: user pages have only Illusts and Bookmarks tabs.
    - Feasibility: medium cost, with a clear improvement to profile information density.
18. **Plaza**: the original has a post feed, post creation with up to 9 work IDs, post details, and comments through `PlazaFragment`.
    - Desktop status: not implemented; it depends on a self-hosted service and is deferred.

### P2 Low priority / platform differences

19. **Reverse image search**: the original `ReverseImage.java` integrates SauceNAO, TinEye, IQDB, and Ascii2D.
20. **Slideshow playback**: the original `SlideshowActivity` provides a black full-screen view, cross-fade and Ken Burns effects, and preloading.
21. **AI suite**: RealESRGAN/CUGAN upscaling, Rembg background removal, manga OCR (`MangaOcr`/`ComicTextDetector`), the full manga translation pipeline, image translation, RIFE animation interpolation, and offline NLLB translation.
    - Desktop feasibility: CoreML or a local service could be considered on macOS; cost is high, so this is deferred.
22. **More languages**: the original supports Chinese, English, Japanese, Korean, Russian, Turkish, and Traditional Chinese.
23. **Update checks**: the original has `AppUpdateChecker` for GitHub Releases, version history, and skipped versions; Desktop could check for new DMGs through the GitHub API.
24. **Remote Aria2 downloads**: the original forwards downloads to a NAS through JSON-RPC; Aria2 is common on macOS and could be supported.
25. **Synonym dictionary**: the original `SynonymDict` merges tag synonyms, auto-selects synonyms during bookmarking, and supports built-in data plus import/export.
26. **Pinned/Prime/featured tags**: the original has `PinnedTags`, `PrimeTags` with 202 curated tags in JSON, and locally curated `FeatureFeed` content.
27. **Network diagnostics**: the original has `NetworkTestFragment`; Desktop could provide QUIC/DoH self-checks to diagnose the direct-connect path.
28. **Chat (`ws-chat`)**: depends on a self-hosted shaft-api-v2 service and WebSocket; Desktop has no service and defers it.
29. **Fanbox browsing**: the original `FanboxHomeFeed` uses the official Fanbox API with creator/submission tabs, native post details, and a WebView bridge for 403; Desktop has links only.
30. **Edit profile, work environment, and membership pages**: the original has account settings such as `FragmentEditAccount`; Desktop does not.
31. **Full backup/restore**: the original `BackupUtils` exports JSON, optionally including browsing history, and restores it as a stream; Desktop exports and imports browsing history only.
32. **Cloud services (`pixshaft`/`moon`)**: the original provides encrypted email-account backup, Moon settings sync, and online UID reporting, all dependent on a self-hosted service and deferred in Desktop. **Cloud browsing-history sync is explicitly not planned**; history remains local with local JSON import/export.
33. **Widgets, Android notifications, and SAF**: Android-specific and not applicable.
34. **Embedded WebView / Pixiv Web home (`Street`)**: platform-specific; using the system browser is appropriate on Desktop.

## 3. Recommended adoption order

1. First batch (P0): mute/block system, bookmark tag selection, novel reader enhancements, notification center, multiple accounts, and long-press menus.
2. Second batch (P1): an `actionqueue` 429-protection queue, EXIF tags, detailed download settings, watch later, comment translation, and bulk operations.
3. Third batch (P2): update checks through the GitHub API, network diagnostics, reverse image search, slideshows, and manga-reader details such as bookmarks, thumbnail navigation, and dual modes.
4. Defer: the AI suite, chat, Plaza, and popular-work/self-hosted rankings because of service dependencies or high cost; cloud browsing-history sync is explicitly out of scope, with history kept local and importable/exportable as JSON.

> Note: for the manga reader currently under development in Desktop, use the original `ComicReaderV3` as the direct reference for paged/Webtoon dual modes, page-level bookmarks, and reading progress. Reuse the illustration API; no new endpoint is needed.
