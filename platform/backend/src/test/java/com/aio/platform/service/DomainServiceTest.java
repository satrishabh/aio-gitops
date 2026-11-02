package com.aio.platform.service;

import static org.junit.jupiter.api.Assertions.*;

import com.aio.platform.domain.Domain;
import java.util.List;
import org.junit.jupiter.api.Test;

class DomainServiceTest {

    @Test
    void normalizesHostsAndAcceptsHostnameUrls() {
        assertEquals("shop.example.com", DomainService.normalizeHost(" HTTPS://Shop.Example.com/path "));
        assertEquals("shop.example.com", DomainService.normalizeHost("shop.example.com."));
    }

    @Test
    void rejectsUnsupportedOrInvalidHosts() {
        for (String host : List.of("", "localhost", "127.0.0.1", "*.example.com", "www.example.com",
                "bad..example.com", "-bad.example.com")) {
            assertThrows(IllegalArgumentException.class, () -> DomainService.normalizeHost(host), host);
        }
    }

    @Test
    void normalizesPathsAndRejectsTraversalAndRegex() {
        assertEquals("/", DomainService.normalizePath(null));
        assertEquals("/api/v1", DomainService.normalizePath("api/v1/"));
        for (String path : List.of("/../secret", "/a/..", "/a/./b", "/a/*", "/has space")) {
            assertThrows(IllegalArgumentException.class, () -> DomainService.normalizePath(path), path);
        }
    }

    @Test
    void certificateOwnerIsOldestRoutableRouteOnly() {
        Domain pending = domain(1L, Domain.PENDING_DNS);
        Domain newer = domain(3L, Domain.READY);
        Domain older = domain(2L, Domain.ISSUING);

        assertEquals(older, DomainService.chooseOwner(List.of(pending, newer, older)).orElseThrow());
        assertTrue(DomainService.chooseOwner(List.of(pending)).isEmpty());
    }

    @Test
    void routesBlockContainsOnlyRouteSettings() {
        Domain route = domain(1L, Domain.READY);
        route.host = "shop.example.com";
        route.path = "/store";
        route.issuer = "gts";
        route.certOwner = true;
        route.forceHttps = true;
        route.stripPrefix = true;
        route.wwwRedirect = true;

        String block = DomainService.routesBlock(List.of(route), null, "dev");

        assertTrue(block.contains("host: shop.example.com"));
        assertTrue(block.contains("path: \"/store\""));
        assertTrue(block.contains("issuer: gts"));
        assertTrue(block.contains("certOwner: true"));
        assertTrue(block.contains("stripPrefix: true"));
        assertTrue(block.contains("wwwRedirect: true"));
    }

    private static Domain domain(Long id, String status) {
        Domain d = new Domain();
        d.id = id;
        d.status = status;
        return d;
    }
}
