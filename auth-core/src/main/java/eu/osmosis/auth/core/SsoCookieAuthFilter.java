package eu.osmosis.auth.core;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates a request that carries a valid shared {@link SsoCookie}. This is what makes a login
 * on any other {@code *.osmosis.work} admin surface count here too, and what lets a login survive an
 * app restart (the self-signed cookie is re-validated with no server-side state).
 */
public class SsoCookieAuthFilter extends OncePerRequestFilter {

    private final String key;
    private final String username;

    public SsoCookieAuthFilter(String key, String username) {
        this.key = key;
        this.username = username;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null && req.getCookies() != null) {
            for (Cookie c : req.getCookies()) {
                if (SsoCookie.NAME.equals(c.getName())
                        && SsoCookie.valid(key, c.getValue(), Instant.now().getEpochSecond())) {
                    var auth = new UsernamePasswordAuthenticationToken(
                            username, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                    break;
                }
            }
        }
        chain.doFilter(req, res);
    }
}
