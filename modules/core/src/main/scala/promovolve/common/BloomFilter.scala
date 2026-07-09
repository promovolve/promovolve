package promovolve.common

import promovolve.SiteId

import java.nio.{ ByteBuffer, ByteOrder }
import java.util.UUID
import scala.util.Try

/**
 * Cuckoo filter operations for site rejection tracking.
 *
 * Supports deletion, making rejections reversible.
 *
 * Fingerprint size:
 * - rejectedSites: 16-bit (~1 in 10,000 FP) - FP just means lost revenue
 */
extension (serializedFilter: Array[Byte]) {

  /** Best-effort deserialize. Returns None for empty/corrupted filters (fail-safe). */
  private def safeDeserialize(bytes: Array[Byte]): Option[CuckooFilter] =
    Option.when(bytes.nonEmpty)(bytes).flatMap(b => Try(CuckooFilter.fromBytes(b)).toOption)

  // --- SiteId-based membership (publisher approval tracking) ---

  /** Check if site ID is in the filter. */
  def mightContain(siteId: SiteId): Boolean =
    safeDeserialize(serializedFilter).exists(_.mightContain(siteId.value))

  /**
   * Add site ID to rejected filter (16-bit fingerprints).
   * FP on rejectedSites just means lost revenue - acceptable trade-off.
   */
  def addRejected(
      siteId: SiteId,
      expectedInsertions: Int = 2000
  ): Array[Byte] = {
    val fpp = 0.0001 // 16-bit fingerprints: ~1 in 10,000 FP rate
    val filter = safeDeserialize(serializedFilter)
      .getOrElse(CuckooFilter(expectedInsertions, fpp))
    filter.insert(siteId.value)
    filter.toBytes
  }

  /** Generic add - prefer addRejected for site rejection tracking. */
  def add(
      siteId: SiteId,
      expectedInsertions: Int = 2000,
      fpp: Double = 0.0001
  ): Array[Byte] = {
    val filter = safeDeserialize(serializedFilter)
      .getOrElse(CuckooFilter(expectedInsertions, fpp))
    filter.insert(siteId.value)
    filter.toBytes
  }

  /** Remove site ID from filter and return new serialized filter. */
  def remove(siteId: SiteId): Array[Byte] = {
    safeDeserialize(serializedFilter) match {
      case Some(filter) =>
        filter.delete(siteId.value)
        filter.toBytes
      case None =>
        serializedFilter
    }
  }

  /** Check if this filter supports deletion (always true for Cuckoo). */
  def supportsDeletion: Boolean = serializedFilter.nonEmpty

  // --- UUID-based membership (idempotency) ---

  /** Check if UUID might be in the filter. */
  def mightContainUUID(uuid: UUID): Boolean =
    safeDeserialize(serializedFilter).exists(_.mightContain(uuid.toString))

  /**
   * Add multiple UUIDs to filter efficiently.
   * Returns new serialized filter.
   */
  def addUUIDs(
      uuids: IterableOnce[UUID],
      expectedInsertions: Int = 50000,
      fpp: Double = 0.001
  ): Array[Byte] = {
    val filter = safeDeserialize(serializedFilter)
      .getOrElse(CuckooFilter(expectedInsertions, fpp))
    uuids.iterator.foreach(uuid => filter.insert(uuid.toString))
    filter.toBytes
  }
}

/** String extension for hashing */
extension (s: String) {

  /** Hash String to Long */
  def hash: Long = {
    val seed = s.hashCode.toLong
    val reversed = s.reverse.hashCode.toLong
    var h = seed ^ (reversed * 0x9E3779B97F4A7C15L)
    h ^= (h >>> 33)
    h *= 0xFF51AFD7ED558CCDL
    h ^= (h >>> 33)
    h *= 0xC4CEB9FE1A85EC53L
    h ^= (h >>> 33)
    h
  }
}

// =============================================================================
// BloomFilter - kept for ReplayGuard internal use (mutable, bucket rotation)
// =============================================================================

/**
 * Probabilistic data structure for efficient membership testing.
 *
 * Not thread-safe: intended for single-threaded / actor-confined use.
 * Used internally by ReplayGuard for nonce deduplication with bucket rotation.
 *
 * @param mBits The total number of bits in the Bloom filter's bit array.
 * @param k The number of hash functions used.
 * @param words The underlying array of 64-bit words that stores the bitset.
 */
