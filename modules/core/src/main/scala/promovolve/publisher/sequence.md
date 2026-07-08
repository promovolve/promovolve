┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
│ PUBLISHER PACKAGE - COMPONENT OVERVIEW │
└─────────────────────────────────────────────────────────────────────────────────────────────────┘

                                      ┌──────────────────┐
                                      │ PublisherEntity  │  (DurableStateBehavior)
                                      │   per publisher  │
                                      │                  │
                                      │ • siteIds        │
                                      │ • domainBlocklist│
                                      └────────┬─────────┘
                                               │ RegisterSite / DeleteSite
                                               ▼
                                      ┌─────────────────┐
                                      │   SiteEntity    │  (DurableStateBehavior)
                                      │    per site     │
                                      │                 │
                                      │ • config        │
                                      │ • cronSchedule  │
                                      │ • taxonomyIds   │
                                      └────────┬────────┘
                                               │ Crawl results → AuctioneerEntity
                                               ▼

┌───────────────────────────────────────────────────────────────────────────────────────────────────┐
│ │
│ ┌─────────────┐ ┌────────────────┐ ┌───────────────────┐ ┌──────────────────┐ │
│ │ AdServer │◄────►│ServeIndexDData │ │ CreativeStore │ │ TaxonomyRanker │ │
│ │ per site │ │   (LWWMap)     │ │ (pending queue)   │ │ Entity │ │
│ │ │ │ │ │ │ │ │ │
│ │ • stats │ │ • ServeView │ │ • upsertPending │ │ • categoryScore │ │
│ │ • exhausted │ │ • CandidateView│ │ • getPending │ │ │ │
│ └─────────────┘ └────────────────┘ └───────────────────┘ └──────────────────┘ │
│ │ │
│ │ TryReserve │
│ ▼ │
│ ┌─────────────────┐ │
│ │ CampaignEntity │  (from advertiser/)                                                         │
│ │ per campaign │ │
│ │ │ │
│ │ • budget │ │
│ │ • spend │ │
│ └─────────────────┘ │
│ │
└───────────────────────────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
│ FLOW 1: SITE REGISTRATION │
└─────────────────────────────────────────────────────────────────────────────────────────────────┘

     API              PublisherEntity              SiteEntity                   DData
      │                     │                          │                          │
      │ RegisterSite(siteId)│                          │                          │
      │────────────────────>│                          │                          │
      │                     │                          │                          │
      │                     │  Register(publisherId)   │                          │
      │                     │─────────────────────────>│                          │
      │                     │                          │                          │
      │                     │  Registered(siteId)      │                          │
      │                     │<─────────────────────────│                          │
      │                     │                          │                          │
      │                     │  persist(state + siteId) │                          │
      │                     │                          │                          │
      │                     │  syncToDData(blocklist)  │                          │
      │                     │────────────────────────────────────────────────────>│
      │                     │                          │                          │
      │ SiteRegistered      │                          │                          │
      │<────────────────────│                          │                          │

┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
│ FLOW 2: CRAWL → CLASSIFICATION → AUCTION │
└─────────────────────────────────────────────────────────────────────────────────────────────────┘

     Quartz            SiteEntity              IABTaxonomy           AuctioneerEntity       AdServer
       │                   │                       │                       │                   │
       │ StartCrawling     │                       │                       │                   │
       │──────────────────>│                       │                       │                   │
       │                   │                       │                       │                   │
       │                   │ spawn Crawler         │                       │                   │
       │                   │──────►                │                       │                   │
       │                   │                       │                       │                   │
       │                   │ PageContent(url,text) │                       │                   │
       │                   │◄──────                │                       │                   │
       │                   │                       │                       │                   │
       │                   │ analyzeTaxonomy(text) │                       │                   │
       │                   │──────────────────────>│                       │                   │
       │                   │                       │                       │                   │
       │                   │ List[Selection]       │                       │                   │
       │                   │<──────────────────────│                       │                   │
       │                   │                       │                       │                   │
       │                   │ PageCategoriesClassified(url, categoryScores, slots)              │
       │                   │──────────────────────────────────────────────>│                   │
       │                   │                       │                       │                   │
       │                   │                       │ (run auction, get candidates)             │
       │                   │                       │                       │                   │
       │                   │                       │ CandidatesCollected(url, slot, candidates)
       │                   │                       │                       │──────────────────>│
       │                   │                       │                       │                   │
       │                   │                       │                       │  (filter by blocklist)
       │                   │                       │                       │  (split: approved/pending)
       │                   │                       │                       │                   │
       │                   │                       │                       │  ServeIndexDData.Put
       │                   │                       │                       │  (approved only)  │
       │                   │                       │                       │                   │
       │                   │                       │                       │  CreativeStore.upsertPending
       │                   │                       │                       │  (pending only)   │

┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
│ FLOW 3: AD SERVE (SELECT)                                          │
└─────────────────────────────────────────────────────────────────────────────────────────────────┘

     Client            ServeRoutes              AdServer           ServeIndexDData      CampaignEntity
       │                    │                      │                      │                   │
       │ GET /v1/serve      │                      │                      │                   │
       │ ?pub=X&url=Y&slot=Z│                      │                      │                   │
       │───────────────────>│                      │                      │                   │
       │                    │                      │                      │                   │
       │                    │ Select(url,slot)     │                      │                   │
       │                    │─────────────────────>│                      │                   │
       │                    │                      │                      │                   │
       │                    │                      │ Get(key)             │                   │
       │                    │                      │─────────────────────>│                   │
       │                    │                      │                      │                   │
       │                    │                      │ Option[ServeView]    │                   │
       │                    │                      │<─────────────────────│                   │
       │                    │                      │                      │                   │
       │                    │              ┌───────┴───────┐              │                   │
       │                    │              │ FILTER:       │              │                   │
       │                    │              │ 1. recency    │              │                   │
       │                    │              │ 2. exhausted  │              │                   │
       │                    │              │               │              │                   │
       │                    │              │ THOMPSON:     │              │                   │
       │                    │              │ Beta sampling │              │                   │
       │                    │              │ × log1p(cpm)  │              │                   │
       │                    │              └───────┬───────┘              │                   │
       │                    │                      │                      │                   │
       │                    │                      │ TryReserve(requestId, $0.005)            │
       │                    │                      │─────────────────────────────────────────>│
       │                    │                      │                      │                   │
       │                    │                      │                      │ ┌─────────────────┤
       │                    │                      │                      │ │ Atomic:         │
       │                    │                      │                      │ │ check + deduct  │
       │                    │                      │                      │ └─────────────────┤
       │                    │                      │                      │                   │
       │                    │                      │ Reserved | InsufficientBudget            │
       │                    │                      │<─────────────────────────────────────────│
       │                    │                      │                      │                   │
       │                    │ Selected(candidate, requestId)              │                   │
       │                    │<─────────────────────│                      │                   │
       │                    │                      │                      │                   │
       │                    │ (build impUrl with requestId)               │                   │
       │                    │                      │                      │                   │
       │ ServeRes(assetUrl, impUrl, clickUrl)      │                      │                   │
       │<───────────────────│                      │                      │                   │

┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
│ FLOW 4: APPROVAL WORKFLOW │
└─────────────────────────────────────────────────────────────────────────────────────────────────┘

     Dashboard          AdServer            CreativeStore       ServeIndexDData    AdvertiserEntity
        │                  │                      │                   │                   │
        │ Approve(cid)     │                      │                   │                   │
        │─────────────────>│                      │                   │                   │
        │                  │                      │                   │                   │
        │                  │ getPending()         │                   │                   │
        │                  │─────────────────────>│                   │                   │
        │                  │                      │                   │                   │
        │                  │ Some(Selection)      │                   │                   │
        │                  │<─────────────────────│                   │                   │
        │                  │                      │                   │                   │
        │                  │ (materialize asset)  │                   │                   │
        │                  │                      │                   │                   │
        │                  │ Append(key, candidateView, ttl)          │                   │
        │                  │─────────────────────────────────────────>│                   │
        │                  │                      │                   │                   │
        │                  │ UpdateCreativeApproval(cid, Approved)    │                   │
        │                  │─────────────────────────────────────────────────────────────>│
        │                  │                      │                   │                   │
        │                  │ CreativeApprovalUpdated                  │ (Bloom filter)    │
        │                  │<─────────────────────────────────────────────────────────────│
        │                  │                      │                   │                   │
        │                  │ removePending()      │                   │                   │
        │                  │─────────────────────>│                   │                   │
        │                  │                      │                   │                   │
        │ Success(assetPtr)│                      │                   │                   │
        │<─────────────────│                      │                   │                   │

┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
│ STATE SUMMARY │
└─────────────────────────────────────────────────────────────────────────────────────────────────┘

    ┌──────────────────────────────────────────────────────────────────────────────────────────────┐
    │ COMPONENT            │ STATE                        │ PERSISTENCE / REPLICATION              │
    ├──────────────────────┼──────────────────────────────┼────────────────────────────────────────┤
    │ PublisherEntity      │ siteIds, domainBlocklist     │ DurableStateBehavior + DData           │
    │ SiteEntity           │ publisherId, config          │ DurableStateBehavior                   │
    │ AdServer             │ creativeStats, exhausted     │ In-memory (ephemeral)                  │
    │ ServeIndexDData      │ ServeView per slot           │ DData LWWMap (replicated)              │
    │ CreativeStore        │ pending Selections           │ (implementation-specific)              │
    │ CampaignEntity       │ budget, spend                │ DurableStateBehavior (advertiser/)     │
    └──────────────────────┴──────────────────────────────┴────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
│ DATA FLOW SUMMARY │
└─────────────────────────────────────────────────────────────────────────────────────────────────┘

                                Crawl
                                  │
                                  ▼
     ┌─────────────────────────────────────────────────────────────────────────────────────┐
     │                                                                                     │
     │   [Crawler] ──PageContent──► [SiteEntity] ──classify──► [IABTaxonomy]               │
     │                                    │                                                │
     │                                    │ PageCategoriesClassified                       │
     │                                    ▼                                                │
     │                           [AuctioneerEntity] ──auction──► [CategoryBidderEntity]    │
     │                                    │                                                │
     │                                    │ CandidatesCollected                            │
     │                                    ▼                                                │
     │   [AdServer] ◄─────────────────────┘                                                │
     │       │                                                                             │
     │       ├──► [ServeIndexDData] (approved candidates)                                  │
     │       └──► [CreativeStore] (pending for approval)                                   │
     │                                                                                     │
     └─────────────────────────────────────────────────────────────────────────────────────┘

                                Serve
                                  │
                                  ▼
     ┌─────────────────────────────────────────────────────────────────────────────────────┐
     │                                                                                     │
     │   [Client] ──GET /serve──► [ServeRoutes] ──Select──► [AdServer]                     │
     │                                                          │                          │
     │                                                          │ Get(key)                 │
     │                                                          ▼                          │
     │                                                   [ServeIndexDData]                 │
     │                                                          │                          │
     │                                                          │ ServeView                │
     │                                                          ▼                          │
     │   [AdServer] ◄───────────────────────────────────────────┘                          │
     │       │                                                                             │
     │       │ Thompson Sampling (per-creative stats)                                      │
     │       │ Filter exhausted campaigns                                                  │
     │       │                                                                             │
     │       │ TryReserve (atomic budget check)                                            │
     │       ▼                                                                             │
     │   [CampaignEntity]                                                                  │
     │       │                                                                             │
     │       │ Reserved / InsufficientBudget                                               │
     │       ▼                                                                             │
     │   [AdServer] ──Selected──► [ServeRoutes] ──ServeRes──► [Client]                     │
     │                                                                                     │
     └─────────────────────────────────────────────────────────────────────────────────────┘

