package work.osmosis.ordersapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import work.osmosis.ordersapp.order.DatabaseGuard;

/**
 * orders.osmosis.work — a delivery timeline built out of forwarded order email.
 *
 * <p>Gmail forwards anything that looks like an order to {@code orders@email.osmosis.work}. A
 * Cloudflare Email Worker posts the raw message to {@code /ingest-email}; from there the mail is
 * archived verbatim, parsed, and — when it really is an order — turned into a row on the timeline.
 *
 * <p>Two things shape the design. First, <b>the raw message is kept forever</b>: extraction is a
 * pure function of the archived bytes, so when a rule improves, every email ever received can be
 * re-read with it. Nothing is lost by parsing badly today. Second, <b>the filter is not trusted</b>
 * — plenty of what arrives will not be an order at all, so classification is part of the pipeline
 * rather than an assumption about the inbox.
 *
 * <p>Every page is behind the shared SSO wall from auth-core (default-deny). The single
 * exception is the ingest endpoint, which is machine-to-machine and carries its own shared secret.
 */
@SpringBootApplication
// The rules re-read the archive on a timer; see ExtractionRunner for why that is the design.
@EnableScheduling
public class OrdersAppApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(OrdersAppApplication.class);
        // Has to run before the connection pool exists, so it is a listener rather than a bean.
        application.addListeners(new DatabaseGuard());
        application.run(args);
    }
}
