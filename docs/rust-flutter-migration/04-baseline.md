# 现有 Compose Desktop 产物实测基线

所有数据由 lead 在本次评估期间于本机实测取得，供 `00-conclusion.md` 的成本收益对比引用。

测量环境：macOS（Apple 芯片），被测对象为已安装的 `/Applications/PixivShaft.app`。

## 产物体积

| 项目 | 实测值 |
|------|--------|
| app bundle 总体积 | **197 MB** |
| JVM runtime（捆绑的 Java 运行时） | 72 MB |
| app 目录（88 个 jar） | 117 MB |
| Resources | 7.7 MB |
| libech.dylib（Rust ECH 库，位于 Resources 内） | 7.4 MB |
| PixivShaft.icns（应用图标，位于 Resources 内） | 288 KB |
| MacOS / Info.plist / 签名 | 约 220 KB |
| DMG 体积（压缩后） | **131 MB** |

JVM runtime 内部构成：`lib/modules` 52 MB、`lib/server` 15 MB、AWT 相关 dylib 若干。

app 目录中体积最大的几个依赖：

| jar | 体积 |
|-----|------|
| material-icons-extended | 36 MB |
| sqlite-jdbc | 13 MB |
| skiko-awt-runtime-macos-arm64（Skia 图形后端） | 8.7 MB |
| foundation-desktop | 4.2 MB |
| app 自身代码 | 3.6 MB |
| material3-desktop | 3.3 MB |
| ui-desktop | 3.2 MB |
| netty-codec-native-quic | 2.2 MB |
| jna | 1.8 MB |

## 运行时内存

用 `footprint` 采样，三次冷启动，每次采样 45 秒：

| 轮次 | 稳定后常驻 | 峰值 |
|------|-----------|------|
| 第 1 次 | 270 MB | 347 MB |
| 第 2 次 | 257 MB | 350 MB |
| 第 3 次 | 259 MB | 344 MB |

**常驻区间 257–270 MB，峰值 344–350 MB。**

内存分类中，`__TEXT`（代码段）21 MB、`mapped file` 43 MB 为干净页，实际脏内存占大头。

进程出现耗时约 0.02–0.04 秒（`open` 命令返回即出现进程，此项不代表界面可用）。

## 代码规模

| 模块 | 文件数 | 行数 |
|------|--------|------|
| `:app`（UI 与业务逻辑，小计） | 86 | 19236 |
| `:app` 内的 ui（页面与组件） | — | 16558 |
| `:app` 内的 download（下载队列） | 5 | 1576 |
| `:app` 内的 platform（macOS 原生桥接） | 4 | 576 |
| `:app` 内的 Main.kt | 1 | 318 |
| `:app` 内的 di（依赖注入容器） | 1 | 102 |
| `:app` 内的 image（图片加载配置） | 1 | 70 |
| `:app` 内的 util | 1 | 15 |
| `:models`（Gson 数据模型，小计） | 28 | 2907 |
| `:models` 内的 ceui/loxia/Models.kt | 1 | 1150 |
| `:models` 内的 ceui/lisa/models（Java） | 16 | 1483 |
| `:net`（网络层，含反墙逻辑） | 24 | 1728 |
| `:store`（SQLDelight + Keychain） | 8 | 563 |
| **主代码合计** | **146** | **24434** |
| 测试代码 | 48 | 5930 |

统计口径：只计 `src/main` 与 `src/test` 下的 Kotlin 与 Java 源文件，已排除 `build/` 目录下的生成代码。全仓 `.kt` 文件（含测试）共 177 个 / 28860 行，`.java` 文件 19 个 / 1560 行。

页面数量：25 个 `*Screen.kt`、14 个 `*ScreenModel.kt`。

## 开发节奏

| 项目 | 值 |
|------|-----|
| 提交总数 | 175 |
| 首次提交 | 2026-07-04 |
| 最近提交 | 2026-09-10 |
| 主要作者 | kongjianguan（174 次），he0119（5 次） |
| 2026-07 提交数 | 105 |
| 2026-08 提交数 | 63 |
| 2026-09 提交数 | 7 |

## 工具链环境

| 工具 | 版本 |
|------|------|
| rustc / cargo | 1.96.0 |
| Java | OpenJDK 21.0.12.1 |
| Flutter | **未安装** |
| Dart | **未安装** |

Flutter 与 Dart 未安装，因此本次评估中所有 Flutter 相关结论均无法在本机实际运行验证。
