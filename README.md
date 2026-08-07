<div align="center">

# Angra Data Recovery

**Offline Android media recovery. No ads, no network, no root, no lies.**

[![Download APK](https://img.shields.io/badge/⬇%20DOWNLOAD%20APK-3DFFA8?style=for-the-badge&labelColor=07090C)](https://github.com/HARSHBHINDER/Angra-Data-Recovery/releases/latest/download/AngraDataRecovery.apk)
[![Latest Release](https://img.shields.io/github/v/release/HARSHBHINDER/Angra-Data-Recovery?style=for-the-badge&color=3DFFA8&labelColor=07090C&label=VERSION)](https://github.com/HARSHBHINDER/Angra-Data-Recovery/releases/latest)

[![Total Downloads](https://img.shields.io/github/downloads/HARSHBHINDER/Angra-Data-Recovery/total?style=for-the-badge&color=3DFFA8&labelColor=07090C&label=TOTAL%20DOWNLOADS)](https://github.com/HARSHBHINDER/Angra-Data-Recovery/releases)
[![Latest Downloads](https://img.shields.io/github/downloads/HARSHBHINDER/Angra-Data-Recovery/latest/total?style=for-the-badge&color=3DFFA8&labelColor=07090C&label=THIS%20RELEASE)](https://github.com/HARSHBHINDER/Angra-Data-Recovery/releases/latest)

[![License](https://img.shields.io/badge/LICENSE-MIT-3DFFA8?style=for-the-badge&labelColor=07090C)](LICENSE)
[![Build](https://img.shields.io/github/actions/workflow/status/HARSHBHINDER/Angra-Data-Recovery/android.yml?style=for-the-badge&color=3DFFA8&labelColor=07090C&label=BUILD)](../../actions)

</div>

---

## What it does

**RECOVERABLE** — media Android has trashed but not yet erased. Android holds deleted photos and videos in a hidden trash state for 30–60 days. The app lists them and restores them to your gallery in one tap.

**STRANDED** — files still sitting on disk but invisible to your gallery: WhatsApp `.Statuses`, Telegram cache, thumbnail leftovers, `.trashed-` remnants.

Everything runs on-device. The app declares **no `INTERNET` permission at all** — offline is enforced by the manifest, not promised in a README.

## What it cannot do — and neither can anything else

Files erased from internal storage outside the trash window are gone at the hardware level. Android 10+ encrypts storage per-file and issues TRIM on delete, which zeroes the underlying flash blocks almost immediately. No app recovers those, rooted or not. Any app claiming otherwise is selling you a progress bar.

Contacts and SMS have no undelete path on Android — deleted rows are vacuumed from their databases. Back them up instead.

## Install

1. Download the APK from the green button above.
2. Allow "install unknown apps" for your browser or file manager.
3. Open, grant media permission, tap **BEGIN SCAN**.

Requires Android 8.0+. Restore needs Android 11+ (that's when Android added the trash API).

## Build from source

No Gradle wrapper is committed — CI supplies Gradle.

```bash
gradle assembleRelease
```

Output lands in `app/build/outputs/apk/release/`. Pushing to `main` builds the APK and publishes it to a new release automatically.

## License

MIT — see [LICENSE](LICENSE).
