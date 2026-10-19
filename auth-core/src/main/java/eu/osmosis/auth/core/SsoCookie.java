package eu.osmosis.auth.core;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The shared single-sign-on cookie for every admin surface under one domain ({@code *.osmosis.work}
 * for this fleet, {@code osmosis.auth.domain} anywhere else). The wire
 * format is mirrored, byte for byte, in osmosis-auth (which mints it), session-controller's
 * {@code server.py} (Python) and vps-health's {@code sso.go} (Go), so a cookie minted by any of
 * them validates in all of them:
 *
 * <pre>
 *   value = base64url( "&lt;user&gt;:&lt;exp&gt;:&lt;sig&gt;" )
 *   sig   = lowercase-hex HMAC-SHA256( key = SSO_SIGNING_KEY, msg = "&lt;user&gt;:&lt;exp&gt;" )
 * </pre>
 *
 * The key is {@code SSO_SIGNING_KEY} — a dedicated random secret from the shared secrets file
 * (see {@link SsoSecurityConfig}), NOT the admin password hash. It is deliberately separate
 * so the crackable bcrypt hash never signs a bearer token; rotating {@code SSO_SIGNING_KEY}
 * (not changing the password) is what invalidates every outstanding cookie (a global logout).
 *
 * <p>{@code SsoCookieTest} pins a cross-language interop vector. If that test breaks, single
 * sign-on across the fleet breaks — do not "fix" it by updating the expected string.
 */
public final class SsoCookie {

    public static final String NAME = "osmosis_sso";
    /** The domain the services live under when {@code osmosis.auth.domain} is not set. */
    public static final String DEFAULT_DOMAIN = "osmosis.work";
    public static final int MAX_AGE_SECONDS = 90 * 24 * 60 * 60;

    private SsoCookie() {}

    /**
     * The configured site domain, tidied: trimmed, lower-cased, without a leading dot, and
     * {@link #DEFAULT_DOMAIN} when blank (an empty {@code OSMOSIS_AUTH_DOMAIN=} line is a typo, not a
     * request for no domain at all).
     */
    public static String siteDomain(String configured) {
        String d = configured == null ? "" : configured.trim().toLowerCase(Locale.ROOT);
        while (d.startsWith(".")) d = d.substring(1);
        return d.isEmpty() ? DEFAULT_DOMAIN : d;
    }

    /**
     * The {@code Domain} attribute that shares the cookie with every host under {@code domain},
     * written as {@code "; Domain=.<domain>"} so it drops straight into a Set-Cookie header. Empty for
     * {@code localhost}, an IP address or any other name without a dot: browsers refuse a Domain like
     * that, and a cookie without one is already sent to every port on the same host, which is all a
     * local setup needs.
     */
    public static String domainAttribute(String domain) {
        String d = siteDomain(domain);
        return isHostOnly(d) ? "" : "; Domain=." + d;
    }

    /** True for a name a cookie cannot be shared across: localhost, an IP address, a single label. */
    public static boolean isHostOnly(String domain) {
        String d = siteDomain(domain);
        return !d.contains(".") || d.contains(":") || d.matches("[0-9.]+");
    }

    private static String sign(String key, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    public static String mint(String key, String user, long exp) {
        String payload = user + ":" + exp;
        String raw = payload + ":" + sign(key, payload);
        return Base64.getUrlEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static boolean valid(String key, String cookie, long now) {
        if (key == null || key.isEmpty() || cookie == null || cookie.isEmpty()) return false;
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(cookie), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return false;
        }
        int i2 = raw.lastIndexOf(':');
        if (i2 < 0) return false;
        int i1 = raw.lastIndexOf(':', i2 - 1);
        if (i1 < 0) return false;
        String payload = raw.substring(0, i2); // "<user>:<exp>"
        if (!constantTimeEquals(raw.substring(i2 + 1), sign(key, payload))) return false;
        try {
            return Long.parseLong(raw.substring(i1 + 1, i2)) > now;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        if (x.length != y.length) return false;
        int r = 0;
        for (int i = 0; i < x.length; i++) r |= x[i] ^ y[i];
        return r == 0;
    }
}
