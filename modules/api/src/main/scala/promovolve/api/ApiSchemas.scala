package promovolve.api

import sttp.tapir.Schema

/** Tapir schemas for OpenAPI documentation (explicit to avoid inline limits) */
object ApiSchemas {
  import ApiModels.*

  // Common
  given Schema[ErrorResponse] = Schema.derived
  given Schema[Meta] = Schema.derived

  // Advertiser
  given Schema[Advertiser] = Schema.derived
  given Schema[AdvertiserList] = Schema.derived
  given Schema[BudgetStatus] = Schema.derived
  given Schema[AdvertiserDetail] = Schema.derived

  // Campaign
  given Schema[CampaignBudget] = Schema.derived
  given Schema[CampaignSchedule] = Schema.derived
  given Schema[CampaignBidding] = Schema.derived
  given Schema[Campaign] = Schema.derived
  given Schema[CampaignList] = Schema.derived
  given Schema[CreateCampaignRequest] = Schema.derived
  given Schema[UpdateCampaignRequest] = Schema.derived
  given Schema[CampaignBudgetStatus] = Schema.derived

  // Creative
  given Schema[CreativeAsset] = Schema.derived
  given Schema[CreativeContent] = Schema.derived
  given Schema[Creative] = Schema.derived
  given Schema[CreativeList] = Schema.derived

  // Publisher
  given Schema[Publisher] = Schema.derived
  given Schema[PublisherList] = Schema.derived
  given Schema[BlockDomainsRequest] = Schema.derived
  given Schema[UnblockDomainsRequest] = Schema.derived

  // Site
  given Schema[SiteCrawlConfig] = Schema.derived
  given Schema[SiteSlotConfig] = Schema.derived
  given Schema[Site] = Schema.derived
  given Schema[SiteList] = Schema.derived
  given Schema[CreateSiteRequest] = Schema.derived
  given Schema[UpdateSiteRequest] = Schema.derived
  given Schema[VerificationTokenResponse] = Schema.derived
  given Schema[VerificationResponse] = Schema.derived

  // Pacing Config
  given Schema[PacingConfig] = Schema.derived
  given Schema[UpdatePacingConfigRequest] = Schema.derived
  given Schema[UpdateSlotFloorRequest] = Schema.derived
  given Schema[FloorObservationResponse] = Schema.derived
  given Schema[RecentFloorObservationsResponse] = Schema.derived
  given Schema[FloorSweepCandidateResponse] = Schema.derived
  given Schema[FloorSweepEvidenceResponse] = Schema.derived
  given Schema[FloorDecisionRow] = Schema.derived
  given Schema[FloorSweepHistoryResponse] = Schema.derived
  given Schema[CategorySweep] = Schema.derived
  given Schema[CategoryDemand] = Schema.derived
  given Schema[CategoryDemandResponse] = Schema.derived
  given Schema[SiteRevenueTodayResponse] = Schema.derived
  given Schema[AdvertiserSpendTodayResponse] = Schema.derived

  // Internal (billing settlement)
  given Schema[MeteringDailyRow] = Schema.derived
  given Schema[MeteringDailyResponse] = Schema.derived
  given Schema[MeteringIntradayRow] = Schema.derived
  given Schema[MeteringIntradayResponse] = Schema.derived

  // Taxonomy
  given Schema[TaxonomyCategory] = Schema.derived
  given Schema[TaxonomyCategoryList] = Schema.derived
  given Schema[VerifiedSite] = Schema.derived
  given Schema[VerifiedSiteList] = Schema.derived
  given Schema[AdvertiserDomainList] = Schema.derived

  // Additional Advertiser
  given Schema[UpdateBudgetRequest] = Schema.derived
  given Schema[ServedSite] = Schema.derived
  given Schema[ServedSitesResponse] = Schema.derived

  // Additional Campaign
  given Schema[UpdateCampaignStatusRequest] = Schema.derived
  given Schema[AssignCreativesRequest] = Schema.derived
  given Schema[CampaignBudgetInfo] = Schema.derived
  given Schema[CampaignWinRateInfo] = Schema.derived
  given Schema[AdvertiserWinRatesResponse] = Schema.derived
  given Schema[CampaignSpendTodayRow] = Schema.derived
  given Schema[AdvertiserCampaignSpendTodayResponse] = Schema.derived
  given Schema[HourlyDeliveryRow] = Schema.derived
  given Schema[AdvertiserHourlyTodayResponse] = Schema.derived
  given Schema[ReportDailyRow] = Schema.derived
  given Schema[AdvertiserReportResponse] = Schema.derived
  given Schema[ReportBreakdownRow] = Schema.derived
  given Schema[AdvertiserReportBreakdownResponse] = Schema.derived
  given Schema[PublisherSiteCategoryRow] = Schema.derived
  given Schema[PublisherSiteCategoryReportResponse] = Schema.derived
  given Schema[ReportBreakdownByCampaignRow] = Schema.derived
  given Schema[AdvertiserReportBreakdownByCampaignResponse] = Schema.derived
  given Schema[ReportBreakdownDailyRow] = Schema.derived
  given Schema[AdvertiserReportBreakdownDailyResponse] = Schema.derived
  given Schema[PublisherSiteCategoryDailyRow] = Schema.derived
  given Schema[PublisherSiteCategoryDailyReportResponse] = Schema.derived
  given Schema[ReplenishBudgetRequest] = Schema.derived
  given Schema[ReplenishBudgetResponse] = Schema.derived

