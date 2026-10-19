package work.osmosis.ordersapp.ingest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Keeps the untouched bytes of every message that arrives.
 *
 * <p>The archive is the point of the whole design. The rules that read these emails will be wrong
 * about some of them, and the only way to fix that without asking anyone to forward their mail
 * again is to hold the originals and read them a second time. Everything downstream is derived and
 * disposable; this is not.
 */
@Component
public class EmailArchive {

    private static final Logger log = LoggerFactory.getLogger(EmailArchive.class);

    private static final DateTimeFormatter MONTH_DIR =
            DateTimeFormatter.ofPattern("yyyy/MM").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZoneOffset.UTC);

    private final Path root;

    public EmailArchive(@Value("${orders.archive-dir:data/archive}") String archiveDir)
            throws IOException {
        this.root = Path.of(archiveDir).toAbsolutePath().normalize();
        Files.createDirectories(root);
        log.info("Email archive at {}", root);
    }

    /**
     * Writes the message and returns its path relative to the archive root.
     *
     * <p>Named by arrival time and a digest of the content, so re-sending an identical message
     * overwrites one file instead of accumulating copies, while a corrected re-send lands beside the
     * original rather than destroying it.
     */
    public String store(byte[] raw, Instant receivedAt) throws IOException {
        String relative =
                MONTH_DIR.format(receivedAt)
                        + "/"
                        + FILE_STAMP.format(receivedAt)
                        + "-"
                        + shortDigest(raw)
                        + ".eml";
        Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());

        // Write beside the target and move into place, so a failure mid-write never leaves a
        // truncated file that later looks like a real archived message.
        Path temp = Files.createTempFile(target.getParent(), "incoming-", ".part");
        try {
            Files.write(temp, raw);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
        return relative;
    }

    /** Reads an archived message back, for replaying newer rules over older mail. */
    public byte[] read(String relativePath) throws IOException {
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("Archive path escapes the archive directory: " + relativePath);
        }
        return Files.readAllBytes(target);
    }

    /** Every archived message, oldest first: the names start with the arrival time. */
    public List<String> listAll() throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(f -> f.getFileName().toString().endsWith(".eml"))
                    .map(f -> root.relativize(f).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }

    /** When a message arrived, read back from the name {@link #store} gave it. */
    public static Instant arrivalOf(String relativePath) {
        String name = Path.of(relativePath).getFileName().toString();
        return FILE_STAMP.parse(name.substring(0, 15), Instant::from);
    }

    public Path getRoot() {
        return root;
    }

    private static String shortDigest(byte[] raw) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(raw);
            return HexFormat.of().formatHex(hash, 0, 6);
        } catch (Exception e) {
            // Cannot happen for SHA-256, but a nameless file would lose the message.
            return Long.toHexString(System.nanoTime());
        }
    }
}
