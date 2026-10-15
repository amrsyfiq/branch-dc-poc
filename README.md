# Branch–DC Event-Driven POC

A data centre (DC) distributes data to branches through Apache Kafka. Each branch gets **only its own** data, and WSO2 API Manager guards the entry into each branch. The flow is one-way: **DC → Kafka → listener → WSO2 → branch**.

- Plan, decisions and progress: [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md)
- Architecture chart: [docs/architecture-chart.md](docs/architecture-chart.md)

> **Status:** Phases 1–2 are done: the DC, Kafka, the branches and the listeners, with one database per site and isolated networks. WSO2 (Phase 3) and the dashboard (Phase 4) come next. Until Phase 3, listeners call the branches directly.

## How it fits together

```text
 DC environment (dc-net)                         Branch A env (branch-a-net, internal)
┌──────────────────────────────────────────┐    ┌─────────────────────────────┐
│ dc-service ──► Kafka ──► listener-a ─────┼────┼─► branch-a  (BRANCH_A_PDB)  │
│ (DC_PDB)           └───► listener-b ─────┼─┐  └─────────────────────────────┘
└──────────────────────────────────────────┘ │  Branch B env (branch-b-net, internal)
                                             │  ┌─────────────────────────────┐
                                             └──┼─► branch-b  (BRANCH_B_PDB)  │
                                                └─────────────────────────────┘
 Phase 3 puts WSO2-A / WSO2-B in front of each branch, inside its environment.
```

- **Environments are Docker networks.** A container can only reach containers on a network it has joined.
- **The branch networks are `internal`.** A branch has no route out of its network at all: not to the DC, not to Kafka, not to the other branch, and not even to ports published on your PC.
- **Listeners live in the DC environment, one per branch.** Each joins `dc-net` to read Kafka, plus **only** its own branch's network to deliver events.
- **The branches are the same code.** Branch A and branch B run the same image; only `BRANCH_ID` and the database differ. They aren't published on your PC.

[scripts/check-isolation.sh](scripts/check-isolation.sh) tests these rules by opening TCP connections from inside the containers.

## Quick start

You need Docker Desktop, JDK 21 and Node 22.

```bash
node infra/init-env.mjs              # creates .env with random passwords (git-ignored; keep it)
./mvnw -DskipTests package           # builds the jars
docker compose up -d --build --wait  # Oracle (3 PDBs), Kafka, DC, 2 branches, 2 listeners
```

The first Oracle start creates the pluggable databases `DC_PDB`, `BRANCH_A_PDB` and `BRANCH_B_PDB` and takes about a minute. See [infra/oracle/01-create-pdbs.sh](infra/oracle/01-create-pdbs.sh).

## Try it

```bash
curl localhost:8090/data              # DC: every branch's products and prices
curl -X POST localhost:8090/sync/A    # send branch A its slice   -> 202 {"status":"PUBLISHED", ...}
curl -X POST localhost:8090/purge/B   # tell branch B to delete its data
curl localhost:8090/sync-log          # latest requests, newest first
```

The branches aren't reachable from your PC, so read them from inside their own network:

```bash
docker run --rm --network branch-dc_branch-a-net curlimages/curl:8.11.1 -s http://branch-a:8080/data
docker run --rm --network branch-dc_branch-b-net curlimages/curl:8.11.1 -s http://branch-b:8080/data
```

Scripts:

```bash
bash scripts/check-isolation.sh   # network rules between the environments (all must say "ok")
bash scripts/demo-outage.sh       # branch A down during a sync: retries -> dead-letter topic -> recovery
```

Look at the records on a topic:

```bash
docker exec bdc-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9094 \
  --topic dc.branch-a --from-beginning --formatter-property print.key=true --timeout-ms 5000
```

In Git Bash, put `MSYS_NO_PATHCONV=1` in front of `docker exec`. Otherwise Git Bash rewrites `/opt/...` into a Windows path.

## What a branch does with an event

The listener delivers each event with `POST /events`. The branch then:

| Case | Result | Listener |
|---|---|---|
| New snapshot or purge | `APPLIED`: the snapshot replaces all products; the purge deletes them | done |
| Same event id again (Kafka delivers at least once) | `DUPLICATE`, nothing changes | done |
| Older than the event the current data came from | `STALE`, recorded but not applied | done |
| Event for another branch | 422, nothing stored | straight to the dead-letter topic |
| Branch down or 5xx | | retry 3 times (1 s, 2 s, 4 s), then the dead-letter topic |

A dead-lettered event isn't replayed automatically. Sync the branch again: a snapshot is a full replace.

## Tests

```bash
./mvnw test     # needs Docker running: starts a real Oracle and Kafka (Testcontainers)
```

Check that the summary says `Skipped: 0`. If Docker isn't running, the integration tests are skipped, not failed.

## Ports

| Service | Port on your PC |
|---|---|
| DC service | 8090 |
| Oracle | 1531 (`DC_PDB`, `BRANCH_A_PDB`, `BRANCH_B_PDB`) |
| Kafka | 9094 |
| Kafka UI (`--profile ui`) | 8096 |
| Branches, listeners | not published |

## Layout

```text
events/           event contract shared by all services (BranchSyncEvent, ProductPrice)
dc-service/       the DC: GET /data, POST /sync/{branch}, POST /purge/{branch}, GET /sync-log
branch-service/   a branch (same code per branch): GET /data, POST /events
branch-listener/  a listener (same code per branch): reads dc.branch-<id>, delivers to the branch
infra/            init-env.mjs (creates .env), oracle/ (PDB creation script)
scripts/          check-isolation.sh, demo-outage.sh
Dockerfile        one image recipe for every Spring Boot module (MODULE build argument)
```
