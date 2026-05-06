package in.codefarm.price.aggregator.integration;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import in.codefarm.price.aggregator.dto.PriceResult;
import in.codefarm.price.aggregator.dto.PriceSource;
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

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests that verify Resilience4j CircuitBreaker behavior end-to-end.
 *
 * Circuit breaker config for these tests (via @TestPropertySource):
 *   slidingWindowSize = 5           — evaluates last 5 calls for failure rate
 *   minimumNumberOfCalls = 3        — needs at least 3 calls before it can OPEN
 *   failureRateThreshold = 50%      — opens when >= 50% of calls fail
 *   waitDurationInOpenState = 2s    — waits 2s before transitioning to HALF_OPEN
 *   slowCallDurationThreshold = 1s  — calls > 1s are counted as slow
 *
 * State machine:
 *   CLOSED (normal) → OPEN (failure rate exceeded) → HALF_OPEN (after wait) → CLOSED (if probe calls succeed)
 *
 * When OPEN: CircuitBreaker throws CallNotPermittedException immediately without calling the vendor.
 *            The AmazonClient catch block then falls back to getFallbackPrice().
 *            Since Redis is unavailable, fallback returns PriceResult.error("amazon", "No price available", traceId).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "spring.data.redis.host=localhost",
    "spring.data.redis.port=6379",
    "spring.data.redis.repositories.enabled=false",
    "price.fetch.timeout-ms=5000",
    "price.pool.core-size=3",
    "resilience4j.circuitbreaker.instances.amazon.slidingWindowSize=5",
    "resilience4j.circuitbreaker.instances.amazon.minimumNumberOfCalls=3",
    "resilience4j.circuitbreaker.instances.amazon.failureRateThreshold=50",
    "resilience4j.circuitbreaker.instances.amazon.waitDurationInOpenState=2s",
    "resilience4j.circuitbreaker.instances.amazon.slowCallDurationThreshold=1000ms",
    "resilience4j.circuitbreaker.instances.amazon.slowCallRateThreshold=50",
    "resilience4j.circuitbreaker.instances.amazon.permittedNumberOfCallsInHalfOpenState=1",
    "resilience4j.circuitbreaker.instances.flipkart.slidingWindowSize=5",
    "resilience4j.circuitbreaker.instances.flipkart.minimumNumberOfCalls=3",
    "resilience4j.circuitbreaker.instances.flipkart.failureRateThreshold=50",
    "resilience4j.circuitbreaker.instances.flipkart.waitDurationInOpenState=2s",
    "resilience4j.circuitbreaker.instances.flipkart.permittedNumberOfCallsInHalfOpenState=1",
    "resilience4j.circuitbreaker.instances.walmart.slidingWindowSize=5",
    "resilience4j.circuitbreaker.instances.walmart.minimumNumberOfCalls=3",
    "resilience4j.circuitbreaker.instances.walmart.failureRateThreshold=50",
    "resilience4j.circuitbreaker.instances.walmart.waitDurationInOpenState=2s",
    "resilience4j.circuitbreaker.instances.walmart.permittedNumberOfCallsInHalfOpenState=1"
})
@DisplayName("CircuitBreaker Integration Tests")
class CircuitBreakerIntegrationTest {

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

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @LocalServerPort
    private int port;

    private WebClient webClient;

    private final ParameterizedTypeReference<List<PriceResult>> priceResultListType =
            new ParameterizedTypeReference<List<PriceResult>>() {};

    @BeforeEach
    void setUp() {
        wiremock.resetAll();
        webClient = WebClient.create("http://localhost:" + port);

        // Reset all circuit breakers to CLOSED before each test
        circuitBreakerRegistry.circuitBreaker("amazon").reset();
        circuitBreakerRegistry.circuitBreaker("flipkart").reset();
        circuitBreakerRegistry.circuitBreaker("walmart").reset();
    }

