# 🐕 ShibaGram Desktop

<p align="center">
  <img src="src/main/resources/icon.png" alt="ShibaGram Logo" width="128" height="128">
</p>

<p align="center">
  <strong>A modern Telegram media client for Desktop</strong><br>
  Built with Kotlin, Compose Multiplatform & Material Design 3
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Kotlin-1.9.22-blue?logo=kotlin" alt="Kotlin">
  <img src="https://img.shields.io/badge/Compose-1.6.0-green?logo=jetpack-compose" alt="Compose">
  <img src="https://img.shields.io/badge/TDLib-Native-orange" alt="TDLib">
  <img src="https://img.shields.io/badge/Platform-Windows-lightgrey?logo=windows" alt="Windows">
</p>

---

## ✨ Features

| Feature | Description |
|---------|-------------|
| 🔐 **Telegram Auth** | Login via phone number with 2FA support or QR code scan |
| 📺 **Channel Browser** | Browse all your Telegram channels and groups with thumbnails |
| 🎬 **Video Playback** | Smooth VLC-powered video player with full controls |
| 📋 **Playlists** | Navigate through all videos in a channel (up to 500) |
| ⏯️ **Continue Watching** | Automatically resume videos where you left off |
| 🔖 **Saved Videos** | Bookmark your favorite videos for quick access |
| 🔍 **Search** | Find channels quickly with real-time search |
| 🌓 **Dark/Light Theme** | Supports system theme or manual toggle |
| 🎨 **Material 3 UI** | Modern design following latest guidelines |
| 📦 **Portable** | Run without installation - single folder distribution |

### Channel video list
- **Sort** (remembered): newest/oldest, name A→Z / Z→A with natural order (`Ep 2` before `Ep 10`), duration, size
- **Filter**: All · Unwatched · In progress · Watched · Downloaded
- **Progress on every card**: progress bar, ✓ for watched (dimmed), `NEW` for posts from the last 3 days
- **Continue** resumes the first in-progress (or next unwatched) video; **Play all** plays the list in the shown order
- The player's next/previous and auto-play follow the order shown on screen
- **Right-click a video**: save/unsave, mark as watched/unwatched, show the downloaded file in its folder
- **Jump buttons** (bottom-right of the list): go straight to the first or the last video
- **Grid / List / Compact** views (Compact is a dense numbered list, good for long series)
- Videos without a Telegram thumbnail get a colored placeholder per series with the file format

### Player keyboard shortcuts
| Key | Action |
|-----|--------|
| `Space` / `K` | Play / pause |
| `←` / `→` | Back / forward 5 s (`Shift`: 30 s) |
| `J` / `L` | Back / forward 10 s |
| `↑` / `↓` | Volume |
| `M` | Mute |
| `F` / `Esc` | Fullscreen / leave fullscreen |
| `N` / `P` | Next / previous video |
| `0`–`9` | Jump to 0 %–90 % |

Volume and playback speed are remembered between sessions.

### Streaming and external mpv
- Videos that aren't fully downloaded are streamed from a local HTTP server while Telegram downloads them.
  Seeks move the download to the new position; MKV files (index at the end) open without waiting for the whole file.
