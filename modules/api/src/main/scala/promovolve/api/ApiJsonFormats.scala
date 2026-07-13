package promovolve.api

import spray.json.*

/** Spray JSON formats for API models */
trait ApiJsonFormats extends DefaultJsonProtocol {
  import ApiModels.*

  // Common
  given RootJsonFormat[ErrorResponse] = jsonFormat3(ErrorResponse.apply)
  given RootJsonFormat[Meta] = jsonFormat3(Meta.apply)

  // Advertiser
  given RootJsonFormat[Advertiser] = jsonFormat5(Advertiser.apply)
  given RootJsonFormat[AdvertiserList] = jsonFormat2(AdvertiserList.apply)
  given RootJsonFormat[BudgetStatus] = jsonFormat4(BudgetStatus.apply)
  given RootJsonFormat[AdvertiserDetail] = jsonFormat8(AdvertiserDetail.apply)

  // Campaign
  given RootJsonFormat[CampaignBudget] = jsonFormat2(CampaignBudget.apply)
  given RootJsonFormat[CampaignSchedule] = jsonFormat2(CampaignSchedule.apply)
  given RootJsonFormat[CampaignBidding] = jsonFormat2(CampaignBidding.apply)
  given RootJsonFormat[Campaign] = jsonFormat22(Campaign.apply)
  given RootJsonFormat[CampaignList] = jsonFormat2(CampaignList.apply)
  given RootJsonFormat[CreateCampaignRequest] = jsonFormat8(CreateCampaignRequest.apply)
  given RootJsonFormat[UpdateCampaignRequest] = jsonFormat10(UpdateCampaignRequest.apply)
  given RootJsonFormat[CampaignBudgetStatus] = jsonFormat3(CampaignBudgetStatus.apply)

  // Creative
  given RootJsonFormat[CreativeAsset] = jsonFormat2(CreativeAsset.apply)
  given RootJsonFormat[CreativeContent] = jsonFormat5(CreativeContent.apply)
  given RootJsonFormat[Creative] = jsonFormat12(Creative.apply)
  given RootJsonFormat[CreativeList] = jsonFormat2(CreativeList.apply)

  // Publisher
  given RootJsonFormat[Publisher] = jsonFormat6(Publisher.apply)
  given RootJsonFormat[PublisherList] = jsonFormat2(PublisherList.apply)
  given RootJsonFormat[BlockDomainsRequest] = jsonFormat1(BlockDomainsRequest.apply)
  given RootJsonFormat[UnblockDomainsRequest] = jsonFormat1(UnblockDomainsRequest.apply)

