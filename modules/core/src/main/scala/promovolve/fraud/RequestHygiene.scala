package promovolve.fraud

import java.util.concurrent.atomic.AtomicReference

/**
 * Composes the Layer-0 request-hygiene detectors into a single
 * classify call for the serve/track hot path
 * (docs/design/FRAUD_PREVENTION.md).
 *
 * Returns the FIRST matching suspect reason (or None = clean) in a
 * deterministic priority order: datacenter ASN, then bot UA, then rate
 * cap. The reason is advisory — callers mark, never block.
 *
 * The IP database is swappable at runtime (monthly refresh) via an
 * AtomicReference, so a reload never blocks classification. Absent a
 * loaded db every IP is Unknown → clean, so an un-provisioned
 * deployment fails open and serves normally.
 *
 * `rateKey`/`allow` take an ALREADY-HASHED key — raw IPs must not be
 * retained (see the design doc's privacy rule). Classification hashes
 * internally for the rate bucket and never stores the raw address.
 */
final class RequestHygiene(
    initialDb: IpClassifier.IpDb,
    rateGate: RequestRateGate,
    enabled: Boolean = true
) {
  private val dbRef = new AtomicReference[IpClassifier.IpDb](initialDb)

  /** Swap the IP database (monthly refresh). Non-blocking. */
  def updateDb(db: IpClassifier.IpDb): Unit = dbRef.set(db)

  /**
   * ISO 3166-1 country for this request's source, or None when the db is
   * absent or the address unrouted.
   *
   * Lives here rather than at the call site so there is exactly one
   * reference to the loaded db, and so the country never travels further
   * than the one caller that tallies it (tier 3 of
   * docs/design/GEOGRAPHIC_CONTEXT.md). Nothing about this feeds the
   * suspect decision — an ad may not be chosen from it.
   */
  def countryOf(ip: String): Option[String] = dbRef.get().countryOf(ip)

  /**
   * True when a real ASN database is loaded.
   *
   * Callers that COUNT rather than mark need this: with the empty db every
   * lookup returns None, and a tally of zero is indistinguishable from a
   * site with no foreign readers. Better to run the counter not at all
   * than to publish a confidently empty distribution.
   */
  def hasDb: Boolean = dbRef.get().size > 0

  def dbSize: Int = dbRef.get().size

  /**
   * Classify one request. `ip` is the raw client address (used for
   * ASN lookup and, hashed, for the rate bucket — never retained).
   * Returns the suspect reason to stamp on the event, or None.
   */
  def classify(ip: String, userAgent: Option[String]): Option[String] =
    if (!enabled) None
    else if (IpClassifier.IpClass.Datacenter == dbRef.get().classify(ip)) Some(Suspect.DatacenterAsn)
    else if (BotUaMatcher.isBot(userAgent)) Some(Suspect.BotUa)
    else if (ip.nonEmpty && !rateGate.allow(bucketKey(ip))) Some(Suspect.RateCap)
    else None

  /** Stable per-IP bucket key; the raw IP is hashed, not stored. */
  private def bucketKey(ip: String): String =
    Integer.toHexString(ip.hashCode)
}

object RequestHygiene {

  /**
   * A permissive instance: everything clean. For tests and for
   * deployments that haven't provisioned the ASN database yet.
   */
  def disabled: RequestHygiene =
    new RequestHygiene(IpClassifier.empty, new RequestRateGate(Double.MaxValue, Double.MaxValue), enabled = false)
}
