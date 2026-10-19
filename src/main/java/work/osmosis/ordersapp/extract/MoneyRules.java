package work.osmosis.ordersapp.extract;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds what an order cost.
 *
 * <p>Only the total is wanted, and an email is full of other amounts — item prices, shipping, VAT,
 * discounts, and a footer offering ten euros off the next order. So the amount is never taken by
 * shape alone: it has to follow a word that means "this is the total", and the more specific words
 * are tried first so that "total" does not win over "total to pay" in an email containing both.
 */
public final class MoneyRules {

    /** Most specific first — the first phrase that matches anywhere is the one believed. */
    private static final List<String> TOTAL_PHRASES = List.of(
            "totaalbedrag|total\\s*amount|amount\\s*paid|bedrag\\s*betaald|grand\\s*total"
                    // "Factuurbedrag" is what a Dutch shop calls the sum it charged, and
                    // "Totaalprijs incl. btw" what another calls the same thing. Both sit beside a
                    // "Subtotaal" that is a few euros lower, so both have to be named here rather
                    // than left to the plain word "total".
                    + "|factuurbedrag|totaalprijs\\s*incl(?:usief)?|eindtotaal",
            "te\\s*betalen|order\\s*total|totaal\\s*bestelling|totaal\\s*te\\s*voldoen",
            "totaal|total");

    /** The amount written on the same line as the word that introduces it. */
    private static final List<Pattern> SAME_LINE =
            TOTAL_PHRASES.stream().map(p -> compile(p, "[^\\n]{0,40}")).toList();

    /**
     * The word standing alone on its own line, which is how a till receipt is laid out: a narrow
     * column with "Totaal" on one line and the sum on the next.
     *
     * <p>Reading downwards like this was at first allowed wherever the word appeared, and that was a
     * mistake worth recording: it filled forty-two more orders in, and a good few of them were
     * wrong — a table header called "Total" took the first item's price, an address block took a
     * house number, and a carrier's import-duty receipt took the duty instead of what the order
     * cost. Hence the two conditions below, which together describe a receipt column and almost
     * nothing else: the label has the line to itself, and the next line holds an amount and nothing
     * else. Anything looser buys coverage with lies.
     */
    private static final List<Pattern> LABEL_ALONE = TOTAL_PHRASES.stream()
            .map(p -> Pattern.compile("^\\s*(?:" + p + ")\\s*:?\\s*$", Pattern.CASE_INSENSITIVE))
            .toList();

    /** A line holding an amount and nothing besides. */
    private static final Pattern AMOUNT_ALONE = Pattern.compile(
            "^\\s*(?:(?:€|\\$|£|EUR|USD|JOD)\\s*\\d{1,3}(?:[.,]\\d{3})*(?:[.,]\\d{1,2})?"
                    + "|\\d{1,3}(?:[.,]\\d{3})*(?:[.,]\\d{1,2})?\\s*(?:€|\\$|£|EUR|USD|JOD))\\s*$",
            Pattern.CASE_INSENSITIVE);

    /**
     * An amount in euros, dollars or Jordanian dinars, with the symbol on either side. Both decimal
     * conventions appear — Dutch mail writes {@code 84,20} and English mail {@code 84.20}.
     */
    private static final String AMOUNT = "(\\d{1,3}(?:[.,]\\d{3})*(?:[.,]\\d{1,2})?)";

    private static final Pattern SYMBOL_FIRST = Pattern.compile("(€|\\$|£|EUR|USD|JOD)\\s*" + AMOUNT);
    private static final Pattern SYMBOL_LAST = Pattern.compile(AMOUNT + "\\s*(€|\\$|£|EUR|USD|JOD)");

    private MoneyRules() {}

    /**
     * The phrase has to begin a word. Without that, "total" matches inside "Subtotal" and the wrong
     * number is believed with complete confidence: every takeaway order in this mailbox was filed at
     * its subtotal, a few euros under what was actually paid, because the subtotal line comes first.
     * A shop that prints only a subtotal now states no total at all, which is the intended outcome —
     * a blank price says less than a wrong one, but it does not mislead.
     */
    private static Pattern compile(String phrase, String tail) {
        return Pattern.compile("(?<!\\p{L})(?:" + phrase + ")\\b" + tail, Pattern.CASE_INSENSITIVE);
    }

