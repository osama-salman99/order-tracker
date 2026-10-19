package work.osmosis.ordersapp.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DateRulesTest {

    private static final LocalDate SENT = LocalDate.of(2026, 10, 14);

    @Test
    void readsADateOnTheLineAfterItsLabel() {
        // IKEA's confirmation prints the label on one line and the date on the next.
        String text = "Truck delivery\nDelivery date:\nMonday 19 October 2026 ,\n08:00 - 18:00\n";
        DeliveryEstimate found = DateRules.find(text, SENT);
        assertNotNull(found);
        assertEquals(LocalDate.of(2026, 10, 19), found.from());
        assertEquals(LocalDate.of(2026, 10, 19), found.to());
    }

    @Test
    void stillReadsADateOnTheSameLine() {
        DeliveryEstimate found = DateRules.find("Scheduled Delivery Date: Monday, 13/07/2026", LocalDate.of(2026, 7, 10));
        assertNotNull(found);
        assertEquals(LocalDate.of(2026, 7, 13), found.from());
    }

    @Test
    void readsCoolbluesDeliveryDayUnderItsHeading() {
        // No colon after the heading; the date is the whole of the next line.
        DeliveryEstimate found = DateRules.find(
                "Your delivery day\ntomorrow, Saturday, July 18, 2026\n", LocalDate.of(2026, 7, 17));
        assertNotNull(found);
        assertEquals(LocalDate.of(2026, 7, 18), found.from());
    }

    @Test
    void readsAPickUpDate() {
        DeliveryEstimate found = DateRules.find(
                "Pick up your order from\ntomorrow, Saturday, October 17, 2026\n", LocalDate.of(2026, 10, 16));
        assertNotNull(found);
        assertEquals(LocalDate.of(2026, 10, 17), found.from());
        DeliveryEstimate decathlon = DateRules.find("Pakket 1\nBeschikbaar vanaf 25-7-2026\n", LocalDate.of(2026, 7, 24));
        assertNotNull(decathlon);
        assertEquals(LocalDate.of(2026, 7, 25), decathlon.from());
    }

    @Test
    void readsAWeekdayWrittenBeforeTheVisit() {
        // Sent on a Sunday: "Dinsdag" is the coming Tuesday.
        DeliveryEstimate found = DateRules.find(
                "Dinsdag komen we bij je langs (JVGL01234567890123456789)\nWe komen dinsdag bij je langs",
                LocalDate.of(2025, 5, 18));
        assertNotNull(found);
        assertEquals(LocalDate.of(2025, 5, 20), found.from());
    }
}
