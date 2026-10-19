package work.osmosis.ordersapp.order;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import work.osmosis.ordersapp.domain.EmailCode;
import work.osmosis.ordersapp.domain.EmailCodeRepository;
import work.osmosis.ordersapp.domain.Order;
import work.osmosis.ordersapp.domain.OrderClaim;
import work.osmosis.ordersapp.domain.OrderClaimRepository;
import work.osmosis.ordersapp.domain.OrderRepository;
import work.osmosis.ordersapp.domain.ReceivedEmail;
import work.osmosis.ordersapp.domain.ReceivedEmailRepository;
import work.osmosis.ordersapp.extract.EmailExtractor;
import work.osmosis.ordersapp.extract.EmailFacts;
import work.osmosis.ordersapp.extract.Stage;
import work.osmosis.ordersapp.extract.TrackingNumber;
import work.osmosis.ordersapp.ingest.EmailArchive;
import work.osmosis.ordersapp.ingest.MimeReader;

/**
 * Turns emails into orders, and keeps doing it as more emails arrive about the same purchase.
 *
 * <p>The problem this solves is that one purchase produces a small scattered correspondence: a
 * confirmation from the shop, a shipping notice a day later, an update from a delivery company that
 * has never heard of the shop, and sometimes a second parcel with its own tracking. None of them is
 * a duplicate; all of them are the same order.
 *
 * <p>So nothing is keyed on a single field. Every email states whatever it can about which order it
 * belongs to — see {@link OrderClaim} — and emails that share any statement are the same order.
 * Because that is a transitive relation, an email can arrive that merges two orders previously
 * thought separate, and this handles that by moving the smaller into the larger rather than
 * pretending it cannot happen: a carrier's notice quoting two tracking numbers does exactly it.
 *
 * <p>Once the membership is settled the order is rebuilt from its emails, field by field, each field
 * taking the most recent email that actually said something about it. That is what "use the most
 * recent one" has to mean in practice — taking the newest email wholesale would blank out the total,
 * because the carrier's "we're coming Wednesday" has never heard of the price.
 */
@Service
public class OrderAssembler {

    private static final Logger log = LoggerFactory.getLogger(OrderAssembler.class);
    private static final ZoneId AMSTERDAM = ZoneId.of("Europe/Amsterdam");

    private final ReceivedEmailRepository emails;
    private final OrderRepository orders;
    private final OrderClaimRepository claims;
    private final EmailCodeRepository codes;
    private final EmailExtractor extractor;
    private final EmailArchive archive;
    private final MimeReader mime;

    public OrderAssembler(
            ReceivedEmailRepository emails,
            OrderRepository orders,
            OrderClaimRepository claims,
            EmailCodeRepository codes,
            EmailExtractor extractor,
            EmailArchive archive,
            MimeReader mime) {
        this.emails = emails;
        this.orders = orders;
        this.claims = claims;
        this.codes = codes;
        this.extractor = extractor;
        this.archive = archive;
        this.mime = mime;
    }

    /** Reads one email with the current rules and files it, replacing anything a previous run said. */
    @Transactional
    public void process(Long emailId) {
        ReceivedEmail email = emails.findById(emailId).orElse(null);
        if (email == null) {
            return;
        }
        Long previousOrderId = email.getOrderId();
        EmailFacts facts = extractor.read(
                email.getSubject(),
                email.getSenderAddress(),
                email.getSenderName(),
                email.getBodyText(),
                htmlOf(email),
                email.getSentAt());

        email.setExtracted(
                facts.shopKey(),
                facts.shopName(),
                facts.fromCarrier(),
                joinOrderNumbers(facts.orderNumbers()),
                joinTracking(facts.tracking()),
                facts.total(),
                facts.itemSummary(),
                facts.imageUrl(),
                facts.delivery() == null ? null : facts.delivery().from(),
                facts.delivery() == null ? null : facts.delivery().to(),
                facts.stage());
        email.setExtractorVersion(EmailExtractor.VERSION);

        recordCodes(email, facts);

        if (!facts.order()) {
            email.setStatus(ReceivedEmail.Status.IGNORED, facts.ignoredBecause());
            email.setOrderId(null);
        } else {
            email.setStatus(ReceivedEmail.Status.ORDER, null);
            Long orderId = attach(email, facts);
            email.setOrderId(orderId);
        }
        emails.save(email);

        // The email may have moved, which can leave its old order without a reason to exist.
        if (previousOrderId != null && !previousOrderId.equals(email.getOrderId())) {
            rebuild(previousOrderId);
        }
        if (email.getOrderId() != null) {
            rebuild(email.getOrderId());
        }
    }

