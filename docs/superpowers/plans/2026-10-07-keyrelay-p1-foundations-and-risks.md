# KeyRelay P1 — Temel iskelet ve risk doğrulama — Uygulama planı

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Repo iskeletini, protokol belgesini ve iki platformun proje iskeletini kurmak; spec'teki beş riskli varsayımı (Risk 1–5) gerçek cihazlarda, ileride kalacak gerçek alt seviye kodla doğrulamak.

**Architecture:** Android'de `:core` (saf Kotlin) ve `:app` (APK; içinde `shell` uid'inde `app_process` ile çalışan yardımcı giriş noktası ve evdev JNI'si) modülleri; Windows'ta LocalSystem servisi ve aynı ikilinin `--agent` modu. Bu planın kodu sonraki planlarda (P2 Android uygulaması, P3 Windows uygulaması) üzerine inşa edilir; risk dosya-tetikleyicili prob kodu (T7) P3'te protokol ve pipe ile değiştirilir.

**Tech Stack:** Kotlin 2.4.20, AGP 9.4.1, Gradle 9.8.0, Compose BOM 2026.09.00, NDK (C), JUnit 6.1.3, libadb-android 3.1.1 (JitPack), Conscrypt-android 2.7.0, BouncyCastle bcpkix-jdk18on 1.86; .NET 10 (SDK 10.0.302), Microsoft.Extensions.Hosting.WindowsServices 10.0.12, MSTest.Sdk 4.4.1.

**Spec:** `docs/superpowers/specs/2026-10-07-keyrelay-design.md` (tasarım kapısı 5 turda geçti; log `.superpowers/gates/keyrelay-design-gate-log.md`).

## Global Constraints

- Android: minSdk 30, targetSdk 36, paket adı `dev.keyrelay`, Kotlin; yardımcı giriş sınıfı `dev.keyrelay.helper.Main`, `--nice-name=keyrelay-helper`.
- Windows: .NET 10, `TargetFramework` servis için `net10.0-windows`; sabitler `UPPER_SNAKE`, private alanlar `_camelCase`; kütüphane kodunda `ConfigureAwait(false)` ve `CancellationToken`; `.Result`/`.Wait()` yok; her I/O'ya timeout.
- Kod, isimler, XML doc ve kod içi yorumlar İngilizce; kod içi yorum ve log string'lerinde Türkçe özel karakter yok. Belgeler (`docs/`, `README.tr.md`) Türkçe.
- Lisans Apache-2.0. WiX kullanılmaz.
- Commit: `<type>(<scope>): <türkçe küçük harfli açıklama>` (subject ≤ 72 karakter), gövde maddeleri jargonsuz, sonda `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Header'lar aşağıdaki listedir; kullanıcı toplu onay verdi/vermedi durumu controller'dadır — implementer commit'ten önce controller'a header'ı bildirir.
- Yorum kuralı: yalnız koddan görünmeyen gerekçe; yorum açıkladığı koddan uzun olmaz.
- Cihaz adımları (tablet: SM-X910 Android 14, adb ile eşli; PC: `homepc`, yönetici hesabıyla) controller tarafından kullanıcıyla koordine edilir; implementer bu adımları komut listesi olarak hazırlar, sonucu uydurmaz.
- Prototip (`%USERPROFILE%\kbdfwd`, tablette `/data/local/tmp/kbdfwd`, homepc'de `kbdrecv` görevi) bu planda kaldırılmaz; T3–T5 cihaz testlerinden önce tablette `kill $(pidof kbdfwd)` ile durdurulur (iki süreç aynı düğümü grab edemez) ve test sonunda kullanıcının isteğine göre yeniden başlatılır.

## Review Focus

1. Yardımcı grab tutarken ölür (kill -9) → kernel grab'ı kaldırır, klavye Android'de hemen çalışır. → T3 Step 7.
2. Bluetooth klavye uykuya geçip düğüm kaybolur → okuma `-ENODEV` döner, yardımcı çökmez, döngüden çıkar. → T3 Step 7.
3. TeamViewer zaten öndeyken yardımcı başlar (hiç ön plan olayı gelmez) → başlangıç durumu `top-app` cgroup'tan doğru tohumlanır. → T4 Step 1 testi + Step 6.
4. Açılışta Wi-Fi yok (uçak modu) → otomatik başlatma meşgul döngüye girmeden bekler, Wi-Fi gelince devam eder. → T5 Step 9.
5. PC açılıp kimse oturum açmamışken (giriş ekranı) ajan başlar ve Winlogon masaüstünü görür. → T7 Step 8.

## Commit header listesi

| Görev | Header |
|---|---|
| T1 | `chore(repo): proje iskeleti, protokol belgesi ve test vektörleri eklendi` |
| T2 | `build(android): android proje iskeleti ve otomatik derleme eklendi` |
| T3 | `feat(android): klavyeyi bulup kilitleyen yardımcı süreç eklendi` |
| T4 | `feat(android): ön plandaki uygulama yardımcı süreçte izlenir oldu` |
| T5 | `feat(android): yardımcı süreç tablet açılınca kendiliğinden başlar` |
| T6 | `build(windows): windows çözüm iskeleti ve otomatik derleme eklendi` |
| T7 | `feat(windows): servis oturuma ajan başlatır, kilit ekranı denemesi` |
| T8 | `docs(spec): risk doğrulama sonuçları tasarıma işlendi` |

---

### Task 1: Repo iskeleti, protokol belgesi, test vektörleri

**Files:**
- Create: `README.md`, `README.tr.md`, `LICENSE`, `SECURITY.md`, `CONTRIBUTING.md`, `CHANGELOG.md`, `.editorconfig`, `.gitattributes`, `.gitignore`
- Create: `docs/protocol.md`
- Create: `protocol/test-vectors/frames.json`
- Create: `.github/ISSUE_TEMPLATE/bug_report.yml`, `.github/PULL_REQUEST_TEMPLATE.md`, `.github/dependabot.yml`

**Interfaces:**
- Produces: `protocol/test-vectors/frames.json` — P2 (`:core`) ve P3 (`KeyRelay.Protocol`) çözücü testlerinin girdisi. Şema: `{ "version": 1, "valid": [{ "name", "channels", "type", "fields", "hex" }], "invalid": [{ "name", "hex", "error" }] }`; `channels` ⊆ `["tls", "pipe"]` (her çözücü yalnız kendi kanalının vektörlerini kullanır; `:core` yalnız `tls`); `error` ∈ `empty | too_large | unknown_type | bad_payload | truncated` (geçersiz vektörler iki kanal için de geçerlidir).

- [ ] **Step 1: `LICENSE`'ı indir**

Run: `curl -sSfL https://www.apache.org/licenses/LICENSE-2.0.txt -o D:/Repos/KeyRelay/LICENSE`
Expected: dosya `Apache License` ve `Version 2.0, January 2004` satırlarını içerir.

- [ ] **Step 2: Kök dosyaları yaz**

`.gitattributes`:
```
* text=auto eol=lf
*.bat text eol=crlf
*.cmd text eol=crlf
*.ps1 text eol=crlf
*.iss text eol=crlf
gradlew text eol=lf
*.jar binary
*.png binary
```

`.gitignore`:
```
# Android / Gradle
android/.gradle/
android/**/build/
android/local.properties
android/.idea/
android/**/.cxx/
*.apk
*.aab
# .NET
windows/**/bin/
windows/**/obj/
windows/**/TestResults/
*.user
.vs/
# Release output
artifacts/
# Local
.superpowers/
```

`.editorconfig`:
```
root = true

[*]
charset = utf-8
end_of_line = lf
insert_final_newline = true
trim_trailing_whitespace = true
indent_style = space
indent_size = 4

[*.{yml,yaml,json,md}]
indent_size = 2

[*.{kt,kts}]
max_line_length = 120
ktlint_code_style = android_studio

[*.cs]
max_line_length = 120
dotnet_naming_rule.constants.symbols = constants
dotnet_naming_rule.constants.style = upper_snake
dotnet_naming_rule.constants.severity = warning
dotnet_naming_symbols.constants.applicable_kinds = field
dotnet_naming_symbols.constants.required_modifiers = const
dotnet_naming_style.upper_snake.capitalization = all_upper
dotnet_naming_style.upper_snake.word_separator = _
dotnet_naming_rule.private_fields.symbols = private_fields
dotnet_naming_rule.private_fields.style = underscore_camel
dotnet_naming_rule.private_fields.severity = warning
dotnet_naming_symbols.private_fields.applicable_kinds = field
dotnet_naming_symbols.private_fields.applicable_accessibilities = private
dotnet_naming_style.underscore_camel.capitalization = camel_case
dotnet_naming_style.underscore_camel.required_prefix = _
```

`README.md` (İngilizce, kısa): başlık `# KeyRelay`; tek cümle tanım ("Use every Windows shortcut — Alt+Tab, Win, Ctrl+Alt+Del — from a physical keyboard on an Android tablet while remote-controlling a Windows PC."); "Why" (Android swallows Alt+Tab/Meta before apps see them); "How it works" (3 madde: tablet helper grabs the keyboard at the kernel level; keys travel over pinned TLS; a Windows service injects them, including on the lock screen); "Status: early development"; "License: Apache-2.0"; `README.tr.md`'ye bağlantı.

`README.tr.md`: aynı içeriğin Türkçesi.

`SECURITY.md`: güvenlik açığını GitHub "Private vulnerability reporting" ile bildirme talimatı; kapsam: Windows servisi ağdan tuş enjeksiyonu yapar, kimlik doğrulama atlatma kritik sayılır.

`CONTRIBUTING.md`: derleme (`android/gradlew test`, `dotnet test windows/KeyRelay.slnx`), commit biçimi (`type(scope): açıklama`), PR şablonu.

`CHANGELOG.md`:
```
# Changelog

## [Unreleased]
- Project skeleton, protocol document and shared test vectors.
```

- [ ] **Step 3: `docs/protocol.md` yaz**

Belgeye şu cümleyi açıkça yaz: "`u16 uzunluk` alanının değeri (tip baytı + yük) 1 ile 512 arasında olmalıdır; 0 veya 512'den büyük değer bağlantıyı kapatır" (vektörler `empty_length` ve `length_over_512` bununla uyumlu).
Spec'in "Protokol" bölümünü (çerçeve biçimi, mesaj tablosu `0x01`–`0x21` ve `0x12 SAS`, `0x13 LOCK`, QR biçimi, kimlik doğrulama, canlılık ve `sendTimeout`, Funnel port/SNI kısıtı) Türkçe olarak birebir aktar. Ek olarak şu bölümü ekle (tasarım kapısı takip #29):

```markdown
## Servis ↔ ajan pipe mesajları

`\\.\pipe\KeyRelay.Agent` (ACL yalnız SYSTEM) üzerinde aynı çerçeve biçimi (`u16 uzunluk | u8 tip | yük`) kullanılır.

| Yön | Tip | Yük | Anlam |
|---|---|---|---|
| S→A | `0x10 KEY` | `u16 evdev keycode, u8 değer` | Tuşu bas/bırak/tekrarla |
| S→A | `0x13 LOCK` | — | `LockWorkStation` |
| S→A | `0x30 RELEASE_ALL` | — | Ajanın tuttuğu tüm basılı tuşları bırak (istemci koptu) |
| A→S | `0x31 DESKTOP` | `u8 yazılabilir (0/1)` | Giriş masaüstünün yazılabilirliği değişti |

- Servis her pipe yazımında 1 sn timeout uygular; aşılırsa ajan ölü sayılır ve yeniden başlatılır.
- Yeni başlayan ajan, açılışta sol/sağ Ctrl, Alt, Shift ve Win için key-up gönderir (önceki ajandan kalmış basılı modifier'ları temizler; tasarım kapısı takip #27).
- `SAS` pipe'a gitmez: servis `SendSAS(FALSE)`'ı kendisi çağırır.
```

- [ ] **Step 4: Test vektörlerini yaz**

