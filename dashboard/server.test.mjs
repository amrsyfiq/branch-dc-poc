// Tests for the dashboard's proxy (server.mjs) against fake upstreams. Run: npm run test:server
import assert from 'node:assert/strict';
import { createServer as createHttpServer, request } from 'node:http';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { after, before, beforeEach, describe, it } from 'node:test';
import { createServer } from './server.mjs';

/** A fake upstream that records requests and answers with a scripted handler. */
function fake(handler) {
  const calls = [];
  const server = createHttpServer((req, res) => {
    let body = '';
    req.on('data', (c) => { body += c; });
    req.on('end', () => {
      calls.push({ method: req.method, url: req.url, headers: req.headers, body });
      const [status, payload] = handler(req, body, calls.length);
      res.writeHead(status, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(payload));
    });
  });
  return { server, calls, url: () => `http://127.0.0.1:${server.address().port}` };
}

const listen = (server) => new Promise((r) => server.listen(0, '127.0.0.1', r));

/** Raw request, so paths like /../ are sent as-is (fetch would normalise them). */
function send(port, method, path) {
  return new Promise((resolve, reject) => {
    const req = request({ host: '127.0.0.1', port, method, path }, (res) => {
      let body = '';
      res.on('data', (c) => { body += c; });
      res.on('end', () => resolve({ status: res.statusCode, body, json: () => JSON.parse(body) }));
    });
    req.on('error', reject);
    req.end();
  });
}

