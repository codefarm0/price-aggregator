package in.codefarm.price.aggregator.unit.service;

import in.codefarm.price.aggregator.dto.PriceResult;
import in.codefarm.price.aggregator.external.PriceAggregator;
import in.codefarm.price.aggregator.service.PriceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PriceService Unit Tests")
class PriceServiceTest {

    private final PriceAggregator amazonClient = mock(PriceAggregator.class);
    private final PriceAggregator flipkartClient = mock(PriceAggregator.class);
    private final PriceAggregator walmartClient = mock(PriceAggregator.class);
    private final Executor executor = Runnable::run;
    private PriceService priceService;

    @BeforeEach
    void setUp() {
        priceService = new PriceService(
                List.of(amazonClient, flipkartClient, walmartClient),
                executor,
                5000L
        );
    }

    @Test
    @DisplayName("Should fetch prices from all vendors and return results")
    void shouldFetchPricesFromAllVendors() {
        when(amazonClient.getPrice("iphone-15", false))
                .thenReturn(PriceResult.fromApi("amazon", 999.99, 1000L, "test-trace"));
        when(flipkartClient.getPrice("iphone-15", false))
                .thenReturn(PriceResult.fromApi("flipkart", 899.99, 2000L, "test-trace"));
        when(walmartClient.getPrice("iphone-15", false))
                .thenReturn(PriceResult.fromApi("walmart", 799.99, 3000L, "test-trace"));

        MDC.put("traceId", "test-trace");
        List<PriceResult> results = priceService.fetchPrices("iphone-15", false);

        assertEquals(3, results.size());
        assertTrue(results.stream().anyMatch(r -> r.getVendor().equals("amazon")));
        assertTrue(results.stream().anyMatch(r -> r.getVendor().equals("flipkart")));
        assertTrue(results.stream().anyMatch(r -> r.getVendor().equals("walmart")));
        MDC.clear();
    }

    @Test
    @DisplayName("Should pass refreshCache=true to all vendor clients")
    void shouldPassRefreshCacheTrueToVendorClients() {
        when(amazonClient.getPrice("iphone-15", true))
                .thenReturn(PriceResult.fromApi("amazon", 999.99, 1000L, "test-trace"));
        when(flipkartClient.getPrice("iphone-15", true))
                .thenReturn(PriceResult.fromApi("flipkart", 899.99, 2000L, "test-trace"));
        when(walmartClient.getPrice("iphone-15", true))
                .thenReturn(PriceResult.fromApi("walmart", 799.99, 3000L, "test-trace"));

        MDC.put("traceId", "test-trace");
        List<PriceResult> results = priceService.fetchPrices("iphone-15", true);

        assertEquals(3, results.size());
        verify(amazonClient).getPrice("iphone-15", true);
        verify(flipkartClient).getPrice("iphone-15", true);
        verify(walmartClient).getPrice("iphone-15", true);
        MDC.clear();
    }

    @Test
    @DisplayName("Should return error result when a vendor fails")
    void shouldReturnErrorResultWhenVendorFails() {
        when(amazonClient.getPrice("iphone-15", false))
                .thenReturn(PriceResult.fromApi("amazon", 999.99, 1000L, "test-trace"));
        when(flipkartClient.getPrice("iphone-15", false))
                .thenReturn(PriceResult.error("flipkart", "Service down", "test-trace"));
        when(walmartClient.getPrice("iphone-15", false))
                .thenReturn(PriceResult.fromApi("walmart", 799.99, 3000L, "test-trace"));

        MDC.put("traceId", "test-trace");
        List<PriceResult> results = priceService.fetchPrices("iphone-15", false);

        assertEquals(3, results.size());
        PriceResult flipkartResult = results.stream()
                .filter(r -> r.getVendor().equals("flipkart"))
                .findFirst()
                .orElseThrow();
        assertNotNull(flipkartResult.getError());
        MDC.clear();
    }
}
