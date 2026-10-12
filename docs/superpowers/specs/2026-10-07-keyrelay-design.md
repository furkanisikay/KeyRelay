# KeyRelay — Tasarım

Tarih: 2026-10-07 · Güncelleme: 2026-10-08 (P1 risk sonuçları + görsel tasarım yönü) · Durum: tasarım kapısı yeniden açıldı

## Bitiş çizgisi

İş, aşağıdakilerin hepsi gerçek cihazlarda (Galaxy Tab S9 Ultra SM-X910 / Android 14, Logitech MX Keys Mini,
homepc / Windows 11 26300) gösterildiğinde biter:

1. TeamViewer ön plandayken şu kombinasyonlar uzak PC'de çalışır: Alt basılı tutularak art arda Tab, Win tek başına,
   Win+E, Win+D, Win+Shift+S, Türkçe Q harfleri (`ğüşıöç`), AltGr+Q (`@`), medya tuşları.
2. (a) Fiziksel Ctrl+Alt+Del uzak PC'de güvenlik ekranını açar (`SendSAS`).
   (b) PC kilit ve giriş ekranındayken şifre KeyRelay kanalından yazılır (risk 4 cihazda doğrulandı: SYSTEM ajan
   Winlogon masaüstündeki PIN kutusuna SCANCODE ile yazdı; yeniden başlatma sonrası oturum açılmadan da).
