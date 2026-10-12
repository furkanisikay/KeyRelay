---
name: device-testing
description: Use when a KeyRelay change has to be checked on a real Android tablet or Windows PC - installing builds, reading helper or service logs, measuring latency or idle CPU, or asking the user to observe something on a device.
---

# Testing on real devices

Unit tests cannot show most KeyRelay failures: Android hides input before apps see it, Windows treats injected input by
session and desktop, and timing depends on the network. Every claim that something works on a device needs an
observation from that device, and anything not observed is reported as "not measured".

## Tablet (Android)

- **adb over Wi-Fi:** the user turns on *Wireless debugging*; find it with `adb mdns services` and `adb connect
  <ip:port>`. Starting the KeyRelay helper turns wireless debugging off again by design, so after installing or
  restarting the app, expect the connection to drop and ask the user to turn it back on.
- **Install:** `adb install -r android/app/build/outputs/apk/debug/app-debug.apk`, then
  `adb shell am start -n dev.keyrelay/.MainActivity`.
- **Helper log:** `adb shell cat /data/local/tmp/keyrelay-helper.log` (grab, foreground, connection lines; never key
  values).
- **Input nodes:** `adb shell getevent -lp` lists devices; `adb shell getevent -lt /dev/input/eventN` shows real codes.
  Keyboards like the MX Keys Mini send F-keys or media keys from the top row depending on the Fn lock — when a media key
  "does nothing", first check which code actually arrives.
- **Test PC without Windows:** `.\android\gradlew.bat -p android :core:fakeServer -Pport=47111` prints a pairing link;
  open it on the tablet with `adb shell am start -a android.intent.action.VIEW -d '<link>' dev.keyrelay`.
- **Before touching device settings** (night mode, animation scales, stay-awake), read the current value and restore
  exactly that value afterwards.
- **Taps:** check that KeyRelay is the foreground app (`dumpsys activity activities | grep topResumedActivity`) before
  every scripted tap; a tap that lands in another app can create real data there.

## PC (Windows)

- **Install:** run `KeyRelay-Setup-x.y.z.exe /VERYSILENT /SUPPRESSMSGBOXES /NORESTART /LOG=<file>`; installing over a
  running tray needs the tray closed first.
- **Developer client:** `windows/tools/devclient.cs` (published as `devclient.exe`) talks to the local service:
  `status`, `logs`, `latency` (per-part p50/p99 of the last minute: service, pipe, desktop, sendinput, own),
  `latency-bench <n>`, `pair <link>`, `key <code>`, `sas`, `lock`.
- **Sessions:** commands started from another session (remote shell, service) run in a different session than the
  signed-in user; anything that must run on the user's desktop (screenshots, SendKeys experiments) needs a one-off
  scheduled task with `/it`, deleted afterwards.
  Pairing and revoking are refused from other sessions on purpose.

## Measuring

- **Latency:** let the user type normally for a minute in the remote-desktop app, then read `devclient latency`.
  `sendinput` includes third-party low-level keyboard hooks (TeamViewer) and is outside KeyRelay's control; judge
  KeyRelay by `own`.
- **Idle CPU (tablet):** read `/proc/<pid>/stat` utime+stime for the helper and the app twice, 10 minutes apart, with
  the screen off (`dumpsys power | grep mWakefulness` should say Dozing/Asleep). Change one thing at a time between a
  before and an after measurement, and use the same build type for both.
- **Boot:** compare `USER_UNLOCKED` in logcat with the helper's first "connected" line; both use the tablet clock.
