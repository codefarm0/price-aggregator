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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("PriceController Integration Tests")
class PriceControllerIntegrationTest {

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
        webClient = WebClient.create("http://localhost:" + port);
    }

    @Test
    @DisplayName("Should return HTTP 200 when all vendor APIs respond successfully")
    void shouldReturn200WhenAllVendorsRespond() {
        stubVendor("amazon", "iphone-15", 999.99);
        stubVendor("flipkart", "iphone-15", 899.99);
        stubVendor("walmart", "iphone-15", 799.99);

        List<PriceResult> results = webClient.get()
                .uri("/api/prices/iphone-15")
                .retrieve()
                .bodyToMono(priceResultListType)
                .block();

        assertNotNull(results);
        assertEquals(3, results.size());
        assertTrue(results.stream().anyMatch(r -> "amazon".equals(r.getVendor())));
        assertTrue(results.stream().anyMatch(r -> "flipkart".equals(r.getVendor())));
        assertTrue(results.stream().anyMatch(r -> "walmart".equals(r.getVendor())));
    }

    @Test
    @DisplayName("Should return HTTP 207 when all vendor APIs return errors")
    void shouldReturn207WhenAllVendorsFail() {
        stubVendorFailure("amazon", "iphone-15");
        stubVendorFailure("flipkart", "iphone-15");
        stubVendorFailure("walmart", "iphone-15");

        List<PriceResult> results = webClient.get()
                .uri("/api/prices/iphone-15")
                .retrieve()
                .bodyToMono(priceResultListType)
                .block();

        assertNotNull(results);
        assertEquals(3, results.size());
        assertTrue(results.stream().allMatch(r -> r.getPrice() == null || r.getError() != null));
    }

    @Test
    @DisplayName("Should return HTTP 200 when some vendors succeed and others fail")
    void shouldReturn200WhenSomeVendorsSucceed() {
        stubVendor("amazon", "iphone-15", 999.99);
        stubVendorFailure("flipkart", "iphone-15");
        stubVendor("walmart", "iphone-15", 799.99);

        List<PriceResult> results = webClient.get()
                .uri("/api/prices/iphone-15")
                .retrieve()
                .bodyToMono(priceResultListType)
                .block();

        assertNotNull(results);
        assertEquals(3, results.size());

    }

    @Test
    @DisplayName("Should echo client-provided X-Trace-Id header in response")
    void shouldEchoClientProvidedTraceIdInResponseHeader() {
        stubVendor("amazon", "iphone-15", 999.99);
        stubVendor("flipkart", "iphone-15", 899.99);
        stubVendor("walmart", "iphone-15", 799.99);

        String responseTraceId = webClient.get()
                .uri("/api/prices/iphone-15")
                .header("X-Trace-Id", "my-custom-trace-id")
                .retrieve()
                .toBodilessEntity()
                .map(response -> response.getHeaders().getFirst("X-Trace-Id"))
                .block();

        assertEquals("my-custom-trace-id", responseTraceId);
    }

    @Test
    @DisplayName("Should auto-generate a valid UUID traceId when client does not provide one")
    void shouldAutoGenerateUuidTraceIdWhenClientOmitsIt() {
        stubVendor("amazon", "iphone-15", 999.99);
        stubVendor("flipkart", "iphone-15", 899.99);
        stubVendor("walmart", "iphone-15", 799.99);

        String traceId = webClient.get()
                .uri("/api/prices/iphone-15")
                .retrieve()
                .toBodilessEntity()
                .map(response -> response.getHeaders().getFirst("X-Trace-Id"))
                .block();

        assertNotNull(traceId);
        assertFalse(traceId.isEmpty());
        assertDoesNotThrow(() -> java.util.UUID.fromString(traceId));
    }

    @Test
    @DisplayName("Should call vendor APIs when X-Refresh-Cache header is true")
    void shouldCallVendorApisWhenRefreshCacheHeaderIsTrue() {
        stubVendor("amazon", "iphone-15", 999.99);
        stubVendor("flipkart", "iphone-15", 899.99);
        stubVendor("walmart", "iphone-15", 799.99);

        webClient.get()
                .uri("/api/prices/iphone-15")
                .header("X-Refresh-Cache", "true")
                .retrieve()
                .bodyToMono(priceResultListType)
                .block();

        wiremock.verify(getRequestedFor(urlEqualTo("/mock-api/amazon/iphone-15")));
        wiremock.verify(getRequestedFor(urlEqualTo("/mock-api/flipkart/iphone-15")));
        wiremock.verify(getRequestedFor(urlEqualTo("/mock-api/walmart/iphone-15")));
    }

    @Test
    @DisplayName("Should call vendor APIs on second request when Redis cache is unavailable")
    void shouldCallApiOnSecondRequestWhenCacheIsUnavailable() {
        stubVendor("amazon", "iphone-15", 999.99);
        stubVendor("flipkart", "iphone-15", 899.99);
        stubVendor("walmart", "iphone-15", 799.99);

        List<PriceResult> firstResults = webClient.get()
                .uri("/api/prices/iphone-15")
                .retrieve()
                .bodyToMono(priceResultListType)
                .block();

        assertNotNull(firstResults);
        assertEquals(3, firstResults.size());
        assertTrue(firstResults.stream().allMatch(r -> "API".equals(r.getSource().name())));

        wiremock.verify(3, getRequestedFor(urlPathMatching("/mock-api/.*/iphone-15")));
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

    private void stubVendorFailure(String vendor, String productId) {
        wiremock.stubFor(get(urlPathMatching("/mock-api/" + vendor + "/" + productId))
                .willReturn(serverError().withBody("Internal Server Error")));
    }
}
