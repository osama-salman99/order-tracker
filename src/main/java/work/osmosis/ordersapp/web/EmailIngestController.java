package work.osmosis.ordersapp.web;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import work.osmosis.ordersapp.ingest.EmailIngestService;

/**
 * Where mail enters the service.
 *
 * <p>A Cloudflare Email Worker posts the raw message here whenever something arrives at
 * {@code orders@email.osmosis.work}. The endpoint sits outside the single-sign-on wall — a worker
 * cannot sign in — so a shared secret in a header stands in for the login.
 */
@RestController
public class EmailIngestController {

    private static final Logger log = LoggerFactory.getLogger(EmailIngestController.class);

    /** Refuse anything larger than this outright: real order mail is a fraction of it. */
    private static final int MAX_BYTES = 25 * 1024 * 1024;

    private final EmailIngestService ingestService;
    private final String expectedToken;

    public EmailIngestController(
            EmailIngestService ingestService, @Value("${orders.ingest-token:}") String expectedToken) {
        this.ingestService = ingestService;
        this.expectedToken = expectedToken;
    }

    /**
     * The body is read straight off the request rather than through a {@code @RequestBody}
     * parameter. Spring picks a converter by {@code Content-Type}, so a sender that omits the header
     * — or labels a message something unexpected — is refused before any of this runs, which is a
     * silly way to lose an email. Here the bytes are the bytes whatever the header says.
     */
    @PostMapping("/ingest-email")
    public ResponseEntity<String> ingest(
            @RequestHeader(value = "X-Ingest-Token", required = false) String token,
            HttpServletRequest request) {

        if (expectedToken == null || expectedToken.isBlank()) {
            // Better to visibly refuse mail than to accept it from anyone while the secret is missing.
            log.error("Refusing an ingest request: no ingest token is configured");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("ingest not configured");
        }
        if (!matches(token)) {
            log.warn("Rejected an ingest request with a bad or missing token");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("bad token");
        }
        byte[] raw;
        try {
            raw = request.getInputStream().readNBytes(MAX_BYTES + 1);
        } catch (IOException e) {
            log.warn("Could not read the body of an ingest request", e);
            return ResponseEntity.badRequest().body("unreadable body");
        }
        if (raw.length == 0) {
            return ResponseEntity.badRequest().body("empty body");
        }
        if (raw.length > MAX_BYTES) {
            log.warn("Rejected an oversized message of over {} bytes", MAX_BYTES);
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body("message too large");
        }

        try {
            return ResponseEntity.ok(ingestService.ingest(raw));
        } catch (Exception e) {
            // Answering with an error makes Cloudflare's worker log the failure, which is the only
            // place the problem would otherwise be visible.
            log.error("Ingest failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("ingest failed");
        }
    }

    /** Constant-time comparison, so a wrong guess reveals nothing about how wrong it was. */
    private boolean matches(String token) {
        if (token == null) {
            return false;
        }
        return MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), expectedToken.getBytes(StandardCharsets.UTF_8));
    }
}