  // Site
  given RootJsonFormat[SiteCrawlConfig] = jsonFormat6(SiteCrawlConfig.apply)
  given RootJsonFormat[SiteSlotConfig] = jsonFormat8(SiteSlotConfig.apply)
  given RootJsonFormat[Site] = new RootJsonFormat[Site] {
    def write(s: Site): JsValue = JsObject(
      "id" -> JsString(s.id),
      "publisherId" -> JsString(s.publisherId),
      "domain" -> JsString(s.domain),
      "status" -> JsString(s.status),
      "crawlConfig" -> summon[RootJsonFormat[SiteCrawlConfig]].write(s.crawlConfig),
      "slots" -> JsArray(s.slots.map(summon[RootJsonFormat[SiteSlotConfig]].write).toVector),
      "taxonomyIds" -> JsArray(s.taxonomyIds.map(JsString(_)).toVector),
      "createdAt" -> JsString(s.createdAt),
      "updatedAt" -> JsString(s.updatedAt),
      "verificationStatus" -> JsString(s.verificationStatus),
      "floorCpm" -> JsString(s.floorCpm),
      "minFloorCpm" -> JsString(s.minFloorCpm),
      "bidWeight" -> JsString(s.bidWeight),
      "acceptsFillerTraffic" -> JsBoolean(s.acceptsFillerTraffic)
    )
    def read(v: JsValue): Site = {
      val o = v.asJsObject.fields
      Site(
        id = o("id").convertTo[String],
        publisherId = o("publisherId").convertTo[String],
        domain = o("domain").convertTo[String],
        status = o("status").convertTo[String],
        crawlConfig = o("crawlConfig").convertTo[SiteCrawlConfig],
        slots = o.get("slots").map(_.convertTo[Vector[SiteSlotConfig]]).getOrElse(Vector.empty),
        taxonomyIds = o.get("taxonomyIds").map(_.convertTo[Vector[String]]).getOrElse(Vector.empty),
        createdAt = o("createdAt").convertTo[String],
        updatedAt = o("updatedAt").convertTo[String],
        verificationStatus = o.get("verificationStatus").map(_.convertTo[String]).getOrElse("unverified"),
        floorCpm = o.get("floorCpm").map(_.convertTo[String]).getOrElse("0.50"),
        minFloorCpm = o.get("minFloorCpm").map(_.convertTo[String]).getOrElse("0.10"),
        bidWeight = o.get("bidWeight").map(_.convertTo[String]).getOrElse("0.50"),
        acceptsFillerTraffic = o.get("acceptsFillerTraffic").map(_.convertTo[Boolean]).getOrElse(true)
      )
    }
  }
  given RootJsonFormat[SiteList] = jsonFormat2(SiteList.apply)
  given RootJsonFormat[CreateSiteRequest] = jsonFormat6(CreateSiteRequest.apply)
  given RootJsonFormat[UpdateSiteRequest] = jsonFormat9(UpdateSiteRequest.apply)
  given RootJsonFormat[VerificationTokenResponse] = jsonFormat6(VerificationTokenResponse.apply)
  given RootJsonFormat[VerificationResponse] = jsonFormat3(VerificationResponse.apply)

  // Pacing Config
  given RootJsonFormat[PacingConfig] = jsonFormat4(PacingConfig.apply)
  given RootJsonFormat[UpdatePacingConfigRequest] = jsonFormat4(UpdatePacingConfigRequest.apply)
  given RootJsonFormat[UpdateSlotFloorRequest] = jsonFormat1(UpdateSlotFloorRequest.apply)
  given RootJsonFormat[FloorObservationResponse] = jsonFormat9(FloorObservationResponse.apply)
  given RootJsonFormat[RecentFloorObservationsResponse] = jsonFormat2(RecentFloorObservationsResponse.apply)
  given RootJsonFormat[FloorSweepCandidateResponse] = jsonFormat3(FloorSweepCandidateResponse.apply)
  given RootJsonFormat[FloorSweepEvidenceResponse] = jsonFormat10(FloorSweepEvidenceResponse.apply)
  given RootJsonFormat[FloorDecisionRow] = jsonFormat7(FloorDecisionRow.apply)
  given RootJsonFormat[FloorSweepHistoryResponse] = jsonFormat2(FloorSweepHistoryResponse.apply)
  given RootJsonFormat[CategorySweep] = jsonFormat7(CategorySweep.apply)
  given RootJsonFormat[CategoryDemand] = jsonFormat6(CategoryDemand.apply)
  given RootJsonFormat[CategoryDemandResponse] = jsonFormat2(CategoryDemandResponse.apply)
  given RootJsonFormat[SiteRevenueTodayResponse] = jsonFormat5(SiteRevenueTodayResponse.apply)
  given RootJsonFormat[AdvertiserSpendTodayResponse] = jsonFormat5(AdvertiserSpendTodayResponse.apply)

  // Internal (billing settlement)
  given RootJsonFormat[MeteringDailyRow] = jsonFormat6(MeteringDailyRow.apply)
  given RootJsonFormat[MeteringDailyResponse] = jsonFormat2(MeteringDailyResponse.apply)
  given RootJsonFormat[MeteringIntradayRow] = jsonFormat2(MeteringIntradayRow.apply)
  given RootJsonFormat[MeteringIntradayResponse] = jsonFormat2(MeteringIntradayResponse.apply)

