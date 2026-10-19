package work.osmosis.ordersapp.order;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.DependsOn;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import work.osmosis.ordersapp.domain.ReceivedEmail;
import work.osmosis.ordersapp.domain.ReceivedEmailRepository;
import work.osmosis.ordersapp.extract.EmailExtractor;

/**
 * Keeps every stored email read by the current version of the rules.
 *
 * <p>This is the payoff of archiving the raw message: the rules are not applied once at the door and
 * then set in stone. Every email records which generation of the rules last looked at it, and
 * anything behind gets read again — so improving a pattern and bumping
 * {@link EmailExtractor#VERSION} corrects the entire history, not just tomorrow's mail. That is what
 * makes a rules-only approach bearable when the rules are certainly incomplete.
 *
 * <p>It also does the ordinary work of picking up newly arrived mail, since a new email starts at
 * version zero and is therefore behind by definition. One mechanism covers both.
 *
 * <p><b>Why this is a {@link SmartLifecycle}.</b> Re-reading the whole archive takes minutes, and a
 * deploy stops the service whenever it likes. Left to itself, the shutdown tears the database down
 * while this is still writing to it: the connection pool closes, aborts the connection this thread
 * is using mid-statement, and the process exits — all in about twenty milliseconds, with nothing
 * logged. That leaves a pass half done and a batch of emails marked as read that were not, which
 * the next pass has no way of noticing.
 *
 * <p>So shutting down now waits for this. Spring stops lifecycle beans <em>before</em> it destroys
 * ordinary ones, and the highest phase stops first — which is the default — so this is the very
 * first thing asked to stop and the database is still fully alive while it finishes. The pass gives
 * up between emails rather than mid-email, so the wait is one email long, not one batch.
 *
 * <p>This was first written believing it was also the cure for the store being left unreadable
 * after a replay, and it is not. The database broke whether the replay was cut short or allowed to
 * finish; what stopped it was telling H2 to write each commit to disk instead of holding it in
 * memory, which is a line in the connection settings rather than anything here.
 */
@Component
@DependsOn("storedEnumsAsText")
public class ExtractionRunner implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ExtractionRunner.class);

    /** Small enough that a full re-read of the archive never loads all of it into memory at once. */
    private static final int BATCH = 100;

    /** How long to let the email in hand finish before giving up on a tidy stop. */
    private static final long STOP_WAIT_MILLIS = 30_000;

    private final ReceivedEmailRepository emails;
    private final OrderAssembler assembler;
    private final ArchiveBackup backup;
    private final ArchiveCatchUp catchUp;
    private final ReaderMarks marks;

    /** The archive is compared with the database once, on the first pass after a start. */
    private boolean caughtUp;

    /** Set at start and after every re-read: put back any of the reader's marks the database lacks. */
    private boolean marksToRestore = true;

    /** Only for the log: says nothing about correctness, but a silent full replay is unnerving. */
    private boolean replayInProgress;

    /** Set once shutdown begins; read between emails so a pass stops promptly. */
    private volatile boolean stopping;

    /** True while a pass is actually touching the database. */
    private final AtomicBoolean working = new AtomicBoolean();

    private volatile boolean running;

    public ExtractionRunner(
            ReceivedEmailRepository emails,
            OrderAssembler assembler,
            ArchiveBackup backup,
            ArchiveCatchUp catchUp,
            ReaderMarks marks) {
        this.emails = emails;
        this.assembler = assembler;
        this.backup = backup;
        this.catchUp = catchUp;
        this.marks = marks;
    }

    @Scheduled(initialDelayString = "PT5S", fixedDelayString = "PT10S")
    public void readWhateverIsBehind() {
        if (stopping) {
            return;
        }
        working.set(true);
        try {
            readABatch();
        } finally {
            working.set(false);
        }
    }

    private void readABatch() {
        if (!caughtUp) {
            caughtUp = true;
            catchUp.run();
        }
        List<ReceivedEmail> behind = emails.findByExtractorVersionLessThanOrderByIdAsc(
                EmailExtractor.VERSION, PageRequest.of(0, BATCH));
        if (behind.isEmpty()) {
            if (replayInProgress) {
                log.info("Finished re-reading the archive with version {} of the rules", EmailExtractor.VERSION);
                replayInProgress = false;
                marksToRestore = true;
            }
            if (marksToRestore) {
                marksToRestore = false;
                try {
                    marks.restore();
                } catch (Exception e) {
                    log.warn("Could not put back the reader's marks", e);
                }
            }
            return;
        }
        if (!replayInProgress) {
            long total = emails.countByExtractorVersionLessThan(EmailExtractor.VERSION);
            log.info("Reading {} email(s) with version {} of the rules", total, EmailExtractor.VERSION);
            // Re-reading the archive rewrites nearly every row in the database. Keep a copy of what
            // it looked like beforehand, so a run that goes wrong costs a file copy rather than an
            // afternoon.
            backup.beforeBulkRewrite(total);
            replayInProgress = true;
        }
        for (ReceivedEmail email : behind) {
            if (stopping) {
                log.info("Stopping the re-read part way through; it picks up where it left off");
                return;
            }
            try {
                assembler.process(email.getId());
            } catch (Exception e) {
                // One unreadable email must not stall the queue behind it: mark it and move on.
                log.warn("Could not read email #{}", email.getId(), e);
                markUnreadable(email.getId(), e);
            }
        }
    }

    private void markUnreadable(Long id, Exception cause) {
        emails.findById(id).ifPresent(email -> {
            email.setStatus(ReceivedEmail.Status.ERROR, "the rules could not read it: " + cause.getMessage());
            email.setExtractorVersion(EmailExtractor.VERSION);
            emails.save(email);
        });
    }

    @Override
    public void start() {
        stopping = false;
        running = true;
    }

    /**
     * Lets the email in hand finish, then reports the reader idle. Everything that owns the database
     * — the connection pool, Hibernate, the store itself — is closed after this returns.
     */
    @Override
    public void stop() {
        stopping = true;
        long deadline = System.currentTimeMillis() + STOP_WAIT_MILLIS;
        while (working.get() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (working.get()) {
            // Should not happen: one email takes milliseconds. Worth saying out loud if it does,
            // because it means the database is about to be closed under a writer after all.
            log.warn("An email was still being read after {}ms; shutting down anyway", STOP_WAIT_MILLIS);
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
