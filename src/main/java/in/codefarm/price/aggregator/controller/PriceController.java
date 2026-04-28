package in.codefarm.price.aggregator.controller;

import in.codefarm.price.aggregator.dto.PriceResult;
import in.codefarm.price.aggregator.service.PriceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// localhost:8080/api/prices/iphone
@RestController
@RequestMapping("/api/prices")
public class PriceController {

    private static final Logger log = LoggerFactory.getLogger(PriceController.class);
    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    private final PriceService priceService;

    public PriceController(PriceService priceService) {
        this.priceService = priceService;
    }

    @GetMapping("/{productId}")
    public ResponseEntity<List<PriceResult>> getPrices(
            @PathVariable String productId,
            @RequestHeader(value = "X-Refresh-Cache", defaultValue = "false") boolean refreshCache) {

        log.info("GET /api/prices/{} refreshCache={}", productId, refreshCache);

        List<PriceResult> results = priceService.fetchPrices(productId, refreshCache);

        // Check if all vendors failed
        boolean allFailed = results.stream()
                .allMatch(r -> r.getPrice() == null || r.getError() != null);

        if (allFailed) {
            log.warn("All vendors failed for product={}, returning HTTP 207", productId);
            return ResponseEntity.status(HttpStatus.MULTI_STATUS)
                    .body(results);
        }

        return ResponseEntity.ok()
                .body(results);
    }
}
