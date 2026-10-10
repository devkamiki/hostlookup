package io.hostlookup.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import de.obsp.hostlookup.R;

/**
 * Network enrichment for discovered addresses.
 * RIPEstat supplies WHOIS, prefix, and ASN data. Location tries RIPEstat
 * MaxMind, then RIPEstat geoloc, then DB-IP and ipwho.is when those have no country.
 */
final class RipeStatClient {
    private static final String RIPE_BASE = "https://stat.ripe.net/data/";
    private static final String DBIP_BASE = "https://api.db-ip.com/v2/free/";
    private static final String IPWHO_BASE = "https://ipwho.is/";
    private static final int MAX_ADDRESSES = 24;
    private static final int MAX_BODY = 1_500_000;
    private static final long FAST_PHASE_SECONDS = 12;
    private static final long WHOIS_PHASE_SECONDS = 18;
    private static final ConcurrentHashMap<String, JSONObject> CACHE = new ConcurrentHashMap<>();

    private RipeStatClient() { }

    interface Update {
        void onProgress(List<JSONObject> entries, String error, boolean finished);
    }

    static final class Task {
        volatile boolean cancelled;
        private final Set<HttpURLConnection> live = ConcurrentHashMap.newKeySet();

        void cancel() {
            cancelled = true;
            for (HttpURLConnection connection : live) connection.disconnect();
        }

        private void track(HttpURLConnection connection) { live.add(connection); }

        private void untrack(HttpURLConnection connection) { live.remove(connection); }
    }

    static void enrich(Context context, Set<String> addresses, List<JSONObject> nativeEntries, Task task, Update update) {
        Map<String, JSONObject> entries = new ConcurrentHashMap<>();
        Set<String> present = Collections.synchronizedSet(new HashSet<>());
        for (JSONObject entry : nativeEntries) {
            String kind = firstKey(entry);
            if ("Error".equals(kind)) continue;
            JSONObject payload = entry.optJSONObject(kind);
            String resource = payload == null ? "" : withoutPrefix(payload.optString("resource"));
            if (!resource.isBlank() && payload != null && usableKind(kind, payload)) {
                entries.put(key(kind, resource), entry);
                present.add(key(kind, resource));
            }
        }

        List<String> selected = new ArrayList<>();
        for (String address : addresses) {
            if (address == null || address.isBlank() || !address.matches("[0-9a-fA-F:.]+")) continue;
            if (present.contains(key("NetworkInfo", address))
                    && present.contains(key("Whois", address))
                    && hasUsableGeo(entries, address)) {
                continue;
            }
            selected.add(address);
        }
        Collections.sort(selected);
        if (selected.size() > MAX_ADDRESSES) selected = new ArrayList<>(selected.subList(0, MAX_ADDRESSES));
        if (selected.isEmpty() || task.cancelled) {
            update.onProgress(snapshot(entries), "", true);
            return;
        }

        Map<String, String> prefixOf = new ConcurrentHashMap<>();
        Map<String, Extra> extras = new ConcurrentHashMap<>();
        String[] firstDetail = new String[1];
        boolean[] timedOut = new boolean[1];
        ExecutorService pool = Executors.newFixedThreadPool(8, runnable -> {
            Thread thread = new Thread(runnable, "hostlookup-enrich");
            thread.setDaemon(true);
            return thread;
        });
        try {
            ExecutorCompletionService<Void> fast = new ExecutorCompletionService<>(pool);
            List<Future<Void>> fastTasks = new ArrayList<>();
            for (String address : selected) {
                if (!present.contains(key("NetworkInfo", address))) {
                    fastTasks.add(fast.submit(() -> {
                        fetchNetwork(task, entries, prefixOf, address, firstDetail);
                        return null;
                    }));
                } else {
                    prefixOf.putIfAbsent(address, address);
                }
                if (!hasUsableGeo(entries, address)) {
                    fastTasks.add(fast.submit(() -> {
                        fetchGeo(task, entries, extras, address, firstDetail);
                        return null;
                    }));
                }
            }
            await(fast, fastTasks.size(), FAST_PHASE_SECONDS, task, timedOut, entries, update);
            for (Future<Void> future : fastTasks) future.cancel(true);
            if (task.cancelled) return;

            for (String address : selected) prefixOf.putIfAbsent(address, address);
            Map<String, List<String>> byPrefix = new LinkedHashMap<>();
            for (String address : selected) {
                if (present.contains(key("Whois", address)) || entries.containsKey(key("Whois", address))) continue;
                String prefix = prefixOf.getOrDefault(address, address);
                if (prefix.isBlank()) prefix = address;
                byPrefix.computeIfAbsent(prefix, ignored -> new ArrayList<>()).add(address);
            }
            ExecutorCompletionService<Void> whois = new ExecutorCompletionService<>(pool);
            List<Future<Void>> whoisTasks = new ArrayList<>();
            for (Map.Entry<String, List<String>> group : byPrefix.entrySet()) {
                whoisTasks.add(whois.submit(() -> {
                    fetchWhoisGroup(task, entries, extras, group.getKey(), group.getValue(), firstDetail);
                    return null;
                }));
            }
            await(whois, whoisTasks.size(), WHOIS_PHASE_SECONDS, task, timedOut, entries, update);
            for (Future<Void> future : whoisTasks) future.cancel(true);
            if (task.cancelled) return;
            backfill(entries, extras, selected);
            update.onProgress(snapshot(entries), summary(context, selected, entries, firstDetail[0], timedOut[0]), true);
        } finally {
            pool.shutdownNow();
        }
    }