  // Approval Queue
  given Schema[PendingCreative] = Schema.derived
  given Schema[PendingCreativeList] = Schema.derived
  given Schema[PendingPlacement] = Schema.derived
  given Schema[PendingCreativeGroup] = Schema.derived
  given Schema[PendingCreativeGroupList] = Schema.derived
  given Schema[ServingPlacement] = Schema.derived
  given Schema[ServingCreativeGroup] = Schema.derived
  given Schema[ServingCreativeGroupList] = Schema.derived
  given Schema[ApproveCreativeRequest] = Schema.derived
  given Schema[ApproveCreativeResponse] = Schema.derived
  given Schema[RejectCreativeRequest] = Schema.derived
  given Schema[RejectCreativeResponse] = Schema.derived

  // Flagging / Quarantine
  given Schema[FlagCreativeRequest] = Schema.derived
  given Schema[FlagCreativeResponse] = Schema.derived
  given Schema[UnflagCreativeRequest] = Schema.derived
  given Schema[UnflagCreativeResponse] = Schema.derived
  given Schema[RevokeCreativeRequest] = Schema.derived
  given Schema[RevokeCreativeResponse] = Schema.derived
  given Schema[FlaggedCreative] = Schema.derived
  given Schema[FlaggedCreativeList] = Schema.derived

  // Page Classification
  given Schema[SlotSpec] = Schema.derived
  given Schema[ClassifyPageRequest] = Schema.derived
  given Schema[ClassifyPageResponse] = Schema.derived

  // Bulk Approval
  given Schema[BulkApproveRequest] = Schema.derived
  given Schema[BulkApproveResponse] = Schema.derived

  // Site Stats
  given Schema[SiteStats] = Schema.derived

  // AdServer Stats
  given Schema[CreativeStats] = Schema.derived
  given Schema[AdServerStatsResponse] = Schema.derived

  // Taxonomy Stats
  given Schema[TaxonomyStatsResponse] = Schema.derived

  // Pacing Reset
  given Schema[CampaignRef] = Schema.derived
  given Schema[PacingResetRequest] = Schema.derived
  given Schema[CampaignResetResult] = Schema.derived
  given Schema[PacingResetResponse] = Schema.derived

  // Serve Index
  given Schema[ServeIndexCandidate] = Schema.derived
  given Schema[ServeIndexResponse] = Schema.derived
  given Schema[ServeIndexKeysResponse] = Schema.derived

  // Auction / Category Registration
  given Schema[RegisterCampaignsRequest] = Schema.derived
  given Schema[RegisterCampaignsResponse] = Schema.derived

  // Magazine Creative (expandable banner). JsValue fields are opaque pass-through.
  given Schema[spray.json.JsValue] = Schema.string[spray.json.JsValue]
  given Schema[ExtractedBannerPage] = Schema.derived
  given Schema[CreateCreativeRequest] = Schema.derived
  given Schema[CreateCreativeResponse] = Schema.derived

  // LP Analysis
  given Schema[AnalyzeLPRequest] = Schema.derived
  given Schema[AnalyzeLPImage] = Schema.derived
  given Schema[AnalyzeLPSection] = Schema.derived
  given Schema[AnalyzeLPResponse] = Schema.derived
  given Schema[RewriteSectionInput] = Schema.derived
  given Schema[RewriteSectionsRequest] = Schema.derived
  given Schema[RewriteSectionsResponse] = Schema.derived
  given Schema[CropRect] = Schema.derived
  given Schema[LayoutItem] = Schema.derived
  given Schema[GenerateLayoutRequest] = Schema.derived
  given Schema[GenerateLayoutResponse] = Schema.derived
  given Schema[GenerateLayoutPairRequest] = Schema.derived
  given Schema[GenerateLayoutPairResponse] = Schema.derived
  given Schema[RewriteCopyRequest] = Schema.derived
  given Schema[RewriteCopyResponse] = Schema.derived
  given Schema[AdvertiserAssetView] = Schema.derived
  given Schema[AdvertiserAssetListResponse] = Schema.derived
  given Schema[UploadAssetRequest] = Schema.derived
  given Schema[UploadAssetResponse] = Schema.derived
  given Schema[PresignedUploadRequest] = Schema.derived
  given Schema[PresignedUploadResponse] = Schema.derived
  given Schema[RegisterAssetRequest] = Schema.derived
  given Schema[RegisterAssetResponse] = Schema.derived
  given Schema[ImportAssetUrl] = Schema.derived
  given Schema[ImportAssetUrlsRequest] = Schema.derived
  given Schema[ImportAssetUrlResult] = Schema.derived
  given Schema[ImportAssetUrlsResponse] = Schema.derived

  // IAB Ad Product Taxonomy
  given Schema[UpdateAdProductCategoryRequest] = Schema.derived
  given Schema[UpdateAdProductCategoryResponse] = Schema.derived
  given Schema[AdProductBlocklistRequest] = Schema.derived
  given Schema[AdProductBlocklistResponse] = Schema.derived
  given Schema[AdProductUnblockRequest] = Schema.derived
  given Schema[AdProductUnblockResponse] = Schema.derived

  // Category Verification
  given Schema[VerifyCategoryRequest] = Schema.derived
  given Schema[VerifyCategoryResponse] = Schema.derived

  // Creative Status
  given Schema[UpdateCreativeStatusRequest] = Schema.derived
  given Schema[UpdateCreativeStatusResponse] = Schema.derived

  // Login
  given Schema[LoginRequest] = Schema.derived
  given Schema[LoginResponse] = Schema.derived

  // Publisher Login
  given Schema[PublisherLoginRequest] = Schema.derived
  given Schema[PublisherLoginResponse] = Schema.derived
}
