# Launch kit — start here

Everything needed to put Wild Odds Gym Tracker on Google Play, in one folder.

| File | What it is |
|---|---|
| [`CHECKLIST.md`](CHECKLIST.md) | **The steps only you can do, in order.** Start here. |
| [`STORE_LISTING.md`](STORE_LISTING.md) | Copy-paste text for the Play listing: name, short + full description, release notes, category, tags, URLs. |
| [`PLAY_CONSOLE_ANSWERS.md`](PLAY_CONSOLE_ANSWERS.md) | Exact answers for Play Console forms: Data safety, content rating, target audience, app access, Health Connect. |
| [`PURPOSE_AND_FEATURES.md`](PURPOSE_AND_FEATURES.md) | What the app is for, the full feature list, and what it deliberately doesn't do. |
| `graphics/icon-512.png` | Play Store icon (512×512, matches the installed app icon). |
| `graphics/feature-graphic-1024x500.png` | Play Store feature graphic. |
| `screenshots/` | Phone screenshots (1080×2400) taken from the signed release build. |
| `build/` | The signed bundle to upload (`.aab`) and an installable APK. Not committed to git — rebuild with `./gradlew :app:bundleOfflineRelease`. |

## The app in one line

A free, open-source gym tracker with no internet access, no account, no ads, no tracking and no
pop-ups — that turns a program in any format (even a screenshot) into one you can train from.

## Upload

`build/app-offline-release.aab` → Play Console → Testing → Closed testing (see the checklist).
Signed with the upload key `wildodds-upload-2026.jks` (repo root, gitignored — back it up first).
