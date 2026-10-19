package work.osmosis.ordersapp.extract;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out what was actually bought.
 *
 * <p>An email's subject line is almost never the answer. "Your order is successful", "Bedankt voor
 * je bestelling", "Je pakket is bezorgd" — every shop writes about the order rather than about the
 * thing, so a timeline built from subjects says nothing about what is arriving. The product names
 * are in the body instead, in the list of items the shop prints back to you.
 *
 * <p>That list has a recognisable shape across shops: a heading introduces it, the shop's own table
 * headings and labels come first, then the product, then its quantity and price, and the block ends
 * at the totals or the delivery address. So the block is found by its heading, closed at its ending,
 * and the first line inside it that reads like the name of a thing — rather than a heading, a label,
 * a seller, a date, a price or a sentence — is the item.
 *
 * <p>Precision matters far more than coverage. A wrong product name is worse than none at all: the
 * shop, the date and the parcel are already on the page, so a missing name costs a little detail
 * while a wrong one makes the whole row lie. Everything doubtful is therefore dropped and the order
 * goes untitled, which the timeline is built to cope with.
 */
public final class ItemRules {

    /** Headings that introduce the list of items, in both languages the mailbox uses. */
    private static final Pattern BLOCK_START = Pattern.compile(
            "^\\s*(?:dit heb je besteld|wat je hebt besteld|je bestelling|jouw bestelling"
                    // The same shop's headings for the later chapters of the same order: what is on
                    // its way, and what has arrived. They introduce the identical item table, and
                    // without them an order whose confirmation was never received — or whose only
                    // email is the replacement one — had nothing to take a name from.
                    + "|dit sturen we (?:op|naar je)|dit is (?:bezorgd|onderweg|verzonden)"
                    + "|dit heb(?:ben)? (?:je|we) (?:ontvangen|bezorgd)"
                    + "|bestelde artikelen|je artikelen|jouw artikelen|besteloverzicht"
                    + "|overzicht van je bestelling|je producten|bestelde producten"
                    + "|you ordered this|your order|order summary|order overview|order details"
                    + "|items ordered|your items|what you ordered|items in this (?:order|shipment)"
                    + "|in this (?:order|parcel|shipment)"
                    // The heading a till receipt puts above what was carried out of the shop.
                    + "|gekochte artikelen|purchased items)\\s*:?\\s*$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Where the list stops. The money and the address always follow it, and so does the summary of
     * the order's own paperwork — one shop prints "Order Number" and the shipping address directly
     * under a heading called "Order Summary", which is otherwise a perfectly good place to start.
     */
    private static final Pattern BLOCK_END = Pattern.compile(
            "^\\s*(?:sub)?(?:totaal|total)\\b|^\\s*verzendkosten|^\\s*shipping\\b|^\\s*delivery\\s+(?:free|costs?)"
                    + "|^\\s*bezorgadres|^\\s*afleveradres|^\\s*factuuradres|^\\s*invoice address"
                    + "|^\\s*(?:delivery|billing|shipping) address|^\\s*ship to\\b|^\\s*je hebt betaald"
                    + "|^\\s*betaal(?:methode|wijze)|^\\s*payment|^\\s*order (?:number|date|id|total)\\s*$"
                    + "|^\\s*(?:bestel|order)nummer\\s*$|^\\s*besteldatum\\s*$"
                    + "|^\\s*hier wordt het bezorgd|^\\s*change (?:order|delivery)|^\\s*kan ik nog"
                    + "|^\\s*herroepingsrecht|^\\s*you may also like|^\\s*misschien vind je"
                    + "|^\\s*(?:aanbevolen|recommended|others also)\\b",
            Pattern.CASE_INSENSITIVE);

    /** A line that is only a price, which is what follows an item rather than being one. */
    private static final Pattern PRICE_ONLY = Pattern.compile(
            "^\\s*(?:€|\\$|£|eur|usd|us\\s*\\$)?\\s*[0-9][0-9.,\\s]*(?:€|\\$|£|eur|usd|ea\\.?|,-)?\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** A quantity on its own line: "1x", "2 x", "Aantal: 3". */
    private static final Pattern QUANTITY = Pattern.compile(
            "^\\s*(?:aantal|quantity|qty)?\\s*:?\\s*\\d{1,3}\\s*(?:x|×|stuks?|pcs?)?\\s*$", Pattern.CASE_INSENSITIVE);

    /** Fields the shop prints beside an item; the label is not the product. */
    private static final Pattern LABELLED = Pattern.compile(
            "^\\s*(?:bestelnummer|ordernummer|bestelling|order(?:\\s*(?:number|no|id|date))?|verkoper|seller"
                    + "|bezorgdatum|leverdatum|verwachte?\\s+\\w+|delivery(?:\\s+\\w+)?|levering|prijs|price"
                    + "|artikelnummer|artikelnr\\.?|art\\.?\\s*(?:nr|no)\\.?|item\\s*(?:number|no)"
                    + "|kleur|colou?r|maat|size|sold by|verzonden door"
                    + "|bezorging|track\\s*(?:&|and|en)?\\s*trace|tracking\\s*(?:number|nummer)?|status)"
                    // The colon is optional: plenty of shops write "Bestelnummer 1234567890" plain.
                    + "\\s*(?::|\\s+[A-Z0-9][A-Z0-9-]{3,}\\s*$)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Whole lines that are a shop's furniture rather than a product: the headings of the item table,
     * the greeting, and the "sold by" line the marketplaces print above the thing you actually
     * bought. These are matched as complete lines, so a product whose name contains one of the words
     * survives.
     */
    private static final Set<String> FURNITURE = Set.of(
            "artikel", "artikelen", "aantal", "hoeveelheid", "bezorging", "levering", "product", "producten",
            "omschrijving", "beschrijving", "description", "item", "items", "qty", "qty.", "quantity", "price",
            "prijs", "bedrag", "amount", "total", "totaal", "subtotal", "subtotaal", "vat", "btw", "verkoper",
            "seller", "shipping", "delivery", "size", "maat", "kleur", "colour", "color", "model", "status",
            "datum", "date", "goods", "the \"goods\"", "stuks", "gratis", "free", "n.v.t.", "notes",
            "tracking number", "trackingnummer", "track & trace", "track and trace", "carrier", "vervoerder",
            // Loyalty points are printed in the same table as the items and are not a thing that
            // gets delivered: one order was titled "punten +4 more".
            "punten", "spaarpunten", "points", "reward points", "saldo", "korting", "discount",
            // A variant printed under the item is a property of it, not a name of its own.
            "xxs", "xxl", "xxxl", "one size", "black", "white", "green", "blue", "red", "pink", "grey", "gray",
            "beige", "brown", "yellow", "orange", "purple", "silver", "gold", "navy", "khaki", "zwart", "wit",
            "groen", "blauw", "rood", "roze", "grijs", "bruin", "geel", "paars", "zilver", "goud");

    /** Openers that mean the line is about the order or the reader, never about a product. */
    private static final Pattern NOT_A_PRODUCT = Pattern.compile(
            "^(?:hi|hey|hallo|hoi|beste|dear|geachte)\\b.{0,30}$"
                    + "|^(?:placed|ordered|besteld|geplaatst)\\s+(?:on|op)\\b"
                    + "|^(?:gekocht|besteld|geleverd|verzonden|verkocht)\\s+(?:bij|door|via)\\b"
                    + "|^(?:sold|shipped|fulfilled|delivered)\\s+by\\b"
                    + "|^(?:view|bekijk|volg|track|download|klik|click)\\b"
                    // The marketplaces print the seller's shop name directly above the thing bought,
                    // and a seller is always named "<something> Store" there.
                    + "|^[\\p{L}0-9][\\p{L}0-9 &.'-]{0,24}\\s+store$",
            Pattern.CASE_INSENSITIVE);

    /**
     * The reassurances a shop prints beside its item list — the returns window, the free delivery,
     * the invitation to follow the parcel. Matched as whole lines so a product keeps its words.
     */
    private static final Pattern PATTER = Pattern.compile(
            "^(?:\\d+\\s+(?:dagen|days?|weken|weeks?|maanden|months?)\\b.*"
                    + "|gratis\\b.*|free\\s+(?:delivery|shipping|returns?)\\b.*"
                    + "|(?:incl|excl|inclusief|exclusief)\\b.*|meer\\s+info.*|more\\s+info.*"
                    + "|naar\\s+je\\s+bestelling|to\\s+your\\s+order|op\\s+voorraad|in\\s+stock"
                    + "|niet\\s+op\\s+voorraad|out\\s+of\\s+stock|let\\s+op\\b.*|note\\b.*)$",
            Pattern.CASE_INSENSITIVE);

    /** A date or a date and time standing alone, which is what an order line's metadata looks like. */
    private static final Pattern DATE_LIKE = Pattern.compile(
            "^\\s*(?:\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{2,4}|\\d{4}-\\d{2}-\\d{2}"
                    + "|\\p{L}{3,10}\\s+\\d{1,2},?\\s*\\d{4}|\\d{1,2}\\s+\\p{L}{3,10}\\s+\\d{4})"
                    + "[,\\s]*(?:\\d{1,2}:\\d{2})?\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** A Dutch postcode, i.e. the delivery address has started even without a heading to say so. */
    private static final Pattern ADDRESS_LIKE = Pattern.compile("\\b\\d{4}\\s?[A-Z]{2}\\b");

    /** The carriers turn up inside item tables as the delivery method for each line. */
    private static final Set<String> CARRIER_WORDS = Set.of(
            "postnl", "post nl", "dhl", "dhl express", "dpd", "ups", "gls", "fedex", "bpost", "homerr",
            "budbee", "trunkrs", "aramex", "inpost", "cycloon", "dynalogic", "red je pakketje");

    /**
     * Amazon puts the product in the subject and nothing else does. Amazon has already abbreviated it
     * with an ellipsis, which is fine — it is what Amazon shows you too.
     */
    private static final Pattern SUBJECT_QUOTED = Pattern.compile(
            "^\\s*(?:ordered|shipped|dispatched|delivered|out for delivery|verzonden|bezorgd|besteld)\\s*:\\s*"
                    + "(?:\\p{Nd}+\\s+)?[\"'‘“‹«]([^\"'’”›»]{3,90})",
            Pattern.CASE_INSENSITIVE);

    /** A link is written "name (https://…)" once the HTML has been flattened. */
    private static final Pattern TRAILING_LINK = Pattern.compile("\\s*\\((?:https?://|www\\.)\\S*\\)\\s*$");

    /** Shops append the quantity to the name: "Air75 Wireless Mechanical Keyboard × 1". */
    private static final Pattern TRAILING_QUANTITY = Pattern.compile("\\s*[x×]\\s*\\d{1,3}\\s*$", Pattern.CASE_INSENSITIVE);

    /** How many lines past the heading the item list can plausibly still be running. */
    private static final int BLOCK_LINES = 40;

    private ItemRules() {}

    /**
     * A short description of what was bought, or {@code null} when the email does not say clearly.
     *
     * @param subject the subject line, which only Amazon uses to name the product
     * @param text the body, already flattened out of HTML
     */
    public static String find(String subject, String text) {
        String fromSubject = fromSubject(subject);
        if (fromSubject != null) {
            return fromSubject;
        }
        List<String> items = itemsInBody(text);
        if (items.isEmpty()) {
            items = itemsAboveTheTotal(text);
        }
        if (items.isEmpty()) {
            return null;
        }
        String first = items.get(0);
        // The count is only worth printing when the rest of the block was as clean as the first line,
        // so an implausibly long list is reported as a name alone rather than as a wrong number.
        if (items.size() == 1 || items.size() > 8) {
            return first;
        }
        return first + " +" + (items.size() - 1) + " more";
    }

    private static String fromSubject(String subject) {
        if (subject == null) {
            return null;
        }
        Matcher m = SUBJECT_QUOTED.matcher(subject);
        return m.find() ? tidy(m.group(1)) : null;
    }

    /**
     * The item names inside the block the shop prints them in. Only the first block is read: later
     * ones are recommendations, and a recommendation is not something that is on its way.
     */
    private static List<String> itemsInBody(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (BLOCK_START.matcher(lines[i]).matches()) {
                List<String> items = itemsFrom(lines, i + 1);
                if (!items.isEmpty()) {
                    return items;
                }
            }
        }
        return itemsUnderColumnNames(lines);
    }

    /**
     * The list of a shop that heads its table with the column names alone — "Producten Aantal prijs"
     * — and writes nothing else to introduce it.
     *
     * <p>Only tried once every proper heading has been tried and found empty, and hedged twice over,
     * because column names are common and most of what follows them is not a product. The wrapped
     * remainder of the heading is stepped over, and then the very first row has to read like the name
     * of a thing: a table whose first line is a sentence or a row of figures is not an item list, and
     * abandoning it there is what keeps a grocery order from being titled with the small print about
     * vouchers.
     */
    private static List<String> itemsUnderColumnNames(String[] lines) {
        for (int i = 0; i < lines.length; i++) {
            if (!isColumnHeading(lines[i])) {
                continue;
            }
            int first = i + 1;
            while (first < lines.length && (lines[first].isBlank() || isColumnHeading(lines[first]))) {
                first++;
            }
            if (first >= lines.length || !isProductName(tidy(withoutTrailingPrice(lines[first])))) {
                continue;
            }
            List<String> items = itemsFrom(lines, first);
            if (!items.isEmpty()) {
                return items;
            }
        }
        return List.of();
    }

    /**
     * Whether the whole line is the table's column names and nothing else: "Producten Aantal prijs".
     *
     * <p>A shop can head its list this way and never write a sentence introducing it, and one that
     * does put a heading higher up may be heading something else — an invoice repeats the words "Je
     * bestelling" above the order number and the address, with the actual goods printed further down
     * under these column names. That is why a block that turns out to hold nothing is not the end of
     * the search: the next one is tried, and the one after that.
     */
    private static boolean isColumnHeading(String line) {
        // The tax note a shop hangs off a column name — "Prijs (incl. btw)" — is part of the heading
        // and is dropped before the words are counted. A heading that wraps leaves its second half on
        // a line of its own, so the check has to recognise "(incl. btw) Aantal btw Totaal" as heading
        // too; read as a product it became the title of two grocery orders.
        String words = line.replaceAll("\\([^)]*\\)", " ").replaceAll("[.,:|]", " ").trim();
        if (words.isEmpty()) {
            return false;
        }
        String[] parts = words.split("\\s+");
        if (parts.length < 2 || parts.length > 6) {
            return false;
        }
        for (String word : parts) {
            if (!COLUMN_WORDS.contains(word.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    private static final Set<String> COLUMN_WORDS = Set.of(
            "product", "producten", "artikel", "artikelen", "omschrijving", "beschrijving", "description",
            "item", "items", "aantal", "hoeveelheid", "qty", "quantity", "prijs", "price", "stukprijs",
            "bedrag", "amount", "totaal", "total", "subtotaal", "subtotal", "btw", "vat", "incl",
            "inclusief", "excl", "exclusief",
            // The German shops in this mailbox head the same table in their own words.
            "preis", "anzahl", "menge", "gesamt", "summe", "mwst", "stück", "stuks");

    private static List<String> itemsFrom(String[] lines, int start) {
        List<String> items = new ArrayList<>();
        int limit = Math.min(lines.length, start + BLOCK_LINES);
        for (int i = start; i < limit; i++) {
            if (BLOCK_END.matcher(lines[i]).find()) {
                break;
            }
            if (isColumnHeading(lines[i])) {
                continue;
            }
            String candidate = tidy(withoutTrailingPrice(lines[i]));
            if (isProductName(candidate) && !containsIgnoringCase(items, candidate)) {
                items.add(candidate);
            }
        }
        return items;
    }

    /**
     * The item list of a shop that prints no heading above it, read upwards from the total.
     *
     * <p>The takeaway platform is why this exists, and it is the largest single group of orders in
     * this mailbox: thirty-eight meals, every one of them a row saying nothing but the platform's
     * name, because the food is listed under no heading at all — just the dish, then its price, then
     * the next dish. Reading the list downwards would need a way to know where it starts, which is
     * the thing that is missing. Reading it upwards does not: the totals line is where an item list
     * always ends, and above it a price on its own line is always the price of the line above that.
     *
     * <p>So the walk begins at the totals and climbs, taking price-then-name in pairs, and stops the
     * moment the alternation breaks — at the restaurant's name, the address, or whatever else the
     * shop chose to put there. That refusal to guess is what keeps it honest: a body with no such
     * list ends the walk on its first step and the order stays untitled, which is the outcome this
     * whole class prefers to a name that might be wrong.
     */
    private static List<String> itemsAboveTheTotal(String text) {
        List<String> items = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return items;
        }
        String[] lines = text.split("\n");
        int total = -1;
        for (int i = 0; i < lines.length; i++) {
            if (BLOCK_END.matcher(lines[i]).find()) {
                total = i;
                break;
            }
        }
        if (total < 2) {
            return items;
        }
        int i = total - 1;
        while (items.size() < 8) {
            i = previousLine(lines, i);
            if (i < 0 || !PRICE_ONLY.matcher(lines[i]).matches()) {
                break;
            }
            i = previousLine(lines, i - 1);
            if (i < 0) {
                break;
            }
            String candidate = tidy(withoutLeadingQuantity(lines[i]));
            if (!isProductName(candidate) || containsIgnoringCase(items, candidate)) {
                break;
            }
            items.add(candidate);
            i--;
        }
        java.util.Collections.reverse(items);
        return items;
    }

    /**
     * "BLOCKTECH PARKA 1 € 79,90 T" is a parka. A shop with room for a wide table prints the count
     * and the price on the same line as the name, and left in place they become part of the title —
     * which reads as though the price were something the customer bought.
     */
    private static String withoutTrailingPrice(String line) {
        return TRAILING_PRICE.matcher(line).replaceFirst("");
    }

    private static final Pattern TRAILING_PRICE = Pattern.compile(
            "\\s+(?:\\d{1,3}\\s*(?:x|×|stuks?|pcs?)?\\s+)?(?:€|\\$|£|eur|usd)\\s*[0-9][0-9.,]*\\s*[A-Z]?\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** The nearest line at or above {@code from} that has anything on it. */
    private static int previousLine(String[] lines, int from) {
        int i = from;
        while (i >= 0 && lines[i].isBlank()) {
            i--;
        }
        return i;
    }

    /**
     * "1 Nashville burger" is one Nashville burger. The count belongs beside the name rather than
     * inside it, and only this walk sees names written that way — the shops that print a heading put
     * the quantity in its own column.
     */
    private static String withoutLeadingQuantity(String line) {
        return LEADING_QUANTITY.matcher(line).replaceFirst("");
    }

    private static final Pattern LEADING_QUANTITY =
            Pattern.compile("^\\s*\\d{1,3}\\s*[x×]?\\s+(?=\\p{L})");

    private static boolean containsIgnoringCase(List<String> items, String candidate) {
        return items.stream().anyMatch(existing -> existing.equalsIgnoreCase(candidate));
    }

    private static String tidy(String raw) {
        String cleaned = TRAILING_LINK.matcher(raw).replaceAll("");
        cleaned = TRAILING_QUANTITY.matcher(cleaned).replaceAll("");
        cleaned = cleaned.replaceAll("^\\s*[-*•·—]\\s*", "");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        // A name the shop has already shortened, written as three full stops. Left as it is, the
        // line ends in a full stop and the check below throws it away as a sentence — which is how
        // every bol order in this mailbox came out untitled, their product names sitting in plain
        // sight one line under the heading. Kept as a single ellipsis, because the truncation is
        // honest and worth showing: it is exactly what the shop's own email shows.
        cleaned = cleaned.replaceAll("\\s*\\.{2,}\\s*$", "…");
        // A name wrapped onto the next line leaves a bracket hanging open — "… Transit Game (Wave".
        // Cutting at the bracket reads better than showing half a parenthesis.
        int open = cleaned.lastIndexOf('(');
        if (open > 2 && cleaned.indexOf(')', open) < 0) {
            cleaned = cleaned.substring(0, open).trim();
        }
        while (cleaned.endsWith(",") || cleaned.endsWith(";") || cleaned.endsWith("-")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }
        return cleaned;
    }

    private static boolean isProductName(String line) {
        if (line.length() < 3 || line.length() > 90) {
            return false;
        }
        if (line.chars().noneMatch(Character::isLetter)) {
            return false;
        }
        String lower = line.toLowerCase(Locale.ROOT);
        if (FURNITURE.contains(lower) || CARRIER_WORDS.contains(lower)) {
            return false;
        }
        // A button, once the HTML has been flattened: one order was titled "[Track my package]".
        if (line.startsWith("[") && line.endsWith("]")) {
            return false;
        }
        if (PRICE_ONLY.matcher(line).matches()
                || QUANTITY.matcher(line).matches()
                || DATE_LIKE.matcher(line).matches()
                || ADDRESS_LIKE.matcher(line).find()
                || LABELLED.matcher(line).find()
                || PATTER.matcher(line).matches()
                || NOT_A_PRODUCT.matcher(line).find()) {
            return false;
        }
        // A price sitting on the same line as words is a specification or a total, not a name.
        if (line.matches(".*[€$£]\\s*\\d.*")) {
            return false;
        }
        // Product names are noun phrases. Anything written as a sentence to the reader is the shop
        // talking, and the second person is the giveaway in both languages.
        String padded = " " + lower + " ";
        for (String word : SECOND_PERSON) {
            if (padded.contains(" " + word + " ")) {
                return false;
            }
        }
        if (line.endsWith(".") || line.endsWith("!") || line.endsWith("?") || line.endsWith(":")) {
            return false;
        }
        return countWords(line) <= 14;
    }

    private static final String[] SECOND_PERSON = {
        "je", "jij", "jouw", "uw", "we", "wij", "you", "your", "our", "is", "are", "wordt", "heb", "hebt"
    };

    private static int countWords(String line) {
        return line.trim().split("\\s+").length;
    }
}
