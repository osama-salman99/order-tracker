package eu.osmosis.auth.core;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Minimal host application for the wall tests — auth-core is a library, so the tests need something
 * for Spring Boot to boot. It contributes no controllers on purpose: an unmapped path still runs the
 * whole security chain, which is what these tests assert on.
 */
@SpringBootApplication
public class TestApp {}
