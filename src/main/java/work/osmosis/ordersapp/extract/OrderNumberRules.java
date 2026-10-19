package work.osmosis.ordersapp.extract;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the shop's own reference for an order.
 *
 * <p>This is the spine of an order's identity: unlike a tracking number it exists from the
 * confirmation email onwards, and it survives every later message about the same purchase. It is
 * only unique within one shop, though — this mailbox holds several shops whose numbers are just a
 * short run of digits, and one Shopify-built shop numbers its orders {@code #1234} — so it is never
 * used on its own, always paired with the sender.
 *
 * <p>There is no useful shape to match: the corpus has shapes like {@code A0001B2CD3}, {@code 1234567890},
 * {@code ABCNL0012345678}, {@code NL1A2BCDEF3G-A}, {@code ABC12345678}, {@code SHOP-12345} and
 * {@code 123-4567890-1234567}. So nothing is matched by shape alone — a candidate is only taken when
 * a word like "order number" or "bestelnummer" introduces it, or when it is written with a hash in a
 * subject line, which shops only do for order numbers.
 */
public final class OrderNumberRules {

    private static final Pattern ANCHORED = Pattern.compile(
            "(?:order\\s*(?:number|no\\.?|nr\\.?|id|#)|ordernummer|bestelnummer|bestelling(?:snummer)?"
                    + "|confirmation\\s*number|web\\s*order|purchase\\s*(?:number|no)|referentie"
                    + "|reference\\s*(?:number|no)?|factuurnummer|invoice\\s*(?:number|no)"
                    // A shop that was walked into rather than ordered from emails a till receipt,
                    // and its ticket number is the only reference such a purchase ever has. Ten of
                    // these sat on the page as a bare shop name.
                    + "|kasticket\\s*(?:nr\\.?|nummer)?)"
                    // A shop that wants the number noticed puts it on a line of its own: "Your order
                    // number is:" and then the number underneath. The blank space between label and
                    // value was already allowed for; the connecting word was not, and one word is
                    // all that stood between the largest group of orders in this mailbox — the
                    // takeaway ones — and stating no reference at all, each meal an order that
                    // nothing could ever be joined to. The word is required to stand alone, so that
                    // a reference beginning "IS" keeps its first two letters.
                    + "[ \\t]*(?:\\bis\\b|\\bluidt\\b)?[^\\p{Alnum}\\n]{0,12}#?\\s*"
                    // The value has to have a digit in it, checked before it is taken rather than
                    // after. Without that the search stopped on the first word it reached and gave
                    // up there: "Je bestelling" is a heading, the blank line under it is crossed
                    // looking for a value, and the word "Bestelnummer" on the next line was taken
                    // as the number itself — so the real one, two characters further on, was never
                    // read and a shop's only order was thrown away as a support email.
                    + "(?=[A-Z0-9-]{0,28}\\d)([A-Z0-9][A-Z0-9-]{3,28})",
            Pattern.CASE_INSENSITIVE);

    /**
     * The reference written straight after the word "order" in a subject line, with nothing to
     * introduce it: "Your order  12345 is confirmed". Only ever read in a subject, where a run of
     * digits sitting next to that word is the order and not, as it would be in a body, a price, a
     * quantity or an article number. Two of this shop's purchases were split in half without it.
     */
    private static final Pattern BARE_IN_SUBJECT =
            Pattern.compile("\\b(?:order|bestelling)\\s{1,3}#?\\s?(\\d{4,12})\\b", Pattern.CASE_INSENSITIVE);

    /**
     * The reference a subject line ends on, after a dash: "Order Confirmation - WO00012345". Three
     * messages about one purchase — the confirmation, the invoice and the delivery
     * notice — each sat on the page as an order of its own, all three quoting this in plain sight.
     * A parcel number written the same way is already excluded, because tracking is read first.
     */
    private static final Pattern TRAILING_IN_SUBJECT =
            Pattern.compile("[-–—]\\s*([A-Z]{1,4}\\d{5,20}|\\d{6,20})\\s*$", Pattern.CASE_INSENSITIVE);

    /**
     * A hash followed by digits. Shops write order numbers this way and almost nothing else — with
     * one exception that cost real accuracy here: an HTML numeric entity looks identical. Marketing
     * email is padded with hundreds of {@code &#8204;} zero-width characters to stretch the preview
     * line, and reading those as order numbers welded every one of a shop's orders into a single
     * one. Hence the refusal to match a hash that follows an ampersand.
     */
    private static final Pattern HASHED =
            Pattern.compile("(?<!&)#\\s?([0-9][0-9A-Z-]{2,26})", Pattern.CASE_INSENSITIVE);

    /**
     * The order number as a link's own query parameter, which is the one place in an email where it
     * is labelled unambiguously by the shop itself. The separator is written both ways because a
     * link that has been through a click tracker arrives with its "=" percent-encoded as {@code %3D}
     * — left alone, that turns IKEA's {@code orderId=1234567890} into the order number
     * "3D1234567890" and every such email claims an order of its own.
     */
    private static final Pattern IN_LINK = Pattern.compile(
            "(?:orderno|orderid|ordernumber|order_number|ordernummer|bestelnummer|bestelnr)"
                    + "(?:=|%3D)([A-Z0-9][A-Z0-9-]{3,27})",
            Pattern.CASE_INSENSITIVE);

    private OrderNumberRules() {}

    /**
     * The order numbers the text can be trusted to contain.
     *
     * @param subject the email's subject line, searched for the hash form as well as the body
     * @param text the flattened body
     * @param tracking numbers already recognised as parcel numbers, which must not be taken for
     *     order numbers as well — several shops introduce their tracking number with the word
     *     "reference"
     */
    public static Set<String> find(String subject, String text, Set<TrackingNumber> tracking) {
        Set<String> found = new LinkedHashSet<>();
        Set<String> parcelNumbers = new LinkedHashSet<>();
        tracking.forEach(t -> parcelNumbers.add(t.number()));

        // Links are read by parameter name; everywhere else they are blanked out first, because the
        // words around a link describe the page it opens, not the number buried in its address.
        String prose = Text.withoutLinks(text);

        collect(IN_LINK, text, found, parcelNumbers);
        collect(ANCHORED, subject, found, parcelNumbers);
        collect(ANCHORED, prose, found, parcelNumbers);
        collect(HASHED, subject, found, parcelNumbers);
        collect(BARE_IN_SUBJECT, subject, found, parcelNumbers);
        collect(TRAILING_IN_SUBJECT, subject, found, parcelNumbers);
        // In a body, only look for the hash form near the top: shops repeat "#12345" in footers and
        // unrelated recommendations further down.
        collect(HASHED, head(prose), found, parcelNumbers);
        return found;
    }

    /**
     * Every code in the email that <em>might</em> be the shop's reference, for an email that states
     * no reference anybody labelled.
     *
     * <p>Shops do write the order number with nothing to introduce it. bol's shipping email is the
     * case that prompted this: it ends with {@code C000F30K33} alone on a line below a satisfaction
     * survey, and because nothing labels it the rules above quite correctly refuse it — with the
     * result that the shipping notice claimed no identity at all and became a second order sitting
     * next to the confirmation it belonged to.
     *
     * <p>These are not order numbers and are never shown as one. They are only ever used to ask
     * whether an order already exists that <em>does</em> carry this exact number as its labelled
     * reference; see how {@code OrderAssembler} files them. Two unlabelled codes never merge with
     * each other, so a product code appearing in two emails cannot join them.
     *
     * <p>The shape is deliberately narrow — eight characters or more, and either digits with letters
     * mixed in or a long run of digits — because an unlabelled six-digit number in a shop's email is
     * an article number far more often than it is an order.
     */
    public static Set<String> findUnlabelled(String subject, String text) {
        Set<String> found = new LinkedHashSet<>();
        Matcher m = UNLABELLED.matcher((subject == null ? "" : subject) + "\n" + (text == null ? "" : text));
        while (m.find()) {
            String candidate = clean(m.group());
            if (plausible(candidate) && couldBeAReference(candidate)) {
                found.add(candidate);
            }
        }
        return found;
    }

    /** Any word made of letters, digits and dashes — narrowed down by {@link #couldBeAReference}. */
    private static final Pattern UNLABELLED = Pattern.compile("\\b[A-Z0-9][A-Z0-9-]{6,28}\\b", Pattern.CASE_INSENSITIVE);

    private static boolean couldBeAReference(String candidate) {
        long digits = candidate.chars().filter(Character::isDigit).count();
        long letters = candidate.chars().filter(Character::isLetter).count();
        if (candidate.length() < 8) {
            return false;
        }
        return digits >= 8 || (digits >= 3 && letters >= 2);
    }

    private static void collect(Pattern pattern, String where, Set<String> into, Set<String> parcelNumbers) {
        if (where == null || where.isBlank()) {
            return;
        }
        Matcher m = pattern.matcher(where);
        while (m.find()) {
            String candidate = clean(m.group(1));
            if (plausible(candidate) && !parcelNumbers.contains(candidate)) {
                into.add(candidate);
            }
        }
    }

    private static String head(String text) {
        return text == null ? "" : text.substring(0, Math.min(text.length(), 2500));
    }

    private static String clean(String raw) {
        String candidate = raw.toUpperCase(Locale.ROOT).trim();
        while (candidate.endsWith("-") || candidate.endsWith(".")) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        return candidate;
    }

    /**
     * Rejects what an introducing word can drag in by accident. Years, dates and prices are the
     * common ones; so is a word with no digit in it at all, which is usually the sentence carrying
     * on ("your order number will follow shortly").
     */
    private static boolean plausible(String candidate) {
        if (candidate.length() < 4 || candidate.length() > 28) {
            return false;
        }
        if (candidate.chars().noneMatch(Character::isDigit)) {
            return false;
        }
        // A bare four-digit number is far more likely to be a year than an order.
        if (candidate.matches("(19|20)\\d{2}")) {
            return false;
        }
        if (Text.isMachineNoise(candidate)) {
            return false;
        }
        return !candidate.matches("\\d{1,2}-\\d{1,2}(-\\d{2,4})?");
    }
}
