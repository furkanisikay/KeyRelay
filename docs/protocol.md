# KeyRelay protokolü

Test vektörleri: `protocol/test-vectors/frames.json`.

## Çerçeve biçimi

TLS 1.3 üstünde çerçeveler: `u16 uzunluk (big-endian, tip dahil) | u8 tip | yük`.

`u16 uzunluk` alanının değeri (tip baytı + yük) 1 ile 512 arasında olmalıdır (tel üzerinde çerçeve en çok 514 bayt); 0 veya 512'den büyük değer bağlantıyı kapatır.

## Test vektörleri

`protocol/test-vectors/frames.json` iki platformun çözücü testlerinin ortak girdisidir.

- `channels`: geçerli vektörün hangi kanalda kullanıldığı (`tls` = tablet↔servis, `pipe` = servis↔ajan). Her çözücü yalnız kendi kanalının geçerli vektörlerini kullanır; geçersiz vektörler iki kanal için de geçerlidir.
- `error` değerleri: `empty` (uzunluk 0), `too_large` (uzunluk 512'den büyük), `unknown_type`, `truncated` (akış, bildirilen uzunluk dolmadan bitti), `bad_payload` (çerçeve tam ama yükün boyu veya değeri hatalı).

## Mesajlar

| Yön | Tip | Yük |
|---|---|---|
| C→S | `0x01 PAIR` | `u8 sürüm, 16B tek kullanımlık kod, utf8 cihaz adı` |
| S→C | `0x02 PAIRED` | `16B cihaz kimliği, 32B cihaz token'ı` |
| C→S | `0x03 HELLO` | `u8 sürüm, 16B cihaz kimliği, 32B token` |
| S→C | `0x04 WELCOME` | `utf8 PC adı` |
| S→C | `0x05 REJECT` | `u8 neden (1 sürüm, 2 bilinmeyen cihaz, 3 kod geçersiz/süresi dolmuş, 4 eşleştirme bu yoldan yapılamaz)` |
| C→S | `0x10 KEY` | `u16 evdev keycode, u8 değer (0 bırak, 1 bas, 2 tekrar)` |
| S→C | `0x11 STATE` | `u8 bayrak (bit0 hazır)` |
| C→S | `0x12 SAS` | — (Ctrl+Alt+Del; Del yutulur, Ctrl/Alt basma-bırakmaları normal KEY olarak akar) |
| C→S | `0x13 LOCK` | — (Win+L; L yutulur, Win normal akar) |
| C→S | `0x20 PING` | `u32 sıra` |
| S→C | `0x21 PONG` | `u32 sıra` |

- Tuş tekrarı: Android `EventHub` klavyelerde çekirdek tekrarını kapatır (`EventHub::Device::configureFd`,
  `EVIOCSREP {0, 0}`, AOSP `frameworks/native`). Bu yüzden tekrarı tablet yardımcısı üretir ve `değer=2` gönderir.
  Windows ajanı `değer=2`'yi yeniden basış (key-down) olarak işler, ama yalnız tuş hâlâ basılıysa. Bırakmadan ya da
  `RELEASE_ALL`'dan sonra ağdan geç gelen `değer=2` yok sayılır, fazladan karakter yazılmaz. Ajan kendi tekrarını
  üretmez. (P4'te ajan tarafı tekrar denendi: geç gelen bırakma fazladan karakter yazdırdı, kaldırıldı.)
- Medya tuşları (113–115, 163–166) yalnız basışta, `SendInput` sanal tuşu (`VK_VOLUME_*`, `VK_MEDIA_*`; basış +
  bırakma) olarak gönderilir; ses kilit ekranında da, parça tuşları yalnız `Default` masaüstünde.
- Fare tuşları da `KEY` çerçevesiyle gelir (evdev `BTN_*`, protokol değişmez; eski ajan bunları yok sayıp `KEY_DONE`
  döner). Ajan `SendInput` fare girdisine çevirir: `BTN_MIDDLE (0x112)` → orta tuş; `BTN_SIDE (0x113)` ve
  `BTN_BACK (0x116)` → `XBUTTON1`; `BTN_EXTRA (0x114)` → `XBUTTON2`; basış/bırakma ayrı, tekrar yok, basılıysa
  `RELEASE_ALL` bırakır. `BTN_FORWARD (0x115)` ve `BTN_TASK (0x117)` (hareket tuşu) yalnız basışta Win+Tab (Görev
  Görünümü) olur. Masaüstü/yazılabilirlik kuralı ve `KEY_DONE` gecikme ölçümü tuşlardaki gibidir.

## Eşleştirme, kimlik doğrulama ve canlılık

- QR içeriği: `keyrelay://pair?v=1&n=<PC adı>&a=<adres1>,<adres2>,...&p=<port>&fp=<sertifika SPKI SHA-256, base64url>&c=<kod, base64url>`.
  Kod 5 dk geçerli, tek kullanımlık. İstemci adresleri sırayla dener (TCP bağlantısı 2 sn, TLS el sıkışması 8 sn
  timeout), son başarılıyı başa alır. El sıkışması ayrı ve uzun: Funnel'da TCP Tailscale'in ingress'inde hemen biter,
  TLS cevabı PC'ye tailnet üzerinden ulaşıldıktan sonra gelir (evden ~1,3 sn, VPN ile daha uzun).
  QR sırası: Tailscale IP ve MagicDNS adı, ağ geçidi olan LAN adresleri, en sonda Funnel adresi. Funnel sonda çünkü
  servis loopback'ten (Funnel'dan) gelen `PAIR`'i `REJECT(PAIRING_NOT_ALLOWED_HERE = 4)` ile reddeder (kod harcanmaz,
  aynı QR evden yeniden denenebilir): eşleştirme LAN'dan ya da tailnet'ten yapılır, `HELLO` Funnel'dan da kabul edilir.
  Ayrı neden, tabletin "kod yanlış" yerine "ev ağına ya da Tailscale'e bağlan" diyebilmesi için. Uyumluluk: tablet
  `REJECT` nedenini aralık denetlemeden okur, eski tablet 4'ü de diğer `PAIR` retleri gibi "PC kodu kabul etmedi"
  diye gösterir (öncekiyle aynı); yeni tablet eski PC'den 3 alırsa yine o genel mesajı gösterir. Sürüm denetimi gerekmez.
  Adres girdisi `host:port` olabilir; port yoksa `p` kullanılır. Funnel adresi yalnız 443, 8443 veya 10000 portunda olabilir
  (Funnel kısıtı) ve istemci TLS'te SNI olarak host adını gönderir (Funnel yönlendirmesi SNI'ye bakar).
