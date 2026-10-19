package work.osmosis.ordersapp.extract;

import java.util.Set;

/**
 * Everything the rules could read out of one email.
 *
 * <p>Deliberately a plain description of that single email rather than of an order: several emails
 * usually describe one purchase, and reconciling them is a separate job. Any field may be missing,
 * because most emails say only one or two of these things.
 *
 * @param order whether this is about a parcel at all
 * @param ignoredBecause why it isn't, in words fit to show on a page — null when it is
 * @param shopKey stable identifier for the shop, used together with an order number as an identity
 * @param shopName the shop's name as a person would write it
 * @param fromCarrier true when a delivery company sent this rather than a shop, which means the
 *     shop's own name and order number are almost certainly absent
 * @param orderNumbers the shop's references for the order, unique only within that shop
 * @param unlabelledCodes codes in the body that nothing introduced, so they are not order numbers
 *     and are never shown as one — only used to recognise an order that already carries one of them
 *     as its labelled reference
 * @param tracking parcel numbers, which are unique everywhere and join a carrier's mail to a shop's
 * @param total what it cost, formatted for display
 * @param itemSummary what was bought, when the email lists it — null far more often than not
 * @param imageUrl where the shop's picture of the product lives, when the email showed one
 * @param delivery when it is expected
 * @param stage how far along it is
 */
public record EmailFacts(
        boolean order,
        String ignoredBecause,
        String shopKey,
        String shopName,
        boolean fromCarrier,
        Set<String> orderNumbers,
        Set<String> unlabelledCodes,
        Set<TrackingNumber> tracking,
        String total,
        String itemSummary,
        String imageUrl,
        DeliveryEstimate delivery,
        Stage stage) {

    /** True when this email says nothing that could tie it to any other email about the order. */
    public boolean hasNoIdentity() {
        return orderNumbers.isEmpty() && tracking.isEmpty();
    }
}
