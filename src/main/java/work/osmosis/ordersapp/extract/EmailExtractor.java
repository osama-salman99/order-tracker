package work.osmosis.ordersapp.extract;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Reads one archived email and says what it is about.
 *
 * <p>The whole of this package is rules — patterns and word lists, no model and no network call.
 * That is a decision with a consequence: the rules will be wrong about shops nobody has taught them
 * about yet. What makes it workable is that the raw message is kept forever and every stored email
 * records which version of the rules last read it, so improving a pattern here and bumping
 * {@link #VERSION} re-reads the entire history rather than only tomorrow's mail.
 *
 * <p>So the ordering below matters. Tracking numbers are found first because they are the safest
 * thing in an email, and knowing them keeps the order-number rules from claiming one by mistake and
 * lets the classifier trust an email whose subject line otherwise reads like an advertisement.
 */
@Component
public class EmailExtractor {

    /**
     * Bump this whenever any rule in this package changes. Every stored email with a lower number is
     * read again in the background, so a fix reaches the whole archive and not just new arrivals.
     */
    public static final int VERSION = 18;

    private static final ZoneId AMSTERDAM = ZoneId.of("Europe/Amsterdam");

    /**
     * @param subject the email's subject line
     * @param senderAddress the address it came from, after any forwarding has been unwrapped
     * @param senderName the name beside that address, which is the only thing naming a shop that
     *     sends through a host shared by thousands of them
     * @param text the body, already flattened out of HTML
     * @param html the same body before it was flattened, or null when the message had no HTML part.
     *     Two rules need it, and for the same reason: flattening keeps what a person would read and
     *     throws away everything else, which is where the pictures and the shop's own machine-readable
     *     statement of the parcel number both live
     * @param sentAt when it was sent, which is what all relative delivery wording is measured from
     */
    public EmailFacts read(
            String subject,
            String senderAddress,
            String senderName,
            String text,
            String html,
            java.time.Instant sentAt) {
        Senders.Sender sender = Senders.identify(senderAddress, senderName);
        String body = text == null ? "" : text;
        String subjectLine = subject == null ? "" : subject;

        Set<TrackingNumber> tracking = TrackingRules.find(
                subjectLine + "\n" + body, html, sender.carrier() ? sender.name() : null);
        Set<String> orderNumbers = OrderNumberRules.find(subjectLine, body, tracking);

        OrderClassifier.Verdict verdict = OrderClassifier.classify(
                senderAddress, sender.carrier(), subjectLine, body, orderNumbers, tracking);
        if (!verdict.order()) {
            return new EmailFacts(false, verdict.reason(), sender.key(), sender.name(), sender.carrier(),
                    Set.of(), Set.of(), Set.of(), null, null, null, null, Stage.UNKNOWN);
        }

        LocalDate sentOn = (sentAt == null ? java.time.Instant.now() : sentAt).atZone(AMSTERDAM).toLocalDate();
        Stage stage = StageRules.read(subjectLine, body);
        DeliveryEstimate delivery = DateRules.find(subjectLine + "\n" + body, sentOn);
        if (delivery == null && stage == Stage.DELIVERED) {
            // "Your parcel has been delivered" rarely repeats the date. The email is the evidence.
            delivery = DeliveryEstimate.on(sentOn);
        }

        // Read once and used twice: the picture rule matches the shop's alt text against the item
        // name, so it needs the same answer the page will show rather than a second reading of it.
        String items = ItemRules.find(subjectLine, body);

        // A carrier's subject usually names the shop it is delivering for, and that name is worth
        // more on the page than the carrier's own. It stays out of the order's identity: the key
        // below is still the carrier's, so nothing is ever merged on a shop's name alone.
        String shopName = sender.name();
        if (sender.carrier()) {
            String named = Senders.shopNamedInCarrierSubject(subjectLine);
            if (named != null) {
                shopName = named;
            }
        }

        return new EmailFacts(
                true,
                null,
                sender.key(),
                shopName,
                sender.carrier(),
                orderNumbers,
                // Only worth gathering when the email named no reference of its own: an email that
                // did state one has already said everything it can, and its other codes are article
                // numbers.
                orderNumbers.isEmpty() && !sender.carrier()
                        ? OrderNumberRules.findUnlabelled(subjectLine, body)
                        : Set.of(),
                tracking,
                // A carrier does not know what the order cost, so any sum in its mail is something
                // else — the customs duty it is asking to be paid before it will hand the parcel
                // over. One order was shown at €26.71 that way, which was the duty on it.
                sender.carrier() ? null : MoneyRules.findTotal(body),
                items,
                // A delivery company shows a cartoon van, never the goods: it has no idea what is in
                // the parcel. Only the shop's own mail is worth looking through for a picture.
                sender.carrier() ? null : ImageRules.find(html, items),
                delivery,
                stage);
    }
}
