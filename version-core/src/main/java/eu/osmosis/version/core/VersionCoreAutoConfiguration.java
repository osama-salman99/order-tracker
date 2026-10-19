package eu.osmosis.version.core;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * Registers {@link VersionController} on any service that puts version-core on its classpath — no
 * component scan needed, since this library lives outside every consumer's package.
 *
 * <p>{@link ConditionalOnMissingBean} only defers to a bean of <i>this</i> type. A service that
 * still has its own copy of the controller in its own package is a different type, so both would
 * register and Spring fails at startup with an ambiguous mapping for {@code GET /version} — loudly,
 * in CI, not silently in production. Delete the local copy in the same commit that adds version-core.
 */
@AutoConfiguration
@ConditionalOnWebApplication
public class VersionCoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public VersionController osmosisVersionController() {
        return new VersionController();
    }
}