`protocol/test-vectors/frames.json` (hex küçük harf, boşluksuz; cihaz kimliği `000102…0f`, token `202122…3f`, eşleştirme kodu `a0a1…af`):
```json
{
  "version": 1,
  "valid": [
    { "name": "key_down_a", "channels": ["tls", "pipe"], "type": "KEY", "fields": { "code": 30, "value": 1 }, "hex": "000410001e01" },
    { "name": "key_up_leftmeta", "channels": ["tls", "pipe"], "type": "KEY", "fields": { "code": 125, "value": 0 }, "hex": "000410007d00" },
    { "name": "key_repeat_tab", "channels": ["tls", "pipe"], "type": "KEY", "fields": { "code": 15, "value": 2 }, "hex": "000410000f02" },
    { "name": "hello", "channels": ["tls"], "type": "HELLO", "fields": { "version": 1, "deviceId": "000102030405060708090a0b0c0d0e0f", "token": "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f" }, "hex": "00320301000102030405060708090a0b0c0d0e0f202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f" },
    { "name": "pair", "channels": ["tls"], "type": "PAIR", "fields": { "version": 1, "code": "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf", "deviceName": "Tab" }, "hex": "00150101a0a1a2a3a4a5a6a7a8a9aaabacadaeaf546162" },
    { "name": "paired", "channels": ["tls"], "type": "PAIRED", "fields": { "deviceId": "000102030405060708090a0b0c0d0e0f", "token": "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f" }, "hex": "003102000102030405060708090a0b0c0d0e0f202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f" },
    { "name": "welcome", "channels": ["tls"], "type": "WELCOME", "fields": { "pcName": "PC" }, "hex": "0003045043" },
    { "name": "reject_unknown_device", "channels": ["tls"], "type": "REJECT", "fields": { "reason": 2 }, "hex": "00020502" },
    { "name": "state_ready", "channels": ["tls"], "type": "STATE", "fields": { "ready": true }, "hex": "00021101" },
    { "name": "state_not_ready", "channels": ["tls"], "type": "STATE", "fields": { "ready": false }, "hex": "00021100" },
    { "name": "sas", "channels": ["tls"], "type": "SAS", "fields": {}, "hex": "000112" },
    { "name": "lock", "channels": ["tls", "pipe"], "type": "LOCK", "fields": {}, "hex": "000113" },
    { "name": "ping", "channels": ["tls"], "type": "PING", "fields": { "seq": 16909060 }, "hex": "00052001020304" },
    { "name": "pong", "channels": ["tls"], "type": "PONG", "fields": { "seq": 16909060 }, "hex": "00052101020304" },
    { "name": "release_all", "channels": ["pipe"], "type": "RELEASE_ALL", "fields": {}, "hex": "000130" },
    { "name": "desktop_writable", "channels": ["pipe"], "type": "DESKTOP", "fields": { "writable": true }, "hex": "00023101" }
  ],
  "invalid": [
    { "name": "empty_length", "hex": "0000", "error": "empty" },
    { "name": "length_over_512", "hex": "0201", "error": "too_large" },
    { "name": "unknown_type", "hex": "00017f", "error": "unknown_type" },
    { "name": "key_payload_short", "hex": "000310001e", "error": "bad_payload" },
    { "name": "key_value_out_of_range", "hex": "000410001e03", "error": "bad_payload" },
    { "name": "hello_truncated_token", "hex": "00320301000102", "error": "truncated" }
  ]
}
```

- [ ] **Step 5: Vektörleri bağımsız doğrula**

Run (Python, repo dışı tek seferlik kontrol):
```
python -I -c "import json;d=json.load(open(r'D:\Repos\KeyRelay\protocol\test-vectors\frames.json'));bad=[v['name'] for v in d['valid'] if int(v['hex'][:4],16)!=len(bytes.fromhex(v['hex']))-2];print('length mismatches:',bad)"
```
Expected: `length mismatches: []`

- [ ] **Step 6: GitHub şablonları**

`.github/dependabot.yml`:
```yaml
version: 2
updates:
  - package-ecosystem: gradle
    directory: /android
    schedule: { interval: weekly }
  - package-ecosystem: nuget
    directory: /windows
    schedule: { interval: weekly }
  - package-ecosystem: github-actions
    directory: /
    schedule: { interval: weekly }
```
`bug_report.yml`: alanlar — tablet modeli/Android sürümü, klavye modeli, Windows sürümü, uzak masaüstü uygulaması, beklenen/olan davranış, KeyRelay logu (zorunlu alanlar: Android sürümü, Windows sürümü, beklenen, olan). `PULL_REQUEST_TEMPLATE.md`: Özet, Test edilen cihazlar, CHANGELOG güncellendi mi kutusu.

- [ ] **Step 7: Commit**

```
git -C D:/Repos/KeyRelay add -A
git -C D:/Repos/KeyRelay commit -m "chore(repo): proje iskeleti, protokol belgesi ve test vektörleri eklendi" -m "- lisans, katkı ve güvenlik belgeleri eklendi
- tablet ile PC arasındaki mesaj biçimi belgelendi
- iki platformun ortak kullanacağı örnek mesajlar eklendi" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
(Spec ve gate klasörü `.superpowers/` ignore'dadır; spec `docs/superpowers/specs/` altında bu commit'e girer.)

---

### Task 2: Android araç zinciri ve proje iskeleti

**Files:**
- Create: `android/settings.gradle.kts`, `android/build.gradle.kts`, `android/gradle.properties`, `android/gradle/libs.versions.toml`, `android/gradlew`, `android/gradlew.bat`, `android/gradle/wrapper/*`
- Create: `android/core/build.gradle.kts`, `android/core/src/test/kotlin/dev/keyrelay/core/SmokeTest.kt`
- Create: `android/app/build.gradle.kts`, `android/app/src/main/AndroidManifest.xml`, `android/app/src/main/kotlin/dev/keyrelay/MainActivity.kt`, `android/app/src/main/res/values/strings.xml`
- Create: `.github/workflows/android.yml`

**Interfaces:**
- Produces: `:core` (Kotlin/JVM, JUnit 6) ve `:app` (Android, Compose, CMake/NDK hazır) modülleri; `./gradlew test assembleDebug` çalışır.

- [ ] **Step 1: JDK ve Android SDK kur**

Run:
```
winget install --id Microsoft.OpenJDK.21 -e --silent --accept-source-agreements --accept-package-agreements
```
Android SDK komut satırı araçları:
```
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
New-Item -ItemType Directory -Force "$sdk\cmdline-tools" | Out-Null
Invoke-WebRequest https://dl.google.com/android/repository/commandlinetools-win-16111833_latest.zip -OutFile "$env:TEMP\cmdtools.zip"
Expand-Archive "$env:TEMP\cmdtools.zip" "$sdk\cmdline-tools" -Force
Rename-Item "$sdk\cmdline-tools\cmdline-tools" latest
[Environment]::SetEnvironmentVariable("ANDROID_HOME", $sdk, "User")
```
Ardından (JAVA_HOME = kurulan JDK 21 klasörü):
```
& "$sdk\cmdline-tools\latest\bin\sdkmanager.bat" --list | Select-String "ndk;|build-tools;36|platforms;android-36"
```
Listede görünen en yüksek kararlı `ndk;X`, `build-tools;36.x.y` ve `platforms;android-36` için:
```
& "$sdk\cmdline-tools\latest\bin\sdkmanager.bat" --install "platform-tools" "platforms;android-36" "build-tools;<36.x.y>" "ndk;<X>" "cmake;<listede en yüksek 3.x>"
```
Lisansları `--licenses` ile kabul et. Kurulan NDK ve CMake sürümlerini raporda yaz; Step 3'te `ndkVersion` olarak kullanılır.

- [ ] **Step 2: Gradle wrapper üret**

```
Invoke-WebRequest https://services.gradle.org/distributions/gradle-9.8.0-bin.zip -OutFile "$env:TEMP\gradle.zip"
Expand-Archive "$env:TEMP\gradle.zip" "$env:TEMP\gradle-dist" -Force
New-Item -ItemType Directory -Force D:\Repos\KeyRelay\android | Out-Null
& "$env:TEMP\gradle-dist\gradle-9.8.0\bin\gradle.bat" -p D:\Repos\KeyRelay\android wrapper --gradle-version 9.8.0 --distribution-type bin
```
Expected: `android/gradlew`, `android/gradlew.bat`, `android/gradle/wrapper/gradle-wrapper.jar|properties` oluşur.

- [ ] **Step 3: Gradle dosyaları**

`android/gradle/libs.versions.toml`:
```toml
[versions]
agp = "9.4.1"
kotlin = "2.4.20"
composeBom = "2026.09.00"
junit = "6.1.3"
libadb = "3.1.1"
conscrypt = "2.7.0"
bouncycastle = "1.86"
activityCompose = "1.11.0"

[libraries]
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
compose-material3 = { module = "androidx.compose.material3:material3" }
compose-ui = { module = "androidx.compose.ui:ui" }
activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit" }
junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher" }
libadb = { module = "com.github.MuntashirAkon:libadb-android", version.ref = "libadb" }
conscrypt = { module = "org.conscrypt:conscrypt-android", version.ref = "conscrypt" }
bcpkix = { module = "org.bouncycastle:bcpkix-jdk18on", version.ref = "bouncycastle" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```
`activityCompose` sürümünü Google Maven'dan (`androidx/activity/activity-compose/maven-metadata.xml`) en yüksek kararlı sürümle doğrula ve gerekirse güncelle; raporda yaz.

`android/settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral(); maven("https://jitpack.io") }
}
rootProject.name = "KeyRelay"
include(":core", ":app")
```
`android/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
```
`android/gradle.properties`:
```
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
org.gradle.configuration-cache=true
android.useAndroidX=true
android.nonTransitiveRClass=true
```
`android/core/build.gradle.kts`:
```kotlin
plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(21) }
dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
tasks.test { useJUnitPlatform() }
```
`android/app/build.gradle.kts` (AGP 9 dahili Kotlin desteği kullanılır; `org.jetbrains.kotlin.android` eklenmez — AGP 9 ile derleme bu eklentiyi isterse AGP 9 sürüm notlarındaki yönergeye göre ekle ve raporda belirt):
```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "dev.keyrelay"
    compileSdk = 36
    ndkVersion = "<Step 1'de kurulan NDK>"
    defaultConfig {
        applicationId = "dev.keyrelay"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    buildFeatures { compose = true }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    // The helper runs under app_process and loads the .so by absolute path, so it must exist on disk.
    packaging { jniLibs { useLegacyPackaging = true } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.activity.compose)
}
```
`externalNativeBuild` T3'te dolacak `CMakeLists.txt`'i ister; bu görevde şu içerikle oluştur:

`android/app/src/main/cpp/CMakeLists.txt`:
```cmake
cmake_minimum_required(VERSION 3.22.1)
project(keyrelay C)
add_library(keyrelay SHARED evdev.c)
```
`android/app/src/main/cpp/evdev.c` (T3 dolduracak):
```c
#include <jni.h>
```

- [ ] **Step 4: Asgari uygulama ve smoke test**

`AndroidManifest.xml`:
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:label="@string/app_name" android:theme="@android:style/Theme.Material.NoActionBar">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```
`strings.xml`: `<string name="app_name">KeyRelay</string>`

`MainActivity.kt`:
```kotlin
package dev.keyrelay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Text("KeyRelay") } }
    }
}
```
`core/src/test/kotlin/dev/keyrelay/core/SmokeTest.kt`:
```kotlin
package dev.keyrelay.core

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmokeTest {
    @Test
    fun junitPlatform_runs_onJvm() = assertTrue(true)
}
```

- [ ] **Step 5: Derle ve test et**

Run: `D:\Repos\KeyRelay\android\gradlew.bat -p D:\Repos\KeyRelay\android test assembleDebug`
Expected: `BUILD SUCCESSFUL`; `app/build/outputs/apk/debug/app-debug.apk` var; `core` testi 1 geçti.

- [ ] **Step 6: CI iş akışı**

`.github/workflows/android.yml`:
```yaml
name: android
on:
  push: { paths: ["android/**", "protocol/**", ".github/workflows/android.yml"] }
  pull_request: { paths: ["android/**", "protocol/**", ".github/workflows/android.yml"] }
jobs:
  build:
    runs-on: ubuntu-latest
    defaults: { run: { working-directory: android } }
    steps:
      - uses: actions/checkout@v5
      - uses: actions/setup-java@v5
        with: { distribution: temurin, java-version: "21" }
      - uses: gradle/actions/setup-gradle@v5
      - run: ./gradlew test assembleDebug
      - uses: actions/upload-artifact@v5
        with: { name: app-debug, path: android/app/build/outputs/apk/debug/app-debug.apk }
```
Action sürümlerini (`actions/checkout`, `actions/setup-java`, `gradle/actions`, `actions/upload-artifact`) `gh api repos/<owner>/<repo>/releases/latest` ile doğrula; en yüksek ana sürümü kullan ve raporda yaz.

- [ ] **Step 7: Commit**

```
git -C D:/Repos/KeyRelay add -A
git -C D:/Repos/KeyRelay commit -m "build(android): android proje iskeleti ve otomatik derleme eklendi" -m "- tablet uygulaması ve ortak çekirdek modülü derlenir hale geldi
- her değişiklikte testler ve derleme otomatik koşar" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: evdev JNI, klavye gruplama, yardımcı giriş noktası (Risk 3, 5)

**Files:**
- Create: `android/core/src/main/kotlin/dev/keyrelay/core/InputNode.kt`, `android/core/src/main/kotlin/dev/keyrelay/core/KeyboardGrouping.kt`
- Test: `android/core/src/test/kotlin/dev/keyrelay/core/KeyboardGroupingTest.kt`
- Modify: `android/app/src/main/cpp/evdev.c`
- Create: `android/app/src/main/kotlin/dev/keyrelay/helper/Evdev.kt`, `android/app/src/main/kotlin/dev/keyrelay/helper/DeviceScanner.kt`, `android/app/src/main/kotlin/dev/keyrelay/helper/Main.kt`
- Create: `docs/spikes/2026-10-risk-results.md` (bu görev Risk 3 ve 5 bölümlerini yazar)

**Interfaces:**
- Produces (`:core`):
  - `class InputNode(val path: String, val name: String, val phys: String, val uniq: String, val evBits: Int, val keyBits: ByteArray)` + `fun hasEv(type: Int): Boolean`, `fun hasKey(code: Int): Boolean`
  - `data class Keyboard(val name: String, val nodes: List<InputNode>)`
  - `object KeyboardGrouping { fun group(nodes: List<InputNode>): List<Keyboard> }`
- Produces (`:app`, yardımcı tarafı): `object Evdev` (`open/close/grab/keyBits/evBits/name/phys/uniq/read`), `object DeviceScanner { fun scan(): List<InputNode> }`, giriş `dev.keyrelay.helper.Main` (`--lib=<so yolu>` zorunlu, `--grab=<saniye>` isteğe bağlı).

- [ ] **Step 1: Gruplama testlerini yaz**

`KeyboardGroupingTest.kt`:
```kotlin
package dev.keyrelay.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KeyboardGroupingTest {
    private val evKey = 1 shl 1
    private val evRel = 1 shl 2

    private fun keys(vararg codes: Int) = ByteArray(96).also { b -> codes.forEach { b[it / 8] = (b[it / 8].toInt() or (1 shl (it % 8))).toByte() } }
    private fun node(path: String, uniq: String = "aa:bb", phys: String = "host/input0", ev: Int = evKey, vararg codes: Int) =
        InputNode(path, path, phys, uniq, ev, keys(*codes))

    @Test
    fun group_typingNodeWithConsumerControl_groupsSameUniq() {
        val kb = node("/dev/input/event13", codes = intArrayOf(30, 57))
        val consumer = node("/dev/input/event16", codes = intArrayOf(164))
        val result = KeyboardGrouping.group(listOf(kb, consumer))
        assertEquals(listOf(listOf(kb.path, consumer.path)), result.map { k -> k.nodes.map { it.path } })
    }

    @Test
    fun group_mouseWithSameUniq_isExcluded() {
        val kb = node("/dev/input/event13", codes = intArrayOf(30, 57))
        val mouse = node("/dev/input/event18", ev = evKey or evRel, codes = intArrayOf(272))
        assertEquals(listOf(kb.path), KeyboardGrouping.group(listOf(kb, mouse)).single().nodes.map { it.path })
    }

    @Test
    fun group_differentUniq_notGrouped() {
        val kb = node("/dev/input/event13", uniq = "aa", codes = intArrayOf(30, 57))
        val other = node("/dev/input/event16", uniq = "bb", codes = intArrayOf(164))
        assertEquals(listOf(kb.path), KeyboardGrouping.group(listOf(kb, other)).single().nodes.map { it.path })
    }

    @Test
    fun group_emptyUniq_fallsBackToPhysRoot() {
        val kb = node("/dev/input/event3", uniq = "", phys = "usb-1/input0", codes = intArrayOf(30, 57))
        val consumer = node("/dev/input/event4", uniq = "", phys = "usb-1/input1", codes = intArrayOf(164))
        val stranger = node("/dev/input/event5", uniq = "", phys = "usb-2/input1", codes = intArrayOf(164))
        assertEquals(listOf(kb.path, consumer.path), KeyboardGrouping.group(listOf(kb, consumer, stranger)).single().nodes.map { it.path })
    }

    @Test
    fun group_builtInNodesWithoutLetters_noKeyboard() {
        val gpio = node("/dev/input/event6", uniq = "", phys = "", codes = intArrayOf(114, 115, 116))
        val touch = node("/dev/input/event10", uniq = "", phys = "", ev = evKey or (1 shl 3), codes = intArrayOf(330))
        assertTrue(KeyboardGrouping.group(listOf(gpio, touch)).isEmpty())
    }

    @Test
    fun group_twoTypingNodesSameDevice_yieldsOneKeyboard() {
        val a = node("/dev/input/event13", codes = intArrayOf(30, 57))
        val b = node("/dev/input/event14", codes = intArrayOf(30, 57))
        assertEquals(1, KeyboardGrouping.group(listOf(a, b)).size)
    }

    @Test
    fun group_emptyUniqAndEmptyPhys_doesNotGroupStrangers() {
        val kb = node("/dev/input/event3", uniq = "", phys = "", codes = intArrayOf(30, 57))
        val other = node("/dev/input/event4", uniq = "", phys = "", codes = intArrayOf(164))
        assertEquals(listOf(kb.path), KeyboardGrouping.group(listOf(kb, other)).single().nodes.map { it.path })
    }
}
```

- [ ] **Step 2: Testin başarısız olduğunu gör**

Run: `android\gradlew.bat -p android :core:test`
Expected: derleme hatası (`InputNode` / `KeyboardGrouping` yok).

- [ ] **Step 3: `InputNode` ve `KeyboardGrouping`**

`InputNode.kt`:
```kotlin
package dev.keyrelay.core

/** One /dev/input/event* node as reported by its evdev ioctls. */
class InputNode(
    val path: String,
    val name: String,
    val phys: String,
    val uniq: String,
    val evBits: Int,
    val keyBits: ByteArray,
) {
    fun hasEv(type: Int): Boolean = evBits and (1 shl type) != 0

    fun hasKey(code: Int): Boolean =
        code / 8 < keyBits.size && keyBits[code / 8].toInt() and (1 shl (code % 8)) != 0
}

