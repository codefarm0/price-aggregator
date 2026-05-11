import { check, sleep } from 'k6';
import http from 'k6/http';
import { BASE_URL, DEFAULT_HEADERS, pickAnyProductId } from './common/config.js';

export const options = {
  vus: 50,
  duration: '5m',
  thresholds: {
    http_req_duration: [
      { threshold: 'p(50)<150', abortOnFail: false },
      { threshold: 'p(95)<500', abortOnFail: false },
      { threshold: 'p(99)<1500', abortOnFail: false },
    ],
    http_req_failed: [
      { threshold: 'rate<0.005', abortOnFail: true },
    ],
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
  });

  sleep(2);
}
