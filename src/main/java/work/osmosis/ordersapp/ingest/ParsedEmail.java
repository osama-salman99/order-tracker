package work.osmosis.ordersapp.ingest;

import java.time.Instant;

/**
 * An email reduced to the things the rules care about, with the forwarding wrapper already removed
 * so {@code sender} is the retailer and not the person who forwarded it.
 *
 * @param messageId original {@code Message-ID}, or a synthesised one when the message carries none
 * @param subject original subject
 * @param senderName display name of the retailer, where the message gave one
 * @param senderAddress envelope address of the retailer — the single most reliable signal we get
 * @param sentAt when the retailer sent it
 * @param text readable body: the plain-text part, or the HTML flattened with its structure kept
 * @param unwrapped true when a forwarding wrapper was peeled off to reach these values
 */
public record ParsedEmail(
        String messageId,
        String subject,
        String senderName,
        String senderAddress,
        Instant sentAt,
        String text,
        boolean unwrapped) {}
