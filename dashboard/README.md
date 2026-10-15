# Dashboard

A live view of the POC: the DC's data next to each branch's own copy, with Sync and Purge buttons and the DC's sync log. It refreshes every 2 seconds.

```text
browser ──► server.mjs (:8095) ──► DC                      GET /data, /sync-log; POST /sync, /purge
                               └─► WSO2-A / WSO2-B gateway  GET /branch-x/1.0.0/data  (read-only token)
```

- **Angular 22** (standalone, zoneless, signals) in `src/`.
- **[server.mjs](server.mjs)**, a small Node server with no dependencies. It serves the built app and forwards a **fixed list** of API calls. It holds the read-only WSO2 client secrets (`dashboard-a`, `dashboard-b`) and fetches the tokens, so the browser never sees a secret or a token.
- **How a branch is read:** through **its own WSO2 gateway**, like any other caller. The dashboard has no special access to the branches.
- **Per-branch badge:**
  - **In sync with DC:** same products and prices as the branch's slice in the DC.
  - **Not synced:** the branch has nothing, but the DC has products for it.
  - **Differs from DC:** different rows, which are highlighted.
  - **Unreachable:** the gateway or the branch is down.

## Run

The stack must be up and WSO2 set up (see the [main README](../README.md)).

```bash
npm ci
npm run build && npm run serve   # http://localhost:8095
```

During development, run `npm run serve` in one terminal and `npm start` in another. Then open `http://localhost:4200`; the dev server forwards `/api` to `:8095`.

## Tests

```bash
npm test -- --watch=false   # Angular (Vitest): comparison logic, panels, buttons, error states
npm run test:server         # server.mjs against fake upstreams: allowed routes only, tokens, 401 retry, errors
```
