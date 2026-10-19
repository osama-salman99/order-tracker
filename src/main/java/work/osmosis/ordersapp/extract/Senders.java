package work.osmosis.ordersapp.extract;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out who sent an email, from its address alone.
 *
 * <p>Shops send from a scatter of subdomains — IKEA alone uses {@code ikea.com},
 * {@code order.email.ikea.com}, and {@code cm.order.email.ikea.com} — and two shops
 * occasionally share one bulk-mail provider's domain. Collapsing all of that to a single stable name
 * per shop matters more than it looks: the shop's name is half of an order's identity, because order
 * numbers are only unique within a shop. Coolblue's {@code 12345678} and some other shop's
 * {@code 12345678} must never be taken for the same order.
 *
 * <p>Carriers are recognised separately. Their emails carry a tracking number but almost never an
 * order number, and the shop they are delivering for is usually not named at all — so they are only
 * ever joined onto an order through the tracking number.
 */
public final class Senders {

    /** A recognised sender: a stable key, a name to show, and whether it delivers or sells. */
    public record Sender(String key, String name, boolean carrier) {}

    /**
     * Domain endings mapped to the shop they belong to. Matched as suffixes, so one entry covers
     * every subdomain a shop sends from. Longest match wins, which is how a shop that sends through
     * a shared provider can still be told apart.
     */
    private static final Map<String, String> SHOPS = Map.ofEntries(
            Map.entry("coolblue.nl", "Coolblue"),
            Map.entry("coolblue.eu", "Coolblue"),
            Map.entry("bol.com", "bol"),
            Map.entry("ikea.com", "IKEA"),
            Map.entry("amazon.nl", "Amazon"),
            Map.entry("amazon.de", "Amazon"),
            Map.entry("amazon.com", "Amazon"),
            Map.entry("uniqlo.eu", "Uniqlo"),
            Map.entry("uniqlo.com", "Uniqlo"),
            Map.entry("thuisbezorgd.nl", "Thuisbezorgd"),
            Map.entry("temu.com", "Temu"),
            Map.entry("aliexpress.com", "AliExpress"),
            Map.entry("decathlon.com", "Decathlon"),
            Map.entry("decathlon.nl", "Decathlon"),
            Map.entry("ebay.com", "eBay"),
            Map.entry("ebay.co.uk", "eBay"),
            Map.entry("gamma.nl", "Gamma"),
            Map.entry("zalando-lounge.nl", "Zalando Lounge"),
            Map.entry("lego.com", "LEGO"),
            Map.entry("ah.nl", "Albert Heijn"));

    /** Domain endings belonging to a delivery company rather than a shop. */
    private static final Map<String, String> CARRIERS = Map.ofEntries(
            Map.entry("postnl.nl", "PostNL"),
            Map.entry("dhl.com", "DHL"),
            Map.entry("dhlecommerce.nl", "DHL"),
            Map.entry("dhlparcel.nl", "DHL"),
            Map.entry("ups.com", "UPS"),
            Map.entry("dpd.nl", "DPD"),
            Map.entry("dpd.com", "DPD"),
            Map.entry("aramex.com", "Aramex"));

    /**
     * Senders whose mail is never about a parcel, however much it looks like it. Hosting invoices
     * and game platforms are the bulk of it — they have order numbers, totals and even "your order
     * has shipped" wording, and nothing ever arrives at the door.
     *
     * <p>{@code shopifyemail.com} used to be on this list and should never have been. It is not a
     * company: it is the address every Shopify-built shop sends its own order mail from, so listing
     * it threw away real orders in their entirety — a shop's "Order #1234 confirmed" and the
     * shipping notice that followed both vanished, and the order never existed. Those shops are
     * told apart by the store number in the address instead; see {@link #identify}.
     */
    private static final Set<String> NEVER_ORDERS = Set.of(
            "hetzner.com",
            "ovh.ca",
            "ovh.ie",
            "ovh.com",
            "ovhcloud.com",
            "steampowered.com",
            "riotgames.com",
            "trustpilotmail.com",
            "paddle.com",
            "worldpay.com",
            "lightspeedhq.com",
            "smile.io",
            // A game account's download carries a reference and a price and is not a thing that
            // gets delivered.
            "mojang.com");

    /**
     * Consumer mail providers. A shop never sends an order confirmation from one, so an email still
     * appearing to come from one after forwarding has been unwrapped is either the user's own
     * correspondence with a shop or a forward whose original could not be recovered.
     */
    private static final Set<String> PERSONAL_MAILBOXES = Set.of(
            "gmail.com", "googlemail.com", "outlook.com", "hotmail.com", "live.com",
            "yahoo.com", "icloud.com", "me.com", "protonmail.com", "proton.me");

