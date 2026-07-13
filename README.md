<div align="center">

# PixivShaft Desktop

### macOS port of Pixiv-Shaft

[![CI](https://github.com/kongjianguan/Pixiv-Shaft-Desktop/actions/workflows/ci.yml/badge.svg)](https://github.com/kongjianguan/Pixiv-Shaft-Desktop/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](./LICENSE)

A desktop port of [CeuiLiSA/Pixiv-Shaft](https://github.com/CeuiLiSA/Pixiv-Shaft), built with Kotlin + Compose Multiplatform.
It's now running properly on MacOS 27.
</div>

> [!NOTE]
> This is an unofficial third-party client for Pixiv. All illustrations, manga, and novel
> works are copyrighted by their respective creators or Pixiv. This project is open-source
> for learning and communication purposes only.

## Features

| Module | Status |
|--------|--------|
| Recommend / Discover / Search | ✅ |
| Illust detail (gallery, tags, related) | ✅ |
| Ugoira animation | ✅ |
| User profile + bookmarks | ✅ |
| Settings (network, DNS, image host) | ✅ |
| Mac gesture:Pinch-to-zoom + pan | ✅ |

## Network

Built-in QUIC acceleration, no additional proxy required:

- **API/OAuth**: Netty 4.2 QUIC over HTTPS
- **Images**: Custom DNS resolution + TLS
- **Image hosts**: Pixiv / pixiv.cat / pixiv.re / pixiv.nl / custom

## Build

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

## Tech Stack

| Layer | Choice |
|-------|--------|
| Language | Kotlin 2.1 |
| UI | Compose Multiplatform (Desktop) |
| Navigation | Voyager |
| Network | Retrofit + OkHttp + Netty 4.2 QUIC |
| Images | Coil 3 + OkHttp |
| Storage | SQLDelight + Keychain + java.util.prefs |
| Auth | OAuth PKCE (in-house) |
| Serialization | Gson |

## Credits

Based on [CeuiLiSA/Pixiv-Shaft](https://github.com/CeuiLiSA/Pixiv-Shaft), an excellent third-party Pixiv client for Android.
