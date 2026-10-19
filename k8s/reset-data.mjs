#!/usr/bin/env node
/**
 * Resets the demo data: the DC back to its 40 seeded passes (every rush removed), each running branch emptied
 * and synced again. Every rush adds passes for good (A had grown to 83,144 at the DC), which makes syncs slow.
 *
 *   node k8s/reset-data.mjs          (node k8s/port-forward.mjs must be running: the syncs go through the DC's API)
 *
 * It goes around the event flow on purpose: an operator resetting test data, not something the DC or a branch
 * would ever do. Per running branch:
 *   1. its listener is held at 0 copies (KEDA's pause annotation) and its group's position in the topic is moved to
 *      the end: events still waiting are skipped, because delivered after the reset they would bring removed
 *      passes back (the branch is emptied and synced again anyway);
 *   2. its database is emptied (psql inside the PostgreSQL pod).
 * Then the DC's rush passes are deleted, the listeners handed back to KEDA, and each branch synced normally,
 * through Kafka, its listener and WSO2.
 * A parked branch (k8s/park.mjs) is skipped: unpark it and run this again.
 */
import { execFileSync } from 'node:child_process';
import { isParked } from './park.mjs';
import { requireLocalCluster } from './secrets.mjs';

const PAUSE = 'autoscaling.keda.sh/paused-replicas';
const kubectl = (...args) => execFileSync('kubectl', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
const psql = (ns, pod, db, sql) => kubectl('exec', '-n', ns, pod, '--', 'psql', '-U', db, '-d', db, '-v', 'ON_ERROR_STOP=1', '-c', sql);
const kafkaGroups = (...args) => kubectl('exec', '-n', 'platform', 'kafka-0', '--', '/opt/kafka/bin/kafka-consumer-groups.sh',
  '--bootstrap-server', 'localhost:9092', ...args);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

requireLocalCluster();
const running = ['a', 'b'].filter((x) => !isParked(x));
for (const x of ['a', 'b'].filter((b) => !running.includes(b))) console.log(`Branch ${x.toUpperCase()} is parked: skipped`);

for (const x of running) {
  kubectl('annotate', 'scaledobject', `listener-${x}`, '-n', 'dc', `${PAUSE}=0`, '--overwrite');
  for (let i = 0; kubectl('get', 'pods', '-n', 'dc', '-l', `app.kubernetes.io/name=branch-listener,branch=${x}`, '--no-headers', '--ignore-not-found') !== ''; i++) {
    if (i > 60) throw new Error(`listener-${x} did not stop within 2 minutes`);
    await sleep(2000);
  }
  // Allowed only while the group has no members, hence the pause first.
  const moved = kafkaGroups('--group', `listener-${x}`, '--topic', `dc.branch-${x}`, '--reset-offsets', '--to-latest', '--execute');
  const skipped = moved.split('\n').filter((l) => l.includes(` dc.branch-${x} `)).length;
  psql(`branch-${x}`, `postgres-${x}-0`, 'branch', 'TRUNCATE branch_pass');
  console.log(`Branch ${x.toUpperCase()}: listener held, its ${skipped} partitions moved to the end, database emptied`);
}

// Rush passes are numbered from 100001 (application_no_seq); the 40 seeded ones are 000001-000040.
const deleted = psql('dc', 'postgres-dc-0', 'dc', "DELETE FROM travel_pass WHERE split_part(application_no, '-', 3)::int >= 100001");
console.log(`DC: ${deleted.replace(/^DELETE /, '')} rush passes removed`); // psql answers "DELETE <count>"

for (const x of running) {
  kubectl('annotate', 'scaledobject', `listener-${x}`, '-n', 'dc', `${PAUSE}-`);
  const res = await fetch(`http://localhost:8090/sync/${x.toUpperCase()}`, { method: 'POST' }).catch(() => null);
  if (!res) throw new Error('The DC is not reachable on localhost:8090: start node k8s/port-forward.mjs, then sync from the dashboard');
  const body = await res.json();
  console.log(`Branch ${x.toUpperCase()}: listener back with KEDA; sync of ${body.eventCount} passes ${body.status}`);
}