    private Senders() {}

    /** True when the address belongs to a person's own mailbox rather than to a shop or carrier. */
    public static boolean isPersonalMailbox(String address) {
        String domain = domainOf(address);
        return !domain.isEmpty() && PERSONAL_MAILBOXES.contains(registeredDomain(domain));
    }

    /**
     * True when the address is a shop's help desk rather than the address its order mail comes from.
     *
     * <p>Deliberately narrow. Plenty of small shops send genuine order confirmations from a plain
     * {@code support@}, so only the unmistakable customer-service addresses count, and even then the
     * decision is only ever used for an email that quotes no order and no parcel.
     */
    public static boolean isCustomerService(String address) {
        String local = localPartOf(address);
        return local.contains("customer.support")
                || local.contains("customersupport")
                || local.contains("customer.service")
                || local.contains("customerservice")
                || local.contains("klantenservice")
                || local.contains("klantcontact");
    }

    /**
     * Shopify hosts thousands of shops behind one sending domain and writes the store's own number
     * into the address, as {@code store+12345678@t.shopifyemail.com}. That number is the only stable
     * thing distinguishing one shop from another here, so it becomes the identity — otherwise every
     * Shopify shop in the mailbox would share a key and their order numbers could collide.
     */
    private static final Pattern SHOPIFY_STORE =
            Pattern.compile("^store\\+(\\d{3,})$", Pattern.CASE_INSENSITIVE);

    /**
     * Shipping services that send a shop's track-and-trace mail from their own domain, with the shop
     * written only in the From name: {@code "Lampenwinkel.nl" <no-reply@myparcel.nl>}. Named by the domain,
     * every such order read as bought from "Myparcel".
     */
    private static final Set<String> SENDS_FOR_SHOPS = Set.of("myparcel.nl", "sendcloud.sc", "sendcloud.com");

    /** A From name that is itself a web address, like "Lampenwinkel.nl". */
    private static final Pattern DOMAIN_NAME =
            Pattern.compile("^[a-z0-9][a-z0-9-]*(?:\\.[a-z0-9-]+)*\\.[a-z]{2,}$", Pattern.CASE_INSENSITIVE);

    /**
     * Identifies the sender of an address. Falls back to the registered domain with its first letter
     * capitalised, so an unrecognised shop still gets a stable identity and a passable name rather
     * than being dropped — the whole point is that a shop nobody has taught the rules about still
     * produces a usable order.
     */
    public static Sender identify(String address) {
        return identify(address, null);
    }

    /**
     * @param displayName the name the sender wrote in the From header, used only where the address
     *     itself cannot name the shop — a Shopify store number is an identity but not a name a
     *     person would recognise
     */
    public static Sender identify(String address, String displayName) {
        String domain = domainOf(address);
        if (domain.isEmpty()) {
            return new Sender("unknown", "Unknown", false);
        }
        if (domain.equals("shopifyemail.com") || domain.endsWith(".shopifyemail.com")) {
            Matcher store = SHOPIFY_STORE.matcher(localPartOf(address));
            if (store.find()) {
                String shown = displayName == null ? "" : displayName.trim();
                return new Sender("shopify-" + store.group(1),
                        shown.isEmpty() ? "Shop " + store.group(1) : shown, false);
            }
        }
        String shown = displayName == null ? "" : displayName.trim();
        if (longestSuffixMatch(domain, keysAsMap(SENDS_FOR_SHOPS)) != null && !shown.isEmpty()) {
            // A name that is a web address is read as that address, so it lands on the same shop
            // as the shop's own mail; any other name is kept as written.
            if (DOMAIN_NAME.matcher(shown).matches()) {
                return identify("shop@" + shown.toLowerCase(Locale.ROOT), null);
            }
            String slug = shown.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
            if (!slug.isEmpty()) {
                return new Sender(registeredDomain(domain) + "-" + slug, shown, false);
            }
        }
        String carrier = longestSuffixMatch(domain, CARRIERS);
        if (carrier != null) {
            return new Sender(carrier.toLowerCase(Locale.ROOT).replace(" ", "-"), carrier, true);
        }
        String shop = longestSuffixMatch(domain, SHOPS);
        if (shop != null) {
            return new Sender(shop.toLowerCase(Locale.ROOT).replace(" ", "-").replace(".", ""), shop, false);
        }
        String registered = registeredDomain(domain);
        String label = registered.contains(".") ? registered.substring(0, registered.indexOf('.')) : registered;
        String name = label.isEmpty() ? registered : Character.toUpperCase(label.charAt(0)) + label.substring(1);
        return new Sender(registered, name, false);
    }

