#!/bin/bash
# Checks the network rules between the "environments" (Docker networks) by opening TCP connections
# from inside the containers. Run with the stack up:  bash scripts/check-isolation.sh
# Exit code 0 = every rule holds.
export MSYS_NO_PATHCONV=1   # Git Bash on Windows: do not rewrite /dev/tcp paths

failures=0

# expect <from-container> <host> <port> <reachable|blocked>
expect() {
  local from="$1" host="$2" port="$3" want="$4" got
  if docker exec "$from" bash -c "timeout 3 bash -c 'exec 3<>/dev/tcp/$host/$port'" 2>/dev/null; then
    got=reachable
  else
    got=blocked
  fi
  if [ "$got" = "$want" ]; then
    printf '  ok    %-16s -> %-16s %-10s\n' "$from" "$host:$port" "$got"
  else
    printf '  FAIL  %-16s -> %-16s %-10s (expected %s)\n' "$from" "$host:$port" "$got" "$want"
    failures=$((failures + 1))
  fi
}

echo "Branches cannot reach the DC environment or the other branch:"
expect bdc-branch-a dc-service 8090 blocked
expect bdc-branch-a kafka      29092 blocked
expect bdc-branch-a listener-b 8080 blocked
expect bdc-branch-a branch-b   8080 blocked
expect bdc-branch-b dc-service 8090 blocked
expect bdc-branch-b kafka      29092 blocked
expect bdc-branch-b branch-a   8080 blocked

echo "Not just DNS: the DC's and Kafka's IP addresses are unreachable from a branch too:"
dc_ip=$(docker inspect -f '{{(index .NetworkSettings.Networks "branch-dc_dc-net").IPAddress}}' bdc-dc-service)
kafka_ip=$(docker inspect -f '{{(index .NetworkSettings.Networks "branch-dc_dc-net").IPAddress}}' bdc-kafka)
expect bdc-branch-a "$dc_ip"    8090 blocked
expect bdc-branch-a "$kafka_ip" 29092 blocked

echo "Nor through ports published on your machine (the DC and Kafka are published for you, not for branches):"
expect bdc-branch-a host.docker.internal 8090 blocked
expect bdc-branch-a host.docker.internal 9094 blocked
expect bdc-branch-b host.docker.internal 8090 blocked

echo "Each listener reaches Kafka and its own branch only:"
expect bdc-listener-a kafka    29092 reachable
expect bdc-listener-a branch-a 8080 reachable
expect bdc-listener-a branch-b 8080 blocked
expect bdc-listener-b kafka    29092 reachable
expect bdc-listener-b branch-b 8080 reachable
expect bdc-listener-b branch-a 8080 blocked

echo "Each branch reaches the shared Oracle server (its own PDB; see the PDB login checks):"
expect bdc-branch-a oracle 1521 reachable
expect bdc-branch-b oracle 1521 reachable

if [ "$failures" -eq 0 ]; then echo "All rules hold."; else echo "$failures rule(s) broken."; fi
exit "$failures"