3. Tablette başka bir uygulamaya geçildiğinde klavye en geç 500 ms içinde tablete döner, TeamViewer'a dönünce PC'ye geçer.
   (Risk 2 ölçümü: öne gelme 112–138 ms; yalnız `onForegroundActivitiesChanged(false)` ile arkaya geçiş 481–531 ms →
   "Ön plan kuralı" ile karşılanması beklenir; P2'nin ilk cihaz görevinde ölçülür.)
4. Tablet yeniden başlatıldıktan sonra, kullanıcı hiçbir şeye dokunmadan, kilit açılışından sonra en geç 60 sn içinde
   klavye yönlendirmesi hazırdır; bu, kablosuz hata ayıklamaya daha önce "her zaman izin ver" denmiş Wi-Fi ağlarında
   geçerlidir (cihazda: kilit açılışından +51,2 sn; Wi-Fi gelişinden +5,3 sn). Hiç izin verilmemiş bir ağda Android
   kablosuz hata ayıklamayı açmadan önce bir kez onay ister (AOSP `AdbDebuggingManager.java:1052-1072`); KeyRelay bunu
   algılayıp tek bir bildirimle kullanıcıyı yönlendirir, onaydan sonra o ağda da dokunmadan çalışır. Çalışan yardımcı ağ
   değişiminden etkilenmez (cihazda bir kez gözlendi: NoviceHome → NHWiFi).
5. PC yeniden başlatıldıktan sonra, oturum açılmadan giriş ekranında şifre yazılabilir.
6. Eşleştirme Windows arayüzündeki QR ile yapılır; Windows arayüzünden iptal edilen cihaz bir sonraki bağlantıda reddedilir.
7. PC tarafında bizim kodumuzun tuş başına eklediği gecikme (`own`: serviste KEY çerçevesinin TLS'ten okunmasından ajanda
   `SendInput` çağrısının hemen öncesine; named pipe dahil) p99 < 2 ms. `SendInput` süresi üçüncü taraf klavye
   kancalarına (ör. TeamViewer) bağlıdır ve ayrıca raporlanır (`latency-breakdown` içinde `sendinput`); gerekçe: cihazda
   TeamViewer açıkken ölçülen `sendinput` p99 ≈ 2 ms (1776 tuş). Tablette uygulama ve yardımcı süreç boşta 10 dk boyunca toplam CPU < %0,1
   (`dumpsys cpuinfo` / `top`), servis ve ajan boşta < %0,1.
8. Windows (`dotnet test`) ve Android (`./gradlew test`) birim testleri ve paylaşılan protokol test vektörleri iki
   tarafta da geçer; GitHub Actions iş akışları yerelde aynı komutlarla koşar.
9. Prototip kaldırıldı: tabletteki `kbdfwd`, homepc'deki `kbdrecv` zamanlanmış görevi ve `tls-terminated-tcp` Funnel
   ayarı yok; Funnel KeyRelay için `--tcp` modunda.
10. Arayüz "Görsel tasarım" bölümündeki ilkeleri karşılar: Android uygulamasının ve Windows tepsi uygulamasının her
    ekranı, ekran görüntüsü + kısa ekran kaydıyla (geçişler ve durum animasyonları görünür) kullanıcıya gösterilir ve
    kullanıcı onaylar; Android'de uyarlanabilir + tematik uygulama ikonu, Windows'ta tepsi simgesinin üç durumu vardır.

## Problem

Android, fiziksel klavyedeki Alt+Tab, Meta (Win) ve Meta kombinasyonlarını `PhoneWindowManager.interceptKeyBeforeDispatching`
içinde uygulamaya ulaşmadan tüketir. TeamViewer gibi uzak masaüstü istemcileri bu tuşları hiç görmez. Samsung One UI
bunu kapatma ayarı sunmaz. Çalışan prototip (`%USERPROFILE%\kbdfwd`) çözümün mekanizmasını kanıtladı: klavyenin evdev
düğümünü `EVIOCGRAB` ile kilitleyip olayları ağ üzerinden Windows'a gönderip `SendInput` ile basmak.

## Kapsam dışı

- Fare hareketi/dokunmatik yönlendirme (TeamViewer taşır). Yalnız farenin ek tuşları PC'ye gider ("Fare tuşları").
- Ekran aktarımı.
- iOS, macOS, Linux hedefleri.
- Birden fazla PC'ye aynı anda yönlendirme (tek aktif bağlantı; eşlenmiş birden çok PC arasında seçim vardır).

## Yaklaşımlar

| Konu | Seçilen | Alternatif | Neden |
|---|---|---|---|
| Android'de evdev erişimi | Gömülü ADB (libadb-android) ile `shell` yetkili yardımcı süreç | Shizuku bağımlılığı; erişilebilirlik servisi | Shizuku ayrı bir uygulama ve kurulum adımı ister; gözetimsiz açılış iki yolda da yalnız kablosuz hata ayıklamaya izin verilmiş ağlarda çalışır (P1 Risk 1). Erişilebilirlik filtresi One UI'da Meta için doğrulanmamış ve tuşu kapalı kaynaklı istemciye geri veremez |
| Windows mimarisi | SYSTEM servis + oturuma SYSTEM ajan | Kullanıcı oturumunda yönetici tray süreci | Kilit ekranı, UAC ve giriş ekranına yalnız Winlogon masaüstüne geçebilen SYSTEM süreç yazabilir |
| Taşıma | Kendi TLS 1.3 (sabitlenmiş sertifika) | Tailscale Funnel'ın TLS sonlandırması + tek token | Tailscale opsiyonel olur (LAN, port yönlendirme, Funnel `--tcp`), uçtan uca şifreli, cihaz bazlı iptal |
| Ön plan algılama | Yardımcıda `IActivityManager.registerProcessObserver` | `top-app` cpuset yoklaması (prototip) | Olay güdümlü, yoklama yok; arayüz Android 4'ten beri sabit |
| evdev okuma yeri | Tüm sıcak yol (okuma, grab, TLS, durum makinesi) yardımcıda | fd'yi uygulamaya verip orada okumak | SELinux: `input_device` erişimi yalnız `shell` alanında (`shell.te:57-58`), `untrusted_app`'te yok; fd geçse de `read`/`ioctl` reddedilir. Prototip shell alanında çalıştığı için çalıştı |

## Mimari

```
Tablet (Android)                                           PC (Windows)
┌──────────────────────────────┐                           ┌───────────────────────────────────────┐
│ app (uid u0_aXXX)            │                            │ KeyRelay.Service (LocalSystem, Session 0)│
│  Compose UI, QS tile          │                            │  TLS dinleyici, eşleştirme, cihaz kaydı │
│  RelayService (foreground)    │                            │  oturum izleme, SendSAS                 │
│  eşleştirme, ayar, başlatma   │                            │        │ named pipe (yalnız SYSTEM)     │
│        │ Binder (AIDL):       │                            │        ▼                                │
│        │ ayar ↓  durum ↑      │   TLS 1.3, ikili çerçeve   │ KeyRelay.Service --agent (SYSTEM,       │
│ helper (uid shell,            │ ─────────────────────────▶ │  aktif konsol oturumu): masaüstü takibi, │
│  app_process, APK classpath)  │ ◀───────────────────────── │  SendInput                              │
│  evdev read+grab (JNI), TLS,  │                            │        ▲ named pipe (Interactive kull.) │
│  durum makinesi, ProcessObs.  │                            │ KeyRelay.Tray (WinUI 3, kullanıcı)      │
└──────────────────────────────┘                            │                                         │
                                                            └───────────────────────────────────────┘
```

## Bileşenler

### Android — `android/`

Kotlin, Jetpack Compose (Material 3), minSdk 30 (kablosuz hata ayıklama Android 11 ile geldi), targetSdk 36.
Modüller:

- **`:core`** — saf Kotlin/JVM: protokol çerçeveleri, yönlendirme durum makinesi, QR ayrıştırma. Android bağımlılığı
  yok; JUnit ile JVM'de test edilir, test vektörlerini okur.
- **`:app`** — tek APK. İki çalışma bağlamı vardır:
  - **Uygulama süreci (uid u0_aXXX):** Compose UI, `RelayService` (`connectedDevice` tipinde foreground service;
    tek bildirimde durum (Durum ekranıyla aynı sözcükler), PC adı ve tek eylem: "Duraklat/Sürdür" ya da hata/onay
    durumunda "Yeniden dene"), Quick Settings tile, eşleştirme
    (QR: Google code scanner), ayarların saklanması (DataStore; cihaz token'ı Android Keystore ile şifreli), yardımcıyı
    başlatma (`AppStartupPorts` + `AdbSetup`, aşağıda "Başlatma").
    Ekranlar: Durum, PC'ler (eşlenmiş PC'ler: QR ile ekle, sil, varsayılan seç; geniş ekranda liste-ayrıntı; aynı ekranda
    **Hedef uygulamalar** bölümü: yüklü uygulamalardan seçim, varsayılan `com.teamviewer.teamviewer.market.mobile`),
    PC ayrıntısı, Ayarlar (geçiş kısayolu, tuşları göster, bağlantı süreleri, **Klavyeler** bölümü: algılanan
    klavyeler), Kurulum sihirbazı. Hedef uygulamalar ve Klavyeler ayrı ekran değil, bu ekranların bölümleridir.
  - **Yardımcı süreç (uid shell, `CLASSPATH=<apk> app_process /system/bin dev.keyrelay.helper.Main`):** sıcak yolun
    tamamı. evdev düğümlerini bulur (EV_KEY'de KEY_A ve KEY_SPACE bitleri olan, EV_REL bildirmeyen düğüm; aynı `uniq`/`phys`
    önekini paylaşan Consumer/System Control düğümleri de aynı klavyeye bağlanır ve birlikte grab edilir), `/dev/input`
    üzerinde `FileObserver` (inotify) ile takılıp çıkarılmayı izler, okur, grab eder, TLS bağlantısını tutar,
    `IActivityManager.registerProcessObserver` ile ön plan değişimini alır (`SET_ACTIVITY_WATCHER` shell manifestinde var),
    `:core` durum makinesini çalıştırır.
  - JNI (`libkeyrelay.so`, C, NDK; `extractNativeLibs=true`, yardımcı `System.load` ile mutlak yoldan yükler):
    `grab(fd, on)`, `readEvents(fd, buf)`, `keyBits(fd)`, `name/uniq/phys(fd)`. Başka iş yapmaz.
  - Uygulama ↔ yardımcı AIDL `IKeyRelayHelper`: `configure(RelayConfig)` (adresler, SPKI parmak izi, cihaz kimliği
    ve token'ı, hedef paketler, geçiş kısayolu, süre ayarları), `setEnabled(bool)`, `setStatusListener(IStatusListener)`
    (bağlantı, pcReady, hedef ön planda, grab, klavyeler, son hata), `ping()`.
  - Yardımcı başladığında uygulamanın `ContentProvider.call("attach", binder)` yöntemiyle Binder'ını teslim eder
    (Shizuku deseni). Uygulamanın Binder'ı ölürse (`linkToDeath`) yardımcı kendini kapatır; fd'ler kapanır, grab kalkar.
    Gelen her çağrıda arayanın uid'i uygulamanın uid'iyle karşılaştırılır.
- **Başlatma (`AppStartupPorts` / `AdbSetup`; akış `:core` `StartupFlow`)** — libadb-android 3.1.1 (Apache-2.0 seçeneğiyle) + Conscrypt.
  - İlk kurulum (tablette, PC gerekmez): sihirbaz geliştirici seçeneklerini ve kablosuz hata ayıklamayı açtırır,
    eşleştirme kodunu bildirim içi `RemoteInput` ile alır (Ayarlar ekranı açıkken girilebilsin diye), `pair()` yapar,
    ardından adb shell ile `pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS` verir.
  - Yardımcıyı başlatma akışı (tek akış; ayar değeri hiç okunmaz — P1'de uygulama tarafı okuma güvenilmez çıktı;
    hazır olma sinyali `autoConnect`'tir). Sahibi `RelayService`'tir: uygulamanın tek `connectedDevice` FGS'si, tek
    bildirimi (tek kanal, `IMPORTANCE_DEFAULT`, sessiz); P1'deki `StartupService` kalkar. Wi-Fi bağlantısı ön koşuldur,
    tetikleyici değildir (yoksa `NetworkCallback` ile beklenir, yoklama yok).
    **Akışı başlatan tetikleyiciler (tek liste):** (a) `RelayService` başlarken yardımcı bağlı değilse (`BOOT_COMPLETED`
    ve kullanıcının uygulamayı açması bunun özel halidir), (b) kullanıcının "Yeniden dene"si (bildirimde ve Durum
    ekranında; uçuşta akış varsa önce adım 4 ile kapatılır, sonra adım 1), (c) yardımcının ölümü (`linkToDeath`): üstel
    geri çekilmeyle (5 sn, 20 sn, 60 sn) en fazla 3 kez; sonra adım 3 durumu ("Yardımcı tekrar tekrar kapanıyor" + log özeti)
    ve yalnız "Yeniden dene". (c) sayacı, yardımcı Binder'ını teslim ettikten sonra 10 dk ayakta kalınca ve (b) ile
    sıfırlanır. Aynı anda tek akış uçar: uçuş sırasında gelen (a)/(c) yok sayılır, (b) uçuşu kapatıp adım 1'den başlatır.
    1. `development_settings_enabled=1` → `adb_enabled=1` → `put adb_wifi_enabled=1` → `autoConnect` (mDNS penceresi
       2×`ADB_COMMAND_TIMEOUT`; geç kalkan adbd ve yeni ağda pencere açıkken gelen onay bu pencerede yakalanır) → yardımcı
       başlat → başarı ölçütü yardımcının Binder'ını `ADB_COMMAND_TIMEOUT` içinde teslim etmesidir (`attach`); teslim
       edemeyen yardımcı kendini kapatır. Başarı → adım 4.
    2. Başarısızlık sınıfları: `autoConnect` `false` döndürür (bağlantı aşaması zaman aşımı) veya `InterruptedException`
       (mDNS zaman aşımı) / `IOException` (mDNS boş adres, erken bitiş) atar → "kablosuz hata ayıklama başlamadı";
       `AdbPairingRequiredException` (TLS yolunda eşleştirme kaybı) → "eşleştirmeyi yenileyin"; `autoConnect` başarılı ama
       yardımcı `ADB_COMMAND_TIMEOUT` içinde görünmedi → "yardımcı başlamadı" (+ `/data/local/tmp/keyrelay-helper.log`
       özeti). `autoConnect` `runInterruptible` içinde çağrılmaz (kesmeyi iptale çevirir); bağlantı aşaması için
       `setTimeout(ADB_COMMAND_TIMEOUT)` verilir (libadb varsayılanı `Long.MAX_VALUE`).
    3. Başarısız → `adb_wifi_enabled=0` → bildirim ve Durum ekranı sınıfa göre mesaj + "Yeniden dene" gösterir. Yalnız
       "kablosuz hata ayıklama başlamadı" sınıfı "onay bekleniyor"a geçer; mesaj: "Yeni bir ağdaysanız sistem penceresinde
       İzin ver + Her zaman izin ver'i seçin". "Onay bekleniyor"da `adb_wifi_enabled` için `ContentObserver` (0 yazıldıktan
       **sonra** kaydedilir) ve Wi-Fi'ın yeniden bağlanması yalnız uyandırıcıdır: uçuşta deneme yoksa `put` olmadan bir
       `autoConnect` (+ yardımcı başlat) denenir. Kendi 0'ımızın geç gelen `onChange`'i bir boş deneme üretebilir; tolere
       edilir. Diğer sınıflar yalnız "Yeniden dene" bekler. Bu durumun süre sınırı yoktur; boşta maliyeti bir gözlemcidir.
    4. Her çıkışta (başarı, iptal, hata) önce gözlemci kaldırılır, sonra `finally` ile `adb_wifi_enabled=0` yazılır.
    `put` yalnız adım 1'de, yalnız (a)/(b)/(c) tetikleyicileriyle yapılır → 1 `put` = en fazla bir sistem penceresi; (c)'nin
    üst sınırı yardımcı sistematik çöktüğünde pencere/adb açıp-kapama döngüsünü önler. Bu akışın sistem tarafı (1→0 geri
    çekme, pencere, onayda 1 yazma, `onChange` teslimi ve zamanları, bizim 0'ımızın açık pencereyi kapatıp kapatmadığı,
    onay adım 1 bitmek üzereyken gelirse 0'ımızın onu silmesi) P2'nin ilk cihaz görevinde gözlenir (telefon hotspot'u).
    Güvenilir liste BSSID bazlıdır: çok erişim noktalı (mesh) ağlarda her nokta ayrı onay isteyebilir; ağ değişiminde
    Android kablosuz hata ayıklamayı kendisi kapatır. Bitiş 4'ün 60 sn'si adım 1 içindir (kilit açılışı →
    `BOOT_COMPLETED` P1'de 36–43 sn, `BOOT_COMPLETED` → hazır 7,8–9,0 sn).
  - adb kabuk komutları (`AdbManager.exec`): her komutun sonuna `; echo __KR_END__ $?` eklenir ve işarete kadar okunur
    (libadb `AdbStream` iki kapanış hatası: boş kuyrukta `IOException("Stream closed.")`, dolu kuyruktaki kapanışta
    sonsuz bekleme); çıkış kodu işaretten sonra satır sonuna kadar okunur (işaret ve kod ayrı okumalara bölünebilir) ve
    döndürülür (sıfır dışı kod istisna değildir; ör. süreç
    yokken `pidof` 1 döner). Zaman aşımı bloklayan okumayı keser (`runInterruptible` + `withTimeout`; libadb `read`
    beklemedeki kesmeyi `IOException`'a çevirir). Her `exec` bir okuma zaman aşımı taşır (`ADB_COMMAND_TIMEOUT`
    10 sn, iç sabit: aşılırsa adım başarısız sayılır ve yukarıdaki akış devreye girer; `autoConnect` bağlantı aşaması ve
    yardımcı başlama beklemesi aynı sabiti, mDNS penceresi 2 katını kullanır). Yardımcı `setsid sh -c "... &"` ile
    başlatılır (adbd akış kapanışında kabuğun süreç grubunu öldürür; P1'de kanıtlandı).
  - Samsung Otomatik Engelleyici açıksa sihirbaz algılar ve ayar ekranına yönlendirir.
  - Sihirbaz kullanıcıya açıkça söyler: geliştirici seçenekleri ve USB hata ayıklama açık kalır; kablosuz hata ayıklama
    KeyRelay tarafından yalnız başlatma sırasında açılır ve hemen kapatılır (kullanıcının kendi açık tuttuğu kablosuz hata
    ayıklama da kapanır).

### Yönlendirme durum makinesi (`:core`, saf Kotlin, birim testli, yardımcıda çalışır)

Girdiler: `enabled` (kullanıcı), `connected` (TLS + kabul), `pcReady` (PC durumu), `targetForeground` (ön plan), `held`
(basılı tuş kümesi). Çıktı: `grab(on/off)`.

- `want = enabled && connected && pcReady && (targetForeground || override)`
- Ön plan kuralı (`ForegroundTracker`, pid→uid kümesi): `targetForeground = anyTarget && (lastTrueWasTarget || !anyOther)`.
  Hedefin `true` olayı `lastTrueWasTarget=true`, başka bir uid'in `true` olayı `false` yapar; `false`/ölüm olayları
  kümeden çıkarır. Böylece TeamViewer → başka uygulama geçişi o uygulamanın `true` olayıyla, üstte açılıp kapanan
  pencere (izin, paylaşım, çağrı) kapanınca da `false` olayıyla kendiliğinden düzelir; dışlama listesi yoktur. Tohumlama
  yalnız hedef pid'leri alır (`top-app` cgroup'ta ön plan aktivitesi olmayan süreçler "başka" sayılmasın) ve başlangıçta
  `lastTrueWasTarget=true`'dur; tohumlanan hedef pid'in gerçekten ön plan aktivitesi yoksa (bayat) yanlış pozitif
  olabilir — ilk ön plan olayı düzeltir.
  P1 ölçümü yalnız hedef uid'i kapsadı (öne gelme 112–138 ms, hedefin `false` olayı 481–531 ms); başka uygulamanın
  `true` zamanı, son uygulamalar hareketi, üst pencere, PiP, TeamViewer arka plandayken tohumlama senaryoları
  ölçülmedi → P2'nin ilk cihaz görevi bunları ölçer.
- Geçiş kısayolu (Ctrl+Alt+Space) her basışta bir şeyi **çevirir**: hedef öndeyken `enabled`'ı (prototip davranışı);
  hedef önde değilken `override`'ı (bölünmüş ekranda TeamViewer bölmesine dönüş gibi olay üretmeyen durumlar için
  kaçış; `override=true` yaparken `enabled=true` da yapar). Hedefin herhangi bir ön plan olayı veya ölümü `override`'ı
  sıfırlar. Böylece kısayola ikinci basış klavyeyi her durumda tablete geri verir.
- Grab durumu yalnız `held` boşken değişir (bir tarafın key-down'ı görüp key-up'ı görmemesi = takılı tuş).
- Grab açıkken her EV_KEY olayı (değer 0/1/2) gönderilir; geçiş kısayolunun tuşu iki tarafa da gönderilmez.
- Kombinasyonlar yalnız tetikleyici tuşu yutar, modifier'lar normal akar: Ctrl+Alt+Space (geçiş), Ctrl+Alt+Del → `SAS`,
  Win+L → `LOCK`. Yutulan tuşun bırakma olayı da yutulur.
- Bağlantı koparsa: gönderilemeyen olay atılır, `held` boşalınca grab kalkar.
- Prototipte doğrulanan davranışlar regresyon testi olarak kalır.

### Fare tuşları (P5)

Android fare yan tuşlarını Geri/İleri tuşuna çevirir; TeamViewer bunları PC'ye taşımaz.

- Fare düğümü: `EV_REL` + `BTN_LEFT` + en az bir `BTN_MIDDLE..BTN_TASK` taşıyan düğüm; yardımcının kendi sanal faresi
  ("KeyRelay virtual mouse") adından tanınıp hiç seçilmez (`MouseRouting`).
- Yardımcı ilk fare düğümünü bulunca `/dev/uinput` ile o farenin `EV_KEY/EV_REL/EV_MSC` bitlerini taşıyan bir sanal fare
  açar (shell uid `uhid` grubunda). Açılamazsa helper loguna tek satır düşer ve davranış yalnız klavyedir.
- Fare düğümü klavyeyle aynı anda yakalanır ve bırakılır. Yakalıyken `BTN_MIDDLE..BTN_TASK` durum makinesine gider
  (klavye tuşu gibi `held`'e girer, PC'ye `KEY` çerçevesiyle gider); hareket, tekerlekler, sol/sağ tuş, `EV_MSC` ve `SYN`
  sanal fareye aynen yazılır, Android imleci normal çalışır. Yakalı değilken fare Android'indir; ek tuşlar yalnız `held`
  için durum makinesine girer.
- Yakalama bırakılırken ya da fare düğümü kaybolurken sanal farede basılı kalan tuş bırakılır (takılı sürükleme olmaz).
- Fare tuşları tekrar üretmez; uygulamaya tuş göster/nabız sinyali gitmez.
- Windows eşlemesi `docs/protocol.md` "Mesajlar" altındadır.

### Görsel tasarım (Android + Windows)

Kullanıcı yönü (2026-10-08): profesyonel, kurumsal, minimalist; görsel ve animasyon ağırlıklı; işletim sisteminin yerel
yeteneklerini sonuna kadar kullanır.

- **Kimlik:** tek bir marka işareti (klavye tuşu + yönlü akış), iki platformda aynı renk ve biçim dili. Android'de
  uyarlanabilir ikon (ön/arka katman) + tematik (tek renk) ikon; Windows'ta uygulama ikonu + tepsi simgesinin üç durumu
  (bağlı/hazır, bekliyor, kapalı/hata).
- **Android:** Jetpack Compose, Material 3 Expressive; dinamik renk (Android 12+ duvar kağıdından) ve marka renginden
  türetilen yedek şema; açık/koyu tema; edge-to-edge; öngörülü geri hareketi (predictive back); durum kartından ayrıntı
  ekranına (PC kartı → PC ayrıntısı) paylaşılan öğe geçişi; durum değişimleri (bağlanıyor → bağlı → yönlendiriliyor) yay fiziğiyle (spring) animasyonlu; canlı
  durum kartı (PC, klavye, hedef uygulama, kilit durumu) anlık güncellenir; Quick Settings kutucuğu ve bildirim durumu
  aynı dili kullanır. Haptik geri bildirim kritik geçişlerde (eşleşti, kilit alındı). Animasyon süresi ölçeği 0 iken
  (`Settings.Global.ANIMATOR_DURATION_SCALE` / erişilebilirlik "Animasyonları kaldır") geçişler anlık yapılır; Windows'ta
  `UISettings.AnimationsEnabled` false ise aynı. Kullanılacak Compose/Material 3 Expressive API'lerinin kararlı sürümü
  P2 planında Google Maven'da doğrulanıp sabitlenir (deneysel API kullanılırsa planda işaretlenir).
- **Windows:** WinUI 3, Mica arka plan, Fluent hareket sistemi (bağlantılı animasyonlar, sayfa geçişleri), açık/koyu
  tema ve vurgu rengi; eşleştirme QR'ı animasyonlu kartta; tepsi simgesi durumla değişir.
- **Uygulama yöntemi:** P2/P3 planları ekranları kodlamadan önce görsel taslak (mockup) üretir; taslak kullanıcıya
  gösterilir ve onaylanır, kod taslağa göre yazılır.

### Windows — `windows/`

.NET 10, C# 14, `Directory.Packages.props` (merkezi paket yönetimi), `global.json`.

- **`KeyRelay.Protocol`** (sınıf kütüphanesi): çerçeve kodlayıcı/çözücü, mesaj tipleri, Linux keycode → set-1 scancode
  tablosu. Ağ veya Win32 bağımlılığı yok.
- **`KeyRelay.Service`** (Worker Service, `UseWindowsService`, LocalSystem, otomatik başlatma, servis kurtarma: 3 kez yeniden başlat):
  - TLS dinleyici (`SslStream`, TLS 1.3, sertifika: kurulumda üretilen ECDSA P-256, özel anahtar `ProgramData\KeyRelay`
    altında DPAPI LocalMachine ile korunur; ACL yalnız SYSTEM ve Administrators).
  - Eşleştirme ve cihaz kaydı (`ProgramData\KeyRelay\devices.json`: cihaz kimliği, ad, token'ın SHA-256'sı, ekleme ve son
    görülme zamanı). Tek aktif istemci; eski bağlantı yalnız yeni bağlantı HELLO ile doğrulandıktan sonra kapatılır
    (doğrulanmamış bağlantı gerçek cihazı düşüremez).
  - Aktif konsol oturumunu izler (`WTSGetActiveConsoleSessionId`, oturum değişim bildirimi). Oturumda ajan yoksa kendi
    token'ını çoğaltıp `TokenSessionId` ile `CreateProcessAsUser` → `winsta0\default` ile `--agent` modunda başlatır.
    Ajan ölürse yeniden başlatır (üstel geri çekilme, en fazla 5 sn).
  - `SAS` çerçevesi gelince `SendSAS(FALSE)` çağırır; `SoftwareSASGeneration` politikası kurulumda servisler için (1)
    ayarlanır. Kombinasyon tespiti istemcide (`:core`) yapılır; servis tuş kombinasyonu izlemez.
  - İstemciye giden `STATE hazır` biti = ajan pipe'a bağlı **ve** ajanın son bildirdiği masaüstü yazılabilir.
  - Kontrol pipe'ı (`\\.\pipe\KeyRelay.Control`, ACL: SYSTEM + INTERACTIVE okuma/yazma, Administrators): durum,
    eşleştirme oturumu başlat/bitir, cihaz listesi/iptal, ayarlar, son 500 log satırı, son tuşlar (komut listesi
    `docs/protocol.md`). Durum değiştiren komutlar (eşleştirme başlat/bitir, cihaz iptali, Funnel adresi) ve son tuşlar
    yalnız ajanın çalıştığı oturumdaki istemciden kabul edilir (`GetNamedPipeClientSessionId` = ajan oturumu); başka
    oturumdaki istemci (hızlı kullanıcı geçişi, RDP) yalnız okuma komutlarını kullanabilir, diğerlerine `wrong_session` alır.
- **Ajan modu (`KeyRelay.Service.exe --agent`)**: aynı ikili, host kurmadan çalışır.
  - Servise `\\.\pipe\KeyRelay.Agent` (ACL yalnız SYSTEM) ile bağlanır.
  - Her olaydan önce giriş masaüstü değiştiyse `OpenInputDesktop` + `SetThreadDesktop` ile yeni masaüstüne geçer
    (Default/Winlogon/Screen-saver). Eski masaüstüne gönderim yapılmaz (etkin olmayan masaüstüne `SendInput` reddedilir).
    Olay başına tek `SendInput(count=1)` çağrılır.
  - Takılı modifier temizliği: ajan açılışında, `yazılabilir` 0→1 dönüşünde ve `RELEASE_ALL`'da sol/sağ Ctrl, Alt,
    Shift ve Win için key-up gönderir (reddedilen key-up'lardan kalan basılı modifier'ı temizler).
  - `SendInput` (`KEYEVENTF_SCANCODE`, gerekiyorsa `EXTENDEDKEY`). Medya tuşları yalnız basışta, `SendInput` sanal
    tuşu (`VK_VOLUME_*` / `VK_MEDIA_*`, `EXTENDEDKEY`, basış + bırakma) olarak gönderilir.
  - Tuş tekrarını tablet yardımcısı üretir (`değer=2`). Ajan bunu yalnız tuş hâlâ basılıysa yeniden basış olarak
    işler; bırakmadan sonra geç gelen `değer=2` yok sayılır (ayrıntı `docs/protocol.md`).
  - `LOCK` çerçevesi gelince `LockWorkStation` çağırır (enjekte Win+L'yi Windows yok sayar).
  - Yazılabilirlik tek kurala bağlıdır: bir olay için `SendInput` 0 döndürürse ajan giriş masaüstünü yeniden açıp
    `SetThreadDesktop` yapar ve aynı olayı bir kez daha gönderir; yalnız **güncel giriş masaüstünde** alınan ikinci 0
    `yazılabilir=0`'dır; yeniden açma/`SetThreadDesktop` başarısız olursa (masaüstüne bağlanılamaz) bu da ikinci 0
    sayılır. O anki masaüstü adı saklanır ve durum, giriş masaüstünün adı bu saklanan değerden farklı olana
    kadar yapışkandır (servis `hazır=0` → istemci klavyeyi bırakır, TeamViewer'a döner). Ayrı bir izleme thread'i 500 ms'de
    bir yalnız masaüstü adını (Default, Winlogon, Screen-saver…) okur ve saklanan addan farklıysa `yazılabilir=1` bildirir
    (izleyicinin son gördüğü adla değil — yoklama fazı yarışını kaldırır). İzleyici hiçbir zaman 0 yazmaz; 1'e dönüşte
    saklanan ad silinir. Ajan `yazılabilir=1` ile başlar; servis istemci koptuğunda gönderdiği `RELEASE_ALL` ile yapışkan
    0'ı da bırakır (yeni TLS bağlantısı = ucuz sıfırlama; masaüstü adı değişmeden kalkan geçici nedenler için). `SendInput`'un 0 döndüğü hiçbir durum P1'de gözlenmedi; yapışkan 0 yolunun
    tamamı (UAC dahil) P3'ün ilk cihaz görevinde ölçülür. Sınama amaçlı `SendInput` gönderilmez (boşta sayacını sıfırlayıp ekranın kapanmasını engellerdi). UAC'de
    `SendInput`'un 0 döndürüp döndürmediği cihazda denenmedi → P3'ün ilk cihaz görevi; SYSTEM ajan UAC'ye de yazabiliyorsa
    "yazılamaz" yolu yalnız güvenlik ağı olarak kalır.
- **`KeyRelay.Tray`** (WinUI 3, unpackaged, Windows App SDK 2.5.1, H.NotifyIcon.WinUI 2.4.1): tepside simge (durum rengine
  göre), pencere: Durum (bağlı cihaz, son 1 dk p50/p99 gecikme), Cihazlar (QR ile ekle — QRCoder 1.8.0 —, iptal),
  Ağ (port, QR'a konacak adresler: algılanan LAN IP, Tailscale IP ve MagicDNS adı, Funnel adresi; Funnel'ı `--tcp`
  ile açma düğmesi), Loglar. Mica arka plan, açık/koyu tema. Oturum açılışında kullanıcı bağlamında başlar (HKLM Run,
  kurulum tüm kullanıcılar için yazar).
  Durum sayfasındaki "Son tuşlar" şeridi: varsayılan kapalı, kullanıcı başına ayar (`%LOCALAPPDATA%\KeyRelay\tray.json`,
  `ShowKeys`), göz düğmesiyle açılır. Kapalıyken servis tuş adı tutmaz ve göndermez; şerit yalnız günlük sayaç farkından
  "+N" gösterir. Açıkken tepsi sayfa görünürken saniyede bir `recent-keys` ister; kapatınca, sayfadan çıkınca veya pencere
  gizlenince `recent-keys-stop` gönderir. Kurallar Güvenlik bölümünde.
- **Kurulum:** mantık `KeyRelay.Service.exe install|uninstall` komutunda (yönetici gerekir; idempotent): servis kaydı,
  kurtarma eylemleri, SAS politikası, tray için HKLM Run anahtarı, güvenlik duvarı kuralı (seçili port, özel ağlar +
  Tailscale arayüzü), sertifika üretimi (yoksa). `uninstall` hepsini geri alır; `SoftwareSASGeneration` yalnız kurulumda
  KeyRelay değiştirdiyse önceki haline döndürülür (`ProgramData\KeyRelay\install.json` "değer yoktu" ile sayısal değeri
  ayırt eder; yoktuysa değer silinir);
  `ProgramData\KeyRelay` korunur.
  Dağıtım: Inno Setup 6 (`installer/KeyRelay.iss`) dosyaları kopyalar ve bu komutu çağırır; ayrıca zip.

## Protokol (`docs/protocol.md`, test vektörleri `protocol/test-vectors/*.json`)

TLS 1.3 üstünde çerçeveler: `u16 uzunluk (big-endian, tip dahil) | u8 tip | yük`. Uzunluk alanının değeri en çok 512'dir (tel üzerinde en çok 514 bayt); aşan bağlantı kapatılır.

| Yön | Tip | Yük |
|---|---|---|
| C→S | `0x01 PAIR` | `u8 sürüm, 16B tek kullanımlık kod, utf8 cihaz adı` |
| S→C | `0x02 PAIRED` | `16B cihaz kimliği, 32B cihaz token'ı` |
| C→S | `0x03 HELLO` | `u8 sürüm, 16B cihaz kimliği, 32B token` |
| S→C | `0x04 WELCOME` | `utf8 PC adı` |
| S→C | `0x05 REJECT` | `u8 neden (1 sürüm, 2 bilinmeyen cihaz, 3 kod geçersiz/süresi dolmuş)` |
| C→S | `0x10 KEY` | `u16 evdev keycode, u8 değer (0 bırak, 1 bas, 2 tekrar — yardımcı üretir; ajan yalnız basılı tuş için işler, bkz. `docs/protocol.md`)` |
| S→C | `0x11 STATE` | `u8 bayrak (bit0 hazır)` |
| C→S | `0x12 SAS` | — (Ctrl+Alt+Del; Del yutulur, Ctrl/Alt basma-bırakmaları normal KEY olarak akar) |
| C→S | `0x13 LOCK` | — (Win+L; L yutulur, Win normal akar) |
| C→S | `0x20 PING` | `u32 sıra` |
| S→C | `0x21 PONG` | `u32 sıra` |

- QR içeriği: `keyrelay://pair?v=1&n=<PC adı>&a=<adres1>,<adres2>,...&p=<port>&fp=<sertifika SPKI SHA-256, base64url>&c=<kod, base64url>`.
  Kod 5 dk geçerli, tek kullanımlık. İstemci adresleri sırayla dener (2 sn timeout), son başarılıyı başa alır.
  Adres girdisi `host:port` olabilir; port yoksa `p` kullanılır. Funnel adresi yalnız 443, 8443 veya 10000 portunda olabilir
  (Funnel kısıtı) ve istemci TLS'te SNI olarak host adını gönderir (Funnel yönlendirmesi SNI'ye bakar).
- İstemci TLS'te yalnız sabitlenmiş SPKI parmak izini kabul eder (CA zinciri ve ad doğrulanmaz).
- HELLO 5 sn içinde gelmezse bağlantı kapanır. Token karşılaştırması sabit zamanlı.
- Kimlik doğrulama hataları cihaz/IP başına üstel geri çekilmeyle yavaşlatılır.
- Bağlantı koparsa servis ajana `RELEASE_ALL` gönderir; basılı tuş kümesini yalnız ajan tutar (servis tuş durumu izlemez).
- Servis→ajan pipe yazımı `agentWriteTimeout` (1 sn, iç sabit) taşır; aşılırsa ajan ölü sayılır, sonlandırılıp yeniden
  başlatılır. Ajanın 500 ms masaüstü izleme aralığı iç sabittir: kullanıcı deneyimini belirleyen süre canlılık/hazır
  zinciridir ve 500 ms prototipte yeterli görüldü.
- Canlılık: istemci `pingInterval` (varsayılan 5 sn) aralıkla PING gönderir; `pongTimeout` (varsayılan 10 sn) içinde
  PONG gelmezse bağlantıyı düşmüş sayar (grab, basılı tuş kalmayınca kalkar) ve yeniden bağlanır. Sunucu `idleTimeout`
  (varsayılan 20 sn) sessiz kalan bağlantıyı kapatır. Bu süreler kullanıcının "klavye ne kadar sürede döner" deneyimini
  belirlediği için ayardır (Android ayar ekranı / servis ayar dosyası); çerçeve uzunluk sınırı (512) protokol sabitidir.
- Her yazım `sendTimeout` (varsayılan 1 sn, ayar) taşır; aşılırsa bağlantı düşmüş sayılır. Tüm ağ yazımları (KEY, SAS,
  LOCK, PING, HELLO) durum makinesi kilidinin dışında, tek bir yazıcı thread'inde yapılır (TLS soketi eşzamanlı yazıma güvenli değil) (sınırlı kuyruk: 256 olay; dolarsa bağlantı düşmüş sayılır).
  Böylece bloklanan bir yazma ne evdev okumayı ne de canlılık denetimini durdurur; sert kopma ilk tuşta en geç
  `sendTimeout` içinde algılanır (prototip `kbdfwd.go:156` ile aynı davranış).

## Hata durumları

| Durum | Davranış |
|---|---|
| Yardımcı ölür | Kernel fd'leri kapatır, grab kalkar, klavye Android'e döner; uygulama `linkToDeath` ile görür ve başlatma akışını (c) tetikleyicisiyle, üstel geri çekilmeli en fazla 3 kez adım 1'den başlatır; sonra yalnız "Yeniden dene" |
| Uygulama ölür | Yardımcı `linkToDeath` ile görür ve kapanır; grab kalkar, klavye Android'e döner |
| Wi-Fi yok | Otomatik başlatma Wi-Fi bekler; zaten çalışan yardımcı etkilenmez |
| PC'ye ulaşılamaz | `connected=false` → grab yok, klavye tablette çalışır; geri çekilmeli yeniden bağlanma |
| Ajan ölür / oturum değişir | Servis `hazır=0` gönderir (istemci grab'ı bırakır) ve ajanı yeniden başlatır; yeni ajan boş tuş kümesiyle başlar |
| Kilit/giriş ekranı | Ajan Winlogon masaüstüne geçer, yazmaya devam eder (P1'de doğrulandı). Win11 kilit perdesi `Default` masaüstündedir; PIN kutusu açılınca giriş masaüstü `Winlogon` olur — ajan her olaydan önce giriş masaüstünü yeniden açar |
| UAC güvenli masaüstü | UAC de `Winlogon` adlı masaüstündedir; ajan tuşları gönderir. `SendInput` 0 döndürürse "Ajan modu"ndaki yapışkan `yazılabilir=0` kuralı işler (klavye TeamViewer'a döner, masaüstü adı değişince geri alınır). UAC'de sonuç cihazda denenmedi |
| Yeni Wi-Fi ağında açılış | Sistem kablosuz hata ayıklamayı onay penceresiyle bekletir; tek bildirim kullanıcıyı yönlendirir; çalışan yardımcı etkilenmez |
| Klavye uykuya geçer / düğüm kaybolur | `read` hatası → fd kapanır; `/dev/input` inotify bildirimiyle yeniden açılır (yoklama yok) |
| Sertifika değişir (yeniden kurulum) | İstemci reddeder, "yeniden eşleştir" uyarısı gösterir |

## Güvenlik

- Ağdan gelen veriyi yalnız servis ayrıştırır; uzunluk sınırı, bilinmeyen tip = bağlantı kapanır. Ajan yalnız SYSTEM pipe'ından
  doğrulanmış olayları alır.
- Tray yönetici olmayan kullanıcıyla çalışır. Kontrol pipe'ı INTERACTIVE'e açıktır, ama durum değiştiren komutlar
  (eşleştirme, cihaz iptali, Funnel adresi) yalnız ajanın çalıştığı (tuşların yazıldığı) oturumdaki istemciden kabul
  edilir: o oturumun kullanıcısı zaten klavyeye sahiptir; başka oturumdaki kullanıcı (hızlı kullanıcı geçişi, RDP)
  eşleştirdiği bir tabletle konsol oturumuna kalıcı tuş yazma yetkisi almamalıdır. Diğer oturumlar durumu okuyabilir.
- Son tuşlar (tepsi şeridi): yalnız tepsi isterken (5 sn'lik kira; her istek yeniler, süre dolunca zamanlayıcı siler)
  ve yalnız ajan oturumundaki istemciye, en çok 8 basış, yalnız bellekte; diske ve loga yazılmaz. Giriş masaüstü
  `Default` değilken (kilit, UAC) tutulmaz ve önceki tuşlar silinir; masaüstü değişim bildiriminden önceki son 1 sn'nin
  tuşları da silinir (ajanın 500 ms yoklaması). Tabletten `LOCK`/`SAS`, Windows oturum kilidi/çıkışı/bağlantı kesilmesi
  ve kullanıcı değişimi her şeyi siler. Ad yoksa (noktalama, düzene bağlı tuş) "•" gösterilir.
- `WRITE_SECURE_SETTINGS` yalnız geliştirici ayarlarını açıp kapatmak için kullanılır.
- Yardımcı, Binder çağrılarında arayanın uid'ini uygulamanın uid'iyle karşılaştırır.

## Repo yapısı

```
KeyRelay/
├─ android/            Gradle (Kotlin DSL), gradle/libs.versions.toml, :core, :app
├─ installer/          KeyRelay.iss (Inno Setup)
├─ windows/            KeyRelay.slnx, Directory.Build.props, Directory.Packages.props, global.json, src/, tests/
├─ protocol/test-vectors/
├─ docs/               architecture.md, protocol.md, setup.md, superpowers/specs|plans
├─ .github/            workflows (android.yml, windows.yml, release.yml), dependabot.yml, ISSUE_TEMPLATE, PULL_REQUEST_TEMPLATE.md
├─ README.md, README.tr.md, LICENSE (Apache-2.0), SECURITY.md, CONTRIBUTING.md, CHANGELOG.md, .editorconfig, .gitattributes
```

## Test

- Windows: MSTest; protokol çözücü (bozuk/kısa/uzun çerçeve, bilinmeyen tip), keycode tablosu, eşleştirme (süre dolmuş kod,
  tekrar kullanılan kod, iptal edilmiş cihaz), basılı tuş bırakma, test vektörleri.
- Android: JUnit; durum makinesi (geçiş yalnız held boşken, kopma sırasında basılı tuş, kısayol yutma, ön plan değişimi
  tuş basılıyken, kısayolun iki basışta her durumda tablete geri vermesi, override açıkken hedef öndeyken başka uid'in
  `true` olayı, üst pencere açılıp kapanınca düzelme), protokol, QR ayrıştırma, test vektörleri, `exec` işaret/çıkış
  kodu bölünmesi; başlatma akışı (sahte adb/ayar/saat arkasında JVM testi: tek uçuş, tetikleyici (a)/(b)/(c), (c)'nin
  üstel geri çekilmesi ve 3 deneme sınırı, uçuşta "Yeniden dene", gözlemci kaydı/kaldırma sırası, istisna→mesaj sınıfı,
  `put`'un yalnız adım 1'de yapılması). Akışın saf kısmı (tetikleyici, sayaç, sınıf eşlemesi) `:core`'da küçük bir sınıf
  olarak JVM'de test edilir; `:app`'teki Android yapıştırıcısı ince kalır.
- Windows (ajan): `SendInput` 0 → ikinci deneme → yapışkan `yazılabilir=0` → ad değişimiyle geri alma (masaüstü ve
  `SendInput` sahte arayüz arkasında birim testli).
- Entegrasyon (Windows): gerçek `SslStream` istemcisiyle loopback; ajan yerine sahte pipe.
- Cihaz doğrulaması: bitiş çizgisi maddeleri elle, ölçümler loglanır.

## Riskler ve erken doğrulama (planın ilk görevleri)

**P1 sonuçları (2026-10-08, ayrıntı: `docs/spikes/2026-10-risk-results.md`):**

| Risk | Sonuç | Spec etkisi |
|---|---|---|
| 1 Gömülü ADB + açılışta başlatma | KISMEN — PC'siz eşleştirme/izin/başlatma, kablosuz hata ayıklama kapanınca yaşama, güvenilir ağlarda dokunmadan açılış (+51,2 sn) ve Wi-Fi bekleme (+5,3 sn) cihazda GEÇTİ; hiç izin verilmemiş ağ cihazda denenmedi, AOSP kaynağına göre bir kez sistem onayı gerekir | Bitiş 4 daraltıldı; "Yeni ağ onayı", bitiş işaretli `exec`, `setsid sh -c` başlatma |
| 2 Ön plan izleme | KISMEN — mekanizma GEÇTİ (Android 14); arkaya geçiş olayı 481–531 ms; işlem kodları Android 15+'ta değişti (kod kapısı) | "Ön plan kuralı" (`anyTarget && (lastTrueWasTarget || !anyOther)`), çalışma anında çözülen işlem kodları |
| 3 JNI + EVIOCGRAB | GEÇTİ | — |
| 4 Winlogon'a yazma, SendSAS, LockWorkStation | GEÇTİ (UAC denenmedi) | Bitiş 2(b) tek okuma: KeyRelay kanalından |
| 5 Medya tuşu düğümü + gruplama | GEÇTİ (`uniq` ile) | — |

Yedeklerin hiçbiri seçilmedi. Masaüstü geçişinde eski masaüstünde tuş bırakma adımı kaldırıldı (etkin olmayan
masaüstüne `SendInput` reddedilir); takılı modifier temizliği Ajan modundaki key-up kuralıyla yapılır.

P1'de tanımlanan riskler (tarihsel kayıt):

1. Gömülü ADB ile kendine bağlanma + `app_process` yardımcısı + `adb_wifi_enabled`'ı yabancı ağda programla açma (Samsung).
2. `registerProcessObserver`'ın `shell` uid'inden çağrılabilmesi (izin `SET_ACTIVITY_WATCHER` shell manifestinde var;
   gizli API çağrısının app_process'te çalıştığı cihazda doğrulanır).
3. `app_process` içindeki yardımcının APK'daki JNI kütüphanesini `System.load` ile yükleyip `EVIOCGRAB` yapabilmesi.
4. SYSTEM ajanın Winlogon masaüstünde `SendInput` ve `LockWorkStation` davranışı; servisin (Session 0, LocalSystem)
   `SendSAS(FALSE)` ile aktif konsol oturumunda güvenlik ekranını açması.
5. MX Keys Mini'nin medya tuşlarının hangi evdev düğümünden geldiği (`getevent -l`) ve aynı `uniq`/`phys` ile
   gruplamanın Keyboard + Consumer Control düğümlerini doğru eşleştirmesi.

P1 öncesi tanımlanan yedekler (kullanılmadı): (1) Shizuku desteği, (2) `top-app` cpuset dinleme, (3) APK'dan çıkarılan
native çalıştırılabilir, (4) kilit ekranında klavyeyi TeamViewer'a bırakma, (5) yalnız harf düğümünü grab etme.

## Kararlar

- Windows: servis + oturum ajanı — kullanıcı seçimi (2026-10-07).
- Android başlatma: gömülü ADB — kullanıcı seçimi; Key Mapper `SystemBridgeSetupController.kt:177-238` yöntemi.
- Bağlantı: kendi TLS + QR eşleştirme — kullanıcı seçimi.
- Çalışma kopyası `D:\Repos\KeyRelay`; git aynasına push kullanıcı onayıyla — kullanıcı seçimi.
- Proje adı KeyRelay — önerildi, kullanıcı itiraz etmedi.
- Lisans Apache-2.0 — libadb-android çift lisanslı (README), Conscrypt Apache-2.0; patent maddesi içerir.
- README İngilizce + `README.tr.md` — GitHub kitlesi; global kural "dokümantasyon Türkçe" `README.tr.md` ve docs ile karşılanır.
- Ajan ayrı ikili değil `--agent` modu — dağıtılacak tek .NET ikilisi (YAGNI).
- `IProcessObserver` imzası AOSP android14-release `core/java/android/app/IProcessObserver.aidl` ile doğrulandı; android15/16-release'te başa `onProcessStarted` eklendi ve işlem kodları kaydı (kod kapısı tur 1) → yardımcı kodları cihazın `IProcessObserver$Stub.TRANSACTION_*` alanlarından çalışma anında okur, Android 14 değerleri yedektir.
- Paket sürümleri nuget.org / Google Maven'da 2026-10-07 doğrulandı: H.NotifyIcon.WinUI 2.4.1, Microsoft.WindowsAppSDK 2.5.1,
  QRCoder 1.8.0, Microsoft.Extensions.Hosting.WindowsServices 10.0.12; Inno Setup 6.7.3 (winget).
- Sıcak yol yardımcıda — SELinux: `input_device` yalnız `shell` alanında (`system/sepolicy` android14-release
  `public/shell.te:57-58`; `untrusted_app*.te`'de yok); cihazda prototip `u:r:shell:s0` (tasarım kapısı tur 1, #1).
- WiX yerine `install` komutu + Inno Setup — WiX v6+ gelir elde eden kullanıcılardan OSMF ücreti ister (WixToolset.Sdk
  NuGet sayfası); kurulum işleri tek komutta toplanınca test edilebilir ve kurulum aracından bağımsız olur (tur 1, #3).
- Tray WinUI 3 kalır (tur 2 ponytail önerisi WPF/WinForms reddedildi) — kullanıcı açıkça modern ve güzel arayüz istedi.
- Geçiş kısayolu varsayılanı Ctrl+Alt+Space — MX Keys Mini'de F tuşları varsayılan olarak medya kodu gönderir (prototipte görüldü).
- Bitiş 2(b) KeyRelay kanalından — P1 Risk 4: SCANCODE "test1234" Winlogon PIN kutusunda 4 nokta (kilit + yeniden başlatma sonrası giriş ekranı).
- Ön plan kuralı `anyTarget && (lastTrueWasTarget || !anyOther)` + `override` — P1 Risk 2: hedefin `false` olayı 481–531 ms; "son öne gelen kazanır" üst pencere kapanınca kalıcı yanlış negatif üretirdi ve kısayol kaçışı `targetForeground=false` iken çalışmazdı (tasarım kapısı tur 6, #30).
- Yeni ağda ayar okunmaz; `autoConnect` başarısızlığı → tek bildirim + "onay bekleniyor", gözlemci yalnız uyandırıcı, tek uçuş — AOSP `AdbDebuggingManager.java:1052-1072, 1407-1416`; `onChange` değer taşımaz ve kendi `finally` 0'ımız ayırt edilemez (tur 6, #31); Key Mapper #2071'de put döngüsü kullanıcı şikâyetine dönmüş.
- `AdbManager.exec` bitiş işareti + okuma zaman aşımı — P1'de libadb `AdbStream.read` iki kapanış hatası cihazda (JDWP yığını) ve kaynakta doğrulandı; upstream bildirim kullanıcı kararı.
- Görsel tasarım yönü (profesyonel, kurumsal, minimalist, animasyon ağırlıklı, OS yerel yetenekleri) — kullanıcı talimatı 2026-10-08; ekranlar kodlanmadan önce taslakla onaylanır.
