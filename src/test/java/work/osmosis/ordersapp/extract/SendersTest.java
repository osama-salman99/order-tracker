package work.osmosis.ordersapp.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SendersTest {

    @Test
    void aShippingServiceSendingForAShopIsNamedAfterTheShop() {
        Senders.Sender sender = Senders.identify("no-reply@myparcel.nl", "Lampenwinkel.nl");
        assertEquals("Lampenwinkel", sender.name());
        // The same identity the shop's own mail would get.
        assertEquals(Senders.identify("info@lampenwinkel.nl").key(), sender.key());
    }

    @Test
    void aShopNameThatIsNotAnAddressIsKeptAsWritten() {
        assertEquals("Bakker Jansen", Senders.identify("no-reply@myparcel.nl", "Bakker Jansen").name());
    }
}
