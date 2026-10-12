---
name: ui-motion
description: Use when designing or changing KeyRelay screens, animations, icons or user-facing text on Android (Compose) or Windows (WinUI 3 tray).
---

# KeyRelay UI and motion

The approved design is `docs/design/keyrelay-mockups.html` (open it in a browser; it plays every scene). Match it, and
when a screen is not in the mockup, extend its language rather than inventing a new one.

## Motion rules

The tray app and the helper run all day, so animation must cost nothing when nothing happens.

- **No continuous animation.** Every animation starts from an event (state change, pairing step, revoke, key press,
  heartbeat) and ends within about 0.2–3 s. The only exceptions run while real work is pending (a pairing in progress)
  or while a page is open and visible (QR scan line), and they stop with it.
- **Nothing runs while hidden.** Stop storyboards and timers when the window is hidden or minimised (Windows) or the
  composable leaves composition (Android).
- **Transform, don't replace.** A finished action changes into its result: the pairing code folds into the PC card, a
  revoked device's label is eaten by the bin, the send button folds into a paper plane. Stagger parts 40–170 ms apart.
- **Honest waiting.** A "waiting" animation follows the real operation (pairing, TLS handshake), not a fixed timer.
  Success and failure each get their own ending.
- **Every scene lands on its final frame.** If a scene is interrupted, snap to the end state; never leave a half-drawn
  line or glow behind. Theme every drawn colour explicitly — an unthemed line draws black in dark mode.
- **Reduced motion:** when system animations are off, jump to the final frame.
- **Measure the timing:** Windows storyboards start a frame late; await their completion instead of sleeping for the
  planned duration.

## Text rules

- User-facing terms come from [docs/glossary.md](../../../docs/glossary.md); no internal words (helper, agent, grab,
  SPKI, RTT) on screen.
- ⓘ texts answer, in this order: what this shows, what it means in each state, and what the user can do. Address the
  user informally ("sen" in Turkish), 1–3 sentences, only steps that really exist.
- Android strings: `values/` and `values-tr/` with equal keys (a test enforces it). Tray strings:
  `Strings/Resources.resw`.

## Visual defaults to avoid

Avoid patterns that make the app look generic: plain flat cards with no state colour, centred modal pop-ups for
onboarding (use an inline card on the page instead), unlabeled icons without an ⓘ or tooltip, and English month names
in Turkish UI (format dates with the current culture).
