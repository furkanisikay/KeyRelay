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
