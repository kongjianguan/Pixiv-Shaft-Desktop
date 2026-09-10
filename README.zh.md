<div align="center">

# PixivShaft Desktop

[English](README.md) | 中文

### Pixiv-Shaft 的 macOS 移植版

[![CI](https://github.com/kongjianguan/Pixiv-Shaft-Desktop/actions/workflows/ci.yml/badge.svg)](https://github.com/kongjianguan/Pixiv-Shaft-Desktop/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](./LICENSE)

使用 Kotlin + Compose Multiplatform 构建的 [CeuiLiSA/Pixiv-Shaft](https://github.com/CeuiLiSA/Pixiv-Shaft) 桌面移植版。
目前已在 MacOS 27 上正常运行。
</div>

> [!NOTE]
> 这是 Pixiv 的非官方第三方客户端。所有插画、漫画和小说作品的版权归各自创作者或 Pixiv 所有。
> 本项目仅用于学习和交流。

## 功能

| 模块 | 状态 |
|--------|--------|
| 推荐 / 发现 / 搜索 | ✅ |
| 插画详情（画廊、标签、相关作品） | ✅ |
| Ugoira 动画 | ✅ |
| 用户资料 + 收藏 | ✅ |
| 设置（网络、DNS、图片源） | ✅ |
| Mac 手势：捏合缩放 + 平移 | ✅ |

## 网络

内置 QUIC 加速，无需额外代理：

- **API/OAuth**：基于 HTTPS 的 Netty 4.2 QUIC
- **图片**：自定义 DNS 解析 + TLS
- **图片源**：Pixiv / pixiv.cat / pixiv.re / pixiv.nl / 自定义

## 构建

```bash
# JDK 21 required
brew install openjdk@21
export JAVA_HOME=/opt/homebrew/opt/openjdk@21

# Run
./gradlew :app:run

# Package DMG
./gradlew :app:packageDmg

# Output
ls app/build/compose/binaries/main/dmg/PixivShaft-1.0.0.dmg
```

## 文档

从[架构地图](docs/architecture.zh.md)开始，了解模块边界和运行时路径。贡献者应先阅读[开发指南](docs/development.zh.md)和[构建与打包手册](docs/cookbook/build-and-package.zh.md)。当前子系统契约索引位于 [docs/subsystems](docs/subsystems/README.zh.md)；历史实现计划保存在[归档目录](docs/archived/README.zh.md)中。

## 技术栈

| 层 | 选型 |
|-------|--------|
| 语言 | Kotlin 2.1 |
| UI | Compose Multiplatform（Desktop） |
| 导航 | Voyager |
| 网络 | Retrofit + OkHttp + Netty 4.2 QUIC |
| 图片 | Coil 3 + OkHttp |
| 存储 | SQLDelight + Keychain + java.util.prefs |
| 认证 | OAuth PKCE（自实现） |
| 序列化 | Gson |

## 致谢

本项目基于 [CeuiLiSA/Pixiv-Shaft](https://github.com/CeuiLiSA/Pixiv-Shaft)。这是一个优秀的 Android 第三方 Pixiv 客户端。
