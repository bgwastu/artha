package net.wastu.artha.hook;

import java.util.Locale;

/**
 * Field policy for Artha's plaintext capture file.
 *
 * <p>{@code ArthaCaptureReader} is the only reader of the capture, and it parses just the request
 * envelope ({@code seq}, {@code ts}, {@code url}, {@code code}) plus {@code resp_body} of GoPay
 * history pages. No reader parses a captured request body or a header value, so credential-bearing
 * header values are replaced with {@link #REDACTED} and no request body is ever written.
 */
public final class CaptureRedaction {

    public static final String REDACTED = "[redacted]";

    /** Response metadata that stays useful for explaining a body a reader parses later. */
    private static final String[] SAFE_HEADERS = { "content-type", "content-encoding", "content-length" };

    /** Credential carriers, matched as substrings so renamed variants stay covered. */
    private static final String[] SECRET_HEADER_PARTS = {
        "authorization", "auth", "cookie", "token", "session", "secret", "password", "credential",
        "signature", "apikey", "api-key", "ssid", "csrf", "bearer",
    };

    private static final String[] SECRET_QUERY_PARTS = {
        "token", "auth", "key", "secret", "password", "signature", "session", "ticket",
    };

    private CaptureRedaction() {}

    /** True for headers whose value carries no credential and helps explain a captured body. */
    public static boolean safeHeader(String name) {
        if (name == null) return false;
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        for (String safe : SAFE_HEADERS) if (safe.equals(normalized)) return true;
        return false;
    }

    /** Header value to persist: the value itself only for safelisted names, otherwise a marker. */
    public static String headerValue(String name, String value) {
        if (!safeHeader(name) || secretHeader(name)) return REDACTED;
        return value == null ? "" : value;
    }

    /** URL to persist, with credential-looking query values replaced by {@link #REDACTED}. */
    public static String url(String url) {
        if (url == null) return "";
        int query = url.indexOf('?');
        if (query < 0 || query == url.length() - 1) return url;
        String[] pairs = url.substring(query + 1).split("&", -1);
        StringBuilder out = new StringBuilder(url.length()).append(url, 0, query + 1);
        for (int i = 0; i < pairs.length; i++) {
            if (i > 0) out.append('&');
            int equals = pairs[i].indexOf('=');
            if (equals < 0) {
                out.append(sensitiveParam(pairs[i]) ? REDACTED : pairs[i]);
            } else {
                String name = pairs[i].substring(0, equals);
                out.append(name).append('=');
                if (sensitiveParam(name)) out.append(REDACTED);
                else out.append(pairs[i], equals + 1, pairs[i].length());
            }
        }
        return out.toString();
    }

    private static boolean sensitiveParam(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        for (String part : SECRET_QUERY_PARTS) if (normalized.contains(part)) return true;
        return false;
    }

    /** True when a header value must be dropped before the capture is written. */
    static boolean secretHeader(String name) {
        if (name == null) return true;
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        for (String part : SECRET_HEADER_PARTS) if (normalized.contains(part)) return true;
        return false;
    }
}
