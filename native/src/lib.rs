use std::collections::HashSet;
#[cfg(target_os = "android")]
use std::ffi::c_void;
#[cfg(target_os = "android")]
use std::sync::OnceLock;
use std::time::{Duration, Instant};

use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JClass, JString};
#[cfg(target_os = "android")]
use jni::objects::{Global, JObject, Reference};
use jni::sys::jstring;
use jni::EnvUnowned;
use mhost::resolver::{MultiQuery, ResolverGroupBuilder};
use mhost::services::whois::{self, QueryType, WhoisClient, WhoisClientOpts};
use mhost::{IpNetwork, RecordType};
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

fn run_lookup(domain: &str) -> Result<String, String> {
    if domain.is_empty() || domain.len() > 253 {
        return Err("Enter a valid domain name".to_owned());
    }

    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(4)
        .enable_all()
        .build()
        .map_err(|error| format!("Unable to start lookup engine: {error}"))?;

    runtime.block_on(async move {
        let started = Instant::now();
        let resolvers = ResolverGroupBuilder::new()
            .all_predefined()
            .timeout(Duration::from_secs(5))
            .build()
            .await
            .map_err(|error| format!("Unable to create mhost resolvers: {error}"))?;

        let query = MultiQuery::multi_record(domain, RECORD_TYPES.to_vec())
            .map_err(|error| format!("Invalid domain name: {error}"))?;
        let lookups = resolvers
            .lookup(query)
            .await
            .map_err(|error| format!("DNS lookup failed: {error}"))?;

        let ips: HashSet<_> = lookups.ips().into_iter().map(IpNetwork::from).collect();
        let (whois_result, whois_error) = if ips.is_empty() {
            (None, None)
        } else {
            let query = whois::MultiQuery::from_iter(
                ips,
                [QueryType::GeoLocation, QueryType::NetworkInfo, QueryType::Whois],
            );
            let client = WhoisClient::new(WhoisClientOpts::new(8, Duration::from_secs(8), false));
            match client.query(query).await {
                Ok(responses) => (Some(responses), None),
                Err(error) => (None, Some(format!("RIPEstat request failed: {error}"))),
            }
        };

        serde_json::to_string(&json!({
            "domain": domain,
            "elapsed_ms": started.elapsed().as_millis(),
            "mhost_version": "0.11.3",
            "mode": "mhost -p l --all -w",
            "lookups": lookups,
            "whois": whois_result,
            "whois_error": whois_error,
        }))
        .map_err(|error| format!("Unable to encode results: {error}"))
    })
}

#[cfg(test)]
mod tests {
    use super::run_lookup;

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
