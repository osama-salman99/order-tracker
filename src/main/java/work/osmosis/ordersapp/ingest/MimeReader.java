package work.osmosis.ordersapp.ingest;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns the raw bytes of a forwarded email into a {@link ParsedEmail} describing the <em>original</em>
 * message.
 *
 * <p>Mail reaches this service by two different roads and they do not look alike:
 *
 * <ul>
 *   <li><b>Gmail filter forwarding</b> — the ongoing path. Gmail re-sends the retailer's message
 *       essentially intact: the original {@code From}, {@code Subject}, {@code Date} and
 *       {@code Message-ID} all survive, and there is nothing to unwrap.
 *   <li><b>A person pressing Forward</b> — how a back catalogue arrives. Now the envelope belongs
 *       to the forwarder, the real sender is buried in a banner inside the body, and the useful
 *       headers are gone. Some clients instead attach the original as a {@code message/rfc822}
 *       part, which is the easy case: the whole original message is right there.
 * </ul>
 *
 * <p>Both are handled, in that order of preference: an attached original beats a parsed banner,
 * and a banner beats believing the envelope.
 */
@Component
public class MimeReader {

    private static final Logger log = LoggerFactory.getLogger(MimeReader.class);

    /**
     * The banner clients write above a forwarded message. Gmail uses a varying number of dashes and
     * translates the words; Apple Mail writes "Begin forwarded message:". Matching the line loosely
     * and then reading the headers underneath survives more variants than matching it exactly.
     */
    private static final Pattern FORWARD_BANNER =
            Pattern.compile(
                    "(?im)^\\s*(?:-{2,}\\s*(?:forwarded message|doorgestuurd bericht|"
                            + "message transféré|weitergeleitete nachricht)\\s*-{2,}"
                            + "|begin forwarded message:)\\s*$");

    /** A header line inside a forward banner, in the languages Gmail is likely to write. */
    private static final Pattern BANNER_HEADER =
            Pattern.compile(
                    "(?im)^\\s*(from|van|de|von|subject|onderwerp|objet|betreff|date|datum|to|aan|à|an)"
                            + "\\s*:\\s*(.+)$");

    private static final Pattern ANGLE_ADDRESS = Pattern.compile("<([^<>@\\s]+@[^<>@\\s]+)>");
    private static final Pattern BARE_ADDRESS = Pattern.compile("([^<>@\\s,;]+@[^<>@\\s,;]+)");

    /**
     * Reads a raw RFC-822 message.
     *
     * @param raw the bytes exactly as the worker posted them
     * @param selfAddresses addresses belonging to the person forwarding — when the envelope sender
     *     is one of these, the real sender is inside the body and the banner is worth trusting
     */
    public ParsedEmail read(byte[] raw, List<String> selfAddresses) throws Exception {
        Session session = Session.getInstance(new Properties());
        MimeMessage message = new MimeMessage(session, new ByteArrayInputStream(raw));
        return read(message, selfAddresses, false);
    }

    private ParsedEmail read(MimeMessage message, List<String> selfAddresses, boolean alreadyUnwrapped)
            throws Exception {

        // An attached original wins outright: it is the real message, headers and all.
        MimeMessage attached = findAttachedMessage(message);
        if (attached != null) {
            log.debug("Unwrapping a message/rfc822 attachment");
            return read(attached, selfAddresses, true);
        }

        String bodyText = extractText(message);

        String messageId = header(message, "Message-ID");
        String subject = decode(message.getSubject());
        Instant sentAt = message.getSentDate() != null ? message.getSentDate().toInstant() : null;

        String senderName = null;
        String senderAddress = null;
        Address[] from = message.getFrom();
        if (from != null && from.length > 0 && from[0] instanceof InternetAddress ia) {
            senderName = decode(ia.getPersonal());
            senderAddress = ia.getAddress();
        }

        boolean unwrapped = alreadyUnwrapped;

        // If the envelope sender is the forwarder, the retailer is named in the banner instead.
        if (isSelf(senderAddress, selfAddresses)) {
            Banner banner = parseBanner(bodyText);
            if (banner != null && banner.address != null) {
                log.debug("Unwrapping a forward banner from {}", banner.address);
                senderName = banner.name;
                senderAddress = banner.address;
                if (banner.subject != null && !banner.subject.isBlank()) {
                    subject = banner.subject;
                }
                if (banner.sentAt != null) {
                    sentAt = banner.sentAt;
                }
                // The forward has its own Message-ID, which would make two forwards of one email
                // look like two different emails. Key on the original instead.
                messageId = syntheticId(senderAddress, subject, sentAt);
                unwrapped = true;
            }
        }

        if (messageId == null || messageId.isBlank()) {
            messageId = syntheticId(senderAddress, subject, sentAt);
        }

        return new ParsedEmail(
                messageId,
                subject == null ? "" : subject,
                senderName,
                senderAddress,
                sentAt,
                bodyText,
                unwrapped);
    }

