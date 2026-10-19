package work.osmosis.ordersapp.extract;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reads how far along an order is from the words the email uses.
 *
 * <p>The subject line carries almost all of the signal — "Je bestelling is verzonden", "Woensdag
 * komen we bij je langs", "Bedankt voor je bestelling" — so it is weighted far above the body.
 *
 * <p><b>Why there are two strengths of pattern.</b> The body used to be searched with the same word
 * list as the subject, and it was the single largest source of wrong statuses in this mailbox: an
 * order confirmation explains the whole journey in its opening lines, so "Bedankt voor je
 * bestelling" arrived carrying the words *verzonden*, *retour* and *annuleren* in its first
 * paragraph. Ten Coolblue confirmations read as shipped, three Uniqlo till receipts read as
 * returned, and one shop's order number read as cancelled. So a word on its own is now only ever
 * trusted in a subject line. In a body a stage is only believed when the sentence attaches the verb
 * to the order itself — "je pakket is bezorgd", not the word "bezorgd" somewhere on the page.
 *
 * <p>The tense trap is worth naming: in Dutch, {@code bezorgd} means both "delivered" and, in
 * {@code wordt bezorgd}, "will be delivered". Matching the bare word would mark half the mailbox as
 * already arrived, so delivery is only recognised in phrases that are unambiguously past, and the
 * "we're coming today between 11:36 and 12:36" wording is claimed first by the stage it belongs to.
 */
public final class StageRules {

    private record Signal(Stage stage, Pattern pattern) {}

    /**
     * The order's own noun. A strict pattern puts this next to its verb, so that the word "bezorgd"
     * in a footer link cannot decide anything while "je pakket is bezorgd" can.
     */
    private static final String THING =
            "(?:bestelling|bestellingen|order|orders|pakket(?:je)?|pakketten|zending|package|parcel"
                    + "|shipment|artikelen|items?|aankoop|purchase|retour(?:zending)?|return|alterations?)";

    /**
     * Subjects that settle the reading before anything else is asked, because a general word list
     * gets them wrong.
     *
     * <p>Two kinds. The marketplaces write the stage as a prefix and the product after it —
     * "Ordered: 'Anker USB-C…'" — which reads as an order confirmation to a person and matched
     * nothing here, so the body decided instead and six Amazon confirmations came out as out for
     * delivery. And a delivery company promising a time slot today writes it in the past tense in
     * Dutch, so "wordt vandaag tussen 11:36 en 12:36 bezorgd" read as already delivered.
     */
    private static final List<Signal> SUBJECT_FIRST = List.of(
            new Signal(Stage.ORDERED, compile("^\\s*(?:ordered|besteld)\\s*:")),
            new Signal(Stage.SHIPPED, compile("^\\s*(?:shipped|dispatched|verzonden|onderweg)\\s*:")),
            new Signal(Stage.OUT_FOR_DELIVERY, compile("^\\s*(?:out\\s+for\\s+delivery|arriving\\s+today)\\s*:")),
            new Signal(Stage.DELIVERED, compile("^\\s*(?:delivered|bezorgd|afgeleverd)\\s*:")),
            new Signal(Stage.RETURNED, compile("^\\s*(?:returned|retour)\\s*:")),
            new Signal(Stage.CANCELLED, compile("^\\s*(?:cancell?ed|geannuleerd)\\s*:")),
            new Signal(Stage.OUT_FOR_DELIVERY, compile(
                    "wordt\\s+vandaag\\s+[^\\n]{0,40}bezorgd|vandaag\\s+tussen\\s+\\d{1,2}[:.]\\d{2}"
                            + "|wordt\\s+[^\\n]{0,30}tussen\\s+\\d{1,2}[:.]\\d{2}\\s+en\\s+\\d{1,2}[:.]\\d{2}"
                            + "\\s+bezorgd")),
            // A till receipt from a shop floor. The thing was carried out of the door, so it has
            // arrived — an in-store purchase that sits on the page as "Ordered" for ever and then
            // reports itself as having gone quiet is telling the reader nothing true.
            new Signal(Stage.DELIVERED, compile("kassabon|winkelbon|store[- ]?receipt|receipt\\s+for\\s+your\\s+visit")));

