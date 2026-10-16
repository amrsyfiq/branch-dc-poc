# Branch–DC Event-Driven POC

A data centre (DC) distributes data to branches through Apache Kafka. Each branch gets **only its own** data, and WSO2 API Manager guards the entry into each branch. The flow is one-way: **DC → Kafka → listener → WSO2 → branch**.

- Plan, decisions and progress: [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md)
- Architecture chart: [docs/architecture-chart.md](docs/architecture-chart.md)

> **Status:** Phase 1 is done (DC service, Oracle with one database per site, Kafka). Branches, listeners, WSO2 and the dashboard come in later phases.

## Quick start

You need Docker Desktop, JDK 21 and Node 22.

```bash
node infra/init-env.mjs              # creates .env with random passwords (git-ignored; keep it)
./mvnw -DskipTests package           # builds the jars
docker compose up -d --build --wait  # Oracle (3 PDBs), Kafka, DC service
```

The first Oracle start creates the pluggable databases `DC_PDB`, `BRANCH_A_PDB` and `BRANCH_B_PDB` and takes about a minute. See [infra/oracle/01-create-pdbs.sh](infra/oracle/01-create-pdbs.sh).

## Try it

```bash
curl localhost:8090/data              # every branch's products and prices
curl -X POST localhost:8090/sync/A    # send branch A its slice   -> 202 {"status":"PUBLISHED", ...}
curl -X POST localhost:8090/purge/B   # tell branch B to delete its data
curl localhost:8090/sync-log          # latest requests, newest first
```

Look at the records on the topics:

```bash
docker exec bdc-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9094 \
  --topic dc.branch-a --from-beginning --formatter-property print.key=true --timeout-ms 5000
```

In Git Bash, put `MSYS_NO_PATHCONV=1` in front of `docker exec`. Otherwise Git Bash rewrites `/opt/...` into a Windows path.

## Tests

```bash
./mvnw test     # needs Docker running: starts a real Oracle and Kafka (Testcontainers)
```

Check that the summary says `Skipped: 0`. If Docker isn't running, the integration tests are skipped, not failed.

## Ports

| Service | Port |
|---|---|
| DC service | 8090 |
| Oracle | 1531 (`DC_PDB`, `BRANCH_A_PDB`, `BRANCH_B_PDB`) |
| Kafka | 9094 |
| Kafka UI (`--profile ui`) | 8096 |

## Layout

```text
events/          event contract shared by all services (BranchSyncEvent, ProductPrice)
dc-service/      the DC: GET /data, POST /sync/{branch}, POST /purge/{branch}, GET /sync-log
infra/           init-env.mjs (creates .env), oracle/ (PDB creation script)
Dockerfile       one image recipe for every Spring Boot module (MODULE build argument)
```
