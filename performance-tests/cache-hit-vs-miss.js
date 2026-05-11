import { check, group, sleep } from 'k6';
import http from 'k6/http';
import { BASE_URL, DEFAULT_HEADERS, REFRESH_CACHE_HEADER, pickProductId } from './common/config.js';

export const options = {
  stages: [
    { duration: '10s', target: 5 },
    { duration: '1m', target: 10 },
  ],
  thresholds: {
    'http_req_duration{group:::Cache Hit}': [
      { threshold: 'p(95)<10', abortOnFail: false },
    ],
    'http_req_duration{group:::Cache Miss}': [
      { threshold: 'p(95)<500', abortOnFail: false },
    ],
    http_req_failed: [
      { threshold: 'rate<0.01', abortOnFail: false },
    ],
    checks: ['rate>0.95'],
  },
};

export default function () {
  // Use 80/20 distribution to simulate realistic cache patterns
  const productId = pickProductId();

  group('Cache Hit', function () {
    const res = http.get(`${BASE_URL}/api/prices/${productId}`, {
      headers: DEFAULT_HEADERS,
      tags: { name: 'prices-cache-hit' },
    });

    check(res, {
      'status is 200': (r) => r.status === 200,
      'response time < 10ms': (r) => r.timings.duration < 10,
    });

    sleep(1);
  });

  group('Cache Miss', function () {
    const res = http.get(`${BASE_URL}/api/prices/${productId}`, {
      headers: REFRESH_CACHE_HEADER,
      tags: { name: 'prices-cache-miss' },
    });

    check(res, {
      'status is 200': (r) => r.status === 200,
      'response time < 500ms': (r) => r.timings.duration < 500,
    });

    sleep(2);
  });
}
