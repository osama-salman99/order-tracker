package work.osmosis.ordersapp.extract;

import java.util.regex.Pattern;

/**
 * Whether an email is a shop announcing that it has sent something out again.
 *
 * <p>A parcel that never turns up is usually settled by telephone, and what comes back is a second
 * order with a second order number, a second confirmation and its own delivery date. Read on its
 * own that is simply a new purchase, which is how it looked on the page: two orders for the same
 * thing, the first stuck for ever waiting for a parcel that was never coming.
 *
 * <p>What this cannot do is say <em>which</em> order is being made up for, and that is not a gap to
 * be closed with a cleverer pattern — the replacement confirmation in this mailbox names only the
 * new number, nowhere mentions the old one, and no amount of reading will find what is not written.
 * So this is used for one thing only: to put the likely candidates at the top of the short list the
 * reader picks from. They are the one who telephoned, so they are the one who knows.
 */
public final class ReplacementRules {

    /**
     * The wordings a shop uses for it. Deliberately whole phrases rather than the word "vervangend"
     * on its own: a returns policy explains replacements to everyone who ever buys anything, and a
     * suggestion list that puts every order in the mailbox at the top is no suggestion at all.
     */
    private static final Pattern SAYS_SO = Pattern.compile(
            "vervangende?\\s+(?:bestelling|order|artikel|zending|levering)"
                    + "|(?:we|wij)\\s+sturen\\s+(?:je\\s+)?(?:een\\s+)?nieuwe?\\b[^\\n]{0,40}?\\bop\\b"
                    + "|omdat\\s+de\\s+vorige\\s+niet\\s+(?:aankwam|is\\s+aangekomen|aangekomen\\s+is)"
                    + "|opnieuw\\s+(?:verstuurd|verzonden|opgestuurd)"
                    + "|nogmaals\\s+(?:verstuurd|verzonden|opgestuurd)"
                    + "|replacement\\s+(?:order|item|parcel|shipment|has\\s+been\\s+sent)"
                    + "|(?:we\\s+have|we've|we)\\s+(?:sent|shipped)\\s+(?:you\\s+)?a\\s+replacement"
                    + "|(?:re-?sent|re-?shipped|re-?ordered)\\s+your\\s+(?:order|item|parcel)",
            Pattern.CASE_INSENSITIVE);

    /** As far into a body as a shop's own explanation of itself ever runs. The rest is small print. */
    private static final int BODY_OPENING = 1500;

    private ReplacementRules() {}

    /**
     * Whether this email says, in the shop's own words, that it is sending something out again.
     *
     * @param subject the subject line
     * @param text the flattened body, read only as far as {@link #BODY_OPENING} — past that is the
     *     returns policy, which explains replacements to every customer whether or not this is one
     */
    public static boolean says(String subject, String text) {
        if (subject != null && SAYS_SO.matcher(subject).find()) {
            return true;
        }
        if (text == null || text.isBlank()) {
            return false;
        }
        return SAYS_SO.matcher(text.substring(0, Math.min(text.length(), BODY_OPENING))).find();
    }
}
