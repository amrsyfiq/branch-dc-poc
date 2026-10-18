// k6 load test: counter lookups at checkpoint A through its WSO2 gateway, as the officers' terminals do in a
// holiday rush. Run by k8s/load-test.mjs as a Job inside branch-a (a port-forward always reaches one pod).
//
// Arrival rate, not a fixed number of users: travellers arrive at the counters whether the system keeps up or
// not. If it doesn't, k6 reports "dropped_iterations" and the latency grows.
import encoding from 'k6/encoding';
import http from 'k6/http';
import { check, fail } from 'k6';

const GATEWAY = __ENV.GATEWAY_URL;
const RATE = Number(__ENV.RATE || 40);
const HOLD = __ENV.HOLD || '4m';

export const options = {
  scenarios: {
    rush: {
      executor: 'ramping-arrival-rate',
      startRate: 1,
      timeUnit: '1s',
      preAllocatedVUs: 20,
      maxVUs: 200,
      stages: [
        { target: RATE, duration: '30s' }, // the queue builds up
        { target: RATE, duration: HOLD }, // the rush
        { target: 0, duration: '10s' },
      ],
    },
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

/** Once: a client-credentials token (read scope only) and the passport numbers known at this checkpoint. */
export function setup() {
  const basic = encoding.b64encode(`${__ENV.CLIENT_ID}:${__ENV.CLIENT_SECRET}`);
  const token = http.post(__ENV.TOKEN_URL, { grant_type: 'client_credentials', scope: __ENV.SCOPE },
    { headers: { Authorization: `Basic ${basic}` } });
  if (token.status !== 200) fail(`token endpoint answered ${token.status}`);
  const auth = { Authorization: `Bearer ${token.json('access_token')}` };

  const page = http.get(`${GATEWAY}/data?page=0&size=200`, { headers: auth });
  if (page.status !== 200) fail(`GET /data answered ${page.status}`);
  const passports = page.json('passes.items').map((p) => p.passportNo);
  if (passports.length === 0) fail('the checkpoint has no passes: sync it first (dashboard or POST /sync/A)');
  console.log(`setup: ${passports.length} passport numbers to look up`); // the count only: no personal data in logs
  return { auth, passports };
}

export default function ({ auth, passports }) {
  const passportNo = passports[Math.floor(Math.random() * passports.length)];
  const res = http.post(`${GATEWAY}/passes/search`, JSON.stringify({ passportNo }), {
    headers: { ...auth, 'Content-Type': 'application/json' },
    tags: { name: 'POST /passes/search' }, // one name for all lookups in the summary
  });
  check(res, {
    'status 200': (r) => r.status === 200,
    'pass found': (r) => r.status === 200 && r.json('matches').length > 0,
  });
}
