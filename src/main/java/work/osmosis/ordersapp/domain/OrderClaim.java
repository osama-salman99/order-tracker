package work.osmosis.ordersapp.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * One thing an email said that identifies which order it belongs to.
 *
 * <p>This is how emails about the same purchase find each other. Two emails belong together if they
 * make any claim in common, and claims come in two useful kinds:
 *
 * <ul>
 *   <li>{@code order:<shop>:<number>} — the shop's own reference. It runs from the confirmation
 *       through to delivery, which makes it the backbone, but it is scoped to the shop because a
 *       number like {@code 7761} means nothing on its own.
 *   <li>{@code track:<number>} — a parcel number. This is the only claim a carrier's email can make,
 *       and therefore the only way "DHL is coming Wednesday" ever reaches the Coolblue order it
 *       belongs to. It is globally unique, so it needs no shop.
 * </ul>
 *
 * <p>Claims merge transitively. A confirmation and a shipping notice share an order number; the
 * shipping notice and the carrier's update share a tracking number; all three end up on one order,
 * even though the first and last have nothing in common at all. An order shipped in two parcels
 * simply gains two tracking claims, which is why merging beats picking a single key.
 *
 * <p>An email that identifies nothing gets a {@code solo:<id>} claim so it still becomes an order of
 * its own instead of being lost.
 */
@Entity
@Table(name = "order_claim", uniqueConstraints = @UniqueConstraint(name = "uk_claim", columnNames = "claim"))
public class OrderClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 300)
    private String claim;

    @Column(nullable = false)
    private Long orderId;

    protected OrderClaim() {}

    public OrderClaim(String claim, Long orderId) {
        this.claim = claim;
        this.orderId = orderId;
    }

    public Long getId() {
        return id;
    }

    public String getClaim() {
        return claim;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }
}
