package work.osmosis.ordersapp.extract;

import java.time.LocalDate;

/**
 * When an order is expected, as one email described it.
 *
 * <p>Shops promise delivery in two ways and both have to survive into the timeline: a single day
 * ("bezorgd op maandag 7 september") and a window of a few days ("02/07/2026 - 03/07/2026"). The
 * timeline sorts on {@link #from()}, the earliest it could arrive, so an order with a window sits at
 * the front of it rather than the back.
 */
public record DeliveryEstimate(LocalDate from, LocalDate to) {

    public DeliveryEstimate {
        if (to == null || to.isBefore(from)) {
            to = from;
        }
    }

    public static DeliveryEstimate on(LocalDate day) {
        return new DeliveryEstimate(day, day);
    }

    /** True when the promise was a window of days rather than one day. */
    public boolean isRange() {
        return !from.equals(to);
    }
}