/** A physical keyboard: the node that types letters plus its sibling key-only nodes (media, system keys). */
data class Keyboard(val name: String, val nodes: List<InputNode>)
```
`KeyboardGrouping.kt`:
```kotlin
package dev.keyrelay.core

object KeyboardGrouping {
    private const val EV_KEY = 1
    private const val EV_REL = 2
    private const val KEY_A = 30
    private const val KEY_SPACE = 57

    fun group(nodes: List<InputNode>): List<Keyboard> =
        nodes.filter { it.isTyping() }
            .map { primary -> Keyboard(primary.name, nodes.filter { it === primary || (it.isKeyOnly() && it.sameDevice(primary)) }) }
            .distinctBy { kb -> kb.nodes.map { it.path }.toSet() }

    private fun InputNode.isKeyOnly() = hasEv(EV_KEY) && !hasEv(EV_REL)

    private fun InputNode.isTyping() = isKeyOnly() && hasKey(KEY_A) && hasKey(KEY_SPACE)

    // Bluetooth nodes of one device share uniq (its address); phys is the host adapter and is shared by every device.
    private fun InputNode.sameDevice(other: InputNode): Boolean =
        if (uniq.isNotEmpty() || other.uniq.isNotEmpty()) uniq == other.uniq
        else physRoot().isNotEmpty() && physRoot() == other.physRoot()

    private fun InputNode.physRoot() = phys.substringBeforeLast("/input", phys)
}
```

- [ ] **Step 4: Testlerin geçtiğini gör**

Run: `android\gradlew.bat -p android :core:test`
Expected: 8 test geçti (smoke dahil).

- [ ] **Step 5: JNI ve yardımcı kodu**

`evdev.c`:
```c
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <linux/input.h>

#define FN(name) Java_dev_keyrelay_helper_Evdev_##name

JNIEXPORT jint JNICALL FN(open)(JNIEnv *env, jclass cls, jstring path) {
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    int fd = open(p, O_RDONLY | O_CLOEXEC);
    int err = errno;
    (*env)->ReleaseStringUTFChars(env, path, p);
    return fd >= 0 ? fd : -err;
}

JNIEXPORT void JNICALL FN(close)(JNIEnv *env, jclass cls, jint fd) { close(fd); }

JNIEXPORT jint JNICALL FN(grab)(JNIEnv *env, jclass cls, jint fd, jboolean on) {
    return ioctl(fd, EVIOCGRAB, on ? 1 : 0) == 0 ? 0 : -errno;
}

JNIEXPORT jint JNICALL FN(evBits)(JNIEnv *env, jclass cls, jint fd) {
    unsigned int bits = 0;
    return ioctl(fd, EVIOCGBIT(0, sizeof(bits)), &bits) < 0 ? 0 : (jint) bits;
}

JNIEXPORT jbyteArray JNICALL FN(keyBits)(JNIEnv *env, jclass cls, jint fd) {
    unsigned char bits[KEY_MAX / 8 + 1] = {0};
    if (ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(bits)), bits) < 0) return NULL;
    jbyteArray out = (*env)->NewByteArray(env, sizeof(bits));
    (*env)->SetByteArrayRegion(env, out, 0, sizeof(bits), (const jbyte *) bits);
    return out;
}

