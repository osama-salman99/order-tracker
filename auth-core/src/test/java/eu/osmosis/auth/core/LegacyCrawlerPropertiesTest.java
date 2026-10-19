package eu.osmosis.auth.core;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The seven crawlers opt paths out of the wall with {@code crawler.security.public-paths}, written
 * before auth-core existed. Extracting the wall must not silently re-wall those paths — a crawler's
 * machine endpoint (roofz's {@code /portal/ingest-email}) would start 302-ing to a login page.
 * So the old spelling stays supported, and this is what proves it.
 */
@SpringBootTest(
        classes = TestApp.class,
        properties = "crawler.security.public-paths=/portal/ingest-email")
@AutoConfigureMockMvc
class LegacyCrawlerPropertiesTest {

    @Autowired private MockMvc mvc;

    @Test
    void theOldCrawlerSpellingStillOptsAPathOutOfTheWall() throws Exception {
        // Nothing maps this path, so escaping the wall means a 404 rather than a redirect to login.
        mvc.perform(get("/portal/ingest-email")).andExpect(status().isNotFound());
    }

    @Test
    void everythingElseIsStillBehindTheWall() throws Exception {
        mvc.perform(get("/config")).andExpect(status().is3xxRedirection());
    }
}
