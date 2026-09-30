# Vendored native runtime — provenance

`arm64-v8a/libproot.so` is AndCode's PRoot runner
(`libopencode_android_proot.so`, PRoot 5.4.0, NDK-built for Android), renamed
to `libproot.so` so it installs to `nativeLibraryDir` under that name
(`RuntimeManager.installBundledProot()` looks for `libproot.so` first).

- Source: https://github.com/yuga-hashimoto/and-code
  release v1.2.25 (`and-code-v1.2.25-fdroid-release.apk`,
  `lib/arm64-v8a/libopencode_android_proot.so`), MIT licensed.
- Verified working: `--version` prints PRoot 5.4.0; ran Alpine 3.24.1
  minirootfs (`-r`, binds, `/bin/sh`) on aarch64 Linux; `apk add nodejs`
  + the `extracted-intelligence` bridge served 14 tools on 127.0.0.1.
- Upstream PRoot: https://github.com/cesanta/prd (GPLv2) / proot upstream
  https://github.com/proot-me/proot (GPLv2). AndCode's build/licensing notes
  live in its THIRD_PARTY_NOTICES.md — mirror any required attribution there
  before a store release, and confirm the copyleft implications for your
  distribution with counsel. (Our own Kotlin/TS code in this repo is MIT;
  that does not extend to this binary.)
- To refresh: download a newer AndCode release APK, extract
  `lib/arm64-v8a/libopencode_android_proot.so`, replace this file.
