import { check, sleep } from 'k6';
import http from 'k6/http';
import { BASE_URL, DEFAULT_HEADERS, pickAnyProductId } from './common/config.js';

export const options = {
  stages: [
    { duration: '30s', target: 10 },
    { duration: '1m', target: 50 },
    { duration: '30s', target: 100 },
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    http_req_duration: [
      { threshold: 'p(50)<200', abortOnFail: false },
      { threshold: 'p(95)<500', abortOnFail: false },
      { threshold: 'p(99)<2000', abortOnFail: false },
    ],
    http_req_failed: [
      { threshold: 'rate<0.01', abortOnFail: false },
    ],
    checks: ['rate>0.9'],
  },
};

export default function () {
  const productId = pickAnyProductId();
  const res = http.get(`${BASE_URL}/api/prices/${productId}`, {
    headers: DEFAULT_HEADERS,
    tags: { name: 'prices' },
  });

  check(res, {
    'status is 200 or 207': (r) => r.status === 200 || r.status === 207,
    'body contains data': (r) => r.body.length > 0,
    'response time < 500ms': (r) => r.timings.duration < 500,
  });

  sleep(1);
}