    private static void fetchNetwork(Task task, Map<String, JSONObject> entries, Map<String, String> prefixOf,
                                      String address, String[] firstDetail) {
        if (task.cancelled) return;
        Thread.interrupted();
        try {
            JSONObject data = ripeData(task, "network-info", address, 2_500, 4_000);
            String prefix = data.optString("prefix");
            prefixOf.put(address, prefix.isBlank() ? address : prefix);
            put(entries, "NetworkInfo", address, data, "network_info");
        } catch (Exception error) {
            prefixOf.putIfAbsent(address, address);
            if (!stopped(task)) remember(firstDetail, "NetworkInfo", address, error);
        }
    }

    private static void fetchGeo(Task task, Map<String, JSONObject> entries, Map<String, Extra> extras,
                                 String address, String[] firstDetail) {
        if (task.cancelled || hasUsableGeo(entries, address)) return;
        Thread.interrupted();
        Exception last = null;
        try {
            JSONObject maxmind = ripeData(task, "maxmind-geo-lite", address, 2_500, 4_000);
            if (usableGeo(maxmind)) {
                put(entries, "GeoLocation", address, maxmind, "geo_location");
                return;
            }
        } catch (Exception error) {
            if (stopped(task)) return;
            last = error;
        }
        if (task.cancelled) return;
        try {
            JSONObject geoloc = ripeData(task, "geoloc", address, 2_500, 4_000);
            if (usableGeo(geoloc)) {
                put(entries, "GeoLocation", address, geoloc, "geo_location");
                return;
            }
        } catch (Exception error) {
            if (stopped(task)) return;
            last = error;
        }
        if (task.cancelled) return;
        try {
            JSONObject dbip = externalJson(task, DBIP_BASE + encodedAddress(address), 2_500, 4_000);
            String country = dbip.optString("countryCode");
            String city = cleanCity(dbip.optString("city"));
            if (usableCountry(country)) {
                put(entries, "GeoLocation", address, syntheticGeo(address, country, city), "geo_location");
                return;
            }
        } catch (Exception error) {
            if (stopped(task)) return;
            last = error;
        }
        if (task.cancelled) return;
        try {
            JSONObject ipwho = externalJson(task, IPWHO_BASE + encodedAddress(address), 2_500, 4_000);
            if (!ipwho.optBoolean("success", true) && ipwho.has("success")) {
                throw new IllegalStateException(ipwho.optString("message", "ipwho.is"));
            }
            String country = ipwho.optString("country_code");
            String city = cleanCity(ipwho.optString("city"));
            JSONObject connection = ipwho.optJSONObject("connection");
            String asn = connection == null ? "" : positive(connection.opt("asn"));
            String organization = connection == null ? "" : connection.optString("org");
            if (organization.isBlank() && connection != null) organization = connection.optString("isp");
            if (usableCountry(country)) {
                put(entries, "GeoLocation", address, syntheticGeo(address, country, city), "geo_location");
                extras.put(address, new Extra(country, city, organization, asn));
                if (!asn.isBlank() && !entries.containsKey(key("NetworkInfo", address))) {
                    JSONObject network = new JSONObject();
                    network.put("asns", new JSONArray().put(asn));
                    put(entries, "NetworkInfo", address, network, "network_info");
                }
                return;
            }
        } catch (Exception error) {
            if (stopped(task)) return;
            last = error;
        }
        if (last != null && !stopped(task)) remember(firstDetail, "GeoLocation", address, last);
    }

