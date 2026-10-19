package work.osmosis.ordersapp.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** The shapes below are cut down from real answers PostNL gave in October 2026. */
class PostnlParcelTest {

    private final ObjectMapper json = new ObjectMapper();

    private PostnlParcel read(String parcel) {
        return PostnlParcel.read(json.readTree("{\"colli\":{\"3SABC123\":" + parcel + "}}"), "3SABC123");
    }

    @Test
    void readsAWholeDayAsADateWithoutATimeSlot() {
        PostnlParcel parcel = read("""
                {"eta":{"type":"WholeDay","start":"2026-10-17T00:00:00+02:00","end":"2026-10-17T23:59:59+02:00"},
                 "isDelivered":false,"statusPhase":{"index":2,"message":"Driver is en route to PostNL location"}}""");
        assertNotNull(parcel);
        assertEquals(LocalDate.of(2026, 10, 17), parcel.from());
        assertEquals(LocalDate.of(2026, 10, 17), parcel.to());
        assertNull(parcel.window());
        assertNull(parcel.deliveredAt());
        assertEquals("Driver is en route to PostNL location", parcel.note());
    }

    @Test
    void readsASpecificSlotAsATimeWindow() {
        PostnlParcel parcel = read("""
                {"eta":{"type":"Specific","start":"2026-09-15T11:25:00+02:00","end":"2026-09-15T13:25:00+02:00"},
                 "isDelivered":false}""");
        assertEquals("11:25–13:25", parcel.window());
    }

    @Test
    void readsDeliveryFromTheDeliveryDate() {
        PostnlParcel parcel = read("""
                {"eta":{"type":"WholeDay","start":"2026-09-18T00:00:00+02:00","end":"2026-09-18T23:59:59+02:00"},
                 "isDelivered":true,"deliveryDate":"2026-09-19T13:11:35+02:00",
                 "statusPhase":{"message":"Shipment collected at PostNL outlet"}}""");
        assertEquals(Instant.parse("2026-09-19T11:11:35Z"), parcel.deliveredAt());
        assertFalse(parcel.atPickupPoint());
    }

    @Test
    void notesAParcelWaitingAtAPickupPoint() {
        PostnlParcel parcel = read("""
                {"isDelivered":false,"isAtRetailLocation":true,"isPickedUpAtRetailLocation":false}""");
        assertTrue(parcel.atPickupPoint());
        assertNull(parcel.from());
    }

    @Test
    void anUnknownBarcodeReadsAsNothing() {
        assertNull(PostnlParcel.read(json.readTree("{\"colli\":{}}"), "3SABC123"));
    }

    @Test
    void anOrderIsOnlyDeliveredOnceEveryParcelIs() {
        PostnlParcel arrived = new PostnlParcel(null, null, null, "Delivered", Instant.parse("2026-10-16T10:00:00Z"), false);
        PostnlParcel coming = new PostnlParcel(
                LocalDate.of(2026, 10, 18), LocalDate.of(2026, 10, 18), "14:00–16:00", "Sorted", null, false);
        PostnlParcel both = PostnlParcel.combine(List.of(arrived, coming));
        assertNull(both.deliveredAt());
        assertEquals(LocalDate.of(2026, 10, 18), both.from());
        assertEquals("14:00–16:00", both.window());

        PostnlParcel later = new PostnlParcel(null, null, null, "Delivered", Instant.parse("2026-10-18T13:00:00Z"), false);
        assertEquals(Instant.parse("2026-10-18T13:00:00Z"), PostnlParcel.combine(List.of(arrived, later)).deliveredAt());
    }
}
