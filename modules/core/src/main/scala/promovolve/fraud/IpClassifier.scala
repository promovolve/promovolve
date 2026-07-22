package promovolve.fraud

import java.io.{ BufferedReader, InputStream, InputStreamReader }
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import scala.collection.mutable.ArrayBuffer

/**
 * IP → datacenter/residential classification for request hygiene
 * (docs/design/FRAUD_PREVENTION.md, Layer 0).
 *
 * Data source is the free iptoasn.com combined TSV
 * (`start\tend\tasn\tcountry\tdescription`, textual IPs, v4 + v6).
 * A range is "datacenter" when its ASN is in the curated hosting set
 * OR its AS description matches the hosting-name heuristic — decided
 * once at LOAD time and stored as a per-range flag, so the per-request
 * path is a binary search plus an array read. No allocation, no locks.
 *
 * Classification is advisory: callers MARK suspect traffic (excluded
 * from money and learning) and never block on it. Unknown means
 * "database not loaded or IP unrouted" and must be treated as clean —
 * an empty classifier fails open by design.
 */
object IpClassifier {

  enum IpClass:
    case Residential, Datacenter, Unknown

  /**
   * Curated hosting/cloud ASNs — the operators whose address space is
   * overwhelmingly machines, not people. The name heuristic below
   * catches the long tail; this set pins the giants whose AS names
   * don't say "hosting" (nobody calls AWS a VPS shop).
   */
  private[fraud] val CuratedDatacenterAsns: Set[Int] = Set(
    16509, 14618, // Amazon AWS
    396982, 15169, // Google Cloud / Google
    8075, // Microsoft Azure
    31898, // Oracle Cloud
    45102, // Alibaba Cloud
    132203, // Tencent Cloud
    14061, // DigitalOcean
    16276, // OVH
    24940, 213230, // Hetzner
    63949, // Linode/Akamai
    20473, // Vultr/Choopa
    51167, // Contabo
    197540, // netcup
    9009, // M247
    212238, // Datacamp/CDN77
    60068, // Datacamp
    46606, // Unified Layer / Bluehost
    26496, // GoDaddy
    36351, // IBM SoftLayer
    19318, // Interserver
    55286 // B2 Net Solutions / Servermania
  )

  /**
   * Hosting-name heuristic over the AS description. Deliberately
   * conservative: terms that appear in consumer-ISP names ("net",
   * "telecom", "cable") are absent; false negatives are cheap (the
   * economics layer still sees the traffic), false positives poison
   * honest stats.
   */
  private[fraud] val DatacenterNamePattern =
    "(?i)(hosting|hosted|colocat|datacenter|data center|dedicated server|vps|cloud comput|server farm|serverion|leaseweb|packethub|clouvider)".r

  /** Immutable loaded database; swap the whole reference on refresh. */
  final class IpDb private[IpClassifier] (
      v4Start: Array[Long],
      v4End: Array[Long],
      v4Dc: Array[Boolean],
      v6Start: Array[Array[Byte]],
      v6End: Array[Array[Byte]],
      v6Dc: Array[Boolean]
  ) {
    def size: Int = v4Start.length + v6Start.length

    def classify(ip: String): IpClass =
      parseIp(ip) match {
        case None           => IpClass.Unknown
        case Some(Left(v4)) =>
          val i = floorIndex(v4Start, v4)
          if (i >= 0 && v4 <= v4End(i)) toClass(v4Dc(i)) else IpClass.Unknown
        case Some(Right(v6)) =>
          val i = floorIndex6(v6Start, v6)
          if (i >= 0 && compareBytes(v6, v6End(i)) <= 0) toClass(v6Dc(i)) else IpClass.Unknown
      }

    private def toClass(dc: Boolean): IpClass =
      if (dc) IpClass.Datacenter else IpClass.Residential
  }

  /** The empty database: everything Unknown (fail-open). */
  val empty: IpDb = new IpDb(Array.empty, Array.empty, Array.empty, Array.empty, Array.empty, Array.empty)