  // Taxonomy
  given RootJsonFormat[TaxonomyCategory] = jsonFormat3(TaxonomyCategory.apply)
  given RootJsonFormat[TaxonomyCategoryList] = jsonFormat2(TaxonomyCategoryList.apply)
  given RootJsonFormat[VerifiedSite] = jsonFormat2(VerifiedSite.apply)
  given RootJsonFormat[VerifiedSiteList] = jsonFormat2(VerifiedSiteList.apply)
  given RootJsonFormat[AdvertiserDomainList] = jsonFormat2(AdvertiserDomainList.apply)

  // Additional Advertiser
  given RootJsonFormat[UpdateBudgetRequest] = jsonFormat1(UpdateBudgetRequest.apply)
  given RootJsonFormat[ServedSite] = jsonFormat3(ServedSite.apply)
  given RootJsonFormat[ServedSitesResponse] = jsonFormat1(ServedSitesResponse.apply)

  // LP Creative Extraction
  given RootJsonFormat[ExtractFromLPRequest] = jsonFormat1(ExtractFromLPRequest.apply)
  given RootJsonFormat[ExtractedBannerPage] = jsonFormat14(ExtractedBannerPage.apply)
  given RootJsonFormat[ExtractFromLPResponse] = jsonFormat2(ExtractFromLPResponse.apply)

  // LP Analysis (structural section extraction)
  given RootJsonFormat[AnalyzeLPRequest] = jsonFormat3(AnalyzeLPRequest.apply)
  given RootJsonFormat[AnalyzeLPImage] = jsonFormat4(AnalyzeLPImage.apply)
  given RootJsonFormat[AnalyzeLPSection] = jsonFormat3(AnalyzeLPSection.apply)
  given RootJsonFormat[OriginalFontOffer] = jsonFormat3(OriginalFontOffer.apply)
  given RootJsonFormat[AnalyzeLPResponse] = jsonFormat7(AnalyzeLPResponse.apply)
  given RootJsonFormat[AnalyzeLPJob] = jsonFormat1(AnalyzeLPJob.apply)
  given RootJsonFormat[AnalyzeLPStatusResponse] = jsonFormat4(AnalyzeLPStatusResponse.apply)

  // Rewrite sections
  given RootJsonFormat[RewriteSectionInput] = jsonFormat2(RewriteSectionInput.apply)
  given RootJsonFormat[RewriteSectionsRequest] = jsonFormat5(RewriteSectionsRequest.apply)
  given RootJsonFormat[RewriteSectionsResponse] = jsonFormat4(RewriteSectionsResponse.apply)

  // Generate layout
  given RootJsonFormat[CropRect] = jsonFormat4(CropRect.apply)
  given RootJsonFormat[LayoutItem] = jsonFormat20(LayoutItem.apply)
  given RootJsonFormat[GenerateLayoutRequest] = jsonFormat5(GenerateLayoutRequest.apply)
  given RootJsonFormat[GenerateLayoutResponse] = jsonFormat1(GenerateLayoutResponse.apply)
  given RootJsonFormat[GenerateLayoutPairRequest] = jsonFormat1(GenerateLayoutPairRequest.apply)
  given RootJsonFormat[GenerateLayoutPairResponse] = jsonFormat2(GenerateLayoutPairResponse.apply)
  given RootJsonFormat[RewriteCopyRequest] = jsonFormat2(RewriteCopyRequest.apply)
  given RootJsonFormat[RewriteCopyResponse] = jsonFormat4(RewriteCopyResponse.apply)

