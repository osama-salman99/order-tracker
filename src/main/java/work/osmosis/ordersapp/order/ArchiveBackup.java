package work.osmosis.ordersapp.order;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A copy of the database taken just before the rules are replayed over the whole archive.
 *
 * <p>Re-reading the archive is the one routine thing that rewrites nearly every row at once, and it
 * is exactly when something going wrong is most expensive. Rebuilding from the archived mail is
 * possible but slow and manual; restoring a file is neither. So a bulk re-read starts by writing
 * the current database to a dated zip, and the three most recent are kept.
 *
 * <p>Failing to take the copy is not a reason to refuse to read the mail — the copy is insurance,
 * not the job — so a failure here is logged loudly and the re-read goes ahead.
 */
@Component
public class ArchiveBackup {

    private static final Logger log = LoggerFactory.getLogger(ArchiveBackup.class);

    /** Below this, a "re-read" is just the day's new mail and not worth a copy of everything. */
    private static final int BULK = 25;

    /** How many old copies to keep. Three covers "the last good one" with room to spare. */
    private static final int KEEP = 3;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZoneOffset.UTC);

    private final DataSource dataSource;
    private final Path directory;

    public ArchiveBackup(DataSource dataSource, @Value("${orders.backup-dir:./data/backups}") String directory) {
        this.dataSource = dataSource;
        this.directory = Path.of(directory);
    }

    /** Takes a copy if {@code total} emails is enough to count as rewriting the whole database. */
    public void beforeBulkRewrite(long total) {
        if (total < BULK) {
            return;
        }
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve("orders-db-" + STAMP.format(Instant.now()) + ".zip");
            try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement()) {
                // H2 writes a consistent copy of the store itself; no need to stop the world for it.
                statement.execute("BACKUP TO '" + file.toAbsolutePath() + "'");
            }
            log.info("Copied the database to {} before re-reading {} email(s)", file, total);
            prune();
        } catch (Exception e) {
            log.error("Could not copy the database before re-reading the archive; going ahead anyway", e);
        }
    }

    private void prune() throws IOException {
        try (Stream<Path> found = Files.list(directory)) {
            List<Path> copies = found.filter(p -> p.getFileName().toString().endsWith(".zip"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
            for (Path old : copies.subList(Math.min(KEEP, copies.size()), copies.size())) {
                Files.deleteIfExists(old);
                log.info("Removed an old database copy: {}", old.getFileName());
            }
        }
    }
}
