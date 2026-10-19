package work.osmosis.ordersapp.order;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * What PostNL's own tracking says about one parcel, reduced to the parts the order page uses.
 *
 * <p>PostNL's emails never carry the delivery day — the "we're bringing your parcel soon" mail only
 * links to its tracking page — but that page reads from a plain JSON address that answers to the
 * barcode and the delivery postcode. This is the reading of that answer.
 *
 * @param from the first day it is expected, or null when PostNL has not said
 * @param to the last day it is expected; the same as {@code from} for a single day
 * @param window the promised time slot, as "11:25–13:25", or null while it is still the whole day
 * @param note PostNL's own one-line description of where the parcel is
 * @param deliveredAt when it was delivered or collected, or null while it is still on its way
 * @param atPickupPoint whether it is waiting at a pickup point to be collected
 */
public record PostnlParcel(
        LocalDate from, LocalDate to, String window, String note, Instant deliveredAt, boolean atPickupPoint) {

    private static final ZoneId AMSTERDAM = ZoneId.of("Europe/Amsterdam");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * Reads one parcel out of PostNL's answer, or null when the answer does not describe it — which
     * is what PostNL sends for a barcode it does not know or a postcode that does not match.
     */
    public static PostnlParcel read(JsonNode answer, String barcode) {
        JsonNode parcel = answer.path("colli").path(barcode);
        if (parcel.isMissingNode() || !parcel.isObject()) {
            return null;
        }
        OffsetDateTime start = time(parcel.path("eta").path("start"));
        OffsetDateTime end = time(parcel.path("eta").path("end"));
        LocalDate from = start == null ? null : start.atZoneSameInstant(AMSTERDAM).toLocalDate();
        LocalDate to = end == null ? from : end.atZoneSameInstant(AMSTERDAM).toLocalDate();
        // "WholeDay" runs from midnight to 23:59:59 and is not a time slot; only a "Specific" one is.
        String window = start != null && end != null && "Specific".equals(text(parcel.path("eta").path("type")))
                ? start.atZoneSameInstant(AMSTERDAM).format(CLOCK) + "–" + end.atZoneSameInstant(AMSTERDAM).format(CLOCK)
                : null;
        Instant deliveredAt = null;
        if (parcel.path("isDelivered").asBoolean(false)) {
            OffsetDateTime when = time(parcel.path("deliveryDate"));
            if (when == null) {
                when = time(parcel.path("lastObservation"));
            }
            deliveredAt = when == null ? Instant.now() : when.toInstant();
        }
        boolean atPickupPoint = deliveredAt == null
                && parcel.path("isAtRetailLocation").asBoolean(false)
                && !parcel.path("isPickedUpAtRetailLocation").asBoolean(false);
        String note = text(parcel.path("statusPhase").path("message"));
        return new PostnlParcel(from, to, window, note.isBlank() ? null : note, deliveredAt, atPickupPoint);
    }

    /**
     * Several parcels of one order, as one. The order is only delivered once every parcel is, and
     * until then it is the parcels still to come that decide when it is expected.
     */
    public static PostnlParcel combine(List<PostnlParcel> parcels) {
        if (parcels.size() == 1) {
            return parcels.get(0);
        }
        List<PostnlParcel> waiting = parcels.stream().filter(p -> p.deliveredAt() == null).toList();
        if (waiting.isEmpty()) {
            Instant last = parcels.stream().map(PostnlParcel::deliveredAt).max(Instant::compareTo).orElseThrow();
            return new PostnlParcel(null, null, null, parcels.get(parcels.size() - 1).note(), last, false);
        }
        LocalDate from = waiting.stream().map(PostnlParcel::from).filter(d -> d != null).min(LocalDate::compareTo).orElse(null);
        LocalDate to = waiting.stream().map(PostnlParcel::to).filter(d -> d != null).max(LocalDate::compareTo).orElse(null);
        // A time slot only means something when there is just one parcel left to wait in for.
        String window = waiting.size() == 1 ? waiting.get(0).window() : null;
        boolean allWaitingAtPickup = waiting.stream().allMatch(PostnlParcel::atPickupPoint);
        return new PostnlParcel(from, to, window, waiting.get(0).note(), null, allWaitingAtPickup);
    }

    private static OffsetDateTime time(JsonNode node) {
        String value = text(node);
        if (value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** A field's text, or "" when it is absent or null — never the word "null". */
    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? "" : node.asText();
    }
}
