# P1 risk doğrulama sonuçları

Cihaz: Samsung SM-X910 (Android 14), Logitech MX Keys Mini (Bluetooth). Tarih: 2026-10-07.
Prototip `kbdfwd` testlerden önce durduruldu, sonra yeniden başlatıldı.

Ortak komut (`adb shell` içinde; `--grab` değeri çalıştırmaya göre değişti, liste için hiç verilmedi):
```
APK=$(pm path dev.keyrelay | cut -d: -f2); LIB=$(dirname $APK)/lib/arm64/libkeyrelay.so; CLASSPATH=$APK app_process /system/bin --nice-name=keyrelay-helper dev.keyrelay.helper.Main --lib=$LIB --grab=<saniye>
```

## Risk 5

### Klavye düğümlerinin gruplanması

Komut: yardımcı süreç, `--grab` olmadan.

Gözlenen çıktı:
```
keyboard: MX Keys Mini Keyboard -> /dev/input/event14(MX Keys Mini Keyboard), /dev/input/event16(MX Keys Mini System Control), /dev/input/event17(MX Keys Mini Consumer Control), /dev/input/event18(MX Keys Mini)
```

uniq/phys: Bu cihazda shell kullanıcısı `/proc/bus/input/devices` dosyasını okuyamıyor (`Permission denied`); değerler
`adb shell dumpsys input` çıktısındaki `UniqueId` (= uniq) ve `Location` (= phys) alanlarından alındı.
- Dört MX Keys düğümünde de `UniqueId: d4:ec:b0:ba:a7:ab`, `Location: b4:0b:1d:f6:63:44`.
- `Location` tabletin Bluetooth adaptör adresi; MX Master 3S fare dahil tüm Bluetooth cihazlarında aynı. Gruplama
  tasarlandığı gibi uniq üzerinden yapıldı, phys yedeği devreye girmedi. Fare gruba alınmadı.

### Medya tuşlarının kaynağı (çalıştırma 3, `--grab=180`)