    /** Depth-first search for an attached {@code message/rfc822} part. */
    private MimeMessage findAttachedMessage(Part part) throws Exception {
        if (part.isMimeType("message/rfc822")) {
            Object content = part.getContent();
            if (content instanceof MimeMessage inner) {
                return inner;
            }
        }
        if (part.isMimeType("multipart/*") && part.getContent() instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                MimeMessage found = findAttachedMessage(mp.getBodyPart(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * The message's HTML, exactly as the shop wrote it.
     *
     * <p>Everything else about an email is read from the flattened text, which is the right thing
     * for every rule but one: flattening deletes the images, and the picture of what was bought is
     * only in the markup. So that one rule gets the markup, read back out of the archive rather than
     * kept in the database — the archive exists so that anything can be worked out again later, and
     * storing a second copy of every email to save re-parsing it would be a poor trade.
     *
     * @return the HTML, or null when the message carries none
     */
    public String htmlOf(byte[] raw) throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(raw));
        MimeMessage attached = findAttachedMessage(message);
        Collected collected = new Collected();
        collect(attached != null ? attached : message, collected);
        String html = collected.html.toString();
        return html.isBlank() ? null : html;
    }

    /**
     * Pulls readable text out of a message: the plain-text part if there is one, otherwise the HTML
     * flattened. Retailer mail is nearly always {@code multipart/alternative} carrying both, and the
     * plain-text half is usually the thinner of the two — so both are collected and the richer one
     * wins.
     */
    String extractText(Part part) throws Exception {
        Collected collected = new Collected();
        collect(part, collected);

        String plain = HtmlText.collapse(collected.plain.toString());
        String fromHtml = HtmlText.flatten(collected.html.toString());

        if (plain.isBlank()) {
            return fromHtml;
        }
        if (fromHtml.isBlank()) {
            return plain;
        }
        // Some retailers send a plain part that is a single "view this in your browser" line. Length
        // is a crude test but a reliable one at this scale.
        return fromHtml.length() > plain.length() * 2 ? fromHtml : plain;
    }

    private void collect(Part part, Collected out) throws Exception {
        if (part.isMimeType("text/plain")) {
            out.plain.append(asString(part.getContent())).append('\n');
            return;
        }
        if (part.isMimeType("text/html")) {
            out.html.append(asString(part.getContent())).append('\n');
            return;
        }
        if (part.isMimeType("multipart/*") && part.getContent() instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                // An attached original is handled elsewhere; descending into it here would blend the
                // covering note and the real message into one blob of text.
                if (!mp.getBodyPart(i).isMimeType("message/rfc822")) {
                    collect(mp.getBodyPart(i), out);
                }
            }
        }
    }

    private static String asString(Object content) {
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof byte[] b) {
            return new String(b, StandardCharsets.UTF_8);
        }
        return content == null ? "" : content.toString();
    }

    private static final class Collected {
        final StringBuilder plain = new StringBuilder();
        final StringBuilder html = new StringBuilder();
    }

    /** The fields recovered from a forward banner. */
    private record Banner(String name, String address, String subject, Instant sentAt) {}

