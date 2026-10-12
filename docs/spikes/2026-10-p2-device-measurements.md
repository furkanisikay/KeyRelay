# P2 cihaz ölçümleri (Görev 1)

Cihaz: SM-X910, Android 14 / SDK 34. Tarih: 2026-10-08. Ham loglar repo dışında: `%TEMP%\p2-fg.log`, `p2-marks.txt`, `p2-approval.log`, `p2-approval-logcat.txt`, `ab-*.txt`.
Damgalar `date +%s%3N` (tablet saati); olay zamanları aynı saatle yazıldı, farklar ms.

## 1. IProcessObserver işlem kodları

```
IProcessObserver codes from platform stub: foreground=1 died=3
```
Satır aynen göründü (izleyici başlangıcında, logun 3.–4. satırı). Kodlar Android 14'te çalışma anında `IProcessObserver$Stub`'dan çözülüyor.

## 2. Ön plan olayları

Uid tablosu: TeamViewer (hedef) 10290, Notes 10280, launcher 10092, systemui 10074, intentresolver 10121; paylaşım penceresi olayı uid 1000'den geldi.

**Tohumlama (Step 5 b):** TeamViewer arkadayken (pid 18139 canlı) `seed top-app={18350=10280, 19831=1000, 20628=10195}` — hedef pid top-app'te **yok**, bayat tohum gözlenmedi.

| Senaryo | Tekrar | Başka uygulamanın `true` olayı (damgadan ms) | Hedefin `false` olayı (ms) | Eski kural (`any(isTarget)`) | Yeni kural |
|---|---|---|---|---|---|
| S1 TeamViewer → Notes | 5 | Notes: 130, 132, 118, 113, 111 | 505, 466, 459, 445, 442 | hedef `false` ile: 442–505 | Notes `true` ile: 111–132 |
| S2 TeamViewer → ana ekran | 3 | launcher: 222, 84, 97 | 1084, 466, 471 | 466–1084 | 84–222 |
| S3 Son uygulamalar | 3 | launcher: 223, 136, 126 | 1575, 788, 680 | 680–1575 | 126–223 |
| S4 Paylaşım penceresi (yarı saydam) | 3 | uid 1000: 146, 102, 77 | gelmedi (hedef görünür kalıyor) | hiç bırakmaz | 77–146 |
| S4 Paylaşım → Geri | 3 | — (uid 1000 `false`: 423, 359, 390) | — | — | 359–423'te hedefe döner |
| S4 Bildirim perdesi | 3 | olay yok | olay yok | değişmez | değişmez (klavye PC'de kalır) |
| S5 PiP | — | denenmedi: YouTube PiP Premium istiyor | | | |
| S5' Samsung açılır pencere (yerine) | 1 | Notes açılır pencere `true`; TeamViewer `false` gelmedi; pencereler arası dokunuş olay üretmedi | — | hedef önde sayılır | Notes son `true` → hedef önde değil; TeamViewer'a dokunmak değiştirmez |
| S6 Bölünmüş ekran | 1 | bölmeler arası dokunuş olay üretmedi; kurulumda son `true` TeamViewer | — | hedef önde | hedef önde; Notes bölmesine dokunmak değiştirmez |

Damga anında hedefin "ön planda değil"e geçişi yeni kuralla en geç 223 ms (S1–S4, 14 ölçüm); eski kuralla 1575 ms'ye kadar, S4'te hiç.
Çoklu pencerede (açılır pencere, bölünmüş ekran) odak değişimi IProcessObserver'da görünmüyor: yön, son öne gelen uygulamaya göre kalır; değiştirmek için kısayol (override) gerekir.

## 3. Yeni ağ onayı (telefon hotspot'u)

Uygulama `adb_wifi_enabled`'ı geri okuyamıyor (her `onChange` satırı `read=0`; P1'deki bulguyla aynı), bu yüzden sistem tarafı logcat'ten (`AdbService`, `AdbDebuggingManager`) okundu.

