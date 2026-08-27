# 🎵 NovaMusic - Cyberpunk Android Music Player

NovaMusic is a lightweight, open-source Android music player designed with a premium, futuristic cyberpunk-neon aesthetic. It streams music directly from online sources with high performance, supports offline downloads, playlist management, full localization, and features a specialized driving dashboard with voice controls.

---

## ✨ Features

- **Direct High-Speed Streaming:** Powered by a custom InnerTube client resolver that emulates the `ANDROID_VR` (Oculus Quest) client to bypass standard YouTube rate/range limits, delivering direct progressive playback and fast seeks.
- **Multi-Server Fallback:** Transparently falls back to active Invidious instances or Cobalt APIs if direct connection resolver issues occur.
- **Neo Drive (Drive Mode):** A driving dashboard with giant touch targets, swipe gestures (drag left/right to skip tracks), and hands-free **Voice Search** (Google Speech Recognizer) to request songs instantly.
- **My Library & Offline Playback:** Download songs directly to your device storage to listen offline (ad-free) with complete queue support.
- **Playlist Management:** Create, customize, and edit custom playlists.
- **Genre-Aware Smart Queue:** A versioned offline artist catalog profiles the current track by genre and language, then uses popularity-weighted random sampling to blend relevant chart-style artists, related tracks, and discovery candidates. Favourite/completed tracks raise local affinity while quick skips lower it; no listening data leaves the device.
- **Futuristic Cyberpunk Theme:** Vibrant glassmorphic components, neon glow highlights (Cyan, Purple, Pink, Orange), and fluid animations designed for modern displays.
- **Multi-Language Support:** Full localization for **English** and **Turkish** locales, with system-language detection, an in-app language picker, and Android 13+ per-app language settings support.

---

## 🛠️ Tech Stack

- **UI Framework:** Jetpack Compose (Kotlin)
- **Media Engine:** Androidx Media3 & ExoPlayer (includes local cache data sources)
- **Database:** Room DB (SQLite) for offline song metadata, favorites, and playlists
- **Network Client:** OkHttp3 & Retrofit
- **Image Loading:** Coil (Compose extension)
- **Navigation:** Navigation3

---

## 🚀 Getting Started

### Prerequisites
- Android Studio Ladybug (or newer)
- Android SDK 34+
- JDK 17

### Building from Source

1. Clone the repository:
   ```bash
   git clone https://github.com/msaber07/NovaMusic.git
   cd NovaMusic
   ```

2. Build the debug APK:
   - On Windows:
     ```bash
     .\gradlew.bat assembleDebug
     ```
   - On Linux/macOS:
     ```bash
     ./gradlew assembleDebug
     ```

3. The generated APK will be located at:
   `app/build/outputs/apk/debug/app-debug.apk`

---

## 🤝 Contributing

Contributions, issues, and feature requests are welcome! Feel free to check the [issues page](https://github.com/msaber07/NovaMusic/issues).

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).
