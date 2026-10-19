package eu.osmosis.auth.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SsoCookieTest {

    // Pinned cross-language interop vector. The same (key, user, exp) mints this exact string in
    // osmosis-auth (Java), server.py (Python) and vps-health (Go); if this breaks, single sign-on
    // breaks fleet-wide. Do not update the expected value to make a failure go away.
    private static final String KEY = "testkey123";
    private static final String USER = "osmosis";
    private static final long EXP = 9999999999L;
    private static final String REF =
            "b3Ntb3Npczo5OTk5OTk5OTk5OmEzYmQ1NTU0MWI0MTBkOWUzYWUzZWFhNjU3OTdmMGY2NGU2ZDE4MjhlNzcyOTkzZGU5ZWY0ZDkwOWM2MTI1ZTQ=";

    @Test
    void mintMatchesTheCrossLanguageVector() {
        assertEquals(REF, SsoCookie.mint(KEY, USER, EXP));
    }

    @Test
    void validation() {
        assertTrue(SsoCookie.valid(KEY, REF, 0));
        assertFalse(SsoCookie.valid("wrongkey", REF, 0), "wrong key");
        assertFalse(SsoCookie.valid(KEY, REF, EXP), "expired (now == exp)");
        assertFalse(SsoCookie.valid(KEY, "not-base64!!", 0), "garbage");
        assertFalse(SsoCookie.valid(KEY, "", 0), "empty");
        assertTrue(SsoCookie.valid(KEY, SsoCookie.mint(KEY, USER, EXP), 0), "round-trip");
    }
}
