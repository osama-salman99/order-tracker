package work.osmosis.ordersapp.order;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import work.osmosis.ordersapp.domain.Order;
import work.osmosis.ordersapp.domain.OrderClaim;
import work.osmosis.ordersapp.domain.OrderClaimRepository;
import work.osmosis.ordersapp.domain.OrderRepository;
import work.osmosis.ordersapp.domain.ReceivedEmail;
import work.osmosis.ordersapp.domain.ReceivedEmailRepository;

/**
 * The reader's own marks — arrived, never arrived, not a real order, replaced by — kept in a file
 * beside the database as well as in it.
 *
 * <p>Everything else in the database can be rebuilt from the email archive; these cannot, because
 * nobody wrote them in an email. When the database lost five weeks on 14 October 2026, the emails
 * came back from the archive and these were simply gone. So every change is also written here, and
 * whenever the reader catches up with the archive, any mark the file has and the database lacks is
 * put back.
 *
 * <p>An order is found again by what identifies it in the mail — its order and tracking numbers, and
 * failing those the ids of its emails — never by its row id, which a rebuilt database hands out
 * afresh. Only missing marks are filled in: the file is rewritten after every change, so a mark the
 * database has and the file lacks is one the reader took back, and stays taken back.
 */
@Component
public class ReaderMarks {

    private static final Logger log = LoggerFactory.getLogger(ReaderMarks.class);

    /**
     * One marked order.
     *
     * @param label shop and title, only so a person reading the file can tell what it is
     */
    public record Entry(
            String label,
            List<String> claims,
            List<String> messageIds,
            Instant deliveredAt,
            Instant notDeliveredAt,
            Instant dismissedAt,
            List<String> replacedByClaims,
            List<String> replacedByMessageIds) {}

    private final Path file;
    private final OrderRepository orders;
    private final OrderClaimRepository claims;
    private final ReceivedEmailRepository emails;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public ReaderMarks(
            @Value("${orders.marks-file:data/reader-marks.json}") String file,
            OrderRepository orders,
            OrderClaimRepository claims,
            ReceivedEmailRepository emails,
            ObjectMapper json,
            TransactionTemplate tx) {
        this.file = Path.of(file).toAbsolutePath().normalize();
        this.orders = orders;
        this.claims = claims;
        this.emails = emails;
        this.json = json;
        this.tx = tx;
    }

    /** Writes every marked order to the file. Called after each change the reader makes. */
    public synchronized void save() {
        List<Entry> entries = tx.execute(status -> {
            List<Entry> out = new ArrayList<>();
            for (Order order : orders.findAll()) {
                if (order.getMarkedDeliveredAt() == null
                        && order.getMarkedNotDeliveredAt() == null
                        && order.getDismissedAt() == null
                        && order.getReplacedByOrderId() == null) {
                    continue;
                }
                Order replacement = order.getReplacedByOrderId() == null
                        ? null
                        : orders.findById(order.getReplacedByOrderId()).orElse(null);
                out.add(new Entry(
                        order.getShopName() + " · " + order.getTitle(),
                        claimsOf(order),
                        messageIdsOf(order),
                        order.getMarkedDeliveredAt(),
                        order.getMarkedNotDeliveredAt(),
                        order.getDismissedAt(),
                        replacement == null ? List.of() : claimsOf(replacement),
                        replacement == null ? List.of() : messageIdsOf(replacement)));
            }
            return out;
        });
        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), "reader-marks-", ".part");
            try {
                json.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), entries);
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            log.warn("Could not write the reader's marks to {}", file, e);
        }
    }

    /**
     * Puts back any mark the file holds that the database has lost, then rewrites the file.
     *
     * @return how many orders got a mark back
     */
    public synchronized int restore() {
        if (!Files.exists(file)) {
            save();
            return 0;
        }
        List<Entry> entries;
        try {
            entries = List.of(json.readValue(file.toFile(), Entry[].class));
        } catch (JacksonException e) {
            // Leave the file exactly as it is: it may be the only copy of these marks.
            log.warn("Could not read the reader's marks from {}; leaving it untouched", file, e);
            return 0;
        }
        Integer restored = tx.execute(status -> {
            int count = 0;
            for (Entry entry : entries) {
                Order order = find(entry.claims(), entry.messageIds());
                if (order == null) {
                    continue;
                }
                boolean changed = false;
                if (order.getMarkedDeliveredAt() == null && order.getMarkedNotDeliveredAt() == null) {
                    if (entry.deliveredAt() != null) {
                        order.setMarkedDeliveredAt(entry.deliveredAt());
                        changed = true;
                    } else if (entry.notDeliveredAt() != null) {
                        order.setMarkedNotDeliveredAt(entry.notDeliveredAt());
                        changed = true;
                    }
                }
                if (order.getDismissedAt() == null && entry.dismissedAt() != null) {
                    order.setDismissedAt(entry.dismissedAt());
                    changed = true;
                }
                if (order.getReplacedByOrderId() == null && !isEmpty(entry.replacedByClaims(), entry.replacedByMessageIds())) {
                    Order replacement = find(entry.replacedByClaims(), entry.replacedByMessageIds());
                    if (replacement != null && !replacement.getId().equals(order.getId())) {
                        order.setReplacedByOrderId(replacement.getId());
                        changed = true;
                    }
                }
                if (changed) {
                    orders.save(order);
                    count++;
                }
            }
            return count;
        });
        if (restored != null && restored > 0) {
            log.warn("Put back the reader's marks on {} order(s) the database had lost them from", restored);
        }
        save();
        return restored == null ? 0 : restored;
    }

    private Order find(List<String> claimList, List<String> messageIds) {
        if (claimList != null && !claimList.isEmpty()) {
            for (OrderClaim claim : claims.findByClaimIn(claimList)) {
                Order order = orders.findById(claim.getOrderId()).orElse(null);
                if (order != null) {
                    return order;
                }
            }
        }
        for (String messageId : messageIds == null ? List.<String>of() : messageIds) {
            Long orderId = emails.findByMessageId(messageId).map(ReceivedEmail::getOrderId).orElse(null);
            if (orderId != null) {
                Order order = orders.findById(orderId).orElse(null);
                if (order != null) {
                    return order;
                }
            }
        }
        return null;
    }

    /** The order's identifying numbers. Not its {@code solo:} claims, which carry a row id. */
    private List<String> claimsOf(Order order) {
        return claims.findByOrderId(order.getId()).stream()
                .map(OrderClaim::getClaim)
                .filter(claim -> !claim.startsWith("solo:"))
                .sorted()
                .toList();
    }

    private List<String> messageIdsOf(Order order) {
        return emails.findByOrderIdOrderBySentAtAscIdAsc(order.getId()).stream()
                .map(ReceivedEmail::getMessageId)
                .filter(Objects::nonNull)
                .toList();
    }

    private static boolean isEmpty(List<String> a, List<String> b) {
        return (a == null || a.isEmpty()) && (b == null || b.isEmpty());
    }
}