static jstring string_ioctl(JNIEnv *env, int fd, unsigned long request) {
    char buf[256] = {0};
    if (ioctl(fd, request, buf) < 0) return NULL;
    buf[sizeof(buf) - 1] = 0;
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jstring JNICALL FN(name)(JNIEnv *env, jclass cls, jint fd) { return string_ioctl(env, fd, EVIOCGNAME(255)); }
JNIEXPORT jstring JNICALL FN(phys)(JNIEnv *env, jclass cls, jint fd) { return string_ioctl(env, fd, EVIOCGPHYS(255)); }
JNIEXPORT jstring JNICALL FN(uniq)(JNIEnv *env, jclass cls, jint fd) { return string_ioctl(env, fd, EVIOCGUNIQ(255)); }

// Reads whole input_event records into a direct buffer: no JNI copy, no allocation per call.
JNIEXPORT jint JNICALL FN(read)(JNIEnv *env, jclass cls, jint fd, jobject buffer) {
    void *p = (*env)->GetDirectBufferAddress(env, buffer);
    jlong cap = (*env)->GetDirectBufferCapacity(env, buffer);
    if (p == NULL || cap < (jlong) sizeof(struct input_event)) return -EINVAL;
    ssize_t n = read(fd, p, (size_t) cap);
    return n >= 0 ? (jint) n : -errno;
}
```
`Evdev.kt`:
```kotlin
package dev.keyrelay.helper

import java.nio.ByteBuffer

/** Thin JNI bridge to the evdev ioctls. Negative return values are -errno. */
object Evdev {
    const val INPUT_EVENT_SIZE = 24

    fun load(path: String) = System.load(path)

    @JvmStatic external fun open(path: String): Int
    @JvmStatic external fun close(fd: Int)
    @JvmStatic external fun grab(fd: Int, on: Boolean): Int
    @JvmStatic external fun evBits(fd: Int): Int
    @JvmStatic external fun keyBits(fd: Int): ByteArray?
    @JvmStatic external fun name(fd: Int): String?
    @JvmStatic external fun phys(fd: Int): String?
    @JvmStatic external fun uniq(fd: Int): String?
    @JvmStatic external fun read(fd: Int, buffer: ByteBuffer): Int
}
```
`DeviceScanner.kt`:
```kotlin
package dev.keyrelay.helper

import dev.keyrelay.core.InputNode
import java.io.File

object DeviceScanner {
    fun scan(): List<InputNode> =
        File("/dev/input").listFiles { f -> f.name.startsWith("event") }.orEmpty()
            .sortedBy { it.name }
            .mapNotNull { f ->
                val fd = Evdev.open(f.path)
                if (fd < 0) return@mapNotNull null
                try {
                    InputNode(
                        f.path,
                        Evdev.name(fd).orEmpty(),
                        Evdev.phys(fd).orEmpty(),
                        Evdev.uniq(fd).orEmpty(),
                        Evdev.evBits(fd),
                        Evdev.keyBits(fd) ?: ByteArray(0),
                    )
                } finally {
                    Evdev.close(fd)
                }
            }
}
```
`Main.kt`:
```kotlin
package dev.keyrelay.helper

import dev.keyrelay.core.Keyboard
import dev.keyrelay.core.KeyboardGrouping
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/** Entry point when started as `app_process ... dev.keyrelay.helper.Main` under the shell uid. */
object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        val opts = args.associate { it.substringBefore('=') to it.substringAfter('=', "") }
        Evdev.load(opts["--lib"] ?: error("--lib=<path to libkeyrelay.so> is required"))
        val keyboards = KeyboardGrouping.group(DeviceScanner.scan())
        keyboards.forEach { kb -> println("keyboard: ${kb.name} -> ${kb.nodes.joinToString { "${it.path}(${it.name})" }}") }
        opts["--grab"]?.let { seconds -> keyboards.firstOrNull()?.let { grabAndDump(it, seconds.toLongOrNull() ?: 15) } }
        exitProcess(0)
    }

    private fun grabAndDump(kb: Keyboard, seconds: Long) {
        val fds = kb.nodes.map { node -> node.path to Evdev.open(node.path) }.filter { it.second >= 0 }
        fds.forEach { (path, fd) -> println("grab $path -> ${Evdev.grab(fd, true)}") }
        fds.forEach { (path, fd) ->
            thread(isDaemon = true, name = "read-$path") {
                val buf = ByteBuffer.allocateDirect(Evdev.INPUT_EVENT_SIZE * 64).order(ByteOrder.LITTLE_ENDIAN)
                while (true) {
                    val n = Evdev.read(fd, buf)
                    if (n <= 0) { println("read $path ended: $n"); return@thread }
                    for (off in 0 until n step Evdev.INPUT_EVENT_SIZE) {
                        if (buf.getShort(off + 16).toInt() == 1) {
                            println("${System.currentTimeMillis()} $path key=${buf.getShort(off + 18)} value=${buf.getInt(off + 20)}")
                        }
                    }
                }
            }
        }
        Thread.sleep(seconds * 1000)
        // Exiting closes every fd; the kernel drops the grabs with them.
    }
}
```

- [ ] **Step 6: Derle ve tablete kur**

Run:
```
android\gradlew.bat -p android :core:test :app:assembleDebug
adb install -r android\app\build\outputs\apk\debug\app-debug.apk
```
(adb yolu: `%LOCALAPPDATA%\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe`; tablet eşli, `adb devices` cihazı gösterir.)

- [ ] **Step 7: Cihazda doğrula (controller kullanıcıyla koordine eder)**

Prototipi durdur: `adb shell 'kill $(pidof kbdfwd)'`.
Komut (tek satır, `adb shell` içinde):
```
APK=$(pm path dev.keyrelay | cut -d: -f2); LIB=$(dirname $APK)/lib/arm64/libkeyrelay.so; CLASSPATH=$APK app_process /system/bin --nice-name=keyrelay-helper dev.keyrelay.helper.Main --lib=$LIB --grab=20
```
Kontroller ve kayda geçecekler:
1. Liste: `keyboard: MX Keys Mini Keyboard -> /dev/input/eventA(...), /dev/input/eventB(MX Keys Mini Consumer Control)...` — hangi düğümlerin gruplandığını yaz (Risk 5). Her MX Keys düğümünün `uniq` ve `phys` değerlerini de kaydet (`adb shell cat /proc/bus/input/devices`), gruplamanın `uniq` yoluyla olduğunu (phys yedeğiyle değil) teyit et.
2. Grab sırasında kullanıcı harf + medya tuşu (ses) basar: olaylar loglanır, tablette hiçbir şey yazılmaz, ses değişmez (Risk 3 ve 5).
3. Ayrı olarak `adb shell getevent -l` ile medya tuşunun hangi düğümden geldiğini teyit et.
4. Review Focus 1: grab açıkken başka bir adb oturumunda `kill -9 $(pidof keyrelay-helper)` → klavye tablette hemen çalışır.
5. Review Focus 2: grab açıkken klavyeyi kapat (güç düğmesi) → `read ... ended: -19` görülür, süreç çökmez.
Sonuçları `docs/spikes/2026-10-risk-results.md` dosyasına "Risk 3" ve "Risk 5" başlıklarıyla (komut, gözlenen çıktı, sonuç: GEÇTİ/KALDI) yaz. Kaldıysa spec'teki yedek (3) veya (5) uygulanacak şekilde not düş; kod değişikliği yapma.

- [ ] **Step 8: Commit**

```
git -C D:/Repos/KeyRelay add -A
git -C D:/Repos/KeyRelay commit -m "feat(android): klavyeyi bulup kilitleyen yardımcı süreç eklendi" -m "- tabletteki fiziksel klavye ve medya tuşları tek klavye olarak tanınır
- yardımcı süreç klavyeyi kilitleyip tuşları okuyabilir
- cihazdaki doğrulama sonuçları belgelendi" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Ön plan izleme (Risk 2)

**Files:**
- Create: `android/core/src/main/kotlin/dev/keyrelay/core/ForegroundTracker.kt`
- Test: `android/core/src/test/kotlin/dev/keyrelay/core/ForegroundTrackerTest.kt`
- Create: `android/app/src/main/kotlin/dev/keyrelay/helper/ProcessObserverBinder.kt`, `android/app/src/main/kotlin/dev/keyrelay/helper/HiddenApi.kt`
- Modify: `android/app/src/main/kotlin/dev/keyrelay/helper/Main.kt` (`--watch-foreground=<paket>` seçeneği)
- Modify: `docs/spikes/2026-10-risk-results.md` ("Risk 2")

**Interfaces:**
- Consumes: T3 `Main`, `Evdev`.
- Produces (`:core`): `class ForegroundTracker(isTarget: (uid: Int) -> Boolean)` — `fun seed(pidToUid: Map<Int, Int>)`, `fun onForegroundActivitiesChanged(pid: Int, uid: Int, foreground: Boolean): Boolean` (değişti mi), `fun onProcessDied(pid: Int): Boolean`, `val targetForeground: Boolean`.
- Produces (`:app`): `class ProcessObserverBinder(listener: Listener) : Binder()`, `interface Listener { fun onForegroundActivitiesChanged(pid: Int, uid: Int, foreground: Boolean); fun onProcessDied(pid: Int, uid: Int) }`; `object HiddenApi { fun registerProcessObserver(binder: IBinder); fun uidsForPackages(packages: Set<String>): Set<Int>; fun topAppPids(): Map<Int, Int> }`.

- [ ] **Step 1: Testleri yaz**

`ForegroundTrackerTest.kt`:
```kotlin
package dev.keyrelay.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ForegroundTrackerTest {
    private val target = 10123
    private fun tracker() = ForegroundTracker { it == target }

    @Test
    fun onForegroundActivitiesChanged_targetComesToFront_becomesForeground() {
        val t = tracker()
        assertTrue(t.onForegroundActivitiesChanged(100, target, true))
        assertTrue(t.targetForeground)
    }

    @Test
    fun onForegroundActivitiesChanged_targetLeaves_notForeground() {
        val t = tracker().apply { onForegroundActivitiesChanged(100, target, true) }
        assertTrue(t.onForegroundActivitiesChanged(100, target, false))
        assertFalse(t.targetForeground)
    }

    @Test
    fun onForegroundActivitiesChanged_otherAppChanges_reportsNoChange() {
        val t = tracker().apply { onForegroundActivitiesChanged(100, target, true) }
        assertFalse(t.onForegroundActivitiesChanged(200, 10200, true))
        assertTrue(t.targetForeground)
    }

    @Test
    fun onForegroundActivitiesChanged_sameEventTwice_isIdempotent() {
        val t = tracker()
        t.onForegroundActivitiesChanged(100, target, true)
        assertFalse(t.onForegroundActivitiesChanged(100, target, true))
    }

    @Test
    fun onProcessDied_targetDiesWhileForeground_notForeground() {
        val t = tracker().apply { onForegroundActivitiesChanged(100, target, true) }
        assertTrue(t.onProcessDied(100))
        assertFalse(t.targetForeground)
    }

    @Test
    fun seed_targetAlreadyOnTop_isForegroundWithoutEvents() {
        val t = tracker().apply { seed(mapOf(100 to target, 300 to 1000)) }
        assertTrue(t.targetForeground)
    }

    @Test
    fun onForegroundActivitiesChanged_twoTargetProcessesOneLeaves_staysForeground() {
        val t = tracker().apply {
            onForegroundActivitiesChanged(100, target, true)
            onForegroundActivitiesChanged(101, target, true)
        }
        assertFalse(t.onForegroundActivitiesChanged(100, target, false))
        assertTrue(t.targetForeground)
    }
}
```

- [ ] **Step 2: Başarısız olduğunu gör**

Run: `android\gradlew.bat -p android :core:test`
Expected: derleme hatası (`ForegroundTracker` yok).

- [ ] **Step 3: `ForegroundTracker`**

```kotlin
package dev.keyrelay.core

/** Tracks whether any process of the target app currently has foreground activities. */
class ForegroundTracker(private val isTarget: (uid: Int) -> Boolean) {
    private val foregroundPids = HashMap<Int, Int>()

    var targetForeground: Boolean = false
        private set

    fun seed(pidToUid: Map<Int, Int>) {
        foregroundPids.clear()
        foregroundPids.putAll(pidToUid)
        recompute()
    }

    fun onForegroundActivitiesChanged(pid: Int, uid: Int, foreground: Boolean): Boolean {
        if (foreground) foregroundPids[pid] = uid else foregroundPids.remove(pid)
        return recompute()
    }

    fun onProcessDied(pid: Int): Boolean {
        foregroundPids.remove(pid)
        return recompute()
    }

    private fun recompute(): Boolean {
        val now = foregroundPids.values.any(isTarget)
        val changed = now != targetForeground
        targetForeground = now
        return changed
    }
}
```
Run: `android\gradlew.bat -p android :core:test` → tüm testler geçer.

- [ ] **Step 4: Binder gözlemci ve gizli API köprüsü**

`ProcessObserverBinder.kt`:
```kotlin
package dev.keyrelay.helper

import android.os.Binder
import android.os.IBinder
import android.os.Parcel

/**
 * Hand-written stub for the hidden oneway AIDL interface android.app.IProcessObserver
 * (android14-release: onForegroundActivitiesChanged, onForegroundServicesChanged, onProcessDied).
 */
class ProcessObserverBinder(private val listener: Listener) : Binder() {
    interface Listener {
        fun onForegroundActivitiesChanged(pid: Int, uid: Int, foreground: Boolean)
        fun onProcessDied(pid: Int, uid: Int)
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == INTERFACE_TRANSACTION) {
            reply?.writeString(DESCRIPTOR)
            return true
        }
        data.enforceInterface(DESCRIPTOR)
        when (code) {
            IBinder.FIRST_CALL_TRANSACTION -> listener.onForegroundActivitiesChanged(data.readInt(), data.readInt(), data.readInt() != 0)
            IBinder.FIRST_CALL_TRANSACTION + 2 -> listener.onProcessDied(data.readInt(), data.readInt())
        }
        return true
    }

    companion object {
        const val DESCRIPTOR = "android.app.IProcessObserver"
    }
}
```
`HiddenApi.kt`:
```kotlin
package dev.keyrelay.helper

import android.os.IBinder
import java.io.File

/** Reflection over hidden framework APIs. Only valid in a shell-uid app_process. */
object HiddenApi {
    fun registerProcessObserver(binder: IBinder) {
        val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!
        val iface = Class.forName("android.app.IProcessObserver")
        val proxy = Class.forName("android.app.IProcessObserver\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        am.javaClass.getMethod("registerProcessObserver", iface).invoke(am, proxy)
    }

    fun uidsForPackages(packages: Set<String>): Set<Int> {
        val pm = Class.forName("android.app.ActivityThread").getMethod("getPackageManager").invoke(null)!!
        val getUid = pm.javaClass.getMethod("getPackageUid", String::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        return packages.mapNotNull { pkg -> (getUid.invoke(pm, pkg, 0L, 0) as Int).takeIf { it >= 0 } }.toSet()
    }

    /** pid -> uid of processes Android currently shows on screen; seeds state before the first observer event. */
    fun topAppPids(): Map<Int, Int> =
        File("/dev/cpuset/top-app/cgroup.procs").readLines().mapNotNull { line ->
            val pid = line.trim().toIntOrNull() ?: return@mapNotNull null
            val uid = runCatching {
                File("/proc/$pid/status").readLines().first { it.startsWith("Uid:") }.split(Regex("\\s+"))[1].toInt()
            }.getOrNull() ?: return@mapNotNull null
            pid to uid
        }.toMap()
}
```
`IPackageManager.getPackageUid(String, long, int)` imzası Android 13+ içindir; implementer bunu cihazda `HiddenApi.uidsForPackages` çağrısıyla doğrular, NoSuchMethodException alırsa `getMethods()` listesinden `getPackageUid` imzasını loglayıp ona göre düzeltir ve raporda yazar.

