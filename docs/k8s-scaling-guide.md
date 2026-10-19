# Scaling lab

Exercises you run yourself on the local cluster, with what to watch and what I measured on this PC (Windows 10,
4 cores / 8 threads, 16 GB, Docker Desktop's Kubernetes with 1 node). Phase 8 of the
[plan](../IMPLEMENTATION_PLAN.md#phase-8-scaling-lab).

- **8a:** the branch service, scaled by a HorizontalPodAutoscaler (HPA) on CPU. Exercises 1 and 2.
- **8b:** the listener, scaled by KEDA on its Kafka lag (the events waiting for it). Exercise 3.
- **[The demo](#the-demo-k9s):** both stories, automatic and by hand, in about 15 minutes, watched in k9s.

The holiday schedule (scaling up before a known date) is dropped.

## In short

| Question | Answer (measured) |
|---|---|
| What scales the branch service? | An HPA ([k8s/branch/base/hpa.yaml](../k8s/branch/base/hpa.yaml)): 1 to 3 copies, aiming for 70% of each copy's CPU request (200m) |
| How fast does it react? | Within ~15 s of the CPU rising it asks for more copies; they are ready ~25–30 s later. Back down after 2 minutes of low CPU, 1 copy a minute |
| Did 3 branch copies make it faster on this PC? | **No.** One warm copy answered 150 lookups/s with 0.2 of a core and 6 ms at the 95th percentile. 3 copies used 2.6 times the CPU for the same lookups |
| Why scale then? | On a real cluster the new copies run on **other machines** and add real capacity. The HPA works the same way there; this lab shows how it decides, not extra speed |
| What scales the listener? | KEDA on its Kafka lag ([k8s/listener/base/scaledobject.yaml](../k8s/listener/base/scaledobject.yaml)): 1 to 3 copies, one per 500 waiting events. The HPA alone can't: a listener's CPU stays low however long its queue is |
| Did 3 listeners help? | **Yes, once warm:** ~200 events/s against ~90–130/s with 1. Not 3×: they share WSO2-A and the branch, which become the limit |
| What surprised me | A cold pod (after a restart) looked busy to the HPA because of JIT compiling, and each new copy needs ~2 cores for ~30 s to start: scaling up added load before it helped |

## Before you start

The stack is running (`node k8s/deploy.mjs`, `node k8s/port-forward.mjs`, see the [README](../README.md)).

**Park branch B** for the lab, to free memory for the extra copies. Pods ask the node for 92% of its memory with
everything running (95% with KEDA), which leaves no room for extra copies. With B parked: about 60%.

```bash
node k8s/park.mjs b              # B's listener, branch service, WSO2 and database to 0 copies
node k8s/park.mjs b --unpark     # afterwards: start them again (WSO2 needs 2-3 minutes)
```

B's data stays on its volumes and events for B wait in Kafka. The dashboard shows B as unreachable, and the
port-forward script keeps restarting B's forward: both are expected. Why a script and not `kubectl scale`: the
listener's copies belong to KEDA, which would start it again within seconds, so the script pauses KEDA with its
annotation `autoscaling.keda.sh/paused-replicas: "0"`. B's HPA does nothing while its Deployment is at 0.
`node k8s/deploy.mjs` unparks every branch.

Useful windows to keep open while you run the exercises:

```bash
kubectl get hpa -n branch-a -w                      # copies and CPU, as the HPA sees them
kubectl get pods -n branch-a -w                     # pods appearing and going
kubectl top pods -n branch-a                        # CPU and memory per pod (needs metrics-server)
kubectl get events -n branch-a --sort-by=.lastTimestamp | tail   # "New size: 3; reason: cpu ... above target"
```

`kubectl logs -l <label>` shows only the **last 10 lines of each pod**. Add `--tail=-1` to see everything (this
cost me a few minutes: a count of 144 deliveries came out as 30).

## The demo (k9s)

Three stories in about 15 minutes: **the branch scales itself** under a counter rush (HPA, CPU), **the listener
scales itself** under a big DC rush (KEDA, backlog), and **scaling by hand**, including what an autoscaler does with
a number you set yourself. You need the dashboard (browser), [k9s](https://k9scli.io) (a terminal view of the
cluster) and one more terminal to start things.

k9s runs on your PC and reads the cluster through your kubectl settings, so it costs the cluster nothing. It shows
what Rancher's Workloads pages show: Deployments, Pods, autoscalers, logs. (Rancher itself needs at least 4 GB of
RAM for its smallest test setup: no room on this PC.) Install it once: `winget install --id Derailed.k9s`.

### The k9s keys you need

| Key | Does |
|---|---|
| `:pods` Enter, then `0` | All pods, all namespaces |
| `/branch-a\|listener` Enter | Show only the branch and listener pods (Esc clears the filter) |
| `:hpa` Enter | The autoscalers: copies now / wanted, CPU (branches) or waiting events (listeners) against the target |
| `:so` Enter | KEDA's ScaledObjects: ACTIVE = events are waiting, PAUSED = held by hand |
| `:deploy` Enter | Deployments, with READY 1/3 while copies start |
| `s` on a **deployment** | Scale it: type the number, Enter |
| `l` on a pod | Its logs; `/now reading` filters them; Esc goes back |
| `?` / `:q` | Help / quit |

Avoid in front of an audience: **Ctrl+D** deletes the selected resource, **Ctrl+K** kills the selected pod, and
`s` on a **pod** (not a deployment) opens a shell inside it. (`k9s --readonly` disables those, but also Scale.)
Practise the keys for five minutes before the demo.

### Prepare (about 5–10 minutes before)

0. **Free memory on Windows first:** Task Manager → Performance → Memory should show **at least ~2 GB available**
   with the stack running. Close other browser tabs and programs. Measured on 2026-10-19: with 0.6–0.7 GB free
   (Chrome 3–4 GB, VS Code 1.5–1.8 GB), the Kubernetes API answered too slowly, so KEDA and metrics-server kept
   restarting, every port-forward dropped, the dashboard timed out, and WSO2-B could not start within 6 minutes.
1. Terminal 1: `node k8s/port-forward.mjs` (keep it open). Terminal 2: `node dashboard/server.mjs` (keep it open).
   Browser: <http://localhost:8095>. Only one of each: a second port-forward fights the first for the same ports.
2. **Start from clean data:** `node k8s/reset-data.mjs` (about 10 s). Every rush adds passes for good; this puts
   the DC back to its 40 seeded passes and empties and re-syncs each running branch, skipping events still waiting.
   The dashboard then says **In sync with DC** for A (the load test needs A's passes: it stops with "sync it
   first" on an empty checkpoint).
3. Terminal 3: `node k8s/park.mjs b` ([Before you start](#before-you-start)).
4. **Warm the pods up:** `node k8s/load-test.mjs --rate 40 --hold 1m`, and wait for "Load test removed". A pod
   that has been idle since a restart looks busy to the HPA while Java compiles (exercise 2), and would scale up
   for the wrong reason in front of the audience.
5. Terminal 4: `k9s --context docker-desktop -A -c pods --logoless` (all pods, all namespaces; naming the context
   means k9s cannot open another cluster from your kubeconfig by mistake). Type `/branch-a|listener` Enter.

### Story 1: the branch scales itself (about 5 minutes)

Terminal 3: `node k8s/load-test.mjs --rate 150 --hold 2m`

| When | k9s | Terminal 3 | What to say |
|---|---|---|---|
| 0:00 | A `counter-load-…` pod appears | "Load test started" | "This pod plays the counter officers: 150 passport lookups a second, through the gateway" |
| ~1:00 | Two new `branch-a-…` pods, CPU climbing | `copies 1/3  hpa cpu 204%/70%` | "CPU passed 70% of what each copy asked for, so Kubernetes adds copies by itself" |
| | `:hpa` Enter | | "This is the autoscaler's view: wanted 3, at most 3" (then `:pods` back) |
| ~1:30 | Three branch pods Running | `copies 3/3` | "A new copy needs about 30 seconds to start" |
| ~2:40 | `counter-load` gone | k6 results: 0 failed | "The rush is over" |
| ~5:00 | Branch pods 3 → 2 → 1 | `copies 2/2`, `1/1`, "Load test removed" | "It waits 2 minutes before removing copies, in case the rush comes back" |

### Story 2: the listener scales itself (about 5 minutes)

1. Dashboard: rush count **10000**, click **Rush A**. Branch A starts counting: "Applied 1,300 of 10,000 events…".
2. k9s `:pods`: **within ~10 s two new `listener-a-…` pods appear** by themselves (measured: 8 s from the rush to
   KEDA's "New size: 3"), Running and ready ~30–50 s later. Say: "Nobody typed anything: KEDA saw 9,000 events
   waiting for this listener, 500 per copy would be 18 copies, capped at 3 on this PC (6 is the partition limit)."
3. `:so` Enter: listener-a **ACTIVE True** (events are waiting). `:hpa` Enter: `keda-hpa-listener-a` at `3k/500`:
   KEDA reports "6 copies × 500", the most that split the 6 partitions evenly, and the HPA caps it at 3.
4. The **branch pods** go up too (its own autoscaler: applying events costs CPU at the branch). Say: "Two
   autoscalers, two signals: the queue for the listener, the CPU for the branch."
5. When the dashboard reaches 10,000 of 10,000: `:so` shows ACTIVE False; about 2 minutes later the listener
   copies go 3 → 2 → 1, one a minute.

### Story 3: scaling by hand (about 5 minutes)

1. **The branch:** k9s `:deploy`, select `branch-a`, `s`, `3`, Enter. Three copies start. Then the autoscaler
   undoes it because the CPU is low: 2 copies after ~2.5 minutes, 1 after ~3.5 (exercise 1b). Say: "With an
   autoscaler in charge, a number set by hand only lasts until its next decision. To keep more copies, raise the
   autoscaler's minimum instead."
2. **The listener, held by hand:** KEDA would undo `s` the same way, so it has a switch for this. Terminal 3:
   `kubectl -n dc annotate so listener-a autoscaling.keda.sh/paused-replicas=3`. k9s `:so`: PAUSED True; `:pods`:
   three listener copies, and they stay. Logs (`l` on a pod, `/` and the text, Enter; Esc back): the old pod says
   "Gave up partitions [2, 3, 4, 5] (offsets committed): another consumer takes over", a new one "Assigned
   partitions [3, 4]; now reading [3, 4]" (which ones differs per run; once settled each copy has 2 of the 6).
3. Give it back to KEDA: `kubectl -n dc annotate so listener-a autoscaling.keda.sh/paused-replicas-`. With nothing
   waiting, KEDA goes back to 1 after its 2-minute window. The old pod then says "now reading [0, 1, 2, 3, 4, 5]".

**Be ready for "did it get faster?"** The branch: no, one warm copy already handled 150 lookups/s (exercise 2). The
listener: **yes, once its copies are warm**: one listener delivered 90–130 events/s, three warm ones ~200/s
(exercise 3). But new copies need ~30–50 s to start, and on this one PC everything they push goes through the same
4 cores and the same WSO2: with 3 listeners and 3 branch copies at once, WSO2-A slowed down to 4–5 s per answer.
On a real cluster each copy runs on its own machine, so the gain is bigger and the side effects smaller.

### Afterwards

`node k8s/park.mjs b --unpark`. If something is left held by hand: remove the annotation (Story 3, step 3).

**Without k9s:** Docker Desktop (4.51 and later) has a **Kubernetes** view with a live list of pods and deployments
for one namespace at a time (switch between `branch-a` and `dc`). It doesn't show the autoscalers, CPU or logs:
keep `kubectl get hpa -A -w` running in a terminal next to it.

## Exercise 1: scale by hand

### 1a. Three listener copies share six partitions

```bash
kubectl -n dc annotate so listener-a autoscaling.keda.sh/paused-replicas=3   # hold 3 copies (KEDA paused)
kubectl exec -n platform kafka-0 -- /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group listener-a   # who owns which partition, and the lag
kubectl -n dc annotate so listener-a autoscaling.keda.sh/paused-replicas-    # back to KEDA
```

In Git Bash, put `MSYS_NO_PATHCONV=1` in front of the `kubectl exec` line. Since 8b, KEDA owns the listener's
copies: a `kubectl scale` would be undone like the branch's in 1b, so the annotation holds the number instead.

What I measured (in 8a, with `kubectl scale`, before KEDA):

- The 2 new copies were ready after 24 s. About 40 s after the command every copy owned **2 of the 6
  partitions** (0+1, 3+4, 2+5).
- The hand-over was gradual (Kafka 4's consumer group protocol, KIP-848): the old copy gave up partitions 3–5
  first and 2 ten seconds later, and kept working on the others meanwhile. Its log says
  `Gave up partitions [3, 4, 5] (offsets committed): another consumer takes over`.
- A sync of A (144 events) was then delivered by all three, each only from its own partitions: 45, 48 and 51
  events. It took 4.4 s, no faster than one copy (~35 events/s), because the new copies were cold (JIT, a first
  token from WSO2) and 144 events are too few. Exercise 3 measures drain time with a real rush.
- Back to 1 (`--replicas=1`): Kubernetes removed the two newest copies; the old one took all 6 partitions back,
  lag 0.

A 7th copy would get no partition and sit idle: **partitions are the limit** (one consumer per partition per
group). Threads count the same way: in Spring Kafka every thread is a full consumer (exercise 7).

### 1b. Branch A by hand: the HPA takes it back

```bash
kubectl -n branch-a scale deploy/branch-a --replicas=3
kubectl get hpa -n branch-a -w
```

What I measured: 3 copies ready after 23 s; the HPA removed one at 141 s and the next at 203 s. With an HPA in
charge, the HPA decides: a number you set by hand lasts only until its next decision (here: after its 2-minute
scale-down window). To keep a number, change the HPA (`minReplicas`), not the Deployment. That is also why the
Deployment has no `replicas` field any more: otherwise every redeploy would reset it.

## Exercise 2: the HPA in a counter rush

### How the HPA decides

Every 15 s: **wanted copies = ceil(copies now × CPU now ÷ target)**, CPU as a percentage of the request (200m).
Example from the run below: 1 copy at 204% → ceil(1 × 204 ÷ 70) = 3.

- **Up:** at once (no waiting window), at most 2 copies per 30 s, never more than `maxReplicas: 3`.
- **Down:** only when every wish of the last 2 minutes was lower (the default is 5), then 1 copy a minute. One
  quiet minute in a rush must not remove a copy needed again a minute later.
- The CPU a new copy burns before it is ready (startup probe) is set aside: at 1:32 below the pods used 3.1 cores together, the HPA counted 132%, not 517%.

### Run it

```bash
node k8s/load-test.mjs                      # 40 lookups/s for 4 minutes, then waits for the scale-down
node k8s/load-test.mjs --rate 150 --hold 3m
```

The load test is a k6 Job **inside** branch A ([k8s/optional/load-test](../k8s/optional/load-test)): it
stands for the counter terminals at the checkpoint, looking up passports (`POST /passes/search`) through WSO2-A
with its own read-only WSO2 application (`counter-a`; it cannot get the sync scope: checked, 403 on
`POST /events`). It can't run through a port-forward: a port-forward always goes to one pod. Its two
NetworkPolicies open exactly "load test → WSO2-A" and are removed with it after the run.

Requests arrive at a fixed rate whether the system keeps up or not, like travellers. If it doesn't keep up, k6
reports `dropped_iterations` and the latency grows.

Every 15 s the script prints a line like this one (copies ready / wanted, the HPA's CPU, CPU per pod):

```text
 1:03  copies 1/3  hpa cpu 204%/70%  | branch-a-vsxr7 408m, counter-load-5q7t5 64m, wso2-a-0 441m
```

### What I measured (2026-10-18)

| Run | Copies | Branch CPU in the rush | Lookups/s p95 / p99 / max | Dropped | Failed |
|---|---|---|---|---|---|
| 40/s, HPA, **cold** pod (after a reboot) | 1 → 3 | 408m on 1 copy, then 150–390m on 3 | 12 ms / 164 ms / 2.09 s | 53 | 0 |
| 40/s, HPA pinned to 1 (`maxReplicas: 1`) | 1 | **58–95m** | 5.5 ms / 7 ms / 67 ms | 0 | 0 |
| 150/s, HPA, warm | 1 → 3 | ~205m on 1 copy, then 400–560m on 3 | 9.7 ms / 32 ms / 485 ms | 13 | 0 |
| 150/s, pinned to 1 | 1 | **~205m** steady | 5.8 ms / 34 ms / 1.1 s | 93 | 0 |

Timeline of the first run: CPU 82% at 0:48 → the HPA wants 2; 204% at 1:03 → wants 3; 2 ready at 1:18, 3 at 1:32.
k6 ended at 4:44; 3 → 2 at 5:48, 2 → 1 at 6:53.

### What it shows

1. **One warm copy was enough at both rates.** The branch pod has no CPU limit, so it uses as much of the node
   as it needs. 150 lookups/s cost it 0.2 of a core, on a PC with 8 threads. The HPA still scaled to 3 because
   its target is relative to the *request* (70% of 200m = 140m), not to the pod running out of CPU.
2. **A cold pod looks busy.** In the first run the pod had served almost nothing since the reboot. The JIT
   compiler's work at the start of the rush pushed it to 408m, the same pod later did the same rate with 60m.
   The HPA can't tell compiling from working.
3. **A new copy costs about 2 cores for ~30 s** (1.8–2.1 cores measured): Spring starting and the JIT compiling.
   On a node that is already busy, scaling up first adds load. That's when the slow requests (2.09 s) and most
   dropped lookups of the first run happened.
4. **Load is spread by connection, not by request.** WSO2 keeps its connections to the branch open, so one copy
   often did most of the work (506m next to two copies at ~60m), and the busy copy changed over time
   (probably as connections were closed and reopened; not checked). A Service balances connections, not requests.
5. **WSO2 costs more than the branch:** 220–850m for WSO2 (one sample 1.65 cores) against 60–210m for one warm
   branch copy at the same rates. In a
   real rush the gateways need scaling too (the platform team's job; here it is one pod per branch).

### What would make the copies count

- **Several nodes:** new copies land on other machines with their own cores (on one node they share the same 4).
- **A CPU limit per pod** (e.g. `limits: { cpu: 500m }`): then one copy really runs out at some rate, and more
  copies raise the total. This lab doesn't set one on purpose: a CPU limit also slows Java's startup and JIT
  (the 2 cores above), so every restart and scale-up gets slower. Try it yourself (not measured):
  `kubectl -n branch-a patch deploy branch-a --type json -p '[{"op":"add","path":"/spec/template/spec/containers/0/resources/limits/cpu","value":"300m"}]'`,
  then `node k8s/load-test.mjs --rate 150`. Undo with `node k8s/deploy.mjs --no-build`.
- **Warm before the rush:** start the copies before a known peak (the holiday schedule, exercise 4 in 8b), so
  their 30 s of startup and JIT happen before the travellers arrive.

### Try it yourself

```bash
kubectl -n branch-a patch hpa branch-a --type merge -p '{"spec":{"maxReplicas":1}}'   # pin to 1 copy
node k8s/load-test.mjs --rate 150 --hold 3m --no-wait-down
kubectl -n branch-a patch hpa branch-a --type merge -p '{"spec":{"maxReplicas":3}}'   # back to normal
node k8s/load-test.mjs --rate 300                                                     # where does it break?
```

If you lift the pin right after a run, the HPA still acts on the last CPU sample of the run: I saw it add a copy
40 s after the load had stopped, and remove it 2 minutes later.

## Exercise 3: the listener on its backlog (KEDA)

### How KEDA decides

The HPA only knows CPU and memory, and a listener's CPU stays low however long its queue gets: it mostly waits
for WSO2's answer. KEDA ([k8s/addons/keda](../k8s/addons/keda), installed by `deploy.mjs`) asks Kafka how far
behind the group is (the **lag**: events in the topic it hasn't processed) and runs an ordinary HPA with that
number ([k8s/listener/base/scaledobject.yaml](../k8s/listener/base/scaledobject.yaml)):

- **wanted copies = lag ÷ 500, rounded up**, at least 1, at most 3 (6 would be the useful limit: 6 partitions;
  3 because of memory), and only counts that split the 6 partitions evenly (1, 2, 3).
- The HPA asks every 15 s. Down only after 2 minutes with less lag, then 1 copy a minute, as for the branch.
- Kafka's tool `kafka-consumer-groups.sh --describe --group listener-a` shows the same lag (exercise 1a).
- With nothing waiting, `kubectl get hpa -n dc` shows `500/500`: KEDA reports "copies × 500" for the count it
  wants (1 × 500), so the HPA lands exactly on that count.

### Run a rush

Dashboard: rush count 10000, **Rush A** (or `curl -X POST "localhost:8090/rush/A?count=10000"`), then watch:

```bash
kubectl get hpa -n dc -w     # keda-hpa-listener-a: the copies KEDA wants
kubectl get so -n dc         # ACTIVE True while events wait
```

### What I measured (2026-10-19)

| | 1 listener (KEDA paused at 1) | KEDA in charge (1 → 3) |
|---|---|---|
| Reaction | | 8 s from the rush (10,000 events) to "New size: 3"; the new copies ready ~50 s later (busy node) |
| Delivery rate | **~90 events/s**: 10,000 in 116 s (in 8a: 120–130/s) | **~200 events/s** with 3 warm copies: a backlog of 28,445 drained in about 3 minutes |
| Back to 1 | | 2 minutes after the backlog was gone, then 1 copy a minute |
| Side effects | Branch A's own HPA went 1 → 3 (applying events costs CPU) | The same, plus: 3 listeners + 3 branch copies on 4 cores slowed WSO2-A to 4–5 s per answer and stalled the Kubernetes API for a moment (every port-forward dropped) |

Two runs did not go as planned, and I left them out of the table: one measured new copies while they were still
starting (no faster then), and in another two rushes overlapped. The 28,445-event backlog came from a sync that
the DC did not finish (point 4 below).

### What it shows

1. **The backlog is the right signal.** The listener's CPU stayed low the whole time; KEDA reacted to 9,000
   waiting events within 8 s.
2. **More listeners help, once warm**: ~200 events/s with 3 against ~90–130/s with 1, about 1.6–2×, not 3×: the
   listeners share WSO2-A and the branch, which become the limit. Each new copy also needs ~2 cores for ~30 s
   to start, which slows the others first.
3. **Scaling one part moves the load to the next.** The branch scaled itself because the listeners delivered
   faster; WSO2-A (one pod, not scaled here) became the slowest link. In a real system the gateways and the
   branch scale too, each on its own signal.
4. **Found on the way, not KEDA's fault:** the DC's sync loaded every pass of a checkpoint into memory at once.
   After many rushes (A grew to 83,144 passes at the DC) a sync ran the DC out of memory (`OOMKilled`), once also a
   rush on an already busy node. Fixed: sync, purge and rush work in chunks of 1,000, and the DC's JVM is sized
   for its container; a sync of 93,144 passes now takes 9–13 s and leaves the DC at ~385 of 448 MiB.

## Clean up

- Unpark branch B: `node k8s/park.mjs b --unpark`.
- A listener held by hand: `kubectl -n dc annotate so listener-a autoscaling.keda.sh/paused-replicas-`.
- A run removes the load test itself; after `--keep`: `kubectl delete -k k8s/optional/load-test`.
- A pinned HPA: `kubectl -n branch-a patch hpa branch-a --type merge -p '{"spec":{"maxReplicas":3}}'`.
