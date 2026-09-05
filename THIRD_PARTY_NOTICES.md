# Third-party notices

HostLookup uses open-source dependencies distributed under their respective
licenses. The authoritative dependency versions are recorded in Gradle build
files and `native/Cargo.lock`.

Notable components include:

- [mhost 0.11.3](https://github.com/lukaspustina/mhost), licensed under
  Apache-2.0 or MIT.
- [rustls-platform-verifier](https://github.com/rustls/rustls-platform-verifier),
  licensed under Apache-2.0 or MIT. Its Android Kotlin component is vendored
  under [third_party/rustls-platform-verifier-android](third_party/rustls-platform-verifier-android/README.md)
  with the upstream licenses and exact source revision.
- [ndk-context](https://github.com/rust-windowing/android-ndk-rs), licensed
  under Apache-2.0 or MIT.
- [AndroidX](https://github.com/androidx/androidx) and Jetpack Compose,
  primarily licensed under Apache-2.0.

The inclusion of these notices does not imply endorsement by their authors.
