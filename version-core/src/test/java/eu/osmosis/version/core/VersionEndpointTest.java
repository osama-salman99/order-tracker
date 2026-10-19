package eu.osmosis.version.core;

import static org.assertj.core.api.Assertions.assertThat;

import eu.osmosis.version.core.testapp.TestApp;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.ResponseEntity;

/**
 * The endpoint must exist purely by virtue of version-core being on the classpath: {@link TestApp}
 * sits in a sibling package, so nothing here is component-scanned. A missing or misnamed
 * {@code AutoConfiguration.imports} file turns every consumer's {@code /version} into a 404, and
 * this is what catches that.
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(classes = TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class VersionEndpointTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void servesTheBakedInVersionWithoutBeingComponentScanned() {
        ResponseEntity<String> res = rest.getForEntity("/version", String.class);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).isEqualTo("{\"version\":\"v42-test\"}");
    }

    @Test
    void isNeverCached() {
        ResponseEntity<String> res = rest.getForEntity("/version", String.class);

        // A cached /version would agree with a cached page and hide the stale-build mismatch it
        // exists to reveal.
        assertThat(res.getHeaders().getCacheControl()).contains("no-store");
    }
}
