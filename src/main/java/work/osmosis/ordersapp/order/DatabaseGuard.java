package work.osmosis.ordersapp.order;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

/**
 * Puts the last good copy of the database back when the one on disk can no longer be read.
 *
 * <p>The store has been left unreadable more than once by a bulk re-read of the archive, and the
 * damage is only ever discovered on the next start — by which point the service either refuses to
 * come up or, worse, comes up and fails every query. Neither is something the reader can fix, and
 * both need somebody at a terminal.
 *
 * <p>So before anything else is built, the file is opened and read once. If that fails, the broken
 * file is moved aside (never deleted — it is the only evidence of what went wrong) and the newest
 * copy {@link ArchiveBackup} left behind is unpacked in its place. What is lost is whatever arrived
 * between the copy and the damage, and that is recoverable anyway: the mail itself is in the
 * archive, and re-reading it is what the service does on a timer.
 *
 * <p>This runs as a listener rather than a bean because it has to happen before the connection pool
 * is built, which is the point at which a broken store first bites.
 */
public class DatabaseGuard implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    private static final Logger log = LoggerFactory.getLogger(DatabaseGuard.class);

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZoneOffset.UTC);

    /** Guards against a restore loop: a copy that is itself unreadable is not tried twice. */
    private static boolean alreadyRun;

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        if (alreadyRun) {
            return;
        }
        alreadyRun = true;
        Environment environment = event.getEnvironment();
        String url = environment.getProperty("spring.datasource.url", "");
        Path database = fileBehind(url);
        if (database == null || !Files.exists(Path.of(database + ".mv.db"))) {
            return;
        }
        String failure = whyItWillNotOpen(database);
        if (failure == null) {
            return;
        }
        log.error("The database will not open: {}", failure);
        Path backups = Path.of(environment.getProperty("orders.backup-dir", "./data/backups"));
        restore(database, backups);
    }

    /**
     * The database file named by a JDBC url, or null if it does not name one — an in-memory
     * database used by a test, say, has nothing to guard.
     */
    static Path fileBehind(String url) {
        if (!url.startsWith("jdbc:h2:")) {
            return null;
        }
        String rest = url.substring("jdbc:h2:".length());
        // Prefixes that say how to reach the file rather than which file it is.
        for (String prefix : List.of("retry:", "file:", "nio:", "async:")) {
            if (rest.startsWith(prefix)) {
                rest = rest.substring(prefix.length());
            }
        }
        if (rest.startsWith("mem:") || rest.startsWith("tcp:")) {
            return null;
        }
        int settings = rest.indexOf(';');
        String path = settings < 0 ? rest : rest.substring(0, settings);
        return path.isBlank() ? null : Path.of(path).toAbsolutePath();
    }

    /**
     * Reads one row out of the store, which is what turns a damaged file into an error. Returns the
     * complaint, or null when the file is fine.
     *
     * <p>This opens the store the ordinary way rather than read-only on purpose. A store left behind
     * by a process that was killed has work to finish on the next open, and refusing a read-only
     * open is a normal part of that — treating it as damage would throw away good data to fix
     * nothing. What is asked here is the only question worth asking: can the service use this file?
     */
    private String whyItWillNotOpen(Path database) {
        String url = "jdbc:h2:file:" + database + ";IFEXISTS=TRUE;TRACE_LEVEL_FILE=0"
                + ";DB_CLOSE_ON_EXIT=FALSE";
        try (Connection connection = DriverManager.getConnection(url, "", "");
                Statement statement = connection.createStatement()) {
            // Any table would do; this is the one whose loss would hurt.
            try (ResultSet rows = statement.executeQuery("select count(*) from CUSTOMER_ORDER")) {
                rows.next();
            }
            return null;
        } catch (Exception e) {
            // A store from before the table existed is not damaged, only empty.
            String message = String.valueOf(e.getMessage());
            return message.contains("Table \"CUSTOMER_ORDER\" not found") ? null : firstLine(message);
        }
    }

    private void restore(Path database, Path backups) {
        Path newest = newestCopy(backups);
        if (newest == null) {
            log.error("No copy of the database to put back, so it is left as it is; expect the service"
                    + " to fail every query until somebody looks at {}", backups.toAbsolutePath());
            return;
        }
        Path live = Path.of(database + ".mv.db");
        Path broken = Path.of(database + ".broken-" + STAMP.format(Instant.now()) + ".mv.db");
        try {
            Files.move(live, broken, StandardCopyOption.REPLACE_EXISTING);
            log.warn("Moved the unreadable database aside to {}", broken.getFileName());
            unpack(newest, live);
            log.warn("Put back the copy taken at {}; any mail that arrived after it will be read again"
                    + " from the archive", newest.getFileName());
        } catch (Exception e) {
            log.error("Could not put back a copy of the database", e);
        }
    }

    /**
     * Takes the store out of one of the zips {@code BACKUP TO} writes. Those hold the database's
     * files under their own names, and for this database that is the single {@code .mv.db}.
     */
    private void unpack(Path zip, Path target) throws IOException {
        try (ZipFile archive = new ZipFile(zip.toFile())) {
            ZipEntry store = archive.stream()
                    .filter(entry -> entry.getName().endsWith(".mv.db"))
                    .findFirst()
                    .orElseThrow(() -> new IOException("No database file inside " + zip.getFileName()));
            try (var in = archive.getInputStream(store)) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private Path newestCopy(Path backups) {
        if (!Files.isDirectory(backups)) {
            return null;
        }
        try (Stream<Path> found = Files.list(backups)) {
            return found.filter(p -> p.getFileName().toString().endsWith(".zip"))
                    .max(Comparator.comparing(p -> p.getFileName().toString()))
                    .orElse(null);
        } catch (IOException e) {
            log.error("Could not look for a copy of the database in {}", backups.toAbsolutePath(), e);
            return null;
        }
    }

    private static String firstLine(String message) {
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}
