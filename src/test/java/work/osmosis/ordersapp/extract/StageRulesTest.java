package work.osmosis.ordersapp.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class StageRulesTest {

    @Test
    void aParcelLeftAtAPickupPointIsNotDelivered() {
        // PostNL's subject says "Afgeleverd" either way; only the body says it is still to collect.
        String body = "Hoi, je pakket van Coolblue ligt klaar bij het PostNL-punt "
                + "We bewaren je pakket t/m donderdag 22 oktober bij het PostNL-punt.";
        assertEquals(Stage.READY_FOR_PICKUP, StageRules.read("Afgeleverd: je pakket van Coolblue", body));
    }

    @Test
    void aParcelDeliveredHomeIsStillDelivered() {
        String body = "Hoi, je pakket van Coolblue is bezorgd. Bekijk de online versie.";
        assertEquals(Stage.DELIVERED, StageRules.read("Afgeleverd: je pakket van Coolblue", body));
    }
}
