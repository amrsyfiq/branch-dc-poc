# Dashboard

A live view of the POC: every checkpoint in the DC (pass counts, latest request, Sync / Rush / Purge buttons) next to each checkpoint's own copy, and the DC's request log. It refreshes every 2 seconds.

```text
browser ──► server.mjs (:8095) ──► DC                      GET /summary, /data, /sync-log; POST /sync, /purge, /rush
                               └─► WSO2-A / WSO2-B gateway  GET /branch-x/1.0.0/summary, /data  (read-only token)
```

- **Angular 22** (standalone, zoneless, signals) in `src/`.
- **[server.mjs](server.mjs)**, a small Node server with no dependencies. It serves the built app and forwards a **fixed list** of API calls, with every number in a query string checked (e.g. a rush is at most 20000). It holds the read-only WSO2 client secrets (`dashboard-a`, `dashboard-b`) and fetches the tokens, so the browser never sees a secret or a token.
- **How a checkpoint is read:** through **its own WSO2 gateway**, like any other caller. The dashboard has no special access to the branches, and it never does counter lookups (passport numbers).
- **How far a checkpoint is:** each request of the DC has one event per pass, with consecutive sequence numbers. The dashboard asks the checkpoint how many of its rows hold a seq from that range (`GET /summary?from=&to=`): "Applied 1,240 of 5,000 events".
- **Per-checkpoint badge:**
  - **Receiving…:** the DC's latest request is not fully applied yet (with a progress bar).
  - **In sync with DC:** fully applied, and as many passes as the DC has for it.
  - **Differs from DC:** fully applied, but the counts differ, e.g. a rush on a checkpoint that was never synced. Sync it.
  - **Purged:** the latest request was a purge, and the checkpoint is empty.
  - **Not synced:** the DC has never sent this checkpoint anything.
  - **DC could not publish:** Kafka did not confirm all of the latest request's events. Sync again.
  - **Unreachable:** the gateway or the checkpoint is down (or slower than 5 s).

## Run

The stack must be up and WSO2 set up (see the [main README](../README.md)).

```bash
npm ci
npm run build && npm run serve   # http://localhost:8095
```

During development, run `npm run serve` in one terminal and `npm start` in another. Then open `http://localhost:4200`; the dev server forwards `/api` to `:8095`.

## Tests

```bash
npm test -- --watch=false   # Angular (Vitest): comparison logic, panels, buttons, progress, error states
npm run test:fresh          # the same, after clearing the build caches (use it after mutation checks)
npm run test:server         # server.mjs against fake upstreams: allowed routes only, number limits, tokens, 401 retry, errors
```

**Stale builds:** Vitest keeps transformed modules in `node_modules/.vitest-cache`. After changing only a component (not its spec), a run reused the old build of the component and a deliberately broken version "passed". `npm run test:fresh` deletes that cache and `.angular/cache` first.
