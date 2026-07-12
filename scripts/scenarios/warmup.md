Warmup Workflow

1. Start the stack

Deploy to the local k8s cluster (see k8s/README.md) — core API on :8080.
(For a one-off single-node dev run, scripts/run-dev.sh works too.)

2. Enable warmup mode

curl -X PUT http://localhost:8080/v1/publishers/publisher-1/sites/YOUR_SITE_ID/pacing \
-H "Content-Type: application/json" \
-d '{"warmupMode": true, "dayDurationSeconds": 600}'
This enables 10-minute simulated days with no ads served (only traffic recording).

3. Generate traffic

Warmup only needs serve requests hitting the site — no campaigns required,
nothing is served. Two drivers:

- RunScenario (scala-cli), self-contained — use a scenario JSON with
  "continuous": true and "dayDurationSeconds": 600 (matching step 2):

  scala-cli scripts/RunScenario.scala -- --scenario scenarios/continuous.json

- simulate-traffic (Go), against a real page that carries your slots
  (it discovers data-promovolve-slot divs and POSTs /v1/serve/batch):

  go run ./platform/cmd/simulate-traffic -pub YOUR_SITE_ID \
    -site http://localhost:8888 -api http://localhost:8080/v1 \
    -workers 2 -interval 1s

Run for ~70 minutes (7 simulated days) to learn weekday/weekend patterns.

4. Fetch learned shapes

curl http://localhost:8080/v1/publishers/publisher-1/sites/YOUR_SITE_ID/stats | jq
The response now includes:
- weekdayShapeVolumes: 24 hourly values for weekday traffic
- weekendShapeVolumes: 24 hourly values for weekend traffic

5. Create scenario file with learned shapes

Copy the learned shapes into a scenario JSON:
{
"pacing": {
"dayDurationSeconds": 600,
"weekdayShapeVolumes": [...from step 4...],
"weekendShapeVolumes": [...from step 4...]
}
}

6. Disable warmup and run with shapes

curl -X PUT http://localhost:8080/v1/publishers/publisher-1/sites/YOUR_SITE_ID/pacing \
-H "Content-Type: application/json" \
-d '{"warmupMode": false}'

scala-cli scripts/RunScenario.scala -- --scenario scenarios/your_scenario.json
