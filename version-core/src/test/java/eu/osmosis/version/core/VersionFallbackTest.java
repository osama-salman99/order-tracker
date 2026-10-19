package eu.osmosis.version.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * A local build has no {@code osmosis-version} resource — Gradle only writes it from
 * {@code -PosmosisVersion} in CI. Reporting {@code dev} rather than throwing is what lets a
 * developer run any service on their own machine.
 */
class VersionFallbackTest {

    @Test
    void reportsDevWhenTheResourceIsMissing() {
        assertThat(VersionController.readVersion("no-such-resource")).isEqualTo("dev");
    }

    @Test
    void trimsTheResourceSoATrailingNewlineNeverReachesTheClient() {
        // processResources writes the value with a trailing newline; "v42\n" in a JSON body would
        // fail every string comparison the frontend badge makes.
        assertThat(VersionController.readVersion(VersionController.RESOURCE)).isEqualTo("v42-test");
    }
}
