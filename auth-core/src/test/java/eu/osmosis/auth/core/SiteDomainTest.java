package eu.osmosis.auth.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The cookie is shared by every host under one domain. That domain is configuration, so it has to
 * survive the ways people write it, and it must not produce a Domain a browser refuses.
 */
class SiteDomainTest {

    @Test
    void unsetOrBlankMeansThisFleetsOwnDomain() {
        assertEquals("osmosis.work", SsoCookie.siteDomain(null));
        assertEquals("osmosis.work", SsoCookie.siteDomain("  "));
    }

    @Test
    void aLeadingDotSpacesAndCapitalsAreTidiedAway() {
        assertEquals("example.org", SsoCookie.siteDomain(" .Example.ORG "));
    }

    @Test
    void theCookieIsSharedWithEveryHostUnderTheDomain() {
        assertEquals("; Domain=.osmosis.work", SsoCookie.domainAttribute(null));
        assertEquals("; Domain=.example.org", SsoCookie.domainAttribute("example.org"));
    }

    /** Browsers refuse Domain=localhost or an IP; without a Domain the cookie still reaches every port. */
    @Test
    void localhostAndIpAddressesGetAHostOnlyCookie() {
        assertEquals("", SsoCookie.domainAttribute("localhost"));
        assertEquals("", SsoCookie.domainAttribute("192.168.1.20"));
        assertEquals("", SsoCookie.domainAttribute("nas"));
    }
}
