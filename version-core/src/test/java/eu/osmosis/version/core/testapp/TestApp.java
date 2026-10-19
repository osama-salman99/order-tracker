package eu.osmosis.version.core.testapp;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Deliberately in a package that is <b>not</b> a parent of {@code eu.osmosis.version.core}, so
 * component scanning cannot reach the library's controller. If {@code /version} answers in these
 * tests it is because the auto-configuration registered it — which is the only way it ever gets
 * registered in a real consumer.
 */
@SpringBootApplication
public class TestApp {
}
