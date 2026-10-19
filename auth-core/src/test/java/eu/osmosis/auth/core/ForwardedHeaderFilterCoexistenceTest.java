package eu.osmosis.auth.core;

import static org.assertj.core.api.Assertions.assertThat;

import eu.osmosis.auth.core.testapp.IsolatedTestApp;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * Every crawler sets {@code server.forward-headers-strategy: framework}, which makes Spring Boot
 * register its own forwarded-header filter. auth-core registers one too, and for a while both were
 * called {@code forwardedHeaderFilter} — so a service that set the property and pulled in auth-core
 * did not start at all: {@code BeanDefinitionOverrideException} during context refresh, before the
 * first request. The crawlers were all pinned to a crawler-core predating the filter, so this went
 * unnoticed until they were bumped.
 *
 * <p>Loading the context under that property is the whole test: if the two ever collide again, this
 * fails the same way production would, in CI. It runs on {@link IsolatedTestApp} rather than the
 * scanned {@code TestApp}, because only an unscanned app reproduces the auto-configuration ordering
 * that causes the clash — on the scanned one this test passes against the broken code.
 */
@SpringBootTest(
        classes = IsolatedTestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.forward-headers-strategy=framework")
class ForwardedHeaderFilterCoexistenceTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void theContextStartsWhenBootAlsoRegistersTheFilter() {
        // Reaching this line at all is the assertion — a collision fails the context refresh.
        assertThat(context).isNotNull();
    }

    @Test
    void exactlyOneForwardedHeaderFilterIsRegistered() {
        Map<String, FilterRegistrationBean> registrations =
                context.getBeansOfType(FilterRegistrationBean.class);

        long forwarding = registrations.values().stream()
                .filter(r -> r.getFilter() instanceof ForwardedHeaderFilter)
                .count();

        // Two would not throw — they would silently rewrite the request twice. One filter, whether
        // it came from Boot or from here.
        assertThat(forwarding).isEqualTo(1);
    }
}
