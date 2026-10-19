package work.osmosis.ordersapp.web;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import work.osmosis.ordersapp.domain.Order;
import work.osmosis.ordersapp.domain.OrderRepository;
import work.osmosis.ordersapp.domain.ReceivedEmail;
import work.osmosis.ordersapp.domain.ReceivedEmailRepository;
import work.osmosis.ordersapp.extract.ReplacementRules;
import work.osmosis.ordersapp.extract.Stage;
import work.osmosis.ordersapp.extract.TrackingNumber;
import work.osmosis.ordersapp.order.OrderAssembler;
import work.osmosis.ordersapp.order.ReaderMarks;

/**
 * What the two pages read.
 *
 * <p>The timeline comes back in exactly one order — earliest expected delivery first — because the
 * page has no other way of showing it. Sorting is not an option offered to the reader, so it is not
 * an option offered here either.
 */
@RestController
public class OrdersController {

    private final OrderRepository orders;
    private final ReceivedEmailRepository emails;
    private final String deliveryPostcode;
    private final ReaderMarks marks;

    public OrdersController(
            OrderRepository orders,
            ReceivedEmailRepository emails,
            @Value("${orders.delivery-postcode:}") String deliveryPostcode,
            ReaderMarks marks) {
        this.orders = orders;
        this.emails = emails;
        this.deliveryPostcode = deliveryPostcode;
        this.marks = marks;
    }

    /**
     * One row of the timeline.
     *
     * @param finished whether this is over and belongs in the folded-away history
     * @param markedDelivered whether the reader said it had arrived
     * @param markedNotDelivered whether the reader said it had not, against a shop that said it had
     * @param stageFinished whether a shop said it arrived, which is a claim and not always a fact
     * @param quiet whether nothing has been heard about it in a long time, which is worth saying
     *     but is not the same as knowing it arrived
     * @param image where to ask this service for the shop's picture of what was bought, or null when
     *     no email about this order showed one. Deliberately this service's own address rather than
     *     the retailer's: see {@code ProductImages} for why the page never links to a shop directly
     * @param replacedBy the order sent out to make up for this one, when the reader has said there
     *     was one. Its presence is what closes this order
     * @param replaces the orders this one was sent out to make up for, usually none and occasionally
     *     more than one
     * @param dismissed whether the reader said this is not a real order
     */
    public record OrderRow(
            Long id,
            String shop,
            String title,
            String orderNumber,
            String total,
            LocalDate expectedFrom,
            LocalDate expectedTo,
            LocalDate sortDate,
            boolean dateAssumed,
            String stage,
            String stageLabel,
            boolean finished,
            boolean markedDelivered,
            boolean markedNotDelivered,
            boolean stageFinished,
            boolean quiet,
            int emailCount,
            String image,
            List<Parcel> parcels,
            Link replacedBy,
            List<Link> replaces,
            boolean dismissed,
            String carrierWindow,
            String carrierNote,
            Instant carrierCheckedAt) {}

    /**
     * Another order, named the way a person would recognise it.
     *
     * <p>Enough to show and to follow, and nothing else: the page has the whole list in hand and can
     * look the rest up itself, and an order carrying full copies of its neighbours would grow a
     * second copy of every field that ever has to be kept in step.
     */
    public record Link(Long id, String shop, String name) {}

    /**
     * An order that might be the replacement for another, offered for the reader to pick from.
     *
     * @param suggested whether there is a reason to think this is the one — the shop's own words
     *     saying it is sending something out again, or a total matching to the cent
     * @param why that reason in the words the page shows, or null when it is only a possibility
     */
    public record Candidate(
            Long id,
            String shop,
            String title,
            String orderNumber,
            String total,
            LocalDate sortDate,
            boolean suggested,
            String why) {}

    /** A parcel number with the page that will show where it is, when the carrier is known. */
    public record Parcel(String carrier, String number, String url) {}

    /**
     * One of the emails an order was assembled from, with what it changed about the order: the state
     * it moved the order to, the delivery date it set, and the parcel numbers it first mentioned.
     * Each is null or empty when the email left that part as it was.
     */
    public record EmailRow(
            Long id,
            String subject,
            String sender,
            String sentAt,
            String stage,
            String stageLabel,
            String became,
            String becameLabel,
            String expectedFrom,
            String expectedTo,
            List<String> newParcels) {}

