package work.osmosis.ordersapp.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import work.osmosis.ordersapp.extract.Stage;

/**
 * One email as it arrived, after the forwarding wrapper has been peeled off but before anyone has
 * decided what it means.
 *
 * <p>This is the durable record. {@link #archivePath} points at the untouched bytes on disk, and
 * {@link #extractorVersion} records which generation of the rules last looked at this row — bump
 * the rules, and every row below the current version can be replayed from its archive.
 */
@Entity
@Table(
        name = "received_email",
        indexes = {
            @Index(name = "idx_received_message_id", columnList = "messageId"),
            @Index(name = "idx_received_status", columnList = "status"),
            @Index(name = "idx_received_at", columnList = "receivedAt")
        })
public class ReceivedEmail {

    /** What the pipeline currently believes an email is. */
    public enum Status {
        /** Archived and parsed, not yet classified. */
        NEW,
        /** Classified as a real order email and projected onto the timeline. */
        ORDER,
        /** Deliberately not an order — a newsletter, a receipt for nothing, a misfiled notice. */
        IGNORED,
        /** Parsing or extraction threw; the archive is intact and can be replayed. */
        ERROR
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The original message's {@code Message-ID}. The dedup key for a literally re-sent email: if
     * the same one is forwarded twice, the second arrival updates this row rather than making a
     * second one. Orders that span several different emails are de-duplicated separately, on the
     * order number, because those are genuinely different messages about one purchase.
     */
    @Column(length = 400)
    private String messageId;

    @Column(length = 1000)
    private String subject;

    @Column(length = 400)
    private String senderName;

    @Column(length = 400)
    private String senderAddress;

    /** When the retailer sent it — the date on the original message, not on the forward. */
    private Instant sentAt;

    /** When this service took delivery of it. */
    private Instant receivedAt;

    /** Path of the verbatim {@code .eml}, relative to the data directory. */
    @Column(length = 500)
    private String archivePath;

    /** Readable text of the message: the plain-text part, or the HTML flattened into text. */
    @Lob
    @Column(length = 1_000_000)
    private String bodyText;

    // Plain text, not the database's own list-of-values type — see StoredEnumsAsText.
    @Enumerated(EnumType.STRING)
    @Column(length = 20, columnDefinition = "varchar(20)")
    private Status status = Status.NEW;

    private static final int REASON_LIMIT = 500;

    /** Why this was set aside, in words, so a wrong call can be understood rather than guessed at. */
    @Column(length = REASON_LIMIT)
    private String statusReason;

    /** Generation of the extraction rules that last read this email. */
    @Column(nullable = false, columnDefinition = "integer default 0")
    private int extractorVersion;

    /**
     * The order this email turned out to be about, once the rules had read it.
     *
     * <p>Several emails point at one order — a confirmation, a shipping notice, the carrier's
     * update. Which order that is can change when the rules improve, so this is a conclusion and
     * never an input: rebuilding it from scratch is always allowed.
     */
    private Long orderId;

    // Below: what the rules made of this one email, kept so an order can be rebuilt from its emails
    // without opening the archive again. Every one of them may be empty; most emails say only a
    // couple of these things.

    @Column(length = 120)
    private String shopKey;

    @Column(length = 200)
    private String shopName;

    /**
     * True when a delivery company sent this rather than a shop.
     *
     * <p>The default is spelled out in the column itself, and that is not decoration. A plain Java
     * {@code boolean} can never be empty, so the schema is generated as "not null" with nothing to
     * fall back on — which is fine on an empty table and fatal on a full one. Adding the column to a
     * table that already has rows means giving every one of them a value, and without a default
     * there is none, so the change is rejected and the column simply never appears. The application
     * then starts perfectly and fails on every query afterwards. Any column added here from now on
     * needs the same treatment.
     */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean fromCarrier;

    /** Order numbers found, separated by spaces. Rarely more than one. */
    @Column(length = 500)
    private String orderNumbers;

    /** Parcel numbers found, written {@code carrier:number} and separated by spaces. */
    @Column(length = 500)
    private String trackingNumbers;

    @Column(length = 40)
    private String total;

    /**
     * What this email says was bought, when it says so at all. Most do not: only the confirmation
     * prints the item list, and every later update just refers to "your order".
     */
    @Column(length = 200)
    private String itemSummary;

