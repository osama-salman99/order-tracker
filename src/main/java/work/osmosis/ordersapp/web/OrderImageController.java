package work.osmosis.ordersapp.web;

import java.nio.file.Files;
import java.time.Duration;
import java.util.Optional;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import work.osmosis.ordersapp.domain.Order;
import work.osmosis.ordersapp.domain.OrderRepository;
import work.osmosis.ordersapp.order.ProductImages;

/**
 * Hands the page the picture for one order.
 *
 * <p>The picture is always served from here rather than from the shop, so the page makes no requests
 * to retailers and cannot be watched by them; {@code ProductImages} explains that choice and does the
 * fetching. The first request for a given picture pays for the download, every one after it is read
 * off the disk, and an order whose picture cannot be had answers "no picture" — not an error, because
 * having none is an ordinary outcome rather than a fault.
 */
@RestController
public class OrderImageController {

    private final OrderRepository orders;
    private final ProductImages images;

    public OrderImageController(OrderRepository orders, ProductImages images) {
        this.orders = orders;
        this.images = images;
    }

    @GetMapping("/api/orders/{id}/image")
    public ResponseEntity<Resource> image(@PathVariable Long id) {
        Optional<Order> found = orders.findById(id);
        if (found.isEmpty() || found.get().getImageUrl() == null) {
            return ResponseEntity.notFound().build();
        }
        Optional<ProductImages.Stored> stored = images.fetch(found.get().getImageUrl());
        if (stored.isEmpty() || !Files.isReadable(stored.get().file())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                // A product photograph never changes once taken, and the address it is keyed on
                // changes if the picture does, so the browser can keep it as long as it likes.
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePrivate())
                .contentType(MediaType.parseMediaType(stored.get().contentType()))
                .body(new FileSystemResource(stored.get().file()));
    }
}
