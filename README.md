# ObsidianTasksPebbleBridge

Sync your [Obsidian](https://obsidian.md) tasks to a Pebble smartwatch — with timeline pins, smart reminders, and voice note capture.

> 🤖 Vibecoded with [Claude](https://claude.ai) (Anthropic).

---

## What it does

- Reads open tasks (`- [ ]`) from your Obsidian vault and displays them on the watch
- Tasks with due dates appear as **timeline pins** on the Pebble timeline
- From the watch you can:
  - ✅ **Mark a task as done** — writes `- [x]` back to the Obsidian file
  - ⏰ **Set a reminder** — 1 h / tonight / tomorrow morning / next week / pick a weekday
  - 🎙️ **Dictate a new note** — saved as a task in a configurable `.md` file
- Reminders fire as **Android notifications** (mirrored to the watch by the Pebble app)
- Tag-based grouping rules, configurable sync interval, task preview in the companion app

---

## Requirements

| Component | Details |
|---|---|
| Pebble watch | Any model (aplite, basalt, chalk, diorite, emery) |
| Pebble app | [Core Devices app](https://play.google.com/store/apps/details?id=coredevices.coreapp) or [Rebble](https://rebble.io) |
| Android | 8.0+ (API 26+) |
| Obsidian | Any version; vault stored locally or synced to phone storage |

---

## Installation

### 1 — Watch app
Install from the **[Rebble store](https://apps.rebble.io/en_US/application/6a2eb7a169dd300009bf84e4)**, or sideload the `.pbw` via the button in the Android companion app.

### 2 — Android companion app
Download the latest APK from **[Releases](https://github.com/bquelhas/ObsidianTasksPebbleBridge/releases)** and install it on your Android phone.

### 3 — First-run setup
1. Open the companion app → **Config** tab
2. Pick your Obsidian vault folder (grants read/write access via Android's Storage Access Framework)
3. Optionally configure tag-grouping rules and sync interval
4. Tap **Sync** — your tasks will appear on the watch within seconds

---

## Project structure

```
ObsidianTasksPebbleBridge/   ← Android companion app (Kotlin)
watchapp/                    ← Pebble watch app (C + PebbleKit JS)
```

### Android app highlights
| File | Role |
|---|---|
| `BackgroundReceiver.kt` | Handles all Pebble messages (FETCH / DONE / REMIND / NOTE / PIN_ACT) |
| `ReminderStore.kt` | Persists scheduled reminders so the UI can list them |
| `ReminderReceiver.kt` | Fires when an alarm triggers; posts the Android notification |
| `PebbleBridgeService.kt` | Foreground service keeping the runtime broadcast receiver alive (Android 8+ restriction) |
| `MainActivity.kt` | Two-tab UI: Config (vault, rules, preview) + Sync (reminders, log, settings) |

### Watch app highlights
| File | Role |
|---|---|
| `src/c/teste_obsidian.c` | Main C watchapp — task list, action menu, reminder submenu, timeline relay |
| `src/pkjs/index.js` | PebbleKit JS — communicates with Android, manages Rebble timeline pins |
| `src/pkjs/config.js` | Clay config page (colour picker, voice note toggle, links) |

---

## How reminders work

```
Watch button → "Remind in X"
      ↓
BackgroundReceiver (Android) schedules exact AlarmManager alarm
      ↓
Alarm fires → ReminderReceiver posts Android notification
      ↓
Pebble app mirrors notification to watch
```

Delays are computed **on the watch** using local time (`localtime` / `mktime`), so "tonight" always means 20:00 in your timezone, "tomorrow morning" means 09:00, etc.

---

## Timeline pins

Tasks with a due date (📅 `YYYY-MM-DD`, `@due(…)`, or `due::…`) are pushed as timeline pins to the Rebble timeline. From a pin you can mark the task done or set a reminder directly.

---

## Building from source

### Android
```bash
cd ObsidianTasksPebbleBridge
./gradlew assembleDebug
# APK → app/build/outputs/apk/debug/app-debug.apk
```

### Watch app
```bash
cd watchapp
pebble build
# Bundle → build/teste_obsidian.pbw
```
Requires the [Pebble SDK](https://developer.rebble.io/developer.pebble.com/sdk/index.html).

---

## License

MIT — see [LICENSE](LICENSE).