- [ ] **Step 5: `Main`'e `--watch-foreground`**

`Main.main` içinde `opts["--grab"]` satırından sonra, `exitProcess(0)`'dan önce:
```kotlin
        opts["--watch-foreground"]?.let { pkgs -> watchForeground(pkgs.split(',').toSet()) }
```
ve `Main` içine:
```kotlin
    private fun watchForeground(packages: Set<String>) {
        val targetUids = HiddenApi.uidsForPackages(packages)
        val tracker = ForegroundTracker { it in targetUids }
        tracker.seed(HiddenApi.topAppPids())
        println("${System.currentTimeMillis()} target uids=$targetUids foreground=${tracker.targetForeground} (seed)")
        val lock = Any()
        HiddenApi.registerProcessObserver(ProcessObserverBinder(object : ProcessObserverBinder.Listener {
            override fun onForegroundActivitiesChanged(pid: Int, uid: Int, foreground: Boolean) = synchronized(lock) {
                if (tracker.onForegroundActivitiesChanged(pid, uid, foreground)) {
                    println("${System.currentTimeMillis()} foreground=${tracker.targetForeground}")
                }
            }

            override fun onProcessDied(pid: Int, uid: Int) = synchronized(lock) {
                if (tracker.onProcessDied(pid)) println("${System.currentTimeMillis()} foreground=${tracker.targetForeground} (died)")
            }
        }))
        Thread.sleep(Long.MAX_VALUE)
    }
```
(`import dev.keyrelay.core.ForegroundTracker`.)

- [ ] **Step 6: Cihazda doğrula**

Derle + kur (T3 Step 6). TeamViewer'ı öne getir, sonra:
```
adb shell 'APK=$(pm path dev.keyrelay | cut -d: -f2); LIB=$(dirname $APK)/lib/arm64/libkeyrelay.so; CLASSPATH=$APK app_process /system/bin --nice-name=keyrelay-helper dev.keyrelay.helper.Main --lib=$LIB --watch-foreground=com.teamviewer.teamviewer.market.mobile'
```
1. Review Focus 3: ilk satır `foreground=true (seed)` olmalı (TeamViewer öndeyken başlatıldı).
2. Gecikme ölçümü (5 kez): ikinci bir terminalde `adb shell 'date +%s%3N; am start -n com.samsung.android.app.notes/.memolist.MemoListActivity'` (Notes'un başlatıcı aktivitesini `cmd package resolve-activity --brief com.samsung.android.app.notes` ile bul) → helper satırındaki zaman damgasıyla farkı yaz; sonra TeamViewer'ı aynı yolla başlat. Her ölçüm < 500 ms (bitiş maddesi 3). Ölçüm uygulamanın başlatılma süresini de içerir (üst sınır); sonuç belgesine bu notu yaz.
3. TeamViewer'ı `am force-stop` ile öldür → `foreground=false (died)`.
Sonucu "Risk 2" başlığıyla yaz (ölçüm tablosu dahil).

- [ ] **Step 7: Commit**

```
git -C D:/Repos/KeyRelay add -A
git -C D:/Repos/KeyRelay commit -m "feat(android): ön plandaki uygulama yardımcı süreçte izlenir oldu" -m "- uzak masaüstü uygulaması öne gelince ya da arka plana geçince anında fark edilir
- ölçülen geçiş süreleri belgelendi" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Gömülü ADB ile başlatma ve açılışta otomatik başlatma (Risk 1)

**Files:**
- Modify: `android/app/build.gradle.kts` (libadb, conscrypt, bcpkix bağımlılıkları)
- Create: `android/app/src/main/kotlin/dev/keyrelay/launcher/AdbManager.kt`, `android/app/src/main/kotlin/dev/keyrelay/launcher/HelperLauncher.kt`, `android/app/src/main/kotlin/dev/keyrelay/launcher/StartupService.kt`, `android/app/src/main/kotlin/dev/keyrelay/launcher/BootReceiver.kt`
- Modify: `android/app/src/main/kotlin/dev/keyrelay/MainActivity.kt` (eşleştirme ve "şimdi başlat" ekranı), `android/app/src/main/AndroidManifest.xml`
- Test: `android/core/src/test/kotlin/dev/keyrelay/core/HelperCommandTest.kt`; Create: `android/core/src/main/kotlin/dev/keyrelay/core/HelperCommand.kt`
- Modify: `docs/spikes/2026-10-risk-results.md` ("Risk 1")

**Interfaces:**
- Consumes: T3/T4 `dev.keyrelay.helper.Main` (`--lib`, `--watch-foreground`).
- Produces: `object HelperCommand { fun build(apkPath: String, nativeLibDir: String, args: List<String>): String }` (`:core`); `class AdbManager` (`AbsAdbConnectionManager`, singleton `AdbManager.get(context)`, `fun exec(cmd: String): String`); `class HelperLauncher(context: Context)` — `suspend fun pair(host: String, port: Int, code: String)`, `suspend fun grantSecureSettings()`, `suspend fun startHelper(): Boolean`, `suspend fun autoStart(): Boolean`.

- [ ] **Step 1: Komut oluşturucu testleri**

`HelperCommandTest.kt`:
```kotlin
package dev.keyrelay.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class HelperCommandTest {
    @Test
    fun build_typicalPaths_producesDetachedAppProcessCommand() {
        val cmd = HelperCommand.build("/data/app/~~a/dev.keyrelay-b/base.apk", "/data/app/~~a/dev.keyrelay-b/lib/arm64", listOf("--watch-foreground=com.x"))
        assertEquals(
            "CLASSPATH='/data/app/~~a/dev.keyrelay-b/base.apk' setsid nohup app_process /system/bin --nice-name=keyrelay-helper " +
                "dev.keyrelay.helper.Main '--lib=/data/app/~~a/dev.keyrelay-b/lib/arm64/libkeyrelay.so' '--watch-foreground=com.x' " +
                "> /data/local/tmp/keyrelay-helper.log 2>&1 < /dev/null &",
            cmd,
        )
    }

    @Test
    fun build_pathWithSingleQuote_isRejected() {
        assertThrows(IllegalArgumentException::class.java) { HelperCommand.build("/data/app/x'y/base.apk", "/lib", emptyList()) }
    }
}
```
Run `:core:test` → derleme hatası.

- [ ] **Step 2: `HelperCommand`**

```kotlin
package dev.keyrelay.core

/** Builds the adb shell command that starts the helper detached from the adb session. */
object HelperCommand {
    // 15 chars: the kernel comm limit; a longer name is truncated and pidof stops matching.
    const val NICE_NAME = "keyrelay-helper"
    const val LOG_PATH = "/data/local/tmp/keyrelay-helper.log"

    fun build(apkPath: String, nativeLibDir: String, args: List<String>): String {
        val all = listOf(apkPath, nativeLibDir) + args
        require(all.none { '\'' in it }) { "shell argument contains a single quote" }
        val quotedArgs = (listOf("--lib=$nativeLibDir/libkeyrelay.so") + args).joinToString(" ") { "'$it'" }
        return "CLASSPATH='$apkPath' setsid nohup app_process /system/bin --nice-name=$NICE_NAME " +
            "dev.keyrelay.helper.Main $quotedArgs > $LOG_PATH 2>&1 < /dev/null &"
    }
}
```
Run `:core:test` → geçer.

- [ ] **Step 3: Bağımlılıklar ve manifest**

`app/build.gradle.kts` `dependencies`'e: `implementation(libs.libadb)`, `implementation(libs.conscrypt)`, `implementation(libs.bcpkix)`.

`AndroidManifest.xml` (`<manifest>` altına `xmlns:tools="http://schemas.android.com/tools"` ekle):
```xml
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.WRITE_SECURE_SETTINGS" tools:ignore="ProtectedPermissions" />
```
`<application>` içine:
```xml
        <service android:name=".launcher.StartupService" android:exported="false" android:foregroundServiceType="connectedDevice" />
        <receiver android:name=".launcher.BootReceiver" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
            </intent-filter>
        </receiver>
```

- [ ] **Step 4: `AdbManager`**

```kotlin
package dev.keyrelay.launcher

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/** ADB client identity of this app; the key pair is what wireless debugging remembers after pairing. */
class AdbManager private constructor(context: Context) : AbsAdbConnectionManager() {
    private val keyFile = File(context.filesDir, "adb.key")
    private val certFile = File(context.filesDir, "adb.crt")
    private val privateKey: PrivateKey
    private val certificate: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        if (keyFile.exists() && certFile.exists()) {
            privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            certificate = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        } else {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val now = Date()
            val name = X500Name("CN=KeyRelay")
            val holder = JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now.time), now, Date(now.time + VALIDITY_MS), name, pair.public)
                .build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private))
            privateKey = pair.private
            certificate = JcaX509CertificateConverter().setProvider(BouncyCastleProvider()).getCertificate(holder)
            keyFile.writeBytes(privateKey.encoded)
            certFile.writeBytes(certificate.encoded)
        }
    }

    override fun getPrivateKey(): PrivateKey = privateKey
    override fun getCertificate(): Certificate = certificate
    override fun getDeviceName(): String = "KeyRelay"

    // ponytail: no per-call timeout; safe only for commands that return promptly (the helper start detaches with < /dev/null &). P2 adds a timeout.
    fun exec(cmd: String): String = openStream("shell:$cmd").use { it.openInputStream().bufferedReader().readText() }

    companion object {
        private const val VALIDITY_MS = 10L * 365 * 24 * 60 * 60 * 1000
        @Volatile private var instance: AdbManager? = null

        fun get(context: Context): AdbManager =
            instance ?: synchronized(this) { instance ?: AdbManager(context.applicationContext).also { instance = it } }
    }
}
```
libadb sınıf/paket adlarını (`io.github.muntashirakon.adb.AbsAdbConnectionManager`, `openStream`, `AdbStream.openInputStream`, `pair`, `autoConnect`) bağımlılık indirildikten sonra jar içinde doğrula (`unzip -l` veya IDE); farklıysa gerçek adı kullan ve raporda yaz. `AdbStream` `Closeable` değilse `use` yerine `try/finally { close() }`.

- [ ] **Step 5: `HelperLauncher`**

```kotlin
package dev.keyrelay.launcher

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.provider.Settings
import android.util.Log
import dev.keyrelay.core.HelperCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class HelperLauncher(private val context: Context) {
    private val adb get() = AdbManager.get(context)
    private val resolver get() = context.contentResolver

    suspend fun pair(host: String, port: Int, code: String) = withContext(Dispatchers.IO) { adb.pair(host, port, code) }

    suspend fun grantSecureSettings() = withContext(Dispatchers.IO) {
        connect()
        adb.exec("pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS")
    }

    suspend fun startHelper(): Boolean = withContext(Dispatchers.IO) {
        connect()
        val info = context.applicationInfo
        adb.exec(HelperCommand.build(info.sourceDir, info.nativeLibraryDir, listOf(TARGET_ARG)))
        withTimeoutOrNull(HELPER_START_TIMEOUT_MS) {
            while (adb.exec("pidof ${HelperCommand.NICE_NAME}").isBlank()) delay(POLL_MS)
            true
        } ?: false
    }

    /** Key Mapper SystemBridgeSetupController.autoStartWithAdb order: dev options, adb, wireless adb, connect, start, wireless adb off. */
    suspend fun autoStart(): Boolean {
        if (context.checkSelfPermission(WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return log("no WRITE_SECURE_SETTINGS")
        awaitWifi()
        putGlobal(DEV_OPTIONS, 1)
        putGlobal(Settings.Global.ADB_ENABLED, 1)
        putGlobal(ADB_WIFI, 1)
        if (withTimeoutOrNull(SETTING_TIMEOUT_MS) { while (getGlobal(ADB_WIFI) != 1) delay(POLL_MS); true } == null) return log("wireless debugging did not turn on")
        val started = runCatching { startHelper() }.onFailure { Log.e(TAG, "start failed", it) }.getOrDefault(false)
        putGlobal(ADB_WIFI, 0)
        return started.also { log("autoStart result=$it") }
    }

    private fun connect() {
        if (!adb.isConnected && !adb.autoConnect(context, CONNECT_TIMEOUT_MS)) error("adb autoConnect failed")
    }

    private suspend fun awaitWifi() {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) return
        log("waiting for wifi")
        suspendCancellableCoroutine { cont ->
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    cm.unregisterNetworkCallback(this)
                    if (cont.isActive) cont.resume(Unit)
                }
            }
            cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
            cont.invokeOnCancellation { runCatching { cm.unregisterNetworkCallback(callback) } }
        }
    }

    private fun putGlobal(key: String, value: Int) = Settings.Global.putInt(resolver, key, value)
    private fun getGlobal(key: String) = Settings.Global.getInt(resolver, key, 0)
    private fun log(msg: String): Boolean { Log.i(TAG, msg); return false }

    companion object {
        private const val TAG = "KeyRelayLauncher"
        private const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"
        private const val DEV_OPTIONS = "development_settings_enabled"
        private const val ADB_WIFI = "adb_wifi_enabled"
        private const val TARGET_ARG = "--watch-foreground=com.teamviewer.teamviewer.market.mobile"
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val SETTING_TIMEOUT_MS = 5_000L
        private const val HELPER_START_TIMEOUT_MS = 10_000L
        private const val POLL_MS = 200L
    }
}
```
`kotlinx-coroutines-android`'i version catalog'a ekle (sürümü Maven Central `org/jetbrains/kotlinx/kotlinx-coroutines-android/maven-metadata.xml`'den en yüksek kararlı) ve `:app`'e bağla. `adb.isConnected` libadb'de metot ise (`isConnected()`), Kotlin özellik erişimi çalışır; değilse gerçek adı kullan.

- [ ] **Step 6: Başlatma servisi ve açılış alıcısı**

`StartupService.kt`:
```kotlin
package dev.keyrelay.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Short-lived foreground service: boot receivers may not run long enough for the adb start sequence. */
class StartupService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "KeyRelay", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL).setContentTitle("KeyRelay starting").setSmallIcon(android.R.drawable.ic_dialog_info).build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        scope.launch {
            HelperLauncher(this@StartupService).autoStart()
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val CHANNEL = "startup"
        const val NOTIFICATION_ID = 1
    }
}
```
`BootReceiver.kt`:
```kotlin
package dev.keyrelay.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            context.startForegroundService(Intent(context, StartupService::class.java))
        }
    }
}
```

- [ ] **Step 7: Kurulum ekranı**

`MainActivity` içeriğini şu Compose ekranıyla değiştir: üç metin alanı (IP, eşleştirme portu, 6 haneli kod), "Eşleştir" düğmesi (`HelperLauncher.pair`), "İzin ver" (`grantSecureSettings`), "Şimdi başlat" (`startHelper`), "Otomatik başlatmayı dene" (`startForegroundService(StartupService)`), ve son işlemin sonucunu gösteren durum metni. İşlemler `rememberCoroutineScope().launch` içinde, hatalar `runCatching` ile durum metnine yazılır. Açılışta `POST_NOTIFICATIONS` izni istenir (`rememberLauncherForActivityResult(RequestPermission())`). Görsel tasarım P2'de yapılır; bu ekran yalnız işlevseldir.

- [ ] **Step 8: Cihazda doğrula (kullanıcı adımları; controller koordine eder)**

Prototipi durdur. APK'yı kur. Kullanıcı:
1. Ayarlar → Geliştirici seçenekleri → Kablosuz hata ayıklama → "Eşleştirme koduyla eşle" (bölünmüş ekranda KeyRelay açık) → IP, port, kodu KeyRelay'e gir → Eşleştir → İzin ver → Şimdi başlat. Beklenen: `adb shell pidof keyrelay-helper` bir pid döner; `/data/local/tmp/keyrelay-helper.log` ön plan satırları içerir.
2. Kablosuz hata ayıklamayı kapat → yardımcı yaşamaya devam eder (`pidof` hâlâ pid; PC'den `adb` bağlantısı koptuğu için kontrolü KeyRelay ekranındaki durum metniyle veya yeniden eşli USB/adb ile yap).
3. Ev Wi-Fi'ında yeniden başlat, cihaza dokunma; ekran kilidini açma dahil hiçbir şey yapmadan 60 sn bekle → sonra kilidi aç ve `pidof keyrelay-helper` (adb'yi yeniden bağlamak gerekirse) ile çalıştığını, `adb_wifi_enabled`'ın 0'a döndüğünü (`settings get global adb_wifi_enabled`) doğrula. Not: `BOOT_COMPLETED` ilk kilit açılışından sonra gelir (cihaz şifreliyse); süre kilit açılışından ölçülür — bu durumu sonuç belgesine yaz.
4. Yabancı ağ: tableti daha önce kablosuz hata ayıklamaya hiç izin verilmemiş bir Wi-Fi'a (kullanıcının telefon hotspot'u) bağla, yeniden başlat, aynı kontrol.
5. Kablosuz hata ayıklama yabancı ağda programla açılamıyorsa logcat'te (`adb logcat -s KeyRelayLauncher AdbDebuggingManager`) gerekçeyi kaydet.
Sonucu "Risk 1" başlığıyla yaz (her senaryo için: süre, sonuç).

- [ ] **Step 9: Review Focus 4 — açılışta Wi-Fi yok**

Uçak modunda yeniden başlat; 2 dk bekle; `StartupService` bildirimi görünür, CPU'yu meşgul etmez (`adb shell top -n 1 | grep dev.keyrelay` — %0'a yakın; adb için Wi-Fi gerektiğinden bu ölçümü Wi-Fi açılmadan hemen önce bildirim + pil istatistiğiyle yap ya da USB adb kullan); sonra Wi-Fi'ı aç → yardımcı başlar. Sonucu "Risk 1" altında yaz.

- [ ] **Step 10: Commit**

```
git -C D:/Repos/KeyRelay add -A
git -C D:/Repos/KeyRelay commit -m "feat(android): yardımcı süreç tablet açılınca kendiliğinden başlar" -m "- ilk kurulum bilgisayar olmadan tablette yapılır
- tablet yeniden başlayınca kimse dokunmadan hazır olur
- farklı Wi-Fi ağlarındaki sonuçlar belgelendi" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Windows çözüm iskeleti

