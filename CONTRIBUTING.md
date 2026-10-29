# Contributing

## Build and test

- Android: `android/gradlew test`
- Windows: `dotnet test windows/KeyRelay.slnx`

## Commit format

```
type(scope): description
```

Types: `feat`, `fix`, `refactor`, `perf`, `docs`, `test`, `chore`, `ci`, `build`, `revert`.

## Pull requests

Fill in the pull request template (`.github/PULL_REQUEST_TEMPLATE.md`): summary, devices tested, and the commit format check.

## Sürümleme

Sürümün tek kaynağı kökteki `version.txt` dosyasıdır; Android (`versionName`, `versionCode`), Windows derlemeleri ve
kurulum paketi oradan okur. Bu dosyayı ve `CHANGELOG.md`'yi elle değiştirme; [release-please](https://github.com/googleapis/release-please)
`main`'e gelen commit başlıklarına bakarak ikisini de günceller.

| Commit | Sürüm etkisi (0.x) | Sürüm etkisi (1.0 ve sonrası) |
|--------|--------------------|-------------------------------|
| `feat` | minor (0.1.0 → 0.2.0) | minor |
| `fix`, `perf`, `refactor`, `docs`, `revert` | patch (0.1.0 → 0.1.1) | patch |
| `feat!` / `BREAKING CHANGE:` | minor (1.0.0'a kendiliğinden geçmez) | major |
| `chore`, `ci`, `test`, `build` | yok, değişiklik günlüğünde de görünmez | yok |

- `main`'e görünür türde commit geldiğinde release-please bir **sürüm PR'ı** açar ya da günceller.
- Sürüm PR'ını merge etmek yayın demektir: etiket (`vX.Y.Z`) ve GitHub sürümü oluşur, kurulum paketi, zip ve APK
  aynı iş akışında derlenip sürüme yüklenir.
- Belirli bir sürüme geçmek için (ör. 1.0.0) commit gövdesine `Release-As: 1.0.0` satırı ekle.

## APK imza anahtarı

Android bir güncellemeyi yalnızca önceki kurulumla aynı anahtarla imzalıysa kurar; aksi halde
`INSTALL_FAILED_UPDATE_INCOMPATIBLE` verir ve uygulamayı kaldırıp sihirbazı ve PC eşleştirmesini baştan yapmak gerekir.
Bu yüzden CI ve yayınlanan APK tek bir paylaşılan anahtarla imzalanır:

- `KEYRELAY_KEYSTORE` (keystore dosyasının yolu) ve `KEYRELAY_KEYSTORE_PASSWORD` ortam değişkenleri varsa `app`
  debug derlemesi bu anahtarla (takma ad `keyrelay`) imzalanır. Yoksa makinenin kendi debug anahtarı kullanılır; böyle
  bir derleme yayınlanmış APK'nın üzerine kurulmaz (tersi de geçerli).
- CI anahtarı `KEYRELAY_KEYSTORE_BASE64` ve `KEYRELAY_KEYSTORE_PASSWORD` GitHub secret'larından alır. Fork PR'larında
  secret olmadığından debug anahtarına düşer; `release` iş akışı ise secret yoksa durur.
- Keystore'u repoya koyma (`*.jks` yok sayılıyor) ve kopyasını parola yöneticisinde sakla: anahtar kaybolursa her
  kullanıcı uygulamayı bir kez daha kaldırıp kurmak zorunda kalır.

Anahtarı bir kez üretip secret'ları girmek için (PowerShell; parolayı `keytool` ve `gh` kendisi sorar):

```powershell
keytool -genkeypair -v -keystore "$HOME\keyrelay-signing.jks" -storetype PKCS12 -alias keyrelay -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=KeyRelay"
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$HOME\keyrelay-signing.jks")) | gh secret set KEYRELAY_KEYSTORE_BASE64
gh secret set KEYRELAY_KEYSTORE_PASSWORD
```

Yerelde de aynı anahtarla derlemek için `KEYRELAY_KEYSTORE` değişkenini bu dosyaya,
`KEYRELAY_KEYSTORE_PASSWORD` değişkenini parolaya ayarla.