| Deneme | Gözlem |
|---|---|
| H1 (30 sn, dokunma) | `put 1`'den 13 ms sonra `startConfirmationForNetwork` → onay penceresi; +121 ms `setAdbEnabled(false)` (sistem 1→0 geri çekti). `onChange` iki kez (+43, +136 ms). `autoConnect` 20 sn sonra `InterruptedException: Timed out while trying to find a valid host address and port`. Bizim `put 0`'ımız pencereyi **kapatmadı**; pencere `end`'den sonra da açıktı (kullanıcı gözlemi). |
| H2 (30 sn, yalnız "İzin ver") | Onay +0,8 sn: `startAdbDebuggingThread`, `adb start wireless adb`, tls port +1,8 sn; `onChange` onay anında ve +1,8 sn'de teslim edildi; `autoConnect=true` +2,06 sn. |
| H3 (5 sn, 0'dan sonra onay) | Deneme H2'nin bitişiyle çakıştı (kullanıcı `end`'i beklemeden başlattı). `put 0` (+25 sn) sonrasında `adb start wireless adb` satırı yok; kullanıcı kablosuz hata ayıklamanın kapalı kaldığını doğruladı. Onayın tam anı belirsiz (+7,7 sn'de bir `setAdbEnabled(false)` var). Sonuç: 0'dan sonra verilen onay kablosuz hata ayıklamayı açık bırakmadı — zamanlama belirsiz, tek örnek. |
| (ara deneme, 5 sn) | Kullanıcı yeniden bastı ve hızlı onayladı: `autoConnect=true` +2,4 sn. |
| H4a (30 sn, "her zaman izin ver") | `MESSAGE_ADB_PERSIST_KEYSTORE`; `autoConnect=true` +2,7 sn. |
| H4b (30 sn, aynı ağda ikinci kez) | Pencere açılmadı; `adb start wireless adb` ve tls port `put 1`'den +15 ms sonra (sessiz başlangıç). **Ama `autoConnect` 20 sn'de mDNS ile adresi bulamadı** (`Timed out while trying to find a valid host address and port`). Diyalogla onaylanan turlarda bulma ~1 sn sürmüştü. Neden bulunamadı; tek örnek, sıklığı ölçülmedi. `service.adb.tls.port` shell'den de okunamıyor (`adbd_prop`), mDNS'e alternatif yol yok. |

Sonra ev ağına dönüş: `wifi lost 104` → `wifi available 105`; kullanıcı kablosuz hata ayıklamayı açtı, bağlantı normal.

## 4. Otomatik Engelleyici

Samsung iç adı "rampart" (`settings secure`: `rampart_blocked_adb_cmd`, `rampart_blocked_commands` … kapalıyken hepsi 0). Ayar etkinliğinin adı `dumpsys package com.android.settings` içinde `autoblock` kalıbıyla bulunamadı.
Kullanıcı açtığı anda kablosuz adb **kesildi** (`device offline`); açıkken ayarlar okunamadı, değişen anahtar ölçülemedi. Otomatik Engelleyici kablosuz adb'yi engelliyor.

## 5. Kararlar

| Karar | Gözlem | Etki |
|---|---|---|
| D1 ön plan kuralı (T4) | S1–S4'te başka uygulamanın `true` olayı ≤ 223 ms (14/14 ≤ 500 ms) | T4 yazıldığı gibi. Ek bilgi: çoklu pencerede odak değişimi görünmüyor → kısayol gerekir (spec override bunu kapsıyor; T16'da kullanıcı belgesine not). |
| D2 tohumlama (T9) | Arka plandaki TeamViewer pid'i top-app'te değil | T9 yazıldığı gibi. |
| D3 yeni ağ akışı (T7, T11) | (a) sistem 1→0 geri çekiyor (+121 ms) ve pencere açıyor; (b) bizim 0 açık pencereyi kapatmıyor; (c) onayda `onChange` geliyor (H2) | Dur yok. T7 ve T11 yazıldığı gibi (gözlemci uyandırıcı çalışır). Ek: H4b'de sessiz başlangıçta mDNS kaçırması (tek örnek) → "Başarısız" + Yeniden dene yolu karşılar; T16 kafe denemesinde yeniden ölçülür. |
| D4 kod kapısı kanıtı | §1 satırı aynen | Kanıt; kod değişmez. |
| D5 Otomatik Engelleyici (T15) | Açıkken kablosuz adb kesiliyor, anahtar bulunamadı | T15 Step 4'ün (B) dalı. |

## 6. Görev 8 — klavye kapatma/açma (InputReader)

2026-10-08, `--grab=180` teşhis modu, Notes önde; ham log `/data/local/tmp/grab.log`, `grab-hotplug.log` (repo dışı).

- **Sonradan bağlanma:** yardımcı klavye yokken başladı (`keyboards=[]`); kullanıcı klavyeyi bağlayınca inotify ile bulundu (`keyboards=[MX Keys Mini Keyboard]`), ilk tuşlar yakalandı. Bir bağlanmada `/dev/input` olayları ~10–12 yeniden tarama üretiyor (aynı liste tekrar yazılıyor); ek düğüm açılmıyor.
- **Basılı tuşla kapatma:** `29 value=1` (Ctrl) → kullanıcı Ctrl basılıyken klavyeyi kapattı → `29 value=0` (+3,3 sn) → 6 ms sonra dört düğüm `read ... ended: -19` ve dört `node lost`. Bu örnekte çekirdeğin sentetik key-up'ı istemciye **ulaştı** (tek örnek); `releaseHeld` güvenlik ağı olarak kalır.
- **Yeniden açma:** `skip /dev/input/event17: -2` (tarama sırasında kaybolan düğüm), `keyboards=[]`, ardından açılınca +4,7 sn `keyboards=[MX Keys Mini Keyboard]` ve yeni tuşlar yakalandı.
- Kullanıcı gözlemi: üç adımın hiçbirinde tablette karakter/ses değişimi olmadı (grab kapatma-açma sonrası da sürdü). SELinux `avc` reddi yok.
- Prototip (`kbdfwd`) durdurulmadı: yalnız TeamViewer öndeyken yakaladığı için Notes'taki deneme etkilenmedi.
