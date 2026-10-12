---
name: protocol-change
description: Use when changing anything that crosses the tablet↔PC boundary in KeyRelay - wire frames, frame fields, key or button codes, pairing URI fields, control-pipe commands, or the agent pipe between the Windows service and its session agent.
---

# Changing the protocol

Both sides are built and released together, but a tablet and a PC can run different versions for a while (the APK and
the Windows installer update separately). A protocol change is only done when both implementations, the document and
the shared vectors agree, and an older peer behaves sensibly.

## Steps

1. **Read the current contract first:** [docs/protocol.md](../../../docs/protocol.md) and
   `protocol/test-vectors/frames.json`. Find both implementations:
   - Android: `android/core/src/main/kotlin/dev/keyrelay/core/Frame.kt`, `RelayClient.kt`, `PairingUri.kt`.
   - Windows: `windows/src/KeyRelay.Protocol/` (`Frames.cs`, `FrameRules.cs`, control messages).
2. **Prefer extending over versioning.** New meaning for existing frames is cheapest when an old peer can ignore it
   safely. Example: extra mouse buttons travel as `BTN_*` codes in the existing KEY frame; an older agent maps unknown
   codes to nothing, so nothing breaks. Only bump the protocol version when an old peer would misbehave.
3. **Update in one change:** `docs/protocol.md` (field table and the reason), `frames.json` (a vector for every new or
   changed frame, byte-exact), the Kotlin and C# code, and tests on both sides that read the shared vectors.
4. **Check the limits the transport already has.** Frames are at most 512 bytes and share one TLS socket with key
   traffic; anything bulky (files, large clipboard data) needs its own channel so keys are never delayed behind it.
5. **Agent pipe and control pipe are protocols too.** Service↔agent frames (`AgentLink`) and tray↔service control
   commands (`ControlMessages`) follow the same rules; state-changing control commands must keep the active-session
   check.
6. **Verify:** `cd windows; dotnet test KeyRelay.slnx` and `.\android\gradlew.bat -p android test`. If behaviour on a
   device changes, follow the `device-testing` skill.

## Compatibility note to write

Add one line to the change description: what an older tablet does with a newer PC, and the reverse. If the answer is
"it breaks", say how the user recovers (update the other side).
