package work.osmosis.ordersapp.ingest;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeVisitor;

/**
 * Flattens retailer HTML into text that still has its structure.
 *
 * <p>This matters more than it sounds. Retailer mail is a nest of layout tables, and
 * {@code Jsoup.text()} runs every cell together into one line — which glues a label to the number
 * beside it ("Totaal€84,20Verzendkosten") and destroys exactly the adjacency the rules read. Here
 * each cell is separated and each block element ends a line, so "Total" and its amount stay
 * neighbours on one line and unrelated values stay apart.
 */
final class HtmlText {

    private HtmlText() {}

    /** Block-level elements that should end the current line. */
    private static final String BLOCKS =
            "p, div, br, tr, li, h1, h2, h3, h4, h5, h6, table, thead, tbody, ul, ol, "
                    + "section, article, header, footer, blockquote, hr";

    static String flatten(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Document doc = Jsoup.parse(html);
        doc.select("style, script, head noscript").remove();

        StringBuilder out = new StringBuilder();
        doc.body().traverse(
                new NodeVisitor() {
                    @Override
                    public void head(Node node, int depth) {
                        if (node instanceof TextNode t) {
                            String s = t.text();
                            if (!s.isBlank()) {
                                out.append(s);
                            }
                        } else if (node instanceof Element e && e.is("td, th")) {
                            // Two spaces, not a newline: a label and its value usually live in
                            // adjacent cells of the same row and must stay on one line.
                            out.append("  ");
                        }
                    }

                    @Override
                    public void tail(Node node, int depth) {
                        if (node instanceof Element e && e.is(BLOCKS)) {
                            out.append('\n');
                        }
                    }
                });

        return collapse(out.toString());
    }

    /**
     * Squeezes runs of blank lines and trailing spaces, and removes the invisible characters that
     * retailer mail is padded with. Retailer HTML generates enormous amounts of vertical whitespace,
     * and left alone it pushes the real content out of any excerpt.
     */
    static String collapse(String text) {
        if (text == null) {
            return "";
        }
        // Two kinds of invisible character have to go before anything reads this.
        //
        // The first is &nbsp;, which String.strip() does not treat as whitespace and which would
        // otherwise survive into the middle of every extracted value.
        //
        // The second is subtler and did real damage: the plain-text half of a marketing email is
        // padded with hundreds of raw "&#8204;" entities — zero-width non-joiners, used to stretch
        // the preview line an inbox shows. Nothing decodes them on that path, so the rules saw the
        // literal text "#8204" over and over and read it as an order number, which merged every
        // order from that shop into one. Decoding them first turns them back into the invisible
        // characters they are, and stripping those leaves nothing behind to misread.
        String decoded = org.jsoup.parser.Parser.unescapeEntities(text, false)
                .replace('\u00A0', ' ')
                .replaceAll("[\u200B-\u200F\uFEFF\u2060]", "");
        String[] lines = decoded.split("\n");
        StringBuilder out = new StringBuilder();
        int blanks = 0;
        for (String raw : lines) {
            String line = raw.replaceAll("[ \t]+", " ").strip();
            if (line.isEmpty()) {
                blanks++;
                if (blanks > 1) {
                    continue;
                }
            } else {
                blanks = 0;
            }
            out.append(line).append('\n');
        }
        return out.toString().strip();
    }
}
