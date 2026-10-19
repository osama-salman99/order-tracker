package work.osmosis.ordersapp.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ReceivedEmailRepository extends JpaRepository<ReceivedEmail, Long> {

    Optional<ReceivedEmail> findByMessageId(String messageId);

    /** Which archived files the database knows about, to spot any it has lost. */
    @Query("select e.archivePath from ReceivedEmail e where e.archivePath is not null")
    List<String> archivePaths();

    List<ReceivedEmail> findByStatusOrderByReceivedAtDesc(ReceivedEmail.Status status);

    /** Everything the current generation of the rules has not read yet — the replay queue. */
    List<ReceivedEmail> findByExtractorVersionLessThan(int version);

    /** The replay queue, a batch at a time, so a full re-read never loads the archive into memory. */
    List<ReceivedEmail> findByExtractorVersionLessThanOrderByIdAsc(int version, org.springframework.data.domain.Pageable page);

    long countByExtractorVersionLessThan(int version);

    /** Every email that turned out to be about one order, oldest first — how an order is rebuilt. */
    List<ReceivedEmail> findByOrderIdOrderBySentAtAscIdAsc(Long orderId);
}
