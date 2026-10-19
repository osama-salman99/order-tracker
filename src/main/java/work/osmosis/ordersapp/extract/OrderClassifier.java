package work.osmosis.ordersapp.extract;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides whether an email is about a parcel at all.
 *
 * <p>This exists because the mail arrives through a Gmail label, and a label is a filter someone
 * wrote once — it lets through login codes from shops, review requests, sale announcements, hosting
 * invoices and "welcome to our newsletter". Roughly a fifth of this mailbox is not an order. Sorting
 * that out here rather than in Gmail means the rule can be corrected and every email re-read,
 * which is not true of a filter that has already delivered the mail.
 *
 * <p>The bias is deliberately towards keeping things. A wrongly kept email shows up as an odd row on
 * a page the user is looking at anyway; a wrongly dropped one is invisible, and an order they were
 * waiting for simply never appears.
 */
public final class OrderClassifier {

    /** What the email is, when it isn't an order. */
    public record Verdict(boolean order, String reason) {

        static final Verdict ORDER = new Verdict(true, null);

        static Verdict not(String reason) {
            return new Verdict(false, reason);
        }
    }

    private record Rejection(String reason, Pattern pattern) {}

    /**
     * Subjects that settle the matter on their own. Each is a kind of email a shop sends that has
     * every other marking of an order — the shop's name, sometimes an order number — and is not one.
     */
    private static final List<Rejection> REJECTIONS = List.of(
            new Rejection("a login or verification code", compile(
                    "verificatiecode|verification\\s+code|login\\s+code|inlogcode|security\\s+code|passcode"
                            + "|eenmalige\\s+code|one[- ]time\\s+(?:code|password|passcode)|authentication\\s+code"
                            + "|je\\s+code\\s+is|your\\s+code\\s+is|\\botp\\b|two[- ]factor")),
            new Rejection("an account or password notice", compile(
                    "wachtwoord|password|passkey|inloggen\\s+bij|sign[- ]?in\\b|aanmelden\\s+bij"
                            + "|account(?:gegevens|\\s+details)|account\\s+(?:aangemaakt|created|gesloten|closed|verwijderd)"
                            + "|welkom\\s+bij|welcome\\s+to|thanks?\\s+for\\s+registering|registratie\\s+voltooid"
                            + "|bevestig\\s+je\\s+e-?mail|confirm\\s+your\\s+e-?mail|privacybeleid|privacy\\s+policy"
                            + "|voorwaarden\\s+(?:zijn\\s+)?gewijzigd|terms\\s+(?:have\\s+)?changed|membership")),
            new Rejection("a review or survey request", compile(
                    "beoordel|schrijf\\s+een\\s+review|write\\s+a\\s+review|leave\\s+a\\s+review|\\breview\\?"
                            + "|hoe\\s+(?:vond|beviel)\\s+je|tell\\s+us\\s+(?:how|about|what)|how\\s+did\\s+we\\s+do"
                            + "|jouw\\s+ervaring|your\\s+experience|enqu[eê]te|survey|trustpilot|meet\\s+your\\s+expectations"
                            + "|rate\\s+your|deel\\s+je\\s+mening|wat\\s+v(?:ind|ond)\\s+j|help\\s+other\\s+customers"
                            // Thuisbezorgd asks for a rating with nothing but a pair of thumbs in the subject.
                            + "|\\uD83D\\uDC4D|\\uD83D\\uDC4E")),
            new Rejection("an advertisement", compile(
                    "nieuwsbrief|newsletter|black\\s+friday|sale\\b|uitverkoop|korting(?:scode)?|aanbieding"
                            + "|kortingsactie|deal\\s+of\\s+the|cadeau[- ]?idee|inspiratie|nieuw\\s+binnen"
                            + "|new\\s+arrivals|shop\\s+now|bekijk\\s+de\\s+collectie|weer\\s+op\\s+voorraad"
                            + "|back\\s+in\\s+stock|verlanglijst|wishlist|winkelwagen|your\\s+cart|spaaractie"
                            + "|voucher|cadeaubon|tegoedbon|expires\\s+soon|verloopt\\s+binnenkort"
                            + "|laatste\\s+kans|last\\s+chance|% \\s?korting|\\d+%\\s+off")),
            new Rejection("a service or subscription notice", compile(
                    "abonnement\\s+(?:verlengd|verloopt|opgezegd)|subscription\\s+(?:renew|expir|cancel)"
                            + "|betaling\\s+mislukt|payment\\s+(?:failed|declined)|creditcard\\s+verloopt"
                            + "|server\\s+|vps\\b|domeinnaam|domain\\s+name|hosting")),
            // "Hurra! BESTÅ / LACK is available for delivery" is IKEA saying a thing you once looked
            // at can now be bought. Nothing was ordered and nothing is coming.
            new Rejection("a notice that something is back in stock", compile(
                    "available\\s+for\\s+(?:delivery|collection|order)|weer\\s+(?:op\\s+voorraad|leverbaar)"
                            + "|(?:signed\\s+up|aangemeld)\\s+to\\s+receive\\s+notifications"
                            + "|notifications?\\s+about\\s+(?:delivery\\s+)?availability"
                            + "|(?:now\\s+)?available\\s+for\\s+pre-?order|pre-?order\\s+(?:now|open)")),
            // The marketplaces route a seller's own message through their notification address, and
            // it quotes the order it is about without being a step in it.
            new Rejection("a message from a seller rather than about a parcel", compile(
                    "bericht\\s+van\\s+(?:de\\s+)?verkoper|message\\s+from\\s+(?:the\\s+)?seller"
                            + "|regarding\\s+your\\s+recent\\s+order|reageer\\s+op\\s+de\\s+verkoper"
                            + "|antwoord\\s+van\\s+de\\s+verkoper")));

