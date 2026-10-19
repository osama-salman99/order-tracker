package work.osmosis.ordersapp.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import work.osmosis.ordersapp.ShutdownSignal;
import work.osmosis.ordersapp.domain.OrderRepository;

/**
 * Downloads the pictures before anyone asks for them.
 *
 * <p>Without this the first person to open the page would wait on a few dozen downloads happening at
 * once, and the run that improves the rules — which finds new pictures across the whole history —
 * would push all of that cost onto whoever looked next. So the fetching is done quietly in the
 * background instead: one at a time, so a burst of requests never leaves the VPS, and repeatedly,
 * because a replay keeps discovering pictures long after startup.
 *
 * <p>Nothing here decides anything. It only moves work earlier in time; a picture that was never
 * warmed is still fetched on demand, and one that cannot be fetched at all is skipped by
 * {@link ProductImages} without complaint. That is what makes it safe for this to share the one
 * scheduler thread with the pass that re-reads the archive: the two take turns, and a warm pass being
 * cut short costs nothing at all.
 */
@Component
public class ImageWarmer {

    private static final Logger log = LoggerFactory.getLogger(ImageWarmer.class);

    /** Enough to keep up with a replay; small enough that one pass is never a long stretch of work. */
    private static final int PER_PASS = 15;

    private final OrderRepository orders;
    private final ProductImages images;
    private final ShutdownSignal shutdown;

    public ImageWarmer(OrderRepository orders, ProductImages images, ShutdownSignal shutdown) {
        this.orders = orders;
        this.images = images;
        this.shutdown = shutdown;
    }

    @Scheduled(initialDelayString = "PT45S", fixedDelayString = "PT5M")
    public void fetchWhateverIsMissing() {
        int tried = 0;
        int fetched = 0;
        // The whole list is read out of the database in one go, on purpose: everything after this
        // line is network and disk, so the pass can be abandoned at any point without a database
        // call being the thing that gets interrupted.
        for (String url : orders.imageUrls()) {
            // The scheduler waits for a running job instead of interrupting it, so shutdown is
            // noticed through the signal; up to 15 downloads of 10 s each would otherwise hold it up.
            if (tried >= PER_PASS || shutdown.requested() || Thread.currentThread().isInterrupted()) {
                break;
            }
            if (images.cached(url).isPresent()) {
                continue;
            }
            // Counted whether or not it worked. Counting only the successes would mean a shop that
            // refuses this machine — and IKEA does — got retried from the top of the list on every
            // pass, and a run made mostly of refusals would never reach the pictures it could get.
            tried++;
            if (images.fetch(url).isPresent()) {
                fetched++;
            }
        }
        if (fetched > 0) {
            log.info("Fetched {} product picture(s)", fetched);
        }
    }
}
