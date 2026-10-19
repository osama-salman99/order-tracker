package eu.osmosis.version.core;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reports the build version this service is actually running, uncached, at {@code /version}.
 *
 * <p>Every osmosis.work frontend bakes the same value into its page at build time and compares the
 * two: the page says what the browser loaded, this endpoint says what the server is serving, so a
 * mismatch means the browser is showing a stale cached build rather than that the deploy failed.
 * That check is why the response is {@code no-store} — a cached {@code /version} would agree with a
 * cached page and hide exactly the problem it exists to catch.
 *
 * <p>The value is read once at startup from the {@code osmosis-version} classpath resource, which
 * each service's own {@code processResources} fills in from {@code -PosmosisVersion} at build time.
 * This library deliberately ships no such resource: it would land on the consumer's classpath and
 * could win over the real one. A local build with no resource reports {@code dev}.
 */
@RestController
public class VersionController {

    private final String version;

    static final String RESOURCE = "osmosis-version";

    public VersionController() {
        this.version = readVersion(RESOURCE);
    }

    static String readVersion(String resource) {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            String v = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            return v.isEmpty() ? "dev" : v;
        } catch (Exception e) {
            return "dev";
        }
    }

    @GetMapping("/version")
    public ResponseEntity<Map<String, String>> version() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.of("version", version));
    }
}
