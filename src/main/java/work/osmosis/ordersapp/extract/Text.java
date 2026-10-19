package work.osmosis.ordersapp.extract;

import java.util.regex.Pattern;

/** Small text preparations shared by the rules. */
final class Text {

    private static final Pattern LINK = Pattern.compile("(?:https?://|www\\.)\\S+", Pattern.CASE_INSENSITIVE);

    /** Anything that is shaped like a version 1 or 4 identifier rather than a human reference. */
    private static final Pattern UUID_SHAPED =
            Pattern.compile("[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}.*", Pattern.CASE_INSENSITIVE);

    /** A six-digit colour, which is what a stylesheet leaking into the text looks like. */
    private static final Pattern HEX_COLOUR = Pattern.compile("(?=.*[A-F])[0-9A-F]{6}", Pattern.CASE_INSENSITIVE);

    private Text() {}

    /**
     * The text with every web address blanked out.
     *
     * <p>Links are where the false references come from, and the corpus shows three separate ways
     * they do it: an IKEA link whose own path ends {@code volg-je-bestelling-pub4b31b950} sits
     * straight after the word "bestelling", so the page's name reads as an order number; a link to
     * {@code customer-service/#36c5bd97-af2d-11eb-…} reads as a hashed order number, and because
     * every IKEA email carries the same one it fused two unrelated orders into one; and a support
     * message's long random link token reads as a parcel number.
     *
     * <p>Nothing is lost by ignoring them. The numbers that genuinely live in a link are read out of
     * the query string by name instead, which is exact, rather than by whatever words happen to
     * precede the link in the sentence.
     */
    static String withoutLinks(String text) {
        return text == null ? "" : LINK.matcher(text).replaceAll(" ");
    }

    /** True when a candidate is really an identifier or a colour that happens to sit in the text. */
    static boolean isMachineNoise(String candidate) {
        return UUID_SHAPED.matcher(candidate).matches() || HEX_COLOUR.matcher(candidate).matches();
    }
}