    /**
     * The email's HTML, read back out of the archive.
     *
     * <p>Only the picture rule needs it, and it is fetched here rather than held in the database so
     * that the stored copy of an email stays one flattened string. A message whose archive file has
     * gone missing still extracts perfectly well from the text already stored — it just cannot show
     * a picture — so this never fails an email that would otherwise have been read.
     */
    private String htmlOf(ReceivedEmail email) {
        if (email.getArchivePath() == null || email.getArchivePath().isBlank()) {
            return null;
        }
        try {
            return mime.htmlOf(archive.read(email.getArchivePath()));
        } catch (Exception e) {
            log.debug("No archived HTML for email #{}: {}", email.getId(), e.toString());
            return null;
        }
    }

    /**
     * Finds the order this email belongs to, creating one or merging several as needed, and records
     * every identity the email claims so the next email can find its way here too.
     */
    private Long attach(ReceivedEmail email, EmailFacts facts) {
        Set<String> wanted = claimsOf(email, facts);
        Set<String> lookFor = new LinkedHashSet<>(wanted);
        lookFor.addAll(alsoAnswersTo(facts));
        List<OrderClaim> existing = claims.findByClaimIn(lookFor);
        Set<Long> matched = existing.stream().map(OrderClaim::getOrderId).collect(Collectors.toCollection(LinkedHashSet::new));
        matched.addAll(ordersThatMentioned(email, facts));

        Long target;
        if (matched.isEmpty()) {
            Order order = new Order();
            order.setShopKey(facts.shopKey());
            order.setShopName(facts.shopName());
            target = orders.save(order).getId();
        } else {
            // The oldest surviving order wins, so an order keeps the identity it was first given.
            target = matched.stream().min(Comparator.naturalOrder()).orElseThrow();
            for (Long other : matched) {
                if (!other.equals(target)) {
                    mergeInto(other, target);
                }
            }
        }
        Set<String> alreadyRecorded =
                claims.findByOrderId(target).stream().map(OrderClaim::getClaim).collect(Collectors.toSet());
        for (String claim : wanted) {
            if (!alreadyRecorded.contains(claim)) {
                claims.save(new OrderClaim(claim, target));
            }
        }
        return target;
    }

    /** Everything this email says about which order it is. */
    private Set<String> claimsOf(ReceivedEmail email, EmailFacts facts) {
        Set<String> result = new LinkedHashSet<>();
        for (TrackingNumber tracking : facts.tracking()) {
            result.add("track:" + tracking.number());
        }
        // A carrier's reference is claimed under the carrier's own name, and a shop's under the
        // shop's. Keeping the two apart is the point: the carrier does not say which shop it is
        // delivering for, so its number can never be allowed to match a shop's number of the same
        // digits. What it can and should match is the carrier's own other messages about the same
        // parcel, and that is worth having — DPD announces a consignment, promises a time slot for
        // it the next morning and quotes the same reference both times, and without this those sat
        // on the page as two separate half-known parcels.
        for (String number : facts.orderNumbers()) {
            result.add("order:" + facts.shopKey() + ":" + number);
        }
        if (result.isEmpty()) {
            // Nothing to tie this to anything else. It still deserves to be an order of its own —
            // plenty of confirmations state neither a number nor a parcel — so it gets an identity
            // only it can ever hold.
            result.add("solo:" + email.getId());
        }
        return result;
    }

    /**
     * The claims this email should <em>find</em> without holding them itself.
     *
     * <p>An email that merely mentions a code looks for an order that states the same number
     * plainly. It does not claim the code itself, because a code it never labelled is not something
     * it is entitled to speak for.
     *
     * <p>bol is the case in hand: its confirmation says "Bestelnummer: C000F30K33" and its shipping
     * notice ends with the same string alone under a satisfaction survey. Before this they were two
     * orders sitting next to each other.
     */
    private static Set<String> alsoAnswersTo(EmailFacts facts) {
        Set<String> result = new LinkedHashSet<>();
        if (facts.fromCarrier()) {
            return result;
        }
        for (String code : facts.unlabelledCodes()) {
            result.add("order:" + facts.shopKey() + ":" + code);
        }
        return result;
    }

