import { SharedArray } from 'k6/data';

export const BASE_URL = __ENV.K6_BASE_URL || 'http://localhost:8080';
export const HEALTH_ENDPOINT = '/actuator/health';
export const CHAOS_RESET = '/mock-api/chaos/reset';
export const CHAOS_FAST_FAIL = '/mock-api/chaos/scenario/fast-failures';

// 80/20 product distribution: 20% "hot" products get 80% of traffic
const HOT_PRODUCTS = [
  'iphone-15', 'samsung-galaxy-s24', 'airpods-pro',
  'macbook-air-m3', 'pixel-8',
];
const COLD_PRODUCTS = [
  'kindle-paperwhite', 'echo-dot', 'fire-tv-stick', 'ring-doorbell',
  'kindle-oasis', 'fire-tablet', 'echo-show', 'ring-chime',
  'echo-buds', 'fire-stick-lite', 'kindle-scribe', 'echo-studio',
  'ring-spotlight-cam', 'echo-flex', 'fire-tablet-kids', 'echo-auto',
  'echo-dot-clock', 'fire-tv-cube', 'ring-alarm', 'echo-sub',
  'kindle-basic', 'echo-show-15', 'fire-tv-omni', 'ring-peek',
  'echo-pops', 'fire-tablet-max', 'kindle-colorsoft', 'echo-gang',
  'ring-camera-battery', 'echo-hub', 'fire-tv-amp', 'ring-intercom',
  'echo-buds-2', 'fire-tablet-hd', 'kindle-scribe-pen', 'echo-frames',
  'ring-bridge', 'fire-stick-4k', 'echo-show-8', 'kindle-voyage',
];
const PRODUCTS = [...HOT_PRODUCTS, ...COLD_PRODUCTS];

// Pre-loaded product ID pool shared across all VUs
export const productPool = new SharedArray('products', function () {
  return PRODUCTS;
});

// Pick a product ID with 80/20 distribution
export function pickProductId() {
  if (Math.random() < 0.8) {
    return HOT_PRODUCTS[Math.floor(Math.random() * HOT_PRODUCTS.length)];
  }
  return COLD_PRODUCTS[Math.floor(Math.random() * COLD_PRODUCTS.length)];
}

// Pick from the full pool (uniform distribution)
export function pickAnyProductId() {
  return productPool[Math.floor(Math.random() * productPool.length)];
}

export const DEFAULT_HEADERS = {
  'Content-Type': 'application/json',
  'X-Trace-Id': `k6-${__VU}-${Date.now()}`,
};

export const REFRESH_CACHE_HEADER = {
  ...DEFAULT_HEADERS,
  'X-Refresh-Cache': 'true',
};
