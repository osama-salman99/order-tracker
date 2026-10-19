package work.osmosis.ordersapp.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * A code an email stated without labelling it — a hint about which order it belongs to, not a
 * statement of identity.
 *
 * <p>These deliberately live apart from {@link OrderClaim}. A claim is globally unique: one claim,
 * one order, enforced by the database, which is exactly what makes a claim a claim. A code has none
 * of that authority. The same string turns up in every email a shop sends — one takeaway shop
 * prints its company reference in the footer of all of them — so any number of emails may carry it,
 * and none of them is thereby the same order.
 *
 * <p>The only question these ever answer is the narrow one: <em>has an email quietly mentioned the
 * number this other email states plainly?</em> A code is never matched against another code, so a
 * footer constant can sit in a hundred emails without ever joining two of them.
 */
@Entity
@Table(
        name = "email_code",
        indexes = {@Index(name = "idx_email_code_lookup", columnList = "shop_key,code"),
                   @Index(name = "idx_email_code_email", columnList = "email_id")})
public class EmailCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String shopKey;
    private String code;
    private Long emailId;

    protected EmailCode() {}

    public EmailCode(String shopKey, String code, Long emailId) {
        this.shopKey = shopKey;
        this.code = code;
        this.emailId = emailId;
    }

    public Long getId() {
        return id;
    }

    public String getShopKey() {
        return shopKey;
    }

    public String getCode() {
        return code;
    }

    public Long getEmailId() {
        return emailId;
    }
}
