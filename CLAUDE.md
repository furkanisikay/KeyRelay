# KeyRelay — guide for AI-assisted development

KeyRelay forwards a physical keyboard (and the extra mouse buttons) from an Android tablet to a Windows PC so that
Alt+Tab, Win, Ctrl+Alt+Del and the lock-screen PIN work while the PC is remote-controlled from the tablet. Read
[README.md](README.md) for the user view and [docs/protocol.md](docs/protocol.md) for the wire format before changing
behaviour that crosses the two sides.

## Map

| Path | What lives there |
|---|---|
| `android/core` | Pure Kotlin, JVM-tested: wire frames, TLS dialer, relay state machine (grab rules, key repeat, toggle), keyboard/mouse grouping, pairing URI, settings logic. Put logic here whenever it does not need Android APIs. |
| `android/app/.../helper` | The helper process (`app_process`, shell uid, started over wireless debugging): reads evdev, EVIOCGRAB, uinput virtual mouse via JNI (`app/src/main/cpp`). |
| `android/app/.../service`, `ui`, `launcher`, `settings` | Foreground service, Compose UI, startup over libadb, DataStore settings. |
| `windows/src/KeyRelay.Protocol` | Frames and key maps shared by service and tray. |
| `windows/src/KeyRelay.Service` | SYSTEM service: TLS listener, pairing, device registry, control pipe; `--agent` mode injects input in the user session. |
| `windows/src/KeyRelay.Tray` | WinUI 3 tray app (unpackaged, self-contained). |
| `protocol/test-vectors/frames.json` | Byte-exact vectors both sides test against. |
| `installer/` | Inno Setup script and `build.ps1` (layout, tray smoke test, setup, zip). |
| `docs/spikes` | Device measurements; the source of truth for what has been observed on real hardware. |
| `docs/superpowers` | Design spec and implementation plans from the original build. |

## Build and test

```powershell
# Windows: run from the windows directory, global.json selects the test runner mode
cd windows; dotnet test KeyRelay.slnx
# Installer (needs Inno Setup 6): publishes, runs the tray --smoke check, builds setup + zip
.\installer\build.ps1
# Android (JDK 21)
.\android\gradlew.bat -p android test assembleDebug
```

Run the tests of every side you touched before calling a change done, and show the result. A change to frames or key
maps touches both sides: update `docs/protocol.md`, `protocol/test-vectors/frames.json` and the tests in both
languages together (see the `protocol-change` skill).

## Working style

These come from how this project has gone wrong before; each has a reason.

- **Read before you claim.** Open the file before saying what it does, and check an API, constant or enum value in its
  source or SDK header before using it. Several bugs here came from a plausible but wrong assumption (for example
  `DEVELOPMENT_SETTINGS_ENABLED` always reads 0 for third-party apps).
- **Keep changes minimal.** Make the change that was asked for or is clearly needed. Don't add configuration, helpers,
  abstractions or defensive checks for situations that cannot happen; validate at the boundaries (network frames,
  pairing input, settings files). Prefer extending an existing type over a new file.
- **Solve the general case, not the test.** Tests verify behaviour; don't hard-code values to make them pass. If a test
  looks wrong, say so instead of working around it.
- **Fix it where it belongs.** If the right fix is in another component (the agent vs. the helper, Android vs.
  Windows), make it there or say why not; don't add a local workaround that hides the real defect.
- **Evidence over inference.** A claim that something "works on the device" needs a device observation. When something
  was not measured, write "not measured". Behaviour observed only with a hand-made input proves the mechanism exists,
  not how common it is.
- **Report plainly.** For multi-step work, state the intent in one line before starting and end with what changed, how
  it was verified, and what is still open.

## Conventions

- Code, identifiers, comments and log strings are English and ASCII. User-visible text (UI strings, docs) may be
  Turkish with proper characters; Android strings live in `values/` and `values-tr/` with equal keys, tray strings in
  `Strings/Resources.resw`. Terms shown to users come from [docs/glossary.md](docs/glossary.md).
- Comments explain a reason the code cannot show (a platform quirk, an ordering constraint, a measured number), and
  match the density of the code around them.
- .NET: `UPPER_SNAKE` constants, `_camelCase` private fields, most restrictive access, `ConfigureAwait(false)` outside
  the tray, no `.Result`/`.Wait()`, timeouts on all I/O. Tests: `Method_Scenario_Expected`, arrange-act-assert, and
  cover misuse, repetition and interruption, not only the happy path.
- Kotlin: minSdk 30 — check API levels; pure logic in `:core` with JUnit tests.
- Commits follow Conventional Commits with a lowercase Turkish description, for example
  `fix(windows): kilit ekranında tuş tekrarı durur`. `feat` and `fix` drive the version (see the `release` skill).

## Privacy and security rules

- Never log, store or display key values outside the opt-in "show keys" path; key codes leave the helper only when
  that setting is on.
- State-changing control-pipe commands are accepted only from the active session; keep that check when adding commands.
- Do not commit real network addresses, hostnames, device names, fingerprints or personal paths. Use the example values
  already in the docs (`homepc.tailnet-example.ts.net`, `192.168.1.10`, `100.64.0.10`, fingerprint/identity code from
  the test vectors).

## Hardware-dependent behaviour

Much of KeyRelay can only be verified on a real tablet and PC. See the `device-testing` skill before changing the
helper, the agent or anything timing-related, and the `input-pipeline` skill for the non-obvious platform rules
(EVIOCGRAB, key repeat, uinput, SendInput from a SYSTEM process, the writable-desktop rule).
