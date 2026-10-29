package com.bms.analytics;

import com.bms.common.ApiException;
import com.bms.config.BmsProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.LocalDate;
import java.util.function.Function;

/**
 * Calls the Python analytics service (read-only computations). Java authorises the caller FIRST, then asks
 * Python; Python can only read the minimal transaction feed exposed at /internal/v1 and can never write.
 */
@Component
public class AnalyticsClient {
    private static final Logger log = LoggerFactory.getLogger(AnalyticsClient.class);

    private final RestClient client;
    private final boolean configured;

    public AnalyticsClient(BmsProperties props) {
        var cfg = props.analytics();
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout((int) cfg.timeout().toMillis());
        rf.setReadTimeout((int) cfg.timeout().toMillis());
        this.configured = cfg.serviceToken() != null && !cfg.serviceToken().isBlank();
        this.client = RestClient.builder()
                .baseUrl(cfg.baseUrl())
                .requestFactory(rf)
                .defaultHeader("X-Service-Token", configured ? cfg.serviceToken() : "")
                .build();
    }

    public JsonNode summary(LocalDate from, LocalDate to) {
        return get(u -> u.path("/api/v1/summary").queryParam("from", from).queryParam("to", to).build());
    }

    public JsonNode periodic(String period, LocalDate from, LocalDate to) {
        return get(u -> u.path("/api/v1/stats/periodic").queryParam("period", period)
                .queryParam("from", from).queryParam("to", to).build());
    }

    public JsonNode trends(int days) {
        return get(u -> u.path("/api/v1/trends").queryParam("days", days).build());
    }

    public JsonNode topAccounts(LocalDate from, LocalDate to, int limit) {
        return get(u -> u.path("/api/v1/accounts/top").queryParam("from", from).queryParam("to", to)
                .queryParam("limit", limit).build());
    }

    public JsonNode accountSummary(Long accountId, LocalDate from, LocalDate to) {
        return get(u -> u.path("/api/v1/accounts/{id}/summary").queryParam("from", from).queryParam("to", to)
                .build(accountId));
    }

    public JsonNode report(String period, LocalDate date) {
        return get(u -> u.path("/api/v1/reports/admin").queryParam("period", period).queryParam("date", date).build());
    }

    public boolean healthy() {
        try {
            JsonNode n = client.get().uri("/health").retrieve().body(JsonNode.class);
            return n != null && "ok".equals(n.path("status").asText());
        } catch (RestClientException e) {
            return false;
        }
    }

    private JsonNode get(Function<org.springframework.web.util.UriBuilder, java.net.URI> uri) {
        if (!configured) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ANALYTICS_NOT_CONFIGURED",
                    "Analytics service token is not configured");
        }
        try {
            return client.get().uri(uri).retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            log.warn("Analytics service returned {}", e.getStatusCode());
            if (e.getStatusCode().is4xxClientError()) {
                throw ApiException.badRequest("ANALYTICS_REJECTED", "The analytics request was rejected: invalid parameters");
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, "ANALYTICS_ERROR", "The analytics service reported an error");
        } catch (RestClientException e) {
            log.warn("Analytics service unreachable: {}", e.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ANALYTICS_UNAVAILABLE",
                    "The analytics service is not reachable. Start it and try again.");
        }
    }
}
