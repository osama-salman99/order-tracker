package work.osmosis.ordersapp.extract;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds parcel tracking numbers in the text of an email.
 *
 * <p>These carry more weight than anything else the rules pull out, because a tracking number is the
 * only thing a shop's email and a carrier's email reliably have in common — it is what lets "your
 * Coolblue order has shipped" and "DHL is coming Wednesday" be recognised as one parcel. A wrong one
 * therefore does real damage: it welds two unrelated orders together.
 *
 * <p>So the patterns come in two strengths, and the difference is the whole design:
 *
 * <ul>
 *   <li><b>Self-identifying</b> — shapes no other number has. UPS numbers start {@code 1Z}, PostNL's
 *       start {@code 3S}, DHL eCommerce's start {@code JVGL}, and international post uses two
 *       letters, nine digits and a country code. These can be picked out of running text safely.
 *   <li><b>Ambiguous</b> — plain runs of digits, which every shop also uses for order numbers,
 *       invoice numbers and phone numbers. In this mailbox a bare ten-digit pattern matches IKEA
 *       order numbers and a bare fourteen-digit one matches an international phone number. These are only
 *       accepted when a word like "tracking" or "barcode" sits just before them.
 * </ul>
 */
public final class TrackingRules {

    private record SelfIdentifying(String carrier, Pattern pattern) {}

    private static final List<SelfIdentifying> STRONG = List.of(
            new SelfIdentifying("UPS", Pattern.compile("\\b1Z[0-9A-Z]{16}\\b")),
            // Four letters and eight digits is a real PostNL code seen in this mailbox, so the
            // digit run has to start lower than the twelve-or-so most of them use.
            new SelfIdentifying("PostNL", Pattern.compile("\\b3S[A-Z]{0,5}\\d{7,14}\\b")),
            new SelfIdentifying("DHL", Pattern.compile("\\bJVGL\\d{14,22}\\b")),
            // The Universal Postal Union's format, used by national post services worldwide.
            new SelfIdentifying("Post", Pattern.compile("\\b[A-Z]{2}\\d{9}[A-Z]{2}\\b")));

    /**
     * The words that make a plain number trustworthy. Dutch and English both appear, often in the
     * same email, and shops are inconsistent about which they use.
     */
    private static final Pattern ANCHORED = Pattern.compile(
            "(?:track(?:ing|\\s*&\\s*trace|\\s*and\\s*trace)?|trackingnummer|barcode|zendingnummer"
                    // A carrier writing to say something is wrong with a parcel introduces it as
                    // "Zending 1234567890" and nothing else, which left the message with no way of
                    // ever joining the order it was about.
                    + "|zending|verzendnummer|pakketnummer|shipment\\s*(?:number|no|id)|waybill|awb"
                    + "|parcel\\s*(?:number|no)|consignment)"
                    + "[^\\p{Alnum}\\n]{0,20}(?:number|nummer|nr\\.?|no\\.?|code|is|:)?"
                    + "[^\\p{Alnum}\\n]{0,10}([0-9][0-9A-Z-]{7,24})",
            Pattern.CASE_INSENSITIVE);

    /**
     * Tracking numbers also travel inside carrier links. Pulling them out of the query string is
     * more reliable than reading the sentence around them, which is often just "click here".
     */
    private static final Pattern IN_LINK = Pattern.compile(
            "(?:postnl|dhl|ups|dpd|aramex)[^\\s\"'<>]*?[?&](?:b|barcode|tracking-id|tracknum|trackingnumber"
                    + "|parcelnumber|shipmentnumber|pieceid)=([0-9A-Z]{8,26})",
            Pattern.CASE_INSENSITIVE);

    /**
     * The number as the email states it to a machine rather than to a reader.
     *
     * <p>Shops increasingly mark their mail up with schema.org's delivery vocabulary so that a mail
     * client can show a "track this parcel" card, and that markup names the parcel outright. It
     * never reaches the flattened body, because flattening keeps what a person would read and this
     * lives in a script block — so bol's own emails about a parcel appeared to mention no parcel at
     * all, while PostNL's email about the very same one did. The two halves of one delivery sat on
     * the page as two separate orders: a bol order that seemed never to have shipped, and a bare
     * carrier card with nothing in it but a number.
     *
     * <p>Trusted without a keyword nearby because the key <em>is</em> the keyword, and a stronger
     * one than any sentence: the shop has declared, in a format defined elsewhere and meant for
     * machines, that this is the tracking number.
     */
    private static final Pattern STRUCTURED =
            Pattern.compile("\"trackingNumber\"\\s*:\\s*\"([0-9A-Za-z][0-9A-Za-z-]{7,25})\"");

