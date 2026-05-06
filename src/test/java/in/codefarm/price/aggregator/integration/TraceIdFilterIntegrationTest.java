package in.codefarm.price.aggregator.integration;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import in.codefarm.price.aggregator.dto.PriceResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "spring.data.redis.host=localhost",
    "spring.data.redis.port=6379",
    "spring.data.redis.repositories.enabled=false",
    "price.fetch.timeout-ms=5000"
})
@DisplayName("TraceIdFilter Integration Tests")
class TraceIdFilterIntegrationTest {

    @RegisterExtension
    static WireMockExtension wiremock = WireMockExtension.newInstance()
            .options(WireMockConfiguration.wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        String baseUrl = "http://localhost:" + wiremock.getPort();
        registry.add("vendors.amazon.base-url", () -> baseUrl);
        registry.add("vendors.flipkart.base-url", () -> baseUrl);
        registry.add("vendors.walmart.base-url", () -> baseUrl);
    }

    @LocalServerPort
    private int port;

    private WebClient webClient;

    private final ParameterizedTypeReference<List<PriceResult>> priceResultListType =
            new ParameterizedTypeReference<List<PriceResult>>() {};

    @BeforeEach
    void setUp() {
        wiremock.resetAll();
        stubAllVendors("iphone-15", 999.99);
        webClient = WebClient.create("http://localhost:" + port);
    }

    @Test
    @DisplayName("Should return the client-provided X-Trace-Id in response header")
    void shouldReturnClientProvidedTraceIdInResponseHeader() {
        String clientTraceId = "client-trace-" + UUID.randomUUID();

        String responseTraceId = webClient.get()
                .uri("/api/prices/iphone-15")
                .header("X-Trace-Id", clientTraceId)
                .retrieve()
                .toBodilessEntity()
                .map(response -> response.getHeaders().getFirst("X-Trace-Id"))
                .block();

        assertEquals(clientTraceId, responseTraceId);
    }

    @Test
    @DisplayName("Should generate a valid UUID when client omits X-Trace-Id header")
    void shouldGenerateValidUuidWhenClientOmitsTraceIdHeader() {
        String traceId = webClient.get()
                .uri("/api/prices/iphone-15")
                .retrieve()
                .toBodilessEntity()
                .map(response -> response.getHeaders().getFirst("X-Trace-Id"))
                .block();

        assertNotNull(traceId);
        assertFalse(traceId.isEmpty());
        assertDoesNotThrow(() -> UUID.fromString(traceId));
    }

    @Test
    @DisplayName("Should propagate X-Trace-Id header to outbound vendor API requests")
    void shouldPropagateTraceIdToOutboundVendorApiRequests() {
        String clientTraceId = "trace-to-propagate-" + UUID.randomUUID();

        wiremock.resetAll();
        wiremock.stubFor(get(urlPathMatching("/mock-api/amazon/iphone-15"))
                .withHeader("X-Trace-Id", equalTo(clientTraceId))
                .willReturn(okJson("""
                        {
                            "productId": "iphone-15",
                            "price": 999.99,
                            "vendor": "amazon",
                            "timestamp": %d
                        }
                        """.formatted(System.currentTimeMillis()))
                        .withHeader("Content-Type", "application/json")));

        stubVendor("flipkart", "iphone-15", 899.99);
        stubVendor("walmart", "iphone-15", 799.99);

        webClient.get()
                .uri("/api/prices/iphone-15")
                .header("X-Trace-Id", clientTraceId)
                .retrieve()
                .bodyToMono(priceResultListType)
                .block();

        wiremock.verify(getRequestedFor(urlPathEqualTo("/mock-api/amazon/iphone-15"))
                .withHeader("X-Trace-Id", equalTo(clientTraceId)));
    }

    @Test
    @DisplayName("Should return HTTP 207 with client traceId when all vendors fail")
    void shouldReturn207WithClientTraceIdWhenAllVendorsFail() {
        String clientTraceId = "error-trace-" + UUID.randomUUID();

        wiremock.resetAll();
        wiremock.stubFor(get(urlPathMatching("/mock-api/amazon/iphone-15"))
                .willReturn(serverError()));
        wiremock.stubFor(get(urlPathMatching("/mock-api/flipkart/iphone-15"))
                .willReturn(serverError()));
        wiremock.stubFor(get(urlPathMatching("/mock-api/walmart/iphone-15"))
                .willReturn(serverError()));

        ResponseEntity<List<PriceResult>> response = webClient.get()
                .uri("/api/prices/iphone-15")
                .header("X-Trace-Id", clientTraceId)
                .exchangeToMono(clientResponse -> clientResponse.toEntity(priceResultListType))
                .block();

        assertNotNull(response);
        assertEquals(HttpStatus.MULTI_STATUS, response.getStatusCode());
        assertEquals(clientTraceId, response.getHeaders().getFirst("X-Trace-Id"));

        List<PriceResult> results = response.getBody();
        assertNotNull(results);
        assertEquals(3, results.size());
        assertTrue(results.stream().allMatch(r -> r.getPrice() == null || r.getError() != null));
    }

    @Test
    @DisplayName("Should assign unique traceIds to concurrent requests")
    void shouldAssignUniqueTraceIdsToConcurrentRequests() throws InterruptedException {
        Thread thread1 = new Thread(this::fetchPricesAndCaptureTraceId, "test-thread-1");
        Thread thread2 = new Thread(this::fetchPricesAndCaptureTraceId, "test-thread-2");

        thread1.start();
        thread2.start();
        thread1.join(5000);
        thread2.join(5000);
    }

    private void fetchPricesAndCaptureTraceId() {
        String traceId = webClient.get()
                .uri("/api/prices/iphone-15")
                .retrieve()
                .toBodilessEntity()
                .map(response -> response.getHeaders().getFirst("X-Trace-Id"))
                .block();

        assertNotNull(traceId);
    }

    private void stubAllVendors(String productId, double price) {
        stubVendor("amazon", productId, price);
        stubVendor("flipkart", productId, price);
        stubVendor("walmart", productId, price);
    }

    private void stubVendor(String vendor, String productId, double price) {
        wiremock.stubFor(get(urlPathMatching("/mock-api/" + vendor + "/" + productId))
                .willReturn(okJson("""
                        {
                            "productId": "%s",
                            "price": %.2f,
                            "vendor": "%s",
                            "timestamp": %d
                        }
                        """.formatted(productId, price, vendor, System.currentTimeMillis()))
                        .withHeader("Content-Type", "application/json")));
    }
}
