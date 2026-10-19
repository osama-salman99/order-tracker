package work.osmosis.ordersapp.order;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import work.osmosis.ordersapp.ShutdownSignal;
import work.osmosis.ordersapp.domain.Order;
import work.osmosis.ordersapp.domain.OrderRepository;
import work.osmosis.ordersapp.domain.ReceivedEmail;
import work.osmosis.ordersapp.domain.ReceivedEmailRepository;
import work.osmosis.ordersapp.extract.Stage;
import work.osmosis.ordersapp.extract.TrackingNumber;

/**
 * Asks PostNL itself when each open order's parcels are coming.
 *
 * <p>The dates on the page used to come only from emails, and PostNL's emails never carry one: its
 * "a parcel is on its way" mail just links to the tracking page, which is where the day — and on
 * the day itself, a two-hour slot — actually lives. So every half hour, each order that is still
 * open and has a PostNL barcode is looked up there, and what comes back is stored on the order for
 * the rebuild to prefer over whatever the emails said.
 *
 * <p>An order is asked about until PostNL itself says the parcel was delivered or collected — not
 * merely until an email says so, because PostNL's "Afgeleverd" mail is also what it sends when a
 * parcel is left at a pickup point, and that one still has to be picked up. And new mail on an
 * order, which is usually what brings the barcode in the first place, is looked up within a minute
 * instead of waiting for the next half-hourly round.
 *
 * <p>Nothing happens without a delivery postcode: PostNL will not describe a parcel from its
 * barcode alone.
 */
@Component
public class CarrierTracking {

    private static final Logger log = LoggerFactory.getLogger(CarrierTracking.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    /**
     * How long after its last email an order is still worth asking about. PostNL forgets parcels
     * after a few weeks anyway, and an order silent for this long is for the reader to settle.
     */
    private static final Duration RECENT = Duration.ofDays(60);

    private final OrderRepository orders;
    private final ReceivedEmailRepository emails;
    private final OrderAssembler assembler;
    private final ObjectMapper json;
    private final String postcode;
    private final ShutdownSignal shutdown;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    /**
     * When each order was last tried, answered or not. The minute-by-minute check for new mail goes
     * by this, so a barcode PostNL does not recognise is not asked about again every minute.
     */
    private final Map<Long, Instant> lastTried = new ConcurrentHashMap<>();

    public CarrierTracking(
            OrderRepository orders,
            ReceivedEmailRepository emails,
            OrderAssembler assembler,
            ObjectMapper json,
            @Value("${orders.delivery-postcode:}") String postcode,
            ShutdownSignal shutdown) {
        this.shutdown = shutdown;
        this.orders = orders;
        this.emails = emails;
        this.assembler = assembler;
        this.json = json;
        this.postcode = postcode == null ? "" : postcode.replaceAll("\\s+", "").toUpperCase();
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT30M")
    public void lookUpOpenOrders() {
        lookUpWhere(order -> true);
    }

    /** The orders that have had mail since they were last looked up. */
    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "PT1M")
    public void lookUpNewMail() {
        lookUpWhere(order -> {
            Instant tried = lastTried.get(order.getId());
            return tried == null || order.getLastEmailAt().isAfter(tried);
        });
    }

    private void lookUpWhere(Predicate<Order> wanted) {
        if (postcode.isEmpty()) {
            return;
        }
        Instant since = Instant.now().minus(RECENT);
        int updated = 0;
        for (Order order : orders.findAll()) {
            // Each order can mean several PostNL lookups of up to 20 s; stop between orders on shutdown.
            if (shutdown.requested() || Thread.currentThread().isInterrupted()) {
                break;
            }
            if (!stillToCome(order) || order.getLastEmailAt() == null || order.getLastEmailAt().isBefore(since)
                    || !wanted.test(order)) {
                continue;
            }
            lastTried.put(order.getId(), Instant.now());
            List<String> barcodes = postnlBarcodes(order.getId());
            if (barcodes.isEmpty()) {
                continue;
            }
            List<PostnlParcel> parcels = new ArrayList<>();
            for (String barcode : barcodes) {
                PostnlParcel parcel = lookUp(barcode);
                if (parcel != null) {
                    parcels.add(parcel);
                }
            }
            // Nothing PostNL recognised: what an earlier lookup said is better than nothing.
            if (parcels.isEmpty()) {
                continue;
            }
            PostnlParcel all = PostnlParcel.combine(parcels);
            order.setCarrierTracking(
                    all.from(), all.to(), all.window(), all.note(), all.deliveredAt(), all.atPickupPoint(), Instant.now());
            orders.save(order);
            assembler.rebuild(order.getId());
            updated++;
        }
        if (updated > 0) {
            log.info("Looked up {} order(s) at PostNL", updated);
        }
    }

    /**
     * Whether PostNL may still have something to say. An email saying "delivered" does not settle it
     * — only PostNL's own word, or the reader's — so a parcel left at a pickup point keeps being
     * looked up until it is collected.
     */
    private static boolean stillToCome(Order order) {
        return order.getDismissedAt() == null
                && order.getReplacedByOrderId() == null
                && order.getMarkedDeliveredAt() == null
                && order.getCarrierDeliveredAt() == null
                && order.getStage() != Stage.RETURNED
                && order.getStage() != Stage.CANCELLED;
    }

    private List<String> postnlBarcodes(Long orderId) {
        Set<String> found = new LinkedHashSet<>();
        for (ReceivedEmail email : emails.findByOrderIdOrderBySentAtAscIdAsc(orderId)) {
            for (TrackingNumber tracking : OrderAssembler.parseTracking(email.getTrackingNumbers())) {
                if ("PostNL".equals(tracking.carrier()) && !tracking.number().isEmpty()) {
                    found.add(tracking.number());
                }
            }
        }
        return new ArrayList<>(found);
    }

    /** One parcel at PostNL, or null when the lookup failed or PostNL does not know it. */
    private PostnlParcel lookUp(String barcode) {
        URI address = URI.create("https://jouw.postnl.nl/track-and-trace/api/trackAndTrace/"
                + barcode + "-NL-" + postcode + "?language=en");
        HttpRequest request = HttpRequest.newBuilder(address)
                .timeout(TIMEOUT)
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("PostNL answered {} for a parcel", response.statusCode());
                return null;
            }
            return PostnlParcel.read(json.readTree(response.body()), barcode);
        } catch (IOException | RuntimeException e) {
            log.warn("PostNL lookup failed: {}", e.toString());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