    private TrackingRules() {}

    /**
     * Every tracking number the email can be trusted to contain, in the order they were found.
     *
     * @param text the flattened body of the email
     * @param html the body before flattening, or null when there was none. Read only for the
     *     structured markup described on {@link #STRUCTURED}; everything a person can see is
     *     already in the text
     * @param sendingCarrier the carrier that sent the email, if one did — it is the best guess for
     *     any number whose shape does not name its own carrier
     */
    public static Set<TrackingNumber> find(String text, String html, String sendingCarrier) {
        Set<TrackingNumber> found = new LinkedHashSet<>();
        if (html != null && !html.isBlank()) {
            Matcher declared = STRUCTURED.matcher(html);
            while (declared.find()) {
                String number = declared.group(1).toUpperCase(Locale.ROOT);
                found.add(new TrackingNumber(carrierOf(number, sendingCarrier), number));
            }
        }
        if (text == null || text.isBlank()) {
            return found;
        }
        String upper = text.toUpperCase(Locale.ROOT);

        for (SelfIdentifying rule : STRONG) {
            Matcher m = rule.pattern().matcher(upper);
            while (m.find()) {
                // "Post" is a placeholder: if a carrier sent the email, it is the one that knows.
                String carrier = "Post".equals(rule.carrier()) && sendingCarrier != null
                        ? sendingCarrier
                        : rule.carrier();
                found.add(new TrackingNumber(carrier, m.group()));
            }
        }
        Matcher link = IN_LINK.matcher(text);
        while (link.find()) {
            found.add(new TrackingNumber(carrierOfLink(link.group()), link.group(1)));
        }
        // The keyword form reads the prose only. Inside a link the same keyword introduces a page
        // name or a session token — a support message's link token was being read as a parcel
        // number, and since every message in that thread carried the same token it pulled three
        // customer-service replies into an unrelated order.
        Matcher anchored = ANCHORED.matcher(Text.withoutLinks(text));
        while (anchored.find()) {
            String candidate = anchored.group(1).toUpperCase(Locale.ROOT);
            if (plausible(candidate) && notAlreadyCovered(found, candidate)) {
                found.add(new TrackingNumber(sendingCarrier == null ? "" : sendingCarrier, candidate));
            }
        }
        return found;
    }

    /**
     * Who carries a number that arrived without a sentence around it to say so. The shape is asked
     * first, because {@code 3S…} is PostNL whoever passed the number on; the sender is the fallback,
     * and a shop naming a parcel it has handed over says nothing about who has it.
     */
    private static String carrierOf(String number, String sendingCarrier) {
        for (SelfIdentifying rule : STRONG) {
            if (!"Post".equals(rule.carrier()) && rule.pattern().matcher(number).matches()) {
                return rule.carrier();
            }
        }
        return sendingCarrier == null ? "" : sendingCarrier;
    }

    private static String carrierOfLink(String link) {
        String lower = link.toLowerCase(Locale.ROOT);
        if (lower.contains("postnl")) {
            return "PostNL";
        }
        if (lower.contains("dhl")) {
            return "DHL";
        }
        if (lower.contains("ups")) {
            return "UPS";
        }
        if (lower.contains("dpd")) {
            return "DPD";
        }
        if (lower.contains("aramex")) {
            return "Aramex";
        }
        return "";
    }

    /**
     * Rejects the shapes that a keyword can drag in by accident: dates, prices, years, and anything
     * short enough to be an order number rather than a parcel number.
     */
    private static boolean plausible(String candidate) {
        String bare = candidate.replace("-", "");
        if (bare.length() < 8 || bare.length() > 26) {
            return false;
        }
        // Every carrier whose numbers contain letters announces itself in the shape of the number
        // and is matched above; what is left for a keyword to introduce is a plain run of digits.
        // Insisting on that throws away the random mixed-case tokens that live in support emails.
        return bare.chars().allMatch(Character::isDigit);
    }

    /** Skips a weak match that is really just part of a number a strong pattern already found. */
    private static boolean notAlreadyCovered(Set<TrackingNumber> found, String candidate) {
        return found.stream().noneMatch(t -> t.number().contains(candidate) || candidate.contains(t.number()));
    }
}
