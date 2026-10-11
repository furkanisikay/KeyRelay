# KeyRelay terim sözlüğü (Android + Windows)

Uygulamalarda kullanıcıya gösterilen metinler bu tablodan alınır. Teknik terim yalnız belgelerde ve kodda kalır;
arayüzde "Kullanıcıya gösterilen" sütunu kullanılır. Bir alanın yanındaki ⓘ işaretinin açıklaması (üzerine gelince
ya da dokununca) "ⓘ açıklaması" sütunundadır. Yeni terim eklenince iki uygulama da bu dosyayı günceller.

ⓘ açıklaması yazım ölçütü:

1. İlk cümle "bu ne gösterir": somut, kullanıcının diliyle.
2. Her durum ya da seçenek için "ne anlama gelir + ne yaparsın".
3. İç terim yok (hizmet, ajan, masaüstü, oturum gibi kavramlar kullanıcının görebildiği şeyle anlatılır).
4. Hitap "sen" (Android ve Windows aynı).
5. 1–3 cümle; yalnız gerçekten yapılabilir adımlar, uydurma yönerge yok.

| Teknik terim | Kullanıcıya gösterilen | ⓘ açıklaması |
|---|---|---|
| Bağlantı durumu (Ready / Waiting / Error) | Bağlı / Bekliyor / Hata | Tabletteki klavyenin şu an bu bilgisayara yazıp yazamadığını gösterir. Bağlı: tablette TeamViewer öndeyken bastığın tuşlar buraya gelir. Bekliyor: tablet henüz bağlanmadı; tabletin açık ve internete bağlı olduğundan emin ol, tabletteki KeyRelay bildirimine bak. Hata: bu bilgisayardaki KeyRelay çalışmıyor; bilgisayarı yeniden başlat, düzelmezse KeyRelay'i yeniden kur. |
| Servis (KeyRelay.Service) | KeyRelay (bu bilgisayarda) | Arayüzde ayrı adı yok; "KeyRelay çalışmıyor" olarak görünür. Bilgisayarı yeniden başlat, düzelmezse KeyRelay'i yeniden kur. |
| Ajan (oturum ajanı) | Yazmaya hazırlanıyor / yazmaya hazır | Arayüzde ayrı adı yok; yalnız hazır olup olmadığı görünür. Birkaç saniye içinde kendiliğinden hazır olur. |
| Giriş masaüstü + yazılabilirlik | Bu bilgisayarda şu an · Açık olan: … · yazılabilir / şu an yazılamıyor | Bu bilgisayarda şu an neyin açık olduğunu ve oraya yazılıp yazılamadığını gösterir. Yazılabilir: sorun varsa tablettedir. Şu an yazılamıyor: Windows o ekranda yazmaya izin vermiyor; kilidi açınca ya da pencere kapanınca kendiliğinden düzelir. |
| Default | masaüstü | — |
| Winlogon | kilit ekranı ya da Windows izin penceresi | — |
| TLS 1.3, sabitlenmiş sertifika (pinning) | Şifreli · bu PC'ye kilitli | Yazdıklarını ağdaki kimse okuyamaz ve tablet yalnız eşleştiği bu bilgisayarla konuşur. Bir şey yapman gerekmez. |
| Sertifika parmak izi (SPKI SHA-256) | Güvenlik kimliği | Tabletin yalnız bu bilgisayara bağlanmasını sağlayan kimlik; başka bir bilgisayar araya giremez. Normalde bakman gerekmez; destek istersen bu değeri paylaşabilirsin. |
| PC kimlik kodu | PC kimlik kodu | Doğru bilgisayarla eşleştiğini kontrol etmen içindir. Tablette görünen kodla aynıysa onayla; farklıysa onaylama. |
| Eşleştirme kodu / QR, geri sayım | Eşleştirme kodu · … kaldı | Kodun ne kadar daha geçerli olduğunu gösterir. Süre dolduysa "Yeni kod"a bas; eski kod artık çalışmaz. |
| Eşleşmiş cihaz | Eşlenmiş tablet | Bu bilgisayara bağlanabilen tabletler. Kaybettiğin ya da artık kullanmadığın tableti iptal et. |
| İptal (revoke) | İptal et | Tablet bir daha bağlanamaz, bağlıysa hemen kopar. Yeniden kullanmak istersen QR ile tekrar eşleştir. |
| Tailscale Funnel | Dışarıdan erişim (internet üzerinden) | Kafede ya da evden uzaktayken tabletin bu bilgisayara bağlanabilmesi için. Kapalıysa tablet yalnız aynı Wi-Fi'deyken bağlanır. Tailscale Funnel ile kurulur; verdiği adresi "İnternet adresi"ne yaz. |
| Funnel adresi | İnternet adresi | Dışarıdan erişim için Tailscale'in verdiği adres (ad:numara). Yazıp kaydedersen yeni eşleştirme kodları bu adresi de taşır; boş bırakıp kaydedersen dışarıdan erişim kapanır. |
| Yerel ağ (LAN) / yol | Ev/ofis ağı · Tuşların yolu | Tuşlarının tabletten bu bilgisayara hangi yoldan geldiğini gösterir: ev/ofis ağı ya da internet. Yol hangisi olursa olsun bağlantı şifrelidir. |
| Port | Bağlantı noktası | Tabletin bu bilgisayara bağlandığı numara. Genelde dokunman gerekmez; bir güvenlik duvarı tableti engelliyorsa izin vermen gereken numara budur. |
| QR adresleri | Tabletin deneyeceği adresler | Tabletin bu bilgisayara ulaşmak için sırayla deneyeceği adresler. Eşleştirme takılıyorsa tablet ile bilgisayarın aynı Wi-Fi'de olduğundan emin ol ya da dışarıdan erişimi aç. |
| PC adresi | Bu bilgisayarın adresi | Tabletin bu bilgisayarı ağda bulduğu adres. Tablet bağlanamıyorsa Eşleştir'den yeni kodu tabletle okut; yeni kod güncel adresi taşır. |
| Gecikme p50 / p99 | Gecikme · tipik / en yavaş | Bastığın tuşun bu bilgisayara ne kadar sürede ulaştığını gösterir. Tipik: çoğu tuş bu kadar sürer. En yavaş: arada bir yaşanan takılma; yazarken takılma hissediyorsan tableti modeme yaklaştır. |
| Saniyelik en yavaş grafiği | Son 1 dakika · her saniyenin en yavaş tuşu | Son bir dakikada tuşların ne kadar geciktiğini gösterir. Yükselen çubuk, o saniyede bir tuşun geç geldiği anlamına gelir; düz grafik her şeyin yolunda olduğunu gösterir. |
| Bugünkü tuş sayısı | Bugün gelen | Bugün tabletten bu bilgisayara kaç tuş geldiğini gösterir. Bastıkça artıyorsa bağlantı çalışıyordur. Yalnız sayı tutulur, tuşların kendisi saklanmaz. |
| Son tuşlar | Son tuşlar | Son bastığın tuşların adlarını gösterir; bir tuşun buraya ulaşıp ulaşmadığını denemek için aç. Yalnız sen görürsün, kilit ekranında hiç tutulmaz; işin bitince kapat. |
| Heartbeat (PING/PONG) | Sinyal | Tablet bağlıyken sinyal noktasından birkaç saniyede bir halkalar yayılır. Halkalar durduysa bağlantı kopmuş olabilir; tabletteki KeyRelay bildirimine bak. |
| Kayıt seviyeleri INFO / WARN / ERROR | Bilgi / Uyarı / Hata | Bir şey ters gittiğinde ne olduğunu gösterir: mavi bilgi, sarı uyarı, kırmızı hata. Destek istersen son satırları kopyalayıp gönder; metinler İngilizcedir. |
| Yönlendirme (Android, routing/grab) | Tuşları PC'ye gönder · PC'ye gönderiliyor · Gönderme duraklatıldı | Tablet klavyesinin PC'ye yazmasını açıp kapatır. Açık: seçtiğin uygulama ekrandayken tuşlar PC'ye gider. Kapalı: klavye hep tablete yazar; tuşlar PC'ye gitmiyorsa önce bunun açık olduğuna bak. |
| Yardımcı (Android helper) | Klavye bağlantısı | (ⓘ yok, yalnız hata metninde) Klavye bağlantısı başlamadı. Yeniden dene'ye dokun; düzelmezse tableti yeniden başlat. |
| Grab durumu (Android çip) | Klavye PC'de / Klavye tablette | — |
| Hedef uygulama (Android, Durum kutucuğu) | Çalıştığı uygulama | Tuşların hangi uygulama ekrandayken PC'ye gittiğini gösterir. "Açık": tuşlar PC'ye gider. "Kapalı ya da arkada": klavye tablete yazar; uygulamayı açınca tuşlar kendiliğinden PC'ye geçer. |
| Hedef uygulama seçimi (Android, PC'ler) | Hangi uygulamada çalışsın | Tuşların hangi uygulama ekrandayken PC'ye gideceğini burada seçersin (genelde TeamViewer). Seçmediğin uygulamalarda klavye tablete yazar. Uygulamayı listede bulamıyorsan tablete yüklü olduğundan emin ol. |
| RTT (Android) | Tepki süresi | Bir tuşun PC'ye gidip cevabının dönmesinin kaç milisaniye sürdüğünü gösterir. "—" görünüyorsa tablet PC'ye bağlı değil. Gecikme hissediyorsan ve sayı her zamankinden büyükse Wi-Fi'ını kontrol et ya da PC ile aynı ağa geç. |
| Bağlantı kutucuğu (Android, TLS + uç nokta) | Bağlantı · Şifreli · bu PC'ye kilitli | Tabletin şu an hangi adresten PC'ye bağlı olduğunu gösterir; bağlantı şifrelidir. "—" görünüyorsa PC'nin açık ve KeyRelay'in çalışır olduğuna bak. |
| Geçiş kısayolu (Android) | Geçiş kısayolu | Tuşları PC'ye göndermeyi uygulamaya dokunmadan açıp kapatır: Ctrl + Alt + gösterilen tuş. Çakışıyorsa Ayarlar'dan başka tuş seç. |
| Adresler (Android PC ayrıntısı) | Bağlantı yolları · Aynı ağ (ev ya da ofis) / İnternet üzerinden | Tabletin PC'ye ulaşmak için sırayla denediği yollar. Dışarıdayken bağlanamıyorsan ve "İnternet üzerinden" yoksa PC'de dışarıdan erişimi açıp yeniden eşleştir. |
| Ping / pong / sendTimeout (Android Ayarlar) | Bağlantı kontrolü · Cevap bekleme · Takılma süresi | Bağlantının ne sıklıkla kontrol edileceğini ve ne zaman kopmuş sayılacağını belirler. Normalde değiştirme; zayıf ağda sık kopuyorsa uzat, PC kapanınca klavye geç dönüyorsa kısalt. |
| Klavyeler (Android, evdev) | Klavyeler | Tablete şu an bağlı fiziksel klavyeleri gösterir. "Klavye bulunamadı" görüyorsan klavyenin açık ve Bluetooth'ta bağlı olduğunu kontrol et. |
| showKeys (Android) | PC'ye giden tuşları göster | Açık: Durum ekranında PC'ye giden son tuşu görürsün. Kapalı (varsayılan): hangi tuşa bastığın uygulamaya hiç gelmez. |
| Kablosuz hata ayıklama (Android) | Kablosuz hata ayıklama (Android'deki adıyla) | KeyRelay bunu yalnız başlarken bir anlığına kullanır. "Başlatılamadı" görürsen geliştirici seçeneklerinin açık, Samsung Otomatik Engelleyici'nin kapalı olduğuna bak. |