  // Advertiser assets
  given RootJsonFormat[AdvertiserAssetView] = jsonFormat7(AdvertiserAssetView.apply)
  given RootJsonFormat[AdvertiserAssetListResponse] = jsonFormat1(AdvertiserAssetListResponse.apply)
  given RootJsonFormat[UploadAssetRequest] = jsonFormat5(UploadAssetRequest.apply)
  given RootJsonFormat[UploadAssetResponse] = jsonFormat1(UploadAssetResponse.apply)
  given RootJsonFormat[PresignedUploadRequest] = jsonFormat4(PresignedUploadRequest.apply)
  given RootJsonFormat[PresignedUploadResponse] = jsonFormat3(PresignedUploadResponse.apply)
  given RootJsonFormat[RegisterAssetRequest] = jsonFormat5(RegisterAssetRequest.apply)
  given RootJsonFormat[RegisterAssetResponse] = jsonFormat1(RegisterAssetResponse.apply)
  given RootJsonFormat[ImportAssetUrl] = jsonFormat2(ImportAssetUrl.apply)
  given RootJsonFormat[ImportAssetUrlsRequest] = jsonFormat1(ImportAssetUrlsRequest.apply)
  given RootJsonFormat[ImportAssetUrlResult] = jsonFormat2(ImportAssetUrlResult.apply)
  given RootJsonFormat[ImportAssetUrlsResponse] = jsonFormat1(ImportAssetUrlsResponse.apply)

  // Additional Campaign
  given RootJsonFormat[UpdateCampaignStatusRequest] = jsonFormat1(UpdateCampaignStatusRequest.apply)
  given RootJsonFormat[AssignCreativesRequest] = jsonFormat1(AssignCreativesRequest.apply)
  given RootJsonFormat[CampaignBudgetInfo] = jsonFormat4(CampaignBudgetInfo.apply)
  given RootJsonFormat[CampaignWinRateInfo] = jsonFormat4(CampaignWinRateInfo.apply)
  given RootJsonFormat[AdvertiserWinRatesResponse] = jsonFormat3(AdvertiserWinRatesResponse.apply)
  given RootJsonFormat[CampaignSpendTodayRow] = jsonFormat3(CampaignSpendTodayRow.apply)
  given RootJsonFormat[AdvertiserCampaignSpendTodayResponse] = jsonFormat3(AdvertiserCampaignSpendTodayResponse.apply)
  given RootJsonFormat[HourlyDeliveryRow] = jsonFormat3(HourlyDeliveryRow.apply)
  given RootJsonFormat[AdvertiserHourlyTodayResponse] = jsonFormat3(AdvertiserHourlyTodayResponse.apply)
  given RootJsonFormat[ReportDailyRow] = jsonFormat11(ReportDailyRow.apply)
  given RootJsonFormat[AdvertiserReportResponse] = jsonFormat4(AdvertiserReportResponse.apply)
  given RootJsonFormat[ReportBreakdownRow] = jsonFormat7(ReportBreakdownRow.apply)
  given RootJsonFormat[AdvertiserReportBreakdownResponse] = jsonFormat6(AdvertiserReportBreakdownResponse.apply)
  given RootJsonFormat[PublisherSiteCategoryRow] = jsonFormat8(PublisherSiteCategoryRow.apply)
  given RootJsonFormat[PublisherSiteCategoryReportResponse] = jsonFormat5(PublisherSiteCategoryReportResponse.apply)
  given RootJsonFormat[ReportBreakdownByCampaignRow] = jsonFormat8(ReportBreakdownByCampaignRow.apply)
  given RootJsonFormat[AdvertiserReportBreakdownByCampaignResponse] =
    jsonFormat5(AdvertiserReportBreakdownByCampaignResponse.apply)
  given RootJsonFormat[ReportBreakdownDailyRow] = jsonFormat8(ReportBreakdownDailyRow.apply)
  given RootJsonFormat[AdvertiserReportBreakdownDailyResponse] =
    jsonFormat5(AdvertiserReportBreakdownDailyResponse.apply)
  given RootJsonFormat[PublisherSiteCategoryDailyRow] = jsonFormat9(PublisherSiteCategoryDailyRow.apply)
  given RootJsonFormat[PublisherSiteCategoryDailyReportResponse] =
    jsonFormat4(PublisherSiteCategoryDailyReportResponse.apply)
  given RootJsonFormat[ReplenishBudgetRequest] = jsonFormat1(ReplenishBudgetRequest.apply)
  given RootJsonFormat[ReplenishBudgetResponse] = jsonFormat3(ReplenishBudgetResponse.apply)

