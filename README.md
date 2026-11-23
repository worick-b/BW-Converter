# BetterWay Converter
~Thunderstruck

Lightweight, fast, and offline audio & video converter for Android powered by **FFmpeg** & **MKVToolNix**.

---

## Features

- **Embedded FFmpeg 8.1:** With NEON optimizations & `libfdk_aac` (`aac_he_v2`) encoding.
- **Embedded MKVMerge 57.0.0:** Remux a video to mkv without reencoding.
- **UI Powered by:** `ShawarmaUI, A UI Sexy Model`.
- **Batch or Single-File Mode:** Convert an entire folder at once or pick a single media file. Outputs are automatically organized into a `conv_abw` subfolder without overwriting originals.
- **Custom FFmpeg Presets:** Create, edit, delete, restore defaults, or **Import / Export** your custom FFmpeg command presets via JSON.
- **Special Character Safe:** Handles filenames with accents, spaces, and symbols cleanly without failing.
- **Bilingual Interface (`EN` / `PT-BR`):** Automatically detects your Android system language and includes a quick toggle button at the top.
- **Disk-Streamed Processing:** Converts large video files directly from storage without exhausting device RAM.
- **It's:** `A personal project, don't expect a dev master blaster (12" Version)`.

---

## Included Default Presets

| Preset Name | Output Format | FFmpeg Arguments |
| :--- | :--- | :--- |
| **Audio MP3 (192k)** | `.mp3` | `-vn -c:a libmp3lame -b:a 192k -ar 44100` |
| **Audio HE-AAC v2 (39k)** | `.aac` | `-vn -c:a libfdk_aac -profile:a aac_he_v2 -ac 2 -b:a 39k -ar 44100` |
| **Video MP4 H.264 (CRF 23)** | `.mp4` | `-c:v libx264 -preset fast -crf 23 -pix_fmt yuv420p -c:a libfdk_aac -b:a 128k` |

---

## Installation
- Go to the **[Releases](../../releases)** page and download the APK
- Install the APK and grant **"All files access"** permission when prompted so the app can read your media folders and save converted files.

---
