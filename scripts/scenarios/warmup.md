Warmup Workflow

1. Start the server

sbt "project api" run

2. Enable warmup mode

curl -X PUT http://localhost:8080/v1/publishers/publisher-1/sites/YOUR_SITE_ID/pacing \
-H "Content-Type: application/json" \
-d '{"warmupMode": true, "dayDurationSeconds": 600}'
This enables 10-minute simulated days with no ads served (only traffic recording).

3. Generate traffic with ClientTraffic

scala-cli scripts/ClientTraffic.scala -- \
--site YOUR_SITE_ID --slot slot-1 \
--urls "https://example.com/page1" \
--continuous --day-duration 600
Run for ~70 minutes (7 simulated days) to learn weekday/weekend patterns.

4. Fetch learned shapes

curl http://localhost:8080/test/site-stats/YOUR_SITE_ID | jq
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
