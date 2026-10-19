package eu.osmosis.auth.core;

import static org.hamcrest.Matchers.containsString;
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
 * Every service sits behind Caddy, which terminates TLS and proxies onward over plain HTTP. The SSO
 * wall has to send the browser back to the scheme the browser used, not the one the proxy used —
 * otherwise a successful login forwards to {@code http://…} and the round-trip has to be upgraded
 * again. Guards {@link ForwardedHeadersConfig}; without it this asserts {@code next=http%3A%2F%2F}.
 */
@SpringBootTest(classes = TestApp.class)
@AutoConfigureMockMvc
class SsoRedirectSchemeTest {

    @Autowired private MockMvc mvc;

    @Test
    void unauthenticatedRedirectSendsTheBrowserBackOverHttps() throws Exception {
        mvc.perform(get("/library")
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Host", "media.osmosis.work"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(
                        "Location",
                        containsString("next=https%3A%2F%2Fmedia.osmosis.work%2Flibrary")));
    }

    @Test
    void theQueryStringSurvivesTheRoundTrip() throws Exception {
        mvc.perform(get("/library").queryParam("sort", "newest")
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Host", "media.osmosis.work"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(
                        "Location",
                        containsString("next=https%3A%2F%2Fmedia.osmosis.work%2Flibrary%3Fsort%3Dnewest")));
    }

    /**
     * The login form is served at the root of the auth host — {@code GET /login} is not mapped there
     * (it only accepts the form POST), so redirecting to it yields a 405 Whitelabel error page
     * instead of a login form. That is exactly the bug session.osmosis.work shipped, so pin the
     * shape of the URL the wall builds.
     */
    @Test
    void redirectsToTheLoginFormAtTheRootOfTheAuthHost() throws Exception {
        mvc.perform(get("/library").header("X-Forwarded-Proto", "https"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", startsWith("https://auth.osmosis.work/?next=")));
    }
}
