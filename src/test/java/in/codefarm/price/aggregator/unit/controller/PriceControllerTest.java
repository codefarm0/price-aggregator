package in.codefarm.price.aggregator.unit.controller;

import in.codefarm.price.aggregator.controller.PriceController;
import in.codefarm.price.aggregator.dto.PriceResult;
import in.codefarm.price.aggregator.dto.PriceSource;
import in.codefarm.price.aggregator.service.PriceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PriceController Unit Tests")
class PriceControllerTest {

    private PriceService priceService;
    private PriceController controller;

    @BeforeEach
    void setUp() {
        priceService = mock(PriceService.class);
        controller = new PriceController(priceService);
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("Should return HTTP 200 when some vendors succeed")
    void shouldReturn200WhenSomeVendorsSucceed() {
        MDC.put("traceId", "test-trace-123");
        List<PriceResult> results = List.of(
                PriceResult.fromApi("amazon", 999.99, 1000L, "test-trace-123"),
                PriceResult.error("flipkart", "Service unavailable", "test-trace-123")
        );
        when(priceService.fetchPrices("iphone-15", false)).thenReturn(results);

        ResponseEntity<List<PriceResult>> response = controller.getPrices("iphone-15", false);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(2, response.getBody().size());
    }

    @Test
    @DisplayName("Should return HTTP 207 when all vendors fail with errors")
    void shouldReturn207WhenAllVendorsFail() {
        MDC.put("traceId", "test-trace-456");
        List<PriceResult> results = List.of(
                PriceResult.error("amazon", "Service unavailable", "test-trace-456"),
                PriceResult.error("flipkart", "Timeout", "test-trace-456"),
                PriceResult.error("walmart", "Connection refused", "test-trace-456")
        );
        when(priceService.fetchPrices("iphone-15", false)).thenReturn(results);

        ResponseEntity<List<PriceResult>> response = controller.getPrices("iphone-15", false);

        assertEquals(HttpStatus.MULTI_STATUS, response.getStatusCode());
        assertEquals(3, response.getBody().size());
        assertTrue(response.getBody().stream().allMatch(r -> r.getError() != null));
    }

    @Test
    @DisplayName("Should return HTTP 200 when all vendors succeed")
    void shouldReturn200WhenAllVendorsSucceed() {
        MDC.put("traceId", "test-trace-789");
        List<PriceResult> results = List.of(
                PriceResult.fromApi("amazon", 999.99, 1000L, "test-trace-789"),
                PriceResult.fromApi("flipkart", 899.99, 2000L, "test-trace-789"),
                PriceResult.fromApi("walmart", 799.99, 3000L, "test-trace-789")
        );
        when(priceService.fetchPrices("iphone-15", false)).thenReturn(results);

        ResponseEntity<List<PriceResult>> response = controller.getPrices("iphone-15", false);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(3, response.getBody().size());
    }

    @Test
    @DisplayName("Should pass refreshCache=true to service when header is set")
    void shouldPassRefreshCacheTrueToService() {
        MDC.put("traceId", "test-trace-refresh");
        List<PriceResult> results = List.of(
                PriceResult.fromApi("amazon", 999.99, 1000L, "test-trace-refresh")
        );
        when(priceService.fetchPrices("iphone-15", true)).thenReturn(results);

        ResponseEntity<List<PriceResult>> response = controller.getPrices("iphone-15", true);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(priceService).fetchPrices("iphone-15", true);
    }

    @Test
    @DisplayName("Should return HTTP 207 when price is null and error is null")
    void shouldReturn207WhenPriceIsNullAndErrorIsNull() {
        MDC.put("traceId", "test-trace-null");
        PriceResult result = new PriceResult("amazon", null, null, PriceSource.FALLBACK, "test-trace-null");
        List<PriceResult> results = List.of(result);
        when(priceService.fetchPrices("iphone-15", false)).thenReturn(results);

        ResponseEntity<List<PriceResult>> response = controller.getPrices("iphone-15", false);

        assertEquals(HttpStatus.MULTI_STATUS, response.getStatusCode());
    }
}
