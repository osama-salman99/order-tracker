package work.osmosis.ordersapp.order;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import work.osmosis.ordersapp.domain.ReceivedEmailRepository;
import work.osmosis.ordersapp.ingest.EmailArchive;
import work.osmosis.ordersapp.ingest.EmailIngestService;

/**
 * Reads back in any archived email the database does not know about.
 *
 * <p>The database is only a reading of the archive, and on 14 October 2026 it lost five weeks of it:
 * the store opened on a state from early September, with no error anywhere, and every email that had
 * arrived since was simply gone from it while its file sat untouched in the archive. Nothing put them
 * back, because the only way in was the front door. Now every start compares the two and takes in
 * what is missing, so a database that goes back in time repairs itself from the files.
 */
@Component
public class ArchiveCatchUp {

    private static final Logger log = LoggerFactory.getLogger(ArchiveCatchUp.class);

    private final EmailArchive archive;
    private final EmailIngestService ingest;
    private final ReceivedEmailRepository emails;

    public ArchiveCatchUp(EmailArchive archive, EmailIngestService ingest, ReceivedEmailRepository emails) {
        this.archive = archive;
        this.ingest = ingest;
        this.emails = emails;
    }

    /** @return how many emails were taken in */
    public int run() {
        List<String> missing;
        try {
            Set<String> known = new HashSet<>(emails.archivePaths());
            missing = archive.listAll().stream().filter(path -> !known.contains(path)).toList();
        } catch (Exception e) {
            log.warn("Could not compare the archive with the database", e);
            return 0;
        }
        if (missing.isEmpty()) {
            return 0;
        }
        int taken = 0;
        for (String path : missing) {
            try {
                if (ingest.adopt(path, EmailArchive.arrivalOf(path))) {
                    taken++;
                }
            } catch (Exception e) {
                log.warn("Could not read archived email {} back in", path, e);
            }
        }
        // The rest are older copies of a message held in a newer one, checked again on every start
        // and rightly left alone; only a real gap is worth saying anything about.
        if (taken > 0) {
            log.warn("The database was missing {} archived email(s); read them back in", taken);
        }
        return taken;
    }
}