- `n` (PC adı) yüzde kodlanır (RFC 3986; boşluk `%20`, `+` `%2B`); `a` listesindeki adresler kodlanmaz (yalnız harf, rakam, `.`, `-`, `:`), ayırıcı virgüldür.
- `PAIRED`'dan sonra istemci aynı bağlantıda `HELLO` gönderebilir; bağlantıyı kapatıp yeni bağlantıda `HELLO` göndermesi de geçerlidir. HELLO bütçesi (5 sn) bağlantının açılışından sayılır. `REJECT`'ten sonra sunucu bağlantıyı kapatır.
- Tek aktif istemci: yeni bağlantı `HELLO` ile doğrulanınca eski bağlantı kapanır ve servis ajana `RELEASE_ALL` gönderir (eski bağlantıdan basılı kalmış tuşlar).
- İstemci TLS'te yalnız sabitlenmiş SPKI parmak izini kabul eder (CA zinciri ve ad doğrulanmaz).
- HELLO 5 sn içinde gelmezse bağlantı kapanır. Token karşılaştırması sabit zamanlı.
- Kimlik doğrulama hataları cihaz/IP başına üstel geri çekilmeyle yavaşlatılır.
- `HELLO` bekleyen (TLS el sıkışması dahil) bağlantı sayısı loopback (Funnel) ve doğrudan kaynaklar için ayrı ayrı
  en çok 8'dir; aşan bağlantı TLS'e başlamadan kapatılır. Doğrulanmış oturum bu sayıya girmez.
- Eşleştirme, bağlantı, red ve el sıkışma hataları Application olay günlüğüne de yazılır (kaynak `KeyRelay.Service`);
  kimliği doğrulanmamış tarafın tetikleyebildiği satırlar dakikada 20 ile sınırlıdır, atlananların sayısı sonraki satırda yazılır.
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

### PC kimlik kodu

Tabletin eşleştirme onay ekranı ve tepsinin Eşleştir sayfası aynı kısa kodu gösterir; kullanıcı ikisini karşılaştırır.
Kod: QR'daki `fp`'nin çözülmüş 32 baytının (sertifika SPKI SHA-256) **ilk 5 baytı**, RFC 4648 base32 (alfabe
`ABCDEFGHIJKLMNOPQRSTUVWXYZ234567`, büyük harf, dolgusuz; 40 bit = 8 karakter), `XXXX-XXXX` biçiminde.

Test vektörü: `fp=nhDFng6PxrMUestQPWHqag0G99AYI1PSvnW94Q3IWDA` (ilk baytlar `9e10c59e0e`) → `TYIM-LHQO`.

## Servis ↔ ajan pipe mesajları

`\\.\pipe\KeyRelay.Agent` (ACL yalnız SYSTEM) üzerinde aynı çerçeve biçimi (`u16 uzunluk | u8 tip | yük`) kullanılır.

