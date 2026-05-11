import { check, sleep } from 'k6';
import http from 'k6/http';
import { BASE_URL, DEFAULT_HEADERS, pickAnyProductId } from './common/config.js';

export const options = {
  vus: 1,
  duration: '30s',
  thresholds: {
    http_req_duration: ['p(95)<100'],
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.95'],
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
    'has trace-id header': (r) => r.headers['X-Trace-Id'] !== undefined,
    'response time < 100ms': (r) => r.timings.duration < 210,
  });

  sleep(0.5);
}
