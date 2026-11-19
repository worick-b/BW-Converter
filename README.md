# ABW Converter - WORK IN PROGRESS, I'LL BE BACK! 🔥​🗿​
~Thunderstruck

Lightweight, fast, and offline audio & video batch converter for Android powered by **FFmpeg 8.1**.

---

## Features

* **Embedded FFmpeg 8.1 Engine:** Runs completely offline with hardware-accelerated NEON optimizations and native `libfdk_aac` (`aac_he_v2`) encoding.
* **Batch or Single-File Mode:** Convert an entire folder at once or pick a single media file. Outputs are automatically organized into a `conv_abw` subfolder without overwriting originals.
* **Custom FFmpeg Presets:** Create, edit, delete, restore defaults, or **Import / Export** your custom FFmpeg command presets via JSON.
* **Special Character Safe:** Handles filenames with accents, spaces, and symbols cleanly without failing.
* **Bilingual Interface (`EN` / `PT-BR`):** Automatically detects your Android system language and includes a quick toggle button at the top.
* **Disk-Streamed Processing:** Converts large video files directly from storage without exhausting device RAM.

---

## Included Default Presets

| Preset Name | Output Format | FFmpeg Arguments |
| :--- | :--- | :--- |
| **Audio MP3 (192k)** | `.mp3` | `-vn -c:a libmp3lame -b:a 192k -ar 44100` |
| **Audio HE-AAC v2 (39k)** | `.aac` | `-vn -c:a libfdk_aac -profile:a aac_he_v2 -ac 2 -b:a 39k -ar 44100` |
| **Video MP4 H.264 (CRF 23)** | `.mp4` | `-c:v libx264 -preset fast -crf 23 -pix_fmt yuv420p -c:a libfdk_aac -b:a 128k` |

---

## Installation

1. Go to the **[Releases](../../releases)** page and download the APK matching your device architecture:
   * **`arm64-v8a`**: Recommended for modern 64-bit Android devices (Android 10–14+).
   * **`armeabi-v7a`**: For older 32-bit Android devices.
2. Install the APK and grant **"All files access"** permission when prompted so the app can read your media folders and save converted files.

---
