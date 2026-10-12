---
name: input-pipeline
description: Use when changing how KeyRelay captures input on Android (evdev, grab rules, keyboard/mouse grouping, key repeat, uinput virtual mouse) or injects it on Windows (KeyInjector, SendInput, session agent, desktops, lock screen, media keys).
---

# Input pipeline rules

These are platform facts this project learned on real hardware. Each one cost at least one wrong fix before it was
understood; keep them in mind before changing the pipeline.

## Android side (helper)

- **Grab is all-or-nothing per node.** `EVIOCGRAB` takes every event of an input node. To keep some events on the
  tablet, grab the node and re-emit the rest through a uinput device. The mouse works this way: middle/side/extra/
  gesture buttons go to the PC, motion, left/right and wheels go to the `KeyRelay virtual mouse`.
- **Never grab your own virtual device.** Device discovery must skip the uinput mouse by name, or events loop.
- **Android disables kernel key repeat** (`EventHub` sets `EVIOCSREP {0,0}`), so a grabbed keyboard never produces
  `value=2`. The helper generates repeat itself; generating it on the PC instead typed extra characters when a release
  arrived late over the network.
- **Grab only between key presses.** Changing the grab while a key or mouse button is held leaves it stuck on one
  side; the state machine waits until nothing is held.
- **A disappearing node is not a user action.** Bluetooth devices drop their nodes when they sleep or go out of range;
  release only that device's held keys.
- **Foreground rule:** the target app counts as in front only from process-observer events; split screen and pop-up
  windows do not generate focus events, so do not assume a change there is detected.

## Windows side (agent)

- **The agent runs as SYSTEM in the user's session** and must attach to the current input desktop (`Default`,
  `Winlogon`, UAC secure desktop) before each injection. A `SendInput` result of 0 means: reopen the current desktop
  and try once more; if attaching fails, the desktop is not writable.
- **Scancodes for keys, virtual keys for media.** Keys go as `KEYEVENTF_SCANCODE` (layout-independent). Volume and
  media keys go as virtual keys (`VK_VOLUME_*`, `VK_MEDIA_*`), which also shows the Windows volume overlay.
- **Mouse buttons** map to `SendInput` mouse input: middle → middle, `BTN_SIDE`/`BTN_BACK` → XBUTTON1,
  `BTN_EXTRA` → XBUTTON2, the gesture button → Win+Tab.
- **Release everything on disconnect.** `RELEASE_ALL` must cover keys, modifiers and mouse buttons, or a dropped
  connection leaves a key held on the PC.
- **Latency:** `SendInput` time includes other programs' low-level keyboard hooks; do not try to optimise it away.

## Before you change it

Read `docs/protocol.md`, the relevant `docs/spikes` measurements, and the tests around `RelayStateMachine`,
`MouseRouting`, `KeyRepeat` and `KeyInjector`. Then verify on a device with the `device-testing` skill.
