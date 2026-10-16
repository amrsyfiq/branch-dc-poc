#!/usr/bin/env node
/**
 * Configures both branch gateways (wso2-a, wso2-b). Safe to re-run: every step checks whether its
 * result already exists.
 *
 *   node infra/wso2/setup.mjs          (both WSO2 containers must be running)
 *   node infra/wso2/setup.mjs a        (only wso2-a)
 *
 * For each branch x, on that branch's own WSO2 (the same things you would click in the portals):
 *   1. Roles branch_sync / branch_read; users "listener" (branch_sync) and "dashboard" (branch_read)
 *   2. API "Branch<X>API": context /branch-x, version 1.0.0, backend http://branch-x:8080
 *        GET  /data    needs scope branch_x:read  (bound to role branch_read)
 *        POST /events  needs scope branch_x:sync  (bound to role branch_sync)
 *      Revision deployed to the gateway, lifecycle -> PUBLISHED (Publisher)
 *   3. Applications "listener-x" (owned by user listener) and "dashboard-x" (owned by user dashboard),
 *      subscribed to the API, with client-credentials keys (Developer Portal)
 *
 * Why two users: with the client-credentials grant, WSO2 grants a scope only if the application's
 * OWNER has a role bound to that scope. So the dashboard's client cannot obtain branch_x:sync even if
 * it asks for it, and the gateway answers 403 when it tries POST /events.
 *
 * Writes (git-ignored) infra/wso2/generated/<x>/listener.env and dashboard.env with each client's id
 * and secret. WSO2 shows a client secret only once, so the files from the first run are kept.
 * TLS: trusts exactly the instance's own certificate (generated/<x>/signing-cert.pem), nothing else.
 */
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { Agent } from 'node:https';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, '..', '..');
const env = loadDotEnv(join(repoRoot, '.env'));

const SITES = {
  a: { mgmtPort: 9444, gatewayPort: 8244 },
  b: { mgmtPort: 9445, gatewayPort: 8245 },
};
const ADMIN = 'admin';
const ADMIN_PASSWORD = required('WSO2_ADMIN_PASSWORD');
const USERS = [
  { name: 'listener', password: required('WSO2_LISTENER_PASSWORD'), roles: ['Internal/subscriber', 'branch_sync'] },
  { name: 'dashboard', password: required('WSO2_DASHBOARD_PASSWORD'), roles: ['Internal/subscriber', 'branch_read'] },
];

const selected = process.argv.slice(2).length ? process.argv.slice(2) : Object.keys(SITES);
for (const site of selected) {
  if (!SITES[site]) throw new Error(`Unknown branch "${site}" (expected one of ${Object.keys(SITES).join(', ')})`);
  await setUp(site, SITES[site]);
}

