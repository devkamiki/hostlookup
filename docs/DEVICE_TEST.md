# Device verification — 2026-09-05

## Build and package

- OpenJDK 17, Gradle 9.3.1, Android SDK 37, NDK 28.2.13676358,
  official Rust 1.95.0 compiler and matching Android standard libraries.
- `assembleRelease lintRelease assembleDebug` passed. After adding ABI filters,
  `assembleRelease lintRelease` passed again: 0 errors and 4 warnings
  (target SDK, Gradle/coroutines update suggestions, and Kotlin extension style).
- F-Droid 2.4.3 source scan: 0 findings. Final production APK scan: exit 0,
  no detected non-free classes or extra signing-block findings.
- Production APK contains `libhostlookup.so` for arm64-v8a and x86_64 and the
  source-built `org.rustls.platformverifier.CertificateVerifier` class.
  No 32-bit ABIs remain in the APK.
- The 196 resolved Android Rust packages all declare licenses, with no obviously
  non-free declaration found. This is not a complete transitive license audit.

## Connected device

PGW110, Android 16, ARM64, 1080×2412 display. Google Play Services is installed.
The existing `de.obsp.hostlookup` 0.1.0 installation was preserved. Its debug
signing certificate differs from the build machine's certificate.

Installed a release-mode copy of 0.2.0 (versionCode 2) as
`de.obsp.hostlookup.devicetest`, signed with the local debug key. A temporary
Gradle init script changes only the release application ID suffix and signing
configuration. It does not enable debugging or modify the verifier. This test
build completed with Gradle offline and Cargo network access disabled, using
previously built native libraries and cached dependencies.
All DEX files and native libraries are byte-for-byte identical between the
production APK and the installed test APK. The normal production output was
restored after testing and matches the scanned unsigned APK exactly.

## Observations

| Check | Result |
| --- | --- |
| Installation and launch | Passed; native library and verifier initialize without a crash. |
| `eindhoven.nl` lookup | 26 records, 3 responding providers, 20.8 seconds in the observed lookup. |
| Resolver selection | Cloudflare, Google, and Quad9 listed with 26 records each. Selecting Cloudflare updates the result source and per-provider TTLs. |
| NS detail | `nsauth1.bit.nl`, `213.136.12.51`, AS12859, BIT B.V., prefix `213.136.0.0/19`, and Duiven location displayed. |
| DNSSEC detail | NSEC3PARAM record detail opens with TTL, response timing, and provider names. |
| WHOIS/network | 9 IP addresses enriched. Mail infrastructure includes Microsoft AS8075 and prefix `52.96.0.0/12`, with location information. |
| Back navigation | Returns from detail/WHOIS to results; scroll position is preserved. Returns from results to home. |
| Recent lookups | `eindhoven.nl` appears on the home screen. |
| Process log | No AndroidRuntime fatal exception or native crash observed. Device renderer/property-access warnings were present. |

DNS and network data change over time. Successful enrichment verifies the
combined native/Android fallback flow; it does not prove every native RIPEstat
request succeeded independently. No packet capture was performed.

## Artifacts

Four unedited device screenshots are in
`fastlane/metadata/android/en-US/images/phoneScreenshots/`:

1. Home and recent lookups.
2. Overall results for eindhoven.nl.
3. Cloudflare NS record and network details.
4. WHOIS/network details for mail-server addresses.

Country names follow the device locale. The screenshots retain the device's
font size, status bar, and navigation bar.

Local build outputs (ignored by Git):

- `app/build/verification/hostlookup-release-unsigned.apk`: production ID,
  unsigned; SHA-256 `f398e14a5683499b40b594780dbceaf9f4d1343b72458a9f1f73196bcfa560c2`.
- `app/build/verification/hostlookup-device-test.apk`: separately installable
  test copy; SHA-256 `51d0df5b44153c623c13ac580b818fd4649601086d3f40c97057d1ffc229470c`.
- `app/build/reports/lint-results-release.html`: lint report.
- `app/build/reports/rust-license-inventory.json`: declared Rust licenses.
- `app/build/verification/source-scan.log`, `apk-scan.log`, and
  `device-warnings.log`: local verification logs.

## Remaining coverage

Android 8, x86_64 runtime, devices without Play Services, network-failure cases,
and clean offline native builds have not been tested. A full fdroiddata build,
release version/tag, source availability/domain confirmation, and maintainer
anti-feature review remain outstanding. Nothing was published or submitted.
