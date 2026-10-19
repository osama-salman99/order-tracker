package work.osmosis.ordersapp.ingest;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import work.osmosis.ordersapp.domain.ReceivedEmail;
import work.osmosis.ordersapp.domain.ReceivedEmailRepository;

/**
 * Takes delivery of a message: archive the bytes, read them, and record the result.
 *
 * <p>Deliberately does no classification and no extraction. Accepting mail must not depend on
 * understanding it — a message in a layout nothing recognises is still worth keeping, and is
 * precisely the one worth keeping.
 */
@Service
public class EmailIngestService {

    private static final Logger log = LoggerFactory.getLogger(EmailIngestService.class);

    private final ReceivedEmailRepository repository;
    private final EmailArchive archive;
    private final MimeReader reader;
    private final List<String> selfAddresses;

    public EmailIngestService(
            ReceivedEmailRepository repository,
            EmailArchive archive,
            MimeReader reader,
            @Value("${orders.self-addresses:}") String selfAddresses) {
        this.repository = repository;
        this.archive = archive;
        this.reader = reader;
        this.selfAddresses =
                Arrays.stream(selfAddresses.split(","))
                        .map(String::strip)
                        .filter(s -> !s.isEmpty())
                        .toList();
    }

    /**
     * @return a short line describing what happened, which the worker logs when something goes wrong
     */
    @Transactional
    public String ingest(byte[] raw) {
        Instant receivedAt = Instant.now();
        String archivePath;
        try {
            archivePath = archive.store(raw, receivedAt);
        } catch (Exception e) {
            // Losing the bytes is the one failure worth refusing the message over: everything else
            // can be recovered by replaying the archive, and this would leave nothing to replay.
            log.error("Could not archive an incoming message", e);
            throw new IllegalStateException("archive write failed", e);
        }
        return record(raw, receivedAt, archivePath);
    }

    public boolean holds(String messageId) {
        return !messageId.isBlank() && repository.findByMessageId(messageId).isPresent();
    }

    /**
     * Takes in a message read from the mailbox, unless the database already holds it.
     *
     * <p>Unlike {@link #ingest}, which is handed each message once, the mailbox is read over and
     * over, so a message already held is left exactly as it is rather than refreshed and queued
     * again. A message that cannot be parsed is skipped: it is still in the mailbox.
     *
     * @return whether the database gained the message
     */
    @Transactional
    public boolean ingestIfNew(byte[] raw, Instant receivedAt) throws java.io.IOException {
        ParsedEmail parsed;
        try {
            parsed = reader.read(raw, selfAddresses);
        } catch (Exception e) {
            log.warn("Skipped a mailbox message that could not be parsed: {}", e.toString());
            return false;
        }
        if (repository.findByMessageId(parsed.messageId()).isPresent()) {
            return false;
        }
        record(raw, receivedAt, archive.store(raw, receivedAt));
        return true;
    }

    /**
     * Takes in a message that is already in the archive but missing from the database.
     *
     * <p>The archive is the record and the database only a reading of it, so a database that has
     * lost rows — restored from an old copy, or rolled back by the store itself — is repaired by
     * reading the missing files back in rather than by asking for the mail again. An older copy of a
     * message the database already holds is left alone: the newest send is the one to believe.
     *
     * @return whether the database changed
     */
    @Transactional
    public boolean adopt(String archivePath, Instant receivedAt) throws java.io.IOException {
        byte[] raw = archive.read(archivePath);
        try {
            ParsedEmail parsed = reader.read(raw, selfAddresses);
            var existing = repository.findByMessageId(parsed.messageId());
            if (existing.isPresent() && !existing.get().getReceivedAt().isBefore(receivedAt)) {
                return false;
            }
        } catch (Exception e) {
            // Unreadable: record() below files it as such, exactly as it did the first time.
        }
        record(raw, receivedAt, archivePath);
        return true;
    }

    private String record(byte[] raw, Instant receivedAt, String archivePath) {
        ParsedEmail parsed;
        try {
            parsed = reader.read(raw, selfAddresses);
        } catch (Exception e) {
            log.warn("Could not parse an incoming message; archived at {}", archivePath, e);
            ReceivedEmail unreadable =
                    new ReceivedEmail(
                            "unparsed:" + archivePath,
                            "(unreadable message)",
                            null,
                            null,
                            null,
                            receivedAt,
                            archivePath,
                            null);
            unreadable.setStatus(ReceivedEmail.Status.ERROR, "Could not parse: " + e.getMessage());
            repository.save(unreadable);
            return "stored unparsed as " + archivePath;
        }

        return repository
                .findByMessageId(parsed.messageId())
                .map(
                        existing -> {
                            // The same email arriving again replaces what we hold and goes back into
                            // the queue: the most recent copy is the one to believe.
                            existing.refreshFrom(
                                    parsed.subject(),
                                    parsed.senderName(),
                                    parsed.senderAddress(),
                                    parsed.sentAt(),
                                    receivedAt,
                                    archivePath,
                                    parsed.text());
                            repository.save(existing);
                            log.info(
                                    "Refreshed email {} from {} ({})",
                                    existing.getId(),
                                    parsed.senderAddress(),
                                    parsed.subject());
                            return "refreshed #" + existing.getId();
                        })
                .orElseGet(
                        () -> {
                            ReceivedEmail saved =
                                    repository.save(
                                            new ReceivedEmail(
                                                    parsed.messageId(),
                                                    parsed.subject(),
                                                    parsed.senderName(),
                                                    parsed.senderAddress(),
                                                    parsed.sentAt(),
                                                    receivedAt,
                                                    archivePath,
                                                    parsed.text()));
                            log.info(
                                    "Received email {} from {} ({}){}",
                                    saved.getId(),
                                    parsed.senderAddress(),
                                    parsed.subject(),
                                    parsed.unwrapped() ? " [unwrapped forward]" : "");
                            return "stored #" + saved.getId();
                        });
    }
}