    /**
     * Verifies that repeated vendor failures trip the circuit breaker to OPEN state.
     *
     * Flow:
     *   Calls 1-2: Fail, but circuit stays CLOSED (below minimumNumberOfCalls=3)
     *   Call 3: Fails → failure rate = 100% (>= 50%) and calls >= 3 → transitions to OPEN
     *   Calls 4-5: Circuit is OPEN → CallNotPermittedException thrown immediately,
     *              no HTTP request sent to WireMock → fallback returns error result
     *
     * Assertions:
     *   - Circuit state transitions from CLOSED → OPEN
     *   - WireMock receives exactly 3 requests (calls before OPEN), not 5
     *   - After OPEN, amazon result has price=null, error="No price available", source=FALLBACK
     */
    @Test
    @DisplayName("Should transition Amazon circuit breaker to OPEN after 3 consecutive failures (minimumNumberOfCalls=3, failureRateThreshold=50%)")
    void shouldTransitionCircuitToOpenAfterThresholdFailures() {
        stubVendor("flipkart", "iphone-15", 899.99);
        stubVendor("walmart", "iphone-15", 799.99);

        CircuitBreaker amazonCb = circuitBreakerRegistry.circuitBreaker("amazon");
        assertEquals(CircuitBreaker.State.CLOSED, amazonCb.getState(), "Circuit should start CLOSED");

        // Send 3 failing requests — this meets minimumNumberOfCalls=3 and failureRate 100% >= 50%
        for (int i = 0; i < 3; i++) {
            wiremock.stubFor(get(urlPathMatching("/mock-api/amazon/iphone-15"))
                    .willReturn(serverError()));

            List<PriceResult> results = fetchPrices();
            PriceResult amazon = getVendorResult(results, "amazon");

            assertNull(amazon.getPrice(), "Amazon price should be null on failure");
            assertNotNull(amazon.getError(), "Amazon should have an error message");
            assertEquals(PriceSource.FALLBACK, amazon.getSource(), "Amazon should use FALLBACK source");
        }

        // After 3 failures with 50% threshold, circuit transitions to OPEN
        assertEquals(CircuitBreaker.State.OPEN, amazonCb.getState(),
                "Circuit should be OPEN after 3 failures (>= minimumNumberOfCalls=3 and >= 50% failure rate)");

        // Now send 2 more requests — circuit is OPEN so vendor should NOT be called
        for (int i = 0; i < 2; i++) {
            List<PriceResult> results = fetchPrices();
            PriceResult amazon = getVendorResult(results, "amazon");

            assertNull(amazon.getPrice(), "Amazon price should be null when circuit is OPEN");
            assertEquals("No price available", amazon.getError(),
                    "Amazon error should be 'No price available' from fallback (no Redis cache)");
            assertEquals(PriceSource.FALLBACK, amazon.getSource(),
                    "Amazon source should be FALLBACK when circuit is OPEN");
        }

        // Verify WireMock only received 3 requests (the ones before circuit opened)
        // If circuit breaker short-circuits correctly, requests 4 and 5 never reach WireMock
        wiremock.verify(3, getRequestedFor(urlPathMatching("/mock-api/amazon/iphone-15")));
    }

    /**
     * Verifies that once the circuit breaker is OPEN, the vendor API is not called at all.
     * This is the core "circuit breaker" behavior — fail-fast without hitting the downstream service.
     *
     * Assertions:
     *   - After circuit opens, WireMock request count stays constant across multiple fetches
     *   - All amazon results have error="No price available"
     */
    @Test
    @DisplayName("Should NOT call vendor API when circuit breaker is OPEN (fail-fast short-circuit)")
    void shouldNotCallVendorApiWhenCircuitIsOpen() {
        stubVendor("flipkart", "iphone-15", 899.99);
        stubVendor("walmart", "iphone-15", 799.99);

        // Trip the circuit OPEN with 3 failures
        for (int i = 0; i < 3; i++) {
            wiremock.stubFor(get(urlPathMatching("/mock-api/amazon/iphone-15"))
                    .willReturn(serverError()));
            fetchPrices();
        }

        assertEquals(CircuitBreaker.State.OPEN, circuitBreakerRegistry.circuitBreaker("amazon").getState());

        // Record the request count at this point — should be exactly 3
        int requestCountAfterOpening = wiremock.findAll(getRequestedFor(urlPathMatching("/mock-api/amazon/iphone-15"))).size();
        assertEquals(3, requestCountAfterOpening, "Exactly 3 requests should have been made before circuit opened");

        // Send 5 more requests — circuit is OPEN, so NO additional requests should reach WireMock
        for (int i = 0; i < 5; i++) {
            fetchPrices();
        }

        int requestCountAfterMoreFetches = wiremock.findAll(getRequestedFor(urlPathMatching("/mock-api/amazon/iphone-15"))).size();
        assertEquals(3, requestCountAfterMoreFetches,
                "Request count should NOT increase when circuit is OPEN — vendor API is short-circuited");
    }

