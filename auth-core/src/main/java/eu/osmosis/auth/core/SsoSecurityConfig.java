package eu.osmosis.auth.core;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * The shared login wall. Single sign-on lives at {@code auth.<domain>} (auth.osmosis.work for this
 * fleet; see {@code osmosis.auth.domain}) — a service using this never
 * shows a login form or checks a password; it only validates the shared {@code osmosis_sso} cookie
 * and, when there isn't one, bounces the browser to the central login with a {@code ?next} back to
 * where it was going.
 *
 * <p><b>The wall is default-DENY.</b> Every path is behind the login unless it is in
 * {@link #PUBLIC_BASE} or is opted out via {@code osmosis.auth.public-paths}. A service that wants
 * to be fully public sets {@code osmosis.auth.public-paths: /**}.
 */
@Configuration
public class SsoSecurityConfig {

    /**
     * Only these stay public by default: Spring's error page, the shared static assets and the
     * version endpoint. {@code /actuator/**} is the Spring Boot management endpoints — they live on
     * a separate 127.0.0.1-only port (never exposed via Caddy), and the vps-health deployer polls
     * /actuator/health to decide rollback, so they must NOT be walled (a walled 302 would make a
     * redirect-following health check see the login page as a healthy 200).
     */
    private static final String[] PUBLIC_BASE = {
        "/error",
        "/actuator/**",
        "/app.css", "/app.js", "/version",
        "/favicon.svg", "/favicon.png", "/favicon.ico", "/apple-touch-icon.png"
    };

    /**
     * The domain every service lives under ({@code OSMOSIS_AUTH_DOMAIN}). The login cookie is shared
     * by every host under it, and the central login is looked for at {@code https://auth.<domain>/}
     * unless {@code osmosis.auth.url} names it outright. Unset, it is this fleet's own domain.
     */
    @Value("${osmosis.auth.domain:" + SsoCookie.DEFAULT_DOMAIN + "}")
    private String domain;

    /**
     * Where the central login lives, when it is not at {@code https://auth.<domain>/} (a local setup,
     * say, with the login on {@code http://localhost:8210/}). Note the value is the login *page* —
     * the form is served at the root, and {@code ?next=} is appended to it. Pointing this at a path
     * that only accepts POST (e.g. {@code .../login}) is what produces a 405 on the redirect, so the
     * trailing form is normalised below.
     */
    @Value("${osmosis.auth.url:}")
    private String authUrl;

    // Comma-separated Ant patterns to leave OUTSIDE the wall. `crawler.security.public-paths` is the
    // pre-auth-core spelling and still works, so existing services need no config change.
    @Value("${osmosis.auth.public-paths:${crawler.security.public-paths:}}")
    private String publicPaths;

    @Value("${osmosis.auth.csrf-ignore-patterns:${crawler.security.csrf-ignore-patterns:}}")
    private String csrfIgnorePatterns;

    // Dedicated random SSO cookie signing key (SSO_SIGNING_KEY from the shared secrets file).
    @Value("${SSO_SIGNING_KEY:}")
    private String ssoKey;

    @Value("${osmosis.auth.username:${app.admin.username:osmosis}}")
    private String adminUsername;

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        String loginUrl = normalizedAuthUrl();

        http.authorizeHttpRequests(auth ->
                        auth.requestMatchers(allPublicPaths()).permitAll().anyRequest().authenticated())
                // A valid shared osmosis_sso cookie authenticates the request (from a login on any
                // admin surface, or one that survived a restart).
                .addFilterBefore(new SsoCookieAuthFilter(ssoKey, adminUsername),
                        UsernamePasswordAuthenticationFilter.class)
                // No local login: send unauthenticated browsers to the central login, ?next back here.
                .exceptionHandling(e -> e.authenticationEntryPoint((request, response, ex) -> {
                    StringBuffer url = request.getRequestURL();
                    if (request.getQueryString() != null) {
                        url.append('?').append(request.getQueryString());
                    }
                    response.sendRedirect(loginUrl + "?next="
                            + URLEncoder.encode(url.toString(), StandardCharsets.UTF_8));
                }))
                .logout(logout -> logout
                        // Clear the shared cookie (global logout) and return to the central login.
                        .logoutSuccessUrl(loginUrl)
                        .addLogoutHandler((request, response, authentication) ->
                                response.addHeader("Set-Cookie", clearCookieHeader(request)))
                        .deleteCookies("JSESSIONID")
                        .permitAll());

        String[] csrfPatterns = splitTrimmed(csrfIgnorePatterns);
        if (csrfPatterns.length > 0) {
            http.csrf(csrf -> csrf.ignoringRequestMatchers(csrfPatterns));
        }

        return http.build();
    }

    /**
     * The login form is served at the root of the auth host, and this appends {@code ?next=}. A
     * value without a trailing slash would build {@code https://auth.osmosis.work?next=…}, so
     * normalise it rather than trusting whoever set the property.
     */
    private String normalizedAuthUrl() {
        String url = (authUrl == null || authUrl.isBlank())
                ? "https://auth." + SsoCookie.siteDomain(domain) + "/" : authUrl.trim();
        return url.endsWith("/") ? url : url + "/";
    }

    private String[] allPublicPaths() {
        List<String> all = new ArrayList<>(Arrays.asList(PUBLIC_BASE));
        all.addAll(Arrays.asList(splitTrimmed(publicPaths)));
        return all.toArray(String[]::new);
    }

    // Must name the same Domain the login set, or the browser keeps the cookie it is meant to drop.
    private String clearCookieHeader(HttpServletRequest req) {
        String c = SsoCookie.NAME + "=" + SsoCookie.domainAttribute(domain)
                + "; Path=/; Max-Age=0; HttpOnly; SameSite=Lax";
        return req.isSecure() ? c + "; Secure" : c;
    }

    private static String[] splitTrimmed(String value) {
        if (value == null || value.isBlank()) return new String[0];
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }
}