    /**
     * Signals strong enough to be believed anywhere, including in the middle of a body: each one
     * names the order and says something about it, rather than being a word that happens to appear.
     * Checked most-final-first, so the last word on an order wins over an earlier one in the same
     * email.
     */
    private static final List<Signal> STRICT = List.of(
            new Signal(Stage.CANCELLED, compile(
                    THING + "[^\\n]{0,30}?\\s(?:is|was|were|zijn|werd|has\\s+been|have\\s+been)\\s+"
                            + "(?:geannuleerd|cancell?ed)"
                            + "|(?:we|wij)\\s+hebben\\s+j(?:e|ouw)\\s+bestelling\\s+geannuleerd"
                            + "|order\\s+cancell?ation|annulering\\s+(?:van\\s+j(?:e|ouw)\\s+bestelling|bevestigd)"
                            + "|we\\s+(?:have\\s+)?cancell?ed\\s+your\\s+order")),
            new Signal(Stage.RETURNED, compile(
                    "retour(?:zending)?\\s*(?:is\\s+)?(?:ontvangen|verwerkt|bevestigd|afgerond)"
                            + "|retourbevestiging|return\\s+confirmation"
                            + "|\\breturn\\b[^\\n]{0,30}?\\b(?:requested|received|processed|confirmed|completed"
                            + "|closed|approved|accepted)"
                            + "|(?:we|wij)\\s+hebben\\s+j(?:e|ouw)\\s+retour"
                            + "|refund\\s+(?:has\\s+been\\s+|is\\s+being\\s+|was\\s+)?"
                            + "(?:issued|processed|initiated|completed|sent|approved)"
                            + "|terugbetaling\\s+(?:is\\s+)?(?:verwerkt|onderweg|gedaan|uitgevoerd)")),
            new Signal(Stage.DELIVERED, compile(
                    THING + "[^\\n]{0,30}?\\s(?:is|zijn|was|were|has\\s+been|have\\s+been)\\s+"
                            + "(?:af)?(?:bezorgd|geleverd|delivered)"
                            + "|(?:hebben\\s+we|heb\\s+je)\\s+(?:af)?bezorgd|successfully\\s+delivered"
                            + "|delivered\\s+(?:your|to\\s+you)|j(?:e|ouw)\\s+pakket\\s+is\\s+er"
                            + "|package\\s+has\\s+arrived|" + THING + "\\s+is\\s+here"
                            + "|(?:hebt|heb)\\s+je[^\\n]{0,25}?bestelling\\s+binnen|bestelling\\s+binnen\\b"
                            + "|have\\s+received\\s+your[^\\n]{0,20}?order"
                            + "|" + THING + "[^\\n]{0,30}?\\s+is\\s+(?:nu\\s+)?(?:afgerond|voltooid|complete)"
                            + "|delivery\\s+confirmed|bezorging\\s+bevestigd|pakket\\s+in\\s+handen"
                            // UPS writes its delivery notice in Dutch as one word.
                            + "|afleveringsbevestiging")),
            new Signal(Stage.READY_FOR_PICKUP, compile(
                    THING + "[^\\n]{0,30}?\\s(?:is|are)\\s+ready\\s+"
                            + "(?:to\\s+be\\s+(?:picked|collected)|for\\s+(?:pick[- ]?up|collection))"
                            + "|ready\\s+for\\s+pick\\s*up|klaar\\s+om\\s+op\\s+te\\s+halen"
                            + "|ligt\\s+(?:voor\\s+j(?:e|ou)\\s+)?klaar|kun\\s+je\\s+ophalen|afhaalgereed")),
            new Signal(Stage.OUT_FOR_DELIVERY, compile(
                    "out\\s+for\\s+delivery|komen\\s+we\\s+bij\\s+je\\s+langs|bezorgen\\s+we\\s+vandaag"
                            + "|wordt\\s+vandaag\\s+bezorgd|bezorger\\s+is\\s+onderweg|arriving\\s+today"
                            + "|we\\s+will\\s+deliver\\s+between|bezorgen\\s+we\\s+tussen")),
            new Signal(Stage.SHIPPED, compile(
                    THING + "[^\\n]{0,30}?\\s(?:is|zijn|was|were|has\\s+been|have\\s+been)\\s+"
                            + "(?:verzonden|verstuurd|onderweg|shipp?ed|dispatched|on\\s+(?:its|the)\\s+way)"
                            + "|(?:we|wij)\\s+hebben\\s+j(?:e|ouw)\\s+" + THING + "\\s+ver(?:zonden|stuurd)"
                            + "|" + THING + "\\s+(?:has|have)\\s+shipped|shipment\\s+from\\s+order"
                            + "|ontvangen\\s+door\\s+(?:dpd|postnl|dhl|ups|gls)"
                            + "|verzendbericht|shipment\\s+confirmation|verzendbevestiging"
                            + "|is\\s+vertrokken|has\\s+left\\s+(?:our|the)\\s+(?:warehouse|facility)"
                            // A carrier that has the parcel and is telling you when it will arrive.
                            // Deliberately not "out for delivery": both of these are sent the evening
                            // before, and a row that says the van is outside when it is not is worse
                            // than one that only says the parcel is on its way.
                            + "|bezorgtijd\\s+van\\s+j(?:e|ouw)\\s+pakket|afleverschema"
                            + "|scheduled\\s+for\\s+delivery"
                            // The marketplace's own two wordings for a parcel that has left the
                            // seller. The first is sent the evening before — "tomorrow we will
                            // deliver your parcel from <seller>" — and the word "tomorrow" in it is
                            // also where the arrival date comes from. The second is how a bulky item
                            // is handled: the parcel is with the delivery partner, who telephones to
                            // agree a time. Both arrived saying nothing the rules recognised, so two
                            // orders sat at an unknown stage with no date until the next email.
                            + "|morgen\\s+komen\\s+we\\b[^\\n]{0,60}(?:afleveren|bezorgen|langs)"
                            + "|bezorgafspraak")),
            new Signal(Stage.PROCESSING, compile(
                    "(?:we|wij)\\s+pakken\\s+j(?:e|ouw)\\s+bestelling\\s+in"
                            + "|" + THING + "\\s+wordt\\s+(?:voorbereid|klaargemaakt|ingepakt|verzameld)"
                            + "|" + THING + "\\s+(?:is|zijn)\\s+verzameld|(?:is|are)\\s+being\\s+prepared"
                            + "|(?:we\\s+are|we're)\\s+(?:preparing|processing)\\s+your\\s+order"
                            + "|processing\\s+your\\s+order|" + THING + "[^\\n]{0,20}?\\s+(?:has|have)\\s+been\\s+picked"
                            + "|awaiting\\s+shipment|ready\\s+to\\s+ship|klaar\\s+om\\s+te\\s+verzenden"
                            + "|alterations?\\s+are\\s+underway|in\\s+behandeling"
                            // Promised for tomorrow is not gone today.
                            + "|" + THING + "\\s+is\\s+shipping\\s+tomorrow"
                            + "|klaar\\s+voor\\s+verzending")),
            new Signal(Stage.ORDERED, compile(
                    "bedankt\\s+voor\\s+j(?:e|ouw)\\s+(?:bestelling|aankoop|order|order\\b)"
                            + "|thank\\s+you\\s+for\\s+your\\s+(?:order|purchase)"
                            + "|(?:we|wij)\\s+hebben\\s+j(?:e|ouw)\\s+bestelling\\s+(?:goed\\s+)?ontvangen"
                            + "|(?:we\\s+have|we've)\\s+received\\s+your\\s+order"
                            + "|your\\s+order\\s+has\\s+been\\s+received|order\\s+has\\s+been\\s+placed"
                            + "|bestelling\\s+(?:is\\s+)?(?:ontvangen|bevestigd|gelukt|geplaatst)"
                            + "|order\\s+(?:is\\s+)?(?:received|confirmed|successful|successfull|placed)"
                            // The reference between "order" and "confirmed" is written every way a
                            // shop can think of, "ABC#12345" among them, so the hash belongs inside.
                            + "|\\border\\b\\s*[A-Za-z0-9#-]{0,24}\\s*(?:is\\s+)?confirmed"
                            + "|orderbevestiging|bestelbevestiging|order\\s+confirmation"
                            + "|bevestiging\\s+van\\s+j(?:e|ouw)\\s+bestelling"
                            // An invoice is the shop billing for something it has accepted. It says
                            // nothing about shipping, and saying "Ordered" is both true and harmless:
                            // it is the earliest stage, so it can never drag a later one backwards.
                            + "|factuur\\s+(?:van|voor)\\s+(?:j(?:e|ouw)\\s+)?bestelling"
                            + "|factuur\\s+\\d[\\d-]*\\s+van\\b|invoice\\s+for\\s+your\\s+order"
                            // Both spellings of the same label. Only "ordernummer" was listed, and
                            // the shop that writes "Bestelnummer" instead had its replacement
                            // confirmation read as saying nothing at all about the order's progress.
                            // Safe to sit here at the bottom: Ordered is the earliest stage, and the
                            // patterns above are tried first, so a shipping notice quoting its own
                            // reference is still read as shipped.
                            + "|(?:bestel|order)nummer\\s*:"
                            // Money taken is the shop accepting the order. It says nothing about a
                            // parcel, and Ordered is the earliest stage, so it cannot pull a later
                            // one backwards.
                            + "|payment\\s+confirmed|betaling\\s+bevestigd")));

