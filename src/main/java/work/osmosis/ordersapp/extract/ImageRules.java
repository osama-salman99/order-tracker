package work.osmosis.ordersapp.extract;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * Finds the picture of the thing that was bought.
 *
 * <p>An order email carries roughly ten images and usually exactly one of them is the product. The
 * other nine are the shop's logo, social buttons, app-store badges, a tracking pixel, and — in a
 * delivery company's mail — a cartoon van. Picking the right one without keeping a list of every
 * retailer means finding a signal that separates them generally.
 *
 * <p>That signal is the <em>alt text</em>. Shops write the product's name into the alt attribute of
 * its picture, because that is what alt text is for, and something generic into everything else:
 * "Coolblue", "Arrow icon", "Download in the App Store". Since {@link ItemRules} has already read
 * the product name out of the body, the two can simply be compared. It works across shops that have
 * nothing else in common, and it degrades into a URL-shape check for the shops that leave alt empty.
 *
 * <p>Two things were tried first and are worth recording so they are not tried again. Matching the
 * URL against a list of retailer CDNs covered a third of the mail and nothing new. Filtering by the
 * image's displayed width threw away the right answer: Coolblue sends a genuine 500x500 photograph
 * and displays it fifty pixels wide, so "too small to be a product" removed exactly the picture
 * being looked for. Displayed size says how the email was laid out, not what the image is.
 */
public final class ImageRules {

    /**
     * Never a product. Tracking pixels, layout scaffolding, social and app-store buttons, and the
     * stock artwork delivery companies decorate their status mail with.
     */
    private static final Pattern FURNITURE = Pattern.compile(
            "(pixel|spacer|trans\\.gif|blank\\.|clear\\.gif|/open\\b|/o\\.gif|beacon|emltrk|/ee/v1/"
                    + "|/track|tracking|trco\\.|/ci0/|/cl0/"
                    + "|logo|favicon|/icons?[-_/]|sprite|arrow|chevron|divider|separator|bullet|line\\.gif"
                    + "|facebook|instagram|twitter|linkedin|youtube|whatsapp|pinterest|tiktok"
                    + "|appstore|playstore|google-?play|app-?store|badge|qr-?code"
                    + "|/header|/footer|banner|hero|background|/bg[-_]|wave|curve"
                    + "|star|rating|thumbs?[-_]|smiley|emoji|avatar"
                    + "|illustration|/assets/v\\d|/phase_|ontheway|delivered-to"
                    + "|versand-status|sendcloud\\.sc/brands|/sfmc)",
            Pattern.CASE_INSENSITIVE);

    /** URL shapes that say "a picture of a product" without needing to know which shop. */
    private static final Pattern PRODUCT_URL = Pattern.compile(
            "(/images?/products?/|/products?/\\d|/catalog/product|/media/catalog|/transparent/\\d+x\\d+/"
                    + "|media-amazon\\.com/images/I/|image\\.coolblue\\.(io|nl)"
                    + "|cdn\\.shopify\\.com/s/files/.+/(products|files)/"
                    + "|/artikel|/sku/|/productimage)",
            Pattern.CASE_INSENSITIVE);

