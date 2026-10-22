package dev.ad.tv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

final class Http {
    // MET Norway and Nominatim both reject requests without an identifying User-Agent.
    static final String USER_AGENT = "TVHome/" + BuildConfig.VERSION_NAME + " (+https://github.com/ad/tv)";

    private Http() {}

    static String get(String url) throws IOException {
        return get(url, Map.of());
    }

    static String get(String url, Map<String, String> headers) throws IOException {
        HttpURLConnection c = open(url, headers);
        try {
            int code = c.getResponseCode();
            if (code < 200 || code > 299) throw new IOException("HTTP " + code + " for " + url.split("\\?", 2)[0]);
            try (InputStream in = c.getInputStream()) {
                return new String(readAll(in), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }

    static byte[] getBytes(String url) throws IOException {
        HttpURLConnection c = open(url, Map.of());
        try {
            int code = c.getResponseCode();
            if (code < 200 || code > 299) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                return readAll(in);
            }
        } finally {
            c.disconnect();
        }
    }

    static String getFirst(List<String> bases, String path) throws Exception {
        return getFirst(bases, path, Map.of());
    }

    static String getFirst(List<String> bases, String path, Map<String, String> headers) throws Exception {
        Exception last = null;
        for (String base : bases) {
            try {
                return get(base.replaceAll("/+$", "") + path, headers);
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("no hosts");
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static HttpURLConnection open(String url, Map<String, String> headers) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8_000);
        c.setReadTimeout(12_000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", USER_AGENT);
        c.setRequestProperty("Accept", "application/json, image/*;q=0.9, */*;q=0.5");
        headers.forEach(c::setRequestProperty);
        return c;
    }
}