    /**
     * The looser word list, used on a subject line only. A subject is the shop's own summary of why
     * it is writing, so a bare "Verzonden" or "Geannuleerd" there means what it says — which is not
     * remotely true of the same word in a body.
     */
    private static final List<Signal> LOOSE = List.of(
            new Signal(Stage.CANCELLED, compile(
                    "geannuleerd|annulering|annuleren\\s+bevestigd|cancell?ed|order\\s+cancell?ation"
                            + "|niet\\s+doorgegaan")),
            new Signal(Stage.RETURNED, compile(
                    "retour\\s*(?:ontvangen|verwerkt|bevestigd)|geretourneerd|retourbevestiging"
                            + "|return\\s+(?:received|processed|confirmed|requested|closed)"
                            + "|refund(?:ed)?|terugbetaling")),
            new Signal(Stage.DELIVERED, compile(
                    "(?:is|zijn|was|werd|hebben\\s+we|heb\\s+je)\\s+(?:af)?bezorgd|afgeleverd|bezorgd!"
                            + "|(?:has\\s+been|was|is|were)\\s+delivered|successfully\\s+delivered"
                            + "|delivered\\s+(?:your|to)|je\\s+pakket\\s+is\\s+er|package\\s+has\\s+arrived"
                            + "|pakket\\s+in\\s+handen|delivery\\s+confirmed")),
            new Signal(Stage.READY_FOR_PICKUP, compile(
                    "ready\\s+(?:to\\s+be\\s+picked\\s+up|for\\s+(?:pick[- ]?up|collection))"
                            + "|klaar\\s+om\\s+op\\s+te\\s+halen|ligt\\s+klaar|afhaalgereed"
                            // "Collect your parcel before it goes back" — a pick-up point holding it.
                            + "|haal\\s+j(?:e|ouw)\\s+pakket")),
            new Signal(Stage.OUT_FOR_DELIVERY, compile(
                    "out\\s+for\\s+delivery|komen\\s+we\\s+bij\\s+je\\s+langs|bezorgen\\s+we\\s+vandaag"
                            + "|wordt\\s+vandaag\\s+bezorgd|bezorger\\s+is\\s+onderweg|arriving\\s+today"
                            + "|we\\s+will\\s+deliver\\s+between|bezorgen\\s+we\\s+tussen"
                            // "We are at your door today between 12.00 and 13.40."
                            + "|staan\\s+vandaag\\s+voor\\s+de\\s+deur")),
            new Signal(Stage.SHIPPED, compile(
                    "verzonden|onderweg|is\\s+vertrokken|shipp?ed|dispatched|on\\s+(?:its|the)\\s+way"
                            + "|has\\s+left|track\\s*(?:&|and)?\\s*trace|volg\\s+je\\s+pakket"
                            + "|shipment\\s+confirmation|verzendbericht|verzendbevestiging"
                            + "|je\\s+pakket\\s+van"
                            // The carrier's own announcement that it now holds the parcel, and the
                            // shop's that the parcel exists. Both are the same news said differently:
                            // "Nieuw pakket van Coolblue", "Je pakket is nu bij PostNL".
                            + "|nieuw\\s+pakket\\s+van|is\\s+nu\\s+bij\\s+(?:postnl|dhl|dpd|ups|gls)"
                            // "It's coming", in the several ways this mailbox receives it.
                            + "|kom(?:t|en)\\s+eraan|binnenkort\\s+een\\s+pakket|" + THING + "\\s+is\\s+coming"
                            + "|will\\s+be\\s+with\\s+you|delivery\\s+time\\s+of\\s+your\\s+package"
                            + "|arrived\\s+in\\s+your\\s+country"
                            + "|transferred\\s+to\\s+[\\p{L}&.\\s]{2,20}\\s+for\\s+delivery"
                            // A shop that writes only to update the shipping has already shipped.
                            + "|ship(?:ping|ment)\\s+status\\s+updated|shipping\\s+update")),
            new Signal(Stage.PROCESSING, compile(
                    "pakken\\s+we\\s+in|in\\s+behandeling|wordt\\s+voorbereid|being\\s+prepared"
                            + "|preparing\\s+your|processing\\s+your\\s+order|klaargemaakt|verzameld"
                            + "|awaiting\\s+(?:for\\s+)?shipment|ready\\s+to\\s+ship"
                            + "|alterations?\\s+are\\s+underway|has\\s+been\\s+packed")),
            new Signal(Stage.ORDERED, compile(
                    "bedankt\\s+voor\\s+j(?:e|ouw)\\s+(?:bestelling|aankoop)|orderbevestiging"
                            + "|bestelbevestiging|order\\s+confirmation"
                            + "|thank\\s+you\\s+for\\s+your\\s+order|bestelling\\s+ontvangen|order\\s+received"
                            + "|we\\s+hebben\\s+je\\s+bestelling|bevestiging\\s+van\\s+je\\s+bestelling"
                            + "|your\\s+order\\s+(?:is\\s+)?confirmed")));