**Files:**
- Create: `windows/global.json`, `windows/Directory.Build.props`, `windows/Directory.Packages.props`, `windows/KeyRelay.slnx`
- Create: `windows/src/KeyRelay.Service/KeyRelay.Service.csproj`, `windows/src/KeyRelay.Service/Program.cs`, `windows/src/KeyRelay.Service/Native/User32.cs`
- Create: `windows/tests/KeyRelay.Tests/KeyRelay.Tests.csproj`, `windows/tests/KeyRelay.Tests/NativeLayoutTests.cs`
- Create: `.github/workflows/windows.yml`

**Interfaces:**
- Produces: `KeyRelay.Service` (`net10.0-windows`, `OutputType Exe`), `internal static partial class User32` (T7 genişletir), test projesi `InternalsVisibleTo` ile erişir.

- [ ] **Step 1: Kök dosyalar**

Ön koşul: `dotnet --list-sdks` çıktısında `10.0.302` (veya daha yüksek 10.0.3xx) var; yoksa `winget install --id Microsoft.DotNet.SDK.10 -e`.

`windows/global.json`:
```json
{ "sdk": { "version": "10.0.302", "rollForward": "latestFeature" }, "msbuild-sdks": { "MSTest.Sdk": "4.4.1" } }
```
`windows/Directory.Build.props`:
```xml
<Project>
  <PropertyGroup>
    <Nullable>enable</Nullable>
    <ImplicitUsings>enable</ImplicitUsings>
    <LangVersion>latest</LangVersion>
    <TreatWarningsAsErrors>true</TreatWarningsAsErrors>
    <AnalysisLevel>latest-recommended</AnalysisLevel>
    <EnforceCodeStyleInBuild>true</EnforceCodeStyleInBuild>
    <AllowUnsafeBlocks>true</AllowUnsafeBlocks>
  </PropertyGroup>
</Project>
```
`windows/Directory.Packages.props`:
```xml
<Project>
  <PropertyGroup>
    <ManagePackageVersionsCentrally>true</ManagePackageVersionsCentrally>
  </PropertyGroup>
  <ItemGroup>
    <PackageVersion Include="Microsoft.Extensions.Hosting.WindowsServices" Version="10.0.12" />
  </ItemGroup>
</Project>
```
`windows/KeyRelay.slnx`:
```xml
<Solution>
  <Folder Name="/src/">
    <Project Path="src/KeyRelay.Service/KeyRelay.Service.csproj" />
  </Folder>
  <Folder Name="/tests/">
    <Project Path="tests/KeyRelay.Tests/KeyRelay.Tests.csproj" />
  </Folder>
</Solution>
```

- [ ] **Step 2: Servis projesi ve SendInput yapıları**

`KeyRelay.Service.csproj`:
```xml
<Project Sdk="Microsoft.NET.Sdk.Worker">
  <PropertyGroup>
    <TargetFramework>net10.0-windows</TargetFramework>
    <OutputType>Exe</OutputType>
    <RootNamespace>KeyRelay.Service</RootNamespace>
  </PropertyGroup>
  <ItemGroup>
    <PackageReference Include="Microsoft.Extensions.Hosting.WindowsServices" />
    <InternalsVisibleTo Include="KeyRelay.Tests" />
  </ItemGroup>
</Project>
```
`Native/User32.cs`:
```csharp
using System.Runtime.InteropServices;

namespace KeyRelay.Service.Native;

internal static partial class User32
{
    internal const uint INPUT_KEYBOARD = 1;
    internal const uint KEYEVENTF_KEYUP = 0x0002;
    internal const uint KEYEVENTF_UNICODE = 0x0004;
    internal const uint KEYEVENTF_SCANCODE = 0x0008;

    [StructLayout(LayoutKind.Sequential)]
    internal struct KeyboardInput
    {
        public ushort Vk;
        public ushort Scan;
        public uint Flags;
        public uint Time;
        public nint ExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    internal struct MouseInput
    {
        public int Dx;
        public int Dy;
        public uint MouseData;
        public uint Flags;
        public uint Time;
        public nint ExtraInfo;
    }

    [StructLayout(LayoutKind.Explicit)]
    internal struct InputUnion
    {
        [FieldOffset(0)] public MouseInput Mouse;
        [FieldOffset(0)] public KeyboardInput Keyboard;
    }

    [StructLayout(LayoutKind.Sequential)]
    internal struct Input
    {
        public uint Type;
        public InputUnion Union;
    }

    [LibraryImport("user32.dll", SetLastError = true)]
    internal static unsafe partial uint SendInput(uint count, Input* inputs, int size);
}
```
`Program.cs` (T7 genişletir):
```csharp
var builder = Host.CreateApplicationBuilder(args);
builder.Services.AddWindowsService(options => options.ServiceName = "KeyRelay");
await builder.Build().RunAsync().ConfigureAwait(false);
```

- [ ] **Step 3: Testi yaz, çalıştır**

`KeyRelay.Tests.csproj`:
```xml
<Project Sdk="MSTest.Sdk">
  <PropertyGroup>
    <TargetFramework>net10.0-windows</TargetFramework>
  </PropertyGroup>
  <ItemGroup>
    <ProjectReference Include="..\..\src\KeyRelay.Service\KeyRelay.Service.csproj" />
  </ItemGroup>
</Project>
```
`NativeLayoutTests.cs`:
```csharp
using System.Runtime.InteropServices;
using KeyRelay.Service.Native;

namespace KeyRelay.Tests;

[TestClass]
public sealed class NativeLayoutTests
{
    [TestMethod]
    public void InputStruct_X64_MatchesWin32Size()
    {
        // Win32 INPUT is 40 bytes on x64; SendInput rejects any other cbSize.
        Assert.AreEqual(40, Marshal.SizeOf<User32.Input>());
    }
}
```
Run: `dotnet test D:\Repos\KeyRelay\windows\KeyRelay.slnx`
Expected: 1 test geçti, uyarı yok.

- [ ] **Step 4: CI**

