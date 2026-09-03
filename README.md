# HostLookup

A native Android DNS explorer built around the same Rust library as `mhost` 0.11.3. It runs the equivalent of:

```text
mhost -p l --all -w <domain>
```

The app queries all of mhost's predefined UDP resolvers for all 25 supported record types, aggregates the responses, and enriches discovered IP addresses with WHOIS/network information. Its navigation follows the useful parts of nslookup.io: domain search, overall results, resolver switching, record-type sections, and focused detail screens.

The interface is built with Jetpack Compose and Material 3 Expressive. It uses dynamic color on Android 12+, the expressive motion scheme and loading indicator, large mobile touch targets, asymmetric shapes, and preserved scroll position across record and WHOIS detail screens.

Address-bearing records include compact infrastructure context inspired by full
DNS lookup tools: country flags, locations, network prefixes, and autonomous
system numbers. For `NS` and `MX` records, HostLookup resolves each unique
nameserver or mail exchanger and enriches those addresses alongside direct `A`
and `AAAA` answers.

## Build

Requirements:

- JDK 17+
- Android SDK 37 (target SDK remains 36)
- Android Gradle Plugin 9.1.1 / Gradle 9.3.1
- Android NDK `28.2.13676358`
- Rust targets `aarch64-linux-android` and `x86_64-linux-android`

```bash
export JAVA_HOME=/home/user/.jdks/jbr-21.0.11
export ANDROID_SDK_ROOT=/home/user/Android/Sdk
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Privacy

DNS record queries are sent directly from the phone to mhost's predefined public resolvers. Nameserver and mail-server address enrichment uses the phone's configured DNS resolver. WHOIS/network enrichment is requested from the same RIPEstat endpoints as mhost's `--whois` behavior. The app retains mhost's native result and fills missing Android responses through the platform HTTPS stack. No HostLookup-owned server or analytics service is used.

The small `rustls-platform-verifier` Android AAR in `app/libs` is the JVM half of mhost's HTTPS certificate verifier. It is bundled from the corresponding Rust crate so RIPEstat requests use Android's system trust decisions.

## License

HostLookup is licensed under the [Apache License 2.0](LICENSE). Third-party
attributions and license information are listed in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Important notice and disclaimer

I VIBE-CODED THIS. COMPLETELY. EVERY SINGLE LINE OF THIS PROJECT IS WRITTEN BY GPT 5.6 SOL AND OPENCLAW (EXCLUDING THIS ONE). USE IT AT YOUR OWN RISK!
