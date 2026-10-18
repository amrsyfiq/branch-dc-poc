#!/usr/bin/env node
/**
 * Makes the cluster's entry points reachable on your PC, on the same ports as before (dashboard, Postman, the
 * WSO2 portals, setup.mjs and the check scripts need no change):
 *
 *   node k8s/port-forward.mjs      (keep it running; Ctrl+C stops all forwards)
 *
 *   localhost:8090        DC service
 *   localhost:9444 / 8244 WSO2-A portals and token endpoint / gateway
 *   localhost:9445 / 8245 WSO2-B portals and token endpoint / gateway
 *   localhost:5433        the DC's PostgreSQL (database dc, user dc), for tools
 *
 * kubectl port-forward listens on 127.0.0.1 only (a LoadBalancer Service would listen on every interface of your
 * PC, the Wi-Fi included: Phase 5). A forward is tied to the one pod it picked at start. When that pod is replaced
 * (a redeploy, a restart), kubectl does not always exit: it may stay up and only log "error creating error stream
 * ... Timeout occurred" for every connection (seen in Phase 7). So a forward is restarted when it exits, when it
 * logs such an error, or when its local port stops accepting connections (checked every 15 s).
 */
import { spawn } from 'node:child_process';
import { connect } from 'node:net';
import { requireLocalCluster } from './secrets.mjs';

const FORWARDS = [
  { ns: 'dc', target: 'svc/dc-service', ports: ['8090:8090'] },
  { ns: 'branch-a', target: 'svc/wso2-a', ports: ['9444:9444', '8244:8244'] },
  { ns: 'branch-b', target: 'svc/wso2-b', ports: ['9445:9445', '8245:8245'] },
  { ns: 'dc', target: 'svc/postgres-dc', ports: ['5433:5432'] },
];
const BROKEN = /error creating (error|data) stream|lost connection to pod|Timeout occurred|connection refused|EOF/i;

requireLocalCluster();
let stopping = false;
const running = new Map(); // forward -> child process

function start(f) {
  const label = `${f.ns}/${f.target} ${f.ports.join(' ')}`;
  const child = spawn('kubectl', ['port-forward', '-n', f.ns, f.target, ...f.ports], { stdio: ['ignore', 'pipe', 'pipe'] });
  running.set(f, child);
  let up = false;
  child.stdout.on('data', (d) => {
    if (!up && /Forwarding from 127/.test(d)) {
      up = true;
      console.log(`up       ${label}`);
    }
  });
  child.stderr.on('data', (d) => {
    const line = String(d).trim().split('\n')[0];
    if (BROKEN.test(line)) {
      console.log(`broken   ${label}: ${line.slice(0, 160)}`);
      child.kill();
    }
  });
  child.on('exit', () => {
    if (running.get(f) === child) running.delete(f);
    if (stopping) return;
    console.log(`down     ${label}; restarting in 3 s`);
    setTimeout(() => start(f), 3000);
  });
}

/** Can a connection to the forward's first local port be opened? (A stale forward accepts nothing useful.) */
function check(f) {
  const child = running.get(f);
  if (!child) return;
  const port = Number(f.ports[0].split(':')[0]);
  const socket = connect({ host: '127.0.0.1', port, timeout: 5000 });
  socket.on('connect', () => socket.destroy());
  socket.on('timeout', () => { socket.destroy(); child.kill(); });
  socket.on('error', () => child.kill());
}

FORWARDS.forEach(start);
setInterval(() => FORWARDS.forEach(check), 15000);
process.on('SIGINT', () => {
  stopping = true;
  running.forEach((c) => c.kill());
  process.exit(0);
});
