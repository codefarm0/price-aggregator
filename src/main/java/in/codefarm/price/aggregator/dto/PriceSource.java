package in.codefarm.price.aggregator.dto;

public enum PriceSource {
    CACHE,      // From Redis cache
    API,         // Fresh from 3rd party API
    FALLBACK     // Fallback when API fails (may be from cache too)
}
