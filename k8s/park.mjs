#!/usr/bin/env node
/**
 * Parks a branch to free memory for a scaling lab (all its pods stop, its data stays on its volumes), or starts it
 * again:
 *
 *   node k8s/park.mjs b              park branch B: its listener, branch service, WSO2 and database go to 0 copies
 *   node k8s/park.mjs b --unpark     start them again (WSO2 needs 2-3 minutes), then the listener delivers what
 *                                    waited in Kafka
 *
 * The listener's copies belong to KEDA (k8s/listener/base/scaledobject.yaml), which would undo a plain
 * kubectl scale. So the listener is paused with KEDA's annotation autoscaling.keda.sh/paused-replicas=0 and resumed
 * by removing it. The branch's own HPA does nothing while its Deployment is at 0.
 * k8s/deploy.mjs unparks every branch: after a deploy everything runs.
 */
import { execFileSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';
import { requireLocalCluster } from './secrets.mjs';

const PAUSE = 'autoscaling.keda.sh/paused-replicas';
const kubectl = (...args) => execFileSync('kubectl', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const listenerPaused = (x) =>
  kubectl('get', 'scaledobject', `listener-${x}`, '-n', 'dc', '-o', `jsonpath={.metadata.annotations.${PAUSE.replace(/\./g, '\\.')}}`) !== '';

/** Parked = the branch service is at 0 copies. A listener held by hand (paused-replicas=3, the demo) is not. */
export function isParked(x) {
  return kubectl('get', 'deploy', `branch-${x}`, '-n', `branch-${x}`, '-o', 'jsonpath={.spec.replicas}') === '0';
}

export async function park(x) {
  kubectl('annotate', 'scaledobject', `listener-${x}`, '-n', 'dc', `${PAUSE}=0`, '--overwrite');
  kubectl('scale', '-n', `branch-${x}`, `deploy/branch-${x}`, `sts/wso2-${x}`, `sts/postgres-${x}`, '--replicas=0');
  for (let i = 0; i < 60; i++) {
    const left = kubectl('get', 'pods', '-n', `branch-${x}`, '--no-headers', '--ignore-not-found') +
      kubectl('get', 'pods', '-n', 'dc', '-l', `app.kubernetes.io/name=branch-listener,branch=${x}`, '--no-headers', '--ignore-not-found');
    if (left === '') return;
    await sleep(2000);
  }
  throw new Error(`branch ${x}'s pods did not stop within 2 minutes (kubectl get pods -n branch-${x})`);
}

/**
 * @param {boolean} wait  wait until WSO2 answers before resuming the listener (k8s/deploy.mjs does not wait: the
 *                        listener retries a delivery for ~30 minutes anyway). WSO2 took over 6 minutes on a busy PC.
 */
export async function unpark(x, { wait = true } = {}) {
  kubectl('scale', '-n', `branch-${x}`, `sts/postgres-${x}`, `sts/wso2-${x}`, `deploy/branch-${x}`, '--replicas=1');
  if (wait) {
    for (const target of [`sts/postgres-${x}`, `sts/wso2-${x}`, `deploy/branch-${x}`]) {
      try {
        kubectl('rollout', 'status', '-n', `branch-${x}`, target, '--timeout=10m');
      } catch {
        console.log(`   ${target} not ready after 10 minutes (kubectl get pods -n branch-${x}); resuming the listener anyway`);
        break;
      }
    }
  }
  // Only now the listener: WSO2 answers, so the waiting events go through instead of being retried.
  if (listenerPaused(x)) kubectl('annotate', 'scaledobject', `listener-${x}`, '-n', 'dc', `${PAUSE}-`);
  if (!wait) return;
  for (let i = 0; i < 90; i++) { // KEDA brings it back to its minimum (1) within a polling interval or two
    if (Number(kubectl('get', 'deploy', `listener-${x}`, '-n', 'dc', '-o', 'jsonpath={.status.readyReplicas}')) >= 1) return;
    await sleep(2000);
  }
  throw new Error(`listener-${x} did not start within 3 minutes (kubectl get scaledobject -n dc)`);
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  const x = (process.argv[2] ?? '').toLowerCase();
  if (!['a', 'b'].includes(x)) throw new Error('usage: node k8s/park.mjs <a|b> [--unpark]');
  requireLocalCluster();
  const t0 = Date.now();
  if (process.argv.includes('--unpark')) {
    await unpark(x);
    console.log(`branch ${x.toUpperCase()} running again after ${Math.round((Date.now() - t0) / 1000)} s`);
  } else {
    await park(x);
    console.log(`branch ${x.toUpperCase()} parked: its listener, branch service, WSO2 and database are at 0 copies`);
  }
}
