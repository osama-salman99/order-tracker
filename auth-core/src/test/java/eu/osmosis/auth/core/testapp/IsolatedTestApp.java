package eu.osmosis.auth.core.testapp;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A test app in a package that does <b>not</b> contain the library, so nothing here is
 * component-scanned and auth-core arrives the only way it arrives in a real service: through
 * auto-configuration.
 *
 * <p>That distinction is not academic. {@code TestApp} sits in the library's own package, so its
 * scan picks {@link eu.osmosis.auth.core.ForwardedHeadersConfig} up as an ordinary configuration —
 * processed before every auto-configuration, which is the opposite of the ordering a real consumer
 * gets. Under that ordering the forwarded-header bean clash that stopped all seven crawlers from
 * starting simply does not happen, and a test built on it passes against the broken code.
 */
@SpringBootApplication
public class IsolatedTestApp {
}