  // Approval Queue
  given RootJsonFormat[PendingCreative] = jsonFormat12(PendingCreative.apply)
  given RootJsonFormat[PendingCreativeList] = jsonFormat2(PendingCreativeList.apply)
  given RootJsonFormat[PendingPlacement] = jsonFormat6(PendingPlacement.apply)
  given RootJsonFormat[PendingCreativeGroup] = jsonFormat18(PendingCreativeGroup.apply)
  given RootJsonFormat[PendingCreativeGroupList] = jsonFormat2(PendingCreativeGroupList.apply)
  given RootJsonFormat[ServingPlacement] = jsonFormat5(ServingPlacement.apply)
  given RootJsonFormat[ServingCreativeGroup] = jsonFormat15(ServingCreativeGroup.apply)
  given RootJsonFormat[ServingCreativeGroupList] = jsonFormat2(ServingCreativeGroupList.apply)
  given RootJsonFormat[ApproveCreativeRequest] = jsonFormat3(ApproveCreativeRequest.apply)
  given RootJsonFormat[ApproveCreativeResponse] = jsonFormat3(ApproveCreativeResponse.apply)
  given RootJsonFormat[RejectCreativeRequest] = jsonFormat4(RejectCreativeRequest.apply)
  given RootJsonFormat[RejectCreativeResponse] = jsonFormat4(RejectCreativeResponse.apply)

  // Flagging / Quarantine
  given RootJsonFormat[FlagCreativeRequest] = jsonFormat4(FlagCreativeRequest.apply)
  given RootJsonFormat[FlagCreativeResponse] = jsonFormat2(FlagCreativeResponse.apply)
  given RootJsonFormat[UnflagCreativeRequest] = jsonFormat1(UnflagCreativeRequest.apply)
  given RootJsonFormat[UnflagCreativeResponse] = jsonFormat2(UnflagCreativeResponse.apply)
  given RootJsonFormat[RevokeCreativeRequest] = jsonFormat1(RevokeCreativeRequest.apply)
  given RootJsonFormat[RevokeCreativeResponse] = jsonFormat2(RevokeCreativeResponse.apply)
  given RootJsonFormat[FlaggedCreative] = jsonFormat14(FlaggedCreative.apply)
  given RootJsonFormat[FlaggedCreativeList] = jsonFormat2(FlaggedCreativeList.apply)

  // Page Classification
  given RootJsonFormat[SlotSpec] = jsonFormat3(SlotSpec.apply)
  given RootJsonFormat[ClassifyPageRequest] = jsonFormat3(ClassifyPageRequest.apply)
  given RootJsonFormat[ClassifyPageResponse] = jsonFormat4(ClassifyPageResponse.apply)

  // Bulk Approval
  given RootJsonFormat[BulkApproveRequest] = jsonFormat2(BulkApproveRequest.apply)
  given RootJsonFormat[BulkApproveResponse] = jsonFormat4(BulkApproveResponse.apply)

  // Site Stats
  given RootJsonFormat[SiteStats] = jsonFormat14(SiteStats.apply)

  // AdServer Stats
  given RootJsonFormat[CreativeStats] = jsonFormat6(CreativeStats.apply)
  given RootJsonFormat[AdServerStatsResponse] = jsonFormat2(AdServerStatsResponse.apply)

  // Taxonomy Stats
  given RootJsonFormat[TaxonomyStatsResponse] = jsonFormat7(TaxonomyStatsResponse.apply)

