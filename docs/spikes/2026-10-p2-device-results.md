# P2 cihaz sonuçları

Cihaz: SM-X910, Android 14 / SDK 34; MX Keys Mini. PC yerine `gradlew :core:fakeServer` (devpc, LAN). Ham loglar repo dışında (`%TEMP%\p2-*.log`, `fake-server*.log`). Tarih: 2026-10-08. Cihaz arayüz dili İngilizce (`values/`).

## T9 — yardımcı bağlantısı

| # | Adım | Gözlenen | Sonuç |
|---|---|---|---|
| 1 | "Şimdi başlat" | durum `true` | GEÇTİ |
| 2 | Yardımcı logu, attach | `IProcessObserver codes from platform stub: foreground=1 died=3`, `helper ready`; logcat `helper attached generation=1` | GEÇTİ |
| 3 | Uygulama ölümü (`am force-stop`) | `pidof keyrelay-helper` boş, logda `app died` | GEÇTİ |
| 4 | Yardımcı ölümü (`kill`) | logcat `helper died:` | GEÇTİ |
| 5 | Arayan denetimi | `service list | grep keyrelay` boş (binder servis yöneticisinde değil) | GEÇTİ |

İnceleme düzeltmesinden (durum yazımı ve ölüm ana thread'de sıralı) sonra 2–4 yeniden koşuldu: aynı sonuç. HiddenApi'nin API 30 (Android 11) dalı denenmedi (cihaz Android 14).

## T11 — servis ve açılış

| # | Adım | Gözlenen | Sonuç |
|---|---|---|---|
| 1 | "Servisi başlat" | tek bildirim (`id=1`) "No PC paired" | GEÇTİ |
| 2 | Attach | `helper attached generation=1`, `helper ready` | GEÇTİ |
| 3 | Yardımcı ölümü (c) | bildirim "Starting · Restarting in 5 s", 5 sn sonra yeni pid; ardından adb kesildi (deneme sonunda `adb_wifi_enabled=0`, beklenen) | GEÇTİ |
| 4 | Açılış | `USER_UNLOCKED` 17:32:23.8 → yardımcı 17:33:16.5 (~53 sn; kullanıcı gözlemi 55 sn) | GEÇTİ (≤ 60 sn) |
| 5 | "Yeniden dene" | denenmedi: hata ayıklama ekranında düğme yok, bildirimde yalnız ERROR/APPROVAL durumunda var, servis dışa kapalı (`am startservice` çalışmaz); yol StartupFlowTest'te birim testli | DENENMEDİ |

## T12 — uçtan uca (test sunucusu)

| # | Adım | Beklenen | Gözlenen | Sonuç |
|---|---|---|---|---|
| 1 | Kötü niyetli bağlantı, İptal | eşleşme yok | onay satırı çıktı, İptal; sunucuda `PAIR` yok, ayar dosyası değişmedi | GEÇTİ |
| 2 | Eşleştirme | `Paired(FakePC)`, sunucuda `PAIR` | ekranda "Paired FakePC"; sunucuda `PAIR` | GEÇTİ |
| 3 | Başlatma | bildirim Hazır | "Ready · TestPC · keyboard on the tablet" (QR'daki ad `FakePC`, test sunucusunun WELCOME'daki adı `TestPC`; bildirim WELCOME adını gösterir); sunucuda `HELLO`, 5 sn'de bir `PING` | GEÇTİ |
| 4 | Yönlendirme | TeamViewer önde, tuşlar sunucuda, tablette etki yok | bildirim "Relaying"; `a b c` (30/48/46); Alt+Tab×3: 56↓ 15 15 15 56↑; Win (125); Win+E (125↓ 18 125↑); Ctrl+Alt+Del → `SAS` (Del tekrarı kadar); Win+L → `LOCK`; üst sıra tuşlar 87/88/110 (F11/F12/Insert — MX Keys Fn kilidine göre); tablette etki yok | GEÇTİ |
| 5 | Ön plan | Notes'ta yazı tablete, TeamViewer'da sunucuya | Notes önde ~7,5 dk boyunca sunucuya tuş gitmedi; TeamViewer'a dönünce yeniden aktı | GEÇTİ |
| 6 | Kısayol | Ctrl+Alt+Space duraklat/sürdür; bölünmüş ekranda aç/kapat | "Paused" → "Relaying"; bölünmüş ekranda çalıştı. **Yan etki:** yönlendirme kapalıyken Ctrl+Alt+Space Android'e de ulaşıyor ve Samsung klavye dilini değiştiriyor | GEÇTİ (bulgu) |
| 7 | Kutucuk | dokun → Duraklatıldı, tekrar → etkin | "Paused" → etkin | GEÇTİ |
| 8 | Kopma | ~10 sn'de "Connecting", klavye tablette; sunucu dönünce kendiliğinden | "Connecting", yazı tablete; sunucu yeniden başlayınca `HELLO` (Gradle başlatması dahil 36 sn). **Bulgu:** kullanıcı bölünmüş ekrandaydı; tam ekrana geçmek yönü güncellemedi (çoklu pencerede odak değişimi olay üretmiyor, T1 §2); ana ekrana gidip TeamViewer'ı yeniden açınca "Relaying" ve tuşlar sunucuda | GEÇTİ (zaman ölçülmedi — "~10 sn" ölçütü ölçülmedi; kullanıcı "Connecting"i gördü; bulgu) |
| 9 | Açılış | kilit açılışından ≤ 60 sn içinde dokunmadan `HELLO`, TeamViewer açılınca `KEY` | `USER_UNLOCKED` 19:27:51.1 (tablet) → sunucuda `HELLO` 19:28:45.2 (devpc) → attach 19:28:46.2 (tablet); ~55 sn. Kilit açılışı ve attach tablet saatiyle, `HELLO` devpc saatiyle; iki saat arasındaki fark ölçülmedi. TeamViewer açılınca `KEY` 19:29:29.5; yardımcı `connected 192.168.1.20:47111` | GEÇTİ (sınıra yakın) |
| 10 | Kanıtlar | loglar | `%TEMP%\p2-helper.log`, `p2-logcat.txt`, `fake-server-1.log`, `fake-server.log` | alındı |

### Bulgular

- **Kısayol ile Samsung dil değiştirme çakışması:** Yönlendirme kapalıyken klavye yakalanmadığı için Ctrl+Alt+Space Android'e de gidiyor ve klavye dilini değiştiriyor (gözlendi). Yakalamasız bir tuşun Android'den gizlenemeyeceği tasarım çıkarımıdır, ölçülmedi. Plan kısayol tuşunu Ayarlar'dan (Space / ` / Insert) seçtiriyor; kullanıcı kararı (2026-10-08): varsayılan Ctrl+Alt+` olacak (T14).
- **Bölünmüş ekrandan çıkış:** TeamViewer bölünmüş ekranda görünürken tam ekrana geçmesi yeni "öne geldi" olayı üretmiyor; son öne gelen uygulama (Notes) belirleyici kalıyor. Kullanıcı uygulama değiştirerek ya da kısayolla düzeltebiliyor. Belgeye (T16) not.
- **Açılış süresi sınıra yakın:** iki ölçümde ~53 ve ~55 sn; hedef ≤ 60 sn. Sıklığı ve dağılımı ölçülmedi.

## T16 — uçtan uca doğrulama (2026-10-09)

Cihaz SM-X910 / Android 14, test sunucusu devpc'de (`:core:fakeServer`), debug APK. Ham çıktılar ve ekran
görüntüleri repo dışında (`.superpowers/screenshots/2026-10-09-android/`, `t16-results.txt`).

### Step 1 — testler (bitiş 8, Android)

`gradlew -p android test assembleDebug` → `BUILD SUCCESSFUL`. T15 sonundaki sayılar: core 190, app 56 test.

### Step 2 — ön plan geçişi (bitiş 3)

Zaman: işaret satırı (`am start` öncesi/sonrası tablet saati) → helper logundaki `grab=` satırı.

| Geçiş | Ölçümler (ms) | Sonuç |
|---|---|---|
| TeamViewer → Notes (klavye tablete) | 226, 119, 123, 115, 121 | GEÇTİ (≤ 500) |
| TeamViewer → ana ekran | 140, 119, 81 | GEÇTİ (≤ 500) |
| Notes → TeamViewer (klavye PC'ye) | 61, 43, 40, 16 | GEÇTİ; işaret `am start` döndükten sonra alındığı için gerçek süre daha uzun olabilir |

### Step 3 — boşta CPU (bitiş 7, tablet)

`/proc/<pid>/stat` utime+stime, CLK_TCK 100, PC'ye bağlı, klavye boşta.

| Koşul | Süre | helper | uygulama | Toplam | Sonuç |
|---|---|---|---|---|---|
| Ekran açık, Durum ekranı görünür | 604 sn | %0,075 | %0,21 | %0,29 | — (hedef koşulu değil) |
| Ekran kapalı (Dozing), ilk sürüm | 604 sn | %0,070 | %0,129 | %0,20 | KALDI |
| Ekran kapalı, düzeltme sonrası, `compile -m speed` | 606 sn | %0,053 | %0,020 | **%0,073** | Koşullu geçti (aşağıda) |

Düzeltme: helper ping başına RTT'yi uygulamaya yalnız gecikmeyi gösteren ekran görünürken gönderiyor
(`RttWatch`); RTT ayrı akış. Helper'ın kalan maliyeti `relay-connect`/`relay-writer` thread'lerinde; `perfetto_hprof_`
ve `FileObserver` thread'leri de pay alıyor (debug APK'ya özgü olması beklenir, release ile ölçülmedi). Cihaz
kablosuz adb ile bağlıydı; pilde derin Doze davranışı ayrıca ölçülmedi.

**Sonucun sınırları.** KALDI ile geçen ölçüm arasında iki değişken birlikte değişti:
- kod düzeltmesi (512baad, `RttWatch` ve ayrı RTT akışı);
- `cmd package compile -m speed -f dev.keyrelay` (zorla AOT derleme; normal kurulumda yok).

Düzeltmenin tek başına etkisi ayrıştırılmadı. Ek koşullar: debug APK, tek 606 sn'lik pencere, ölçüm boyunca kablosuz
adb bağlı (ürün bu bağlantıyı açılıştan sonra kapatır), PC açık ve bağlı.
**Bitiş 7, tablet kısmı: debug yapıda koşullu geçti; release yapıyla ve PC kapalı durumda P4'te yeniden ölçülecek.**
Ölçüm koşulları: zorla derleme yok, adb kapalı, ekran kapandıktan ≥ 2 dk sonra, 10 dk. Aynı koşulda 512baad^ ile
512baad karşılaştırılacak.

### Step 4 — açılış ve ağ (bitiş 4)

| # | Durum | Gözlenen | Sonuç |
|---|---|---|---|
| 1 | Ev Wi-Fi'ı, yeniden başlatma | `USER_UNLOCKED` 15:01:21.802 → helper hazır 15:02:16.195, bağlandı 15:02:16.421 (+54,4 / +54,6 sn, aynı saat); kullanıcı bildirimde "Starting" sonra 15:02:17'de "Ready" | GEÇTİ (≤ 60 sn, sınıra yakın; üçüncü ölçüm) |
| 2 | Başka bir ağ (telefon hotspot'u), yeniden başlatma | dokunmadan "Ready"; onay penceresi çıkmadı — bu ağa daha önce "her zaman izin ver" denmişti (kullanıcı) | Onaylanmamış ağ yolu **ölçülmedi** (onaylanmamış ağ yoktu) |

### Step 5 — arayüz (bitiş 10, Android)

Açık ve koyu temada Durum, PC'ler, PC ayrıntısı, Ayarlar, sihirbazın 4 adımı (koyu), bildirim ve hızlı ayarlar
kutucuğu görüntülendi; durum geçişleri ve PC'ler açılışı kaydedildi, animasyonlar kapalı (`animator_duration_scale 0`)
kayıt alındı. Kullanıcı onayı: tasarım 2026-10-09'da onaylı taslak v6.1'e göre yeniden yapıldı, kullanıcı cihazda
"hepsi güzel" dedi; sihirbaz cihazda (T15 Step 10) denendi.

**Bulgular:**
- PC'ler ekranında sistem Geri hareketi Durum'a dönmüyor, uygulamadan çıkıyordu (uygulama içi geri oku çalışıyordu); düzeltildi, kullanıcı PC'ler ve Ayarlar'da cihazda doğruladı.
- Sihirbaz adım 1: `DEVELOPMENT_SETTINGS_ENABLED`, `ADB_ENABLED` ve `adb_wifi_enabled` uygulamaya 0 dönüyor (Android referansı: üçüncü taraf uygulamalara her zaman 0); adım "doğrulanamadı" gösteriyor, ilerleme kilitli değil.
- Klavyeler bölümü ölçüm sırasında "klavye bulunamadı" gösterdi (MX Keys o an bağlı değildi; kullanıcı doğrulamadı).

### P4'e devredilenler (Android)

Sahibi: P4 planı (uçtan uca + sertleştirme). Kaynak: P2 ön tarama Ruling #5/#6, ledger, son tam dal incelemesi
(`final-review.md`).

**Yapılmayan cihaz denetimleri (Ruling #5/#6, T16'ya verilmişti):**
1. VPN açıkken yeni ağ: onay/uyandırma akışı (D3) VPN altında çalışıyor mu.
2. PC kapalıyken 10 dk CPU/pil: `RelayClient` en geç `pingInterval` (5 sn) arayla yeniden dener; her deneme bir
   oneway publish ve uygulamada ana thread uyanışı. Bitiş 7'nin ölçmediği, en sık yaşanacak boşta durum.
3. `sendTimeout`'un cihazda doğrulanması: PC okumayı durdurunca ilk tuşta ~1 sn içinde kopma.
   Test sunucusu süreci dondurularak ölçülecek.
4. `pm revoke dev.keyrelay android.permission.WRITE_SECURE_SETTINGS` yedeği: `pm clear`'ın izni silip silmediği
   doğrulanmadı. Taze kurulum yolu bu komutla sınanacak.
5. Cihazda "Yeniden dene": bildirim/Durum düğmesiyle ERROR/APPROVAL durumundan (T11 Step 5 denenmedi).

**Ölçülmeyen ya da görülmeyen:**
- Onaylanmamış ağ yolu (bitiş 4): yeni, daha önce izin verilmemiş bir ağda açılış (Step 4 #2).
- Klavyeler bölümünün bağlı klavyeyle görülmesi (bitiş 10, Step 5 bulgusu).
- CI: `.github/workflows/android.yml` dal push edilmediği için koşmadı; push sonrası yeşil görülecek.

**P4'e kadar düzeltilecekler (son incelemenin triyajı):**
- Protokol vektörleri: BAD_PAYLOAD dalları (PAIR/PAIRED/HELLO/SAS/PING) `frames.json`'da yok (P3 ile ortak).
- QR: `n` uzunluğu ve adres sayısı için üst sınır (herhangi bir uygulama `keyrelay://` açabilir; güven sınırı).
- `:core` için Android API düzeyi denetimi (lint NewApi koşmuyor; T3'te API 34 riski yalnız gözle yakalandı).
- API 30 (Android 11) attach dalı hiç koşmadı; API 30 öykünücüsünde bir koşu.
- "pong > ping" kuralı üç yerde (`AppSettings`, `withTimes`, `ConnectionConfig`); tek yer `AppSettings` olsun.
- Release R8 keep kuralları (yansıma, `app_process` yardımcısı); release yapı açılınca yardımcı kırılır.
- T14 M3: Durum ekranında kare başına fırça ayırma (yalnız ekran açıkken).

**Son incelemenin Minor bulguları (M1–M10):**
- M1: `AdbSetup.pair` zaman aşımı libadb eşleştirme soketini kesmiyor (soket zaman aşımsız açılıyor).
- M2: Keystore token'ı açamazsa hata yayınlanmıyor, durum süresiz "Bağlanıyor".
- M3: `keepSettingsInSync` içinde DataStore hatası toplayıcıyı öldürüyor.
- M4: `MainActivity` izin denetimini `AdbSetup.hasSecureSettingsPermission` yerine kendi sabitiyle yapıyor.
- M5: `launcher` ↔ `service` paket döngüsü (`classifyConnect` `AdbManager`'a taşınmalı).
- M6: adb'ye eşleşmiş bir istemci (uid 2000) yardımcıyı taklit edip token'ı alabilir; spec güvenlik bölümüne not.
- M7: `PairingCodeReceiver` KDoc'unda geliştirici notu (M1 çözülünce tek cümle).
- M8: DNS sorgusu ortak `ForkJoinPool`'da; zaman aşımına düşen sorgu thread tutar (Funnel denemesinde gözle).
- M9: REPAIR bildiriminde ayrıntı satırı boş, tek eylem "Duraklat".
- M10: Klavyeler bölümü yalnız "klavye bulunamadı" hâliyle görüldü.

### P3'e devredilenler

QR'ın Windows arayüzünden kod tarayıcıyla okutulması (P2'de bağlantı adb ile açıldı); Windows'a özgü bitiş
maddeleri (1, 2, 5, 6, 7'nin PC kısmı, 9). P3 bu maddeleri devclient ile gösterdi (`2026-10-risk-results.md`,
"P3 Windows doğrulaması"); tabletle uçtan uca tekrar P4'te.
