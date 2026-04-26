package in.codefarm.price.aggregator.controller;

import in.codefarm.price.aggregator.service.PriceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// localhost:8080/api/prices/iphone
@RestController
@RequestMapping("/api/prices")
public class PriceController {

    private final PriceService priceService;

    public PriceController(PriceService priceService) {
        this.priceService = priceService;
    }

    @GetMapping("/{productId}")
    public Map<String, Double> getPrices(@PathVariable String productId) {
        return priceService.fetchPrices(productId);
    }
}