    private static void fetchWhoisGroup(Task task, Map<String, JSONObject> entries, Map<String, Extra> extras,
                                         String prefix, List<String> addresses, String[] firstDetail) {
        if (task.cancelled) return;
        Thread.interrupted();
        try {
            JSONObject raw = ripeData(task, "whois", prefix, 3_000, 9_000);
            for (String address : addresses) {
                if (task.cancelled) return;
                JSONObject simplified = simplifyWhois(raw, address);
                JSONObject payload = new JSONObject();
                payload.put("resource", address);
                payload.put("whois", simplified);
                entries.put(key("Whois", address), new JSONObject().put("Whois", payload));
                backfillGeoFromWhois(entries, address, simplified.optString("country"));
            }
        } catch (Exception error) {
            if (!stopped(task)) remember(firstDetail, "Whois", prefix, error);
            for (String address : addresses) backfillFromExtra(entries, extras.get(address), address);
        }
    }

    private static void backfill(Map<String, JSONObject> entries, Map<String, Extra> extras, List<String> addresses) {
        for (String address : addresses) {
            Extra extra = extras.get(address);
            if (!entries.containsKey(key("Whois", address))) backfillFromExtra(entries, extra, address);
            if (!hasUsableGeo(entries, address) && extra != null) {
                try {
                    if (usableCountry(extra.country)) {
                        put(entries, "GeoLocation", address, syntheticGeo(address, extra.country, extra.city), "geo_location");
                    }
                } catch (Exception ignored) { }
            }
        }
    }

    private static void backfillFromExtra(Map<String, JSONObject> entries, Extra extra, String address) {
        if (extra == null || entries.containsKey(key("Whois", address))) return;
        if (extra.organization.isBlank() && extra.asn.isBlank()) return;
        try {
            JSONObject whois = new JSONObject();
            whois.put("resource", address);
            whois.put("organization", extra.organization);
            whois.put("country", usableCountry(extra.country) ? extra.country.toUpperCase(Locale.ROOT) : "");
            whois.put("cidr", "");
            whois.put("net_name", "");
            whois.put("source", "ipwho.is");
            JSONObject payload = new JSONObject();
            payload.put("resource", address);
            payload.put("whois", whois);
            entries.putIfAbsent(key("Whois", address), new JSONObject().put("Whois", payload));
            if (!extra.asn.isBlank() && !entries.containsKey(key("NetworkInfo", address))) {
                JSONObject network = new JSONObject();
                network.put("asns", new JSONArray().put(extra.asn));
                put(entries, "NetworkInfo", address, network, "network_info");
            }
        } catch (Exception ignored) { }
    }

    private static void backfillGeoFromWhois(Map<String, JSONObject> entries, String address, String country) throws Exception {
        if (hasUsableGeo(entries, address) || !usableCountry(country)) return;
        put(entries, "GeoLocation", address, syntheticGeo(address, country, ""), "geo_location");
    }

