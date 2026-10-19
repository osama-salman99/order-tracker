package eu.osmosis.auth.core;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Putting auth-core on the classpath is the whole integration: this auto-configuration installs the
 * shared SSO wall, so a service gets the fleet login with no code and no config.
 *
 * <p>Consumed two ways — directly by services that only need the login (media-app, home-app,
 * session-controller's Java successors…), and transitively by crawler-core, which depends on
 * auth-core so every crawler keeps the same wall it always had.
 */
@AutoConfiguration
@Import({ForwardedHeadersConfig.class, SsoSecurityConfig.class})
public class AuthCoreAutoConfiguration {
}
