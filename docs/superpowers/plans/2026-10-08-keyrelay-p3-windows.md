# KeyRelay P3 — Windows tarafı — Uygulama planı

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Windows tarafını ürün hâline getirmek: ortak protokol kütüphanesi, SYSTEM servis (TLS dinleyici, eşleştirme, cihaz kaydı, ajan yönetimi, kontrol kanalı, `install|uninstall`), oturum ajanı (kilit/giriş ekranında yazma kuralı), WinUI 3 tepsi uygulaması, Inno Setup paketi; Windows'a düşen bitiş maddelerini (2a, 2b, 5, 6, 7, 10) homepc'de geliştirici istemcisiyle göstermek.

**Architecture:** `KeyRelay.Protocol` (net10.0) çerçeveleri, tuş tablosunu ve kontrol kanalı mesajlarını taşır; servis ve tepsi uygulaması onu paylaşır. `KeyRelay.Service.exe` üç modda çalışır: servis (TLS dinleyici → `RelayHub` → `AgentLink` pipe'ı; `AgentSupervisor` ajanı oturuma başlatır; `ControlServer` tepsiye hizmet eder), `--agent <servis pid>` (SYSTEM, konsol oturumunda; `KeyInjector` yazılabilirlik kuralıyla `SendInput`), `install|uninstall`. `KeyRelay.Tray` kontrol pipe'ını yoklayan WinUI 3 uygulamasıdır.

**Tech Stack:** .NET SDK 10.0.302 (C# 14), MSTest.Sdk 4.4.1 (Microsoft.Testing.Platform), Microsoft.Extensions.Hosting.WindowsServices 10.0.12, System.Security.Cryptography.ProtectedData 10.0.12, Microsoft.WindowsAppSDK 2.5.1 (unpackaged, self-contained), H.NotifyIcon.WinUI 2.4.1, QRCoder 1.8.0, System.Drawing.Common 10.0.12 (yalnız ikon üreticisi), Inno Setup 6.7.3. Sürümler 2026-10-08'de `api.nuget.org/v3-flatcontainer/<id>/index.json` ve `winget show JRSoftware.InnoSetup` ile doğrulandı (MSTest.Sdk 4.5.1 ve H.NotifyIcon.WinUI 2.5.0-beta yayımlı; spec sabitleri korunur).

**Spec:** `docs/superpowers/specs/2026-10-07-keyrelay-design.md` (bağlayıcı); protokol `docs/protocol.md`, vektörler `protocol/test-vectors/frames.json`; P1 sonuçları `docs/spikes/2026-10-risk-results.md`; onaylı arayüz taslağı `docs/design/keyrelay-mockups.html` (Windows bölümü).

## Global Constraints

- Çalışma yeri: git worktree `D:\Repos\KeyRelay-p3`, dal `feat/p3-windows`, taban `main` = `f81915a`. Aşağıdaki tüm yollar bu worktree'ye göredir (`$WT = 'D:\Repos\KeyRelay-p3'`).
- Kabuk PowerShell; komut ayırıcı `;`. git: `Set-Alias git 'C:\Program Files\Git\cmd\git.exe'`. Bash aracı D:\'yi görmez.
- Onaylı taslak `docs/design/keyrelay-mockups.html` ana çalışma kopyasında (`D:\Repos\KeyRelay`) git'e eklenmemiş dosyadır; worktree'de yoktur, oradan okunur. `.superpowers/` (ham loglar, ekran görüntüleri) de ana kopyadadır ve gitignore'ludur.
- Test: `Set-Location $WT\windows; dotnet test KeyRelay.slnx` (global.json bu klasörde, MTP). Filtre: `dotnet test KeyRelay.slnx --filter "FullyQualifiedName~<Sınıf>"` (denendi).
- .NET 10, `TreatWarningsAsErrors` + `AnalysisLevel latest-recommended` (P1 `Directory.Build.props`). Servis loglaması `[LoggerMessage]` (CA1848/CA1873, P1'deki gibi). Servis ve Protocol kodunda `ConfigureAwait(false)`; Tray UI kodunda yok (dispatcher'a dönmeli).
- Adlandırma: `const` ve `static readonly` alanlar UPPER_SNAKE (P1 deseni), private alanlar `_camelCase`, sınıflar `sealed`, en kısıtlayıcı erişim; `KeyRelay.Protocol` dışındaki tipler `internal`. Public enum'larda 0 değeri vardır, alt tip `int`'tir (CA1008/CA1028).
- Kod, isim, XML doc ve kod içi yorum İngilizce; kod içi yorum ve log string'inde Türkçe karakter yok. Kullanıcının okuduğu metin Türkçe: Tray metinleri `windows/src/KeyRelay.Tray/Strings/Resources.resw`.
- Spec sabitleri (iç sabit, ayar değil): çerçeve uzunluk alanı 1–512; HELLO 5 sn; eşleştirme kodu 5 dk, tek kullanımlık; agentWriteTimeout 1 sn; ajan izleme 500 ms; log 500 satır; ajan yeniden başlatma geri çekilmesi en fazla 5 sn.
- Ayar (`ProgramData\KeyRelay\settings.json`): `port` 47111, `idleTimeoutSeconds` 20, `funnelAddress` "". Varsayılan tek yerde: `ServiceSettings` property initializer'ları.
- Pipe'lar: `\\.\pipe\KeyRelay.Agent` (yalnız SYSTEM), `\\.\pipe\KeyRelay.Control` (SYSTEM + Administrators tam, INTERACTIVE okuma/yazma).
- Kurulum düzeni: `C:\Program Files\KeyRelay\KeyRelay.Service.exe` (self-contained tek dosya) + `C:\Program Files\KeyRelay\Tray\KeyRelay.Tray.exe` (self-contained klasör).
- Commit: `<type>(<scope>): <türkçe küçük harfli açıklama>` (subject ≤ 72), gövde maddeleri jargonsuz, son satır `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Header'lar aşağıdaki tablodadır; implementer commit'ten önce header'ı controller'a bildirir. Push yok.
- Cihaz adımları (homepc: Windows 11 26300, yönetici hesabı, komutlar PC'de yönetici komut isteminde (cmd), oturum açma yalnız Windows Hello PIN, yalnız .NET 8 runtime → self-contained yayın) controller tarafından kullanıcıyla yürütülür; implementer komut listesini hazırlar, sonuç uydurmaz. Her kurulum adımının geri alma komutu aynı adımdadır.
- homepc'de prototip `kbdrecv` 127.0.0.1:47111'de dinliyor, Funnel `tcp://homepc.tailnet-example.ts.net:8443` (TLS-terminated) → localhost:47111 (2026-10-08 görüldü). P3 cihaz kurulumu `install --port 47112` kullanır; prototipe ve Funnel'a dokunulmaz (P4).
- Verikor/DLP: P1'de Verikor DLP Gradle jar'ını engelledi. Windows App SDK self-contained çıktısı (Microsoft.UI.Xaml.dll vb. onlarca yerel DLL) ve NuGet önbelleği aynı engele takılabilir. Derleme/çalıştırma "erişim engellendi" ya da eksik DLL ile düşerse kod değiştirmeden controller'a bildir; istisna kararı kullanıcınındır.

## Kararlar (bu plan)

1. **UI metinleri** `Strings/Resources.resw` (dil klasörsüz, nötr kaynak): Türkçe karakter C# kaynağına girmez (kod içi metin kuralı); MRT Core unpackaged uygulamada nötr kaynağı her sistem dilinde bulur; İngilizce eklemek ileride tek dosyadır.
2. **Pipe protokolü uzantıları** (yalnız servis↔ajan; Android etkilenmez): `DESKTOP` yükü `u8 yazılabilir, utf8 masaüstü adı` olur (Durum ekranı "Giriş masaüstü" kartı, Kayıtlar); yeni A→S `0x32 KEY_DONE u64` = ajanın `SendInput` dönüş anı (QueryPerformanceCounter, süreçler arası tek sayaç). Bitiş 7 ölçümü böylece pipe dahil yapılır.
3. **PAIRED'dan sonra HELLO aynı bağlantıda gönderilebilir; istemci bağlantıyı kapatıp yeni bağlantıda HELLO da gönderebilir** (P2 ikinci yolu kullanır; sunucu ikisini de kabul eder). 5 sn bütçesi bağlantı açılışından sayılır. `docs/protocol.md`'ye bu cümleyle yazılır; P2'den değişiklik istenmez.
4. **QR adresleri**: Funnel (ayar) → Tailscale IP → MagicDNS adı (`<makine adı>.<Tailscale arayüzünün DNS soneki>`) → varsayılan ağ geçidi olan arayüzlerin IPv4'ü. homepc gerçek verisi (2026-10-08): VMware VMnet1 (ağ geçidi yok) ve ProtonVPN (ağ geçidi 0.0.0.0) elenir; kalan Ethernet 192.168.1.10, Tailscale 100.64.0.10 (`tailnet-example.ts.net`).
5. **Ağ ekranında Funnel kurma ve "Yerel ağda dinle" düğmeleri yok** (taslaktan sapma): Funnel `--tcp` geçişi P4'tür ve homepc'de Funnel şu an prototipe bağlı; P3'te Funnel adresi ayar olarak girilir.
6. **Kontrol kanalı**: satır başına bir JSON mesajı, en çok 64 KB. `ControlClient` ve mesaj tipleri `KeyRelay.Protocol`'dedir (servis testleri ve Tray aynı istemciyi kullanır); Protocol'e P/Invoke girmez, yalnız `System.IO.Pipes`.
7. **Sahte pipe sunucusuna karşı**: ajan `GetNamedPipeServerProcessId` ile sunucunun komut satırındaki servis pid'i olduğunu doğrular; servis `GetNamedPipeClientProcessId` ile başlattığı ajanı doğrular; pipe'lar `FirstPipeInstance` ile açılır.
8. **Tepsi simgeleri** taslaktaki üç glif (yeşil dolu daire = bağlı/hazır, kehribar halka = bekliyor, kırmızı çarpı = kapalı/hata); 16 px'te okunsun diye glifler kutuyu daha çok doldurur. İkonlar `windows/tools/make-icons.cs` ile üretilip repoya konur.
9. **Kayıtlar ekranı** servis loglarını İngilizce gösterir (log metni kuralı); taslaktaki Türkçe örnek satırlardan sapma.
10. **Tray HKLM Run** ile başlar (spec Kurulum bölümü; Bileşenler'deki "HKCU Run" ifadesiyle çelişki HKLM lehine çözüldü: tek yönetici kurulumu tüm kullanıcılara yeter).
11. **Cihaz iptali bağlı oturumu da hemen düşürür** (spec "bir sonraki bağlantıda reddedilir"in üstüne; Review Focus 1).
12. **Microsoft.WindowsAppSDK 2.5.1 metapaketi** (spec sabiti); Tray yayın boyutu T13'te ölçülüp rapora yazılır.
13. **Güvenlik duvarı kuralı yalnız Özel profil, yalnız servis programı**: homepc'de Tailscale arayüzü `Private` (2026-10-08 `Get-NetConnectionProfile`), ProtonVPN `Public`; Funnel trafiği tailscaled üzerinden loopback'ten gelir, kurala ihtiyaç duymaz. Tailscale'i `Public` sınıflayan makinelerde tailnet'ten doğrudan bağlantı engellenir (sıklığı ölçülmedi; P4 kurulum belgesine not).
14. **Gecikme grafiği**: Durum sayfasındaki çubuklar son 60 saniyenin her biri için en yavaş tuştur (taslakta 30 çubuk, anlamı tanımsızdı); ölçek alt sınırı 2 ms (bitiş 7 hedefi).

## T1 ölçümünün sonraki görevlere etkisi

İlk görev (T1) homepc'de ölçer: UAC güvenli masaüstünde SYSTEM ajanın `SendInput` dönüşü, bayat (eski) masaüstüne gönderimin dönüşü, açılışta ajanın neden birden çok kez başladığı. Sonuç sonraki kararları değiştirebilir:

| Gözlem | Karar |
|---|---|
| UAC'de Esc `1/0` döner ve UAC kapanır | SYSTEM ajan UAC'ye yazabiliyor; yapışkan 0 yalnız güvenlik ağı. T4 olduğu gibi |
| UAC'de `0/<hata>` döner | Yapışkan 0 UAC'nin asıl yoludur. T4 olduğu gibi; T15'te STATE 0 → 1 gözlenir |
| `1/0` döner ama UAC tepki vermez (sessiz kayıp) | Yazılabilirlik kuralı UAC'yi göremez → **DUR**: tasarım kapısı yeniden açılır (spec "tek kural"); T4–T15 bekler |
| UAC'de `<OpenInputDesktop failed N>` (ajan masaüstüne bağlanamaz) | `KeyInjector.Send` ikinci `AttachToInputDesktop(true)` false dönünce tuşu sessizce düşürür, `yazılabilir=1` kalır → T4'te bağlanamama da yazılamaz (`0`) sayılır; DESKTOP raporu 0 gider |
| Bayat masaüstünde `0` | "0 → yeniden aç → bir kez daha" kuralı masaüstü değişimini de yakalar |
| Bayat masaüstünde `1` (tuş kaybolur) | Olay başına giriş masaüstü kontrolü (T4 `AttachToInputDesktop(false)`) zorunludur; T15 p99'u bu maliyetle ölçer |
| Açılışta ajan çıkış kodu ≠ 0 veya `.NET Runtime` 1026 olayı | Hata T5 brief'ine bulgu olarak eklenir ve orada düzeltilir |
| Açılışta etkin konsol oturum kimliği değişir | T7'nin oturum değişimi beklemesi ve geri çekilmesi karşılar; T15'te yeniden ölçülür |
| `CreateProcessAsUser` hatası | T7 geri çekilmesi karşılar; hata kodu loglanır |

## P1'den devralınan takip maddeleri

| Madde (P1 ledger / tasarım kapısı) | Görev |
|---|---|
| AgentSupervisor pid izleme → süreç handle'ı, WaitForSingleObject, TerminateProcess | T7 |
| OnSessionChange ile oturum değişimi | T7 |
| StopAsync Kill yarışı | T7 (durdurma ExecuteAsync içinde) |
| `Process.GetCurrentProcess()` dispose edilmiyor | T7 (`Kernel32.GetCurrentProcess` sözde handle) |
| Aktif oturum yokken ajan durmuyor | T7 |
| CreateProcessAsUser açılışta erken başarısızlık → geri çekilme | T7 |
| NativeLayoutTests OffsetOf, "x64" değil "64-bit" | T5 |
| Açılışta ajanın birden çok kez başlaması | T1 (inceleme), T15 (yeniden ölçüm) |
| Gate #40/#42/#52 yazılabilirlik kuralı, #27 modifier key-up, count=1 | T4 |
| Gate #21/#28 olay başına OpenInputDesktop maliyeti | T15 (p99 ölçümü) |
| Gate #29 pipe mesajları protocol.md'de | T2 (uzantılar) |
| Probe/ ve dosya tetikleyicileri (her yerel kullanıcı SYSTEM ajana tuş yazdırabiliyordu) | T5 (silinir) |

## Review Focus

1. **Bağlıyken iptal edilen cihaz**: kullanıcı Cihazlar'dan iptal eder, tablet o anda bağlı → bağlantı hemen düşer, tuşlar artık PC'ye gitmez. → T9 `CloseDevice_ActiveDevice_DropsSessionAtOnce`, T11 `Revoke_PairedDevice_RemovesAndDropsSession`.
2. **Ajan pipe'ı yerel bir kullanıcı tarafından önceden açılmış (squatting)**: ajan sahte sunucuya tuş/sonuç göndermez, hiçbir tuş basmaz; servis başlatmadığı istemciyi ajan saymaz. → T5 `Run_ServerIsNotTheService_ExitsWithoutInjecting`, T6 `RunAsync_ClientIsNotTheLaunchedAgent_EndsWithoutReady`.
3. **Aynı tablet eski TCP ölmeden yeniden bağlanır** (Wi-Fi geçişi, tuş basılıyken kopma): yeni bağlantı kazanır, eski kapanır, eski bağlantıdan basılı kalan tuşlar RELEASE_ALL ile bırakılır. → T9 `Hello_SecondVerifiedClient_ReplacesFirstAndReleasesKeys`.
4. **Sanal/VPN ağ bağdaştırıcıları** (VMware, ProtonVPN) QR'a girerse tablet her biri için 2 sn boşa bekler: yalnız ağ geçidi olan LAN, Tailscale ve Funnel girer. → T11 `Order_HomepcInterfaces_KeepsTailscaleAndGatewayLanOnly` (gerçek veri).
5. **Geçersiz Funnel adresi** (yanlış port, IP, virgül): kaydedilmez, kullanıcı nedenini görür, QR bozulmaz. → T8 `IsValidFunnelAddress_*`, T11 `SetFunnel_InvalidPort_FailsAndKeepsSetting`.

## Commit header listesi

| Görev | Header |
|---|---|
| T1 | `docs(spike): uac ve açılışta ajan davranışı cihazda ölçüldü` |
| T2 | `feat(windows): tablet ile pc arasındaki mesajlar okunup yazılabilir oldu` |
| T3 | `feat(windows): tablet tuş kodları windows tuşlarına çevrilir oldu` |
| T4 | `feat(windows): kilit ve güvenlik ekranında tuş gönderme kuralı eklendi` |
| T5 | `feat(windows): ajan servisten gelen tuşları basar, deneme kodu kaldırıldı` |
| T6 | `feat(windows): servis ajanla güvenli kanaldan konuşur, gecikme ölçülür` |
| T7 | `fix(windows): ajan oturum değişince yeniden başlar, takılı kalmaz` |
| T8 | `feat(windows): cihaz kaydı, eşleştirme kodu ve deneme sınırı eklendi` |
| T9 | `feat(windows): tabletler şifreli bağlantıyla eşleşip bağlanabilir` |
| T10 | `feat(windows): bağlı tabletin tuşları pc'ye iletilir` |
| T11 | `feat(windows): tepsi uygulaması için servis kontrol kanalı eklendi` |
| T12 | `feat(windows): kurulum ve kaldırma tek komutla yapılır` |
| T13 | `feat(windows): tepsi uygulaması ve durum ekranı eklendi` |
| T14 | `feat(windows): cihaz, eşleştirme, ağ ve kayıt ekranları eklendi` |
| T15 | `build(windows): kurulum paketi eklendi, pc tarafı cihazda doğrulandı` |

## Dosya yapısı (görevlerin sonunda)

```
windows/
├─ KeyRelay.slnx, Directory.Build.props, Directory.Packages.props, global.json, nuget.config
├─ src/KeyRelay.Protocol/        Frame.cs, FrameReader.cs, FrameRules.cs, Frames.cs, ScancodeMap.cs,
│                                Control/ControlMessages.cs, Control/ControlChannel.cs, Control/ControlClient.cs
├─ src/KeyRelay.Service/         Program.cs, Backoff.cs, ServicePaths.cs, JsonFile.cs
│  ├─ Native/                    Advapi32.cs, Kernel32.cs, Sas.cs, User32.cs
│  ├─ Agent/                     IInputDesktop.cs, KeyInjector.cs, NativeInputDesktop.cs, AgentHost.cs, AgentLink.cs, LatencyStats.cs
│  ├─ Session/                   SessionLauncher.cs, AgentProcess.cs, SessionSignal.cs, SessionAwareServiceLifetime.cs, AgentSupervisor.cs
│  ├─ Settings/                  ServiceSettings.cs
│  ├─ Relay/                     DeviceRegistry.cs, PairingCodes.cs, AuthThrottle.cs, CertificateStore.cs, RelayContext.cs,
│  │                             RelayHub.cs, RelaySession.cs, RelayListener.cs, PairingAddresses.cs
│  ├─ Control/                   LogBuffer.cs, ControlHandler.cs, ControlServer.cs
│  └─ Setup/                     SasPolicy.cs, Installer.cs
├─ src/KeyRelay.Tray/            App.xaml(.cs), MainWindow.xaml(.cs), Ui.cs, app.manifest, Strings/Resources.resw, Assets/*,
│                                Pages/{Status,Devices,Pair,Network,Logs}Page.xaml(.cs)
├─ tests/KeyRelay.Tests/         Protocol/, Agent/, Session/, Relay/, Control/, Settings/, Setup/, TestSupport/, NativeLayoutTests.cs
└─ tools/                        make-icons.cs, devclient.cs
installer/                       KeyRelay.iss, build.ps1
```

---

### Task 1: Cihaz görevi — UAC, bayat masaüstü ve açılışta ajan (homepc)

Kullanıcı homepc başında (TeamViewer'dan izler) olmalıdır. Prob kodu yalnız ölçüm için geçici değiştirilir ve commit'lenmez; commit'e yalnız sonuç belgesi girer. Probe/ klasörü T5'te silinir.

**Files:**
- Modify (geçici, commit'lenmez): `windows/src/KeyRelay.Service/Probe/AgentProbe.cs`, `windows/src/KeyRelay.Service/Session/AgentSupervisor.cs`
- Create (geçici, repo dışı): `$WT\artifacts\boot-events.ps1`
- Modify: `docs/spikes/2026-10-risk-results.md` (yeni bölüm)

**Interfaces:**
- Consumes: P1 prob kodu (`AgentProbe`, `SasProbe`, `AgentSupervisor`, `SessionLauncher`, `User32`).
- Produces: `docs/spikes/2026-10-risk-results.md` → "## P3 cihaz görevi 1 — UAC, bayat masaüstü, açılışta ajan" bölümü ve yukarıdaki karar tablosundan seçilen satırlar. T4, T5, T7 ve T15 bu bölümü okur.

- [ ] **Step 1: Worktree ve taban testi**

```powershell
Set-Alias git 'C:\Program Files\Git\cmd\git.exe'
git -C D:\Repos\KeyRelay worktree add D:\Repos\KeyRelay-p3 -b feat/p3-windows f81915a
Set-Location D:\Repos\KeyRelay-p3\windows; dotnet test KeyRelay.slnx
```
Expected: `succeeded: 1`, `failed: 0`. (`superpowers:using-git-worktrees` başka bir yol seçerse `$WT` ona göre okunur.)

- [ ] **Step 2: homepc'de önceki durumu kaydet**

Bu görevdeki PC komutları PC'de yönetici komut isteminde (cmd) çalışır:
```cmd
sc.exe query KeyRelayProbe & sc.exe query KeyRelay & reg query HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Policies\System /v SoftwareSASGeneration & dir C:\ProgramData\KeyRelay
```
Expected: iki servis için `1060`; `SoftwareSASGeneration REG_DWORD 0x1` (P1'de de 0x1'di); `C:\ProgramData\KeyRelay` yok. Farklıysa sonuç belgesine yaz, controller'a bildir.

- [ ] **Step 3: Probu geçici olarak değiştir**

`windows/src/KeyRelay.Service/Probe/AgentProbe.cs` dosyasını tamamen şununla değiştir (`SasProbe` aynı `DIR` ve `Log`'u kullanmaya devam eder):

```csharp
using System.Runtime.InteropServices;
using KeyRelay.Service.Native;

namespace KeyRelay.Service.Probe;

/// <summary>P3 device task 1 probe. Follows the input desktop and acts on trigger files written by an admin.</summary>
internal static class AgentProbe
{
    internal static readonly string DIR = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "KeyRelay");
    // One file per process: the service (session 0) and the agent (user session) must never append to the same file.
    private static readonly string LOG = Path.Combine(DIR, Environment.GetCommandLineArgs().Contains("--agent") ? "probe-agent.log" : "probe-service.log");
    private static readonly string SCAN1_FILE = Path.Combine(DIR, "probe-scan1");
    private static readonly string ESC_FILE = Path.Combine(DIR, "probe-esc");
    private static readonly string FREEZE_FILE = Path.Combine(DIR, "probe-freeze");
    private static readonly TimeSpan FREEZE = TimeSpan.FromSeconds(30);
    // Set-1 scancodes: digits 1-4 (a Windows Hello PIN box accepts only digits) and Esc (closes a UAC prompt).
    private static readonly ushort[] PIN_SCANCODES = [0x02, 0x03, 0x04, 0x05];
    private static readonly ushort[] ESC_SCANCODES = [0x01];

    internal static int Run()
    {
        Directory.CreateDirectory(DIR);
        Log($"agent start pid={Environment.ProcessId} session={System.Diagnostics.Process.GetCurrentProcess().SessionId}");
        nint current = 0;
        var threadDesktop = "?";
        string? last = null;
        var frozenUntil = DateTime.MinValue;
        while (true)
        {
            var desk = User32.OpenInputDesktop(0, false, User32.GENERIC_ALL);
            var name = desk == 0 ? $"<OpenInputDesktop failed {Marshal.GetLastPInvokeError()}>" : User32.DesktopName(desk);
            if (desk != 0 && DateTime.UtcNow < frozenUntil)
            {
                User32.CloseDesktop(desk);
            }
            else if (desk != 0 && User32.SetThreadDesktop(desk))
            {
                if (current != 0) User32.CloseDesktop(current);
                current = desk;
                threadDesktop = name;
            }
            else if (desk != 0)
            {
                name += $" <SetThreadDesktop failed {Marshal.GetLastPInvokeError()}>";
                User32.CloseDesktop(desk);
            }
            if (name != last) { Log($"desktop: {name} thread={threadDesktop}"); last = name; }
            if (Take(FREEZE_FILE)) { frozenUntil = DateTime.UtcNow + FREEZE; Log($"freeze 30s thread={threadDesktop}"); }
            if (Take(SCAN1_FILE)) Log($"scan1 '1234' results={SendEach(PIN_SCANCODES)} input={name} thread={threadDesktop}");
            if (Take(ESC_FILE)) Log($"esc results={SendEach(ESC_SCANCODES)} input={name} thread={threadDesktop}");
            Thread.Sleep(500);
        }
    }

    /// <summary>Sends each scancode as its own down and up event (count=1, like the product).</summary>
    /// <returns>Comma separated "sent/lastError" pairs.</returns>
    private static string SendEach(ushort[] scancodes)
    {
        var results = new List<string>();
        foreach (var scancode in scancodes)
        {
            results.Add(Send(scancode, 0));
            results.Add(Send(scancode, User32.KEYEVENTF_KEYUP));
        }
        return string.Join(',', results);
    }

    private static unsafe string Send(ushort scancode, uint up)
    {
        var input = new User32.Input
        {
            Type = User32.INPUT_KEYBOARD,
            Union = new User32.InputUnion { Keyboard = new User32.KeyboardInput { Scan = scancode, Flags = User32.KEYEVENTF_SCANCODE | up } },
        };
        Marshal.SetLastPInvokeError(0);
        var sent = User32.SendInput(1, &input, sizeof(User32.Input));
        return $"{sent}/{Marshal.GetLastPInvokeError()}";
    }

    private static bool Take(string path)
    {
        if (!File.Exists(path)) return false;
        File.Delete(path);
        return true;
    }

    internal static void Log(string line) => File.AppendAllText(LOG, $"{DateTime.Now:HH:mm:ss.fff} {line}{Environment.NewLine}");
}
```

`windows/src/KeyRelay.Service/Session/AgentSupervisor.cs` içinde: `using KeyRelay.Service.Probe;` ekle; `_agentSession` alanının altına `private uint _lastSeenSession = Kernel32.NO_ACTIVE_SESSION;` ekle; `ExecuteAsync` döngüsünün başını ve `try` bloğunun ilk satırını şöyle değiştir:

```csharp
            var session = Kernel32.WTSGetActiveConsoleSessionId();
            if (session != _lastSeenSession)
            {
                AgentProbe.Log($"service: active console session {_lastSeenSession} -> {session}");
                _lastSeenSession = session;
            }
            if (session != Kernel32.NO_ACTIVE_SESSION && (_agent is null || _agent.HasExited || session != _agentSession))
            {
                try
                {
                    if (_agent is { HasExited: true }) AgentProbe.Log($"service: agent {_agent.Id} exited code={_agent.ExitCode}");
                    Stop();
```
ve `catch (System.ComponentModel.Win32Exception ex)` bloğuna `LogAgentStartFailed(ex, session);` satırından önce `AgentProbe.Log($"service: agent start failed error={ex.NativeErrorCode} session={session}");` ekle.

- [ ] **Step 4: Derle ve yayınla**

```powershell
Set-Location $WT\windows; dotnet test KeyRelay.slnx
dotnet publish src\KeyRelay.Service -c Release -r win-x64 --self-contained -p:PublishSingleFile=true -o $WT\artifacts\probe-p3
```
Expected: test 1/1, uyarı yok; `artifacts\probe-p3\KeyRelay.Service.exe` var.

- [ ] **Step 5: homepc'ye kur (geri alma: Step 10)**

PC'de `C:\KeyRelayProbe` klasörünü oluştur (`mkdir C:\KeyRelayProbe`) ve `$WT\artifacts\probe-p3\KeyRelay.Service.exe`'yi bu klasöre kopyala; sonra PC'de:
```cmd
sc.exe create KeyRelayProbe binPath= C:\KeyRelayProbe\KeyRelay.Service.exe start= demand obj= LocalSystem & sc.exe start KeyRelayProbe
type C:\ProgramData\KeyRelay\probe-agent.log & type C:\ProgramData\KeyRelay\probe-service.log
```
Expected: `agent start pid=... session=1`, `desktop: Default thread=Default`, `service: active console session 4294967295 -> 1`. `SoftwareSASGeneration` zaten 0x1 olduğu için değiştirilmez.

- [ ] **Step 6: UAC, güncel masaüstü (kullanıcıyla)**

Kullanıcı TeamViewer'dan Terminal'e sağ tıklayıp "Yönetici olarak çalıştır" der; UAC onay penceresi güvenli masaüstünde açılır. Pencere açıkken:
```cmd
type nul> C:\ProgramData\KeyRelay\probe-scan1
type nul> C:\ProgramData\KeyRelay\probe-esc
type C:\ProgramData\KeyRelay\probe-agent.log
```
Kayda geçecekler: `desktop: Winlogon thread=Winlogon` satırı, `scan1` ve `esc` satırlarındaki her `sent/lastError` çifti, kullanıcının gözlemi: Esc'ten sonra UAC penceresi kapandı mı (Hayır'a basılmış gibi)? Kapanmadıysa kullanıcı Hayır'a basar.

- [ ] **Step 7: UAC, bayat masaüstü (kullanıcıyla)**

UAC kapalıyken (giriş masaüstü Default):
```cmd
type nul> C:\ProgramData\KeyRelay\probe-freeze
```
30 sn içinde kullanıcı UAC penceresini yeniden açar; açılır açılmaz:
```cmd
type nul> C:\ProgramData\KeyRelay\probe-esc
```
Log `esc results=... input=Winlogon thread=Default` olmalıdır (iş parçacığı eski masaüstünde). Kullanıcı UAC'nin tepkisini söyler (beklenen: tepki yok). 30 sn dolunca ajan Winlogon'a geçer (`desktop:` satırı değişmez ama sonraki `esc` satırı `thread=Winlogon` gösterir):
```cmd
type nul> C:\ProgramData\KeyRelay\probe-esc
```
Kayda geçecekler: iki `esc` satırı ve iki gözlem.

- [ ] **Step 8: Kilit ekranı, bayat masaüstü (kullanıcıyla)**

Kullanıcı UAC'yi kapatır, `probe-freeze` yazılır, kullanıcı 30 sn içinde PC'yi kilitler ve PIN kutusunu açar; `probe-scan1` yazılır → log `input=Winlogon thread=Default`, kullanıcı PIN kutusunda nokta görüp görmediğini söyler. Freeze bitince `probe-scan1` yeniden yazılır → P1'deki gibi 4 nokta beklenir. Kullanıcı kutuyu temizleyip PIN'iyle girer.

- [ ] **Step 9: Açılışta ajan (kullanıcı onayıyla yeniden başlatma)**

`$WT\artifacts\boot-events.ps1`:
```powershell
$boot = (Get-CimInstance Win32_OperatingSystem).LastBootUpTime
Write-Output "boot: $boot"
Get-WinEvent -FilterHashtable @{ LogName = 'Application'; StartTime = $boot } -ErrorAction SilentlyContinue |
  Where-Object { $_.ProviderName -in '.NET Runtime', 'Application Error', 'KeyRelay.Service' } |
  Format-List TimeCreated, ProviderName, Id, Message
Get-WinEvent -FilterHashtable @{ LogName = 'System'; StartTime = $boot; ProviderName = 'Service Control Manager' } -ErrorAction SilentlyContinue |
  Where-Object { $_.Message -match 'KeyRelay' } | Format-List TimeCreated, Id, Message
Get-WinEvent -FilterHashtable @{ LogName = 'Microsoft-Windows-TerminalServices-LocalSessionManager/Operational'; StartTime = $boot } -ErrorAction SilentlyContinue |
  Format-List TimeCreated, Id, Message
```
Kullanıcı onay verince `boot-events.ps1`'i PC'de `C:\KeyRelayProbe\` klasörüne kopyala, sonra PC'de:
```cmd
sc.exe config KeyRelayProbe start= auto & shutdown /r /t 5
```
Açılınca, kimse oturum açmadan:
```cmd
type C:\ProgramData\KeyRelay\probe-agent.log & type C:\ProgramData\KeyRelay\probe-service.log
powershell -NoProfile -ExecutionPolicy Bypass -File C:\KeyRelayProbe\boot-events.ps1
```
Ardından kullanıcı PIN ile oturum açar; iki komut yeniden çalıştırılır (oturum açılışındaki yeniden başlamalar da görülsün). Kayda geçecekler: her `agent start` satırı, onu izleyen `service: agent ... exited code=...` ve `active console session a -> b` satırları, `.NET Runtime`/`Application Error` olayları, LocalSessionManager olay kimlikleri ve zamanları. Neden, bu satırların zaman sırasıyla yazılır; kanıt yoksa "neden bulunamadı" yazılır.

- [ ] **Step 10: Temizlik ve prob kodunu geri al**

PC'de:
```cmd
sc.exe stop KeyRelayProbe & sc.exe delete KeyRelayProbe & rmdir /s /q C:\KeyRelayProbe & rmdir /s /q C:\ProgramData\KeyRelay
sc.exe query KeyRelayProbe & tasklist /fi "imagename eq KeyRelay.Service.exe" & reg query HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Policies\System /v SoftwareSASGeneration
```
Worktree'de:
```powershell
git -C $WT checkout -- windows/src/KeyRelay.Service
git -C $WT status --short
```
Expected: `1060`; süreç yok; `0x1`; `git status` yalnız `docs/spikes/...` değişikliğini (Step 11 sonrası) gösterir. Logların ham kopyası `D:\Repos\KeyRelay\.superpowers\sdd\2026-10-08-keyrelay-p3-windows\t1-*.log` altına alınır (gitignore'lu).

- [ ] **Step 11: Sonuç belgesi**

`docs/spikes/2026-10-risk-results.md` sonuna "## P3 cihaz görevi 1 — UAC, bayat masaüstü, açılışta ajan (2026-10-..)" bölümü: her adımda komut, gözlenen log satırları (alıntı), kullanıcının gözlemi, sonuç. Bölüm sonunda "Sonraki görevlere etkisi": yukarıdaki karar tablosundan seçilen satırlar, açıkça. `sent=1` ama `lastError` sıfır değilse bunun yalnız `sent=0` durumunda anlamlı olduğunu not et. Bir ölçüm yapılamadıysa "denenmedi" yazılır, neden yazılır. Karar tablosunun "DUR" satırı seçildiyse commit'ten sonra controller'a bildir ve T2'ye geçme.

- [ ] **Step 12: Commit**

```powershell
git -C $WT add docs/spikes/2026-10-risk-results.md
git -C $WT commit -m "docs(spike): uac ve açılışta ajan davranışı cihazda ölçüldü" -m "- yönetici onay penceresi açıkken tuş gönderiminin sonucu kaydedildi
- kilit ekranına geç geçildiğinde tuşların akıbeti kaydedildi
- açılışta yardımcı sürecin neden birden çok kez başladığı incelendi" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: KeyRelay.Protocol — çerçeve okuyucu/yazıcı ve test vektörleri

**Files:**
- Create: `windows/src/KeyRelay.Protocol/KeyRelay.Protocol.csproj`, `windows/src/KeyRelay.Protocol/Frame.cs`, `windows/src/KeyRelay.Protocol/FrameRules.cs`, `windows/src/KeyRelay.Protocol/FrameReader.cs`, `windows/src/KeyRelay.Protocol/Frames.cs`
- Modify: `windows/KeyRelay.slnx`, `windows/src/KeyRelay.Service/KeyRelay.Service.csproj`, `windows/tests/KeyRelay.Tests/KeyRelay.Tests.csproj`, `.editorconfig`
- Modify: `protocol/test-vectors/frames.json`, `docs/protocol.md`
- Test: `windows/tests/KeyRelay.Tests/Protocol/FrameVectorTests.cs`, `windows/tests/KeyRelay.Tests/Protocol/FrameReaderTests.cs`

**Interfaces:**
- Produces (namespace `KeyRelay.Protocol`, public):
  - `enum FrameType { None = 0, Pair = 0x01, Paired = 0x02, Hello = 0x03, Welcome = 0x04, Reject = 0x05, Key = 0x10, State = 0x11, Sas = 0x12, Lock = 0x13, Ping = 0x20, Pong = 0x21, ReleaseAll = 0x30, Desktop = 0x31, KeyDone = 0x32 }`
  - `enum FrameChannel { Tls, Pipe }`, `enum ProtocolError { Empty, TooLarge, UnknownType, Truncated, BadPayload }`, `enum RejectReason { None = 0, Version = 1, UnknownDevice = 2, InvalidCode = 3 }`
  - `readonly record struct Frame(FrameType Type, ReadOnlyMemory<byte> Payload, ReadOnlyMemory<byte> Raw)` + `const int MAX_LENGTH = 512, HEADER_SIZE = 2, MAX_SIZE = 514`
  - `sealed class ProtocolException : Exception` + `ProtocolError Error`
  - `sealed class FrameReader(Stream stream, FrameChannel channel)`: `Frame? Read()`, `ValueTask<Frame?> ReadAsync(CancellationToken)`; Payload/Raw bir sonraki okumaya kadar geçerlidir.
  - `static class Frames`: sabitler `VERSION = 1, ID_SIZE = 16, TOKEN_SIZE = 32, CODE_SIZE = 16, KEY_DONE_SIZE = 11`; `byte[] Pair(ReadOnlySpan<byte> code, string deviceName)`, `Paired(id, token)`, `Hello(id, token)`, `Welcome(string)`, `Reject(RejectReason)`, `Key(ushort code, byte value)`, `State(bool)`, `Empty(FrameType)`, `Ping(uint)`, `Pong(uint)`, `Desktop(bool writable, string name)`, `KeyDone(long timestamp)`; `void WriteKeyDone(Span<byte>, long)`; `ReadOnlyMemory<byte> LockFrame`, `ReleaseAllFrame`; okuyucular `(ushort Code, byte Value) ReadKey(ReadOnlySpan<byte>)`, `uint ReadSeq(...)`, `long ReadKeyDone(...)`, `(bool Writable, string Name) ReadDesktop(...)`.

- [ ] **Step 1: Proje ve referanslar**

`windows/src/KeyRelay.Protocol/KeyRelay.Protocol.csproj`:
```xml
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <TargetFramework>net10.0</TargetFramework>
    <RootNamespace>KeyRelay.Protocol</RootNamespace>
  </PropertyGroup>
</Project>
```
`windows/KeyRelay.slnx`:
```xml
<Solution>
  <Folder Name="/src/">
    <Project Path="src/KeyRelay.Protocol/KeyRelay.Protocol.csproj" />
    <Project Path="src/KeyRelay.Service/KeyRelay.Service.csproj" />
  </Folder>
  <Folder Name="/tests/">
    <Project Path="tests/KeyRelay.Tests/KeyRelay.Tests.csproj" />
  </Folder>
</Solution>
```
`KeyRelay.Service.csproj`'daki `ItemGroup`'a `<ProjectReference Include="..\KeyRelay.Protocol\KeyRelay.Protocol.csproj" />` ekle.
`KeyRelay.Tests.csproj`:
```xml
<Project Sdk="MSTest.Sdk">
  <PropertyGroup>
    <TargetFramework>net10.0-windows</TargetFramework>
  </PropertyGroup>
  <ItemGroup>
    <ProjectReference Include="..\..\src\KeyRelay.Service\KeyRelay.Service.csproj" />
    <ProjectReference Include="..\..\src\KeyRelay.Protocol\KeyRelay.Protocol.csproj" />
    <None Include="..\..\..\protocol\test-vectors\frames.json" Link="frames.json" CopyToOutputDirectory="PreserveNewest" />
  </ItemGroup>
</Project>
```
Kök `.editorconfig` içindeki `[windows/tests/**.cs]` bölümünün başlığını `[windows/**.cs]` yap (Protocol ilk public kütüphane; public `UPPER_SNAKE` sabitleri CA1707'ye takılır, sabit adını naming kuralı zaten korur), yorumunu `# Constants are UPPER_SNAKE by the naming rule above; test names follow Method_Scenario_ExpectedResult` yap. Ardından ayrı bir `[windows/tests/**.cs]` bölümü aç:
```
[windows/tests/**.cs]
# Expected values read best inline in assertions; allocation cost does not matter in tests
dotnet_diagnostic.CA1861.severity = none
```

- [ ] **Step 2: Test vektörlerini genişlet**

`protocol/test-vectors/frames.json` içinde `desktop_writable` satırını şu üç satırla değiştir (yalnız `pipe` kanalı; Android `:core` yalnız `tls` vektörlerini kullanır):
```json
    { "name": "desktop_writable", "channels": ["pipe"], "type": "DESKTOP", "fields": { "writable": true, "name": "Default" }, "hex": "0009310144656661756c74" },
    { "name": "desktop_not_writable", "channels": ["pipe"], "type": "DESKTOP", "fields": { "writable": false, "name": "Winlogon" }, "hex": "000a310057696e6c6f676f6e" },
    { "name": "key_done", "channels": ["pipe"], "type": "KEY_DONE", "fields": { "timestamp": 1000000 }, "hex": "00093200000000000f4240" }
```
Geçersiz vektörlere ekleme yapılmaz (geçersiz vektörler iki kanalda da aynı hatayı vermelidir; kanala özgü hatalar C# testindedir).

Uzunluk kontrolü (P1 Step 5 ile aynı):
```powershell
python -I -c "import json;d=json.load(open(r'D:\Repos\KeyRelay-p3\protocol\test-vectors\frames.json'));bad=[v['name'] for v in d['valid'] if int(v['hex'][:4],16)!=len(bytes.fromhex(v['hex']))-2];print('length mismatches:',bad)"
```
Expected: `length mismatches: []`

- [ ] **Step 3: Failing testleri yaz**

`windows/tests/KeyRelay.Tests/Protocol/FrameVectorTests.cs`:
```csharp
using System.Text;
using System.Text.Json;
using KeyRelay.Protocol;

namespace KeyRelay.Tests.Protocol;

[TestClass]
public sealed class FrameVectorTests
{
    private static readonly JsonElement VECTORS =
        JsonDocument.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "frames.json"))).RootElement;

    [TestMethod]
    public void Read_ValidVectorsOnTheirChannels_DecodeToExpectedFields()
    {
        var checkedCount = 0;
        foreach (var vector in VECTORS.GetProperty("valid").EnumerateArray())
        {
            var name = vector.GetProperty("name").GetString()!;
            foreach (var channel in vector.GetProperty("channels").EnumerateArray())
            {
                var frame = Decode(vector.GetProperty("hex").GetString()!, ParseChannel(channel.GetString()!));

                Assert.AreEqual(ParseType(vector.GetProperty("type").GetString()!), frame.Type, name);
                AssertFields(frame, vector.GetProperty("fields"), name);
                checkedCount++;
            }
        }
        Assert.IsTrue(checkedCount > 20, "no vectors were checked");
    }

    [TestMethod]
    public void Builders_ValidVectors_ProduceSameBytes()
    {
        foreach (var vector in VECTORS.GetProperty("valid").EnumerateArray())
        {
            var name = vector.GetProperty("name").GetString()!;

            var built = Build(ParseType(vector.GetProperty("type").GetString()!), vector.GetProperty("fields"));

            Assert.AreEqual(vector.GetProperty("hex").GetString(), Convert.ToHexStringLower(built), name);
        }
    }

    [TestMethod]
    public void Read_InvalidVectors_FailWithExpectedErrorOnBothChannels()
    {
        foreach (var vector in VECTORS.GetProperty("invalid").EnumerateArray())
        {
            var name = vector.GetProperty("name").GetString()!;
            var expected = Enum.Parse<ProtocolError>(vector.GetProperty("error").GetString()!.Replace("_", "", StringComparison.Ordinal), ignoreCase: true);
            foreach (var channel in new[] { FrameChannel.Tls, FrameChannel.Pipe })
            {
                var hex = vector.GetProperty("hex").GetString()!;

                var error = Assert.ThrowsExactly<ProtocolException>(() => Decode(hex, channel), name).Error;

                Assert.AreEqual(expected, error, $"{name} on {channel}");
            }
        }
    }

    internal static Frame Decode(string hex, FrameChannel channel) =>
        new FrameReader(new MemoryStream(Convert.FromHexString(hex)), channel).Read()!.Value;

    private static FrameChannel ParseChannel(string channel) => channel == "tls" ? FrameChannel.Tls : FrameChannel.Pipe;

    private static FrameType ParseType(string type) =>
        Enum.Parse<FrameType>(type.Replace("_", "", StringComparison.Ordinal), ignoreCase: true);

    private static string Hex(ReadOnlySpan<byte> bytes) => Convert.ToHexStringLower(bytes);

    private static void AssertFields(Frame frame, JsonElement fields, string name)
    {
        var p = frame.Payload.Span;
        switch (frame.Type)
        {
            case FrameType.Key:
                var (code, value) = Frames.ReadKey(p);
                Assert.AreEqual(fields.GetProperty("code").GetInt32(), code, name);
                Assert.AreEqual(fields.GetProperty("value").GetInt32(), value, name);
                break;
            case FrameType.Hello:
                Assert.AreEqual(fields.GetProperty("version").GetInt32(), p[0], name);
                Assert.AreEqual(fields.GetProperty("deviceId").GetString(), Hex(p.Slice(1, Frames.ID_SIZE)), name);
                Assert.AreEqual(fields.GetProperty("token").GetString(), Hex(p.Slice(1 + Frames.ID_SIZE, Frames.TOKEN_SIZE)), name);
                break;
            case FrameType.Pair:
                Assert.AreEqual(fields.GetProperty("version").GetInt32(), p[0], name);
                Assert.AreEqual(fields.GetProperty("code").GetString(), Hex(p.Slice(1, Frames.CODE_SIZE)), name);
                Assert.AreEqual(fields.GetProperty("deviceName").GetString(), Encoding.UTF8.GetString(p[(1 + Frames.CODE_SIZE)..]), name);
                break;
            case FrameType.Paired:
                Assert.AreEqual(fields.GetProperty("deviceId").GetString(), Hex(p[..Frames.ID_SIZE]), name);
                Assert.AreEqual(fields.GetProperty("token").GetString(), Hex(p[Frames.ID_SIZE..]), name);
                break;
            case FrameType.Welcome:
                Assert.AreEqual(fields.GetProperty("pcName").GetString(), Encoding.UTF8.GetString(p), name);
                break;
            case FrameType.Reject:
                Assert.AreEqual(fields.GetProperty("reason").GetInt32(), p[0], name);
                break;
            case FrameType.State:
                Assert.AreEqual(fields.GetProperty("ready").GetBoolean(), p[0] == 1, name);
                break;
            case FrameType.Ping or FrameType.Pong:
                Assert.AreEqual(fields.GetProperty("seq").GetUInt32(), Frames.ReadSeq(p), name);
                break;
            case FrameType.Desktop:
                var (writable, desktop) = Frames.ReadDesktop(p);
                Assert.AreEqual(fields.GetProperty("writable").GetBoolean(), writable, name);
                Assert.AreEqual(fields.GetProperty("name").GetString(), desktop, name);
                break;
            case FrameType.KeyDone:
                Assert.AreEqual(fields.GetProperty("timestamp").GetInt64(), Frames.ReadKeyDone(p), name);
                break;
            default:
                Assert.IsTrue(p.IsEmpty, name);
                break;
        }
    }

    private static byte[] Build(FrameType type, JsonElement f) => type switch
    {
        FrameType.Key => Frames.Key((ushort)f.GetProperty("code").GetInt32(), (byte)f.GetProperty("value").GetInt32()),
        FrameType.Hello => Frames.Hello(Convert.FromHexString(f.GetProperty("deviceId").GetString()!), Convert.FromHexString(f.GetProperty("token").GetString()!)),
        FrameType.Pair => Frames.Pair(Convert.FromHexString(f.GetProperty("code").GetString()!), f.GetProperty("deviceName").GetString()!),
        FrameType.Paired => Frames.Paired(Convert.FromHexString(f.GetProperty("deviceId").GetString()!), Convert.FromHexString(f.GetProperty("token").GetString()!)),
        FrameType.Welcome => Frames.Welcome(f.GetProperty("pcName").GetString()!),
        FrameType.Reject => Frames.Reject((RejectReason)f.GetProperty("reason").GetInt32()),
        FrameType.State => Frames.State(f.GetProperty("ready").GetBoolean()),
        FrameType.Ping => Frames.Ping(f.GetProperty("seq").GetUInt32()),
        FrameType.Pong => Frames.Pong(f.GetProperty("seq").GetUInt32()),
        FrameType.Desktop => Frames.Desktop(f.GetProperty("writable").GetBoolean(), f.GetProperty("name").GetString()!),
        FrameType.KeyDone => Frames.KeyDone(f.GetProperty("timestamp").GetInt64()),
        _ => Frames.Empty(type),
    };
}
```

`windows/tests/KeyRelay.Tests/Protocol/FrameReaderTests.cs`:
```csharp
using KeyRelay.Protocol;

namespace KeyRelay.Tests.Protocol;

[TestClass]
public sealed class FrameReaderTests
{
    [TestMethod]
    public void Read_TwoFramesBackToBack_ReturnsBothThenNull()
    {
        var reader = new FrameReader(new MemoryStream(Convert.FromHexString("000410001e01" + "000113")), FrameChannel.Pipe);

        var first = reader.Read();
        var second = reader.Read();
        var end = reader.Read();

        Assert.AreEqual(FrameType.Key, first!.Value.Type);
        Assert.AreEqual(FrameType.Lock, second!.Value.Type);
        Assert.IsNull(end);
    }

    [TestMethod]
    public void Read_StreamEndsInsideHeader_IsTruncated()
    {
        var reader = new FrameReader(new MemoryStream([0x00]), FrameChannel.Tls);

        var error = Assert.ThrowsExactly<ProtocolException>(() => reader.Read()).Error;

        Assert.AreEqual(ProtocolError.Truncated, error);
    }

    [TestMethod]
    [DataRow("00023102", DisplayName = "DESKTOP value 2")]
    [DataRow("0003320102", DisplayName = "KEY_DONE short")]
    public void Read_PipeBadPayloads_AreBadPayload(string hex)
    {
        var error = Assert.ThrowsExactly<ProtocolException>(() => FrameVectorTests.Decode(hex, FrameChannel.Pipe)).Error;

        Assert.AreEqual(ProtocolError.BadPayload, error);
    }

    [TestMethod]
    public void Read_DesktopWithEmptyName_IsValid()
    {
        var frame = FrameVectorTests.Decode("00023100", FrameChannel.Pipe);

        Assert.AreEqual(FrameType.Desktop, frame.Type);
        Assert.AreEqual((false, ""), Frames.ReadDesktop(frame.Payload.Span));
    }

    [TestMethod]
    [DataRow("000130", FrameChannel.Tls, DisplayName = "RELEASE_ALL on TLS")]
    [DataRow("000112", FrameChannel.Pipe, DisplayName = "SAS on pipe")]
    public void Read_TypeOfOtherChannel_IsUnknownType(string hex, FrameChannel channel)
    {
        var error = Assert.ThrowsExactly<ProtocolException>(() => FrameVectorTests.Decode(hex, channel)).Error;

        Assert.AreEqual(ProtocolError.UnknownType, error);
    }

    [TestMethod]
    public async Task ReadAsync_KeyFrame_MatchesSyncReadAndKeepsRawBytes()
    {
        var bytes = Convert.FromHexString("000410007d00");
        var reader = new FrameReader(new MemoryStream(bytes), FrameChannel.Tls);

        var frame = await reader.ReadAsync(CancellationToken.None);

        Assert.AreEqual(FrameType.Key, frame!.Value.Type);
        CollectionAssert.AreEqual(bytes, frame.Value.Raw.ToArray());
    }

    [TestMethod]
    public void Welcome_NameLongerThanFrameLimit_Throws()
    {
        Assert.ThrowsExactly<ArgumentOutOfRangeException>(() => Frames.Welcome(new string('A', Frame.MAX_LENGTH)));
    }
}
```

- [ ] **Step 4: Testin başarısız olduğunu gör**

Run: `Set-Location $WT\windows; dotnet test KeyRelay.slnx --filter "FullyQualifiedName~KeyRelay.Tests.Protocol"`
Expected: derleme hatası (`FrameReader`, `Frames`, `ProtocolException` yok).

- [ ] **Step 5: Uygulamayı yaz**

`windows/src/KeyRelay.Protocol/Frame.cs`:
```csharp
namespace KeyRelay.Protocol;

/// <summary>Frame types of the TLS link (tablet and service) and the pipe link (service and agent).</summary>
public enum FrameType
{
    None = 0,
    Pair = 0x01,
    Paired = 0x02,
    Hello = 0x03,
    Welcome = 0x04,
    Reject = 0x05,
    Key = 0x10,
    State = 0x11,
    Sas = 0x12,
    Lock = 0x13,
    Ping = 0x20,
    Pong = 0x21,
    ReleaseAll = 0x30,
    Desktop = 0x31,
    KeyDone = 0x32,
}

/// <summary>Which link a reader decodes; each link accepts only its own frame types.</summary>
public enum FrameChannel
{
    Tls,
    Pipe,
}

/// <summary>Why a frame was refused; names match the error values of the shared test vectors.</summary>
public enum ProtocolError
{
    Empty,
    TooLarge,
    UnknownType,
    Truncated,
    BadPayload,
}

/// <summary>Reasons carried by REJECT.</summary>
public enum RejectReason
{
    None = 0,
    Version = 1,
    UnknownDevice = 2,
    InvalidCode = 3,
}

/// <summary>A decoded frame. Payload and Raw point into the reader's buffer and stay valid until the next read.</summary>
/// <param name="Type">Frame type.</param>
/// <param name="Payload">Bytes after the type byte.</param>
/// <param name="Raw">The whole frame as received, used to forward KEY frames unchanged.</param>
public readonly record struct Frame(FrameType Type, ReadOnlyMemory<byte> Payload, ReadOnlyMemory<byte> Raw)
{
    /// <summary>Largest value of the length field (type byte plus payload).</summary>
    public const int MAX_LENGTH = 512;

    /// <summary>Size of the big-endian length field.</summary>
    public const int HEADER_SIZE = 2;

    /// <summary>Largest frame on the wire.</summary>
    public const int MAX_SIZE = HEADER_SIZE + MAX_LENGTH;
}

/// <summary>A frame broke the protocol; the connection must be closed.</summary>
public sealed class ProtocolException : Exception
{
    public ProtocolException()
    {
    }

    public ProtocolException(string message) : base(message)
    {
    }

    public ProtocolException(string message, Exception innerException) : base(message, innerException)
    {
    }

    public ProtocolException(ProtocolError error) : base(error.ToString()) => Error = error;

    /// <summary>Gets what was wrong with the frame.</summary>
    public ProtocolError Error { get; }
}
```

`windows/src/KeyRelay.Protocol/FrameRules.cs`:
```csharp
namespace KeyRelay.Protocol;

/// <summary>Which types each link accepts and what payload each type must carry (docs/protocol.md).</summary>
internal static class FrameRules
{
    /// <exception cref="ProtocolException">UnknownType or BadPayload.</exception>
    internal static FrameType Validate(FrameChannel channel, byte type, ReadOnlySpan<byte> payload)
    {
        var frameType = (FrameType)type;
        if (!IsAllowed(channel, frameType)) throw new ProtocolException(ProtocolError.UnknownType);
        if (!IsPayloadValid(frameType, payload)) throw new ProtocolException(ProtocolError.BadPayload);
        return frameType;
    }

    private static bool IsAllowed(FrameChannel channel, FrameType type) => channel == FrameChannel.Tls
        ? type is FrameType.Pair or FrameType.Paired or FrameType.Hello or FrameType.Welcome or FrameType.Reject
            or FrameType.Key or FrameType.State or FrameType.Sas or FrameType.Lock or FrameType.Ping or FrameType.Pong
        : type is FrameType.Key or FrameType.Lock or FrameType.ReleaseAll or FrameType.Desktop or FrameType.KeyDone;

    private static bool IsPayloadValid(FrameType type, ReadOnlySpan<byte> payload) => type switch
    {
        FrameType.Pair => payload.Length >= 1 + Frames.CODE_SIZE,
        FrameType.Paired => payload.Length == Frames.ID_SIZE + Frames.TOKEN_SIZE,
        FrameType.Hello => payload.Length == 1 + Frames.ID_SIZE + Frames.TOKEN_SIZE,
        FrameType.Welcome => true,
        FrameType.Reject => payload.Length == 1 && payload[0] is >= 1 and <= 3,
        FrameType.Key => payload.Length == 3 && payload[2] <= 2,
        FrameType.State => payload.Length == 1,
        FrameType.Sas or FrameType.Lock or FrameType.ReleaseAll => payload.IsEmpty,
        FrameType.Ping or FrameType.Pong => payload.Length == sizeof(uint),
        FrameType.Desktop => payload.Length >= 1 && payload[0] <= 1,
        FrameType.KeyDone => payload.Length == sizeof(long),
        _ => false,
    };
}
```

`windows/src/KeyRelay.Protocol/FrameReader.cs`:
```csharp
using System.Buffers.Binary;

namespace KeyRelay.Protocol;

/// <summary>Reads length-prefixed frames into one reusable buffer, so reading allocates nothing per frame.</summary>
/// <param name="stream">TLS stream or named pipe.</param>
/// <param name="channel">Decides which frame types are accepted.</param>
public sealed class FrameReader(Stream stream, FrameChannel channel)
{
    private readonly byte[] _buffer = new byte[Frame.MAX_SIZE];

    /// <summary>Blocks until the next frame arrives.</summary>
    /// <returns>The frame, or null when the stream ended cleanly between frames.</returns>
    /// <exception cref="ProtocolException">The frame is malformed or the stream ended inside it.</exception>
    public Frame? Read()
    {
        var got = stream.Read(_buffer, 0, Frame.HEADER_SIZE);
        if (got == 0) return null;
        Fill(got, Frame.HEADER_SIZE);
        var length = CheckedLength();
        Fill(Frame.HEADER_SIZE, Frame.HEADER_SIZE + length);
        return Decode(length);
    }

    /// <summary>Waits for the next frame.</summary>
    /// <param name="cancellationToken">Cancels the wait (idle timeouts, shutdown).</param>
    /// <returns>The frame, or null when the stream ended cleanly between frames.</returns>
    /// <exception cref="ProtocolException">The frame is malformed or the stream ended inside it.</exception>
    public async ValueTask<Frame?> ReadAsync(CancellationToken cancellationToken)
    {
        var got = await stream.ReadAsync(_buffer.AsMemory(0, Frame.HEADER_SIZE), cancellationToken).ConfigureAwait(false);
        if (got == 0) return null;
        await FillAsync(got, Frame.HEADER_SIZE, cancellationToken).ConfigureAwait(false);
        var length = CheckedLength();
        await FillAsync(Frame.HEADER_SIZE, Frame.HEADER_SIZE + length, cancellationToken).ConfigureAwait(false);
        return Decode(length);
    }

    private void Fill(int from, int to)
    {
        try
        {
            stream.ReadExactly(_buffer, from, to - from);
        }
        catch (EndOfStreamException)
        {
            throw new ProtocolException(ProtocolError.Truncated);
        }
    }

    private async ValueTask FillAsync(int from, int to, CancellationToken cancellationToken)
    {
        try
        {
            await stream.ReadExactlyAsync(_buffer.AsMemory(from, to - from), cancellationToken).ConfigureAwait(false);
        }
        catch (EndOfStreamException)
        {
            throw new ProtocolException(ProtocolError.Truncated);
        }
    }

    private int CheckedLength()
    {
        int length = BinaryPrimitives.ReadUInt16BigEndian(_buffer);
        if (length == 0) throw new ProtocolException(ProtocolError.Empty);
        if (length > Frame.MAX_LENGTH) throw new ProtocolException(ProtocolError.TooLarge);
        return length;
    }

    private Frame Decode(int length)
    {
        var payload = _buffer.AsMemory(Frame.HEADER_SIZE + 1, length - 1);
        var type = FrameRules.Validate(channel, _buffer[Frame.HEADER_SIZE], payload.Span);
        return new Frame(type, payload, _buffer.AsMemory(0, Frame.HEADER_SIZE + length));
    }
}
```

`windows/src/KeyRelay.Protocol/Frames.cs`:
```csharp
using System.Buffers.Binary;
using System.Text;

namespace KeyRelay.Protocol;

/// <summary>Builds and reads frame payloads. byte[] builders are for rare frames; Write* fill caller buffers on hot paths.</summary>
public static class Frames
{
    /// <summary>Protocol version carried by PAIR and HELLO.</summary>
    public const byte VERSION = 1;

    /// <summary>Size of a device id.</summary>
    public const int ID_SIZE = 16;

    /// <summary>Size of a device token.</summary>
    public const int TOKEN_SIZE = 32;

    /// <summary>Size of a pairing code.</summary>
    public const int CODE_SIZE = 16;

    /// <summary>Size of a whole KEY_DONE frame.</summary>
    public const int KEY_DONE_SIZE = Frame.HEADER_SIZE + 1 + sizeof(long);

    private const int KEY_SIZE = Frame.HEADER_SIZE + 1 + 3;

    /// <summary>Gets a LOCK frame.</summary>
    public static ReadOnlyMemory<byte> LockFrame { get; } = Empty(FrameType.Lock);

    /// <summary>Gets a RELEASE_ALL frame.</summary>
    public static ReadOnlyMemory<byte> ReleaseAllFrame { get; } = Empty(FrameType.ReleaseAll);

    public static byte[] Pair(ReadOnlySpan<byte> code, string deviceName) =>
        Build(FrameType.Pair, [VERSION, .. code, .. Encoding.UTF8.GetBytes(deviceName)]);

    public static byte[] Paired(ReadOnlySpan<byte> deviceId, ReadOnlySpan<byte> token) => Build(FrameType.Paired, [.. deviceId, .. token]);

    public static byte[] Hello(ReadOnlySpan<byte> deviceId, ReadOnlySpan<byte> token) => Build(FrameType.Hello, [VERSION, .. deviceId, .. token]);

    public static byte[] Welcome(string pcName) => Build(FrameType.Welcome, Encoding.UTF8.GetBytes(pcName));

    public static byte[] Reject(RejectReason reason) => Build(FrameType.Reject, [(byte)reason]);

    public static byte[] Key(ushort code, byte value)
    {
        var frame = new byte[KEY_SIZE];
        BinaryPrimitives.WriteUInt16BigEndian(frame, KEY_SIZE - Frame.HEADER_SIZE);
        frame[Frame.HEADER_SIZE] = (byte)FrameType.Key;
        BinaryPrimitives.WriteUInt16BigEndian(frame.AsSpan(Frame.HEADER_SIZE + 1), code);
        frame[KEY_SIZE - 1] = value;
        return frame;
    }

    public static byte[] State(bool ready) => Build(FrameType.State, [ready ? (byte)1 : (byte)0]);

    /// <summary>Builds a frame without payload (SAS, LOCK, RELEASE_ALL).</summary>
    public static byte[] Empty(FrameType type) => Build(type, []);

    public static byte[] Ping(uint sequence) => Build(FrameType.Ping, BigEndian(sequence));

    public static byte[] Pong(uint sequence) => Build(FrameType.Pong, BigEndian(sequence));

    public static byte[] Desktop(bool writable, string name) =>
        Build(FrameType.Desktop, [writable ? (byte)1 : (byte)0, .. Encoding.UTF8.GetBytes(name)]);

    public static byte[] KeyDone(long timestamp)
    {
        var frame = new byte[KEY_DONE_SIZE];
        WriteKeyDone(frame, timestamp);
        return frame;
    }

    /// <summary>Writes a KEY_DONE frame into a caller buffer of KEY_DONE_SIZE bytes.</summary>
    public static void WriteKeyDone(Span<byte> destination, long timestamp)
    {
        BinaryPrimitives.WriteUInt16BigEndian(destination, KEY_DONE_SIZE - Frame.HEADER_SIZE);
        destination[Frame.HEADER_SIZE] = (byte)FrameType.KeyDone;
        BinaryPrimitives.WriteInt64BigEndian(destination[(Frame.HEADER_SIZE + 1)..], timestamp);
    }

    public static (ushort Code, byte Value) ReadKey(ReadOnlySpan<byte> payload) => (BinaryPrimitives.ReadUInt16BigEndian(payload), payload[2]);

    public static uint ReadSeq(ReadOnlySpan<byte> payload) => BinaryPrimitives.ReadUInt32BigEndian(payload);

    public static long ReadKeyDone(ReadOnlySpan<byte> payload) => BinaryPrimitives.ReadInt64BigEndian(payload);

    public static (bool Writable, string Name) ReadDesktop(ReadOnlySpan<byte> payload) => (payload[0] == 1, Encoding.UTF8.GetString(payload[1..]));

    private static byte[] BigEndian(uint value)
    {
        var bytes = new byte[sizeof(uint)];
        BinaryPrimitives.WriteUInt32BigEndian(bytes, value);
        return bytes;
    }

    private static byte[] Build(FrameType type, ReadOnlySpan<byte> payload)
    {
        if (payload.Length >= Frame.MAX_LENGTH) throw new ArgumentOutOfRangeException(nameof(payload));
        var frame = new byte[Frame.HEADER_SIZE + 1 + payload.Length];
        BinaryPrimitives.WriteUInt16BigEndian(frame, (ushort)(1 + payload.Length));
        frame[Frame.HEADER_SIZE] = (byte)type;
        payload.CopyTo(frame.AsSpan(Frame.HEADER_SIZE + 1));
        return frame;
    }
}
```

- [ ] **Step 6: Testlerin geçtiğini gör**

Run: `dotnet test KeyRelay.slnx --filter "FullyQualifiedName~KeyRelay.Tests.Protocol"`
Expected: tüm Protocol testleri geçer, uyarı yok. Ardından tüm çözüm: `dotnet test KeyRelay.slnx` → hepsi geçer.

- [ ] **Step 7: `docs/protocol.md` güncelle**

"Eşleştirme, kimlik doğrulama ve canlılık" bölümünün ilk maddesinin altına ekle:
```markdown
- `n` (PC adı) yüzde kodlanır (RFC 3986); `a` listesindeki adresler kodlanmaz (yalnız harf, rakam, `.`, `-`, `:`), ayırıcı virgüldür.
- `PAIRED`'dan sonra istemci aynı bağlantıda `HELLO` gönderebilir; bağlantıyı kapatıp yeni bağlantıda `HELLO` göndermesi de geçerlidir. HELLO bütçesi (5 sn) bağlantının açılışından sayılır. `REJECT`'ten sonra sunucu bağlantıyı kapatır.
- Tek aktif istemci: yeni bağlantı `HELLO` ile doğrulanınca eski bağlantı kapanır ve servis ajana `RELEASE_ALL` gönderir (eski bağlantıdan basılı kalmış tuşlar).
```
"Servis ↔ ajan pipe mesajları" tablosunu şununla değiştir ve altındaki maddelere iki madde ekle:
```markdown
| Yön | Tip | Yük | Anlam |
|---|---|---|---|
| S→A | `0x10 KEY` | `u16 evdev keycode, u8 değer` | Tuşu bas/bırak/tekrarla |
| S→A | `0x13 LOCK` | — | `LockWorkStation` |
| S→A | `0x30 RELEASE_ALL` | — | Ajanın tuttuğu tüm basılı tuşları bırak (istemci koptu) |
| A→S | `0x31 DESKTOP` | `u8 yazılabilir (0/1), utf8 masaüstü adı` | Yazılabilirlik veya giriş masaüstünün adı değişti |
| A→S | `0x32 KEY_DONE` | `u64 zaman damgası` | Son KEY için `SendInput` döndü (QueryPerformanceCounter); servis gecikme ölçümü (bitiş 7) için TLS okuma anıyla sırayla eşler |

- Ajan, pipe sunucusunun süreç kimliğinin komut satırında aldığı servis pid'i olduğunu doğrular; değilse tuş basmadan çıkar. Servis, istemcinin kendi başlattığı ajan olduğunu doğrular.
- Ajan her KEY için (tuş eşlenemese de) bir `KEY_DONE` gönderir; sıralama bozulmaz.
```

- [ ] **Step 8: Commit**

```powershell
git -C $WT add windows/src/KeyRelay.Protocol windows/KeyRelay.slnx windows/src/KeyRelay.Service/KeyRelay.Service.csproj windows/tests protocol/test-vectors/frames.json docs/protocol.md .editorconfig
git -C $WT commit -m "feat(windows): tablet ile pc arasındaki mesajlar okunup yazılabilir oldu" -m "- iki platformun ortak örnek mesajları windows tarafında da doğrulanır
- bozuk, eksik ya da fazla uzun mesajlar bağlantıyı kapatır
- servis ile yardımcı süreç arasındaki mesajlar belgelendi" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Linux tuş kodu → set-1 scancode tablosu

**Files:**
- Create: `windows/src/KeyRelay.Protocol/ScancodeMap.cs`
- Test: `windows/tests/KeyRelay.Tests/Protocol/ScancodeMapTests.cs`

**Interfaces:**
- Produces: `public static class ScancodeMap { public static bool TryGet(ushort code, out ushort scancode, out bool extended); }` — 1..88 aynen (KEY_102ND = 86 dahil), diğerleri prototipin `kbdrecv.py` EXTENDED tablosu (E0 önekli), gerisi `false`.

- [ ] **Step 1: Failing testleri yaz**

```csharp
using KeyRelay.Protocol;

namespace KeyRelay.Tests.Protocol;

[TestClass]
public sealed class ScancodeMapTests
{
    [TestMethod]
    [DataRow((ushort)30, DisplayName = "KEY_A")]
    [DataRow((ushort)15, DisplayName = "KEY_TAB")]
    [DataRow((ushort)56, DisplayName = "KEY_LEFTALT")]
    [DataRow((ushort)23, DisplayName = "Turkish Q dotless i")]
    [DataRow((ushort)26, DisplayName = "Turkish Q g with breve")]
    [DataRow((ushort)27, DisplayName = "Turkish Q u with diaeresis")]
    [DataRow((ushort)39, DisplayName = "Turkish Q s with cedilla")]
    [DataRow((ushort)40, DisplayName = "Turkish Q dotted i")]
    [DataRow((ushort)51, DisplayName = "Turkish Q o with diaeresis")]
    [DataRow((ushort)52, DisplayName = "Turkish Q c with cedilla")]
    [DataRow((ushort)86, DisplayName = "KEY_102ND")]
    [DataRow((ushort)88, DisplayName = "KEY_F12")]
    public void TryGet_DirectRange_ReturnsSameCodeNotExtended(ushort code)
    {
        var found = ScancodeMap.TryGet(code, out var scancode, out var extended);

        Assert.IsTrue(found);
        Assert.AreEqual(code, scancode);
        Assert.IsFalse(extended);
    }

    [TestMethod]
    [DataRow((ushort)100, (ushort)0x38, DisplayName = "KEY_RIGHTALT (AltGr)")]
    [DataRow((ushort)97, (ushort)0x1D, DisplayName = "KEY_RIGHTCTRL")]
    [DataRow((ushort)125, (ushort)0x5B, DisplayName = "KEY_LEFTMETA (Win)")]
    [DataRow((ushort)126, (ushort)0x5C, DisplayName = "KEY_RIGHTMETA")]
    [DataRow((ushort)111, (ushort)0x53, DisplayName = "KEY_DELETE")]
    [DataRow((ushort)99, (ushort)0x37, DisplayName = "KEY_SYSRQ (Print Screen)")]
    [DataRow((ushort)113, (ushort)0x20, DisplayName = "KEY_MUTE")]
    [DataRow((ushort)114, (ushort)0x2E, DisplayName = "KEY_VOLUMEDOWN")]
    [DataRow((ushort)115, (ushort)0x30, DisplayName = "KEY_VOLUMEUP")]
    [DataRow((ushort)163, (ushort)0x19, DisplayName = "KEY_NEXTSONG")]
    [DataRow((ushort)164, (ushort)0x22, DisplayName = "KEY_PLAYPAUSE")]
    [DataRow((ushort)165, (ushort)0x10, DisplayName = "KEY_PREVIOUSSONG")]
    [DataRow((ushort)166, (ushort)0x24, DisplayName = "KEY_STOPCD")]
    public void TryGet_ExtendedKey_ReturnsE0Scancode(ushort code, ushort expected)
    {
        var found = ScancodeMap.TryGet(code, out var scancode, out var extended);

        Assert.IsTrue(found);
        Assert.AreEqual(expected, scancode);
        Assert.IsTrue(extended);
    }

    [TestMethod]
    [DataRow((ushort)0, DisplayName = "KEY_RESERVED")]
    [DataRow((ushort)89, DisplayName = "KEY_RO")]
    [DataRow((ushort)119, DisplayName = "KEY_PAUSE (E1 sequence, not supported)")]
    [DataRow((ushort)240, DisplayName = "KEY_UNKNOWN")]
    [DataRow(ushort.MaxValue, DisplayName = "max")]
    public void TryGet_UnmappedKey_ReturnsFalse(ushort code)
    {
        Assert.IsFalse(ScancodeMap.TryGet(code, out _, out _));
    }
}
```

- [ ] **Step 2: Başarısız olduğunu gör**

Run: `dotnet test KeyRelay.slnx --filter "FullyQualifiedName~ScancodeMapTests"` → derleme hatası (`ScancodeMap` yok).

- [ ] **Step 3: `ScancodeMap`**

```csharp
namespace KeyRelay.Protocol;

/// <summary>Maps Linux evdev key codes to PC set-1 scancodes for SendInput.</summary>
public static class ScancodeMap
{
    // Linux KEY_* codes 1..88 equal set-1 scancodes; the rest below are E0-prefixed keys (prototype kbdrecv.py table).
    private const ushort LAST_DIRECT = 88;

    /// <summary>Looks up the scancode of a Linux key code.</summary>
    /// <param name="code">evdev key code from a KEY frame.</param>
    /// <param name="scancode">Set-1 scancode without the E0 prefix.</param>
    /// <param name="extended">True for E0-prefixed keys (KEYEVENTF_EXTENDEDKEY).</param>
    /// <returns>false for keys without a set-1 equivalent; such keys are dropped.</returns>
    public static bool TryGet(ushort code, out ushort scancode, out bool extended)
    {
        if (code is >= 1 and <= LAST_DIRECT)
        {
            scancode = code;
            extended = false;
            return true;
        }
        scancode = code switch
        {
            96 => 0x1C, // KP enter
            97 => 0x1D, // right Ctrl
            98 => 0x35, // KP slash
            99 => 0x37, // Print Screen
            100 => 0x38, // right Alt (AltGr)
            102 => 0x47, // Home
            103 => 0x48, // Up
            104 => 0x49, // Page Up
            105 => 0x4B, // Left
            106 => 0x4D, // Right
            107 => 0x4F, // End
            108 => 0x50, // Down
            109 => 0x51, // Page Down
            110 => 0x52, // Insert
            111 => 0x53, // Delete
            113 => 0x20, // Mute
            114 => 0x2E, // Volume down
            115 => 0x30, // Volume up
            125 => 0x5B, // left Win
            126 => 0x5C, // right Win
            127 => 0x5D, // Menu
            163 => 0x19, // Next track
            164 => 0x22, // Play/Pause
            165 => 0x10, // Previous track
            166 => 0x24, // Stop
            _ => 0,
        };
        extended = scancode != 0;
        return extended;
    }
}
```

- [ ] **Step 4: Testlerin geçtiğini gör**

Run: `dotnet test KeyRelay.slnx --filter "FullyQualifiedName~ScancodeMapTests"` → 30 test geçer.

- [ ] **Step 5: Commit**

```powershell
git -C $WT add windows/src/KeyRelay.Protocol/ScancodeMap.cs windows/tests/KeyRelay.Tests/Protocol/ScancodeMapTests.cs
git -C $WT commit -m "feat(windows): tablet tuş kodları windows tuşlarına çevrilir oldu" -m "- türkçe q harfleri, altgr, win ve medya tuşları doğru tuşa karşılık gelir
- karşılığı olmayan tuşlar sessizce atlanır" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Ajan yazılabilirlik kuralı (`KeyInjector`) — sahte masaüstü arkasında

T1'in sonucu "DUR" ise bu görev başlamaz. T1 "bayat masaüstünde 1" gösterdiyse olay başına `AttachToInputDesktop(false)` zorunludur (aşağıdaki kod zaten öyle yapar); gösterdiği satırı görev raporuna yaz.

**Files:**
- Create: `windows/src/KeyRelay.Service/Agent/IInputDesktop.cs`, `windows/src/KeyRelay.Service/Agent/KeyInjector.cs`
- Modify: `windows/src/KeyRelay.Service/Native/User32.cs` (`KEYEVENTF_EXTENDEDKEY`)
- Test: `windows/tests/KeyRelay.Tests/Agent/FakeDesktop.cs`, `windows/tests/KeyRelay.Tests/Agent/KeyInjectorTests.cs`

**Interfaces:**
- Consumes: T3 `ScancodeMap.TryGet`.
- Produces:
  - `internal interface IInputDesktop { string InputDesktopName(); bool AttachToInputDesktop(bool force); bool SendKey(ushort scancode, uint flags); bool LockWorkStation(); }`
  - `internal sealed class KeyInjector(IInputDesktop desktop, Action<bool, string> report)`: `void Inject(ushort code, byte value)`, `void Watch()` (500 ms'de bir), `void ReleaseAll()`, `void ReleaseModifiers()`, `bool Writable`. `report(writable, desktopName)` yazılabilirlik veya masaüstü adı değişince çağrılır (T5 bunu DESKTOP çerçevesine çevirir).
  - `User32.KEYEVENTF_EXTENDEDKEY = 0x0001`.
- Test yardımcısı (T5 de kullanır): `internal sealed class FakeDesktop : IInputDesktop` — `Input`, `Reject(string)`, `RejectNext(int)`, `SentSnapshot()`, `ForcedAttaches`, `Locks`.

- [ ] **Step 1: Failing testleri yaz**

`windows/tests/KeyRelay.Tests/Agent/FakeDesktop.cs`:
```csharp
using KeyRelay.Service.Agent;

namespace KeyRelay.Tests.Agent;

/// <summary>Input desktop stand-in: SendKey succeeds only on the current input desktop and when that desktop accepts input.</summary>
internal sealed class FakeDesktop : IInputDesktop
{
    private readonly Lock _gate = new();
    private readonly HashSet<string> _rejecting = [];
    private readonly List<(ushort Scancode, uint Flags, string Desktop)> _sent = [];
    private string _input = "Default";
    private string _attached = "Default";
    private int _rejectNext;
    private int _forcedAttaches;
    private int _locks;

    internal string Input
    {
        get { lock (_gate) return _input; }
        set { lock (_gate) _input = value; }
    }

    internal int ForcedAttaches { get { lock (_gate) return _forcedAttaches; } }

    internal int Locks { get { lock (_gate) return _locks; } }

    /// <summary>Makes SendKey fail on this desktop even when the thread is attached to it.</summary>
    internal void Reject(string desktop) { lock (_gate) _rejecting.Add(desktop); }

    /// <summary>Makes the next <paramref name="count"/> SendKey calls fail.</summary>
    internal void RejectNext(int count) { lock (_gate) _rejectNext = count; }

    internal (ushort Scancode, uint Flags, string Desktop)[] SentSnapshot() { lock (_gate) return [.. _sent]; }

    internal void ClearSent() { lock (_gate) _sent.Clear(); }

    public string InputDesktopName() { lock (_gate) return _input; }

    public bool AttachToInputDesktop(bool force)
    {
        lock (_gate)
        {
            if (force) _forcedAttaches++;
            _attached = _input;
            return true;
        }
    }

    public bool SendKey(ushort scancode, uint flags)
    {
        lock (_gate)
        {
            if (_rejectNext > 0)
            {
                _rejectNext--;
                return false;
            }
            if (_attached != _input || _rejecting.Contains(_attached)) return false;
            _sent.Add((scancode, flags, _attached));
            return true;
        }
    }

    public bool LockWorkStation()
    {
        lock (_gate) _locks++;
        return true;
    }
}
```

`windows/tests/KeyRelay.Tests/Agent/KeyInjectorTests.cs`:
```csharp
using KeyRelay.Service.Agent;
using KeyRelay.Service.Native;

namespace KeyRelay.Tests.Agent;

[TestClass]
public sealed class KeyInjectorTests
{
    private const ushort KEY_A = 30;
    private const ushort KEY_DELETE = 111;
    private const ushort KEY_RIGHTALT = 100;
    private const ushort KEY_UNKNOWN = 240;
    private const uint DOWN = User32.KEYEVENTF_SCANCODE;
    private const uint UP = User32.KEYEVENTF_SCANCODE | User32.KEYEVENTF_KEYUP;
    private const uint EXTENDED = User32.KEYEVENTF_EXTENDEDKEY;

    private readonly FakeDesktop _desktop = new();
    private readonly List<(bool Writable, string Desktop)> _reports = [];

    private KeyInjector Create() => new(_desktop, (writable, desktop) => _reports.Add((writable, desktop)));

    private KeyInjector CreateStuckOnWinlogon()
    {
        var injector = Create();
        _desktop.Input = "Winlogon";
        _desktop.Reject("Winlogon");
        injector.Inject(KEY_A, 1);
        _desktop.ClearSent();
        _reports.Clear();
        return injector;
    }

    [TestMethod]
    public void Inject_KeyDown_SendsOneScancodeEvent()
    {
        var injector = Create();

        injector.Inject(KEY_A, 1);

        CollectionAssert.AreEqual(new[] { ((ushort)0x1E, DOWN, "Default") }, _desktop.SentSnapshot());
    }

    [TestMethod]
    public void Inject_RightAlt_SetsExtendedFlag()
    {
        var injector = Create();

        injector.Inject(KEY_RIGHTALT, 1);

        CollectionAssert.AreEqual(new[] { ((ushort)0x38, DOWN | EXTENDED, "Default") }, _desktop.SentSnapshot());
    }

    [TestMethod]
    public void Inject_KeyUp_SetsKeyUpFlag()
    {
        var injector = Create();

        injector.Inject(KEY_A, 0);

        CollectionAssert.AreEqual(new[] { ((ushort)0x1E, UP, "Default") }, _desktop.SentSnapshot());
    }

    [TestMethod]
    public void Inject_Repeat_SendsKeyDownAgain()
    {
        var injector = Create();

        injector.Inject(KEY_A, 1);
        injector.Inject(KEY_A, 2);

        CollectionAssert.AreEqual(new[] { ((ushort)0x1E, DOWN, "Default"), ((ushort)0x1E, DOWN, "Default") }, _desktop.SentSnapshot());
    }

    [TestMethod]
    public void Inject_UnknownCode_SendsNothing()
    {
        var injector = Create();

        injector.Inject(KEY_UNKNOWN, 1);

        Assert.IsEmpty(_desktop.SentSnapshot());
    }

    [TestMethod]
    public void Inject_InputDesktopChanged_SendsOnNewDesktopWithoutRetry()
    {
        var injector = Create();
        _desktop.Input = "Winlogon";

        injector.Inject(KEY_A, 1);

        CollectionAssert.AreEqual(new[] { ((ushort)0x1E, DOWN, "Winlogon") }, _desktop.SentSnapshot());
        Assert.AreEqual(0, _desktop.ForcedAttaches);
        Assert.IsEmpty(_reports);
    }

    [TestMethod]
    public void Inject_FirstAttemptRejected_RetriesOnceAndStaysWritable()
    {
        var injector = Create();
        _desktop.RejectNext(1);

        injector.Inject(KEY_A, 1);

        Assert.HasCount(1, _desktop.SentSnapshot());
        Assert.AreEqual(1, _desktop.ForcedAttaches);
        Assert.IsEmpty(_reports);
        Assert.IsTrue(injector.Writable);
    }

    [TestMethod]
    public void Inject_RejectedTwiceOnCurrentDesktop_ReportsNotWritableOnce()
    {
        var injector = Create();
        _desktop.Input = "Winlogon";
        _desktop.Reject("Winlogon");

        injector.Inject(KEY_A, 1);
        injector.Inject(KEY_A, 0);

        CollectionAssert.AreEqual(new[] { (false, "Winlogon") }, _reports);
        Assert.IsFalse(injector.Writable);
    }

    [TestMethod]
    public void Watch_SameDesktopWhileStuck_StaysNotWritableAndSilent()
    {
        var injector = CreateStuckOnWinlogon();

        injector.Watch();
        injector.Watch();

        Assert.IsEmpty(_reports);
        Assert.IsFalse(injector.Writable);
    }

    [TestMethod]
    public void Watch_DesktopNameChangesAfterStuck_ReleasesModifiersAndReportsWritable()
    {
        var injector = CreateStuckOnWinlogon();
        _desktop.Input = "Default";

        injector.Watch();

        CollectionAssert.AreEqual(new[] { (true, "Default") }, _reports);
        var sent = _desktop.SentSnapshot();
        Assert.HasCount(8, sent);
        Assert.IsTrue(sent.All(s => (s.Flags & User32.KEYEVENTF_KEYUP) != 0));
        Assert.IsTrue(injector.Writable);
    }

    [TestMethod]
    public void Watch_DesktopChangesWhileWritable_ReportsNameAndInjectsNothing()
    {
        var injector = Create();

        injector.Watch();
        _desktop.Input = "Winlogon";
        injector.Watch();

        CollectionAssert.AreEqual(new[] { (true, "Default"), (true, "Winlogon") }, _reports);
        Assert.IsEmpty(_desktop.SentSnapshot());
    }

    [TestMethod]
    public void Watch_RejectingDesktopWhileWritable_NeverReportsNotWritable()
    {
        var injector = Create();
        _desktop.Input = "Winlogon";
        _desktop.Reject("Winlogon");

        injector.Watch();
        injector.Watch();

        Assert.IsTrue(_reports.All(r => r.Writable));
    }

    [TestMethod]
    public void ReleaseAll_HeldKeys_ReleasesThemAndModifiers()
    {
        var injector = Create();
        injector.Inject(KEY_A, 1);
        injector.Inject(KEY_DELETE, 1);
        _desktop.ClearSent();

        injector.ReleaseAll();
        var first = _desktop.SentSnapshot();
        _desktop.ClearSent();
        injector.ReleaseAll();
        var second = _desktop.SentSnapshot();

        Assert.HasCount(10, first);
        Assert.Contains(((ushort)0x1E, UP, "Default"), first);
        Assert.Contains(((ushort)0x53, UP | EXTENDED, "Default"), first);
        Assert.HasCount(8, second);
    }

    [TestMethod]
    public void ReleaseAll_WhileStuck_ReportsWritable()
    {
        var injector = CreateStuckOnWinlogon();

        injector.ReleaseAll();

        CollectionAssert.AreEqual(new[] { (true, "Winlogon") }, _reports);
        Assert.IsTrue(injector.Writable);
    }

    [TestMethod]
    public void ReleaseModifiers_SendsLeftAndRightCtrlAltShiftWinUp()
    {
        var injector = Create();

        injector.ReleaseModifiers();

        CollectionAssert.AreEqual(
            new[]
            {
                ((ushort)0x1D, UP, "Default"), ((ushort)0x1D, UP | EXTENDED, "Default"),
                ((ushort)0x38, UP, "Default"), ((ushort)0x38, UP | EXTENDED, "Default"),
                ((ushort)0x2A, UP, "Default"), ((ushort)0x36, UP, "Default"),
                ((ushort)0x5B, UP | EXTENDED, "Default"), ((ushort)0x5C, UP | EXTENDED, "Default"),
            },
            _desktop.SentSnapshot());
    }

    [TestMethod]
    public void ReleaseAll_KeyAlreadyReleased_SendsOnlyModifiers()
    {
        var injector = Create();
        injector.Inject(KEY_A, 1);
        injector.Inject(KEY_A, 0);
        _desktop.ClearSent();

        injector.ReleaseAll();

        Assert.HasCount(8, _desktop.SentSnapshot());
    }
}
```

- [ ] **Step 2: Başarısız olduğunu gör**

Run: `dotnet test KeyRelay.slnx --filter "FullyQualifiedName~KeyInjectorTests"` → derleme hatası (`IInputDesktop`, `KeyInjector`, `KEYEVENTF_EXTENDEDKEY` yok).

- [ ] **Step 3: Uygulamayı yaz**

`Native/User32.cs` içinde `KEYEVENTF_KEYUP` satırının üstüne ekle:
```csharp
    internal const uint KEYEVENTF_EXTENDEDKEY = 0x0001;
```

`windows/src/KeyRelay.Service/Agent/IInputDesktop.cs`:
```csharp
namespace KeyRelay.Service.Agent;

/// <summary>The user32 calls the agent needs, behind an interface so the writability rule can be tested without a desktop.</summary>
internal interface IInputDesktop
{
    /// <summary>Gets the name of the current input desktop (Default, Winlogon, Screen-saver).</summary>
    /// <returns>The name, or an empty string when the input desktop cannot be opened.</returns>
    string InputDesktopName();

    /// <summary>Moves the calling thread to the current input desktop.</summary>
    /// <param name="force">Reopen and switch even when the thread already seems to be there.</param>
    /// <returns>true when the calling thread is on the current input desktop afterwards.</returns>
    bool AttachToInputDesktop(bool force);

    /// <summary>Calls SendInput with exactly one keyboard event.</summary>
    /// <returns>true when Windows accepted the event.</returns>
    bool SendKey(ushort scancode, uint flags);

    /// <summary>Locks the workstation (an injected Win+L is ignored by Windows).</summary>
    bool LockWorkStation();
}
```

`windows/src/KeyRelay.Service/Agent/KeyInjector.cs`:
```csharp
using KeyRelay.Protocol;
using KeyRelay.Service.Native;

namespace KeyRelay.Service.Agent;

/// <summary>
/// Injects key events on the input desktop and decides whether that desktop accepts input (spec "Ajan modu").
/// Writable turns false only when an event was rejected, retried on the freshly opened input desktop and rejected again;
/// it turns true again when the input desktop name differs from the one stored then, or on RELEASE_ALL.
/// </summary>
/// <param name="desktop">user32 access.</param>
/// <param name="report">Called with (writable, desktop name) when either changes.</param>
internal sealed class KeyInjector(IInputDesktop desktop, Action<bool, string> report)
{
    private const int EXTENDED_BIT = 0x100;
    // Left/right Ctrl, Alt, Shift and Win as set-1 scancodes; EXTENDED_BIT marks E0-prefixed keys.
    private static readonly int[] MODIFIERS =
        [0x1D, 0x1D | EXTENDED_BIT, 0x38, 0x38 | EXTENDED_BIT, 0x2A, 0x36, 0x5B | EXTENDED_BIT, 0x5C | EXTENDED_BIT];

    private readonly Lock _gate = new();
    private readonly bool[] _held = new bool[2 * EXTENDED_BIT];
    private bool _writable = true;
    private string? _stuckDesktop;
    private string? _lastDesktop;

    /// <summary>Gets whether the input desktop currently counts as writable.</summary>
    internal bool Writable { get { lock (_gate) return _writable; } }

    /// <summary>Injects one evdev key event.</summary>
    /// <param name="code">evdev key code.</param>
    /// <param name="value">0 release, 1 press, 2 repeat.</param>
    internal void Inject(ushort code, byte value)
    {
        if (!ScancodeMap.TryGet(code, out var scancode, out var extended)) return;
        var key = scancode | (extended ? EXTENDED_BIT : 0);
        lock (_gate)
        {
            _held[key] = value != 0;
            Send(key, up: value == 0);
        }
    }

    /// <summary>Runs every 500 ms: reports desktop name changes and lifts a stuck "not writable" once the name changes.</summary>
    internal void Watch()
    {
        var name = desktop.InputDesktopName();
        lock (_gate)
        {
            var changed = !string.Equals(name, _lastDesktop, StringComparison.Ordinal);
            _lastDesktop = name;
            if (!_writable && !string.Equals(name, _stuckDesktop, StringComparison.Ordinal))
            {
                ReleaseModifiersLocked();
                MakeWritable(name);
            }
            else if (changed && _writable)
            {
                report(true, name);
            }
        }
    }

    /// <summary>Releases every key this agent pressed plus all modifiers and lifts a stuck "not writable" (client disconnected).</summary>
    internal void ReleaseAll()
    {
        lock (_gate)
        {
            ReleaseModifiersLocked();
            for (var key = 0; key < _held.Length; key++)
            {
                if (!_held[key]) continue;
                _held[key] = false;
                Send(key, up: true);
            }
            if (!_writable) MakeWritable(desktop.InputDesktopName());
        }
    }

    /// <summary>Sends key-up for left and right Ctrl, Alt, Shift and Win.</summary>
    internal void ReleaseModifiers()
    {
        lock (_gate) ReleaseModifiersLocked();
    }

    private void ReleaseModifiersLocked()
    {
        foreach (var key in MODIFIERS)
        {
            _held[key] = false;
            Send(key, up: true);
        }
    }

    private void Send(int key, bool up)
    {
        var flags = User32.KEYEVENTF_SCANCODE
            | (key >= EXTENDED_BIT ? User32.KEYEVENTF_EXTENDEDKEY : 0)
            | (up ? User32.KEYEVENTF_KEYUP : 0);
        var scancode = (ushort)(key & 0xFF);
        desktop.AttachToInputDesktop(force: false);
        if (desktop.SendKey(scancode, flags)) return;
        // SendInput refuses input aimed at a desktop that stopped being the input desktop: reopen it and retry once.
        if (!desktop.AttachToInputDesktop(force: true) || desktop.SendKey(scancode, flags) || !_writable) return;
        _writable = false;
        _stuckDesktop = desktop.InputDesktopName();
        report(false, _stuckDesktop);
    }

    private void MakeWritable(string name)
    {
        _writable = true;
        _stuckDesktop = null;
        report(true, name);
    }
}
```

- [ ] **Step 4: Testlerin geçtiğini gör**

Run: `dotnet test KeyRelay.slnx --filter "FullyQualifiedName~KeyInjectorTests"` → 16 test geçer. Tüm çözüm: `dotnet test KeyRelay.slnx` → hepsi geçer.

- [ ] **Step 5: Commit**

```powershell
git -C $WT add windows/src/KeyRelay.Service/Agent windows/src/KeyRelay.Service/Native/User32.cs windows/tests/KeyRelay.Tests/Agent
git -C $WT commit -m "feat(windows): kilit ve güvenlik ekranında tuş gönderme kuralı eklendi" -m "- tuş kabul edilmezse bir kez daha denenir, yine olmazsa tablet uyarılır
- ekran değişince veya bağlantı yenilenince yazma yeniden açılır
- takılı kalan ctrl, alt, shift ve win tuşları bırakılır" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Ajan modu (`--agent`), user32 bağlantısı, prob kodunun silinmesi

**Files:**
- Create: `windows/src/KeyRelay.Service/Agent/AgentHost.cs`, `windows/src/KeyRelay.Service/Agent/NativeInputDesktop.cs`
- Modify: `windows/src/KeyRelay.Service/Native/User32.cs`, `windows/src/KeyRelay.Service/Native/Kernel32.cs`, `windows/src/KeyRelay.Service/Program.cs`
- Delete: `windows/src/KeyRelay.Service/Probe/AgentProbe.cs`, `windows/src/KeyRelay.Service/Probe/SasProbe.cs`
- Test: `windows/tests/KeyRelay.Tests/Agent/AgentHostTests.cs`, `windows/tests/KeyRelay.Tests/TestSupport/Eventually.cs`, Modify `windows/tests/KeyRelay.Tests/NativeLayoutTests.cs`

**Interfaces:**
- Consumes: T2 `FrameReader`, `Frames`; T4 `IInputDesktop`, `KeyInjector`.
- Produces:
  - `internal sealed class AgentHost(string pipeName, int servicePid, IInputDesktop desktop) : IDisposable` — `const string PIPE_NAME = "KeyRelay.Agent"`, `const int EXIT_OK = 0, EXIT_BAD_ARGUMENTS = 2, EXIT_UNTRUSTED_SERVER = 3, EXIT_PROTOCOL = 4, EXIT_NO_SERVICE = 5`; `static int Main(string[] args)` (`--agent <pid>`); `int Run()`.
  - `internal sealed class NativeInputDesktop : IInputDesktop`.
  - `Kernel32.GetNamedPipeServerProcessId(SafePipeHandle, out uint)`, `Kernel32.GetNamedPipeClientProcessId(SafePipeHandle, out uint)` (T6 kullanır).
  - `User32.DESKTOP_READOBJECTS`, `User32.DesktopNameEquals(nint, string)`.
  - Test yardımcısı: `internal static class Eventually { static Task TrueAsync(Func<bool> condition, string message) }`.

- [ ] **Step 1: Failing testleri yaz**

`windows/tests/KeyRelay.Tests/TestSupport/Eventually.cs`:
```csharp
using System.Diagnostics;

namespace KeyRelay.Tests.TestSupport;

/// <summary>Polls a condition that another thread makes true.</summary>
internal static class Eventually
{
    private static readonly TimeSpan TIMEOUT = TimeSpan.FromSeconds(5);

    internal static async Task TrueAsync(Func<bool> condition, string message)
    {
        var started = Stopwatch.GetTimestamp();
        while (!condition())
        {
            if (Stopwatch.GetElapsedTime(started) > TIMEOUT) Assert.Fail(message);
            await Task.Delay(20);
        }
    }
}
```

`windows/tests/KeyRelay.Tests/Agent/AgentHostTests.cs`:
```csharp
using System.IO.Pipes;
using KeyRelay.Protocol;
using KeyRelay.Service.Agent;
using KeyRelay.Service.Native;
using KeyRelay.Tests.TestSupport;

namespace KeyRelay.Tests.Agent;

[TestClass]
public sealed class AgentHostTests
{
    private const ushort KEY_A = 30;
    private static readonly TimeSpan TIMEOUT = TimeSpan.FromSeconds(5);

    [TestMethod]
    public async Task Run_KeyFrame_InjectsAndAnswersKeyDone()
    {
        var (pipe, server) = CreateServer();
        var desktop = new FakeDesktop();
        using var host = new AgentHost(pipe, Environment.ProcessId, desktop);
        var run = Start(host);
        await using (server)
        {
            await server.WaitForConnectionAsync().WaitAsync(TIMEOUT);
            var reader = new FrameReader(server, FrameChannel.Pipe);

            await server.WriteAsync(Frames.Key(KEY_A, 1));
            var answer = await ReadSkippingDesktopAsync(reader);

            Assert.AreEqual(FrameType.KeyDone, answer);
            Assert.Contains(((ushort)0x1E, User32.KEYEVENTF_SCANCODE, "Default"), desktop.SentSnapshot());
        }
        Assert.AreEqual(AgentHost.EXIT_OK, await run.WaitAsync(TIMEOUT));
    }

    [TestMethod]
    public async Task Run_ServerIsNotTheService_ExitsWithoutInjecting()
    {
        var (pipe, server) = CreateServer();
        var desktop = new FakeDesktop();
        using var host = new AgentHost(pipe, Environment.ProcessId + 1, desktop);
        var run = Start(host);
        await using (server)
        {
            await server.WaitForConnectionAsync().WaitAsync(TIMEOUT);

            var exit = await run.WaitAsync(TIMEOUT);

            Assert.AreEqual(AgentHost.EXIT_UNTRUSTED_SERVER, exit);
            Assert.IsEmpty(desktop.SentSnapshot());
        }
    }

    [TestMethod]
    public async Task Run_Start_ReleasesModifiersFirst()
    {
        var (pipe, server) = CreateServer();
        var desktop = new FakeDesktop();
        using var host = new AgentHost(pipe, Environment.ProcessId, desktop);
        _ = Start(host);
        await using (server)
        {
            await server.WaitForConnectionAsync().WaitAsync(TIMEOUT);

            await Eventually.TrueAsync(() => desktop.SentSnapshot().Length >= 8, "modifiers were not released");

            Assert.IsTrue(desktop.SentSnapshot().Take(8).All(s => (s.Flags & User32.KEYEVENTF_KEYUP) != 0));
        }
    }

    [TestMethod]
    public async Task Run_ReleaseAll_ReleasesHeldKey()
    {
        var (pipe, server) = CreateServer();
        var desktop = new FakeDesktop();
        using var host = new AgentHost(pipe, Environment.ProcessId, desktop);
        _ = Start(host);
        await using (server)
        {
            await server.WaitForConnectionAsync().WaitAsync(TIMEOUT);
            var reader = new FrameReader(server, FrameChannel.Pipe);
            await server.WriteAsync(Frames.Key(KEY_A, 1));
            await ReadSkippingDesktopAsync(reader);

            await server.WriteAsync(Frames.ReleaseAllFrame);

            await Eventually.TrueAsync(
                () => desktop.SentSnapshot().Contains(((ushort)0x1E, User32.KEYEVENTF_SCANCODE | User32.KEYEVENTF_KEYUP, "Default")),
                "held key was not released");
        }
    }

    [TestMethod]
    public async Task Run_Lock_LocksWorkStation()
    {
        var (pipe, server) = CreateServer();
        var desktop = new FakeDesktop();
        using var host = new AgentHost(pipe, Environment.ProcessId, desktop);
        _ = Start(host);
        await using (server)
        {
            await server.WaitForConnectionAsync().WaitAsync(TIMEOUT);

            await server.WriteAsync(Frames.LockFrame);

            await Eventually.TrueAsync(() => desktop.Locks == 1, "workstation was not locked");
        }
    }

    [TestMethod]
    public async Task Run_InputDesktopChanges_SendsDesktopFrameWithName()
    {
        var (pipe, server) = CreateServer();
        var desktop = new FakeDesktop();
        using var host = new AgentHost(pipe, Environment.ProcessId, desktop);
        _ = Start(host);
        await using (server)
        {
            await server.WaitForConnectionAsync().WaitAsync(TIMEOUT);
            var reader = new FrameReader(server, FrameChannel.Pipe);
            desktop.Input = "Winlogon";

            var seen = new List<(bool, string)>();
            using var timeout = new CancellationTokenSource(TIMEOUT);
            while (!seen.Contains((true, "Winlogon")))
            {
                var frame = await reader.ReadAsync(timeout.Token);
                if (frame?.Type == FrameType.Desktop) seen.Add(Frames.ReadDesktop(frame.Value.Payload.Span));
            }

            Assert.Contains((true, "Winlogon"), seen);
        }
    }

    [TestMethod]
    public async Task Run_MalformedFrame_ExitsWithProtocolError()
    {
        var (pipe, server) = CreateServer();
        using var host = new AgentHost(pipe, Environment.ProcessId, new FakeDesktop());
        var run = Start(host);
        await using (server)
        {
            await server.WaitForConnectionAsync().WaitAsync(TIMEOUT);

            await server.WriteAsync(Convert.FromHexString("0201"));

            Assert.AreEqual(AgentHost.EXIT_PROTOCOL, await run.WaitAsync(TIMEOUT));
        }
    }

    private static (string Pipe, NamedPipeServerStream Server) CreateServer()
    {
        var name = $"KeyRelay.Test.{Guid.NewGuid():N}";
        return (name, new NamedPipeServerStream(name, PipeDirection.InOut, 1, PipeTransmissionMode.Byte, PipeOptions.Asynchronous));
    }

    private static Task<int> Start(AgentHost host) =>
        Task.Factory.StartNew(host.Run, CancellationToken.None, TaskCreationOptions.LongRunning, TaskScheduler.Default);

    private static async Task<FrameType> ReadSkippingDesktopAsync(FrameReader reader)
    {
        using var timeout = new CancellationTokenSource(TIMEOUT);
        while (true)
        {
            var frame = await reader.ReadAsync(timeout.Token) ?? throw new AssertFailedException("pipe closed");
            if (frame.Type != FrameType.Desktop) return frame.Type;
        }
    }
}
```

`NativeLayoutTests.cs` içindeki testi şununla değiştir:
```csharp
    [TestMethod]
    public void InputStruct_64Bit_MatchesWin32Layout()
    {
        // 64-bit INPUT: DWORD type, 4 bytes padding, then the union; SendInput rejects any other cbSize.
        Assert.AreEqual(40, Marshal.SizeOf<User32.Input>());
        Assert.AreEqual(8, Marshal.OffsetOf<User32.Input>(nameof(User32.Input.Union)).ToInt32());
        Assert.AreEqual(24, Marshal.SizeOf<User32.KeyboardInput>());
    }
```

- [ ] **Step 2: Başarısız olduğunu gör**

Run: `dotnet test KeyRelay.slnx --filter "FullyQualifiedName~AgentHostTests"` → derleme hatası (`AgentHost` yok).

- [ ] **Step 3: Native ekler**

`Native/Kernel32.cs` içine (dosya başına `using Microsoft.Win32.SafeHandles;`):
```csharp
    [LibraryImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static partial bool GetNamedPipeServerProcessId(SafePipeHandle pipe, out uint processId);

    [LibraryImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static partial bool GetNamedPipeClientProcessId(SafePipeHandle pipe, out uint processId);
```
`Native/User32.cs`: `KEYEVENTF_UNICODE` sabitini sil (yalnız prob kullanıyordu); şunları ekle ve `DesktopName`'i değiştir:
```csharp
    internal const uint DESKTOP_READOBJECTS = 0x0001;
    private const int NAME_CHARS = 64;

    internal static unsafe string DesktopName(nint desktop)
    {
        char* buffer = stackalloc char[NAME_CHARS];
        return GetUserObjectInformation(desktop, UOI_NAME, buffer, NAME_CHARS * sizeof(char), out _) ? new string(buffer) : "?";
    }

    /// <summary>Compares a desktop's name without allocating; runs before every injected key.</summary>
    internal static unsafe bool DesktopNameEquals(nint desktop, string name)
    {
        char* buffer = stackalloc char[NAME_CHARS];
        return GetUserObjectInformation(desktop, UOI_NAME, buffer, NAME_CHARS * sizeof(char), out _)
            && MemoryMarshal.CreateReadOnlySpanFromNullTerminated(buffer).SequenceEqual(name);
    }
```

- [ ] **Step 4: `NativeInputDesktop` ve `AgentHost`**

`windows/src/KeyRelay.Service/Agent/NativeInputDesktop.cs`:
```csharp
using KeyRelay.Service.Native;

namespace KeyRelay.Service.Agent;

/// <summary>IInputDesktop over user32. SetThreadDesktop is per thread, so each thread keeps its own desktop handle.</summary>
internal sealed class NativeInputDesktop : IInputDesktop
{
    [ThreadStatic]
    private static nint _threadDesktop;

    [ThreadStatic]
    private static string? _threadDesktopName;

    public string InputDesktopName()
    {
        var desk = User32.OpenInputDesktop(0, false, User32.DESKTOP_READOBJECTS);
        if (desk == 0) return "";
        try
        {
            return User32.DesktopName(desk);
        }
        finally
        {
            User32.CloseDesktop(desk);
        }
    }

    public bool AttachToInputDesktop(bool force)
    {
        var desk = User32.OpenInputDesktop(0, false, User32.GENERIC_ALL);
        if (desk == 0) return false;
        if (!force && _threadDesktopName is not null && User32.DesktopNameEquals(desk, _threadDesktopName))
        {
            User32.CloseDesktop(desk);
            return true;
        }
        if (!User32.SetThreadDesktop(desk))
        {
            User32.CloseDesktop(desk);
            return false;
        }
        if (_threadDesktop != 0) User32.CloseDesktop(_threadDesktop);
        _threadDesktop = desk;
        _threadDesktopName = User32.DesktopName(desk);
        return true;
    }

    public unsafe bool SendKey(ushort scancode, uint flags)
    {
        var input = new User32.Input
        {
            Type = User32.INPUT_KEYBOARD,
            Union = new User32.InputUnion { Keyboard = new User32.KeyboardInput { Scan = scancode, Flags = flags } },
        };
        return User32.SendInput(1, &input, sizeof(User32.Input)) == 1;
    }

    public bool LockWorkStation() => User32.LockWorkStation();
}
```

`windows/src/KeyRelay.Service/Agent/AgentHost.cs`:
```csharp
using System.Diagnostics;
using System.Globalization;
using System.IO.Pipes;
using KeyRelay.Protocol;
using KeyRelay.Service.Native;

namespace KeyRelay.Service.Agent;

/// <summary>The --agent mode: runs as SYSTEM in the console session and injects the keys the service forwards.</summary>
/// <param name="pipeName">Agent pipe name.</param>
/// <param name="servicePid">Process id the pipe server must have; anything else is a squatter.</param>
/// <param name="desktop">user32 access.</param>
internal sealed class AgentHost(string pipeName, int servicePid, IInputDesktop desktop) : IDisposable
{
    internal const string PIPE_NAME = "KeyRelay.Agent";
    internal const int EXIT_OK = 0;
    internal const int EXIT_BAD_ARGUMENTS = 2;
    internal const int EXIT_UNTRUSTED_SERVER = 3;
    internal const int EXIT_PROTOCOL = 4;
    internal const int EXIT_NO_SERVICE = 5;
    private const int CONNECT_TIMEOUT_MS = 5000;
    private static readonly TimeSpan WATCH_INTERVAL = TimeSpan.FromMilliseconds(500);

    // Overlapped I/O lets the watcher thread write DESKTOP while the main thread is blocked in Read.
    private readonly NamedPipeClientStream _pipe = new(".", pipeName, PipeDirection.InOut, PipeOptions.Asynchronous);
    private readonly Lock _writeGate = new();
    private readonly byte[] _keyDone = new byte[Frames.KEY_DONE_SIZE];
    private readonly ManualResetEventSlim _stop = new();

    /// <summary>Entry point for "KeyRelay.Service.exe --agent &lt;service pid&gt;".</summary>
    /// <returns>An EXIT_* code; the service logs it.</returns>
    internal static int Main(string[] args)
    {
        if (args.Length != 2 || !int.TryParse(args[1], NumberStyles.None, CultureInfo.InvariantCulture, out var pid)) return EXIT_BAD_ARGUMENTS;
        using var host = new AgentHost(PIPE_NAME, pid, new NativeInputDesktop());
        return host.Run();
    }

    /// <summary>Serves the pipe until the service closes it.</summary>
    /// <returns>An EXIT_* code.</returns>
    internal int Run()
    {
        try
        {
            _pipe.Connect(CONNECT_TIMEOUT_MS);
        }
        catch (TimeoutException)
        {
            return EXIT_NO_SERVICE;
        }
        if (!Kernel32.GetNamedPipeServerProcessId(_pipe.SafePipeHandle, out var serverPid) || serverPid != (uint)servicePid)
            return EXIT_UNTRUSTED_SERVER;
        var injector = new KeyInjector(desktop, WriteDesktop);
        injector.ReleaseModifiers();
        var watcher = new Thread(() => Watch(injector)) { IsBackground = true, Name = "desktop-watch" };
        watcher.Start();
        try
        {
            return ReadLoop(injector);
        }
        catch (ProtocolException)
        {
            return EXIT_PROTOCOL;
        }
        catch (IOException)
        {
            return EXIT_OK;
        }
        finally
        {
            _stop.Set();
            watcher.Join(WATCH_INTERVAL * 4);
        }
    }

    private int ReadLoop(KeyInjector injector)
    {
        var reader = new FrameReader(_pipe, FrameChannel.Pipe);
        while (reader.Read() is { } frame)
        {
            switch (frame.Type)
            {
                case FrameType.Key:
                    var (code, value) = Frames.ReadKey(frame.Payload.Span);
                    injector.Inject(code, value);
                    WriteKeyDone(Stopwatch.GetTimestamp());
                    break;
                case FrameType.Lock:
                    desktop.LockWorkStation();
                    break;
                case FrameType.ReleaseAll:
                    injector.ReleaseAll();
                    break;
            }
        }
        return EXIT_OK;
    }

    private void Watch(KeyInjector injector)
    {
        try
        {
            while (!_stop.Wait(WATCH_INTERVAL)) injector.Watch();
        }
        catch (Exception ex) when (ex is IOException or ObjectDisposedException)
        {
            // The pipe closed; the read loop ends on its own.
        }
    }

    private void WriteKeyDone(long timestamp)
    {
        lock (_writeGate)
        {
            Frames.WriteKeyDone(_keyDone, timestamp);
            _pipe.Write(_keyDone);
        }
    }

    private void WriteDesktop(bool writable, string name)
    {
        var frame = Frames.Desktop(writable, name);
        lock (_writeGate) _pipe.Write(frame);
    }

    public void Dispose()
    {
        _pipe.Dispose();
        _stop.Dispose();
    }
}
```

`Program.cs` (prob kayıtları çıkar; servis tarafı T7'de ajanı yeni komut satırıyla başlatır, o zamana kadar servis kurulmaz):
```csharp
using KeyRelay.Service.Agent;
using KeyRelay.Service.Session;

if (args.Length > 0 && args[0] == "--agent") return AgentHost.Main(args);

var builder = Host.CreateApplicationBuilder(args);
builder.Services.AddWindowsService(options => options.ServiceName = "KeyRelay");
builder.Services.AddHostedService<AgentSupervisor>();
await builder.Build().RunAsync().ConfigureAwait(false);
return 0;
```
Probe klasörünü sil: `git -C $WT rm windows/src/KeyRelay.Service/Probe/AgentProbe.cs windows/src/KeyRelay.Service/Probe/SasProbe.cs`.

- [ ] **Step 5: Testlerin geçtiğini gör**

Run: `dotnet test KeyRelay.slnx` → AgentHostTests (7), KeyInjectorTests, NativeLayoutTests ve öncekiler geçer; uyarı yok. `Select-String -Path $WT\windows\src -Pattern 'Probe' -Recurse` sonuç vermez.

- [ ] **Step 6: Commit**

```powershell
git -C $WT add -A windows/src/KeyRelay.Service windows/tests
git -C $WT commit -m "feat(windows): ajan servisten gelen tuşları basar, deneme kodu kaldırıldı" -m "- yardımcı süreç yalnız kendi servisinden gelen tuşları basar
- kilit ekranı veya giriş ekranı değişince yeni ekrana geçer ve servise bildirir
- herhangi bir yerel kullanıcının tuş yazdırabildiği deneme dosyaları kaldırıldı" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Servis tarafı ajan bağlantısı (`AgentLink`) ve gecikme ölçümü (`LatencyStats`)

**Files:**
- Create: `windows/src/KeyRelay.Service/Agent/AgentLink.cs`, `windows/src/KeyRelay.Service/Agent/LatencyStats.cs`
- Test: `windows/tests/KeyRelay.Tests/Agent/AgentLinkTests.cs`, `windows/tests/KeyRelay.Tests/Agent/LatencyStatsTests.cs`, `windows/tests/KeyRelay.Tests/TestSupport/ManualTime.cs`, `windows/tests/KeyRelay.Tests/TestSupport/TestPipes.cs`, `windows/tests/KeyRelay.Tests/TestSupport/FakeAgent.cs`

**Interfaces:**
- Consumes: T2 `FrameReader`, `Frames`; T5 `Kernel32.GetNamedPipeClientProcessId`, `AgentHost.PIPE_NAME`.
- Produces:
  - `internal sealed record AgentLinkOptions(string PipeName, PipeSecurity Security, int BufferSize = 0)` + `static AgentLinkOptions Production()` (yalnız SYSTEM).
  - `internal sealed partial class AgentLink(AgentLinkOptions options, LatencyStats latency, ILogger<AgentLink> logger) : IDisposable`: `Task RunAsync(int agentPid, CancellationToken)`, `ValueTask<bool> SendAsync(ReadOnlyMemory<byte> frame, long keyReadTimestamp = 0)`, `bool Connected`, `bool Writable`, `bool Ready`, `string DesktopName`, `event Action<bool>? ReadyChanged`.
  - `internal sealed class LatencyStats(TimeProvider time)`: `void Sent(long readTimestamp)`, `void Done(long doneTimestamp)`, `void ResetPending()`, `LatencySnapshot Snapshot()`; `internal readonly record struct LatencySnapshot(int P50Micros, int P99Micros, int[] PerSecondMaxMicros, long KeysToday)`.
  - Test yardımcıları: `ManualTime(DateTimeOffset)` (`Now`, `Advance`), `TestPipes.Name()`, `TestPipes.CurrentUser()` (PipeSecurity), `FakeAgent.ConnectAsync(string pipe)` → `ReadAsync()` (ham byte[]), `SendAsync(byte[])`.

- [ ] **Step 1: Test yardımcıları**

`TestSupport/ManualTime.cs`:
```csharp
namespace KeyRelay.Tests.TestSupport;

/// <summary>TimeProvider whose clock only moves when a test says so; local time is UTC.</summary>
internal sealed class ManualTime(DateTimeOffset start) : TimeProvider
{
    internal DateTimeOffset Now { get; set; } = start;

    public override TimeZoneInfo LocalTimeZone => TimeZoneInfo.Utc;

    public override DateTimeOffset GetUtcNow() => Now;

    internal void Advance(TimeSpan by) => Now += by;
}
```
`TestSupport/TestPipes.cs`:
```csharp
using System.IO.Pipes;
using System.Security.AccessControl;
using System.Security.Principal;

namespace KeyRelay.Tests.TestSupport;

/// <summary>Private pipe names and an ACL for the test user (production pipes allow only SYSTEM or the console user).</summary>
internal static class TestPipes
{
    internal static string Name() => $"KeyRelay.Test.{Guid.NewGuid():N}";

    internal static PipeSecurity CurrentUser()
    {
        using var identity = WindowsIdentity.GetCurrent();
        var security = new PipeSecurity();
        security.AddAccessRule(new PipeAccessRule(identity.User!, PipeAccessRights.FullControl, AccessControlType.Allow));
        return security;
    }
}
```
`TestSupport/FakeAgent.cs`:
```csharp
using System.IO.Pipes;
using KeyRelay.Protocol;

namespace KeyRelay.Tests.TestSupport;

/// <summary>Stand-in for the agent process: the client end of the agent pipe, run inside the test process.</summary>
internal sealed class FakeAgent : IAsyncDisposable
{
    private static readonly TimeSpan TIMEOUT = TimeSpan.FromSeconds(5);
    private readonly NamedPipeClientStream _pipe;
    private readonly FrameReader _reader;

    private FakeAgent(NamedPipeClientStream pipe)
    {
        _pipe = pipe;
        _reader = new FrameReader(pipe, FrameChannel.Pipe);
    }

    internal static async Task<FakeAgent> ConnectAsync(string pipeName)
    {
        var pipe = new NamedPipeClientStream(".", pipeName, PipeDirection.InOut, PipeOptions.Asynchronous);
        await pipe.ConnectAsync((int)TIMEOUT.TotalMilliseconds);
        return new FakeAgent(pipe);
    }

    /// <summary>Reads the next frame the service sent.</summary>
    /// <returns>The whole frame as bytes.</returns>
    internal async Task<byte[]> ReadAsync()
    {
        using var timeout = new CancellationTokenSource(TIMEOUT);
        var frame = await _reader.ReadAsync(timeout.Token) ?? throw new EndOfStreamException("service closed the agent pipe");
        return frame.Raw.ToArray();
    }

    internal Task SendAsync(byte[] frame) => _pipe.WriteAsync(frame).AsTask();

    public ValueTask DisposeAsync() => _pipe.DisposeAsync();
}
```

- [ ] **Step 2: Failing testleri yaz**

`Agent/LatencyStatsTests.cs`:
```csharp
using System.Diagnostics;
using KeyRelay.Service.Agent;
using KeyRelay.Tests.TestSupport;

namespace KeyRelay.Tests.Agent;

[TestClass]
public sealed class LatencyStatsTests
{
    private readonly ManualTime _time = new(new DateTimeOffset(2026, 10, 8, 10, 0, 0, TimeSpan.Zero));

    private static long Micros(int micros) => micros * Stopwatch.Frequency / 1_000_000;

    [TestMethod]
    public void Done_HundredSamples_ReportsNearestRankPercentiles()
    {
        var stats = new LatencyStats(_time);
        for (var i = 1; i <= 100; i++)
        {
            stats.Sent(0);
            stats.Done(Micros(i));
        }

        var snapshot = stats.Snapshot();

        Assert.AreEqual(50d, snapshot.P50Micros, 1d);
        Assert.AreEqual(99d, snapshot.P99Micros, 1d);
        Assert.AreEqual(100, snapshot.KeysToday);
    }

    [TestMethod]
    public void Snapshot_SampleOlderThanOneMinute_IsExcluded()
    {
        var stats = new LatencyStats(_time);
        stats.Sent(0);
        stats.Done(Micros(900));
        _time.Advance(TimeSpan.FromSeconds(61));
        stats.Sent(0);
        stats.Done(Micros(5));

        var snapshot = stats.Snapshot();

        Assert.AreEqual(5d, snapshot.P99Micros, 1d);
    }

    [TestMethod]
    public void Snapshot_CurrentSecondSample_IsInLastGraphSlot()
    {
        var stats = new LatencyStats(_time);
        stats.Sent(0);
        stats.Done(Micros(700));

        var perSecond = stats.Snapshot().PerSecondMaxMicros;

        Assert.HasCount(60, perSecond);
        Assert.AreEqual(700d, perSecond[59], 1d);
    }

    [TestMethod]
    public void Done_WithoutPendingKey_IsIgnored()
    {
        var stats = new LatencyStats(_time);

        stats.Done(Micros(10));

        Assert.AreEqual(0, stats.Snapshot().KeysToday);
    }

    [TestMethod]
    public void ResetPending_UnansweredKey_IsDropped()
    {
        var stats = new LatencyStats(_time);
        stats.Sent(0);

        stats.ResetPending();
        stats.Done(Micros(10));

        Assert.AreEqual(0, stats.Snapshot().KeysToday);
    }

    [TestMethod]
    public void KeysToday_NextDay_StartsFromZero()
    {
        var stats = new LatencyStats(_time);
        stats.Sent(0);
        stats.Done(Micros(10));
        _time.Advance(TimeSpan.FromDays(1));
        stats.Sent(0);
        stats.Done(Micros(10));

        Assert.AreEqual(1, stats.Snapshot().KeysToday);
    }
}
```

`Agent/AgentLinkTests.cs`:
```csharp
using System.Diagnostics;
using KeyRelay.Protocol;
using KeyRelay.Service.Agent;
using KeyRelay.Tests.TestSupport;
using Microsoft.Extensions.Logging.Abstractions;

namespace KeyRelay.Tests.Agent;

[TestClass]
public sealed class AgentLinkTests : IDisposable
{
    private static readonly TimeSpan TIMEOUT = TimeSpan.FromSeconds(5);
    private readonly ManualTime _time = new(new DateTimeOffset(2026, 10, 8, 10, 0, 0, TimeSpan.Zero));
    private readonly CancellationTokenSource _stop = new();
    private readonly List<bool> _readyEvents = [];
    private LatencyStats? _latency;
    private AgentLink? _link;
    private string _pipe = "";

    private AgentLink Create(int bufferSize = 0)
    {
        _pipe = TestPipes.Name();
        _latency = new LatencyStats(_time);
        _link = new AgentLink(new AgentLinkOptions(_pipe, TestPipes.CurrentUser(), bufferSize), _latency, NullLogger<AgentLink>.Instance);
        _link.ReadyChanged += ready => { lock (_readyEvents) _readyEvents.Add(ready); };
        return _link;
    }

    public void Dispose()
    {
        _stop.Cancel();
        _stop.Dispose();
        _link?.Dispose();
    }

    [TestMethod]
    public async Task RunAsync_AgentConnects_BecomesReady()
    {
        var link = Create();
        _ = link.RunAsync(Environment.ProcessId, _stop.Token);

        await using var agent = await FakeAgent.ConnectAsync(_pipe);

        await Eventually.TrueAsync(() => link.Ready, "link did not become ready");
        lock (_readyEvents) CollectionAssert.AreEqual(new[] { true }, _readyEvents);
    }

    [TestMethod]
    public async Task RunAsync_DesktopNotWritable_ReadyFalseWithDesktopName()
    {
        var link = Create();
        _ = link.RunAsync(Environment.ProcessId, _stop.Token);
        await using var agent = await FakeAgent.ConnectAsync(_pipe);
        await Eventually.TrueAsync(() => link.Ready, "link did not become ready");

        await agent.SendAsync(Frames.Desktop(false, "Winlogon"));

        await Eventually.TrueAsync(() => !link.Ready && link.DesktopName == "Winlogon", "not-writable desktop was not applied");
        lock (_readyEvents) CollectionAssert.AreEqual(new[] { true, false }, _readyEvents);
    }

    [TestMethod]
    public async Task SendAsync_KeyFrame_ArrivesAtAgentUnchanged()
    {
        var link = Create();
        _ = link.RunAsync(Environment.ProcessId, _stop.Token);
        await using var agent = await FakeAgent.ConnectAsync(_pipe);
        await Eventually.TrueAsync(() => link.Ready, "link did not become ready");
        var key = Frames.Key(30, 1);

        var sent = await link.SendAsync(key);

        Assert.IsTrue(sent);
        CollectionAssert.AreEqual(key, await agent.ReadAsync());
    }

    [TestMethod]
    public async Task SendAsync_NoAgent_ReturnsFalse()
    {
        var link = Create();

        var sent = await link.SendAsync(Frames.Key(30, 1));

        Assert.IsFalse(sent);
    }

    [TestMethod]
    public async Task RunAsync_ClientIsNotTheLaunchedAgent_EndsWithoutReady()
    {
        var link = Create();
        var run = link.RunAsync(Environment.ProcessId + 1, _stop.Token);

        await using var agent = await FakeAgent.ConnectAsync(_pipe);
        await run.WaitAsync(TIMEOUT);

        Assert.IsFalse(link.Ready);
        lock (_readyEvents) Assert.IsEmpty(_readyEvents);
    }

    [TestMethod]
    public async Task SendAsync_AgentStopsReading_TimesOutAndEndsRun()
    {
        var link = Create(bufferSize: 64);
        var run = link.RunAsync(Environment.ProcessId, _stop.Token);
        await using var agent = await FakeAgent.ConnectAsync(_pipe);
        await Eventually.TrueAsync(() => link.Ready, "link did not become ready");

        var sent = true;
        for (var i = 0; i < 100_000 && sent; i++) sent = await link.SendAsync(Frames.Key(30, 1));

        Assert.IsFalse(sent);
        await run.WaitAsync(TIMEOUT);
        Assert.IsFalse(link.Ready);
    }

    [TestMethod]
    public async Task RunAsync_KeyDone_RecordsLatency()
    {
        var link = Create();
        _ = link.RunAsync(Environment.ProcessId, _stop.Token);
        await using var agent = await FakeAgent.ConnectAsync(_pipe);
        await Eventually.TrueAsync(() => link.Ready, "link did not become ready");

        await link.SendAsync(Frames.Key(30, 1), Stopwatch.GetTimestamp());
        await agent.ReadAsync();
        await agent.SendAsync(Frames.KeyDone(Stopwatch.GetTimestamp()));

        await Eventually.TrueAsync(() => _latency!.Snapshot().KeysToday == 1, "latency sample was not recorded");
    }

    [TestMethod]
    public async Task RunAsync_AgentDisconnects_ReadyFalse()
    {
        var link = Create();
        var run = link.RunAsync(Environment.ProcessId, _stop.Token);
        var agent = await FakeAgent.ConnectAsync(_pipe);
        await Eventually.TrueAsync(() => link.Ready, "link did not become ready");

        await agent.DisposeAsync();
        await run.WaitAsync(TIMEOUT);

        Assert.IsFalse(link.Connected);
        lock (_readyEvents) CollectionAssert.AreEqual(new[] { true, false }, _readyEvents);
    }
}
```

- [ ] **Step 3: Başarısız olduğunu gör**

Run: `dotnet test KeyRelay.slnx --filter "FullyQualifiedName~KeyRelay.Tests.Agent"` → derleme hatası (`AgentLink`, `LatencyStats` yok).

- [ ] **Step 4: `LatencyStats`**

```csharp
using System.Diagnostics;

namespace KeyRelay.Service.Agent;

/// <summary>p50/p99, per-second maxima (oldest first) and today's key count over the last minute.</summary>
internal readonly record struct LatencySnapshot(int P50Micros, int P99Micros, int[] PerSecondMaxMicros, long KeysToday);

/// <summary>
/// Per-key latency from reading KEY off TLS to the agent's SendInput return (finish line 7), over the last minute.
/// The pipe keeps order, so KEY_DONE answers pair with read times first-in first-out.
/// </summary>
internal sealed class LatencyStats(TimeProvider time)
{
    private const int PENDING_CAPACITY = 256;
    private const int SAMPLE_CAPACITY = 4096;
    private const int WINDOW_SECONDS = 60;

    private readonly Lock _gate = new();
    private readonly long[] _pending = new long[PENDING_CAPACITY];
    private readonly long[] _sampleSecond = new long[SAMPLE_CAPACITY];
    private readonly int[] _sampleMicros = new int[SAMPLE_CAPACITY];
    private int _pendingHead;
    private int _pendingCount;
    private int _sampleNext;
    private int _sampleCount;
    private DateOnly _today;
    private long _keysToday;

    /// <summary>Remembers when a KEY frame was read off TLS.</summary>
    internal void Sent(long readTimestamp)
    {
        lock (_gate)
        {
            // Full only when the agent stopped answering; the link then restarts it and calls ResetPending.
            if (_pendingCount == PENDING_CAPACITY) return;
            _pending[(_pendingHead + _pendingCount) % PENDING_CAPACITY] = readTimestamp;
            _pendingCount++;
        }
    }

    /// <summary>Pairs the agent's KEY_DONE with the oldest pending read time and records the sample.</summary>
    internal void Done(long doneTimestamp)
    {
        var now = time.GetUtcNow();
        var today = DateOnly.FromDateTime(time.GetLocalNow().DateTime);
        lock (_gate)
        {
            if (_pendingCount == 0) return;
            var start = _pending[_pendingHead];
            _pendingHead = (_pendingHead + 1) % PENDING_CAPACITY;
            _pendingCount--;
            _sampleSecond[_sampleNext] = now.ToUnixTimeSeconds();
            _sampleMicros[_sampleNext] = (int)Math.Min(int.MaxValue, Stopwatch.GetElapsedTime(start, doneTimestamp).Ticks / TimeSpan.TicksPerMicrosecond);
            _sampleNext = (_sampleNext + 1) % SAMPLE_CAPACITY;
            _sampleCount = Math.Min(_sampleCount + 1, SAMPLE_CAPACITY);
            if (today != _today)
            {
                _today = today;
                _keysToday = 0;
            }
            _keysToday++;
        }
    }

    /// <summary>Forgets read times that will never be answered (agent restarted).</summary>
    internal void ResetPending()
    {
        lock (_gate)
        {
            _pendingHead = 0;
            _pendingCount = 0;
        }
    }

    internal LatencySnapshot Snapshot()
    {
        var nowSecond = time.GetUtcNow().ToUnixTimeSeconds();
        var today = DateOnly.FromDateTime(time.GetLocalNow().DateTime);
        var perSecond = new int[WINDOW_SECONDS];
        var window = new List<int>();
        long keys;
        lock (_gate)
        {
            for (var i = 0; i < _sampleCount; i++)
            {
                var age = nowSecond - _sampleSecond[i];
                if (age is < 0 or >= WINDOW_SECONDS) continue;
                window.Add(_sampleMicros[i]);
                var slot = WINDOW_SECONDS - 1 - (int)age;
                perSecond[slot] = Math.Max(perSecond[slot], _sampleMicros[i]);
            }
            keys = _today == today ? _keysToday : 0;
        }
        window.Sort();
        return new LatencySnapshot(Percentile(window, 0.50), Percentile(window, 0.99), perSecond, keys);
    }

    private static int Percentile(List<int> sorted, double fraction) =>
        sorted.Count == 0 ? 0 : sorted[(int)Math.Ceiling(fraction * sorted.Count) - 1];
}
```

- [ ] **Step 5: `AgentLink`**

```csharp
using System.IO.Pipes;
using System.Runtime.CompilerServices;
using System.Security.AccessControl;
using System.Security.Principal;
using KeyRelay.Protocol;
using KeyRelay.Service.Native;

namespace KeyRelay.Service.Agent;

/// <summary>Where the agent pipe lives and who may open it.</summary>
/// <param name="PipeName">Pipe name without the \\.\pipe\ prefix.</param>
/// <param name="Security">Pipe ACL.</param>
/// <param name="BufferSize">Pipe buffer size; 0 lets Windows choose.</param>
internal sealed record AgentLinkOptions(string PipeName, PipeSecurity Security, int BufferSize = 0)
{
    /// <summary>Only SYSTEM may open the production agent pipe.</summary>
    internal static AgentLinkOptions Production()
    {
        var security = new PipeSecurity();
        security.AddAccessRule(new PipeAccessRule(new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null), PipeAccessRights.FullControl, AccessControlType.Allow));
        return new AgentLinkOptions(AgentHost.PIPE_NAME, security);
    }
}

/// <summary>Service end of the agent pipe. Ready (STATE bit0) is true while the agent is connected and its desktop accepts input.</summary>
internal sealed partial class AgentLink(AgentLinkOptions options, LatencyStats latency, ILogger<AgentLink> logger) : IDisposable
{
    private static readonly TimeSpan CONNECT_TIMEOUT = TimeSpan.FromSeconds(10);
    // agentWriteTimeout (spec: internal constant).
    private static readonly TimeSpan WRITE_TIMEOUT = TimeSpan.FromSeconds(1);

    private readonly SemaphoreSlim _writeGate = new(1, 1);
    private readonly Lock _stateGate = new();
    private CancellationTokenSource _writeTimeout = new();
    private NamedPipeServerStream? _pipe;
    private CancellationTokenSource? _run;
    private bool _connected;
    private bool _writable = true;
    private string _desktop = "";

    /// <summary>Raised with the new Ready value whenever it changes; runs on the pipe reader.</summary>
    internal event Action<bool>? ReadyChanged;

    internal bool Connected { get { lock (_stateGate) return _connected; } }

    internal bool Writable { get { lock (_stateGate) return _writable; } }

    internal bool Ready { get { lock (_stateGate) return _connected && _writable; } }

    internal string DesktopName { get { lock (_stateGate) return _desktop; } }

    /// <summary>Waits for the agent with the given process id and serves it until the pipe breaks, a write times out or the token is cancelled.</summary>
    /// <param name="agentPid">Process id of the agent the supervisor just launched.</param>
    /// <param name="cancellationToken">Stops serving.</param>
    internal async Task RunAsync(int agentPid, CancellationToken cancellationToken)
    {
        using var run = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        var pipe = NamedPipeServerStreamAcl.Create(options.PipeName, PipeDirection.InOut, 1, PipeTransmissionMode.Byte,
            PipeOptions.Asynchronous | PipeOptions.FirstPipeInstance, options.BufferSize, options.BufferSize, options.Security);
        await using (pipe.ConfigureAwait(false))
        {
            try
            {
                using (var connect = CancellationTokenSource.CreateLinkedTokenSource(run.Token))
                {
                    connect.CancelAfter(CONNECT_TIMEOUT);
                    await pipe.WaitForConnectionAsync(connect.Token).ConfigureAwait(false);
                }
                if (!Kernel32.GetNamedPipeClientProcessId(pipe.SafePipeHandle, out var clientPid) || clientPid != (uint)agentPid)
                {
                    LogUntrustedClient(clientPid, agentPid);
                    return;
                }
                Attach(pipe, run);
                await ReadLoopAsync(pipe, run.Token).ConfigureAwait(false);
            }
            catch (Exception ex) when (ex is IOException or OperationCanceledException or ProtocolException)
            {
                LogLinkEnded(ex.GetType().Name);
            }
            finally
            {
                Detach();
            }
        }
    }

    /// <summary>Writes one frame to the agent within agentWriteTimeout; a timeout ends the link so the supervisor restarts the agent.</summary>
    /// <param name="frame">A complete pipe frame.</param>
    /// <param name="keyReadTimestamp">Stopwatch timestamp when a KEY frame was read off TLS; 0 for other frames.</param>
    /// <returns>false when no agent is connected or the write failed.</returns>
    [AsyncMethodBuilder(typeof(PoolingAsyncValueTaskMethodBuilder<>))]
    internal async ValueTask<bool> SendAsync(ReadOnlyMemory<byte> frame, long keyReadTimestamp = 0)
    {
        NamedPipeServerStream? pipe;
        CancellationTokenSource? run;
        lock (_stateGate)
        {
            pipe = _pipe;
            run = _run;
        }
        if (pipe is null) return false;
        await _writeGate.WaitAsync().ConfigureAwait(false);
        try
        {
            // Recorded before the write: the agent may answer before WriteAsync returns.
            if (keyReadTimestamp != 0) latency.Sent(keyReadTimestamp);
            _writeTimeout.CancelAfter(WRITE_TIMEOUT);
            await pipe.WriteAsync(frame, _writeTimeout.Token).ConfigureAwait(false);
            return true;
        }
        catch (OperationCanceledException)
        {
            LogWriteTimeout();
            Cancel(run);
            return false;
        }
        catch (Exception ex) when (ex is IOException or ObjectDisposedException or InvalidOperationException)
        {
            return false;
        }
        finally
        {
            if (!_writeTimeout.TryReset())
            {
                _writeTimeout.Dispose();
                _writeTimeout = new CancellationTokenSource();
            }
            _writeGate.Release();
        }
    }

    public void Dispose()
    {
        _writeGate.Dispose();
        _writeTimeout.Dispose();
    }

    private async Task ReadLoopAsync(NamedPipeServerStream pipe, CancellationToken cancellationToken)
    {
        var reader = new FrameReader(pipe, FrameChannel.Pipe);
        while (await reader.ReadAsync(cancellationToken).ConfigureAwait(false) is { } frame)
        {
            if (frame.Type == FrameType.KeyDone) latency.Done(Frames.ReadKeyDone(frame.Payload.Span));
            else if (frame.Type == FrameType.Desktop) OnDesktop(Frames.ReadDesktop(frame.Payload.Span));
        }
    }

    private void Attach(NamedPipeServerStream pipe, CancellationTokenSource run)
    {
        latency.ResetPending();
        lock (_stateGate)
        {
            _pipe = pipe;
            _run = run;
            _connected = true;
            _writable = true;
        }
        LogAgentConnected();
        ReadyChanged?.Invoke(true);
    }

    private void Detach()
    {
        bool wasReady;
        lock (_stateGate)
        {
            wasReady = _connected && _writable;
            _pipe = null;
            _run = null;
            _connected = false;
        }
        if (wasReady) ReadyChanged?.Invoke(false);
    }

    private void OnDesktop((bool Writable, string Name) desktop)
    {
        bool before;
        bool after;
        lock (_stateGate)
        {
            before = _connected && _writable;
            _writable = desktop.Writable;
            _desktop = desktop.Name;
            after = _connected && _writable;
        }
        LogDesktop(desktop.Name, desktop.Writable);
        if (before != after) ReadyChanged?.Invoke(after);
    }

    private static void Cancel(CancellationTokenSource? run)
    {
        try
        {
            run?.Cancel();
        }
        catch (ObjectDisposedException)
        {
            // The run already ended.
        }
    }

    [LoggerMessage(Level = LogLevel.Information, Message = "Agent connected")]
    private partial void LogAgentConnected();

    [LoggerMessage(Level = LogLevel.Warning, Message = "Agent pipe client {ClientPid} is not the launched agent {AgentPid}")]
    private partial void LogUntrustedClient(uint clientPid, int agentPid);

    [LoggerMessage(Level = LogLevel.Information, Message = "Agent link ended ({Reason})")]
    private partial void LogLinkEnded(string reason);

    [LoggerMessage(Level = LogLevel.Information, Message = "Input desktop {Desktop}, writable={Writable}")]
    private partial void LogDesktop(string desktop, bool writable);

    [LoggerMessage(Level = LogLevel.Warning, Message = "Agent did not take a frame within 1 s; restarting it")]
    private partial void LogWriteTimeout();
}
```

- [ ] **Step 6: Testlerin geçtiğini gör**

Run: `dotnet test KeyRelay.slnx --filter "FullyQualifiedName~KeyRelay.Tests.Agent"` → LatencyStats (6) ve AgentLink (8) dahil hepsi geçer. `SendAsync_AgentStopsReading_TimesOutAndEndsRun` 10 sn'den uzun sürerse pipe tamponu 64 bayta inmemiştir; rapora yaz.

- [ ] **Step 7: Commit**

```powershell
git -C $WT add windows/src/KeyRelay.Service/Agent windows/tests/KeyRelay.Tests
git -C $WT commit -m "feat(windows): servis ajanla güvenli kanaldan konuşur, gecikme ölçülür" -m "- servis yalnız kendi başlattığı yardımcı süreçle konuşur
- yardımcı süreç 1 saniyede yanıt vermezse yeniden başlatılır
- tuş başına gecikme son bir dakika için ölçülür" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Ajan yönetimi — oturum değişimi, süreç handle'ı, geri çekilme

**Files:**
- Create: `windows/src/KeyRelay.Service/Backoff.cs`, `windows/src/KeyRelay.Service/Session/AgentProcess.cs`, `windows/src/KeyRelay.Service/Session/SessionSignal.cs`, `windows/src/KeyRelay.Service/Session/SessionAwareServiceLifetime.cs`
- Modify: `windows/src/KeyRelay.Service/Session/SessionLauncher.cs`, `windows/src/KeyRelay.Service/Session/AgentSupervisor.cs`, `windows/src/KeyRelay.Service/Native/Kernel32.cs`, `windows/src/KeyRelay.Service/Program.cs`
- Test: `windows/tests/KeyRelay.Tests/BackoffTests.cs`, `windows/tests/KeyRelay.Tests/Session/SessionSignalTests.cs`

**Interfaces:**
- Consumes: T6 `AgentLink.RunAsync`, `LatencyStats`, `AgentLinkOptions.Production()`; T5 `AgentHost.Main`.
- Produces:
  - `internal static class Backoff { static TimeSpan Delay(int failures, TimeSpan first, TimeSpan max); }` (T8 `AuthThrottle` de kullanır).
  - `internal sealed class AgentProcess(SafeProcessHandle, int id) : IDisposable` — `int Id`, `Task Exited`, `uint? ExitCode`, `Task StopAsync()`.
  - `internal sealed class SessionSignal : IDisposable` — `void Notify()`, `Task WaitForChangeAsync(uint session, Func<uint> activeSession, CancellationToken)`.
  - `internal sealed class SessionAwareServiceLifetime : WindowsServiceLifetime`.
  - `internal sealed partial class AgentSupervisor(AgentLink link, SessionSignal sessions, ILogger<AgentSupervisor> logger) : BackgroundService` — `uint? ActiveSession` (T11 durum ekranı).
  - `SessionLauncher.Launch(uint sessionId, string commandLine)` artık `AgentProcess` döndürür.

- [ ] **Step 1: Failing testleri yaz**

`BackoffTests.cs`:
```csharp
using KeyRelay.Service;

namespace KeyRelay.Tests;

[TestClass]
public sealed class BackoffTests
{
    private static readonly TimeSpan FIRST = TimeSpan.FromMilliseconds(250);
    private static readonly TimeSpan MAX = TimeSpan.FromSeconds(5);

    [TestMethod]
    [DataRow(0, 0)]
    [DataRow(1, 250)]
    [DataRow(2, 500)]
    [DataRow(3, 1000)]
    [DataRow(5, 4000)]
    [DataRow(6, 5000)]
    [DataRow(1000, 5000)]
    public void Delay_Failures_DoublesUpToMax(int failures, int expectedMilliseconds)
    {
        var delay = Backoff.Delay(failures, FIRST, MAX);

        Assert.AreEqual(TimeSpan.FromMilliseconds(expectedMilliseconds), delay);
    }
}
```
`Session/SessionSignalTests.cs`:
```csharp
using KeyRelay.Service.Session;

namespace KeyRelay.Tests.Session;

[TestClass]
public sealed class SessionSignalTests
{
    private static readonly TimeSpan TIMEOUT = TimeSpan.FromSeconds(5);

    [TestMethod]
    public async Task WaitForChangeAsync_SessionAlreadyDifferent_CompletesAtOnce()
    {
        using var signal = new SessionSignal();

        await signal.WaitForChangeAsync(1, () => 2, CancellationToken.None).WaitAsync(TIMEOUT);
    }

    [TestMethod]
    public async Task WaitForChangeAsync_NotifyWithoutChange_KeepsWaitingUntilSessionChanges()
    {
        using var signal = new SessionSignal();
        uint active = 1;
        var wait = signal.WaitForChangeAsync(1, () => Volatile.Read(ref active), CancellationToken.None);

        signal.Notify();
        signal.Notify();
        await Task.Delay(100);
        var completedEarly = wait.IsCompleted;
        Volatile.Write(ref active, 2);
        signal.Notify();
        await wait.WaitAsync(TIMEOUT);

        Assert.IsFalse(completedEarly);
    }
}
```

- [ ] **Step 2: Başarısız olduğunu gör**

Run: `dotnet test KeyRelay.slnx --filter "FullyQualifiedName~Backoff|FullyQualifiedName~SessionSignal"` → derleme hatası.

- [ ] **Step 3: `Backoff`, `SessionSignal`, `AgentProcess`, Kernel32 ekleri**

`Backoff.cs`:
```csharp
namespace KeyRelay.Service;

/// <summary>Exponential back-off: zero, then first, 2 x first, 4 x first ... capped at max.</summary>
internal static class Backoff
{
    /// <param name="failures">Consecutive failures; 0 means no wait.</param>
    /// <param name="first">Wait after the first failure.</param>
    /// <param name="max">Upper limit.</param>
    internal static TimeSpan Delay(int failures, TimeSpan first, TimeSpan max) =>
        failures <= 0 ? TimeSpan.Zero : TimeSpan.FromTicks(Math.Min(max.Ticks, first.Ticks << Math.Min(failures - 1, 20)));
}
```
`Session/SessionSignal.cs`:
```csharp
namespace KeyRelay.Service.Session;

/// <summary>Wakes the agent supervisor when Windows reports a session change (logon, logoff, console connect or disconnect).</summary>
internal sealed class SessionSignal : IDisposable
{
    private readonly SemaphoreSlim _changed = new(0);

    /// <summary>Called from the service control handler; several notifications before a wait count as one.</summary>
    internal void Notify()
    {
        if (_changed.CurrentCount == 0) _changed.Release();
    }

    /// <summary>Waits until the active console session differs from <paramref name="session"/>.</summary>
    /// <param name="session">Session the caller is serving (NO_ACTIVE_SESSION while none).</param>
    /// <param name="activeSession">Reads the active console session (WTSGetActiveConsoleSessionId).</param>
    /// <param name="cancellationToken">Stops waiting.</param>
    internal async Task WaitForChangeAsync(uint session, Func<uint> activeSession, CancellationToken cancellationToken)
    {
        while (activeSession() == session) await _changed.WaitAsync(cancellationToken).ConfigureAwait(false);
    }

    public void Dispose() => _changed.Dispose();
}
```
`Native/Kernel32.cs` içine ekle:
```csharp
    internal const uint INFINITE = 0xFFFFFFFF;
    internal const uint STILL_ACTIVE = 259;

    [LibraryImport("kernel32.dll")]
    internal static partial nint GetCurrentProcess();

    [LibraryImport("kernel32.dll", SetLastError = true)]
    internal static partial uint WaitForSingleObject(SafeProcessHandle handle, uint milliseconds);

    [LibraryImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static partial bool TerminateProcess(SafeProcessHandle process, uint exitCode);

    [LibraryImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static partial bool GetExitCodeProcess(SafeProcessHandle process, out uint exitCode);
```
`Session/AgentProcess.cs`:
```csharp
using KeyRelay.Service.Native;
using Microsoft.Win32.SafeHandles;

namespace KeyRelay.Service.Session;

/// <summary>An agent started by the service, held by handle: exit is seen without polling and a reused pid cannot fool us.</summary>
internal sealed class AgentProcess : IDisposable
{
    private static readonly TimeSpan EXIT_WAIT = TimeSpan.FromSeconds(5);
    private readonly SafeProcessHandle _handle;

    internal AgentProcess(SafeProcessHandle handle, int id)
    {
        _handle = handle;
        Id = id;
        Exited = Task.Factory.StartNew(() => Kernel32.WaitForSingleObject(_handle, Kernel32.INFINITE),
            CancellationToken.None, TaskCreationOptions.LongRunning, TaskScheduler.Default);
    }

    internal int Id { get; }

    /// <summary>Gets a task that completes when the process exits.</summary>
    internal Task Exited { get; }

    /// <summary>Gets the exit code, or null while running or when it cannot be read.</summary>
    internal uint? ExitCode => Kernel32.GetExitCodeProcess(_handle, out var code) && code != Kernel32.STILL_ACTIVE ? code : null;

    /// <summary>Terminates the agent if it still runs and waits until it has exited.</summary>
    /// <exception cref="TimeoutException">The process did not exit within 5 s.</exception>
    internal async Task StopAsync()
    {
        if (!Exited.IsCompleted) Kernel32.TerminateProcess(_handle, 1);
        await Exited.WaitAsync(EXIT_WAIT).ConfigureAwait(false);
    }

    public void Dispose() => _handle.Dispose();
}
```

- [ ] **Step 4: `SessionLauncher`, `SessionAwareServiceLifetime`, `AgentSupervisor`, `Program.cs`**

`SessionLauncher.cs` gövdesi:
```csharp
using System.ComponentModel;
using System.Text;
using KeyRelay.Service.Native;
using Microsoft.Win32.SafeHandles;

namespace KeyRelay.Service.Session;

/// <summary>Starts a process as SYSTEM inside an interactive session, so it can reach that session's desktops.</summary>
internal static class SessionLauncher
{
    /// <exception cref="Win32Exception">A token or process call failed (early in boot the session may not be ready yet).</exception>
    internal static AgentProcess Launch(uint sessionId, string commandLine)
    {
        // GetCurrentProcess returns a pseudo handle that needs no closing.
        if (!Advapi32.OpenProcessToken(Kernel32.GetCurrentProcess(), Advapi32.TOKEN_ALL_ACCESS, out var token))
            throw new Win32Exception();
        nint primary = 0;
        try
        {
            if (!Advapi32.DuplicateTokenEx(token, Advapi32.MAXIMUM_ALLOWED, 0, Advapi32.SECURITY_IMPERSONATION, Advapi32.TOKEN_PRIMARY, out primary))
                throw new Win32Exception();
            if (!Advapi32.SetTokenInformation(primary, Advapi32.TOKEN_SESSION_ID, ref sessionId, sizeof(uint)))
                throw new Win32Exception();
            var startup = new Advapi32.StartupInfo { Cb = System.Runtime.InteropServices.Marshal.SizeOf<Advapi32.StartupInfo>(), Desktop = @"winsta0\default" };
            if (!Advapi32.CreateProcessAsUser(primary, null, new StringBuilder(commandLine), 0, 0, false,
                    Advapi32.CREATE_NO_WINDOW | Advapi32.CREATE_UNICODE_ENVIRONMENT, 0, null, ref startup, out var info))
                throw new Win32Exception();
            Kernel32.CloseHandle(info.Thread);
            return new AgentProcess(new SafeProcessHandle(info.Process, ownsHandle: true), info.ProcessId);
        }
        finally
        {
            if (primary != 0) Kernel32.CloseHandle(primary);
            Kernel32.CloseHandle(token);
        }
    }
}
```
`Session/SessionAwareServiceLifetime.cs`:
```csharp
using System.ServiceProcess;
using Microsoft.Extensions.Hosting.WindowsServices;
using Microsoft.Extensions.Options;

namespace KeyRelay.Service.Session;

/// <summary>The Windows service lifetime plus session change notifications, which the default lifetime does not request.</summary>
internal sealed class SessionAwareServiceLifetime : WindowsServiceLifetime
{
    private readonly SessionSignal _sessions;

    public SessionAwareServiceLifetime(IHostEnvironment environment, IHostApplicationLifetime applicationLifetime, ILoggerFactory loggerFactory,
        IOptions<HostOptions> hostOptions, IOptions<WindowsServiceLifetimeOptions> serviceOptions, SessionSignal sessions)
        : base(environment, applicationLifetime, loggerFactory, hostOptions, serviceOptions)
    {
        _sessions = sessions;
        CanHandleSessionChangeEvent = true;
    }

    protected override void OnSessionChange(SessionChangeDescription changeDescription)
    {
        _sessions.Notify();
        base.OnSessionChange(changeDescription);
    }
}
```
(Kurucu altı parametrelidir: taban sınıfın DI kurucusu beşini ister.)

`Session/AgentSupervisor.cs`:
```csharp
using System.ComponentModel;
using System.Diagnostics;
using KeyRelay.Service.Agent;
using KeyRelay.Service.Native;

namespace KeyRelay.Service.Session;

/// <summary>Keeps one agent running in the active console session; restarts it with back-off (at most 5 s).</summary>
internal sealed partial class AgentSupervisor(AgentLink link, SessionSignal sessions, ILogger<AgentSupervisor> logger) : BackgroundService
{
    private static readonly TimeSpan FIRST_RETRY = TimeSpan.FromMilliseconds(250);
    private static readonly TimeSpan MAX_RETRY = TimeSpan.FromSeconds(5);
    // A run this long counts as healthy and resets the back-off.
    private static readonly TimeSpan HEALTHY_RUN = TimeSpan.FromSeconds(30);
    private long _activeSession = -1;

    /// <summary>Gets the session the agent runs in, or null while none runs.</summary>
    internal uint? ActiveSession => Interlocked.Read(ref _activeSession) is var session and >= 0 ? (uint)session : null;

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        var failures = 0;
        while (!stoppingToken.IsCancellationRequested)
        {
            var session = Kernel32.WTSGetActiveConsoleSessionId();
            if (session == Kernel32.NO_ACTIVE_SESSION)
            {
                await sessions.WaitForChangeAsync(session, Kernel32.WTSGetActiveConsoleSessionId, stoppingToken).ConfigureAwait(false);
                continue;
            }
            var started = Stopwatch.GetTimestamp();
            await RunAgentAsync(session, stoppingToken).ConfigureAwait(false);
            failures = Stopwatch.GetElapsedTime(started) >= HEALTHY_RUN ? 0 : failures + 1;
            await Task.Delay(Backoff.Delay(failures, FIRST_RETRY, MAX_RETRY), stoppingToken).ConfigureAwait(false);
        }
    }

    /// <summary>Runs one agent until it exits, its pipe link ends, the console session changes or the service stops. Never throws.</summary>
    private async Task RunAgentAsync(uint session, CancellationToken stoppingToken)
    {
        try
        {
            using var agent = SessionLauncher.Launch(session, $"\"{Environment.ProcessPath}\" --agent {Environment.ProcessId}");
            Interlocked.Exchange(ref _activeSession, session);
            LogAgentStarted(agent.Id, session);
            using var run = CancellationTokenSource.CreateLinkedTokenSource(stoppingToken);
            var served = link.RunAsync(agent.Id, run.Token);
            var moved = sessions.WaitForChangeAsync(session, Kernel32.WTSGetActiveConsoleSessionId, run.Token);
            var first = await Task.WhenAny(served, agent.Exited, moved).ConfigureAwait(false);
            await run.CancelAsync().ConfigureAwait(false);
            await StopAgentAsync(agent, served).ConfigureAwait(false);
            LogAgentStopped(agent.Id, agent.ExitCode, first == agent.Exited ? "exited" : first == moved ? "session changed" : "pipe link ended");
        }
        catch (Win32Exception ex)
        {
            LogAgentStartFailed(ex, session);
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            LogSupervisionFailed(ex, session);
        }
        finally
        {
            Interlocked.Exchange(ref _activeSession, -1);
        }
    }

    private async Task StopAgentAsync(AgentProcess agent, Task served)
    {
        try
        {
            await agent.StopAsync().ConfigureAwait(false);
        }
        catch (TimeoutException)
        {
            LogAgentStuck(agent.Id);
        }
        await served.ConfigureAwait(false);
    }

    [LoggerMessage(Level = LogLevel.Information, Message = "Agent {Pid} started in session {Session}")]
    private partial void LogAgentStarted(int pid, uint session);

    [LoggerMessage(Level = LogLevel.Information, Message = "Agent {Pid} stopped, exit code {ExitCode}, reason: {Reason}")]
    private partial void LogAgentStopped(int pid, uint? exitCode, string reason);

    [LoggerMessage(Level = LogLevel.Error, Message = "Agent start failed in session {Session}")]
    private partial void LogAgentStartFailed(Exception ex, uint session);

    [LoggerMessage(Level = LogLevel.Error, Message = "Agent supervision failed in session {Session}")]
    private partial void LogSupervisionFailed(Exception ex, uint session);

    [LoggerMessage(Level = LogLevel.Error, Message = "Agent {Pid} did not exit after termination")]
    private partial void LogAgentStuck(int pid);
}
```
`Program.cs`:
```csharp
using KeyRelay.Service.Agent;
using KeyRelay.Service.Session;
using Microsoft.Extensions.Hosting.WindowsServices;

if (args.Length > 0 && args[0] == "--agent") return AgentHost.Main(args);

var builder = Host.CreateApplicationBuilder(args);
var services = builder.Services;
services.AddWindowsService(options => options.ServiceName = "KeyRelay");
if (WindowsServiceHelpers.IsWindowsService()) services.AddSingleton<IHostLifetime, SessionAwareServiceLifetime>();
services.AddSingleton(TimeProvider.System);
services.AddSingleton<SessionSignal>();
services.AddSingleton<LatencyStats>();
services.AddSingleton(AgentLinkOptions.Production());
services.AddSingleton<AgentLink>();
services.AddSingleton<AgentSupervisor>();
services.AddHostedService(provider => provider.GetRequiredService<AgentSupervisor>());
await builder.Build().RunAsync().ConfigureAwait(false);
return 0;
```

- [ ] **Step 5: Testlerin geçtiğini gör**

Run: `dotnet test KeyRelay.slnx` → BackoffTests (7), SessionSignalTests (2) ve öncekiler geçer; uyarı yok. Servis bu görevde cihaza kurulmaz (T15 uçtan uca kurar); ajan başlatma ve oturum değişimi T15'te homepc'de doğrulanır.

- [ ] **Step 6: Commit**

```powershell
git -C $WT add windows/src/KeyRelay.Service windows/tests/KeyRelay.Tests
git -C $WT commit -m "fix(windows): ajan oturum değişince yeniden başlar, takılı kalmaz" -m "- oturum açılıp kapanınca yardımcı süreç doğru oturumda yeniden başlar
- yardımcı süreç çökerse en geç 5 saniye içinde yeniden başlatılır
- kimse oturumda değilken eski yardımcı süreç kapatılır
- servis dururken yardımcı süreç geride kalmaz" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Cihaz kaydı, eşleştirme kodu, deneme sınırı, ayarlar

**Files:**
- Create: `windows/src/KeyRelay.Service/ServicePaths.cs`, `windows/src/KeyRelay.Service/JsonFile.cs`, `windows/src/KeyRelay.Service/Relay/DeviceRegistry.cs`, `windows/src/KeyRelay.Service/Relay/PairingCodes.cs`, `windows/src/KeyRelay.Service/Relay/AuthThrottle.cs`, `windows/src/KeyRelay.Service/Settings/ServiceSettings.cs`
- Test: `windows/tests/KeyRelay.Tests/TestSupport/TempDirectory.cs`, `windows/tests/KeyRelay.Tests/Relay/DeviceRegistryTests.cs`, `windows/tests/KeyRelay.Tests/Relay/PairingCodesTests.cs`, `windows/tests/KeyRelay.Tests/Relay/AuthThrottleTests.cs`, `windows/tests/KeyRelay.Tests/Settings/ServiceSettingsTests.cs`

**Interfaces:**
- Consumes: T2 `Frames.ID_SIZE/TOKEN_SIZE/CODE_SIZE`; T7 `Backoff.Delay`; T6 test yardımcısı `ManualTime`.
- Produces:
  - `ServicePaths.DATA_DIRECTORY` (`ProgramData\KeyRelay`); `JsonFile.Read<T>(string path)` (yoksa null), `JsonFile.Write<T>(string path, T value)` (geçici dosya + taşı).
  - `internal sealed record DeviceRecord(string Id, string Name, string TokenHash, DateTimeOffset AddedAt, DateTimeOffset? LastSeenAt)`; `internal sealed class DeviceRegistry(string directory, TimeProvider time)`: `(byte[] Id, byte[] Token) Add(string name)`, `DeviceRecord? Verify(ReadOnlySpan<byte> id, ReadOnlySpan<byte> token)`, `void Touch(string id)`, `bool Revoke(string id)`, `List<DeviceRecord> List()`. `Id` küçük harf hex.
  - `internal sealed class PairingCodes(TimeProvider time)`: `(byte[] Code, DateTimeOffset ExpiresAt) Start()`, `void Stop()`, `DateTimeOffset? ActiveUntil`, `bool TryConsume(ReadOnlySpan<byte> code)`.
  - `internal sealed class AuthThrottle(TimeProvider time)`: `bool IsBlocked(string key)`, `void Failed(string key)`, `void Succeeded(string key)`.
  - `internal sealed record ServiceSettings { int Port = 47111; int IdleTimeoutSeconds = 20; string FunnelAddress = ""; static bool IsValidFunnelAddress(string) }`; `internal sealed class SettingsStore(string directory)`: `ServiceSettings Load()`, `void Save(ServiceSettings)`.
  - Test yardımcısı: `TempDirectory` (`Path`, `Dispose` siler).

- [ ] **Step 1: Failing testleri yaz**

`TestSupport/TempDirectory.cs`:
```csharp
namespace KeyRelay.Tests.TestSupport;

/// <summary>A fresh directory under %TEMP% that is deleted with the test.</summary>
internal sealed class TempDirectory : IDisposable
{
    internal TempDirectory() => Directory.CreateDirectory(Path);

    internal string Path { get; } = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "keyrelay-tests", Guid.NewGuid().ToString("N"));

    public void Dispose() => Directory.Delete(Path, recursive: true);
}
```
`Relay/DeviceRegistryTests.cs`:
```csharp
using KeyRelay.Service.Relay;
using KeyRelay.Tests.TestSupport;

namespace KeyRelay.Tests.Relay;

[TestClass]
public sealed class DeviceRegistryTests : IDisposable
{
    private readonly TempDirectory _directory = new();
    private readonly ManualTime _time = new(new DateTimeOffset(2026, 10, 8, 10, 0, 0, TimeSpan.Zero));

    public void Dispose() => _directory.Dispose();

    private DeviceRegistry Create() => new(_directory.Path, _time);

    [TestMethod]
    public void Verify_TokenFromAdd_ReturnsDevice()
    {
        var registry = Create();
        var (id, token) = registry.Add("Tab");

        var device = registry.Verify(id, token);

        Assert.AreEqual("Tab", device?.Name);
        Assert.AreEqual(Convert.ToHexStringLower(id), device?.Id);
    }

    [TestMethod]
    public void Verify_OneBitWrongToken_ReturnsNull()
    {
        var registry = Create();
        var (id, token) = registry.Add("Tab");
        token[31] ^= 0x01;

        Assert.IsNull(registry.Verify(id, token));
    }

    [TestMethod]
    public void Verify_UnknownId_ReturnsNull()
    {
        var registry = Create();
        var (_, token) = registry.Add("Tab");

        Assert.IsNull(registry.Verify(new byte[16], token));
    }

    [TestMethod]
    public void Revoke_ThenVerify_ReturnsNull()
    {
        var registry = Create();
        var (id, token) = registry.Add("Tab");

        var revoked = registry.Revoke(Convert.ToHexStringLower(id));

        Assert.IsTrue(revoked);
        Assert.IsNull(registry.Verify(id, token));
    }

    [TestMethod]
    public void Revoke_UnknownId_ReturnsFalse()
    {
        Assert.IsFalse(Create().Revoke("00"));
    }

    [TestMethod]
    public void Add_NewInstanceOnSameDirectory_StillVerifies()
    {
        var (id, token) = Create().Add("Tab");

        var device = Create().Verify(id, token);

        Assert.IsNotNull(device);
    }

    [TestMethod]
    public void Add_File_DoesNotContainTheToken()
    {
        var (_, token) = Create().Add("Tab");

        var text = File.ReadAllText(Path.Combine(_directory.Path, "devices.json"));

        Assert.IsFalse(text.Contains(Convert.ToBase64String(token), StringComparison.Ordinal));
        Assert.IsFalse(text.Contains(Convert.ToHexStringLower(token), StringComparison.Ordinal));
    }

    [TestMethod]
    public void Touch_KnownDevice_SetsLastSeen()
    {
        var registry = Create();
        var (id, _) = registry.Add("Tab");
        _time.Advance(TimeSpan.FromHours(1));

        registry.Touch(Convert.ToHexStringLower(id));

        Assert.AreEqual(_time.Now, registry.List().Single().LastSeenAt);
    }
}
```
`Relay/PairingCodesTests.cs`:
```csharp
using KeyRelay.Service.Relay;
using KeyRelay.Tests.TestSupport;

namespace KeyRelay.Tests.Relay;

[TestClass]
public sealed class PairingCodesTests
{
    private readonly ManualTime _time = new(new DateTimeOffset(2026, 10, 8, 10, 0, 0, TimeSpan.Zero));

    [TestMethod]
    public void TryConsume_ActiveCode_SucceedsOnlyOnce()
    {
        var codes = new PairingCodes(_time);
        var (code, _) = codes.Start();

        var first = codes.TryConsume(code);
        var second = codes.TryConsume(code);

        Assert.IsTrue(first);
        Assert.IsFalse(second);
        Assert.IsNull(codes.ActiveUntil);
    }

    [TestMethod]
    public void TryConsume_JustBeforeFiveMinutes_Succeeds()
    {
        var codes = new PairingCodes(_time);
        var (code, _) = codes.Start();
        _time.Advance(TimeSpan.FromMinutes(5) - TimeSpan.FromSeconds(1));

        Assert.IsTrue(codes.TryConsume(code));
    }

    [TestMethod]
    public void TryConsume_AtFiveMinutes_Fails()
    {
        var codes = new PairingCodes(_time);
        var (code, expiresAt) = codes.Start();
        _time.Advance(TimeSpan.FromMinutes(5));

        Assert.IsFalse(codes.TryConsume(code));
        Assert.AreEqual(_time.Now, expiresAt);
    }

    [TestMethod]
    public void Start_Again_InvalidatesPreviousCode()
    {
        var codes = new PairingCodes(_time);
        var (old, _) = codes.Start();
        var (fresh, _) = codes.Start();

        Assert.IsFalse(codes.TryConsume(old));
        Assert.IsTrue(codes.TryConsume(fresh));
    }

    [TestMethod]
    public void Stop_ActiveCode_InvalidatesIt()
    {
        var codes = new PairingCodes(_time);
        var (code, _) = codes.Start();

        codes.Stop();

        Assert.IsNull(codes.ActiveUntil);
        Assert.IsFalse(codes.TryConsume(code));
    }

    [TestMethod]
    public void TryConsume_WrongCode_KeepsRightCodeUsable()
    {
        var codes = new PairingCodes(_time);
        var (code, _) = codes.Start();

        var wrong = codes.TryConsume(new byte[16]);

        Assert.IsFalse(wrong);
        Assert.IsTrue(codes.TryConsume(code));
    }
}
```
`Relay/AuthThrottleTests.cs`:
```csharp
using KeyRelay.Service.Relay;
using KeyRelay.Tests.TestSupport;

namespace KeyRelay.Tests.Relay;

[TestClass]
public sealed class AuthThrottleTests
{
    private const string KEY = "127.0.0.1|pair";
    private readonly ManualTime _time = new(new DateTimeOffset(2026, 10, 8, 10, 0, 0, TimeSpan.Zero));

    [TestMethod]
    public void Failed_Once_BlocksForOneSecond()
    {
        var throttle = new AuthThrottle(_time);

        throttle.Failed(KEY);
        _time.Advance(TimeSpan.FromMilliseconds(999));
        var during = throttle.IsBlocked(KEY);
        _time.Advance(TimeSpan.FromMilliseconds(1));
        var after = throttle.IsBlocked(KEY);

        Assert.IsTrue(during);
        Assert.IsFalse(after);
    }

    [TestMethod]
    public void Failed_ThreeTimes_BlocksForFourSeconds()
    {
        var throttle = new AuthThrottle(_time);

        for (var i = 0; i < 3; i++) throttle.Failed(KEY);
        _time.Advance(TimeSpan.FromSeconds(3.9));

        Assert.IsTrue(throttle.IsBlocked(KEY));
    }

    [TestMethod]
    public void Failed_ManyTimes_CapsAtSixtySeconds()
    {
        var throttle = new AuthThrottle(_time);

        for (var i = 0; i < 50; i++) throttle.Failed(KEY);
        _time.Advance(TimeSpan.FromSeconds(60));

        Assert.IsFalse(throttle.IsBlocked(KEY));
    }

    [TestMethod]
    public void Succeeded_AfterFailures_Unblocks()
    {
        var throttle = new AuthThrottle(_time);
        throttle.Failed(KEY);

        throttle.Succeeded(KEY);

        Assert.IsFalse(throttle.IsBlocked(KEY));
    }

    [TestMethod]
    public void Failed_OneKey_DoesNotBlockAnother()
    {
        var throttle = new AuthThrottle(_time);

        throttle.Failed(KEY);

        Assert.IsFalse(throttle.IsBlocked("127.0.0.1|00112233"));
    }
}
```
`Settings/ServiceSettingsTests.cs`:
```csharp
using KeyRelay.Service.Settings;
using KeyRelay.Tests.TestSupport;

namespace KeyRelay.Tests.Settings;

[TestClass]
public sealed class ServiceSettingsTests : IDisposable
{
    private readonly TempDirectory _directory = new();

    public void Dispose() => _directory.Dispose();

    [TestMethod]
    public void Load_NoFile_ReturnsDefaults()
    {
        var settings = new SettingsStore(_directory.Path).Load();

        Assert.AreEqual(new ServiceSettings { Port = 47111, IdleTimeoutSeconds = 20, FunnelAddress = "" }, settings);
    }

    [TestMethod]
    public void Save_ThenLoad_RoundTrips()
    {
        var store = new SettingsStore(_directory.Path);
        var saved = new ServiceSettings { Port = 47112, IdleTimeoutSeconds = 30, FunnelAddress = "pc.tail.ts.net:8443" };

        store.Save(saved);

        Assert.AreEqual(saved, new SettingsStore(_directory.Path).Load());
    }

    [TestMethod]
    public void Load_FileWithOnlyPort_KeepsOtherDefaults()
    {
        File.WriteAllText(Path.Combine(_directory.Path, "settings.json"), """{ "port": 47112 }""");

        var settings = new SettingsStore(_directory.Path).Load();

        Assert.AreEqual(47112, settings.Port);
        Assert.AreEqual(20, settings.IdleTimeoutSeconds);
    }

    [TestMethod]
    [DataRow(0)]
    [DataRow(-5)]
    public void Load_NonPositiveIdleTimeout_FallsBackToDefault(int value)
    {
        File.WriteAllText(Path.Combine(_directory.Path, "settings.json"), $$"""{ "idleTimeoutSeconds": {{value}} }""");

        var settings = new SettingsStore(_directory.Path).Load();

        Assert.AreEqual(20, settings.IdleTimeoutSeconds);
    }

    [TestMethod]
    [DataRow("", true)]
    [DataRow("homepc.tailnet-example.ts.net:8443", true)]
    [DataRow("pc.tail.ts.net:443", true)]
    [DataRow("pc.tail.ts.net:10000", true)]
    [DataRow("pc.tail.ts.net:8080", false)]
    [DataRow("100.64.0.10:443", false)]
    [DataRow("pc.tail.ts.net", false)]
    [DataRow(":443", false)]
    [DataRow("a,b.ts.net:443", false)]
    public void IsValidFunnelAddress_Input_MatchesFunnelRules(string address, bool expected)
    {
        Assert.AreEqual(expected, ServiceSettings.IsValidFunnelAddress(address));
    }
}
```

- [ ] **Step 2: Başarısız olduğunu gör**

Run: `dotnet test KeyRelay.slnx --filter "FullyQualifiedName~KeyRelay.Tests.Relay|FullyQualifiedName~KeyRelay.Tests.Settings"` → derleme hatası.

- [ ] **Step 3: Uygulamayı yaz**

`ServicePaths.cs`:
```csharp
namespace KeyRelay.Service;

internal static class ServicePaths
{
    /// <summary>ProgramData\KeyRelay: certificate, devices, settings and install record (SYSTEM and Administrators only after install).</summary>
    internal static readonly string DATA_DIRECTORY =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "KeyRelay");
}
```
`JsonFile.cs`:
```csharp
using System.Text.Json;

namespace KeyRelay.Service;

/// <summary>Small JSON files, replaced through a temporary file so a crash never leaves half a file behind.</summary>
internal static class JsonFile
{
    private static readonly JsonSerializerOptions OPTIONS = new(JsonSerializerDefaults.Web) { WriteIndented = true };

    /// <returns>The content, or null when the file does not exist.</returns>
    internal static T? Read<T>(string path) where T : class =>
        File.Exists(path) ? JsonSerializer.Deserialize<T>(File.ReadAllBytes(path), OPTIONS) : null;

    internal static void Write<T>(string path, T value)
    {
        var temporary = path + ".tmp";
        File.WriteAllBytes(temporary, JsonSerializer.SerializeToUtf8Bytes(value, OPTIONS));
        File.Move(temporary, path, overwrite: true);
    }
}
```
`Relay/DeviceRegistry.cs`:
```csharp
using System.Security.Cryptography;
using KeyRelay.Protocol;

namespace KeyRelay.Service.Relay;

/// <summary>One paired tablet as stored in devices.json; only the SHA-256 of its token is kept.</summary>
internal sealed record DeviceRecord(string Id, string Name, string TokenHash, DateTimeOffset AddedAt, DateTimeOffset? LastSeenAt);

/// <summary>Paired devices in ProgramData\KeyRelay\devices.json.</summary>
internal sealed class DeviceRegistry(string directory, TimeProvider time)
{
    private readonly string _path = Path.Combine(directory, "devices.json");
    private readonly Lock _gate = new();
    private List<DeviceRecord>? _devices;

    /// <summary>Registers a device.</summary>
    /// <returns>Its id and token; the token is not stored and cannot be read back.</returns>
    internal (byte[] Id, byte[] Token) Add(string name)
    {
        var id = RandomNumberGenerator.GetBytes(Frames.ID_SIZE);
        var token = RandomNumberGenerator.GetBytes(Frames.TOKEN_SIZE);
        var record = new DeviceRecord(Convert.ToHexStringLower(id), name, Convert.ToBase64String(SHA256.HashData(token)), time.GetUtcNow(), null);
        lock (_gate)
        {
            Devices().Add(record);
            JsonFile.Write(_path, Devices());
        }
        return (id, token);
    }

    /// <summary>Checks HELLO credentials; the token hash is compared in constant time.</summary>
    /// <returns>The device, or null when the id is unknown or the token does not match.</returns>
    internal DeviceRecord? Verify(ReadOnlySpan<byte> id, ReadOnlySpan<byte> token)
    {
        Span<byte> hash = stackalloc byte[SHA256.HashSizeInBytes];
        Span<byte> stored = stackalloc byte[SHA256.HashSizeInBytes];
        SHA256.HashData(token, hash);
        var key = Convert.ToHexStringLower(id);
        DeviceRecord? device;
        lock (_gate) device = Devices().Find(d => d.Id == key);
        return device is not null
            && Convert.TryFromBase64String(device.TokenHash, stored, out var length)
            && length == stored.Length
            && CryptographicOperations.FixedTimeEquals(hash, stored) ? device : null;
    }

    /// <summary>Records that the device connected now.</summary>
    internal void Touch(string id)
    {
        lock (_gate)
        {
            var devices = Devices();
            var index = devices.FindIndex(d => d.Id == id);
            if (index < 0) return;
            devices[index] = devices[index] with { LastSeenAt = time.GetUtcNow() };
            JsonFile.Write(_path, devices);
        }
    }

    /// <returns>false when no device has this id.</returns>
    internal bool Revoke(string id)
    {
        lock (_gate)
        {
            if (Devices().RemoveAll(d => d.Id == id) == 0) return false;
            JsonFile.Write(_path, Devices());
            return true;
        }
    }

    internal List<DeviceRecord> List()
    {
        lock (_gate) return [.. Devices()];
    }

    private List<DeviceRecord> Devices() => _devices ??= JsonFile.Read<List<DeviceRecord>>(_path) ?? [];
}
```
`Relay/PairingCodes.cs`:
```csharp
using System.Security.Cryptography;
using KeyRelay.Protocol;

namespace KeyRelay.Service.Relay;

/// <summary>The single pairing code shown in the QR: 16 random bytes, valid 5 minutes, usable once.</summary>
internal sealed class PairingCodes(TimeProvider time)
{
    private static readonly TimeSpan LIFETIME = TimeSpan.FromMinutes(5);
    private readonly Lock _gate = new();
    private byte[]? _code;
    private DateTimeOffset _expiresAt;

    /// <summary>Gets when the active code expires, or null when no code is active.</summary>
    internal DateTimeOffset? ActiveUntil
    {
        get
        {
            lock (_gate) return _code is not null && time.GetUtcNow() < _expiresAt ? _expiresAt : null;
        }
    }

    /// <summary>Creates a new code; the previous one stops working.</summary>
    internal (byte[] Code, DateTimeOffset ExpiresAt) Start()
    {
        var code = RandomNumberGenerator.GetBytes(Frames.CODE_SIZE);
        lock (_gate)
        {
            _code = code;
            _expiresAt = time.GetUtcNow() + LIFETIME;
            return ((byte[])code.Clone(), _expiresAt);
        }
    }

    internal void Stop()
    {
        lock (_gate) _code = null;
    }

    /// <summary>Uses up the code if it matches and has not expired; compared in constant time.</summary>
    internal bool TryConsume(ReadOnlySpan<byte> code)
    {
        lock (_gate)
        {
            if (_code is null || time.GetUtcNow() >= _expiresAt || !CryptographicOperations.FixedTimeEquals(_code, code)) return false;
            _code = null;
            return true;
        }
    }
}
```
`Relay/AuthThrottle.cs`:
```csharp
namespace KeyRelay.Service.Relay;

/// <summary>
/// Slows failed PAIR and HELLO attempts per key (source address plus device id, or "pair"): 1 s, 2 s, 4 s ... up to 60 s.
/// Funnel traffic arrives from loopback, so the device id keeps one attacker from blocking a real tablet.
/// </summary>
internal sealed class AuthThrottle(TimeProvider time)
{
    private const int MAX_ENTRIES = 1024;
    private static readonly TimeSpan FIRST = TimeSpan.FromSeconds(1);
    private static readonly TimeSpan MAX = TimeSpan.FromSeconds(60);
    private readonly Lock _gate = new();
    private readonly Dictionary<string, (int Failures, DateTimeOffset Until)> _entries = [];

    internal bool IsBlocked(string key)
    {
        lock (_gate) return _entries.TryGetValue(key, out var entry) && time.GetUtcNow() < entry.Until;
    }

    internal void Failed(string key)
    {
        lock (_gate)
        {
            if (_entries.Count >= MAX_ENTRIES) Prune();
            var failures = _entries.TryGetValue(key, out var entry) ? entry.Failures + 1 : 1;
            _entries[key] = (failures, time.GetUtcNow() + Backoff.Delay(failures, FIRST, MAX));
        }
    }

    internal void Succeeded(string key)
    {
        lock (_gate) _entries.Remove(key);
    }

    // Keeps memory bounded when many addresses fail; expired entries go first.
    private void Prune()
    {
        var now = time.GetUtcNow();
        foreach (var key in _entries.Where(e => e.Value.Until <= now).Select(e => e.Key).ToList()) _entries.Remove(key);
        if (_entries.Count >= MAX_ENTRIES) _entries.Clear();
    }
}
```
`Settings/ServiceSettings.cs`:
```csharp
using System.Globalization;

namespace KeyRelay.Service.Settings;

/// <summary>Service settings in ProgramData\KeyRelay\settings.json; the defaults live here and nowhere else.</summary>
internal sealed record ServiceSettings
{
    private static readonly int[] FUNNEL_PORTS = [443, 8443, 10000];

    /// <summary>TCP port of the TLS listener.</summary>
    public int Port { get; init; } = 47111;

    /// <summary>Seconds of silence after which the service closes a tablet connection (protocol idleTimeout).</summary>
    public int IdleTimeoutSeconds { get; init; } = 20;

    /// <summary>Tailscale Funnel address (host:port) put first into the pairing QR; empty when Funnel is not used.</summary>
    public string FunnelAddress { get; init; } = "";

    /// <summary>Checks a Funnel address: empty, or DNS name and a port Funnel can expose (443, 8443, 10000).</summary>
    internal static bool IsValidFunnelAddress(string address)
    {
        if (address.Length == 0) return true;
        var colon = address.LastIndexOf(':');
        return colon > 0
            && Uri.CheckHostName(address[..colon]) == UriHostNameType.Dns
            && int.TryParse(address.AsSpan(colon + 1), NumberStyles.None, CultureInfo.InvariantCulture, out var port)
            && FUNNEL_PORTS.Contains(port);
    }
}

/// <summary>Reads and writes settings.json; a missing file means defaults.</summary>
internal sealed class SettingsStore(string directory)
{
    private readonly string _path = Path.Combine(directory, "settings.json");
    private readonly Lock _gate = new();

    internal ServiceSettings Load()
    {
        ServiceSettings? stored;
        lock (_gate) stored = JsonFile.Read<ServiceSettings>(_path);
        var defaults = new ServiceSettings();
        if (stored is null) return defaults;
        // A hand-edited file must not stop the service: a bad port throws outside the bind retry,
        // a zero or negative timeout closes every session the moment it opens.
        if (stored.Port is <= 0 or > 65535) stored = stored with { Port = defaults.Port };
        return stored.IdleTimeoutSeconds > 0 ? stored : stored with { IdleTimeoutSeconds = defaults.IdleTimeoutSeconds };
    }

    internal void Save(ServiceSettings settings)
    {
        lock (_gate) JsonFile.Write(_path, settings);
    }
}
```

- [ ] **Step 4: Testlerin geçtiğini gör**

Run: `dotnet test KeyRelay.slnx --filter "FullyQualifiedName~KeyRelay.Tests.Relay|FullyQualifiedName~KeyRelay.Tests.Settings"` → hepsi geçer (`a,b.ts.net` için `Uri.CheckHostName` `Unknown` döndürür). Tüm çözüm de geçer.

- [ ] **Step 5: Commit**

```powershell
git -C $WT add windows/src/KeyRelay.Service windows/tests/KeyRelay.Tests
git -C $WT commit -m "feat(windows): cihaz kaydı, eşleştirme kodu ve deneme sınırı eklendi" -m "- eşlenen tabletler pc'de saklanır, şifrenin kendisi hiç yazılmaz
- eşleştirme kodu 5 dakika geçerli ve tek kullanımlık
- art arda hatalı denemeler giderek yavaşlatılır
- port, bağlantı süresi ve funnel adresi ayar dosyasından okunur" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Sertifika, TLS dinleyici, eşleştirme ve kimlik doğrulama, tek aktif oturum

**Files:**
- Create: `windows/src/KeyRelay.Service/Relay/CertificateStore.cs`, `windows/src/KeyRelay.Service/Relay/RelayContext.cs`, `windows/src/KeyRelay.Service/Relay/RelayHub.cs`, `windows/src/KeyRelay.Service/Relay/RelaySession.cs`, `windows/src/KeyRelay.Service/Relay/RelayListener.cs`
- Modify: `windows/src/KeyRelay.Service/Native/Sas.cs` (`SendSas` delegesi), `windows/src/KeyRelay.Service/KeyRelay.Service.csproj`, `windows/Directory.Packages.props`, `windows/src/KeyRelay.Service/Program.cs`
- Test: `windows/tests/KeyRelay.Tests/Relay/CertificateStoreTests.cs`, `windows/tests/KeyRelay.Tests/TestSupport/TabletClient.cs`, `windows/tests/KeyRelay.Tests/TestSupport/RelayHarness.cs`, `windows/tests/KeyRelay.Tests/Relay/HandshakeTests.cs`

**Interfaces:**
- Consumes: T2 `FrameReader`, `Frames`, `RejectReason`; T6 `AgentLink`, `LatencyStats`, `FakeAgent`, `TestPipes`; T8 `DeviceRegistry`, `PairingCodes`, `AuthThrottle`, `SettingsStore`, `ServicePaths`, `TempDirectory`.
- Produces:
  - `internal static class CertificateStore { static X509Certificate2 LoadOrCreate(string directory); static string Fingerprint(X509Certificate2 certificate); }` — `certificate.bin` = DPAPI LocalMachine ile korunan PKCS#12; parmak izi = SPKI SHA-256, base64url (QR `fp`).
  - `internal delegate void SendSas();`
  - `internal sealed record RelayContext(X509Certificate2 Certificate, DeviceRegistry Devices, PairingCodes Pairing, AuthThrottle Throttle, AgentLink Agent, SendSas SendSas, SettingsStore Settings, ILogger Logger)` + `TimeSpan HelloTimeout { init; }` (5 sn).
  - `internal sealed partial class RelayHub(RelayContext context, ILogger<RelayHub> logger)`: `Task HandleAsync(Socket, CancellationToken)` (hiç fırlatmaz), `void CloseDevice(string deviceId)`, `string? ActiveDeviceName`, `string? ActiveDeviceId`.
  - `internal sealed partial class RelaySession(SslStream ssl, FrameReader reader, DeviceRecord device, RelayContext context) : IDisposable`: `Task RunAsync(CancellationToken)`, `void Close()`, `DeviceRecord Device` (T10 tuş iletimini ekler).
  - `internal sealed partial class RelayListener(RelayHub hub, SettingsStore settings, ILogger<RelayListener> logger) : BackgroundService`.
  - Test yardımcıları: `RelayHarness.StartAsync(bool connectAgent = true, int idleTimeoutSeconds = 20, double helloTimeoutSeconds = 5)`, `PairAsync(string name = "Tab")`, `HelloAsync((byte[] Id, byte[] Token))`, `HelloResultAsync(byte[] id, byte[] token)`, `ConnectTabletAsync()`, özellikler `Time, Settings, Certificate, Fingerprint, Devices, Pairing, Throttle, Latency, Agent, Hub, AgentPipe, SasCalls, Port`; `TabletClient` (`SendAsync`, `NextAsync`, `ExpectAsync(FrameType)`, `IsClosedAsync`, `Protocol`).

- [ ] **Step 1: Paket**

`windows/Directory.Packages.props` `ItemGroup`'una:
```xml
    <PackageVersion Include="System.Security.Cryptography.ProtectedData" Version="10.0.12" />
```
`KeyRelay.Service.csproj` `ItemGroup`'una `<PackageReference Include="System.Security.Cryptography.ProtectedData" />`.

- [ ] **Step 2: Test yardımcıları**

`TestSupport/TabletClient.cs`:
```csharp
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;
using KeyRelay.Protocol;
using KeyRelay.Service.Relay;

namespace KeyRelay.Tests.TestSupport;

/// <summary>Stand-in for the tablet: a TLS 1.3 client that accepts only the pinned service key.</summary>
internal sealed class TabletClient : IAsyncDisposable
{
    private static readonly TimeSpan TIMEOUT = TimeSpan.FromSeconds(5);
    private readonly TcpClient _tcp;
    private readonly SslStream _ssl;
    private readonly FrameReader _reader;

    private TabletClient(TcpClient tcp, SslStream ssl)
    {
        _tcp = tcp;
        _ssl = ssl;
        _reader = new FrameReader(ssl, FrameChannel.Tls);
    }

    internal SslProtocols Protocol => _ssl.SslProtocol;

    internal static async Task<TabletClient> ConnectAsync(int port, string fingerprint)
    {
        var tcp = new TcpClient();
        await tcp.ConnectAsync(IPAddress.Loopback, port);
        var ssl = new SslStream(tcp.GetStream(), false, (_, certificate, _, _) => certificate is not null && Fingerprint(certificate) == fingerprint);
        await ssl.AuthenticateAsClientAsync(new SslClientAuthenticationOptions { TargetHost = "keyrelay", EnabledSslProtocols = SslProtocols.Tls13 });
        return new TabletClient(tcp, ssl);
    }

    internal Task SendAsync(byte[] frame) => _ssl.WriteAsync(frame).AsTask();

    /// <summary>Reads the next frame that is not STATE.</summary>
    internal async Task<(FrameType Type, byte[] Payload)> NextAsync()
    {
        while (true)
        {
            var frame = await ReadAsync() ?? throw new AssertFailedException("service closed the connection");
            if (frame.Type != FrameType.State) return frame;
        }
    }

    /// <summary>Reads until a frame of the given type; STATE frames are skipped unless STATE is asked for.</summary>
    internal async Task<(FrameType Type, byte[] Payload)> ExpectAsync(FrameType type)
    {
        while (true)
        {
            var frame = await ReadAsync() ?? throw new AssertFailedException($"service closed the connection while {type} was expected");
            if (frame.Type == type) return frame;
            if (frame.Type != FrameType.State) throw new AssertFailedException($"expected {type}, got {frame.Type}");
        }
    }

    /// <summary>True when the service closes the connection within 5 s (REJECT and STATE before the close are ignored).</summary>
    internal async Task<bool> IsClosedAsync()
    {
        try
        {
            while (await ReadAsync() is { } frame)
            {
                if (frame.Type is not (FrameType.State or FrameType.Reject)) return false;
            }
            return true;
        }
        catch (IOException)
        {
            return true;
        }
        catch (OperationCanceledException)
        {
            return false;
        }
    }

    public async ValueTask DisposeAsync()
    {
        await _ssl.DisposeAsync();
        _tcp.Dispose();
    }

    private async Task<(FrameType Type, byte[] Payload)?> ReadAsync()
    {
        using var timeout = new CancellationTokenSource(TIMEOUT);
        var frame = await _reader.ReadAsync(timeout.Token);
        return frame is { } f ? (f.Type, f.Payload.ToArray()) : null;
    }

    private static string Fingerprint(X509Certificate certificate)
    {
        using var x509 = X509CertificateLoader.LoadCertificate(certificate.GetRawCertData());
        return CertificateStore.Fingerprint(x509);
    }
}
```
`TestSupport/RelayHarness.cs`:
```csharp
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography.X509Certificates;
using KeyRelay.Protocol;
using KeyRelay.Service.Agent;
using KeyRelay.Service.Relay;
using KeyRelay.Service.Settings;
using Microsoft.Extensions.Logging.Abstractions;

namespace KeyRelay.Tests.TestSupport;

/// <summary>A RelayHub on a loopback port with a fake agent on a private pipe and a manual clock.</summary>
internal sealed class RelayHarness : IAsyncDisposable
{
    private readonly TempDirectory _directory = new();
    private readonly Socket _listener = new(AddressFamily.InterNetwork, SocketType.Stream, ProtocolType.Tcp);
    private readonly CancellationTokenSource _stop = new();
    private readonly string _agentPipe = TestPipes.Name();
    private Task _agentRun = Task.CompletedTask;
    private int _sasCalls;

    private RelayHarness(int idleTimeoutSeconds, TimeSpan helloTimeout)
    {
        Settings = new SettingsStore(_directory.Path);
        Settings.Save(new ServiceSettings { IdleTimeoutSeconds = idleTimeoutSeconds });
        Certificate = CertificateStore.LoadOrCreate(_directory.Path);
        Fingerprint = CertificateStore.Fingerprint(Certificate);
        Devices = new DeviceRegistry(_directory.Path, Time);
        Pairing = new PairingCodes(Time);
        Throttle = new AuthThrottle(Time);
        Latency = new LatencyStats(Time);
        Agent = new AgentLink(new AgentLinkOptions(_agentPipe, TestPipes.CurrentUser()), Latency, NullLogger<AgentLink>.Instance);
        var context = new RelayContext(Certificate, Devices, Pairing, Throttle, Agent, () => Interlocked.Increment(ref _sasCalls), Settings, NullLogger.Instance)
        {
            HelloTimeout = helloTimeout,
        };
        Hub = new RelayHub(context, NullLogger<RelayHub>.Instance);
        _listener.Bind(new IPEndPoint(IPAddress.Loopback, 0));
        _listener.Listen();
        _ = AcceptLoopAsync();
    }

    internal ManualTime Time { get; } = new(new DateTimeOffset(2026, 10, 8, 10, 0, 0, TimeSpan.Zero));

    internal SettingsStore Settings { get; }

    internal X509Certificate2 Certificate { get; }

    internal string Fingerprint { get; }

    internal DeviceRegistry Devices { get; }

    internal PairingCodes Pairing { get; }

    internal AuthThrottle Throttle { get; }

    internal LatencyStats Latency { get; }

    internal AgentLink Agent { get; }

    internal RelayHub Hub { get; }

    internal FakeAgent? AgentPipe { get; private set; }

    internal int SasCalls => Volatile.Read(ref _sasCalls);

    internal int Port => ((IPEndPoint)_listener.LocalEndPoint!).Port;

    internal static async Task<RelayHarness> StartAsync(bool connectAgent = true, int idleTimeoutSeconds = 20, double helloTimeoutSeconds = 5)
    {
        var harness = new RelayHarness(idleTimeoutSeconds, TimeSpan.FromSeconds(helloTimeoutSeconds));
        if (connectAgent) await harness.ConnectAgentAsync();
        return harness;
    }

    internal Task<TabletClient> ConnectTabletAsync() => TabletClient.ConnectAsync(Port, Fingerprint);

    /// <summary>Pairs a device over TLS (the connection closes after PAIRED).</summary>
    internal async Task<(byte[] Id, byte[] Token)> PairAsync(string name = "Tab")
    {
        var (code, _) = Pairing.Start();
        await using var tablet = await ConnectTabletAsync();
        await tablet.SendAsync(Frames.Pair(code, name));
        var paired = await tablet.ExpectAsync(FrameType.Paired);
        return (paired.Payload[..Frames.ID_SIZE], paired.Payload[Frames.ID_SIZE..]);
    }

    /// <summary>Connects and authenticates; returns after WELCOME.</summary>
    internal async Task<TabletClient> HelloAsync((byte[] Id, byte[] Token) device)
    {
        var tablet = await ConnectTabletAsync();
        await tablet.SendAsync(Frames.Hello(device.Id, device.Token));
        await tablet.ExpectAsync(FrameType.Welcome);
        return tablet;
    }

    /// <summary>Sends HELLO on a fresh connection and returns the type of the answer.</summary>
    internal async Task<FrameType> HelloResultAsync(byte[] id, byte[] token)
    {
        await using var tablet = await ConnectTabletAsync();
        await tablet.SendAsync(Frames.Hello(id, token));
        return (await tablet.NextAsync()).Type;
    }

    public async ValueTask DisposeAsync()
    {
        await _stop.CancelAsync();
        _listener.Dispose();
        if (AgentPipe is not null) await AgentPipe.DisposeAsync();
        await _agentRun;
        Agent.Dispose();
        Certificate.Dispose();
        _stop.Dispose();
        _directory.Dispose();
    }

    private async Task ConnectAgentAsync()
    {
        _agentRun = Agent.RunAsync(Environment.ProcessId, _stop.Token);
        AgentPipe = await FakeAgent.ConnectAsync(_agentPipe);
        await Eventually.TrueAsync(() => Agent.Ready, "agent link did not become ready");
    }

    private async Task AcceptLoopAsync()
    {
        try
        {
            while (true)
            {
                var socket = await _listener.AcceptAsync(_stop.Token);
                _ = Hub.HandleAsync(socket, _stop.Token);
            }
        }
        catch (Exception ex) when (ex is OperationCanceledException or ObjectDisposedException or SocketException)
        {
            // Harness disposed.
        }
    }
}
```

- [ ] **Step 3: Failing testleri yaz**

`Relay/CertificateStoreTests.cs`:
```csharp
using System.Buffers.Text;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using KeyRelay.Service.Relay;
using KeyRelay.Tests.TestSupport;

namespace KeyRelay.Tests.Relay;

[TestClass]
public sealed class CertificateStoreTests : IDisposable
{
    private readonly TempDirectory _directory = new();

    public void Dispose() => _directory.Dispose();

    [TestMethod]
    public void LoadOrCreate_Twice_ReturnsSameKey()
    {
        using var first = CertificateStore.LoadOrCreate(_directory.Path);
        using var second = CertificateStore.LoadOrCreate(_directory.Path);

        Assert.AreEqual(CertificateStore.Fingerprint(first), CertificateStore.Fingerprint(second));
    }

    [TestMethod]
    public void LoadOrCreate_NewCertificate_IsEcdsaP256WithPrivateKey()
    {
        using var certificate = CertificateStore.LoadOrCreate(_directory.Path);
        using var key = certificate.GetECDsaPublicKey();

        Assert.AreEqual(256, key?.KeySize);
        Assert.IsTrue(certificate.HasPrivateKey);
    }

    [TestMethod]
    public void LoadOrCreate_FileOnDisk_IsNotReadableAsPlainPkcs12()
    {
        using var _ = CertificateStore.LoadOrCreate(_directory.Path);
        var bytes = File.ReadAllBytes(Path.Combine(_directory.Path, "certificate.bin"));

        Assert.Throws<CryptographicException>(() => X509CertificateLoader.LoadPkcs12(bytes, null));
    }

    [TestMethod]
    public void Fingerprint_Certificate_IsBase64UrlSha256OfPublicKeyInfo()
    {
        using var certificate = CertificateStore.LoadOrCreate(_directory.Path);
        var expected = Base64Url.EncodeToString(SHA256.HashData(certificate.PublicKey.ExportSubjectPublicKeyInfo()));

        var fingerprint = CertificateStore.Fingerprint(certificate);

        Assert.AreEqual(expected, fingerprint);
        Assert.AreEqual(43, fingerprint.Length);
    }
}
```
`Relay/HandshakeTests.cs`:
```csharp
using System.Security.Authentication;
using System.Text;
using KeyRelay.Protocol;
using KeyRelay.Tests.TestSupport;

namespace KeyRelay.Tests.Relay;

[TestClass]
public sealed class HandshakeTests
{
    [TestMethod]
    public async Task Pair_ValidCode_PairsAndSameConnectionHelloIsWelcomed()
    {
        await using var harness = await RelayHarness.StartAsync();
        var (code, _) = harness.Pairing.Start();
        await using var tablet = await harness.ConnectTabletAsync();

        await tablet.SendAsync(Frames.Pair(code, "Tab"));
        var paired = await tablet.ExpectAsync(FrameType.Paired);
        await tablet.SendAsync(Frames.Hello(paired.Payload[..Frames.ID_SIZE], paired.Payload[Frames.ID_SIZE..]));
        var welcome = await tablet.ExpectAsync(FrameType.Welcome);

        Assert.AreEqual(Environment.MachineName, Encoding.UTF8.GetString(welcome.Payload));
        Assert.AreEqual("Tab", harness.Devices.List().Single().Name);
    }

    [TestMethod]
    public async Task Pair_CodeAtFiveMinutes_RejectsInvalidCodeAndCloses()
    {
        await using var harness = await RelayHarness.StartAsync();
        var (code, _) = harness.Pairing.Start();
        harness.Time.Advance(TimeSpan.FromMinutes(5));
        await using var tablet = await harness.ConnectTabletAsync();

        await tablet.SendAsync(Frames.Pair(code, "Tab"));
        var reject = await tablet.ExpectAsync(FrameType.Reject);

        Assert.AreEqual((byte)RejectReason.InvalidCode, reject.Payload[0]);
        Assert.IsTrue(await tablet.IsClosedAsync());
        Assert.IsEmpty(harness.Devices.List());
    }

    [TestMethod]
    public async Task Pair_CodeUsedTwice_SecondIsRejected()
    {
        await using var harness = await RelayHarness.StartAsync();
        var (code, _) = harness.Pairing.Start();
        await using (var first = await harness.ConnectTabletAsync())
        {
            await first.SendAsync(Frames.Pair(code, "Tab"));
            await first.ExpectAsync(FrameType.Paired);
        }
        await using var second = await harness.ConnectTabletAsync();

        await second.SendAsync(Frames.Pair(code, "Other"));
        var reject = await second.ExpectAsync(FrameType.Reject);

        Assert.AreEqual((byte)RejectReason.InvalidCode, reject.Payload[0]);
        Assert.HasCount(1, harness.Devices.List());
    }

    [TestMethod]
    public async Task Hello_RevokedDevice_RejectsUnknownDevice()
    {
        await using var harness = await RelayHarness.StartAsync();
        var device = await harness.PairAsync();
        harness.Devices.Revoke(Convert.ToHexStringLower(device.Id));

        var answer = await harness.HelloResultAsync(device.Id, device.Token);

        Assert.AreEqual(FrameType.Reject, answer);
    }

    [TestMethod]
    public async Task Hello_WrongToken_RejectsAndSlowsDownTheNextAttempt()
    {
        await using var harness = await RelayHarness.StartAsync();
        var device = await harness.PairAsync();
        var wrong = (byte[])device.Token.Clone();
        wrong[0] ^= 0xFF;

        var first = await harness.HelloResultAsync(device.Id, wrong);
        // An attempt while blocked counts as a failure too: 2 failures = 2 s.
        var blocked = await harness.HelloResultAsync(device.Id, device.Token);
        harness.Time.Advance(TimeSpan.FromSeconds(2));
        var afterWait = await harness.HelloResultAsync(device.Id, device.Token);

        Assert.AreEqual(FrameType.Reject, first);
        Assert.AreEqual(FrameType.Reject, blocked);
        Assert.AreEqual(FrameType.Welcome, afterWait);
    }

    [TestMethod]
    public async Task Hello_NewerProtocolVersion_RejectsVersion()
    {
        await using var harness = await RelayHarness.StartAsync();
        var device = await harness.PairAsync();
        var hello = Frames.Hello(device.Id, device.Token);
        hello[Frame.HEADER_SIZE + 1] = 2;
        await using var tablet = await harness.ConnectTabletAsync();

        await tablet.SendAsync(hello);
        var reject = await tablet.ExpectAsync(FrameType.Reject);

        Assert.AreEqual((byte)RejectReason.Version, reject.Payload[0]);
    }

    [TestMethod]
    public async Task Connect_NoHelloWithinTimeout_IsClosed()
    {
        await using var harness = await RelayHarness.StartAsync(helloTimeoutSeconds: 0.5);
        await using var tablet = await harness.ConnectTabletAsync();

        Assert.IsTrue(await tablet.IsClosedAsync());
    }

    [TestMethod]
    public async Task Connect_FirstFrameIsPing_IsClosedWithoutWelcome()
    {
        await using var harness = await RelayHarness.StartAsync();
        await using var tablet = await harness.ConnectTabletAsync();

        await tablet.SendAsync(Frames.Ping(1));

        Assert.IsTrue(await tablet.IsClosedAsync());
    }

    [TestMethod]
    public async Task Connect_LengthOver512_IsClosed()
    {
        await using var harness = await RelayHarness.StartAsync();
        await using var tablet = await harness.ConnectTabletAsync();

        await tablet.SendAsync([0x02, 0x01]);

        Assert.IsTrue(await tablet.IsClosedAsync());
    }

    [TestMethod]
    public async Task Hello_SecondVerifiedClient_ReplacesFirstAndReleasesKeys()
    {
        await using var harness = await RelayHarness.StartAsync();
        var device = await harness.PairAsync();
        await using var first = await harness.HelloAsync(device);

        await using var second = await harness.HelloAsync(device);

        Assert.IsTrue(await first.IsClosedAsync());
        CollectionAssert.AreEqual(Frames.ReleaseAllFrame.ToArray(), await harness.AgentPipe!.ReadAsync());
        await second.SendAsync(Frames.Ping(7));
        await second.ExpectAsync(FrameType.Pong);
    }

    [TestMethod]
    public async Task Hello_UnverifiedSecondClient_KeepsFirstSession()
    {
        await using var harness = await RelayHarness.StartAsync();
        var device = await harness.PairAsync();
        await using var first = await harness.HelloAsync(device);
        var wrong = (byte[])device.Token.Clone();
        wrong[0] ^= 0xFF;

        var answer = await harness.HelloResultAsync(device.Id, wrong);
        await first.SendAsync(Frames.Ping(1));
        var pong = await first.ExpectAsync(FrameType.Pong);

        Assert.AreEqual(FrameType.Reject, answer);
        Assert.AreEqual(1u, Frames.ReadSeq(pong.Payload));
    }

    [TestMethod]
    public async Task CloseDevice_ActiveDevice_DropsSessionAtOnce()
    {
        await using var harness = await RelayHarness.StartAsync();
        var device = await harness.PairAsync();
        await using var tablet = await harness.HelloAsync(device);
        await Eventually.TrueAsync(() => harness.Hub.ActiveDeviceId is not null, "session did not become active");

        harness.Hub.CloseDevice(Convert.ToHexStringLower(device.Id));

        Assert.IsTrue(await tablet.IsClosedAsync());
        CollectionAssert.AreEqual(Frames.ReleaseAllFrame.ToArray(), await harness.AgentPipe!.ReadAsync());
    }

    [TestMethod]
    public async Task Session_NoFramesForIdleTimeout_IsClosed()
    {
        await using var harness = await RelayHarness.StartAsync(idleTimeoutSeconds: 1);
        await using var tablet = await harness.HelloAsync(await harness.PairAsync());

        Assert.IsTrue(await tablet.IsClosedAsync());
    }

    [TestMethod]
    public async Task Session_Ping_AnswersPongWithSameSequence()
    {
        await using var harness = await RelayHarness.StartAsync();
        await using var tablet = await harness.HelloAsync(await harness.PairAsync());

        await tablet.SendAsync(Frames.Ping(16909060));
        var pong = await tablet.ExpectAsync(FrameType.Pong);

        Assert.AreEqual(16909060u, Frames.ReadSeq(pong.Payload));
    }

    [TestMethod]
    public async Task Connect_Tls_NegotiatesTls13()
    {
        await using var harness = await RelayHarness.StartAsync();

        await using var tablet = await harness.ConnectTabletAsync();

        Assert.AreEqual(SslProtocols.Tls13, tablet.Protocol);
    }
}
```

- [ ] **Step 4: Başarısız olduğunu gör**

Run: `dotnet test KeyRelay.slnx --filter "FullyQualifiedName~CertificateStoreTests|FullyQualifiedName~HandshakeTests"` → derleme hatası.

- [ ] **Step 5: Uygulamayı yaz**

`Native/Sas.cs` dosyasının sonuna (sınıfın dışına):
```csharp
/// <summary>Sends Ctrl+Alt+Del to the console session; a delegate so tests do not open the security screen.</summary>
internal delegate void SendSas();
```
`Relay/CertificateStore.cs`:
```csharp
using System.Buffers.Text;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace KeyRelay.Service.Relay;

/// <summary>The service's TLS certificate: ECDSA P-256, stored as PKCS#12 protected with DPAPI LocalMachine.</summary>
internal static class CertificateStore
{
    private const string FILE_NAME = "certificate.bin";
    private const int VALID_YEARS = 20;

    /// <summary>Loads the certificate, creating it on first use.</summary>
    /// <param name="directory">ProgramData\KeyRelay (its ACL limits the file to SYSTEM and Administrators).</param>
    internal static X509Certificate2 LoadOrCreate(string directory)
    {
        var path = Path.Combine(directory, FILE_NAME);
        if (!File.Exists(path))
        {
            using var created = Create();
            File.WriteAllBytes(path, ProtectedData.Protect(created.Export(X509ContentType.Pkcs12), null, DataProtectionScope.LocalMachine));
        }
        var pkcs12 = ProtectedData.Unprotect(File.ReadAllBytes(path), null, DataProtectionScope.LocalMachine);
        // SChannel needs the key in a key container; without PersistKeySet it is removed when the certificate is disposed.
        return X509CertificateLoader.LoadPkcs12(pkcs12, null, X509KeyStorageFlags.MachineKeySet);
    }

    /// <summary>SHA-256 of the SubjectPublicKeyInfo, base64url; the tablet pins this value (QR "fp").</summary>
    internal static string Fingerprint(X509Certificate2 certificate) =>
        Base64Url.EncodeToString(SHA256.HashData(certificate.PublicKey.ExportSubjectPublicKeyInfo()));

    private static X509Certificate2 Create()
    {
        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var request = new CertificateRequest("CN=KeyRelay", key, HashAlgorithmName.SHA256);
        var now = DateTimeOffset.UtcNow;
        return request.CreateSelfSigned(now.AddDays(-1), now.AddYears(VALID_YEARS));
    }
}
```
`Relay/RelayContext.cs`:
```csharp
using System.Security.Cryptography.X509Certificates;
using KeyRelay.Service.Agent;
using KeyRelay.Service.Native;
using KeyRelay.Service.Settings;

namespace KeyRelay.Service.Relay;

/// <summary>Collaborators of the tablet side, grouped so RelayHub and RelaySession take one parameter for them.</summary>
internal sealed record RelayContext(
    X509Certificate2 Certificate,
    DeviceRegistry Devices,
    PairingCodes Pairing,
    AuthThrottle Throttle,
    AgentLink Agent,
    SendSas SendSas,
    SettingsStore Settings,
    ILogger Logger)
{
    /// <summary>Time from accept to a verified HELLO, pairing included (spec: 5 s).</summary>
    internal TimeSpan HelloTimeout { get; init; } = TimeSpan.FromSeconds(5);
}
```
`Relay/RelaySession.cs` (T10 tuş iletimini ekler):
```csharp
using System.Net.Security;
using KeyRelay.Protocol;

namespace KeyRelay.Service.Relay;

/// <summary>One verified tablet connection: answers PING and ends on idle, close or replacement.</summary>
internal sealed partial class RelaySession(SslStream ssl, FrameReader reader, DeviceRecord device, RelayContext context) : IDisposable
{
    // A tablet that does not take a frame within 1 s is gone.
    private static readonly TimeSpan SEND_TIMEOUT = TimeSpan.FromSeconds(1);
    private readonly CancellationTokenSource _closed = new();
    private readonly SemaphoreSlim _writeGate = new(1, 1);

    internal DeviceRecord Device => device;

    /// <summary>Reads frames until the tablet leaves, stays silent for idleTimeout or Close is called.</summary>
    internal async Task RunAsync(CancellationToken cancellationToken)
    {
        using var idle = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, _closed.Token);
        var idleTimeout = TimeSpan.FromSeconds(context.Settings.Load().IdleTimeoutSeconds);
        idle.CancelAfter(idleTimeout);
        while (await reader.ReadAsync(idle.Token).ConfigureAwait(false) is { } frame)
        {
            idle.CancelAfter(idleTimeout);
            if (!await HandleAsync(frame).ConfigureAwait(false)) return;
        }
    }

    /// <summary>Ends the session (replaced by a newer connection or device revoked).</summary>
    internal void Close()
    {
        try
        {
            _closed.Cancel();
        }
        catch (ObjectDisposedException)
        {
            // Already ended.
        }
    }

    public void Dispose()
    {
        _closed.Dispose();
        _writeGate.Dispose();
    }

    private async ValueTask<bool> HandleAsync(Frame frame)
    {
        switch (frame.Type)
        {
            case FrameType.Ping:
                return await WriteAsync(Frames.Pong(Frames.ReadSeq(frame.Payload.Span))).ConfigureAwait(false);
            default:
                return false;
        }
    }

    /// <summary>Writes one frame; TLS allows one writer at a time. A write slower than 1 s ends the session.</summary>
    private async ValueTask<bool> WriteAsync(byte[] frame)
    {
        try
        {
            await _writeGate.WaitAsync(_closed.Token).ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is OperationCanceledException or ObjectDisposedException)
        {
            return false;
        }
        try
        {
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(_closed.Token);
            timeout.CancelAfter(SEND_TIMEOUT);
            await ssl.WriteAsync(frame, timeout.Token).ConfigureAwait(false);
            return true;
        }
        catch (Exception ex) when (ex is IOException or OperationCanceledException or ObjectDisposedException)
        {
            Close();
            return false;
        }
        finally
        {
            _writeGate.Release();
        }
    }
}
```
`Relay/RelayHub.cs`:
```csharp
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Text;
using KeyRelay.Protocol;

namespace KeyRelay.Service.Relay;

/// <summary>Serves tablet connections: TLS 1.3, PAIR and HELLO within 5 s, then one active session at a time.</summary>
internal sealed partial class RelayHub(RelayContext context, ILogger<RelayHub> logger)
{
    private const int MAX_DEVICE_NAME = 64;
    private const string FALLBACK_DEVICE_NAME = "Android";
    private readonly Lock _gate = new();
    private RelaySession? _active;

    internal string? ActiveDeviceName
    {
        get { lock (_gate) return _active?.Device.Name; }
    }

    internal string? ActiveDeviceId
    {
        get { lock (_gate) return _active?.Device.Id; }
    }

    /// <summary>Serves one accepted socket until it closes. Never throws.</summary>
    internal async Task HandleAsync(Socket socket, CancellationToken cancellationToken)
    {
        var remote = (socket.RemoteEndPoint as IPEndPoint)?.Address.ToString() ?? "?";
        try
        {
            var ssl = new SslStream(new NetworkStream(socket, ownsSocket: true));
            await using (ssl.ConfigureAwait(false))
            {
                var reader = new FrameReader(ssl, FrameChannel.Tls);
                var device = await HandshakeAsync(ssl, reader, remote, cancellationToken).ConfigureAwait(false);
                if (device is not null) await RunSessionAsync(new RelaySession(ssl, reader, device, context), cancellationToken).ConfigureAwait(false);
            }
        }
        catch (Exception ex) when (ex is IOException or AuthenticationException or ProtocolException or OperationCanceledException or SocketException)
        {
            LogConnectionClosed(remote, ex.GetType().Name);
        }
        catch (Exception ex)
        {
            LogConnectionFailed(ex, remote);
        }
    }

    /// <summary>Drops the active session of a revoked device at once.</summary>
    internal void CloseDevice(string deviceId)
    {
        lock (_gate)
        {
            if (_active?.Device.Id == deviceId) _active.Close();
        }
    }

    private async Task<DeviceRecord?> HandshakeAsync(SslStream ssl, FrameReader reader, string remote, CancellationToken cancellationToken)
    {
        using var hello = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        hello.CancelAfter(context.HelloTimeout);
        var options = new SslServerAuthenticationOptions
        {
            ServerCertificate = context.Certificate,
            EnabledSslProtocols = SslProtocols.Tls13,
            ClientCertificateRequired = false,
        };
        await ssl.AuthenticateAsServerAsync(options, hello.Token).ConfigureAwait(false);
        var frame = await reader.ReadAsync(hello.Token).ConfigureAwait(false);
        if (frame?.Type == FrameType.Pair)
        {
            if (!await PairAsync(ssl, frame.Value.Payload, remote, hello.Token).ConfigureAwait(false)) return null;
            frame = await reader.ReadAsync(hello.Token).ConfigureAwait(false);
        }
        return frame?.Type == FrameType.Hello ? await HelloAsync(ssl, frame.Value.Payload, remote, hello.Token).ConfigureAwait(false) : null;
    }

    private async Task<bool> PairAsync(SslStream ssl, ReadOnlyMemory<byte> payload, string remote, CancellationToken cancellationToken)
    {
        var name = Pair(payload.Span, remote, out var reason);
        if (name is null)
        {
            LogRejected(remote, reason);
            await ssl.WriteAsync(Frames.Reject(reason), cancellationToken).ConfigureAwait(false);
            return false;
        }
        var (id, token) = context.Devices.Add(name);
        await ssl.WriteAsync(Frames.Paired(id, token), cancellationToken).ConfigureAwait(false);
        LogPaired(name, remote);
        return true;
    }

    private string? Pair(ReadOnlySpan<byte> payload, string remote, out RejectReason reason)
    {
        reason = RejectReason.Version;
        if (payload[0] != Frames.VERSION) return null;
        reason = RejectReason.InvalidCode;
        var key = $"{remote}|pair";
        if (context.Throttle.IsBlocked(key) || !context.Pairing.TryConsume(payload.Slice(1, Frames.CODE_SIZE)))
        {
            context.Throttle.Failed(key);
            return null;
        }
        context.Throttle.Succeeded(key);
        return DeviceName(payload[(1 + Frames.CODE_SIZE)..]);
    }

    private async Task<DeviceRecord?> HelloAsync(SslStream ssl, ReadOnlyMemory<byte> payload, string remote, CancellationToken cancellationToken)
    {
        var device = Verify(payload.Span, remote, out var reason);
        if (device is null)
        {
            LogRejected(remote, reason);
            await ssl.WriteAsync(Frames.Reject(reason), cancellationToken).ConfigureAwait(false);
            return null;
        }
        await ssl.WriteAsync(Frames.Welcome(Environment.MachineName), cancellationToken).ConfigureAwait(false);
        return device;
    }

    private DeviceRecord? Verify(ReadOnlySpan<byte> payload, string remote, out RejectReason reason)
    {
        reason = RejectReason.Version;
        if (payload[0] != Frames.VERSION) return null;
        reason = RejectReason.UnknownDevice;
        var id = payload.Slice(1, Frames.ID_SIZE);
        var key = $"{remote}|{Convert.ToHexStringLower(id)}";
        var device = context.Throttle.IsBlocked(key) ? null : context.Devices.Verify(id, payload.Slice(1 + Frames.ID_SIZE, Frames.TOKEN_SIZE));
        if (device is null)
        {
            context.Throttle.Failed(key);
            return null;
        }
        context.Throttle.Succeeded(key);
        context.Devices.Touch(device.Id);
        return device;
    }

    private async Task RunSessionAsync(RelaySession session, CancellationToken cancellationToken)
    {
        RelaySession? replaced;
        lock (_gate)
        {
            replaced = _active;
            _active = session;
        }
        replaced?.Close();
        LogConnected(session.Device.Name);
        try
        {
            await session.RunAsync(cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            lock (_gate)
            {
                if (_active == session) _active = null;
            }
            // Sent after RunAsync so no key of this session can arrive behind it, replaced or not.
            await context.Agent.SendAsync(Frames.ReleaseAllFrame).ConfigureAwait(false);
            session.Dispose();
            LogDisconnected(session.Device.Name);
        }
    }

    private static string DeviceName(ReadOnlySpan<byte> utf8)
    {
        var name = string.Concat(Encoding.UTF8.GetString(utf8).Where(c => !char.IsControl(c))).Trim();
        return name.Length == 0 ? FALLBACK_DEVICE_NAME : name[..Math.Min(name.Length, MAX_DEVICE_NAME)];
    }

    [LoggerMessage(Level = LogLevel.Debug, Message = "Connection from {Remote} closed ({Reason})")]
    private partial void LogConnectionClosed(string remote, string reason);

    [LoggerMessage(Level = LogLevel.Error, Message = "Connection from {Remote} failed")]
    private partial void LogConnectionFailed(Exception ex, string remote);

    [LoggerMessage(Level = LogLevel.Information, Message = "Paired {Device} from {Remote}")]
    private partial void LogPaired(string device, string remote);

    [LoggerMessage(Level = LogLevel.Information, Message = "Rejected {Remote}: {Reason}")]
    private partial void LogRejected(string remote, RejectReason reason);

    [LoggerMessage(Level = LogLevel.Information, Message = "Connected: {Device}")]
    private partial void LogConnected(string device);

    [LoggerMessage(Level = LogLevel.Information, Message = "Disconnected: {Device}")]
    private partial void LogDisconnected(string device);
}
```
`Relay/RelayListener.cs`:
```csharp
using System.Net;
using System.Net.Sockets;
using KeyRelay.Service.Settings;

namespace KeyRelay.Service.Relay;

/// <summary>Accepts TCP connections on the configured port (IPv4 and IPv6) and hands them to the hub.</summary>
internal sealed partial class RelayListener(RelayHub hub, SettingsStore settings, ILogger<RelayListener> logger) : BackgroundService
{
    private static readonly TimeSpan BIND_RETRY = TimeSpan.FromSeconds(5);

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        var port = settings.Load().Port;
        using var listener = new Socket(AddressFamily.InterNetworkV6, SocketType.Stream, ProtocolType.Tcp) { DualMode = true };
        while (true)
        {
            try
            {
                listener.Bind(new IPEndPoint(IPAddress.IPv6Any, port));
                listener.Listen();
                break;
            }
            catch (SocketException ex)
            {
                // Port taken (for example by the prototype). Throwing stops the host cleanly and SCM recovery
                // only fires on a crash, so keep retrying instead.
                LogBindFailed(ex, port);
                await Task.Delay(BIND_RETRY, stoppingToken).ConfigureAwait(false);
            }
        }
        LogListening(port);
        while (true)
        {
            var socket = await listener.AcceptAsync(stoppingToken).ConfigureAwait(false);
            socket.NoDelay = true;
            _ = hub.HandleAsync(socket, stoppingToken);
        }
    }

    [LoggerMessage(Level = LogLevel.Information, Message = "Listening on TCP port {Port}")]
    private partial void LogListening(int port);

    [LoggerMessage(Level = LogLevel.Critical, Message = "Cannot listen on TCP port {Port}")]
    private partial void LogBindFailed(Exception ex, int port);
}
```
`Program.cs`:
```csharp
using KeyRelay.Service;
using KeyRelay.Service.Agent;
using KeyRelay.Service.Native;
using KeyRelay.Service.Relay;
using KeyRelay.Service.Session;
using KeyRelay.Service.Settings;
using Microsoft.Extensions.Hosting.WindowsServices;

if (args.Length > 0 && args[0] == "--agent") return AgentHost.Main(args);

var data = ServicePaths.DATA_DIRECTORY;
Directory.CreateDirectory(data);
var builder = Host.CreateApplicationBuilder(args);
var services = builder.Services;
services.AddWindowsService(options => options.ServiceName = "KeyRelay");
if (WindowsServiceHelpers.IsWindowsService()) services.AddSingleton<IHostLifetime, SessionAwareServiceLifetime>();
services.AddSingleton(TimeProvider.System);
services.AddSingleton<SessionSignal>();
services.AddSingleton<LatencyStats>();
services.AddSingleton(AgentLinkOptions.Production());
services.AddSingleton<AgentLink>();
services.AddSingleton<AgentSupervisor>();
services.AddHostedService(provider => provider.GetRequiredService<AgentSupervisor>());
services.AddSingleton(new SettingsStore(data));
services.AddSingleton(provider => new DeviceRegistry(data, provider.GetRequiredService<TimeProvider>()));
services.AddSingleton<PairingCodes>();
services.AddSingleton<AuthThrottle>();
services.AddSingleton(_ => CertificateStore.LoadOrCreate(data));
services.AddSingleton(new SendSas(() => Sas.SendSAS(false)));
services.AddSingleton(provider => ActivatorUtilities.CreateInstance<RelayContext>(provider, provider.GetRequiredService<ILogger<RelayHub>>()));
services.AddSingleton<RelayHub>();
services.AddHostedService<RelayListener>();
await builder.Build().RunAsync().ConfigureAwait(false);
return 0;
```

- [ ] **Step 6: Testlerin geçtiğini gör**

Run: `dotnet test KeyRelay.slnx --filter "FullyQualifiedName~CertificateStoreTests|FullyQualifiedName~HandshakeTests"` → 4 + 15 test geçer. Tüm çözüm de geçer. `MachineKeySet` testte "erişim engellendi" verirse (`%ProgramData%\Microsoft\Crypto` izinleri) kod değiştirmeden controller'a bildir.

- [ ] **Step 7: Commit**

```powershell
git -C $WT add windows/Directory.Packages.props windows/src/KeyRelay.Service windows/tests/KeyRelay.Tests
git -C $WT commit -m "feat(windows): tabletler şifreli bağlantıyla eşleşip bağlanabilir" -m "- pc ilk açılışta kendine ait bir kimlik üretir ve korur
- tablet tek kullanımlık kodla eşleşir, sonra kendi anahtarıyla bağlanır
- aynı anda tek tablet bağlı kalır; doğrulanmamış bağlantı onu düşüremez
- iptal edilen tabletin bağlantısı hemen kesilir" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: Tuş, SAS ve LOCK iletimi, STATE, kopmada RELEASE_ALL

**Files:**
- Modify: `windows/src/KeyRelay.Service/Relay/RelaySession.cs`
- Test: `windows/tests/KeyRelay.Tests/Relay/ForwardingTests.cs`

**Interfaces:**
- Consumes: T6 `AgentLink.SendAsync(frame, keyReadTimestamp)`, `AgentLink.Ready`, `AgentLink.ReadyChanged`; T9 `RelayContext.SendSas`, `RelayContext.Logger`, `RelayHarness`.
- Produces: `RelaySession` tamamı — KEY ham çerçevesi ajana (TLS okuma anı gecikme ölçümüne), SAS → `SendSas()`, LOCK → ajana, PING → PONG, bağlanınca ve hazır değişince STATE.

- [ ] **Step 1: Failing testleri yaz**

`Relay/ForwardingTests.cs`:
```csharp
using System.Diagnostics;
using KeyRelay.Protocol;
using KeyRelay.Tests.TestSupport;

namespace KeyRelay.Tests.Relay;

[TestClass]
public sealed class ForwardingTests
{
    [TestMethod]
    public async Task Key_FromTablet_ArrivesAtAgentUnchanged()
    {
        await using var harness = await RelayHarness.StartAsync();
        await using var tablet = await harness.HelloAsync(await harness.PairAsync());
        var key = Frames.Key(30, 1);

        await tablet.SendAsync(key);

        CollectionAssert.AreEqual(key, await harness.AgentPipe!.ReadAsync());
    }

    [TestMethod]
    public async Task Key_HundredInARow_ArriveInOrder()
    {
        await using var harness = await RelayHarness.StartAsync();
        await using var tablet = await harness.HelloAsync(await harness.PairAsync());

        for (var i = 0; i < 100; i++) await tablet.SendAsync(Frames.Key((ushort)(1 + i % 80), (byte)(i % 2)));

        for (var i = 0; i < 100; i++)
            CollectionAssert.AreEqual(Frames.Key((ushort)(1 + i % 80), (byte)(i % 2)), await harness.AgentPipe!.ReadAsync(), $"key {i}");
    }

    [TestMethod]
    public async Task Key_AgentAnswersKeyDone_RecordsLatency()
    {
        await using var harness = await RelayHarness.StartAsync();
        await using var tablet = await harness.HelloAsync(await harness.PairAsync());

        await tablet.SendAsync(Frames.Key(30, 1));
        await harness.AgentPipe!.ReadAsync();
        await harness.AgentPipe.SendAsync(Frames.KeyDone(Stopwatch.GetTimestamp()));

        await Eventually.TrueAsync(() => harness.Latency.Snapshot().KeysToday == 1, "latency was not recorded");
    }

    [TestMethod]
    public async Task Hello_AgentReady_FirstStateIsReady()
    {
        await using var harness = await RelayHarness.StartAsync();
        await using var tablet = await harness.HelloAsync(await harness.PairAsync());

        var state = await tablet.ExpectAsync(FrameType.State);

        Assert.AreEqual((byte)1, state.Payload[0]);
    }

    [TestMethod]
    public async Task Desktop_NotWritableThenWritable_SendsStateZeroThenOne()
    {
        await using var harness = await RelayHarness.StartAsync();
        await using var tablet = await harness.HelloAsync(await harness.PairAsync());
        await tablet.ExpectAsync(FrameType.State);

        await harness.AgentPipe!.SendAsync(Frames.Desktop(false, "Winlogon"));
        var notReady = await tablet.ExpectAsync(FrameType.State);
        await harness.AgentPipe.SendAsync(Frames.Desktop(true, "Default"));
        var ready = await tablet.ExpectAsync(FrameType.State);

        Assert.AreEqual((byte)0, notReady.Payload[0]);
        Assert.AreEqual((byte)1, ready.Payload[0]);
    }

    [TestMethod]
    public async Task Hello_NoAgent_StateNotReadyAndKeysDoNotBreakSession()
    {
        await using var harness = await RelayHarness.StartAsync(connectAgent: false);
        await using var tablet = await harness.HelloAsync(await harness.PairAsync());

        var state = await tablet.ExpectAsync(FrameType.State);
        await tablet.SendAsync(Frames.Key(30, 1));
        await tablet.SendAsync(Frames.Ping(3));
        var pong = await tablet.ExpectAsync(FrameType.Pong);

        Assert.AreEqual((byte)0, state.Payload[0]);
        Assert.AreEqual(3u, Frames.ReadSeq(pong.Payload));
    }

    [TestMethod]
    public async Task Sas_FromTablet_CallsSendSas()
    {
        await using var harness = await RelayHarness.StartAsync();
        await using var tablet = await harness.HelloAsync(await harness.PairAsync());

        await tablet.SendAsync(Frames.Empty(FrameType.Sas));

        await Eventually.TrueAsync(() => harness.SasCalls == 1, "SendSAS was not called");
    }

    [TestMethod]
    public async Task Lock_FromTablet_IsForwardedToAgent()
    {
        await using var harness = await RelayHarness.StartAsync();
        await using var tablet = await harness.HelloAsync(await harness.PairAsync());

        await tablet.SendAsync(Frames.Empty(FrameType.Lock));

        CollectionAssert.AreEqual(Frames.LockFrame.ToArray(), await harness.AgentPipe!.ReadAsync());
    }

    [TestMethod]
    public async Task Disconnect_ActiveTablet_ReleasesAllKeysAtAgent()
    {
        await using var harness = await RelayHarness.StartAsync();
        var tablet = await harness.HelloAsync(await harness.PairAsync());
        await tablet.SendAsync(Frames.Key(56, 1));
        await harness.AgentPipe!.ReadAsync();

        await tablet.DisposeAsync();

        CollectionAssert.AreEqual(Frames.ReleaseAllFrame.ToArray(), await harness.AgentPipe.ReadAsync());
    }

    [TestMethod]
    public async Task Session_HelloAgainAfterWelcome_IsClosed()
    {
        await using var harness = await RelayHarness.StartAsync();
        var device = await harness.PairAsync();
        await using var tablet = await harness.HelloAsync(device);

        await tablet.SendAsync(Frames.Hello(device.Id, device.Token));

        Assert.IsTrue(await tablet.IsClosedAsync());
    }
}
```

- [ ] **Step 2: Başarısız olduğunu gör**

Run: `dotnet test KeyRelay.slnx --filter "FullyQualifiedName~ForwardingTests"` → KEY/SAS/LOCK/STATE testleri başarısız (T9 oturumu bunları kapatır veya göndermez).

- [ ] **Step 3: `RelaySession`'ı tamamla**

`Relay/RelaySession.cs`'nin tamamı:
```csharp
using System.Diagnostics;
using System.Net.Security;
using KeyRelay.Protocol;

namespace KeyRelay.Service.Relay;

/// <summary>One verified tablet connection: forwards keys to the agent, reports STATE, answers PING, ends on idle, close or replacement.</summary>
internal sealed partial class RelaySession(SslStream ssl, FrameReader reader, DeviceRecord device, RelayContext context) : IDisposable
{
    // A tablet that does not take a frame within 1 s is gone.
    private static readonly TimeSpan SEND_TIMEOUT = TimeSpan.FromSeconds(1);
    private readonly CancellationTokenSource _closed = new();
    private readonly SemaphoreSlim _writeGate = new(1, 1);

    internal DeviceRecord Device => device;

    /// <summary>Serves the tablet until it leaves, stays silent for idleTimeout or Close is called.</summary>
    internal async Task RunAsync(CancellationToken cancellationToken)
    {
        using var idle = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, _closed.Token);
        var idleTimeout = TimeSpan.FromSeconds(context.Settings.Load().IdleTimeoutSeconds);
        context.Agent.ReadyChanged += OnReadyChanged;
        try
        {
            if (!await WriteAsync(Frames.State(context.Agent.Ready)).ConfigureAwait(false)) return;
            idle.CancelAfter(idleTimeout);
            while (await reader.ReadAsync(idle.Token).ConfigureAwait(false) is { } frame)
            {
                idle.CancelAfter(idleTimeout);
                if (!await HandleAsync(frame).ConfigureAwait(false)) return;
            }
        }
        finally
        {
            context.Agent.ReadyChanged -= OnReadyChanged;
        }
    }

    /// <summary>Ends the session (replaced by a newer connection or device revoked).</summary>
    internal void Close()
    {
        try
        {
            _closed.Cancel();
        }
        catch (ObjectDisposedException)
        {
            // Already ended.
        }
    }

    public void Dispose()
    {
        _closed.Dispose();
        _writeGate.Dispose();
    }

    private async ValueTask<bool> HandleAsync(Frame frame)
    {
        switch (frame.Type)
        {
            case FrameType.Key:
                // Forwarded even while not ready: key-ups must reach the agent so nothing stays pressed.
                await context.Agent.SendAsync(frame.Raw, Stopwatch.GetTimestamp()).ConfigureAwait(false);
                return true;
            case FrameType.Sas:
                context.SendSas();
                LogSas(context.Logger, device.Name);
                return true;
            case FrameType.Lock:
                await context.Agent.SendAsync(Frames.LockFrame).ConfigureAwait(false);
                LogLock(context.Logger, device.Name);
                return true;
            case FrameType.Ping:
                return await WriteAsync(Frames.Pong(Frames.ReadSeq(frame.Payload.Span))).ConfigureAwait(false);
            default:
                return false;
        }
    }

    private void OnReadyChanged(bool ready) => _ = WriteAsync(Frames.State(ready)).AsTask();

    /// <summary>Writes one frame; TLS allows one writer at a time. A write slower than 1 s ends the session.</summary>
    private async ValueTask<bool> WriteAsync(byte[] frame)
    {
        try
        {
            await _writeGate.WaitAsync(_closed.Token).ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is OperationCanceledException or ObjectDisposedException)
        {
            return false;
        }
        try
        {
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(_closed.Token);
            timeout.CancelAfter(SEND_TIMEOUT);
            await ssl.WriteAsync(frame, timeout.Token).ConfigureAwait(false);
            return true;
        }
        catch (Exception ex) when (ex is IOException or OperationCanceledException or ObjectDisposedException)
        {
            Close();
            return false;
        }
        finally
        {
            _writeGate.Release();
        }
    }

    [LoggerMessage(Level = LogLevel.Information, Message = "Ctrl+Alt+Del (SAS) from {Device}")]
    private static partial void LogSas(ILogger logger, string device);

    [LoggerMessage(Level = LogLevel.Information, Message = "Lock from {Device}")]
    private static partial void LogLock(ILogger logger, string device);
}
```

- [ ] **Step 4: Testlerin geçtiğini gör**

Run: `dotnet test KeyRelay.slnx` → ForwardingTests (10), HandshakeTests ve öncekiler geçer.

- [ ] **Step 5: Commit**

```powershell
git -C $WT add windows/src/KeyRelay.Service/Relay/RelaySession.cs windows/tests/KeyRelay.Tests/Relay/ForwardingTests.cs
git -C $WT commit -m "feat(windows): bağlı tabletin tuşları pc'ye iletilir" -m "- tuşlar, ctrl+alt+del ve kilitleme pc'ye ulaşır
- pc tuş alamayacak durumdaysa tablet hemen haberdar edilir
- bağlantı koparsa basılı kalan tuşlar bırakılır" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: Kontrol kanalı (servis ↔ tepsi), QR adresleri, log tamponu

**Files:**
- Create: `windows/src/KeyRelay.Protocol/Control/ControlMessages.cs`, `windows/src/KeyRelay.Protocol/Control/ControlChannel.cs`, `windows/src/KeyRelay.Protocol/Control/ControlClient.cs`
- Create: `windows/src/KeyRelay.Service/Relay/PairingAddresses.cs`, `windows/src/KeyRelay.Service/Control/LogBuffer.cs`, `windows/src/KeyRelay.Service/Control/ControlHandler.cs`, `windows/src/KeyRelay.Service/Control/ControlServer.cs`
- Modify: `windows/src/KeyRelay.Service/Program.cs`
- Test: `windows/tests/KeyRelay.Tests/Protocol/ControlStatusTests.cs`, `windows/tests/KeyRelay.Tests/Relay/PairingAddressesTests.cs`, `windows/tests/KeyRelay.Tests/Control/LogBufferTests.cs`, `windows/tests/KeyRelay.Tests/Control/ControlServerTests.cs`

**Interfaces:**
- Consumes: T6 `AgentLink`, `LatencyStats`; T7 `AgentSupervisor.ActiveSession`, `SessionSignal`; T8 `DeviceRegistry`, `PairingCodes`, `SettingsStore`, `ServiceSettings.IsValidFunnelAddress`; T9 `RelayHub`, `CertificateStore.Fingerprint`, `RelayHarness`.
- Produces (namespace `KeyRelay.Protocol.Control`, public; T13–T15 kullanır):
  - `ControlOps` sabitleri: `STATUS "status"`, `PAIR_START "pair-start"`, `PAIR_STOP "pair-stop"`, `DEVICES "devices"`, `REVOKE "revoke"`, `NETWORK "network"`, `SET_FUNNEL "set-funnel"`, `LOGS "logs"`; `ControlErrors`: `UNKNOWN_OP`, `UNKNOWN_DEVICE`, `INVALID_FUNNEL`.
  - `record ControlRequest(string Op, string? DeviceId = null, string? FunnelAddress = null)`; `record ControlResponse { bool Ok; string? Error; ControlStatus? Status; PairingInfo? Pairing; IReadOnlyList<DeviceInfo>? Devices; NetworkInfo? Network; IReadOnlyList<string>? Logs; static Fail(string) }`.
  - `record ControlStatus { bool AgentConnected; uint? AgentSession; string InputDesktop; bool Writable; bool Ready; string? ConnectedDevice; int LatencyP50Micros; int LatencyP99Micros; IReadOnlyList<int> LatencyPerSecondMicros; long KeysToday; bool PairingActive; int DeviceCount; TrayState ToTrayState() }`; `enum TrayState { Error = 0, Waiting, Ready }`; `TrayStates.From(ControlStatus?)`.
  - `record PairingInfo(string Link, IReadOnlyList<string> Addresses, DateTimeOffset ExpiresAt)`, `record DeviceInfo(string Id, string Name, DateTimeOffset AddedAt, DateTimeOffset? LastSeenAt, bool Connected)`, `record NetworkInfo(int Port, string FunnelAddress, string Fingerprint, IReadOnlyList<string> Addresses)`.
  - `sealed class ControlChannel(Stream)`: `Task<T?> ReadAsync<T>(CancellationToken)`, `Task WriteAsync<T>(T, CancellationToken)`; `sealed class ControlClient(string pipeName) : IAsyncDisposable`: `const PIPE_NAME = "KeyRelay.Control"`, `Task<ControlResponse> SendAsync(ControlRequest, CancellationToken = default)` (hata: `IOException`, `TimeoutException`, `UnauthorizedAccessException`, `InvalidDataException`, `JsonException`).
- Produces (servis): `ControlOptions(string PipeName, PipeSecurity Security)` + `Production()`; `ControlContext` kaydı; `ControlHandler.Handle(ControlRequest)`; `ControlServer : BackgroundService`; `LogBuffer : ILoggerProvider` (`Add`, `Snapshot`, 500 satır); `PairingAddresses.Order(...)`, `Current()`, `BuildUri(...)`.

- [ ] **Step 1: Failing testleri yaz**

`Protocol/ControlStatusTests.cs`:
```csharp
using KeyRelay.Protocol.Control;

namespace KeyRelay.Tests.Protocol;

[TestClass]
public sealed class ControlStatusTests
{
    [TestMethod]
    public void From_ServiceUnreachable_IsError()
    {
        Assert.AreEqual(TrayState.Error, TrayStates.From(null));
    }

    [TestMethod]
    [DataRow(false, null, false, TrayState.Error, DisplayName = "no agent")]
    [DataRow(true, null, true, TrayState.Waiting, DisplayName = "no tablet")]
    [DataRow(true, "Tab", false, TrayState.Waiting, DisplayName = "tablet, desktop not writable")]
    [DataRow(true, "Tab", true, TrayState.Ready, DisplayName = "tablet and ready")]
    public void ToTrayState_Combination_MatchesTrayIcon(bool agentConnected, string? device, bool ready, TrayState expected)
    {
        var status = new ControlStatus { AgentConnected = agentConnected, ConnectedDevice = device, Ready = ready };

        Assert.AreEqual(expected, status.ToTrayState());
    }
}
```
`Relay/PairingAddressesTests.cs`:
```csharp
using System.Net;
using KeyRelay.Service.Relay;

namespace KeyRelay.Tests.Relay;

[TestClass]
public sealed class PairingAddressesTests
{
    // Up IPv4 interfaces seen on homepc on 2026-10-08 (ProtonVPN reports gateway 0.0.0.0, VMnet1 none).
    private static readonly PairingAddresses.InterfaceAddress[] HOMEPC =
    [
        new(IPAddress.Parse("10.2.0.2"), false, ""),
        new(IPAddress.Parse("100.64.0.10"), false, "tailnet-example.ts.net"),
        new(IPAddress.Parse("192.168.1.10"), true, "home"),
        new(IPAddress.Parse("192.168.56.1"), false, ""),
    ];

    [TestMethod]
    public void Order_HomepcInterfaces_KeepsTailscaleAndGatewayLanOnly()
    {
        var addresses = PairingAddresses.Order(HOMEPC, "homepc.tailnet-example.ts.net:8443", "HOMEPC");

        CollectionAssert.AreEqual(
            new[] { "homepc.tailnet-example.ts.net:8443", "100.64.0.10", "homepc.tailnet-example.ts.net", "192.168.1.10" },
            addresses);
    }

    [TestMethod]
    public void Order_NoFunnelNoTailscale_ReturnsGatewayLanOnly()
    {
        PairingAddresses.InterfaceAddress[] interfaces = [new(IPAddress.Parse("192.168.1.10"), true, "home"), new(IPAddress.Parse("192.168.56.1"), false, "")];

        var addresses = PairingAddresses.Order(interfaces, "", "PC");

        CollectionAssert.AreEqual(new[] { "192.168.1.10" }, addresses);
    }

    [TestMethod]
    public void Order_SameAddressOnTwoInterfaces_IsListedOnce()
    {
        PairingAddresses.InterfaceAddress[] interfaces = [new(IPAddress.Parse("192.168.1.10"), true, ""), new(IPAddress.Parse("192.168.1.10"), true, "")];

        Assert.HasCount(1, PairingAddresses.Order(interfaces, "", "PC"));
    }

    [TestMethod]
    public void BuildUri_Values_EncodesNameAndCode()
    {
        byte[] code = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15];

        var uri = PairingAddresses.BuildUri("NH ROG", ["a.ts.net:443", "1.2.3.4"], 47111, "abc", code);

        Assert.AreEqual("keyrelay://pair?v=1&n=NH%20ROG&a=a.ts.net:443,1.2.3.4&p=47111&fp=abc&c=AAECAwQFBgcICQoLDA0ODw", uri);
    }

    [TestMethod]
    public void Current_ThisMachine_HasNoLoopback()
    {
        Assert.IsFalse(PairingAddresses.Current().Any(a => IPAddress.IsLoopback(a.Address)));
    }
}
```
`Control/LogBufferTests.cs`:
```csharp
using KeyRelay.Service.Control;
using Microsoft.Extensions.Logging;

namespace KeyRelay.Tests.Control;

[TestClass]
public sealed class LogBufferTests
{
    [TestMethod]
    public void Snapshot_MoreThan500Lines_KeepsNewest500InOrder()
    {
        using var buffer = new LogBuffer();

        for (var i = 0; i < 600; i++) buffer.Add($"line {i}");
        var lines = buffer.Snapshot();

        Assert.HasCount(500, lines);
        Assert.AreEqual("line 100", lines[0]);
        Assert.AreEqual("line 599", lines[^1]);
    }

    [TestMethod]
    public void Logger_Information_IsBufferedWithShortCategory()
    {
        using var buffer = new LogBuffer();
        var logger = buffer.CreateLogger("KeyRelay.Service.Relay.RelayHub");

        logger.Log(LogLevel.Information, new EventId(1), "Connected: Tab", null, (state, _) => state);

        Assert.IsTrue(buffer.Snapshot().Single().EndsWith("INFO RelayHub: Connected: Tab", StringComparison.Ordinal));
    }

    [TestMethod]
    public void Logger_Debug_IsNotBuffered()
    {
        using var buffer = new LogBuffer();
        var logger = buffer.CreateLogger("KeyRelay.Service.Relay.RelayHub");

        logger.Log(LogLevel.Debug, new EventId(1), "noise", null, (state, _) => state);

        Assert.IsEmpty(buffer.Snapshot());
    }
}
```
`Control/ControlServerTests.cs`:
```csharp
using System.IO.Pipes;
using KeyRelay.Protocol;
using KeyRelay.Protocol.Control;
using KeyRelay.Service.Control;
using KeyRelay.Service.Session;
using KeyRelay.Tests.TestSupport;
using Microsoft.Extensions.Logging.Abstractions;

namespace KeyRelay.Tests.Control;

[TestClass]
public sealed class ControlServerTests
{
    [TestMethod]
    public async Task Status_ServiceRunning_ReportsAgentAndNoDevice()
    {
        await using var fixture = await Fixture.StartAsync();

        var response = await fixture.Client.SendAsync(new ControlRequest(ControlOps.STATUS));

        Assert.IsTrue(response.Ok);
        Assert.IsTrue(response.Status!.AgentConnected);
        Assert.IsNull(response.Status.ConnectedDevice);
        Assert.AreEqual(0, response.Status.DeviceCount);
        Assert.HasCount(60, response.Status.LatencyPerSecondMicros);
    }

    [TestMethod]
    public async Task PairStart_ReturnsLinkWithFingerprintCodeAndExpiry()
    {
        await using var fixture = await Fixture.StartAsync();

        var pairing = (await fixture.Client.SendAsync(new ControlRequest(ControlOps.PAIR_START))).Pairing!;
        var status = (await fixture.Client.SendAsync(new ControlRequest(ControlOps.STATUS))).Status!;

        Assert.IsTrue(pairing.Link.StartsWith("keyrelay://pair?v=1&n=", StringComparison.Ordinal));
        Assert.IsTrue(pairing.Link.Contains($"&fp={fixture.Harness.Fingerprint}&c=", StringComparison.Ordinal));
        Assert.AreEqual(fixture.Harness.Time.Now + TimeSpan.FromMinutes(5), pairing.ExpiresAt);
        Assert.IsTrue(status.PairingActive);
    }

    [TestMethod]
    public async Task Revoke_PairedDevice_RemovesAndDropsSession()
    {
        await using var fixture = await Fixture.StartAsync();
        var device = await fixture.Harness.PairAsync();
        await using var tablet = await fixture.Harness.HelloAsync(device);
        await Eventually.TrueAsync(() => fixture.Harness.Hub.ActiveDeviceId is not null, "session did not become active");

        var response = await fixture.Client.SendAsync(new ControlRequest(ControlOps.REVOKE, DeviceId: Convert.ToHexStringLower(device.Id)));
        var devices = (await fixture.Client.SendAsync(new ControlRequest(ControlOps.DEVICES))).Devices!;

        Assert.IsTrue(response.Ok);
        Assert.IsTrue(await tablet.IsClosedAsync());
        Assert.IsEmpty(devices);
    }

    [TestMethod]
    public async Task Revoke_UnknownDevice_FailsWithUnknownDevice()
    {
        await using var fixture = await Fixture.StartAsync();

        var response = await fixture.Client.SendAsync(new ControlRequest(ControlOps.REVOKE, DeviceId: "00"));

        Assert.IsFalse(response.Ok);
        Assert.AreEqual(ControlErrors.UNKNOWN_DEVICE, response.Error);
    }

    [TestMethod]
    public async Task Devices_ConnectedTablet_IsMarkedConnected()
    {
        await using var fixture = await Fixture.StartAsync();
        await using var tablet = await fixture.Harness.HelloAsync(await fixture.Harness.PairAsync("Galaxy"));
        await Eventually.TrueAsync(() => fixture.Harness.Hub.ActiveDeviceId is not null, "session did not become active");

        var device = (await fixture.Client.SendAsync(new ControlRequest(ControlOps.DEVICES))).Devices!.Single();

        Assert.AreEqual("Galaxy", device.Name);
        Assert.IsTrue(device.Connected);
    }

    [TestMethod]
    public async Task SetFunnel_InvalidPort_FailsAndKeepsSetting()
    {
        await using var fixture = await Fixture.StartAsync();

        var response = await fixture.Client.SendAsync(new ControlRequest(ControlOps.SET_FUNNEL, FunnelAddress: "pc.tail.ts.net:8080"));
        var network = (await fixture.Client.SendAsync(new ControlRequest(ControlOps.NETWORK))).Network!;

        Assert.AreEqual(ControlErrors.INVALID_FUNNEL, response.Error);
        Assert.AreEqual("", network.FunnelAddress);
    }

    [TestMethod]
    public async Task SetFunnel_Valid_IsFirstAddressOfNextPairing()
    {
        await using var fixture = await Fixture.StartAsync();

        await fixture.Client.SendAsync(new ControlRequest(ControlOps.SET_FUNNEL, FunnelAddress: "pc.tail.ts.net:8443"));
        var pairing = (await fixture.Client.SendAsync(new ControlRequest(ControlOps.PAIR_START))).Pairing!;

        Assert.AreEqual("pc.tail.ts.net:8443", pairing.Addresses[0]);
        Assert.IsTrue(pairing.Link.Contains("&a=pc.tail.ts.net:8443", StringComparison.Ordinal));
    }

    [TestMethod]
    public async Task UnknownOp_FailsWithUnknownOp()
    {
        await using var fixture = await Fixture.StartAsync();

        var response = await fixture.Client.SendAsync(new ControlRequest("format-disk"));

        Assert.AreEqual(ControlErrors.UNKNOWN_OP, response.Error);
    }

    [TestMethod]
    public async Task Logs_BufferedLine_IsReturned()
    {
        await using var fixture = await Fixture.StartAsync();
        fixture.Logs.Add("12:00:00 INFO Test: hello");

        var logs = (await fixture.Client.SendAsync(new ControlRequest(ControlOps.LOGS))).Logs!;

        Assert.Contains("12:00:00 INFO Test: hello", logs);
    }

    [TestMethod]
    public async Task Message_LongerThan64KB_ServerDropsClient()
    {
        await using var fixture = await Fixture.StartAsync();
        await using var raw = new NamedPipeClientStream(".", fixture.PipeName, PipeDirection.InOut, PipeOptions.Asynchronous);
        await raw.ConnectAsync(5000);

        bool dropped;
        try
        {
            await raw.WriteAsync(new byte[ControlChannel.MAX_MESSAGE_BYTES + 100]);
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
            dropped = await raw.ReadAsync(new byte[1], timeout.Token) == 0;
        }
        catch (IOException)
        {
            // The server closed the pipe while the rest of the message was still being written.
            dropped = true;
        }

        Assert.IsTrue(dropped);
    }

    private sealed class Fixture : IAsyncDisposable
    {
        private readonly SessionSignal _sessions = new();
        private readonly AgentSupervisor _supervisor;

        private Fixture(RelayHarness harness)
        {
            Harness = harness;
            _supervisor = new AgentSupervisor(harness.Agent, _sessions, NullLogger<AgentSupervisor>.Instance);
            var context = new ControlContext(harness.Devices, harness.Pairing, harness.Hub, harness.Agent, harness.Latency, harness.Settings, Logs, _supervisor, harness.Certificate);
            Server = new ControlServer(new ControlOptions(PipeName, TestPipes.CurrentUser()), new ControlHandler(context, NullLogger<ControlHandler>.Instance), NullLogger<ControlServer>.Instance);
            Client = new ControlClient(PipeName);
        }

        internal string PipeName { get; } = TestPipes.Name();

        internal RelayHarness Harness { get; }

        internal LogBuffer Logs { get; } = new();

        internal ControlServer Server { get; }

        internal ControlClient Client { get; }

        internal static async Task<Fixture> StartAsync()
        {
            var fixture = new Fixture(await RelayHarness.StartAsync());
            await fixture.Server.StartAsync(CancellationToken.None);
            return fixture;
        }

        public async ValueTask DisposeAsync()
        {
            await Client.DisposeAsync();
            await Server.StopAsync(CancellationToken.None);
            Server.Dispose();
            _supervisor.Dispose();
            _sessions.Dispose();
            Logs.Dispose();
            await Harness.DisposeAsync();
        }
    }
}
```

- [ ] **Step 2: Başarısız olduğunu gör**

Run: `dotnet test KeyRelay.slnx --filter "FullyQualifiedName~ControlStatusTests|FullyQualifiedName~PairingAddressesTests|FullyQualifiedName~KeyRelay.Tests.Control"` → derleme hatası.

- [ ] **Step 3: Protocol tarafı**

`Control/ControlMessages.cs`:
```csharp
namespace KeyRelay.Protocol.Control;

/// <summary>Operations of the control pipe (tray app to service).</summary>
public static class ControlOps
{
    public const string STATUS = "status";
    public const string PAIR_START = "pair-start";
    public const string PAIR_STOP = "pair-stop";
    public const string DEVICES = "devices";
    public const string REVOKE = "revoke";
    public const string NETWORK = "network";
    public const string SET_FUNNEL = "set-funnel";
    public const string LOGS = "logs";
}

/// <summary>Error codes in ControlResponse.Error; the tray maps them to user text.</summary>
public static class ControlErrors
{
    public const string UNKNOWN_OP = "unknown_op";
    public const string UNKNOWN_DEVICE = "unknown_device";
    public const string INVALID_FUNNEL = "invalid_funnel_address";
}

/// <summary>One request on the control pipe.</summary>
public sealed record ControlRequest(string Op, string? DeviceId = null, string? FunnelAddress = null);

/// <summary>One answer on the control pipe; only the member matching the request is set.</summary>
public sealed record ControlResponse
{
    public bool Ok { get; init; } = true;

    public string? Error { get; init; }

    public ControlStatus? Status { get; init; }

    public PairingInfo? Pairing { get; init; }

    public IReadOnlyList<DeviceInfo>? Devices { get; init; }

    public NetworkInfo? Network { get; init; }

    public IReadOnlyList<string>? Logs { get; init; }

    public static ControlResponse Fail(string error) => new() { Ok = false, Error = error };
}

/// <summary>What the tray shows on its status page and in its icon.</summary>
public sealed record ControlStatus
{
    public bool AgentConnected { get; init; }

    public uint? AgentSession { get; init; }

    public string InputDesktop { get; init; } = "";

    public bool Writable { get; init; }

    /// <summary>Gets the STATE bit the tablet sees: agent connected and its desktop writable.</summary>
    public bool Ready { get; init; }

    public string? ConnectedDevice { get; init; }

    public int LatencyP50Micros { get; init; }

    public int LatencyP99Micros { get; init; }

    /// <summary>Gets the slowest key of each of the last 60 seconds, oldest first.</summary>
    public IReadOnlyList<int> LatencyPerSecondMicros { get; init; } = [];

    public long KeysToday { get; init; }

    public bool PairingActive { get; init; }

    public int DeviceCount { get; init; }

    /// <summary>Maps the status to the tray icon (spec: connected/ready, waiting, off/error).</summary>
    public TrayState ToTrayState() =>
        !AgentConnected ? TrayState.Error : ConnectedDevice is not null && Ready ? TrayState.Ready : TrayState.Waiting;
}

/// <summary>The three tray icon states.</summary>
public enum TrayState
{
    Error = 0,
    Waiting,
    Ready,
}

public static class TrayStates
{
    /// <summary>A missing status means the service is unreachable.</summary>
    public static TrayState From(ControlStatus? status) => status?.ToTrayState() ?? TrayState.Error;
}

/// <summary>A started pairing: the QR content, its addresses and when the code expires.</summary>
public sealed record PairingInfo(string Link, IReadOnlyList<string> Addresses, DateTimeOffset ExpiresAt);

public sealed record DeviceInfo(string Id, string Name, DateTimeOffset AddedAt, DateTimeOffset? LastSeenAt, bool Connected);

public sealed record NetworkInfo(int Port, string FunnelAddress, string Fingerprint, IReadOnlyList<string> Addresses);
```
`Control/ControlChannel.cs`:
```csharp
using System.Text.Json;

namespace KeyRelay.Protocol.Control;

/// <summary>Newline-delimited JSON messages on the control pipe, at most 64 KB each.</summary>
public sealed class ControlChannel(Stream stream)
{
    /// <summary>Largest message including its newline; longer input closes the pipe.</summary>
    public const int MAX_MESSAGE_BYTES = 64 * 1024;
    private const byte NEWLINE = (byte)'\n';
    private static readonly JsonSerializerOptions JSON = new(JsonSerializerDefaults.Web);
    private static readonly byte[] NEWLINE_BYTES = [NEWLINE];
    private readonly byte[] _buffer = new byte[MAX_MESSAGE_BYTES];
    private int _start;
    private int _end;

    /// <returns>The next message, or null when the peer closed the pipe.</returns>
    /// <exception cref="InvalidDataException">A message is longer than 64 KB or empty.</exception>
    public async Task<T?> ReadAsync<T>(CancellationToken cancellationToken) where T : class
    {
        while (true)
        {
            var newline = Array.IndexOf(_buffer, NEWLINE, _start, _end - _start);
            if (newline >= 0)
            {
                var message = JsonSerializer.Deserialize<T>(_buffer.AsSpan(_start, newline - _start), JSON);
                _start = newline + 1;
                return message ?? throw new InvalidDataException("Empty control message.");
            }
            Compact();
            if (_end == _buffer.Length) throw new InvalidDataException("Control message too long.");
            var read = await stream.ReadAsync(_buffer.AsMemory(_end), cancellationToken).ConfigureAwait(false);
            if (read == 0) return null;
            _end += read;
        }
    }

    /// <exception cref="InvalidDataException">The serialized message is longer than 64 KB.</exception>
    public async Task WriteAsync<T>(T message, CancellationToken cancellationToken)
    {
        var bytes = JsonSerializer.SerializeToUtf8Bytes(message, JSON);
        if (bytes.Length >= MAX_MESSAGE_BYTES) throw new InvalidDataException("Control message too long.");
        await stream.WriteAsync(bytes, cancellationToken).ConfigureAwait(false);
        await stream.WriteAsync(NEWLINE_BYTES, cancellationToken).ConfigureAwait(false);
        await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
    }

    private void Compact()
    {
        if (_start == 0) return;
        _buffer.AsSpan(_start, _end - _start).CopyTo(_buffer);
        _end -= _start;
        _start = 0;
    }
}
```
`Control/ControlClient.cs`:
```csharp
using System.IO.Pipes;
using System.Text.Json;

namespace KeyRelay.Protocol.Control;

/// <summary>Tray side of the control pipe: one request at a time, reconnects after any failure.</summary>
/// <param name="pipeName">Pipe name without the \\.\pipe\ prefix (PIPE_NAME in production).</param>
public sealed class ControlClient(string pipeName) : IAsyncDisposable
{
    public const string PIPE_NAME = "KeyRelay.Control";
    private const int CONNECT_TIMEOUT_MS = 2000;
    private static readonly TimeSpan REQUEST_TIMEOUT = TimeSpan.FromSeconds(3);
    private readonly SemaphoreSlim _gate = new(1, 1);
    private NamedPipeClientStream? _pipe;
    private ControlChannel? _channel;

    /// <summary>Sends a request and waits up to 3 s for the answer.</summary>
    /// <exception cref="IOException">The service is not running or the pipe broke.</exception>
    /// <exception cref="TimeoutException">The service did not answer in time.</exception>
    /// <exception cref="UnauthorizedAccessException">The caller is not an interactive or administrator user.</exception>
    public async Task<ControlResponse> SendAsync(ControlRequest request, CancellationToken cancellationToken = default)
    {
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            timeout.CancelAfter(REQUEST_TIMEOUT);
            var channel = await ConnectAsync(timeout.Token).ConfigureAwait(false);
            await channel.WriteAsync(request, timeout.Token).ConfigureAwait(false);
            return await channel.ReadAsync<ControlResponse>(timeout.Token).ConfigureAwait(false)
                ?? throw new IOException("Control pipe closed.");
        }
        catch (Exception ex) when (ex is IOException or OperationCanceledException or InvalidDataException or JsonException or UnauthorizedAccessException or TimeoutException)
        {
            await ResetAsync().ConfigureAwait(false);
            if (ex is OperationCanceledException && !cancellationToken.IsCancellationRequested)
                throw new TimeoutException("Control request timed out.", ex);
            throw;
        }
        finally
        {
            _gate.Release();
        }
    }

    public async ValueTask DisposeAsync()
    {
        await ResetAsync().ConfigureAwait(false);
        _gate.Dispose();
    }

    private async Task<ControlChannel> ConnectAsync(CancellationToken cancellationToken)
    {
        if (_channel is not null) return _channel;
        var pipe = new NamedPipeClientStream(".", pipeName, PipeDirection.InOut, PipeOptions.Asynchronous);
        try
        {
            await pipe.ConnectAsync(CONNECT_TIMEOUT_MS, cancellationToken).ConfigureAwait(false);
        }
        catch
        {
            await pipe.DisposeAsync().ConfigureAwait(false);
            throw;
        }
        _pipe = pipe;
        return _channel = new ControlChannel(pipe);
    }

    private async Task ResetAsync()
    {
        _channel = null;
        if (_pipe is not null) await _pipe.DisposeAsync().ConfigureAwait(false);
        _pipe = null;
    }
}
```

- [ ] **Step 4: Servis tarafı**

`Relay/PairingAddresses.cs`:
```csharp
using System.Buffers.Text;
using System.Globalization;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace KeyRelay.Service.Relay;

/// <summary>Chooses the addresses put into the pairing QR and builds the keyrelay:// link (docs/protocol.md).</summary>
internal static class PairingAddresses
{
    /// <summary>An IPv4 address of an up interface.</summary>
    /// <param name="Address">The address.</param>
    /// <param name="HasGateway">The interface has a real IPv4 default gateway (not 0.0.0.0).</param>
    /// <param name="DnsSuffix">Connection DNS suffix; on the Tailscale interface it is the tailnet domain.</param>
    internal sealed record InterfaceAddress(IPAddress Address, bool HasGateway, string DnsSuffix);

    /// <summary>Funnel address, then Tailscale IP and MagicDNS name, then LAN addresses of interfaces with a gateway.</summary>
    /// <remarks>Interfaces without a gateway (host-only virtual adapters, some VPNs) would cost the tablet a 2 s timeout each.</remarks>
    internal static List<string> Order(IEnumerable<InterfaceAddress> interfaces, string funnelAddress, string hostName)
    {
        var all = interfaces.ToList();
        var result = new List<string>();
        if (funnelAddress.Length > 0) result.Add(funnelAddress);
        foreach (var tailscale in all.Where(i => IsTailscale(i.Address)))
        {
            result.Add(tailscale.Address.ToString());
            if (tailscale.DnsSuffix.Length > 0) result.Add($"{hostName.ToLowerInvariant()}.{tailscale.DnsSuffix}");
        }
        result.AddRange(all.Where(i => i.HasGateway && !IsTailscale(i.Address)).Select(i => i.Address.ToString()));
        return [.. result.Distinct(StringComparer.OrdinalIgnoreCase)];
    }

    /// <summary>Reads the IPv4 addresses of up, non-loopback interfaces.</summary>
    internal static List<InterfaceAddress> Current()
    {
        var result = new List<InterfaceAddress>();
        foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (nic.OperationalStatus != OperationalStatus.Up || nic.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
            var properties = nic.GetIPProperties();
            var hasGateway = properties.GatewayAddresses.Any(g => g.Address.AddressFamily == AddressFamily.InterNetwork && !g.Address.Equals(IPAddress.Any));
            result.AddRange(properties.UnicastAddresses
                .Where(u => u.Address.AddressFamily == AddressFamily.InterNetwork)
                .Select(u => new InterfaceAddress(u.Address, hasGateway, properties.DnsSuffix)));
        }
        return result;
    }

    /// <summary>keyrelay://pair?v=1&amp;n=..&amp;a=..&amp;p=..&amp;fp=..&amp;c=.. ; only the PC name needs escaping.</summary>
    internal static string BuildUri(string pcName, IReadOnlyList<string> addresses, int port, string fingerprint, ReadOnlySpan<byte> code)
    {
        var encodedCode = Base64Url.EncodeToString(code);
        return string.Create(CultureInfo.InvariantCulture,
            $"keyrelay://pair?v=1&n={Uri.EscapeDataString(pcName)}&a={string.Join(',', addresses)}&p={port}&fp={fingerprint}&c={encodedCode}");
    }

    // Tailscale hands out addresses from the CGNAT range 100.64.0.0/10.
    private static bool IsTailscale(IPAddress address)
    {
        var bytes = address.GetAddressBytes();
        return bytes.Length == 4 && bytes[0] == 100 && (bytes[1] & 0xC0) == 64;
    }
}
```
`Control/LogBuffer.cs`:
```csharp
using System.Globalization;

namespace KeyRelay.Service.Control;

/// <summary>Keeps the last 500 log lines (Information and above) for the tray's log page.</summary>
internal sealed class LogBuffer : ILoggerProvider
{
    internal const int CAPACITY = 500;
    private readonly Lock _gate = new();
    private readonly string[] _lines = new string[CAPACITY];
    private int _next;
    private int _count;

    public ILogger CreateLogger(string categoryName) => new BufferLogger(this, categoryName[(categoryName.LastIndexOf('.') + 1)..]);

    internal void Add(string line)
    {
        lock (_gate)
        {
            _lines[_next] = line;
            _next = (_next + 1) % CAPACITY;
            _count = Math.Min(_count + 1, CAPACITY);
        }
    }

    /// <returns>Lines oldest first.</returns>
    internal List<string> Snapshot()
    {
        lock (_gate)
        {
            var lines = new List<string>(_count);
            for (var i = 0; i < _count; i++) lines.Add(_lines[(_next - _count + i + CAPACITY) % CAPACITY]);
            return lines;
        }
    }

    public void Dispose()
    {
    }

    private sealed class BufferLogger(LogBuffer buffer, string category) : ILogger
    {
        public IDisposable? BeginScope<TState>(TState state) where TState : notnull => null;

        public bool IsEnabled(LogLevel logLevel) => logLevel >= LogLevel.Information;

        public void Log<TState>(LogLevel logLevel, EventId eventId, TState state, Exception? exception, Func<TState, Exception?, string> formatter)
        {
            if (!IsEnabled(logLevel)) return;
            var level = logLevel switch { LogLevel.Warning => "WARN", >= LogLevel.Error => "ERROR", _ => "INFO" };
            var error = exception is null ? "" : $" | {exception.Message}";
            buffer.Add(string.Create(CultureInfo.InvariantCulture, $"{DateTime.Now:HH:mm:ss} {level} {category}: {formatter(state, exception)}{error}"));
        }
    }
}
```
`Control/ControlHandler.cs`:
```csharp
using System.Security.Cryptography.X509Certificates;
using KeyRelay.Protocol.Control;
using KeyRelay.Service.Agent;
using KeyRelay.Service.Relay;
using KeyRelay.Service.Session;
using KeyRelay.Service.Settings;

namespace KeyRelay.Service.Control;

/// <summary>What the control handler reads and changes.</summary>
internal sealed record ControlContext(
    DeviceRegistry Devices,
    PairingCodes Pairing,
    RelayHub Hub,
    AgentLink Agent,
    LatencyStats Latency,
    SettingsStore Settings,
    LogBuffer Logs,
    AgentSupervisor Supervisor,
    X509Certificate2 Certificate);

/// <summary>Answers control pipe requests from the tray app.</summary>
internal sealed partial class ControlHandler(ControlContext context, ILogger<ControlHandler> logger)
{
    internal ControlResponse Handle(ControlRequest request) => request.Op switch
    {
        ControlOps.STATUS => new ControlResponse { Status = Status() },
        ControlOps.PAIR_START => new ControlResponse { Pairing = StartPairing() },
        ControlOps.PAIR_STOP => StopPairing(),
        ControlOps.DEVICES => new ControlResponse { Devices = Devices() },
        ControlOps.REVOKE => Revoke(request.DeviceId),
        ControlOps.NETWORK => new ControlResponse { Network = Network() },
        ControlOps.SET_FUNNEL => SetFunnel(request.FunnelAddress ?? ""),
        ControlOps.LOGS => new ControlResponse { Logs = context.Logs.Snapshot() },
        _ => ControlResponse.Fail(ControlErrors.UNKNOWN_OP),
    };

    private ControlStatus Status()
    {
        var latency = context.Latency.Snapshot();
        return new ControlStatus
        {
            AgentConnected = context.Agent.Connected,
            AgentSession = context.Supervisor.ActiveSession,
            InputDesktop = context.Agent.DesktopName,
            Writable = context.Agent.Writable,
            Ready = context.Agent.Ready,
            ConnectedDevice = context.Hub.ActiveDeviceName,
            LatencyP50Micros = latency.P50Micros,
            LatencyP99Micros = latency.P99Micros,
            LatencyPerSecondMicros = latency.PerSecondMaxMicros,
            KeysToday = latency.KeysToday,
            PairingActive = context.Pairing.ActiveUntil is not null,
            DeviceCount = context.Devices.List().Count,
        };
    }

    private PairingInfo StartPairing()
    {
        var settings = context.Settings.Load();
        var addresses = PairingAddresses.Order(PairingAddresses.Current(), settings.FunnelAddress, Environment.MachineName);
        var (code, expiresAt) = context.Pairing.Start();
        var link = PairingAddresses.BuildUri(Environment.MachineName, addresses, settings.Port, CertificateStore.Fingerprint(context.Certificate), code);
        LogPairingStarted(addresses.Count);
        return new PairingInfo(link, addresses, expiresAt);
    }

    private ControlResponse StopPairing()
    {
        context.Pairing.Stop();
        return new ControlResponse();
    }

    private List<DeviceInfo> Devices()
    {
        var active = context.Hub.ActiveDeviceId;
        return [.. context.Devices.List().Select(d => new DeviceInfo(d.Id, d.Name, d.AddedAt, d.LastSeenAt, d.Id == active))];
    }

    private ControlResponse Revoke(string? deviceId)
    {
        if (deviceId is null || !context.Devices.Revoke(deviceId)) return ControlResponse.Fail(ControlErrors.UNKNOWN_DEVICE);
        context.Hub.CloseDevice(deviceId);
        LogRevoked(deviceId);
        return new ControlResponse();
    }

    private NetworkInfo Network()
    {
        var settings = context.Settings.Load();
        var addresses = PairingAddresses.Order(PairingAddresses.Current(), settings.FunnelAddress, Environment.MachineName);
        return new NetworkInfo(settings.Port, settings.FunnelAddress, CertificateStore.Fingerprint(context.Certificate), addresses);
    }

    private ControlResponse SetFunnel(string address)
    {
        address = address.Trim();
        if (!ServiceSettings.IsValidFunnelAddress(address)) return ControlResponse.Fail(ControlErrors.INVALID_FUNNEL);
        context.Settings.Save(context.Settings.Load() with { FunnelAddress = address });
        LogFunnelSet(address);
        return new ControlResponse();
    }

    [LoggerMessage(Level = LogLevel.Information, Message = "Pairing started with {Count} addresses")]
    private partial void LogPairingStarted(int count);

    [LoggerMessage(Level = LogLevel.Information, Message = "Device {DeviceId} revoked")]
    private partial void LogRevoked(string deviceId);

    [LoggerMessage(Level = LogLevel.Information, Message = "Funnel address set to '{Address}'")]
    private partial void LogFunnelSet(string address);
}
```
`Control/ControlServer.cs`:
```csharp
using System.IO.Pipes;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text.Json;
using KeyRelay.Protocol.Control;

namespace KeyRelay.Service.Control;

/// <summary>Where the control pipe lives and who may use it.</summary>
internal sealed record ControlOptions(string PipeName, PipeSecurity Security)
{
    /// <summary>SYSTEM and Administrators get full control; the console user (INTERACTIVE) may read and write, as the tray runs unelevated.</summary>
    internal static ControlOptions Production()
    {
        var security = new PipeSecurity();
        security.AddAccessRule(new PipeAccessRule(new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null), PipeAccessRights.FullControl, AccessControlType.Allow));
        security.AddAccessRule(new PipeAccessRule(new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null), PipeAccessRights.FullControl, AccessControlType.Allow));
        security.AddAccessRule(new PipeAccessRule(new SecurityIdentifier(WellKnownSidType.InteractiveSid, null), PipeAccessRights.ReadWrite, AccessControlType.Allow));
        return new ControlOptions(ControlClient.PIPE_NAME, security);
    }
}

/// <summary>Serves the tray app over the control pipe, a few clients at a time.</summary>
internal sealed partial class ControlServer(ControlOptions options, ControlHandler handler, ILogger<ControlServer> logger) : BackgroundService
{
    private const int MAX_INSTANCES = 4;
    private static readonly TimeSpan IDLE_TIMEOUT = TimeSpan.FromSeconds(60);
    private static readonly TimeSpan BUSY_RETRY = TimeSpan.FromSeconds(1);

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        var first = true;
        while (!stoppingToken.IsCancellationRequested)
        {
            NamedPipeServerStream pipe;
            try
            {
                pipe = NamedPipeServerStreamAcl.Create(options.PipeName, PipeDirection.InOut, MAX_INSTANCES, PipeTransmissionMode.Byte,
                    PipeOptions.Asynchronous | (first ? PipeOptions.FirstPipeInstance : PipeOptions.None), 0, 0, options.Security);
                first = false;
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                // All instances busy, or another process squats the name (access denied); never stop the service.
                LogPipeUnavailable(ex);
                await Task.Delay(BUSY_RETRY, stoppingToken).ConfigureAwait(false);
                continue;
            }
            try
            {
                await pipe.WaitForConnectionAsync(stoppingToken).ConfigureAwait(false);
            }
            catch
            {
                await pipe.DisposeAsync().ConfigureAwait(false);
                throw;
            }
            _ = ServeAsync(pipe, stoppingToken);
        }
    }

    private async Task ServeAsync(NamedPipeServerStream pipe, CancellationToken stoppingToken)
    {
        await using (pipe.ConfigureAwait(false))
        {
            var channel = new ControlChannel(pipe);
            try
            {
                while (true)
                {
                    using var idle = CancellationTokenSource.CreateLinkedTokenSource(stoppingToken);
                    idle.CancelAfter(IDLE_TIMEOUT);
                    var request = await channel.ReadAsync<ControlRequest>(idle.Token).ConfigureAwait(false);
                    if (request is null) return;
                    await channel.WriteAsync(handler.Handle(request), idle.Token).ConfigureAwait(false);
                }
            }
            catch (Exception ex) when (ex is IOException or OperationCanceledException or InvalidDataException or JsonException)
            {
                LogClientDropped(ex.GetType().Name);
            }
            catch (Exception ex)
            {
                LogRequestFailed(ex);
            }
        }
    }

    [LoggerMessage(Level = LogLevel.Debug, Message = "Control pipe instance unavailable")]
    private partial void LogPipeUnavailable(Exception ex);

    [LoggerMessage(Level = LogLevel.Debug, Message = "Control client dropped ({Reason})")]
    private partial void LogClientDropped(string reason);

    [LoggerMessage(Level = LogLevel.Error, Message = "Control request failed")]
    private partial void LogRequestFailed(Exception ex);
}
```
`Program.cs` (T9 sürümüne `LogBuffer` ve kontrol kanalı eklenir; tam dosya):
```csharp
using KeyRelay.Service;
using KeyRelay.Service.Agent;
using KeyRelay.Service.Control;
using KeyRelay.Service.Native;
using KeyRelay.Service.Relay;
using KeyRelay.Service.Session;
using KeyRelay.Service.Settings;
using Microsoft.Extensions.Hosting.WindowsServices;

if (args.Length > 0 && args[0] == "--agent") return AgentHost.Main(args);

var data = ServicePaths.DATA_DIRECTORY;
Directory.CreateDirectory(data);
using var logs = new LogBuffer();
var builder = Host.CreateApplicationBuilder(args);
builder.Logging.AddProvider(logs);
var services = builder.Services;
services.AddWindowsService(options => options.ServiceName = "KeyRelay");
if (WindowsServiceHelpers.IsWindowsService()) services.AddSingleton<IHostLifetime, SessionAwareServiceLifetime>();
services.AddSingleton(TimeProvider.System);
services.AddSingleton(logs);
services.AddSingleton<SessionSignal>();
services.AddSingleton<LatencyStats>();
services.AddSingleton(AgentLinkOptions.Production());
services.AddSingleton<AgentLink>();
services.AddSingleton<AgentSupervisor>();
services.AddHostedService(provider => provider.GetRequiredService<AgentSupervisor>());
services.AddSingleton(new SettingsStore(data));
services.AddSingleton(provider => new DeviceRegistry(data, provider.GetRequiredService<TimeProvider>()));
services.AddSingleton<PairingCodes>();
services.AddSingleton<AuthThrottle>();
services.AddSingleton(_ => CertificateStore.LoadOrCreate(data));
services.AddSingleton(new SendSas(() => Sas.SendSAS(false)));
services.AddSingleton(provider => ActivatorUtilities.CreateInstance<RelayContext>(provider, provider.GetRequiredService<ILogger<RelayHub>>()));
services.AddSingleton<RelayHub>();
services.AddHostedService<RelayListener>();
services.AddSingleton(ControlOptions.Production());
services.AddSingleton<ControlContext>();
services.AddSingleton<ControlHandler>();
services.AddHostedService<ControlServer>();
await builder.Build().RunAsync().ConfigureAwait(false);
return 0;
```

- [ ] **Step 5: Testlerin geçtiğini gör**

Run: `dotnet test KeyRelay.slnx` → ControlStatusTests (5), PairingAddressesTests (5), LogBufferTests (3), ControlServerTests (10) ve öncekiler geçer. `ToLowerInvariant` için CA1308 hata verirse satırın üstüne `#pragma warning disable CA1308 // DNS names are shown in lower case` / `restore` ekle ve rapora yaz.

- [ ] **Step 6: Commit**

```powershell
git -C $WT add windows/src windows/tests
git -C $WT commit -m "feat(windows): tepsi uygulaması için servis kontrol kanalı eklendi" -m "- tepsi uygulaması durumu, gecikmeyi ve son kayıtları servisten alır
- eşleştirme kodu başlatılır, cihazlar listelenir ve iptal edilir
- qr koduna yalnız gerçekten ulaşılabilen ağ adresleri konur" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 12: `install` / `uninstall` komutu

**Files:**
- Create: `windows/src/KeyRelay.Service/Setup/SasPolicy.cs`, `windows/src/KeyRelay.Service/Setup/Installer.cs`
- Modify: `windows/src/KeyRelay.Service/Program.cs`
- Test: `windows/tests/KeyRelay.Tests/Setup/SasPolicyTests.cs`

**Interfaces:**
- Consumes: T8 `JsonFile`, `ServicePaths`, `SettingsStore`; T9 `CertificateStore.LoadOrCreate`.
- Produces: `internal sealed record InstallRecord(bool SasChanged, int? SasPrevious)`; `internal static class SasPolicy { const string KEY, VALUE; static void Apply(RegistryKey policy, string recordPath); static void Restore(RegistryKey policy, string recordPath); }`; `internal static class Installer { const string SERVICE_NAME = "KeyRelay"; static Task<int> RunAsync(string[] args); }` — komut satırı `install [--port N]`, `uninstall`; çıkış kodları 0 tamam, 1 yönetici değil, 2 hata. T15 Inno betiği ve cihaz adımları bunu çağırır.

- [ ] **Step 1: Failing testleri yaz**

`Setup/SasPolicyTests.cs` (gerçek kayıt defteri yerine HKCU altında geçici anahtar):
```csharp
using KeyRelay.Service.Setup;
using KeyRelay.Tests.TestSupport;
using Microsoft.Win32;

namespace KeyRelay.Tests.Setup;

[TestClass]
public sealed class SasPolicyTests : IDisposable
{
    private readonly TempDirectory _directory = new();
    private readonly string _keyPath = $@"Software\KeyRelayTests\{Guid.NewGuid():N}";
    private readonly RegistryKey _policy;

    public SasPolicyTests() => _policy = Registry.CurrentUser.CreateSubKey(_keyPath);

    private string Record => Path.Combine(_directory.Path, "install.json");

    public void Dispose()
    {
        _policy.Dispose();
        Registry.CurrentUser.DeleteSubKeyTree(_keyPath, throwOnMissingSubKey: false);
        _directory.Dispose();
    }

    [TestMethod]
    public void Apply_ValueAbsent_SetsServicesBitAndRestoreDeletesIt()
    {
        SasPolicy.Apply(_policy, Record);
        var applied = _policy.GetValue(SasPolicy.VALUE);
        SasPolicy.Restore(_policy, Record);

        Assert.AreEqual(1, applied);
        Assert.IsNull(_policy.GetValue(SasPolicy.VALUE));
        Assert.IsFalse(File.Exists(Record));
    }

    [TestMethod]
    public void Apply_EaseOfAccessOnly_AddsServicesBitAndRestoreReturnsTwo()
    {
        _policy.SetValue(SasPolicy.VALUE, 2, RegistryValueKind.DWord);

        SasPolicy.Apply(_policy, Record);
        var applied = _policy.GetValue(SasPolicy.VALUE);
        SasPolicy.Restore(_policy, Record);

        Assert.AreEqual(3, applied);
        Assert.AreEqual(2, _policy.GetValue(SasPolicy.VALUE));
    }

    [TestMethod]
    public void Apply_ServicesAlreadyAllowed_ChangesNothingAndRestoreKeepsValue()
    {
        _policy.SetValue(SasPolicy.VALUE, 1, RegistryValueKind.DWord);

        SasPolicy.Apply(_policy, Record);
        SasPolicy.Restore(_policy, Record);

        Assert.IsFalse(File.Exists(Record));
        Assert.AreEqual(1, _policy.GetValue(SasPolicy.VALUE));
    }

    [TestMethod]
    public void Apply_ValueZero_RestoreReturnsZero()
    {
        _policy.SetValue(SasPolicy.VALUE, 0, RegistryValueKind.DWord);

        SasPolicy.Apply(_policy, Record);
        SasPolicy.Restore(_policy, Record);

        Assert.AreEqual(0, _policy.GetValue(SasPolicy.VALUE));
    }

    [TestMethod]
    public void Apply_SecondInstallAfterUserReset_KeepsFirstRecord()
    {
        SasPolicy.Apply(_policy, Record);
        _policy.SetValue(SasPolicy.VALUE, 0, RegistryValueKind.DWord);

        SasPolicy.Apply(_policy, Record);
        SasPolicy.Restore(_policy, Record);

        Assert.IsNull(_policy.GetValue(SasPolicy.VALUE));
    }

    [TestMethod]
    public void Restore_WithoutRecord_DoesNothing()
    {
        _policy.SetValue(SasPolicy.VALUE, 0, RegistryValueKind.DWord);

        SasPolicy.Restore(_policy, Record);

        Assert.AreEqual(0, _policy.GetValue(SasPolicy.VALUE));
    }
}
```

- [ ] **Step 2: Başarısız olduğunu gör**

Run: `dotnet test KeyRelay.slnx --filter "FullyQualifiedName~SasPolicyTests"` → derleme hatası.

- [ ] **Step 3: `SasPolicy` ve `Installer`**

`Setup/SasPolicy.cs`:
```csharp
using Microsoft.Win32;

namespace KeyRelay.Service.Setup;

/// <summary>What install found in SoftwareSASGeneration, so uninstall restores only what KeyRelay changed.</summary>
/// <param name="SasChanged">Install changed the value.</param>
/// <param name="SasPrevious">The value before install; null when it did not exist.</param>
internal sealed record InstallRecord(bool SasChanged, int? SasPrevious);

/// <summary>Lets services call SendSAS (SoftwareSASGeneration bit 1) and undoes that on uninstall.</summary>
internal static class SasPolicy
{
    internal const string KEY = @"SOFTWARE\Microsoft\Windows\CurrentVersion\Policies\System";
    internal const string VALUE = "SoftwareSASGeneration";
    private const int SERVICES = 1;

    /// <summary>Sets the services bit if missing; records the original state only the first time.</summary>
    internal static void Apply(RegistryKey policy, string recordPath)
    {
        var current = policy.GetValue(VALUE) as int?;
        if (current is { } value && (value & SERVICES) != 0) return;
        if (JsonFile.Read<InstallRecord>(recordPath) is not { SasChanged: true }) JsonFile.Write(recordPath, new InstallRecord(true, current));
        policy.SetValue(VALUE, (current ?? 0) | SERVICES, RegistryValueKind.DWord);
    }

    /// <summary>Puts back the value install found (deletes it when it was absent).</summary>
    internal static void Restore(RegistryKey policy, string recordPath)
    {
        if (JsonFile.Read<InstallRecord>(recordPath) is not { SasChanged: true } record) return;
        if (record.SasPrevious is { } previous) policy.SetValue(VALUE, previous, RegistryValueKind.DWord);
        else policy.DeleteValue(VALUE, throwOnMissingValue: false);
        File.Delete(recordPath);
    }
}
```
`Setup/Installer.cs`:
```csharp
using System.ComponentModel;
using System.Diagnostics;
using System.Globalization;
using System.Security.AccessControl;
using System.Security.Principal;
using System.ServiceProcess;
using KeyRelay.Service.Relay;
using KeyRelay.Service.Settings;
using Microsoft.Win32;

namespace KeyRelay.Service.Setup;

/// <summary>"install [--port N]" and "uninstall", run elevated by the setup program. Both are idempotent.</summary>
internal static class Installer
{
    internal const string SERVICE_NAME = "KeyRelay";
    private const string RUN_KEY = @"SOFTWARE\Microsoft\Windows\CurrentVersion\Run";
    private const string FIREWALL_RULE = "KeyRelay";
    private const string RECORD_FILE = "install.json";
    private const int EXIT_OK = 0;
    private const int EXIT_NOT_ADMIN = 1;
    private const int EXIT_FAILED = 2;
    private const int ERROR_SERVICE_DOES_NOT_EXIST = 1060;
    private static readonly TimeSpan TOOL_TIMEOUT = TimeSpan.FromSeconds(30);

    /// <returns>0 done, 1 not elevated, 2 a step failed (details on stderr).</returns>
    internal static async Task<int> RunAsync(string[] args)
    {
        if (!Environment.IsPrivilegedProcess)
        {
            await Console.Error.WriteLineAsync("Run as administrator.").ConfigureAwait(false);
            return EXIT_NOT_ADMIN;
        }
        try
        {
            if (args[0] == "install") await InstallAsync(ParsePort(args)).ConfigureAwait(false);
            else await UninstallAsync().ConfigureAwait(false);
            return EXIT_OK;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or InvalidOperationException
            or Win32Exception or OperationCanceledException or System.ServiceProcess.TimeoutException)
        {
            await Console.Error.WriteLineAsync(ex.Message).ConfigureAwait(false);
            return EXIT_FAILED;
        }
    }

    private static async Task InstallAsync(int? port)
    {
        var data = ServicePaths.DATA_DIRECTORY;
        SecureDataDirectory(data);
        CertificateStore.LoadOrCreate(data).Dispose();
        var store = new SettingsStore(data);
        var settings = port is { } p ? store.Load() with { Port = p } : store.Load();
        store.Save(settings);
        using (var policy = Registry.LocalMachine.CreateSubKey(SasPolicy.KEY)) SasPolicy.Apply(policy, Path.Combine(data, RECORD_FILE));
        var exe = Environment.ProcessPath!;
        var tray = Path.Combine(Path.GetDirectoryName(exe)!, "Tray", "KeyRelay.Tray.exe");
        using (var run = Registry.LocalMachine.CreateSubKey(RUN_KEY)) run.SetValue(SERVICE_NAME, $"\"{tray}\" --background");
        await RegisterServiceAsync(exe).ConfigureAwait(false);
        // "delete" exits 1 when the rule does not exist yet.
        await RunToolAsync("netsh", "advfirewall", "firewall", "delete", "rule", $"name={FIREWALL_RULE}").ConfigureAwait(false);
        await RequireAsync("netsh", "advfirewall", "firewall", "add", "rule", $"name={FIREWALL_RULE}", "dir=in", "action=allow",
            "protocol=TCP", $"localport={settings.Port.ToString(CultureInfo.InvariantCulture)}", "profile=private", $"program={exe}").ConfigureAwait(false);
        RestartService();
    }

    private static async Task UninstallAsync()
    {
        StopServiceIfPresent();
        await RunToolAsync("sc.exe", "delete", SERVICE_NAME).ConfigureAwait(false);
        using (var run = Registry.LocalMachine.OpenSubKey(RUN_KEY, writable: true)) run?.DeleteValue(SERVICE_NAME, throwOnMissingValue: false);
        await RunToolAsync("netsh", "advfirewall", "firewall", "delete", "rule", $"name={FIREWALL_RULE}").ConfigureAwait(false);
        using (var policy = Registry.LocalMachine.CreateSubKey(SasPolicy.KEY)) SasPolicy.Restore(policy, Path.Combine(ServicePaths.DATA_DIRECTORY, RECORD_FILE));
    }

    private static async Task RegisterServiceAsync(string exe)
    {
        var exists = await RunToolAsync("sc.exe", "query", SERVICE_NAME).ConfigureAwait(false) != ERROR_SERVICE_DOES_NOT_EXIST;
        await RequireAsync("sc.exe", exists ? "config" : "create", SERVICE_NAME, "binPath=", $"\"{exe}\"", "start=", "auto",
            "obj=", "LocalSystem", "DisplayName=", "KeyRelay").ConfigureAwait(false);
        await RequireAsync("sc.exe", "description", SERVICE_NAME, "Forwards keys from a paired Android tablet, including on the lock screen.").ConfigureAwait(false);
        await RequireAsync("sc.exe", "failure", SERVICE_NAME, "reset=", "86400", "actions=", "restart/5000/restart/5000/restart/5000").ConfigureAwait(false);
    }

    /// <summary>Only SYSTEM and Administrators may read the certificate key, device hashes and settings.</summary>
    internal static void SecureDataDirectory(string path)
    {
        var security = new DirectorySecurity();
        security.SetAccessRuleProtection(isProtected: true, preserveInheritance: false);
        foreach (var sid in new[] { WellKnownSidType.LocalSystemSid, WellKnownSidType.BuiltinAdministratorsSid })
        {
            security.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(sid, null), FileSystemRights.FullControl,
                InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit, PropagationFlags.None, AccessControlType.Allow));
        }
        Directory.CreateDirectory(path).SetAccessControl(security);
    }

    private static void RestartService()
    {
        using var service = new ServiceController(SERVICE_NAME);
        if (service.Status != ServiceControllerStatus.Stopped)
        {
            service.Stop();
            service.WaitForStatus(ServiceControllerStatus.Stopped, TOOL_TIMEOUT);
        }
        service.Start();
        service.WaitForStatus(ServiceControllerStatus.Running, TOOL_TIMEOUT);
    }

    private static void StopServiceIfPresent()
    {
        using var service = new ServiceController(SERVICE_NAME);
        try
        {
            if (service.Status == ServiceControllerStatus.Stopped) return;
            service.Stop();
            service.WaitForStatus(ServiceControllerStatus.Stopped, TOOL_TIMEOUT);
        }
        catch (InvalidOperationException)
        {
            // Not installed.
        }
    }

    private static int? ParsePort(string[] args)
    {
        var index = Array.IndexOf(args, "--port");
        if (index < 0) return null;
        if (index + 1 < args.Length && int.TryParse(args[index + 1], NumberStyles.None, CultureInfo.InvariantCulture, out var port) && port is > 0 and <= 65535)
            return port;
        throw new InvalidOperationException("--port needs a number between 1 and 65535.");
    }

    private static async Task RequireAsync(string file, params string[] arguments)
    {
        var code = await RunToolAsync(file, arguments).ConfigureAwait(false);
        if (code != 0) throw new InvalidOperationException($"{file} {arguments[0]} failed with exit code {code}.");
    }

    /// <returns>The tool's exit code; its output is echoed for the setup log.</returns>
    private static async Task<int> RunToolAsync(string file, params string[] arguments)
    {
        var start = new ProcessStartInfo(file) { UseShellExecute = false, RedirectStandardOutput = true, RedirectStandardError = true, CreateNoWindow = true };
        foreach (var argument in arguments) start.ArgumentList.Add(argument);
        using var process = Process.Start(start) ?? throw new InvalidOperationException($"{file} did not start.");
        using var timeout = new CancellationTokenSource(TOOL_TIMEOUT);
        var output = process.StandardOutput.ReadToEndAsync(timeout.Token);
        var errors = process.StandardError.ReadToEndAsync(timeout.Token);
        try
        {
            await process.WaitForExitAsync(timeout.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            process.Kill(entireProcessTree: true);
            throw;
        }
        Console.WriteLine($"{file} {string.Join(' ', arguments)} -> {process.ExitCode} {(await output.ConfigureAwait(false)).Trim()} {(await errors.ConfigureAwait(false)).Trim()}");
        return process.ExitCode;
    }
}
```
`Program.cs` (son hâli):
```csharp
using KeyRelay.Service;
using KeyRelay.Service.Agent;
using KeyRelay.Service.Control;
using KeyRelay.Service.Native;
using KeyRelay.Service.Relay;
using KeyRelay.Service.Session;
using KeyRelay.Service.Settings;
using KeyRelay.Service.Setup;
using Microsoft.Extensions.Hosting.WindowsServices;

if (args.Length > 0 && args[0] == "--agent") return AgentHost.Main(args);
if (args.Length > 0 && args[0] is "install" or "uninstall") return await Installer.RunAsync(args).ConfigureAwait(false);

var data = ServicePaths.DATA_DIRECTORY;
// A recreated folder must not inherit ProgramData's ACL.
Installer.SecureDataDirectory(data);
using var logs = new LogBuffer();
var builder = Host.CreateApplicationBuilder(args);
builder.Logging.AddProvider(logs);
var services = builder.Services;
services.AddWindowsService(options => options.ServiceName = Installer.SERVICE_NAME);
if (WindowsServiceHelpers.IsWindowsService()) services.AddSingleton<IHostLifetime, SessionAwareServiceLifetime>();
services.AddSingleton(TimeProvider.System);
services.AddSingleton(logs);
services.AddSingleton<SessionSignal>();
services.AddSingleton<LatencyStats>();
services.AddSingleton(AgentLinkOptions.Production());
services.AddSingleton<AgentLink>();
services.AddSingleton<AgentSupervisor>();
services.AddHostedService(provider => provider.GetRequiredService<AgentSupervisor>());
services.AddSingleton(new SettingsStore(data));
services.AddSingleton(provider => new DeviceRegistry(data, provider.GetRequiredService<TimeProvider>()));
services.AddSingleton<PairingCodes>();
services.AddSingleton<AuthThrottle>();
services.AddSingleton(_ => CertificateStore.LoadOrCreate(data));
services.AddSingleton(new SendSas(() => Sas.SendSAS(false)));
services.AddSingleton(provider => ActivatorUtilities.CreateInstance<RelayContext>(provider, provider.GetRequiredService<ILogger<RelayHub>>()));
services.AddSingleton<RelayHub>();
services.AddHostedService<RelayListener>();
services.AddSingleton(ControlOptions.Production());
services.AddSingleton<ControlContext>();
services.AddSingleton<ControlHandler>();
services.AddHostedService<ControlServer>();
await builder.Build().RunAsync().ConfigureAwait(false);
return 0;
```

- [ ] **Step 4: Testlerin geçtiğini gör**

Run: `dotnet test KeyRelay.slnx` → SasPolicyTests (6) ve öncekiler geçer.

- [ ] **Step 5: Yönetici olmayan çağrı (yalnız kabuk yükseltilmemişse)**

```powershell
$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $admin) { dotnet run --project $WT\windows\src\KeyRelay.Service -- install; "exit=$LASTEXITCODE" }
```
Expected (yükseltilmemiş kabukta): `Run as administrator.` ve `exit=1`. Kabuk yükseltilmişse bu adım atlanır (bu adımda servis kurulmaz); gerçek kurulum T15'te homepc'de doğrulanır.

- [ ] **Step 6: Commit**

```powershell
git -C $WT add windows/src/KeyRelay.Service windows/tests/KeyRelay.Tests/Setup
git -C $WT commit -m "feat(windows): kurulum ve kaldırma tek komutla yapılır" -m "- servis kaydı, çökmede yeniden başlatma, güvenlik duvarı izni ve oturum açılışında tepsi uygulaması tek komutla kurulur
- ctrl+alt+del izni yalnız gerekiyorsa açılır, kaldırırken eski hâline döner
- komut tekrar çalıştırılınca aynı sonucu verir; ayarlar ve eşleşmiş cihazlar korunur" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 13: Tepsi uygulaması kabuğu — WinUI 3, Mica, tepsi simgesi, gezinme, Durum sayfası

Ekranlar onaylı taslağa (`docs/design/keyrelay-mockups.html`, Windows bölümü) göre yazılır; görsel onay T15'te alınır (bitiş 10). Bu görevde birim test yoktur: tepsi simgesi kararı `ControlStatus.ToTrayState` içinde ve T11'de testlidir. Doğrulama: derleme, `--smoke` çalıştırması (pencere göstermeden her sayfayı açar, XAML hatası varsa sıfırdan farklı çıkar) ve yayın boyutu.

**Files:**
- Modify: `windows/Directory.Packages.props`, `windows/KeyRelay.slnx`
- Create: `windows/tools/make-icons.cs`
- Create: `windows/src/KeyRelay.Tray/KeyRelay.Tray.csproj`, `app.manifest`, `App.xaml`, `App.xaml.cs`, `MainWindow.xaml`, `MainWindow.xaml.cs`, `Ui.cs`, `Strings/Resources.resw`, `Pages/StatusPage.xaml`, `Pages/StatusPage.xaml.cs`
- Create (üretilir): `windows/src/KeyRelay.Tray/Assets/KeyRelay.ico`, `KeyRelay.png`, `tray-ready.ico`, `tray-waiting.ico`, `tray-error.ico`

**Interfaces:**
- Consumes: T11 `ControlClient`, `ControlRequest`, `ControlOps`, `ControlStatus`, `TrayState`, `TrayStates.From`.
- Produces (T14 kullanır): `App.Current` (`App`), `App.Control` (`ControlClient`), `App.Status` (`ControlStatus?`), `App.StatusChanged` (`event Action`), `App.Shell` (`MainWindow?`), `App.ShowWindow()`; `MainWindow.Open(string tag)` (etiketler: `status`, `devices`, `pair`, `network`, `logs`), `MainWindow.OpenEveryPage()`; `Ui.Text(key)`, `Ui.Format(key, values)`, `Ui.AnimationsEnabled`, `Ui.IsControlFailure(Exception)`, `Ui.Milliseconds(int micros)`; App.xaml kaynakları `ReadyBrush`, `WaitingBrush`, `ErrorBrush`, `NumberFont`, `CardStyle`, `CardKeyStyle`, `CardValueStyle`.
- H.NotifyIcon.WinUI 2.4.1 (paketten doğrulandı): `H.NotifyIcon.TaskbarIcon` — `Icon` (`System.Drawing.Icon`), `ToolTipText`, `LeftClickCommand`, `NoLeftClickDelay`, `ContextFlyout`, `ForceCreate(bool)` (XML doc: "turns on Efficiency Mode by default"; ikilideki parametre adı `enablesEfficiencyMode`), `Dispose()`.

- [ ] **Step 1: Paketler ve çözüm**

`windows/Directory.Packages.props` `ItemGroup`'una:
```xml
    <PackageVersion Include="Microsoft.WindowsAppSDK" Version="2.5.1" />
    <PackageVersion Include="H.NotifyIcon.WinUI" Version="2.4.1" />
    <PackageVersion Include="QRCoder" Version="1.8.0" />
```
`windows/KeyRelay.slnx` `/src/` klasörüne `<Project Path="src/KeyRelay.Tray/KeyRelay.Tray.csproj" />` ekle.

- [ ] **Step 2: İkon üreticisi ve ikonlar**

`windows/tools/make-icons.cs`:
```csharp
#:package System.Drawing.Common@10.0.12
#:property TargetFramework=net10.0-windows
#:property TreatWarningsAsErrors=false

using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;

// Draws the KeyRelay mark and the three tray glyphs of docs/design/keyrelay-mockups.html into PNG-compressed .ico files.
if (args.Length != 1) throw new ArgumentException("usage: dotnet run tools/make-icons.cs -- <Assets folder>");
var assets = args[0];
int[] appSizes = [16, 20, 24, 32, 48, 64, 256];
int[] traySizes = [16, 20, 24, 32, 48];
WriteIcon(Path.Combine(assets, "KeyRelay.ico"), appSizes, DrawMark);
File.WriteAllBytes(Path.Combine(assets, "KeyRelay.png"), Png(256, DrawMark));
WriteIcon(Path.Combine(assets, "tray-ready.ico"), traySizes, DrawReady);
WriteIcon(Path.Combine(assets, "tray-waiting.ico"), traySizes, DrawWaiting);
WriteIcon(Path.Combine(assets, "tray-error.ico"), traySizes, DrawError);
Console.WriteLine($"icons written to {Path.GetFullPath(assets)}");

// Every drawing uses the 64 x 64 space of the mockup's SVG viewBox.
static void DrawMark(Graphics g)
{
    using (var square = RoundedRect(new RectangleF(0, 0, 64, 64), 14))
    using (var background = new LinearGradientBrush(new PointF(0, 0), new PointF(64, 64), Color.FromArgb(0x2A, 0x4F, 0xC9), Color.FromArgb(0x0B, 0x14, 0x33)))
    {
        g.FillPath(background, square);
    }
    using (var cap = RoundedRect(new RectangleF(6, 14, 30, 30), 8))
    using (var capFill = new SolidBrush(Color.FromArgb(0xEA, 0xF0, 0xFF)))
    {
        g.FillPath(capFill, cap);
    }
    using var plus = RoundPen(Color.FromArgb(0x1E, 0x3A, 0x8F), 3.5f);
    g.DrawLine(plus, 15, 29, 27, 29);
    g.DrawLine(plus, 21, 23, 21, 35);
    using var flow = RoundPen(Color.FromArgb(0x89, 0xA8, 0xFF), 4);
    g.DrawLine(flow, 40, 30, 58, 30);
    using var head = RoundPen(Color.FromArgb(0xEA, 0xF0, 0xFF), 4);
    PointF[] arrowHead = [new(52, 23), new(59, 30), new(52, 37)];
    g.DrawLines(head, arrowHead);
}

// Tray glyphs fill more of the box than the mockup's 18 px tile so they stay readable at 16 px.
static void DrawReady(Graphics g)
{
    using var fill = new SolidBrush(Color.FromArgb(0x2F, 0xD4, 0x9C));
    g.FillEllipse(fill, 10, 10, 44, 44);
}

static void DrawWaiting(Graphics g)
{
    using var ring = new Pen(Color.FromArgb(0xF2, 0xB5, 0x44), 8);
    g.DrawEllipse(ring, 13, 13, 38, 38);
}

static void DrawError(Graphics g)
{
    using var cross = RoundPen(Color.FromArgb(0xFF, 0x6B, 0x6B), 9);
    g.DrawLine(cross, 16, 16, 48, 48);
    g.DrawLine(cross, 48, 16, 16, 48);
}

static Pen RoundPen(Color color, float width) =>
    new(color, width) { StartCap = LineCap.Round, EndCap = LineCap.Round, LineJoin = LineJoin.Round };

static GraphicsPath RoundedRect(RectangleF r, float radius)
{
    var d = radius * 2;
    var path = new GraphicsPath();
    path.AddArc(r.X, r.Y, d, d, 180, 90);
    path.AddArc(r.Right - d, r.Y, d, d, 270, 90);
    path.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
    path.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
    path.CloseFigure();
    return path;
}

static byte[] Png(int size, Action<Graphics> draw)
{
    using var bitmap = new Bitmap(size, size, PixelFormat.Format32bppArgb);
    using (var g = Graphics.FromImage(bitmap))
    {
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.PixelOffsetMode = PixelOffsetMode.HighQuality;
        g.ScaleTransform(size / 64f, size / 64f);
        draw(g);
    }
    using var stream = new MemoryStream();
    bitmap.Save(stream, ImageFormat.Png);
    return stream.ToArray();
}

// ICO container with PNG entries (supported since Windows Vista).
static void WriteIcon(string path, int[] sizes, Action<Graphics> draw)
{
    var images = sizes.Select(size => Png(size, draw)).ToArray();
    using var writer = new BinaryWriter(File.Create(path));
    writer.Write((ushort)0);
    writer.Write((ushort)1);
    writer.Write((ushort)images.Length);
    var offset = 6 + 16 * images.Length;
    for (var i = 0; i < images.Length; i++)
    {
        var side = (byte)(sizes[i] >= 256 ? 0 : sizes[i]);
        writer.Write(side);
        writer.Write(side);
        writer.Write((byte)0);
        writer.Write((byte)0);
        writer.Write((ushort)1);
        writer.Write((ushort)32);
        writer.Write(images[i].Length);
        writer.Write(offset);
        offset += images[i].Length;
    }
    foreach (var image in images) writer.Write(image);
}
```
Run:
```powershell
Set-Location $WT\windows
New-Item -ItemType Directory -Force src\KeyRelay.Tray\Assets | Out-Null
dotnet run tools/make-icons.cs -- src/KeyRelay.Tray/Assets
Get-ChildItem src\KeyRelay.Tray\Assets | Select-Object Name, Length
```
Expected: beş dosya, her biri sıfırdan büyük. (`dotnet run <dosya>.cs` .NET 10 dosya tabanlı uygulamadır; `windows\global.json` 10.0.302'yi seçer.)

- [ ] **Step 3: Proje dosyaları**

`windows/src/KeyRelay.Tray/KeyRelay.Tray.csproj`:
```xml
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <OutputType>WinExe</OutputType>
    <TargetFramework>net10.0-windows10.0.26100.0</TargetFramework>
    <TargetPlatformMinVersion>10.0.17763.0</TargetPlatformMinVersion>
    <RootNamespace>KeyRelay.Tray</RootNamespace>
    <ApplicationManifest>app.manifest</ApplicationManifest>
    <ApplicationIcon>Assets\KeyRelay.ico</ApplicationIcon>
    <RuntimeIdentifier>win-x64</RuntimeIdentifier>
    <UseWinUI>true</UseWinUI>
    <WindowsPackageType>None</WindowsPackageType>
    <WindowsAppSDKSelfContained>true</WindowsAppSDKSelfContained>
    <SelfContained>true</SelfContained>
  </PropertyGroup>
  <ItemGroup>
    <PackageReference Include="Microsoft.WindowsAppSDK" />
    <PackageReference Include="H.NotifyIcon.WinUI" />
    <PackageReference Include="QRCoder" />
    <ProjectReference Include="..\KeyRelay.Protocol\KeyRelay.Protocol.csproj" />
    <Content Include="Assets\**" CopyToOutputDirectory="PreserveNewest" />
  </ItemGroup>
</Project>
```
(`RuntimeIdentifier` şarttır: `Microsoft.WindowsAppSDK.SelfContained.targets` "AnyCPU" platformunda mimariyi RID'den okur, yoksa "WindowsAppSDKSelfContained requires a supported Windows architecture" hatası verir — 2.0.4 Base paketinde doğrulandı.)

`app.manifest`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<assembly manifestVersion="1.0" xmlns="urn:schemas-microsoft-com:asm.v1">
  <assemblyIdentity version="1.0.0.0" name="KeyRelay.Tray.app" />
  <application xmlns="urn:schemas-microsoft-com:asm.v3">
    <windowsSettings>
      <dpiAwareness xmlns="http://schemas.microsoft.com/SMI/2016/WindowsSettings">PerMonitorV2</dpiAwareness>
    </windowsSettings>
  </application>
</assembly>
```

`Strings/Resources.resw` (tüm Tray metinleri; T14 sayfaları da buradan okur):
```xml
<?xml version="1.0" encoding="utf-8"?>
<root>
  <resheader name="resmimetype"><value>text/microsoft-resx</value></resheader>
  <resheader name="version"><value>2.0</value></resheader>
  <resheader name="reader"><value>System.Resources.ResXResourceReader, System.Windows.Forms, Version=4.0.0.0, Culture=neutral, PublicKeyToken=b77a5c561934e089</value></resheader>
  <resheader name="writer"><value>System.Resources.ResXResourceWriter, System.Windows.Forms, Version=4.0.0.0, Culture=neutral, PublicKeyToken=b77a5c561934e089</value></resheader>
  <data name="AppTitle.Text" xml:space="preserve"><value>KeyRelay</value></data>
  <data name="NavStatus.Content" xml:space="preserve"><value>Durum</value></data>
  <data name="NavDevices.Content" xml:space="preserve"><value>Cihazlar</value></data>
  <data name="NavPair.Content" xml:space="preserve"><value>Eşleştir</value></data>
  <data name="NavNetwork.Content" xml:space="preserve"><value>Ağ</value></data>
  <data name="NavLogs.Content" xml:space="preserve"><value>Kayıtlar</value></data>
  <data name="TrayOpen" xml:space="preserve"><value>KeyRelay'i aç</value></data>
  <data name="TrayExit" xml:space="preserve"><value>Çıkış</value></data>
  <data name="TrayTooltipReady" xml:space="preserve"><value>KeyRelay · tablet bağlı</value></data>
  <data name="TrayTooltipWaiting" xml:space="preserve"><value>KeyRelay · tablet bekleniyor</value></data>
  <data name="TrayTooltipError" xml:space="preserve"><value>KeyRelay · servise ulaşılamıyor</value></data>
  <data name="NoValue" xml:space="preserve"><value>–</value></data>
  <data name="StatusTitle.Text" xml:space="preserve"><value>Durum</value></data>
  <data name="StatusLeadRunning" xml:space="preserve"><value>Servis çalışıyor · ajan oturumu: {0}</value></data>
  <data name="StatusLeadNoAgent" xml:space="preserve"><value>Servis çalışıyor · ajan bekleniyor</value></data>
  <data name="StatusLeadUnreachable" xml:space="preserve"><value>Servise ulaşılamıyor. KeyRelay servisi çalışıyor mu?</value></data>
  <data name="StatusDeviceLabel.Text" xml:space="preserve"><value>Bağlı cihaz</value></data>
  <data name="StatusNoDevice" xml:space="preserve"><value>Bağlı cihaz yok</value></data>
  <data name="StatusLatencyLabel.Text" xml:space="preserve"><value>p50 / p99 gecikme</value></data>
  <data name="LatencyFormat" xml:space="preserve"><value>{0} / {1} ms</value></data>
  <data name="StatusDesktopLabel.Text" xml:space="preserve"><value>Giriş masaüstü</value></data>
  <data name="DesktopWritable" xml:space="preserve"><value>{0} · yazılabilir</value></data>
  <data name="DesktopNotWritable" xml:space="preserve"><value>{0} · yazılamıyor</value></data>
  <data name="StatusGraphLabel.Text" xml:space="preserve"><value>Son 1 dakika · tuş başına gecikme</value></data>
  <data name="StatusTodayLabel.Text" xml:space="preserve"><value>Bugün</value></data>
  <data name="KeysTodayFormat" xml:space="preserve"><value>{0:N0} tuş</value></data>
  <data name="DevicesTitle.Text" xml:space="preserve"><value>Cihazlar</value></data>
  <data name="DevicesLead.Text" xml:space="preserve"><value>Eşlenmiş tabletler. İptal edilen cihazın bağlantısı hemen kesilir ve bir daha bağlanamaz.</value></data>
  <data name="DevicesAdd.Content" xml:space="preserve"><value>QR ile ekle</value></data>
  <data name="DevicesEmpty.Text" xml:space="preserve"><value>Henüz eşlenmiş cihaz yok.</value></data>
  <data name="DeviceConnected" xml:space="preserve"><value>bağlı</value></data>
  <data name="DeviceLastSeen" xml:space="preserve"><value>son görülme {0:g}</value></data>
  <data name="DevicePaired" xml:space="preserve"><value>eşleşme {0:d}</value></data>
  <data name="DeviceRevoke" xml:space="preserve"><value>İptal et</value></data>
  <data name="RevokeTitle" xml:space="preserve"><value>Cihaz iptal edilsin mi?</value></data>
  <data name="RevokeBody" xml:space="preserve"><value>{0} bir daha bağlanamaz. Yeniden eklemek için QR ile eşleştirin.</value></data>
  <data name="RevokeConfirm" xml:space="preserve"><value>İptal et</value></data>
  <data name="RevokeCancel" xml:space="preserve"><value>Vazgeç</value></data>
  <data name="PairTitle.Text" xml:space="preserve"><value>Eşleştir</value></data>
  <data name="PairLead.Text" xml:space="preserve"><value>Tablette KeyRelay → PC ekle → QR okut. Kod 5 dakika geçerli ve tek kullanımlık.</value></data>
  <data name="PairAddressesLabel.Text" xml:space="preserve"><value>QR'daki adresler</value></data>
  <data name="PairNewCode.Content" xml:space="preserve"><value>Yeni kod</value></data>
  <data name="PairClose.Content" xml:space="preserve"><value>Kapat</value></data>
  <data name="PairRemaining" xml:space="preserve"><value>{0:m\:ss} kaldı</value></data>
  <data name="PairExpired" xml:space="preserve"><value>Kodun süresi doldu. Yeni kod alın.</value></data>
  <data name="PairSucceeded" xml:space="preserve"><value>Eşleşti. Tablet Cihazlar listesinde.</value></data>
  <data name="NetworkTitle.Text" xml:space="preserve"><value>Ağ</value></data>
  <data name="NetworkLead.Text" xml:space="preserve"><value>Dışarıdan erişim için Tailscale Funnel ya da yerel ağ.</value></data>
  <data name="NetworkPortLabel.Text" xml:space="preserve"><value>Port</value></data>
  <data name="NetworkFunnelLabel.Text" xml:space="preserve"><value>Funnel adresi</value></data>
  <data name="NetworkFunnelBox.PlaceholderText" xml:space="preserve"><value>makine.tailnet.ts.net:8443</value></data>
  <data name="NetworkFunnelSave.Content" xml:space="preserve"><value>Kaydet</value></data>
  <data name="NetworkFingerprintLabel.Text" xml:space="preserve"><value>Sertifika parmak izi</value></data>
  <data name="NetworkAddressesLabel.Text" xml:space="preserve"><value>QR'a konacak adresler</value></data>
  <data name="FunnelInvalid" xml:space="preserve"><value>Funnel adresi ad:port biçiminde olmalı; port 443, 8443 ya da 10000.</value></data>
  <data name="FunnelSaved" xml:space="preserve"><value>Kaydedildi. Yeni QR kodlarında ilk adres bu olacak.</value></data>
  <data name="LogsTitle.Text" xml:space="preserve"><value>Kayıtlar</value></data>
  <data name="LogsLead.Text" xml:space="preserve"><value>Son 500 satır</value></data>
  <data name="LogsRefresh.Content" xml:space="preserve"><value>Yenile</value></data>
  <data name="ServiceUnreachable" xml:space="preserve"><value>Servise ulaşılamıyor.</value></data>
</root>
```

`App.xaml`:
```xml
<Application
    x:Class="KeyRelay.Tray.App"
    xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
    xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml">
    <Application.Resources>
        <ResourceDictionary>
            <ResourceDictionary.MergedDictionaries>
                <XamlControlsResources xmlns="using:Microsoft.UI.Xaml.Controls" />
            </ResourceDictionary.MergedDictionaries>
            <!-- Brand accent #4C7DFF replaces the system accent: one colour language on Android and Windows. -->
            <Color x:Key="SystemAccentColor">#4C7DFF</Color>
            <Color x:Key="SystemAccentColorLight1">#6A93FF</Color>
            <Color x:Key="SystemAccentColorLight2">#89A8FF</Color>
            <Color x:Key="SystemAccentColorLight3">#B3C6FF</Color>
            <Color x:Key="SystemAccentColorDark1">#3A66E0</Color>
            <Color x:Key="SystemAccentColorDark2">#2A4FC9</Color>
            <Color x:Key="SystemAccentColorDark3">#1E3A8F</Color>
            <SolidColorBrush x:Key="ReadyBrush" Color="#2FD49C" />
            <SolidColorBrush x:Key="WaitingBrush" Color="#F2B544" />
            <SolidColorBrush x:Key="ErrorBrush" Color="#FF6B6B" />
            <FontFamily x:Key="NumberFont">Cascadia Mono, Consolas</FontFamily>
            <Style x:Key="CardStyle" TargetType="Border">
                <Setter Property="Background" Value="{ThemeResource CardBackgroundFillColorDefaultBrush}" />
                <Setter Property="BorderBrush" Value="{ThemeResource CardStrokeColorDefaultBrush}" />
                <Setter Property="BorderThickness" Value="1" />
                <Setter Property="CornerRadius" Value="8" />
                <Setter Property="Padding" Value="16" />
            </Style>
            <Style x:Key="CardKeyStyle" TargetType="TextBlock" BasedOn="{StaticResource CaptionTextBlockStyle}">
                <Setter Property="Foreground" Value="{ThemeResource TextFillColorSecondaryBrush}" />
            </Style>
            <Style x:Key="CardValueStyle" TargetType="TextBlock" BasedOn="{StaticResource SubtitleTextBlockStyle}">
                <Setter Property="Margin" Value="0,6,0,0" />
                <Setter Property="TextWrapping" Value="Wrap" />
            </Style>
        </ResourceDictionary>
    </Application.Resources>
</Application>
```

`Ui.cs`:
```csharp
using System.Globalization;
using System.Runtime.InteropServices;
using System.Text.Json;
using Microsoft.Windows.ApplicationModel.Resources;
using Windows.UI.ViewManagement;

namespace KeyRelay.Tray;

/// <summary>Helpers shared by the window and the pages.</summary>
internal static partial class Ui
{
    private static readonly ResourceLoader STRINGS = new();

    /// <summary>Gets Turkish UI text from Strings/Resources.resw.</summary>
    internal static string Text(string key) => STRINGS.GetString(key);

    internal static string Format(string key, params object?[] values) => string.Format(CultureInfo.CurrentCulture, Text(key), values);

    /// <summary>Gets false when the user turned animation effects off in Windows settings; transitions are then instant.</summary>
    internal static bool AnimationsEnabled => new UISettings().AnimationsEnabled;

    /// <summary>Errors that mean "service unreachable", not a bug.</summary>
    internal static bool IsControlFailure(Exception ex) =>
        ex is IOException or TimeoutException or UnauthorizedAccessException or InvalidDataException or JsonException;

    internal static string Milliseconds(int micros) => (micros / 1000.0).ToString("0.0", CultureInfo.CurrentCulture);

    [LibraryImport("user32.dll")]
    internal static partial uint GetDpiForWindow(nint window);
}
```

`App.xaml.cs`:
```csharp
using H.NotifyIcon;
using KeyRelay.Protocol.Control;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;

namespace KeyRelay.Tray;

/// <summary>Tray app: one instance per user, a tray icon all the time, the window on demand.</summary>
public partial class App : Application
{
    private const string INSTANCE_NAME = @"Local\KeyRelay.Tray";
    private const string SHOW_EVENT_NAME = @"Local\KeyRelay.Tray.Show";
    private static readonly TimeSpan POLL_INTERVAL = TimeSpan.FromSeconds(1);
    private readonly Dictionary<TrayState, System.Drawing.Icon> _icons = [];
    private Mutex? _instance;
    private EventWaitHandle? _showRequests;
    private TaskbarIcon? _trayIcon;
    private DispatcherQueueTimer? _poll;
    private TrayState? _trayState;
    private bool _polling;
    private bool _exiting;

    public App() => InitializeComponent();

    internal static new App Current => (App)Application.Current;

    internal ControlClient Control { get; } = new(ControlClient.PIPE_NAME);

    internal MainWindow? Shell { get; private set; }

    /// <summary>Gets the latest service status, or null while the service is unreachable.</summary>
    internal ControlStatus? Status { get; private set; }

    /// <summary>Raised on the UI thread after every poll.</summary>
    internal event Action? StatusChanged;

    /// <summary>Shows and focuses the window (tray click, second launch, Start menu).</summary>
    internal void ShowWindow()
    {
        if (Shell is null) return;
        Shell.AppWindow.Show();
        Shell.Activate();
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        var arguments = Environment.GetCommandLineArgs();
        if (arguments.Contains("--smoke"))
        {
            new MainWindow().OpenEveryPage();
            Environment.Exit(0);
        }
        _instance = new Mutex(true, INSTANCE_NAME, out var first);
        _showRequests = new EventWaitHandle(false, EventResetMode.AutoReset, SHOW_EVENT_NAME);
        if (!first)
        {
            _showRequests.Set();
            Exit();
            return;
        }
        Shell = new MainWindow();
        Shell.AppWindow.Closing += (_, closing) =>
        {
            if (_exiting) return;
            closing.Cancel = true;
            Shell.AppWindow.Hide();
        };
        CreateTrayIcon();
        ListenForShowRequests();
        StartPolling();
        if (!arguments.Contains("--background")) ShowWindow();
    }

    private void CreateTrayIcon()
    {
        foreach (var state in Enum.GetValues<TrayState>())
            _icons[state] = new System.Drawing.Icon(Path.Combine(AppContext.BaseDirectory, "Assets", IconFile(state)));
        var open = new XamlUICommand();
        open.ExecuteRequested += (_, _) => ShowWindow();
        var exit = new XamlUICommand();
        exit.ExecuteRequested += (_, _) => ExitApp();
        var menu = new MenuFlyout();
        menu.Items.Add(new MenuFlyoutItem { Text = Ui.Text("TrayOpen"), Command = open });
        menu.Items.Add(new MenuFlyoutItem { Text = Ui.Text("TrayExit"), Command = exit });
        _trayIcon = new TaskbarIcon { ContextFlyout = menu, LeftClickCommand = open, NoLeftClickDelay = true };
        // false keeps the process out of Efficiency Mode so the 1 s status poll stays on time.
        _trayIcon.ForceCreate(false);
        UpdateTrayIcon();
    }

    private static string IconFile(TrayState state) => state switch
    {
        TrayState.Ready => "tray-ready.ico",
        TrayState.Waiting => "tray-waiting.ico",
        _ => "tray-error.ico",
    };

    private void UpdateTrayIcon()
    {
        var state = TrayStates.From(Status);
        if (_trayIcon is null || state == _trayState) return;
        _trayState = state;
        _trayIcon.Icon = _icons[state];
        _trayIcon.ToolTipText = Ui.Text(state switch
        {
            TrayState.Ready => "TrayTooltipReady",
            TrayState.Waiting => "TrayTooltipWaiting",
            _ => "TrayTooltipError",
        });
    }

    private void StartPolling()
    {
        _poll = DispatcherQueue.GetForCurrentThread().CreateTimer();
        _poll.Interval = POLL_INTERVAL;
        _poll.Tick += async (_, _) => await PollAsync();
        _poll.Start();
    }

    private async Task PollAsync()
    {
        if (_polling) return;
        _polling = true;
        try
        {
            Status = (await Control.SendAsync(new ControlRequest(ControlOps.STATUS))).Status;
        }
        catch (Exception ex) when (Ui.IsControlFailure(ex))
        {
            Status = null;
        }
        finally
        {
            _polling = false;
        }
        UpdateTrayIcon();
        StatusChanged?.Invoke();
    }

    private void ListenForShowRequests()
    {
        var dispatcher = DispatcherQueue.GetForCurrentThread();
        var requests = _showRequests!;
        var listener = new Thread(() =>
        {
            while (requests.WaitOne()) dispatcher.TryEnqueue(ShowWindow);
        })
        { IsBackground = true, Name = "show-requests" };
        listener.Start();
    }

    private void ExitApp()
    {
        _exiting = true;
        _poll?.Stop();
        _trayIcon?.Dispose();
        foreach (var icon in _icons.Values) icon.Dispose();
        Shell?.Close();
        _instance?.Dispose();
        Exit();
    }
}
```

`MainWindow.xaml`:
```xml
<Window
    x:Class="KeyRelay.Tray.MainWindow"
    xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
    xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml"
    Title="KeyRelay">
    <Window.SystemBackdrop>
        <MicaBackdrop />
    </Window.SystemBackdrop>
    <Grid>
        <Grid.RowDefinitions>
            <RowDefinition Height="48" />
            <RowDefinition Height="*" />
        </Grid.RowDefinitions>
        <Grid x:Name="TitleBar" Padding="16,0" ColumnSpacing="10">
            <Grid.ColumnDefinitions>
                <ColumnDefinition Width="Auto" />
                <ColumnDefinition Width="*" />
            </Grid.ColumnDefinitions>
            <Image Source="ms-appx:///Assets/KeyRelay.png" Width="20" Height="20" VerticalAlignment="Center" />
            <TextBlock x:Uid="AppTitle" Grid.Column="1" VerticalAlignment="Center" Style="{StaticResource CaptionTextBlockStyle}" />
        </Grid>
        <NavigationView
            x:Name="Navigation"
            Grid.Row="1"
            PaneDisplayMode="Left"
            IsBackButtonVisible="Collapsed"
            IsSettingsVisible="False"
            IsPaneToggleButtonVisible="False"
            OpenPaneLength="220"
            SelectionChanged="OnSelectionChanged">
            <NavigationView.MenuItems>
                <NavigationViewItem x:Uid="NavStatus" Tag="status" />
                <NavigationViewItem x:Uid="NavDevices" Tag="devices" />
                <NavigationViewItem x:Uid="NavPair" Tag="pair" />
                <NavigationViewItem x:Uid="NavNetwork" Tag="network" />
                <NavigationViewItem x:Uid="NavLogs" Tag="logs" />
            </NavigationView.MenuItems>
            <Frame x:Name="ContentFrame" Padding="32,24" />
        </NavigationView>
    </Grid>
</Window>
```
`MainWindow.xaml.cs`:
```csharp
using KeyRelay.Tray.Pages;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media.Animation;
using Windows.Graphics;

namespace KeyRelay.Tray;

/// <summary>The window: Mica backdrop, title bar in the content, left navigation with the five pages of the approved mockup.</summary>
public sealed partial class MainWindow : Window
{
    // Mockup window size in effective pixels.
    private const int WIDTH = 960;
    private const int HEIGHT = 620;
    private const double DEFAULT_DPI = 96.0;

    public MainWindow()
    {
        InitializeComponent();
        ExtendsContentIntoTitleBar = true;
        SetTitleBar(TitleBar);
        AppWindow.SetIcon(Path.Combine(AppContext.BaseDirectory, "Assets", "KeyRelay.ico"));
        var scale = Ui.GetDpiForWindow(WinRT.Interop.WindowNative.GetWindowHandle(this)) / DEFAULT_DPI;
        AppWindow.Resize(new SizeInt32((int)(WIDTH * scale), (int)(HEIGHT * scale)));
        Navigation.SelectedItem = Navigation.MenuItems[0];
    }

    /// <summary>Selects a page by its navigation tag (status, devices, pair, network, logs).</summary>
    internal void Open(string tag) =>
        Navigation.SelectedItem = Navigation.MenuItems.OfType<NavigationViewItem>().First(item => (string)item.Tag == tag);

    /// <summary>Visits every page once; --smoke uses it to catch XAML errors without showing the window.</summary>
    internal void OpenEveryPage()
    {
        foreach (var item in Navigation.MenuItems.OfType<NavigationViewItem>()) Navigation.SelectedItem = item;
    }

    private void OnSelectionChanged(NavigationView sender, NavigationViewSelectionChangedEventArgs args)
    {
        if (args.SelectedItem is not NavigationViewItem { Tag: string tag } || PageFor(tag) is not { } page) return;
        NavigationTransitionInfo transition = Ui.AnimationsEnabled ? new EntranceNavigationTransitionInfo() : new SuppressNavigationTransitionInfo();
        ContentFrame.Navigate(page, null, transition);
    }

    private static Type? PageFor(string tag) => tag switch
    {
        "status" => typeof(StatusPage),
        _ => null,
    };
}
```

`Pages/StatusPage.xaml`:
```xml
<Page
    x:Class="KeyRelay.Tray.Pages.StatusPage"
    xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
    xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml">
    <StackPanel Spacing="4">
        <TextBlock x:Uid="StatusTitle" Style="{StaticResource TitleTextBlockStyle}" />
        <TextBlock x:Name="Lead" Style="{StaticResource BodyTextBlockStyle}" Foreground="{ThemeResource TextFillColorSecondaryBrush}" Margin="0,0,0,18" />
        <Grid ColumnSpacing="12" RowSpacing="12">
            <Grid.ColumnDefinitions>
                <ColumnDefinition />
                <ColumnDefinition />
                <ColumnDefinition />
            </Grid.ColumnDefinitions>
            <Grid.RowDefinitions>
                <RowDefinition Height="Auto" />
                <RowDefinition Height="Auto" />
            </Grid.RowDefinitions>
            <Border Style="{StaticResource CardStyle}">
                <StackPanel>
                    <TextBlock x:Uid="StatusDeviceLabel" Style="{StaticResource CardKeyStyle}" />
                    <StackPanel Orientation="Horizontal" Spacing="8" Margin="0,6,0,0">
                        <Ellipse x:Name="DeviceDot" Width="10" Height="10" VerticalAlignment="Center" />
                        <TextBlock x:Name="DeviceValue" Style="{StaticResource SubtitleTextBlockStyle}" TextWrapping="Wrap" />
                    </StackPanel>
                </StackPanel>
            </Border>
            <Border Grid.Column="1" Style="{StaticResource CardStyle}">
                <StackPanel>
                    <TextBlock x:Uid="StatusLatencyLabel" Style="{StaticResource CardKeyStyle}" />
                    <TextBlock x:Name="LatencyValue" Style="{StaticResource CardValueStyle}" FontFamily="{StaticResource NumberFont}" />
                </StackPanel>
            </Border>
            <Border Grid.Column="2" Style="{StaticResource CardStyle}">
                <StackPanel>
                    <TextBlock x:Uid="StatusDesktopLabel" Style="{StaticResource CardKeyStyle}" />
                    <TextBlock x:Name="DesktopValue" Style="{StaticResource CardValueStyle}" />
                </StackPanel>
            </Border>
            <Border Grid.Row="1" Grid.ColumnSpan="2" Style="{StaticResource CardStyle}">
                <StackPanel>
                    <TextBlock x:Uid="StatusGraphLabel" Style="{StaticResource CardKeyStyle}" />
                    <Grid x:Name="Graph" Height="60" Margin="0,10,0,0" ColumnSpacing="3" />
                </StackPanel>
            </Border>
            <Border Grid.Row="1" Grid.Column="2" Style="{StaticResource CardStyle}">
                <StackPanel>
                    <TextBlock x:Uid="StatusTodayLabel" Style="{StaticResource CardKeyStyle}" />
                    <TextBlock x:Name="KeysValue" Style="{StaticResource CardValueStyle}" FontFamily="{StaticResource NumberFont}" />
                </StackPanel>
            </Border>
        </Grid>
    </StackPanel>
</Page>
```
`Pages/StatusPage.xaml.cs`:
```csharp
using KeyRelay.Protocol.Control;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;

namespace KeyRelay.Tray.Pages;

/// <summary>Durum: connected tablet, p50/p99, input desktop, last-minute latency graph, keys today (mockup).</summary>
public sealed partial class StatusPage : Page
{
    private const int GRAPH_SECONDS = 60;
    private const double GRAPH_HEIGHT = 60;
    // Finish line 7 target (p99 below 2 ms) as the graph's minimum scale, so small values do not look alarming.
    private const int GRAPH_FLOOR_MICROS = 2000;
    private readonly Rectangle[] _bars = new Rectangle[GRAPH_SECONDS];

    public StatusPage()
    {
        InitializeComponent();
        CreateBars();
        Loaded += (_, _) =>
        {
            App.Current.StatusChanged += Render;
            Render();
        };
        Unloaded += (_, _) => App.Current.StatusChanged -= Render;
    }

    private void CreateBars()
    {
        var brush = (Brush)Application.Current.Resources["AccentFillColorDefaultBrush"];
        for (var i = 0; i < _bars.Length; i++)
        {
            Graph.ColumnDefinitions.Add(new ColumnDefinition());
            _bars[i] = new Rectangle { Fill = brush, RadiusX = 2, RadiusY = 2, VerticalAlignment = VerticalAlignment.Bottom, Height = 0 };
            Grid.SetColumn(_bars[i], i);
            Graph.Children.Add(_bars[i]);
        }
    }

    private void Render()
    {
        var status = App.Current.Status;
        Lead.Text = status is null ? Ui.Text("StatusLeadUnreachable")
            : status.AgentSession is { } session ? Ui.Format("StatusLeadRunning", session)
            : Ui.Text("StatusLeadNoAgent");
        DeviceValue.Text = status?.ConnectedDevice ?? Ui.Text("StatusNoDevice");
        DeviceDot.Fill = (Brush)Application.Current.Resources[TrayStates.From(status) switch
        {
            TrayState.Ready => "ReadyBrush",
            TrayState.Waiting => "WaitingBrush",
            _ => "ErrorBrush",
        }];
        LatencyValue.Text = status is null ? Ui.Text("NoValue")
            : Ui.Format("LatencyFormat", Ui.Milliseconds(status.LatencyP50Micros), Ui.Milliseconds(status.LatencyP99Micros));
        DesktopValue.Text = status is null ? Ui.Text("NoValue")
            : Ui.Format(status.Writable ? "DesktopWritable" : "DesktopNotWritable", status.InputDesktop);
        KeysValue.Text = Ui.Format("KeysTodayFormat", status?.KeysToday ?? 0);
        RenderGraph(status?.LatencyPerSecondMicros ?? []);
    }

    private void RenderGraph(IReadOnlyList<int> perSecond)
    {
        var max = Math.Max(GRAPH_FLOOR_MICROS, perSecond.Count == 0 ? 0 : perSecond.Max());
        for (var i = 0; i < _bars.Length; i++) _bars[i].Height = i < perSecond.Count ? GRAPH_HEIGHT * perSecond[i] / max : 0;
    }
}
```

- [ ] **Step 4: Derle ve duman testi**

```powershell
Set-Location $WT\windows
dotnet build src\KeyRelay.Tray -c Debug
$smoke = Start-Process -FilePath "$WT\windows\src\KeyRelay.Tray\bin\Debug\net10.0-windows10.0.26100.0\win-x64\KeyRelay.Tray.exe" -ArgumentList '--smoke' -Wait -PassThru
"smoke exit=$($smoke.ExitCode)"
dotnet test KeyRelay.slnx
```
Expected: derleme uyarısız, `smoke exit=0` (servis yokken sayfa "Servise ulaşılamıyor" durumunu çizer), tüm testler geçer.
Olası düzeltmeler (yalnız bu hatalar görülürse, rapora yazılır):
- `This version of the Windows App SDK requires Microsoft.Windows.SDK.NET.Ref <x> or later` → csproj'a hata metnindeki `<WindowsSdkPackageVersion>` değerini ekle.
- XAML'dan üretilen `public` tipler için CA1515 → Tray csproj'a `<NoWarn>$(NoWarn);CA1515</NoWarn>` (XAML derleyicisi `public partial` üretir).
- `App` için CA1001 (Mutex, tepsi simgesi, ikonlar) → aynı `NoWarn`'a `CA1001` (WinUI `Application` yaşam döngüsünde Dispose çağrılmaz; kaynaklar `ExitApp`'te bırakılır).
- `dotnet build` XAML derleyicisi veya PRI üretimi için Visual Studio MSBuild isterse: kod değiştirmeden controller'a bildir (CI ve yayın komutları buna göre seçilir).
- Verikor/DLP bir DLL'i engellerse: Global Constraints'teki gibi controller'a bildir.

- [ ] **Step 5: Yayın boyutu**

```powershell
dotnet publish src\KeyRelay.Tray -c Release -r win-x64 --self-contained -o $WT\artifacts\layout\Tray
"{0:N1} MB, {1} files" -f ((Get-ChildItem -Recurse $WT\artifacts\layout\Tray | Measure-Object Length -Sum).Sum / 1MB), (Get-ChildItem -Recurse -File $WT\artifacts\layout\Tray).Count
```
Rapora yaz (karar 12). `Microsoft.WindowsAppSDK.AI`/`ML` yükleri belirgin yer kaplıyorsa (ör. onnxruntime*.dll) dosya adları ve boyutlarıyla rapora yaz; metapaket yerine bileşen paketlerine geçmek kullanıcı kararıdır.

- [ ] **Step 6: Commit**

```powershell
git -C $WT add windows/Directory.Packages.props windows/KeyRelay.slnx windows/tools/make-icons.cs windows/src/KeyRelay.Tray
git -C $WT commit -m "feat(windows): tepsi uygulaması ve durum ekranı eklendi" -m "- görev çubuğunda bağlı, bekliyor ve hata durumunu renkle gösteren simge
- mica arka planlı pencere, açık ve koyu temaya uyum, marka rengi
- durum ekranında bağlı tablet, gecikme, giriş ekranı ve son bir dakikanın grafiği
- animasyonlar windows'ta kapatılmışsa geçişler anında olur" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 14: Tepsi sayfaları — Cihazlar, Eşleştir (animasyonlu QR), Ağ, Kayıtlar

**Files:**
- Create: `windows/src/KeyRelay.Tray/Pages/DevicesPage.xaml(.cs)`, `PairPage.xaml(.cs)`, `NetworkPage.xaml(.cs)`, `LogsPage.xaml(.cs)`
- Modify: `windows/src/KeyRelay.Tray/MainWindow.xaml.cs` (`PageFor`)

**Interfaces:**
- Consumes: T13 `App.Current.Control/Status/Shell`, `MainWindow.Open`, `Ui.*`, App.xaml stilleri, `Resources.resw` anahtarları; T11 `ControlOps.DEVICES/REVOKE/PAIR_START/PAIR_STOP/NETWORK/SET_FUNNEL/LOGS`, `DeviceInfo`, `PairingInfo`, `NetworkInfo`, `ControlResponse`.
- Produces: `PairPage.CARD_ANIMATION` (bağlantılı animasyon anahtarı: Cihazlar'daki "QR ile ekle" düğmesinden Eşleştir'deki QR kartına).

Birim test yoktur (davranış servis tarafında testli); doğrulama `--smoke` ve T15'teki görsel onaydır.

- [ ] **Step 1: Cihazlar**

`Pages/DevicesPage.xaml`:
```xml
<Page
    x:Class="KeyRelay.Tray.Pages.DevicesPage"
    xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
    xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml">
    <StackPanel Spacing="4">
        <TextBlock x:Uid="DevicesTitle" Style="{StaticResource TitleTextBlockStyle}" />
        <TextBlock x:Uid="DevicesLead" Style="{StaticResource BodyTextBlockStyle}" Foreground="{ThemeResource TextFillColorSecondaryBrush}" TextWrapping="Wrap" Margin="0,0,0,18" />
        <Button x:Name="AddButton" x:Uid="DevicesAdd" Style="{StaticResource AccentButtonStyle}" Click="OnAdd" Margin="0,0,0,12" />
        <TextBlock x:Name="Empty" x:Uid="DevicesEmpty" Visibility="Collapsed" Foreground="{ThemeResource TextFillColorSecondaryBrush}" />
        <StackPanel x:Name="Rows" Spacing="8" />
    </StackPanel>
</Page>
```
`Pages/DevicesPage.xaml.cs`:
```csharp
using KeyRelay.Protocol.Control;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Animation;

namespace KeyRelay.Tray.Pages;

/// <summary>Cihazlar: paired tablets with connection state; revoking drops the tablet at once.</summary>
public sealed partial class DevicesPage : Page
{
    public DevicesPage()
    {
        InitializeComponent();
        if (Ui.AnimationsEnabled) Rows.ChildrenTransitions = new TransitionCollection { new EntranceThemeTransition { IsStaggeringEnabled = true } };
        Loaded += async (_, _) => await RefreshAsync();
    }

    private async Task RefreshAsync()
    {
        IReadOnlyList<DeviceInfo> devices;
        try
        {
            devices = (await App.Current.Control.SendAsync(new ControlRequest(ControlOps.DEVICES))).Devices ?? [];
        }
        catch (Exception ex) when (Ui.IsControlFailure(ex))
        {
            devices = [];
        }
        Rows.Children.Clear();
        foreach (var device in devices) Rows.Children.Add(Row(device));
        Empty.Visibility = devices.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    private Grid Row(DeviceInfo device)
    {
        var row = new Grid
        {
            Padding = new Thickness(14, 12, 14, 12),
            CornerRadius = new CornerRadius(8),
            ColumnSpacing = 12,
            Background = (Brush)Application.Current.Resources["CardBackgroundFillColorDefaultBrush"],
        };
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        row.Children.Add(new TextBlock { Text = device.Name, FontWeight = FontWeights.SemiBold, VerticalAlignment = VerticalAlignment.Center });
        var detail = new TextBlock
        {
            Text = Detail(device),
            VerticalAlignment = VerticalAlignment.Center,
            Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"],
        };
        Grid.SetColumn(detail, 1);
        row.Children.Add(detail);
        var revoke = new Button { Content = Ui.Text("DeviceRevoke") };
        revoke.Click += async (_, _) => await RevokeAsync(device);
        Grid.SetColumn(revoke, 2);
        row.Children.Add(revoke);
        return row;
    }

    private static string Detail(DeviceInfo device) =>
        device.Connected ? Ui.Text("DeviceConnected")
        : device.LastSeenAt is { } seen ? Ui.Format("DeviceLastSeen", seen.ToLocalTime())
        : Ui.Format("DevicePaired", device.AddedAt.ToLocalTime());

    private async Task RevokeAsync(DeviceInfo device)
    {
        var dialog = new ContentDialog
        {
            XamlRoot = XamlRoot,
            Title = Ui.Text("RevokeTitle"),
            Content = Ui.Format("RevokeBody", device.Name),
            PrimaryButtonText = Ui.Text("RevokeConfirm"),
            CloseButtonText = Ui.Text("RevokeCancel"),
            DefaultButton = ContentDialogButton.Close,
        };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
        try
        {
            await App.Current.Control.SendAsync(new ControlRequest(ControlOps.REVOKE, DeviceId: device.Id));
        }
        catch (Exception ex) when (Ui.IsControlFailure(ex))
        {
            // The list below shows what the service still has.
        }
        await RefreshAsync();
    }

    private void OnAdd(object sender, RoutedEventArgs e)
    {
        if (Ui.AnimationsEnabled) ConnectedAnimationService.GetForCurrentView().PrepareToAnimate(PairPage.CARD_ANIMATION, AddButton);
        App.Current.Shell?.Open("pair");
    }
}
```

- [ ] **Step 2: Eşleştir**

`Pages/PairPage.xaml`:
```xml
<Page
    x:Class="KeyRelay.Tray.Pages.PairPage"
    xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
    xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml">
    <Page.Resources>
        <Storyboard x:Name="SweepStoryboard" RepeatBehavior="Forever">
            <DoubleAnimation Storyboard.TargetName="SweepTransform" Storyboard.TargetProperty="Y" From="-60" To="220" Duration="0:0:2.6">
                <DoubleAnimation.EasingFunction>
                    <CubicEase EasingMode="EaseInOut" />
                </DoubleAnimation.EasingFunction>
            </DoubleAnimation>
        </Storyboard>
        <Storyboard x:Name="EnterStoryboard">
            <DoubleAnimation Storyboard.TargetName="QrCard" Storyboard.TargetProperty="Opacity" From="0" To="1" Duration="0:0:0.35" />
            <DoubleAnimation Storyboard.TargetName="QrScale" Storyboard.TargetProperty="ScaleX" From="0.92" To="1" Duration="0:0:0.45">
                <DoubleAnimation.EasingFunction>
                    <BackEase EasingMode="EaseOut" Amplitude="0.3" />
                </DoubleAnimation.EasingFunction>
            </DoubleAnimation>
            <DoubleAnimation Storyboard.TargetName="QrScale" Storyboard.TargetProperty="ScaleY" From="0.92" To="1" Duration="0:0:0.45">
                <DoubleAnimation.EasingFunction>
                    <BackEase EasingMode="EaseOut" Amplitude="0.3" />
                </DoubleAnimation.EasingFunction>
            </DoubleAnimation>
        </Storyboard>
    </Page.Resources>
    <StackPanel Spacing="4">
        <TextBlock x:Uid="PairTitle" Style="{StaticResource TitleTextBlockStyle}" />
        <TextBlock x:Uid="PairLead" Style="{StaticResource BodyTextBlockStyle}" Foreground="{ThemeResource TextFillColorSecondaryBrush}" TextWrapping="Wrap" Margin="0,0,0,18" />
        <StackPanel Orientation="Horizontal" Spacing="22">
            <Grid x:Name="QrCard" Width="220" Height="220" CornerRadius="12" Background="White" Padding="12" RenderTransformOrigin="0.5,0.5">
                <Grid.RenderTransform>
                    <ScaleTransform x:Name="QrScale" />
                </Grid.RenderTransform>
                <Grid.Clip>
                    <RectangleGeometry Rect="0,0,220,220" />
                </Grid.Clip>
                <Image x:Name="QrImage" Stretch="Uniform" />
                <Rectangle Height="60" VerticalAlignment="Top" Margin="-12,0,-12,0" IsHitTestVisible="False">
                    <Rectangle.Fill>
                        <LinearGradientBrush StartPoint="0,0" EndPoint="0,1">
                            <GradientStop Color="#004C7DFF" Offset="0" />
                            <GradientStop Color="#594C7DFF" Offset="0.5" />
                            <GradientStop Color="#004C7DFF" Offset="1" />
                        </LinearGradientBrush>
                    </Rectangle.Fill>
                    <Rectangle.RenderTransform>
                        <TranslateTransform x:Name="SweepTransform" Y="-60" />
                    </Rectangle.RenderTransform>
                </Rectangle>
            </Grid>
            <StackPanel Width="380" Spacing="12">
                <Border Style="{StaticResource CardStyle}">
                    <StackPanel>
                        <TextBlock x:Uid="PairAddressesLabel" Style="{StaticResource CardKeyStyle}" />
                        <TextBlock x:Name="Addresses" FontFamily="{StaticResource NumberFont}" Margin="0,6,0,0" LineHeight="22" TextWrapping="Wrap" IsTextSelectionEnabled="True" />
                    </StackPanel>
                </Border>
                <ProgressBar x:Name="Countdown" Maximum="300" />
                <TextBlock x:Name="Remaining" Style="{StaticResource CaptionTextBlockStyle}" />
                <InfoBar x:Name="Result" IsClosable="False" />
                <StackPanel Orientation="Horizontal" Spacing="8">
                    <Button x:Uid="PairNewCode" Style="{StaticResource AccentButtonStyle}" Click="OnNewCode" />
                    <Button x:Uid="PairClose" Click="OnClose" />
                </StackPanel>
            </StackPanel>
        </StackPanel>
    </StackPanel>
</Page>
```
`Pages/PairPage.xaml.cs`:
```csharp
using System.Runtime.InteropServices.WindowsRuntime;
using KeyRelay.Protocol.Control;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media.Animation;
using Microsoft.UI.Xaml.Media.Imaging;
using Microsoft.UI.Xaml.Navigation;
using QRCoder;
using Windows.Storage.Streams;

namespace KeyRelay.Tray.Pages;

/// <summary>Eşleştir: QR on an animated card, the addresses inside it and a 5 minute countdown.</summary>
public sealed partial class PairPage : Page
{
    /// <summary>Connected animation key from the Devices page's add button to the QR card.</summary>
    internal const string CARD_ANIMATION = "PairCard";
    private const int PIXELS_PER_MODULE = 10;
    private const double EXPIRED_OPACITY = 0.15;
    // Mockup ink (#0B111C) on white, RGBA.
    private static readonly byte[] INK = [0x0B, 0x11, 0x1C, 0xFF];
    private static readonly byte[] PAPER = [0xFF, 0xFF, 0xFF, 0xFF];
    private readonly DispatcherQueueTimer _tick;
    private DateTimeOffset _expiresAt;
    private int _devicesAtStart;

    public PairPage()
    {
        InitializeComponent();
        _tick = DispatcherQueue.CreateTimer();
        _tick.Interval = TimeSpan.FromSeconds(1);
        _tick.Tick += (_, _) => Tick();
    }

    protected override async void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
        if (Ui.AnimationsEnabled && ConnectedAnimationService.GetForCurrentView().GetAnimation(CARD_ANIMATION) is { } animation) animation.TryStart(QrCard);
        await StartAsync();
    }

    protected override async void OnNavigatedFrom(NavigationEventArgs e)
    {
        base.OnNavigatedFrom(e);
        _tick.Stop();
        SweepStoryboard.Stop();
        // An open code lets anyone who sees it pair; leaving the page withdraws it.
        try
        {
            await App.Current.Control.SendAsync(new ControlRequest(ControlOps.PAIR_STOP));
        }
        catch (Exception ex) when (Ui.IsControlFailure(ex))
        {
            // Unreachable service has no open code either.
        }
    }

    private async void OnNewCode(object sender, RoutedEventArgs e) => await StartAsync();

    private void OnClose(object sender, RoutedEventArgs e) => App.Current.Shell?.Open("devices");

    private async Task StartAsync()
    {
        Result.IsOpen = false;
        PairingInfo? pairing;
        try
        {
            pairing = (await App.Current.Control.SendAsync(new ControlRequest(ControlOps.PAIR_START))).Pairing;
        }
        catch (Exception ex) when (Ui.IsControlFailure(ex))
        {
            pairing = null;
        }
        if (pairing is null)
        {
            ShowResult(InfoBarSeverity.Error, "ServiceUnreachable");
            return;
        }
        _devicesAtStart = App.Current.Status?.DeviceCount ?? 0;
        _expiresAt = pairing.ExpiresAt;
        QrImage.Source = await QrImageAsync(pairing.Link);
        QrImage.Opacity = 1;
        Addresses.Text = string.Join(Environment.NewLine, pairing.Addresses);
        Tick();
        _tick.Start();
        if (!Ui.AnimationsEnabled) return;
        EnterStoryboard.Begin();
        SweepStoryboard.Begin();
    }

    private void Tick()
    {
        var status = App.Current.Status;
        if (status is { PairingActive: false } && status.DeviceCount > _devicesAtStart)
        {
            Finish(InfoBarSeverity.Success, "PairSucceeded");
            return;
        }
        var remaining = _expiresAt - DateTimeOffset.Now;
        if (remaining <= TimeSpan.Zero)
        {
            Finish(InfoBarSeverity.Warning, "PairExpired");
            return;
        }
        Countdown.Value = remaining.TotalSeconds;
        Remaining.Text = Ui.Format("PairRemaining", remaining);
    }

    private void Finish(InfoBarSeverity severity, string key)
    {
        _tick.Stop();
        SweepStoryboard.Stop();
        Countdown.Value = 0;
        // A used or expired code must not look scannable.
        QrImage.Opacity = EXPIRED_OPACITY;
        ShowResult(severity, key);
    }

    private void ShowResult(InfoBarSeverity severity, string key)
    {
        Result.Severity = severity;
        Result.Message = Ui.Text(key);
        Result.IsOpen = true;
    }

    private static async Task<BitmapImage> QrImageAsync(string text)
    {
        using var generator = new QRCodeGenerator();
        using var data = generator.CreateQrCode(text, QRCodeGenerator.ECCLevel.M);
        var png = new PngByteQRCode(data).GetGraphic(PIXELS_PER_MODULE, INK, PAPER, true);
        using var stream = new InMemoryRandomAccessStream();
        await stream.WriteAsync(png.AsBuffer());
        stream.Seek(0);
        var image = new BitmapImage();
        await image.SetSourceAsync(stream);
        return image;
    }
}
```

- [ ] **Step 3: Ağ**

`Pages/NetworkPage.xaml`:
```xml
<Page
    x:Class="KeyRelay.Tray.Pages.NetworkPage"
    xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
    xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml">
    <StackPanel Spacing="4">
        <TextBlock x:Uid="NetworkTitle" Style="{StaticResource TitleTextBlockStyle}" />
        <TextBlock x:Uid="NetworkLead" Style="{StaticResource BodyTextBlockStyle}" Foreground="{ThemeResource TextFillColorSecondaryBrush}" Margin="0,0,0,18" />
        <Grid ColumnSpacing="12" RowSpacing="12">
            <Grid.ColumnDefinitions>
                <ColumnDefinition />
                <ColumnDefinition Width="2*" />
            </Grid.ColumnDefinitions>
            <Grid.RowDefinitions>
                <RowDefinition Height="Auto" />
                <RowDefinition Height="Auto" />
            </Grid.RowDefinitions>
            <Border Style="{StaticResource CardStyle}">
                <StackPanel>
                    <TextBlock x:Uid="NetworkPortLabel" Style="{StaticResource CardKeyStyle}" />
                    <TextBlock x:Name="PortValue" Style="{StaticResource CardValueStyle}" FontFamily="{StaticResource NumberFont}" />
                </StackPanel>
            </Border>
            <Border Grid.Column="1" Style="{StaticResource CardStyle}">
                <StackPanel Spacing="8">
                    <TextBlock x:Uid="NetworkFunnelLabel" Style="{StaticResource CardKeyStyle}" />
                    <StackPanel Orientation="Horizontal" Spacing="8">
                        <TextBox x:Name="FunnelBox" x:Uid="NetworkFunnelBox" Width="300" FontFamily="{StaticResource NumberFont}" />
                        <Button x:Uid="NetworkFunnelSave" Style="{StaticResource AccentButtonStyle}" Click="OnSaveFunnel" />
                    </StackPanel>
                </StackPanel>
            </Border>
            <Border Grid.Row="1" Style="{StaticResource CardStyle}">
                <StackPanel>
                    <TextBlock x:Uid="NetworkFingerprintLabel" Style="{StaticResource CardKeyStyle}" />
                    <TextBlock x:Name="FingerprintValue" FontFamily="{StaticResource NumberFont}" FontSize="13" Margin="0,6,0,0" TextWrapping="Wrap" IsTextSelectionEnabled="True" />
                </StackPanel>
            </Border>
            <Border Grid.Row="1" Grid.Column="1" Style="{StaticResource CardStyle}">
                <StackPanel>
                    <TextBlock x:Uid="NetworkAddressesLabel" Style="{StaticResource CardKeyStyle}" />
                    <TextBlock x:Name="AddressesValue" FontFamily="{StaticResource NumberFont}" Margin="0,6,0,0" LineHeight="22" IsTextSelectionEnabled="True" />
                </StackPanel>
            </Border>
        </Grid>
        <InfoBar x:Name="Result" IsClosable="True" Margin="0,12,0,0" />
    </StackPanel>
</Page>
```
`Pages/NetworkPage.xaml.cs`:
```csharp
using System.Globalization;
using KeyRelay.Protocol.Control;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace KeyRelay.Tray.Pages;

/// <summary>Ağ: listening port, Funnel address (setting), certificate fingerprint and the addresses put into the QR.</summary>
public sealed partial class NetworkPage : Page
{
    public NetworkPage()
    {
        InitializeComponent();
        Loaded += async (_, _) => await RefreshAsync();
    }

    private async Task RefreshAsync()
    {
        NetworkInfo? network;
        try
        {
            network = (await App.Current.Control.SendAsync(new ControlRequest(ControlOps.NETWORK))).Network;
        }
        catch (Exception ex) when (Ui.IsControlFailure(ex))
        {
            network = null;
        }
        if (network is null)
        {
            Show(InfoBarSeverity.Error, "ServiceUnreachable");
            return;
        }
        PortValue.Text = network.Port.ToString(CultureInfo.InvariantCulture);
        FunnelBox.Text = network.FunnelAddress;
        FingerprintValue.Text = network.Fingerprint;
        AddressesValue.Text = string.Join(Environment.NewLine, network.Addresses);
    }

    private async void OnSaveFunnel(object sender, RoutedEventArgs e)
    {
        ControlResponse response;
        try
        {
            response = await App.Current.Control.SendAsync(new ControlRequest(ControlOps.SET_FUNNEL, FunnelAddress: FunnelBox.Text));
        }
        catch (Exception ex) when (Ui.IsControlFailure(ex))
        {
            Show(InfoBarSeverity.Error, "ServiceUnreachable");
            return;
        }
        if (!response.Ok)
        {
            Show(InfoBarSeverity.Error, "FunnelInvalid");
            return;
        }
        Show(InfoBarSeverity.Success, "FunnelSaved");
        await RefreshAsync();
    }

    private void Show(InfoBarSeverity severity, string key)
    {
        Result.Severity = severity;
        Result.Message = Ui.Text(key);
        Result.IsOpen = true;
    }
}
```

- [ ] **Step 4: Kayıtlar**

`Pages/LogsPage.xaml`:
```xml
<Page
    x:Class="KeyRelay.Tray.Pages.LogsPage"
    xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
    xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml">
    <Grid RowSpacing="4">
        <Grid.RowDefinitions>
            <RowDefinition Height="Auto" />
            <RowDefinition Height="Auto" />
            <RowDefinition Height="*" />
        </Grid.RowDefinitions>
        <TextBlock x:Uid="LogsTitle" Style="{StaticResource TitleTextBlockStyle}" />
        <StackPanel Grid.Row="1" Orientation="Horizontal" Spacing="12" Margin="0,0,0,12">
            <TextBlock x:Uid="LogsLead" VerticalAlignment="Center" Foreground="{ThemeResource TextFillColorSecondaryBrush}" />
            <Button x:Uid="LogsRefresh" Click="OnRefresh" />
        </StackPanel>
        <Border Grid.Row="2" Style="{StaticResource CardStyle}">
            <ListView x:Name="Lines" SelectionMode="None" FontFamily="{StaticResource NumberFont}" FontSize="12" />
        </Border>
    </Grid>
</Page>
```
`Pages/LogsPage.xaml.cs`:
```csharp
using KeyRelay.Protocol.Control;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace KeyRelay.Tray.Pages;

/// <summary>Kayıtlar: the service's last 500 log lines, newest at the bottom.</summary>
public sealed partial class LogsPage : Page
{
    public LogsPage()
    {
        InitializeComponent();
        Loaded += async (_, _) => await RefreshAsync();
    }

    private async void OnRefresh(object sender, RoutedEventArgs e) => await RefreshAsync();

    private async Task RefreshAsync()
    {
        IReadOnlyList<string> lines;
        try
        {
            lines = (await App.Current.Control.SendAsync(new ControlRequest(ControlOps.LOGS))).Logs ?? [];
        }
        catch (Exception ex) when (Ui.IsControlFailure(ex))
        {
            lines = [Ui.Text("ServiceUnreachable")];
        }
        Lines.ItemsSource = lines;
        if (lines.Count > 0) Lines.ScrollIntoView(lines[^1]);
    }
}
```

- [ ] **Step 5: Gezinme haritası**

`MainWindow.xaml.cs` içindeki `PageFor`:
```csharp
    private static Type? PageFor(string tag) => tag switch
    {
        "status" => typeof(StatusPage),
        "devices" => typeof(DevicesPage),
        "pair" => typeof(PairPage),
        "network" => typeof(NetworkPage),
        "logs" => typeof(LogsPage),
        _ => null,
    };
```

- [ ] **Step 6: Derle ve duman testi**

```powershell
Set-Location $WT\windows
dotnet build src\KeyRelay.Tray -c Debug
$smoke = Start-Process -FilePath "$WT\windows\src\KeyRelay.Tray\bin\Debug\net10.0-windows10.0.26100.0\win-x64\KeyRelay.Tray.exe" -ArgumentList '--smoke' -Wait -PassThru
"smoke exit=$($smoke.ExitCode)"
dotnet test KeyRelay.slnx
```
Expected: uyarısız derleme, `smoke exit=0` (beş sayfa da kurulur; servis yokken her sayfa "Servise ulaşılamıyor" durumunu çizer), tüm testler geçer.

- [ ] **Step 7: Commit**

```powershell
git -C $WT add windows/src/KeyRelay.Tray
git -C $WT commit -m "feat(windows): cihaz, eşleştirme, ağ ve kayıt ekranları eklendi" -m "- eşlenmiş tabletler listelenir, onayla iptal edilir
- eşleştirme qr kodu hareketli kartta, adresleri ve 5 dakikalık süresiyle gösterilir
- funnel adresi girilir, sertifika parmak izi ve qr adresleri görünür
- servisin son 500 kayıt satırı okunur" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 15: Kurulum paketi, geliştirici istemcisi ve homepc'de uçtan uca doğrulama

Kullanıcı homepc başında olmalıdır (TeamViewer). Tablet uygulaması P2'de yazıldığı için tablet yerine `devclient` (aynı protokolü konuşan geliştirici istemcisi) kullanılır; tabletle bitiş doğrulaması P4'tedir.

**Files:**
- Create: `installer/KeyRelay.iss`, `installer/build.ps1`, `windows/tools/devclient.cs`
- Modify: `docs/spikes/2026-10-risk-results.md` ("## P3 Windows doğrulaması")
- Create (repo dışı): `$WT\artifacts\idle-cpu.ps1`

**Interfaces:**
- Consumes: T12 `install`/`uninstall`; T11 kontrol kanalı (`devclient status|pair-uri|logs` homepc'de yönetici komut isteminden, pipe ACL'deki Administrators ile); T2 çerçeveler.
- Produces: `artifacts\installer\KeyRelay-Setup-0.1.0.exe`, `artifacts\installer\KeyRelay-0.1.0.zip`, `artifacts\devclient\devclient.exe`; P4 release iş akışı `installer/build.ps1`'i yeniden kullanır.

- [ ] **Step 1: Geliştirici istemcisi**

`windows/tools/devclient.cs`:
```csharp
#:project ../src/KeyRelay.Protocol/KeyRelay.Protocol.csproj
#:property TargetFramework=net10.0
#:property PublishAot=false
#:property TreatWarningsAsErrors=false

using System.Buffers.Text;
using System.Globalization;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using System.Web;
using KeyRelay.Protocol;
using KeyRelay.Protocol.Control;

// Developer client for checking the Windows side without the Android app (P3 device steps).
// The paired identity is kept in devclient.json in the current directory.
const string STATE_FILE = "devclient.json";
var json = new JsonSerializerOptions { WriteIndented = true };
var command = args.Length > 0 ? args[0] : "";
return command switch
{
    "status" => await ControlAsync(ControlOps.STATUS, r => JsonSerializer.Serialize(r.Status, json)),
    "pair-uri" => await ControlAsync(ControlOps.PAIR_START, r => r.Pairing!.Link),
    "logs" => await ControlAsync(ControlOps.LOGS, r => string.Join(Environment.NewLine, r.Logs!)),
    "pair" when args.Length >= 2 => await PairAsync(args[1], args.Length >= 3 ? args[2] : null),
    "hello" => await SessionAsync(_ => Task.CompletedTask),
    "type" when args.Length == 2 => await SessionAsync(ssl => TypeAsync(ssl, args[1])),
    "key" when args.Length == 2 => await SessionAsync(ssl => TapAsync(ssl, ushort.Parse(args[1], CultureInfo.InvariantCulture))),
    "sas" => await SessionAsync(ssl => ssl.WriteAsync(Frames.Empty(FrameType.Sas)).AsTask()),
    "lock" => await SessionAsync(ssl => ssl.WriteAsync(Frames.Empty(FrameType.Lock)).AsTask()),
    "bench" when args.Length == 2 => await SessionAsync(ssl => BenchAsync(ssl, int.Parse(args[1], CultureInfo.InvariantCulture))),
    "watch" when args.Length == 2 => await SessionAsync(ssl => WatchAsync(ssl, int.Parse(args[1], CultureInfo.InvariantCulture))),
    _ => Usage(),
};

async Task<int> ControlAsync(string op, Func<ControlResponse, string> show)
{
    await using var client = new ControlClient(ControlClient.PIPE_NAME);
    var response = await client.SendAsync(new ControlRequest(op));
    Console.WriteLine(response.Ok ? show(response) : $"error: {response.Error}");
    return response.Ok ? 0 : 1;
}

async Task<int> PairAsync(string link, string? addressOverride)
{
    var query = HttpUtility.ParseQueryString(new Uri(link).Query);
    var port = query["p"]!;
    string[] addresses = addressOverride is not null
        ? [addressOverride]
        : [.. query["a"]!.Split(',').Select(a => a.Contains(':', StringComparison.Ordinal) ? a : $"{a}:{port}")];
    var fingerprint = query["fp"]!;
    var code = Base64Url.DecodeFromChars(query["c"]);
    foreach (var address in addresses)
    {
        try
        {
            using var ssl = await ConnectAsync(address, fingerprint);
            var reader = new FrameReader(ssl, FrameChannel.Tls);
            await ssl.WriteAsync(Frames.Pair(code, "devclient"));
            var answer = await reader.ReadAsync(CancellationToken.None) ?? throw new IOException("closed");
            if (answer.Type != FrameType.Paired)
            {
                Console.WriteLine($"rejected, reason {answer.Payload.Span[0]}");
                return 1;
            }
            var id = Convert.ToHexStringLower(answer.Payload.Span[..Frames.ID_SIZE]);
            var token = Convert.ToHexStringLower(answer.Payload.Span[Frames.ID_SIZE..]);
            File.WriteAllText(STATE_FILE, JsonSerializer.Serialize(new State(address, fingerprint, id, token), json));
            Console.WriteLine($"paired via {address}, device id {id}");
            return 0;
        }
        catch (Exception ex) when (ex is SocketException or IOException or AuthenticationException or OperationCanceledException)
        {
            Console.WriteLine($"{address}: {ex.Message}");
        }
    }
    return 1;
}

async Task<int> SessionAsync(Func<SslStream, Task> action)
{
    var state = JsonSerializer.Deserialize<State>(File.ReadAllText(STATE_FILE)) ?? throw new InvalidOperationException("run pair first");
    using var ssl = await ConnectAsync(state.Address, state.Fingerprint);
    var reader = new FrameReader(ssl, FrameChannel.Tls);
    await ssl.WriteAsync(Frames.Hello(Convert.FromHexString(state.Id), Convert.FromHexString(state.Token)));
    var answer = await reader.ReadAsync(CancellationToken.None);
    if (answer?.Type != FrameType.Welcome)
    {
        Console.WriteLine($"rejected: {answer?.Type}, reason {(answer is { Payload.Length: > 0 } a ? a.Payload.Span[0] : 0)}");
        return 1;
    }
    Console.WriteLine($"welcome from {Encoding.UTF8.GetString(answer.Value.Payload.Span)}");
    using var stop = new CancellationTokenSource();
    var printer = PrintFramesAsync(reader, stop.Token);
    await action(ssl);
    await Task.Delay(500);
    await stop.CancelAsync();
    try
    {
        await printer;
    }
    catch (Exception ex) when (ex is OperationCanceledException or IOException)
    {
        // Session over.
    }
    return 0;
}

static async Task PrintFramesAsync(FrameReader reader, CancellationToken cancellationToken)
{
    while (await reader.ReadAsync(cancellationToken) is { } frame)
    {
        var detail = frame.Type == FrameType.State ? $" ready={frame.Payload.Span[0] & 1}" : "";
        Console.WriteLine($"{DateTime.Now:HH:mm:ss.fff} {frame.Type}{detail}");
    }
}

static async Task TypeAsync(SslStream ssl, string digits)
{
    foreach (var digit in digits)
    {
        if (digit is < '0' or > '9') throw new ArgumentException("digits only");
        // Linux KEY_1..KEY_9 are 2..10, KEY_0 is 11.
        await TapAsync(ssl, (ushort)(digit == '0' ? 11 : digit - '0' + 1));
    }
}

static async Task TapAsync(SslStream ssl, ushort code)
{
    await ssl.WriteAsync(Frames.Key(code, 1));
    await Task.Delay(30);
    await ssl.WriteAsync(Frames.Key(code, 0));
    await Task.Delay(30);
}

// Right Shift taps: harmless on any screen; the service measures each key from TLS read to SendInput return.
static async Task BenchAsync(SslStream ssl, int taps)
{
    for (var i = 0; i < taps; i++)
    {
        await ssl.WriteAsync(Frames.Key(54, 1));
        await Task.Delay(5);
        await ssl.WriteAsync(Frames.Key(54, 0));
        await Task.Delay(5);
    }
}

static async Task WatchAsync(SslStream ssl, int seconds)
{
    for (uint i = 0; i < seconds / 5; i++)
    {
        await ssl.WriteAsync(Frames.Ping(i));
        await Task.Delay(TimeSpan.FromSeconds(5));
    }
}

static async Task<SslStream> ConnectAsync(string address, string fingerprint)
{
    var colon = address.LastIndexOf(':');
    var host = address[..colon];
    var tcp = new TcpClient { NoDelay = true };
    using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(2));
    await tcp.ConnectAsync(host, int.Parse(address[(colon + 1)..], CultureInfo.InvariantCulture), timeout.Token);
    var ssl = new SslStream(tcp.GetStream(), false, (_, certificate, _, _) => certificate is not null && Fingerprint(certificate) == fingerprint);
    await ssl.AuthenticateAsClientAsync(new SslClientAuthenticationOptions { TargetHost = host, EnabledSslProtocols = SslProtocols.Tls13 }, timeout.Token);
    return ssl;
}

static string Fingerprint(X509Certificate certificate)
{
    using var x509 = X509CertificateLoader.LoadCertificate(certificate.GetRawCertData());
    return Base64Url.EncodeToString(SHA256.HashData(x509.PublicKey.ExportSubjectPublicKeyInfo()));
}

static int Usage()
{
    Console.WriteLine("devclient status | pair-uri | logs | pair <keyrelay link> [host:port] | hello | type <digits> | key <linux code> | sas | lock | bench <taps> | watch <seconds>");
    return 64;
}

internal sealed record State(string Address, string Fingerprint, string Id, string Token);
```
Yayınla (homepc'de yalnız .NET 8 runtime var):
```powershell
Set-Location $WT\windows
dotnet publish tools/devclient.cs -c Release -r win-x64 --self-contained -p:PublishSingleFile=true -o $WT\artifacts\devclient
& $WT\artifacts\devclient\devclient.exe
```
Expected: kullanım satırı, çıkış kodu 64.

- [ ] **Step 2: Inno Setup betiği ve derleme betiği**

`installer/KeyRelay.iss`:
```iss
#define AppVersion "0.1.0"

[Setup]
AppId={{5B7C9E2A-3F41-4D8B-9C6E-1A2B3C4D5E6F}
AppName=KeyRelay
AppVersion={#AppVersion}
AppPublisher=KeyRelay
DefaultDirName={autopf}\KeyRelay
DisableProgramGroupPage=yes
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir=..\artifacts\installer
OutputBaseFilename=KeyRelay-Setup-{#AppVersion}
SetupIconFile=..\windows\src\KeyRelay.Tray\Assets\KeyRelay.ico
UninstallDisplayIcon={app}\Tray\KeyRelay.Tray.exe
Compression=lzma2
SolidCompression=yes
WizardStyle=modern

[Languages]
Name: "turkish"; MessagesFile: "compiler:Languages\Turkish.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Files]
Source: "..\artifacts\layout\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\KeyRelay"; Filename: "{app}\Tray\KeyRelay.Tray.exe"

[Run]
Filename: "{app}\KeyRelay.Service.exe"; Parameters: "install"; Flags: runhidden waituntilterminated
Filename: "{app}\Tray\KeyRelay.Tray.exe"; Flags: nowait postinstall skipifsilent runasoriginaluser; Description: "{cm:LaunchProgram,KeyRelay}"

[UninstallRun]
Filename: "{sys}\taskkill.exe"; Parameters: "/im KeyRelay.Tray.exe /f"; Flags: runhidden; RunOnceId: "KeyRelayTray"
Filename: "{app}\KeyRelay.Service.exe"; Parameters: "uninstall"; Flags: runhidden waituntilterminated; RunOnceId: "KeyRelayUninstall"

[Code]
// Upgrades replace files in use: stop the service (it ends the agent) and the tray first.
function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  Code: Integer;
begin
  Exec(ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe'), '-NoProfile -Command "Stop-Service KeyRelay -ErrorAction SilentlyContinue"', '', SW_HIDE, ewWaitUntilTerminated, Code);
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/im KeyRelay.Tray.exe /f', '', SW_HIDE, ewWaitUntilTerminated, Code);
  Result := '';
end;
```
`installer/build.ps1`:
```powershell
# Builds the self-contained layout, the Inno Setup installer and the zip. Run from any directory.
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$layout = Join-Path $root 'artifacts\layout'
$output = Join-Path $root 'artifacts\installer'
Remove-Item -Recurse -Force $layout -ErrorAction SilentlyContinue
# global.json lives in windows\; dotnet picks the SDK from the current directory.
Push-Location (Join-Path $root 'windows')
try {
    dotnet publish src\KeyRelay.Service -c Release -r win-x64 --self-contained -p:PublishSingleFile=true -o $layout
    if ($LASTEXITCODE -ne 0) { throw 'service publish failed' }
    dotnet publish src\KeyRelay.Tray -c Release -r win-x64 --self-contained -o (Join-Path $layout 'Tray')
    if ($LASTEXITCODE -ne 0) { throw 'tray publish failed' }
} finally { Pop-Location }
Remove-Item (Join-Path $layout '*.pdb') -ErrorAction SilentlyContinue
$iscc = @("${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe", "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe") | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $iscc) { throw 'Inno Setup 6 not found (winget install --id JRSoftware.InnoSetup -e)' }
& $iscc (Join-Path $PSScriptRoot 'KeyRelay.iss')
if ($LASTEXITCODE -ne 0) { throw 'ISCC failed' }
Compress-Archive -Path (Join-Path $layout '*') -DestinationPath (Join-Path $output 'KeyRelay-0.1.0.zip') -Force
Get-ChildItem $output | Select-Object Name, @{ n = 'MB'; e = { [math]::Round($_.Length / 1MB, 1) } }
```
Inno Setup'ı kur ve derle:
```powershell
winget install --id JRSoftware.InnoSetup -e --version 6.7.3 --silent --accept-source-agreements --accept-package-agreements
& $WT\installer\build.ps1
```
Expected: `KeyRelay-Setup-0.1.0.exe` ve `KeyRelay-0.1.0.zip`; boyutları rapora yaz.

- [ ] **Step 3: homepc önceki durum (kullanıcıyla)**

Bu görevdeki PC komutları PC'de yönetici komut isteminde (cmd) çalışır; `devclient.exe` `C:\KeyRelayDev` klasöründen çalıştırılır.
```cmd
sc.exe query KeyRelay & reg query HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Run /v KeyRelay & netsh advfirewall firewall show rule name=KeyRelay & reg query HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Policies\System /v SoftwareSASGeneration & dir C:\ProgramData\KeyRelay
```
Expected: servis 1060, Run değeri yok, kural yok, `0x1`, klasör yok. Sonuç belgesine yaz.

- [ ] **Step 4: Kur (geri alma: Step 16)**

PC'de `C:\KeyRelayDev` klasörünü oluştur (`mkdir C:\KeyRelayDev`); `$WT\artifacts\installer\KeyRelay-Setup-0.1.0.exe` ve `$WT\artifacts\devclient\devclient.exe`'yi bu klasöre kopyala. Sonra:
```cmd
C:\KeyRelayDev\KeyRelay-Setup-0.1.0.exe /VERYSILENT /SUPPRESSMSGBOXES /NORESTART /LOG=C:\KeyRelayDev\setup.log
"C:\Program Files\KeyRelay\KeyRelay.Service.exe" install --port 47112
"C:\Program Files\KeyRelay\KeyRelay.Service.exe" install --port 47112
```
İkinci `install` idempotentliği gösterir (aynı çıktı, hata yok). Port 47112: prototip 127.0.0.1:47111'i tutuyor (Global Constraints); Inno'nun çağırdığı ilk `install` varsayılan 47111'i dener, kayıtlarda `Cannot listen on TCP port 47111` görülebilir, `--port 47112` bunu düzeltir.
Doğrula:
```cmd
sc.exe qc KeyRelay & sc.exe qfailure KeyRelay & sc.exe query KeyRelay & reg query HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Run /v KeyRelay & netsh advfirewall firewall show rule name=KeyRelay & icacls C:\ProgramData\KeyRelay & reg query HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Policies\System /v SoftwareSASGeneration & dir C:\ProgramData\KeyRelay
cd /d C:\KeyRelayDev & devclient.exe status & devclient.exe logs
```
Expected: `START_TYPE AUTO_START`, `SERVICE_START_NAME LocalSystem`, üç kez `RESTART -- Delay = 5000`, `RUNNING`; Run değeri `"C:\Program Files\KeyRelay\Tray\KeyRelay.Tray.exe" --background`; kural `LocalPort 47112`, `Profiles Private`; `icacls` yalnız `NT AUTHORITY\SYSTEM` ve `BUILTIN\Administrators`; SAS hâlâ `0x1` ve `install.json` yok (KeyRelay değiştirmedi); status `agentConnected: true`, `agentSession: 1`, `inputDesktop: "Default"`; loglarda `Listening on TCP port 47112`, `Agent ... started in session 1`, `Agent connected`.

- [ ] **Step 5: Tepsi uygulaması (kullanıcıyla)**

Kullanıcı Başlat menüsünden KeyRelay'i açar (oturum açılışında Run ile de açılır). Gözlenecekler: tepside kehribar halka (tablet yok), pencere Mica arka planlı, Durum'da "Servis çalışıyor · ajan oturumu: 1", Giriş masaüstü "Default · yazılabilir". Uygulama ikinci kez açılınca yeni simge oluşmaz, mevcut pencere öne gelir.

- [ ] **Step 6: Eşleştirme (bitiş 6'nın ilk yarısı)**

Kullanıcı Eşleştir sayfasını açar: QR kartı girişe animasyonla gelir, tarama çizgisi akar, 5:00 geri sayım başlar; "QR'daki adresler" Tailscale IP'si, MagicDNS adı ve 192.168.1.10 içerir, VMware/ProtonVPN içermez (Review Focus 4). Ekran görüntüsü alınır. Sonra:
```cmd
cd /d C:\KeyRelayDev
devclient.exe pair-uri
devclient.exe pair "<pair-uri çıktısı>" 192.168.1.10:47112
```
Expected: `paired via 192.168.1.10:47112`; Cihazlar sayfasında "devclient" görünür. (Tray'in açık Eşleştir sayfası ayrı bir kod gösteriyordu; `pair-uri` yeni kod üretir ve eskisini geçersiz kılar — tasarım gereği tek aktif kod.)

- [ ] **Step 7: Gecikme ve boşta CPU (bitiş 7)**

```cmd
devclient.exe bench 1000
devclient.exe status
```
`latencyP50Micros`, `latencyP99Micros`, `keysToday` rapora yazılır; ölçüt p99 < 2000 µs. Ölçüm "KEY TLS'ten okundu → ajanın SendInput dönüşü" aralığıdır (pipe dahil, karar 2). Tutmazsa kod değiştirme: değerleri ve Durum grafiğini rapora yaz, controller'a bildir (aday neden: olay başına `OpenInputDesktop`, tasarım kapısı #21/#28).
`$WT\artifacts\idle-cpu.ps1`:
```powershell
$cores = (Get-CimInstance Win32_ComputerSystem).NumberOfLogicalProcessors
$samples = Get-Counter '\Process(keyrelay.service*)\% Processor Time' -SampleInterval 10 -MaxSamples 60
$samples.CounterSamples | Group-Object InstanceName | ForEach-Object {
    $average = ($_.Group | Measure-Object CookedValue -Average).Average / $cores
    '{0}: {1:N4} % of total CPU (10 min average)' -f $_.Name, $average
}
```
Tablet bağlı değilken ve kullanıcı PC'yi kullanmazken `idle-cpu.ps1`'i PC'de `C:\KeyRelayDev\` klasörüne kopyala, sonra:
```cmd
powershell -NoProfile -ExecutionPolicy Bypass -File C:\KeyRelayDev\idle-cpu.ps1
```
Expected: iki örnek (servis ve ajan) her biri < 0.1. Değerler rapora yazılır.

- [ ] **Step 8: Ctrl+Alt+Del (bitiş 2a)**

`.\devclient.exe sas` → kullanıcı güvenlik ekranını görür (Kilitle / Kullanıcı değiştir / Oturumu kapat / Görev Yöneticisi), Esc ile kapatır. Kayıtlar sayfasında `Ctrl+Alt+Del (SAS) from devclient`.

- [ ] **Step 9: Kilit ekranı (bitiş 2b) ve kilitleme**

`.\devclient.exe lock` → PC kilitlenir. Kullanıcı PIN kutusunu açar; `.\devclient.exe type 1234` → 4 nokta; kullanıcı kutuyu temizleyip PIN'iyle girer. Bu sırada Durum'daki Giriş masaüstü "Winlogon" gösterir (kayıtlarda `Input desktop Winlogon`).

- [ ] **Step 10: UAC ve yapışkan yazılamaz (T1 sonucuna göre)**

`.\devclient.exe watch 60` çalışırken kullanıcı bir yönetici onay penceresi açar, sonra kapatır. Beklenen, T1'de seçilen satıra göre: (A) STATE değişmez ve `.\devclient.exe key 1` (Esc) UAC'yi kapatır; (B) STATE `ready=0` olur, UAC kapanınca `ready=1` döner ve tepsi simgesi kehribara dönüp yeşile geri gelir. Gözlenen STATE satırları ve zamanları rapora yazılır.

- [ ] **Step 11: Oturum açılmadan giriş ekranı (bitiş 5) ve açılışta ajan sayısı (kullanıcı onayıyla)**

```cmd
shutdown /r /t 5
```
Açılınca, kimse oturum açmadan:
```cmd
cd /d C:\KeyRelayDev & devclient.exe status & devclient.exe logs
devclient.exe type 1234
```
Expected: giriş ekranının PIN kutusunda 4 nokta (kullanıcı TeamViewer'dan bakar); loglarda `Agent ... started` satırları. Açılıştaki `Agent ... started/stopped` satır sayısı ve nedenleri (exit code, "session changed", "pipe link ended") T1'deki P1 gözlemiyle karşılaştırılıp rapora yazılır. Kullanıcı PIN'iyle oturum açar; `devclient.exe logs` yeniden alınır (oturum açılışında ajan aynı oturumda kalmalı ya da tek kez yeniden başlamalı).

- [ ] **Step 12: İptal (bitiş 6'nın ikinci yarısı)**

`.\devclient.exe watch 60` çalışırken kullanıcı Cihazlar'da "devclient" için İptal et → onaylar. Expected: `watch` bağlantısı hemen kapanır (Review Focus 1); ardından `.\devclient.exe hello` → `rejected: Reject, reason 2`.

- [ ] **Step 13: Görsel onay (bitiş 10, Windows tarafı)**

Kullanıcıyla: beş sayfanın açık ve koyu temada ekran görüntüleri; tepsi simgesinin üç durumu (yeşil: `devclient watch` bağlıyken — yeniden eşleştirme gerekir: Step 6; kehribar: bağlı tablet yokken; kırmızı: `sc.exe stop KeyRelay` sonrası, ardından `sc.exe start KeyRelay`); kısa ekran kaydı (Ekran Alıntısı Aracı video): sayfa geçişleri, Cihazlar → Eşleştir bağlantılı animasyonu, QR girişi ve tarama çizgisi, sonra Ayarlar > Erişilebilirlik > Görsel efektler > Animasyon efektleri kapalıyken aynı gezinmenin anında olması. Görüntüler `D:\Repos\KeyRelay\.superpowers\sdd\2026-10-08-keyrelay-p3-windows\` altına konur (gitignore'lu). Kullanıcının onayı ya da istediği düzeltmeler rapora yazılır; düzeltme istenirse controller yeni bir görev açar.

- [ ] **Step 14: Kaldırma ve yeniden kurma doğrulaması**

```cmd
"C:\Program Files\KeyRelay\unins000.exe" /VERYSILENT /SUPPRESSMSGBOXES /NORESTART
sc.exe query KeyRelay & reg query HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Run /v KeyRelay & netsh advfirewall firewall show rule name=KeyRelay & reg query HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Policies\System /v SoftwareSASGeneration & dir C:\ProgramData\KeyRelay & tasklist /fi "imagename eq KeyRelay.Service.exe" & tasklist /fi "imagename eq KeyRelay.Tray.exe"
```
Expected: servis 1060, Run değeri yok, kural yok, SAS `0x1` (dokunulmadı), `C:\ProgramData\KeyRelay` duruyor (`certificate.bin`, `devices.json`, `settings.json`), süreç yok.

- [ ] **Step 15: Sonuç belgesi**

`docs/spikes/2026-10-risk-results.md` sonuna "## P3 Windows doğrulaması (2026-10-..)" bölümü: Step 3–14'ün her biri için komut, gözlenen çıktı (alıntı), kullanıcı gözlemi, sonuç (GEÇTİ/KALDI/DENENMEDİ). Bitiş maddeleri tablosu: 2a, 2b, 5, 6, 7 (p50/p99 ve iki CPU değeri), 10 (Windows) — her biri için kanıt bölümü. Kurulum ve Tray boyutları. Tablet olmadan yapılan ölçümlerin sınırı açıkça yazılır: "istemci devclient; tablet ile P4'te tekrar".

- [ ] **Step 16: Cihaz durumu (kullanıcı kararı)**

Kullanıcıya sorulur: P4'e kadar kurulu kalsın mı? Evet → PC'de `C:\KeyRelayDev\KeyRelay-Setup-0.1.0.exe /VERYSILENT /SUPPRESSMSGBOXES /NORESTART & "C:\Program Files\KeyRelay\KeyRelay.Service.exe" install --port 47112`. Hayır → PC'de `rmdir /s /q C:\KeyRelayDev & rmdir /s /q C:\ProgramData\KeyRelay`. Karar rapora yazılır.

- [ ] **Step 17: Commit**

```powershell
git -C $WT add installer windows/tools/devclient.cs docs/spikes/2026-10-risk-results.md
git -C $WT commit -m "build(windows): kurulum paketi eklendi, pc tarafı cihazda doğrulandı" -m "- tek kurulum dosyası servisi, tepsi uygulamasını ve izinleri kurar; kaldırınca geri alır
- ctrl+alt+del, kilit ekranında şifre, açılışta giriş ekranı ve cihaz iptali gerçek pc'de denendi
- tuş başına gecikme ve boştaki işlemci kullanımı ölçüldü" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Sonraki planlar (bu planın kapsamı dışında)

- **P2 Android:** değişiklik yok (karar 3 her iki HELLO yolunu kabul eder; `n` yüzde kodlaması P2'de zaten var).
- **P4 notları (plan kapısı P3 tur 1):** `AuthThrottle` `remote|pair` anahtarı Funnel'da tüm istemcileri loopback'te birleştirir (eşleştirme penceresinde uzaktan bozma); MagicDNS adı `Environment.MachineName`'den türetiliyor, Tailscale cihaz adı farklıysa yanlış; Inno `[Run] install` çıkış kodunu denetlemiyor (`[Code]` + `Exec`); `AgentProcess.Exited` bekleyen thread'i `ThreadPool.RegisterWaitForSingleObject`'e taşımak; kontrol kanalını `PipeTransmissionMode.Message`'a sadeleştirmek; servis dururken ajan `TerminateProcess` ile ölünce basılı harf tuşu takılı kalabilir (stop yolunda önce RELEASE_ALL; `run` iptali link'i kapattığı için sıra değişikliği gerekir).
- **P4 Geçiş ve bitiş:** prototipin kaldırılması (homepc `kbdrecv` görevi, tablet `kbdfwd`), Funnel `--tcp` geçişi ve Ağ ekranında Funnel kurma düğmesi (karar 5), port 47111'e dönüş, tabletle bitiş çizgisi ölçümleri, release iş akışı (`installer/build.ps1`), README/architecture/setup belgeleri.
