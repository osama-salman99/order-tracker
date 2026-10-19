package eu.osmosis.auth.core;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Logging out clears the shared cookie, and a clear only works when it names the Domain the login
 * set. With nothing configured that is still the fleet's own .osmosis.work, byte for byte.
 */
@SpringBootTest(classes = TestApp.class)
@AutoConfigureMockMvc
class LogoutCookieTest {

    @Autowired private MockMvc mvc;

    @Test
    void logoutClearsTheCookieOnTheFleetDomain() throws Exception {
        mvc.perform(post("/logout").with(csrf()))
                .andExpect(redirectedUrl("https://auth.osmosis.work/"))
                .andExpect(cookie().value("osmosis_sso", ""))
                .andExpect(cookie().maxAge("osmosis_sso", 0))
                .andExpect(cookie().domain("osmosis_sso", ".osmosis.work"))
                .andExpect(cookie().path("osmosis_sso", "/"))
                .andExpect(cookie().httpOnly("osmosis_sso", true))
                .andExpect(cookie().sameSite("osmosis_sso", "Lax"));
    }
}