| Yön | Tip | Yük | Anlam |
|---|---|---|---|
| S→A | `0x10 KEY` | `u16 evdev keycode, u8 değer` | Tuşu bas/bırak/tekrarla |
| S→A | `0x13 LOCK` | — | `LockWorkStation` |
| S→A | `0x30 RELEASE_ALL` | — | Ajanın tuttuğu tüm basılı tuşları bırak (istemci koptu) |
| A→S | `0x31 DESKTOP` | `u8 yazılabilir (0/1), utf8 masaüstü adı` | Yazılabilirlik veya giriş masaüstünün adı değişti |
| A→S | `0x32 KEY_DONE` | `i64 okundu, i64 masaüstünde, i64 bitti` (big-endian) | Son KEY için `SendInput` döndü. Üç QueryPerformanceCounter damgası: ajan çerçeveyi pipe'tan okudu / giriş masaüstü kontrolü bitti (`SendInput`'tan hemen önce) / `SendInput` döndü (eşlenmeyen tuşta ikincisi = birincisi). Servis gecikme ölçümü (bitiş 7) için TLS okuma anıyla sırayla eşler ve süreyi parçalara böler |
| A→S | `0x33 AGENT_LOG` | `u8 uyarı (0/1), utf8 satır` | Ajanın tanı satırı; servis uyarıyı Warning (Kayıtlar'da görünür), diğerini Debug olarak yazar. Şimdilik yalnız medya tuşu sonucu (`SendInput` sayısı ve hata kodu; başarısızsa uyarı). Yazılan metin içermez |

- Ajan, pipe sunucusunun süreç kimliğinin komut satırında aldığı servis pid'i olduğunu doğrular; değilse tuş basmadan çıkar. Servis, istemcinin kendi başlattığı ajan olduğunu doğrular.
- Ajan her KEY için (tuş eşlenemese de) bir `KEY_DONE` gönderir; sıralama bozulmaz.
- Servis her pipe yazımında 1 sn timeout uygular; aşılırsa ajan ölü sayılır ve yeniden başlatılır.
- Yeni başlayan ajan, açılışta sol/sağ Ctrl, Alt, Shift ve Win için key-up gönderir (önceki ajandan kalmış basılı modifier'ları temizler; tasarım kapısı takip #27).
- `SAS` pipe'a gitmez: servis `SendSAS(FALSE)`'ı kendisi çağırır.

## Tepsi ↔ servis kontrol kanalı

`\\.\pipe\KeyRelay.Control` (ACL: SYSTEM ve Administrators tam, INTERACTIVE okuma/yazma). Satır başına bir JSON mesajı
(UTF-8, `\n` ile biter, en çok 64 KB; aşan bağlantı kapanır), alan adları camelCase. İstek `{"op":..., "deviceId":..., "funnelAddress":...}`,
yanıt `{"ok":true|false, "error":..., <istenen alan>}`; yalnız isteğe karşılık gelen alan doludur. Tipler
`KeyRelay.Protocol.Control` (`ControlMessages.cs`). İstemci istek başına 3 sn, bağlanma için 2 sn bekler; sunucu 60 sn
boşta kalan bağlantıyı kapatır.

| `op` | Yanıt alanı | Oturum kısıtı | Anlam |
|---|---|---|---|
| `status` | `status` | — | Ajan, oturum, giriş masaüstü, bağlı cihaz, p50/p99, son 60 sn, bugünkü tuş sayısı, eşleştirme açık mı, cihaz sayısı |
| `pair-start` | `pairing` | ajan oturumu | 5 dk'lık tek kullanımlık kod açar: QR bağlantısı, adresler, bitiş, PC kimlik kodu |
| `pair-stop` | — | ajan oturumu | Açık kodu geri çeker |
| `devices` | `devices` | — | Eşlenmiş cihazlar; `error: devices_set_aside` (ok=true) ise devices.json okunamadı, yedeğe alındı ve liste boş başladı |
| `revoke` | — | ajan oturumu | `deviceId`'yi siler ve bağlıysa bağlantısını hemen kapatır |
| `network` | `network` | — | Port, Funnel adresi, sertifika parmak izi, QR'a konacak adresler |
| `set-funnel` | — | ajan oturumu | `funnelAddress`'i kaydeder (boş = kaldır) |
| `logs` | `logs` | — | Son 500 log satırından JSON'da 60 KB'a sığanlar, eskiden yeniye |
| `recent-keys` | `recentKeys` | ajan oturumu (başka oturum boş liste alır, toplama başlamaz) | Son en çok 8 tuş adı (eskiden yeniye) ve 5 sn'lik toplama kirası; kurallar spec Güvenlik bölümünde |
| `recent-keys-stop` | — | — | Toplamayı durdurur ve tutulanları siler |
| `latency-breakdown` | `latencyBreakdown` | — | Tanı: son 1 dk'nın gecikmesi parçalara bölünmüş: `total`, `service` (TLS okuma → pipe yazımı döndü), `pipe` (→ ajan okudu), `desktop` (→ giriş masaüstü kontrolü bitti), `sendinput` (→ `SendInput` döndü) ve `own` (tuş başına total − sendinput: bizim kodumuzun payı, bitiş 7); her biri için örnek sayısı, p50, p99, en büyük (µs). Tuş değeri içermez |

Oturum kısıtı: servis istemci oturumunu `GetNamedPipeClientSessionId` ile alır ve ajanın çalıştığı oturumla karşılaştırır.

Hata kodları (`error`, `ok=false`): `unknown_op`, `unknown_device`, `invalid_funnel_address`, `wrong_session` (komut
başka oturumdan geldi), `internal_error` (servis yanıtlarken hata aldı; nedeni servis logunda, Kayıtlar sayfasında görünür).