    /** A receipt for money handed over, as opposed to a word about a parcel. */
    private static final Pattern PAID = compile(
            "payment\\s+(?:was\\s+)?(?:successful|received|confirmed|complete)"
                    + "|betaling\\s+(?:is\\s+)?(?:gelukt|ontvangen|geslaagd)"
                    + "|payment\\s+receipt|betalingsbevestiging");

    /** Wording that means a physical thing is coming, or has come, to an address. */
    private static final Pattern PARCEL_WORDING = compile(
            // "order" on its own is worth having: shops write "your Amazon.de order has been
            // canceled" and "Sam, order confirmed", and no amount of listing phrases catches
            // every arrangement of it. The rejections above run first, so the review requests and
            // sale announcements that also say "order" are already gone by the time this is asked.
            "\\border(?:s|ed)?\\b|bestel\\w*|verzonden|verstuurd|onderweg|shipp?(?:ed|ing)|dispatched|on\\s+its\\s+way"
                    + "|bezorg\\w*|afgeleverd|delivered|delivery|leverdatum|levering|geleverd|leverdag"
                    + "|pakket\\w*|parcel|package|zending|shipment|track\\s*(?:&|and)?\\s*trace"
                    + "|receipt|kassabon|je\\s+aankoop|your\\s+purchase|retour|artikelen\\s+komen"
                    + "|refund|terugbetaling|invoice|factuur");

    private OrderClassifier() {}

    private static Pattern compile(String alternatives) {
        return Pattern.compile("(?:" + alternatives + ")", Pattern.CASE_INSENSITIVE);
    }

    /** True when the part of the address before the @ says outright that this is a mailing list. */
    private static boolean sendsMarketingOnly(String senderAddress) {
        if (senderAddress == null) {
            return false;
        }
        int at = senderAddress.indexOf('@');
        String localPart = (at > 0 ? senderAddress.substring(0, at) : senderAddress).toLowerCase(Locale.ROOT);
        return localPart.contains("nieuwsbrief") || localPart.contains("newsletter")
                || localPart.contains("marketing") || localPart.contains("promo");
    }

