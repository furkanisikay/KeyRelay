# KeyRelay

Use every Windows shortcut — Alt+Tab, Win, Win+E, Ctrl+Alt+Del, the lock screen PIN — from a physical keyboard on an
Android tablet while you remote-control a Windows PC (for example with TeamViewer). Works on the same Wi-Fi and from
anywhere over the internet, including behind a VPN.

[Türkçe](README.tr.md)

<p align="center">
  <img src="docs/images/android-status.png" width="49%" alt="Android status screen">
  <img src="docs/images/windows-status.png" width="49%" alt="Windows status window">
</p>

## Why

Android swallows Alt+Tab, the Win (Meta) key and similar shortcuts before apps can see them, so remote-desktop apps on
a tablet cannot send them to the PC. KeyRelay takes the keyboard before Android does and sends every key straight to
the PC.

## How it works

- **Tablet:** a small helper started through Android's own wireless debugging (no root, no computer needed) reads the
  keyboard at the kernel level while your remote-desktop app is in front. In any other app the keyboard stays on the
  tablet.
- **Link:** keys travel over TLS 1.3 to the one PC you paired; the PC's certificate is pinned with a QR code.
- **PC:** a Windows service types the keys with the right Windows session, so they also work on the lock screen,
  the Ctrl+Alt+Del screen and UAC prompts. Holding a key repeats it; volume and media keys work.
- **Away from home:** [Tailscale Funnel](https://tailscale.com/kb/1223/funnel) gives the PC an internet address
  (raw TCP passthrough, TLS stays end to end), so the tablet connects from a café or behind a VPN such as ProtonVPN.

<p align="center">
  <img src="docs/images/windows-pair.png" width="49%" alt="Pairing with a QR code">
  <img src="docs/images/windows-network.png" width="49%" alt="Network page">
</p>

## Requirements

| | |
|---|---|
| Tablet | Android 11 or later (tested: Galaxy Tab S9 Ultra, Android 14), a physical keyboard (tested: Logitech MX Keys Mini) |
| PC | Windows 11 x64 (Windows 10 not tested) |
| Remote control | Any remote-desktop app; TeamViewer is the default target app |
| Away from home | A [Tailscale](https://tailscale.com) account on the PC (free plan is enough) |

## Install

**PC**

1. Download `KeyRelay-Setup-x.y.z.exe` from [Releases](../../releases) and run it. It installs the service and the
   tray app and opens TCP port 47111 for private networks.
2. Optional, for use away from home: install Tailscale on the PC, then run (admin)
   `tailscale funnel --bg --tcp 8443 tcp://localhost:47111`
   and enter the shown address (for example `homepc.tailnet-example.ts.net:8443`) under **Network → Internet address**.

**Tablet**

1. Install `KeyRelay-x.y.z.apk` from [Releases](../../releases).
2. Open KeyRelay; the setup wizard walks you through developer options, wireless-debugging pairing (you type the
   6-digit code straight into the KeyRelay notification) and adding your PC.
3. On the PC open **Pair**, scan the QR code on the tablet and check that the PC identity code is the same on both
   screens.

After that KeyRelay starts by itself when the tablet starts. Switch to your remote-desktop app and type.

<p align="center">
  <img src="docs/images/android-ready.png" width="49%" alt="Setup finished">
  <img src="docs/images/windows-devices.png" width="49%" alt="Paired tablets">
</p>

## Good to know

- **Toggle:** Ctrl+Alt+` turns forwarding on and off (changeable in Settings).
- **Fn keys:** on keyboards like the MX Keys Mini the top row sends F-keys or media keys depending on the Fn lock.
- **Privacy:** KeyRelay never logs or stores the keys you type. "Show keys" (off by default) only shows the last few
  keys on your own screen.
- **Security:** only a tablet you paired with a short-lived, single-use QR code can connect; revoking it on the PC
  cuts it off immediately. Only the signed-in Windows session can pair or revoke.
- **Known issue:** Tailscale sometimes drops the public DNS record of a Funnel address
  ([tailscale#21502](https://github.com/tailscale/tailscale/issues/21502)). Toggling the `funnel` node attribute in
  the Tailscale admin console brings it back.

## Build from source

```powershell
# Windows (.NET 10 SDK, Inno Setup 6)
cd windows; dotnet test KeyRelay.slnx
cd ..; .\installer\build.ps1

# Android (JDK 21)
.\android\gradlew.bat -p android test assembleDebug
```

Protocol: [docs/protocol.md](docs/protocol.md) · Design: [docs/superpowers/specs](docs/superpowers/specs) ·
Measurements: [docs/spikes](docs/spikes)

## License

[Apache-2.0](LICENSE)