    /**
     * The shop a delivery company names in its subject line, if it names one.
     *
     * <p>Carriers deliver for shops they otherwise say nothing about, so an order made only of
     * carrier mail — which happens whenever the shop's own confirmation was never forwarded —
     * would show up as "PostNL" rather than as the thing that was bought. The subject is the one
     * place the shop is reliably written: "Afgeleverd: je pakket van DECATHLON.NL", "Jouw
     * Coolblue-pakket is onderweg".
     *
     * <p>This is for display only and never becomes part of an order's identity. A shop's name is
     * far too weak a thing to merge two orders on.
     */
    public static String shopNamedInCarrierSubject(String subject) {
        if (subject == null) {
            return null;
        }
        for (Pattern pattern : CARRIER_SUBJECT_SHOP) {
            Matcher m = pattern.matcher(subject);
            if (m.find()) {
                String named = tidyShopName(m.group(1));
                if (!named.isEmpty()) {
                    return named;
                }
            }
        }
        return null;
    }

    private static final List<Pattern> CARRIER_SUBJECT_SHOP = List.of(
            Pattern.compile("pakket van ([\\p{L}0-9&.'\\- ]{2,30}?)\\s*(?:$|[,.!:])", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b([\\p{L}0-9&.']{2,30})-pakket\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:parcel|package|shipment|delivery) from ([\\p{L}0-9&.'\\- ]{2,30}?)\\s*(?:$|[,.!:])",
                    Pattern.CASE_INSENSITIVE));

    /**
     * Turns what the subject wrote into the name the shop is otherwise known by, so "DECATHLON.NL"
     * and "decathlon" both read as "Decathlon" and land on the same shop as its own emails do.
     */
    private static String tidyShopName(String raw) {
        String cleaned = raw.trim().replaceAll("(?i)\\.(nl|com|de|eu|be)$", "").trim();
        if (cleaned.isEmpty()) {
            return "";
        }
        for (String known : SHOPS.values()) {
            if (known.equalsIgnoreCase(cleaned)) {
                return known;
            }
        }
        return Character.toUpperCase(cleaned.charAt(0)) + cleaned.substring(1).toLowerCase(Locale.ROOT);
    }

    /** True when this sender's mail should never be treated as an order, whatever it says. */
    public static boolean neverOrders(String address) {
        String domain = domainOf(address);
        return !domain.isEmpty() && longestSuffixMatch(domain, keysAsMap(NEVER_ORDERS)) != null;
    }

    /** The bare domain of an address, lower-cased, or an empty string if there isn't one. */
    static String domainOf(String address) {
        if (address == null) {
            return "";
        }
        int at = address.lastIndexOf('@');
        String domain = at >= 0 ? address.substring(at + 1) : address;
        domain = domain.trim().toLowerCase(Locale.ROOT);
        if (domain.endsWith(">")) {
            domain = domain.substring(0, domain.length() - 1);
        }
        return domain.replaceAll("[^a-z0-9.-]", "");
    }

    /** The part of an address before the @, lower-cased, or an empty string if there isn't one. */
    static String localPartOf(String address) {
        if (address == null) {
            return "";
        }
        int at = address.lastIndexOf('@');
        String local = at > 0 ? address.substring(0, at) : "";
        return local.trim().toLowerCase(Locale.ROOT).replaceAll("^[<\"']+", "");
    }

    /**
     * The domain a person would name the site by: normally the last two labels, but three for the
     * handful of country registries that sell under a second level, such as {@code .co.uk}.
     */
    static String registeredDomain(String domain) {
        String[] parts = domain.split("\\.");
        if (parts.length <= 2) {
            return domain;
        }
        String secondLast = parts[parts.length - 2];
        boolean twoPartSuffix = Set.of("co", "com", "org", "net", "ac", "gov").contains(secondLast)
                && parts[parts.length - 1].length() == 2;
        int take = twoPartSuffix ? 3 : 2;
        return String.join(".", java.util.Arrays.copyOfRange(parts, parts.length - take, parts.length));
    }

    /**
     * Matches the longest entry that the domain ends with, on a label boundary — so
     * {@code notikea.com} never matches {@code ikea.com}.
     */
    private static String longestSuffixMatch(String domain, Map<String, String> table) {
        String bestKey = null;
        for (String candidate : table.keySet()) {
            if (domain.equals(candidate) || domain.endsWith("." + candidate)) {
                if (bestKey == null || candidate.length() > bestKey.length()) {
                    bestKey = candidate;
                }
            }
        }
        return bestKey == null ? null : table.get(bestKey);
    }

    private static Map<String, String> keysAsMap(Set<String> keys) {
        return keys.stream().collect(java.util.stream.Collectors.toMap(k -> k, k -> k));
    }
}