    /** The order's total as it should be shown, or null when the email did not state one. */
    public static String findTotal(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String beside = firstAmount(SAME_LINE, text);
        if (beside != null) {
            return beside;
        }
        String below = amountUnderTheLabel(text);
        return below != null ? below : amountWithNoSymbol(text);
    }

    /**
     * The last resort: a sum written with no currency mark at all, as a Dutch invoice lays its
     * closing lines out — "Eindtotaal 194,97". Only the handful of words that can mean nothing but
     * the amount charged are allowed to introduce one, because without a symbol the pattern is just
     * a number and every quantity, house number and article code in the email looks like one. The
     * currency is taken to be euros, which is what these particular Dutch words are always billed in.
     */
    private static String amountWithNoSymbol(String text) {
        for (Pattern label : BARE_LABELS) {
            Matcher line = label.matcher(text);
            while (line.find()) {
                Matcher amount = BARE_AMOUNT.matcher(line.group());
                if (amount.find()) {
                    return "€ " + amount.group(1);
                }
            }
        }
        return null;
    }

    private static final List<Pattern> BARE_LABELS =
            List.of("eindtotaal", "totaalbedrag", "factuurbedrag", "totaal\\s*te\\s*voldoen").stream()
                    .map(p -> compile(p, "[^\\n]{0,20}"))
                    .toList();

    /** A number written with two decimals, in either convention. Anything else is not a price. */
    private static final Pattern BARE_AMOUNT =
            Pattern.compile("(\\d{1,3}(?:\\.\\d{3})*,\\d{2}|\\d{1,3}(?:,\\d{3})*\\.\\d{2})");

    /** The receipt column: the label alone on its line, the amount alone on the next. */
    private static String amountUnderTheLabel(String text) {
        String[] lines = text.split("\n");
        for (Pattern label : LABEL_ALONE) {
            for (int i = 0; i < lines.length - 1; i++) {
                if (!label.matcher(lines[i]).matches()) {
                    continue;
                }
                int next = i + 1;
                while (next < lines.length && lines[next].isBlank()) {
                    next++;
                }
                if (next < lines.length
                        && AMOUNT_ALONE.matcher(lines[next]).matches()
                        && !amountFollows(lines, next + 1)) {
                    return amountIn(lines[next]);
                }
            }
        }
        return null;
    }

    private static String firstAmount(List<Pattern> phrases, String text) {
        for (Pattern phrase : phrases) {
            Matcher line = phrase.matcher(text);
            while (line.find()) {
                String amount = amountIn(line.group());
                if (amount != null) {
                    return amount;
                }
            }
        }
        return null;
    }

    /**
     * Whether a second amount stands directly under the first. A shop that lays its summary out as
     * two columns flattens to a run of labels followed by a run of figures — "Delivery / Total /
     * € 0.99 / € 101.98" — and there the figure under the word "Total" is the delivery charge, not
     * the total. One order was filed at 99 cents that way. Amounts stacked like that say the
     * pairing is not what it looks like, so nothing is taken at all.
     */
    private static boolean amountFollows(String[] lines, int from) {
        int i = from;
        while (i < lines.length && lines[i].isBlank()) {
            i++;
        }
        return i < lines.length && AMOUNT_ALONE.matcher(lines[i]).matches();
    }

    private static String amountIn(String fragment) {
        Matcher first = SYMBOL_FIRST.matcher(fragment);
        if (first.find()) {
            return format(first.group(1), first.group(2));
        }
        Matcher last = SYMBOL_LAST.matcher(fragment);
        if (last.find()) {
            return format(last.group(2), last.group(1));
        }
        return null;
    }

    private static String format(String symbol, String amount) {
        String currency = switch (symbol.toUpperCase(java.util.Locale.ROOT)) {
            case "EUR" -> "€";
            case "USD" -> "$";
            case "GBP" -> "£";
            default -> symbol;
        };
        return currency.length() == 1 ? currency + " " + amount : currency + " " + amount;
    }
}