    /** How much of a body is worth reading. Past this it is footers, help links and small print. */
    private static final int BODY_OPENING = 1500;

    /** A body saying the parcel is waiting at a pickup point, however the subject put it. */
    private static final List<Signal> AT_PICKUP_POINT = List.of(new Signal(Stage.READY_FOR_PICKUP, compile(
            "ligt\\s+(?:voor\\s+j(?:e|ou)\\s+)?klaar\\s+(?:bij|op|in)|ready\\s+(?:for|to\\s+be)\\s+"
                    + "(?:pick(?:ed)?[- ]?up|collect(?:ed|ion))")));

    private StageRules() {}

    private static Pattern compile(String alternatives) {
        return Pattern.compile("(?:" + alternatives + ")", Pattern.CASE_INSENSITIVE);
    }

    /**
     * How far along the email says the order is.
     *
     * @param subject the subject line, which decides whenever it says anything at all
     * @param text the flattened body, consulted only when the subject is silent — and then only for
     *     the sentences that name the order outright, because everything else in a body is the shop
     *     explaining the parts of the journey that have not happened yet
     */
    public static Stage read(String subject, String text) {
        String opening = text == null ? "" : text.substring(0, Math.min(text.length(), BODY_OPENING));
        Stage decided = firstMatch(SUBJECT_FIRST, subject);
        if (decided != Stage.UNKNOWN) {
            // PostNL titles a parcel dropped at a pickup point "Afgeleverd: je pakket van Coolblue"
            // too, and only the body says it is waiting there to be collected. It has not arrived.
            if (decided == Stage.DELIVERED && firstMatch(AT_PICKUP_POINT, opening) != Stage.UNKNOWN) {
                return Stage.READY_FOR_PICKUP;
            }
            return decided;
        }
        Stage stated = firstMatch(STRICT, subject);
        if (stated != Stage.UNKNOWN) {
            return stated;
        }
        Stage hinted = firstMatch(LOOSE, subject);
        if (hinted != Stage.UNKNOWN) {
            return hinted;
        }
        return firstMatch(STRICT, opening);
    }

    /**
     * Whether the subject reports, in so many words, where this order has got to.
     *
     * <p>Used to overrule the rules that decide an email is not about an order at all. Those work on
     * the same subject line and are blunter, so "Return requested" was being filed as an account
     * notice and a Shopify shop's "Order #1234 confirmed" was dropped along with the whole order.
     * Only the strict patterns count here: a sale announcement saying "gratis verzonden" must not
     * buy its way in on the word alone.
     */
    public static boolean statesProgress(String subject) {
        return firstMatch(SUBJECT_FIRST, subject) != Stage.UNKNOWN
                || firstMatch(STRICT, subject) != Stage.UNKNOWN;
    }

    private static Stage firstMatch(List<Signal> signals, String where) {
        if (where == null || where.isBlank()) {
            return Stage.UNKNOWN;
        }
        String haystack = where.toLowerCase(Locale.ROOT);
        for (Signal signal : signals) {
            if (signal.pattern().matcher(haystack).find()) {
                return signal.stage();
            }
        }
        return Stage.UNKNOWN;
    }
}
