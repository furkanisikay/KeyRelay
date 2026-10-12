# Changelog

## [0.2.0](https://github.com/furkanisikay/KeyRelay/compare/v0.1.0...v0.2.0) (2026-10-12)


### Features

* fare tuşları pc'ye gider, kaydırma yönü ayarlanabilir ([ea1ebdf](https://github.com/furkanisikay/KeyRelay/commit/ea1ebdfab35fc8ebe638a6cc13f3496a2846c640))

## [0.1.0] - 2026-10-12

First public version.

- Android app: status, PCs, settings and a four-step setup wizard; keyboard capture through a wireless-debugging
  helper while the remote-desktop app is in front; key repeat; per-state status bar icons; QR pairing with a pinned
  PC certificate.
- Windows: service + tray app; keys typed into the signed-in session, the lock screen, Ctrl+Alt+Del and UAC;
  volume and media keys; pairing, revoking and internet access (Tailscale Funnel) from the tray; installer.
- Tested end to end on a Galaxy Tab S9 Ultra (Android 14) with an MX Keys Mini and Windows 11, on the same Wi-Fi and
  over the internet behind ProtonVPN.

Known limits: the APK is debug-signed; Windows 10 is not tested; see `docs/spikes/2026-10-p4-end-to-end.md` for
open measurements.