  // Pacing Reset
  given RootJsonFormat[CampaignRef] = jsonFormat2(CampaignRef.apply)
  given RootJsonFormat[PacingResetRequest] = jsonFormat1(PacingResetRequest.apply)
  given RootJsonFormat[CampaignResetResult] = jsonFormat3(CampaignResetResult.apply)
  given RootJsonFormat[PacingResetResponse] = jsonFormat2(PacingResetResponse.apply)

  // Serve Index
  given RootJsonFormat[ServeIndexCandidate] = jsonFormat7(ServeIndexCandidate.apply)
  given RootJsonFormat[ServeIndexResponse] = jsonFormat4(ServeIndexResponse.apply)
  given RootJsonFormat[ServeIndexKeysResponse] = jsonFormat2(ServeIndexKeysResponse.apply)

  // Auction / Category Registration
  given RootJsonFormat[RegisterCampaignsRequest] = jsonFormat1(RegisterCampaignsRequest.apply)
  given RootJsonFormat[RegisterCampaignsResponse] = jsonFormat3(RegisterCampaignsResponse.apply)

  // Magazine Creative (expandable banner)
  given RootJsonFormat[CreateCreativeRequest] = jsonFormat9(CreateCreativeRequest.apply)
  given RootJsonFormat[CreateCreativeResponse] = jsonFormat3(CreateCreativeResponse.apply)

  // IAB Ad Product Taxonomy
  given RootJsonFormat[UpdateAdProductCategoryRequest] = jsonFormat1(UpdateAdProductCategoryRequest.apply)
  given RootJsonFormat[UpdateAdProductCategoryResponse] = jsonFormat2(UpdateAdProductCategoryResponse.apply)
  given RootJsonFormat[AdProductBlocklistRequest] = jsonFormat1(AdProductBlocklistRequest.apply)
  given RootJsonFormat[AdProductBlocklistResponse] = jsonFormat2(AdProductBlocklistResponse.apply)
  given RootJsonFormat[AdProductUnblockRequest] = jsonFormat1(AdProductUnblockRequest.apply)
  given RootJsonFormat[AdProductUnblockResponse] = jsonFormat2(AdProductUnblockResponse.apply)

  // Site Auto-Approve (trust anchors)
  given RootJsonFormat[TrustAnchorDetail] = jsonFormat6(TrustAnchorDetail.apply)
  given RootJsonFormat[AutoApproveSettingsResponse] = jsonFormat5(AutoApproveSettingsResponse.apply)
  given RootJsonFormat[UpdateAutoApproveRequest] = jsonFormat1(UpdateAutoApproveRequest.apply)
  given RootJsonFormat[RemoveTrustAnchorRequest] = jsonFormat2(RemoveTrustAnchorRequest.apply)
  given RootJsonFormat[RemoveTrustAnchorResponse] = jsonFormat2(RemoveTrustAnchorResponse.apply)

  // Category Verification
  given RootJsonFormat[VerifyCategoryRequest] = jsonFormat3(VerifyCategoryRequest.apply)
  given RootJsonFormat[VerifyCategoryResponse] = jsonFormat2(VerifyCategoryResponse.apply)

  // Creative Status
  given RootJsonFormat[UpdateCreativeStatusRequest] = jsonFormat1(UpdateCreativeStatusRequest.apply)
  given RootJsonFormat[UpdateCreativeStatusResponse] = jsonFormat3(UpdateCreativeStatusResponse.apply)

  // Login
  given RootJsonFormat[LoginRequest] = jsonFormat1(LoginRequest.apply)
  given RootJsonFormat[LoginResponse] = jsonFormat3(LoginResponse.apply)

  // Publisher Login
  given RootJsonFormat[PublisherLoginRequest] = jsonFormat1(PublisherLoginRequest.apply)
  given RootJsonFormat[PublisherLoginResponse] = jsonFormat3(PublisherLoginResponse.apply)
}

object ApiJsonFormats extends ApiJsonFormats
