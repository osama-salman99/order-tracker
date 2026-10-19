package eu.osmosis.auth.core;

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
 * Someone running these services under their own domain sets one property and gets the login at
 * auth.&lt;their domain&gt; and a logout that clears their cookie — nothing still points at osmosis.work.
 */
@SpringBootTest(classes = TestApp.class, properties = "osmosis.auth.domain=example.org")
@AutoConfigureMockMvc
class CustomDomainTest {

    @Autowired private MockMvc mvc;

    @Test
    void theWallSendsTheBrowserToTheLoginOnThatDomain() throws Exception {
        mvc.perform(get("/config").header("X-Forwarded-Proto", "https"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", startsWith("https://auth.example.org/?next=")));
    }

    @Test
    void logoutClearsTheCookieOnThatDomain() throws Exception {
        mvc.perform(post("/logout").with(csrf()))
                .andExpect(header().string("Location", "https://auth.example.org/"))
                .andExpect(cookie().maxAge("osmosis_sso", 0))
                .andExpect(cookie().domain("osmosis_sso", ".example.org"));
    }
}
