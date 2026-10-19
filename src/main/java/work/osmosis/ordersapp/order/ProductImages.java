package work.osmosis.ordersapp.order;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Keeps a local copy of each product picture, so the page never asks a retailer for one.
 *
 * <p>The alternative — putting the shop's URL straight into an {@code <img>} tag — was rejected for
 * three reasons, in increasing order of seriousness. The links rot: retailer image URLs carry
 * resizing and signing parameters and stop working after a while, so a page that reads back over a
 * year of orders would fill up with broken pictures. The pictures are outside the login wall that
 * everything else here sits behind. And every time the page were opened, each shop would be told
 * that this person is looking at their old order right now — which is a page-load beacon that the
 * reader never asked for and cannot see.
 *
 * <p>So the picture is fetched once, from here, and served from disk afterwards. Fetching is
 * deliberately timid: a short timeout, a size ceiling, and a refusal to store anything that does not
 * come back declaring itself an image. A failure is remembered as a failure for a while rather than
 * retried on every page load, because the common cause is a retailer that will not serve this
 * machine at all — www.ikea.com blocks the VPS's address outright — and hammering it changes nothing.
 */
@Component
public class ProductImages {

    private static final Logger log = LoggerFactory.getLogger(ProductImages.class);

    /** Big enough for any product photograph, small enough that a surprise cannot fill the disk. */
    private static final long MAX_BYTES = 3L * 1024 * 1024;

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** How long a shop that refused us is left alone before trying again. */
    private static final Duration RETRY_AFTER = Duration.ofDays(7);

    private static final Set<String> ALLOWED =
            Set.of("image/jpeg", "image/png", "image/webp", "image/gif", "image/avif");

    /**
     * What to say we can display. AVIF is deliberately left out of it.
     *
     * <p>A retailer's image service reads this line and picks a format from it, and asking for AVIF
     * is asking to be given one: with it listed first, twenty-one of the thirty pictures that could
     * not be shown came back as AVIF — including every Coolblue photograph. It is a fine format and
     * modern browsers read it, but it is the newest of the four and the only one an older phone might
     * not have, and there is no reason to be handed the format with the narrowest reach when the same
     * service will send an ordinary JPEG for the asking. It stays in the accepted list above for the
     * case where a shop sends one regardless.
     */
    private static final String ACCEPT = "image/webp,image/jpeg,image/png,*/*;q=0.8";

    /**
     * Some retailers answer a bare programmatic request with a refusal and the same request with a
     * browser's user-agent with the picture. Saying we are a browser is accurate enough — a browser
     * is exactly what will display this — and it is the difference between a picture and a blank.
     */
    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";

    private final Path root;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public ProductImages(@Value("${orders.image-dir:./data/images}") String imageDir) throws IOException {
        this.root = Path.of(imageDir).toAbsolutePath().normalize();
        Files.createDirectories(root);
        log.info("Product pictures cached at {}", root);
    }

    /** A picture already on disk, if there is one. */
    public Optional<Stored> cached(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        String name = nameOf(url);
        for (String extension : new String[] {"jpg", "png", "webp", "gif", "avif"}) {
            Path file = root.resolve(name + "." + extension);
            if (Files.isReadable(file)) {
                return Optional.of(new Stored(file, contentTypeOf(extension)));
            }
        }
        return Optional.empty();
    }

    /**
     * Fetches the picture and keeps it, or reports that it could not be had.
     *
     * <p>Never throws: a picture is a nicety, and no failure to get one is worth turning into an
     * error the reader has to look at.
     */
    public Optional<Stored> fetch(String url) {
        Optional<Stored> already = cached(url);
        if (already.isPresent()) {
            return already;
        }
        Path failure = root.resolve(nameOf(url) + ".failed");
        if (recentlyFailed(failure)) {
            return Optional.empty();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(biggerIfWeCan(url)))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", ACCEPT)
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                return give(failure, url, "answered " + response.statusCode());
            }
            String type = response.headers().firstValue("content-type").orElse("")
                    .split(";")[0].trim().toLowerCase(Locale.ROOT);
            if (!ALLOWED.contains(type)) {
                return give(failure, url, "sent " + (type.isEmpty() ? "no content type" : type));
            }
            byte[] bytes;
            try (InputStream body = response.body()) {
                bytes = body.readNBytes((int) MAX_BYTES + 1);
            }
            if (bytes.length == 0 || bytes.length > MAX_BYTES) {
                return give(failure, url, bytes.length == 0 ? "sent nothing" : "sent too much");
            }
            Path target = root.resolve(nameOf(url) + "." + extensionOf(type));
            Path temp = Files.createTempFile(root, "fetching-", ".part");
            try {
                Files.write(temp, bytes);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
            Files.deleteIfExists(failure);
            return Optional.of(new Stored(target, type));
        } catch (InterruptedException e) {
            // Being interrupted means shutdown, not a shop that will not talk to us: put the flag
            // back so the caller's loop can see it and stop, and do not blame the retailer for it by
            // writing a failure that would keep this picture unfetched for a week.
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            return give(failure, url, e.toString());
        }
    }

    private Optional<Stored> give(Path failure, String url, String why) {
        log.debug("No picture from {}: {}", url, why);
        try {
            Files.writeString(failure, why);
        } catch (IOException ignored) {
            // Not being able to remember the failure only costs a retry.
        }
        return Optional.empty();
    }

    private boolean recentlyFailed(Path failure) {
        try {
            return Files.exists(failure)
                    && Files.getLastModifiedTime(failure).toInstant().isAfter(java.time.Instant.now().minus(RETRY_AFTER));
        } catch (IOException e) {
            return false;
        }
    }

    /** A picture on disk, with the type to serve it as. */
    public record Stored(Path file, String contentType) {}

    /**
     * Amazon writes the size it will send into the address, and what its emails ask for is 90 pixels
     * square — a thumbnail for a line in a table, too small to look at anywhere else. Asking the same
     * server for the same picture at 500 costs nothing and is not a trick: the number is Amazon's own,
     * and the larger sizes are what its own product pages use.
     *
     * <p>Only Amazon, and only when the size it names is smaller than what is wanted. Every other shop
     * is asked for exactly the address its email gave, because guessing at a size a CDN did not
     * advertise gets a 404 rather than a bigger picture.
     */
    private static final Pattern AMAZON_SIZE = Pattern.compile("(?<=\\.)_(S[SLXY])(\\d{2,3})_(?=\\.)");

    private static final int WANTED = 500;

    static String biggerIfWeCan(String url) {
        if (!url.contains("media-amazon.com/images/")) {
            return url;
        }
        Matcher size = AMAZON_SIZE.matcher(url);
        if (!size.find() || number(size.group(2)) >= WANTED) {
            return url;
        }
        return new StringBuilder(url)
                .replace(size.start(), size.end(), "_" + size.group(1) + WANTED + "_")
                .toString();
    }

    private static int number(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /** Named after the address rather than the order, so two orders of one thing share a file. */
    private static String nameOf(String url) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(url.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (Exception e) {
            return Integer.toHexString(url.hashCode());
        }
    }

    private static String extensionOf(String contentType) {
        return switch (contentType) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            case "image/avif" -> "avif";
            default -> "jpg";
        };
    }

    private static String contentTypeOf(String extension) {
        return switch (extension) {
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            case "avif" -> "image/avif";
            default -> "image/jpeg";
        };
    }
}
