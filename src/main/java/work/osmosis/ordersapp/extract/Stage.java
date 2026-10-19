package work.osmosis.ordersapp.extract;

/**
 * How far along an order is, as far as the last email about it said.
 *
 * <p>Emails about one purchase are read in the order they arrived, so the last thing said is the
 * answer. {@link #rank()} exists for the one thing that still needs comparing: an update that
 * arrives late must not drag an order backwards, because a carrier's "on its way" regularly
 * overtakes the shop's "we've packed it".
 */
public enum Stage {
    /** Nothing said either way — the email was about the order but not about its progress. */
    UNKNOWN(0, "Ordered"),
    ORDERED(1, "Ordered"),
    /** Paid for and being picked, but not yet handed to a carrier. */
    PROCESSING(2, "Being prepared"),
    SHIPPED(3, "On its way"),
    OUT_FOR_DELIVERY(4, "Out for delivery"),
    /**
     * Waiting in a shop or a locker for someone to come and get it. Not finished: the thing is not
     * with the reader until they have been, which is exactly the case worth a row on the page.
     */
    READY_FOR_PICKUP(5, "Ready for pickup"),
    DELIVERED(6, "Delivered"),
    /** Terminal and unusual, so these outrank delivery: they are the last word on the order. */
    RETURNED(7, "Returned"),
    CANCELLED(8, "Cancelled");

    private final int rank;
    private final String label;

    Stage(int rank, String label) {
        this.rank = rank;
        this.label = label;
    }

    public int rank() {
        return rank;
    }

    /** What to show a person. */
    public String label() {
        return label;
    }

    /** True once the order has stopped moving, whether it arrived, went back, or never happened. */
    public boolean finished() {
        return this == DELIVERED || this == RETURNED || this == CANCELLED;
    }
}