`.github/workflows/windows.yml`:
```yaml
name: windows
on:
  push: { paths: ["windows/**", "protocol/**", ".github/workflows/windows.yml"] }
  pull_request: { paths: ["windows/**", "protocol/**", ".github/workflows/windows.yml"] }
jobs:
  build:
    runs-on: windows-latest
    steps:
      - uses: actions/checkout@v5
      - uses: actions/setup-dotnet@v5
        with: { global-json-file: windows/global.json }
      - run: dotnet test windows/KeyRelay.slnx -c Release
```
Action sürümlerini T2 Step 6'daki gibi doğrula.

- [ ] **Step 5: Commit**

```
git -C D:/Repos/KeyRelay add -A
git -C D:/Repos/KeyRelay commit -m "build(windows): windows çözüm iskeleti ve otomatik derleme eklendi" -m "- windows servisi ve test projesi derlenir hale geldi
- her değişiklikte testler otomatik koşar" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Oturuma SYSTEM ajan, kilit ekranı denemesi (Risk 4)

**Files:**
- Create: `windows/src/KeyRelay.Service/Native/Advapi32.cs`, `windows/src/KeyRelay.Service/Native/Kernel32.cs`, `windows/src/KeyRelay.Service/Native/Sas.cs`
- Modify: `windows/src/KeyRelay.Service/Native/User32.cs` (masaüstü ve kilit fonksiyonları)
- Create: `windows/src/KeyRelay.Service/Session/SessionLauncher.cs`, `windows/src/KeyRelay.Service/Session/AgentSupervisor.cs`
- Create: `windows/src/KeyRelay.Service/Probe/AgentProbe.cs`, `windows/src/KeyRelay.Service/Probe/SasProbe.cs`
- Modify: `windows/src/KeyRelay.Service/Program.cs`
- Modify: `docs/spikes/2026-10-risk-results.md` ("Risk 4")

**Interfaces:**
- Consumes: T6 `User32.Input`, `User32.SendInput`.
- Produces: `internal static class SessionLauncher { static Process Launch(uint sessionId, string commandLine) }`, `internal sealed class AgentSupervisor : BackgroundService` (P3'te pipe bağlantısıyla genişler). `Probe/` klasörü P3'te silinir.

- [ ] **Step 1: P/Invoke bildirimleri**

`Kernel32.cs`:
```csharp
using System.Runtime.InteropServices;

namespace KeyRelay.Service.Native;

internal static partial class Kernel32
{
    internal const uint NO_ACTIVE_SESSION = 0xFFFFFFFF;

    [LibraryImport("kernel32.dll")]
    internal static partial uint WTSGetActiveConsoleSessionId();

    [LibraryImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static partial bool CloseHandle(nint handle);
}
```
`Advapi32.cs`:
```csharp
using System.Runtime.InteropServices;

namespace KeyRelay.Service.Native;

internal static partial class Advapi32
{
    internal const uint TOKEN_ALL_ACCESS = 0xF01FF;
    internal const uint MAXIMUM_ALLOWED = 0x02000000;
    internal const int SECURITY_IMPERSONATION = 2;
    internal const int TOKEN_PRIMARY = 1;
    internal const int TOKEN_SESSION_ID = 12;
    internal const uint CREATE_NO_WINDOW = 0x08000000;
    internal const uint CREATE_UNICODE_ENVIRONMENT = 0x00000400;

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    internal struct StartupInfo
    {
        public int Cb;
        public string? Reserved;
        public string? Desktop;
        public string? Title;
        public int X, Y, XSize, YSize, XCountChars, YCountChars, FillAttribute, Flags;
        public short ShowWindow, Reserved2;
        public nint Reserved2Ptr, StdInput, StdOutput, StdError;
    }

    [StructLayout(LayoutKind.Sequential)]
    internal struct ProcessInformation
    {
        public nint Process;
        public nint Thread;
        public int ProcessId;
        public int ThreadId;
    }

