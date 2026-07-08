curl -X PATCH http://localhost:8080/v1/advertisers/adv-1-31486-652/campaigns/01KF00AM2PW1KESN14D3TKVFRQ \
-H "Content-Type: application/json" \
-d '{"bidding": {"strategy": "fixed", "maxCpm": ".0"}}'

# Change budget
curl -X PATCH http://localhost:8080/v1/advertisers/adv-1-31486-652/campaigns/01KF00AM2PW1KESN14D3TKVFRQ \
-H "Content-Type: application/json" \
-d '{"budget": {"daily": "100.0"}}'

# Update ADVERTISER budget (not campaign)
curl -X PUT http://localhost:8080/v1/advertisers/adv-1-31486-652/budget \
-H "Content-Type: application/json" \
-d '{"dailyBudget": "1000.0"}'

