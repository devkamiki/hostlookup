package io.hostlookup.app;

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
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Android-system HTTPS fallback for the same RIPEstat endpoints used by mhost. */
final class RipeStatClient {
    private static final String BASE = "https://stat.ripe.net/data/";
    private static final int MAX_ADDRESSES = 24;
    private static final long DEADLINE_SECONDS = 20;

    private RipeStatClient() { }

    static Result complete(Set<String> addresses, List<JSONObject> nativeEntries) {
        List<JSONObject> completed = Collections.synchronizedList(new ArrayList<>());
        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        Set<String> present = Collections.synchronizedSet(new HashSet<>());

        for (JSONObject entry : nativeEntries) {
            String kind = firstKey(entry);
            if ("Error".equals(kind)) continue;
            JSONObject payload = entry.optJSONObject(kind);
            String resource = payload == null ? "" : withoutPrefix(payload.optString("resource"));
            if (!resource.isBlank()) {
                completed.add(entry);
                present.add(kind + "\u0000" + resource);
            }
        }

        if (addresses.isEmpty()) return new Result(completed, "");
        List<String> selectedAddresses = new ArrayList<>(addresses);
        selectedAddresses.removeIf(address -> present.contains("NetworkInfo\u0000" + address)
                && present.contains("Whois\u0000" + address)
                && present.contains("GeoLocation\u0000" + address));
        Collections.sort(selectedAddresses);
        if (selectedAddresses.size() > MAX_ADDRESSES) {
            selectedAddresses = selectedAddresses.subList(0, MAX_ADDRESSES);
        }
        int workers = Math.min(6, Math.max(1, selectedAddresses.size() * 3));
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<Future<?>> pending = new ArrayList<>();
        for (String address : selectedAddresses) {
            schedule(pool, pending, completed, errors, present, "NetworkInfo", "network-info", address);
            schedule(pool, pending, completed, errors, present, "Whois", "whois", address);
            schedule(pool, pending, completed, errors, present, "GeoLocation", "maxmind-geo-lite", address);
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEADLINE_SECONDS);
        try {
            for (Future<?> future : pending) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    errors.add("RIPEstat enrichment timed out");
                    break;
                }
                try {
                    future.get(remaining, TimeUnit.NANOSECONDS);
                } catch (TimeoutException error) {
                    errors.add("RIPEstat enrichment timed out");
                    break;
                } catch (Exception error) {
                    errors.add(readable(error));
                }
            }
        } finally {
            for (Future<?> future : pending) future.cancel(true);
            pool.shutdownNow();
        }

        String message = errors.isEmpty() ? "" : errors.size() + " RIPEstat requests failed";
        return new Result(completed, message);
    }

    private static void schedule(ExecutorService pool, List<Future<?>> pending,
                                 List<JSONObject> completed, List<String> errors, Set<String> present,
                                 String kind, String endpoint, String address) {
        String key = kind + "\u0000" + address;
        if (present.contains(key)) return;
        pending.add(pool.submit(() -> {
            try {
                JSONObject data = fetch(endpoint, address);
                JSONObject payload = new JSONObject();
                payload.put("resource", address);
                if ("NetworkInfo".equals(kind)) payload.put("network_info", data);
                else if ("Whois".equals(kind)) payload.put("whois", simplifyWhois(data, address));
                else payload.put("geo_location", data);
                JSONObject wrapper = new JSONObject().put(kind, payload);
                completed.add(wrapper);
                present.add(key);
            } catch (Exception error) {
                errors.add(kind + " for " + address + ": " + readable(error));
            }
        }));
    }

    private static JSONObject fetch(String endpoint, String address) throws Exception {
        String query = URLEncoder.encode(address, StandardCharsets.UTF_8.toString());
        URL url = new URL(BASE + endpoint + "/data.json?resource=" + query + "&sourceapp=hostlookup");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(8_000);
        connection.setReadTimeout(8_000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "HostLookup/0.2.1 Android");
        try {
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            StringBuilder body = new StringBuilder();
            if (stream != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) body.append(line);
                }
            }
            if (status < 200 || status >= 300) throw new IllegalStateException("HTTP " + status);
            JSONObject data = new JSONObject(body.toString()).optJSONObject("data");
            if (data == null) throw new IllegalStateException("empty response");
            return data;
        } finally {
            connection.disconnect();
        }
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
        out.put("country", country);
        out.put("cidr", cidr);
        out.put("net_name", netName);
        JSONArray authorities = raw.optJSONArray("authorities");
        out.put("source", authorities != null && authorities.length() > 0 ? authorities.optString(0) : "");
        return out;
    }

    private static String firstKey(JSONObject object) {
        java.util.Iterator<String> keys = object.keys();
        return keys.hasNext() ? keys.next() : "";
    }

    private static String withoutPrefix(String resource) {
        int slash = resource.indexOf('/');
        return slash < 0 ? resource : resource.substring(0, slash);
    }

    private static String readable(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    static final class Result {
        final List<JSONObject> entries;
        final String error;

        Result(List<JSONObject> entries, String error) {
            this.entries = entries;
            this.error = error;
        }
    }
}