  /**
   * Parse an iptoasn combined TSV stream (plain or, via [[loadGzip]],
   * gzip). Ranges with ASN 0 (unrouted) are skipped — an unrouted
   * source classifies Unknown, which is the honest answer.
   */
  def load(in: InputStream): IpDb = {
    val reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
    val v4s = new ArrayBuffer[(Long, Long, Boolean)](700000)
    val v6s = new ArrayBuffer[(Array[Byte], Array[Byte], Boolean)](200000)
    var line = reader.readLine()
    while (line != null) {
      val cols = line.split('\t')
      if (cols.length >= 5 && cols(2) != "0") {
        val asn = cols(2).toIntOption.getOrElse(0)
        if (asn != 0) {
          val dc = CuratedDatacenterAsns.contains(asn) ||
            DatacenterNamePattern.findFirstIn(cols(4)).isDefined
          (parseIp(cols(0)), parseIp(cols(1))) match {
            case (Some(Left(s)), Some(Left(e)))   => v4s += ((s, e, dc))
            case (Some(Right(s)), Some(Right(e))) => v6s += ((s, e, dc))
            case _                                => () // mixed/garbled row — skip
          }
        }
      }
      line = reader.readLine()
    }
    val v4sorted = v4s.sortBy(_._1)
    val v6sorted = v6s.sortWith((a, b) => compareBytes(a._1, b._1) < 0)
    new IpDb(
      v4sorted.map(_._1).toArray,
      v4sorted.map(_._2).toArray,
      v4sorted.map(_._3).toArray,
      v6sorted.map(_._1).toArray,
      v6sorted.map(_._2).toArray,
      v6sorted.map(_._3).toArray
    )
  }

  def loadGzip(in: InputStream): IpDb = load(new GZIPInputStream(in))

  /** v4 → unsigned int in a Long; v6 → 16 raw bytes. None = not an IP literal. */
  private[fraud] def parseIp(s: String): Option[Either[Long, Array[Byte]]] = {
    // InetAddress.getByName performs DNS for hostnames — refuse anything
    // that isn't shaped like an address literal before calling it.
    val looksLiteral = s.nonEmpty && s.forall(c => c.isDigit || c == '.' || c == ':' || isHexLetter(c))
    if (!looksLiteral) None
    else
      try {
        val addr = InetAddress.getByName(s).getAddress
        if (addr.length == 4)
          Some(Left(java.lang.Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(addr).getInt)))
        else Some(Right(addr))
      } catch { case _: Exception => None }
  }

  private def isHexLetter(c: Char): Boolean = (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')

  /** Index of the greatest start <= key, or -1. */
  private def floorIndex(starts: Array[Long], key: Long): Int = {
    var lo = 0
    var hi = starts.length - 1
    var res = -1
    while (lo <= hi) {
      val mid = (lo + hi) >>> 1
      if (starts(mid) <= key) { res = mid; lo = mid + 1 }
      else hi = mid - 1
    }
    res
  }

  private def floorIndex6(starts: Array[Array[Byte]], key: Array[Byte]): Int = {
    var lo = 0
    var hi = starts.length - 1
    var res = -1
    while (lo <= hi) {
      val mid = (lo + hi) >>> 1
      if (compareBytes(starts(mid), key) <= 0) { res = mid; lo = mid + 1 }
      else hi = mid - 1
    }
    res
  }

  /** Unsigned lexicographic compare of equal-length byte arrays. */
  private[fraud] def compareBytes(a: Array[Byte], b: Array[Byte]): Int = {
    var i = 0
    var cmp = 0
    while (cmp == 0 && i < a.length && i < b.length) {
      cmp = java.lang.Integer.compare(a(i) & 0xFF, b(i) & 0xFF)
      i += 1
    }
    if (cmp != 0) cmp else java.lang.Integer.compare(a.length, b.length)
  }
}
