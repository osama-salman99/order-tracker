package work.osmosis.ordersapp.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import work.osmosis.ordersapp.extract.Stage;

/**
 * One purchase, as assembled from every email that turned out to be about it.
 *
 * <p>Nothing here is authoritative on its own — every field is a conclusion drawn from the emails,
 * and can be drawn again. Rebuilding an order from its emails is always safe, and that is what
 * happens whenever the rules improve.
 *
 * <p>With one exception, and it is deliberate: {@link #markedDeliveredAt} is the only thing on an
 * order that no email can say. It is the reader's own word that the parcel arrived, which matters
 * because a great many shops never send the email that would say so. Rebuilding must therefore
 * leave it alone.
 */
@Entity
@Table(name = "customer_order", indexes = {@Index(name = "idx_order_sort", columnList = "sortDate")})
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Stable identifier for the shop; half of an order number's meaning. */
    @Column(length = 120)
    private String shopKey;

    @Column(length = 200)
    private String shopName;

    /** The shop's own reference, when one was ever stated. Shown to the user as the order's name. */
    @Column(length = 60)
    private String orderNumber;

    @Column(length = 40)
    private String total;

    /** The earliest day it is expected, and the latest when the shop promised a window instead. */
    private LocalDate expectedFrom;

    private LocalDate expectedTo;

    /**
     * What the timeline is ordered by. Normally the expected delivery day; for an order that never
     * named one, the day of its most recent email, so it still lands somewhere sensible rather than
     * falling off the page.
     */
    private LocalDate sortDate;

    // Plain text on purpose, not the database's own list-of-values type: see StoredEnumsAsText for
    // why letting the database hold the list of stages breaks the day a stage is added.
    @Enumerated(EnumType.STRING)
    @Column(length = 30, columnDefinition = "varchar(30)")
    private Stage stage = Stage.UNKNOWN;

    /**
     * True when the delivery day was inferred from the email's own date rather than stated.
     *
     * <p>Spelled out with a default for the same reason as {@code ReceivedEmail.fromCarrier}: a
     * column that can never be empty cannot be added to a table that already has rows unless it says
     * what those rows should get.
     */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean deliveryDateAssumed;

    private Instant firstEmailAt;

    private Instant lastEmailAt;

    @Column(nullable = false, columnDefinition = "integer default 0")
    private int emailCount;

    /**
     * When the reader said this had arrived, if they ever did.
     *
     * <p>Nullable on purpose, and not a plain {@code boolean}: "never marked" and "marked at some
     * point" are genuinely different, and the moment it happened is worth keeping. Being nullable
     * also means the column can be added to a table that already has rows without a default, which
     * a primitive could not — see {@code ReceivedEmail.fromCarrier} for what that costs.
     */
    private Instant markedDeliveredAt;

    /**
     * When the reader said this had <em>not</em> arrived, despite a shop saying it had.
     *
     * <p>Shops are wrong about this more often than the confident wording of their mail suggests: a
     * parcel is scanned as delivered at the depot, left with a neighbour who is out, or handed to
     * the wrong door. Until this existed the page treated "delivered" from a shop as beyond argument
     * and offered no control at all on such an order, which left the one case where the reader most
     * needs to disagree as the one case they could not.
     *
     * <p>It is not permanent, and deliberately so. A note here stands only until the next email
     * about the order arrives — see {@link #isDelivered()} — because a later email is newer evidence
     * than the note, and an order that really does turn up the following day should close itself
     * rather than sit on the list waiting to be ticked off a second time.
     */
    private Instant markedNotDeliveredAt;

    /**
     * When the reader said this is not a real order: mail that looks like an order and is about
     * nothing they are waiting for — a carrier announcing a parcel nobody sent, for instance. The
     * emails are real and are kept; the order is just set aside, out of the list.
     * Nullable for the same reason as {@link #markedDeliveredAt}.
     */
    private Instant dismissedAt;

    /**
     * The order that was sent out to make up for this one, when the reader has said there was one.
     *
     * <p>Two order numbers really are two orders — the shop billed them separately, wrote about them
     * separately, and will ship them separately — so they are never welded into one. Merging them
     * would also not survive: an order is rebuilt from its emails every time the rules improve, and
     * the emails would pull them apart again. What is true instead is narrower and worth saying
     * exactly: <em>this</em> one is over, and <em>that</em> one is why.
     *
     * <p>The shop cannot be relied on to say which order a replacement is for. bol's own replacement
     * confirmation names only the new number, so nothing in the mail joins the two; it is the reader
     * who knows, because they are the one who telephoned. So this is theirs to set, kept beside the
     * two delivery marks and left alone by rebuilding for the same reason.
     *
     * <p>Nullable, and a {@code Long} rather than anything that cannot be empty, so that the column
     * can be added to a table that already has rows — see {@link #markedDeliveredAt}.
     */
    private Long replacedByOrderId;

    /**
     * What the carrier's own tracking said about the parcel when it was last looked up — see
     * {@code CarrierTracking}. Like the reader's marks, none of this comes from an email, so
     * rebuilding leaves it alone; the rebuild reads it instead, as the newest word on the parcel.
     *
     * <p>All nullable, so the columns can be added to a table that already has rows.
     */
    private LocalDate carrierFrom;

    private LocalDate carrierTo;

    /** The promised time slot, as "11:25–13:25", when the carrier has narrowed the day down to one. */
    @Column(length = 20)
    private String carrierWindow;

    /** The carrier's own one-line description of where the parcel is. */
    @Column(length = 200)
    private String carrierNote;

    private Instant carrierDeliveredAt;

    private Boolean carrierAtPickupPoint;

    private Instant carrierCheckedAt;

    /** The subject of the email that best describes the order — usually the confirmation. */
    @Column(length = 500)
    private String title;

    /**
     * The shop's picture of what was bought, taken from whichever of the order's emails showed one.
     * Null for most orders: plenty of shops send text-only confirmations, and a delivery company
     * never knows what is inside the parcel.
     */
    @Column(length = 1000)
    private String imageUrl;

    public Long getId() {
        return id;
    }

    public String getShopKey() {
        return shopKey;
    }

    public void setShopKey(String shopKey) {
        this.shopKey = shopKey;
    }

    public String getShopName() {
        return shopName;
    }

    public void setShopName(String shopName) {
        this.shopName = shopName;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public void setOrderNumber(String orderNumber) {
        this.orderNumber = orderNumber;
    }

    public String getTotal() {
        return total;
    }

    public void setTotal(String total) {
        this.total = total;
    }

    public LocalDate getExpectedFrom() {
        return expectedFrom;
    }

    public void setExpectedFrom(LocalDate expectedFrom) {
        this.expectedFrom = expectedFrom;
    }

    public LocalDate getExpectedTo() {
        return expectedTo;
    }

    public void setExpectedTo(LocalDate expectedTo) {
        this.expectedTo = expectedTo;
    }

    public LocalDate getSortDate() {
        return sortDate;
    }

    public void setSortDate(LocalDate sortDate) {
        this.sortDate = sortDate;
    }

    public Stage getStage() {
        return stage;
    }

    public void setStage(Stage stage) {
        this.stage = stage;
    }

    public boolean isDeliveryDateAssumed() {
        return deliveryDateAssumed;
    }

    public void setDeliveryDateAssumed(boolean deliveryDateAssumed) {
        this.deliveryDateAssumed = deliveryDateAssumed;
    }

    public Instant getFirstEmailAt() {
        return firstEmailAt;
    }

    public void setFirstEmailAt(Instant firstEmailAt) {
        this.firstEmailAt = firstEmailAt;
    }

    public Instant getLastEmailAt() {
        return lastEmailAt;
    }

    public void setLastEmailAt(Instant lastEmailAt) {
        this.lastEmailAt = lastEmailAt;
    }

    public int getEmailCount() {
        return emailCount;
    }

    public void setEmailCount(int emailCount) {
        this.emailCount = emailCount;
    }

    public Instant getMarkedDeliveredAt() {
        return markedDeliveredAt;
    }

    public void setMarkedDeliveredAt(Instant markedDeliveredAt) {
        this.markedDeliveredAt = markedDeliveredAt;
    }

    public Instant getMarkedNotDeliveredAt() {
        return markedNotDeliveredAt;
    }

    public void setMarkedNotDeliveredAt(Instant markedNotDeliveredAt) {
        this.markedNotDeliveredAt = markedNotDeliveredAt;
    }

    /**
     * True once this has arrived: either a shop said so, or the reader did — unless the reader has
     * since said it did not, and nothing has been heard about it since.
     */
    public boolean isDelivered() {
        return !readerSaysNotHere() && (markedDeliveredAt != null || (stage != null && stage.finished()));
    }

    /**
     * Whether the reader's word that this never turned up is still the last word on it.
     *
     * <p>An email that arrived after they said so replaces it. That is the whole of the rule: the
     * note is a statement about what was known at the time, and a shop writing again — to say it has
     * been redelivered, or that it is on its way after all — is a newer account of the same parcel.
     */
    public boolean readerSaysNotHere() {
        return markedNotDeliveredAt != null
                && (lastEmailAt == null || !lastEmailAt.isAfter(markedNotDeliveredAt));
    }

    public Long getReplacedByOrderId() {
        return replacedByOrderId;
    }

    public void setReplacedByOrderId(Long replacedByOrderId) {
        this.replacedByOrderId = replacedByOrderId;
    }

    /**
     * True when this order is over, whichever way it ended.
     *
     * <p>Wider than {@link #isDelivered()} by two cases. An order the reader has said was re-ordered
     * never arrived and never will, but it is finished with all the same: the replacement is the one
     * still to wait for. And one they have said is not a real order was never anything to wait for.
     */
    public boolean isSettled() {
        return dismissedAt != null || replacedByOrderId != null || isDelivered();
    }

    public Instant getDismissedAt() {
        return dismissedAt;
    }

    public void setDismissedAt(Instant dismissedAt) {
        this.dismissedAt = dismissedAt;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public LocalDate getCarrierFrom() {
        return carrierFrom;
    }

    public LocalDate getCarrierTo() {
        return carrierTo;
    }

    public String getCarrierWindow() {
        return carrierWindow;
    }

    public String getCarrierNote() {
        return carrierNote;
    }

    public Instant getCarrierDeliveredAt() {
        return carrierDeliveredAt;
    }

    public boolean isCarrierAtPickupPoint() {
        return Boolean.TRUE.equals(carrierAtPickupPoint);
    }

    public Instant getCarrierCheckedAt() {
        return carrierCheckedAt;
    }

    /** Records one lookup at the carrier, replacing whatever the previous one said. */
    public void setCarrierTracking(
            LocalDate from, LocalDate to, String window, String note, Instant deliveredAt, boolean atPickupPoint,
            Instant checkedAt) {
        this.carrierFrom = from;
        this.carrierTo = to;
        this.carrierWindow = window;
        this.carrierNote = note;
        this.carrierDeliveredAt = deliveredAt;
        this.carrierAtPickupPoint = atPickupPoint;
        this.carrierCheckedAt = checkedAt;
    }
}
