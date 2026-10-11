# KeyRelay P2 — Android tarafı — Uygulama planı

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tabletteki KeyRelay'in tamamını kurmak: `:core` (protokol, QR, yönlendirme kuralları, TLS istemcisi, başlatma akışı), `shell` uid'inde çalışan yardımcı süreç (evdev, TLS, ön plan, AIDL), uygulama süreci (`RelayService`, eşleştirme, ayarlar, QS kutucuğu) ve onaylı taslağa sadık Compose arayüzü.

**Architecture:** Sıcak yol (evdev okuma → `RelayStateMachine` → sınırlı kuyruk → tek yazıcı → TLS) yardımcı süreçte; uygulama süreci yalnız başlatma, ayar ve arayüzdür ve yardımcıyla AIDL üzerinden konuşur. Saf mantığın tamamı (`Frame`, `PairingUri`, `ForegroundTracker`, `RelayStateMachine`, `RelayClient`, `StartupFlow`) `:core`'da JVM testleriyle; Android yapıştırıcısı `:app`'te incedir. İlk görev cihaz ölçümüdür: sonuçları T4 (ön plan kuralı), T7/T11 (yeni ağ akışı), T9 (tohumlama) ve T15 (Otomatik Engelleyici) kararlarını değiştirebilir.

**Tech Stack:** Kotlin 2.4.20, AGP 9.4.1, Gradle 9.8.0, JDK 21; Compose BOM 2026.09.00 (ui/foundation/animation 1.12.1), **Material 3 1.5.0-beta01 (ön sürüm, BOM'un 1.4.0'ını geçersiz kılar — gerekçe Kararlar'da)**, material-icons-core (BOM: 1.7.8), Navigation 3 1.2.0, lifecycle-runtime-compose 2.11.0, activity-compose 1.13.0, DataStore Preferences 1.2.1, play-services-code-scanner 16.1.0, kotlinx-coroutines 1.11.0 (+ `-test`), JUnit Jupiter 6.1.3, org.json 20260814 (yalnız `:core` testleri), libadb-android 3.1.1, Conscrypt-android 2.7.0, BouncyCastle 1.86.

**Spec:** `docs/superpowers/specs/2026-10-07-keyrelay-design.md` (tasarım kapısı yeniden açılışı tur 10'da bitti; log `.superpowers/gates/keyrelay-design-gate-log.md`). Protokol: `docs/protocol.md`, `protocol/test-vectors/frames.json`. P1 sonuçları: `docs/spikes/2026-10-risk-results.md`. Onaylı arayüz taslağı: `docs/design/keyrelay-mockups.html`.

## Global Constraints

- Android: minSdk 30, targetSdk 36, compileSdk 37 (P1), paket `dev.keyrelay`, Kotlin, JVM hedefi 21. Yardımcı giriş sınıfı `dev.keyrelay.helper.Main`, `--nice-name=keyrelay-helper`, log `/data/local/tmp/keyrelay-helper.log`.
- `:core` saf Kotlin/JVM: Android bağımlılığı yok; JUnit Jupiter ile JVM'de test edilir; test vektörlerini `protocol/test-vectors/frames.json`'dan okur (yalnız `tls` kanalı + tüm geçersizler).
- Protokol: `u16 uzunluk (big-endian, tip dahil) | u8 tip | yük`; uzunluk 1–512 ("Uzunluk alanının değeri en çok 512'dir (tel üzerinde en çok 514 bayt); aşan bağlantı kapatılır"). İstemci adresleri sırayla dener (2 sn timeout), son başarılıyı başa alır; host adına SNI gönderir; yalnız sabitlenmiş SPKI SHA-256'yı kabul eder.
- Ayar olan süreler (varsayılanlar `ConnectionConfig`'te tek yerde): `pingInterval` 5 sn, `pongTimeout` 10 sn, `sendTimeout` 1 sn. Protokol sabitleri: çerçeve sınırı 512, kuyruk 256 ("dolarsa bağlantı düşmüş sayılır"), adres başına 2 sn.
- Başlatma: `ADB_COMMAND_TIMEOUT` 10 sn (iç sabit, `ShellOutput.COMMAND_TIMEOUT_MS`), mDNS penceresi 2 katı; (c) geri çekilmesi 5/20/60 sn, en fazla 3; sayaç yardımcı 10 dk ayakta kalınca ve "Yeniden dene" ile sıfırlanır; `put` yalnız adım 1'de; her çıkışta önce gözlemci kaldırılır sonra `adb_wifi_enabled=0`; gözlemci 0 yazıldıktan **sonra** kaydedilir. Yardımcı `setsid sh -c "... &"` ile başlar; her komut `; echo __KR_END__ $?` ile biter.
- Sıcak yol (evdev okuma → durum makinesi → kuyruk → yazıcı) tuş başına nesne ayırmaz ve istisna atmaz; her ağ/adb G/Ç'sinin zaman aşımı vardır.
- Kod, isimler, kod içi yorumlar İngilizce; kod içi yorum ve log string'lerinde Türkçe özel karakter yok. Kullanıcı metinleri `strings.xml`'de: `values/` İngilizce (varsayılan), `values-tr/` Türkçe; iki dosyanın anahtarları aynıdır (T13 testi). Android XML'de kesme işareti `\'` ile kaçırılır (`PC\'ler`).
- Yorum kuralı: yalnız koddan görünmeyen gerekçe; yorum açıkladığı koddan uzun olmaz. Test adı `Metot_Senaryo_BeklenenSonuc`.
- Komutlar PowerShell'de, çalışma dizini **worktree kökü** (`feat/p2-android`). Ortam: `$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"`; `$git = "C:\Program Files\Git\cmd\git.exe"`; `$adb = "C:\Users\user\AppData\Local\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe"`. Komutları `;` ile ayır.
- Commit: `<type>(<scope>): <türkçe küçük harfli açıklama>` (subject ≤ 72), gövde maddeleri jargonsuz, son satır `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Header'lar aşağıdaki listedir; implementer commit'ten önce header'ı controller'a bildirir. Bu plan dosyası commit'lenmez.
- Cihaz adımları (tablet SM-X910 Android 14, MX Keys Mini; adb kablosuz, mDNS ile) controller tarafından kullanıcıyla koordine edilir; implementer komut listesini hazırlar, sonucu uydurmaz. **KeyRelay başlatma akışı her çalışmada kablosuz hata ayıklamayı kapatır → PC'nin adb oturumu düşer.** Log almak için kullanıcı Ayarlar'dan kablosuz hata ayıklamayı yeniden açar (çalışan yardımcı etkilenmez — P1 8.2).
- Cihaz testlerinden önce prototip durdurulur (`& $adb shell 'kill $(pidof kbdfwd)'`); iki süreç aynı düğümü grab edemez. Prototip P2'de kaldırılmaz (P4).
- Üretim kodunda deneysel (opt-in isteyen) API yok. Tek istisna testlerdedir: `kotlinx-coroutines-test`'in `@ExperimentalCoroutinesApi` sanal zaman fonksiyonları (`advanceTimeBy`, `runCurrent`, `currentTime`) T7 testinde `@OptIn` ile kullanılır. Derleyici başka bir yerde opt-in isterse implementer durur ve controller'a bildirir; plan güncellenmeden `@OptIn` eklenmez.

## Review Focus

1. Ağ geri çağrısı kayıt anında bulunulan Wi-Fi için hemen `onAvailable` verir → "onay bekleniyor"da kendini tetikleyen sonsuz deneme döngüsü. Beklenen: yalnız *yeni* ağ uyandırır. → T11 `isNewNetwork` testi + kod.
2. Yardımcı Binder'ını teslim edip uçuş bitmeden ölür (ör. ilk `configure`'da çöker) → uçuş sırasında gelen ölüm yok sayıldığı için akış "çalışıyor"da kalır. Beklenen: uçuş sonunda yardımcı canlı değilse (c) geri çekilmesi başlar. → T7 `attempt_helperDeadAtEnd_schedulesRestart`.
3. Bozuk/uyumsuz süre ayarı (`pongTimeout ≤ pingInterval`) yardımcıda `ConnectionConfig`'i patlatıp çökme döngüsü üretir. Beklenen: ayar okunurken varsayılana düşer. → T10 `from_pongNotAbovePing_fallsBackToDefaults`.
4. Keystore anahtarı yok/çözülemeyen token (geri yükleme, cihaz değişimi) uygulamayı çökertir. Beklenen: o PC bağlantısız yapılandırılır, arayüz "yeniden eşleştir" der. → T11 `build_tokenCannotBeOpened_configuresNoConnection`.
5. Başka bir uygulama/site `keyrelay://pair?...` bağlantısı açar → kullanıcı onayı olmadan saldırganın PC'siyle eşleşme. Beklenen: yalnız onay penceresi açılır, "İptal" hiçbir şey saklamaz. → T12 Step 6 (cihaz adımı 1) ve T14 `PairConfirmDialog`.

## Kararlar (bu planın)

- **Arayüz dili:** `values/strings.xml` İngilizce (varsayılan), `values-tr/strings.xml` Türkçe. Gerekçe: spec README'yi İngilizce + `README.tr.md` olarak seçti (GitHub kitlesi); Android cihaz diline göre seçer, kullanıcının Türkçe tableti onaylı taslaktaki metinleri görür.
- **Material 3 1.5.0-beta01:** BOM 2026.09.00'ın getirdiği 1.4.0'da `MaterialExpressiveTheme`, `MotionScheme`, `expressiveLightColorScheme` `internal` (kaynakta doğrulandı); 1.5.0-beta01'de `public` ve opt-in istemiyor. Beta = API dondurulmuş ön sürüm. `LoadingIndicator` gibi `@ExperimentalMaterial3ExpressiveApi` bileşenler kullanılmaz.
- **Gezinme:** Navigation 3 1.2.0 (kararlı): `NavDisplay(backStack, sharedTransitionScope, entryProvider)` öngörülü geri ve paylaşılan öğe geçişini taşır; geri yığını düz `mutableStateListOf` (serileştirme eklentisi gerekmez). `SharedTransitionLayout` animation 1.12.1'de kararlı.
- **TLS istemcisi `:core`'da:** `javax.net.ssl` JVM standardıdır; sabitleme, SNI, adres sırası, kuyruk, canlılık ve `sendTimeout` gerçek TLS 1.3 loopback sunucusuyla JVM'de test edilir (`FakeRelayServer`, test sertifikasının SPKI'si .NET ile üretildi: `nhDFng6PxrMUestQPWHqag0G99AYI1PSvnW94Q3IWDA`). Aynı sunucu `gradlew :core:fakeServer` ile cihaz denemelerinde PC yerine geçer (P3 yokken uçtan uca).
- **Yeniden bağlanma geri çekilmesi** 0,5 sn'den ikiye katlanır, üst sınırı `pingInterval`'dır (yeni ayar açmadan Eşik kuralını karşılar: kullanıcının ayarladığı canlılık süresi kadar).
- **Yazı tipleri APK'ya gömülür** (Manrope ve JetBrains Mono değişken TTF, SIL OFL 1.1; lisans metinleri `assets/licenses/`): indirilebilir yazı tipi GMS sağlayıcısına ve ağa bağlı.
- **Eşleştirme sonrası HELLO yeni bağlantıda:** `pair` PAIRED'dan sonra bağlantıyı kapatır, HELLO yardımcı sürecindeki yeni bağlantıda gider; P3 protocol.md bu yolu açıkça geçerli sayar.
- **Anahtar uyuşmazlığı (PC yeniden kuruldu):** tüm adresler başka anahtarla yanıtlarsa istemci yeniden denemez, yerel `REJECT_PIN_MISMATCH` ile REJECT gibi bildirir → Durum "yeniden eşleştir" (spec "Hata durumları"). Tek adresteki uyuşmazlık normal hata sayılır.
- **Plan kapısı tur 1 notları (P4 / T16'da bakılır, P2'de kod değişmez):** VPN açıkken `registerWakeups` `activeNetwork` VPN olduğu için Wi-Fi geri çağrısını yeni ağ sayabilir (T16'da VPN'li deneme); PC kapalıyken geri çekilme tavanı `pingInterval` olduğundan sürekli deneme — T16'ya "PC kapalı 10 dk pil" ölçümü; JVM'de `sendTimeout` yolu `send queue full` yoluyla örtülüyor, gerçek doğrulama cihazda; `PairingCodeReceiver` `goAsync()` kullanmıyor; cached-app freezer altında `onRtt` oneway yayınları binder tamponunu doldurabilir; `pm clear` sonrası `pm revoke` yedeği; T1 Step 5 `head -n 3` yerine `grep IProcessObserver`; spec "Klavyeler ekranı" cümlesi Ayarlar bölümüyle eşlenir (T16 Step 7); release R8 keep kuralları P4.
- **Geçiş kısayolu** Ctrl+Alt+<tuş>; tuş Ayarlar'da Space / ` / Insert arasından seçilir (spec AIDL'de `configure`'a giren ayar).
- **Klavyeler** ayrı ekran değil, Ayarlar'da salt okunur bölüm (yardımcının gruplayıp grab ettiği klavyeler). Seçim YAGNI: algılanan her klavye yönlendirilir.
- **Yeni eşlenen PC varsayılan olur** (son eşleştirme kullanıcının niyeti).
- `HiddenApi.uidsForPackages` (gizli `getPackageUid`) ve yardımcının teşhis modları T9'da kalkar; hedef uid'leri uygulama public `PackageManager.getPackageUid` ile çözüp `RelayConfig.targetUids` ile geçirir (Android 11/12 uyumu).

## Commit header listesi

| Görev | Header |
|---|---|
| T1 | `test(android): ön plan ve yeni ağ onayı davranışı cihazda ölçüldü` |
| T2 | `feat(android): tablet ile pc arasındaki mesajlar okunup yazılabilir oldu` |
| T3 | `feat(android): pc eşleştirme bağlantısı okunur, eşlenen pc'ler saklanır` |
| T4 | `feat(android): klavye yalnız hedef uygulama öndeyken pc'ye yönlenir` |
| T5 | `feat(android): pc ile sabitlenmiş sertifikalı şifreli bağlantı kurulur` |
| T6 | `fix(android): kurulum komutları zaman aşımı ve çıkış koduyla denetlenir` |
| T7 | `feat(android): başlatma yeni ağ onayını ve tekrarlayan çökmeleri yönetir` |
| T8 | `feat(android): klavye kapatılıp açılınca yeniden yakalanır` |
| T9 | `feat(android): uygulama ile yardımcı süreç birbirine güvenle bağlanır` |
| T10 | `feat(android): ayarlar ve pc anahtarı cihazda şifreli saklanır` |
| T11 | `feat(android): yönlendirme servisi açılışta kendiliğinden başlar` |
| T12 | `feat(android): pc bağlantıyla eşlenir, hızlı ayarlar kutucuğu eklendi` |
| T13 | `feat(android): uygulama ikonu, renkleri ve yazı tipleri eklendi` |
| T14 | `feat(android): durum ve pc ekranları eklendi` |
| T15 | `feat(android): kurulum sihirbazı ve ayarlar ekranı eklendi` |
| T16 | `docs(android): android tarafı cihazda uçtan uca doğrulandı` |

## Dosya haritası

```
android/
├─ gradle/libs.versions.toml                     T2 T7 T10 T13 T14 (sürümler)
├─ core/build.gradle.kts                         T2 (vektör yolu, org.json) T5 (fakeServer) T7 (coroutines)
├─ core/src/main/kotlin/dev/keyrelay/core/
│  ├─ Frame.kt                 T2  çerçeve tipleri + FrameCodec
│  ├─ PairingUri.kt            T3  Endpoint, PairingInfo, PairingUri
│  ├─ PairedPc.kt              T3  PairedPc, liste düzenlemeleri, PairedPcCodec
│  ├─ ForegroundTracker.kt     T4  yeni ön plan kuralı
│  ├─ RelayStateMachine.kt     T4  want/held/kısayol/SAS/LOCK
│  ├─ IntRingQueue.kt          T5  ayırmasız sınırlı kuyruk
│  ├─ TlsDialer.kt             T5  adres sırası, SNI, sabitleme
│  ├─ RelayClient.kt           T5  ConnectionConfig + oturum, yazıcı, zamanlayıcı, pair()
│  ├─ ShellOutput.kt           T6  işaret + çıkış kodu
│  ├─ HelperCommand.kt         (P1; T6 testleri)
│  ├─ StartupFlow.kt           T7  tetikleyiciler, tek uçuş, geri çekilme, sınıflar
│  ├─ InputNode.kt, KeyboardGrouping.kt   T8 touchpad dışlama
├─ core/src/test/...           her görevin testleri; FakeRelayServer.kt + resources/test-server.pem (T5)
├─ app/build.gradle.kts        T9 (aidl) T10 (testler, datastore) T13 T14
├─ app/src/main/AndroidManifest.xml   T9 T10 T11 T12 T14 T15
├─ app/src/main/aidl/dev/keyrelay/helper/  T9  IKeyRelayHelper, IStatusListener, RelayConfig, HelperStatus
├─ app/src/main/cpp/evdev.c    T8
├─ app/src/main/kotlin/dev/keyrelay/
│  ├─ helper/   Evdev, DeviceScanner (T8), InputReader (T8), HiddenApi (T9), ProcessObserverBinder, Helper (T9), Main (T1 T8 T9)
│  ├─ launcher/ AdbManager (T6), AdbSetup (T11), BootReceiver (T11), NetworkApprovalProbe (T1, T11'de silinir)
│  ├─ service/  AppState, RelayStatus, HelperConnection, HelperAttachProvider (T9); AppStartupPorts, RelayService,
│  │            Notifications, StatusKind, RelayConfigFactory (T11); PcPairing, RelayTileService (T12)
│  ├─ settings/ AppSettings, SettingsStore, TokenCipher (T10)
│  ├─ ui/       theme/Theme.kt (T13); KeyRelayApp, StatusScreen, PcsScreen, PcDetailScreen, PairConfirmDialog,
│  │            InstalledApps (T14); WizardScreen, AdbPairing, PairingNotification, SettingsScreen (T15)
│  └─ MainActivity.kt           T1 T11 T12 (geçici hata ayıklama ekranı) → T14 (gerçek)
├─ app/src/main/res/            T11 T13 T14 T15
└─ app/src/test/kotlin/...      T10–T15 JVM testleri
docs/spikes/2026-10-p2-device-measurements.md   T1
docs/spikes/2026-10-p2-device-results.md        T12 T16
docs/design/keyrelay-mockups.html               T13 (PC ayrıntısı + Ayarlar taslağı eklenir)
```

---

### Task 1: Cihaz ölçümü — ön plan olayları, işlem kodları, yeni ağ onayı, Otomatik Engelleyici

Kod önce değil ölçüm önce (tasarım kapısı kararı). İki aşama: (A) ölçüm aracı kodu, (B) kullanıcıyla cihaz adımları. Sonuçlar `docs/spikes/2026-10-p2-device-measurements.md`'ye yazılır ve **sonraki görevlerin kararlarını değiştirebilir** (Step 10'daki karar tablosu). Bu görevin "testi" cihaz ölçümüdür; birim test yazılmaz.

**Files:**
- Modify: `android/app/src/main/kotlin/dev/keyrelay/helper/Main.kt` (`watchForeground` ham olay günlüğü)
- Create: `android/app/src/main/kotlin/dev/keyrelay/launcher/NetworkApprovalProbe.kt` (T11'de silinir)
- Modify: `android/app/src/main/kotlin/dev/keyrelay/MainActivity.kt` (ölçüm düğmesi)
- Create: `docs/spikes/2026-10-p2-device-measurements.md`

**Interfaces:**
- Consumes: P1 `HiddenApi.topAppPids/uidsForPackages/registerProcessObserver`, `ProcessObserverBinder`, `ForegroundTracker`, `AdbManager.get(context).autoConnect(context, ms)`.
- Produces: ölçüm belgesi; T4/T7/T9/T11/T15'in girdisi olan karar satırları D1–D5 ve Otomatik Engelleyici ayar anahtarı/etkinliği (§4).

- [ ] **Step 1: `Main.watchForeground` her olayı zaman damgasıyla yazsın**

`Main.kt` içinde `watchForeground` fonksiyonunu tamamen şununla değiştir ve dosyanın sonuna `now()` ekle:
```kotlin
    private fun watchForeground(packages: Set<String>) {
        val targetUids = HiddenApi.uidsForPackages(packages)
        val top = HiddenApi.topAppPids()
        println("${now()} seed top-app=$top target uids=$targetUids")
        val tracker = ForegroundTracker { it in targetUids }
        tracker.seed(top)
        println("${now()} target uids=$targetUids foreground=${tracker.targetForeground} (seed)")
        val lock = Any()
        HiddenApi.registerProcessObserver(ProcessObserverBinder(object : ProcessObserverBinder.Listener {
            override fun onForegroundActivitiesChanged(pid: Int, uid: Int, foreground: Boolean) = synchronized(lock) {
                println("${now()} event fg pid=$pid uid=$uid foreground=$foreground")
                if (tracker.onForegroundActivitiesChanged(pid, uid, foreground)) {
                    println("${now()} foreground=${tracker.targetForeground}")
                }
            }

            override fun onProcessDied(pid: Int, uid: Int) = synchronized(lock) {
                println("${now()} event died pid=$pid uid=$uid")
                if (tracker.onProcessDied(pid)) println("${now()} foreground=${tracker.targetForeground} (died)")
            }
        }))
        Thread.sleep(Long.MAX_VALUE)
    }

    private fun now() = System.currentTimeMillis()
```

- [ ] **Step 2: Yeni ağ onayı ölçüm aracı**

`NetworkApprovalProbe.kt`:
```kotlin
package dev.keyrelay.launcher

import android.content.Context
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** P2 Task 1 device measurement of the new-network approval flow; deleted in Task 11. */
class NetworkApprovalProbe(private val context: Context) {
    private val logFile = File(context.filesDir, "approval-probe.log")
    private val main = Handler(Looper.getMainLooper())

    private fun write(msg: String) {
        val line = "${System.currentTimeMillis()} $msg"
        Log.i(TAG, line)
        logFile.appendText(line + "\n")
        last.value = line
    }

    suspend fun run(holdSeconds: Int) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) =
                write("onChange adb_wifi_enabled read=${Settings.Global.getInt(resolver, ADB_WIFI, -1)}")
        }
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) = write("wifi available $n")
            override fun onLost(n: Network) = write("wifi lost $n")
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(ADB_WIFI), false, observer)
        connectivity.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), network,
        )
        try {
            write("start hold=$holdSeconds")
            Settings.Global.putInt(resolver, ADB_WIFI, 1)
            write("put 1")
            val result = withContext(Dispatchers.IO) {
                runCatching { AdbManager.get(context).autoConnect(context, MDNS_WINDOW_MS) }
                    .fold({ "autoConnect=$it" }, { "autoConnect threw $it" })
            }
            write(result)
            delay(holdSeconds * 1000L)
        } finally {
            withContext(NonCancellable) {
                Settings.Global.putInt(resolver, ADB_WIFI, 0)
                write("put 0")
                delay(AFTER_ZERO_MS)
                resolver.unregisterContentObserver(observer)
                connectivity.unregisterNetworkCallback(network)
                write("end")
            }
        }
    }

    companion object {
        private const val TAG = "KeyRelayProbe"
        private const val ADB_WIFI = "adb_wifi_enabled"
        private const val MDNS_WINDOW_MS = 20_000L
        private const val AFTER_ZERO_MS = 120_000L
        val last = MutableStateFlow("")
        // Outlives the activity: the system approval dialog may pause or recreate it mid-run.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }
}
```

- [ ] **Step 3: Hata ayıklama ekranına ölçüm düğmesi**

`MainActivity.kt`'de `SetupScreen` içine, `Text(status)` satırından hemen önce ekle (importlar: `androidx.compose.runtime.collectAsState`, `dev.keyrelay.launcher.NetworkApprovalProbe`):
```kotlin
            var hold by remember { mutableStateOf("30") }
            val probeLine by NetworkApprovalProbe.last.collectAsState()
            OutlinedTextField(hold, { hold = it }, label = { Text("Bekleme (sn)") })
            Button({
                NetworkApprovalProbe.scope.launch { NetworkApprovalProbe(applicationContext).run(hold.trim().toIntOrNull() ?: 30) }
            }) { Text("Yeni ağ ölçümü") }
            Text(probeLine)
```

- [ ] **Step 4: Derle ve kur**

Run:
```
.\android\gradlew.bat -p android :core:test :app:assembleDebug
& $adb install -r android\app\build\outputs\apk\debug\app-debug.apk
```
Expected: `BUILD SUCCESSFUL`; `Success`.

- [ ] **Step 5: Cihaz B1 — işlem kodları ve tohumlama (controller kullanıcıyla)**

1. Uid'ler: `& $adb shell "pm list packages -U | grep -E 'teamviewer|app.notes|launcher|intentresolver|systemui'"` → tabloya yaz.
2. TeamViewer'ı aç, sonra Notes'u aç (TeamViewer arkada, süreci canlı): `& $adb shell pidof com.teamviewer.teamviewer.market.mobile` (boş değil olmalı).
3. İzleyiciyi başlat (P1 `setsid sh -c` biçimi):
```
& $adb shell 'APK=$(pm path dev.keyrelay | cut -d: -f2); LIB=$(dirname $APK)/lib/arm64/libkeyrelay.so; setsid sh -c "CLASSPATH=$APK nohup app_process /system/bin --nice-name=keyrelay-helper dev.keyrelay.helper.Main --lib=$LIB --watch-foreground=com.teamviewer.teamviewer.market.mobile > /data/local/tmp/fg.log 2>&1 < /dev/null &"'
& $adb shell 'sleep 2; head -n 3 /data/local/tmp/fg.log; cat /dev/cpuset/top-app/cgroup.procs'
```
Kayda geç: (a) `IProcessObserver codes from platform stub: foreground=1 died=3` satırının **aynen** görünüp görünmediği (P1 kod kapısı takibi: Android 14'te kodlar çalışma anında çözülmeli); (b) `seed top-app=` haritasında arka plandaki TeamViewer pid'inin bulunup bulunmadığı (bayat tohum riski, spec "Ön plan kuralı").

- [ ] **Step 6: Cihaz B2 — ön plan senaryoları**

İzleyici çalışırken (Step 5). Her senaryoda `date +%s%3N` damgası ile log satırlarını karşılaştır. Komutlar `adb shell` içinde tek satır:

- S1 TeamViewer → Notes (×5):
```
& $adb shell 'for i in 1 2 3 4 5; do am start -n com.teamviewer.teamviewer.market.mobile/com.teamviewer.remotecontrollib.activity.MainActivity > /dev/null; sleep 3; echo "notes $(date +%s%3N)"; am start -n com.samsung.android.app.notes/.memolist.MemoListActivity > /dev/null; sleep 3; done'
```
Ölç: Notes uid'inin `foreground=true` olayı − damga; TeamViewer `foreground=false` − damga.
- S2 TeamViewer → ana ekran (×3): TeamViewer öndeyken `& $adb shell 'echo "home $(date +%s%3N)"; input keyevent KEYCODE_HOME'`.
- S3 Son uygulamalar (×3): `& $adb shell 'echo "recents $(date +%s%3N)"; input keyevent KEYCODE_APP_SWITCH'`, 3 sn sonra TeamViewer'a dokunarak dön (kullanıcı).
- S4 Üst pencere (×3): TeamViewer öndeyken `& $adb shell 'echo "share $(date +%s%3N)"; am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT KeyRelay'`, 3 sn sonra `& $adb shell 'echo "back $(date +%s%3N)"; input keyevent KEYCODE_BACK'`. Ayrıca bildirim perdesi: `& $adb shell 'echo "shade $(date +%s%3N)"; cmd statusbar expand-notifications; sleep 2; echo "close $(date +%s%3N)"; cmd statusbar collapse'`.
- S5 PiP (kullanıcı): PiP destekleyen bir uygulamada video PiP'e alınır, sonra TeamViewer açılır, sonra PiP penceresine dokunulur. Kullanıcı her adımı söyler; controller o anda `& $adb shell 'echo "mark $(date +%s%3N)"'` yazar.
- S6 Bölünmüş ekran (kullanıcı): TeamViewer + Notes yan yana; bölmeler arasında 3 kez dokunulur (aynı işaretleme).

Her senaryo için yeni kuralın (`targetForeground = anyTarget && (lastTrueWasTarget || !anyOther)`) ham olaylardan elle hesaplanmış zaman çizelgesini çıkar: hedef "ön planda değil"e en erken hangi olayla ve damgadan kaç ms sonra geçerdi.

Logu al: `& $adb shell cat /data/local/tmp/fg.log > "$env:TEMP\p2-fg.log"`; izleyiciyi durdur: `& $adb shell 'kill $(pidof keyrelay-helper)'`.

- [ ] **Step 7: Cihaz B3 — telefon hotspot'unda yeni ağ onayı**

Kullanıcı tableti daha önce kablosuz hata ayıklamaya **hiç izin verilmemiş** telefon hotspot'una bağlar (PC adb oturumu düşer; adımlar uygulamadaki "Yeni ağ ölçümü" düğmesiyle yürür, log `files/approval-probe.log`'a ve ekrana yazılır). Kullanıcı her adımda ekranda ne gördüğünü ve saati söyler:

- H1 "Bekleme" 30 → düğme. Sistem penceresi açılırsa **dokunma**. Gözle: pencere ne zaman açıldı; uygulama `put 0` yazdığında pencere kapandı mı, açık mı kaldı?
- H2 "Bekleme" 30 → düğme; pencere açılınca **yalnız "İzin ver"** ("her zaman" işaretsiz) mümkün olduğunca çabuk. Gözle: `onChange` satırları, `autoConnect=` sonucu.
- H3 "Bekleme" 5 → düğme; `put 0` satırı ekranda görünene kadar bekle, **sonra** pencere hâlâ açıksa "İzin ver". Gözle: bizim 0'ımızdan sonra gelen onay kablosuz hata ayıklamayı açık bıraktı mı (`onChange` + Ayarlar'daki anahtar)?
- H4 "Bekleme" 30 → düğme; pencerede "Bu ağda her zaman izin ver" + "İzin ver". Sonra H4'ü bir kez daha çalıştır: pencere açılmadan `autoConnect=true` olmalı.

Ev Wi-Fi'ına dönüldükten sonra kullanıcı kablosuz hata ayıklamayı açar; logları al:
```
& $adb shell run-as dev.keyrelay cat files/approval-probe.log > "$env:TEMP\p2-approval.log"
& $adb logcat -d -b all -v epoch | Select-String -Pattern 'AdbDebuggingManager|adbwifi|WifiDebugging|KeyRelayProbe' > "$env:TEMP\p2-approval-logcat.txt"
```
Logcat tamponu dönmüş olabilir; boşsa belgeye "logcat kaydı tamponda kalmadı" yaz.

- [ ] **Step 8: Cihaz B4 — Samsung Otomatik Engelleyici**

1. Kapalıyken anlık görüntü: `foreach ($ns in 'global','secure','system') { & $adb shell settings list $ns > "$env:TEMP\ab-off-$ns.txt" }`
2. Etkinlik adı: `& $adb shell "dumpsys package com.android.settings | grep -i -E 'autoblock|auto_block|AutoBlocker'"`
3. Kullanıcı Ayarlar > Güvenlik ve gizlilik > Otomatik Engelleyici'yi açar. adb hâlâ bağlıysa aynı üç listeyi `ab-on-*.txt` olarak al ve farkı çıkar: `Compare-Object (Get-Content "$env:TEMP\ab-off-secure.txt") (Get-Content "$env:TEMP\ab-on-secure.txt")` (üç ad alanı için). adb kesildiyse bunu kaydet (Otomatik Engelleyici kablosuz adb'yi engelliyor demektir).
4. Kullanıcı Otomatik Engelleyici'yi eski durumuna getirir.

- [ ] **Step 9: Ölçüm belgesini yaz**

`docs/spikes/2026-10-p2-device-measurements.md` şu başlıklarla, yalnız gözlenen değerlerle (gözlenmeyen her şey "ölçülmedi"):
```markdown
# P2 cihaz ölçümleri (Görev 1)

Cihaz: SM-X910, Android 14 / SDK 34. Tarih: <ölçüm günü>. Ham loglar repo dışında: `%TEMP%\p2-fg.log`, `p2-approval.log`, `p2-approval-logcat.txt`, `ab-*.txt`.

## 1. IProcessObserver işlem kodları
<Step 5 (a) satırı, aynen>

## 2. Ön plan olayları
Uid tablosu. Senaryo başına tablo: | # | damga | olay sırası (uid, true/false, ms) | eski kural | yeni kural |
S1 … S6, ayrıca Step 5 (b) tohumlama gözlemi.

## 3. Yeni ağ onayı (telefon hotspot'u)
H1–H4: sistemin 1→0 geri çekme süresi, pencerenin görünmesi, onayda 1 yazılıp yazılmadığı, `onChange` teslimi ve zamanları,
bizim 0'ımızın açık pencereyi kapatıp kapatmadığı, 0'dan sonra gelen onayın sonucu, "her zaman izin ver" sonrası sessiz açılış.

## 4. Otomatik Engelleyici
Değişen ayar anahtarı (ad alanı + ad + açık/kapalı değer) ya da "değişen anahtar yok"; adb'nin açıkken kesilip kesilmediği; ayar etkinliğinin adı.

## 5. Kararlar
D1–D5 tablosu (Step 10).
```

- [ ] **Step 10: Karar tablosu — sonraki görevlere etkisi**

Belgenin §5'ine her satırı "gözlem → etki" olarak yaz. Kurallar:

| Karar | Gözlem | Etki |
|---|---|---|
| D1 ön plan kuralı (T4) | S1–S4'te başka uygulamanın `true` olayı damgadan ≤ 500 ms sonra geliyor | T4 yazıldığı gibi. Bir senaryoda gelmiyorsa: o senaryonun olay sırası T4'e test olarak eklenir; bitiş 3 o senaryoda karşılanmıyorsa **dur**, controller kullanıcıya A/B sunar |
| D2 tohumlama (T9) | Arka plandaki TeamViewer pid'i top-app'te | Görüldüyse **dur**: spec "bayat tohum" kabulü gerçek sıklıkla yeniden değerlendirilir (controller kullanıcıya sunar). Görülmediyse T9 yazıldığı gibi |
| D3 yeni ağ akışı (T7, T11) | (a) sistem 1→0 geri çekiyor mu, (b) bizim 0 açık pencereyi kapatıyor mu, (c) onayda `onChange` geliyor mu | (b) evet ise spec adım 3 sırası ("Başarısız → 0 → bildirim") kullanıcının onay şansını yok eder: **dur**, tasarım kapısına dönülür. (c) hayır ise uyandırıcı yalnız Wi-Fi yeniden bağlanması + "Yeniden dene" olur; T11'de gözlemci kalır (zararsız) ve bildirim metni "onayladıktan sonra Yeniden dene'ye basın" olur |
| D4 kod kapısı kanıtı | §1 satırı | Kanıt; kod değişmez |
| D5 Otomatik Engelleyici (T15) | §4 | Anahtar bulunduysa T15 Step 4'ün (A) dalı, bulunmadıysa (B) dalı uygulanır |

"Dur" çıkan bir karar varsa bu görevin commit'i yapılır ama T2'ye geçilmez; controller kullanıcıya iki seçeneği maliyetleriyle sunar ve cevabı bekler.

- [ ] **Step 11: Commit**

```
& $git add android/app/src/main/kotlin/dev/keyrelay/helper/Main.kt android/app/src/main/kotlin/dev/keyrelay/launcher/NetworkApprovalProbe.kt android/app/src/main/kotlin/dev/keyrelay/MainActivity.kt docs/spikes/2026-10-p2-device-measurements.md
& $git commit -m "test(android): ön plan ve yeni ağ onayı davranışı cihazda ölçüldü" -m "- uygulama değişimlerinde klavyenin ne zaman döneceği ölçüldü
- yeni bir Wi-Fi ağında sistemin onay penceresi gözlendi
- Samsung Otomatik Engelleyici'nin kurulumu nasıl etkilediği kaydedildi" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Protokol çerçeve kodlayıcı/çözücü (`:core`)

**Files:**
- Modify: `android/gradle/libs.versions.toml`, `android/core/build.gradle.kts`
- Create: `android/core/src/main/kotlin/dev/keyrelay/core/Frame.kt`
- Test: `android/core/src/test/kotlin/dev/keyrelay/core/FrameCodecTest.kt`

**Interfaces:**
- Produces:
  - `sealed interface Frame` — `Pair(version: Int, code: ByteArray, deviceName: String)`, `Paired(deviceId: ByteArray, token: ByteArray)`, `Hello(version: Int, deviceId: ByteArray, token: ByteArray)`, `Welcome(pcName: String)`, `Reject(reason: Int)`, `Key(code: Int, value: Int)`, `State(ready: Boolean)`, `Sas`, `Lock`, `Ping(seq: Int)`, `Pong(seq: Int)`.
  - `enum class FrameError { EMPTY, TOO_LARGE, UNKNOWN_TYPE, TRUNCATED, BAD_PAYLOAD }`, `class FrameException(val error: FrameError) : IOException`.
  - `object FrameCodec` — sabitler `PROTOCOL_VERSION=1`, `MAX_LENGTH=512`, `KEY_FRAME_SIZE=6`, `PING_FRAME_SIZE=7`, `EMPTY_FRAME_SIZE=3`, `ID_SIZE=16`, `TOKEN_SIZE=32`, `CODE_SIZE=16`, tip kodları `PAIR…PONG`; `fun encode(frame: Frame): ByteArray`; ayırmasız `fun encodeKey(code: Int, value: Int, out: ByteArray)`, `fun encodePing(seq: Int, out: ByteArray): Int`, `fun encodeEmpty(type: Int, out: ByteArray): Int`; `fun read(input: InputStream, buf: ByteArray): Frame?` (akış çerçeveler arasında bitince `null`).

- [ ] **Step 1: Bağımlılık ve vektör yolu**

`libs.versions.toml` `[versions]`'a `json = "20260814"`, `[libraries]`'e `json = { module = "org.json:json", version.ref = "json" }` ekle.

`android/core/build.gradle.kts`'yi şununla değiştir:
```kotlin
plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(21) }
dependencies {
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.json)
    testRuntimeOnly(libs.junit.platform.launcher)
}
val vectors = rootProject.file("../protocol/test-vectors/frames.json")
tasks.test {
    useJUnitPlatform()
    inputs.file(vectors)
    systemProperty("keyrelay.vectors", vectors.absolutePath)
}
```

- [ ] **Step 2: Failing test**

`FrameCodecTest.kt`:
```kotlin
package dev.keyrelay.core

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.util.HexFormat

class FrameCodecTest {
    private val hex = HexFormat.of()
    private val vectors = JSONObject(File(System.getProperty("keyrelay.vectors")).readText())

    private fun list(name: String) = vectors.getJSONArray(name).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private fun tlsValid() = list("valid").filter { "tls" in it.getJSONArray("channels").toList() }
    private fun read(h: String) = FrameCodec.read(ByteArrayInputStream(hex.parseHex(h)), ByteArray(FrameCodec.MAX_LENGTH))
    private fun errorOf(h: String) = assertThrows(FrameException::class.java) { read(h) }.error

    private fun frameOf(v: JSONObject): Frame {
        val f = v.getJSONObject("fields")
        return when (v.getString("type")) {
            "KEY" -> Frame.Key(f.getInt("code"), f.getInt("value"))
            "HELLO" -> Frame.Hello(f.getInt("version"), hex.parseHex(f.getString("deviceId")), hex.parseHex(f.getString("token")))
            "PAIR" -> Frame.Pair(f.getInt("version"), hex.parseHex(f.getString("code")), f.getString("deviceName"))
            "PAIRED" -> Frame.Paired(hex.parseHex(f.getString("deviceId")), hex.parseHex(f.getString("token")))
            "WELCOME" -> Frame.Welcome(f.getString("pcName"))
            "REJECT" -> Frame.Reject(f.getInt("reason"))
            "STATE" -> Frame.State(f.getBoolean("ready"))
            "SAS" -> Frame.Sas
            "LOCK" -> Frame.Lock
            "PING" -> Frame.Ping(f.getInt("seq"))
            "PONG" -> Frame.Pong(f.getInt("seq"))
            else -> error("no tls frame for ${v.getString("type")}")
        }
    }

    @TestFactory
    fun encode_validTlsVectors_matchHex(): List<DynamicTest> = tlsValid().map { v ->
        dynamicTest(v.getString("name")) { assertEquals(v.getString("hex"), hex.formatHex(FrameCodec.encode(frameOf(v)))) }
    }

    @TestFactory
    fun read_validTlsVectors_decodeToSameFrame(): List<DynamicTest> = tlsValid().map { v ->
        dynamicTest(v.getString("name")) {
            val frame = read(v.getString("hex"))!!
            assertEquals(frameOf(v).javaClass, frame.javaClass)
            assertEquals(v.getString("hex"), hex.formatHex(FrameCodec.encode(frame)))
        }
    }

    @TestFactory
    fun read_invalidVectors_failWithListedError(): List<DynamicTest> = list("invalid").map { v ->
        dynamicTest(v.getString("name")) { assertEquals(v.getString("error"), errorOf(v.getString("hex")).name.lowercase()) }
    }

    @Test
    fun read_pipeOnlyReleaseAll_isUnknownOnTlsChannel() = assertEquals(FrameError.UNKNOWN_TYPE, errorOf("000130"))

    @Test
    fun read_emptyStream_returnsNull() = assertNull(read(""))

    @Test
    fun read_streamEndsAfterFirstLengthByte_truncated() = assertEquals(FrameError.TRUNCATED, errorOf("00"))

    @Test
    fun read_welcomeWithInvalidUtf8_badPayload() = assertEquals(FrameError.BAD_PAYLOAD, errorOf("00030480ff"))

    @Test
    fun read_stateWithReservedBits_usesBitZeroOnly() {
        assertEquals(true, (read("00021103") as Frame.State).ready)
        assertEquals(false, (read("00021102") as Frame.State).ready)
    }

    @Test
    fun read_twoFramesBackToBack_readsBothThenNull() {
        val input = ByteArrayInputStream(hex.parseHex("000410001e01" + "00021101"))
        val buf = ByteArray(FrameCodec.MAX_LENGTH)
        assertEquals(30, (FrameCodec.read(input, buf) as Frame.Key).code)
        assertEquals(true, (FrameCodec.read(input, buf) as Frame.State).ready)
        assertNull(FrameCodec.read(input, buf))
    }

    @Test
    fun encodeKey_writesSameBytesAsEncode() {
        val out = ByteArray(FrameCodec.KEY_FRAME_SIZE)
        FrameCodec.encodeKey(125, 0, out)
        assertEquals(hex.formatHex(FrameCodec.encode(Frame.Key(125, 0))), hex.formatHex(out))
    }

    @Test
    fun encodePingAndEmpty_writeSameBytesAsEncode() {
        val out = ByteArray(FrameCodec.PING_FRAME_SIZE)
        assertEquals(7, FrameCodec.encodePing(16909060, out))
        assertEquals("00052001020304", hex.formatHex(out))
        assertEquals(3, FrameCodec.encodeEmpty(FrameCodec.SAS, out))
        assertEquals("000112", hex.formatHex(out, 0, 3))
    }

    @Test
    fun encode_welcomeOver511Bytes_isRejected() {
        assertThrows(IllegalArgumentException::class.java) { FrameCodec.encode(Frame.Welcome("A".repeat(512))) }
    }

    @Test
    fun encode_helloWithShortToken_isRejected() {
        assertThrows(IllegalArgumentException::class.java) { FrameCodec.encode(Frame.Hello(1, ByteArray(16), ByteArray(31))) }
    }
}
```

- [ ] **Step 3: Çalıştır, başarısız olduğunu gör**

Run: `.\android\gradlew.bat -p android :core:test --tests "dev.keyrelay.core.FrameCodecTest"`
Expected: derleme hatası (`Frame`, `FrameCodec` yok).

- [ ] **Step 4: `Frame.kt`**

```kotlin
package dev.keyrelay.core

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Messages of the tablet-service TLS channel (docs/protocol.md "Mesajlar"). */
sealed interface Frame {
    class Pair(val version: Int, val code: ByteArray, val deviceName: String) : Frame
    class Paired(val deviceId: ByteArray, val token: ByteArray) : Frame
    class Hello(val version: Int, val deviceId: ByteArray, val token: ByteArray) : Frame
    class Welcome(val pcName: String) : Frame
    class Reject(val reason: Int) : Frame
    class Key(val code: Int, val value: Int) : Frame
    class State(val ready: Boolean) : Frame
    data object Sas : Frame
    data object Lock : Frame
    class Ping(val seq: Int) : Frame
    class Pong(val seq: Int) : Frame
}

/** Error classes named like the "error" values of protocol/test-vectors/frames.json. */
enum class FrameError { EMPTY, TOO_LARGE, UNKNOWN_TYPE, TRUNCATED, BAD_PAYLOAD }

class FrameException(val error: FrameError) : IOException(error.name)

object FrameCodec {
    const val PROTOCOL_VERSION = 1
    const val MAX_LENGTH = 512
    const val KEY_FRAME_SIZE = 6
    const val PING_FRAME_SIZE = 7
    const val EMPTY_FRAME_SIZE = 3
    const val ID_SIZE = 16
    const val TOKEN_SIZE = 32
    const val CODE_SIZE = 16

    const val PAIR = 0x01
    const val PAIRED = 0x02
    const val HELLO = 0x03
    const val WELCOME = 0x04
    const val REJECT = 0x05
    const val KEY = 0x10
    const val STATE = 0x11
    const val SAS = 0x12
    const val LOCK = 0x13
    const val PING = 0x20
    const val PONG = 0x21

    fun encode(frame: Frame): ByteArray {
        val (type, payload) = when (frame) {
            is Frame.Pair -> PAIR to byteArrayOf(frame.version.toByte()) + sized(frame.code, CODE_SIZE) + frame.deviceName.toByteArray()
            is Frame.Paired -> PAIRED to sized(frame.deviceId, ID_SIZE) + sized(frame.token, TOKEN_SIZE)
            is Frame.Hello -> HELLO to byteArrayOf(frame.version.toByte()) + sized(frame.deviceId, ID_SIZE) + sized(frame.token, TOKEN_SIZE)
            is Frame.Welcome -> WELCOME to frame.pcName.toByteArray()
            is Frame.Reject -> REJECT to byteArrayOf(frame.reason.toByte())
            is Frame.Key -> KEY to byteArrayOf((frame.code shr 8).toByte(), frame.code.toByte(), frame.value.toByte())
            is Frame.State -> STATE to byteArrayOf(if (frame.ready) 1 else 0)
            Frame.Sas -> SAS to ByteArray(0)
            Frame.Lock -> LOCK to ByteArray(0)
            is Frame.Ping -> PING to int32(frame.seq)
            is Frame.Pong -> PONG to int32(frame.seq)
        }
        val length = payload.size + 1
        require(length <= MAX_LENGTH) { "frame length $length exceeds $MAX_LENGTH" }
        return byteArrayOf((length shr 8).toByte(), length.toByte(), type.toByte()) + payload
    }

    /** KEY frame into out[0..5] without allocating: the per-keystroke path. */
    fun encodeKey(code: Int, value: Int, out: ByteArray) {
        out[0] = 0
        out[1] = 4
        out[2] = KEY.toByte()
        out[3] = (code shr 8).toByte()
        out[4] = code.toByte()
        out[5] = value.toByte()
    }

    fun encodePing(seq: Int, out: ByteArray): Int {
        out[0] = 0
        out[1] = 5
        out[2] = PING.toByte()
        out[3] = (seq ushr 24).toByte()
        out[4] = (seq shr 16).toByte()
        out[5] = (seq shr 8).toByte()
        out[6] = seq.toByte()
        return PING_FRAME_SIZE
    }

    /** Frames without payload (SAS, LOCK). */
    fun encodeEmpty(type: Int, out: ByteArray): Int {
        out[0] = 0
        out[1] = 1
        out[2] = type.toByte()
        return EMPTY_FRAME_SIZE
    }

    /** Reads one frame using [buf] (at least [MAX_LENGTH] bytes); null when the stream ends between frames. */
    fun read(input: InputStream, buf: ByteArray): Frame? {
        val high = input.read()
        if (high < 0) return null
        val low = input.read()
        if (low < 0) throw FrameException(FrameError.TRUNCATED)
        val length = (high shl 8) or low
        if (length == 0) throw FrameException(FrameError.EMPTY)
        if (length > MAX_LENGTH) throw FrameException(FrameError.TOO_LARGE)
        if (readFully(input, buf, length) < length) throw FrameException(FrameError.TRUNCATED)
        return decode(buf[0].toInt() and 0xFF, buf, 1, length - 1)
    }

    private fun decode(type: Int, p: ByteArray, off: Int, n: Int): Frame = when (type) {
        PAIR -> {
            payload(n >= 1 + CODE_SIZE)
            Frame.Pair(u8(p, off), p.copyOfRange(off + 1, off + 1 + CODE_SIZE), utf8(p, off + 1 + CODE_SIZE, n - 1 - CODE_SIZE))
        }
        PAIRED -> {
            payload(n == ID_SIZE + TOKEN_SIZE)
            Frame.Paired(p.copyOfRange(off, off + ID_SIZE), p.copyOfRange(off + ID_SIZE, off + n))
        }
        HELLO -> {
            payload(n == 1 + ID_SIZE + TOKEN_SIZE)
            Frame.Hello(u8(p, off), p.copyOfRange(off + 1, off + 1 + ID_SIZE), p.copyOfRange(off + 1 + ID_SIZE, off + n))
        }
        WELCOME -> Frame.Welcome(utf8(p, off, n))
        REJECT -> { payload(n == 1); Frame.Reject(u8(p, off)) }
        KEY -> { payload(n == 3 && u8(p, off + 2) <= 2); Frame.Key((u8(p, off) shl 8) or u8(p, off + 1), u8(p, off + 2)) }
        STATE -> { payload(n == 1); Frame.State(u8(p, off) and 1 != 0) }
        SAS -> { payload(n == 0); Frame.Sas }
        LOCK -> { payload(n == 0); Frame.Lock }
        PING -> { payload(n == 4); Frame.Ping(int32(p, off)) }
        PONG -> { payload(n == 4); Frame.Pong(int32(p, off)) }
        else -> throw FrameException(FrameError.UNKNOWN_TYPE)
    }

    private fun payload(valid: Boolean) {
        if (!valid) throw FrameException(FrameError.BAD_PAYLOAD)
    }

    private fun sized(bytes: ByteArray, size: Int): ByteArray {
        require(bytes.size == size) { "expected $size bytes, got ${bytes.size}" }
        return bytes
    }

    private fun u8(p: ByteArray, i: Int) = p[i].toInt() and 0xFF

    private fun int32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    private fun int32(p: ByteArray, off: Int) =
        (u8(p, off) shl 24) or (u8(p, off + 1) shl 16) or (u8(p, off + 2) shl 8) or u8(p, off + 3)

    private fun utf8(p: ByteArray, off: Int, n: Int): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(p, off, n)).toString()
    } catch (e: CharacterCodingException) {
        throw FrameException(FrameError.BAD_PAYLOAD)
    }

    private fun readFully(input: InputStream, buf: ByteArray, length: Int): Int {
        var total = 0
        while (total < length) {
            val n = input.read(buf, total, length - total)
            if (n < 0) break
            total += n
        }
        return total
    }
}
```

- [ ] **Step 5: Testlerin geçtiğini gör**

Run: `.\android\gradlew.bat -p android :core:test`
Expected: `BUILD SUCCESSFUL`; FrameCodecTest'te 15 geçerli `tls` vektörü × 2 + 6 geçersiz vektör dinamik test ve 10 tekil test geçer; P1 testleri de geçer.

- [ ] **Step 6: Gradle uyarısının kaynağı (P1 ertelenmiş takip)**

Run: `.\android\gradlew.bat -p android help --warning-mode all`
Uyarı satırındaki "at ..." yığın/dosya bilgisine bak. Kaynak bizim `*.gradle.kts` dosyalarımızdaysa o satırı uyarının önerdiği API ile düzelt ve komutu yeniden çalıştırıp uyarının kalktığını gör; AGP/Kotlin eklentisinden geliyorsa kod değiştirme, uyarının tam metnini ve kaynağını görev raporuna yaz (controller progress dosyasına "P2'de kapatıldı: kaynak <eklenti>" olarak işler).

- [ ] **Step 7: Commit**

```
& $git add android/gradle/libs.versions.toml android/core/build.gradle.kts android/core/src/main/kotlin/dev/keyrelay/core/Frame.kt android/core/src/test/kotlin/dev/keyrelay/core/FrameCodecTest.kt
& $git commit -m "feat(android): tablet ile pc arasındaki mesajlar okunup yazılabilir oldu" -m "- tablet, PC ile konuşacağı mesajları ortak örneklerle aynı biçimde üretir
- bozuk, eksik veya fazla uzun mesajlar reddedilir" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
(Step 6'da bir `.gradle.kts` düzeltildiyse o dosya da `git add`'e eklenir.)

---

### Task 3: Eşleştirme bağlantısı (QR) ve eşlenen PC kaydı (`:core`)

**Files:**
- Create: `android/core/src/main/kotlin/dev/keyrelay/core/PairingUri.kt`, `android/core/src/main/kotlin/dev/keyrelay/core/PairedPc.kt`
- Test: `android/core/src/test/kotlin/dev/keyrelay/core/PairingUriTest.kt`, `android/core/src/test/kotlin/dev/keyrelay/core/PairedPcTest.kt`

**Interfaces:**
- Produces:
  - `data class Endpoint(val host: String, val port: Int)` — `val sni: String?` (IP sabitinde `null`), `toString()` = `host:port` / `[v6]:port`; `companion fun parse(text: String, defaultPort: Int): Endpoint` (geçersizse `IllegalArgumentException`).
  - `class PairingInfo(val pcName: String, val endpoints: List<Endpoint>, val spkiSha256: ByteArray, val code: ByteArray)`; `object PairingUri { const val PREFIX = "keyrelay://pair?"; fun parse(uri: String): PairingInfo }`.
  - `class PairedPc(deviceId: ByteArray, name: String, endpoints: List<Endpoint>, spkiSha256: ByteArray, sealedToken: ByteArray, lastSeenMs: Long, isDefault: Boolean)` + `val id: String` (deviceId hex) + `fun copy(endpoints, lastSeenMs, isDefault)`.
  - Liste düzenlemeleri: `List<PairedPc>.upsert(pc)`, `.without(id)`, `.makeDefault(id)`, `.promote(id, endpoint)`, `.touch(id, nowMs)`, `val List<PairedPc>.defaultPc: PairedPc?`.
  - `object PairedPcCodec { fun encode(pcs: List<PairedPc>): String; fun decode(text: String): List<PairedPc> }`.

- [ ] **Step 1: Failing tests**

`PairingUriTest.kt`:
```kotlin
package dev.keyrelay.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.HexFormat

class PairingUriTest {
    private val fp = "nhDFng6PxrMUestQPWHqag0G99AYI1PSvnW94Q3IWDA"
    private val code = "oKGio6SlpqeoqaqrrK2urw"
    private fun link(query: String) = "keyrelay://pair?$query"
    private fun full(a: String = "homepc.tailnet-example.ts.net:8443,100.64.0.10,192.168.1.10", n: String = "homepc") =
        link("v=1&n=$n&a=$a&p=47111&fp=$fp&c=$code")
    private fun rejects(uri: String) = assertThrows(IllegalArgumentException::class.java) { PairingUri.parse(uri) }

    @Test
    fun parse_fullLink_readsAllFields() {
        val info = PairingUri.parse(full())
        assertEquals("homepc", info.pcName)
        assertEquals(
            listOf(Endpoint("homepc.tailnet-example.ts.net", 8443), Endpoint("100.64.0.10", 47111), Endpoint("192.168.1.10", 47111)),
            info.endpoints,
        )
        assertEquals("9e10c59e0e8fc6b3147acb503d61ea6a0d06f7d0182353d2be75bde10dc85830", HexFormat.of().formatHex(info.spkiSha256))
        assertArrayEquals(HexFormat.of().parseHex("a0a1a2a3a4a5a6a7a8a9aaabacadaeaf"), info.code)
    }

    @Test
    fun parse_percentEncodedName_isDecoded() = assertEquals("Ofis PC", PairingUri.parse(full(n = "Ofis%20PC")).pcName)

    @Test
    fun parse_missingName_usesFirstHost() = assertEquals("192.168.1.10", PairingUri.parse(full(a = "192.168.1.10", n = "")).pcName)

    @Test
    fun parse_ipv6WithAndWithoutPort_parsed() {
        val info = PairingUri.parse(full(a = "[fd7a:115c::1]:443,fd7a:115c::2"))
        assertEquals(listOf(Endpoint("fd7a:115c::1", 443), Endpoint("fd7a:115c::2", 47111)), info.endpoints)
    }

    @Test
    fun parse_wrongVersion_rejected() { rejects(link("v=2&a=h&p=1&fp=$fp&c=$code")) }

    @Test
    fun parse_missingOrBadPort_rejected() {
        rejects(link("v=1&a=h&fp=$fp&c=$code"))
        rejects(link("v=1&a=h&p=0&fp=$fp&c=$code"))
        rejects(link("v=1&a=h:70000&p=1&fp=$fp&c=$code"))
    }

    @Test
    fun parse_fingerprintNot32Bytes_rejected() { rejects(link("v=1&a=h&p=1&fp=AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHw&c=$code")) }

    @Test
    fun parse_codeNot16Bytes_rejected() { rejects(link("v=1&a=h&p=1&fp=$fp&c=AQID")) }

    @Test
    fun parse_noAddresses_rejected() { rejects(link("v=1&a=&p=1&fp=$fp&c=$code")) }

    @Test
    fun parse_otherScheme_rejected() { rejects("https://example.com/pair?v=1") }

    @Test
    fun sni_hostName_isHost_ipLiterals_none() {
        assertEquals("homepc.tailnet-example.ts.net", Endpoint("homepc.tailnet-example.ts.net", 8443).sni)
        assertNull(Endpoint("192.168.1.10", 1).sni)
        assertNull(Endpoint("fd7a:115c::1", 1).sni)
    }

    @Test
    fun toString_thenParse_roundTripsIpv6() {
        val e = Endpoint("fd7a:115c::1", 443)
        assertEquals("[fd7a:115c::1]:443", e.toString())
        assertEquals(e, Endpoint.parse(e.toString(), 0))
    }
}
```

`PairedPcTest.kt`:
```kotlin
package dev.keyrelay.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PairedPcTest {
    private fun pc(n: Int, default: Boolean = false, endpoints: List<Endpoint> = listOf(Endpoint("10.0.0.$n", 47111))) =
        PairedPc(ByteArray(16) { n.toByte() }, "PC $n", endpoints, ByteArray(32) { 7 }, byteArrayOf(1, 2, 3), 1000L + n, default)

    @Test
    fun encode_thenDecode_roundTrips() {
        val original = listOf(pc(1, true, listOf(Endpoint("host.ts.net", 8443), Endpoint("fd7a::1", 443))), pc(2))
        val back = PairedPcCodec.decode(PairedPcCodec.encode(original))
        assertEquals(original.map { it.id }, back.map { it.id })
        assertEquals("PC 1", back[0].name)
        assertEquals(listOf(Endpoint("host.ts.net", 8443), Endpoint("fd7a::1", 443)), back[0].endpoints)
        assertArrayEquals(byteArrayOf(1, 2, 3), back[0].sealedToken)
        assertEquals(1001L, back[0].lastSeenMs)
        assertEquals(listOf(true, false), back.map { it.isDefault })
    }

    @Test
    fun decode_damagedLine_keepsTheOthers() {
        val text = PairedPcCodec.encode(listOf(pc(1))) + "\nv1 not-hex !!\n" + PairedPcCodec.encode(listOf(pc(2)))
        assertEquals(listOf(pc(1).id, pc(2).id), PairedPcCodec.decode(text).map { it.id })
    }

    @Test
    fun decode_empty_returnsEmpty() = assertTrue(PairedPcCodec.decode("").isEmpty())

    @Test
    fun upsert_sameId_replaces() {
        val list = listOf(pc(1)).upsert(pc(1).copy(lastSeenMs = 5))
        assertEquals(1, list.size)
        assertEquals(5L, list[0].lastSeenMs)
    }

    @Test
    fun without_defaultRemoved_firstRemainingBecomesDefault() {
        val list = listOf(pc(1, true), pc(2), pc(3)).without(pc(1).id)
        assertEquals(listOf(true, false), list.map { it.isDefault })
        assertEquals(pc(2).id, list.defaultPc?.id)
    }

    @Test
    fun makeDefault_leavesExactlyOneDefault() {
        val list = listOf(pc(1, true), pc(2)).makeDefault(pc(2).id)
        assertEquals(listOf(false, true), list.map { it.isDefault })
    }

    @Test
    fun promote_knownEndpoint_movesItFirst_unknownIgnored() {
        val a = Endpoint("a", 1)
        val b = Endpoint("b", 2)
        val list = listOf(pc(1, true, listOf(a, b)))
        assertEquals(listOf(b, a), list.promote(pc(1).id, b)[0].endpoints)
        assertEquals(listOf(a, b), list.promote(pc(1).id, Endpoint("c", 3))[0].endpoints)
    }

    @Test
    fun defaultPc_noneMarked_isFirst() = assertEquals(pc(1).id, listOf(pc(1), pc(2)).defaultPc?.id)
}
```

- [ ] **Step 2: Çalıştır, başarısız olduğunu gör**

Run: `.\android\gradlew.bat -p android :core:test --tests "dev.keyrelay.core.PairingUriTest" --tests "dev.keyrelay.core.PairedPcTest"`
Expected: derleme hatası (`Endpoint`, `PairingUri`, `PairedPc` yok).

- [ ] **Step 3: `PairingUri.kt`**

```kotlin
package dev.keyrelay.core

import java.net.URLDecoder
import java.util.Base64

/** One address from the QR `a` list. */
data class Endpoint(val host: String, val port: Int) {
    /** Host name for TLS SNI (Funnel routes by it); IP literals send none. */
    val sni: String? get() = if (isIpLiteral(host)) null else host

    override fun toString() = if (':' in host) "[$host]:$port" else "$host:$port"

    companion object {
        private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

        fun isIpLiteral(host: String) = ':' in host || IPV4.matches(host)

        /** `host`, `host:port`, `[v6]`, `[v6]:port` or a bare IPv6 literal; [defaultPort] fills a missing port. */
        fun parse(text: String, defaultPort: Int): Endpoint {
            val t = text.trim()
            val (host, port) = when {
                t.startsWith("[") -> {
                    val end = t.indexOf(']')
                    require(end > 1) { "bad IPv6 address: $t" }
                    t.substring(1, end) to t.substring(end + 1).removePrefix(":").ifEmpty { null }
                }
                t.count { it == ':' } == 1 -> t.substringBefore(':') to t.substringAfter(':')
                else -> t to null
            }
            val p = if (port == null) defaultPort else port.toIntOrNull() ?: throw IllegalArgumentException("bad port: $t")
            require(host.isNotEmpty()) { "empty host" }
            require(p in 1..65535) { "port out of range: $t" }
            return Endpoint(host, p)
        }
    }
}

class PairingInfo(val pcName: String, val endpoints: List<Endpoint>, val spkiSha256: ByteArray, val code: ByteArray)

/** The QR content of protocol.md "Eşleştirme": keyrelay://pair?v=1&n=&a=&p=&fp=&c= */
object PairingUri {
    const val PREFIX = "keyrelay://pair?"
    private const val SPKI_SIZE = 32

    fun parse(uri: String): PairingInfo {
        require(uri.startsWith(PREFIX)) { "not a KeyRelay pairing link" }
        val q = uri.removePrefix(PREFIX).split('&').filter { it.isNotEmpty() }
            .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8) }
        require(q["v"] == "1") { "unsupported pairing version: ${q["v"]}" }
        val port = q["p"]?.toIntOrNull() ?: throw IllegalArgumentException("missing port")
        require(port in 1..65535) { "port out of range: $port" }
        val endpoints = q["a"].orEmpty().split(',').filter { it.isNotBlank() }.map { Endpoint.parse(it, port) }
        require(endpoints.isNotEmpty()) { "no addresses" }
        val fp = base64Url(q["fp"])
        require(fp.size == SPKI_SIZE) { "fingerprint must be $SPKI_SIZE bytes" }
        val code = base64Url(q["c"])
        require(code.size == FrameCodec.CODE_SIZE) { "code must be ${FrameCodec.CODE_SIZE} bytes" }
        return PairingInfo(q["n"].orEmpty().ifBlank { endpoints.first().host }, endpoints, fp, code)
    }

    private fun base64Url(value: String?): ByteArray = Base64.getUrlDecoder().decode(value.orEmpty())
}
```

- [ ] **Step 4: `PairedPc.kt`**

```kotlin
package dev.keyrelay.core

import java.util.Base64
import java.util.HexFormat

/** A PC this tablet is paired with; [sealedToken] is the device token encrypted by the app (Android Keystore). */
class PairedPc(
    val deviceId: ByteArray,
    val name: String,
    val endpoints: List<Endpoint>,
    val spkiSha256: ByteArray,
    val sealedToken: ByteArray,
    val lastSeenMs: Long,
    val isDefault: Boolean,
) {
    val id: String get() = HexFormat.of().formatHex(deviceId)

    fun copy(endpoints: List<Endpoint> = this.endpoints, lastSeenMs: Long = this.lastSeenMs, isDefault: Boolean = this.isDefault) =
        PairedPc(deviceId, name, endpoints, spkiSha256, sealedToken, lastSeenMs, isDefault)
}

fun List<PairedPc>.upsert(pc: PairedPc): List<PairedPc> = filter { it.id != pc.id } + pc

fun List<PairedPc>.without(id: String): List<PairedPc> {
    val rest = filter { it.id != id }
    return if (rest.isEmpty() || rest.any { it.isDefault }) rest else listOf(rest.first().copy(isDefault = true)) + rest.drop(1)
}

fun List<PairedPc>.makeDefault(id: String): List<PairedPc> = map { it.copy(isDefault = it.id == id) }

/** Moves the address that just worked to the front (protocol.md: the last successful address is tried first). */
fun List<PairedPc>.promote(id: String, endpoint: Endpoint): List<PairedPc> = map { pc ->
    if (pc.id != id || endpoint !in pc.endpoints) pc else pc.copy(endpoints = listOf(endpoint) + pc.endpoints.filter { it != endpoint })
}

fun List<PairedPc>.touch(id: String, nowMs: Long): List<PairedPc> = map { if (it.id == id) it.copy(lastSeenMs = nowMs) else it }

val List<PairedPc>.defaultPc: PairedPc? get() = firstOrNull { it.isDefault } ?: firstOrNull()

/** One line per PC, fields separated by spaces; the name is base64url so a line never contains a space. */
object PairedPcCodec {
    private const val VERSION = "v1"
    private const val FIELDS = 8
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(pcs: List<PairedPc>): String = pcs.joinToString("\n") { pc ->
        listOf(
            VERSION, pc.id, encoder.encodeToString(pc.name.toByteArray()), pc.endpoints.joinToString(","),
            encoder.encodeToString(pc.spkiSha256), encoder.encodeToString(pc.sealedToken), pc.lastSeenMs.toString(),
            if (pc.isDefault) "1" else "0",
        ).joinToString(" ")
    }

    /** Skips lines it cannot read: one damaged entry must not hide the other PCs. */
    fun decode(text: String): List<PairedPc> = text.lineSequence().mapNotNull { runCatching { decodeLine(it) }.getOrNull() }.toList()

    private fun decodeLine(line: String): PairedPc {
        val f = line.split(' ')
        require(f.size == FIELDS && f[0] == VERSION) { "unknown line" }
        return PairedPc(
            HexFormat.of().parseHex(f[1]), String(decoder.decode(f[2])), f[3].split(',').map { Endpoint.parse(it, 0) },
            decoder.decode(f[4]), decoder.decode(f[5]), f[6].toLong(), f[7] == "1",
        )
    }
}
```

- [ ] **Step 5: Testlerin geçtiğini gör**

Run: `.\android\gradlew.bat -p android :core:test`
Expected: `BUILD SUCCESSFUL`, tüm testler geçer.

- [ ] **Step 6: Commit**

```
& $git add android/core/src/main/kotlin/dev/keyrelay/core/PairingUri.kt android/core/src/main/kotlin/dev/keyrelay/core/PairedPc.kt android/core/src/test/kotlin/dev/keyrelay/core/PairingUriTest.kt android/core/src/test/kotlin/dev/keyrelay/core/PairedPcTest.kt
& $git commit -m "feat(android): pc eşleştirme bağlantısı okunur, eşlenen pc'ler saklanır" -m "- Windows'taki QR kodunun içeriği okunur, hatalı kodlar açık bir nedenle reddedilir
- birden çok PC saklanabilir, biri varsayılan seçilir
- en son çalışan adres bir sonraki bağlantıda önce denenir" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Yönlendirme kuralları — ön plan kuralı ve durum makinesi (`:core`)

T1 D1 bir senaryoda farklı olay sırası gösterdiyse, o senaryonun sırası `ForegroundTrackerTest`'e ek test olarak yazılır (beklenen sonuç spec kuralından hesaplanır).

**Files:**
- Modify: `android/core/src/main/kotlin/dev/keyrelay/core/ForegroundTracker.kt`
- Modify: `android/core/src/test/kotlin/dev/keyrelay/core/ForegroundTrackerTest.kt`
- Create: `android/core/src/main/kotlin/dev/keyrelay/core/RelayStateMachine.kt`
- Test: `android/core/src/test/kotlin/dev/keyrelay/core/RelayStateMachineTest.kt`

**Interfaces:**
- Consumes: P1 `ForegroundTracker` imzası (değişmez).
- Produces:
  - `class ForegroundTracker(val isTarget: (uid: Int) -> Boolean)` — `fun seed(pidToUid: Map<Int, Int>)` (yalnız hedef pid'leri alır), `fun onForegroundActivitiesChanged(pid: Int, uid: Int, foreground: Boolean): Boolean`, `fun onProcessDied(pid: Int): Boolean`, `val targetForeground: Boolean`.
  - `class RelayStateMachine(out: Output)` — `interface Output { fun sendKey(code: Int, value: Int); fun sendSas(); fun sendLock(); fun setGrab(on: Boolean); fun onToggled(enabled: Boolean, override: Boolean) }`; `var toggleKey: Int`; `val enabled/override/grabbed: Boolean`; `fun setEnabled(on: Boolean)`, `fun setConnected(on: Boolean)`, `fun setPcReady(on: Boolean)`, `fun onForeground(targetForeground: Boolean, targetEvent: Boolean)`, `fun onKey(code: Int, value: Int)`, `fun releaseHeld()`; sabitler `KEY_SPACE=57`, `KEY_GRAVE=41`, `KEY_INSERT=110`, `TOGGLE_KEYS`.

- [ ] **Step 1: ForegroundTracker testlerini güncelle (failing)**

`ForegroundTrackerTest.kt`'de `onForegroundActivitiesChanged_otherAppChanges_reportsNoChange` testini **sil** (eski "herhangi bir hedef ön planda" kuralını sabitliyordu; spec kuralı başka uygulamanın `true` olayını hedefin ayrılması sayar) ve sınıfın sonuna ekle:
```kotlin
    private val other = 10200

    @Test
    fun onForegroundActivitiesChanged_otherAppComesFront_targetNotForeground() {
        val t = tracker().apply { onForegroundActivitiesChanged(100, target, true) }
        assertTrue(t.onForegroundActivitiesChanged(200, other, true))
        assertFalse(t.targetForeground)
    }

    @Test
    fun onForegroundActivitiesChanged_targetFalseAfterOtherTrue_staysNotForeground() {
        val t = tracker().apply {
            onForegroundActivitiesChanged(100, target, true)
            onForegroundActivitiesChanged(200, other, true)
        }
        assertFalse(t.onForegroundActivitiesChanged(100, target, false))
        assertFalse(t.targetForeground)
    }

    @Test
    fun onForegroundActivitiesChanged_overlayOpensAndCloses_targetReturns() {
        val t = tracker().apply {
            onForegroundActivitiesChanged(100, target, true)
            onForegroundActivitiesChanged(300, other, true)
        }
        assertTrue(t.onForegroundActivitiesChanged(300, other, false))
        assertTrue(t.targetForeground)
    }

    @Test
    fun onForegroundActivitiesChanged_targetTrueAfterOther_targetWins() {
        val t = tracker().apply {
            onForegroundActivitiesChanged(200, other, true)
            onForegroundActivitiesChanged(100, target, true)
        }
        assertTrue(t.targetForeground)
    }

    @Test
    fun seed_ignoresNonTargetPids_theirLaterFalseChangesNothing() {
        val t = tracker().apply { seed(mapOf(100 to target, 300 to other)) }
        assertTrue(t.targetForeground)
        assertFalse(t.onForegroundActivitiesChanged(300, other, false))
    }

    @Test
    fun seed_calledTwice_replacesPreviousState() {
        val t = tracker().apply { seed(mapOf(100 to target)) }
        assertTrue(t.targetForeground)
        t.seed(emptyMap())
        assertFalse(t.targetForeground)
    }

    @Test
    fun onProcessDied_unknownPid_reportsNoChange() {
        val t = tracker().apply { onForegroundActivitiesChanged(100, target, true) }
        assertFalse(t.onProcessDied(999))
        assertTrue(t.targetForeground)
    }
```

- [ ] **Step 2: RelayStateMachine testleri (failing)**

`RelayStateMachineTest.kt`:
```kotlin
package dev.keyrelay.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RelayStateMachineTest {
    private class Recorder : RelayStateMachine.Output {
        val log = mutableListOf<String>()
        override fun sendKey(code: Int, value: Int) { log += "key $code $value" }
        override fun sendSas() { log += "sas" }
        override fun sendLock() { log += "lock" }
        override fun setGrab(on: Boolean) { log += "grab $on" }
        override fun onToggled(enabled: Boolean, override: Boolean) { log += "toggled enabled=$enabled override=$override" }
    }

    private val out = Recorder()
    private val m = RelayStateMachine(out)

    private fun ready(targetFront: Boolean) {
        m.setConnected(true)
        m.setPcReady(true)
        m.onForeground(targetFront, targetEvent = true)
    }

    private fun keys(vararg events: Pair<Int, Int>) = events.forEach { (code, value) -> m.onKey(code, value) }
    private fun combo(key: Int = 57, ctrl: Int = 29, alt: Int = 56) = keys(ctrl to 1, alt to 1, key to 1, key to 0, alt to 0, ctrl to 0)
    private fun fresh() = out.log.clear()

    @Test
    fun onForeground_targetFrontAndReady_grabs() {
        ready(targetFront = true)
        assertEquals(listOf("grab true"), out.log)
    }

    @Test
    fun onForeground_targetLeavesWhileKeyHeld_releasesAfterKeyUp() {
        ready(true); fresh()
        m.onKey(30, 1)
        m.onForeground(false, targetEvent = true)
        assertEquals(listOf("key 30 1"), out.log)
        m.onKey(30, 0)
        assertEquals(listOf("key 30 1", "key 30 0", "grab false"), out.log)
    }

    @Test
    fun setConnected_dropWhileKeyHeld_keyUpStillSentThenUngrab() {
        ready(true); fresh()
        m.onKey(56, 1)
        m.setConnected(false)
        m.onKey(56, 0)
        assertEquals(listOf("key 56 1", "key 56 0", "grab false"), out.log)
    }

    @Test
    fun onKey_toggleWhileTargetFront_disablesAndHidesToggleKey() {
        ready(true); fresh()
        combo()
        assertEquals(
            listOf("key 29 1", "key 56 1", "toggled enabled=false override=false", "key 56 0", "key 29 0", "grab false"),
            out.log,
        )
    }

    @Test
    fun onKey_toggleTwiceTargetNotFront_secondPressReturnsToTablet() {
        ready(false); fresh()
        combo()
        assertEquals(listOf("toggled enabled=true override=true", "grab true"), out.log)
        fresh()
        combo()
        assertEquals(
            listOf("key 29 1", "key 56 1", "toggled enabled=true override=false", "key 56 0", "key 29 0", "grab false"),
            out.log,
        )
        assertFalse(m.grabbed)
    }

    @Test
    fun onKey_toggleTwiceTargetFrontDisabled_secondPressReturnsToTablet() {
        ready(true)
        m.setEnabled(false)
        assertFalse(m.grabbed)
        combo()
        assertTrue(m.grabbed)
        combo()
        assertFalse(m.grabbed)
    }

    @Test
    fun onKey_ctrlAltDelWhileGrabbed_sendsSasInsteadOfDelete() {
        ready(true); fresh()
        keys(29 to 1, 56 to 1, 111 to 1, 111 to 2, 111 to 0, 56 to 0, 29 to 0)
        assertEquals(listOf("key 29 1", "key 56 1", "sas", "key 56 0", "key 29 0"), out.log)
    }

    @Test
    fun releaseHeld_keyHeldWhenNodeLost_sendsKeyUpAndUngrabs() {
        ready(true); fresh()
        keys(29 to 1, 30 to 1)
        m.onForeground(targetForeground = false, targetEvent = true)
        m.releaseHeld()
        assertEquals(listOf("key 29 1", "key 30 1", "key 29 0", "key 30 0", "grab false"), out.log)
    }

    @Test
    fun onKey_winLWhileGrabbed_sendsLockInsteadOfL() {
        ready(true); fresh()
        keys(125 to 1, 38 to 1, 38 to 0, 125 to 0)
        assertEquals(listOf("key 125 1", "lock", "key 125 0"), out.log)
    }

    @Test
    fun onKey_ctrlAltDelNotGrabbed_sendsNothing() {
        keys(29 to 1, 56 to 1, 111 to 1, 111 to 0, 56 to 0, 29 to 0)
        assertTrue(out.log.isEmpty())
    }

    @Test
    fun onForeground_nonTargetEventWithOverride_keepsGrab() {
        ready(false)
        combo(); fresh()
        m.onForeground(false, targetEvent = false)
        assertTrue(out.log.isEmpty())
        assertTrue(m.grabbed)
    }

    @Test
    fun onForeground_targetEventWithOverride_clearsOverride() {
        ready(false)
        combo(); fresh()
        m.onForeground(true, targetEvent = true)
        assertEquals(listOf("toggled enabled=true override=false"), out.log)
        assertTrue(m.grabbed)
        m.onForeground(false, targetEvent = true)
        assertFalse(m.grabbed)
    }

    @Test
    fun setPcReady_false_releasesGrab() {
        ready(true); fresh()
        m.setPcReady(false)
        assertEquals(listOf("grab false"), out.log)
    }

    @Test
    fun onKey_outOfRangeCodeOrValue_ignored() {
        ready(true); fresh()
        keys(-1 to 1, 0x300 to 1, 30 to 3)
        assertTrue(out.log.isEmpty())
        m.onForeground(false, targetEvent = true)
        assertEquals(listOf("grab false"), out.log)
    }

    @Test
    fun onKey_releaseWithoutPress_doesNotBlockGrabChange() {
        ready(true); fresh()
        m.onKey(30, 0)
        m.onForeground(false, targetEvent = true)
        assertEquals(listOf("key 30 0", "grab false"), out.log)
    }

    @Test
    fun setConnected_twice_grabsOnce() {
        ready(true)
        m.setConnected(true)
        assertEquals(listOf("grab true"), out.log)
    }

    @Test
    fun onKey_ctrlSpaceWithoutAlt_forwardsSpace() {
        ready(true); fresh()
        keys(29 to 1, 57 to 1, 57 to 0, 29 to 0)
        assertEquals(listOf("key 29 1", "key 57 1", "key 57 0", "key 29 0"), out.log)
    }

    @Test
    fun onKey_customToggleKey_replacesSpace() {
        m.toggleKey = RelayStateMachine.KEY_GRAVE
        ready(true); fresh()
        combo(key = 57)
        assertTrue(m.enabled)
        combo(key = RelayStateMachine.KEY_GRAVE)
        assertFalse(m.enabled)
    }

    @Test
    fun onKey_rightCtrlAndAltGr_toggle() {
        ready(true); fresh()
        combo(ctrl = 97, alt = 100)
        assertFalse(m.enabled)
    }
}
```

- [ ] **Step 3: Çalıştır, başarısız olduğunu gör**

Run: `.\android\gradlew.bat -p android :core:test --tests "dev.keyrelay.core.ForegroundTrackerTest" --tests "dev.keyrelay.core.RelayStateMachineTest"`
Expected: `RelayStateMachine` yok (derleme hatası); derleme geçtikten sonra eski kuralla (`any(isTarget)`) üç test KALIR: `onForegroundActivitiesChanged_otherAppComesFront_targetNotForeground`, `..._targetFalseAfterOtherTrue_staysNotForeground`, `..._overlayOpensAndCloses_targetReturns` (`seed_ignoresNonTargetPids_...` eski kuralla da geçer; yeni kuralın gerilemesine karşı durur).

- [ ] **Step 4: `ForegroundTracker.kt`**

```kotlin
package dev.keyrelay.core

/**
 * targetForeground = anyTarget && (lastTrueWasTarget || !anyOther): another app's foreground event wins over the target,
 * and a window that opened on top hands focus back with its own false event (spec "Ön plan kuralı").
 */
class ForegroundTracker(val isTarget: (uid: Int) -> Boolean) {
    private val foregroundPids = HashMap<Int, Int>()
    private var lastTrueWasTarget = true

    var targetForeground: Boolean = false
        private set

    /** Takes target pids only: the top-app cgroup also lists processes without foreground activities. */
    fun seed(pidToUid: Map<Int, Int>) {
        foregroundPids.clear()
        for ((pid, uid) in pidToUid) if (isTarget(uid)) foregroundPids[pid] = uid
        lastTrueWasTarget = true
        recompute()
    }

    fun onForegroundActivitiesChanged(pid: Int, uid: Int, foreground: Boolean): Boolean {
        if (foreground) {
            foregroundPids[pid] = uid
            lastTrueWasTarget = isTarget(uid)
        } else {
            foregroundPids.remove(pid)
        }
        return recompute()
    }

    fun onProcessDied(pid: Int): Boolean {
        foregroundPids.remove(pid)
        return recompute()
    }

    private fun recompute(): Boolean {
        var anyTarget = false
        var anyOther = false
        for (uid in foregroundPids.values) if (isTarget(uid)) anyTarget = true else anyOther = true
        val now = anyTarget && (lastTrueWasTarget || !anyOther)
        val changed = now != targetForeground
        targetForeground = now
        return changed
    }
}
```

- [ ] **Step 5: `RelayStateMachine.kt`**

```kotlin
package dev.keyrelay.core

/**
 * Where the keyboard goes (spec "Yönlendirme durum makinesi"). Not thread-safe: the helper drives it under one lock.
 * [onKey] allocates nothing.
 */
class RelayStateMachine(private val out: Output) {
    interface Output {
        fun sendKey(code: Int, value: Int)
        fun sendSas()
        fun sendLock()
        fun setGrab(on: Boolean)
        fun onToggled(enabled: Boolean, override: Boolean)
    }

    var toggleKey = KEY_SPACE
    var enabled = true
        private set
    var override = false
        private set
    var grabbed = false
        private set

    private var connected = false
    private var pcReady = false
    private var targetForeground = false
    private val held = BooleanArray(KEY_CNT)
    private var heldCount = 0
    // A consumed trigger key stays consumed until its release, so its repeat and up never leak to either side.
    private val swallowed = BooleanArray(KEY_CNT)

    fun setEnabled(on: Boolean) {
        if (enabled != on) {
            enabled = on
            out.onToggled(enabled, override)
        }
        applyGrab()
    }

    fun setConnected(on: Boolean) {
        connected = on
        applyGrab()
    }

    fun setPcReady(on: Boolean) {
        pcReady = on
        applyGrab()
    }

    /** [targetEvent]: the event came from a target process (foreground change or death); such events clear [override]. */
    fun onForeground(targetForeground: Boolean, targetEvent: Boolean) {
        this.targetForeground = targetForeground
        if (targetEvent && override) {
            override = false
            out.onToggled(enabled, override)
        }
        applyGrab()
    }

    fun onKey(code: Int, value: Int) {
        if (code < 0 || code >= KEY_CNT || value < 0 || value > 2) return
        if (value == 1 && !held[code]) { held[code] = true; heldCount++ }
        if (value == 0 && held[code]) { held[code] = false; heldCount-- }
        when {
            swallowed[code] -> if (value == 0) swallowed[code] = false
            value == 1 && code == toggleKey && ctrl() && alt() -> { swallowed[code] = true; toggle() }
            !grabbed -> Unit
            value == 1 && code == KEY_DELETE && ctrl() && alt() -> { swallowed[code] = true; out.sendSas() }
            value == 1 && code == KEY_L && meta() -> { swallowed[code] = true; out.sendLock() }
            else -> out.sendKey(code, value)
        }
        applyGrab()
    }

    /** The keyboard node vanished (Bluetooth out of range): its key-ups may never arrive, so release what is held. */
    fun releaseHeld() {
        for (code in held.indices) if (held[code]) onKey(code, 0)
    }

    private fun toggle() {
        if (targetForeground) {
            enabled = !enabled
        } else {
            override = !override
            if (override) enabled = true
        }
        out.onToggled(enabled, override)
    }

    private fun want() = enabled && connected && pcReady && (targetForeground || override)

    // Only with no key held: a side that saw a key-down but not its key-up would keep that key stuck.
    private fun applyGrab() {
        if (heldCount == 0 && want() != grabbed) {
            grabbed = !grabbed
            out.setGrab(grabbed)
        }
    }

    private fun ctrl() = held[KEY_LEFTCTRL] || held[KEY_RIGHTCTRL]
    private fun alt() = held[KEY_LEFTALT] || held[KEY_RIGHTALT]
    private fun meta() = held[KEY_LEFTMETA] || held[KEY_RIGHTMETA]

    companion object {
        const val KEY_CNT = 0x300
        const val KEY_LEFTCTRL = 29
        const val KEY_RIGHTCTRL = 97
        const val KEY_LEFTALT = 56
        const val KEY_RIGHTALT = 100
        const val KEY_LEFTMETA = 125
        const val KEY_RIGHTMETA = 126
        const val KEY_SPACE = 57
        const val KEY_GRAVE = 41
        const val KEY_INSERT = 110
        const val KEY_DELETE = 111
        const val KEY_L = 38

        /** Keys the user can pick for Ctrl+Alt+key in Settings. */
        val TOGGLE_KEYS = intArrayOf(KEY_SPACE, KEY_GRAVE, KEY_INSERT)
    }
}
```

- [ ] **Step 6: Testlerin geçtiğini gör**

Run: `.\android\gradlew.bat -p android :core:test`
Expected: `BUILD SUCCESSFUL`, tüm testler geçer.

- [ ] **Step 7: Commit**

```
& $git add android/core/src/main/kotlin/dev/keyrelay/core/ForegroundTracker.kt android/core/src/test/kotlin/dev/keyrelay/core/ForegroundTrackerTest.kt android/core/src/main/kotlin/dev/keyrelay/core/RelayStateMachine.kt android/core/src/test/kotlin/dev/keyrelay/core/RelayStateMachineTest.kt
& $git commit -m "feat(android): klavye yalnız hedef uygulama öndeyken pc'ye yönlenir" -m "- başka bir uygulamaya geçilince klavye o uygulamanın açılışıyla tablete döner
- üstte açılıp kapanan pencereler klavyeyi kalıcı olarak tablette bırakmaz
- Ctrl+Alt+Space her basışta yönü çevirir; ikinci basış klavyeyi her durumda tablete verir
- Ctrl+Alt+Del ve Win+L PC'de güvenlik ekranı ve kilit olarak çalışır" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: TLS istemcisi — sabitleme, adres sırası, tek yazıcı, canlılık (`:core`)

**Files:**
- Modify: `android/core/build.gradle.kts` (`fakeServer` görevi)
- Create: `android/core/src/main/kotlin/dev/keyrelay/core/IntRingQueue.kt`, `TlsDialer.kt`, `RelayClient.kt`
- Create (test fixture): `android/core/src/test/kotlin/dev/keyrelay/core/FakeRelayServer.kt`, `android/core/src/test/resources/test-server.pem`
- Test: `android/core/src/test/kotlin/dev/keyrelay/core/IntRingQueueTest.kt`, `RelayClientTest.kt`

**Interfaces:**
- Consumes: T2 `Frame`, `FrameCodec` (`encode`, `encodeKey`, `encodePing`, `encodeEmpty`, `read`); T3 `Endpoint`.
- Produces:
  - `class IntRingQueue(capacity: Int)` — `fun offer(v: Int): Boolean`, `fun take(): Int` (bloklar; kapandıktan sonra `CLOSED = -1`), `fun clear()`, `fun close()`.
  - `object TlsDialer` — `const val CONNECT_TIMEOUT_MS = 2_000`; `fun dial(endpoints: List<Endpoint>, spkiSha256: ByteArray): Pair<SSLSocket, Endpoint>`; `fun spkiSha256(cert: X509Certificate): ByteArray`; `class PinMismatchException : IOException` (tüm adresler başka anahtarla yanıtladı).
  - `class ConnectionConfig(endpoints: List<Endpoint>, spkiSha256: ByteArray, deviceId: ByteArray, token: ByteArray, pingIntervalMs: Long = DEFAULT_PING_INTERVAL_MS, pongTimeoutMs: Long = DEFAULT_PONG_TIMEOUT_MS, sendTimeoutMs: Long = DEFAULT_SEND_TIMEOUT_MS)` — `require(pongTimeoutMs > pingIntervalMs)`; `fun sameConnection(other): Boolean`; `DEFAULT_PING_INTERVAL_MS = 5_000`, `DEFAULT_PONG_TIMEOUT_MS = 10_000`, `DEFAULT_SEND_TIMEOUT_MS = 1_000`.
  - `class RelayClient(config: ConnectionConfig, listener: Listener)` — `interface Listener { onConnected(pcName: String, endpoint: Endpoint); onPcReady(ready: Boolean); onDisconnected(reason: String); onRejected(reason: Int); onRtt(ms: Int) }`; `fun start()`, `fun stop()`, `fun sendKey(code: Int, value: Int)`, `fun sendSas()`, `fun sendLock()`, `val endpointOrder: List<Endpoint>`; `companion const val REJECT_PIN_MISMATCH = 0x100` (yerel `onRejected` nedeni); `companion fun pair(endpoints, spkiSha256, code: ByteArray, deviceName: String): Frame` (`Frame.Paired` veya `Frame.Reject`; ağ hatasında `IOException`).
  - Test fixture `FakeRelayServer(behavior, port, bind)` + `main` (`gradlew :core:fakeServer -Pport=47111`) — T12/T16 cihaz denemelerinde PC yerine.

- [ ] **Step 1: Test sertifikası (repo'da, yalnız testler için)**

`android/core/src/test/resources/test-server.pem` (ECDSA P-256, `CN=KeyRelay Test`, 2026–2046; SPKI SHA-256 base64url `nhDFng6PxrMUestQPWHqag0G99AYI1PSvnW94Q3IWDA`, .NET `ExportSubjectPublicKeyInfo` ile hesaplandı):
```
-----BEGIN CERTIFICATE-----
MIIBIjCByqADAgECAghJPASv1MG5FTAKBggqhkjOPQQDAjAYMRYwFAYDVQQDEw1L
ZXlSZWxheSBUZXN0MB4XDTI2MDEwMTAwMDAwMFoXDTQ2MDEwMTAwMDAwMFowGDEW
MBQGA1UEAxMNS2V5UmVsYXkgVGVzdDBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IA
BG5MAG1jUVkp4ADlusmOHKrnVQ6jtSBjpUmR2vXmsWQ8KaahUQuUOyZHm1XrQHcZ
qaLyMXaRqhJsoU6FVAiLfygwCgYIKoZIzj0EAwIDRwAwRAIgRSFM/JxF15CHn/SQ
LH6rb2yoEonKNH+y5jUcKDX4GhoCICprUi00f7ZT8S/lOpFzqDq1a15j6DlRNJ/t
WLJ4z6zh
-----END CERTIFICATE-----
-----BEGIN PRIVATE KEY-----
MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg2FiRuKdg6UQ4Ry8J
fywfuSgMO4eJbClhzvJD9NnEDlKhRANCAARuTABtY1FZKeAA5brJjhyq51UOo7Ug
Y6VJkdr15rFkPCmmoVELlDsmR5tV60B3Gami8jF2kaoSbKFOhVQIi38o
-----END PRIVATE KEY-----
```

- [ ] **Step 2: Test sunucusu (fixture)**

`FakeRelayServer.kt` (test kaynakları):
```kotlin
package dev.keyrelay.core

import java.io.BufferedInputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/** Stand-in for KeyRelay.Service in tests and device checks; serves the checked-in test certificate. */
class FakeRelayServer(
    private val behavior: Behavior = Behavior(),
    port: Int = 0,
    bind: InetAddress = InetAddress.getLoopbackAddress(),
) : AutoCloseable {
    class Behavior(
        val reject: Int = 0,
        val answerPings: Boolean = true,
        val readAfterWelcome: Boolean = true,
        val log: Boolean = false,
    )

    val received = LinkedBlockingQueue<Frame>()
    val sniNames = LinkedBlockingQueue<List<String>>()
    val connections = AtomicInteger()
    private val server = sslContext().serverSocketFactory.createServerSocket(port, 50, bind) as SSLServerSocket
    val port: Int get() = server.localPort

    init {
        server.enabledProtocols = arrayOf("TLSv1.3")
        thread(isDaemon = true, name = "fake-server") {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() as SSLSocket }.getOrNull() ?: break
                connections.incrementAndGet()
                thread(isDaemon = true) {
                    runCatching { handle(socket) }.onFailure { if (behavior.log) println("connection ended: $it") }
                }
            }
        }
    }

    private fun handle(socket: SSLSocket) {
        socket.use { s ->
            s.startHandshake()
            sniNames.put((s.session as ExtendedSSLSession).requestedServerNames.map { (it as SNIHostName).asciiName })
            val input = BufferedInputStream(s.inputStream)
            val out = s.outputStream
            val buf = ByteArray(FrameCodec.MAX_LENGTH)
            while (true) {
                val frame = FrameCodec.read(input, buf) ?: return
                received.put(frame)
                if (behavior.log) println("${System.currentTimeMillis()} ${describe(frame)}")
                when (frame) {
                    is Frame.Hello -> {
                        if (behavior.reject != 0) {
                            out.write(FrameCodec.encode(Frame.Reject(behavior.reject)))
                            return
                        }
                        out.write(FrameCodec.encode(Frame.Welcome(PC_NAME)))
                        out.write(FrameCodec.encode(Frame.State(true)))
                        if (!behavior.readAfterWelcome) Thread.sleep(Long.MAX_VALUE)
                    }
                    is Frame.Pair -> {
                        val reply = if (behavior.reject != 0) Frame.Reject(behavior.reject) else Frame.Paired(PAIRED_ID, PAIRED_TOKEN)
                        out.write(FrameCodec.encode(reply))
                        return
                    }
                    is Frame.Ping -> if (behavior.answerPings) out.write(FrameCodec.encode(Frame.Pong(frame.seq)))
                    else -> Unit
                }
            }
        }
    }

    override fun close() = server.close()

    companion object {
        const val PC_NAME = "TestPC"
        val FINGERPRINT: ByteArray = Base64.getUrlDecoder().decode("nhDFng6PxrMUestQPWHqag0G99AYI1PSvnW94Q3IWDA")
        val PAIRED_ID = ByteArray(16) { it.toByte() }
        val PAIRED_TOKEN = ByteArray(32) { (32 + it).toByte() }
        private const val PASSWORD = "test"

        private fun pem(type: String): ByteArray {
            val text = FakeRelayServer::class.java.getResource("/test-server.pem")!!.readText()
            return Base64.getMimeDecoder().decode(text.substringAfter("-----BEGIN $type-----").substringBefore("-----END $type-----"))
        }

        fun certificate(): X509Certificate =
            CertificateFactory.getInstance("X.509").generateCertificate(pem("CERTIFICATE").inputStream()) as X509Certificate

        fun sslContext(): SSLContext {
            val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pem("PRIVATE KEY")))
            val store = KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setKeyEntry("server", key, PASSWORD.toCharArray(), arrayOf(certificate()))
            }
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, PASSWORD.toCharArray()) }
            return SSLContext.getInstance("TLSv1.3").apply { init(keys.keyManagers, null, null) }
        }

        fun describe(f: Frame) = when (f) {
            is Frame.Key -> "KEY code=${f.code} value=${f.value}"
            is Frame.Ping -> "PING ${f.seq}"
            else -> f.javaClass.simpleName.uppercase()
        }
    }
}

/** `gradlew :core:fakeServer -Pport=47111`: plays the PC for device checks until the Windows service exists (P3). */
fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toIntOrNull() ?: 47111
    FakeRelayServer(FakeRelayServer.Behavior(log = true), port, InetAddress.getByName("0.0.0.0"))
    val hosts = NetworkInterface.networkInterfaces().toList().filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses().toList() }.filterIsInstance<Inet4Address>().map { it.hostAddress }
    val b64 = Base64.getUrlEncoder().withoutPadding()
    println(
        "keyrelay://pair?v=1&n=FakePC&a=${hosts.joinToString(",")}&p=$port" +
            "&fp=${b64.encodeToString(FakeRelayServer.FINGERPRINT)}&c=${b64.encodeToString(ByteArray(FrameCodec.CODE_SIZE))}",
    )
    Thread.sleep(Long.MAX_VALUE)
}
```

`android/core/build.gradle.kts` sonuna ekle:
```kotlin
tasks.register<JavaExec>("fakeServer") {
    description = "Runs the test relay server for device checks (P2 Task 12)."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.keyrelay.core.FakeRelayServerKt")
    args(providers.gradleProperty("port").getOrElse("47111"))
}
```

- [ ] **Step 3: Failing tests**

`IntRingQueueTest.kt`:
```kotlin
package dev.keyrelay.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.concurrent.thread

class IntRingQueueTest {
    @Test
    fun offer_beyondCapacity_returnsFalse() {
        val q = IntRingQueue(2)
        assertTrue(q.offer(1))
        assertTrue(q.offer(2))
        assertFalse(q.offer(3))
    }

    @Test
    fun take_afterWrapAround_keepsFifoOrder() {
        val q = IntRingQueue(2)
        q.offer(1); q.offer(2)
        assertEquals(1, q.take())
        q.offer(3)
        assertEquals(2, q.take())
        assertEquals(3, q.take())
    }

    @Test
    fun close_wakesBlockedTake_returnsClosed() {
        val q = IntRingQueue(4)
        var got = 0
        val t = thread { got = q.take() }
        Thread.sleep(100)
        q.close()
        t.join(2_000)
        assertEquals(IntRingQueue.CLOSED, got)
    }

    @Test
    fun clear_dropsQueuedItems() {
        val q = IntRingQueue(4)
        q.offer(1); q.offer(2)
        q.clear()
        q.offer(9)
        assertEquals(9, q.take())
    }
}
```

`RelayClientTest.kt`:
```kotlin
package dev.keyrelay.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class RelayClientTest {
    private class Events : RelayClient.Listener {
        val queue = LinkedBlockingQueue<String>()
        override fun onConnected(pcName: String, endpoint: Endpoint) { queue.put("connected $pcName $endpoint") }
        override fun onPcReady(ready: Boolean) { queue.put("ready $ready") }
        override fun onDisconnected(reason: String) { queue.put("disconnected") }
        override fun onRejected(reason: Int) { queue.put("rejected $reason") }
        override fun onRtt(ms: Int) { queue.put("rtt") }

        fun await(prefix: String, seconds: Long = 5): String {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
            while (true) {
                val left = deadline - System.nanoTime()
                val e = queue.poll(left.coerceAtLeast(0), TimeUnit.NANOSECONDS) ?: error("no '$prefix' within $seconds s")
                if (e.startsWith(prefix)) return e
            }
        }
    }

    private val events = Events()
    private val closeables = mutableListOf<AutoCloseable>()
    private var client: RelayClient? = null

    @AfterEach
    fun tearDown() {
        client?.stop()
        closeables.forEach { it.close() }
    }

    private fun server(behavior: FakeRelayServer.Behavior = FakeRelayServer.Behavior()) = FakeRelayServer(behavior).also { closeables += it }
    private fun local(s: FakeRelayServer) = Endpoint("127.0.0.1", s.port)
    private fun unusedPort() = ServerSocket(0).use { it.localPort }

    private fun start(
        vararg endpoints: Endpoint,
        pin: ByteArray = FakeRelayServer.FINGERPRINT,
        ping: Long = 5_000,
        pong: Long = 10_000,
        send: Long = 1_000,
    ): RelayClient = RelayClient(ConnectionConfig(endpoints.toList(), pin, ByteArray(16), ByteArray(32), ping, pong, send), events)
        .also { client = it; it.start() }

    private inline fun <reified T : Frame> FakeRelayServer.next(): T {
        while (true) {
            val f = received.poll(5, TimeUnit.SECONDS) ?: error("no ${T::class.simpleName} received")
            if (f is T) return f
        }
    }

    @Test
    fun spkiSha256_testCertificate_matchesDotNetFingerprint() =
        assertArrayEquals(FakeRelayServer.FINGERPRINT, TlsDialer.spkiSha256(FakeRelayServer.certificate()))

    @Test
    fun start_pinnedServer_connectsSendsHelloAndReportsReady() {
        val s = server()
        start(local(s))
        assertEquals("connected TestPC 127.0.0.1:${s.port}", events.await("connected"))
        events.await("ready true")
        assertArrayEquals(ByteArray(16), s.next<Frame.Hello>().deviceId)
    }

    @Test
    fun start_wrongPin_reportsRepairAndStopsRetrying() {
        val s = server()
        start(local(s), pin = ByteArray(32))
        assertEquals("rejected ${RelayClient.REJECT_PIN_MISMATCH}", events.await("rejected"))
        Thread.sleep(1_000)
        assertEquals(1, s.connections.get())
        assertTrue(events.queue.none { it.startsWith("connected") || it.startsWith("disconnected") })
    }

    @Test
    fun start_wrongPinOnOneAddressOnly_keepsRetrying() {
        val s = server()
        start(Endpoint("127.0.0.1", unusedPort()), local(s), pin = ByteArray(32))
        assertEquals("disconnected", events.queue.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun start_firstEndpointDead_usesSecondAndMovesItFirst() {
        val s = server()
        val dead = Endpoint("127.0.0.1", unusedPort())
        val c = start(dead, local(s))
        events.await("connected")
        assertEquals(local(s), c.endpointOrder.first())
    }

    @Test
    fun sendKeySasLock_connected_serverReceivesThem() {
        val s = server()
        val c = start(local(s))
        events.await("ready true")
        c.sendKey(30, 1)
        c.sendSas()
        c.sendLock()
        val key = s.next<Frame.Key>()
        assertEquals(30 to 1, key.code to key.value)
        s.next<Frame.Sas>()
        s.next<Frame.Lock>()
    }

    @Test
    fun start_serverAnswersPings_staysConnectedAndMeasuresRtt() {
        val s = server()
        start(local(s), ping = 100, pong = 300)
        events.await("connected")
        events.await("rtt")
        Thread.sleep(1_000)
        assertTrue(events.queue.none { it == "disconnected" })
    }

    @Test
    fun start_serverIgnoresPings_disconnectsAfterPongTimeout() {
        val s = server(FakeRelayServer.Behavior(answerPings = false))
        start(local(s), ping = 100, pong = 300)
        events.await("connected")
        events.await("disconnected", seconds = 3)
    }

    @Test
    fun sendKey_serverStopsReading_dropsConnection() {
        val s = server(FakeRelayServer.Behavior(readAfterWelcome = false))
        val c = start(local(s), send = 200)
        events.await("ready true")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (events.queue.none { it == "disconnected" } && System.nanoTime() < deadline) {
            repeat(1_000) { c.sendKey(30, it % 2) }
            Thread.sleep(10)
        }
        assertTrue(events.queue.any { it == "disconnected" })
    }

    @Test
    fun start_rejected_reportsReasonAndStopsRetrying() {
        val s = server(FakeRelayServer.Behavior(reject = 2))
        start(local(s))
        events.await("rejected 2")
        Thread.sleep(1_500)
        assertEquals(1, s.connections.get())
    }

    @Test
    fun start_hostName_sendsSni_ipLiteral_sendsNone() {
        val s = server()
        start(Endpoint("localhost", s.port))
        events.await("connected")
        assertEquals(listOf("localhost"), s.sniNames.poll(5, TimeUnit.SECONDS))
        client!!.stop()
        start(local(s))
        events.await("connected")
        assertEquals(emptyList<String>(), s.sniNames.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun stop_connected_doesNotReconnect() {
        val s = server()
        val c = start(local(s))
        events.await("connected")
        c.stop()
        Thread.sleep(1_000)
        assertEquals(1, s.connections.get())
    }

    @Test
    fun pair_accepted_returnsPaired() {
        val s = server()
        val reply = RelayClient.pair(listOf(local(s)), FakeRelayServer.FINGERPRINT, ByteArray(16), "Tab")
        assertArrayEquals(FakeRelayServer.PAIRED_TOKEN, (reply as Frame.Paired).token)
        assertEquals("Tab", s.next<Frame.Pair>().deviceName)
    }

    @Test
    fun pair_rejected_returnsReject() {
        val s = server(FakeRelayServer.Behavior(reject = 3))
        assertEquals(3, (RelayClient.pair(listOf(local(s)), FakeRelayServer.FINGERPRINT, ByteArray(16), "Tab") as Frame.Reject).reason)
    }

    @Test
    fun pair_wrongPin_throws() {
        val s = server()
        assertThrows(IOException::class.java) { RelayClient.pair(listOf(local(s)), ByteArray(32), ByteArray(16), "Tab") }
    }
}
```

- [ ] **Step 4: Çalıştır, başarısız olduğunu gör**

Run: `.\android\gradlew.bat -p android :core:test --tests "dev.keyrelay.core.IntRingQueueTest" --tests "dev.keyrelay.core.RelayClientTest"`
Expected: derleme hatası (`IntRingQueue`, `TlsDialer`, `RelayClient`, `ConnectionConfig` yok).

- [ ] **Step 5: `IntRingQueue.kt`**

```kotlin
package dev.keyrelay.core

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Bounded FIFO of ints for the single writer thread; offering allocates nothing. */
class IntRingQueue(capacity: Int) {
    private val items = IntArray(capacity)
    private var head = 0
    private var size = 0
    private var closed = false
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()

    fun offer(v: Int): Boolean = lock.withLock {
        if (closed || size == items.size) return false
        items[(head + size) % items.size] = v
        size++
        notEmpty.signal()
        true
    }

    /** Blocks until an item is available; returns [CLOSED] once [close] was called. */
    fun take(): Int = lock.withLock {
        while (size == 0 && !closed) notEmpty.await()
        if (size == 0) return CLOSED
        val v = items[head]
        head = (head + 1) % items.size
        size--
        v
    }

    fun clear() = lock.withLock {
        head = 0
        size = 0
    }

    fun close() = lock.withLock {
        closed = true
        notEmpty.signalAll()
    }

    companion object {
        const val CLOSED = -1
    }
}
```

- [ ] **Step 6: `TlsDialer.kt`**

```kotlin
package dev.keyrelay.core

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** Opens the pinned TLS 1.3 socket: addresses in order, 2 s each, SNI for host names (protocol.md). */
object TlsDialer {
    const val CONNECT_TIMEOUT_MS = 2_000

    /** Thrown when every address answered with a different key: the PC was reinstalled and must be paired again. */
    class PinMismatchException : IOException("server key does not match the paired PC")

    fun dial(endpoints: List<Endpoint>, spkiSha256: ByteArray): Pair<SSLSocket, Endpoint> {
        var last = IOException("no endpoints")
        var mismatches = 0
        for (e in endpoints) {
            val trust = PinningTrustManager(spkiSha256)
            try {
                return open(e, trust) to e
            } catch (x: IOException) {
                last = x
                if (trust.mismatched) mismatches++
            }
        }
        // Only all addresses agreeing means reinstall; one hijacked address must not raise a false re-pair warning.
        throw if (endpoints.isNotEmpty() && mismatches == endpoints.size) PinMismatchException() else last
    }

    fun spkiSha256(cert: X509Certificate): ByteArray = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)

    private fun open(e: Endpoint, trust: PinningTrustManager): SSLSocket {
        val context = SSLContext.getInstance("TLSv1.3").apply { init(null, arrayOf(trust), null) }
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(resolve(e.host), e.port), CONNECT_TIMEOUT_MS)
            raw.soTimeout = CONNECT_TIMEOUT_MS
            raw.tcpNoDelay = true
            val tls = context.socketFactory.createSocket(raw, e.host, e.port, true) as SSLSocket
            tls.enabledProtocols = arrayOf("TLSv1.3")
            tls.sslParameters = tls.sslParameters.apply { serverNames = e.sni?.let { listOf(SNIHostName(it)) } ?: emptyList() }
            tls.startHandshake()
            return tls
        } catch (x: Exception) {
            raw.close()
            // An invalid SNI name throws IllegalArgumentException; it must fail this address, not kill the thread.
            throw x as? IOException ?: IOException(x)
        }
    }

    // Name lookups have no timeout of their own; an unanswered DNS query must not stall the address list.
    private fun resolve(host: String): InetAddress = try {
        CompletableFuture.supplyAsync { InetAddress.getByName(host) }.get(CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
    } catch (x: ExecutionException) {
        throw x.cause as? IOException ?: IOException(x.cause)
    } catch (x: TimeoutException) {
        throw SocketTimeoutException("name lookup timed out: $host")
    }

    /** Accepts exactly the paired PC's key; CA chain and host name are not checked (spec "Protokol"). */
    private class PinningTrustManager(private val pin: ByteArray) : X509TrustManager {
        @Volatile var mismatched = false

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            if (chain.isEmpty() || !MessageDigest.isEqual(spkiSha256(chain[0]), pin)) {
                mismatched = true
                throw CertificateException("server key does not match the paired PC")
            }
        }

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            throw CertificateException("client certificates are not used")

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
```

- [ ] **Step 7: `RelayClient.kt`**

```kotlin
package dev.keyrelay.core

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/** What the client needs to reach one paired PC; the three times are user settings (protocol.md "Canlılık"). */
class ConnectionConfig(
    val endpoints: List<Endpoint>,
    val spkiSha256: ByteArray,
    val deviceId: ByteArray,
    val token: ByteArray,
    val pingIntervalMs: Long = DEFAULT_PING_INTERVAL_MS,
    val pongTimeoutMs: Long = DEFAULT_PONG_TIMEOUT_MS,
    val sendTimeoutMs: Long = DEFAULT_SEND_TIMEOUT_MS,
) {
    init {
        require(pongTimeoutMs > pingIntervalMs) { "pongTimeout must exceed pingInterval" }
    }

    /** Equal enough to keep the running session: address order and the enabled flag do not matter. */
    fun sameConnection(other: ConnectionConfig) =
        endpoints.toSet() == other.endpoints.toSet() && spkiSha256.contentEquals(other.spkiSha256) &&
            deviceId.contentEquals(other.deviceId) && token.contentEquals(other.token) &&
            pingIntervalMs == other.pingIntervalMs && pongTimeoutMs == other.pongTimeoutMs && sendTimeoutMs == other.sendTimeoutMs

    companion object {
        const val DEFAULT_PING_INTERVAL_MS = 5_000L
        const val DEFAULT_PONG_TIMEOUT_MS = 10_000L
        const val DEFAULT_SEND_TIMEOUT_MS = 1_000L
    }
}

/**
 * One pinned TLS session to the PC. Three threads: connection (dial, HELLO, reads), writer (the only socket writer)
 * and timer (PING cadence and the per-write send timeout). Sending never blocks the caller.
 */
class RelayClient(private val config: ConnectionConfig, private val listener: Listener) {
    interface Listener {
        fun onConnected(pcName: String, endpoint: Endpoint)
        fun onPcReady(ready: Boolean)
        fun onDisconnected(reason: String)
        fun onRejected(reason: Int)
        fun onRtt(ms: Int)
    }

    @Volatile private var running = false
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var dropReason: String? = null
    @Volatile var endpointOrder: List<Endpoint> = config.endpoints
        private set

    private val queue = IntRingQueue(QUEUE_CAPACITY)
    private val timerLock = ReentrantLock()
    private val timerWake = timerLock.newCondition()
    private var writeStartedNs = 0L
    private var pingSentNs = 0L
    private var pingSeq = 0
    private var failures = 0
    private val frame = ByteArray(FrameCodec.PING_FRAME_SIZE)
    private var connectThread: Thread? = null

    fun start() {
        running = true
        connectThread = thread(isDaemon = true, name = "relay-connect") { connectLoop() }
        thread(isDaemon = true, name = "relay-writer") { writerLoop() }
        thread(isDaemon = true, name = "relay-timer") { timerLoop() }
    }

    fun stop() {
        running = false
        queue.close()
        runCatching { socket?.close() }
        timerLock.withLock { timerWake.signalAll() }
        connectThread?.interrupt()
    }

    fun sendKey(code: Int, value: Int) = enqueue(ITEM_KEY or (code shl 8) or value)

    fun sendSas() = enqueue(ITEM_SAS)

    fun sendLock() = enqueue(ITEM_LOCK)

    // Not connected: the event is dropped (spec: unsendable events are discarded, not replayed later).
    private fun enqueue(item: Int) {
        if (output == null) return
        if (!queue.offer(item)) drop("send queue full")
    }

    private fun drop(reason: String) {
        if (dropReason == null) dropReason = reason
        runCatching { socket?.close() }
    }

    private fun connectLoop() {
        while (running) {
            dropReason = null
            try {
                session()
                return
            } catch (_: TlsDialer.PinMismatchException) {
                // Retrying cannot help until the user pairs again; reported like a REJECT.
                listener.onRejected(REJECT_PIN_MISMATCH)
                return
            } catch (e: IOException) {
                if (!running) return
                listener.onDisconnected(dropReason ?: e.message ?: e.javaClass.simpleName)
            }
            val backoff = minOf(RETRY_BASE_MS shl minOf(failures++, MAX_SHIFT), config.pingIntervalMs)
            try {
                Thread.sleep(backoff)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    /** Returns normally only after a REJECT; every other end is an IOException. */
    private fun session() {
        val (s, endpoint) = TlsDialer.dial(endpointOrder, config.spkiSha256)
        socket = s
        try {
            val out = s.outputStream
            val input = BufferedInputStream(s.inputStream)
            val hello = FrameCodec.encode(Frame.Hello(FrameCodec.PROTOCOL_VERSION, config.deviceId, config.token))
            timedWrite(out, hello, hello.size)
            // The server answers every PING; a silent socket for pongTimeout is a dead connection.
            s.soTimeout = config.pongTimeoutMs.toInt()
            val buf = ByteArray(FrameCodec.MAX_LENGTH)
            when (val first = FrameCodec.read(input, buf) ?: throw EOFException("closed before WELCOME")) {
                is Frame.Welcome -> {
                    failures = 0
                    endpointOrder = listOf(endpoint) + endpointOrder.filter { it != endpoint }
                    queue.clear()
                    output = out
                    timerLock.withLock { timerWake.signalAll() }
                    listener.onConnected(first.pcName, endpoint)
                }
                is Frame.Reject -> {
                    listener.onRejected(first.reason)
                    return
                }
                else -> throw IOException("unexpected frame before WELCOME")
            }
            while (true) {
                when (val f = FrameCodec.read(input, buf) ?: throw EOFException("server closed the connection")) {
                    is Frame.State -> listener.onPcReady(f.ready)
                    is Frame.Pong -> onPong(f.seq)
                    else -> throw IOException("unexpected frame ${f.javaClass.simpleName}")
                }
            }
        } finally {
            output = null
            socket = null
            runCatching { s.close() }
        }
    }

    private fun writerLoop() {
        while (true) {
            val item = queue.take()
            if (item == IntRingQueue.CLOSED) return
            val out = output ?: continue
            val length = when (item and KIND_MASK) {
                ITEM_KEY -> { FrameCodec.encodeKey((item shr 8) and 0xFFFF, item and 0xFF, frame); FrameCodec.KEY_FRAME_SIZE }
                ITEM_SAS -> FrameCodec.encodeEmpty(FrameCodec.SAS, frame)
                ITEM_LOCK -> FrameCodec.encodeEmpty(FrameCodec.LOCK, frame)
                else -> FrameCodec.encodePing(item and SEQ_MASK, frame)
            }
            try {
                timedWrite(out, frame, length)
            } catch (e: IOException) {
                drop("write failed: ${e.message}")
            }
        }
    }

    private fun timedWrite(out: OutputStream, bytes: ByteArray, length: Int) {
        timerLock.withLock {
            writeStartedNs = System.nanoTime()
            timerWake.signalAll()
        }
        try {
            out.write(bytes, 0, length)
            out.flush()
        } finally {
            timerLock.withLock { writeStartedNs = 0 }
        }
    }

    private fun timerLoop() {
        val pingNs = config.pingIntervalMs * NS_PER_MS
        val sendNs = config.sendTimeoutMs * NS_PER_MS
        timerLock.withLock {
            var nextPingNs = System.nanoTime() + pingNs
            while (running) {
                val now = System.nanoTime()
                if (writeStartedNs != 0L && now - writeStartedNs >= sendNs) {
                    writeStartedNs = 0
                    drop("send timeout")
                }
                if (output == null) {
                    nextPingNs = now + pingNs
                } else if (now >= nextPingNs) {
                    pingSeq = (pingSeq + 1) and SEQ_MASK
                    pingSentNs = now
                    nextPingNs = now + pingNs
                    enqueue(ITEM_PING or pingSeq)
                }
                val writeDeadline = if (writeStartedNs != 0L) writeStartedNs + sendNs else Long.MAX_VALUE
                val waitNs = minOf(nextPingNs, writeDeadline) - System.nanoTime()
                if (waitNs > 0) timerWake.awaitNanos(waitNs)
            }
        }
    }

    private fun onPong(seq: Int) {
        val rttMs = timerLock.withLock { if (seq == pingSeq) ((System.nanoTime() - pingSentNs) / NS_PER_MS).toInt() else -1 }
        if (rttMs >= 0) listener.onRtt(rttMs)
    }

    companion object {
        const val QUEUE_CAPACITY = 256
        /** Local reject reason outside the protocol's u8 range: the PC answers with a different key. */
        const val REJECT_PIN_MISMATCH = 0x100
        private const val RETRY_BASE_MS = 500L
        private const val MAX_SHIFT = 10
        private const val PAIR_REPLY_TIMEOUT_MS = 10_000
        private const val NS_PER_MS = 1_000_000L
        private const val KIND_MASK = 0xFF shl 24
        private const val SEQ_MASK = 0xFFFFFF
        private const val ITEM_KEY = 1 shl 24
        private const val ITEM_SAS = 2 shl 24
        private const val ITEM_LOCK = 3 shl 24
        private const val ITEM_PING = 4 shl 24

        /** One-shot pairing (QR flow): PAIR, then PAIRED or REJECT. Blocks the calling thread. */
        fun pair(endpoints: List<Endpoint>, spkiSha256: ByteArray, code: ByteArray, deviceName: String): Frame {
            val (s, _) = TlsDialer.dial(endpoints, spkiSha256)
            s.use {
                it.soTimeout = PAIR_REPLY_TIMEOUT_MS
                it.outputStream.write(FrameCodec.encode(Frame.Pair(FrameCodec.PROTOCOL_VERSION, code, deviceName)))
                val reply = FrameCodec.read(BufferedInputStream(it.inputStream), ByteArray(FrameCodec.MAX_LENGTH))
                if (reply is Frame.Paired || reply is Frame.Reject) return reply
                throw IOException("unexpected pairing reply")
            }
        }
    }
}
```

- [ ] **Step 8: Testlerin geçtiğini gör**

Run: `.\android\gradlew.bat -p android :core:test`
Expected: `BUILD SUCCESSFUL`. RelayClientTest toplam süresi < 60 sn. Bir zamanlama testi aralıklı kalıyorsa beklemeyi büyütmeden önce `superpowers:systematic-debugging` ile nedenini bul (yarış kodda olabilir).

- [ ] **Step 9: Test sunucusunun komut satırından çalıştığını gör**

Run (arka planda, 10 sn sonra durdur): `Start-Job { & "$using:PWD\android\gradlew.bat" -p "$using:PWD\android" :core:fakeServer -Pport=47111 } | Out-Null; Start-Sleep 15; Get-Job | Receive-Job; Get-Job | Stop-Job`
Expected: çıktıda `keyrelay://pair?v=1&n=FakePC&a=<LAN IP'leri>&p=47111&fp=nhDFng6PxrMUestQPWHqag0G99AYI1PSvnW94Q3IWDA&c=AAAAAAAAAAAAAAAAAAAAAA` satırı.

- [ ] **Step 10: Commit**

```
& $git add android/core/build.gradle.kts android/core/src/main/kotlin/dev/keyrelay/core/IntRingQueue.kt android/core/src/main/kotlin/dev/keyrelay/core/TlsDialer.kt android/core/src/main/kotlin/dev/keyrelay/core/RelayClient.kt android/core/src/test/kotlin/dev/keyrelay/core/FakeRelayServer.kt android/core/src/test/resources/test-server.pem android/core/src/test/kotlin/dev/keyrelay/core/IntRingQueueTest.kt android/core/src/test/kotlin/dev/keyrelay/core/RelayClientTest.kt
& $git commit -m "feat(android): pc ile sabitlenmiş sertifikalı şifreli bağlantı kurulur" -m "- tablet yalnız eşlendiği PC'nin anahtarını kabul eder
- PC'nin adresleri sırayla denenir, çalışan adres bir sonraki sefer önce denenir
- yanıt vermeyen ya da yazmayı durduran bağlantı birkaç saniyede düşmüş sayılır
- Windows tarafı hazır olmadan denemek için test sunucusu eklendi" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: adb kabuk katmanı — çıkış kodu, zaman aşımı, atomik anahtar yazımı

**Files:**
- Modify: `android/core/src/main/kotlin/dev/keyrelay/core/ShellOutput.kt`
- Modify: `android/core/src/test/kotlin/dev/keyrelay/core/ShellOutputTest.kt`, `android/core/src/test/kotlin/dev/keyrelay/core/HelperCommandTest.kt`
- Modify: `android/app/src/main/kotlin/dev/keyrelay/launcher/AdbManager.kt`, `android/app/src/main/kotlin/dev/keyrelay/launcher/HelperLauncher.kt` (çağıranlar derlensin diye; T11'de silinir)

**Interfaces:**
- Produces:
  - `class ShellResult(val output: String, val exitCode: Int)`.
  - `object ShellOutput` — `END_MARKER = "__KR_END__"`, `COMMAND_TIMEOUT_MS = 10_000L` (spec `ADB_COMMAND_TIMEOUT`), `fun wrap(command: String): String`, `fun readUntilMarker(read: (ByteArray) -> Int): ShellResult`.
  - `AdbManager.exec(cmd: String): ShellResult` artık `suspend`; zaman aşımında `IOException` atar (asla `TimeoutCancellationException` değil); bağlantı aşaması `setTimeout(COMMAND_TIMEOUT_MS)`.

- [ ] **Step 1: Failing tests**

`ShellOutputTest.kt`'yi şununla değiştir:
```kotlin
package dev.keyrelay.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException

class ShellOutputTest {
    private val marker = ShellOutput.END_MARKER

    private fun reader(vararg chunks: String): (ByteArray) -> Int {
        val queue = ArrayDeque(chunks.map { it.toByteArray() })
        return { buf -> queue.removeFirstOrNull()?.let { it.copyInto(buf); it.size } ?: -1 }
    }

    private fun read(vararg chunks: String) = ShellOutput.readUntilMarker(reader(*chunks))

    @Test
    fun readUntilMarker_markerInOneChunk_returnsOutputAndCode() {
        val r = read("1234\r\n$marker 0\r\n")
        assertEquals("1234", r.output)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun readUntilMarker_markerSplitAcrossChunks_returnsTextBeforeMarker() =
        assertEquals("1234", read("12", "34\n" + marker.take(5), marker.drop(5) + " 0\n").output)

    @Test
    fun readUntilMarker_exitCodeInLaterChunk_waitsForLineEnd() = assertEquals(1, read("x\n$marker", " 1", "\n").exitCode)

    @Test
    fun readUntilMarker_nonZeroCode_returnedNotThrown() {
        val r = read("$marker 1\n")
        assertEquals("", r.output)
        assertEquals(1, r.exitCode)
    }

    @Test
    fun readUntilMarker_streamEndsAfterCodeWithoutNewline_returnsCode() = assertEquals(0, read("$marker 0").exitCode)

    @Test
    fun readUntilMarker_markerWithoutCode_throwsIOException() {
        assertThrows(IOException::class.java) { read("$marker\n") }
    }

    @Test
    fun readUntilMarker_streamEndsWithoutMarker_throwsIOException() {
        assertThrows(IOException::class.java) { read("1234\n") }
    }

    @Test
    fun wrap_appendsMarkerAndExitStatus() = assertEquals("pidof x; echo __KR_END__ \$?", ShellOutput.wrap("pidof x"))
}
```

`HelperCommandTest.kt` sınıfının sonuna ekle (P1 ertelenmiş takip: metakarakter testleri):
```kotlin
    @Test
    fun build_argumentWithBacktickOrBackslash_isRejected() {
        assertThrows(IllegalArgumentException::class.java) { HelperCommand.build("/a/base.apk", "/lib", listOf("--x=`id`")) }
        assertThrows(IllegalArgumentException::class.java) { HelperCommand.build("/a/base.apk", "/lib", listOf("--x=a\\b")) }
    }

    @Test
    fun build_argumentWithSemicolonAndSpace_isQuotedNotRejected() {
        val cmd = HelperCommand.build("/a/base.apk", "/lib", listOf("--x=a; rm -rf b"))
        assertTrue(cmd.contains("'--x=a; rm -rf b'"))
    }

    @Test
    fun build_appUidArgument_followsLib() {
        val cmd = HelperCommand.build("/a/base.apk", "/lib", listOf("--app-uid=10234"))
        assertTrue(cmd.contains("'--lib=/lib/libkeyrelay.so' '--app-uid=10234'"))
    }
```
(`import org.junit.jupiter.api.Assertions.assertTrue` ekle.)

- [ ] **Step 2: Çalıştır, başarısız olduğunu gör**

Run: `.\android\gradlew.bat -p android :core:test --tests "dev.keyrelay.core.ShellOutputTest" --tests "dev.keyrelay.core.HelperCommandTest"`
Expected: `ShellResult`/`wrap` yok → derleme hatası. (HelperCommand testleri mevcut kodla geçer; yeni davranış değil, eksik testtir.)

- [ ] **Step 3: `ShellOutput.kt`**

```kotlin
package dev.keyrelay.core

import java.io.ByteArrayOutputStream
import java.io.IOException

/** Output and exit status of one adb shell command; a non-zero status is a result, not an error. */
class ShellResult(val output: String, val exitCode: Int)

/** Reads an adb shell command's output up to the end marker the command was told to echo. */
object ShellOutput {
    const val END_MARKER = "__KR_END__"

    /** Upper bound for one adb step (spec ADB_COMMAND_TIMEOUT); the mDNS window is twice this. */
    const val COMMAND_TIMEOUT_MS = 10_000L

    private const val BUFFER_SIZE = 8192

    fun wrap(command: String) = "$command; echo $END_MARKER \$?"

    /**
     * Calls [read] (bytes read into the buffer, or -1 at end of stream) until the marker and the exit status after it
     * have arrived. Throws [IOException] if the stream ends first or the status is missing.
     */
    fun readUntilMarker(read: (ByteArray) -> Int): ShellResult {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(BUFFER_SIZE)
        while (true) {
            val n = read(buf)
            if (n > 0) out.write(buf, 0, n)
            // Search everything read so far: marker and exit status can arrive in separate reads.
            val text = out.toString(Charsets.UTF_8.name())
            val marker = text.indexOf(END_MARKER)
            if (marker >= 0) {
                val lineEnd = text.indexOf('\n', marker)
                if (lineEnd >= 0 || n < 0) {
                    val status = text.substring(marker + END_MARKER.length, if (lineEnd >= 0) lineEnd else text.length).trim()
                    val code = status.toIntOrNull() ?: throw IOException("no exit status after end marker")
                    return ShellResult(text.substring(0, marker).trimEnd('\r', '\n'), code)
                }
            }
            if (n < 0) throw IOException("command output ended without end marker")
        }
    }
}
```

- [ ] **Step 4: Testlerin geçtiğini gör**

Run: `.\android\gradlew.bat -p android :core:test`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: `AdbManager` — zaman aşımı, bağlantı aşaması sınırı, atomik anahtar**

`AdbManager.kt`'de: `init` bloğunun başına `setApi(...)`'den sonra şu satırları ekle:
```kotlin
        // libadb's default is Long.MAX_VALUE: a dead adbd would hang the connect phase forever.
        setTimeout(ShellOutput.COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
```
`else` dalındaki iki `writeBytes` satırını şununla değiştir:
```kotlin
            // Certificate first: a key file on disk means its pair is complete.
            writeAtomically(certFile, certificate.encoded)
            writeAtomically(keyFile, privateKey.encoded)
```
`exec`'i (ve üstündeki iki yorum satırını) şununla değiştir:
```kotlin
    // libadb AdbStream.read never signals end of stream cleanly: after adbd closes it throws "Stream closed." when its
    // queue is empty, and blocks forever when data was still queued at close. So every command echoes a marker.
    /** Runs [cmd]; throws IOException when it does not finish within [ShellOutput.COMMAND_TIMEOUT_MS]. */
    suspend fun exec(cmd: String): ShellResult =
        // Null-then-IOException: callers must not mistake a timeout for their own cancellation.
        withTimeoutOrNull(ShellOutput.COMMAND_TIMEOUT_MS) {
            runInterruptible(Dispatchers.IO) {
                openStream("shell:" + ShellOutput.wrap(cmd)).use { stream ->
                    ShellOutput.readUntilMarker { buf ->
                        try {
                            stream.read(buf, 0, buf.size)
                        } catch (e: IOException) {
                            if (stream.isClosed) -1 else throw e
                        }
                    }
                }
            }
        } ?: throw IOException("adb command timed out: ${cmd.take(LOG_CMD_CHARS)}")

    private fun writeAtomically(file: File, bytes: ByteArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use {
            it.write(bytes)
            it.fd.sync()
        }
        if (!tmp.renameTo(file)) throw IOException("could not replace $file")
    }
```
`companion object`'e `private const val LOG_CMD_CHARS = 40` ekle. Importlar: `dev.keyrelay.core.ShellResult`, `java.io.FileOutputStream`, `java.util.concurrent.TimeUnit`, `kotlinx.coroutines.Dispatchers`, `kotlinx.coroutines.runInterruptible`, `kotlinx.coroutines.withTimeoutOrNull`.

- [ ] **Step 6: Çağıranları uyarla**

`HelperLauncher.kt`'de `adb.exec(...)` çağrılarını sonuç tipine uyarla: `adb.exec("pidof ${HelperCommand.NICE_NAME}").isNotBlank()` → `adb.exec("pidof ${HelperCommand.NICE_NAME}").output.isNotBlank()`; döngüdeki `.isBlank()` → `.output.isBlank()`. (`grantSecureSettings` ve `startHelper` zaten `suspend` ve `withContext(Dispatchers.IO)` içinde.)

- [ ] **Step 7: Derle**

Run: `.\android\gradlew.bat -p android :core:test :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. (Cihazda davranış T11/T12 uçtan uca adımlarında görülür.)

- [ ] **Step 8: Commit**

```
& $git add android/core/src/main/kotlin/dev/keyrelay/core/ShellOutput.kt android/core/src/test/kotlin/dev/keyrelay/core/ShellOutputTest.kt android/core/src/test/kotlin/dev/keyrelay/core/HelperCommandTest.kt android/app/src/main/kotlin/dev/keyrelay/launcher/AdbManager.kt android/app/src/main/kotlin/dev/keyrelay/launcher/HelperLauncher.kt
& $git commit -m "fix(android): kurulum komutları zaman aşımı ve çıkış koduyla denetlenir" -m "- takılan bir kurulum komutu en geç 10 saniyede kesilir, kablosuz hata ayıklama açık kalmaz
- komutun başarısız olduğu sonucundan anlaşılır
- cihazın kurulum kimliği yarım yazılıp bozulmaz" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Başlatma akışının saf kısmı (`:core`)

T1 D3 sonucu "dur" çıktıysa bu görev, tasarım kapısının yeni kararıyla güncellenmiş plan maddesine göre yapılır. D3 (c) "onayda `onChange` gelmiyor" çıktıysa kod değişmez (gözlemci zararsızdır), yalnız T11 bildirim metni değişir.

**Files:**
- Modify: `android/gradle/libs.versions.toml`, `android/core/build.gradle.kts` (coroutines)
- Create: `android/core/src/main/kotlin/dev/keyrelay/core/StartupFlow.kt`
- Test: `android/core/src/test/kotlin/dev/keyrelay/core/StartupFlowTest.kt`

**Interfaces:**
- Consumes: T6 `ShellOutput.COMMAND_TIMEOUT_MS`.
- Produces:
  - `enum class AdbConnectResult { CONNECTED, NOT_STARTED, PAIRING_REQUIRED }`.
  - `enum class StartupFailure { NO_PERMISSION, WIRELESS_DEBUGGING_OFF, PAIRING_REQUIRED, HELPER_NOT_STARTED, HELPER_CRASH_LOOP }`.
  - `sealed interface StartupState { Idle; WaitingForWifi; Starting; Running; Restarting(attempt: Int, delayMs: Long); AwaitingApproval; Failed(failure: StartupFailure, detail: String) }`.
  - `interface StartupPorts` — `hasSecureSettingsPermission(): Boolean`, `suspend awaitWifi()`, `putGlobal(key: String, value: Int)`, `suspend connectAdb(): AdbConnectResult`, `suspend startHelper()`, `suspend awaitAttach(timeoutMs: Long): Boolean`, `helperAlive(): Boolean`, `registerWakeups(onWake: () -> Unit)`, `unregisterWakeups()`, `suspend helperLogTail(): String`. Sözleşme: hatalar iptal dışı herhangi bir istisnadır; `StartupFlow` hepsini `Failed`'e çevirir.
  - `class StartupFlow(ports: StartupPorts, scope: CoroutineScope, now: () -> Long)` — `val state: StateFlow<StartupState>`, `fun onServiceStarted(helperAttached: Boolean)` (a), `fun onRetry()` (b), `fun onHelperDied(lastError: String)` (c), `fun onHelperAttached()`; sabitler `DEVELOPMENT_SETTINGS_ENABLED`, `ADB_ENABLED`, `ADB_WIFI_ENABLED`, `RESTART_DELAYS_MS`, `STABLE_MS`. Tüm çağrılar `scope`'un tek thread'inden.

- [ ] **Step 1: Bağımlılıklar**

`libs.versions.toml` `[libraries]`'e:
```toml
coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
```
`core/build.gradle.kts` `dependencies`'e `implementation(libs.coroutines.core)` ve `testImplementation(libs.coroutines.test)` ekle.

- [ ] **Step 2: Failing test**

`StartupFlowTest.kt`:
```kotlin
package dev.keyrelay.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class StartupFlowTest {
    private class FakePorts : StartupPorts {
        val calls = mutableListOf<String>()
        var permission = true
        val connectResults = ArrayDeque<AdbConnectResult>()
        var attach = true
        var alive = true
        var startError: Exception? = null
        var connectGate: CompletableDeferred<Unit>? = null
        var wifiGate: CompletableDeferred<Unit>? = null
        var wake: (() -> Unit)? = null

        override fun hasSecureSettingsPermission() = permission
        override suspend fun awaitWifi() { calls += "wifi"; wifiGate?.await() }
        override fun putGlobal(key: String, value: Int) { calls += "put $key=$value" }
        override suspend fun connectAdb(): AdbConnectResult {
            calls += "connect"
            connectGate?.await()
            return connectResults.removeFirstOrNull() ?: AdbConnectResult.CONNECTED
        }
        override suspend fun startHelper() { calls += "start"; startError?.let { throw it } }
        override suspend fun awaitAttach(timeoutMs: Long): Boolean { calls += "attach"; return attach }
        override fun helperAlive() = alive
        override fun registerWakeups(onWake: () -> Unit) { calls += "register"; wake = onWake }
        override fun unregisterWakeups() { calls += "unregister"; wake = null }
        override suspend fun helperLogTail() = "log tail"
    }

    private val ports = FakePorts()
    private val putOn = listOf("put development_settings_enabled=1", "put adb_enabled=1", "put adb_wifi_enabled=1")
    private val success = listOf("unregister", "wifi") + putOn + listOf("connect", "start", "attach", "unregister", "put adb_wifi_enabled=0")

    private fun TestScope.flow() = StartupFlow(ports, backgroundScope) { testScheduler.currentTime }
    private fun puts() = ports.calls.count { it == "put adb_wifi_enabled=1" }

    @Test
    fun onServiceStarted_helperNotAttached_runsStepOneThenZeroAndRuns() = runTest {
        val f = flow()
        f.onServiceStarted(helperAttached = false)
        advanceUntilIdle()
        assertEquals(success, ports.calls)
        assertEquals(StartupState.Running, f.state.value)
    }

    @Test
    fun onServiceStarted_helperAttached_doesNothing() = runTest {
        val f = flow()
        f.onServiceStarted(helperAttached = true)
        advanceUntilIdle()
        assertTrue(ports.calls.isEmpty())
        assertEquals(StartupState.Running, f.state.value)
    }

    @Test
    fun attempt_wirelessDebuggingNotStarted_awaitsApprovalWithObserverAfterZero() = runTest {
        ports.connectResults += AdbConnectResult.NOT_STARTED
        val f = flow()
        f.onServiceStarted(false)
        advanceUntilIdle()
        assertEquals(listOf("unregister", "wifi") + putOn + listOf("connect", "unregister", "put adb_wifi_enabled=0", "register"), ports.calls)
        assertEquals(StartupState.AwaitingApproval, f.state.value)
    }

    @Test
    fun wake_whileAwaitingApproval_triesWithoutPut() = runTest {
        ports.connectResults += AdbConnectResult.NOT_STARTED
        val f = flow()
        f.onServiceStarted(false)
        advanceUntilIdle()
        ports.calls.clear()
        ports.wake!!()
        advanceUntilIdle()
        assertEquals(listOf("unregister", "wifi", "connect", "start", "attach", "unregister", "put adb_wifi_enabled=0"), ports.calls)
        assertEquals(StartupState.Running, f.state.value)
    }

    @Test
    fun wake_attemptFailsAgain_registersOnceAndPutsOnlyInStepOne() = runTest {
        ports.connectResults += listOf(AdbConnectResult.NOT_STARTED, AdbConnectResult.NOT_STARTED)
        val f = flow()
        f.onServiceStarted(false)
        advanceUntilIdle()
        ports.wake!!()
        advanceUntilIdle()
        assertEquals(1, puts())
        assertEquals(StartupState.AwaitingApproval, f.state.value)
        assertEquals("register", ports.calls.last())
    }

    @Test
    fun attempt_pairingRequired_failsWithoutObserver() = runTest {
        ports.connectResults += AdbConnectResult.PAIRING_REQUIRED
        val f = flow()
        f.onServiceStarted(false)
        advanceUntilIdle()
        assertEquals(StartupState.Failed(StartupFailure.PAIRING_REQUIRED, ""), f.state.value)
        assertTrue("register" !in ports.calls)
    }

    @Test
    fun attempt_helperNeverAttaches_failsWithLogTail() = runTest {
        ports.attach = false
        val f = flow()
        f.onServiceStarted(false)
        advanceUntilIdle()
        assertEquals(StartupState.Failed(StartupFailure.HELPER_NOT_STARTED, "log tail"), f.state.value)
        assertEquals("put adb_wifi_enabled=0", ports.calls.last())
    }

    @Test
    fun attempt_startCommandThrows_failsAndStillWritesZero() = runTest {
        ports.startError = IOException("adb command timed out")
        val f = flow()
        f.onServiceStarted(false)
        advanceUntilIdle()
        assertEquals(StartupState.Failed(StartupFailure.HELPER_NOT_STARTED, "adb command timed out"), f.state.value)
        assertEquals("put adb_wifi_enabled=0", ports.calls.last())
    }

    @Test
    fun attempt_portThrowsRuntimeException_failsInsteadOfPropagating() = runTest {
        ports.startError = IllegalStateException("Already connected")
        val f = flow()
        f.onServiceStarted(false)
        advanceUntilIdle()
        assertEquals(StartupState.Failed(StartupFailure.HELPER_NOT_STARTED, "Already connected"), f.state.value)
        assertEquals("put adb_wifi_enabled=0", ports.calls.last())
    }

    @Test
    fun attempt_noPermission_failsWithoutTouchingSettings() = runTest {
        ports.permission = false
        val f = flow()
        f.onServiceStarted(false)
        advanceUntilIdle()
        assertEquals(StartupState.Failed(StartupFailure.NO_PERMISSION, ""), f.state.value)
        assertTrue(ports.calls.none { it.startsWith("put") })
    }

    @Test
    fun attempt_noWifiYet_waitsWithoutPut() = runTest {
        ports.wifiGate = CompletableDeferred()
        val f = flow()
        f.onServiceStarted(false)
        runCurrent()
        assertEquals(StartupState.WaitingForWifi, f.state.value)
        assertEquals(0, puts())
        ports.wifiGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(StartupState.Running, f.state.value)
    }

    @Test
    fun onHelperDied_backsOff5_20_60ThenStops() = runTest {
        val f = flow()
        f.onServiceStarted(false)
        advanceUntilIdle()
        for (delayMs in listOf(5_000L, 20_000L, 60_000L)) {
            val before = puts()
            f.onHelperDied("boom")
            advanceTimeBy(delayMs - 1)
            runCurrent()
            assertEquals(before, puts())
            advanceTimeBy(1)
            advanceUntilIdle()
            assertEquals(before + 1, puts())
        }
        f.onHelperDied("boom")
        advanceUntilIdle()
        assertEquals(StartupState.Failed(StartupFailure.HELPER_CRASH_LOOP, "boom"), f.state.value)
        assertEquals(4, puts())
    }

    @Test
    fun onHelperDied_afterTenStableMinutes_restartsCounter() = runTest {
        val f = flow()
        f.onServiceStarted(false)
        advanceUntilIdle()
        repeat(3) { f.onHelperDied("x"); advanceUntilIdle() }
        advanceTimeBy(StartupFlow.STABLE_MS)
        f.onHelperDied("x")
        assertEquals(StartupState.Restarting(1, 5_000L), f.state.value)
    }

    @Test
    fun onRetry_afterCrashLoop_startsAgainWithPut() = runTest {
        val f = flow()
        f.onServiceStarted(false)
        advanceUntilIdle()
        repeat(4) { f.onHelperDied("x"); advanceUntilIdle() }
        val before = puts()
        f.onRetry()
        advanceUntilIdle()
        assertEquals(before + 1, puts())
        assertEquals(StartupState.Running, f.state.value)
    }

    @Test
    fun onRetry_duringFlight_closesFlightBeforeStartingOver() = runTest {
        ports.connectGate = CompletableDeferred()
        val f = flow()
        f.onServiceStarted(false)
        runCurrent()
        ports.connectGate = null
        f.onRetry()
        advanceUntilIdle()
        val firstZero = ports.calls.indexOf("put adb_wifi_enabled=0")
        val secondOn = ports.calls.lastIndexOf("put adb_wifi_enabled=1")
        assertTrue(firstZero in 0 until secondOn, ports.calls.toString())
        assertEquals(StartupState.Running, f.state.value)
    }

    @Test
    fun onServiceStartedAndDeath_duringFlight_areIgnored() = runTest {
        ports.connectGate = CompletableDeferred()
        val f = flow()
        f.onServiceStarted(false)
        runCurrent()
        f.onServiceStarted(false)
        f.onHelperDied("x")
        ports.connectGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, puts())
        assertEquals(StartupState.Running, f.state.value)
    }

    @Test
    fun attempt_helperDeadAtEnd_schedulesRestart() = runTest {
        ports.alive = false
        val f = flow()
        f.onServiceStarted(false)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(StartupState.Restarting(1, 5_000L), f.state.value)
    }

    @Test
    fun scopeCancelled_duringFlight_stillWritesZero() = runTest {
        ports.connectGate = CompletableDeferred()
        val scope = TestScope(testScheduler)
        val f = StartupFlow(ports, scope) { testScheduler.currentTime }
        f.onServiceStarted(false)
        runCurrent()
        scope.cancel()
        runCurrent()
        assertEquals("put adb_wifi_enabled=0", ports.calls.last())
    }
}
```
(Son testte `import kotlinx.coroutines.cancel` gerekir.)

- [ ] **Step 3: Çalıştır, başarısız olduğunu gör**

Run: `.\android\gradlew.bat -p android :core:test --tests "dev.keyrelay.core.StartupFlowTest"`
Expected: derleme hatası (`StartupFlow` vb. yok).

- [ ] **Step 4: `StartupFlow.kt`**

```kotlin
package dev.keyrelay.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

enum class AdbConnectResult { CONNECTED, NOT_STARTED, PAIRING_REQUIRED }

enum class StartupFailure { NO_PERMISSION, WIRELESS_DEBUGGING_OFF, PAIRING_REQUIRED, HELPER_NOT_STARTED, HELPER_CRASH_LOOP }

sealed interface StartupState {
    data object Idle : StartupState
    data object WaitingForWifi : StartupState
    data object Starting : StartupState
    data object Running : StartupState
    data class Restarting(val attempt: Int, val delayMs: Long) : StartupState
    data object AwaitingApproval : StartupState
    data class Failed(val failure: StartupFailure, val detail: String) : StartupState
}

/** Android side effects of the start sequence. Failures are exceptions other than cancellation. */
interface StartupPorts {
    fun hasSecureSettingsPermission(): Boolean
    suspend fun awaitWifi()
    fun putGlobal(key: String, value: Int)
    suspend fun connectAdb(): AdbConnectResult
    suspend fun startHelper()
    suspend fun awaitAttach(timeoutMs: Long): Boolean
    fun helperAlive(): Boolean
    fun registerWakeups(onWake: () -> Unit)
    fun unregisterWakeups()
    suspend fun helperLogTail(): String
}

/**
 * Starts the shell-uid helper over wireless adb (spec "Başlatma", steps 1-4 and triggers a/b/c).
 * One attempt flies at a time. Call every method from the [scope]'s single thread.
 */
class StartupFlow(private val ports: StartupPorts, private val scope: CoroutineScope, private val now: () -> Long) {
    private val _state = MutableStateFlow<StartupState>(StartupState.Idle)
    val state: StateFlow<StartupState> = _state.asStateFlow()

    private var flight: Job? = null
    private var deaths = 0
    private var attachedAtMs = NOT_ATTACHED

    /** Trigger (a): the relay service started. */
    fun onServiceStarted(helperAttached: Boolean) {
        if (helperAttached) {
            onHelperAttached()
            return
        }
        if (!inFlight()) flight = scope.launch { attempt(withPut = true) }
    }

    /** Trigger (b): closes a running attempt (its cleanup runs first) and starts over. */
    fun onRetry() {
        deaths = 0
        val previous = flight
        flight = scope.launch {
            previous?.cancelAndJoin()
            attempt(withPut = true)
        }
    }

    /** Trigger (c). [lastError] is what the helper reported before it died. */
    fun onHelperDied(lastError: String) {
        if (attachedAtMs != NOT_ATTACHED && now() - attachedAtMs >= STABLE_MS) deaths = 0
        attachedAtMs = NOT_ATTACHED
        if (!inFlight()) restartLater(lastError)
    }

    fun onHelperAttached() {
        attachedAtMs = now()
        _state.value = StartupState.Running
    }

    private fun inFlight() = flight?.isActive == true

    // Bounded so a helper that always crashes cannot cycle the approval window and adb on and off forever.
    private fun restartLater(lastError: String) {
        if (deaths >= RESTART_DELAYS_MS.size) {
            _state.value = StartupState.Failed(StartupFailure.HELPER_CRASH_LOOP, lastError)
            return
        }
        val delayMs = RESTART_DELAYS_MS[deaths++]
        _state.value = StartupState.Restarting(deaths, delayMs)
        flight = scope.launch {
            delay(delayMs)
            attempt(withPut = true)
        }
    }

    private fun onWake() {
        if (!inFlight()) flight = scope.launch { attempt(withPut = false) }
    }

    private suspend fun attempt(withPut: Boolean) {
        ports.unregisterWakeups()
        if (!ports.hasSecureSettingsPermission()) {
            _state.value = StartupState.Failed(StartupFailure.NO_PERMISSION, "")
            return
        }
        _state.value = StartupState.WaitingForWifi
        ports.awaitWifi()
        _state.value = StartupState.Starting
        var failure: StartupFailure? = null
        var detail = ""
        try {
            if (withPut) {
                ports.putGlobal(DEVELOPMENT_SETTINGS_ENABLED, 1)
                ports.putGlobal(ADB_ENABLED, 1)
                ports.putGlobal(ADB_WIFI_ENABLED, 1)
            }
            when (ports.connectAdb()) {
                AdbConnectResult.NOT_STARTED -> failure = StartupFailure.WIRELESS_DEBUGGING_OFF
                AdbConnectResult.PAIRING_REQUIRED -> failure = StartupFailure.PAIRING_REQUIRED
                AdbConnectResult.CONNECTED -> {
                    ports.startHelper()
                    if (!ports.awaitAttach(ShellOutput.COMMAND_TIMEOUT_MS)) {
                        failure = StartupFailure.HELPER_NOT_STARTED
                        detail = ports.helperLogTail()
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not only IO: a corrupt adb key or a libadb state error would otherwise crash the service in a loop.
            failure = StartupFailure.HELPER_NOT_STARTED
            detail = e.message ?: e.toString()
        } finally {
            withContext(NonCancellable) {
                ports.unregisterWakeups()
                ports.putGlobal(ADB_WIFI_ENABLED, 0)
            }
        }
        when (failure) {
            // A helper that attached and died inside this flight had its death ignored; catch it here.
            null -> if (ports.helperAlive()) onHelperAttached() else restartLater("helper died right after attach")
            // Registered only now: our own 0 must not be the first change the observer sees.
            StartupFailure.WIRELESS_DEBUGGING_OFF -> {
                _state.value = StartupState.AwaitingApproval
                ports.registerWakeups(::onWake)
            }
            else -> _state.value = StartupState.Failed(failure, detail)
        }
    }

    companion object {
        const val DEVELOPMENT_SETTINGS_ENABLED = "development_settings_enabled"
        const val ADB_ENABLED = "adb_enabled"
        const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
        val RESTART_DELAYS_MS = longArrayOf(5_000, 20_000, 60_000)
        const val STABLE_MS = 10 * 60_000L
        private const val NOT_ATTACHED = -1L
    }
}
```

- [ ] **Step 5: Testlerin geçtiğini gör**

Run: `.\android\gradlew.bat -p android :core:test`
Expected: `BUILD SUCCESSFUL`, `StartupFlowTest`'in 18 testi geçer.

- [ ] **Step 6: Commit**

```
& $git add android/gradle/libs.versions.toml android/core/build.gradle.kts android/core/src/main/kotlin/dev/keyrelay/core/StartupFlow.kt android/core/src/test/kotlin/dev/keyrelay/core/StartupFlowTest.kt
& $git commit -m "feat(android): başlatma yeni ağ onayını ve tekrarlayan çökmeleri yönetir" -m "- yeni bir Wi-Fi ağında sistem onayı beklenir, onay gelince tekrar denenir
- kapanan yardımcı süreç 5, 20 ve 60 saniye arayla en fazla üç kez yeniden başlatılır
- kablosuz hata ayıklama her denemenin sonunda kapatılır" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Yardımcı girdi katmanı — tüm klavye düğümleri, takıp çıkarma, EINTR

**Files:**
- Modify: `android/app/src/main/cpp/evdev.c` (`GetStringUTFChars` NULL)
- Modify: `android/core/src/main/kotlin/dev/keyrelay/core/KeyboardGrouping.kt`, `android/core/src/test/kotlin/dev/keyrelay/core/KeyboardGroupingTest.kt`
- Modify: `android/app/src/main/kotlin/dev/keyrelay/helper/DeviceScanner.kt`
- Create: `android/app/src/main/kotlin/dev/keyrelay/helper/InputReader.kt`
- Modify: `android/app/src/main/kotlin/dev/keyrelay/helper/Main.kt` (`--grab` teşhis modu `InputReader` kullanır; T9'da kalkar)

**Interfaces:**
- Consumes: P1 `Evdev`, `KeyboardGrouping.group`, `InputNode`.
- Produces: `fun interface KeySink { fun onKey(code: Int, value: Int) }`; `class InputReader(sink: KeySink, onKeyboards: (List<String>) -> Unit, onNodeLost: () -> Unit = {})` — `fun start()`, `fun setGrab(on: Boolean)` (`@Synchronized`; kendi kilidini tutarken dışarı çağrı yapmaz), `fun rescan()`.

- [ ] **Step 1: Failing test (touchpad kardeşi)**

`KeyboardGroupingTest.kt`'ye ekle:
```kotlin
    private val evAbs = 1 shl 3

    @Test
    fun group_touchpadSiblingSameUniq_isExcluded() {
        val kb = node("/dev/input/event13", codes = intArrayOf(30, 57))
        val touchpad = node("/dev/input/event15", ev = evKey or evAbs, codes = intArrayOf(330, 272))
        assertEquals(listOf(kb.path), KeyboardGrouping.group(listOf(kb, touchpad)).single().nodes.map { it.path })
    }

    @Test
    fun group_absNodeWithoutTouch_isGrouped() {
        val kb = node("/dev/input/event13", codes = intArrayOf(30, 57))
        val consumer = node("/dev/input/event16", ev = evKey or evAbs, codes = intArrayOf(164))
        assertEquals(listOf(kb.path, consumer.path), KeyboardGrouping.group(listOf(kb, consumer)).single().nodes.map { it.path })
    }
```

- [ ] **Step 2: Çalıştır, başarısız olduğunu gör**

Run: `.\android\gradlew.bat -p android :core:test --tests "dev.keyrelay.core.KeyboardGroupingTest"`
Expected: `group_touchpadSiblingSameUniq_isExcluded` KALIR (touchpad gruba giriyor).

- [ ] **Step 3: `KeyboardGrouping` düzeltmesi**

Sabitlere `private const val EV_ABS = 3` ve `private const val BTN_TOUCH = 330` ekle; `isKeyOnly`'yi değiştir:
```kotlin
    // A touchpad reports EV_ABS with BTN_TOUCH; other EV_ABS siblings (some media nodes) are still keys.
    private fun InputNode.isKeyOnly() = hasEv(EV_KEY) && !hasEv(EV_REL) && !(hasEv(EV_ABS) && hasKey(BTN_TOUCH))
```

- [ ] **Step 4: Testin geçtiğini gör**

Run: `.\android\gradlew.bat -p android :core:test`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: `evdev.c` NULL denetimi**

`FN(open)` gövdesinin ilk satırından sonra ekle:
```c
    if (p == NULL) return -ENOMEM;
```

- [ ] **Step 6: `DeviceScanner` — sayısal sıra, atlanan düğüm loglanır**

`DeviceScanner.kt`'de `.sortedBy { it.name }` → `.sortedBy { it.name.removePrefix("event").toIntOrNull() ?: Int.MAX_VALUE }` (sözlük sırası `event14 < event9` yapıyordu); `if (fd < 0) return@mapNotNull null` → `if (fd < 0) { println("skip ${f.path}: $fd"); return@mapNotNull null }`.

- [ ] **Step 7: `InputReader.kt`**

```kotlin
package dev.keyrelay.helper

import android.os.FileObserver
import dev.keyrelay.core.KeyboardGrouping
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/** Called on a reader thread for every EV_KEY event; a fun interface so the ints are not boxed. */
fun interface KeySink {
    fun onKey(code: Int, value: Int)
}

/**
 * Reads and grabs every keyboard node. Plugged-in or re-connected keyboards are found through inotify on /dev/input;
 * a node that disappears ends its reader with an error (no polling).
 */
class InputReader(
    private val sink: KeySink,
    private val onKeyboards: (List<String>) -> Unit,
    private val onNodeLost: () -> Unit = {},
) {
    private val open = HashMap<String, Int>()
    private var grabbed = false

    // Kept in a field: an unreferenced FileObserver is garbage collected and stops watching.
    // ATTRIB too: ueventd creates the node first and makes it readable a moment later.
    private val observer = object : FileObserver(File(INPUT_DIR), FileObserver.CREATE or FileObserver.ATTRIB) {
        override fun onEvent(event: Int, path: String?) {
            if (path?.startsWith("event") == true) rescan()
        }
    }

    fun start() {
        rescan()
        observer.startWatching()
    }

    @Synchronized
    fun setGrab(on: Boolean) {
        grabbed = on
        for ((path, fd) in open) {
            val result = Evdev.grab(fd, on)
            if (result != 0) println("grab $path on=$on -> $result")
        }
    }

    fun rescan() {
        val keyboards = KeyboardGrouping.group(DeviceScanner.scan())
        synchronized(this) {
            for (node in keyboards.flatMap { it.nodes }) {
                if (node.path in open) continue
                val fd = Evdev.open(node.path)
                if (fd < 0) {
                    println("open ${node.path} failed: $fd")
                    continue
                }
                open[node.path] = fd
                if (grabbed) Evdev.grab(fd, true)
                thread(isDaemon = true, name = "read ${node.path}") { readLoop(node.path, fd) }
            }
        }
        onKeyboards(keyboards.map { it.name })
    }

    // Only the reader closes its fd: closing it from another thread would not wake a blocked read.
    private fun readLoop(path: String, fd: Int) {
        val buf = ByteBuffer.allocateDirect(Evdev.INPUT_EVENT_SIZE * EVENTS_PER_READ).order(ByteOrder.nativeOrder())
        while (true) {
            val n = Evdev.read(fd, buf)
            if (n == -EINTR) continue
            if (n <= 0) {
                println("${System.currentTimeMillis()} read $path ended: $n")
                break
            }
            var off = 0
            while (off + Evdev.INPUT_EVENT_SIZE <= n) {
                if (buf.getShort(off + TYPE_OFFSET).toInt() == EV_KEY) {
                    sink.onKey(buf.getShort(off + CODE_OFFSET).toInt() and 0xFFFF, buf.getInt(off + VALUE_OFFSET))
                }
                off += Evdev.INPUT_EVENT_SIZE
            }
        }
        synchronized(this) {
            open.remove(path)
            Evdev.close(fd)
        }
        // The kernel's synthetic key-ups for a vanished device usually never reach an evdev reader.
        onNodeLost()
        rescan()
    }

    private companion object {
        const val INPUT_DIR = "/dev/input"
        const val EVENTS_PER_READ = 64
        const val EINTR = 4
        const val EV_KEY = 1
        // struct input_event on 64-bit: timeval (16 bytes), u16 type, u16 code, s32 value.
        const val TYPE_OFFSET = 16
        const val CODE_OFFSET = 18
        const val VALUE_OFFSET = 20
    }
}
```

- [ ] **Step 8: `--grab` teşhis modu `InputReader` ile**

`Main.kt`'de `opts["--grab"]?.let { ... grabAndDump(...) }` satırını şununla, `grabAndDump` fonksiyonunu da `grabAndLog` ile değiştir:
```kotlin
        opts["--grab"]?.let { seconds -> grabAndLog(seconds.toLongOrNull() ?: 15) }
```
```kotlin
    private fun grabAndLog(seconds: Long) {
        val reader = InputReader(
            sink = { code, value -> println("${now()} key=$code value=$value") },
            onKeyboards = { names -> println("${now()} keyboards=$names") },
            onNodeLost = { println("${now()} node lost") },
        )
        reader.start()
        reader.setGrab(true)
        Thread.sleep(seconds * 1000)
    }
```
(`keyboards.forEach { println(...) }` satırı ve kullanılmayan importlar (`Keyboard`, `ByteBuffer`, `ByteOrder`, `thread`) kaldırılır.)

- [ ] **Step 9: Cihazda doğrula (controller kullanıcıyla)**

Prototipi durdur, derle, kur ve 120 sn grab'li çalıştır:
```
& $adb shell 'kill $(pidof kbdfwd)'
.\android\gradlew.bat -p android :app:assembleDebug; & $adb install -r android\app\build\outputs\apk\debug\app-debug.apk
& $adb shell 'APK=$(pm path dev.keyrelay | cut -d: -f2); LIB=$(dirname $APK)/lib/arm64/libkeyrelay.so; setsid sh -c "CLASSPATH=$APK nohup app_process /system/bin --nice-name=keyrelay-helper dev.keyrelay.helper.Main --lib=$LIB --grab=120 > /data/local/tmp/grab.log 2>&1 < /dev/null &"'
```
Kullanıcı: (1) Notes'ta harf ve ses tuşu basar → tablette etki yok; (2) Ctrl'i basılı tutarken klavyeyi kapatır, 5 sn sonra açar; (3) tekrar harf basar → tablette yine etki yok. Sonra: `& $adb shell cat /data/local/tmp/grab.log`.
Expected: `keyboards=[MX Keys Mini Keyboard]` (dört düğüm açılır), kapatınca dört `read ... ended: -19` ve `node lost` (Ctrl'in `value=0` satırının gelip gelmediğini ölçüm belgesine yaz: gelmiyorsa T9'daki `releaseHeld` tek kurtarıcıdır), açınca yeni `keyboards=` satırı ve (3)'teki `key=` satırları. (3)'te tablete harf giderse inotify bildirimi gelmemiştir (SELinux `dir watch` reddi olabilir: `& $adb logcat -d | Select-String avc`): **dur**, controller kullanıcıya iki seçenek sunar — A: `ProcessObserver` olaylarında yeniden tarama (yoklamasız, gecikmeli), B: 2 sn'lik `/dev/input` yoklaması (spec "yoklama yok" kararından sapma).

- [ ] **Step 10: Commit**

```
& $git add android/app/src/main/cpp/evdev.c android/core/src/main/kotlin/dev/keyrelay/core/KeyboardGrouping.kt android/core/src/test/kotlin/dev/keyrelay/core/KeyboardGroupingTest.kt android/app/src/main/kotlin/dev/keyrelay/helper/DeviceScanner.kt android/app/src/main/kotlin/dev/keyrelay/helper/InputReader.kt android/app/src/main/kotlin/dev/keyrelay/helper/Main.kt
& $git commit -m "feat(android): klavye kapatılıp açılınca yeniden yakalanır" -m "- Bluetooth klavye uykudan dönünce ya da yeniden takılınca kendiliğinden yakalanır
- klavyeye bağlı touchpad yakalanmaz, tablette çalışmaya devam eder
- açılamayan giriş aygıtları kayda yazılır" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Yardımcı IPC — AIDL, attach, ölüm takibi, arayan denetimi, yardımcı süreç

T1 D2 "dur" çıktıysa tohumlama kuralı kararı bu görevden önce verilir. Yardımcının teşhis modları (`--grab`, `--watch-foreground`) ve gizli `getPackageUid` kullanımı bu görevde kalkar.

**Files:**
- Modify: `android/app/build.gradle.kts` (aidl, JVM testleri; katalogdaki mevcut `junit` girdileri kullanılır)
- Create: `android/app/src/main/aidl/dev/keyrelay/helper/IKeyRelayHelper.aidl`, `IStatusListener.aidl`, `RelayConfig.aidl`, `HelperStatus.aidl`
- Create: `android/app/src/main/kotlin/dev/keyrelay/helper/Helper.kt`
- Modify: `android/app/src/main/kotlin/dev/keyrelay/helper/HiddenApi.kt`, `Main.kt`
- Create: `android/app/src/main/kotlin/dev/keyrelay/service/AppState.kt`, `HelperConnection.kt`, `HelperAttachProvider.kt`
- Modify: `android/app/src/main/AndroidManifest.xml` (provider), `android/app/src/main/kotlin/dev/keyrelay/launcher/HelperLauncher.kt` (argüman)
- Test: `android/app/src/test/kotlin/dev/keyrelay/service/RelayStatusTest.kt`

**Interfaces:**
- Consumes: T4 `ForegroundTracker`, `RelayStateMachine`; T5 `RelayClient`, `ConnectionConfig`; T3 `Endpoint`; T6 `ShellOutput.COMMAND_TIMEOUT_MS`; T7 `StartupState`; T8 `InputReader`, `KeySink`.
- Produces:
  - AIDL `IKeyRelayHelper { configure(in RelayConfig); setEnabled(boolean); setStatusListener(IStatusListener); int ping() }`, `oneway IStatusListener { onStatus(in HelperStatus) }`, yapılandırılmış parcelable'lar `RelayConfig` (alanlar: `String[] endpoints`, `byte[] spkiSha256`, `byte[] deviceId`, `byte[] token`, `int[] targetUids`, `int toggleKey`, `long pingIntervalMs`, `long pongTimeoutMs`, `long sendTimeoutMs`, `boolean enabled`) ve `HelperStatus` (`configured`, `connected`, `pcReady`, `pcName`, `endpoint`, `targetForeground`, `enabled`, `overriding`, `grabbed`, `keyboards`, `lastError`, `rttMs`, `rejectReason`).
  - `object AppState { val relay: MutableStateFlow<RelayStatus>; val startup: MutableStateFlow<StartupState>; val scope: CoroutineScope }`; `data class RelayStatus(...)` + `fun HelperStatus.toRelayStatus(): RelayStatus`.
  - `object HelperConnection` — `val appToken: IBinder`, `val helper: IKeyRelayHelper?`, `val generation: StateFlow<Int>`, `var onAttached: () -> Unit`, `var onDied: (String) -> Unit`, `fun attach(binder: IBinder)`, `fun isAlive(): Boolean`, `fun configure(config: RelayConfig)`, `fun setEnabled(on: Boolean)`.
  - `class HelperAttachProvider` — `AUTHORITY = "dev.keyrelay.helper"`, `METHOD_ATTACH = "attach"`, `KEY_BINDER = "binder"`.
  - Yardımcı komut satırı: `--lib=<so> --app-uid=<uid>`.

- [ ] **Step 1: Derleme ayarı ve AIDL**

`app/build.gradle.kts`'de `buildFeatures { compose = true }` → `buildFeatures { compose = true; aidl = true }`; `android { }` içine:
```kotlin
    testOptions { unitTests.all { it.useJUnitPlatform() } }
```
`dependencies`'e `testImplementation(libs.junit.jupiter)` ve `testRuntimeOnly(libs.junit.platform.launcher)` ekle.

`IKeyRelayHelper.aidl`:
```aidl
package dev.keyrelay.helper;

import dev.keyrelay.helper.IStatusListener;
import dev.keyrelay.helper.RelayConfig;

interface IKeyRelayHelper {
    void configure(in RelayConfig config);
    void setEnabled(boolean enabled);
    void setStatusListener(IStatusListener listener);
    int ping();
}
```
`IStatusListener.aidl`:
```aidl
package dev.keyrelay.helper;

import dev.keyrelay.helper.HelperStatus;

oneway interface IStatusListener {
    void onStatus(in HelperStatus status);
}
```
`RelayConfig.aidl`:
```aidl
package dev.keyrelay.helper;

parcelable RelayConfig {
    String[] endpoints;
    byte[] spkiSha256;
    byte[] deviceId;
    byte[] token;
    int[] targetUids;
    int toggleKey;
    long pingIntervalMs;
    long pongTimeoutMs;
    long sendTimeoutMs;
    boolean enabled;
}
```
`HelperStatus.aidl`:
```aidl
package dev.keyrelay.helper;

parcelable HelperStatus {
    boolean configured;
    boolean connected;
    boolean pcReady;
    String pcName;
    String endpoint;
    boolean targetForeground;
    boolean enabled = true;
    boolean overriding;
    boolean grabbed;
    String[] keyboards;
    String lastError;
    int rttMs = -1;
    int rejectReason;
}
```
Run: `.\android\gradlew.bat -p android :app:compileDebugAidl`
Expected: `BUILD SUCCESSFUL`. (AGP yapılandırılmış parcelable'ı derlemezse **dur**: controller'a bildir; yedek `Bundle` ile taşımaktır ve plan değişikliği gerektirir.)

- [ ] **Step 2: Failing test**

`RelayStatusTest.kt`:
```kotlin
package dev.keyrelay.service

import dev.keyrelay.helper.HelperStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RelayStatusTest {
    @Test
    fun toRelayStatus_unsetStringsAndArrays_becomeEmpty() {
        val s = HelperStatus().toRelayStatus()
        assertTrue(s.attached)
        assertEquals("", s.pcName)
        assertEquals("", s.lastError)
        assertEquals(emptyList<String>(), s.keyboards)
        assertEquals(-1, s.rttMs)
        assertTrue(s.enabled)
    }

    @Test
    fun toRelayStatus_copiesFlagsAndLists() {
        val h = HelperStatus().apply {
            configured = true
            connected = true
            grabbed = true
            overriding = true
            keyboards = arrayOf("MX Keys Mini Keyboard")
            rejectReason = 2
        }
        val s = h.toRelayStatus()
        assertEquals(listOf(true, true, true, true), listOf(s.configured, s.connected, s.grabbed, s.override))
        assertEquals(listOf("MX Keys Mini Keyboard"), s.keyboards)
        assertEquals(2, s.rejectReason)
    }
}
```

- [ ] **Step 3: Çalıştır, başarısız olduğunu gör**

Run: `.\android\gradlew.bat -p android :app:testDebugUnitTest --tests "dev.keyrelay.service.RelayStatusTest"`
Expected: derleme hatası (`toRelayStatus` yok).

- [ ] **Step 4: `AppState.kt`**

```kotlin
package dev.keyrelay.service

import android.util.Log
import dev.keyrelay.core.StartupState
import dev.keyrelay.helper.HelperStatus
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/** What the helper last reported; [attached] is false while no helper binder is held. */
data class RelayStatus(
    val attached: Boolean = false,
    val configured: Boolean = false,
    val connected: Boolean = false,
    val pcReady: Boolean = false,
    val pcName: String = "",
    val endpoint: String = "",
    val targetForeground: Boolean = false,
    val enabled: Boolean = true,
    val override: Boolean = false,
    val grabbed: Boolean = false,
    val keyboards: List<String> = emptyList(),
    val lastError: String = "",
    val rttMs: Int = -1,
    val rejectReason: Int = 0,
)

fun HelperStatus.toRelayStatus() = RelayStatus(
    attached = true,
    configured = configured,
    connected = connected,
    pcReady = pcReady,
    pcName = pcName.orEmpty(),
    endpoint = endpoint.orEmpty(),
    targetForeground = targetForeground,
    enabled = enabled,
    override = overriding,
    grabbed = grabbed,
    keyboards = keyboards?.toList().orEmpty(),
    lastError = lastError.orEmpty(),
    rttMs = rttMs,
    rejectReason = rejectReason,
)

/** Process-wide state shared by the service, the screens, the tile and the notification. */
object AppState {
    val relay = MutableStateFlow(RelayStatus())
    val startup = MutableStateFlow<StartupState>(StartupState.Idle)

    /** For writes that must outlive a screen, a tile click or a receiver call. */
    // An uncaught failure (DataStore disk error) must be logged, not crash the process and loop the sticky service.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e -> Log.e("KeyRelay", "uncaught", e) })
}
```

- [ ] **Step 5: Testlerin geçtiğini gör**

Run: `.\android\gradlew.bat -p android :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Uygulama tarafı — provider ve bağlantı**

`HelperAttachProvider.kt`:
```kotlin
package dev.keyrelay.service

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle

/**
 * The shell-uid helper calls "attach" here through getContentProviderExternal (Shizuku pattern) and gets the app's
 * token back. The manifest permission keeps other apps out; the uid check keeps out everything but the shell.
 */
class HelperAttachProvider : ContentProvider() {
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_ATTACH || Binder.getCallingUid() != SHELL_UID) return null
        val binder = extras?.getBinder(KEY_BINDER) ?: return null
        HelperConnection.attach(binder)
        return Bundle().apply { putBinder(KEY_BINDER, HelperConnection.appToken) }
    }

    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = "dev.keyrelay.helper"
        const val METHOD_ATTACH = "attach"
        const val KEY_BINDER = "binder"
        private const val SHELL_UID = 2000
    }
}
```

`HelperConnection.kt`:
```kotlin
package dev.keyrelay.service

import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import dev.keyrelay.helper.HelperStatus
import dev.keyrelay.helper.IKeyRelayHelper
import dev.keyrelay.helper.IStatusListener
import dev.keyrelay.helper.RelayConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The app's end of the helper binder: attach, death, status updates and calls into the helper. */
object HelperConnection {
    private const val TAG = "KeyRelayHelperLink"

    /** Given to the helper on attach; when this process dies the helper sees it and exits (grab released). */
    val appToken: IBinder = Binder()

    @Volatile var helper: IKeyRelayHelper? = null
        private set

    private val _generation = MutableStateFlow(0)
    /** Grows by one on every attach; the start-up flow waits for it to pass the value it saw before starting. */
    val generation: StateFlow<Int> = _generation.asStateFlow()

    var onAttached: () -> Unit = {}
    var onDied: (lastError: String) -> Unit = {}
    private val main = Handler(Looper.getMainLooper())

    private val statusListener = object : IStatusListener.Stub() {
        override fun onStatus(status: HelperStatus) {
            AppState.relay.value = status.toRelayStatus()
        }
    }

    /** Binder thread: called by [HelperAttachProvider]. */
    fun attach(binder: IBinder) {
        val h = IKeyRelayHelper.Stub.asInterface(binder)
        try {
            binder.linkToDeath({ main.post { died(h) } }, 0)
        } catch (e: RemoteException) {
            return
        }
        helper = h
        runCatching { h.setStatusListener(statusListener) }
        main.post {
            _generation.value += 1
            Log.i(TAG, "helper attached generation=${_generation.value}")
            onAttached()
        }
    }

    private fun died(h: IKeyRelayHelper) {
        if (helper?.asBinder() !== h.asBinder()) return
        helper = null
        val lastError = AppState.relay.value.lastError
        AppState.relay.value = RelayStatus()
        Log.i(TAG, "helper died: $lastError")
        onDied(lastError)
    }

    fun isAlive() = helper?.asBinder()?.pingBinder() == true

    fun configure(config: RelayConfig) {
        runCatching { helper?.configure(config) }.onFailure { Log.w(TAG, "configure failed", it) }
    }

    fun setEnabled(on: Boolean) {
        runCatching { helper?.setEnabled(on) }.onFailure { Log.w(TAG, "setEnabled failed", it) }
    }
}
```

Manifest `<application>` içine:
```xml
        <provider
            android:name=".service.HelperAttachProvider"
            android:authorities="dev.keyrelay.helper"
            android:exported="true"
            android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />
```

- [ ] **Step 7: Yardımcı tarafı — `HiddenApi`**

`HiddenApi.kt`'yi şununla değiştir (`uidsForPackages` kalkar; `topAppPids` yol yoksa boş döner — P1 ertelenmiş takip):
```kotlin
package dev.keyrelay.helper

import android.content.AttributionSource
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import dev.keyrelay.service.HelperAttachProvider
import java.io.File

/** Reflection over hidden framework APIs. Only valid in a shell-uid app_process. */
object HiddenApi {
    private const val SHELL_PACKAGE = "com.android.shell"
    private const val TOP_APP_PROCS = "/dev/cpuset/top-app/cgroup.procs"
    private val iActivityManager = Class.forName("android.app.IActivityManager")
    private val iContentProvider = Class.forName("android.content.IContentProvider")

    private fun activityManager(): Any = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!

    fun registerProcessObserver(binder: IBinder) {
        val iface = Class.forName("android.app.IProcessObserver")
        val proxy = Class.forName("android.app.IProcessObserver\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        iActivityManager.getMethod("registerProcessObserver", iface).invoke(activityManager(), proxy)
    }

    /**
     * Calls the app's provider "attach" with [binder] and returns the app's token binder.
     * IContentProvider.call is (callingPkg, attributionTag, authority, method, arg, extras) on API 30 and
     * (AttributionSource, authority, method, arg, extras) from API 31 (AOSP android11/12/14/16-release).
     */
    fun attach(authority: String, binder: IBinder, userId: Int): IBinder? {
        val am = activityManager()
        val holder = iActivityManager
            .getMethod("getContentProviderExternal", String::class.java, Int::class.javaPrimitiveType, IBinder::class.java, String::class.java)
            .invoke(am, authority, userId, null, authority) ?: return null
        try {
            val provider = holder.javaClass.getField("provider").get(holder)
            val extras = Bundle().apply { putBinder(HelperAttachProvider.KEY_BINDER, binder) }
            val reply = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val source = AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PACKAGE).build()
                iContentProvider
                    .getMethod("call", AttributionSource::class.java, String::class.java, String::class.java, String::class.java, Bundle::class.java)
                    .invoke(provider, source, authority, HelperAttachProvider.METHOD_ATTACH, null, extras)
            } else {
                iContentProvider
                    .getMethod("call", String::class.java, String::class.java, String::class.java, String::class.java, String::class.java, Bundle::class.java)
                    .invoke(provider, SHELL_PACKAGE, null, authority, HelperAttachProvider.METHOD_ATTACH, null, extras)
            } as Bundle?
            return reply?.getBinder(HelperAttachProvider.KEY_BINDER)
        } finally {
            iActivityManager
                .getMethod("removeContentProviderExternalAsUser", String::class.java, IBinder::class.java, Int::class.javaPrimitiveType)
                .invoke(am, authority, null, userId)
        }
    }

    /** pid -> uid of processes Android currently shows on screen; seeds state before the first observer event. */
    fun topAppPids(): Map<Int, Int> {
        val procs = File(TOP_APP_PROCS)
        if (!procs.canRead()) return emptyMap()
        return procs.readLines().mapNotNull { line ->
            val pid = line.trim().toIntOrNull() ?: return@mapNotNull null
            val uid = runCatching {
                File("/proc/$pid/status").readLines().first { it.startsWith("Uid:") }.split(Regex("\\s+"))[1].toInt()
            }.getOrNull() ?: return@mapNotNull null
            pid to uid
        }.toMap()
    }
}
```

- [ ] **Step 8: Yardımcı tarafı — `Helper.kt`**

```kotlin
package dev.keyrelay.helper

import android.os.Binder
import android.os.IBinder
import android.os.Process
import dev.keyrelay.core.ConnectionConfig
import dev.keyrelay.core.Endpoint
import dev.keyrelay.core.ForegroundTracker
import dev.keyrelay.core.RelayClient
import dev.keyrelay.core.RelayStateMachine
import dev.keyrelay.core.ShellOutput
import dev.keyrelay.service.HelperAttachProvider
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/** The helper process: owns the keyboard, the PC connection and the routing state machine. */
class Helper(private val appUid: Int) : IKeyRelayHelper.Stub(), RelayStateMachine.Output {
    // Lock order: lock, then InputReader's monitor; InputReader never calls back while holding its own.
    private val lock = Any()
    private val machine = RelayStateMachine(this)
    private val input = InputReader(
        sink = { code, value -> synchronized(lock) { machine.onKey(code, value) } },
        onKeyboards = ::onKeyboards,
        onNodeLost = { synchronized(lock) { machine.releaseHeld() } },
    )
    private var tracker = ForegroundTracker { false }
    private var targetUids = IntArray(0)
    private var connection: ConnectionConfig? = null
    private var client: RelayClient? = null
    private var clientGeneration = 0
    @Volatile private var statusListener: IStatusListener? = null
    private val status = HelperStatus()

    private val observer = ProcessObserverBinder(object : ProcessObserverBinder.Listener {
        override fun onForegroundActivitiesChanged(pid: Int, uid: Int, foreground: Boolean) = synchronized(lock) {
            if (tracker.onForegroundActivitiesChanged(pid, uid, foreground)) logForeground()
            machine.onForeground(tracker.targetForeground, tracker.isTarget(uid))
        }

        override fun onProcessDied(pid: Int, uid: Int) = synchronized(lock) {
            if (tracker.onProcessDied(pid)) logForeground()
            machine.onForeground(tracker.targetForeground, tracker.isTarget(uid))
        }
    })

    fun start() {
        val token = attachToApp() ?: run {
            println("${now()} attach to app failed")
            exitProcess(EXIT_ATTACH)
        }
        token.linkToDeath({
            println("${now()} app died")
            exitProcess(0)
        }, 0)
        HiddenApi.registerProcessObserver(observer)
        input.start()
    }

    fun reportFatal(e: Throwable) = publish { lastError = e.toString() }

    // A helper that cannot hand over its binder in time counts as not started (spec "Başlatma" step 1).
    private fun attachToApp(): IBinder? {
        var token: IBinder? = null
        val t = thread(name = "attach") {
            token = runCatching { HiddenApi.attach(HelperAttachProvider.AUTHORITY, this, appUid / PER_USER_RANGE) }
                .onFailure { println("${now()} attach error: $it") }
                .getOrNull()
        }
        t.join(ShellOutput.COMMAND_TIMEOUT_MS)
        return token
    }

    override fun configure(config: RelayConfig) {
        checkCaller()
        val next = config.endpoints?.takeIf { it.isNotEmpty() }?.let { endpoints ->
            ConnectionConfig(
                endpoints.map { Endpoint.parse(it, 0) }, config.spkiSha256, config.deviceId, config.token,
                config.pingIntervalMs, config.pongTimeoutMs, config.sendTimeoutMs,
            )
        }
        synchronized(lock) {
            val uids = config.targetUids ?: IntArray(0)
            // Re-seeding on every settings write would throw away the event history the rule depends on.
            if (!uids.contentEquals(targetUids)) {
                targetUids = uids
                val set = uids.toSet()
                tracker = ForegroundTracker { it in set }.apply { seed(HiddenApi.topAppPids()) }
                logForeground()
            }
            if (config.toggleKey in RelayStateMachine.TOGGLE_KEYS) machine.toggleKey = config.toggleKey
            machine.setEnabled(config.enabled)
            machine.onForeground(tracker.targetForeground, targetEvent = false)
            val current = connection
            if (next == null || current == null || !next.sameConnection(current)) restartClient(next)
            publish { configured = true; enabled = config.enabled }
        }
    }

    override fun setEnabled(enabled: Boolean) {
        checkCaller()
        synchronized(lock) { machine.setEnabled(enabled) }
    }

    override fun setStatusListener(listener: IStatusListener?) {
        checkCaller()
        statusListener = listener
        publish { }
    }

    override fun ping(): Int {
        checkCaller()
        return Process.myPid()
    }

    private fun checkCaller() {
        val caller = Binder.getCallingUid()
        if (caller != appUid) throw SecurityException("caller uid $caller is not the KeyRelay app")
    }

    private fun restartClient(next: ConnectionConfig?) {
        client?.stop()
        client = null
        machine.setConnected(false)
        machine.setPcReady(false)
        connection = next
        val generation = ++clientGeneration
        if (next != null) client = RelayClient(next, ClientEvents(generation)).also { it.start() }
        publish { connected = false; pcReady = false; rejectReason = 0 }
    }

    override fun sendKey(code: Int, value: Int) {
        client?.sendKey(code, value)
    }

    override fun sendSas() {
        client?.sendSas()
    }

    override fun sendLock() {
        client?.sendLock()
    }

    override fun setGrab(on: Boolean) {
        input.setGrab(on)
        println("${now()} grab=$on")
        publish { grabbed = on }
    }

    override fun onToggled(enabled: Boolean, override: Boolean) = publish {
        this.enabled = enabled
        overriding = override
    }

    private fun onKeyboards(names: List<String>) = publish { keyboards = names.toTypedArray() }

    private fun logForeground() {
        println("${now()} foreground=${tracker.targetForeground}")
        publish { targetForeground = tracker.targetForeground }
    }

    private fun publish(update: HelperStatus.() -> Unit) {
        synchronized(status) {
            status.update()
            // A dead app is handled by linkToDeath; a failed oneway call needs nothing more.
            runCatching { statusListener?.onStatus(status) }
        }
    }

    /** Ignores callbacks of a client that a newer configure already replaced. */
    private inner class ClientEvents(private val generation: Int) : RelayClient.Listener {
        private fun current() = generation == clientGeneration

        override fun onConnected(pcName: String, endpoint: Endpoint) {
            synchronized(lock) {
                if (!current()) return
                machine.setConnected(true)
                println("${now()} connected $endpoint")
                publish { connected = true; this.pcName = pcName; this.endpoint = endpoint.toString(); rejectReason = 0; lastError = "" }
            }
        }

        override fun onPcReady(ready: Boolean) {
            synchronized(lock) {
                if (!current()) return
                machine.setPcReady(ready)
                publish { pcReady = ready }
            }
        }

        override fun onDisconnected(reason: String) {
            synchronized(lock) {
                if (!current()) return
                machine.setConnected(false)
                machine.setPcReady(false)
                println("${now()} disconnected: $reason")
                publish { connected = false; pcReady = false; lastError = reason }
            }
        }

        override fun onRejected(reason: Int) {
            synchronized(lock) {
                if (!current()) return
                machine.setConnected(false)
                println("${now()} rejected: $reason")
                publish { connected = false; rejectReason = reason }
            }
        }

        override fun onRtt(ms: Int) {
            if (current()) publish { rttMs = ms }
        }
    }

    private companion object {
        const val EXIT_ATTACH = 2
        const val PER_USER_RANGE = 100_000

        fun now() = System.currentTimeMillis()
    }
}
```

- [ ] **Step 9: Yardımcı giriş noktası ve P1 başlatıcısı**

`Main.kt`'yi şununla değiştir (teşhis modları kalkar):
```kotlin
package dev.keyrelay.helper

import kotlin.system.exitProcess

/** Entry point when started as `app_process ... dev.keyrelay.helper.Main --lib=... --app-uid=...` under the shell uid. */
object Main {
    private const val EXIT_CRASH = 1

    @JvmStatic
    fun main(args: Array<String>) {
        val opts = args.associate { it.substringBefore('=') to it.substringAfter('=', "") }
        Evdev.load(opts["--lib"] ?: error("--lib=<path to libkeyrelay.so> is required"))
        val helper = Helper(opts["--app-uid"]?.toIntOrNull() ?: error("--app-uid=<uid> is required"))
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            e.printStackTrace()
            helper.reportFatal(e)
            exitProcess(EXIT_CRASH)
        }
        helper.start()
        println("${System.currentTimeMillis()} helper ready")
        Thread.sleep(Long.MAX_VALUE)
    }
}
```
`HelperLauncher.kt`'de `listOf(TARGET_ARG)` → `listOf("--app-uid=${android.os.Process.myUid()}")`; `TARGET_ARG` sabitini sil.

- [ ] **Step 10: Derle ve testler**

Run: `.\android\gradlew.bat -p android :core:test :app:testDebugUnitTest :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 11: Cihazda doğrula (controller kullanıcıyla)**

1. Kur; uygulamayı aç (P1 hata ayıklama ekranı); kablosuz hata ayıklama açık; "Şimdi başlat".
2. `& $adb shell 'head -n 5 /data/local/tmp/keyrelay-helper.log'` → `IProcessObserver codes from platform stub: foreground=1 died=3` ve `helper ready`; `& $adb logcat -d -s KeyRelayHelperLink` → `helper attached generation=1`.
3. Uygulama ölünce yardımcı kapanır: `& $adb shell am force-stop dev.keyrelay; sleep 1; pidof keyrelay-helper` → boş; logda `app died`.
4. Yardımcı ölünce uygulama görür: uygulamayı aç, "Şimdi başlat", sonra `& $adb shell 'kill $(pidof keyrelay-helper)'` → logcat'te `helper died`.
5. Arayan denetimi: `& $adb shell 'service list | grep -i keyrelay'` boş (yardımcı servis yöneticisine kayıtlı değil; binder'ına yalnız uygulama ulaşır) — bu kanıt yeterli; yanlış uid ile çağrı denemesi cihazda yapılmaz.
Sonuçları T12'de yazılacak `docs/spikes/2026-10-p2-device-results.md`'nin "T9" bölümü için not al.

- [ ] **Step 12: Commit**

```
& $git add android/app/build.gradle.kts android/app/src/main/aidl android/app/src/main/kotlin/dev/keyrelay/helper/Helper.kt android/app/src/main/kotlin/dev/keyrelay/helper/HiddenApi.kt android/app/src/main/kotlin/dev/keyrelay/helper/Main.kt android/app/src/main/kotlin/dev/keyrelay/service android/app/src/main/AndroidManifest.xml android/app/src/main/kotlin/dev/keyrelay/launcher/HelperLauncher.kt android/app/src/test/kotlin/dev/keyrelay/service/RelayStatusTest.kt
& $git commit -m "feat(android): uygulama ile yardımcı süreç birbirine güvenle bağlanır" -m "- yardımcı süreç açılınca uygulamaya kendini tanıtır, yalnız uygulamanın komutlarını kabul eder
- uygulama kapanırsa yardımcı da kapanır ve klavye tablete döner
- hedef uygulamalar Android 11 ve 12'de de doğru tanınır" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: Ayarlar ve gizli saklama

**Files:**
- Modify: `android/gradle/libs.versions.toml`, `android/app/build.gradle.kts` (DataStore)
- Create: `android/app/src/main/kotlin/dev/keyrelay/settings/AppSettings.kt`, `SettingsStore.kt`, `TokenCipher.kt`
- Create: `android/app/src/main/res/xml/data_extraction_rules.xml`
- Modify: `android/app/src/main/AndroidManifest.xml` (yedekleme kapalı, `<queries>`)
- Test: `android/app/src/test/kotlin/dev/keyrelay/settings/AppSettingsTest.kt`

**Interfaces:**
- Consumes: T3 `PairedPc`, `PairedPcCodec`; T4 `RelayStateMachine.KEY_SPACE`, `TOGGLE_KEYS`; T5 `ConnectionConfig.DEFAULT_*`.
- Produces:
  - `data class AppSettings(pcs, targets, enabled, pingIntervalMs, pongTimeoutMs, sendTimeoutMs, toggleKey, onboardingDone)` + `fun writeTo(p: MutablePreferences)` + `companion fun from(p: Preferences): AppSettings` + `TEAMVIEWER`.
  - `object SettingsStore { fun flow(context): Flow<AppSettings>; suspend fun current(context): AppSettings; suspend fun update(context, transform: (AppSettings) -> AppSettings) }`.
  - `object TokenCipher { fun seal(plain: ByteArray): ByteArray; fun open(sealed: ByteArray): ByteArray }` (AES-GCM, Android Keystore anahtarı `keyrelay.token`; anahtar yoksa/çözülemezse istisna).

- [ ] **Step 1: Bağımlılık**

`libs.versions.toml`: `[versions]` `datastore = "1.2.1"`; `[libraries]` `datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }`. `app/build.gradle.kts`: `implementation(libs.datastore.preferences)`.

- [ ] **Step 2: Failing test**

`AppSettingsTest.kt`:
```kotlin
package dev.keyrelay.settings

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.keyrelay.core.ConnectionConfig
import dev.keyrelay.core.Endpoint
import dev.keyrelay.core.PairedPc
import dev.keyrelay.core.RelayStateMachine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AppSettingsTest {
    private fun pc() = PairedPc(ByteArray(16) { 1 }, "PC", listOf(Endpoint("10.0.0.1", 47111)), ByteArray(32), byteArrayOf(9), 5L, true)

    @Test
    fun from_emptyPreferences_returnsDefaults() = assertEquals(AppSettings(), AppSettings.from(emptyPreferences()))

    @Test
    fun writeTo_thenFrom_roundTrips() {
        val original = AppSettings(
            pcs = listOf(pc()), targets = setOf("a.b"), enabled = false, pingIntervalMs = 2_000, pongTimeoutMs = 5_000,
            sendTimeoutMs = 500, toggleKey = RelayStateMachine.KEY_GRAVE, onboardingDone = true,
        )
        val prefs = mutablePreferencesOf()
        original.writeTo(prefs)
        val back = AppSettings.from(prefs)
        assertEquals(original.copy(pcs = emptyList()), back.copy(pcs = emptyList()))
        assertEquals(listOf(pc().id), back.pcs.map { it.id })
    }

    @Test
    fun from_pongNotAbovePing_fallsBackToDefaults() {
        val prefs = mutablePreferencesOf()
        AppSettings(pingIntervalMs = 10_000, pongTimeoutMs = 10_000).writeTo(prefs)
        val s = AppSettings.from(prefs)
        assertEquals(ConnectionConfig.DEFAULT_PING_INTERVAL_MS, s.pingIntervalMs)
        assertEquals(ConnectionConfig.DEFAULT_PONG_TIMEOUT_MS, s.pongTimeoutMs)
    }

    @Test
    fun from_unknownToggleKey_fallsBackToSpace() {
        val prefs = mutablePreferencesOf()
        AppSettings(toggleKey = 30).writeTo(prefs)
        assertEquals(RelayStateMachine.KEY_SPACE, AppSettings.from(prefs).toggleKey)
    }

    @Test
    fun from_damagedPcEntry_keepsTheOthers() {
        val prefs = mutablePreferencesOf()
        AppSettings(pcs = listOf(pc())).writeTo(prefs)
        val key = stringPreferencesKey("pcs")
        prefs[key] = prefs[key] + "\nv1 garbage"
        assertEquals(1, AppSettings.from(prefs).pcs.size)
    }
}
```

- [ ] **Step 3: Çalıştır, başarısız olduğunu gör**

Run: `.\android\gradlew.bat -p android :app:testDebugUnitTest --tests "dev.keyrelay.settings.AppSettingsTest"`
Expected: derleme hatası (`AppSettings` yok).

- [ ] **Step 4: `AppSettings.kt`**

```kotlin
package dev.keyrelay.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dev.keyrelay.core.ConnectionConfig
import dev.keyrelay.core.PairedPc
import dev.keyrelay.core.PairedPcCodec
import dev.keyrelay.core.RelayStateMachine

data class AppSettings(
    val pcs: List<PairedPc> = emptyList(),
    val targets: Set<String> = setOf(TEAMVIEWER),
    val enabled: Boolean = true,
    val pingIntervalMs: Long = ConnectionConfig.DEFAULT_PING_INTERVAL_MS,
    val pongTimeoutMs: Long = ConnectionConfig.DEFAULT_PONG_TIMEOUT_MS,
    val sendTimeoutMs: Long = ConnectionConfig.DEFAULT_SEND_TIMEOUT_MS,
    val toggleKey: Int = RelayStateMachine.KEY_SPACE,
    val onboardingDone: Boolean = false,
) {
    fun writeTo(p: MutablePreferences) {
        p[PCS] = PairedPcCodec.encode(pcs)
        p[TARGETS] = targets
        p[ENABLED] = enabled
        p[PING] = pingIntervalMs
        p[PONG] = pongTimeoutMs
        p[SEND] = sendTimeoutMs
        p[TOGGLE] = toggleKey
        p[ONBOARDED] = onboardingDone
    }

    companion object {
        const val TEAMVIEWER = "com.teamviewer.teamviewer.market.mobile"
        private val PCS = stringPreferencesKey("pcs")
        private val TARGETS = stringSetPreferencesKey("targets")
        private val ENABLED = booleanPreferencesKey("enabled")
        private val PING = longPreferencesKey("ping_interval_ms")
        private val PONG = longPreferencesKey("pong_timeout_ms")
        private val SEND = longPreferencesKey("send_timeout_ms")
        private val TOGGLE = intPreferencesKey("toggle_key")
        private val ONBOARDED = booleanPreferencesKey("onboarding_done")

        /** Values the helper cannot use fall back to defaults: a bad pair of times would crash it on every start. */
        fun from(p: Preferences): AppSettings {
            val d = AppSettings()
            val ping = p[PING] ?: d.pingIntervalMs
            val pong = p[PONG] ?: d.pongTimeoutMs
            val timesValid = ping > 0 && pong > ping
            return AppSettings(
                pcs = PairedPcCodec.decode(p[PCS].orEmpty()),
                targets = p[TARGETS] ?: d.targets,
                enabled = p[ENABLED] ?: d.enabled,
                pingIntervalMs = if (timesValid) ping else d.pingIntervalMs,
                pongTimeoutMs = if (timesValid) pong else d.pongTimeoutMs,
                sendTimeoutMs = (p[SEND] ?: d.sendTimeoutMs).takeIf { it > 0 } ?: d.sendTimeoutMs,
                toggleKey = (p[TOGGLE] ?: d.toggleKey).takeIf { it in RelayStateMachine.TOGGLE_KEYS } ?: d.toggleKey,
                onboardingDone = p[ONBOARDED] ?: d.onboardingDone,
            )
        }
    }
}
```

- [ ] **Step 5: `SettingsStore.kt` ve `TokenCipher.kt`**

```kotlin
package dev.keyrelay.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** The app's settings in one DataStore file; every write transforms the whole [AppSettings]. */
object SettingsStore {
    fun flow(context: Context): Flow<AppSettings> = context.applicationContext.settingsDataStore.data.map(AppSettings::from)

    suspend fun current(context: Context): AppSettings = flow(context).first()

    suspend fun update(context: Context, transform: (AppSettings) -> AppSettings) {
        context.applicationContext.settingsDataStore.edit { prefs -> transform(AppSettings.from(prefs)).writeTo(prefs) }
    }
}
```
```kotlin
package dev.keyrelay.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts PC device tokens with a non-exportable Android Keystore key; output is IV followed by ciphertext. */
object TokenCipher {
    private const val ALIAS = "keyrelay.token"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128

    fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(plain)
    }

    /** Throws when the key is gone (backup restored on another device) or the data was altered. */
    fun open(sealed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_SIZE))
        return cipher.doFinal(sealed, IV_SIZE, sealed.size - IV_SIZE)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as SecretKey?)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
    }
}
```

- [ ] **Step 6: Yedekleme kapalı, uygulama görünürlüğü**

`res/xml/data_extraction_rules.xml` (adb anahtarı ve eşleştirme verisi başka cihaza taşınmasın):
```xml
<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="root" />
        <exclude domain="file" />
        <exclude domain="database" />
        <exclude domain="sharedpref" />
        <exclude domain="external" />
    </cloud-backup>
    <device-transfer>
        <exclude domain="root" />
        <exclude domain="file" />
        <exclude domain="database" />
        <exclude domain="sharedpref" />
        <exclude domain="external" />
    </device-transfer>
</data-extraction-rules>
```
Manifest `<application>` etiketine `android:allowBackup="false" android:fullBackupContent="false" android:dataExtractionRules="@xml/data_extraction_rules"` ekle; `<manifest>` altına (izinlerden sonra):
```xml
    <queries>
        <intent>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent>
    </queries>
```

- [ ] **Step 7: Testlerin geçtiğini gör**

Run: `.\android\gradlew.bat -p android :app:testDebugUnitTest :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```
& $git add android/gradle/libs.versions.toml android/app/build.gradle.kts android/app/src/main/kotlin/dev/keyrelay/settings android/app/src/main/res/xml/data_extraction_rules.xml android/app/src/main/AndroidManifest.xml android/app/src/test/kotlin/dev/keyrelay/settings/AppSettingsTest.kt
& $git commit -m "feat(android): ayarlar ve pc anahtarı cihazda şifreli saklanır" -m "- PC ile paylaşılan gizli anahtar cihazın güvenli donanımında şifrelenir
- uygulama verisi buluta ya da yeni cihaza yedeklenmez
- hatalı süre ayarları güvenli varsayılanlara döner" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: `RelayService` ve başlatma yapıştırıcısı — tek ön plan servisi, bildirim, açılışta başlatma

**Files:**
- Create: `android/app/src/main/kotlin/dev/keyrelay/service/AppStartupPorts.kt`, `RelayService.kt`, `Notifications.kt`, `StatusKind.kt`, `RelayConfigFactory.kt`
- Create: `android/app/src/main/kotlin/dev/keyrelay/launcher/AdbSetup.kt`
- Modify: `android/app/src/main/kotlin/dev/keyrelay/launcher/BootReceiver.kt`, `android/app/src/main/kotlin/dev/keyrelay/MainActivity.kt` (geçici hata ayıklama ekranı)
- Delete: `android/app/src/main/kotlin/dev/keyrelay/launcher/StartupService.kt`, `HelperLauncher.kt`, `NetworkApprovalProbe.kt`
- Create: `android/app/src/main/res/drawable/ic_stat_keyrelay.xml`, `android/app/src/main/res/values-tr/strings.xml`
- Modify: `android/app/src/main/res/values/strings.xml`, `android/app/src/main/AndroidManifest.xml`
- Test: `android/app/src/test/kotlin/dev/keyrelay/service/StatusKindTest.kt`, `AppStartupPortsTest.kt`, `RelayConfigFactoryTest.kt`

**Interfaces:**
- Consumes: T7 `StartupFlow`, `StartupPorts`, `AdbConnectResult`, `StartupState`, `StartupFailure`; T9 `HelperConnection`, `AppState`, `RelayStatus`, `RelayConfig`; T10 `SettingsStore`, `AppSettings`, `TokenCipher`; T6 `AdbManager.exec`, `ShellOutput`; P1 `HelperCommand`.
- Produces:
  - `enum class StatusKind { STARTING, NO_WIFI, APPROVAL, ERROR, NOT_PAIRED, REPAIR, PAUSED, CONNECTING, PC_NOT_READY, READY, RELAYING }`, `fun statusKind(relay: RelayStatus, startup: StartupState, hasPc: Boolean): StatusKind`.
  - `RelayService` — `ACTION_RETRY`, `ACTION_TOGGLE`; `companion fun start(context)`, `fun retry(context)`, `fun setEnabled(context, on: Boolean)`.
  - `object Notifications` — `ID`, `CHANNEL`, `data class Model(kind: StatusKind, detail: String)`, `fun model(context, relay, startup, hasPc): Model`, `fun titleRes(kind): Int`, `fun createChannel(context)`, `fun build(context, model): Notification`, `fun show(context, model)`.
  - `object RelayConfigFactory { fun build(s: AppSettings, uidOf: (String) -> Int?, openToken: (ByteArray) -> ByteArray): RelayConfig }`.
  - `internal fun classifyConnect(connect: () -> Boolean): AdbConnectResult`, `internal fun isNewNetwork(registeredOn: Long?, available: Long): Boolean` (AppStartupPorts.kt dosya düzeyinde).
  - `object AdbSetup { suspend fun pair(context, host, port, code): Boolean; suspend fun grantSecureSettings(context): Boolean }`.
  - `AppState.notice: MutableStateFlow<Notifications.Model?>` (kutucuk aynı dili kullansın diye; bu görevde `AppState`'e eklenir).

- [ ] **Step 1: Failing tests**

`StatusKindTest.kt`:
```kotlin
package dev.keyrelay.service

import dev.keyrelay.core.StartupFailure
import dev.keyrelay.core.StartupState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StatusKindTest {
    private val live = RelayStatus(attached = true, configured = true, connected = true, pcReady = true, pcName = "homepc")

    @Test
    fun statusKind_startupFailure_winsOverEverything() =
        assertEquals(StatusKind.ERROR, statusKind(live, StartupState.Failed(StartupFailure.HELPER_NOT_STARTED, ""), true))

    @Test
    fun statusKind_awaitingApproval_isApproval() = assertEquals(StatusKind.APPROVAL, statusKind(RelayStatus(), StartupState.AwaitingApproval, true))

    @Test
    fun statusKind_waitingForWifi_isNoWifi() = assertEquals(StatusKind.NO_WIFI, statusKind(RelayStatus(), StartupState.WaitingForWifi, true))

    @Test
    fun statusKind_noHelperYet_isStarting() = assertEquals(StatusKind.STARTING, statusKind(RelayStatus(), StartupState.Restarting(1, 5_000), true))

    @Test
    fun statusKind_noPc_isNotPaired() = assertEquals(StatusKind.NOT_PAIRED, statusKind(live, StartupState.Running, false))

    @Test
    fun statusKind_rejected_isRepair() = assertEquals(StatusKind.REPAIR, statusKind(live.copy(rejectReason = 2), StartupState.Running, true))

    @Test
    fun statusKind_disabled_isPausedEvenWhenGrabbedFlagLags() =
        assertEquals(StatusKind.PAUSED, statusKind(live.copy(enabled = false, grabbed = true), StartupState.Running, true))

    @Test
    fun statusKind_notConnected_isConnecting() = assertEquals(StatusKind.CONNECTING, statusKind(live.copy(connected = false), StartupState.Running, true))

    @Test
    fun statusKind_grabbed_isRelaying() = assertEquals(StatusKind.RELAYING, statusKind(live.copy(grabbed = true), StartupState.Running, true))

    @Test
    fun statusKind_pcNotReady_isPcNotReady() = assertEquals(StatusKind.PC_NOT_READY, statusKind(live.copy(pcReady = false), StartupState.Running, true))

    @Test
    fun statusKind_connectedIdle_isReady() = assertEquals(StatusKind.READY, statusKind(live, StartupState.Running, true))
}
```

`AppStartupPortsTest.kt`:
```kotlin
package dev.keyrelay.service

import dev.keyrelay.core.AdbConnectResult
import io.github.muntashirakon.adb.AdbPairingRequiredException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class AppStartupPortsTest {
    @Test
    fun classifyConnect_true_connected() = assertEquals(AdbConnectResult.CONNECTED, classifyConnect { true })

    @Test
    fun classifyConnect_falseFromConnectPhaseTimeout_notStarted() = assertEquals(AdbConnectResult.NOT_STARTED, classifyConnect { false })

    @Test
    fun classifyConnect_mdnsTimeoutOrEmptyAddress_notStarted() {
        assertEquals(AdbConnectResult.NOT_STARTED, classifyConnect { throw InterruptedException("mdns") })
        assertEquals(AdbConnectResult.NOT_STARTED, classifyConnect { throw IOException("no address") })
    }

    @Test
    fun classifyConnect_pairingLost_pairingRequired() =
        assertEquals(AdbConnectResult.PAIRING_REQUIRED, classifyConnect { throw AdbPairingRequiredException("tls") })

    @Test
    fun classifyConnect_programmingError_propagates() {
        assertThrows(IllegalStateException::class.java) { classifyConnect { throw IllegalStateException("bug") } }
    }

    @Test
    fun isNewNetwork_sameHandleReplayedOnRegister_isNotNew() = assertFalse(isNewNetwork(42L, 42L))

    @Test
    fun isNewNetwork_reconnectOrFirstNetwork_isNew() {
        assertTrue(isNewNetwork(42L, 43L))
        assertTrue(isNewNetwork(null, 43L))
    }
}
```

`RelayConfigFactoryTest.kt`:
```kotlin
package dev.keyrelay.service

import dev.keyrelay.core.Endpoint
import dev.keyrelay.core.PairedPc
import dev.keyrelay.settings.AppSettings
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.security.GeneralSecurityException

class RelayConfigFactoryTest {
    private val a = Endpoint("homepc.ts.net", 8443)
    private val b = Endpoint("192.168.1.10", 47111)
    private val pc = PairedPc(ByteArray(16) { 3 }, "homepc", listOf(a, b), ByteArray(32) { 4 }, byteArrayOf(9), 0L, true)
    private val uids = mapOf(AppSettings.TEAMVIEWER to 10290)

    @Test
    fun build_defaultPc_passesConnectionInOrder() {
        val c = RelayConfigFactory.build(AppSettings(pcs = listOf(pc)), uids::get) { byteArrayOf(1, 2) }
        assertEquals(listOf("homepc.ts.net:8443", "192.168.1.10:47111"), c.endpoints.toList())
        assertArrayEquals(byteArrayOf(1, 2), c.token)
        assertArrayEquals(pc.deviceId, c.deviceId)
        assertArrayEquals(intArrayOf(10290), c.targetUids)
    }

    @Test
    fun build_tokenCannotBeOpened_configuresNoConnection() {
        val c = RelayConfigFactory.build(AppSettings(pcs = listOf(pc)), uids::get) { throw GeneralSecurityException("key gone") }
        assertEquals(0, c.endpoints.size)
        assertEquals(0, c.token.size)
        assertArrayEquals(intArrayOf(10290), c.targetUids)
    }

    @Test
    fun build_missingPackage_skippedFromTargets() {
        val c = RelayConfigFactory.build(AppSettings(targets = setOf(AppSettings.TEAMVIEWER, "not.installed")), uids::get) { it }
        assertArrayEquals(intArrayOf(10290), c.targetUids)
    }

    @Test
    fun build_noPc_noEndpointsButSettingsPassed() {
        val c = RelayConfigFactory.build(AppSettings(enabled = false, pingIntervalMs = 2_000, pongTimeoutMs = 5_000), uids::get) { it }
        assertEquals(0, c.endpoints.size)
        assertEquals(false, c.enabled)
        assertEquals(2_000L, c.pingIntervalMs)
        assertEquals(5_000L, c.pongTimeoutMs)
    }
}
```

- [ ] **Step 2: Çalıştır, başarısız olduğunu gör**

Run: `.\android\gradlew.bat -p android :app:testDebugUnitTest`
Expected: derleme hatası (`statusKind`, `classifyConnect`, `isNewNetwork`, `RelayConfigFactory` yok).

- [ ] **Step 3: `StatusKind.kt` ve `RelayConfigFactory.kt`**

```kotlin
package dev.keyrelay.service

import dev.keyrelay.core.StartupState

/** One answer to "what is KeyRelay doing", shared by the status screen, the notification and the tile. */
enum class StatusKind { STARTING, NO_WIFI, APPROVAL, ERROR, NOT_PAIRED, REPAIR, PAUSED, CONNECTING, PC_NOT_READY, READY, RELAYING }

fun statusKind(relay: RelayStatus, startup: StartupState, hasPc: Boolean): StatusKind = when {
    startup is StartupState.Failed -> StatusKind.ERROR
    startup is StartupState.AwaitingApproval -> StatusKind.APPROVAL
    startup is StartupState.WaitingForWifi -> StatusKind.NO_WIFI
    !relay.attached -> StatusKind.STARTING
    !hasPc -> StatusKind.NOT_PAIRED
    relay.rejectReason != 0 -> StatusKind.REPAIR
    !relay.enabled -> StatusKind.PAUSED
    !relay.connected -> StatusKind.CONNECTING
    relay.grabbed -> StatusKind.RELAYING
    !relay.pcReady -> StatusKind.PC_NOT_READY
    else -> StatusKind.READY
}
```
```kotlin
package dev.keyrelay.service

import dev.keyrelay.core.Endpoint
import dev.keyrelay.core.defaultPc
import dev.keyrelay.helper.RelayConfig
import dev.keyrelay.settings.AppSettings

object RelayConfigFactory {
    /**
     * [uidOf] resolves packages with the public PackageManager (works on Android 11/12, unlike the hidden call).
     * [openToken] throws when the Keystore key is gone; that PC is then configured without a connection.
     */
    fun build(s: AppSettings, uidOf: (String) -> Int?, openToken: (ByteArray) -> ByteArray): RelayConfig {
        val pc = s.pcs.defaultPc
        val plainToken = pc?.let { runCatching { openToken(it.sealedToken) }.getOrNull() }
        val usable = if (plainToken != null) pc else null
        return RelayConfig().apply {
            endpoints = usable?.endpoints?.map(Endpoint::toString)?.toTypedArray() ?: emptyArray()
            spkiSha256 = usable?.spkiSha256 ?: ByteArray(0)
            deviceId = usable?.deviceId ?: ByteArray(0)
            token = plainToken ?: ByteArray(0)
            targetUids = s.targets.mapNotNull(uidOf).toIntArray()
            toggleKey = s.toggleKey
            pingIntervalMs = s.pingIntervalMs
            pongTimeoutMs = s.pongTimeoutMs
            sendTimeoutMs = s.sendTimeoutMs
            enabled = s.enabled
        }
    }
}
```

- [ ] **Step 4: `AppStartupPorts.kt`**

```kotlin
package dev.keyrelay.service

import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import dev.keyrelay.core.AdbConnectResult
import dev.keyrelay.core.HelperCommand
import dev.keyrelay.core.ShellOutput
import dev.keyrelay.core.StartupFlow
import dev.keyrelay.core.StartupPorts
import dev.keyrelay.launcher.AdbManager
import io.github.muntashirakon.adb.AdbPairingRequiredException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.coroutines.resume

/** Android side of [StartupFlow]. Called on the main thread. */
class AppStartupPorts(private val context: Context) : StartupPorts {
    private val adb get() = AdbManager.get(context)
    private val main = Handler(Looper.getMainLooper())
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val wifi = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
    private var generationAtStart = 0
    private var settingsObserver: ContentObserver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun hasSecureSettingsPermission() =
        context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    override suspend fun awaitWifi() {
        if (connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) return
        suspendCancellableCoroutine { cont ->
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    runCatching { connectivity.unregisterNetworkCallback(this) }
                    if (cont.isActive) cont.resume(Unit)
                }
            }
            connectivity.registerNetworkCallback(wifi, callback)
            cont.invokeOnCancellation { runCatching { connectivity.unregisterNetworkCallback(callback) } }
        }
    }

    override fun putGlobal(key: String, value: Int) {
        Settings.Global.putInt(context.contentResolver, key, value)
    }

    override suspend fun connectAdb(): AdbConnectResult = withContext(Dispatchers.IO) {
        // A session from an earlier start died when wireless debugging went off; never reuse it.
        runCatching { adb.disconnect() }
        classifyConnect { adb.autoConnect(context, 2 * ShellOutput.COMMAND_TIMEOUT_MS) }
    }

    override suspend fun startHelper() {
        generationAtStart = HelperConnection.generation.value
        val info = context.applicationInfo
        val start = HelperCommand.build(info.sourceDir, info.nativeLibraryDir, listOf("--app-uid=${Process.myUid()}"))
        // EVIOCGRAB is exclusive: a leftover helper would keep the keyboard and the new one could not grab it.
        val result = adb.exec("kill \$(pidof ${HelperCommand.NICE_NAME}) 2>/dev/null; $start")
        if (result.exitCode != 0) throw IOException("helper start command failed (${result.exitCode}): ${result.output}")
    }

    override suspend fun awaitAttach(timeoutMs: Long) =
        withTimeoutOrNull(timeoutMs) { HelperConnection.generation.first { it > generationAtStart } } != null

    override fun helperAlive() = HelperConnection.isAlive()

    override fun registerWakeups(onWake: () -> Unit) {
        settingsObserver = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) = onWake()
        }.also {
            context.contentResolver.registerContentObserver(Settings.Global.getUriFor(StartupFlow.ADB_WIFI_ENABLED), false, it)
        }
        val registeredOn = connectivity.activeNetwork?.networkHandle
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            // Registration replays the network we are already on; only a reconnect counts as a wake-up.
            override fun onAvailable(network: Network) {
                if (isNewNetwork(registeredOn, network.networkHandle)) main.post(onWake)
            }
        }.also { connectivity.registerNetworkCallback(wifi, it) }
    }

    override fun unregisterWakeups() {
        settingsObserver?.let { context.contentResolver.unregisterContentObserver(it) }
        networkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        settingsObserver = null
        networkCallback = null
    }

    override suspend fun helperLogTail(): String =
        runCatching { adb.exec("tail -n $LOG_LINES ${HelperCommand.LOG_PATH}").output }.getOrDefault("")

    private companion object {
        const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"
        const val LOG_LINES = 20
    }
}

/**
 * libadb's ways of failing (spec "Başlatma" step 2): false = connect phase timed out, InterruptedException = mDNS
 * window ran out (not a real interrupt, so the flag is not restored), IOException = no address or early end.
 */
internal fun classifyConnect(connect: () -> Boolean): AdbConnectResult = try {
    if (connect()) AdbConnectResult.CONNECTED else AdbConnectResult.NOT_STARTED
} catch (e: AdbPairingRequiredException) {
    AdbConnectResult.PAIRING_REQUIRED
} catch (e: InterruptedException) {
    AdbConnectResult.NOT_STARTED
} catch (e: IOException) {
    AdbConnectResult.NOT_STARTED
}

internal fun isNewNetwork(registeredOn: Long?, available: Long) = registeredOn != available
```

- [ ] **Step 5: Testlerin geçtiğini gör**

Run: `.\android\gradlew.bat -p android :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Metinler ve bildirim simgesi**

`res/values/strings.xml`:
```xml
<resources>
    <string name="app_name">KeyRelay</string>
    <string name="notification_channel">Keyboard relay</string>
    <string name="status_starting">Starting</string>
    <string name="status_no_wifi">Waiting for Wi-Fi</string>
    <string name="status_approval">Approval needed</string>
    <string name="status_error">Could not start</string>
    <string name="status_not_paired">No PC paired</string>
    <string name="status_repair">Pair again</string>
    <string name="status_paused">Paused</string>
    <string name="status_connecting">Connecting</string>
    <string name="status_pc_not_ready">PC not ready</string>
    <string name="status_ready">Ready</string>
    <string name="status_relaying">Relaying</string>
    <string name="detail_approval">If you are on a new network, choose Allow and Always allow in the system dialog</string>
    <string name="detail_relaying">%1$s · keys go to the PC</string>
    <string name="detail_ready">%1$s · keyboard on the tablet</string>
    <string name="detail_connecting">Looking for %1$s</string>
    <string name="detail_restarting">Restarting in %1$d s</string>
    <string name="failure_no_permission">Permission missing. Finish the setup.</string>
    <string name="failure_wireless_debugging">Wireless debugging did not start</string>
    <string name="failure_pairing">Renew the wireless debugging pairing</string>
    <string name="failure_helper">The helper did not start</string>
    <string name="failure_crash_loop">The helper keeps closing</string>
    <string name="action_retry">Retry</string>
    <string name="action_pause">Pause</string>
    <string name="action_resume">Resume</string>
</resources>
```
`res/values-tr/strings.xml`:
```xml
<resources>
    <string name="app_name">KeyRelay</string>
    <string name="notification_channel">Klavye yönlendirme</string>
    <string name="status_starting">Başlatılıyor</string>
    <string name="status_no_wifi">Wi-Fi bekleniyor</string>
    <string name="status_approval">Onay bekleniyor</string>
    <string name="status_error">Başlatılamadı</string>
    <string name="status_not_paired">PC eşlenmedi</string>
    <string name="status_repair">Yeniden eşleştirin</string>
    <string name="status_paused">Duraklatıldı</string>
    <string name="status_connecting">Bağlanıyor</string>
    <string name="status_pc_not_ready">PC hazır değil</string>
    <string name="status_ready">Hazır</string>
    <string name="status_relaying">Yönlendiriliyor</string>
    <string name="detail_approval">Yeni bir ağdaysanız sistem penceresinde İzin ver + Her zaman izin ver\'i seçin</string>
    <string name="detail_relaying">%1$s · tuşlar PC\'ye gidiyor</string>
    <string name="detail_ready">%1$s · klavye tablette</string>
    <string name="detail_connecting">%1$s aranıyor</string>
    <string name="detail_restarting">%1$d sn sonra yeniden başlatılıyor</string>
    <string name="failure_no_permission">İzin eksik. Kurulumu tamamlayın.</string>
    <string name="failure_wireless_debugging">Kablosuz hata ayıklama başlamadı</string>
    <string name="failure_pairing">Kablosuz hata ayıklama eşleştirmesini yenileyin</string>
    <string name="failure_helper">Yardımcı başlamadı</string>
    <string name="failure_crash_loop">Yardımcı tekrar tekrar kapanıyor</string>
    <string name="action_retry">Yeniden dene</string>
    <string name="action_pause">Duraklat</string>
    <string name="action_resume">Sürdür</string>
</resources>
```
(T1 D3 (c) "onayda `onChange` gelmiyor" çıktıysa `detail_approval` metinleri "… İzin ver + Her zaman izin ver'i seçin, sonra Yeniden dene'ye basın" / "…, then tap Retry" olur.)

`res/drawable/ic_stat_keyrelay.xml` (taslaktaki tematik işaret, tek renk):
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="64" android:viewportHeight="64">
    <path android:pathData="M14,14H28A8,8 0,0 1,36,22V36A8,8 0,0 1,28,44H14A8,8 0,0 1,6,36V22A8,8 0,0 1,14,14Z"
        android:strokeColor="#FFFFFFFF" android:strokeWidth="3.5" android:fillColor="#00000000" />
    <path android:pathData="M15,29H27M21,23V35" android:strokeColor="#FFFFFFFF" android:strokeWidth="3.5" android:strokeLineCap="round" />
    <path android:pathData="M40,30H58M52,23L59,30L52,37" android:strokeColor="#FFFFFFFF" android:strokeWidth="4"
        android:strokeLineCap="round" android:strokeLineJoin="round" />
</vector>
```

- [ ] **Step 7: `Notifications.kt`**

```kotlin
package dev.keyrelay.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import dev.keyrelay.MainActivity
import dev.keyrelay.R
import dev.keyrelay.core.StartupFailure
import dev.keyrelay.core.StartupState

/** The relay service's single notification (one channel, default importance, silent). */
object Notifications {
    const val ID = 1
    const val CHANNEL = "relay"
    private const val OLD_CHANNEL = "startup"

    /** Everything the notification shows; the latency is left out so a ping does not rebuild it. */
    data class Model(val kind: StatusKind, val detail: String)

    fun model(context: Context, relay: RelayStatus, startup: StartupState, hasPc: Boolean): Model {
        val kind = statusKind(relay, startup, hasPc)
        val detail = when (kind) {
            StatusKind.ERROR -> {
                val failed = startup as StartupState.Failed
                val lastLine = failed.detail.lineSequence().lastOrNull { it.isNotBlank() }
                context.getString(failureRes(failed.failure)) + (lastLine?.let { " · $it" } ?: "")
            }
            StatusKind.APPROVAL -> context.getString(R.string.detail_approval)
            StatusKind.STARTING -> (startup as? StartupState.Restarting)?.let { context.getString(R.string.detail_restarting, it.delayMs / 1000) }.orEmpty()
            StatusKind.RELAYING -> context.getString(R.string.detail_relaying, relay.pcName)
            StatusKind.READY, StatusKind.PC_NOT_READY -> context.getString(R.string.detail_ready, relay.pcName)
            StatusKind.CONNECTING -> context.getString(R.string.detail_connecting, relay.endpoint.ifEmpty { relay.pcName })
            else -> ""
        }
        return Model(kind, detail)
    }

    fun titleRes(kind: StatusKind) = when (kind) {
        StatusKind.STARTING -> R.string.status_starting
        StatusKind.NO_WIFI -> R.string.status_no_wifi
        StatusKind.APPROVAL -> R.string.status_approval
        StatusKind.ERROR -> R.string.status_error
        StatusKind.NOT_PAIRED -> R.string.status_not_paired
        StatusKind.REPAIR -> R.string.status_repair
        StatusKind.PAUSED -> R.string.status_paused
        StatusKind.CONNECTING -> R.string.status_connecting
        StatusKind.PC_NOT_READY -> R.string.status_pc_not_ready
        StatusKind.READY -> R.string.status_ready
        StatusKind.RELAYING -> R.string.status_relaying
    }

    fun failureRes(failure: StartupFailure) = when (failure) {
        StartupFailure.NO_PERMISSION -> R.string.failure_no_permission
        StartupFailure.WIRELESS_DEBUGGING_OFF -> R.string.failure_wireless_debugging
        StartupFailure.PAIRING_REQUIRED -> R.string.failure_pairing
        StartupFailure.HELPER_NOT_STARTED -> R.string.failure_helper
        StartupFailure.HELPER_CRASH_LOOP -> R.string.failure_crash_loop
    }

    fun createChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel(OLD_CHANNEL)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.notification_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
    }

    fun build(context: Context, m: Model): Notification {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val retry = m.kind == StatusKind.ERROR || m.kind == StatusKind.APPROVAL
        val action = if (retry) RelayService.ACTION_RETRY else RelayService.ACTION_TOGGLE
        val label = when {
            retry -> R.string.action_retry
            m.kind == StatusKind.PAUSED -> R.string.action_resume
            else -> R.string.action_pause
        }
        val pending = PendingIntent.getService(
            context, action.hashCode(), Intent(context, RelayService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_keyrelay)
            .setContentTitle(context.getString(titleRes(m.kind)))
            .setContentText(m.detail)
            .setStyle(Notification.BigTextStyle().bigText(m.detail))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stat_keyrelay), context.getString(label), pending).build())
            .build()
    }

    fun show(context: Context, m: Model) {
        context.getSystemService(NotificationManager::class.java).notify(ID, build(context, m))
    }
}
```
`AppState`'e ekle: `val notice = MutableStateFlow<Notifications.Model?>(null)`.

- [ ] **Step 8: `RelayService.kt`**

```kotlin
package dev.keyrelay.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import dev.keyrelay.core.Endpoint
import dev.keyrelay.core.StartupFlow
import dev.keyrelay.core.StartupState
import dev.keyrelay.core.defaultPc
import dev.keyrelay.core.promote
import dev.keyrelay.core.touch
import dev.keyrelay.settings.AppSettings
import dev.keyrelay.settings.SettingsStore
import dev.keyrelay.settings.TokenCipher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app's only foreground service (connectedDevice): owns the start-up flow, the helper link, the config push and
 * the single status notification.
 */
class RelayService : Service() {
    // Same boundary as AppState.scope: START_STICKY would replay a crash forever.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e -> Log.e("KeyRelay", "uncaught", e) })
    private var flow: StartupFlow? = null
    private val packageChanges = object : BroadcastReceiver() {
        // A reinstalled target app gets a new uid; resolve the targets again.
        override fun onReceive(context: Context, intent: Intent) {
            scope.launch { pushConfig(SettingsStore.current(this@RelayService)) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannel(this)
        if (!enterForeground()) {
            stopSelf()
            return
        }
        val startup = StartupFlow(AppStartupPorts(this), scope) { SystemClock.elapsedRealtime() }
        flow = startup
        HelperConnection.onAttached = {
            startup.onHelperAttached()
            scope.launch { pushConfig(SettingsStore.current(this@RelayService)) }
        }
        HelperConnection.onDied = startup::onHelperDied
        scope.launch { startup.state.collect { AppState.startup.value = it } }
        scope.launch { SettingsStore.flow(this@RelayService).collect { pushConfig(it) } }
        scope.launch { keepSettingsInSync() }
        scope.launch {
            combine(AppState.relay, AppState.startup, SettingsStore.flow(this@RelayService)) { relay, state, settings ->
                Notifications.model(this@RelayService, relay, state, settings.pcs.defaultPc != null)
            }.distinctUntilChanged().collect {
                AppState.notice.value = it
                Notifications.show(this@RelayService, it)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(packageChanges, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(packageChanges, filter)
        startup.onServiceStarted(HelperConnection.helper != null)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val startup = flow ?: return START_NOT_STICKY
        when (intent?.action) {
            ACTION_RETRY -> startup.onRetry()
            ACTION_TOGGLE -> setEnabled(this, !AppState.relay.value.enabled)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // Cancelling the scope runs the flow's cleanup: wireless debugging is switched off even here.
        scope.cancel()
        runCatching { unregisterReceiver(packageChanges) }
        HelperConnection.onAttached = {}
        HelperConnection.onDied = {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun enterForeground(): Boolean = try {
        val first = Notifications.model(this, AppState.relay.value, StartupState.Idle, hasPc = true)
        startForeground(Notifications.ID, Notifications.build(this, first), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        true
    } catch (e: IllegalStateException) {
        Log.w(TAG, "foreground start refused", e)
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "foreground start refused", e)
        false
    }

    // Keystore decryption and the binder call both stay off the main thread.
    private suspend fun pushConfig(settings: AppSettings) = withContext(Dispatchers.IO) {
        HelperConnection.configure(RelayConfigFactory.build(settings, ::uidOf, TokenCipher::open))
    }

    private fun uidOf(packageName: String): Int? = try {
        packageManager.getPackageUid(packageName, 0)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /** Writes back what the helper changed: the shortcut's on/off and the address that worked. */
    private suspend fun keepSettingsInSync() {
        AppState.relay.filter { it.configured }
            .distinctUntilChanged { a, b -> a.enabled == b.enabled && a.connected == b.connected && a.endpoint == b.endpoint }
            .collect { relay ->
                SettingsStore.update(this) { s ->
                    val pc = s.pcs.defaultPc
                    var next = if (relay.enabled != s.enabled) s.copy(enabled = relay.enabled) else s
                    if (pc != null && relay.endpoint.isNotEmpty()) {
                        val pcs = next.pcs.promote(pc.id, Endpoint.parse(relay.endpoint, 0)).touch(pc.id, System.currentTimeMillis())
                        next = next.copy(pcs = pcs)
                    }
                    next
                }
            }
    }

    companion object {
        private const val TAG = "KeyRelayService"
        const val ACTION_RETRY = "dev.keyrelay.action.RETRY"
        const val ACTION_TOGGLE = "dev.keyrelay.action.TOGGLE"

        fun start(context: Context) = send(context, null)

        fun retry(context: Context) = send(context, ACTION_RETRY)

        fun setEnabled(context: Context, on: Boolean) {
            HelperConnection.setEnabled(on)
            AppState.scope.launch { SettingsStore.update(context) { it.copy(enabled = on) } }
        }

        private fun send(context: Context, action: String?) {
            try {
                context.startForegroundService(Intent(context, RelayService::class.java).setAction(action))
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (Android 12+) is an IllegalStateException.
                Log.w(TAG, "relay service start refused", e)
            }
        }
    }
}
```
- [ ] **Step 9: `AdbSetup`, `BootReceiver`, eski dosyaların silinmesi, manifest**

`AdbSetup.kt`:
```kotlin
package dev.keyrelay.launcher

import android.content.Context
import android.content.pm.PackageManager
import dev.keyrelay.core.AdbConnectResult
import dev.keyrelay.core.ShellOutput
import dev.keyrelay.service.classifyConnect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** One-time setup steps over wireless debugging: pair this app as an adb client and grant it the settings permission. */
object AdbSetup {
    private const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"

    suspend fun pair(context: Context, host: String, port: Int, code: String): Boolean =
        withContext(Dispatchers.IO) { AdbManager.get(context).pair(host, port, code) }

    /** Wireless debugging must be on (the user is in its settings page during the wizard). */
    suspend fun grantSecureSettings(context: Context): Boolean {
        val adb = AdbManager.get(context)
        val connected = withContext(Dispatchers.IO) {
            runCatching { adb.disconnect() }
            classifyConnect { adb.autoConnect(context, 2 * ShellOutput.COMMAND_TIMEOUT_MS) }
        }
        if (connected != AdbConnectResult.CONNECTED) throw IOException("wireless debugging is not reachable: $connected")
        adb.exec("pm grant ${context.packageName} $WRITE_SECURE_SETTINGS")
        return context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
    }
}
```
`BootReceiver.kt`:
```kotlin
package dev.keyrelay.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dev.keyrelay.service.RelayService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ready = context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && ready) RelayService.start(context)
    }
}
```
Sil: `& $git rm android/app/src/main/kotlin/dev/keyrelay/launcher/StartupService.kt android/app/src/main/kotlin/dev/keyrelay/launcher/HelperLauncher.kt android/app/src/main/kotlin/dev/keyrelay/launcher/NetworkApprovalProbe.kt`

Manifest: `StartupService` satırını şununla değiştir:
```xml
        <service android:name=".service.RelayService" android:exported="false" android:foregroundServiceType="connectedDevice" />
```

- [ ] **Step 10: Geçici hata ayıklama ekranı**

`MainActivity.kt`'yi şununla değiştir (T14'te gerçek arayüzle değişir; metinler geçici olduğu için kaynakta):
```kotlin
package dev.keyrelay

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.keyrelay.launcher.AdbSetup
import dev.keyrelay.service.AppState
import dev.keyrelay.service.RelayService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { DebugScreen() } }
    }

    @Composable
    private fun DebugScreen() {
        val scope = rememberCoroutineScope()
        var host by remember { mutableStateOf("") }
        var port by remember { mutableStateOf("") }
        var code by remember { mutableStateOf("") }
        var status by remember { mutableStateOf("") }
        val relay by AppState.relay.collectAsState()
        val startup by AppState.startup.collectAsState()
        val notificationPermission = rememberLauncherForActivityResult(RequestPermission()) {}
        LaunchedEffect(Unit) { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }

        fun run(label: String, action: suspend () -> Any?) {
            status = "$label..."
            scope.launch { status = runCatching { action() }.fold({ "$label: $it" }, { "$label hata: $it" }) }
        }

        Column(Modifier.padding(16.dp)) {
            OutlinedTextField(host, { host = it }, label = { Text("IP") })
            OutlinedTextField(port, { port = it }, label = { Text("Eşleştirme portu") })
            OutlinedTextField(code, { code = it }, label = { Text("6 haneli kod") })
            Button({ run("Eşleştir") { AdbSetup.pair(applicationContext, host.trim(), port.trim().toInt(), code.trim()) } }) { Text("Eşleştir") }
            Button({ run("İzin ver") { AdbSetup.grantSecureSettings(applicationContext) } }) { Text("İzin ver") }
            Button({ RelayService.start(applicationContext); status = "Servis başlatıldı" }) { Text("Servisi başlat") }
            Text(status)
            Text("startup=$startup")
            Text("relay=$relay")
        }
    }
}
```

- [ ] **Step 11: Derle ve testler**

Run: `.\android\gradlew.bat -p android :core:test :app:testDebugUnitTest :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 12: Cihazda doğrula (controller kullanıcıyla)**

Ön koşul: P1'de verilen `WRITE_SECURE_SETTINGS` duruyor (`& $adb shell dumpsys package dev.keyrelay | Select-String WRITE_SECURE` → `granted=true`), prototip durdurulmuş.
1. Kur, uygulamayı aç, bildirim iznini ver, "Servisi başlat". Beklenen: tek bildirim "Başlatılıyor" → "PC eşlenmedi"; birkaç saniye içinde PC'nin adb oturumu düşer (beklenen). Kullanıcı kablosuz hata ayıklamayı yeniden açar.
2. `& $adb logcat -d -s KeyRelayHelperLink KeyRelayService` → `helper attached`; `& $adb shell head -n 3 /data/local/tmp/keyrelay-helper.log`.
3. Yardımcı ölümü (c): `& $adb shell 'kill $(pidof keyrelay-helper)'`; bildirim "5 sn sonra yeniden başlatılıyor", sonra yeniden "PC eşlenmedi" (adb yine düşer; kullanıcı yeniden açar).
4. Açılış (bitiş 4 kısmi): kullanıcı tableti yeniden başlatır, kilidi açar ve saati söyler; dokunmadan bekler. Beklenen: ≤ 60 sn içinde bildirim "PC eşlenmedi" (yardımcı ayakta). Kablosuz hata ayıklamayı açıp `& $adb logcat -d -b all -v epoch | Select-String 'BOOT_COMPLETED|KeyRelay'` ile zamanları al.
5. "Yeniden dene": `& $adb shell am startservice -a dev.keyrelay.action.RETRY dev.keyrelay/.service.RelayService` → yeni yardımcı (eski pid gider, yeni pid gelir).
Sonuçları T12'deki sonuç belgesinin "T11" bölümü için not al.

- [ ] **Step 13: Commit**

```
& $git add -A android/app/src/main android/app/src/test
& $git commit -m "feat(android): yönlendirme servisi açılışta kendiliğinden başlar" -m "- tablet açılınca KeyRelay kendiliğinden başlar, tek bir bildirimle durumunu gösterir
- bildirimden duraklatılıp sürdürülebilir; başlatma başarısızsa nedeni yazılır ve yeniden denenebilir
- yeni bir ağda sistem onayı gerekiyorsa bildirim ne yapılacağını söyler
- kapanan yardımcı süreç kendiliğinden yeniden başlatılır" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 12: PC eşleştirme (bağlantıyla), Quick Settings kutucuğu, uçtan uca cihaz denemesi

**Files:**
- Create: `android/app/src/main/kotlin/dev/keyrelay/service/PcPairing.kt`, `RelayTileService.kt`
- Modify: `android/app/src/main/kotlin/dev/keyrelay/MainActivity.kt` (geçici ekrana bağlantı onayı), `android/app/src/main/AndroidManifest.xml` (deep link, kutucuk), `res/values/strings.xml`, `res/values-tr/strings.xml`
- Test: `android/app/src/test/kotlin/dev/keyrelay/service/PcPairingTest.kt`
- Create: `docs/spikes/2026-10-p2-device-results.md` (T9, T11, T12 bölümleri)

**Interfaces:**
- Consumes: T3 `PairingInfo`, `PairingUri`, `PairedPc`, `upsert`, `makeDefault`; T5 `RelayClient.pair`; T10 `SettingsStore`, `TokenCipher`; T11 `RelayService`, `Notifications`, `AppState.notice`, `HelperConnection`.
- Produces: `sealed interface PairResult { Paired(pcName: String); Rejected(reason: Int); Unreachable(detail: String) }`; `object PcPairing { suspend fun pair(context, info: PairingInfo): PairResult }` (yalnız kullanıcı onayından sonra çağrılır); `internal fun pairingDeviceName(deviceName: String?, model: String): String`; `RelayTileService`.

- [ ] **Step 1: Failing test**

`PcPairingTest.kt`:
```kotlin
package dev.keyrelay.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PcPairingTest {
    @Test
    fun pairingDeviceName_userNameSet_usesIt() = assertEquals("My Tab", pairingDeviceName("My Tab", "SM-X910"))

    @Test
    fun pairingDeviceName_blankOrMissing_fallsBackToModel() {
        assertEquals("SM-X910", pairingDeviceName(null, "SM-X910"))
        assertEquals("SM-X910", pairingDeviceName("  ", "SM-X910"))
    }

    @Test
    fun pairingDeviceName_veryLong_cutTo64Chars() = assertEquals(64, pairingDeviceName("a".repeat(300), "x").length)
}
```

- [ ] **Step 2: Çalıştır, başarısız olduğunu gör**

Run: `.\android\gradlew.bat -p android :app:testDebugUnitTest --tests "dev.keyrelay.service.PcPairingTest"`
Expected: derleme hatası (`pairingDeviceName` yok).

- [ ] **Step 3: `PcPairing.kt`**

```kotlin
package dev.keyrelay.service

import android.content.Context
import android.os.Build
import android.provider.Settings
import dev.keyrelay.core.Frame
import dev.keyrelay.core.PairedPc
import dev.keyrelay.core.PairingInfo
import dev.keyrelay.core.RelayClient
import dev.keyrelay.core.makeDefault
import dev.keyrelay.core.upsert
import dev.keyrelay.settings.SettingsStore
import dev.keyrelay.settings.TokenCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

sealed interface PairResult {
    data class Paired(val pcName: String) : PairResult
    data class Rejected(val reason: Int) : PairResult
    data class Unreachable(val detail: String) : PairResult
}

// 64 UTF-16 chars stay far below the PAIR frame's byte limit even for 4-byte characters.
private const val MAX_NAME_CHARS = 64

internal fun pairingDeviceName(deviceName: String?, model: String) = (deviceName?.takeIf { it.isNotBlank() } ?: model).take(MAX_NAME_CHARS)

object PcPairing {
    /** Pairs with the PC of a scanned or opened link; the new PC becomes the default. Only after the user confirmed. */
    suspend fun pair(context: Context, info: PairingInfo): PairResult = withContext(Dispatchers.IO) {
        val name = pairingDeviceName(Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME), Build.MODEL)
        val reply = try {
            RelayClient.pair(info.endpoints, info.spkiSha256, info.code, name)
        } catch (e: IOException) {
            return@withContext PairResult.Unreachable(e.message.orEmpty())
        }
        when (reply) {
            is Frame.Paired -> {
                val pc = PairedPc(reply.deviceId, info.pcName, info.endpoints, info.spkiSha256, TokenCipher.seal(reply.token), System.currentTimeMillis(), true)
                SettingsStore.update(context) { it.copy(pcs = it.pcs.upsert(pc).makeDefault(pc.id)) }
                PairResult.Paired(info.pcName)
            }
            is Frame.Reject -> PairResult.Rejected(reply.reason)
            else -> PairResult.Unreachable("unexpected reply")
        }
    }
}
```

- [ ] **Step 4: Testin geçtiğini gör**

Run: `.\android\gradlew.bat -p android :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Kutucuk, bağlantı filtresi, metinler**

`RelayTileService.kt`:
```kotlin
package dev.keyrelay.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.keyrelay.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Quick Settings tile: pause/resume, with the same status words as the notification. */
class RelayTileService : TileService() {
    private var listening: Job? = null

    override fun onStartListening() {
        listening = AppState.scope.launch { AppState.notice.collect { render() } }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
    }

    override fun onClick() {
        if (HelperConnection.helper == null) {
            openApp()
            return
        }
        RelayService.setEnabled(this, !AppState.relay.value.enabled)
    }

    private fun render() {
        val tile = qsTile ?: return
        val relay = AppState.relay.value
        tile.state = if (relay.attached && relay.enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = AppState.notice.value?.let { getString(Notifications.titleRes(it.kind)) }
        tile.updateTile()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
```
Manifest `<application>` içine:
```xml
        <service
            android:name=".service.RelayTileService"
            android:exported="true"
            android:icon="@drawable/ic_stat_keyrelay"
            android:label="@string/tile_label"
            android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">
            <intent-filter>
                <action android:name="android.service.quicksettings.action.QS_TILE" />
            </intent-filter>
        </service>
```
`MainActivity` etkinliğine `android:launchMode="singleTop"` ve ikinci bir intent filtresi ekle:
```xml
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="keyrelay" android:host="pair" />
            </intent-filter>
```
Metinler — `values/strings.xml`'e:
```xml
    <string name="tile_label">KeyRelay</string>
```
`values-tr/strings.xml`'e:
```xml
    <string name="tile_label">KeyRelay</string>
```

- [ ] **Step 6: Geçici ekranda bağlantı onayı (Review Focus 5)**

`MainActivity.kt`'ye: sınıf alanı `private var pendingLink by mutableStateOf<PairingInfo?>(null)`; `onCreate`'te `setContent`'ten önce `pendingLink = linkFrom(intent)`; ekle:
```kotlin
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        linkFrom(intent)?.let { pendingLink = it }
    }

    private fun linkFrom(intent: Intent?): PairingInfo? = intent?.dataString?.let { runCatching { PairingUri.parse(it) }.getOrNull() }
```
`DebugScreen` içindeki `Column`'un sonuna:
```kotlin
            pendingLink?.let { info ->
                Text("Eşleştir: ${info.pcName} ${info.endpoints.joinToString()}")
                Button({ run("PC eşleştir") { PcPairing.pair(applicationContext, info).also { pendingLink = null } } }) { Text("Eşleştir") }
                Button({ pendingLink = null }) { Text("İptal") }
            }
```
(Importlar: `android.content.Intent`, `dev.keyrelay.core.PairingInfo`, `dev.keyrelay.core.PairingUri`, `dev.keyrelay.service.PcPairing`.)

- [ ] **Step 7: Derle ve testler**

Run: `.\android\gradlew.bat -p android :core:test :app:testDebugUnitTest :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Uçtan uca cihaz denemesi — test sunucusu PC yerine (controller kullanıcıyla)**

Hazırlık:
1. devpc güvenlik duvarı: kullanıcı **yönetici** PowerShell'de bir kez çalıştırır: `New-NetFirewallRule -DisplayName "KeyRelay test 47111" -Direction Inbound -Protocol TCP -LocalPort 47111 -Action Allow -Profile Private`
2. Test sunucusu (arka planda, çıktısı dosyaya): `Start-Process -NoNewWindow -FilePath .\android\gradlew.bat -ArgumentList "-p","android",":core:fakeServer","-Pport=47111" -RedirectStandardOutput "$env:TEMP\fake-server.log"`; ilk satırdaki `keyrelay://pair?...` bağlantısını al.
3. Prototipi durdur, APK'yı kur.

Adımlar (her biri gözlem + zaman):
1. **Kötü niyetli bağlantı (Review Focus 5):** `& $adb shell "am start -a android.intent.action.VIEW -d '<bağlantı>'"` → uygulamada onay satırı görünür; kullanıcı **İptal**'e basar → `& $adb shell run-as dev.keyrelay ls files/datastore` ve uygulamada PC yok (eşleşme olmadı; test sunucusu logunda `PAIR` yok).
2. **Eşleştirme:** aynı komut → kullanıcı **Eşleştir** → ekranda `PC eşleştir: Paired(pcName=FakePC)`; sunucu logunda `PAIR`.
3. **Başlatma:** kullanıcı "Servisi başlat" → bildirim "Hazır · FakePC · klavye tablette" (adb düşer; buradan sonra gözlem sunucu logu ve kullanıcı).
4. **Yönlendirme:** kullanıcı TeamViewer'ı açar → bildirim "Yönlendiriliyor"; harf yazar, Alt basılıyken art arda Tab, Win tek başına, Win+E, Ctrl+Alt+Del, Win+L, ses tuşları. Sunucu logunda: `KEY code=…` satırları, Alt+Tab için 56/15/15/…/56 sırası, `SAS`, `LOCK`; tablette hiçbiri etki yapmaz.
5. **Ön plan:** Notes'a geçer → yazı Notes'a gider (sunucuya gitmez); TeamViewer'a döner → yine sunucuya.
6. **Kısayol:** TeamViewer öndeyken Ctrl+Alt+Space → bildirim "Duraklatıldı", yazı tablete; tekrar → "Yönlendiriliyor". Bölünmüş ekranda (TeamViewer + Notes, Notes son dokunulan) Ctrl+Alt+Space → yönlendirme açılır; tekrar → kapanır.
7. **Kutucuk:** kullanıcı Hızlı Ayarlar'a KeyRelay kutucuğunu ekler; dokunur → "Duraklatıldı"; tekrar → etkin.
8. **Kopma:** sunucu sürecini durdur (`Stop-Process` ile java) → en geç ~10 sn'de bildirim "Bağlanıyor", klavye tablette; sunucuyu yeniden başlat → kendiliğinden "Hazır/Yönlendiriliyor".
9. **Açılış:** tablet yeniden başlatılır; kilit açılışından ≤ 60 sn içinde, dokunmadan, sunucu logunda yeni `HELLO` ve TeamViewer açılınca `KEY`.
10. Kullanıcı kablosuz hata ayıklamayı açar; kanıtları al: `& $adb shell cat /data/local/tmp/keyrelay-helper.log > "$env:TEMP\p2-helper.log"`, `& $adb logcat -d -b all -v epoch | Select-String 'KeyRelay|BOOT_COMPLETED' > "$env:TEMP\p2-logcat.txt"`.

Bir adım beklenenden saparsa düzeltmeyi bu görevde yapma: `superpowers:systematic-debugging` ile kök nedeni bul, controller'a bildir (düzeltme ayrı inceleme turudur).

- [ ] **Step 9: Sonuç belgesi**

`docs/spikes/2026-10-p2-device-results.md`:
```markdown
# P2 cihaz sonuçları

Cihaz: SM-X910, Android 14 / SDK 34; MX Keys Mini. PC yerine `gradlew :core:fakeServer` (devpc, LAN). Ham loglar repo dışında (`%TEMP%\p2-*.log`).

## T9 — yardımcı bağlantısı
<T9 Step 11 gözlemleri: işlem kodu satırı, attach, uygulama ölümü → yardımcı kapanır, yardımcı ölümü → uygulama görür>

## T11 — servis ve açılış
<T11 Step 12 gözlemleri ve açılış zamanları: kilit açılışı → BOOT_COMPLETED → yardımcı attach>

## T12 — uçtan uca (test sunucusu)
| # | Adım | Beklenen | Gözlenen | Sonuç |
<Step 8'in on adımı>
```
Yalnız gözlenenler yazılır; ölçülmeyen "ölçülmedi".

- [ ] **Step 10: Commit**

```
& $git add android/app/src/main android/app/src/test/kotlin/dev/keyrelay/service/PcPairingTest.kt docs/spikes/2026-10-p2-device-results.md
& $git commit -m "feat(android): pc bağlantıyla eşlenir, hızlı ayarlar kutucuğu eklendi" -m "- PC'nin eşleştirme bağlantısı açılınca onay istenir; onaysız eşleşme olmaz
- eşlenen PC'ye tuşlar, Ctrl+Alt+Del ve Win+L gider
- hızlı ayarlardaki kutucukla yönlendirme duraklatılıp sürdürülür" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 13: Marka — ikon, renkler, yazı tipleri, tema; eksik ekran taslakları

Spec "Uygulama yöntemi": ekranlar kodlanmadan önce taslak onaylanır. Onaylı taslakta PC ayrıntısı ve Ayarlar (Klavyeler bölümüyle) yok; bu görev önce onları taslağa ekletip onaylatır, sonra tema ve ikonları kodlar.

**Files:**
- Modify: `docs/design/keyrelay-mockups.html` (PC ayrıntısı + Ayarlar)
- Modify: `android/gradle/libs.versions.toml`, `android/app/build.gradle.kts` (Material 3 1.5.0-beta01, icons-core)
- Create: `android/app/src/main/res/font/manrope.ttf`, `jetbrains_mono.ttf`; `android/app/src/main/assets/licenses/manrope-OFL.txt`, `jetbrains-mono-OFL.txt`
- Create: `android/app/src/main/res/drawable/ic_launcher_background.xml`, `ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml`, `ic_keyboard.xml`, `ic_monitor.xml`, `ic_qr.xml`; `android/app/src/main/res/mipmap-anydpi/ic_launcher.xml`; `android/app/src/main/res/values/themes.xml`
- Create: `android/app/src/main/kotlin/dev/keyrelay/ui/theme/Theme.kt`
- Modify: `android/app/src/main/AndroidManifest.xml` (ikon, tema), `res/values*/strings.xml`
- Test: `android/app/src/test/kotlin/dev/keyrelay/ui/StringResourcesTest.kt`

**Interfaces:**
- Produces: `@Composable fun KeyRelayTheme(content)`; `object Brand` (taslak renkleri); `data class StatusColors(ready, waiting)` + `LocalStatusColors`; `val JetBrainsMono: FontFamily`; çizimler `R.drawable.ic_keyboard`, `ic_monitor`, `ic_qr`, `ic_stat_keyrelay` (T11), `R.mipmap.ic_launcher`.

- [ ] **Step 1: Taslağa PC ayrıntısı ve Ayarlar ekle**

`docs/design/keyrelay-mockups.html`'de `<p class="label">PC'ler ve hedef uygulamalar · kart → ayrıntı paylaşılan öğe geçişi</p>` satırından sonraki `</div>`'in hemen ardına ekle:
```html
    <!-- PC DETAIL -->
    <div>
      <div class="phone">
        <div class="scroll">
          <div class="topbar"><button class="iconbtn" aria-label="Geri">&#8249;</button><div class="title">homepc</div><span style="width:44px"></span></div>
          <div class="hero" data-s="ready">
            <div class="state">Bağlı</div>
            <div class="headline">homepc</div>
            <div class="sub">Varsayılan PC · son görülme şimdi</div>
            <div class="chips"><span class="chip"><span class="dot"></span><span class="mono">3,8 ms</span></span><span class="chip">TLS 1.3 · sabitlenmiş</span></div>
          </div>
          <div class="section-t">Adresler · ilk sıradaki önce denenir</div>
          <div class="app mono" style="font-size:13px">homepc.tailnet-example.ts.net:8443</div>
          <div class="app mono" style="font-size:13px">100.64.0.10:47111</div>
          <div class="app mono" style="font-size:13px">192.168.1.10:47111</div>
          <div class="section-t">Sertifika parmak izi</div>
          <div class="app mono" style="font-size:12px;word-break:break-all">nhDFng6PxrMUestQPWHqag0G99AYI1PSvnW94Q3IWDA</div>
          <button class="primary">Varsayılan yap</button>
          <button class="ghost" style="color:var(--err);border-color:#5A2A33">PC'yi kaldır</button>
        </div>
      </div>
      <p class="label">PC ayrıntısı · karttan paylaşılan öğe geçişiyle açılır, geri hareketi önizlenir</p>
    </div>

    <!-- SETTINGS -->
    <div>
      <div class="phone">
        <div class="scroll">
          <div class="topbar"><button class="iconbtn" aria-label="Geri">&#8249;</button><div class="title">Ayarlar</div><span style="width:44px"></span></div>
          <div class="section-t">Bağlantı</div>
          <div class="check"><div style="flex:1"><b>Canlılık aralığı</b><span>PC'ye ne sıklıkla yoklama gider</span></div></div>
          <div class="demo"><button>2 sn</button><button aria-current="true">5 sn</button><button>10 sn</button></div>
          <div class="check"><div style="flex:1"><b>Yanıt bekleme</b><span>Bu sürede yanıt gelmezse klavye tablete döner</span></div></div>
          <div class="demo"><button>5 sn</button><button aria-current="true">10 sn</button><button>20 sn</button></div>
          <div class="check"><div style="flex:1"><b>Gönderme sınırı</b><span>Takılan bağlantı bu sürede düşmüş sayılır</span></div></div>
          <div class="demo"><button>0,5 sn</button><button aria-current="true">1 sn</button><button>2 sn</button></div>
          <div class="section-t">Geçiş kısayolu</div>
          <div class="demo"><button aria-current="true" class="mono">Ctrl Alt Space</button><button class="mono">Ctrl Alt `</button><button class="mono">Ctrl Alt Ins</button></div>
          <div class="section-t">Klavyeler</div>
          <div class="app"><span class="dot"></span><div style="flex:1"><b style="font-size:15px">MX Keys Mini Keyboard</b><div style="font-size:12px;color:var(--muted)">Yönlendirilirken yakalanır</div></div></div>
          <div class="section-t">Kurulum</div>
          <button class="ghost">Kurulum sihirbazını yeniden çalıştır</button>
          <p style="color:var(--muted);font-size:12px;margin-top:18px">Yazı tipleri: Manrope, JetBrains Mono (SIL OFL 1.1)</p>
        </div>
      </div>
      <p class="label">Ayarlar · süreler, kısayol, klavyeler</p>
    </div>
```
Controller kullanıcıdan dosyayı kendi tarayıcısında açıp iki yeni ekranı onaylamasını ister ve **onayı bekler**; değişiklik isterse bu adım tekrarlanır. (Agent tarayıcı açmaz.)

- [ ] **Step 2: Sürümler**

`libs.versions.toml`: `[versions]`'a `material3 = "1.5.0-beta01"`; `[libraries]`'de `compose-material3` satırını `compose-material3 = { module = "androidx.compose.material3:material3", version.ref = "material3" }` yap (BOM'un 1.4.0'ını geçersiz kılar — Kararlar) ve `compose-icons-core = { module = "androidx.compose.material:material-icons-core" }` ekle. `app/build.gradle.kts`: `implementation(libs.compose.icons.core)`.

- [ ] **Step 3: Yazı tipleri ve lisansları**

```
New-Item -ItemType Directory -Force android\app\src\main\res\font, android\app\src\main\assets\licenses | Out-Null
Invoke-WebRequest "https://github.com/google/fonts/raw/main/ofl/manrope/Manrope%5Bwght%5D.ttf" -OutFile android\app\src\main\res\font\manrope.ttf
Invoke-WebRequest "https://github.com/google/fonts/raw/main/ofl/jetbrainsmono/JetBrainsMono%5Bwght%5D.ttf" -OutFile android\app\src\main\res\font\jetbrains_mono.ttf
Invoke-WebRequest "https://github.com/google/fonts/raw/main/ofl/manrope/OFL.txt" -OutFile android\app\src\main\assets\licenses\manrope-OFL.txt
Invoke-WebRequest "https://github.com/google/fonts/raw/main/ofl/jetbrainsmono/OFL.txt" -OutFile android\app\src\main\assets\licenses\jetbrains-mono-OFL.txt
Get-FileHash android\app\src\main\res\font\*.ttf | Format-Table Hash, Path
```
Expected: iki TTF (~165 KB, ~187 KB); özetleri görev raporuna yaz. `.gitattributes`'e `*.ttf binary` ekle.

- [ ] **Step 4: İkonlar (taslaktaki işaret: klavye tuşu + sağa akan ok)**

`drawable/ic_launcher_background.xml`:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="radial" android:centerX="21.6" android:centerY="10.8" android:gradientRadius="130">
                <item android:offset="0" android:color="#FF2A4FC9" />
                <item android:offset="0.55" android:color="#FF14245E" />
                <item android:offset="1" android:color="#FF0B1433" />
            </gradient>
        </aapt:attr>
    </path>
</vector>
```
`drawable/ic_launcher_foreground.xml` (64 birimlik işaret 66 dp güvenli alanın ortasında):
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <group android:translateX="21.5" android:translateY="24">
        <path android:fillColor="#FFEAF0FF" android:pathData="M14,14H28A8,8 0,0 1,36,22V36A8,8 0,0 1,28,44H14A8,8 0,0 1,6,36V22A8,8 0,0 1,14,14Z" />
        <path android:fillColor="#FF9FB4FF" android:pathData="M9,40H33A3,3 0,0 1,36,43A3,3 0,0 1,33,46H9A3,3 0,0 1,6,43A3,3 0,0 1,9,40Z" />
        <path android:strokeColor="#FF1E3A8F" android:strokeWidth="3.5" android:strokeLineCap="round" android:pathData="M15,29H27M21,23V35" />
        <path android:strokeColor="#FF89A8FF" android:strokeWidth="4" android:strokeLineCap="round" android:pathData="M40,30H58" />
        <path android:strokeColor="#FFEAF0FF" android:strokeWidth="4" android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="M52,23L59,30L52,37" />
    </group>
</vector>
```
`drawable/ic_launcher_monochrome.xml` (tematik ikon; sistem renklendirir):
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <group android:translateX="21.5" android:translateY="24">
        <path android:strokeColor="#FFFFFFFF" android:strokeWidth="3.5" android:fillColor="#00000000"
            android:pathData="M14,14H28A8,8 0,0 1,36,22V36A8,8 0,0 1,28,44H14A8,8 0,0 1,6,36V22A8,8 0,0 1,14,14Z" />
        <path android:strokeColor="#FFFFFFFF" android:strokeWidth="3.5" android:strokeLineCap="round" android:pathData="M15,29H27M21,23V35" />
        <path android:strokeColor="#FFFFFFFF" android:strokeWidth="4" android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="M40,30H58M52,23L59,30L52,37" />
    </group>
</vector>
```
`mipmap-anydpi/ic_launcher.xml`:
```xml
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
```
Arayüz çizimleri (taslaktaki SVG'ler, 24 dp, renk Compose `Icon` tint'inden):
`drawable/ic_keyboard.xml`:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:strokeColor="#FF000000" android:strokeWidth="2" android:fillColor="#00000000"
        android:pathData="M4,6H20A2,2 0,0 1,22,8V16A2,2 0,0 1,20,18H4A2,2 0,0 1,2,16V8A2,2 0,0 1,4,6Z" />
    <path android:strokeColor="#FF000000" android:strokeWidth="2" android:strokeLineCap="round"
        android:pathData="M6,10H6.01M10,10H10.01M14,10H14.01M18,10H18.01M7,14H17" />
</vector>
```
`drawable/ic_monitor.xml`:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:strokeColor="#FF000000" android:strokeWidth="2" android:fillColor="#00000000"
        android:pathData="M5,4H19A2,2 0,0 1,21,6V14A2,2 0,0 1,19,16H5A2,2 0,0 1,3,14V6A2,2 0,0 1,5,4Z" />
    <path android:strokeColor="#FF000000" android:strokeWidth="2" android:strokeLineCap="round" android:pathData="M8,20H16M12,16V20" />
</vector>
```
`drawable/ic_qr.xml`:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:strokeColor="#FF000000" android:strokeWidth="2.2" android:strokeLineCap="round" android:fillColor="#00000000"
        android:pathData="M3,7V5A2,2 0,0 1,5,3H7M17,3H19A2,2 0,0 1,21,5V7M21,17V19A2,2 0,0 1,19,21H17M7,21H5A2,2 0,0 1,3,19V17M7,12H17" />
</vector>
```
`values/themes.xml` (Compose çizene kadar pencere zemini gece/gündüze uysun):
```xml
<resources>
    <style name="Theme.KeyRelay" parent="@android:style/Theme.DeviceDefault.DayNight">
        <item name="android:windowActionBar">false</item>
        <item name="android:windowNoTitle">true</item>
    </style>
</resources>
```
Manifest `<application>`: `android:icon="@mipmap/ic_launcher" android:roundIcon="@mipmap/ic_launcher" android:theme="@style/Theme.KeyRelay"`; `android:enableOnBackInvokedCallback="true"` (öngörülü geri, T14).

- [ ] **Step 5: Failing test — iki dil dosyası aynı anahtarlar**

`values/strings.xml`'e ekle: `<string name="about_fonts">Fonts: Manrope and JetBrains Mono (SIL Open Font License 1.1)</string>` (Türkçesini henüz ekleme).

`StringResourcesTest.kt`:
```kotlin
package dev.keyrelay.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

class StringResourcesTest {
    private fun keys(path: String) = Regex("<string name=\"([^\"]+)\"").findAll(File(path).readText()).map { it.groupValues[1] }.toSortedSet()

    @Test
    fun turkishStrings_haveSameKeysAsDefault() =
        assertEquals(keys("src/main/res/values/strings.xml"), keys("src/main/res/values-tr/strings.xml"))
}
```
Run: `.\android\gradlew.bat -p android :app:testDebugUnitTest --tests "dev.keyrelay.ui.StringResourcesTest"`
Expected: KALIR (`about_fonts` Türkçede yok).

- [ ] **Step 6: Türkçesini ekle, testin geçtiğini gör**

`values-tr/strings.xml`'e: `<string name="about_fonts">Yazı tipleri: Manrope ve JetBrains Mono (SIL Open Font License 1.1)</string>`.
Run: aynı komut → PASS.

- [ ] **Step 7: `Theme.kt`**

```kotlin
package dev.keyrelay.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import dev.keyrelay.R

/** The approved mockup's palette; used as is when dynamic color is unavailable (before Android 12). */
object Brand {
    val Ink = Color(0xFF0B111C)
    val Surface = Color(0xFF111A29)
    val SurfaceHigh = Color(0xFF18233A)
    val Line = Color(0xFF24314B)
    val Text = Color(0xFFE8EEF8)
    val Muted = Color(0xFF9AA8C0)
    val Accent = Color(0xFF4C7DFF)
    val AccentLight = Color(0xFF89A8FF)
    val Ready = Color(0xFF2FD49C)
    val Waiting = Color(0xFFF2B544)
    val Error = Color(0xFFFF6B6B)
}

/** Status colors Material has no slot for: the mockup's green (ready) and amber (waiting). */
@Immutable
data class StatusColors(val ready: Color, val waiting: Color)

val LocalStatusColors = staticCompositionLocalOf { StatusColors(Brand.Ready, Brand.Waiting) }

private val DarkBrand = darkColorScheme(
    primary = Brand.Accent, onPrimary = Color.White, primaryContainer = Color(0xFF1B2D5E), onPrimaryContainer = Brand.AccentLight,
    secondary = Brand.AccentLight, onSecondary = Color(0xFF0B1433),
    background = Brand.Ink, onBackground = Brand.Text, surface = Brand.Ink, onSurface = Brand.Text,
    surfaceVariant = Brand.SurfaceHigh, onSurfaceVariant = Brand.Muted,
    surfaceContainerLow = Brand.Surface, surfaceContainer = Brand.Surface, surfaceContainerHigh = Brand.SurfaceHigh,
    outline = Brand.Line, outlineVariant = Brand.Line, error = Brand.Error, onError = Color(0xFF2B0606),
)

private val LightBrand = lightColorScheme(
    primary = Color(0xFF2F5FE0), onPrimary = Color.White, primaryContainer = Color(0xFFDCE4FF), onPrimaryContainer = Color(0xFF0B1433),
    secondary = Brand.Accent, onSecondary = Color.White,
    background = Color(0xFFF6F8FC), onBackground = Brand.Ink, surface = Color(0xFFF6F8FC), onSurface = Brand.Ink,
    surfaceVariant = Color(0xFFE4E9F2), onSurfaceVariant = Color(0xFF4A5670),
    surfaceContainerLow = Color.White, surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFEDF1F8),
    outline = Color(0xFFC9D2E3), outlineVariant = Color(0xFFDDE3EE), error = Color(0xFFD93A3A), onError = Color.White,
)

private val Manrope = FontFamily(
    listOf(400, 500, 600, 700, 800).map { w ->
        Font(R.font.manrope, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    },
)

/** For numbers and codes: latency, keys, addresses, fingerprints. */
val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
)

@Composable
fun KeyRelayTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkBrand
        else -> LightBrand
    }
    val status = if (dark) StatusColors(Brand.Ready, Brand.Waiting) else StatusColors(Color(0xFF0E9F6E), Color(0xFFB7791F))
    CompositionLocalProvider(LocalStatusColors provides status) {
        MaterialExpressiveTheme(
            colorScheme = colors,
            motionScheme = MotionScheme.expressive(),
            typography = Typography(fontFamily = Manrope),
            content = content,
        )
    }
}
```

- [ ] **Step 8: Derle, ikonu cihazda gör**

Run: `.\android\gradlew.bat -p android :app:testDebugUnitTest :app:assembleDebug; & $adb install -r android\app\build\outputs\apk\debug\app-debug.apk`
Expected: `BUILD SUCCESSFUL`, opt-in uyarısı yok. Kullanıcı başlatıcıda ikonu (normal + "Tematik simgeler" açıkken) görür; ekran görüntüsü: `& $adb exec-out screencap -p > "$env:TEMP\p2-icon.png"`.

- [ ] **Step 9: Commit**

```
& $git add docs/design/keyrelay-mockups.html android/gradle/libs.versions.toml android/app/build.gradle.kts android/app/src/main/res android/app/src/main/assets android/app/src/main/kotlin/dev/keyrelay/ui/theme/Theme.kt android/app/src/main/AndroidManifest.xml android/app/src/test/kotlin/dev/keyrelay/ui/StringResourcesTest.kt .gitattributes
& $git commit -m "feat(android): uygulama ikonu, renkleri ve yazı tipleri eklendi" -m "- uygulama ikonu Android'in şekil ve tek renkli simge seçeneklerine uyar
- renkler duvar kağıdından gelir, eski sürümlerde marka renkleri kullanılır; açık ve koyu tema
- PC ayrıntısı ve Ayarlar ekranlarının taslağı eklendi" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 14: Ekranlar A — gezinme, Durum, PC'ler, PC ayrıntısı, hedef uygulamalar, QR ile eşleştirme

Ekranlar onaylı taslağa (`docs/design/keyrelay-mockups.html`, T13'te eklenen PC ayrıntısı dahil) sadıktır: hero kart (durum etiketi, başlık, alt satır, klavye → PC hattı, çipler), 2×2 kutucuk, Yönlendirme anahtarı; PC kartları + "QR ile PC ekle" FAB; hedef uygulamalar listesi. Ayarlar ve sihirbaz T15'tedir.

**Files:**
- Modify: `android/gradle/libs.versions.toml`, `android/app/build.gradle.kts` (Navigation 3, lifecycle-runtime-compose, code scanner)
- Create: `android/app/src/main/kotlin/dev/keyrelay/ui/Components.kt`, `KeyRelayApp.kt`, `StatusScreen.kt`, `PcsScreen.kt`, `PcDetailScreen.kt`, `PairConfirmDialog.kt`, `InstalledApps.kt`
- Modify: `android/app/src/main/kotlin/dev/keyrelay/MainActivity.kt` (gerçek arayüz)
- Modify: `res/values/strings.xml`, `res/values-tr/strings.xml`
- Test: `android/app/src/test/kotlin/dev/keyrelay/ui/StatusFormattingTest.kt`

**Interfaces:**
- Consumes: T11 `statusKind`, `StatusKind`, `Notifications.titleRes/failureRes`, `RelayService.start/retry/setEnabled`; T9 `AppState`, `RelayStatus`; T10 `AppSettings`, `SettingsStore`; T12 `PcPairing`, `PairResult`; T3 `PairingInfo`, `PairingUri`, `PairedPc`, `defaultPc`, `makeDefault`, `without`; T13 `KeyRelayTheme`, `LocalStatusColors`, `JetBrainsMono`, çizimler.
- Produces: `sealed interface Route { Status; Pcs; PcDetail(id); Settings; Wizard }`; `@Composable fun KeyRelayApp(settings: AppSettings, pendingLink: PairingInfo?, onLinkHandled: () -> Unit)`; `@Composable fun StatusScreen(settings, onOpenPcs: () -> Unit, onOpenSettings: (() -> Unit)?)`; `@Composable fun PcsScreen(settings, onOpenPc: (String) -> Unit, onBack: () -> Unit)`; `@Composable fun PcDetailScreen(settings, id: String, onBack: () -> Unit)`; `@Composable fun PairConfirmDialog(info: PairingInfo, onDone: (paired: Boolean) -> Unit)`; `fun scanPairingQr(context, onResult: (PairingInfo) -> Unit, onError: (String) -> Unit)`; ortak `ScreenTopBar`, `SectionTitle`, `KrCard`, `Modifier.sharedPcBounds(id)`, `appLabel(pkg)`; `internal fun formatRtt(ms: Int): String`, `internal fun shortcutLabel(key: Int): String`; `data class AppEntry`, `object InstalledApps`, `object TargetApps`.

- [ ] **Step 1: Sürümler**

`libs.versions.toml` `[versions]`: `navigation3 = "1.2.0"`, `lifecycle = "2.11.0"`, `codeScanner = "16.1.0"`; `[libraries]`:
```toml
navigation3-runtime = { module = "androidx.navigation3:navigation3-runtime", version.ref = "navigation3" }
navigation3-ui = { module = "androidx.navigation3:navigation3-ui", version.ref = "navigation3" }
lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
code-scanner = { module = "com.google.android.gms:play-services-code-scanner", version.ref = "codeScanner" }
```
`app/build.gradle.kts` `dependencies`: `implementation(libs.navigation3.runtime)`, `implementation(libs.navigation3.ui)`, `implementation(libs.lifecycle.runtime.compose)`, `implementation(libs.code.scanner)`.

- [ ] **Step 2: Failing test**

`StatusFormattingTest.kt`:
```kotlin
package dev.keyrelay.ui

import dev.keyrelay.core.RelayStateMachine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StatusFormattingTest {
    private fun app(pkg: String, label: String) = AppEntry(pkg, label, null)

    @Test
    fun formatRtt_unknown_isDash_known_isMs() {
        assertEquals("—", formatRtt(-1))
        assertEquals("4 ms", formatRtt(4))
    }

    @Test
    fun shortcutLabel_toggleKeys_named() {
        assertEquals("Ctrl Alt Space", shortcutLabel(RelayStateMachine.KEY_SPACE))
        assertEquals("Ctrl Alt `", shortcutLabel(RelayStateMachine.KEY_GRAVE))
        assertEquals("Ctrl Alt Ins", shortcutLabel(RelayStateMachine.KEY_INSERT))
    }

    @Test
    fun order_selectedFirstThenLabelIgnoringCase() {
        val apps = listOf(app("z", "Zoom"), app("a", "anydesk"), app("t", "TeamViewer"), app("b", "Bitwarden"))
        assertEquals(listOf("t", "a", "b", "z"), TargetApps.order(apps, setOf("t")).map { it.packageName })
    }
}
```

- [ ] **Step 3: Çalıştır, başarısız olduğunu gör**

Run: `.\android\gradlew.bat -p android :app:testDebugUnitTest --tests "dev.keyrelay.ui.StatusFormattingTest"`
Expected: derleme hatası (`formatRtt`, `AppEntry`, `TargetApps` yok).

- [ ] **Step 4: Ortak bileşenler — `Components.kt`**

```kotlin
package dev.keyrelay.ui

import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import dev.keyrelay.R
import java.util.Locale

/** Lets screens opt into the PC card -> PC detail shared bounds transition. */
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

@Composable
fun ScreenTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        onBack?.let { IconButton(it) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.cd_back)) } }
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
fun SectionTitle(text: String) = Text(
    text.uppercase(Locale.getDefault()),
    style = MaterialTheme.typography.labelLarge,
    letterSpacing = 1.sp,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(start = 4.dp, top = 22.dp, bottom = 4.dp),
)

/** The mockup's rounded card: container surface with a hairline outline. */
@Composable
fun KrCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    val color = MaterialTheme.colorScheme.surfaceContainer
    val inner: @Composable () -> Unit = { Column(Modifier.padding(16.dp), content = content) }
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = color, border = border, content = inner)
    } else {
        Surface(modifier = modifier, shape = shape, color = color, border = border, content = inner)
    }
}

/** Same key on the PC card and the PC detail container; no-op outside a shared transition. */
@Composable
fun Modifier.sharedPcBounds(id: String): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val animated = LocalNavAnimatedContentScope.current
    return with(shared) { this@sharedPcBounds.sharedBounds(rememberSharedContentState("pc-$id"), animated) }
}

@Composable
fun appLabel(packageName: String?): String {
    val context = LocalContext.current
    return remember(packageName) {
        packageName?.let { pkg ->
            runCatching { context.packageManager.run { getApplicationLabel(getApplicationInfo(pkg, 0)).toString() } }.getOrDefault(pkg)
        }.orEmpty()
    }
}
```

- [ ] **Step 5: Yüklü uygulamalar — `InstalledApps.kt`**

```kotlin
package dev.keyrelay.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

data class AppEntry(val packageName: String, val label: String, val icon: ImageBitmap?)

/** Launcher apps the user can pick as targets (visible through the manifest's launcher <queries>). */
object InstalledApps {
    private const val ICON_PX = 96

    fun load(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0).map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != context.packageName }
            .map { info -> AppEntry(info.packageName, pm.getApplicationLabel(info).toString(), runCatching { pm.getApplicationIcon(info).toImage() }.getOrNull()) }
    }

    private fun Drawable.toImage(): ImageBitmap {
        val bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        setBounds(0, 0, ICON_PX, ICON_PX)
        draw(Canvas(bitmap))
        return bitmap.asImageBitmap()
    }
}

object TargetApps {
    /** Chosen apps first, then by label. */
    fun order(apps: List<AppEntry>, selected: Set<String>): List<AppEntry> =
        apps.sortedWith(compareBy<AppEntry>({ it.packageName !in selected }, { it.label.lowercase() }))
}
```

- [ ] **Step 6: Durum ekranı — `StatusScreen.kt`**

```kotlin
package dev.keyrelay.ui

import android.Manifest
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.keyrelay.R
import dev.keyrelay.core.RelayStateMachine
import dev.keyrelay.core.StartupState
import dev.keyrelay.core.defaultPc
import dev.keyrelay.service.AppState
import dev.keyrelay.service.Notifications
import dev.keyrelay.service.RelayService
import dev.keyrelay.service.RelayStatus
import dev.keyrelay.service.StatusKind
import dev.keyrelay.service.statusKind
import dev.keyrelay.settings.AppSettings
import dev.keyrelay.ui.theme.JetBrainsMono
import dev.keyrelay.ui.theme.LocalStatusColors
import java.util.Locale

private const val PIPE_CYCLE_MS = 1_300
private const val RING_CYCLE_MS = 1_800
private const val PACKETS = 3
private const val SPARK_BARS = 7

internal fun formatRtt(ms: Int) = if (ms >= 0) "$ms ms" else "—"

internal fun shortcutLabel(key: Int) = "Ctrl Alt " + when (key) {
    RelayStateMachine.KEY_GRAVE -> "`"
    RelayStateMachine.KEY_INSERT -> "Ins"
    else -> "Space"
}

@Composable
fun StatusScreen(settings: AppSettings, onOpenPcs: () -> Unit, onOpenSettings: (() -> Unit)?) {
    val context = LocalContext.current
    val relay by AppState.relay.collectAsStateWithLifecycle()
    val startup by AppState.startup.collectAsStateWithLifecycle()
    val kind = statusKind(relay, startup, settings.pcs.defaultPc != null)
    val haptics = LocalHapticFeedback.current
    var previous by remember { mutableStateOf(kind) }
    LaunchedEffect(kind) {
        // Taking the keyboard is the transition worth feeling (spec: haptics at critical transitions).
        if (kind == StatusKind.RELAYING && previous != StatusKind.RELAYING) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        previous = kind
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        val missing = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && missing) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        ScreenTopBar(stringResource(R.string.app_name)) {
            IconButton(onOpenPcs) { Icon(painterResource(R.drawable.ic_monitor), stringResource(R.string.cd_pcs)) }
            onOpenSettings?.let { IconButton(it) { Icon(Icons.Rounded.Settings, stringResource(R.string.cd_settings)) } }
        }
        StatusHero(kind, relay, startup, settings, onRetry = { RelayService.retry(context) }, onOpenPcs = onOpenPcs)
        Spacer(Modifier.height(14.dp))
        StatusTiles(relay, settings)
        Spacer(Modifier.height(14.dp))
        KrCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.routing_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.routing_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = relay.enabled, onCheckedChange = { RelayService.setEnabled(context, it) })
            }
        }
    }
}

@Composable
private fun StatusHero(
    kind: StatusKind,
    relay: RelayStatus,
    startup: StartupState,
    settings: AppSettings,
    onRetry: () -> Unit,
    onOpenPcs: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val status = LocalStatusColors.current
    val accent = when (kind) {
        StatusKind.RELAYING -> colors.primary
        StatusKind.READY -> status.ready
        StatusKind.ERROR, StatusKind.REPAIR -> colors.error
        StatusKind.PAUSED, StatusKind.NOT_PAIRED -> colors.outline
        else -> status.waiting
    }
    val effects = MaterialTheme.motionScheme.slowEffectsSpec<Color>()
    val container by animateColorAsState(lerp(colors.surfaceContainer, accent, 0.22f), effects, label = "heroContainer")
    val border by animateColorAsState(lerp(colors.outlineVariant, accent, 0.55f), effects, label = "heroBorder")
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    Surface(shape = RoundedCornerShape(28.dp), color = container, border = BorderStroke(1.dp, border), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(22.dp)) {
            Text(
                stringResource(Notifications.titleRes(kind)).uppercase(Locale.getDefault()),
                style = MaterialTheme.typography.labelLarge, letterSpacing = 1.sp, color = colors.onSurfaceVariant,
            )
            AnimatedContent(kind, transitionSpec = { (slideInVertically(spatial) { it / 3 } + fadeIn()) togetherWith fadeOut() }, label = "headline") { k ->
                Text(headline(k, relay), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 6.dp))
            }
            Text(subline(kind, relay, startup, settings), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            RelayPipe(kind, accent)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 20.dp)) {
                StatusChip(relay.keyboards.firstOrNull() ?: stringResource(R.string.chip_no_keyboard), if (relay.keyboards.isEmpty()) colors.outline else status.ready)
                StatusChip(stringResource(if (relay.grabbed) R.string.chip_locked else R.string.chip_unlocked), if (relay.grabbed) status.ready else colors.outline)
            }
            when (kind) {
                StatusKind.ERROR, StatusKind.APPROVAL -> FilledTonalButton(onRetry, Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.action_retry)) }
                StatusKind.NOT_PAIRED, StatusKind.REPAIR -> FilledTonalButton(onOpenPcs, Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.action_add_pc)) }
                else -> Unit
            }
        }
    }
}

@Composable
private fun headline(kind: StatusKind, relay: RelayStatus): String = when (kind) {
    StatusKind.RELAYING -> stringResource(R.string.headline_relaying, relay.pcName)
    StatusKind.READY -> stringResource(R.string.headline_ready)
    StatusKind.CONNECTING -> stringResource(R.string.headline_connecting)
    StatusKind.PC_NOT_READY -> stringResource(R.string.headline_pc_not_ready)
    StatusKind.PAUSED -> stringResource(R.string.headline_paused)
    StatusKind.STARTING -> stringResource(R.string.headline_starting)
    StatusKind.NO_WIFI -> stringResource(R.string.headline_no_wifi)
    StatusKind.APPROVAL -> stringResource(R.string.headline_approval)
    StatusKind.ERROR -> stringResource(R.string.headline_error)
    StatusKind.NOT_PAIRED -> stringResource(R.string.headline_not_paired)
    StatusKind.REPAIR -> stringResource(R.string.headline_repair)
}

@Composable
private fun subline(kind: StatusKind, relay: RelayStatus, startup: StartupState, settings: AppSettings): String {
    val target = appLabel(settings.targets.firstOrNull())
    return when (kind) {
        StatusKind.RELAYING -> stringResource(R.string.sub_relaying, target)
        StatusKind.READY -> stringResource(R.string.sub_ready, target)
        StatusKind.CONNECTING -> relay.endpoint.ifEmpty { relay.pcName }
        StatusKind.APPROVAL -> stringResource(R.string.detail_approval)
        StatusKind.ERROR -> (startup as? StartupState.Failed)?.let { stringResource(Notifications.failureRes(it.failure)) }.orEmpty()
        StatusKind.REPAIR -> stringResource(R.string.sub_repair)
        StatusKind.PC_NOT_READY -> stringResource(R.string.sub_pc_not_ready)
        else -> ""
    }
}

@Composable
private fun RelayPipe(kind: StatusKind, accent: Color) {
    // Decorative loops do not run at all when the user switched animations off (animator duration scale 0).
    val moving = kind == StatusKind.RELAYING || kind == StatusKind.CONNECTING || kind == StatusKind.STARTING
    val animate = moving && ValueAnimator.areAnimatorsEnabled()
    val phase = if (animate) {
        rememberInfiniteTransition(label = "pipe")
            .animateFloat(0f, 1f, infiniteRepeatable(tween(PIPE_CYCLE_MS, easing = LinearEasing)), label = "phase").value
    } else {
        0f
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 22.dp)) {
        PipeNode(R.drawable.ic_keyboard, pulsing = animate && kind == StatusKind.RELAYING, accent = accent)
        Canvas(Modifier.weight(1f).height(12.dp).padding(horizontal = 10.dp)) {
            val y = size.height / 2
            val stroke = 4.dp.toPx()
            if (kind == StatusKind.CONNECTING || kind == StatusKind.STARTING) {
                val dash = 8.dp.toPx()
                drawLine(accent.copy(alpha = 0.7f), Offset(0f, y), Offset(size.width, y), stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash), -phase * 2 * dash))
            } else {
                drawLine(accent.copy(alpha = 0.18f), Offset(0f, y), Offset(size.width, y), stroke)
                if (kind == StatusKind.RELAYING) {
                    val packet = 10.dp.toPx()
                    repeat(PACKETS) { i ->
                        val x = ((phase + i.toFloat() / PACKETS) % 1f) * size.width
                        drawRoundRect(accent, Offset(x - packet / 2, y - packet / 2), Size(packet, packet), CornerRadius(3.dp.toPx()))
                    }
                }
            }
        }
        PipeNode(R.drawable.ic_monitor, pulsing = false, accent = accent)
    }
}

@Composable
private fun PipeNode(icon: Int, pulsing: Boolean, accent: Color) {
    val ring = if (pulsing) {
        rememberInfiniteTransition(label = "ring").animateFloat(0f, 1f, infiniteRepeatable(tween(RING_CYCLE_MS)), label = "ringPhase").value
    } else {
        -1f
    }
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(56.dp)) {
        if (ring >= 0f) {
            Box(Modifier.size(56.dp).scale(0.9f + ring * 0.35f).border(2.dp, accent.copy(alpha = 0.8f * (1 - ring)), RoundedCornerShape(22.dp)))
        }
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), modifier = Modifier.size(56.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(painterResource(icon), null, tint = MaterialTheme.colorScheme.onSurface) }
        }
    }
}

@Composable
private fun StatusChip(text: String, dot: Color) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(32.dp).padding(horizontal = 12.dp)) {
            Box(Modifier.size(8.dp).background(dot, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun StatusTiles(relay: RelayStatus, settings: AppSettings) {
    val history = remember { mutableStateListOf<Int>() }
    LaunchedEffect(relay.rttMs) {
        if (relay.rttMs >= 0) {
            history += relay.rttMs
            if (history.size > SPARK_BARS) history.removeAt(0)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoTile(stringResource(R.string.tile_latency), formatRtt(relay.rttMs), mono = true, modifier = Modifier.weight(1f)) { RttSpark(history) }
            InfoTile(
                stringResource(R.string.tile_target), appLabel(settings.targets.firstOrNull()),
                stringResource(if (relay.targetForeground) R.string.tile_target_front else R.string.tile_target_back), modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoTile(
                stringResource(R.string.tile_connection), relay.endpoint.substringBeforeLast(':').ifEmpty { "—" },
                stringResource(R.string.tile_connection_hint), modifier = Modifier.weight(1f),
            )
            InfoTile(stringResource(R.string.tile_shortcut), shortcutLabel(settings.toggleKey), mono = true, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun InfoTile(
    label: String,
    value: String,
    hint: String? = null,
    mono: Boolean = false,
    modifier: Modifier = Modifier,
    extra: @Composable () -> Unit = {},
) {
    KrCard(modifier.heightIn(min = 104.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = if (mono) MaterialTheme.typography.titleLarge.copy(fontFamily = JetBrainsMono) else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp),
        )
        hint?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)) }
        extra()
    }
}

@Composable
private fun RttSpark(history: List<Int>) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(26.dp).padding(top = 8.dp)) {
        val peak = (history.maxOrNull() ?: 1).coerceAtLeast(1)
        val bar = 6.dp.toPx()
        history.forEachIndexed { i, ms ->
            val h = (ms.toFloat() / peak).coerceIn(0.2f, 1f) * size.height
            drawRoundRect(color.copy(alpha = 0.8f), Offset(i * (bar + 3.dp.toPx()), size.height - h), Size(bar, h), CornerRadius(2.dp.toPx()))
        }
    }
}
```

- [ ] **Step 7: PC'ler, ayrıntı, onay penceresi**

`PairConfirmDialog.kt`:
```kotlin
package dev.keyrelay.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import dev.keyrelay.R
import dev.keyrelay.core.PairingInfo
import dev.keyrelay.core.PairingUri
import dev.keyrelay.service.PairResult
import dev.keyrelay.service.PcPairing
import dev.keyrelay.ui.theme.JetBrainsMono
import kotlinx.coroutines.launch

/** Every pairing passes through here: a scanned QR or an opened keyrelay:// link never pairs without this "Pair". */
@Composable
fun PairConfirmDialog(info: PairingInfo, onDone: (paired: Boolean) -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDone(false) },
        title = { Text(stringResource(R.string.pair_confirm_title, info.pcName)) },
        text = {
            Column {
                Text(stringResource(R.string.pair_confirm_body))
                Text(info.endpoints.joinToString("\n"), fontFamily = JetBrainsMono, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
            }
        },
        confirmButton = {
            Button(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    when (val result = PcPairing.pair(context, info)) {
                        is PairResult.Paired -> {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            onDone(true)
                        }
                        is PairResult.Rejected -> error = context.getString(R.string.pair_rejected)
                        is PairResult.Unreachable -> error = context.getString(R.string.pair_unreachable, result.detail)
                    }
                    busy = false
                }
            }) { Text(stringResource(R.string.pair_confirm)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { onDone(false) }) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Google code scanner: no camera permission, the scan UI runs in Play services. */
fun scanPairingQr(context: Context, onResult: (PairingInfo) -> Unit, onError: (String) -> Unit) {
    val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    GmsBarcodeScanning.getClient(context, options).startScan()
        .addOnSuccessListener { code ->
            val info = runCatching { PairingUri.parse(code.rawValue.orEmpty()) }.getOrNull()
            if (info != null) onResult(info) else onError(context.getString(R.string.pair_not_keyrelay))
        }
        .addOnFailureListener { onError(it.message.orEmpty()) }
}
```

`PcsScreen.kt`:
```kotlin
package dev.keyrelay.ui

import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.keyrelay.R
import dev.keyrelay.core.PairedPc
import dev.keyrelay.core.PairingInfo
import dev.keyrelay.service.AppState
import dev.keyrelay.settings.AppSettings
import dev.keyrelay.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PcsScreen(settings: AppSettings, onOpenPc: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val relay by AppState.relay.collectAsStateWithLifecycle()
    var scanned by remember { mutableStateOf<PairingInfo?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val apps by produceState(emptyList<AppEntry>()) { value = withContext(Dispatchers.IO) { InstalledApps.load(context) } }
    // Ordered once per load: a row must not jump away while the user is switching it.
    val ordered = remember(apps) { TargetApps.order(apps, settings.targets) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(contentPadding = WindowInsets.safeDrawing.asPaddingValues(), modifier = Modifier.padding(horizontal = 20.dp)) {
            item { ScreenTopBar(stringResource(R.string.pcs_title), onBack) }
            items(settings.pcs, key = { it.id }) { pc ->
                PcCard(pc, connected = relay.connected && pc.isDefault, rttMs = relay.rttMs) { onOpenPc(pc.id) }
            }
            if (settings.pcs.isEmpty()) {
                item { Text(stringResource(R.string.pcs_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp)) }
            }
            item { SectionTitle(stringResource(R.string.targets_title)) }
            items(ordered, key = { it.packageName }) { app ->
                TargetRow(app, app.packageName in settings.targets) { on ->
                    AppState.scope.launch {
                        SettingsStore.update(context) { s -> s.copy(targets = if (on) s.targets + app.packageName else s.targets - app.packageName) }
                    }
                }
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
        ExtendedFloatingActionButton(
            text = { Text(stringResource(R.string.pcs_add_qr)) },
            icon = { Icon(painterResource(R.drawable.ic_qr), null) },
            onClick = { scanPairingQr(context, onResult = { scanned = it }, onError = { message = it }) },
            modifier = Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(20.dp),
        )
    }
    scanned?.let { info -> PairConfirmDialog(info) { scanned = null } }
    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = { TextButton({ message = null }) { Text(stringResource(R.string.action_ok)) } },
            text = { Text(text) },
        )
    }
}

@Composable
private fun PcCard(pc: PairedPc, connected: Boolean, rttMs: Int, onClick: () -> Unit) {
    KrCard(Modifier.fillMaxWidth().padding(top = 12.dp).sharedPcBounds(pc.id), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(52.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_monitor), null) }
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(pc.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    if (connected) stringResource(R.string.pc_connected, formatRtt(rttMs))
                    else stringResource(R.string.pc_last_seen, DateUtils.getRelativeTimeSpanString(pc.lastSeenMs).toString()),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (pc.isDefault) {
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)) {
                    Text(stringResource(R.string.pc_default), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun TargetRow(app: AppEntry, checked: Boolean, onChange: (Boolean) -> Unit) {
    KrCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val icon = app.icon
            if (icon != null) Image(icon, null, Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))) else Spacer(Modifier.size(36.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(app.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.targets_hint), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}
```

`PcDetailScreen.kt`:
```kotlin
package dev.keyrelay.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.keyrelay.R
import dev.keyrelay.core.makeDefault
import dev.keyrelay.core.without
import dev.keyrelay.service.AppState
import dev.keyrelay.settings.AppSettings
import dev.keyrelay.settings.SettingsStore
import dev.keyrelay.ui.theme.JetBrainsMono
import kotlinx.coroutines.launch
import java.util.Base64

@Composable
fun PcDetailScreen(settings: AppSettings, id: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val pc = settings.pcs.firstOrNull { it.id == id }
    if (pc == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val relay by AppState.relay.collectAsStateWithLifecycle()
    var confirmRemove by remember { mutableStateOf(false) }
    val connected = relay.connected && pc.isDefault
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        ScreenTopBar(pc.name, onBack)
        KrCard(Modifier.fillMaxWidth().sharedPcBounds(pc.id)) {
            Text(stringResource(if (connected) R.string.pc_status_connected else R.string.pc_status_idle), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(pc.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 6.dp))
            Text(
                (if (pc.isDefault) stringResource(R.string.pc_default) + " · " else "") +
                    stringResource(R.string.pc_last_seen, DateUtils.getRelativeTimeSpanString(pc.lastSeenMs).toString()),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                (if (connected) formatRtt(relay.rttMs) + " · " else "") + stringResource(R.string.pc_tls),
                fontFamily = JetBrainsMono, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp),
            )
        }
        SectionTitle(stringResource(R.string.pc_addresses))
        pc.endpoints.forEach { e ->
            KrCard(Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(e.toString(), fontFamily = JetBrainsMono, style = MaterialTheme.typography.bodyMedium) }
        }
        SectionTitle(stringResource(R.string.pc_fingerprint))
        KrCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(Base64.getUrlEncoder().withoutPadding().encodeToString(pc.spkiSha256), fontFamily = JetBrainsMono, style = MaterialTheme.typography.bodySmall)
        }
        if (!pc.isDefault) {
            Button(
                onClick = { AppState.scope.launch { SettingsStore.update(context) { it.copy(pcs = it.pcs.makeDefault(pc.id)) } } },
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp).height(56.dp),
            ) { Text(stringResource(R.string.pc_make_default)) }
        }
        OutlinedButton(
            onClick = { confirmRemove = true },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        ) { Text(stringResource(R.string.pc_remove)) }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            text = { Text(stringResource(R.string.pc_remove_confirm, pc.name)) },
            confirmButton = {
                TextButton({
                    confirmRemove = false
                    AppState.scope.launch { SettingsStore.update(context) { it.copy(pcs = it.pcs.without(pc.id)) } }
                    onBack()
                }) { Text(stringResource(R.string.pc_remove)) }
            },
            dismissButton = { TextButton({ confirmRemove = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
```

- [ ] **Step 8: Gezinme ve etkinlik**

`KeyRelayApp.kt`:
```kotlin
package dev.keyrelay.ui

import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.IntOffset
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import dev.keyrelay.core.PairingInfo
import dev.keyrelay.settings.AppSettings

sealed interface Route {
    data object Status : Route
    data object Pcs : Route
    data class PcDetail(val id: String) : Route
    data object Settings : Route
    data object Wizard : Route
}

@Composable
fun KeyRelayApp(settings: AppSettings, pendingLink: PairingInfo?, onLinkHandled: () -> Unit) {
    val backStack = remember { mutableStateListOf<Route>(Route.Status) }
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedTransitionScope provides this) {
            NavDisplay(
                backStack = backStack,
                sharedTransitionScope = this,
                transitionSpec = { (slideInHorizontally(spatial) { it / 5 } + fadeIn(effects)) togetherWith fadeOut(effects) },
                popTransitionSpec = { fadeIn(effects) togetherWith (slideOutHorizontally(spatial) { it / 5 } + fadeOut(effects)) },
                entryProvider = entryProvider {
                    entry<Route.Status> { StatusScreen(settings, onOpenPcs = { backStack += Route.Pcs }, onOpenSettings = null) }
                    entry<Route.Pcs> { PcsScreen(settings, onOpenPc = { backStack += Route.PcDetail(it) }, onBack = { backStack.removeLastOrNull() }) }
                    entry<Route.PcDetail> { key -> PcDetailScreen(settings, key.id, onBack = { backStack.removeLastOrNull() }) }
                },
            )
        }
    }
    pendingLink?.let { info -> PairConfirmDialog(info) { onLinkHandled() } }
}
```
(Öngörülü geri: `NavDisplay`'in varsayılan `predictivePopTransitionSpec`'i ve T13'teki `enableOnBackInvokedCallback`.)

`MainActivity.kt`'yi şununla değiştir:
```kotlin
package dev.keyrelay

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.keyrelay.core.PairingInfo
import dev.keyrelay.core.PairingUri
import dev.keyrelay.service.RelayService
import dev.keyrelay.settings.SettingsStore
import dev.keyrelay.ui.KeyRelayApp
import dev.keyrelay.ui.theme.KeyRelayTheme

class MainActivity : ComponentActivity() {
    private var pendingLink by mutableStateOf<PairingInfo?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        pendingLink = linkFrom(intent)
        if (checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) RelayService.start(applicationContext)
        setContent {
            KeyRelayTheme {
                // The root Surface provides onBackground as content color; without it uncolored text and icons are black.
                Surface(color = MaterialTheme.colorScheme.background) {
                    val settings by remember { SettingsStore.flow(applicationContext) }.collectAsStateWithLifecycle(null)
                    settings?.let { KeyRelayApp(it, pendingLink) { pendingLink = null } }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        linkFrom(intent)?.let { pendingLink = it }
    }

    private fun linkFrom(intent: Intent?): PairingInfo? = intent?.dataString?.let { runCatching { PairingUri.parse(it) }.getOrNull() }

    private companion object {
        const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"
    }
}
```

- [ ] **Step 9: Metinler**

`values/strings.xml`'e:
```xml
    <string name="cd_back">Back</string>
    <string name="cd_pcs">PCs</string>
    <string name="cd_settings">Settings</string>
    <string name="routing_title">Relaying</string>
    <string name="routing_hint">When off, the keyboard stays on the tablet</string>
    <string name="headline_relaying">Your keys are on %1$s</string>
    <string name="headline_ready">Keyboard on the tablet</string>
    <string name="headline_connecting">Looking for the PC…</string>
    <string name="headline_pc_not_ready">The PC is not ready</string>
    <string name="headline_paused">Relaying paused</string>
    <string name="headline_starting">Starting</string>
    <string name="headline_no_wifi">Waiting for Wi-Fi</string>
    <string name="headline_approval">Approval needed</string>
    <string name="headline_error">Could not start</string>
    <string name="headline_not_paired">No PC yet</string>
    <string name="headline_repair">The PC no longer knows this tablet</string>
    <string name="sub_relaying">%1$s in front · Alt+Tab, Win and Ctrl+Alt+Del go to the PC</string>
    <string name="sub_ready">Switch to %1$s and keys go to the PC automatically</string>
    <string name="sub_repair">Pair it again from the PCs screen</string>
    <string name="sub_pc_not_ready">The PC cannot take keys right now, for example on a secure screen</string>
    <string name="chip_no_keyboard">No keyboard</string>
    <string name="chip_locked">Locked · secure</string>
    <string name="chip_unlocked">Not locked</string>
    <string name="action_add_pc">Add a PC</string>
    <string name="action_ok">OK</string>
    <string name="action_cancel">Cancel</string>
    <string name="tile_latency">Latency</string>
    <string name="tile_target">Target app</string>
    <string name="tile_target_front">in front · automatic</string>
    <string name="tile_target_back">in the background</string>
    <string name="tile_connection">Connection</string>
    <string name="tile_connection_hint">TLS · pinned certificate</string>
    <string name="tile_shortcut">Toggle shortcut</string>
    <string name="pcs_title">PCs</string>
    <string name="pcs_empty">No paired PC yet. Scan the QR code shown by KeyRelay on Windows.</string>
    <string name="pcs_add_qr">Add PC with QR</string>
    <string name="pc_default">Default</string>
    <string name="pc_connected">connected · %1$s</string>
    <string name="pc_last_seen">last seen %1$s</string>
    <string name="pc_status_connected">Connected</string>
    <string name="pc_status_idle">Not connected</string>
    <string name="pc_tls">TLS 1.3 · pinned</string>
    <string name="pc_addresses">Addresses · the first one is tried first</string>
    <string name="pc_fingerprint">Certificate fingerprint</string>
    <string name="pc_make_default">Make default</string>
    <string name="pc_remove">Remove PC</string>
    <string name="pc_remove_confirm">Remove %1$s? You will need to pair it again to use it.</string>
    <string name="targets_title">Target apps</string>
    <string name="targets_hint">Relay while this app is in front</string>
    <string name="pair_confirm_title">Pair with %1$s?</string>
    <string name="pair_confirm_body">Continue only if you opened this QR code on your own PC.</string>
    <string name="pair_confirm">Pair</string>
    <string name="pair_rejected">The PC did not accept the code. Open a new QR code and try again.</string>
    <string name="pair_unreachable">Could not reach the PC: %1$s</string>
    <string name="pair_not_keyrelay">This is not a KeyRelay QR code.</string>
```
`values-tr/strings.xml`'e:
```xml
    <string name="cd_back">Geri</string>
    <string name="cd_pcs">PC\'ler</string>
    <string name="cd_settings">Ayarlar</string>
    <string name="routing_title">Yönlendirme</string>
    <string name="routing_hint">Kapalıyken klavye tablette kalır</string>
    <string name="headline_relaying">Tuşların %1$s\'de</string>
    <string name="headline_ready">Klavye tablette</string>
    <string name="headline_connecting">PC aranıyor…</string>
    <string name="headline_pc_not_ready">PC hazır değil</string>
    <string name="headline_paused">Yönlendirme duraklatıldı</string>
    <string name="headline_starting">Başlatılıyor</string>
    <string name="headline_no_wifi">Wi-Fi bekleniyor</string>
    <string name="headline_approval">Onay bekleniyor</string>
    <string name="headline_error">Başlatılamadı</string>
    <string name="headline_not_paired">Henüz PC yok</string>
    <string name="headline_repair">PC bu tableti artık tanımıyor</string>
    <string name="sub_relaying">%1$s önde · Alt+Tab, Win ve Ctrl+Alt+Del PC\'ye gidiyor</string>
    <string name="sub_ready">%1$s\'a geçince tuşlar otomatik olarak PC\'ye gider</string>
    <string name="sub_repair">PC\'ler ekranından yeniden eşleştirin</string>
    <string name="sub_pc_not_ready">PC şu an tuş alamıyor, örneğin güvenli ekranda</string>
    <string name="chip_no_keyboard">Klavye yok</string>
    <string name="chip_locked">Kilitli · güvenli</string>
    <string name="chip_unlocked">Kilitli değil</string>
    <string name="action_add_pc">PC ekle</string>
    <string name="action_ok">Tamam</string>
    <string name="action_cancel">İptal</string>
    <string name="tile_latency">Gecikme</string>
    <string name="tile_target">Hedef uygulama</string>
    <string name="tile_target_front">önde · otomatik</string>
    <string name="tile_target_back">arkada</string>
    <string name="tile_connection">Bağlantı</string>
    <string name="tile_connection_hint">TLS · sabitlenmiş sertifika</string>
    <string name="tile_shortcut">Geçiş kısayolu</string>
    <string name="pcs_title">PC\'ler</string>
    <string name="pcs_empty">Henüz eşlenmiş PC yok. Windows\'taki KeyRelay penceresindeki QR kodu okutun.</string>
    <string name="pcs_add_qr">QR ile PC ekle</string>
    <string name="pc_default">Varsayılan</string>
    <string name="pc_connected">bağlı · %1$s</string>
    <string name="pc_last_seen">son görülme %1$s</string>
    <string name="pc_status_connected">Bağlı</string>
    <string name="pc_status_idle">Bağlı değil</string>
    <string name="pc_tls">TLS 1.3 · sabitlenmiş</string>
    <string name="pc_addresses">Adresler · ilk sıradaki önce denenir</string>
    <string name="pc_fingerprint">Sertifika parmak izi</string>
    <string name="pc_make_default">Varsayılan yap</string>
    <string name="pc_remove">PC\'yi kaldır</string>
    <string name="pc_remove_confirm">%1$s kaldırılsın mı? Kullanmak için yeniden eşleştirmeniz gerekir.</string>
    <string name="targets_title">Hedef uygulamalar</string>
    <string name="targets_hint">Önde olduğunda yönlendir</string>
    <string name="pair_confirm_title">%1$s ile eşleşilsin mi?</string>
    <string name="pair_confirm_body">Yalnız bu QR kodu kendi PC\'nizde açtıysanız devam edin.</string>
    <string name="pair_confirm">Eşleştir</string>
    <string name="pair_rejected">PC kodu kabul etmedi. Yeni bir QR kodu açıp tekrar deneyin.</string>
    <string name="pair_unreachable">PC\'ye ulaşılamadı: %1$s</string>
    <string name="pair_not_keyrelay">Bu bir KeyRelay QR kodu değil.</string>
```

- [ ] **Step 10: Testlerin geçtiğini gör, derle**

Run: `.\android\gradlew.bat -p android :core:test :app:testDebugUnitTest :app:assembleDebug`
Expected: `BUILD SUCCESSFUL` (StatusFormattingTest ve StringResourcesTest dahil); derleyici opt-in istemez.

- [ ] **Step 11: Cihazda ekranlar (controller kullanıcıyla)**

Kur, uygulamayı aç (T12'deki test sunucusu eşleşmesi duruyor). Kullanıcı: Durum ekranı (Hazır), TeamViewer'ı açıp dönünce Yönlendiriliyor (paket akışı, titreşim), PC'ler → kart → ayrıntı (paylaşılan öğe geçişi), geri hareketini yarıda bırakıp önizleme, hedef uygulamada bir anahtar, FAB → QR tarayıcı açılır (kapatılır). Ekran görüntüleri: `& $adb exec-out screencap -p > "$env:TEMP\p2-status.png"` (her ekran için ayrı ad). Kısa kayıt: `& $adb shell screenrecord --time-limit 15 /sdcard/p2-nav.mp4; & $adb pull /sdcard/p2-nav.mp4 $env:TEMP`. (Kablosuz hata ayıklama başlatmadan sonra kapalıdır; kullanıcı açar.) Controller görüntüleri kullanıcıya sunar; kullanıcı taslağa göre farkları söyler, düzeltmeler bu görevde yapılır.

- [ ] **Step 12: Commit**

```
& $git add android/gradle/libs.versions.toml android/app/build.gradle.kts android/app/src/main android/app/src/test/kotlin/dev/keyrelay/ui/StatusFormattingTest.kt
& $git commit -m "feat(android): durum ve pc ekranları eklendi" -m "- durum ekranı yönlendirmenin o anki halini canlı ve animasyonlu gösterir
- PC'ler QR ile eklenir, varsayılan seçilir, kaldırılır; kart ayrıntıya akarak açılır
- hangi uygulamalar öndeyken klavyenin PC'ye gideceği seçilir
- animasyonlar kapalıysa geçişler anında olur" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 15: Ekranlar B — kurulum sihirbazı (ADB eşleştirme, izin, Otomatik Engelleyici) ve Ayarlar

**Files:**
- Create: `android/app/src/main/kotlin/dev/keyrelay/ui/AdbPairing.kt` (`AdbPairing`, `PairingNotification`, `PairingCodeReceiver`), `WizardScreen.kt` (+ `AutoBlocker`), `SettingsScreen.kt` (+ `SettingsChoices`)
- Create: `android/app/src/main/res/drawable/ic_wifi.xml`
- Modify: `android/app/src/main/kotlin/dev/keyrelay/ui/KeyRelayApp.kt` (başlangıç rotası, Ayarlar ve Sihirbaz girdileri)
- Modify: `android/app/src/main/AndroidManifest.xml` (alıcı), `res/values/strings.xml`, `res/values-tr/strings.xml`
- Test: `android/app/src/test/kotlin/dev/keyrelay/ui/WizardSettingsTest.kt`

**Interfaces:**
- Consumes: T11 `AdbSetup.pair/grantSecureSettings`, `RelayService.start`, `Notifications.CHANNEL/createChannel`; T14 `KrCard`, `ScreenTopBar`, `SectionTitle`, `PairConfirmDialog`, `scanPairingQr`, `appLabel`, `shortcutLabel`; T10 `SettingsStore`; T4 `RelayStateMachine.TOGGLE_KEYS`; T5 `ConnectionConfig.DEFAULT_*`; T1 §4 (Otomatik Engelleyici).
- Produces: `object AdbPairing { CODE_LENGTH; state: MutableStateFlow<State>; startDiscovery(context); stopDiscovery(); hasPort(); isValidCode(code); suspend submit(context, code) }`; `object PairingNotification { KEY_CODE; show(context); update(context, state) }`; `class PairingCodeReceiver`; `@Composable fun WizardScreen(settings: AppSettings, linkPairs: Int, onFinished: () -> Unit)` (`linkPairs`: `KeyRelayApp`'teki bağlantı diyaloğunun başarılı eşleşme sayacı; adım 3 artınca ilerler); `@Composable fun SettingsScreen(settings, onRunWizard: () -> Unit, onBack: () -> Unit)`; `object SettingsChoices { PING_MS; PONG_MS; SEND_MS }`.
- Uygulama notu (T15 fix1/fix2, 2026-10-09): sihirbaz T14 v6.1 tasarımıyla yeniden kuruldu. Aşağıdaki kod blokları sözleşme ve davranış düzeltmeleri için güncellendi; görsel ayrıntının (illüstrasyonlar, `StepPage`, `CheckRow(Look, …)`, `GhostButton`) bağlayıcı hali çalışma ağacındaki `ui/WizardScreen.kt`, `ui/AdbPairing.kt`, `ui/SettingsScreen.kt`'dir. Ek davranışlar: `AdbPairing.searching` ve `PairingNotification.cancel`; başarısız sonuç bildirimi keşif sürerken kod girişli, değilse "KeyRelay'i aç" eylemli ve süreli; `AppSettings.withTimes` (ayar yazımında ping < pong doğrulaması).

- [ ] **Step 1: Failing test**

`WizardSettingsTest.kt`:
```kotlin
package dev.keyrelay.ui

import dev.keyrelay.core.ConnectionConfig
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WizardSettingsTest {
    @Test
    fun choices_includeTheDefaults() {
        assertTrue(ConnectionConfig.DEFAULT_PING_INTERVAL_MS in SettingsChoices.PING_MS)
        assertTrue(ConnectionConfig.DEFAULT_PONG_TIMEOUT_MS in SettingsChoices.PONG_MS)
        assertTrue(ConnectionConfig.DEFAULT_SEND_TIMEOUT_MS in SettingsChoices.SEND_MS)
    }

    @Test
    fun choices_everyPingHasALongerPong() = assertTrue(SettingsChoices.PING_MS.all { p -> SettingsChoices.PONG_MS.any { it > p } })

    @Test
    fun isValidCode_sixDigitsOnly() {
        assertTrue(AdbPairing.isValidCode("482931"))
        assertFalse(AdbPairing.isValidCode("48293"))
        assertFalse(AdbPairing.isValidCode("4829310"))
        assertFalse(AdbPairing.isValidCode("48a931"))
    }
}
```

- [ ] **Step 2: Çalıştır, başarısız olduğunu gör**

Run: `.\android\gradlew.bat -p android :app:testDebugUnitTest --tests "dev.keyrelay.ui.WizardSettingsTest"`
Expected: derleme hatası (`SettingsChoices`, `AdbPairing` yok).

- [ ] **Step 3: `AdbPairing.kt`**

```kotlin
package dev.keyrelay.ui

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import dev.keyrelay.R
import dev.keyrelay.launcher.AdbSetup
import dev.keyrelay.service.AppState
import dev.keyrelay.service.Notifications
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Wizard step 2: finds the pairing port over mDNS and pairs with the code typed on screen or in the notification. */
object AdbPairing {
    const val CODE_LENGTH = 6

    sealed interface State {
        data object Searching : State
        data class Found(val host: String, val port: Int) : State
        data object Working : State
        data object Done : State
        data class Failed(val detail: String) : State
    }

    val state = MutableStateFlow<State>(State.Searching)
    @Volatile private var found: State.Found? = null
    private var mdns: AdbMdns? = null

    fun startDiscovery(context: Context) {
        stopDiscovery()
        found = null
        state.value = State.Searching
        mdns = AdbMdns(context, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { host, port ->
            if (host != null && port > 0) {
                val f = State.Found(host.hostAddress.orEmpty(), port)
                found = f
                state.value = f
            }
        }.also { it.start() }
    }

    fun stopDiscovery() {
        mdns?.stop()
        mdns = null
    }

    fun hasPort() = found != null

    fun isValidCode(code: String) = code.length == CODE_LENGTH && code.all(Char::isDigit)

    /** Pairs, then grants WRITE_SECURE_SETTINGS over the same wireless debugging the user has open. */
    suspend fun submit(context: Context, code: String) {
        val target = found
        if (target == null || !isValidCode(code)) {
            state.value = State.Failed(context.getString(if (target == null) R.string.wizard_pair_no_port else R.string.wizard_pair_bad_code))
            PairingNotification.update(context, state.value)
            return
        }
        state.value = State.Working
        state.value = try {
            check(AdbSetup.pair(context, target.host, target.port, code)) { "pairing refused" }
            check(AdbSetup.grantSecureSettings(context)) { "permission not granted" }
            stopDiscovery()
            State.Done
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            State.Failed(e.message ?: e.javaClass.simpleName)
        }
        PairingNotification.update(context, state.value)
    }
}

/** Code entry in the notification shade: works while the Settings pairing dialog covers KeyRelay. */
object PairingNotification {
    const val KEY_CODE = "code"
    private const val ID = 2
    private const val RESULT_VISIBLE_MS = 10_000L

    fun show(context: Context) {
        val input = RemoteInput.Builder(KEY_CODE).setLabel(context.getString(R.string.wizard_code_label)).build()
        // Mutable so the system can add the typed text; the explicit target keeps that safe.
        val reply = PendingIntent.getBroadcast(
            context, 0, Intent(context, PairingCodeReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val action = Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stat_keyrelay), context.getString(R.string.wizard_code_action), reply)
            .addRemoteInput(input).build()
        post(
            context,
            base(context).setContentTitle(context.getString(R.string.wizard_code_notification_title))
                .setContentText(context.getString(R.string.wizard_code_notification_text)).addAction(action).build(),
        )
    }

    fun update(context: Context, state: AdbPairing.State) {
        val text = when (state) {
            AdbPairing.State.Done -> context.getString(R.string.wizard_pair_done)
            is AdbPairing.State.Failed -> context.getString(R.string.wizard_pair_failed, state.detail)
            else -> return
        }
        post(context, base(context).setContentTitle(text).setTimeoutAfter(RESULT_VISIBLE_MS).build())
    }

    private fun base(context: Context) = Notification.Builder(context, Notifications.CHANNEL).setSmallIcon(R.drawable.ic_stat_keyrelay).setOnlyAlertOnce(true)

    private fun post(context: Context, notification: Notification) {
        Notifications.createChannel(context)
        context.getSystemService(NotificationManager::class.java).notify(ID, notification)
    }
}

class PairingCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(PairingNotification.KEY_CODE)?.toString()?.trim().orEmpty()
        val app = context.applicationContext
        AppState.scope.launch { AdbPairing.submit(app, code) }
    }
}
```
Manifest `<application>` içine: `<receiver android:name=".ui.PairingCodeReceiver" android:exported="false" />`.

- [ ] **Step 4: Otomatik Engelleyici — T1 §4 sonucuna göre tek dal**

`WizardScreen.kt`'nin sonuna **yalnız birini** ekle.

(A) §4'te okunabilir bir ayar anahtarı bulunduysa — `SETTING`, `ON` ve `SCREEN` değerleri §4 tablosundan aynen kopyalanır; anahtar `secure` dışındaki bir ad alanındaysa `Settings.Secure` o ad alanının sınıfıyla (`Settings.Global` / `Settings.System`) değiştirilir:
```kotlin
/** Samsung Auto Blocker; the setting and its screen were found on the device in P2 Task 1 (measurements section 4). */
private object AutoBlocker {
    private const val SETTING = "<§4 tablosundaki anahtar adı>"
    private const val ON = 1
    private const val SCREEN = "<§4 tablosundaki etkinlik sınıf adı>"

    fun shouldWarn(context: Context) = Settings.Secure.getInt(context.contentResolver, SETTING, 0) == ON

    fun settingsIntent(): Intent = Intent().setClassName("com.android.settings", SCREEN)
}
```
(B) §4'te okunabilir anahtar yoksa:
```kotlin
/** Samsung Auto Blocker has no readable setting (P2 Task 1, section 4): every Samsung device gets the warning. */
private object AutoBlocker {
    fun shouldWarn(context: Context) = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    fun settingsIntent(): Intent = Intent(Settings.ACTION_SECURITY_SETTINGS)
}
```

- [ ] **Step 5: `WizardScreen.kt` (taslağın 4 adımı)**

`res/drawable/ic_wifi.xml` (taslaktaki SVG):
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:strokeColor="#FF000000" android:strokeWidth="1.6" android:strokeLineCap="round" android:fillColor="#00000000"
        android:pathData="M5 12.55a11 11 0 0 1 14.08 0M1.42 9a16 16 0 0 1 21.16 0M8.53 16.11a6 6 0 0 1 6.95 0M12 20h.01" />
</vector>
```
`WizardScreen.kt` (Step 4'teki `AutoBlocker` dosyanın sonundadır):
```kotlin
package dev.keyrelay.ui

import android.Manifest
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.keyrelay.R
import dev.keyrelay.core.PairingInfo
import dev.keyrelay.core.StartupFlow
import dev.keyrelay.service.AppState
import dev.keyrelay.service.RelayService
import dev.keyrelay.settings.AppSettings
import dev.keyrelay.settings.SettingsStore
import dev.keyrelay.ui.theme.JetBrainsMono
import dev.keyrelay.ui.theme.LocalStatusColors
import kotlinx.coroutines.launch

private const val STEP_COUNT = 4
private const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"
private const val SCAN_CYCLE_MS = 2_200
private const val SCAN_FROM = 0.18f
private const val SCAN_TO = 0.82f
private const val SCAN_INSET = 0.12f

@Composable
fun WizardScreen(settings: AppSettings, linkPairs: Int, onFinished: () -> Unit) {
    val app = LocalContext.current.applicationContext
    var step by rememberSaveable { mutableIntStateOf(0) }
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    BackHandler(enabled = step > 0) { step-- }
    // Own Surface: a background modifier gives no content color, and uncolored text would be black on the dark theme.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp, vertical = 16.dp)) {
            StepBars(step)
            AnimatedContent(
                step,
                transitionSpec = {
                    val direction = if (targetState > initialState) 1 else -1
                    (slideInHorizontally(spatial) { direction * it / 6 } + fadeIn()) togetherWith fadeOut()
                },
                label = "wizardStep",
            ) { s ->
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    when (s) {
                        0 -> DeveloperStep(onNext = { step = 1 })
                        1 -> PairingStep(onNext = { step = 2 }, onBack = { step = 0 })
                        2 -> PcStep(linkPairs, onNext = { step = 3 }, onBack = { step = 1 })
                        else -> DoneStep(settings, onBack = { step = 2 }, onFinish = {
                            AppState.scope.launch {
                                SettingsStore.update(app) { it.copy(onboardingDone = true) }
                                RelayService.start(app)
                                onFinished()
                            }
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun StepBars(step: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 22.dp)) {
        repeat(STEP_COUNT) { i ->
            val fill by animateFloatAsState(if (i <= step) 1f else 0f, MaterialTheme.motionScheme.slowSpatialSpec(), label = "bar$i")
            Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.outlineVariant)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fill).background(MaterialTheme.colorScheme.primary))
            }
        }
    }
}

@Composable
private fun StepPage(
    illustration: Painter,
    title: String,
    body: String,
    primary: String,
    onPrimary: () -> Unit,
    onBack: (() -> Unit)?,
    primaryEnabled: Boolean = true,
    scanning: Boolean = false,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    Column {
        Box(
            Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(28.dp))
                .background(Brush.radialGradient(listOf(colors.primaryContainer, colors.surfaceContainer))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(illustration, null, Modifier.size(110.dp), tint = colors.primary)
            if (scanning) ScanLine()
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 22.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        extra()
        Button(onPrimary, enabled = primaryEnabled, modifier = Modifier.fillMaxWidth().padding(top = 20.dp).height(56.dp)) { Text(primary) }
        onBack?.let { OutlinedButton(it, Modifier.fillMaxWidth().padding(top = 10.dp)) { Text(stringResource(R.string.cd_back)) } }
    }
}

/** The mockup's QR step: a green line sweeps over the illustration; no line when animations are off. */
@Composable
private fun BoxScope.ScanLine() {
    if (!ValueAnimator.areAnimatorsEnabled()) return
    val ready = LocalStatusColors.current.ready
    val y by rememberInfiniteTransition(label = "scan").animateFloat(
        SCAN_FROM, SCAN_TO, infiniteRepeatable(tween(SCAN_CYCLE_MS, easing = MOCKUP_EASE), RepeatMode.Reverse), label = "scanY",
    )
    Canvas(Modifier.matchParentSize()) {
        val left = size.width * SCAN_INSET
        val right = size.width - left
        val at = size.height * y
        drawLine(Brush.horizontalGradient(listOf(Color.Transparent, ready, Color.Transparent), left, right), Offset(left, at), Offset(right, at), 2.dp.toPx())
    }
}

@Composable
private fun CheckRow(done: Boolean, title: String, hint: String) {
    val status = LocalStatusColors.current
    KrCard(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                if (done) Icons.Rounded.CheckCircle else Icons.Rounded.Warning, null,
                tint = if (done) status.ready else status.waiting, modifier = Modifier.size(28.dp),
            )
            Column(Modifier.padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                if (hint.isNotEmpty()) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// Indirect, false = unknown: DEVELOPMENT_SETTINGS_ENABLED is always 0 for third-party apps (developer.android.com/reference/android/provider/Settings.Global).
private fun developerOptionsOn(context: Context): Boolean {
    val r = context.contentResolver
    fun on(key: String) = runCatching { Settings.Global.getInt(r, key, 0) == 1 }.getOrDefault(false)
    // adb_wifi_enabled is a hidden key; a newer platform may refuse it with SecurityException, hence runCatching.
    return on(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) || on(Settings.Global.ADB_ENABLED) || on(StartupFlow.ADB_WIFI_ENABLED)
}

@Composable
private fun DeveloperStep(onNext: () -> Unit) {
    val context = LocalContext.current
    var devOn by remember { mutableStateOf(developerOptionsOn(context)) }
    var blocker by remember { mutableStateOf(AutoBlocker.shouldWarn(context)) }
    LifecycleResumeEffect(Unit) {
        devOn = developerOptionsOn(context)
        blocker = AutoBlocker.shouldWarn(context)
        onPauseOrDispose { }
    }
    StepPage(
        rememberVectorPainter(Icons.Rounded.Star), stringResource(R.string.wizard_dev_title), stringResource(R.string.wizard_dev_body),
        // Never locked: an unknown state is not "off", and the next step needs wireless debugging anyway (the real gate).
        primary = stringResource(R.string.wizard_next), onPrimary = onNext, onBack = null,
    ) {
        CheckRow(devOn, stringResource(if (devOn) R.string.wizard_dev_on else R.string.wizard_dev_unknown), if (devOn) "" else stringResource(R.string.wizard_dev_hint))
        val action = if (devOn) Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS else Settings.ACTION_DEVICE_INFO_SETTINGS
        OutlinedButton({ context.startActivity(Intent(action)) }, Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Text(stringResource(if (devOn) R.string.wizard_open_developer else R.string.wizard_open_about))
        }
        if (blocker) {
            CheckRow(false, stringResource(R.string.wizard_blocker_title), stringResource(R.string.wizard_blocker_body))
            OutlinedButton({ context.startActivity(AutoBlocker.settingsIntent()) }, Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Text(stringResource(R.string.wizard_blocker_open))
            }
        }
    }
}

@Composable
private fun PairingStep(onNext: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val state by AdbPairing.state.collectAsStateWithLifecycle()
    var code by rememberSaveable { mutableStateOf("") }
    // Read once per visit: a pairing finished during this visit moves the wizard on by itself.
    val alreadyDone = remember { AdbSetup.hasSecureSettingsPermission(context) }
    // null: not asked yet in this visit; asked only from the row that says what the notification is for.
    var notifyAnswer by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val notifyMissing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notifyAnswer = granted
        if (granted) PairingNotification.show(app)
    }
    DisposableEffect(Unit) {
        if (!alreadyDone && !notifyMissing) PairingNotification.show(app)
        if (!alreadyDone) AdbPairing.startDiscovery(app)
        onDispose {
            AdbPairing.stopDiscovery()
            if (AdbPairing.state.value != AdbPairing.State.Done) PairingNotification.cancel(app)
        }
    }
    LaunchedEffect(state) {
        if (alreadyDone || state != AdbPairing.State.Done) return@LaunchedEffect
        delay(DONE_HOLD_MS)
        onNext()
    }
    val statusText = when (val s = state) {
        AdbPairing.State.Searching -> stringResource(R.string.wizard_pair_searching)
        is AdbPairing.State.Found -> stringResource(R.string.wizard_pair_found, s.port)
        AdbPairing.State.Working -> stringResource(R.string.wizard_pair_working)
        AdbPairing.State.Done -> stringResource(R.string.wizard_pair_done)
        is AdbPairing.State.Failed -> stringResource(R.string.wizard_pair_failed, s.detail)
    }
    StepPage(
        painterResource(R.drawable.ic_wifi), stringResource(R.string.wizard_pair_title), stringResource(R.string.wizard_pair_body),
        primary = stringResource(if (alreadyDone) R.string.wizard_next else R.string.wizard_pair_action),
        onPrimary = { if (alreadyDone) onNext() else AppState.scope.launch { AdbPairing.submit(app, code) } },
        onBack = onBack,
        primaryEnabled = alreadyDone || (AdbPairing.hasPort() && state != AdbPairing.State.Working && AdbPairing.isValidCode(code)),
    ) {
        if (alreadyDone) {
            CheckRow(true, stringResource(R.string.wizard_pair_already), "")
        } else {
            CheckRow(state == AdbPairing.State.Done, statusText, stringResource(R.string.wizard_pair_hint))
            CodeField(code) { code = it }
            if (notifyMissing && notifyAnswer != true) {
                // Refused: the boxes above still work; the row only explains why the notification is missing.
                CheckRow(false, stringResource(R.string.wizard_notify_title), stringResource(if (notifyAnswer == false) R.string.wizard_notify_denied else R.string.wizard_notify_body))
                if (notifyAnswer == null) OutlinedButton({ notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }, Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Text(stringResource(R.string.wizard_notify_allow))
                }
            }
        }
        OutlinedButton({ context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }, Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Text(stringResource(R.string.wizard_open_developer))
        }
    }
}

@Composable
private fun CodeField(code: String, onChange: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val label = stringResource(R.string.wizard_code_label)
    BasicTextField(
        value = code,
        onValueChange = { v -> onChange(v.filter { it in '0'..'9' }.take(AdbPairing.CODE_LENGTH)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        // A one-time code, not a password: keeps password managers from offering to save it.
        modifier = Modifier.padding(top = 12.dp).semantics {
            contentDescription = label
            contentType = ContentType.SmsOtpCode
        },
        decorationBox = { inner ->
            Box {
                // The real field stays composed (it owns focus and the keyboard) but draws nothing; the boxes show the digits.
                Box(Modifier.alpha(0f)) { inner() }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(AdbPairing.CODE_LENGTH) { i ->
                        val current = i == code.length
                        Box(
                            Modifier.size(44.dp, 56.dp).background(colors.surfaceContainer, RoundedCornerShape(14.dp))
                                .border(if (current) 2.dp else 1.dp, if (current) colors.primary else colors.outlineVariant, RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                code.getOrNull(i)?.toString().orEmpty(),
                                style = MaterialTheme.typography.headlineSmall.copy(fontFamily = JetBrainsMono), fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun PcStep(linkPairs: Int, onNext: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    // A PC paired from a keyrelay:// link uses the app-level dialog, not this step's; its closing moves the step on too.
    val linkPairsAtStart = remember { linkPairs }
    LaunchedEffect(linkPairs) { if (linkPairs != linkPairsAtStart) onNext() }
    var scanned by rememberSaveable(stateSaver = PairingInfoSaver) { mutableStateOf<PairingInfo?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    StepPage(
        painterResource(R.drawable.ic_qr), stringResource(R.string.wizard_pc_title), stringResource(R.string.wizard_pc_body),
        primary = stringResource(R.string.wizard_pc_scan),
        onPrimary = { scanPairingQr(context, onResult = { scanned = it }, onError = { message = it }) },
        onBack = onBack,
        scanning = true,
    ) {
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
        TextButton(onNext, Modifier.fillMaxWidth().padding(top = 10.dp)) { Text(stringResource(R.string.wizard_pc_later)) }
    }
    scanned?.let { info ->
        PairConfirmDialog(info) { paired, _ ->
            scanned = null
            if (paired) onNext()
        }
    }
}

@Composable
private fun DoneStep(settings: AppSettings, onBack: () -> Unit, onFinish: () -> Unit) {
    StepPage(
        rememberVectorPainter(Icons.Rounded.CheckCircle), stringResource(R.string.wizard_done_title),
        stringResource(R.string.wizard_done_body, appLabel(settings.targets.firstOrNull())),
        primary = stringResource(R.string.wizard_finish), onPrimary = onFinish, onBack = onBack,
    ) {
        CheckRow(true, stringResource(R.string.wizard_done_check), stringResource(R.string.wizard_done_hint))
    }
}
```

- [ ] **Step 6: `SettingsScreen.kt`**

```kotlin
package dev.keyrelay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.keyrelay.R
import dev.keyrelay.core.RelayStateMachine
import dev.keyrelay.service.AppState
import dev.keyrelay.settings.AppSettings
import dev.keyrelay.settings.SettingsStore
import dev.keyrelay.ui.theme.JetBrainsMono
import kotlinx.coroutines.launch
import java.text.NumberFormat

/** The values the Settings screen offers; defaults live in ConnectionConfig. */
object SettingsChoices {
    val PING_MS = longArrayOf(2_000, 5_000, 10_000)
    val PONG_MS = longArrayOf(5_000, 10_000, 20_000)
    val SEND_MS = longArrayOf(500, 1_000, 2_000)
}

@Composable
fun SettingsScreen(settings: AppSettings, onRunWizard: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val relay by AppState.relay.collectAsStateWithLifecycle()
    val save: ((AppSettings) -> AppSettings) -> Unit = { transform -> AppState.scope.launch { SettingsStore.update(context, transform) } }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        ScreenTopBar(stringResource(R.string.settings_title), onBack)
        SectionTitle(stringResource(R.string.settings_connection))
        // A reply wait not longer than the ping interval would drop healthy connections; such pairs are disabled.
        ChoiceRow(R.string.settings_ping, R.string.settings_ping_hint, SettingsChoices.PING_MS, settings.pingIntervalMs, { it < settings.pongTimeoutMs }) { v ->
            save { it.copy(pingIntervalMs = v) }
        }
        ChoiceRow(R.string.settings_pong, R.string.settings_pong_hint, SettingsChoices.PONG_MS, settings.pongTimeoutMs, { it > settings.pingIntervalMs }) { v ->
            save { it.copy(pongTimeoutMs = v) }
        }
        ChoiceRow(R.string.settings_send, R.string.settings_send_hint, SettingsChoices.SEND_MS, settings.sendTimeoutMs, { true }) { v ->
            save { it.copy(sendTimeoutMs = v) }
        }
        SectionTitle(stringResource(R.string.settings_shortcut))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            RelayStateMachine.TOGGLE_KEYS.forEachIndexed { i, key ->
                SegmentedButton(
                    selected = settings.toggleKey == key,
                    onClick = { save { it.copy(toggleKey = key) } },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = RelayStateMachine.TOGGLE_KEYS.size),
                    label = { Text(shortcutLabel(key), fontFamily = JetBrainsMono) },
                )
            }
        }
        SectionTitle(stringResource(R.string.settings_keyboards))
        if (relay.keyboards.isEmpty()) {
            Text(stringResource(R.string.settings_no_keyboard), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        relay.keyboards.forEach { name ->
            KrCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.settings_keyboard_hint), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SectionTitle(stringResource(R.string.settings_setup))
        OutlinedButton(onRunWizard, Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_run_wizard)) }
        Text(
            stringResource(R.string.about_fonts), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 18.dp),
        )
    }
}

@Composable
private fun ChoiceRow(title: Int, hint: Int, options: LongArray, selected: Long, allowed: (Long) -> Boolean, onSelect: (Long) -> Unit) {
    Text(stringResource(title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
    Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        options.forEachIndexed { i, ms ->
            SegmentedButton(
                selected = ms == selected,
                onClick = { onSelect(ms) },
                enabled = allowed(ms),
                shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                label = { Text(stringResource(R.string.unit_seconds, NumberFormat.getInstance().format(ms / 1000.0))) },
            )
        }
    }
}
```

- [ ] **Step 7: Gezinmeye ekle**

`KeyRelayApp.kt`'de:
- `mutableStateListOf<Route>(Route.Status)` → `mutableStateListOf<Route>(if (settings.onboardingDone) Route.Status else Route.Wizard)`
- `entry<Route.Status>` satırında `onOpenSettings = null` → `onOpenSettings = { backStack += Route.Settings }`
- `backStack`'ten önce: `var linkPairs by rememberSaveable { mutableIntStateOf(0) }`; bağlantı diyaloğu `PairConfirmDialog(info) { paired, _ -> if (paired) linkPairs++; onLinkHandled() }`
- `entryProvider` bloğuna ekle:
```kotlin
                    entry<Route.Settings> { SettingsScreen(settings, onRunWizard = { backStack += Route.Wizard }, onBack = { backStack.removeLastOrNull() }) }
                    entry<Route.Wizard> {
                        WizardScreen(settings, linkPairs) {
                            backStack.clear()
                            backStack += Route.Status
                        }
                    }
```

- [ ] **Step 8: Metinler**

`values/strings.xml`'e:
```xml
    <string name="wizard_next">Continue</string>
    <string name="wizard_dev_title">Developer options</string>
    <string name="wizard_dev_body">KeyRelay uses Android\'s wireless debugging to take over the keyboard. You turn it on once; no computer needed.</string>
    <string name="wizard_dev_on">Developer options are on</string>
    <string name="wizard_dev_unknown">Could not check developer options</string>
    <string name="wizard_dev_hint">If they are off: Settings → About tablet → Software information → Build number × 7</string>
    <string name="wizard_open_about">Open About tablet</string>
    <string name="wizard_open_developer">Open developer options</string>
    <string name="wizard_blocker_title">Samsung Auto Blocker</string>
    <string name="wizard_blocker_body">Auto Blocker can stop wireless debugging. Turn it off for KeyRelay to start.</string>
    <string name="wizard_blocker_open">Open Auto Blocker</string>
    <string name="wizard_pair_title">Pairing code</string>
    <string name="wizard_pair_body">In Settings, tap \"Wireless debugging → Pair device with pairing code\". KeyRelay finds the port itself; type only the 6-digit code here or in the notification.</string>
    <string name="wizard_pair_hint">Keep the pairing dialog open next to KeyRelay (split screen) or use the notification</string>
    <string name="wizard_pair_searching">Looking for the pairing port…</string>
    <string name="wizard_pair_found">Pairing port found: %1$d</string>
    <string name="wizard_pair_working">Pairing…</string>
    <string name="wizard_pair_done">Paired, permission granted</string>
    <string name="wizard_pair_failed">Pairing failed: %1$s</string>
    <string name="wizard_pair_no_port">The pairing port has not been found yet</string>
    <string name="wizard_pair_bad_code">Type the 6-digit code</string>
    <string name="wizard_pair_already">Already paired, permission granted</string>
    <string name="wizard_pair_action">Pair</string>
    <string name="wizard_code_label">6-digit code</string>
    <string name="wizard_code_action">Enter code</string>
    <string name="wizard_code_notification_title">KeyRelay pairing</string>
    <string name="wizard_code_notification_text">Type the code shown by Wireless debugging</string>
    <string name="wizard_pc_title">Connect your PC</string>
    <string name="wizard_pc_body">In KeyRelay on Windows, open \"Pair\" and scan the QR code. The connection is end-to-end encrypted.</string>
    <string name="wizard_pc_scan">Scan QR</string>
    <string name="wizard_pc_later">Not now</string>
    <string name="wizard_done_title">You\'re ready</string>
    <string name="wizard_done_body">KeyRelay starts by itself when the tablet starts. While %1$s is in front, every shortcut works on the PC.</string>
    <string name="wizard_done_check">Starts automatically</string>
    <string name="wizard_done_hint">Developer options and USB debugging stay on. KeyRelay turns wireless debugging on only while starting and then off again, also if you had turned it on yourself.</string>
    <string name="wizard_finish">Finish</string>
    <string name="settings_title">Settings</string>
    <string name="settings_connection">Connection</string>
    <string name="settings_ping">Liveness interval</string>
    <string name="settings_ping_hint">How often the PC is checked</string>
    <string name="settings_pong">Reply wait</string>
    <string name="settings_pong_hint">Without a reply in this time, the keyboard returns to the tablet</string>
    <string name="settings_send">Send limit</string>
    <string name="settings_send_hint">A stuck connection counts as dropped after this time</string>
    <string name="settings_shortcut">Toggle shortcut</string>
    <string name="settings_keyboards">Keyboards</string>
    <string name="settings_keyboard_hint">Taken while relaying</string>
    <string name="settings_no_keyboard">No keyboard found</string>
    <string name="settings_setup">Setup</string>
    <string name="settings_run_wizard">Run the setup wizard again</string>
    <string name="unit_seconds">%1$s s</string>
```
`values-tr/strings.xml`'e:
```xml
    <string name="wizard_next">Devam</string>
    <string name="wizard_dev_title">Geliştirici seçenekleri</string>
    <string name="wizard_dev_body">KeyRelay klavyeyi kilitleyebilmek için Android\'in kablosuz hata ayıklamasını kullanır. Bunu bir kez açman yeter; bilgisayar gerekmez.</string>
    <string name="wizard_dev_on">Geliştirici seçenekleri açık</string>
    <string name="wizard_dev_unknown">Geliştirici seçenekleri doğrulanamadı</string>
    <string name="wizard_dev_hint">Açık değilse: Ayarlar → Tablet hakkında → Yazılım bilgileri → Yapım numarası × 7</string>
    <string name="wizard_open_about">Tablet hakkında\'yı aç</string>
    <string name="wizard_open_developer">Geliştirici seçeneklerini aç</string>
    <string name="wizard_blocker_title">Samsung Otomatik Engelleyici</string>
    <string name="wizard_blocker_body">Otomatik Engelleyici kablosuz hata ayıklamayı durdurabilir. KeyRelay\'in başlayabilmesi için kapatın.</string>
    <string name="wizard_blocker_open">Otomatik Engelleyici\'yi aç</string>
    <string name="wizard_pair_title">Eşleştirme kodu</string>
    <string name="wizard_pair_body">Ayarlar\'da \"Kablosuz hata ayıklama → Eşleştirme koduyla eşle\"ye dokun. KeyRelay portu kendisi bulur; sadece 6 haneli kodu buraya ya da bildirime yaz.</string>
    <string name="wizard_pair_hint">Eşleştirme penceresini KeyRelay ile yan yana (bölünmüş ekran) açık tut ya da bildirimi kullan</string>
    <string name="wizard_pair_searching">Eşleştirme portu aranıyor…</string>
    <string name="wizard_pair_found">Eşleştirme portu bulundu: %1$d</string>
    <string name="wizard_pair_working">Eşleştiriliyor…</string>
    <string name="wizard_pair_done">Eşleşildi, izin verildi</string>
    <string name="wizard_pair_failed">Eşleştirme başarısız: %1$s</string>
    <string name="wizard_pair_no_port">Eşleştirme portu henüz bulunamadı</string>
    <string name="wizard_pair_bad_code">6 haneli kodu yazın</string>
    <string name="wizard_pair_already">Zaten eşleşilmiş, izin verilmiş</string>
    <string name="wizard_pair_action">Eşleştir</string>
    <string name="wizard_code_label">6 haneli kod</string>
    <string name="wizard_code_action">Kodu gir</string>
    <string name="wizard_code_notification_title">KeyRelay eşleştirme</string>
    <string name="wizard_code_notification_text">Kablosuz hata ayıklamanın gösterdiği kodu yaz</string>
    <string name="wizard_pc_title">PC\'ni bağla</string>
    <string name="wizard_pc_body">Windows\'taki KeyRelay penceresinde \"Eşleştir\"i aç ve QR kodu okut. Bağlantı uçtan uca şifrelidir.</string>
    <string name="wizard_pc_scan">QR okut</string>
    <string name="wizard_pc_later">Şimdi değil</string>
    <string name="wizard_done_title">Hazırsın</string>
    <string name="wizard_done_body">Tablet açıldığında KeyRelay kendiliğinden başlar. %1$s önde oldukça tüm kısayolların PC\'de çalışır.</string>
    <string name="wizard_done_check">Açılışta otomatik başlar</string>
    <string name="wizard_done_hint">Geliştirici seçenekleri ve USB hata ayıklama açık kalır. Kablosuz hata ayıklama yalnız başlatırken açılır ve hemen kapanır; kendin açık tuttuysan o da kapanır.</string>
    <string name="wizard_finish">Bitir</string>
    <string name="settings_title">Ayarlar</string>
    <string name="settings_connection">Bağlantı</string>
    <string name="settings_ping">Canlılık aralığı</string>
    <string name="settings_ping_hint">PC\'ye ne sıklıkla yoklama gider</string>
    <string name="settings_pong">Yanıt bekleme</string>
    <string name="settings_pong_hint">Bu sürede yanıt gelmezse klavye tablete döner</string>
    <string name="settings_send">Gönderme sınırı</string>
    <string name="settings_send_hint">Takılan bağlantı bu sürede düşmüş sayılır</string>
    <string name="settings_shortcut">Geçiş kısayolu</string>
    <string name="settings_keyboards">Klavyeler</string>
    <string name="settings_keyboard_hint">Yönlendirilirken yakalanır</string>
    <string name="settings_no_keyboard">Klavye bulunamadı</string>
    <string name="settings_setup">Kurulum</string>
    <string name="settings_run_wizard">Kurulum sihirbazını yeniden çalıştır</string>
    <string name="unit_seconds">%1$s sn</string>
```
T1 §4'e göre Otomatik Engelleyici kablosuz hata ayıklamayı engellemiyorsa `wizard_blocker_body` metni §4'te gözlenen etkiyi söyleyecek biçimde yazılır (iki dilde).

- [ ] **Step 9: Testlerin geçtiğini gör, derle**

Run: `.\android\gradlew.bat -p android :core:test :app:testDebugUnitTest :app:assembleDebug`
Expected: `BUILD SUCCESSFUL` (WizardSettingsTest, StringResourcesTest dahil).

- [ ] **Step 10: Cihazda sihirbaz ve ayarlar (controller kullanıcıyla)**

1. Ayarlar → "Kurulum sihirbazını yeniden çalıştır": adım 1 (geliştirici seçenekleri açık görünür; Otomatik Engelleyici uyarısı §4'e göre), adım 2 (zaten eşleşmiş → "Devam"), adım 3 "Şimdi değil", adım 4 "Bitir" → Durum. Geri hareketi adımlar arasında geri götürür.
2. Taze kurulum yolu: `& $adb shell pm clear dev.keyrelay` (eşleştirme ve izin silinir; PC'nin kendi adb eşleşmesi etkilenmez) → uygulamayı aç → sihirbaz; adım 2'de kullanıcı Ayarlar'da "Eşleştirme koduyla eşle"ye dokunur (bölünmüş ekran): "Eşleştirme portu bulundu" görünür; kodu **bildirimden** yazar → "Eşleşildi, izin verildi", sihirbaz kendiliğinden adım 3'e geçer. `& $adb shell dumpsys package dev.keyrelay | Select-String WRITE_SECURE` → `granted=true`. (İkinci denemede kodu uygulama içi kutulara yazar.)
3. Adım 3'te PC eşleştirmesi T12 test sunucusunun bağlantısıyla (`am start -d`) yapılır; ardından "Bitir" → servis başlar.
4. Ayarlar: süre seçimleri (geçersiz eşleşme pasif), kısayol Ctrl Alt ` seçilir → Ctrl+Alt+` yönlendirmeyi çevirir, Ctrl+Alt+Space artık çevirmez; Klavyeler bölümünde MX Keys Mini.
Ekran görüntüleri: her sihirbaz adımı ve Ayarlar (`screencap`, T14 Step 11 gibi).

- [ ] **Step 11: Commit**

```
& $git add android/app/src/main android/app/src/test/kotlin/dev/keyrelay/ui/WizardSettingsTest.kt
& $git commit -m "feat(android): kurulum sihirbazı ve ayarlar ekranı eklendi" -m "- kurulum dört adımda, bilgisayar olmadan tamamlanır; eşleştirme kodu bildirimden de girilebilir
- Samsung Otomatik Engelleyici kurulumu etkiliyorsa uyarılır
- bağlantı süreleri, geçiş kısayolu ve algılanan klavyeler ayarlardan görülür ve değiştirilir" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 16: Uçtan uca cihaz doğrulaması, arayüz onayı, belgeler

Bu görev kod yazmaz (bulunan kusur ayrı düzeltme turudur); ölçüm, görsel onay ve belge görevidir.

**Files:**
- Modify: `docs/spikes/2026-10-p2-device-results.md` (T16 bölümü)
- Modify: `docs/superpowers/specs/2026-10-07-keyrelay-design.md` (tasarım kapısı #54 takibi — kullanıcı onayıyla)

**Interfaces:**
- Consumes: T1–T15'in tamamı; T5 `gradlew :core:fakeServer`.
- Produces: bitiş çizgisinin Android tarafındaki maddeler için kanıt (3, 4, 7'nin tablet kısmı, 8'in Android kısmı, 10'un Android kısmı); P3'e devredilenlerin listesi.

- [ ] **Step 1: Tüm testler ve CI komutu**

Run: `.\android\gradlew.bat -p android test assembleDebug`
Expected: `BUILD SUCCESSFUL` (CI'daki `./gradlew test assembleDebug` ile aynı komut; bitiş 8'in Android kısmı).

- [ ] **Step 2: Bitiş 3 — ön plan geçiş süresi (yeni kural)**

Test sunucusu çalışır, TeamViewer öndeyken yönlendirme açık; kullanıcı kablosuz hata ayıklamayı açık tutar. ×5:
```
& $adb shell 'for i in 1 2 3 4 5; do am start -n com.teamviewer.teamviewer.market.mobile/com.teamviewer.remotecontrollib.activity.MainActivity > /dev/null; sleep 3; echo "notes $(date +%s%3N)"; am start -n com.samsung.android.app.notes/.memolist.MemoListActivity > /dev/null; sleep 3; done'
& $adb shell cat /data/local/tmp/keyrelay-helper.log | Select-String 'grab=|foreground='
```
Her tur: `grab=false` zaman damgası − `notes` damgası (≤ 500 ms beklenir) ve TeamViewer'a dönüşte `grab=true` − dönüş damgası. Ayrıca T1'de ölçülen S2–S4 senaryolarından biri (ana ekran) aynı yöntemle.

- [ ] **Step 3: Bitiş 7 (tablet) — boşta CPU**

Bağlı (test sunucusu), klavye boşta, ekran kilitli, 10 dk:
```
& $adb shell 'getconf CLK_TCK; for p in $(pidof keyrelay-helper) $(pidof dev.keyrelay); do echo "$p $(cut -d" " -f14,15 /proc/$p/stat)"; done'
```
10 dk sonra aynı komut. Her süreç için `(Δutime + Δstime) / CLK_TCK / 600 × 100` = tek çekirdeğin yüzdesi; iki sürecin toplamı < %0,1 beklenir. `& $adb shell dumpsys cpuinfo | Select-String keyrelay` çıktısı da kayda girer.

- [ ] **Step 4: Bitiş 4 — açılış ve yeni ağ**

1. Ev Wi-Fi'ı: tablet yeniden başlatılır, kilit açılış saati söylenir; dokunmadan ≤ 60 sn içinde bildirim "Hazır" (TeamViewer açılınca "Yönlendiriliyor"); zamanlar logcat'ten.
2. Hiç onaylanmamış bir ağ bulunabilirse (T1'de "her zaman izin ver" denmemiş başka bir telefon hotspot'u): o ağdayken yeniden başlatma → bildirim "Onay bekleniyor" + metin; kullanıcı sistem penceresinde İzin ver + Her zaman izin ver → dokunmadan "Hazır". Ağ bulunamazsa belgeye "onaylanmamış ağ yoktu, ölçülmedi" yazılır.

- [ ] **Step 5: Arayüz — ekran görüntüleri ve kayıtlar (bitiş 10, Android)**

Açık ve koyu temada (`& $adb shell cmd uimode night yes` / `no`) her ekranın görüntüsü: Durum (Hazır, Yönlendiriliyor, Bağlanıyor, Onay bekleniyor ya da Hata), PC'ler, PC ayrıntısı, Ayarlar, sihirbazın 4 adımı, bildirim, QS kutucuğu, başlatıcıda ikon (normal + tematik). Kısa kayıtlar (`screenrecord --time-limit 20`): durum geçişleri (TeamViewer ↔ Notes), PC kartı → ayrıntı paylaşılan öğe geçişi, öngörülü geri hareketi, sihirbaz adım geçişleri. Animasyonlar kapalı: `& $adb shell settings put global animator_duration_scale 0` → aynı geçişler anlık, paket akışı durur; sonra `... animator_duration_scale 1`. Dosyalar `%TEMP%\p2-ui\` altında; controller kullanıcıya sunar ve **onay bekler**. Kullanıcının istediği düzeltmeler ayrı düzeltme turudur (ilgili görev dosyalarında).

- [ ] **Step 6: Sonuç belgesi**

`docs/spikes/2026-10-p2-device-results.md`'ye "T16" bölümü: Step 1–5 tabloları (yalnız gözlenen değerler), kullanıcı onayı (tarih + kapsam), ve "P3'e devredilenler": QR'ın Windows arayüzünden kod tarayıcıyla okutulması (P2'de bağlantı adb ile açıldı), Windows'a özgü bitiş maddeleri (1, 2, 5, 6, 7'nin PC kısmı, 9).

- [ ] **Step 7: Spec takibi (tasarım kapısı #54) — kullanıcı onayıyla**

Spec'te iki cümle P2 gerçeğine göre düzeltilir; controller farkı kullanıcıya gösterip onay alır, onay yoksa bu adım atlanır ve progress dosyasına yazılır:
1. "Yaklaşımlar" tablosu, "Android'de evdev erişimi" satırının "Neden" sütunu: `Shizuku'nun otomatik başlatması yalnız güvenilir Wi-Fi'da.` → `Shizuku ayrı bir uygulama ve kurulum adımı ister; gözetimsiz açılış iki yolda da yalnız kablosuz hata ayıklamaya izin verilmiş ağlarda çalışır (P1 Risk 1).`
2. "Bileşenler / Android / Uygulama süreci" maddesinde `RelayService` açıklaması: `bildirimde bağlı PC, hedef uygulama ön planda mı, klavye; "Duraklat/Sürdür" eylemi` → `tek bildirimde durum (Durum ekranıyla aynı sözcükler), PC adı ve tek eylem: "Duraklat/Sürdür" ya da hata/onay durumunda "Yeniden dene"`.

- [ ] **Step 8: Commit**

```
& $git add docs/spikes/2026-10-p2-device-results.md docs/superpowers/specs/2026-10-07-keyrelay-design.md
& $git commit -m "docs(android): android tarafı cihazda uçtan uca doğrulandı" -m "- uygulama değişiminde klavyenin dönüş süresi, açılış süresi ve boşta işlemci kullanımı ölçüldü
- tüm ekranlar açık ve koyu temada görüntülendi, kullanıcı onayladı
- tasarım belgesi uygulamadaki bildirim ve kurulum gerçeğine göre düzeltildi" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
(Step 7 atlandıysa spec dosyası `git add`'den çıkarılır ve gövdenin son maddesi silinir.)