    /**
     * Reads the header block underneath a forward banner.
     *
     * <p>Only the lines immediately following the banner are considered: order emails quote earlier
     * correspondence often enough that scanning the whole body would pick up the wrong sender.
     */
    private Banner parseBanner(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        Matcher banner = FORWARD_BANNER.matcher(body);
        if (!banner.find()) {
            return null;
        }

        String tail = body.substring(banner.end());
        String[] lines = tail.split("\n", 40);

        String name = null;
        String address = null;
        String subject = null;
        Instant sentAt = null;

        int examined = 0;
        for (String line : lines) {
            // The header block ends at the first blank line; beyond that is the message body.
            if (line.isBlank()) {
                if (examined > 0) {
                    break;
                }
                continue;
            }
            if (++examined > 12) {
                break;
            }
            Matcher m = BANNER_HEADER.matcher(line);
            if (!m.matches()) {
                continue;
            }
            String key = m.group(1).toLowerCase(Locale.ROOT);
            String value = m.group(2).strip();
            switch (key) {
                case "from", "van", "de", "von" -> {
                    address = addressIn(value);
                    name = nameIn(value);
                }
                case "subject", "onderwerp", "objet", "betreff" -> subject = value;
                case "date", "datum" -> sentAt = parseBannerDate(value);
                default -> {
                    // "To"/"Aan" identify the forwarder, which we already know.
                }
            }
        }
        return address == null ? null : new Banner(name, address, subject, sentAt);
    }

    private static String addressIn(String value) {
        Matcher angle = ANGLE_ADDRESS.matcher(value);
        if (angle.find()) {
            return angle.group(1).strip();
        }
        Matcher bare = BARE_ADDRESS.matcher(value);
        return bare.find() ? bare.group(1).strip() : null;
    }

    private static String nameIn(String value) {
        int lt = value.indexOf('<');
        if (lt <= 0) {
            return null;
        }
        String name = value.substring(0, lt).strip();
        if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) {
            name = name.substring(1, name.length() - 1);
        }
        return name.isBlank() ? null : name;
    }

    /**
     * Banner dates are written for people, not machines: Gmail localises them and inserts "at"
     * between date and time. Several shapes are tried and a failure is not fatal — the send date is
     * secondary here, because the timeline is ordered by expected delivery.
     */
    private static Instant parseBannerDate(String value) {
        String cleaned =
                value.replaceAll("(?i)\\s+(at|om|à|um)\\s+", " ")
                        .replaceAll("\\s+", " ")
                        .strip();
        List<DateTimeFormatter> formats =
                List.of(
                        DateTimeFormatter.RFC_1123_DATE_TIME,
                        DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
                        DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm", Locale.ENGLISH),
                        DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.ENGLISH),
                        DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm", Locale.forLanguageTag("nl")),
                        DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.forLanguageTag("nl")));
        for (DateTimeFormatter f : formats) {
            try {
                return ZonedDateTime.parse(cleaned, f).toInstant();
            } catch (Exception ignored) {
                // try the next shape
            }
        }
        try {
            return java.time.LocalDateTime.parse(
                            cleaned, DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.ENGLISH))
                    .toInstant(ZoneOffset.UTC);
        } catch (Exception ignored) {
            log.debug("Could not read a forward-banner date from {}", value);
            return null;
        }
    }

    private static boolean isSelf(String address, List<String> selfAddresses) {
        if (address == null || selfAddresses == null) {
            return false;
        }
        String a = address.toLowerCase(Locale.ROOT).strip();
        return selfAddresses.stream()
                .filter(s -> s != null && !s.isBlank())
                .anyMatch(s -> a.equals(s.toLowerCase(Locale.ROOT).strip()));
    }

    /**
     * A stable identity for a message that has none. Built from sender, subject and send time so
     * that forwarding the same email twice lands on one row; when even the date is unreadable it
     * falls back to a random id, which merely means a duplicate survives as two rows.
     */
    private static String syntheticId(String sender, String subject, Instant sentAt) {
        if (sender == null && subject == null && sentAt == null) {
            return "synthetic:" + UUID.randomUUID();
        }
        return "synthetic:"
                + Integer.toHexString(
                        (String.valueOf(sender) + '|' + subject + '|' + sentAt).hashCode());
    }

    private static String header(MimeMessage message, String name) {
        try {
            String[] values = message.getHeader(name);
            return values == null || values.length == 0 ? null : values[0].strip();
        } catch (Exception e) {
            return null;
        }
    }

    private static String decode(String value) {
        if (value == null) {
            return null;
        }
        try {
            return MimeUtility.decodeText(value).strip();
        } catch (Exception e) {
            return value.strip();
        }
    }

    /** Exposed for the ingest service's logging of what a message turned out to be. */
    public static String describe(Message message) {
        try {
            return message.getSubject();
        } catch (Exception e) {
            return "(unreadable subject)";
        }
    }
}