describe('dashboard server', () => {
  let tokensIssued = 0;
  let gatewayAnswers = []; // scripted statuses for the next gateway calls
  const dc = fake((req) => [req.method === 'POST' ? 202 : 200, { from: 'dc', path: req.url }]);
  const wso2 = fake((req) => {
    if (req.url === '/oauth2/token') return [200, { access_token: `token-${++tokensIssued}`, expires_in: 3600 }];
    return [gatewayAnswers.shift() ?? 200, { site: 'A', passes: 0 }];
  });
  let proxy;
  let port;

  before(async () => {
    await Promise.all([listen(dc.server), listen(wso2.server)]);
    const staticDir = mkdtempSync(join(tmpdir(), 'dash-'));
    writeFileSync(join(staticDir, 'index.html'), '<app-root></app-root>');
    proxy = createServer({
      dcUrl: dc.url(),
      sites: {
        a: { tokenUrl: `${wso2.url()}/oauth2/token`, gatewayUrl: `${wso2.url()}/branch-a/1.0.0`,
          clientId: 'dash-a', clientSecret: 'secret-a', scope: 'branch_a:read' },
        b: { tokenUrl: 'http://127.0.0.1:1/oauth2/token', gatewayUrl: 'http://127.0.0.1:1/branch-b/1.0.0' }, // no client
      },
      staticDir,
    });
    await listen(proxy);
    port = proxy.address().port;
  });

  after(() => [proxy, dc.server, wso2.server].forEach((s) => s.close()));

  beforeEach(() => {
    dc.calls.length = 0;
    wso2.calls.length = 0;
    gatewayAnswers = [];
  });

  it('forwards the DC routes', async () => {
    assert.deepEqual((await send(port, 'GET', '/api/dc/summary')).json(), { from: 'dc', path: '/summary' });
    assert.equal((await send(port, 'GET', '/api/dc/data?branch=a&size=5')).json().path, '/data?branch=A&page=0&size=5');
    assert.deepEqual((await send(port, 'GET', '/api/dc/sync-log?limit=5')).json().path, '/sync-log?limit=5');
    const sync = await send(port, 'POST', '/api/dc/sync/a');
    assert.equal(sync.status, 202);
    assert.equal(sync.json().path, '/sync/A');
    assert.equal((await send(port, 'POST', '/api/dc/purge/b')).json().path, '/purge/B');
    assert.equal((await send(port, 'POST', '/api/dc/rush/a?count=500')).json().path, '/rush/A?count=500');
  });

  it('keeps the numbers it forwards within limits', async () => {
    assert.equal((await send(port, 'POST', '/api/dc/rush/a?count=999999')).json().path, '/rush/A?count=20000');
    assert.equal((await send(port, 'POST', '/api/dc/rush/a?count=1;DROP')).json().path, '/rush/A?count=1000');
    assert.equal((await send(port, 'GET', '/api/dc/data?branch=b&size=5000')).json().path, '/data?branch=B&page=0&size=50');
    assert.equal((await send(port, 'GET', '/api/dc/sync-log?limit=-3')).json().path, '/sync-log?limit=20');
  });

  it('forwards nothing else (not an open proxy)', async () => {
    for (const [method, path] of [
      ['GET', '/api/dc/../admin'], ['DELETE', '/api/dc/data'], ['POST', '/api/dc/sync/c'], ['GET', '/api/dc/sync/a'],
      ['GET', '/api/dc/data'], ['GET', '/api/dc/data?branch=c'], ['GET', '/api/dc/rush/a'],
      ['GET', '/api/branches/c/data'], ['POST', '/api/branches/a/data'], ['GET', '/api/branches/a/events'],
      ['POST', '/api/branches/a/summary'], ['POST', '/api/branches/a/passes/search'],
      ['GET', '/api/dc/sync-log?limit=1&x=http://evil'],
    ]) {
      const res = await send(port, method, path);
      if (path.startsWith('/api/dc/sync-log')) {
        assert.equal(dc.calls.at(-1).url, '/sync-log?limit=1', 'extra query parameters are dropped');
        continue;
      }
      assert.equal(res.status, 404, `${method} ${path}`);
    }
    assert.equal(wso2.calls.length, 0);
    assert.equal(dc.calls.length, 1); // only the sync-log call above
  });

  it('reads a branch through its gateway with a cached client-credentials token', async () => {
    tokensIssued = 0;
    const first = await send(port, 'GET', '/api/branches/a/data');
    const second = await send(port, 'GET', '/api/branches/A/summary?from=10&to=20');
    const third = await send(port, 'GET', '/api/branches/a/summary?from=x&to=20'); // not a range: plain summary
    assert.equal(first.status, 200);
    assert.equal(second.status, 200);
    assert.equal(third.status, 200);

    const [tokenCall, ...gatewayCalls] = wso2.calls;
    assert.equal(tokenCall.url, '/oauth2/token');
    assert.equal(tokenCall.headers.authorization, `Basic ${Buffer.from('dash-a:secret-a').toString('base64')}`);
    assert.equal(new URLSearchParams(tokenCall.body).get('scope'), 'branch_a:read');
    assert.equal(new URLSearchParams(tokenCall.body).get('grant_type'), 'client_credentials');
    assert.deepEqual(gatewayCalls.map((c) => [c.url, c.headers.authorization]), [
      ['/branch-a/1.0.0/data?page=0&size=5', 'Bearer token-1'],
      ['/branch-a/1.0.0/summary?from=10&to=20', 'Bearer token-1'], // cached: no second token call
      ['/branch-a/1.0.0/summary', 'Bearer token-1'],
    ]);
  });

  it('gets a new token once when the gateway answers 401', async () => {
    tokensIssued = 10;
    await send(port, 'GET', '/api/branches/a/data'); // may reuse a cached token
    wso2.calls.length = 0;
    gatewayAnswers = [401, 200];

    const res = await send(port, 'GET', '/api/branches/a/data');
    assert.equal(res.status, 200);
    assert.deepEqual(wso2.calls.map((c) => c.url),
      ['/branch-a/1.0.0/data?page=0&size=5', '/oauth2/token', '/branch-a/1.0.0/data?page=0&size=5']);
    assert.notEqual(wso2.calls[0].headers.authorization, wso2.calls[2].headers.authorization);
  });

  it('passes gateway refusals through and explains missing setup or unreachable upstreams', async () => {
    gatewayAnswers = [403];
    assert.equal((await send(port, 'GET', '/api/branches/a/data')).status, 403);

    const noClient = await send(port, 'GET', '/api/branches/b/data');
    assert.equal(noClient.status, 503);
    assert.match(noClient.json().detail, /Run: node infra\/wso2\/setup\.mjs/);

    const down = createServer({ dcUrl: 'http://127.0.0.1:1', sites: {} });
    await listen(down);
    const res = await send(down.address().port, 'GET', '/api/dc/summary');
    down.close();
    assert.equal(res.status, 502);
    assert.match(res.json().detail, /Upstream not reachable: ECONNREFUSED/);
  });

  it('serves the app and never files outside it', async () => {
    assert.equal((await send(port, 'GET', '/')).body, '<app-root></app-root>');
    assert.equal((await send(port, 'GET', '/some/route')).body, '<app-root></app-root>'); // single-page app
    assert.equal((await send(port, 'GET', '/..%2f..%2fpackage.json')).status, 403);
  });
});
