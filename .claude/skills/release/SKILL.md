---
name: release
description: Use when writing commit messages, deciding a version bump, cutting or fixing a KeyRelay release, or touching the GitHub workflows (CI, release-please, publish job).
---

# Versions and releases

Versions follow semantic versioning and are computed from commit messages by release-please; nobody edits version
numbers by hand.

## Commit messages

Conventional Commits with a short, lowercase Turkish description that says what changed for the user, not how:

```text
fix(windows): kilit ekranında tuş tekrarı durur

- tablet bağlantısı kopunca basılı tuş pc'de takılı kalmıyor
```

| Type | Effect |
|---|---|
| `feat` | minor bump (0.x: minor) |
| `fix`, `perf` | patch bump |
| `feat!` / `BREAKING CHANGE:` | while 0.x: minor; 1.0.0 is set deliberately with `Release-As: 1.0.0` |
| `docs`, `refactor`, `test`, `ci`, `build`, `chore` | no release on their own |

Scopes in use: `android`, `windows`, `deps`, `main` (release commits).

## Single source of the version

`version.txt` at the repository root. Gradle derives `versionName` and `versionCode` (major*10000+minor*100+patch),
`windows/Directory.Build.props` sets the assembly version, and `installer/build.ps1` passes it to Inno Setup. Never
write a version anywhere else.

## How a release happens

1. Pushes to `main` run `.github/workflows/release.yml`; release-please opens or updates a "release x.y.z" PR with the
   new `version.txt` and CHANGELOG entry.
2. Merging that PR creates the tag and GitHub release, and the `publish` job in the same workflow builds the installer,
   the zip and the APK and uploads them. It lives in the same workflow because a tag created with `GITHUB_TOKEN` does
   not trigger other workflows.
3. If `publish` fails, fix the cause, push the fix, then rerun the asset build for the existing tag:
   `gh workflow run release -f tag=vX.Y.Z` (uploads with `--clobber`).

## APK signing key

Android rejects an update signed with a different key (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), which forces an
uninstall and a fresh wizard + PC pairing. So every published APK must carry the same key:

- `android/app/build.gradle.kts` signs the debug build type (the one releases ship) with the keystore at
  `KEYRELAY_KEYSTORE` (alias `keyrelay`, password `KEYRELAY_KEYSTORE_PASSWORD`). Without the variables it falls back
  to the machine's own debug key, and such an APK cannot be installed over a release build.
- Both workflows decode the `KEYRELAY_KEYSTORE_BASE64` secret into `$RUNNER_TEMP`. `android.yml` skips it when the
  secret is empty (fork PRs); the `publish` job fails instead, so never "fix" it by removing that check.
- The keystore never goes into the repository. Key generation and `gh secret set` commands are in `CONTRIBUTING.md`.

## CI notes

- `dotnet test` must run from `windows/` so `global.json` selects the Microsoft.Testing.Platform runner.
- `android/gradlew` must keep its executable bit; when moving files between machines, use `git diff --binary` /
  `git apply`, not archive extraction, so modes survive.
- Release PRs are opened by GitHub Actions, so their CI runs wait for a maintainer to approve them.
