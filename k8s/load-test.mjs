#!/usr/bin/env node
/**
 * Runs the counter-lookup load test (k8s/optional/load-test) against branch A and shows what the autoscaler does.
 *
 *   node k8s/load-test.mjs                      40 lookups/s for 4 minutes
 *   node k8s/load-test.mjs --rate 80 --hold 2m
 *   node k8s/load-test.mjs --keep               leave the Job and its NetworkPolicies afterwards
 *   node k8s/load-test.mjs --no-wait-down       stop when k6 ends instead of waiting for the scale-down
 *
 * Every 15 s it prints a line: the branch's copies (ready / wanted), the HPA's average CPU against its 70% target,
 * and the CPU each pod in branch-a uses (WSO2 included: on this PC it competes for the same 4 cores).
 * k6 prints its summary at the end (latency percentiles, failed requests, dropped iterations).
 * Afterwards the load test, including its NetworkPolicies, is removed unless --keep is given.
 */
import { execFileSync, spawn } from 'node:child_process';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { requireLocalCluster } from './secrets.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const NS = 'branch-a';
const args = process.argv.slice(2);
const option = (name, fallback) => {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : fallback;
};
const rate = option('--rate', '40');
const hold = option('--hold', '4m');
if (!/^\d{1,4}$/.test(rate) || Number(rate) < 1) throw new Error(`--rate must be 1..9999 lookups per second, not "${rate}"`);
if (!/^\d{1,3}[sm]$/.test(hold)) throw new Error(`--hold must look like 90s or 4m, not "${hold}"`);

requireLocalCluster();
const kubectl = (...a) => execFileSync('kubectl', a, { encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] });

// Render, set RATE and HOLD, replace the previous run.
const rendered = execFileSync('kubectl', ['kustomize', join(root, 'k8s', 'optional', 'load-test')], { encoding: 'utf8' });
// YAML -> JSON without a YAML library: kubectl prints one JSON document per object, each starting at column 0.
const items = execFileSync('kubectl', ['create', '--dry-run=client', '-o', 'json', '-f', '-'], { input: rendered, encoding: 'utf8' })
  .split(/\n(?=\{)/).map((doc) => JSON.parse(doc));
const list = { apiVersion: 'v1', kind: 'List', items };
const job = items.find((i) => i.kind === 'Job');
for (const e of job.spec.template.spec.containers[0].env) {
  if (e.name === 'RATE') e.value = rate;
  if (e.name === 'HOLD') e.value = hold;
}
kubectl('delete', 'job', 'counter-load', '-n', NS, '--ignore-not-found', '--wait=true');
execFileSync('kubectl', ['apply', '-f', '-'], { input: JSON.stringify(list), stdio: ['pipe', 'ignore', 'inherit'] });
console.log(`Load test started: ${rate} lookups/s for ${hold} (+40 s ramp), through WSO2-A to branch A\n`);

const t0 = Date.now();
const clock = () => {
  const s = Math.round((Date.now() - t0) / 1000);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
};

/** One timeline line: copies, HPA CPU, CPU per pod (millicores). */
function status() {
  try {
    const hpa = JSON.parse(kubectl('get', 'hpa', 'branch-a', '-n', NS, '-o', 'json'));
    const deploy = JSON.parse(kubectl('get', 'deploy', 'branch-a', '-n', NS, '-o', 'json'));
    const cpu = hpa.status.currentMetrics?.[0]?.resource?.current?.averageUtilization;
    const pods = JSON.parse(kubectl('get', '--raw', `/apis/metrics.k8s.io/v1beta1/namespaces/${NS}/pods`)).items
      .map((p) => ({ name: p.metadata.name, m: p.containers.reduce((sum, c) => sum + millicores(c.usage.cpu), 0) }))
      .filter((p) => !p.name.startsWith('postgres'))
      .sort((a, b) => a.name.localeCompare(b.name))
      .map((p) => `${p.name.replace(/^branch-a-[a-z0-9]+-/, 'branch-a-')} ${p.m}m`);
    const ready = deploy.status.readyReplicas ?? 0;
    console.log(`${clock().padStart(5)}  copies ${ready}/${hpa.status.desiredReplicas}  hpa cpu ${cpu ?? '?'}%/70%  | ${pods.join(', ')}`);
    return { ready, desired: hpa.status.desiredReplicas, min: hpa.spec.minReplicas };
  } catch (e) {
    console.log(`${clock().padStart(5)}  (status not available: ${String(e.message).split('\n')[0]})`);
    return null;
  }
}

function millicores(q) {
  if (q.endsWith('n')) return Math.round(Number(q.slice(0, -1)) / 1e6);
  if (q.endsWith('u')) return Math.round(Number(q.slice(0, -1)) / 1e3);
  if (q.endsWith('m')) return Number(q.slice(0, -1));
  return Math.round(Number(q) * 1000);
}

status();
const timer = setInterval(status, 15000);

// Follow k6 once its pod has started (its summary appears when the run ends).
async function waitForPod() {
  for (let i = 0; i < 120; i++) {
    const phase = kubectl('get', 'pods', '-n', NS, '-l', 'job-name=counter-load', '-o', 'jsonpath={.items[0].status.phase}').trim();
    if (['Running', 'Succeeded', 'Failed'].includes(phase)) return;
    await new Promise((r) => setTimeout(r, 1000));
  }
  throw new Error('the load-test pod did not start within 2 minutes (kubectl describe job counter-load -n branch-a)');
}
await waitForPod();
await new Promise((done) => spawn('kubectl', ['logs', '-f', '-n', NS, 'job/counter-load'], { stdio: 'inherit' }).on('exit', done));
// The log ends when the pod stops; the Job's status follows a moment later.
let succeeded = false;
for (let i = 0; i < 30; i++) {
  const [ok, failed] = kubectl('get', 'job', 'counter-load', '-n', NS, '-o', 'jsonpath={.status.succeeded} {.status.failed}').split(' ');
  if (ok === '1' || failed) {
    succeeded = ok === '1';
    break;
  }
  await new Promise((r) => setTimeout(r, 1000));
}
console.log(`\n${clock()}  k6 finished: ${succeeded ? 'run completed' : 'run FAILED (see above)'}`);

if (!args.includes('--no-wait-down')) {
  console.log('Waiting for the scale-down (the HPA waits 2 minutes of low CPU, then removes 1 copy a minute) ...');
  const end = Date.now() + 8 * 60000;
  while (Date.now() < end) {
    await new Promise((r) => setTimeout(r, 15000));
    const s = status();
    if (s && s.desired === s.min && s.ready === s.min) break;
  }
}
clearInterval(timer);

if (!args.includes('--keep')) {
  kubectl('delete', '-k', join(root, 'k8s', 'optional', 'load-test'), '--ignore-not-found');
  console.log('Load test removed (Job, script, NetworkPolicies).');
}
process.exit(succeeded ? 0 : 1);
