package promovolve.fraud

/**
 * The pure economics-detector core (docs/design/FRAUD_PREVENTION.md,
 * Layer 2). Turns per-site daily metrics into flags using robust
 * statistics — no ML, no I/O, fully unit-tested. The sharded actor and
 * SQL aggregation are thin shells around this.
 *
 * Robust-by-design: median/MAD instead of mean/stddev, so a single
 * fraudulent spike can't inflate the baseline it's measured against
 * (an attacker who ramps slowly moves a median far more visibly than a
 * gradient). Every signal is gated on a minimum sample so low-traffic
 * noise never trips a flag — the paramount "don't poison honest sites"
 * rule.
 */
object FraudDetection {

  /**
   * One site's activity for a single local day. `pageviews` is the
   * mount-beacon row count — a per-pageview-PROPORTIONAL proxy (the
   * tag fires a few beacons per view), used only in ratios compared
   * against the site's own history, where the constant cancels.
   */
  final case class SiteDay(
      impressions: Long,
      clicks: Long,
      pageviews: Long,
      suspect: Long,
      total: Long
  )

  /** A site's latest day plus its trailing history (excluding latest). */
  final case class SiteMetrics(
      siteId: String,
      latestDay: java.time.LocalDate,
      latest: SiteDay,
      history: Vector[SiteDay]
  )

  final case class Flag(
      siteId: String,
      signal: String, // "suspect_share" | "imp_per_pageview" | "ctr_spike"
      severity: Double, // z-score or ratio — bigger = worse
      evidence: String // human-readable metric snapshot
  )

  final case class Config(
      // Absolute guards: never flag below these (kills small-sample noise).
      minEvents: Long = 500,
      minPageviews: Long = 200,
      minHistoryDays: Int = 5,
      // Suspect-share: fraction of a site's events the system marked.
      suspectShareThreshold: Double = 0.30,
      // Robust-z trip level for ratio anomalies (median absolute deviations).
      zThreshold: Double = 3.5
  )

  /** Signal names (also the `signal` column values). */
  val SigSuspectShare = "suspect_share"
  val SigImpPerPageview = "imp_per_pageview"
  val SigCtrSpike = "ctr_spike"

  /** Per-site daily event aggregate, straight from the SQL rollup. */
  final case class EventDay(
      siteId: String,
      day: java.time.LocalDate,
      impressions: Long,
      clicks: Long,
      suspect: Long,
      total: Long
  )

  /**
   * Join the tracking-event rollup with the mount-beacon pageview
   * counts into per-site metrics: each site's most-recent day is
   * `latest`, all earlier days are `history` (newest-first). Pure — the
   * repo does the two SQL reads and hands the rows here.
   */
  def assembleMetrics(
      events: Vector[EventDay],
      pageviews: Map[(String, java.time.LocalDate), Long]
  ): Vector[SiteMetrics] =
    events
      .groupBy(_.siteId)
      .toVector
      .flatMap { case (siteId, rows) =>
        val byDayDesc = rows.sortBy(_.day.toEpochDay).reverse
        byDayDesc match {
          case Nil             => None
          case latest +: older =>
            def toSiteDay(e: EventDay): SiteDay =
              SiteDay(
                impressions = e.impressions,
                clicks = e.clicks,
                pageviews = pageviews.getOrElse((e.siteId, e.day), 0L),
                suspect = e.suspect,
                total = e.total
              )
            Some(SiteMetrics(siteId, latest.day, toSiteDay(latest), older.map(toSiteDay)))
        }
      }

  // ── Robust statistics ───────────────────────────────────────────────

  def median(xs: Vector[Double]): Option[Double] =
    if (xs.isEmpty) None
    else {
      val s = xs.sorted
      val n = s.length
      Some(if (n % 2 == 1) s(n / 2) else (s(n / 2 - 1) + s(n / 2)) / 2.0)
    }

  /**
   * Median absolute deviation, scaled to be a consistent σ estimator
   * for normal data (×1.4826). None when undefined.
   */
  def mad(xs: Vector[Double]): Option[Double] =
    median(xs).flatMap { m =>
      median(xs.map(x => math.abs(x - m))).map(_ * 1.4826)
    }

  /**
   * Robust z of `value` against `series`. None when the series is too
   * short or degenerate (zero spread) — a zero-MAD baseline can't
   * distinguish signal from noise, so it must not flag. A tiny floor
   * on the denominator avoids divide-by-near-zero blow-ups.
   */
  def robustZ(value: Double, series: Vector[Double], minLen: Int): Option[Double] =
    if (series.length < minLen) None
    else
      for {
        m <- median(series)
        d <- mad(series)
        if d > 1e-9
      } yield (value - m) / d

  // ── Signal evaluation ───────────────────────────────────────────────

  private def ratio(num: Long, den: Long): Option[Double] =
    if (den <= 0) None else Some(num.toDouble / den.toDouble)

  /**
   * Evaluate one site's latest day. Returns every signal that tripped
   * (most sites: empty). Only one-directional anomalies flag — a DROP
   * in CTR or impressions/pageview is not fraud, so negative z's are
   * ignored.
   */
  def evaluate(m: SiteMetrics, cfg: Config): Vector[Flag] = {
    val d = m.latest
    val flags = Vector.newBuilder[Flag]

    // 1. Suspect share — the system's own Layer 0/1 marks. A high
    //    fraction is a direct bot signal; hard threshold, no baseline
    //    needed (gated on volume so a handful of events can't trip it).
    if (d.total >= cfg.minEvents) {
      ratio(d.suspect, d.total).foreach { share =>
        if (share >= cfg.suspectShareThreshold)
          flags += Flag(m.siteId, SigSuspectShare, share,
            f"suspect ${d.suspect}/${d.total} = ${share * 100}%.1f%% of events (>= ${cfg.suspectShareThreshold * 100}%.0f%%)")
      }
    }

    // 2. Impressions per pageview vs the site's own trailing history.
    //    A spike = impressions without matching real pageviews = the
    //    canonical self-inflation shape.
    if (d.pageviews >= cfg.minPageviews) {
      val cur = ratio(d.impressions, d.pageviews)
      val hist = m.history.flatMap(h => ratio(h.impressions, h.pageviews))
      (cur, robustZ(cur.getOrElse(0.0), hist, cfg.minHistoryDays)) match {
        case (Some(c), Some(z)) if z >= cfg.zThreshold =>
          flags += Flag(m.siteId, SigImpPerPageview, z,
            f"imp/pageview ${c}%.2f is z=${z}%.1f above the site's own median (${median(hist).getOrElse(0.0)}%.2f)")
        case _ => ()
      }
    }

    // 3. CTR spike vs the site's own history — scripted engagement.
    if (d.impressions >= cfg.minEvents) {
      val cur = ratio(d.clicks, d.impressions)
      val hist = m.history.flatMap(h => ratio(h.clicks, h.impressions))
      (cur, robustZ(cur.getOrElse(0.0), hist, cfg.minHistoryDays)) match {
        case (Some(c), Some(z)) if z >= cfg.zThreshold =>
          flags += Flag(m.siteId, SigCtrSpike, z,
            f"CTR ${c * 100}%.2f%% is z=${z}%.1f above the site's own median")
        case _ => ()
      }
    }

    flags.result()
  }
}
