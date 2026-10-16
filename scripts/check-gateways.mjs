#!/usr/bin/env node
/**
 * Security matrix for the two branch gateways. Run with the whole stack up and WSO2 set up:
 *
 *   node scripts/check-gateways.mjs
 *
 * Through each gateway (from your machine, real tokens from that branch's WSO2):
 *   listener token -> POST /events 200, GET /data 403;  dashboard token -> GET /data 200, POST /events 403;
 *   no token / garbage token / the OTHER branch's token -> 401
 * Straight to the branch, around the gateway (from inside the branch's network, forged JWTs):
 *   no JWT 401; JWT signed with the OTHER branch's WSO2 key 401; signed with its own WSO2 key 200 (control);
 *   own key but read scope on POST /events 403; own key but expired 401
 *
 * The forged JWTs use the WSO2 private keys from infra/wso2/generated/ (local POC keys). The POST /events
 * calls send a purge dated 2000-01-01: older than the branch's data, so the branch records it as STALE and
 * changes nothing. Exit code = number of failed checks.
 */
import { createSign, randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { Agent, request } from 'node:https';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const SITES = { a: { mgmt: 9444, gateway: 8244 }, b: { mgmt: 9445, gateway: 8245 } };
const other = (x) => (x === 'a' ? 'b' : 'a');
const dockerEnv = { ...process.env, MSYS_NO_PATHCONV: '1' };
let failures = 0;
const privateKeys = {}; // per site, filled on first use by privateKey()

for (const x of Object.keys(SITES)) {
  const X = x.toUpperCase();
  const base = `/branch-${x}/1.0.0`;
  console.log(`\n== Branch ${X} through wso2-${x} (https://localhost:${SITES[x].gateway}${base})`);
  const listener = await token(x, 'listener');
  const dashboard = await token(x, 'dashboard');
  const foreign = await token(other(x), 'listener');
  const stale = staleEvent(X);

  expect('listener-' + x + ' token, POST /events', 200, await viaGateway(x, 'POST', '/events', listener, stale));
  expect('listener-' + x + ' token, GET /data', 403, await viaGateway(x, 'GET', '/data', listener));
  expect('dashboard-' + x + ' token, GET /data', 200, await viaGateway(x, 'GET', '/data', dashboard));
  expect('dashboard-' + x + ' token, POST /events', 403, await viaGateway(x, 'POST', '/events', dashboard, stale));
  expect('no token, GET /data', 401, await viaGateway(x, 'GET', '/data', null));
  expect('garbage token, GET /data', 401, await viaGateway(x, 'GET', '/data', 'not-a-token'));
  expect(`listener-${other(x)} token (from wso2-${other(x)}), POST /events`, 401,
    await viaGateway(x, 'POST', '/events', foreign, stale));

  console.log(`== Branch ${X} directly, around the gateway (from inside branch-${x}-net)`);
  const claims = { scope: `branch_${x}:sync`, 'http://wso2.org/claims/applicationname': 'forged' };
  expect('no X-JWT-Assertion, POST /events', 401, direct(x, null, stale));
  expect(`JWT signed by wso2-${other(x)}'s key, POST /events`, 401, direct(x, forge(other(x), claims), stale));
  expect(`JWT signed by wso2-${x}'s own key, POST /events (control)`, 200, direct(x, forge(x, claims), stale));
  expect(`own key, read scope only, POST /events`, 403, direct(x, forge(x, { ...claims, scope: `branch_${x}:read` }), stale));
  expect('own key, expired, POST /events', 401, direct(x, forge(x, claims, -600), stale));
}

console.log(failures === 0 ? '\nAll checks pass.' : `\n${failures} check(s) failed.`);
process.exit(failures);

// ---------------------------------------------------------------------------------------------

function expect(label, want, got) {
  const ok = got.status === want;
  if (!ok) failures++;
  const detail = ok ? '' : `   <- expected ${want}: ${got.body.slice(0, 160)}`;
  console.log(`  ${ok ? 'ok  ' : 'FAIL'}  ${String(got.status).padEnd(4)} ${label}${detail}`);
}

function staleEvent(branchId) {
  return JSON.stringify({ eventId: randomUUID(), type: 'PURGE', branchId, occurredAt: '2000-01-01T00:00:00Z', products: [] });
}

function cert(x) {
  return readFileSync(join(root, 'infra', 'wso2', 'generated', x, 'signing-cert.pem'), 'utf8');
}

function client(x, user) {
  const values = {};
  for (const line of readFileSync(join(root, 'infra', 'wso2', 'generated', x, `${user}.env`), 'utf8').split(/\r?\n/)) {
    const m = /^([A-Z_]+)=(.*)$/.exec(line);
    if (m) values[m[1]] = m[2];
  }
  return values;
}

function https(port, path, ca, { method = 'GET', headers = {}, body } = {}) {
  return new Promise((resolvePromise, reject) => {
    const req = request(`https://localhost:${port}${path}`, { method, headers, agent: new Agent({ ca }) }, (res) => {
      let data = '';
      res.on('data', (c) => { data += c; });
      res.on('end', () => resolvePromise({ status: res.statusCode, body: data }));
    });
    req.on('error', reject);
    if (body) req.write(body);
    req.end();
  });
}

async function token(x, user) {
  const c = client(x, user);
  const res = await https(SITES[x].mgmt, '/oauth2/token', cert(x), {
    method: 'POST',
    headers: {
      Authorization: `Basic ${Buffer.from(`${c.CLIENT_ID}:${c.CLIENT_SECRET}`).toString('base64')}`,
      'Content-Type': 'application/x-www-form-urlencoded',
    },
    // Ask for both scopes: WSO2 grants only what the application's owner role allows.
    body: new URLSearchParams({ grant_type: 'client_credentials', scope: `branch_${x}:sync branch_${x}:read` }).toString(),
  });
  if (res.status !== 200) throw new Error(`token for ${user}-${x}: HTTP ${res.status} ${res.body}`);
  return JSON.parse(res.body).access_token;
}

function viaGateway(x, method, path, bearer, body) {
  return https(SITES[x].gateway, `/branch-${x}/1.0.0${path}`, cert(x), {
    method,
    headers: { ...(bearer ? { Authorization: `Bearer ${bearer}` } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body,
  });
}

/** POST /events straight to the branch container, from a throwaway curl container in the branch's network. */
function direct(x, jwt, body) {
  const args = ['run', '--rm', '--network', `branch-dc_branch-${x}-net`, 'curlimages/curl:8.11.1',
    '-s', '-o', '/dev/null', '-w', '%{http_code}', '-X', 'POST', '-H', 'Content-Type: application/json',
    ...(jwt ? ['-H', `X-JWT-Assertion: ${jwt}`] : []), '--data', body, `http://branch-${x}:8080/events`];
  const status = Number(execFileSync('docker', args, { encoding: 'utf8', env: dockerEnv }).trim());
  return { status, body: '' };
}

/** The WSO2 instance's private signing key, read from its keystore (never written to disk). */
function privateKey(x) {
  privateKeys[x] ??= execFileSync('docker', ['run', '--rm', '-v', `${join(root, 'infra', 'wso2', 'generated', x)}:/in:ro`,
    '--entrypoint', 'sh', 'wso2/wso2am:4.7.0', '-c',
    'keytool -importkeystore -srckeystore /in/wso2carbon.jks -srcstorepass wso2carbon -srcalias wso2carbon ' +
    '-destkeystore /tmp/k.p12 -deststoretype PKCS12 -deststorepass wso2carbon >/dev/null 2>&1 && ' +
    'openssl pkcs12 -in /tmp/k.p12 -nocerts -nodes -passin pass:wso2carbon | openssl pkey'],
  { encoding: 'utf8', env: dockerEnv });
  return privateKeys[x];
}

/** A backend JWT like the gateway's, signed RS256 with wso2-x's key. */
function forge(x, extraClaims, expiresInSeconds = 300) {
  const now = Math.floor(Date.now() / 1000);
  const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');
  const unsigned = `${b64({ alg: 'RS256', typ: 'JWT' })}.${b64({
    iss: 'wso2.org/products/am', sub: 'forged', iat: now, exp: now + expiresInSeconds, ...extraClaims,
  })}`;
  const signature = createSign('RSA-SHA256').update(unsigned).sign(privateKey(x)).toString('base64url');
  return `${unsigned}.${signature}`;
}