- **External mpv** (Settings → Player): plays in your own mpv with your `mpv.conf`, shaders and scripts.
  The clicked video and the rest of the list go to mpv as a playlist (use mpv's next/previous keys); progress is
  saved and each video resumes where you left it. Right-click a video → *Open in external mpv* works even when the
  built-in player is the default.
- **Preferred languages** (built-in player): audio/subtitle language codes, e.g. `jpn,ja` and `pt-BR,pt,por,en`.
- `gradlew streamingSmokeTest -Pmpv="C:\path\mpv.com"` checks streaming end to end with your session
  (close the app first): it plays an MKV headless from the start and after seeks, and an external-mpv playlist.

---

## 📸 Screenshots

*Coming soon*

---

## 🖥️ System Requirements

| Requirement | Minimum |
|-------------|---------|
| **OS** | Windows 10/11 (64-bit) |
| **JDK** | Java 17 or later |
| **VLC** | VLC Media Player 3.x (64-bit) |
| **RAM** | 4 GB recommended |
| **Disk** | ~500 MB for app + cache |

---

## 🚀 Quick Start

### 1. Prerequisites

1. **Install JDK 17 or 21**
   - Download from [Adoptium](https://adoptium.net/)
   - The Gradle wrapper (8.5) can't run on newer JDKs (22+). If your default `java` is newer, point
     `JAVA_HOME` at JDK 17/21 before running `gradlew`.

2. **Video player**
   - **mpv (default):** `libs/libmpv-2.dll` is bundled, nothing to install.
   - **VLC (optional):** install [VLC 64-bit](https://www.videolan.org/vlc/) and select it in Settings.

3. **Get Telegram API Credentials**
   - Go to [my.telegram.org](https://my.telegram.org)
   - Login and create a new application
   - Note your `api_id` and `api_hash`

4. **TDLib native library** (bundled, prebuilt; nothing to install)
   - Windows: `libs/tdjson.dll` · Linux: `libs/linux-x64/libtdjson.so`, `libs/linux-arm64/libtdjson.so`
     (from [prebuilt-tdlib](https://github.com/eilvelia/tdl/tree/main/packages/prebuilt-tdlib), self-contained).
   - The app keeps TDLib's official Java API (`org.drinkless.tdlib.TdApi`). On desktop, `Client` is a
     small Kotlin class that talks to `tdjson` and converts TdApi objects to/from JSON
     (`src/main/kotlin/org/drinkless/tdlib/`). On Android the same `TdApi.java` works with the official
     JNI `Client.java` + `libtdjni.so` from [TDLibAndroidPrebuilt](https://github.com/FaiBah/TDLibAndroidPrebuilt).

   **Updating TDLib** (keep both sides on the same TDLib commit):
   1. Pick a TDLib version available in both prebuilt-tdlib (npm `@prebuilt-tdlib/win32-x64`, `linux-x64-glibc`,
      `linux-arm64-glibc`) and TDLibAndroidPrebuilt; check that `tdlib.commit` in the npm package equals
      `GIT_COMMIT_HASH` in that release's `TdApi.java`.
   2. Replace the libraries in `libs/` and `src/main/java/org/drinkless/tdlib/TdApi.java`.
   3. In `TdApi.java`, remove the `static { System.loadLibrary("tdjni") }` block and replace both
      `public native String toString();` with `return TdJsonCodec.encode(this);` (marked "ShibaGram desktop").
   4. Run `gradlew test` (JSON codec tests) and fix any API changes the compiler reports.

### 2. Configuration

Rename `.env.example` to `.env` and edit its content.

### 3. Build & Run

```bash
# Run the application in development mode
./gradlew run

# Verbose logging (TDLib, mpv, streaming): set SHIBAGRAM_DEBUG=1 or pass -Dshibagram.debug=true

# Build portable executable (recommended)
./gradlew packageExe

# Build MSI installer
./gradlew packageMsi
```

---

## 📁 Project Structure

```
ShibaGramTDLIB/
├── 📄 build.gradle.kts          # Gradle build configuration
├── 📄 settings.gradle.kts       # Gradle settings
├── 📂 libs/                     # Native libraries (tdjni.dll, etc.)
├── 📂 src/main/
│   ├── 📂 kotlin/com/shirou/shibagram/
│   │   ├── 📄 Main.kt           # Application entry point
│   │   ├── 📂 data/
│   │   │   ├── 📂 local/        # SQLite database (Exposed ORM)
│   │   │   ├── 📂 preferences/  # User preferences
│   │   │   ├── 📂 remote/       # Telegram client service
│   │   │   └── 📂 repository/   # Data repositories
│   │   ├── 📂 domain/
│   │   │   └── 📂 model/        # Domain models
│   │   ├── 📂 streaming/        # Video streaming server
│   │   ├── 📂 player/           # Player abstraction + mpv (libmpv via JNA)
│   │   ├── 📂 tdlib/            # TDLib (tdjson) loader
│   │   ├── 📂 util/             # Logging, native library lookup
│   │   ├── 📂 ui/
│   │   │   ├── 📂 components/   # Reusable UI components
│   │   │   ├── 📂 screens/      # App screens
│   │   │   └── 📂 theme/        # Material Design 3 theme
│   │   └── 📂 vlc/              # VLC player integration
│   └── 📂 resources/
│       ├── 🖼️ icon.ico          # App icon (Windows)
│       └── 🖼️ icon.png          # App icon (Runtime)
└── 📂 build/
    └── 📂 compose/portable-exe/ # Portable distribution
```

---

## 🛠️ Technologies

| Technology | Purpose |
|------------|---------|
| [Kotlin](https://kotlinlang.org/) | Primary language |
| [Compose Multiplatform](https://www.jetbrains.com/lp/compose-multiplatform/) | Declarative UI framework |
| [Material Design 3](https://m3.material.io/) | Modern UI design system |
| [TDLib](https://github.com/tdlib/td) | Official Telegram Database Library |
| [libmpv](https://mpv.io/) | Default video player (native window) |
| [VLCJ](https://github.com/caprica/vlcj) | Optional VLC player backend |
| [Exposed](https://github.com/JetBrains/Exposed) + SQLite | Watch history and saved videos |
| [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) | Local streaming server |

---

## 🏗️ Architecture

The app follows **Clean Architecture** principles:

```
┌─────────────────────────────────────────────────────────┐
│                    UI Layer                              │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────────┐    │
│  │   Screens   │ │ Components  │ │     Theme       │    │
│  └─────────────┘ └─────────────┘ └─────────────────┘    │
├─────────────────────────────────────────────────────────┤
│                  Domain Layer                            │
│  ┌─────────────────────────────────────────────────┐    │
│  │                   Models                         │    │
│  └─────────────────────────────────────────────────┘    │
├─────────────────────────────────────────────────────────┤
│                   Data Layer                             │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────────┐    │
│  │ Repositories│ │   Remote    │ │     Local       │    │
│  │             │ │  (TDLib)    │ │   (SQLite)      │    │
│  └─────────────┘ └─────────────┘ └─────────────────┘    │
└─────────────────────────────────────────────────────────┘
```

---

## 📋 Screens

| Screen | Description |
|--------|-------------|
| **Login** | Phone number entry with country code, 2FA support, QR code |
| **Home** | Quick access to Continue Watching and Saved Videos |
| **Channels** | Grid view of all subscribed channels with search |
| **Video Player** | Full-featured player with playlist navigation |
| **Settings** | Theme toggle, logout, app info |

---

## 🔧 Build Commands

| Command | Description |
|---------|-------------|
| `./gradlew run` | Run in development mode |
| `./gradlew packageExe` | Create portable EXE distribution |
| `./gradlew packageMsi` | Create MSI installer |
| `./gradlew createDistributable` | Create app folder distribution |
| `./gradlew clean` | Clean build artifacts |

### Output Locations

- **Portable EXE**: `build/compose/portable-exe/ShibaGram/ShibaGram.exe`
- **MSI Installer**: `build/compose/binaries/main/msi/ShibaGram-1.0.0.msi`
- **App Folder**: `build/compose/binaries/main/app/ShibaGram/`

---

## 🐛 Troubleshooting

### VLC not detected
- Make sure VLC 64-bit is installed
- Add VLC to your system PATH, or set `VLC_PLUGIN_PATH` environment variable

### TDLib not loading
- Ensure `tdjson.dll` (Windows) or `libtdjson.so` (Linux, in `libs/linux-x64/` or next to the launcher) is present
- Windows needs the Microsoft Visual C++ runtime; the packaged app already ships it with its Java runtime

### Authentication issues
- Verify your API_ID and API_HASH are correct
- Check your internet connection
- Delete the `%USERPROFILE%\.ShibaGram\session` folder and try again

### Video playback issues
- Update VLC to the latest version
- Try restarting the app
- Check if the video format is supported by VLC

---

## 📄 License

This project is for educational purposes. Please respect Telegram's Terms of Service.

---

## 🙏 Acknowledgments

- [TDLib](https://github.com/tdlib/td) - Telegram Database Library
- [JetBrains](https://www.jetbrains.com/) - Kotlin & Compose Multiplatform
- [VideoLAN](https://www.videolan.org/) - VLC Media Player

---

<p align="center">
  Made with ❤️ by Shirou
</p>