    /**
     * The other half of the same question, for when the messages arrive the other way round.
     *
     * <p>{@link #alsoAnswersTo} only works if the email stating the number plainly was filed first.
     * When the shipping notice comes first it is filed knowing nothing, so the confirmation that
     * follows has to go looking: it asks whether any earlier email of this shop quietly mentioned
     * the number it states, and adopts that email's order if one did.
     *
     * <p>Only labelled numbers are ever used to search. A code is never matched against another
     * code, which is what keeps a constant printed in every one of a shop's emails from joining
     * them all together — that mistake welded 38 separate takeaway orders into one.
     */
    private Set<Long> ordersThatMentioned(ReceivedEmail email, EmailFacts facts) {
        if (facts.fromCarrier() || facts.orderNumbers().isEmpty() || facts.shopKey() == null) {
            return Set.of();
        }
        return codes.findByShopKeyAndCodeIn(facts.shopKey(), facts.orderNumbers()).stream()
                .filter(code -> !code.getEmailId().equals(email.getId()))
                .map(code -> emails.findById(code.getEmailId()).map(ReceivedEmail::getOrderId).orElse(null))
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Replaces what this email was last understood to have mentioned in passing. */
    private void recordCodes(ReceivedEmail email, EmailFacts facts) {
        codes.deleteByEmailId(email.getId());
        if (!facts.order() || facts.fromCarrier() || facts.shopKey() == null) {
            return;
        }
        for (String code : facts.unlabelledCodes()) {
            codes.save(new EmailCode(facts.shopKey(), code, email.getId()));
        }
    }

    private void mergeInto(Long from, Long into) {
        log.info("Merging order #{} into #{}: an email claimed both", from, into);
        // Everything else about an order can be worked out again from its emails, so it is left to
        // the rebuild. These two cannot: if the reader ticked the order that is about to disappear
        // off — or said it never turned up despite the shop's claim — that is a statement no email
        // will ever repeat, and losing it would put the order back where they had already dealt with
        // it. The earlier mark wins, being when the reader first said so.
        Order source = orders.findById(from).orElse(null);
        Order target = orders.findById(into).orElse(null);
        if (source != null && target != null) {
            if (earlier(source.getMarkedDeliveredAt(), target.getMarkedDeliveredAt())) {
                target.setMarkedDeliveredAt(source.getMarkedDeliveredAt());
            }
            if (earlier(source.getMarkedNotDeliveredAt(), target.getMarkedNotDeliveredAt())) {
                target.setMarkedNotDeliveredAt(source.getMarkedNotDeliveredAt());
            }
            if (earlier(source.getDismissedAt(), target.getDismissedAt())) {
                target.setDismissedAt(source.getDismissedAt());
            }
            // The carrier's tracking is not in any email either. Kept from whichever row looked
            // it up last; the next lookup replaces it anyway.
            if (later(source.getCarrierCheckedAt(), target.getCarrierCheckedAt())) {
                target.setCarrierTracking(source.getCarrierFrom(), source.getCarrierTo(), source.getCarrierWindow(),
                        source.getCarrierNote(), source.getCarrierDeliveredAt(), source.isCarrierAtPickupPoint(),
                        source.getCarrierCheckedAt());
            }
            // Same reasoning, third statement of the same kind: the reader said this order was made
            // good by another one, and no email will ever say it again.
            if (target.getReplacedByOrderId() == null) {
                target.setReplacedByOrderId(source.getReplacedByOrderId());
            }
            // ...but not at itself. A merge can bring both ends of a replacement link into one row —
            // the order that vanishes is the one this points at, or is the one pointing at this —
            // and an order cannot have been replaced by the order it now is.
            Long points = target.getReplacedByOrderId();
            if (into.equals(points) || from.equals(points)) {
                target.setReplacedByOrderId(null);
            }
            orders.save(target);
        }
        // Anything else pointing at the order about to disappear has to be told where it went, or it
        // is left naming a row that no longer exists.
        for (Order pointing : orders.findByReplacedByOrderId(from)) {
            if (pointing.getId().equals(from) || pointing.getId().equals(into)) {
                continue;   // both ends of the link have become one order; handled above
            }
            pointing.setReplacedByOrderId(into);
            orders.save(pointing);
        }
        claims.findByOrderId(from).forEach(claim -> claim.setOrderId(into));
        emails.findByOrderIdOrderBySentAtAscIdAsc(from).forEach(other -> other.setOrderId(into));
        orders.deleteById(from);
    }

    /** Whether the mark being kept is one the reader made, and made first. */
    private static boolean earlier(java.time.Instant candidate, java.time.Instant existing) {
        return candidate != null && (existing == null || candidate.isBefore(existing));
    }

    private static boolean later(java.time.Instant candidate, java.time.Instant existing) {
        return candidate != null && (existing == null || candidate.isAfter(existing));
    }

    /**
     * Rewrites an order from the emails that belong to it. Each field takes its value from the most
     * recent email that stated one, so a carrier's update can move the delivery date forward without
     * erasing the price the confirmation carried.
     */
    @Transactional
    public void rebuild(Long orderId) {
        Order order = orders.findById(orderId).orElse(null);
        if (order == null) {
            return;
        }
        List<ReceivedEmail> belonging = emails.findByOrderIdOrderBySentAtAscIdAsc(orderId);
        if (belonging.isEmpty()) {
            // Nothing left to have been replaced by this. Cleared rather than left behind, because a
            // link to a row that no longer exists would keep the order that holds it off the list of
            // outstanding orders for a reason nobody could see.
            for (Order pointing : orders.findByReplacedByOrderId(orderId)) {
                pointing.setReplacedByOrderId(null);
                orders.save(pointing);
            }
            claims.deleteByOrderId(orderId);
            orders.deleteById(orderId);
            return;
        }
        List<ReceivedEmail> newestFirst = new ArrayList<>(belonging);
        java.util.Collections.reverse(newestFirst);

        // The shop is whoever sold the thing. Carriers deliver for shops they never name, so their
        // emails are only consulted when nothing else is available.
        ReceivedEmail shopEmail = belonging.stream().filter(e -> !e.isFromCarrier()).findFirst().orElse(belonging.get(0));
        order.setShopKey(shopEmail.getShopKey());
        order.setShopName(shopEmail.getShopName());
        // What was bought, taken from the earliest email that actually listed it — that is the
        // confirmation, and the shipping updates that follow only ever say "your order". A subject
        // line is never used as a fallback: "Your order is successful" is the shop talking about the
        // order rather than naming anything, and a row that says it tells you less than a blank one.
        order.setTitle(belonging.stream()
                .map(ReceivedEmail::getItemSummary)
                .filter(summary -> summary != null && !summary.isBlank())
                .findFirst()
                .orElse(null));
        order.setOrderNumber(firstOrderNumber(belonging));
        order.setTotal(latest(newestFirst, ReceivedEmail::getTotal));
        // The picture, from the earliest email that showed one — the same email the item list came
        // from, so the row's words and its picture describe the same thing. A later email showing a
        // different product would be one of several in the order, and quietly swapping the picture
        // for it would make the row look like it had changed into something else.
        order.setImageUrl(belonging.stream()
                .map(ReceivedEmail::getImageUrl)
                .filter(url -> url != null && !url.isBlank())
                .findFirst()
                .orElse(null));

        Stage stage = stageFrom(belonging);
        order.setStage(stage);

        // The delivery date, from the most recent email that stated one. An order a shop has said
        // arrived is the exception: it takes the date from the email that said so, because the
        // estimate made a week earlier is not when it turned up, and leaving that estimate in place
        // put delivered orders in next week's part of a timeline sorted by delivery date.
        ReceivedEmail withDate = stage == Stage.DELIVERED
                ? newestFirst.stream()
                        .filter(e -> e.getStage() == Stage.DELIVERED && e.getExpectedFrom() != null)
                        .findFirst()
                        .orElse(null)
                : null;
        if (withDate == null) {
            withDate = newestFirst.stream().filter(e -> e.getExpectedFrom() != null).findFirst().orElse(null);
        }
        order.setExpectedFrom(withDate == null ? null : withDate.getExpectedFrom());
        order.setExpectedTo(withDate == null ? null : withDate.getExpectedTo());
        order.setDeliveryDateAssumed(withDate == null);

        // What the carrier's own tracking said, which is newer than any email: PostNL never puts
        // the day in its mails, and a shop's estimate is made before the parcel is even sorted. It
        // is not allowed to undo a return or a cancellation, which are the shop's last word.
        if (stage != Stage.RETURNED && stage != Stage.CANCELLED) {
            if (order.getCarrierDeliveredAt() != null) {
                stage = Stage.DELIVERED;
                LocalDate day = dayOf(order.getCarrierDeliveredAt());
                order.setExpectedFrom(day);
                order.setExpectedTo(day);
                order.setDeliveryDateAssumed(false);
            } else {
                // Waiting at a pickup point is not delivered, whatever an email called it.
                if (order.isCarrierAtPickupPoint()
                        && (stage.rank() < Stage.READY_FOR_PICKUP.rank() || stage == Stage.DELIVERED)) {
                    stage = Stage.READY_FOR_PICKUP;
                }
                if (order.getCarrierFrom() != null && !stage.finished()) {
                    order.setExpectedFrom(order.getCarrierFrom());
                    order.setExpectedTo(order.getCarrierTo() == null ? order.getCarrierFrom() : order.getCarrierTo());
                    order.setDeliveryDateAssumed(false);
                }
            }
            order.setStage(stage);
        }

        order.setFirstEmailAt(belonging.get(0).getSentAt());
        order.setLastEmailAt(newestFirst.get(0).getSentAt());
        order.setEmailCount(belonging.size());

        LocalDate lastDay = dayOf(order.getLastEmailAt());
        // The carrier can close an order after its last email, and that day is when it closed.
        if (order.getCarrierDeliveredAt() != null && dayOf(order.getCarrierDeliveredAt()).isAfter(lastDay)) {
            lastDay = dayOf(order.getCarrierDeliveredAt());
        }
        LocalDate sortOn = order.getExpectedFrom() != null ? order.getExpectedFrom() : lastDay;
        // An order that is over cannot still be arriving. Where a stale estimate outlives the email
        // that closed the order, the day it closed is the truthful place for it in the timeline.
        if (stage.finished() && sortOn.isAfter(lastDay)) {
            sortOn = lastDay;
        }
        order.setSortDate(sortOn);
        orders.save(order);
    }

    /**
     * How far along the order is, from its emails read in the order they arrived.
     *
     * <p>This used to be the furthest stage any email had claimed, which is wrong twice over: an
     * order delivered and then sent back stayed "Delivered" for ever, and one wrongly-read email
     * anywhere in a long correspondence outranked every correct one after it.
     *
     * <p>Reading them in arrival order fixes both, with one qualification. Mail about a parcel
     * genuinely arrives out of sequence — a carrier's "on its way" regularly overtakes the shop's
     * "we've packed it" — so an ordinary update only ever moves an order forwards. A final word is
     * different: cancelled, returned and delivered are all the shop closing the matter, so the
     * latest of those is taken as said, and nothing that was already in flight undoes it.
     */
    private static Stage stageFrom(List<ReceivedEmail> oldestFirst) {
        Stage current = Stage.UNKNOWN;
        for (ReceivedEmail email : oldestFirst) {
            current = advance(current, email.getStage());
        }
        return current;
    }

    /**
     * Where an order stands after one more email, given where it stood before. The single step of
     * {@link #stageFrom}, public so the order page can say what each email changed by the very same
     * rule that decided the order's state.
     */
    public static Stage advance(Stage current, Stage said) {
        if (said == null || said == Stage.UNKNOWN) {
            return current;
        }
        if (said.finished()) {
            return said;
        }
        return !current.finished() && said.rank() > current.rank() ? said : current;
    }

    /**
     * The shop's own reference, preferring the earliest email that stated one: the confirmation is
     * the message a person would recognise the number from, and later mail sometimes quotes a
     * different internal reference alongside it.
     */
    private String firstOrderNumber(List<ReceivedEmail> belonging) {
        for (ReceivedEmail email : belonging) {
            if (!email.isFromCarrier() && email.getOrderNumbers() != null && !email.getOrderNumbers().isBlank()) {
                return email.getOrderNumbers().split(" ")[0];
            }
        }
        return null;
    }

    private String latest(List<ReceivedEmail> newestFirst, java.util.function.Function<ReceivedEmail, String> field) {
        return newestFirst.stream().map(field).filter(v -> v != null && !v.isBlank()).findFirst().orElse(null);
    }

    private LocalDate dayOf(java.time.Instant instant) {
        return (instant == null ? java.time.Instant.now() : instant).atZone(AMSTERDAM).toLocalDate();
    }

    private static String joinOrderNumbers(Set<String> numbers) {
        return numbers.isEmpty() ? null : String.join(" ", numbers);
    }

    private static String joinTracking(Set<TrackingNumber> tracking) {
        return tracking.isEmpty()
                ? null
                : tracking.stream().map(t -> t.carrier() + ":" + t.number()).collect(Collectors.joining(" "));
    }

    /** Reads the tracking numbers back out of an email row, for the detail page. */
    public static List<TrackingNumber> parseTracking(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        List<TrackingNumber> result = new ArrayList<>();
        for (String part : stored.split(" ")) {
            String[] halves = part.split(":", 2);
            if (halves.length == 2) {
                result.add(new TrackingNumber(halves[0], halves[1]));
            }
        }
        return result;
    }
}