    /**
     * Whether to treat this email as an order.
     *
     * @param senderAddress used only to drop senders that never send orders whatever they write
     * @param fromCarrier whether a delivery company sent it, which changes what counts as substance
     * @param subject the subject line, which decides nearly everything
     * @param text the flattened body, used to rescue an email whose subject says nothing either way
     * @param orderNumbers order numbers the rules found
     * @param tracking tracking numbers the rules found
     */
    public static Verdict classify(
            String senderAddress,
            boolean fromCarrier,
            String subject,
            String text,
            Set<String> orderNumbers,
            Set<TrackingNumber> tracking) {

        if (Senders.neverOrders(senderAddress)) {
            return Verdict.not("the sender only ever sends account and billing mail");
        }
        // A shop that sends its marketing from newsletter@ has told you what the email is before
        // you have read a word of it, and its subjects are written to avoid the usual giveaways.
        if (sendsMarketingOnly(senderAddress)) {
            return Verdict.not("an advertisement");
        }
        if (Senders.isPersonalMailbox(senderAddress)) {
            // Either a reply in the user's own conversation with a shop, or a hand-forwarded email
            // whose original could not be recovered from the wrapper. Said plainly, because the
            // second case is a gap worth noticing rather than a decision.
            return Verdict.not("it came from a personal mailbox, so no shop could be identified");
        }
        String line = subject == null ? "" : subject.toLowerCase(Locale.ROOT);
        String opening = text == null ? "" : text.substring(0, Math.min(text.length(), 1200)).toLowerCase(Locale.ROOT);

        // A tracking number is close to proof: nothing but a real shipment carries one, and it is
        // the one signal strong enough to overrule a subject line that reads like an advertisement.
        boolean hasParcelNumber = !tracking.isEmpty();
        boolean namesSomething = hasParcelNumber || !orderNumbers.isEmpty();

        // A delivery company that quotes neither a parcel nor an order has nothing an order could be
        // built out of. It became a row with no shop, no title, no price and no number — a weather
        // warning from PostNL and three personal notes from a courier each got one.
        if (fromCarrier && !namesSomething) {
            return Verdict.not("a delivery company's message that names no parcel");
        }
        // Money paid to a delivery company is a receipt for the delivery, not a purchase. The number
        // it quotes is a payment reference, which looked enough like an order number to build a row
        // that said only "Aramex" and could never say anything more.
        if (fromCarrier && PAID.matcher(subject == null ? "" : subject).find()) {
            return Verdict.not("a delivery company's payment receipt");
        }
        // A reply in a support thread quotes the order it is about without being a step in it. Two
        // halves of one conversation with IKEA became two orders, one "returned" and one "shipped",
        // purely on the words used in the question.
        if (Senders.isCustomerService(senderAddress) && !namesSomething) {
            return Verdict.not("a customer-service conversation");
        }
        // The subject says outright where the order has got to, which settles it before the blunter
        // rules below get a chance to read "Return requested" as an account notice.
        if (StageRules.statesProgress(subject)) {
            return Verdict.ORDER;
        }

        for (Rejection rejection : REJECTIONS) {
            if (rejection.pattern().matcher(line).find() && !hasParcelNumber) {
                return Verdict.not(rejection.reason());
            }
        }
        if (hasParcelNumber) {
            return Verdict.ORDER;
        }
        if (PARCEL_WORDING.matcher(line).find()) {
            return Verdict.ORDER;
        }
        // No help from the subject. An order number plus parcel wording in the opening lines is
        // enough; an order number on its own is not, because invoices and quotes have those too.
        if (!orderNumbers.isEmpty() && PARCEL_WORDING.matcher(opening).find()) {
            return Verdict.ORDER;
        }
        return Verdict.not("nothing in it refers to a parcel or an order");
    }
}
