use std::collections::HashSet;
#[cfg(target_os = "android")]
use std::ffi::c_void;
use std::sync::OnceLock;
use std::time::{Duration, Instant};

use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JClass, JString};
#[cfg(target_os = "android")]
use jni::objects::{Global, JObject, Reference};
use jni::sys::jstring;
use jni::EnvUnowned;
use mhost::nameserver::predefined::PredefinedProvider;
use mhost::nameserver::{NameServerConfig, Protocol};
use mhost::resolver::{MultiQuery, ResolverGroup, ResolverGroupBuilder, ResolverOpts};
use mhost::RecordType;
use serde_json::json;

const RECORD_TYPES: [RecordType; 25] = [
    RecordType::A,
    RecordType::AAAA,
    RecordType::ANAME,
    RecordType::CAA,
    RecordType::CNAME,
    RecordType::DNSKEY,
    RecordType::DS,
    RecordType::HINFO,
    RecordType::HTTPS,
    RecordType::MX,
    RecordType::NAPTR,
    RecordType::NSEC,
    RecordType::NSEC3,
    RecordType::NSEC3PARAM,
    RecordType::NULL,
    RecordType::NS,
    RecordType::OPENPGPKEY,
    RecordType::PTR,
    RecordType::RRSIG,
    RecordType::SOA,
    RecordType::SRV,
    RecordType::SSHFP,
    RecordType::SVCB,
    RecordType::TLSA,
    RecordType::TXT,
];

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_hostlookup_app_DnsBridge_initialize(
    mut unowned_env: EnvUnowned,
    _class: JClass,
    context: JObject,
) {
    unowned_env
        .with_env(|env| {
            // hickory-resolver (pulled in by mhost's HTTP client) obtains Android's
            // networking context through ndk-context. Keep a global JNI reference
            // alive for the lifetime of this process before any Tokio workers start.
            static ANDROID_CONTEXT: OnceLock<Global<JObject<'static>>> = OnceLock::new();

            if ANDROID_CONTEXT.get().is_none() {
                let vm = env.get_java_vm()?;
                let global_context = env.new_global_ref(&context)?;
                let raw_context = global_context.as_raw();

                if ANDROID_CONTEXT.set(global_context).is_ok() {
                    unsafe {
                        ndk_context::initialize_android_context(
                            vm.get_raw().cast::<c_void>(),
                            raw_context.cast::<c_void>(),
                        );
                    }
                }
            }

            rustls_platform_verifier::android::init_with_env(env, context)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_io_hostlookup_app_DnsBridge_lookup(
    mut unowned_env: EnvUnowned,
    _class: JClass,
    input: JString,
) -> jstring {
    unowned_env
        .with_env(|env| -> jni::errors::Result<jstring> {
            let domain = input.mutf8_chars(env)?.to_str().into_owned();
            let payload = run_lookup(domain.trim())
                .unwrap_or_else(|message| json!({ "error": message }).to_string());
            Ok(env.new_string(payload)?.into_raw())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

fn runtime() -> Result<&'static tokio::runtime::Runtime, String> {
    static RUNTIME: OnceLock<tokio::runtime::Runtime> = OnceLock::new();
    if let Some(runtime) = RUNTIME.get() {
        return Ok(runtime);
    }
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(4)
        .enable_all()
        .thread_name("hostlookup-dns")
        .build()
        .map_err(|error| format!("Unable to start lookup engine: {error}"))?;
    let _ = RUNTIME.set(runtime);
    RUNTIME
        .get()
        .ok_or_else(|| "Unable to start lookup engine".to_owned())
}

fn shared_resolvers() -> Result<&'static ResolverGroup, String> {
    static GROUP: OnceLock<ResolverGroup> = OnceLock::new();
    if let Some(group) = GROUP.get() {
        return Ok(group);
    }
    let group = runtime()?.block_on(build_resolvers())?;
    let _ = GROUP.set(group);
    GROUP
        .get()
        .ok_or_else(|| "Unable to create mhost resolvers".to_owned())
}

fn build_resolvers() -> impl std::future::Future<Output = Result<ResolverGroup, String>> {
    let mut resolver_opts = ResolverOpts::default();
    // Hickory treats this as extra attempts after the first send. Zero keeps a single try.
    resolver_opts.retries = 0;
    resolver_opts.max_concurrent_requests = 16;
    resolver_opts.timeout = Duration::from_millis(2_000);
    // A missing record type must not cancel the rest of that provider.
    // A dead endpoint still aborts its own remaining queries after one timeout.
    resolver_opts.abort_on_error = false;
    resolver_opts.abort_on_timeout = true;

    async move {
        ResolverGroupBuilder::new()
            .nameservers(selected_nameservers())
            .resolver_opts(resolver_opts)
            .max_concurrent_servers(24)
            .build()
            .await
            .map_err(|error| format!("Unable to create mhost resolvers: {error}"))
    }
}

// One IPv4 socket per transport. Extra addresses duplicate provider answers, and
// IPv6 is included only when the device has a global route so blackholed v6
// does not add a timeout to every lookup.
fn selected_nameservers() -> Vec<NameServerConfig> {
    let include_v6 = device_has_global_ipv6();
    let mut selected = Vec::new();
    for provider in PredefinedProvider::all() {
        let mut seen_v4 = HashSet::new();
        let mut saw_v6_udp = false;
        for config in provider.configs() {
            if config.ip_addr().is_ipv6() {
                if include_v6 && !saw_v6_udp && config.protocol() == Protocol::Udp {
                    saw_v6_udp = true;
                    selected.push(config);
                }
                continue;
            }
            if seen_v4.insert(config.protocol()) {
                selected.push(config);
            }
        }
    }
    selected
}

fn device_has_global_ipv6() -> bool {
    let Ok(text) = std::fs::read_to_string("/proc/net/if_inet6") else {
        return false;
    };
    text.lines().any(|line| {
        let addr = line.split_whitespace().next().unwrap_or("");
        // 2000::/3 global unicast. Link-local, loopback, and ULA are ignored.
        matches!(addr.as_bytes().first(), Some(b'2') | Some(b'3'))
    })
}

fn run_lookup(domain: &str) -> Result<String, String> {
    if domain.is_empty() || domain.len() > 253 {
        return Err("Enter a valid domain name".to_owned());
    }

    let resolvers = shared_resolvers()?;
    let runtime = runtime()?;
    runtime.block_on(async move {
        let started = Instant::now();
        let query = MultiQuery::multi_record(domain, RECORD_TYPES.to_vec())
            .map_err(|error| format!("Invalid domain name: {error}"))?;
        let lookups = resolvers
            .lookup(query)
            .await
            .map_err(|error| format!("DNS lookup failed: {error}"))?;

        // Network enrichment runs on the platform HTTPS stack so it can fall
        // back when RIPEstat has no location, and so DNS results can render first.
        serde_json::to_string(&json!({
            "domain": domain,
            "elapsed_ms": started.elapsed().as_millis(),
            "mhost_version": "0.11.3",
            "mode": "mhost -p l --all",
            "lookups": lookups,
            "whois": null,
            "whois_error": null,
        }))
        .map_err(|error| format!("Unable to encode results: {error}"))
    })
}

#[cfg(test)]
mod tests {
    use std::net::IpAddr;

    use super::{run_lookup, selected_nameservers};

    #[test]
    fn selected_nameservers_keep_one_endpoint_per_transport() {
        let configs = selected_nameservers();
        assert!(
            configs.len() >= 24,
            "expected one IPv4 endpoint for udp, tcp, tls, and https on each provider, got {}",
            configs.len()
        );
        assert!(
            configs.len() <= 40,
            "duplicate provider addresses should be skipped, got {}",
            configs.len()
        );
        assert!(configs.iter().any(|config| matches!(config.ip_addr(), IpAddr::V4(_))));
    }

    #[test]
    fn lookup_payload_matches_android_contract() {
        let raw = run_lookup("example.com").expect("mhost lookup should complete");
        let payload: serde_json::Value = serde_json::from_str(&raw).expect("payload should be JSON");
        assert_eq!(payload["domain"], "example.com");
        assert_eq!(payload["mhost_version"], "0.11.3");
        assert!(payload["lookups"]["lookups"].as_array().is_some());
        assert!(!payload["lookups"]["lookups"].as_array().unwrap().is_empty());
    }
}
