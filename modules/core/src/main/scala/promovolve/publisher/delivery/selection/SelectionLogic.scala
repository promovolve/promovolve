package promovolve.publisher.delivery.selection

import promovolve.publisher.CandidateView

/** Pure helper functions for ad selection pipeline.
  *
  * Contains stateless logic extracted from AdServer for testability
  * and reuse. All functions are pure - no side effects.
  */
object SelectionLogic {

  /** Filter candidates by content recency.
    *
    * Removes candidates whose content was classified longer ago than
    * the specified window allows.
    *
    * @param candidates          All candidates from ServeIndex
    * @param nowMs               Current time in milliseconds
    * @param recencyWindowMs     Maximum age in milliseconds
    * @return Candidates within the recency window
    */
  def filterByRecency(
      candidates: Vector[CandidateView],
      nowMs: Long,
      recencyWindowMs: Long
  ): Vector[CandidateView] =
    candidates.filter { c =>
      val ageMs = nowMs - c.classifiedAtMs
      ageMs <= recencyWindowMs
    }

  /** Calculate average CPM per campaign from candidates.
    *
    * @param candidates All candidates
    * @param defaultCpm Default CPM if a campaign has no candidates
    * @return Map from campaign ID to average CPM
    */
  def calculateAvgCpmByCampaign(
      candidates: Vector[CandidateView],
      defaultCpm: Double = 5.0
  ): Map[String, Double] =
    candidates
      .groupBy(_.campaignId.value)
      .view
      .mapValues { cands =>
        val cpms = cands.map(_.cpm.toDouble)
        if (cpms.nonEmpty) cpms.sum / cpms.size else defaultCpm
      }
      .toMap

  /** Get unique campaign IDs from candidates.
    *
    * @param candidates All candidates
    * @return Distinct campaign IDs
    */
  def uniqueCampaignIds(candidates: Vector[CandidateView]): Seq[String] =
    candidates.map(_.campaignId.value).distinct

  /** Filter candidates to only those from eligible campaigns.
    *
    * @param candidates     All candidates
    * @param eligibleCampIds Campaign IDs that passed pacing check
    * @return Candidates from eligible campaigns
    */
  def filterByCampaigns(
      candidates: Vector[CandidateView],
      eligibleCampIds: Set[String]
  ): Vector[CandidateView] =
    candidates.filter(c => eligibleCampIds.contains(c.campaignId.value))

  /** Calculate estimated spend for a candidate.
    *
    * @param candidate The winning candidate
    * @return Estimated spend amount (CPM / 1000)
    */
  def estimatedSpend(candidate: CandidateView): Double =
    candidate.cpm.toDouble / 1000.0
}