async function setUp(site, { mgmtPort, gatewayPort }) {
  const X = site.toUpperCase();
  const generated = join(here, 'generated', site);
  const certFile = join(generated, 'signing-cert.pem');
  if (!existsSync(certFile)) throw new Error(`${certFile} not found: run node infra/wso2/init-keys.mjs first`);
  const http = client(`https://localhost:${mgmtPort}`, readFileSync(certFile, 'utf8'));
  const api = {
    name: `Branch${X}API`,
    context: `/branch-${site}`,
    version: '1.0.0',
    backend: `http://branch-${site}:8080`,
    scopes: [
      { name: `branch_${site}:read`, description: `Read branch ${X}'s data`, roles: ['branch_read'] },
      { name: `branch_${site}:sync`, description: `Deliver DC events to branch ${X}`, roles: ['branch_sync'] },
    ],
    operations: [
      { verb: 'GET', target: '/data', scope: `branch_${site}:read` },
      { verb: 'POST', target: '/events', scope: `branch_${site}:sync` },
    ],
  };

  console.log(`\n######## wso2-${site} (https://localhost:${mgmtPort})`);
  await waitForServer(http);

  step('1. Roles and users');
  for (const role of ['branch_sync', 'branch_read']) {
    if (await soap(http, 'isExistingRole', `<ser:roleName>${role}</ser:roleName>`) === 'true') {
      log(`role ${role} exists`);
    } else {
      await soap(http, 'addRole', `<ser:roleName>${role}</ser:roleName>`);
      log(`role ${role} created`);
    }
  }
  for (const user of USERS) {
    if (await soap(http, 'isExistingUser', `<ser:userName>${user.name}</ser:userName>`) === 'true') {
      log(`user ${user.name} exists`);
      continue;
    }
    await soap(http, 'addUser', [
      `<ser:userName>${user.name}</ser:userName>`,
      `<ser:credential>${xml(user.password)}</ser:credential>`,
      ...user.roles.map((r) => `<ser:roleList>${r}</ser:roleList>`),
      '<ser:profileName>default</ser:profileName>',
      '<ser:requirePasswordChange>false</ser:requirePasswordChange>',
    ].join(''));
    log(`user ${user.name} created with roles ${user.roles.join(', ')}`);
  }

  step(`2. API ${api.name} ${api.version} -> ${api.backend}`);
  const setupClient = await registerClient(http, 'branch_setup_client');
  const adminToken = await passwordToken(http, setupClient, ADMIN, ADMIN_PASSWORD,
    'apim:api_view apim:api_create apim:api_publish apim:api_manage');
  const publisher = rest(http, '/api/am/publisher/v4', adminToken);

  const found = await publisher('GET', `/apis?query=${encodeURIComponent(`name:${api.name}`)}`);
  let created = found.list.find((a) => a.name === api.name && a.version === api.version);
  if (created) {
    log(`API exists (${created.id}), status ${created.lifeCycleStatus}`);
  } else {
    created = await publisher('POST', '/apis', {
      name: api.name,
      context: api.context,
      version: api.version,
      description: `Branch ${X}: the only way into the branch. Reached by the DC's listener ${site} and the dashboard.`,
      transport: ['https'],
      policies: ['Unlimited'],
      securityScheme: ['oauth2'],
      endpointConfig: {
        endpoint_type: 'http',
        production_endpoints: { url: api.backend },
        sandbox_endpoints: { url: api.backend },
      },
      scopes: api.scopes.map((s) => ({
        shared: false,
        scope: { name: s.name, displayName: s.name, description: s.description, bindings: s.roles },
      })),
      operations: api.operations.map((op) => ({
        target: op.target,
        verb: op.verb,
        authType: 'Application',
        throttlingPolicy: 'Unlimited',
        scopes: [op.scope],
      })),
    });
    log(`API created (${created.id})`);
  }
  if (created.lifeCycleStatus !== 'PUBLISHED') {
    const revision = await publisher('POST', `/apis/${created.id}/revisions`, { description: 'Initial revision (setup.mjs)' });
    await publisher('POST', `/apis/${created.id}/deploy-revision?revisionId=${revision.id}`, [
      { name: 'Default', vhost: 'localhost', displayOnDevportal: true },
    ]);
    log(`revision ${revision.displayName} deployed to gateway "Default"`);
    await publisher('POST', `/apis/change-lifecycle?apiId=${created.id}&action=Publish`);
    log('lifecycle -> PUBLISHED');
  }

  step('3. Applications, subscriptions and client-credentials keys');
  mkdirSync(generated, { recursive: true });
  for (const [user, scope] of [['listener', `branch_${site}:sync`], ['dashboard', `branch_${site}:read`]]) {
    const appName = `${user}-${site}`;
    const owner = USERS.find((u) => u.name === user);
    const token = await passwordToken(http, setupClient, owner.name, owner.password,
      'apim:subscribe apim:app_manage apim:sub_manage');
    const devportal = rest(http, '/api/am/devportal/v3', token);

    const apps = await devportal('GET', `/applications?query=${encodeURIComponent(appName)}`);
    let app = apps.list.find((a) => a.name === appName);
    if (app) {
      log(`${appName}: application exists (owner ${user})`);
    } else {
      app = await devportal('POST', '/applications', {
        name: appName, throttlingPolicy: 'Unlimited', tokenType: 'JWT',
        description: user === 'listener'
          ? `The DC's listener for branch ${X}: delivers events (POST /events)`
          : `Dashboard: reads branch ${X}'s data (GET /data)`,
      });
      log(`${appName}: application created (owner ${user})`);
    }

    const subs = await devportal('GET', `/subscriptions?applicationId=${app.applicationId}`);
    if (subs.list.some((s) => s.apiId === created.id)) {
      log(`${appName}: already subscribed`);
    } else {
      await waitForDevportalApi(devportal, created.id);
      await devportal('POST', '/subscriptions', { applicationId: app.applicationId, apiId: created.id, throttlingPolicy: 'Unlimited' });
      log(`${appName}: subscribed to ${api.name}`);
    }

    const keys = await devportal('GET', `/applications/${app.applicationId}/oauth-keys`);
    let prod = keys.list.find((k) => k.keyType === 'PRODUCTION');
    if (!prod) {
      prod = await devportal('POST', `/applications/${app.applicationId}/generate-keys`, {
        keyType: 'PRODUCTION', keyManager: 'Resident Key Manager',
        grantTypesToBeSupported: ['client_credentials'], validityTime: 3600,
      });
      log(`${appName}: client-credentials keys generated`);
    } else {
      log(`${appName}: keys exist`);
    }

    // The secret is returned in clear text only when the keys are generated; later reads mask it.
    const file = join(generated, `${user}.env`);
    const saved = existsSync(file) ? loadDotEnvFile(file) : {};
    let secret = prod.consumerSecret;
    if (!secret || secret.includes('*')) {
      secret = saved.CLIENT_ID === prod.consumerKey ? saved.CLIENT_SECRET : '';
    }
    if (!secret) {
      throw new Error(`${appName}: the client secret is unknown (WSO2 shows it only once). Delete the application ` +
        `in the Developer Portal (https://localhost:${mgmtPort}/devportal, user ${user}) and run this script again.`);
    }
    writeFileSync(file, [
      '# Written by infra/wso2/setup.mjs. Do not commit.',
      `CLIENT_ID=${prod.consumerKey}`,
      `CLIENT_SECRET=${secret}`,
      `SCOPE=${scope}`,
      '',
    ].join('\n'));
    log(`${appName}: ${join('infra', 'wso2', 'generated', site, `${user}.env`)}`);
  }

  step('Done');
  console.log(`   Gateway: https://localhost:${gatewayPort}${api.context}/${api.version}/data`);
}