    public record OrderDetail(OrderRow order, List<EmailRow> emails) {}

    @GetMapping("/api/orders")
    public List<OrderRow> list() {
        // Both ends of every replacement link, worked out once for the whole list rather than looked
        // up again for each of a few hundred rows that almost never have one.
        List<Order> timeline = orders.timeline();
        Map<Long, Order> byId = new LinkedHashMap<>();
        timeline.forEach(order -> byId.put(order.getId(), order));
        Map<Long, List<Order>> replacing = new LinkedHashMap<>();
        for (Order order : timeline) {
            if (order.getReplacedByOrderId() != null) {
                replacing.computeIfAbsent(order.getReplacedByOrderId(), key -> new ArrayList<>()).add(order);
            }
        }
        List<OrderRow> rows = new ArrayList<>();
        for (Order order : timeline) {
            rows.add(rowOf(
                    order,
                    order.getReplacedByOrderId() == null ? null : byId.get(order.getReplacedByOrderId()),
                    replacing.getOrDefault(order.getId(), List.of())));
        }
        return rows;
    }

    @GetMapping("/api/orders/{id}")
    public ResponseEntity<OrderDetail> detail(@PathVariable Long id) {
        Optional<Order> found = orders.findById(id);
        if (found.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        List<EmailRow> story = new ArrayList<>();
        // Replayed alongside the story, email by email, so each row can say what it changed.
        Stage standing = Stage.UNKNOWN;
        LocalDate knownFrom = null;
        LocalDate knownTo = null;
        Set<String> knownParcels = new HashSet<>();
        for (ReceivedEmail email : emails.findByOrderIdOrderBySentAtAscIdAsc(id)) {
            Stage stage = email.getStage() == null ? Stage.UNKNOWN : email.getStage();
            Stage after = OrderAssembler.advance(standing, stage);
            Stage became = after == standing ? null : after;
            standing = after;
            LocalDate setFrom = null;
            LocalDate setTo = null;
            if (email.getExpectedFrom() != null
                    && !(email.getExpectedFrom().equals(knownFrom) && Objects.equals(email.getExpectedTo(), knownTo))) {
                setFrom = email.getExpectedFrom();
                setTo = email.getExpectedTo();
                knownFrom = setFrom;
                knownTo = setTo;
            }
            List<String> newParcels = new ArrayList<>();
            for (TrackingNumber tracking : OrderAssembler.parseTracking(email.getTrackingNumbers())) {
                if (knownParcels.add(tracking.number())) {
                    newParcels.add(tracking.number());
                }
            }
            story.add(new EmailRow(
                    email.getId(),
                    email.getSubject(),
                    email.getSenderName() == null || email.getSenderName().isBlank()
                            ? email.getSenderAddress()
                            : email.getSenderName(),
                    email.getSentAt() == null ? null : email.getSentAt().toString(),
                    stage.name(),
                    stage.label(),
                    became == null ? null : became.name(),
                    became == null ? null : became.label(),
                    setFrom == null ? null : setFrom.toString(),
                    setTo == null ? null : setTo.toString(),
                    newParcels));
        }
        return ResponseEntity.ok(new OrderDetail(rowOf(found.get()), story));
    }

    /**
     * How long without a word before an order is worth remarking on.
     *
     * <p>Shops stop writing once the thing has arrived, and plenty of them never send the email that
     * would say so. This once counted as delivery, which kept the page short but meant the app was
     * quietly deciding that orders it knew nothing about had arrived. That decision is the reader's:
     * an order stays out in the open until a shop confirms delivery or the reader ticks it off. So
     * silence is now only ever said out loud — "went quiet" is a prompt to look, not a conclusion.
     */
    private static final Duration LONG_SILENCE = Duration.ofDays(30);

    /** One row, for the pages that are looking at a single order and can afford to ask. */
    private OrderRow rowOf(Order order) {
        Order replacement = order.getReplacedByOrderId() == null
                ? null
                : orders.findById(order.getReplacedByOrderId()).orElse(null);
        return rowOf(order, replacement, orders.findByReplacedByOrderId(order.getId()));
    }

    private OrderRow rowOf(Order order, Order replacement, List<Order> replaces) {
        Stage stage = order.getStage() == null ? Stage.UNKNOWN : order.getStage();
        boolean quiet = !stage.finished() && !order.isSettled() && hasGoneQuiet(order);
        return new OrderRow(
                order.getId(),
                order.getShopName(),
                order.getTitle(),
                order.getOrderNumber(),
                order.getTotal(),
                order.getExpectedFrom(),
                order.getExpectedTo(),
                order.getSortDate(),
                order.isDeliveryDateAssumed(),
                // A hand-marked order reads as delivered, because that is what the reader said. Long
                // silence gets its own neutral state instead of the last thing a shop happened to
                // say, because "On its way" is a poor description of an order nobody has mentioned
                // since 2022.
                stateOf(order, stage, quiet),
                labelOf(order, stage, quiet),
                order.isSettled(),
                order.getMarkedDeliveredAt() != null,
                order.readerSaysNotHere(),
                stage.finished(),
                quiet,
                order.getEmailCount(),
                order.getImageUrl() == null || order.getImageUrl().isBlank()
                        ? null
                        : "/api/orders/" + order.getId() + "/image",
                parcelsOf(order.getId()),
                linkTo(replacement),
                replaces.stream().map(OrdersController::linkTo).toList(),
                order.getDismissedAt() != null,
                order.getCarrierWindow(),
                order.getCarrierNote(),
                order.getCarrierCheckedAt());
    }

    /** How another order is named on this one: the shop's reference if it has one, else the goods. */
    private static Link linkTo(Order other) {
        if (other == null) {
            return null;
        }
        String name = other.getOrderNumber();
        if (name == null || name.isBlank()) {
            name = other.getTitle();
        }
        if (name == null || name.isBlank()) {
            name = "order #" + other.getId();
        }
        return new Link(other.getId(), other.getShopName(), name);
    }

    private static String stateOf(Order order, Stage stage, boolean quiet) {
        if (order.getDismissedAt() != null) {
            return "DISMISSED";
        }
        // Checked before everything else, because it exists precisely to contradict what follows.
        if (order.readerSaysNotHere()) {
            // ...except by the thing that closes the question for good. An order that never turned up
            // and was sent out again has been dealt with, and going on calling it missing would keep
            // asking the reader to do something they have already done.
            return order.getReplacedByOrderId() != null ? "REPLACED" : "NOT_HERE";
        }
        if (order.getReplacedByOrderId() != null) {
            return "REPLACED";
        }
        if (order.getMarkedDeliveredAt() != null && !stage.finished()) {
            return "DELIVERED";
        }
        return quiet ? "QUIET" : stage.name();
    }

    private static String labelOf(Order order, Stage stage, boolean quiet) {
        if (order.getDismissedAt() != null) {
            return "Not a real order";
        }
        // Short enough for the badge on a row, and it says the part that is news: this order is over
        // because another one took its place. Which order that was is written out underneath, where
        // there is room for it and for the link.
        if (order.getReplacedByOrderId() != null) {
            return "Re-ordered";
        }
        // Says who claimed what, rather than picking a winner: the shop's mail said it arrived, and
        // the reader says it did not. Both of those are worth seeing at a glance, and "Missing" on
        // its own would hide the half of it that explains why the order looks settled elsewhere.
        if (order.readerSaysNotHere()) {
            return stage.finished() ? "Said delivered · never arrived" : "Never arrived";
        }
        if (order.getMarkedDeliveredAt() != null && !stage.finished()) {
            return "Delivered";
        }
        return quiet ? "Went quiet" : stage.label();
    }

    private boolean hasGoneQuiet(Order order) {
        return order.getLastEmailAt() != null
                && order.getLastEmailAt().isBefore(Instant.now().minus(LONG_SILENCE));
    }

    /**
     * The reader's own word that an order arrived, for the many that never got a delivery email.
     *
     * <p>Kept separately from everything the emails say, because rebuilding an order from its emails
     * happens whenever the rules improve and must never undo this.
     */
    @PostMapping("/api/orders/{id}/delivered")
    public ResponseEntity<OrderRow> markDelivered(@PathVariable Long id) {
        return setMark(id, Instant.now());
    }

    /** Undo — for a row ticked off by mistake. */
    @DeleteMapping("/api/orders/{id}/delivered")
    public ResponseEntity<OrderRow> unmarkDelivered(@PathVariable Long id) {
        return setMark(id, null);
    }

    /**
     * The reader's word that an order never turned up, whatever the shop's mail said.
     *
     * <p>The other direction of the same control, and the one that matters more: a shop saying a
     * parcel was delivered is a claim about the world that is sometimes simply untrue, and this is
     * the only way to say so. It is written down rather than acted on — nothing about the emails is
     * altered, and the order goes back among the outstanding ones where it can be chased.
     */
    @PostMapping("/api/orders/{id}/not-delivered")
    public ResponseEntity<OrderRow> markNotDelivered(@PathVariable Long id) {
        return setNotHere(id, Instant.now());
    }

    /** Undo, for either kind of mark: back to whatever the emails say. */
    @DeleteMapping("/api/orders/{id}/not-delivered")
    public ResponseEntity<OrderRow> unmarkNotDelivered(@PathVariable Long id) {
        return setNotHere(id, null);
    }

    /**
     * The reader's word that this is not a real order at all, so it leaves the list and the numbers.
     * Nothing about its emails changes, and it is undone the same way as the other marks.
     */
    @PostMapping("/api/orders/{id}/dismissed")
    public ResponseEntity<OrderRow> dismiss(@PathVariable Long id) {
        return update(id, order -> order.setDismissedAt(Instant.now()));
    }

    @DeleteMapping("/api/orders/{id}/dismissed")
    public ResponseEntity<OrderRow> undismiss(@PathVariable Long id) {
        return update(id, order -> order.setDismissedAt(null));
    }

    private ResponseEntity<OrderRow> setMark(Long id, Instant when) {
        return update(id, order -> {
            order.setMarkedDeliveredAt(when);
            // The two marks contradict each other, so one replaces the other rather than sitting
            // beside it: saying an order arrived is also withdrawing any claim that it had not.
            order.setMarkedNotDeliveredAt(null);
        });
    }

    private ResponseEntity<OrderRow> setNotHere(Long id, Instant when) {
        return update(id, order -> {
            order.setMarkedNotDeliveredAt(when);
            order.setMarkedDeliveredAt(null);
        });
    }

    /**
     * Says that this order never came and that another one was sent out in its place.
     *
     * <p>A link rather than a merge, and that is the whole design. The two are genuinely two orders
     * — two numbers, two confirmations, two delivery dates — and folding them together would be
     * undone the next time the rules improve and every order is rebuilt from its emails. What is
     * true is only this: the first one is finished with, and the second one is why.
     */
    @PostMapping("/api/orders/{id}/replaced-by/{otherId}")
    public ResponseEntity<OrderRow> markReplaced(@PathVariable Long id, @PathVariable Long otherId) {
        if (id.equals(otherId) || orders.findById(otherId).isEmpty() || leadsBackTo(otherId, id)) {
            // An order cannot stand in for itself, nor for something already standing in for it.
            // Chains are fine — a replacement that also went missing is replaced in its turn — but a
            // ring would leave every order in it waiting on the next one for ever.
            return ResponseEntity.badRequest().build();
        }
        return update(id, order -> order.setReplacedByOrderId(otherId));
    }

    /** Undo — for a link made to the wrong order, or one the shop turned out to have delivered. */
    @DeleteMapping("/api/orders/{id}/replaced-by")
    public ResponseEntity<OrderRow> unmarkReplaced(@PathVariable Long id) {
        return update(id, order -> order.setReplacedByOrderId(null));
    }

    /** Whether following the replacements out of {@code from} ever arrives back at {@code target}. */
    private boolean leadsBackTo(Long from, Long target) {
        Long at = from;
        for (int steps = 0; at != null && steps < 50; steps++) {
            if (at.equals(target)) {
                return true;
            }
            at = orders.findById(at).map(Order::getReplacedByOrderId).orElse(null);
        }
        return false;
    }

    /**
     * The orders that could be the replacement for this one, likeliest first.
     *
     * <p>Nothing in the mail joins a replacement to what it replaces — the shop's own confirmation
     * of one names only the new number — so this does not try to decide. It narrows: the same shop,
     * after this order, not already spoken for. Then it says which of those have a reason behind
     * them, so that the obvious answer is at the top and the reader is still the one choosing.
     */
    @GetMapping("/api/orders/{id}/replacements")
    public ResponseEntity<List<Candidate>> replacementCandidates(@PathVariable Long id) {
        Optional<Order> found = orders.findById(id);
        if (found.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Order lost = found.get();
        List<Candidate> candidates = new ArrayList<>();
        for (Order other : orders.timeline()) {
            if (other.getId().equals(id)
                    || other.getReplacedByOrderId() != null
                    || !sameShop(lost, other)
                    || !isAfter(other, lost)
                    || leadsBackTo(other.getId(), id)) {
                continue;
            }
            String why = reasonToSuggest(lost, other);
            candidates.add(new Candidate(
                    other.getId(),
                    other.getShopName(),
                    other.getTitle(),
                    other.getOrderNumber(),
                    other.getTotal(),
                    other.getSortDate(),
                    why != null,
                    why));
        }
        // Suggested ones first, then the most recent, because a replacement is arranged in a phone
        // call that has just happened.
        candidates.sort(Comparator.comparing(Candidate::suggested).reversed()
                .thenComparing(Candidate::id, Comparator.reverseOrder()));
        return ResponseEntity.ok(candidates.size() > MOST_CANDIDATES
                ? candidates.subList(0, MOST_CANDIDATES)
                : candidates);
    }

    /** How many are worth offering. Past this it is a list to search rather than a list to pick from. */
    private static final int MOST_CANDIDATES = 12;

    private static boolean sameShop(Order lost, Order other) {
        return lost.getShopKey() != null && lost.getShopKey().equals(other.getShopKey());
    }

    /** A replacement is arranged after the thing it replaces failed to turn up, never before. */
    private static boolean isAfter(Order other, Order lost) {
        return other.getFirstEmailAt() != null
                && lost.getFirstEmailAt() != null
                && other.getFirstEmailAt().isAfter(lost.getFirstEmailAt());
    }

    /**
     * Why this order looks like the replacement, in the words the page shows — or null when it is
     * merely possible, which most of them are.
     */
    private String reasonToSuggest(Order lost, Order other) {
        for (ReceivedEmail email : emails.findByOrderIdOrderBySentAtAscIdAsc(other.getId())) {
            if (ReplacementRules.says(email.getSubject(), email.getBodyText())) {
                return "the shop says this one is a replacement";
            }
        }
        if (lost.getTotal() != null && lost.getTotal().equals(other.getTotal())) {
            return "same total";
        }
        return null;
    }

    private ResponseEntity<OrderRow> update(Long id, java.util.function.Consumer<Order> change) {
        Optional<Order> found = orders.findById(id);
        if (found.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Order order = found.get();
        change.accept(order);
        orders.save(order);
        // Also kept outside the database: these are the one thing the archive cannot rebuild.
        marks.save();
        return ResponseEntity.ok(rowOf(order));
    }

    /**
     * The token the pages need before they may change anything.
     *
     * <p>These are static files rather than rendered templates, so there is nowhere to stamp the
     * token into the HTML; the page asks for it instead. It is behind the same login as everything
     * else, so this hands it only to someone who is already signed in — which is the whole point of
     * the check.
     */
    @GetMapping("/api/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    /**
     * The parcel numbers of the whole order, gathered from all of its emails.
     *
     * <p>The same number usually appears in several of them, and often the shop mentions it without
     * naming a carrier while the carrier's own email does. So the first mention that names a carrier
     * wins, and a bare number is only kept when nothing better turns up.
     */
    private List<Parcel> parcelsOf(Long orderId) {
        Map<String, String> carrierByNumber = new LinkedHashMap<>();
        for (ReceivedEmail email : emails.findByOrderIdOrderBySentAtAscIdAsc(orderId)) {
            for (TrackingNumber tracking : OrderAssembler.parseTracking(email.getTrackingNumbers())) {
                String known = carrierByNumber.get(tracking.number());
                if (known == null || known.isBlank()) {
                    carrierByNumber.put(tracking.number(), tracking.carrier());
                }
            }
        }
        List<Parcel> parcels = new ArrayList<>();
        carrierByNumber.forEach((number, carrier) ->
                parcels.add(new Parcel(
                        carrier, number, new TrackingNumber(carrier, number).trackingUrl(deliveryPostcode))));
        return parcels;
    }
}