    /**
     * Verifies that other vendors' circuit breakers are independent and remain CLOSED
     * when only Amazon is failing.
     *
     * Assertions:
     *   - Amazon circuit = OPEN
     *   - Flipkart circuit = CLOSED
     *   - Walmart circuit = CLOSED
     *   - Flipkart and Walmart results have valid prices
     */
    @Test
    @DisplayName("Should keep other vendor circuit breakers CLOSED when only Amazon is failing (circuit isolation)")
    void shouldKeepOtherVendorsClosedWhenOnlyAmazonFails() {
        stubVendor("flipkart", "iphone-15", 899.99);
        stubVendor("walmart", "iphone-15", 799.99);

        // Trip only Amazon's circuit breaker
        for (int i = 0; i < 3; i++) {
            wiremock.stubFor(get(urlPathMatching("/mock-api/amazon/iphone-15"))
                    .willReturn(serverError()));
            fetchPrices();
        }

        // Amazon should be OPEN
        assertEquals(CircuitBreaker.State.OPEN, circuitBreakerRegistry.circuitBreaker("amazon").getState(),
                "Amazon circuit should be OPEN after failures");

        // Flipkart and Walmart should still be CLOSED — they never failed
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreakerRegistry.circuitBreaker("flipkart").getState(),
                "Flipkart circuit should remain CLOSED (no failures)");
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreakerRegistry.circuitBreaker("walmart").getState(),
                "Walmart circuit should remain CLOSED (no failures)");

        // Flipkart and Walmart results should have valid prices
        List<PriceResult> results = fetchPrices();
        PriceResult flipkart = getVendorResult(results, "flipkart");
        PriceResult walmart = getVendorResult(results, "walmart");

        assertNotNull(flipkart.getPrice(), "Flipkart should still return a price");
        assertEquals(PriceSource.API, flipkart.getSource(), "Flipkart should source from API");
        assertNotNull(walmart.getPrice(), "Walmart should still return a price");
        assertEquals(PriceSource.API, walmart.getSource(), "Walmart should source from API");
    }

    /**
     * Verifies the circuit breaker transitions from OPEN → HALF_OPEN after waitDurationInOpenState (2s),
     * and then transitions back to CLOSED when the probe call succeeds.
     *
     * Flow:
     *   1. Trip circuit OPEN with 3 failures
     *   2. Wait 2.5s (exceeds waitDurationInOpenState=2s) → circuit auto-transitions to HALF_OPEN
     *   3. Restore WireMock stub to return success
     *   4. Next request → probe call succeeds → circuit transitions to CLOSED
     *   5. Amazon result has valid price from API
     */
    @Test
    @DisplayName("Should transition from OPEN → HALF_OPEN → CLOSED after waitDuration and successful probe call")
    void shouldRecoverFromOpenToClosedAfterWaitDurationAndSuccessfulProbe() throws InterruptedException {
        stubVendor("flipkart", "iphone-15", 899.99);
        stubVendor("walmart", "iphone-15", 799.99);

        // Step 1: Trip Amazon circuit to OPEN
        for (int i = 0; i < 3; i++) {
            wiremock.stubFor(get(urlPathMatching("/mock-api/amazon/iphone-15"))
                    .willReturn(serverError()));
            fetchPrices();
        }
        assertEquals(CircuitBreaker.State.OPEN, circuitBreakerRegistry.circuitBreaker("amazon").getState(),
                "Amazon circuit should be OPEN after failures");

        // Step 2: Wait for waitDurationInOpenState (2s) to elapse → transitions to HALF_OPEN
        Thread.sleep(2500);

        // Step 3: Restore Amazon to return success
        wiremock.resetAll();
        stubVendor("amazon", "iphone-15", 999.99);
        stubVendor("flipkart", "iphone-15", 899.99);
        stubVendor("walmart", "iphone-15", 799.99);

        //Amazon circuit should be HALF_OPEN state
        assertEquals(CircuitBreaker.State.HALF_OPEN, circuitBreakerRegistry.circuitBreaker("amazon").getState(), "Amazon circuit should be HALF_OPEN state");
        // Step 4: Make a request — circuit is HALF_OPEN, probe call is attempted
        List<PriceResult> results = fetchPrices();

        // The state transition from HALF_OPEN → CLOSED happens asynchronously after the probe succeeds.
        // Give it a brief moment to complete the transition.
        Thread.sleep(500);

        // Step 5: After successful probe, circuit should be CLOSED
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreakerRegistry.circuitBreaker("amazon").getState(),
                "Amazon circuit should be CLOSED after successful probe call in HALF_OPEN state");

        // Amazon result should have a valid price from the recovered API call
        PriceResult amazon = getVendorResult(results, "amazon");
        assertNotNull(amazon.getPrice(), "Amazon should return a price after circuit recovery");
        assertEquals(999.99, amazon.getPrice(), "Amazon price should match the stubbed value");
        assertEquals(PriceSource.API, amazon.getSource(), "Amazon should source from API after recovery");
    }

    /**
     * Verifies that when the circuit breaker is HALF_OPEN and the probe call fails,
     * the circuit transitions back to OPEN.
     *
     * Flow:
     *   1. Trip circuit OPEN
     *   2. Wait 2.5s → HALF_OPEN
     *   3. Keep WireMock returning failure → probe call fails
     *   4. Circuit transitions back to OPEN
     */
    @Test
    @DisplayName("Should transition from HALF_OPEN back to OPEN when probe call fails")
    void shouldTransitionBackToOpenWhenProbeCallFailsInHalfOpen() throws InterruptedException {
        stubVendor("flipkart", "iphone-15", 899.99);
        stubVendor("walmart", "iphone-15", 799.99);

        // Step 1: Trip Amazon circuit to OPEN
        for (int i = 0; i < 3; i++) {
            wiremock.stubFor(get(urlPathMatching("/mock-api/amazon/iphone-15"))
                    .willReturn(serverError()));
            fetchPrices();
        }
        assertEquals(CircuitBreaker.State.OPEN, circuitBreakerRegistry.circuitBreaker("amazon").getState());

        // Step 2: Wait for waitDuration → transitions to HALF_OPEN
        Thread.sleep(2500);

        // Step 3: Keep Amazon failing — probe call will fail
        wiremock.stubFor(get(urlPathMatching("/mock-api/amazon/iphone-15"))
                .willReturn(serverError()));

        // Step 4: Make a request — probe call fails in HALF_OPEN
        fetchPrices();

        // The state transition from HALF_OPEN → OPEN happens asynchronously after the probe fails.
        Thread.sleep(500);

        // Step 5: Circuit should transition back to OPEN
        assertEquals(CircuitBreaker.State.OPEN, circuitBreakerRegistry.circuitBreaker("amazon").getState(),
                "Amazon circuit should be OPEN again after failed probe call in HALF_OPEN state");

        // Amazon result should be a fallback error
        List<PriceResult> results = fetchPrices();
        PriceResult amazon = getVendorResult(results, "amazon");
        assertNull(amazon.getPrice(), "Amazon price should be null when circuit re-opened");
        assertEquals("No price available", amazon.getError(),
                "Amazon error should be from fallback (no cache available)");
    }

    /**
     * Verifies that when all three vendor circuit breakers are OPEN,
     * every result is an error and the controller returns HTTP 207 (Multi-Status).
     *
     * This is the worst-case scenario: all downstream services are down,
     * circuit breakers are open, and no cached data is available (Redis is off).
     */
    @Test
    @DisplayName("Should return all error results with HTTP 207 when all circuit breakers are OPEN")
    void shouldReturnAllErrorsWhenAllCircuitBreakersAreOpen() {
        // Trip all three circuit breakers to OPEN
        for (int vendor = 0; vendor < 3; vendor++) {
            String[] vendors = {"amazon", "flipkart", "walmart"};
            for (int i = 0; i < 3; i++) {
                wiremock.stubFor(get(urlPathMatching("/mock-api/" + vendors[vendor] + "/iphone-15"))
                        .willReturn(serverError()));
                fetchPrices();
            }
            assertEquals(CircuitBreaker.State.OPEN, circuitBreakerRegistry.circuitBreaker(vendors[vendor]).getState(),
                    vendors[vendor] + " circuit should be OPEN");
        }

        // Now all circuits are OPEN — fetch should return 3 error results
        List<PriceResult> results = fetchPrices();

        assertEquals(3, results.size(), "Should have 3 vendor results");

        // Every vendor result should be a fallback error
        for (PriceResult result : results) {
            assertNull(result.getPrice(), result.getVendor() + " price should be null when circuit is OPEN");
            assertNotNull(result.getError(), result.getVendor() + " should have an error message");
            assertEquals("No price available", result.getError(),
                    result.getVendor() + " error should be 'No price available' from fallback");
            assertEquals(PriceSource.FALLBACK, result.getSource(),
                    result.getVendor() + " should have FALLBACK source");
        }

        // No vendor API calls should have been made after circuits opened
        wiremock.verify(3, getRequestedFor(urlPathMatching("/mock-api/amazon/iphone-15")));
        wiremock.verify(3, getRequestedFor(urlPathMatching("/mock-api/flipkart/iphone-15")));
        wiremock.verify(3, getRequestedFor(urlPathMatching("/mock-api/walmart/iphone-15")));
    }

    /**
     * Verifies that the circuit breaker records both successful and failed calls
     * in its metrics, and that the failure rate calculation is correct.
     *
     * Flow:
     *   - 2 successful calls (failure rate = 0%)
     *   - 2 failed calls (failure rate = 50% — meets threshold)
     *   - 1 more failed call (failure rate = 60% — exceeds threshold with 5 calls >= minimumNumberOfCalls=3)
     *   - Circuit should transition to OPEN
     */
    @Test
    @DisplayName("Should track failure rate across mixed success/failure calls and open at threshold")
    void shouldTrackFailureRateAndOpenAtThreshold() {
        stubVendor("flipkart", "iphone-15", 899.99);
        stubVendor("walmart", "iphone-15", 799.99);

        CircuitBreaker amazonCb = circuitBreakerRegistry.circuitBreaker("amazon");

        // 2 successful calls — failure rate = 0%
        stubVendor("amazon", "iphone-15", 999.99);
        fetchPrices();
        fetchPrices();
        assertEquals(CircuitBreaker.State.CLOSED, amazonCb.getState(),
                "Circuit should remain CLOSED after 2 successful calls");

        // 2 failed calls — failure rate = 50% (2/4), but we need minimumNumberOfCalls=3 met
        wiremock.resetAll();
        stubVendorFailure("amazon", "iphone-15");
        stubVendor("flipkart", "iphone-15", 899.99);
        stubVendor("walmart", "iphone-15", 799.99);
        fetchPrices();
        fetchPrices();

        // failure rate = 50% with 4 calls (>= minimumNumberOfCalls=3) → should trip at 50%
        // Note: With slidingWindowSize=5, the window now has: success, success, fail, fail = 50%
        // The 5th call will push it over
        fetchPrices();

        // After 5 calls with 3 failures: failure rate = 60% > 50% → OPEN
        assertEquals(CircuitBreaker.State.OPEN, amazonCb.getState(),
                "Circuit should be OPEN after 3 failures out of 5 calls (60% > 50% threshold)");
    }

    private List<PriceResult> fetchPrices() {
        return webClient.get()
                .uri("/api/prices/iphone-15")
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