    [LibraryImport("advapi32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static partial bool OpenProcessToken(nint process, uint access, out nint token);

    [LibraryImport("advapi32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static partial bool DuplicateTokenEx(nint token, uint access, nint attributes, int level, int type, out nint newToken);

    [LibraryImport("advapi32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static partial bool SetTokenInformation(nint token, int infoClass, ref uint info, int length);

    // STARTUPINFO carries strings, which LibraryImport cannot marshal inside a struct.
#pragma warning disable SYSLIB1054
    [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Unicode, EntryPoint = "CreateProcessAsUserW")]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static extern bool CreateProcessAsUser(nint token, string? application, System.Text.StringBuilder commandLine,
        nint processAttributes, nint threadAttributes, [MarshalAs(UnmanagedType.Bool)] bool inheritHandles, uint flags,
        nint environment, string? currentDirectory, ref StartupInfo startupInfo, out ProcessInformation processInformation);
#pragma warning restore SYSLIB1054
}
```
`Sas.cs`:
```csharp
using System.Runtime.InteropServices;

namespace KeyRelay.Service.Native;

internal static partial class Sas
{
    /// <summary>Simulates Ctrl+Alt+Del. Requires the SoftwareSASGeneration policy; asUser false = caller is a service.</summary>
    [LibraryImport("sas.dll")]
    internal static partial void SendSAS([MarshalAs(UnmanagedType.Bool)] bool asUser);
}
```
`User32.cs`'e ekle:
```csharp
    internal const uint GENERIC_ALL = 0x10000000;
    internal const int UOI_NAME = 2;

    [LibraryImport("user32.dll", SetLastError = true)]
    internal static partial nint OpenInputDesktop(uint flags, [MarshalAs(UnmanagedType.Bool)] bool inherit, uint access);

    [LibraryImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static partial bool SetThreadDesktop(nint desktop);

    [LibraryImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static partial bool CloseDesktop(nint desktop);

    [LibraryImport("user32.dll", SetLastError = true, EntryPoint = "GetUserObjectInformationW")]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static unsafe partial bool GetUserObjectInformation(nint obj, int index, char* buffer, uint length, out uint needed);

    [LibraryImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static partial bool LockWorkStation();

    internal static unsafe string DesktopName(nint desktop)
    {
        char* buffer = stackalloc char[64];
        return GetUserObjectInformation(desktop, UOI_NAME, buffer, 64 * sizeof(char), out _) ? new string(buffer) : "?";
    }
```

- [ ] **Step 2: `SessionLauncher`**

```csharp
using System.ComponentModel;
using System.Diagnostics;
using System.Text;
using KeyRelay.Service.Native;

namespace KeyRelay.Service.Session;

/// <summary>Starts a process as SYSTEM inside an interactive session, so it can reach that session's desktops.</summary>
internal static class SessionLauncher
{
    internal static Process Launch(uint sessionId, string commandLine)
    {
        if (!Advapi32.OpenProcessToken(Process.GetCurrentProcess().Handle, Advapi32.TOKEN_ALL_ACCESS, out var token))
            throw new Win32Exception();
        nint primary = 0;
        try
        {
            if (!Advapi32.DuplicateTokenEx(token, Advapi32.MAXIMUM_ALLOWED, 0, Advapi32.SECURITY_IMPERSONATION, Advapi32.TOKEN_PRIMARY, out primary))
                throw new Win32Exception();
            if (!Advapi32.SetTokenInformation(primary, Advapi32.TOKEN_SESSION_ID, ref sessionId, sizeof(uint)))
                throw new Win32Exception();
            var startup = new Advapi32.StartupInfo { Cb = System.Runtime.InteropServices.Marshal.SizeOf<Advapi32.StartupInfo>(), Desktop = @"winsta0\default" };
            if (!Advapi32.CreateProcessAsUser(primary, null, new StringBuilder(commandLine), 0, 0, false,
                    Advapi32.CREATE_NO_WINDOW | Advapi32.CREATE_UNICODE_ENVIRONMENT, 0, null, ref startup, out var info))
                throw new Win32Exception();
            Kernel32.CloseHandle(info.Thread);
            Kernel32.CloseHandle(info.Process);
            return Process.GetProcessById(info.ProcessId);
        }
        finally
        {
            if (primary != 0) Kernel32.CloseHandle(primary);
            Kernel32.CloseHandle(token);
        }
    }
}
```

- [ ] **Step 3: `AgentSupervisor`**

```csharp
using System.Diagnostics;
using KeyRelay.Service.Native;

namespace KeyRelay.Service.Session;

/// <summary>Keeps exactly one agent running in the active console session.</summary>
internal sealed class AgentSupervisor(ILogger<AgentSupervisor> logger) : BackgroundService
{
    private static readonly TimeSpan CHECK_INTERVAL = TimeSpan.FromSeconds(1);
    private Process? _agent;
    private uint _agentSession = Kernel32.NO_ACTIVE_SESSION;

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(CHECK_INTERVAL);
        do
        {
            var session = Kernel32.WTSGetActiveConsoleSessionId();
            if (session != Kernel32.NO_ACTIVE_SESSION && (_agent is null || _agent.HasExited || session != _agentSession))
            {
                Stop();
                try
                {
                    _agent = SessionLauncher.Launch(session, $"\"{Environment.ProcessPath}\" --agent");
                    _agentSession = session;
                    logger.LogInformation("Agent {Pid} started in session {Session}", _agent.Id, session);
                }
                catch (System.ComponentModel.Win32Exception ex)
                {
                    logger.LogError(ex, "Agent start failed in session {Session}", session);
                }
            }
        }
        while (await timer.WaitForNextTickAsync(stoppingToken).ConfigureAwait(false));
    }

    public override async Task StopAsync(CancellationToken cancellationToken)
    {
        await base.StopAsync(cancellationToken).ConfigureAwait(false);
        Stop();
    }

    private void Stop()
    {
        if (_agent is { HasExited: false }) _agent.Kill();
        _agent?.Dispose();
        _agent = null;
    }
}
```
(1 sn'lik kontrol P1 spike'ı içindir; P3'te `OnSessionChange` bildirimiyle değiştirilir — spec "oturum değişim bildirimi".)

- [ ] **Step 4: Prob ajan ve SAS probu**

`Probe/AgentProbe.cs`:
```csharp
using KeyRelay.Service.Native;

namespace KeyRelay.Service.Probe;

/// <summary>Risk 4 probe. Follows the input desktop and acts on trigger files written by an admin.</summary>
internal static class AgentProbe
{
    internal static readonly string DIR = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "KeyRelay");
    // One file per process: the service (session 0) and the agent (user session) must never append to the same file.
    private static readonly string LOG = Path.Combine(DIR, Environment.GetCommandLineArgs().Contains("--agent") ? "probe-agent.log" : "probe-service.log");
    private static readonly string TYPE_FILE = Path.Combine(DIR, "probe-type.txt");
    private static readonly string LOCK_FILE = Path.Combine(DIR, "probe-lock");
    private static readonly string SCAN_FILE = Path.Combine(DIR, "probe-scan");

    // Set-1 scancodes for "test": the product injects scancodes, so the secure-desktop verdict must use them too.
    private static readonly ushort[] TEST_SCANCODES = [0x14, 0x12, 0x1F, 0x14];

    internal static int Run()
    {
        Directory.CreateDirectory(DIR);
        Log($"agent start pid={Environment.ProcessId} session={System.Diagnostics.Process.GetCurrentProcess().SessionId}");
        nint current = 0;
        string? last = null;
        while (true)
        {
            var desk = User32.OpenInputDesktop(0, false, User32.GENERIC_ALL);
            var name = desk == 0 ? $"<OpenInputDesktop failed {System.Runtime.InteropServices.Marshal.GetLastPInvokeError()}>" : User32.DesktopName(desk);
            if (desk != 0)
            {
                if (User32.SetThreadDesktop(desk))
                {
                    if (current != 0) User32.CloseDesktop(current);
                    current = desk;
                }
                else
                {
                    name += $" <SetThreadDesktop failed {System.Runtime.InteropServices.Marshal.GetLastPInvokeError()}>";
                    User32.CloseDesktop(desk);
                }
            }
            if (name != last) { Log($"desktop: {name}"); last = name; }
            if (File.Exists(TYPE_FILE))
            {
                var text = File.ReadAllText(TYPE_FILE).Trim();
                File.Delete(TYPE_FILE);
                var sent = TypeText(text);
                Log($"typed len={text.Length} sent={sent} err={System.Runtime.InteropServices.Marshal.GetLastPInvokeError()} desktop={name}");
            }
            if (File.Exists(SCAN_FILE))
            {
                File.Delete(SCAN_FILE);
                var sent = TypeScancodes(TEST_SCANCODES);
                Log($"scancodes 'test' sent={sent} err={System.Runtime.InteropServices.Marshal.GetLastPInvokeError()} desktop={name}");
            }
            if (File.Exists(LOCK_FILE))
            {
                File.Delete(LOCK_FILE);
                Log($"LockWorkStation={User32.LockWorkStation()} err={System.Runtime.InteropServices.Marshal.GetLastPInvokeError()}");
            }
            Thread.Sleep(500);
        }
    }

    private static unsafe uint TypeText(string text)
    {
        var inputs = new User32.Input[text.Length * 2];
        for (var i = 0; i < text.Length; i++)
        {
            inputs[i * 2] = Unicode(text[i], 0);
            inputs[i * 2 + 1] = Unicode(text[i], User32.KEYEVENTF_KEYUP);
        }
        fixed (User32.Input* p = inputs) return User32.SendInput((uint)inputs.Length, p, sizeof(User32.Input));
    }

    private static unsafe uint TypeScancodes(ushort[] scancodes)
    {
        var inputs = new User32.Input[scancodes.Length * 2];
        for (var i = 0; i < scancodes.Length; i++)
        {
            inputs[i * 2] = Scancode(scancodes[i], 0);
            inputs[i * 2 + 1] = Scancode(scancodes[i], User32.KEYEVENTF_KEYUP);
        }
        fixed (User32.Input* p = inputs) return User32.SendInput((uint)inputs.Length, p, sizeof(User32.Input));
    }

    private static User32.Input Scancode(ushort scan, uint up) => new()
    {
        Type = User32.INPUT_KEYBOARD,
        Union = new User32.InputUnion { Keyboard = new User32.KeyboardInput { Scan = scan, Flags = User32.KEYEVENTF_SCANCODE | up } },
    };

    private static User32.Input Unicode(char c, uint up) => new()
    {
        Type = User32.INPUT_KEYBOARD,
        Union = new User32.InputUnion { Keyboard = new User32.KeyboardInput { Scan = c, Flags = User32.KEYEVENTF_UNICODE | up } },
    };

    internal static void Log(string line) => File.AppendAllText(LOG, $"{DateTime.Now:HH:mm:ss.fff} {line}{Environment.NewLine}");
}
```
`Probe/SasProbe.cs`:
```csharp
using KeyRelay.Service.Native;

namespace KeyRelay.Service.Probe;

/// <summary>Risk 4 probe: SendSAS must be called by the service itself (asUser false).</summary>
internal sealed class SasProbe : BackgroundService
{
    private static readonly string SAS_FILE = Path.Combine(AgentProbe.DIR, "probe-sas");

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        Directory.CreateDirectory(AgentProbe.DIR);
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(1));
        while (await timer.WaitForNextTickAsync(stoppingToken).ConfigureAwait(false))
        {
            if (!File.Exists(SAS_FILE)) continue;
            File.Delete(SAS_FILE);
            Sas.SendSAS(false);
            AgentProbe.Log("service: SendSAS(false) called");
        }
    }
}
```
`Program.cs`:
```csharp
using KeyRelay.Service.Probe;
using KeyRelay.Service.Session;

if (args.Contains("--agent")) return AgentProbe.Run();

var builder = Host.CreateApplicationBuilder(args);
builder.Services.AddWindowsService(options => options.ServiceName = "KeyRelay");
builder.Services.AddHostedService<AgentSupervisor>();
builder.Services.AddHostedService<SasProbe>();
await builder.Build().RunAsync().ConfigureAwait(false);
return 0;
```

- [ ] **Step 5: Derle, test et, yayınla**

Run:
```
dotnet test D:\Repos\KeyRelay\windows\KeyRelay.slnx
dotnet publish D:\Repos\KeyRelay\windows\src\KeyRelay.Service -c Release -r win-x64 --self-contained -p:PublishSingleFile=true -o D:\Repos\KeyRelay\artifacts\probe
```
(homepc'de yalnız .NET 8 runtime var; self-contained zorunlu.)

- [ ] **Step 6: homepc'ye kur (controller; geri alma bilgisi kaydedilir)**

Bu adımdaki ve aşağıdaki komutlar PC'de yönetici komut isteminde (cmd) çalışır. `C:\KeyRelayProbe` klasörünü oluştur (`mkdir C:\KeyRelayProbe`), `artifacts\probe\KeyRelay.Service.exe`'yi PC'de bu klasöre kopyala, sonra:
```
reg query HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Policies\System /v SoftwareSASGeneration
```
Önceki değeri (veya "yok") sonuç belgesine yaz. Sonra:
```
reg add HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Policies\System /v SoftwareSASGeneration /t REG_DWORD /d 1 /f & sc.exe create KeyRelayProbe binPath= C:\KeyRelayProbe\KeyRelay.Service.exe start= demand obj= LocalSystem & sc.exe start KeyRelayProbe
```
Beklenen: birkaç saniye içinde `C:\ProgramData\KeyRelay\probe-agent.log` içinde `agent start ... session=1` ve `desktop: Default`.

- [ ] **Step 7: Kullanıcıyla deneme (TeamViewer'dan izlenir)**

Ön koşul (adım 2, 4 ve Step 8'den önce): kullanıcı TeamViewer'ın kilit/giriş ekranını gösterdiğini (gözetimsiz erişim) teyit eder; PC kilitliyken de yönetici olarak tetik dosyası yazılabilir, `shutdown /r` ve servis silme yapılabilir. Teyit yoksa bu adımlar atlanır ve sonuçta "denenmedi" yazılır.
Tetik komutları PC'de yönetici komut isteminde (cmd) çalışır. Her adımda `type C:\ProgramData\KeyRelay\probe-agent.log & type C:\ProgramData\KeyRelay\probe-service.log` ile log alınır:
1. Kullanıcı Not Defteri'ni öne alır; `echo abc> C:\ProgramData\KeyRelay\probe-type.txt` → Not Defteri'nde `abc` görünür.
2. Kullanıcı PC'yi Başlat menüsünden kilitler → log `desktop: Winlogon`. Kullanıcı şifre alanını açar (bir tuşa/tıklamaya basar); `echo test1> ...probe-type.txt` → şifre kutusunda 5 nokta görünür mü (UNICODE yöntemi)? Kullanıcı kutuyu temizler; ardından `type nul> C:\ProgramData\KeyRelay\probe-scan` → 4 nokta görünür mü (SCANCODE yöntemi — ürünün kullanacağı yol; 2(b) kararı bu sonuca dayanır)? Kullanıcı kutuyu temizler ve kendi şifresiyle (TeamViewer üzerinden) girer.
3. `type nul> C:\ProgramData\KeyRelay\probe-sas` → güvenlik ekranı (Kilitle / Kullanıcı değiştir / Oturumu kapat / Görev Yöneticisi) açılıyor mu; Esc ile kapat.
4. `type nul> ...probe-lock` → PC kilitleniyor mu.
5. UAC: kullanıcı yönetici olarak bir CMD açmayı dener (güvenli masaüstünde UAC istemi) → log masaüstü adı; `echo x> ...probe-type.txt` → UAC'de bir etki var mı (istem şifre istemiyorsa gözle "x" görülmez; masaüstü adı ve `sent` değeri kaydedilir).
Her adımın sonucunu "Risk 4" altında yaz: beklenen, gözlenen, log satırları.

- [ ] **Step 8: Review Focus 5 — oturum açılmadan**

Kullanıcı onay verirse PC'de: `sc.exe config KeyRelayProbe start= auto & shutdown /r /t 5`. Açılınca (kimse oturum açmadan) log: `agent start ... session=1`, `desktop: Winlogon`; `echo test2> ...probe-type.txt` ve ayrı olarak `type nul> ...probe-scan` → giriş ekranının şifre kutusunda her iki yöntem için nokta görünür mü (kullanıcı TeamViewer'dan bakar); sonuçlar yönteme göre ayrı kaydedilir, GEÇTİ yalnız SCANCODE sonucuna dayanır. Ardından `sc.exe config KeyRelayProbe start= demand`.

- [ ] **Step 9: Temizlik**

```
sc.exe stop KeyRelayProbe & sc.exe delete KeyRelayProbe & rmdir /s /q C:\KeyRelayProbe
```
`SoftwareSASGeneration`'ı Step 6'da kaydedilen önceki haline getir (yoksa `reg delete ... /v SoftwareSASGeneration /f`, varsa eski değerle `reg add`). `C:\ProgramData\KeyRelay\probe-*.log` sonuç belgesine aktarıldıktan sonra silinir.

- [ ] **Step 10: Commit**

```
git -C D:/Repos/KeyRelay add -A
git -C D:/Repos/KeyRelay commit -m "feat(windows): servis oturuma ajan başlatır, kilit ekranı denemesi" -m "- servis, açık oturumda tuş basabilen bir yardımcı süreç başlatır
- kilit ekranı, güvenlik ekranı ve kilitleme davranışı denendi ve belgelendi" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Sonuçları tasarıma işle

**Files:**
- Modify: `docs/spikes/2026-10-risk-results.md` (özet tablo)
- Modify: `docs/superpowers/specs/2026-10-07-keyrelay-design.md` (bitiş 2(b), Riskler bölümü, gerekirse bileşenler)

**Interfaces:**
- Produces: P2 ve P3 planlarının dayanacağı, risk sonuçlarıyla güncellenmiş spec.

- [ ] **Step 1: Özet tablo**

`docs/spikes/2026-10-risk-results.md` başına:

| Risk | Sonuç | Kanıt (bölüm) | Spec etkisi |
|---|---|---|---|

her risk için bir satır; "Sonuç" GEÇTİ/KALDI/KISMEN; "Spec etkisi" yok veya hangi yedeğin seçildiği.

- [ ] **Step 2: Spec'i güncelle**

- Bitiş maddesi 2(b): Risk 4 sonucuna göre tek okumaya indir (ya "KeyRelay kanalından yazılır" ya "klavye TeamViewer'a bırakılır").
- Riskler bölümü: her risk için "Doğrulandı (tarih, kanıt)" veya seçilen yedek.
- Bir yedek seçildiyse ilgili bileşen metnini yedeğe göre değiştir.
- Kararlar'a her sonucu tek satır ekle.

- [ ] **Step 3: Kapıyı yeniden aç (spec değiştiyse)**

Spec'te yalnız "Doğrulandı" notu ve 2(b)'nin tek okumaya indirilmesi değiştiyse tasarım kapısı yeniden açılmaz (karar spec'te önceden koşullu yazılmıştı). Bir yedek seçildiyse veya bileşen değiştiyse controller tasarım kapısını yeniden açar.

- [ ] **Step 4: Commit**

```
git -C D:/Repos/KeyRelay add -A
git -C D:/Repos/KeyRelay commit -m "docs(spec): risk doğrulama sonuçları tasarıma işlendi" -m "- beş riskli varsayımın cihazlardaki sonuçları tasarıma yansıtıldı" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Sonraki planlar (bu planın kapsamı dışında)

- **P2 Android:** `:core` protokol kodlayıcı/çözücü (test vektörleri, `tls` kanalı), durum makinesi (kombinasyonlar, held kuralı, sendTimeout, tek yazıcı thread), yardımcıda TLS istemcisi ve sabitlenmiş SPKI, AIDL `IKeyRelayHelper` + ContentProvider attach + `linkToDeath` + arayan uid denetimi, cihaz token'ının Android Keystore ile şifreli DataStore'da saklanması, ayarlanabilir hedef paketler ve süreler, `RelayService`, QS tile, Compose arayüz (Durum, PC'ler, Hedef uygulamalar, Klavyeler, Ayarlar, Kurulum sihirbazı; NSD ile eşleştirme portu bulma, bildirimde RemoteInput), Otomatik Engelleyici algılama.
- **P3 Windows:** `KeyRelay.Protocol` (çerçeve, test vektörleri `tls` + `pipe`, keycode→scancode), TLS dinleyici, eşleştirme ve cihaz kaydı, kontrol pipe'ı `\\.\pipe\KeyRelay.Control` (Tray↔servis: durum, eşleştirme, cihaz iptali, ayarlar, log), `OnSessionChange` ile ajan yönetimi, ajan pipe protokolü (RELEASE_ALL, DESKTOP, açılışta modifier temizliği), `install|uninstall`, WinUI 3 Tray, Inno Setup, prob kodunun silinmesi.
- **P4 Geçiş ve bitiş:** prototipin kaldırılması, Funnel `--tcp`, bitiş çizgisi ölçümleri, release iş akışı, `docs/architecture.md` ve `docs/setup.md`.
