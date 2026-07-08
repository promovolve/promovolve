  ==========================================
   Platform Setup
  ==========================================
   Base URL:    http://localhost:8080
   Site ID:     site-32730-456
   Slot ID:     slot-header
   Categories:  sports, tech, gaming
   Advertisers: 2 per category
   Budget:      $100.0 per advertiser
   CPM:         $5.0
   Pacing:      AdaptivePacing (peak/off-peak aware)
  ==========================================

  [1/4] Creating advertisers and campaigns...
         Created: adv-sports-1-32730-456 -> campaign=01KE5SVK15MTYVR7BWJEPAWRG7, creative=01KE5SVK32XPYNG649C9PWQXPM (targets sports)
         Created: adv-sports-2-32730-456 -> campaign=01KE5SVK5FM3T4XSGBK0XENHH6, creative=01KE5SVK5YTY3FTPC6A26HYH4H (targets sports)
         Created: adv-tech-1-32730-456 -> campaign=01KE5SVK72CQS91YJ6YE1B0F23, creative=01KE5SVK7H84643P02P1C6A0TP (targets tech)
         Created: adv-tech-2-32730-456 -> campaign=01KE5SVK8QYCZEK0T70TYEYCWP, creative=01KE5SVK99S3Z1HER8BPXMTD6Y (targets tech)
         Created: adv-gaming-1-32730-456 -> campaign=01KE5SVKA8NT7DP1MHG4WVG0TR, creative=01KE5SVKAYVBFCYT9DBN1Q02Z6 (targets gaming)
         Created: adv-gaming-2-32730-456 -> campaign=01KE5SVKBY2PB1KHVFAQ72W80B, creative=01KE5SVKCFAFJF76NDP9XAAJ12 (targets gaming)

  [2/4] Triggering auctions...
         Auction triggered: https://publisher.com/sports-article-32730-456 (category: sports)
         Auction triggered: https://publisher.com/tech-article-32730-456 (category: tech)
         Auction triggered: https://publisher.com/gaming-article-32730-456 (category: gaming)

  [3/4] Approving all pending creatives...
         https://publisher.com/sports-article-32730-456 -> approved: 2, failed: 0
         https://publisher.com/tech-article-32730-456 -> approved: 2, failed: 0
         https://publisher.com/gaming-article-32730-456 -> approved: 2, failed: 0

  [4/4] Verifying ServeIndex...
         https://publisher.com/sports-article-32730-456 -> 2 candidates
         https://publisher.com/tech-article-32730-456 -> 2 candidates
         https://publisher.com/gaming-article-32730-456 -> 2 candidates


  ==========================================
   Setup Complete
  ==========================================
   Site ID:  site-32730-456
   Slot ID:  slot-header
   Pages:    3
   Total candidates in ServeIndex: 6
  ------------------------------------------

   Ready for ClientTraffic.scala:

     scala-cli scripts/ClientTraffic.scala -- \
       --site "site-32730-456" \
       --slot "slot-header" \
       --urls "https://publisher.com/sports-article-32730-456,https://publisher.com/tech-article-32730-456,https://publisher.com/gaming-article-32730-456"

  ==========================================

   curl -X PATCH http://localhost:8080/v1/advertisers/adv-tech-2-32730-456/campaigns/01KE5SVK8QYCZEK0T70TYEYCWP \
      -H "Content-Type: application/json" \
      -d '{
        "budget": {"daily": "200.0000"},
        "bidding": {"strategy": "fixed", "maxCpm": "5.0000"},
        "targeting": {"categories": ["gaming", "tech"], "sites": {"include": [], "exclude": []}}
      }'

