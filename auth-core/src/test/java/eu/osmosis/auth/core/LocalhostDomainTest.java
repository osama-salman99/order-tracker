package eu.osmosis.auth.core;

import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Everything on one machine: the login on localhost:8210, a crawler on another port. A browser
 * refuses a cookie with Domain=localhost, so the wall must clear a host-only cookie there instead.
 */
@SpringBootTest(classes = TestApp.class, properties = {
        "osmosis.auth.domain=localhost", "osmosis.auth.url=http://localhost:8210"})
@AutoConfigureMockMvc
class LocalhostDomainTest {

    @Autowired private MockMvc mvc;

    @Test
    void anExplicitLoginUrlWinsOverTheDomain() throws Exception {
        mvc.perform(get("/config"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", startsWith("http://localhost:8210/?next=")));
    }

    @Test
    void logoutClearsAHostOnlyCookie() throws Exception {
        mvc.perform(post("/logout").with(csrf()))
                .andExpect(cookie().maxAge("osmosis_sso", 0))
                .andExpect(cookie().domain("osmosis_sso", nullValue()));
    }
}