    private static void await(ExecutorCompletionService<Void> completion, int pending, long seconds,
                              Task task, boolean[] timedOut, Map<String, JSONObject> entries, Update update) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        for (int i = 0; i < pending; i++) {
            if (task.cancelled) return;
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                timedOut[0] = true;
                break;
            }
            try {
                Future<Void> done = completion.poll(remaining, TimeUnit.NANOSECONDS);
                if (done == null) {
                    timedOut[0] = true;
                    break;
                }
                done.get();
                if (!task.cancelled) update.onProgress(snapshot(entries), "", false);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ignored) { }
        }
    }

    private static boolean stopped(Task task) {
        return task.cancelled || Thread.currentThread().isInterrupted();
    }

    private static JSONObject ripeData(Task task, String endpoint, String resource, int connectMs, int readMs) throws Exception {
        String query = URLEncoder.encode(resource, StandardCharsets.UTF_8);
        String url = RIPE_BASE + endpoint + "/data.json?resource=" + query + "&sourceapp=hostlookup";
        JSONObject root = httpGet(task, url, connectMs, readMs);
        String status = root.optString("status");
        if (!status.isBlank() && !"ok".equalsIgnoreCase(status)) {
            throw new IllegalStateException(status);
        }
        JSONObject data = root.optJSONObject("data");
        if (data == null) throw new IllegalStateException("empty response");
        return data;
    }

    private static JSONObject externalJson(Task task, String url, int connectMs, int readMs) throws Exception {
        JSONObject root = httpGet(task, url, connectMs, readMs);
        if (root.has("error") && !root.optString("error").isBlank() && !root.has("countryCode") && !root.has("country_code")) {
            throw new IllegalStateException(root.optString("error"));
        }
        return root;
    }

    private static JSONObject httpGet(Task task, String url, int connectMs, int readMs) throws Exception {
        if (task.cancelled) throw new InterruptedException();
        JSONObject cached = CACHE.get(url);
        if (cached != null) return cached;
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        task.track(connection);
        connection.setConnectTimeout(connectMs);
        connection.setReadTimeout(readMs);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "HostLookup/0.2.3 Android");
        try {
            if (task.cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedException();
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            String body = readBody(task, stream);
            if (task.cancelled) throw new InterruptedException();
            if (status < 200 || status >= 300) throw new IllegalStateException("HTTP " + status);
            JSONObject root = new JSONObject(body);
            if (CACHE.size() > 256) CACHE.clear();
            CACHE.put(url, root);
            return root;
        } finally {
            task.untrack(connection);
            connection.disconnect();
        }
    }

    private static String readBody(Task task, InputStream stream) throws Exception {
        StringBuilder body = new StringBuilder();
        if (stream == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (task.cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedException();
                body.append(line);
                if (body.length() > MAX_BODY) throw new IllegalStateException("response too large");
            }
        }
        return body.toString();
    }

    private static JSONObject syntheticGeo(String address, String country, String city) throws Exception {
        JSONObject location = new JSONObject();
        location.put("country", country.toUpperCase(Locale.ROOT));
        location.put("city", city == null ? "" : city);
        location.put("resources", new JSONArray().put(address));
        JSONObject located = new JSONObject();
        located.put("resource", address);
        located.put("locations", new JSONArray().put(location));
        JSONObject data = new JSONObject();
        data.put("located_resources", new JSONArray().put(located));
        return data;
    }

    private static void put(Map<String, JSONObject> entries, String kind, String address, JSONObject data, String field) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("resource", address);
        payload.put(field, data);
        entries.put(key(kind, address), new JSONObject().put(kind, payload));
    }

    private static JSONObject simplifyWhois(JSONObject raw, String address) throws Exception {
        JSONObject out = new JSONObject();
        out.put("resource", address);
        out.put("authorities", raw.optJSONArray("authorities"));
        String organization = "";
        String description = "";
        String country = "";
        String cidr = "";
        String netName = "";
        JSONArray groups = raw.optJSONArray("records");
        if (groups != null) {
            for (int i = 0; i < groups.length(); i++) {
                JSONArray fields = groups.optJSONArray(i);
                if (fields == null) continue;
                for (int j = 0; j < fields.length(); j++) {
                    JSONObject field = fields.optJSONObject(j);
                    if (field == null) continue;
                    String key = field.optString("key").toLowerCase(Locale.ROOT);
                    String value = field.optString("value");
                    if ((key.equals("org-name") || key.equals("organization")) && organization.isBlank()) organization = value;
                    else if ((key.equals("descr") || key.equals("owner")) && description.isBlank()) description = value;
                    else if (key.equals("country") && country.isBlank()) country = value;
                    else if ((key.equals("cidr") || key.equals("inetnum") || key.equals("inet6num")) && cidr.isBlank()) cidr = value;
                    else if (key.equals("netname") && netName.isBlank()) netName = value;
                }
            }
        }
        out.put("organization", organization.isBlank() ? description : organization);
        out.put("country", usableCountry(country) ? country.toUpperCase(Locale.ROOT) : "");
        out.put("cidr", cidr);
        out.put("net_name", netName);
        JSONArray authorities = raw.optJSONArray("authorities");
        out.put("source", authorities != null && authorities.length() > 0 ? authorities.optString(0) : "");
        return out;
    }

    private static boolean usableKind(String kind, JSONObject payload) {
        if (!"GeoLocation".equals(kind)) return true;
        return usableGeo(payload.optJSONObject("geo_location"));
    }

    private static boolean hasUsableGeo(Map<String, JSONObject> entries, String address) {
        JSONObject wrapper = entries.get(key("GeoLocation", address));
        if (wrapper == null) return false;
        JSONObject payload = wrapper.optJSONObject("GeoLocation");
        return payload != null && usableGeo(payload.optJSONObject("geo_location"));
    }

    private static boolean usableGeo(JSONObject data) {
        if (data == null) return false;
        JSONArray located = data.optJSONArray("located_resources");
        if (located == null || located.length() == 0) return false;
        JSONObject first = located.optJSONObject(0);
        if (first == null) return false;
        JSONArray locations = first.optJSONArray("locations");
        if (locations == null || locations.length() == 0) return false;
        JSONObject location = locations.optJSONObject(0);
        return location != null && usableCountry(location.optString("country"));
    }

    private static boolean usableCountry(String country) {
        if (country == null) return false;
        String code = country.trim().toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z]{2}")) return false;
        return !code.equals("XX") && !code.equals("ZZ") && !code.equals("T1")
                && !code.equals("A1") && !code.equals("A2") && !code.equals("O1");
    }

    private static String cleanCity(String city) {
        if (city == null) return "";
        String trimmed = city.trim();
        if (trimmed.equals("-") || trimmed.equalsIgnoreCase("null")) return "";
        return trimmed;
    }

    private static String positive(Object value) {
        if (value == null || value == JSONObject.NULL) return "";
        String text = value.toString().trim();
        if (text.startsWith("AS") || text.startsWith("as")) text = text.substring(2);
        if (!text.matches("[0-9]+") || text.equals("0")) return "";
        return text;
    }

    private static void remember(String[] firstDetail, String kind, String resource, Exception error) {
        if (firstDetail[0] != null) return;
        Throwable cause = error;
        while (cause.getCause() != null && !(cause instanceof IllegalStateException)) cause = cause.getCause();
        String message = cause.getMessage();
        if (message == null || message.isBlank()) message = cause.getClass().getSimpleName();
        firstDetail[0] = kind + "\u0000" + resource + "\u0000" + message;
    }

    private static String summary(Context context, List<String> addresses, Map<String, JSONObject> entries,
                                  String firstDetail, boolean timedOut) {
        int missing = 0;
        for (String address : addresses) {
            if (!entries.containsKey(key("NetworkInfo", address))) missing++;
            if (!entries.containsKey(key("Whois", address))) missing++;
            if (!hasUsableGeo(entries, address)) missing++;
        }
        if (missing == 0) return "";
        if (timedOut && missing > 1) return context.getString(R.string.ripestat_timeout);
        if (missing == 1 && firstDetail != null) {
            String[] parts = firstDetail.split("\u0000", 3);
            if (parts.length == 3) return context.getString(R.string.ripestat_kind_error, parts[0], parts[1], parts[2]);
        }
        return context.getResources().getQuantityString(R.plurals.ripestat_requests_failed, missing, missing);
    }

    private static List<JSONObject> snapshot(Map<String, JSONObject> entries) {
        return new ArrayList<>(entries.values());
    }

    private static String key(String kind, String address) {
        return kind + "\u0000" + address;
    }

    private static String firstKey(JSONObject object) {
        java.util.Iterator<String> keys = object.keys();
        return keys.hasNext() ? keys.next() : "";
    }

    private static String encodedAddress(String address) {
        return URLEncoder.encode(address, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String withoutPrefix(String resource) {
        int slash = resource.indexOf('/');
        return slash < 0 ? resource : resource.substring(0, slash);
    }

    private static final class Extra {
        final String country;
        final String city;
        final String organization;
        final String asn;

        Extra(String country, String city, String organization, String asn) {
            this.country = country == null ? "" : country;
            this.city = city == null ? "" : city;
            this.organization = organization == null ? "" : organization;
            this.asn = asn == null ? "" : asn;
        }
    }
}
