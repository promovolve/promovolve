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
                                      │ • classifications│
                                      │ • taxonomyIds   │
                                      └────────┬────────┘
                                               │ ClassifyUrl (ad tag) → Gemini →
                                               │ PageCategoriesClassified → AuctioneerEntity
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
│ FLOW 2: ON-DEMAND CLASSIFICATION → AUCTION │
└─────────────────────────────────────────────────────────────────────────────────────────────────┘

    Ad tag           ServeRoutes            SiteEntity          Gemini/IABTaxonomy   AuctioneerEntity      AdServer
       │                   │                     │                       │                  │                  │
       │ POST /v1/classify-page                  │                       │                  │                  │
       │ {pub,url,text,slots}                    │                       │                  │                  │
       │──────────────────>│                     │                       │                  │                  │
       │                   │                     │                       │                  │                  │
       │                   │ ClassifyUrl(url,text,section,slots)         │                  │                  │
       │                   │────────────────────>│                       │                  │                  │
       │                   │                     │                       │                  │                  │
       │                   │ ClassifyAck         │ (single-flight per URL:                  │                  │
       │                   │<────────────────────│  in_flight/not_ready → no Gemini call)   │                  │
       │ 202 Accepted      │                     │                       │                  │                  │
       │<──────────────────│                     │                       │                  │                  │
       │                   │                     │ analyzeTaxonomy(text) │                  │                  │
       │                   │                     │──────────────────────>│                  │                  │
       │                   │                     │                       │                  │                  │
       │                   │                     │ category selections   │                  │                  │
       │                   │                     │<──────────────────────│                  │                  │
       │                   │                     │                       │                  │                  │
       │                   │                     │ (persist classification)                 │                  │
       │                   │                     │                       │                  │                  │
       │                   │                     │ PageCategoriesClassified(url, categoryScores, slots)        │
       │                   │                     │─────────────────────────────────────────>│                  │
       │                   │                     │  (or FillerAuctionRequested when no      │                  │
       │                   │                     │   demand category matches)               │                  │
       │                   │                     │                       │                  │                  │
       │                   │                     │                       │ (run auction, get candidates)       │
       │                   │                     │                       │                  │                  │
       │                   │                     │                       │ CandidatesCollected(url, slot, candidates)
       │                   │                     │                       │                  │─────────────────>│
       │                   │                     │                       │                  │                  │
       │                   │                     │                       │                  │ (filter by blocklist)
       │                   │                     │                       │                  │ (split: approved/pending)
       │                   │                     │                       │                  │                  │
       │                   │                     │                       │                  │ ServeIndexDData.Put
       │                   │                     │                       │                  │ (approved only)  │
       │                   │                     │                       │                  │                  │
       │                   │                     │                       │                  │ CreativeStore.upsertPending
       │                   │                     │                       │                  │ (pending only)   │

┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
│ FLOW 3: AD SERVE (BATCH SELECT)                                     │
└─────────────────────────────────────────────────────────────────────────────────────────────────┘

     Client            ServeRoutes              AdServer           ServeIndexDData      CampaignEntity
       │                    │                      │                      │                   │
       │ POST /v1/serve/batch                      │                      │                   │
       │ {pub, url, imp:[slots], pins?}            │                      │                   │
       │───────────────────>│                      │                      │                   │
       │                    │                      │                      │                   │
       │                    │ BatchSelect(url,slots)                      │                   │
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
       │                    │              │ × CPM^α       │              │                   │
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

                        On-demand classification
                                  │
                                  ▼
     ┌─────────────────────────────────────────────────────────────────────────────────────┐
     │                                                                                     │
     │   [Ad tag] ──POST /v1/classify-page──► [SiteEntity] ──classify──► [Gemini/IABTaxonomy]│
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
     │   [Client] ──POST /v1/serve/batch──► [ServeRoutes] ──BatchSelect──► [AdServer]      │
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

