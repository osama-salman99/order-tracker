package eu.osmosis.auth.core;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code ?next=} is appended straight onto the configured auth URL, so a value without a trailing
 * slash would build {@code https://auth.osmosis.work?next=…}. Normalising it in one place is
 * cheaper than every consumer remembering the slash.
 */
@SpringBootTest(classes = TestApp.class, properties = "osmosis.auth.url=https://auth.osmosis.work")
@AutoConfigureMockMvc
class AuthUrlNormalisationTest {

    @Autowired private MockMvc mvc;

    @Test
    void aConfiguredUrlWithoutATrailingSlashStillHitsTheLoginForm() throws Exception {
        mvc.perform(get("/config").header("X-Forwarded-Proto", "https"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", startsWith("https://auth.osmosis.work/?next=")));
    }
}
