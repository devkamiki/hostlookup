# Android certificate verifier source

Source: https://github.com/rustls/rustls-platform-verifier

Revision: `996b1c903491641b17b3c9afb65d1352f6fc6b76` (tag `v/0.7.0`).
The Kotlin source and manifest are copied unchanged from
`android/rustls-platform-verifier/src/main/` at that revision, matching the
Rust verifier 0.7.0 in `native/Cargo.lock`.

Licensed under Apache-2.0 OR MIT; both upstream license files are included.
HostLookup supplies the Gradle build and consumer keep rules. AGP's built-in
Kotlin support compiles this component from source. `BuildConfig.TEST` is always
false, including debug builds, so the verifier uses Android's system trust store.

This replaces the precompiled Android 0.1.1 AAR previously stored in `app/libs`.
The Cargo Android support crate still appears in Cargo.lock, but its AAR is not
used by Gradle or packaged into the app. When updating the Rust verifier, review
and update this source from the corresponding upstream revision, including JNI
signatures, license files, and keep rules.