// ---------------------------------------------------------------------------------------------
// helpers

/** fetch-like helper for one WSO2 instance that trusts only that instance's certificate. */
function client(base, cert) {
  const agent = new Agent({ ca: cert });
  return async (path, { method = 'GET', headers = {}, body } = {}) => {
    const { request } = await import('node:https');
    return new Promise((resolvePromise, reject) => {
      const req = request(base + path, { method, headers, agent }, (res) => {
        let data = '';
        res.setEncoding('utf8');
        res.on('data', (chunk) => { data += chunk; });
        res.on('end', () => resolvePromise({ status: res.statusCode, ok: res.statusCode < 300, text: data }));
      });
      req.on('error', reject);
      if (body) req.write(body);
      req.end();
    });
  };
}

async function waitForServer(http) {
  for (let i = 0; i < 40; i++) {
    try {
      if ((await http('/api/am/publisher/v4/settings')).ok) return;
    } catch {
      // not up yet
    }
    if (i === 0) console.log('   waiting for WSO2 ...');
    await sleep(5000);
  }
  throw new Error('WSO2 API Manager is not reachable');
}

async function waitForDevportalApi(devportal, apiId) {
  // Publishing is asynchronous; the Developer Portal may need a moment to list the API.
  for (let i = 0; i < 20; i++) {
    try {
      await devportal('GET', `/apis/${apiId}`);
      return;
    } catch {
      await sleep(2000);
    }
  }
  throw new Error('API did not appear in the Developer Portal');
}

async function soap(http, operation, bodyXml) {
  const envelope =
    '<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:ser="http://service.ws.um.carbon.wso2.org">' +
    `<soapenv:Body><ser:${operation}>${bodyXml}</ser:${operation}></soapenv:Body></soapenv:Envelope>`;
  const res = await http('/services/RemoteUserStoreManagerService', {
    method: 'POST',
    headers: { Authorization: basic(ADMIN, ADMIN_PASSWORD), 'Content-Type': 'text/xml;charset=UTF-8', SOAPAction: `urn:${operation}` },
    body: envelope,
  });
  if (!res.ok) throw new Error(`${operation} failed (HTTP ${res.status}): ${res.text.slice(0, 400)}`);
  return /<ns:return>([^<]*)<\/ns:return>/.exec(res.text)?.[1] ?? null;
}

async function registerClient(http, clientName) {
  return parse(await http('/client-registration/v0.17/register', {
    method: 'POST',
    headers: { Authorization: basic(ADMIN, ADMIN_PASSWORD), 'Content-Type': 'application/json' },
    body: JSON.stringify({ callbackUrl: 'https://localhost', clientName, owner: ADMIN, grantType: 'password refresh_token', saasApp: true }),
  }), 'client registration');
}

async function passwordToken(http, registered, username, password, scope) {
  const res = parse(await http('/oauth2/token', {
    method: 'POST',
    headers: { Authorization: basic(registered.clientId, registered.clientSecret), 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', username, password, scope }).toString(),
  }), `token for ${username}`);
  return res.access_token;
}

function rest(http, base, token) {
  return async (method, path, body) => parse(await http(base + path, {
    method,
    headers: { Authorization: `Bearer ${token}`, ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  }), `${method} ${path}`);
}

function parse(res, what) {
  if (!res.ok) throw new Error(`${what} failed (HTTP ${res.status}): ${res.text.slice(0, 600)}`);
  return res.text ? JSON.parse(res.text) : {};
}

function loadDotEnv(file) {
  if (!existsSync(file)) throw new Error(`${file} not found: run node infra/init-env.mjs first`);
  return { ...loadDotEnvFile(file), ...process.env };
}

function loadDotEnvFile(file) {
  const values = {};
  for (const line of readFileSync(file, 'utf8').split(/\r?\n/)) {
    const m = /^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/.exec(line);
    if (m) values[m[1]] = m[2];
  }
  return values;
}

function required(name) {
  if (!env[name]) throw new Error(`${name} is missing from .env (run node infra/init-env.mjs)`);
  return env[name];
}

function basic(user, password) {
  return `Basic ${Buffer.from(`${user}:${password}`).toString('base64')}`;
}

function xml(value) {
  return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function step(title) {
  console.log(`\n== ${title}`);
}

function log(message) {
  console.log(`   ${message}`);
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}
