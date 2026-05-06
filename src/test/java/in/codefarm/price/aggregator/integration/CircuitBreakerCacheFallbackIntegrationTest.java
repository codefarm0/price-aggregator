package in.codefarm.price.aggregator.integration;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import in.codefarm.price.aggregator.dto.PriceResult;
import in.codefarm.price.aggregator.dto.PriceSource;
import in.codefarm.price.aggregator.service.PriceCacheService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests that verify the interaction between CircuitBreaker and Redis cache.
 *
 * This is a critical resilience pattern: when a vendor's circuit breaker is OPEN
 * (vendor is down), the system should fall back to cached prices from Redis
 * instead of returning an error to the user.
 *
 * Test setup:
 *   - Testcontainers Redis for real caching
 *   - WireMock for vendor API stubbing
 *   - Circuit breaker config (from src/test/resources/application.yaml):
 *     minCalls=3, failureRate=50%, waitDuration=2s, halfOpenCalls=1
 *
 * Scenarios tested:
 *   1. Cache HIT on first request — vendor returns price, it's cached, subsequent request uses cache
 *   2. Circuit breaker OPEN + cache available — fallback returns cached price instead of error
 *   3. Circuit breaker OPEN + no cache — fallback returns error (baseline behavior)
 *   4. X-Refresh-Cache=true bypasses cache even when cache has data
 *   5. Full lifecycle: cache populate → circuit open → cache fallback → circuit recovery → fresh cache
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = {
    "resilience4j.circuitbreaker.instances.amazon.slidingWindowSize=5",
    "resilience4j.circuitbreaker.instances.amazon.minimumNumberOfCalls=3",
    "resilience4j.circuitbreaker.instances.amazon.waitDurationInOpenState=2s",
    "resilience4j.circuitbreaker.instances.amazon.permittedNumberOfCallsInHalfOpenState=1"
})
@DisplayName("CircuitBreaker + Cache Fallback Integration Tests")
class CircuitBreakerCacheFallbackIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:8.6-trixie")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerRedisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @RegisterExtension
    static WireMockExtension wiremock = WireMockExtension.newInstance()
            .options(WireMockConfiguration.wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void registerWiremockProperties(DynamicPropertyRegistry registry) {
        String baseUrl = "http://localhost:" + wiremock.getPort();
        registry.add("vendors.amazon.base-url", () -> baseUrl);
        registry.add("vendors.flipkart.base-url", () -> baseUrl);
        registry.add("vendors.walmart.base-url", () -> baseUrl);
    }

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private PriceCacheService cacheService;

    @LocalServerPort
    private int port;

    private WebClient webClient;

    private final ParameterizedTypeReference<List<PriceResult>> priceResultListType =
            new ParameterizedTypeReference<List<PriceResult>>() {};

    @BeforeEach
    void setUp() {
        wiremock.resetAll();
        webClient = WebClient.create("http://localhost:" + port);
        circuitBreakerRegistry.circuitBreaker("amazon").reset();
        circuitBreakerRegistry.circuitBreaker("flipkart").reset();
        circuitBreakerRegistry.circuitBreaker("walmart").reset();
        cacheService.evictAll();
    }

    /**
     * Verifies that the first request calls the vendor API and caches the result,
     * then the second request returns from cache without calling the vendor API.
     *
     * Flow:
     *   Request 1: cache MISS → API call → cache SET → PriceResult.source = API
     *   Request 2: cache HIT → no API call → PriceResult.source = CACHE
     *
     * Assertions:
     *   - First result: source = API
     *   - Second result: source = CACHE
     *   - WireMock received exactly 1 request (only the first call)
     *   - Price value is the same in both responses
     */
    @Test
    @DisplayName("Should return CACHE source on second request without calling vendor API")
    void shouldReturnCachedResultOnSecondRequestWithoutApiCall() {
        stubVendor("amazon", "iphone-15", 999.99);
        stubVendor("flipkart", "iphone-15", 899.99);
        stubVendor("walmart", "iphone-15", 799.99);

        // First request — cache MISS, should call API
        List<PriceResult> firstResults = fetchPrices();
        PriceResult firstAmazon = getVendorResult(firstResults, "amazon");
        assertEquals(PriceSource.API, firstAmazon.getSource(),
                "First request should source from API (cache miss)");
        assertEquals(999.99, firstAmazon.getPrice(), "Amazon price should match stubbed value");

        // Second request — cache HIT, should NOT call API
        List<PriceResult> secondResults = fetchPrices();
        PriceResult secondAmazon = getVendorResult(secondResults, "amazon");
        assertEquals(PriceSource.CACHE, secondAmazon.getSource(),
                "Second request should source from CACHE (cache hit)");
        assertEquals(999.99, secondAmazon.getPrice(),
                "Cached price should match the originally fetched price");

        // Verify WireMock received exactly 3 requests (one per vendor on first call only)
        wiremock.verify(1, getRequestedFor(urlPathMatching("/mock-api/amazon/iphone-15")));
        wiremock.verify(1, getRequestedFor(urlPathMatching("/mock-api/flipkart/iphone-15")));
        wiremock.verify(1, getRequestedFor(urlPathMatching("/mock-api/walmart/iphone-15")));
    }

    /**
     * Verifies the critical resilience pattern: when the circuit breaker is OPEN
     * and Redis has cached data, the fallback returns the cached price instead of an error.
     *
     * Flow:
     *   1. First request: API succeeds → price cached (source = API)
     *   2. Trip circuit breaker OPEN with 3 failures
     *   3. Request with circuit OPEN → fallback finds cache → returns cached price (source = FALLBACK)
     *
     * This is the "graceful degradation" behavior — users see stale prices instead of errors.
     *
     * Assertions:
     *   - Circuit state = OPEN
     *   - Amazon result has price = cached price (not null)
     *   - Amazon result source = FALLBACK (indicates cache was used as fallback, not fresh API)
     *   - Amazon result has no error field
     */
    @Test
    @DisplayName("Should return cached price as fallback when circuit breaker is OPEN")
    void shouldReturnCachedPriceWhenCircuitBreakerIsOpen() {
        stubVendor("flipkart", "iphone-15", 899.99);
        stubVendor("walmart", "iphone-15", 799.99);

        // Step 1: Populate cache with a successful API call
        stubVendor("amazon", "iphone-15", 999.99);
        List<PriceResult> initialResults = fetchPrices();
        PriceResult initialAmazon = getVendorResult(initialResults, "amazon");
        assertEquals(PriceSource.API, initialAmazon.getSource());
        assertEquals(999.99, initialAmazon.getPrice(), "Cache should have the original price");

        // Step 2: Trip Amazon circuit breaker to OPEN
        // Use refreshCache=true to bypass cache so API is actually called (and fails)
        // This trips the circuit breaker without destroying the cached value
        wiremock.resetAll();
        stubVendorFailure("amazon", "iphone-15");
        stubVendor("flipkart", "iphone-15", 899.99);
        stubVendor("walmart", "iphone-15", 799.99);

        for (int i = 0; i < 3; i++) {
            fetchPricesWithRefresh(true);
        }

        assertEquals(CircuitBreaker.State.OPEN, circuitBreakerRegistry.circuitBreaker("amazon").getState(),
                "Amazon circuit should be OPEN after failures");

        // Step 3: Request with circuit OPEN — use refreshCache=true to force API path
        // Circuit is OPEN → CallNotPermittedException → fallback finds cache → returns cached price
        // This verifies the "graceful degradation" pattern: stale data > no data
        List<PriceResult> fallbackResults = fetchPricesWithRefresh(true);
        PriceResult fallback = getVendorResult(fallbackResults, "amazon");

        assertNotNull(fallback.getPrice(),
                "Amazon price should NOT be null — fallback should use cached data");
        assertEquals(999.99, fallback.getPrice(),
                "Amazon price should match the cached price (not null or different)");
        assertEquals(PriceSource.FALLBACK, fallback.getSource(),
                "Amazon source should be FALLBACK (cache used as fallback after circuit blocked API call)");
        assertNull(fallback.getError(),
                "Amazon should have NO error when fallback succeeds with cached data");
    }

    /**
     * Verifies that when the circuit breaker is OPEN and there is NO cached data,
     * the fallback returns an error result (the worst-case scenario).
     *
     * This is the baseline: no circuit breaker + no cache = error.
     */
    @Test
    @DisplayName("Should return error when circuit breaker is OPEN and cache is empty")
    void shouldReturnErrorWhenCircuitIsOpenAndCacheIsEmpty() {
        stubVendor("flipkart", "iphone-15", 899.99);
        stubVendor("walmart", "iphone-15", 799.99);

        // Trip Amazon circuit without ever caching — never made a successful API call
        stubVendorFailure("amazon", "iphone-15");
        for (int i = 0; i < 3; i++) {
            fetchPrices();
        }

        assertEquals(CircuitBreaker.State.OPEN, circuitBreakerRegistry.circuitBreaker("amazon").getState());

        // Request with circuit OPEN and empty cache
        List<PriceResult> results = fetchPrices();
        PriceResult amazon = getVendorResult(results, "amazon");

        assertNull(amazon.getPrice(), "Amazon price should be null (no cache available)");
        assertNotNull(amazon.getError(), "Amazon should have an error message");
        assertEquals("No price available", amazon.getError(),
                "Error should indicate no price is available");
        assertEquals(PriceSource.FALLBACK, amazon.getSource(),
                "Source should be FALLBACK");
    }

    /**
     * Verifies that X-Refresh-Cache=true bypasses the cache and forces a fresh API call,
     * even when valid cached data exists.
     *
     * Flow:
     *   1. Populate cache via first request
     *   2. Request with X-Refresh-Cache: true → cache MISS → API call
     *
     * Assertions:
     *   - Second request source = API (not CACHE)
     *   - WireMock received 2 requests for Amazon (initial + refresh)
     */
    @Test
    @DisplayName("Should bypass cache and call API when X-Refresh-Cache header is true")
    void shouldBypassCacheWhenRefreshCacheHeaderIsTrue() {
        stubVendor("amazon", "iphone-15", 999.99);
        stubVendor("flipkart", "iphone-15", 899.99);
        stubVendor("walmart", "iphone-15", 799.99);

        // First request — populates cache
        List<PriceResult> firstResults = fetchPrices();
        assertEquals(PriceSource.API, getVendorResult(firstResults, "amazon").getSource());

        // Second request with refresh=true — bypasses cache
        List<PriceResult> refreshResults = fetchPricesWithRefresh(true);
        assertEquals(PriceSource.API, getVendorResult(refreshResults, "amazon").getSource(),
                "With refreshCache=true, source should be API even though cache has data");

        // Verify WireMock received 2 requests for Amazon
        wiremock.verify(2, getRequestedFor(urlPathMatching("/mock-api/amazon/iphone-15")));
    }

    /**
     * Full lifecycle test: cache populate → circuit open → cache fallback →
     * circuit recovery → fresh cache update.
     *
     * This verifies the complete resilience lifecycle in one test.
     *
     * Stages:
     *   1. WARM-UP:   API call → cache populated (source = API)
     *   2. FAILURE:   3 API failures → circuit OPEN
     *   3. FALLBACK:  Circuit OPEN + cache available → returns cached price (source = FALLBACK)
     *   4. RECOVERY:  Wait 2.5s → circuit HALF_OPEN → restore API → circuit CLOSED
     *   5. REFRESH:   New API call → fresh price (source = API)
     */
    @Test
    @DisplayName("Should complete full lifecycle: cache → circuit open → fallback → recovery → fresh cache")
    void shouldCompleteFullLifecycleCacheToRecovery() throws InterruptedException {
        // Stage 1: WARM-UP — populate cache
        stubVendor("amazon", "iphone-15", 999.99);
        stubVendor("flipkart", "iphone-15", 899.99);
        stubVendor("walmart", "iphone-15", 799.99);

        List<PriceResult> warmResults = fetchPrices();
        assertEquals(PriceSource.API, getVendorResult(warmResults, "amazon").getSource());
        assertEquals(999.99, getVendorResult(warmResults, "amazon").getPrice());

        // Stage 2: FAILURE — trip circuit breaker
        // Use refreshCache=true to bypass cache so API is actually called (and fails)
        wiremock.resetAll();
        stubVendorFailure("amazon", "iphone-15");
        stubVendor("flipkart", "iphone-15", 899.99);
        stubVendor("walmart", "iphone-15", 799.99);

        for (int i = 0; i < 3; i++) {
            fetchPricesWithRefresh(true);
        }
        assertEquals(CircuitBreaker.State.OPEN, circuitBreakerRegistry.circuitBreaker("amazon").getState());

        // Stage 3: FALLBACK — circuit open, cache from Stage 1 is still intact
        // Use refreshCache=true to force the API path → circuit blocks → fallback returns cache
        List<PriceResult> fallbackResults = fetchPricesWithRefresh(true);
        PriceResult fallback = getVendorResult(fallbackResults, "amazon");
        assertNotNull(fallback.getPrice(), "Should have cached price during circuit open");
        assertEquals(999.99, fallback.getPrice(), "Cached price should match original");
        assertEquals(PriceSource.FALLBACK, fallback.getSource(), "Should use FALLBACK source");

        // Stage 4: RECOVERY — wait for circuit to transition to HALF_OPEN, then restore API
        Thread.sleep(2500);
        wiremock.resetAll();
        stubVendor("amazon", "iphone-15", 949.99); // Different price to verify it's fresh
        stubVendor("flipkart", "iphone-15", 899.99);
        stubVendor("walmart", "iphone-15", 799.99);

        // Use refreshCache=true to bypass cache — allows circuit breaker probe call to reach API
        List<PriceResult> recoveryResults = fetchPricesWithRefresh(true);
        Thread.sleep(500); // Allow async state transition

        assertEquals(CircuitBreaker.State.CLOSED, circuitBreakerRegistry.circuitBreaker("amazon").getState(),
                "Circuit should be CLOSED after successful probe");

        // Stage 5: REFRESH — fresh price from API, cache updated
        assertEquals(949.99, getVendorResult(recoveryResults, "amazon").getPrice(),
                "Price should be the new fresh value from API, not the old cached value");
        assertEquals(PriceSource.API, getVendorResult(recoveryResults, "amazon").getSource(),
                "Source should be API after circuit recovery");
    }

    private List<PriceResult> fetchPrices() {
        return webClient.get()
                .uri("/api/prices/iphone-15")
                .retrieve()
                .bodyToMono(priceResultListType)
                .block();
    }

    private List<PriceResult> fetchPricesWithRefresh(boolean refresh) {
        return webClient.get()
                .uri("/api/prices/iphone-15")
                .header("X-Refresh-Cache", String.valueOf(refresh))
                .retrieve()
                .bodyToMono(priceResultListType)
                .block();
    }

    private PriceResult getVendorResult(List<PriceResult> results, String vendor) {
        return results.stream()
                .filter(r -> vendor.equals(r.getVendor()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No result found for vendor: " + vendor));
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
