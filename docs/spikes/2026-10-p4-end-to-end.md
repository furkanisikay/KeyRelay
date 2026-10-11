# P4 uçtan uca doğrulama (2026-10-09 — 2026-10-12)

Cihazlar: Galaxy Tab S9 Ultra (SM-X910, Android 14), Logitech MX Keys Mini, homepc (Windows 11 26300).
Tablet homepc'ye tray'deki QR ile eşleştirildi (kimlik kodu TYIM-LHQO, kullanıcı karşılaştırdı). Uzaktan
erişim Tailscale Funnel `--tcp 8443 → localhost:47111` (TLS uçtan uca; Tailscale içeriği açmaz).

## Bitiş maddeleri

| Madde | Gözlenen | Sonuç |
|---|---|---|
| 1 Kısayollar | TeamViewer önde: Alt basılı + Tab×3, Win, Win+E, Win+D, Win+Shift+S, `ğüşıöç ĞÜŞİÖÇ`, AltGr+Q (`@`) çalıştı (kullanıcı). Ses tuşları Fn ile: `VK_VOLUME_*` SendInput, Windows ses göstergesi çıktı ve ses arttı (kullanıcı) | GEÇTİ |
| 2a SAS / 2b kilit PIN | Ctrl+Alt+Del güvenlik ekranı, Win+L sonrası PIN yazıldı (kullanıcı, tabletle) | GEÇTİ |
| 6 QR ile eşleştirme | Tablet kamerasıyla tray QR'ı okundu; tablet Cihazlar'a düştü; Funnel adresi tablete geçti | GEÇTİ |
| 7 PC gecikmesi | Gerçek yazım, 1776 tuş (µs): total p50 772 / p99 3687; service 49/1780; desktop 34/605; sendinput 528/1995. `sendinput` p99 ≈ 2 ms üçüncü taraf klavye kancalarından (TeamViewer); spec hedefi "bizim payımız (own) p99 < 2 ms, SendInput ayrı raporlanır" olarak güncellendi. `own` satırı eklendi, cihazda ölçülmedi | AÇIK (own ölçülmedi) |
| 9 Prototip | homepc'de `kbd` görevi/süreç/klasör yok; tablette `kbd` paketi/dosyası yok; Funnel `--tcp` | GEÇTİ |
| 10 Arayüz | v6.1 ve P4 düzeltmeleri cihazda kullanıcıyla gözden geçirildi; sinyal halkaları, "Nasıl çalışır?" kartı, ⓘ metinleri, durum çubuğu ikonları onaylandı | GEÇTİ (kullanıcı) |

## Kafe senaryosu (ana kullanım)

Tablet telefon hotspot'unda, ProtonVPN açık, Tailscale kapalı (tek yol Funnel):

- İlk denemede bağlanmadı: `homepc.tailnet-example.ts.net` genel DNS'te **NXDOMAIN** (Cloudflare, Google, Quad9 DoH).
  9 Ekim'de tablet logunda aynı ad genel IP'ye çözülüyordu; kayıt sonradan kaybolmuş. Funnel'ı kapatıp açmak
  10 dk içinde düzeltmedi. Tailscale'in açık hatalarıyla aynı belirti (tailscale/tailscale #21502, #21698, #21429).
- Geçici çözüm (#11849): yönetim panelinde `nodeAttrs` funnel girdisi silinip kaydedildi, geri eklenip kaydedildi
  → kayıt döndü (203.0.113.x röleleri). Ardından homepc'de Funnel yeniden başlatılınca röle üzerinden TLS
  tamamlandı; SPKI pin ile birebir aynı (devpc'den, SNI ile).
- Tablet 01:21:03'te Funnel üzerinden bağlandı; "Ready", TeamViewer'da tuşlar çalıştı (kullanıcı). ProtonVPN
  kopunca KeyRelay de koptu; VPN dönünce 12 sn içinde kendiliğinden yeniden bağlandı (servis logu 01:21:55 →
  01:22:07).

## Cihazda bulunup düzeltilenler

- Basılı tutulan tuş Windows'ta tekrar etmiyordu: Android EventHub çekirdek tekrarını kapatıyor
  (`EVIOCSREP {0,0}`), grab ile `value=2` hiç gelmiyor. Tekrar helper'da üretiliyor; PC tarafında üretmek ağ
  gecikmesiyle fazladan karakter yazıyordu.
- "Ses tuşları çalışmıyor" gözlemi geçersizdi: tuşlar Fn olmadan Insert gönderiyordu. Uçtan uca log satırı
  eklenince ayrıldı; ara turlarda denenen Core Audio ve WM_APPCOMMAND yolları kaldırıldı.
- Android eşleştirme sahnesindeki kısa siyah çizgi: kimlik kodundaki "-" ayracı katlanmada gizlenmiyordu.
- Windows eşleştirme sahnesi çok hızlıydı: storyboard'lar planlanan süre + 100 ms sonra durduruluyordu, oysa
  başlangıç bir kare sonra; her perde artık kendi bitişini bildiriyor.
- Kontrol kanalında durum değiştiren komutlar yalnız etkin oturumdan kabul ediliyor.

## Açık kalanlar

- Bitiş 7: `own` p99 cihazda ölçülmedi; tablet boşta CPU release yapıyla ve PC kapalıyken ölçülmedi; PC boşta
  CPU ölçülmedi.
- Bitiş 4: hiç izin verilmemiş ağda açılış (onay penceresi yolu) ölçülmedi.
- Funnel DNS kırılganlığı: öneri — tablet son çalışan röle IP'sini saklasın, DNS çözülemezse o IP'ye aynı SNI
  ile bağlansın. Ev ağı adresi her turda 2 sn harcıyor; ağ türüne göre sıralama önerilir.
- P2 ve P3 son incelemelerinden "P4'e kadar düzelt" listeleri (iki sonuç belgesi ve iki final-review dosyası).
