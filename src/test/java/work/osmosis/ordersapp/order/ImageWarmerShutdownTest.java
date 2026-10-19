package work.osmosis.ordersapp.order;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import work.osmosis.ordersapp.ShutdownSignal;
import work.osmosis.ordersapp.domain.OrderRepository;

/** A warm pass that is under way when shutdown begins stops after the picture in hand. */
class ImageWarmerShutdownTest {

    @Test
    void stopsBetweenPicturesOnceShutdownBegins() {
        OrderRepository orders = mock(OrderRepository.class);
        ProductImages images = mock(ProductImages.class);
        ShutdownSignal shutdown = new ShutdownSignal();
        shutdown.start();
        when(orders.imageUrls()).thenReturn(List.of("https://a/1", "https://a/2", "https://a/3", "https://a/4"));
        when(images.cached(anyString())).thenReturn(Optional.empty());
        // The first download is where the deploy's shutdown arrives.
        when(images.fetch(anyString())).thenAnswer(call -> {
            shutdown.stop();
            return Optional.empty();
        });

        new ImageWarmer(orders, images, shutdown).fetchWhateverIsMissing();

        verify(images, times(1)).fetch(anyString());
    }
}