Dört grab da `0` döndü. Kullanıcı Win, ses aç/kıs (Fn'siz) ve aynı tuşları Fn ile bastı. Ekran ve ses değişmedi.
Düğüm başına olay sayıları:
- event17 (Consumer Control): 114 (VOLUMEDOWN) ×38, 115 (VOLUMEUP) ×26, 113 (MUTE) ×4
- event14: 125 (LEFTMETA/Win) ×34, 88 (F12) ×32, 110 (INSERT) ×16 (Fn katmanı), birkaç harf

Medya tuşları yalnız Consumer Control düğümünden geliyor; bu düğümün gruplanıp kilitlenmesi şart.
`getevent -l` ayrıca çalıştırılmadı; düğüm başına olay günlüğü her tuşun kaynak düğümünü doğrudan gösteriyor.

**Sonuç: GEÇTİ**

## Risk 3

### Harfler ve Alt+Tab (çalıştırma 1, `--grab=60`)

Dört grab da `0` döndü. Kullanıcı Samsung Notes'ta harf yazdı ve Alt+Tab bastı: hiçbir şey yazılmadı, uygulama
değiştirici açılmadı. Günlük: 174 tuş olayı, hepsi event14'te (harfler 30/31/32/36/37/38/39…, Alt 56 + tekrarlanan Tab 15).

### Win ve medya tuşları (çalıştırma 3, `--grab=180`)

Yukarıdaki Risk 5 bölümündeki çalıştırma: Win ve ses tuşları tablette etki yapmadı, olaylar yardımcı süreçte görüldü.

### Geçersiz çalıştırma 2

Çalıştırma 1 grab'i hâlâ tutarken başlatıldı; tüm grab'ler `-16` (EBUSY) döndü ve süreç yalnız dinledi. Çalıştırma 1
bitince Win tuşu Android'de etki yaptı. Sıralama hatası; ürün kusuru değil, sonuçlara katılmadı.

**Sonuç: GEÇTİ**

## Review Focus 1 ve 2

- **Focus 1 — grab açıkken `kill -9`:** Çalıştırma 3 sırasında `kill -9 $(pidof keyrelay-helper)` → süreç sonlandı,
  kullanıcı Notes'ta hemen yazabildi. Çekirdek grab'i bıraktı. **GEÇTİ**
- **Focus 2 — grab açıkken klavyeyi kapatma (çalıştırma 4, `--grab=120`):** Klavye ~5 sn kapatılıp açıldı. Günlük:
  `read /dev/input/event14 ended: -19`, event16, 17, 18 için de aynısı. Süreç ayakta kaldı (istisna yok). Açılınca klavye
  yeni düğümlerle yeniden bağlandı. **GEÇTİ** (Yeniden bağlanınca tekrar grab P1 kapsamında değil.)

## Risk 2

Cihaz sürümü: Android 14, SDK 34 (`getprop ro.build.version.release` / `ro.build.version.sdk`). İşlem kodları Android
15+'ta değişti (onProcessStarted eklendi); yardımcı kodları çalışma anında platform stub'ından okur, Android 14 değerleri
yedektir.

Komut (`adb shell` içinde, arka planda; çıktı `/data/local/tmp/fg.log`). `kbdfwd` bu testte durdurulmadı; `--watch-foreground` grab yapmaz.
```
APK=$(pm path dev.keyrelay | cut -d: -f2); LIB=$(dirname $APK)/lib/arm64/libkeyrelay.so; CLASSPATH=$APK setsid nohup app_process /system/bin --nice-name=keyrelay-helper dev.keyrelay.helper.Main --lib=$LIB --watch-foreground=com.teamviewer.teamviewer.market.mobile > /data/local/tmp/fg.log 2>&1 < /dev/null &
```
`IPackageManager.getPackageUid(String, long, int)` bu cihazda bulundu; TeamViewer uid'i `10290`.

### Review Focus 3 — başlangıç durumu

TeamViewer `am start` ile öne getirildi, 2 sn sonra yardımcı süreç başlatıldı. İlk satır:
`1791385503936 target uids=[10290] foreground=true (seed)`. **GEÇTİ**

Uyarı: seed `/dev/cpuset/top-app/cgroup.procs` dosyasını okur; bu dosyada ön plan aktivitesi olmayan süreçler de
bulunabilir. Böyle bir hedef pid seed'e girerse yalnız süreç ölünce silinir ve `targetForeground` `true` kalabilir.
Bu çalıştırmada görülmedi; sıklığı ölçülmedi.

### Gecikme ölçümü

Yöntem: aynı `adb shell` içinde `date +%s%3N` ardından `am start -n <aktivite>`; fark = günlükteki satırın zaman
damgası − `date` değeri. Notes: `com.samsung.android.app.notes/.memolist.MemoListActivity`, TeamViewer:
`com.teamviewer.teamviewer.market.mobile/com.teamviewer.remotecontrollib.activity.MainActivity`. Ölçüm uygulamanın
başlatılma süresini de içerir (üst sınır).

| # | Notes öne (TeamViewer arkaya) → `foreground=false` | TeamViewer öne → `foreground=true` |
|---|---|---|
| 1 | 1555 ms (Notes soğuk başlatma) | 119 ms |
| 2 | 503 ms | 132 ms |
| 3 | 481 ms | 112 ms |
| 4 | 507 ms | 124 ms |
| 5 | 503 ms | 131 ms |

Ek iki tur (olay günlüğüyle birlikte): arkaya 494 / 531 ms, öne 138 / 127 ms.

Öne gelme her ölçümde < 500 ms. **Arkaya geçiş 500 ms sınırında / üstünde**: 7 ölçümün 5'i 500 ms'yi aşıyor;
soğuk başlatma (1. tur) hariç 6'nın 4'ü.

Neden (`logcat -b events -v epoch`, ek tur 1): `am start` 555.790 → TeamViewer `wm_on_paused_called` 555.852 →
Notes `wm_on_resume_called` 555.896 → `wm_stop_activity` (TeamViewer) 556.167 → `wm_on_stop_called` 556.241 →
yardımcı süreç `foreground=false` 556.284. `IProcessObserver.onForegroundActivitiesChanged(false)` TeamViewer'ın
pause anında değil, geçiş animasyonu bitip aktivite durdurulduğunda geliyor; Notes'un öne geçmesinden ~390 ms sonra.
Gecikmenin büyük kısmı uygulama başlatma değil, sistemin stop'u geciktirmesi. Ek turlarda da aynı sıra görüldü.

### Uygulama öldürülünce

`am force-stop com.teamviewer.teamviewer.market.mobile` (1791385578388) →
`1791385578497 foreground=false (died)` (109 ms). **GEÇTİ**

**Sonuç: KISMEN GEÇTİ** — başlangıç durumu, öne gelme ve ölüm algılanıyor; arkaya geçiş ~0,5 sn gecikiyor
(bitiş maddesi 3'teki < 500 ms sınırını 7 ölçümün 5'inde, soğuk başlatma hariç 6'nın 4'ünde aşıyor). Bu aralıkta tuşlar hâlâ PC'ye gider.

## Risk 1

Tarih: 2026-10-08. Aynı cihaz (SM-X910, Android 14 / SDK 34). Uygulama: Task 5 APK (kurulum ekranı, `StartupService`,
`BootReceiver`); yardımcı `--watch-foreground=com.teamviewer.teamviewer.market.mobile` ile başlatılıyor.

### 8.1 Cihazda eşleştirme, izin, başlatma (PC'siz)

İşlem: Ayarlar > Kablosuz hata ayıklama > Eşleştirme koduyla eşle (bölünmüş ekranda KeyRelay açık) → IP, port, kod →
"Eşleştir" → "İzin ver" → "Şimdi başlat".

Gözlenen: Eşleştirme başarılı; libadb BouncyCastle 1.86 ile çalışıyor (bcprov 1.81 dışlaması cihazda sorunsuz).
"İzin ver": `WRITE_SECURE_SETTINGS` verildi (`dumpsys`: `granted=true`). Düzeltmelerden sonra "Şimdi başlat" birkaç
saniyede `true`; yardımcı pid 3459, PPID 1, kendi oturumu, log `target uids=[10290] foreground=false (seed)`. **GEÇTİ**

Bu adımda bulunan kusurlar ve düzeltmeleri (ayrıntı: `task-5-report.md` düzeltme turları 2–4):
1. libadb `AdbStream.read`, karşı taraf kuyruk boşken kapatınca EOF yerine `IOException("Stream closed.")` fırlatıyor →
   her komut istisnayla bitiyordu. Düzeltme: `isClosed` iken gelen istisna akış sonu sayıldı; sonra 3. kusurun işaretçi
   okuması kapanışa bağımlılığı tamamen kaldırdı.
2. `CLASSPATH=... setsid nohup app_process ... &` komutu adbd akış kapanışıyla yarışıyordu: adbd, `setsid` çalışmadan
   kabuğun süreç grubunu öldürüyor → yardımcı boş logla ölüyordu. PC'den eski `shell:` (PTY) ve `exec:` kanallarında
   yeniden üretildi; `; sleep 3` ile yaşıyor. Düzeltme: `setsid` ön planda, arka plana atma yeni oturumda:
   `setsid sh -c "... &"`.
3. libadb `AdbStream.read`, karşı taraf kuyrukta veri varken kapatınca veriyi verdikten sonra sonsuza dek bloke oluyor
   (`mPendingClose` bekleme döngüsünde kontrol edilmiyor) → çıktılı komutlar asılıyordu (JDWP yığını:
   `AdbStream.read:159 ← AdbManager.exec ← HelperLauncher.startHelper:37`). Düzeltme: her komuta `; echo __KR_END__`
   ekleniyor, işaretçi görünene kadar okunuyor.

### 8.2 Yardımcı çalışırken kablosuz hata ayıklamayı kapat/aç

Kullanıcı kablosuz hata ayıklamayı ~10 sn kapatıp yeniden açtı (yeni adb TLS portu çıktı; gerçekten yeniden başladı).
Yardımcı aynı süreç kaldı (pid 3459). **GEÇTİ**

### 8.3 Ev Wi-Fi'ında yeniden başlatma, kilit açma dışında dokunma yok

- **Deneme 1 (5. tur düzeltmesinden önce):** `BOOT_COMPLETED` `BootReceiver`'a ulaştı; `BOOT_COMPLETED` kaynaklı ön plan
  servisi kabul edildi (`Background started FGS: Allowed ... code:BOOT_COMPLETED`, `connectedDevice`) — plan kapısı takip
  maddesi 8 kapandı. `putGlobal(adb_wifi_enabled=1)` ~50 ms'de etkili oldu (`adbwifi started on port 42197`), ama
  uygulamanın kendi geri okuması 5 s içinde 1 görmedi → "wireless debugging did not turn on", erken dönüş, kablosuz hata
  ayıklama AÇIK kaldı. Kabuk ölçümü (put 1, sonra 200 ms'de bir 40 okuma): 40/40 kez 1 → sistem değeri geri almıyor;
  uygulama tarafı geri okuma güvenilmez (nedeni doğrulanmadı). 5. turda düzeltildi: geri okuma kaldırıldı, `finally`
  içinde 0'a çekiliyor. **KALDI**
- **Deneme 2 (5. tur sonrası):** kilit açma 02:18:01.6 → `BOOT_COMPLETED` 02:18:44.9 (+43,3 s, Android'in kendi
  gecikmesi; şifreli cihazda `BOOT_COMPLETED` ilk kilit açmadan sonra gelir, bu yüzden süre kilit açmadan ölçülür) →
  adbwifi başladı 02:18:50.1 → `autoStart result=true` 02:18:52.7 (kilit açmadan **+51,2 s**). Kullanıcı
  sonrasında kablosuz hata ayıklamayı KAPALI gördü (sıfırlama çalıştı). Yardımcı ayakta (pid 12273, PPID 1).
  **GEÇTİ** (bitiş maddesi 4: ≤ 60 s)

### 8.4 Başka Wi-Fi ağı (ikinci ev ağı)

Tablet "NoviceHome" ağında (kullanıcının ikinci ev Wi-Fi'ı; telefon hotspot'u DEĞİL): kilit açma 02:32:59.7 →
`BOOT_COMPLETED` 02:33:36.5 → adbwifi başladı 02:33:41.6 (uyarı penceresi yok) → `autoStart result=true` 02:33:45.5
(+45,7 s). "NHWiFi"ye geri dönüldüğünde yardımcı aynı süreç kaldı. **GEÇTİ** (bu ağ için)

### 9 / Review Focus 4 — Wi-Fi'siz açılış (uçak modu)

02:44:04 `StartupService` başladı → `waiting for wifi`; 2,5 dk olay güdümlü bekledi (yoklama yok; "KeyRelay starting"
bildirimi kullanıcıya görünür). Wi-Fi (NHWiFi) bağlandı 02:46:35.2 → adbwifi başladı 02:46:35.6 →
`autoStart result=true` 02:46:40.5 (Wi-Fi'dan **+5,3 s**). Ön plan servisi durdu. Yardımcı ayakta (pid 14036).
`dumpsys cpuinfo`: keyrelay-helper %0 user + %0 kernel. Bekleyen başlatma servisinin CPU'su ayrıca ölçülmedi; ölçülen
değer yardımcıya aittir. **GEÇTİ**

### Yabancı ağ (kafe) — cihazda TEST EDİLMEDİ

Hiç izin verilmemiş bir ağ (kafe senaryosu, telefon hotspot'u) cihazda denenmedi. Aşağıdakiler AOSP kaynak
araştırmasıdır (android14-release, frameworks/base), cihazda gözlenmemiştir:

- BSSID'si kablosuz hata ayıklama güvenilir listesinde olmayan ağda programatik `adb_wifi_enabled=1` sistem tarafından
  milisaniyeler içinde 0'a çevriliyor ve SystemUI "Bu ağda kablosuz hata ayıklamaya izin verilsin mi?" penceresini
  gösteriyor (`WifiDebuggingActivity`). Kablosuz adb yalnız kullanıcı "İzin ver"e basarsa başlıyor (1'i sistem kendisi
  yazıyor); "Bu ağda her zaman izin ver" BSSID'yi güvenilir listeye ekliyor, o ağdaki sonraki açılışlar sessiz.
  Kapatma/iptal = ret (0 kalır).
- Kanıt: `AdbService.java:215-220` ayar gözlemcisi → `setAdbEnabled(WIFI)`, `:548-549`; `AdbDebuggingManager.java:1052-1072`
  `MSG_ADBDWIFI_ENABLE` (Wi-Fi AP yok → `putInt(0)`; `!verifyWifiNetwork(bssid, ssid)` → `putInt(0)`); `:1407-1416`
  `verifyWifiNetwork` (`isTrustedNetwork(bssid)` değilse `startConfirmationForNetwork`); güvenilir liste BSSID (AP MAC)
  bazlı (`:1833`, `:1844-1845`, `:2256-2258`; `/data/misc/adb/adb_temp_keys.xml`); onay arayüzü
  `config_customAdbWifiNetworkConfirmationComponent` → `com.android.systemui/.wifi.WifiDebuggingActivity` (izin →
  `MSG_ADBWIFI_ALLOW putInt(1)`, her zaman izin → `addTrustedNetwork`; kapatma → `MSG_ADBWIFI_DENY putInt(0)`);
  `:644-699` Wi-Fi kapanma / kopma / BSSID değişimi → "Detected wifi network change. Disabling adbwifi." → `putInt(0)`
  (çalışan yardımcı etkilenmiyor — 8.2 ve 8.4'te gözlendi).
- Dış teyit: Key Mapper issue #2071 yeni ağdaki uyarıyı doğruluyor; Shizuku 13.6 otomatik başlatmayı "güvenilir WLAN" ile
  sınırlıyor; thedjchi Shizuku çatalı 1→0 dönüşünü "uyarı gösterildi, kullanıcı bekleniyor" sayıyor.
- Doğrulanamayan: Samsung One UI 6'nın bu akışı değiştirip değiştirmediği (kapalı kaynak); tabletin gerçek güvenilir
  listesi (root gerekir).
- Sonuç: yeniden başlatma sonrası gözetimsiz başlatma yalnız kablosuz hata ayıklama için daha önce "her zaman izin ver"
  denmiş ağlarda çalışır. Yeni bir kafe ağında oradaki ilk açılışta bir kez kullanıcı dokunuşu gerekir ("İzin ver" +
  "Bu ağda her zaman izin ver"). Çalışan yardımcı ağ değişiminden etkilenmez. P2 takibi: `adb_wifi_enabled`'ı
  `ContentObserver` ile izle; 0'a dönerse kullanıcıya izin + her zaman izin isteyen tek bildirim göster; 1'i döngüde
  yeniden yazma (her yazım pencereyi yeniden açar).

**Sonuç: KISMEN** — güvenilir ağlarda gözetimsiz başlatma doğrulandı (kilit açmadan 51,2 s, Wi-Fi'dan 5,3 s); hiç izin
verilmemiş bir ağ bir kerelik kullanıcı onayı gerektirir (kaynak araştırması, cihazda gözlenmedi).

## Risk 4

Tarih: 2026-10-08. PC: homepc, Windows 11 build 26300, yönetici hesabı. Oturum açma yalnız Windows Hello PIN ile.
Prob: `KeyRelay.Service.exe` (self-contained), `KeyRelayProbe` servisi olarak LocalSystem hesabıyla çalıştı. Servis, etkin
konsol oturumunda SYSTEM yetkili bir ajan başlatır. Ajan giriş masaüstünü izler ve tetik dosyalarına göre tuş gönderir.
Ham loglar: `.superpowers/sdd/2026-10-07-keyrelay-p1-foundations-and-risks/t7-probe-agent.log`, `t7-probe-service.log` (repo dışı, git'e girmez).

**2(b) kararının dayanağı:** Winlogon PIN kutusunda (kilit ekranı) ve yeniden başlatma sonrası giriş ekranında
**SCANCODE** yöntemiyle 4 nokta görülmesi. Ürün tuşları scancode olarak gönderecek; UNICODE sonucu yalnız bilgi amaçlıdır.

**PIN kısıtı ve prob değişikliği:** PIN kutusu yalnız rakam kabul ediyor. Harflerden oluşan SCANCODE "test" bu yüzden
sonuç vermedi. Prob, SCANCODE dizisi "test1234" olacak şekilde değiştirildi (1–4 rakamları için 0x02–0x05 eklendi),
yeniden derlenip kuruldu. PIN kutusunda görünen 4 nokta bu rakamlardır.

### Step 6 — Kurulum

- `SoftwareSASGeneration` kurulumdan önce zaten `REG_DWORD 0x1` (servislere izinli) idi. Değiştirilmedi, temizlikte de
  olduğu gibi bırakıldı.
- Servis oluşturuldu (LocalSystem, demand) ve başlatıldı. Log: `agent start pid=33764 session=1`, `desktop: Default`.

**GEÇTİ**

### Step 7.1 — Normal masaüstü (Not Defteri)

UNICODE "abc" ve SCANCODE "test" gönderildi. Log: `typed len=3 sent=6 err=0 desktop=Default`,
`scancodes 'test' sent=8 err=0 desktop=Default`. Kullanıcı Not Defteri'nde `abctest` gördü. Kullanıcının gördüğü
"Device selector ... application failed" penceresi başka oturumdaki Equalizer APO'dan geldi; kullanıcı ilgisiz olduğunu
teyit etti, Application olay günlüğünde hata yok. **GEÇTİ**

### Step 7.2 — Kilit ekranı (Winlogon PIN kutusu)

PC Başlat menüsünden kilitlendi, PIN kutusu açıldı. Ajan masaüstünü takip etti: `desktop: Winlogon`.
- UNICODE "test1": `typed len=5 sent=10 err=0 desktop=Winlogon` → tam 1 nokta (PIN kutusu yalnız "1"i aldı).
- SCANCODE "test": `scancodes 'test' sent=8 err=0 desktop=Winlogon` → PIN kutusu harfleri reddetti, sonuç belirsiz.
  Prob "test1234" olarak değiştirildi.
- SCANCODE "test1234" (7.4'teki kilitlemeden sonra), ilk deneme: `03:07:16.362 scancodes 'test1234' sent=16 err=0 desktop=Default`, ardından
  `03:07:16.868 desktop: Winlogon`. Yeniden deneme: `03:07:29.092 scancodes 'test1234' sent=16 err=0 desktop=Winlogon`
  → kullanıcı 4 nokta gördü.

**GEÇTİ** — ürünün kullanacağı SCANCODE yolu güvenli (Winlogon) masaüstüne yazabiliyor.

### Step 7.3 — Ctrl+Alt+Del (SendSAS, servisten)

Servis (oturum 0): `03:06:10.985 service: SendSAS(false) called`. Ajan: `03:06:11.268 desktop: Winlogon`. Kullanıcı
güvenlik ekranını (Kilitle / Kullanıcı değiştir / Oturumu kapat / Görev Yöneticisi) gördü. **GEÇTİ**

### Step 7.4 — Kilitleme (LockWorkStation, SYSTEM ajandan)

Log: `LockWorkStation=True err=0`. Kullanıcı PC'nin kilitlendiğini teyit etti. **GEÇTİ**

### Step 7.5 — UAC güvenli masaüstü

Ayrıca denenmedi: kullanıcı yönetici olduğu için UAC istemi metin alanı olmayan onay penceresi açıyor. Aynı Winlogon
masaüstü 7.2 ve Step 8'de denendi. **DENENMEDİ**

### Step 8 / Review Focus 5 — Yeniden başlatma, oturum açılmadan

Kullanıcı onayıyla servis `auto` yapıldı, `shutdown /r /t 5`. Açılışta, kimse oturum açmadan (özet; tam log yerel
çalışma klasöründe):
```
03:11:28.155 agent start pid=39620 session=1
03:11:29.041 agent start pid=26380 session=1
03:11:31.053 agent start pid=39484 session=1
03:11:31.068 desktop: Winlogon
03:12:11.827 agent start pid=9456 session=1
03:12:11.873 desktop: Winlogon
03:12:30.665 scancodes 'test1234' sent=16 err=0 desktop=Winlogon
```
Giriş ekranındaki PIN kutusunda kullanıcı 4 nokta gördü. UNICODE yöntemi bu ekranda ayrıca denenmedi. **GEÇTİ**
(bitiş maddesi 5)

### Step 9 — Temizlik

Servis durduruldu ve silindi (`sc query` → 1060, servis yok). `C:\KeyRelayProbe` ve `C:\ProgramData\KeyRelay` silindi.
`KeyRelay.Service` süreci kalmadı (sayı 0). `SoftwareSASGeneration` hâlâ 0x1 (değişmedi). **GEÇTİ**

### Gözlemler

- **Kilit perdesi zamanlaması:** Windows 11'de kilit perdesi (LockApp) Default masaüstünde duruyor. Winlogon masaüstüne
  PIN kutusu açılınca geçiliyor. 7.2'deki ilk "test1234" denemesi bu yüzden Default'a gitti; Winlogon 0,5 sn sonra
  geldi. Ürün, PIN kutusu açılmadan gönderilen tuşların kilit perdesine gidebileceğini hesaba katmalı.
- **Açılışta ajanın yeniden başlaması:** Açılışta ajan birden çok kez başladı (log satırları yukarıda). Neden
  araştırılmadı; etkin konsol oturum kimliğinin kısa süre değişmiş olması muhtemel.
- 03:04:03'te ajan bir kez session=2'de başladı (pid 41092); nedeni araştırılmadı — o sırada etkin konsol oturumu kısa süre değişmiş olabilir.

**Sonuç: GEÇTİ (UAC hariç)** — SYSTEM ajan kilit ekranında ve oturum açılmadan giriş ekranında SCANCODE ile yazabiliyor; SendSAS
ve kilitleme çalışıyor. UAC ayrıca denenmedi.

## P3 cihaz görevi 1 — UAC, bayat masaüstü, açılışta ajan (2026-10-08)

homepc, Windows 11 (build 26300). Geçici prob (`AgentProbe` tetik dosyalarıyla) `KeyRelayProbe` servisi olarak kuruldu; ölçümden sonra silindi, kod geri alındı. Ham loglar repo dışında (`.superpowers/sdd/2026-10-08-keyrelay-p3-windows/t1-*.log`).

### Önceki durum
`sc query KeyRelayProbe` ve `KeyRelay` → 1060; `SoftwareSASGeneration` 0x1; `C:\ProgramData\KeyRelay` yok. Kurulumda: `agent start pid=31052 session=1`, `desktop: Default thread=Default`, `service: active console session 4294967295 -> 1`.

### UAC, güncel masaüstü
Kullanıcı Terminal'i "Yönetici olarak çalıştır" ile açtı; onay penceresi açıkken:
```
14:51:42.933 desktop: Winlogon thread=Winlogon
14:51:49.587 scan1 '1234' results=1/0,1/0,1/0,1/0,1/0,1/0,1/0,1/0 input=Winlogon thread=Winlogon
14:51:52.150 esc results=1/0,1/0 input=Winlogon thread=Winlogon
14:51:53.183 desktop: Default thread=Default
```
Kullanıcı: Esc'ten sonra UAC penceresi kapandı (Hayır'a basılmış gibi). **SYSTEM ajan UAC güvenli masaüstüne yazabiliyor.**

### UAC, bayat masaüstü
İlk denemede dondurma (30 sn) UAC açılmadan bitti (ajan Winlogon'a geçmişti, Esc pencereyi kapattı) — geçersiz sayıldı. İkinci deneme:
```
14:55:08.277 freeze 30s thread=Default
14:55:15.932 desktop: Winlogon thread=Default
14:55:22.061 esc results=0/5,0/5 input=Winlogon thread=Default
14:55:47.653 esc results=1/0,1/0 input=Winlogon thread=Winlogon
14:55:48.168 desktop: Default thread=Default
```
Kullanıcı: ilk Esc'te (`0/5`) tepki yok, ikinci Esc pencereyi kapattı. **Eski masaüstüne gönderim `SendInput` = 0, `GetLastError` = 5 (erişim reddedildi) döndürüyor; tuş sessizce "başarılı" görünüp kaybolmuyor.**

### Kilit ekranı
Bayat masaüstü ölçümü kilit ekranında yapılamadı: tetik dosyası yazma gecikmesi ve PIN kutusunun işlem olmayınca kilit perdesine dönmesi yüzünden dondurma penceresi iki kez kaçtı; mekanizma UAC'de ölçüldüğü için tekrarlanmadı (**denenmedi**). Gözlenenler:
- Kilit perdesi `Default`, PIN kutusu `Winlogon` masaüstünde; perdeye giden tuşlar (`input=Default`, `1/0`) perdeyi kaldırıp PIN kutusunu açtı ama rakamlar kutuya geçmedi (kullanıcı: nokta yok). Masaüstü ~1 sn sonra `Winlogon`.
- PIN kutusu işlem olmayınca ~30 sn içinde perdeye dönüyor (`Winlogon` → `Default`).
- Ajan `Winlogon`'dayken "1234" gönderimleri (`1/0`) PIN kutusunda 4 nokta olarak göründü (P1 sonucu tekrarlandı). Not: deneme 3 sn arayla 10 kez gönderdi; tekrar eden yanlış PIN deneme sınırına takılabilir — ölçüm adımında tek gönderim yeterli.

### Açılışta ajan
Yeniden başlatma (açılış 15:02:19), oturum açılmadan:
```
15:02:36.615 service: active console session 4294967295 -> 1
15:02:36.778 agent start pid=8552 session=1
15:02:36.809 desktop: Winlogon thread=Winlogon
```
Oturum açıldıktan sonra (LocalSessionManager 21/22, 15:04:00–01, Session ID 1):
```
15:04:06.578 desktop: Default thread=Default
```
Ajan **tek kez** başladı, oturum açılışında yeniden başlamadı, oturum kimliği değişmedi. `.NET Runtime` olayı yok; tek `Application Error` (15:02:56) Notepad.exe / Microsoft.UI.Input.dll — KeyRelay ile ilgisiz. P1'deki çoklu başlangıç bu açılışta tekrarlanmadı; neden bulunamadı (P1 açılışında oturum 2'ye kısa geçiş görülmüştü, bu açılışta yok).

### Temizlik
`KeyRelayProbe` silindi (`sc query` → 1060), `KeyRelay.Service.exe` süreci yok, `C:\KeyRelayProbe` ve `C:\ProgramData\KeyRelay` silindi, `SoftwareSASGeneration` 0x1. Prob kodu `git checkout` ile geri alındı.

### Sonraki görevlere etkisi
- **UAC'de Esc `1/0` döner ve UAC kapanır** → SYSTEM ajan UAC'ye yazabiliyor; yapışkan 0 yalnız güvenlik ağı. **T4 olduğu gibi.**
- **Bayat masaüstünde `0`** (`0/5`) → "0 → yeniden aç → bir kez daha" kuralı masaüstü değişimini de yakalar.
- **Açılışta ajan çıkış kodu ≠ 0 / `.NET Runtime` 1026 yok; oturum kimliği değişmedi** → T5 ve T7'ye bulgu yok; T7'nin oturum değişimi beklemesi ve geri çekilmesi P1'deki durumu karşılar, T15'te yeniden ölçülür.
- "DUR" satırı seçilmedi. `OpenInputDesktop failed` satırı gözlenmedi (ön tarama kararı #2 yine de T4'te uygulanır).
- Not: `sent=1` iken `lastError` yalnız `sent=0` durumunda anlamlıdır; tüm `1/0` satırlarında hata kodu 0.

## P3 Windows doğrulaması (2026-10-08/09)

Cihaz: homepc (Windows 11 26300). İstemci `windows/tools/devclient.cs` (geliştirici istemcisi, Tailscale üzerinden);
**tablet ile P4'te tekrar**. Kurulum `KeyRelay-Setup-0.1.0.exe` (son derleme 89,9 MB), Tray yayın klasörü 234 MB
(WinAppSDK self-contained; kullanıcı kararıyla olduğu gibi).

| Step | Ne yapıldı | Gözlenen | Sonuç |
|---|---|---|---|
| 3 | Önceki durum | servis 1060, Run yok, kural yok, SAS `0x1`, `C:\ProgramData\KeyRelay` yok | GEÇTİ |
| 4 | Sessiz kurulum, ikinci kurulum | `setup-exit=0` (iki kez); `AUTO_START`, 3× yeniden başlatma 5000 ms, failure flag, Run `--background`, kural 47111 Özel, klasör ACL SYSTEM+Administrators, SAS `0x1`; ajan oturum 1, Default yazılabilir | GEÇTİ |
| 5 | Tepsi | kehribar simge, Mica, ikinci açılış pencereyi öne getirdi (kullanıcı) | GEÇTİ |
| 6 | Eşleştirme | QR, tarama bandı, adresler 100.64.0.10 / homepc.tailnet-example.ts.net / 192.168.1.10; kimlik kodu TYIM-LHQO (kullanıcı karşılaştırmadı); devclient LAN'dan zaman aşımı (istemci tarafında ProtonVPN açık), Tailscale'den eşleşti | GEÇTİ |
| 7 | Gecikme, 1000 tuş | p50 660 µs, **p99 2900 µs** (hedef < 2000 µs); KeysToday 1000; boşta CPU **ölçülmedi** | KALDI (p99) / DENENMEDİ (CPU) |
| 8 | SAS | güvenlik ekranı açıldı, Esc kapattı (kullanıcı) | GEÇTİ |
| 9 | Kilit ekranı | Winlogon yazılabilir; `1234` → PIN kutusunda 4 nokta (kullanıcı) | GEÇTİ |
| 10 | UAC | Winlogon yazılabilir, ajan durumu değişmedi; Esc UAC'yi kapattı | GEÇTİ |
| 11 | Yeniden başlatma | oturum açılmadan ajan oturum 1 Winlogon'da bir kez başladı; `1234` → giriş ekranında 4 nokta; oturum açılınca ajan yeniden başlamadı | GEÇTİ |
| 12 | İptal | 21:14:36 iptal → oturum aynı saniyede kapandı → sonraki hello `rejected reason 2` | GEÇTİ |
| 13 | Görsel onay | ilk sürümde tepsi çöktü (simge önbelleği, `fix(windows)` commit'i), servis 2× durdur/başlat ile düzeltme doğrulandı; arayüz kullanıcı geri bildirimiyle onaylı taslak v6.1'e göre yeniden yapıldı, kullanıcı "kat kat iyi" diyerek onayladı; ekran kaydı alınmadı | GEÇTİ (kullanıcı) |
| 14 | Kaldırma + yeniden kurma | kaldırma: servis 1060, Run yok, kural yok, SAS `0x1`, `C:\ProgramData\KeyRelay` korundu; yeniden kurma `setup-exit=0`, eşleşmeler duruyor. **Bulgu:** `unins000.exe` Program Files'ta kaldı; kaldırma logu `_unins-done.tmp` satırında bitiyor, "succeeded" satırı yok — kaldırmayı başlatan oturum kapanınca ilk aşama sürecinin sonlandırıldığı hipotezi, ölçülmedi | GEÇTİ (bulgu) |

Ara bulgu: 21:03:16'daki kopuş ProtonVPN'in bağlanmasıyla aynı anda (kullanıcı gözlemi).

### Bitiş maddeleri (Windows kısmı)

| Madde | Kanıt | Durum |
|---|---|---|
| 2a SAS | Step 8 | gösterildi (devclient) |
| 2b kilit/giriş ekranında şifre | Step 9, 11 | gösterildi (devclient) |
| 5 açılışta giriş ekranı | Step 11 | gösterildi (devclient) |
| 6 QR ile eşleştirme, iptal | Step 6, 12 | gösterildi (devclient; QR'ı tablet okutmadı) |
| 7 gecikme p99 < 2 ms, boşta CPU < %0,1 | Step 7 | **tutmadı** (p99 2900 µs; aday neden olay başına `OpenInputDesktop`); CPU ölçülmedi — P4'te tabletle |
| 10 arayüz onayı (Windows) | Step 13 | kullanıcı onayladı; ekran kaydı yok |
