package work.osmosis.ordersapp.ingest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import work.osmosis.ordersapp.ShutdownSignal;

/**
 * Reads the order mail straight out of Gmail, so nothing has to be forwarded here any more.
 *
 * <p>Every minute it lists the messages matching {@code orders.gmail.query} and takes in any whose
 * Message-ID the database does not already hold. The first pass after a start walks the whole
 * label, which also picks up mail that was labelled after it arrived — something forwarding never
 * saw. Later passes only look at the last few days.
 *
 * <p>Uses the same Google sign-in as Mail (its client and refresh token, copied into this
 * service's settings). With any of the three missing it does nothing, and forwarded mail still
 * arrives through the ingest endpoint as before.
 */
@Component
public class GmailReader {

    private static final Logger log = LoggerFactory.getLogger(GmailReader.class);

    private static final String BASE = "https://gmail.googleapis.com/gmail/v1/users/me/";
    // Gmail allows far more, but there is never a hurry and the sign-in is shared with Mail.
    private static final Duration PACE = Duration.ofMillis(100);

    private final String clientId;
    private final String clientSecret;
    private final String refreshToken;
    private final String query;
    private final EmailIngestService ingest;
    private final ObjectMapper json;
    private final ShutdownSignal shutdown;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    /** Gmail ids already dealt with since this start, so a pass only fetches what is new. */
    private final Set<String> seen = new HashSet<>();

    private boolean walkedWholeLabel;
    private boolean signInRefused;
    private String accessToken;
    private Instant accessTokenExpires = Instant.EPOCH;

    public GmailReader(
            @Value("${orders.gmail.client-id:}") String clientId,
            @Value("${orders.gmail.client-secret:}") String clientSecret,
            @Value("${orders.gmail.refresh-token:}") String refreshToken,
            @Value("${orders.gmail.query:label:Orders}") String query,
            EmailIngestService ingest,
            ObjectMapper json,
            ShutdownSignal shutdown) {
        this.clientId = clientId.strip();
        this.clientSecret = clientSecret.strip();
        this.refreshToken = refreshToken.strip();
        this.query = query;
        this.ingest = ingest;
        this.json = json;
        this.shutdown = shutdown;
        if (!isConfigured()) {
            log.info("Not reading Gmail: no Google sign-in configured; relying on forwarded mail");
        }
    }

    private boolean isConfigured() {
        return !clientId.isEmpty() && !clientSecret.isEmpty() && !refreshToken.isEmpty();
    }

    // Starts after the archive catch-up has had its turn, so the two never take in the same file.
    @Scheduled(initialDelayString = "${orders.gmail.first-read-ms:90000}", fixedDelayString = "${orders.gmail.every-ms:60000}")
    public synchronized void poll() {
        if (!isConfigured() || signInRefused) {
            return;
        }
        try {
            String q = walkedWholeLabel ? query + " newer_than:3d" : query;
            List<String> ids = list(q);
            if (!walkedWholeLabel && ids.isEmpty()) {
                log.warn("Gmail has no messages matching \"{}\"; has the label been renamed?", query);
            }
            int taken = 0;
            for (String id : ids) {
                // Stopping here leaves the label marked as not yet walked; the next start walks it.
                if (shutdown.requested()) {
                    log.info("Stopping the Gmail read part way through; the next start reads it again");
                    return;
                }
                if (seen.contains(id)) {
                    continue;
                }
                if (take(id)) {
                    taken++;
                }
                seen.add(id);
            }
            if (!walkedWholeLabel) {
                log.info("Read the whole of \"{}\" in Gmail: {} message(s), {} new here", query, ids.size(), taken);
                walkedWholeLabel = true;
            } else if (taken > 0) {
                log.info("Took {} new email(s) from Gmail", taken);
            }
        } catch (SignInRefused e) {
            signInRefused = true;
            log.error("Google refused the saved sign-in ({}); not reading Gmail until the token is replaced "
                    + "and the service restarted", e.getMessage());
        } catch (Exception e) {
            // Nothing is lost: the next pass lists the same messages and anything unseen is tried again.
            log.warn("Could not read Gmail this time: {}", e.toString());
        }
    }

    /** @return whether the database gained the message */
    private boolean take(String id) throws IOException, InterruptedException {
        // The headers alone answer "do we have it?" for the great majority, at a fraction of the bytes.
        JsonNode meta = call("messages/" + id + "?format=metadata&metadataHeaders=Message-ID");
        for (JsonNode header : meta.path("payload").path("headers")) {
            if (header.path("name").asText().equalsIgnoreCase("Message-ID")
                    && ingest.holds(header.path("value").asText().strip())) {
                return false;
            }
        }
        JsonNode message = call("messages/" + id + "?format=raw");
        byte[] raw = Base64.getUrlDecoder().decode(message.path("raw").asText());
        Instant arrived = Instant.ofEpochMilli(message.path("internalDate").asLong(System.currentTimeMillis()));
        return ingest.ingestIfNew(raw, arrived);
    }

    private List<String> list(String q) throws IOException, InterruptedException {
        List<String> ids = new ArrayList<>();
        String pageToken = null;
        do {
            if (shutdown.requested()) {
                throw new IOException("shutting down");
            }
            JsonNode page = call("messages?maxResults=500&q=" + enc(q)
                    + (pageToken == null ? "" : "&pageToken=" + enc(pageToken)));
            page.path("messages").forEach(m -> ids.add(m.path("id").asText()));
            pageToken = page.hasNonNull("nextPageToken") ? page.path("nextPageToken").asText() : null;
        } while (pageToken != null);
        return ids;
    }

    private JsonNode call(String path) throws IOException, InterruptedException {
        Thread.sleep(PACE.toMillis());
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken())
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401) {
            accessToken = null;
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Gmail answered " + response.statusCode() + " for " + path.replaceAll("\\?.*", ""));
        }
        return json.readTree(response.body());
    }

    private String accessToken() throws IOException, InterruptedException {
        if (accessToken != null && Instant.now().isBefore(accessTokenExpires.minusSeconds(60))) {
            return accessToken;
        }
        String form = "grant_type=refresh_token&client_id=" + enc(clientId) + "&client_secret=" + enc(clientSecret)
                + "&refresh_token=" + enc(refreshToken);
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/token"))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode token = json.readTree(response.body());
        if (response.statusCode() / 100 != 2) {
            String error = token.path("error").asText("status " + response.statusCode());
            if (error.equals("invalid_grant") || error.equals("invalid_client") || error.equals("unauthorized_client")) {
                throw new SignInRefused(error);
            }
            throw new IOException("Google token request failed: " + error);
        }
        accessToken = token.path("access_token").asText();
        accessTokenExpires = Instant.now().plusSeconds(token.path("expires_in").asLong(3600));
        return accessToken;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static final class SignInRefused extends IOException {
        SignInRefused(String message) {
            super(message);
        }
    }
}
