#!/bin/bash
# Shows what happens when a branch is down during a sync:
#   listener retries -> gives up -> event goes to the dead-letter topic -> branch back -> sync again.
# Run with the stack up:  bash scripts/demo-outage.sh
export MSYS_NO_PATHCONV=1
set -u

since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
echo "1. Stopping branch A"
docker stop bdc-branch-a >/dev/null

echo "2. DC: sync branch A"
event=$(curl -s -X POST localhost:8090/sync/A | grep -o '"eventId":"[^"]*"' | cut -d'"' -f4)
echo "   event $event"

echo "3. Listener A tries, retries, gives up (up to 60 s):"
for _ in $(seq 1 30); do
  docker logs --since "$since" bdc-listener-a 2>&1 | grep -q "Giving up" && break
  sleep 2
done
docker logs --since "$since" bdc-listener-a 2>&1 | grep -E "Delivering|Giving up" | sed -E 's/^([^ ]+) .* : /   \1 /' | cut -c1-200

echo "4. On dc.branch-a.DLT, with the reason in its headers:"
docker exec bdc-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9094 \
  --topic dc.branch-a.DLT --from-beginning --timeout-ms 5000 --formatter-property print.headers=true 2>/dev/null \
  > "${TMPDIR:-/tmp}/dlt.txt"
# The stack-trace header spans many lines, so the short headers are picked out by name.
grep -c "$event" "${TMPDIR:-/tmp}/dlt.txt" | sed 's/^/   records with this event id: /'
grep -aoE "kafka_dlt-(original-topic|exception-message):[^,]*" "${TMPDIR:-/tmp}/dlt.txt" | tail -2 | sed 's/^/   /' | cut -c1-160

echo "5. Starting branch A again and syncing"
docker start bdc-branch-a >/dev/null
until [ "$(docker inspect -f '{{.State.Health.Status}}' bdc-branch-a)" = healthy ]; do sleep 2; done
curl -s -X POST localhost:8090/sync/A | grep -o '"status":"[A-Z]*"' | sed 's/^/   DC: /'
sleep 3
docker logs --since "$since" bdc-branch-a 2>&1 | grep "Applied" | sed -E 's/^.* : /   branch A: /'