    /** Alt text belonging to the email's furniture rather than to a thing that was bought. */
    private static final Pattern FURNITURE_ALT = Pattern.compile(
            "^(|\\[alt\\]|alt|image|afbeelding|logo\\b.*|.*\\blogo|arrow.*|.*icon|icon.*"
                    + "|download.*|discover.*|volg ons.*|follow us.*|shop now.*|bekijk.*|lees meer.*"
                    + "|klantenservice.*|customer service.*|anything for a smile\\.?)$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern WORD = Pattern.compile("[A-Za-z][\\w-]{2,}");

    /** Anything past this is a page, a video or a font — not a picture we can show. */
    private static final Pattern IMAGE_LIKE = Pattern.compile("^https?://", Pattern.CASE_INSENSITIVE);

    /**
     * Below this the evidence is too thin to be worth a picture. A wrong picture is worse than none
     * — it says the order is something it is not, which no other field on the page can do.
     */
    private static final double ENOUGH = 4.0;

    private ImageRules() {}

    /**
     * The product picture's address, or null when the email has none worth trusting.
     *
     * @param html the email's HTML part, untouched — this is the one rule that cannot work from the
     *     flattened text, since flattening is what throws the images away
     * @param itemSummary what {@link ItemRules} read out of the same email, which is what the alt
     *     text is compared against
     */
    public static String find(String html, String itemSummary) {
        if (html == null || html.isBlank()) {
            return null;
        }
        Document doc = Jsoup.parse(html);
        Set<String> nameWords = wordsOf(itemSummary == null ? "" : withoutTheCount(itemSummary));
        List<Element> anchors = anchorsFor(doc, nameWords);

        String best = null;
        double bestScore = 0;
        for (Element img : doc.select("img[src]")) {
            String src = img.attr("src").trim();
            if (!IMAGE_LIKE.matcher(src).find() || FURNITURE.matcher(src).find()) {
                continue;
            }
            double score = scoreOf(img, src, nameWords, anchors);
            if (score > bestScore) {
                bestScore = score;
                best = src;
            }
        }
        return bestScore >= ENOUGH ? best : null;
    }

    private static double scoreOf(Element img, String src, Set<String> nameWords, List<Element> anchors) {
        double score = 0;

        String alt = img.attr("alt").trim();
        if (!alt.isEmpty() && !FURNITURE_ALT.matcher(alt).matches()) {
            Set<String> altWords = wordsOf(alt);
            int shared = shared(altWords, nameWords);
            if (shared >= 2) {
                // The alt text names the same thing the body listed. Nothing else comes close.
                score += 5;
            } else if (shared == 1) {
                score += 2.5;
            } else if (altWords.size() >= 3) {
                // No item name to compare against, but the alt text reads like one.
                score += 2;
            }
        }

        if (PRODUCT_URL.matcher(src).find()) {
            score += 4;
        }

        if (!anchors.isEmpty()) {
            int nearest = Integer.MAX_VALUE;
            for (Element anchor : anchors) {
                nearest = Math.min(nearest, distance(img, anchor));
            }
            if (nearest <= 8) {
                score += (9 - nearest) / 4.0;
            }
        }

        // Displayed size is not used to reject anything — see the note on the class — but an image
        // laid out at postage-stamp size is a decoration whatever its URL says.
        int width = number(img.attr("width"));
        if (width > 0 && width < 25) {
            score -= 2;
        }
        return score;
    }

    /** The elements whose text contains a word from the item name, for measuring nearness to. */
    private static List<Element> anchorsFor(Document doc, Set<String> nameWords) {
        List<Element> found = new ArrayList<>();
        int used = 0;
        for (String word : nameWords) {
            if (used++ >= 3) {
                break;
            }
            Elements matching = doc.select("*:matchesOwn((?i)\\b" + Pattern.quote(word) + "\\b)");
            for (int i = 0; i < matching.size() && i < 4; i++) {
                found.add(matching.get(i));
            }
        }
        return found;
    }

    /** How far apart two elements are in the document, counted through their nearest shared parent. */
    private static int distance(Element a, Element b) {
        List<Element> up = new ArrayList<>();
        for (Element e = a; e != null; e = e.parent()) {
            up.add(e);
        }
        int down = 0;
        for (Element e = b; e != null; e = e.parent()) {
            int index = up.indexOf(e);
            if (index >= 0) {
                return index + down;
            }
            down++;
        }
        return Integer.MAX_VALUE;
    }

    /** The item summary lists several products; only the first one is named in full. */
    private static String withoutTheCount(String summary) {
        String first = summary.replaceAll("\\s*\\+\\d+ more$", "").trim();
        int bar = first.indexOf(" | ");
        return bar > 0 ? first.substring(0, bar) : first;
    }

    private static Set<String> wordsOf(String text) {
        Set<String> words = new LinkedHashSet<>();
        var matcher = WORD.matcher(text);
        while (matcher.find()) {
            words.add(matcher.group().toLowerCase(Locale.ROOT));
        }
        return words;
    }

    private static int shared(Set<String> a, Set<String> b) {
        int count = 0;
        for (String word : a) {
            if (b.contains(word)) {
                count++;
            }
        }
        return count;
    }

    private static int number(String value) {
        var matcher = Pattern.compile("^(\\d+)").matcher(value == null ? "" : value.trim());
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }
}
