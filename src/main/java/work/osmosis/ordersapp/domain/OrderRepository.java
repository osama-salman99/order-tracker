package work.osmosis.ordersapp.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * The timeline, oldest first. The page is rendered in this order and scrolled to the bottom, so
     * the nearest arrival sits at the end and earlier orders are reached by scrolling up.
     *
     * <p>The one thing being sorted here is when the order is expected to arrive. An order nobody
     * has given a date for has no place on that timeline at all, so rather than guess it a position
     * they all go below the end of it: {@code deliveryDateAssumed} sorts false before true, which
     * puts every real estimate first and gathers the dateless ones underneath. They were previously
     * slotted in by the date of their last email, which is when the shop wrote — not when anything
     * arrives — and made the whole list read as though it were ordered by when things were bought.
     */
    @Query("select o from Order o order by o.deliveryDateAssumed asc, o.sortDate asc, o.id asc")
    List<Order> timeline();

    /**
     * The orders this one was sent out to make up for.
     *
     * <p>A list rather than a single order, because one parcel can stand in for more than one: two
     * things lost out of the same delivery can perfectly well come back as a single replacement, and
     * refusing to record that would only mean the second one stayed on the list for ever.
     */
    List<Order> findByReplacedByOrderId(Long replacedByOrderId);

    /** Every order that is standing in for an earlier one, for building both directions at once. */
    @Query("select o from Order o where o.replacedByOrderId is not null")
    List<Order> replacedOnes();

    /**
     * Every picture the orders refer to, newest order first.
     *
     * <p>Only the addresses, because the caller is deciding what still needs downloading and has no
     * use for the rest of the order. Newest first so that if a run is cut short, what was fetched is
     * what the reader is most likely to be looking at.
     */
    @Query("select o.imageUrl from Order o where o.imageUrl is not null order by o.id desc")
    List<String> imageUrls();
}
