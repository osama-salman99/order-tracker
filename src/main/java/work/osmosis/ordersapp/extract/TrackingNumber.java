package work.osmosis.ordersapp.extract;

/**
 * A parcel's tracking number together with the carrier it belongs to.
 *
 * <p>The number alone is the useful part — it is what lets a carrier's email be recognised as being
 * about the same parcel as the shop's — but carrying the carrier with it means the detail page can
 * offer a link to the right tracking site.
 */
public record TrackingNumber(String carrier, String number) implements Comparable<TrackingNumber> {

    public TrackingNumber {
        number = number == null ? "" : number.trim().toUpperCase();
        carrier = carrier == null ? "" : carrier;
    }

    /** Where a person can go to look this parcel up, or null when the carrier has no known page. */
    public String trackingUrl() {
        return trackingUrl(null);
    }

    /**
     * The same, for someone whose delivery postcode is known.
     *
     * <p>PostNL will not show a parcel from its barcode alone — its page answers "the opened link
     * does not contain a (valid) postal code" and makes you type one in. The postcode goes in the
     * address itself, as {@code <barcode>-<country>-<postcode>}. Every other carrier here is happy
     * with the number on its own, so the postcode is ignored for them.
     *
     * @param postcode the delivery postcode, spaces and case not important; null or blank is fine
     *                 and simply gives the plain link back
     */
    public String trackingUrl(String postcode) {
        String tidy = postcode == null ? "" : postcode.replaceAll("\\s+", "").toUpperCase();
        return switch (carrier) {
            case "PostNL" -> tidy.isEmpty()
                    ? "https://jouw.postnl.nl/track-and-trace/" + number
                    : "https://jouw.postnl.nl/track-and-trace/" + number + "-NL-" + tidy;
            case "DHL" -> "https://www.dhl.com/nl-nl/home/tracking.html?tracking-id=" + number;
            case "UPS" -> "https://www.ups.com/track?tracknum=" + number;
            case "DPD" -> "https://tracking.dpd.de/status/nl_NL/parcel/" + number;
            case "Aramex" -> "https://www.aramex.com/track/results?ShipmentNumber=" + number;
            default -> null;
        };
    }

    @Override
    public int compareTo(TrackingNumber other) {
        return number.compareTo(other.number);
    }
}