    /**
     * Where the shop's picture of the product lives. Only the address is kept: the picture itself is
     * fetched once and cached on disk, so this stays a short string and the archive stays the only
     * thing that has to be preserved. Generous length because retailer image URLs carry a long tail
     * of resizing and signing parameters.
     */
    @Column(length = 1000)
    private String imageUrl;

    private LocalDate expectedFrom;

    private LocalDate expectedTo;

    // Plain text, not the database's own list-of-values type — see StoredEnumsAsText.
    @Enumerated(EnumType.STRING)
    @Column(length = 30, columnDefinition = "varchar(30)")
    private Stage stage = Stage.UNKNOWN;

    protected ReceivedEmail() {
        // for JPA
    }

    public ReceivedEmail(
            String messageId,
            String subject,
            String senderName,
            String senderAddress,
            Instant sentAt,
            Instant receivedAt,
            String archivePath,
            String bodyText) {
        this.messageId = messageId;
        this.subject = subject;
        this.senderName = senderName;
        this.senderAddress = senderAddress;
        this.sentAt = sentAt;
        this.receivedAt = receivedAt;
        this.archivePath = archivePath;
        this.bodyText = bodyText;
    }

    public Long getId() {
        return id;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getSubject() {
        return subject;
    }

    public String getSenderName() {
        return senderName;
    }

    public String getSenderAddress() {
        return senderAddress;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public String getArchivePath() {
        return archivePath;
    }

    public String getBodyText() {
        return bodyText;
    }

    public Status getStatus() {
        return status;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public int getExtractorVersion() {
        return extractorVersion;
    }

    /**
     * The reason is cut to fit rather than trusted to. It is written when something has already gone
     * wrong, and the text at hand is then whatever a failure happened to say — a database complaint
     * quoting a whole statement runs to well over a thousand characters. Storing that as-is fails a
     * second time, on the length, and the second failure is the damaging one: it escapes the handler
     * that was recording the first, so the pass over the archive dies rather than skipping one
     * unreadable email.
     */
    public void setStatus(Status status, String reason) {
        this.status = status;
        this.statusReason = reason == null || reason.length() <= REASON_LIMIT
                ? reason
                : reason.substring(0, REASON_LIMIT - 1) + "…";
    }

    public void setExtractorVersion(int extractorVersion) {
        this.extractorVersion = extractorVersion;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public String getShopKey() {
        return shopKey;
    }

    public String getShopName() {
        return shopName;
    }

    public boolean isFromCarrier() {
        return fromCarrier;
    }

    public String getOrderNumbers() {
        return orderNumbers;
    }

    public String getTrackingNumbers() {
        return trackingNumbers;
    }

    public String getTotal() {
        return total;
    }

    public LocalDate getExpectedFrom() {
        return expectedFrom;
    }

    public LocalDate getExpectedTo() {
        return expectedTo;
    }

    public String getItemSummary() {
        return itemSummary;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public Stage getStage() {
        return stage;
    }

    /** Records what one run of the rules made of this email, replacing whatever a previous run said. */
    public void setExtracted(
            String shopKey,
            String shopName,
            boolean fromCarrier,
            String orderNumbers,
            String trackingNumbers,
            String total,
            String itemSummary,
            String imageUrl,
            LocalDate expectedFrom,
            LocalDate expectedTo,
            Stage stage) {
        this.shopKey = shopKey;
        this.shopName = shopName;
        this.fromCarrier = fromCarrier;
        this.orderNumbers = orderNumbers;
        this.trackingNumbers = trackingNumbers;
        this.total = total;
        this.itemSummary = itemSummary;
        this.imageUrl = imageUrl;
        this.expectedFrom = expectedFrom;
        this.expectedTo = expectedTo;
        this.stage = stage == null ? Stage.UNKNOWN : stage;
    }

    /** A later copy of the same message replaces what we hold: the most recent send wins. */
    public void refreshFrom(
            String subject,
            String senderName,
            String senderAddress,
            Instant sentAt,
            Instant receivedAt,
            String archivePath,
            String bodyText) {
        this.subject = subject;
        this.senderName = senderName;
        this.senderAddress = senderAddress;
        this.sentAt = sentAt;
        this.receivedAt = receivedAt;
        this.archivePath = archivePath;
        this.bodyText = bodyText;
        this.status = Status.NEW;
        this.statusReason = null;
        this.extractorVersion = 0;
        // The conclusions drawn from the previous copy no longer apply to these bytes.
        setExtracted(null, null, false, null, null, null, null, null, null, null, Stage.UNKNOWN);
        this.orderId = null;
    }
}
