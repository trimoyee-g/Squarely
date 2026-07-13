package com.squarely.gateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import static org.assertj.core.api.Assertions.assertThat;

/** Every public path prefix must reach its owning service — the one thing this module does. */
@SpringBootTest
class RouteMapTest {

    @Autowired
    RouteLocator routeLocator;

    @ParameterizedTest
    @CsvSource({
            "/auth/login,               auth",
            "/auth/refresh,             auth",
            "/groups/1,                 group",
            "/expenses/1,               group",
            "/balances/group/1,         ledger",
            "/settlements/1/ack,        ledger",
            "/personal-debts/1,         ledger",
            "/ledger/entries,           ledger",
            "/notifications,            notification",
            "/recurring/1,              notification",
            "/obligations/1,            notification",
    })
    void routesToOwningService(String path, String expectedRouteId) {
        assertThat(routeIdFor(path)).isEqualTo(expectedRouteId);
    }

    @Test
    void unknownPathMatchesNoRoute() {
        assertThat(routeIdFor("/nope")).isNull();
    }

    private String routeIdFor(String path) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path.trim()));
        return routeLocator.getRoutes()
                .filterWhen(route -> route.getPredicate().apply(exchange))
                .next()
                .map(route -> route.getId())
                .block();
    }
}