final class BloomFilter private[common] (
    val mBits: Int,
    val k: Int,
    private val words: Array[Long]
) {

  require(mBits > 0, s"mBits must be > 0, got $mBits")
  require(k > 0, s"k must be > 0, got $k")
  require(
    words.length == ((mBits + 63) >>> 6),
    s"words length (${words.length}) does not match mBits ($mBits)"
  )

  /** Add an element to the Bloom filter. */
  def add(x: Long): Unit = {
    val (h1, h2) = hashes(x)
    var i = 0
    while (i < k) {
      val bit = bitIndex(h1, h2, i)
      words(wordIx(bit)) |= bitMask(bit)
      i += 1
    }
  }

  @inline private def hashes(x: Long): (Long, Long) = {
    val z1 = mix64(x ^ 0x9E3779B97F4A7C15L)
    val z2 = mix64(x ^ 0x94D049BB133111EBL)
    (z1 & 0x7FFFFFFFFFFFFFFFL, z2 & 0x7FFFFFFFFFFFFFFFL)
  }

  @inline private def mix64(z0: Long): Long = {
    var z = z0
    z ^= (z >>> 33)
    z *= 0xFF51AFD7ED558CCDL
    z ^= (z >>> 33)
    z *= 0xC4CEB9FE1A85EC53L
    z ^= (z >>> 33)
    z
  }

  @inline private def wordIx(bit: Int): Int = bit >>> 6

  @inline private def bitMask(bit: Int): Long = 1L << (bit & 63)

  @inline private def bitIndex(h1: Long, h2: Long, i: Int): Int = {
    val mix = h1 + (i.toLong * h2)
    val nonNegative = mix & 0x7FFFFFFFFFFFFFFFL
    (nonNegative % mBits).toInt
  }

  /** Test if an element might be in the set (may have false positives). */
  def maybeContains(x: Long): Boolean = {
    val (h1, h2) = hashes(x)
    var i = 0
    var ok = true
    while (i < k && ok) {
      val bit = bitIndex(h1, h2, i)
      ok = (words(wordIx(bit)) & bitMask(bit)) != 0L
      i += 1
    }
    ok
  }

  /** Create an immutable snapshot of the internal bit array. */
  def snapshotWords: Array[Long] = java.util.Arrays.copyOf(words, words.length)

  /** Load state from a snapshot, zeroing any tail elements. */
  def loadFromArray(a: Array[Long]): Unit = {
    val n = math.min(words.length, a.length)
    var i = 0
    while (i < n) { words(i) = a(i); i += 1 }
    while (i < words.length) { words(i) = 0L; i += 1 }
  }

  /** Reset all bits to 0. */
  def clear(): Unit = {
    var i = 0
    while (i < words.length) { words(i) = 0L; i += 1 }
  }

  /** Estimate the number of inserted elements based on bit density. */
  def estimateInsertCount: Int = {
    val m = mBits.toDouble
    val X = countSetBits.toDouble
    val one = 1.0 - math.min(1.0, math.max(0.0, X / m))
    val frac = math.max(1e-12, one)
    math.max(0, math.round(-(m / k) * math.log(frac)).toInt)
  }

  /** Count the number of set bits in the filter. */
  def countSetBits: Long = {
    var i = 0
    var acc = 0L
    val n = words.length
    while (i < n) { acc += java.lang.Long.bitCount(words(i)); i += 1 }
    acc
  }
}

object BloomFilter {

  private val Ln2 = math.log(2.0)
  private val Ln2Sq = Ln2 * Ln2

  /** Create a new Bloom filter optimized for expected elements and FPR. */
  def create(expectedN: Int, fpr: Double): BloomFilter = {
    val (mBits, k) = sizing(expectedN, fpr)
    val words = new Array[Long]((mBits + 63) >>> 6)
    new BloomFilter(mBits, k, words)
  }

  /**
   * Calculate the theoretical bitset size in bytes (m / 8).
   * Does not account for JVM array/object overhead.
   */
  def memoryUsage(expectedN: Int, fpr: Double): Long = {
    val (mBits, _) = sizing(expectedN, fpr)
    mBits / 8L
  }

  /** Compute optimal m (bits) and k (hash functions). */
  def sizing(expectedN: Int, fpr: Double): (Int, Int) = {
    val n = math.max(1, expectedN)
    val f = math.min(0.5, math.max(1e-9, fpr))
    val m = math.ceil(-(n * math.log(f)) / Ln2Sq).toInt
    val mAligned = ((m + 63) / 64) * 64
    val k = math.max(1, math.round((mAligned.toDouble / n) * Ln2).toInt)
    (mAligned, k)
  }

  /** Serialize Bloom filter to bytes: [mBits(4 bytes)][k(4 bytes)][words array] */
  def serialize(filter: BloomFilter): Array[Byte] = {
    val words = filter.snapshotWords
    val buffer = ByteBuffer.allocate(8 + words.length * 8)
    buffer.order(ByteOrder.BIG_ENDIAN)
    buffer.putInt(filter.mBits)
    buffer.putInt(filter.k)
    words.foreach(buffer.putLong)
    buffer.array()
  }

  /**
   * Deserialize Bloom filter from bytes.
   * @throws IllegalArgumentException if data is corrupted
   */
  def deserialize(bytes: Array[Byte]): BloomFilter = {
    require(bytes.length >= 8, s"Bloom filter data too short: ${bytes.length} bytes")
    val buffer = ByteBuffer.wrap(bytes)
    buffer.order(ByteOrder.BIG_ENDIAN)
    val mBits = buffer.getInt()
    val k = buffer.getInt()
    val wordCount = (mBits + 63) >>> 6
    require(
      bytes.length >= 8 + wordCount * 8,
      s"Bloom filter data truncated: expected ${8 + wordCount * 8} bytes, got ${bytes.length}"
    )
    val words = new Array[Long](wordCount)
    var i = 0
    while (i < wordCount) {
      words(i) = buffer.getLong()
      i += 1
    }
    new BloomFilter(mBits, k, words)
  }
}
