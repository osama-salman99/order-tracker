package eu.osmosis.auth.core;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingFilterBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * Makes the app see the scheme/host the browser actually used, not the one Caddy used to reach it.
 *
 * <p>Every service in the fleet is bound to 127.0.0.1 and fronted by Caddy, which terminates TLS
 * and proxies onward over plain HTTP. Without this filter {@code request.getRequestURL()} reports
 * {@code http://…}, so any absolute URL the app builds points at the wrong scheme. That bit the SSO
 * wall in {@link SsoSecurityConfig}: an unauthenticated request was redirected to the central login
 * with {@code ?next=http://…}, sending the browser back over plain HTTP after a successful login.
 *
 * <p>Trusting {@code X-Forwarded-*} unconditionally is safe here precisely because nothing but Caddy
 * can reach the port, and Caddy replaces {@code X-Forwarded-Proto} rather than appending to it, so a
 * client cannot forge one.
 */
@Configuration
public class ForwardedHeadersConfig {

    /**
     * Runs ahead of the security chain — the entry point that builds the {@code ?next} URL has to
     * see the rewritten request, not the raw one.
     *
     * <p>Spring Boot registers this exact filter itself, at this exact order, when a service sets
     * {@code server.forward-headers-strategy: framework} — and it names the bean
     * {@code forwardedHeaderFilter}, as this once did. Two things were needed to stop the two
     * colliding, because a service that sets that property is asking for the same behaviour twice:
     *
     * <ul>
     *   <li>{@link ConditionalOnMissingFilterBean}, not {@code @ConditionalOnMissingBean}. Boot's
     *       bean is a {@code FilterRegistrationBean<ForwardedHeaderFilter>}, so a plain missing-bean
     *       check for {@code ForwardedHeaderFilter} never sees it; this annotation looks through the
     *       registration wrapper, which is what it exists for.</li>
     *   <li>A bean name of our own. Boot's auto-configuration is ordered ahead of this one, so its
     *       definition lands first; an identically named one behind it is a hard
     *       {@code BeanDefinitionOverrideException} at startup rather than an override.</li>
     * </ul>
     *
     * <p>The crawlers all set that property and were pinned to a crawler-core from before this
     * filter existed, so bumping them was what surfaced it — every one of them failed to start.
     */
    @Bean
    @ConditionalOnMissingFilterBean(ForwardedHeaderFilter.class)
    FilterRegistrationBean<ForwardedHeaderFilter> osmosisForwardedHeaderFilter() {
        FilterRegistrationBean<ForwardedHeaderFilter> registration =
                new FilterRegistrationBean<>(new ForwardedHeaderFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
