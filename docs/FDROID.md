# F-Droid submission review

Reviewed on 2026-09-05 against the [submission guide](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/)
and [inclusion policy](https://f-droid.org/docs/Inclusion_Policy/).
The app builds locally and passed source/APK scans with fdroidserver 2.4.3.
Release-mode device checks and screenshots are complete. An isolated fdroiddata
build and acceptance have not been verified. No submission has been sent.

## Repository findings

| Area | Finding |
| --- | --- |
| License | Root LICENSE is Apache-2.0, with copyright in NOTICE. Vendored verifier includes upstream Apache-2.0 and MIT texts. |
| Source availability | Intended public URL is https://github.com/devkamiki/hostlookup. Public availability confirmed by the developer. |
| Application ID | `de.obsp.hostlookup`; keep this stable. Developer confirmed control of obsp.de. |
| Dependencies | Direct Gradle dependencies use Google Maven/Maven Central; no proprietary SDK, ads, analytics, updater, or API key found in app source. F-Droid source/APK scans passed. Declared licenses for 196 resolved Android Rust packages were inspected; full transitive license-text review remains outstanding. |
| Binary library | Removed checked-in verifier AAR; its Kotlin component now builds from pinned, unmodified upstream source. Cargo's Android support crate includes an unused AAR; disclose it to the packager and ensure it is not used or packaged. |
| Native code | Both arm64-v8a and x86_64 libraries built from Rust source. APK ABI filters now match these targets, excluding unsupported 32-bit AndroidX libraries. Cargo uses `--locked`; Gradle tracks Cargo.lock. |
| Toolchain | Command-line Gradle and Rust; use OpenJDK. SDK 37, NDK 28.2.13676358, AGP 9.1.1, Gradle 9.3.1. Rust 1.95.0 was tested; pin it in the packaging recipe. |
| Releases | Prepared 0.2.1 (versionCode 3), with a new release tag planned after verification. Existing tags are preserved. |
| Store listing | Fastlane title, descriptions, version-2 changelog, icon, and four real device screenshots are present. |

The policy requires FLOSS dependencies and tools and an inspectable source build.
The removed AAR was outside the policy's trusted Maven binary distribution path.
Replacing it addresses that source-build issue; it does not certify all transitive
dependencies or guarantee inclusion.

## Network-service disclosures

The app sends DNS queries to mhost's fixed public resolver list over UDP, TCP,
DNS-over-TLS, and DNS-over-HTTPS. UDP and TCP queries are unencrypted. NS/MX address enrichment also uses Android's DNS resolver. Native and Java
WHOIS/network/location requests use RIPEstat, including its `maxmind-geo-lite`
endpoint. The recipients see the requests and public source IP. Android's
certificate verifier may make revocation requests. There is no app setting to
replace the public resolvers or RIPEstat service.

Ask the packager to review `TetheredNet` for the fixed enrichment service and
`NonFreeNet` for the public providers and geolocation service. These are candidate
labels, not a finding that RIPEstat itself is proprietary. DNS results remain
useful without successful enrichment. Service use alone does not establish an
analytics SDK. Disclose this behavior for review under F-Droid's
[Anti-Features definitions](https://f-droid.org/en/docs/Anti-Features/).

## Remaining submission work

1. Public source availability and domain authority have been confirmed by the
   developer. Continue responding to F-Droid review and maintenance requests.
2. Local build, lint, and Android 16 device checks passed (see below). Additional
   coverage on Android 8, x86_64, and a device without Google Play Services remains
   untested. Repeat relevant checks for any release changes.
3. Resolve and audit Gradle and Cargo dependencies, including license texts and
   native build-script inputs. Source/APK scanners passed locally; rerun them
   through fdroiddata CI. Prefetch Cargo dependencies before the isolated build
   and verify a clean native build offline. The cached test build ran offline.
4. Four real screenshots are in
   `fastlane/metadata/android/en-US/images/phoneScreenshots/`. The listing icon
   is rendered from the existing launcher artwork. Version 0.2.1 / code 3 and its changelog are prepared. Commit the verified
   release and publish its new tag.
5. Prepare `metadata/de.obsp.hostlookup.yml` in a fork of fdroiddata. Use the
   public repository URL, Apache-2.0 license, Connectivity category, `subdir: app`,
   `gradle: [yes]`, NDK 28.2.13676358, and the new release commit. The Rust
   setup/prefetch steps need a tested, pinned toolchain and both Android targets.
   Pass the installed SDK/NDK paths to the native script. Discuss candidate
   anti-features with the packager. Enable tag-based update checks after the
   initial build works.
6. Run `fdroid rewritemeta de.obsp.hostlookup`, `fdroid lint de.obsp.hostlookup`,
   and `fdroid build de.obsp.hostlookup` in the fdroiddata checkout. Submit a
   merge request once its CI passes, or open a packaging request in
   [Requests For Packaging](https://gitlab.com/fdroid/rfp/-/issues) with this review.

Reproducible builds with the developer's signing key are encouraged by the guide
but optional. Decide on signing before the first F-Droid publication; switching
keys later affects installed users.

## Validation performed here

See [the device test report](DEVICE_TEST.md) for the device, observations, and
artifact paths. The production release and a separate release-mode test copy
built with OpenJDK 17, Gradle 9.3.1, SDK 37, NDK 28.2.13676358, and Rust 1.95.0.
Release lint completed with 0 errors and 4 advisory warnings. Source scans and
APK scans passed with fdroidserver 2.4.3 and refreshed SUSS signatures.

The scanner ran against a temporary snapshot of tracked and unignored source
files, excluding generated build outputs. It removed the Gradle wrapper from
that snapshot as expected. The installed Nix Python magic package had a tab
indentation error; only whitespace in a temporary copy was repaired to run the
unmodified scanner. This was a local scan, not an fdroiddata CI build.

Upstream source/license equality, Cargo crate checksum, metadata lengths, shell
syntax, and the repository diff were also checked. The APK contains the verifier
class and both supported native libraries, with no advertised 32-bit ABI.
