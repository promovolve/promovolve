package promovolve.common

import java.nio.{ByteBuffer, ByteOrder}
import scala.util.Random

/** A Cuckoo Filter implementation with support for insert, lookup, and delete.
  *
  * False positive rates by fingerprint size:
  *   - 8 bits:  ~3%               (1 in 33)
  *   - 12 bits: ~0.2%             (1 in 500)
  *   - 16 bits: ~0.01%            (1 in 10,000)
  *   - 20 bits: ~0.001%           (1 in 100,000)
  *   - 24 bits: ~0.00006%         (1 in 1.6 million)
  *   - 32 bits: ~0.0000000018%    (1 in 500 million)
  *
  * @param numBuckets Number of buckets (should be power of 2)
  * @param bucketSize Number of fingerprints per bucket (typically 4)
  * @param fingerprintBits Size of fingerprint in bits (8, 12, 16, 20, 24, or 32)
  */
final class CuckooFilter private (
    val numBuckets: Int,
    val bucketSize: Int,
    val fingerprintBits: Int,
    private val buckets: Array[Array[Int]]  // fingerprint = 0 means empty
) {
  require((numBuckets & (numBuckets - 1)) == 0, "numBuckets must be power of 2")
  require(fingerprintBits >= 8 && fingerprintBits <= 32, "fingerprintBits must be 8-32")

  private val maxFingerprint = if (fingerprintBits == 32) Int.MaxValue else (1 << fingerprintBits) - 1
  private val bucketMask = numBuckets - 1
  private val maxKicks = 500  // Max relocations before giving up

  private var count: Int = buckets.map(_.count(_ != 0)).sum

  /** Number of items in the filter */
  def size: Int = count

  /** Capacity of the filter */
  def capacity: Int = numBuckets * bucketSize

  /** Load factor (0.0 to 1.0) */
  def loadFactor: Double = count.toDouble / capacity

  /** Insert an item. Returns true if successful, false if filter is full. */
  def insert(item: String): Boolean = {
    val fp = fingerprint(item)
    val (i1, i2) = bucketIndices(item, fp)

    // Try primary bucket
    if (insertIntoBucket(i1, fp)) {
      count += 1
      return true
    }

    // Try alternate bucket
    if (insertIntoBucket(i2, fp)) {
      count += 1
      return true
    }

    // Both full - need to kick
    kickInsert(i1, fp)
  }

  /** Check if item might be in the filter.
    * Returns true if probably present, false if definitely not present.
    */
  def mightContain(item: String): Boolean = {
    val fp = fingerprint(item)
    val (i1, i2) = bucketIndices(item, fp)
    bucketContains(i1, fp) || bucketContains(i2, fp)
  }

  /** Delete an item. Returns true if found and deleted, false if not found. */
  def delete(item: String): Boolean = {
    val fp = fingerprint(item)
    val (i1, i2) = bucketIndices(item, fp)

    if (deleteFromBucket(i1, fp)) {
      count -= 1
      true
    } else if (deleteFromBucket(i2, fp)) {
      count -= 1
      true
    } else {
      false
    }
  }

  /** Serialize to byte array for persistence */
  def toBytes: Array[Byte] = {
    val bytesPerFp = (fingerprintBits + 7) / 8
    val dataSize = numBuckets * bucketSize * bytesPerFp
    val headerSize = 16  // numBuckets (4) + bucketSize (4) + fingerprintBits (4) + count (4)

    val buffer = ByteBuffer.allocate(headerSize + dataSize).order(ByteOrder.BIG_ENDIAN)
    buffer.putInt(numBuckets)
    buffer.putInt(bucketSize)
    buffer.putInt(fingerprintBits)
    buffer.putInt(count)

    for {
      bucket <- buckets
      fp <- bucket
    } {
      bytesPerFp match {
        case 1 => buffer.put(fp.toByte)
        case 2 => buffer.putShort(fp.toShort)
        case 3 =>
          buffer.put((fp >> 16).toByte)
          buffer.putShort(fp.toShort)
        case _ => buffer.putInt(fp)
      }
    }

    buffer.array()
  }

  // --- Private helpers ---

  private def fingerprint(item: String): Int = {
    val hash = murmurHash3(item.getBytes("UTF-8"), seed = 0x5F3759DF)
    val fp = if (fingerprintBits == 32) {
      // Use full 32 bits, but avoid 0 (means empty)
      if (hash == 0) 1 else hash
    } else {
      val masked = hash & maxFingerprint
      if (masked == 0) 1 else masked  // 0 means empty, so use 1 instead
    }
    fp
  }

  private def bucketIndices(item: String, fp: Int): (Int, Int) = {
    val hash = murmurHash3(item.getBytes("UTF-8"), seed = 0x1337CAFE)
    val i1 = hash & bucketMask
    val i2 = (i1 ^ murmurHash3(intToBytes(fp), seed = 0xDEADBEEF)) & bucketMask
    (i1, i2)
  }

  private def alternateIndex(i: Int, fp: Int): Int = {
    (i ^ murmurHash3(intToBytes(fp), seed = 0xDEADBEEF)) & bucketMask
  }

  private def insertIntoBucket(bucketIdx: Int, fp: Int): Boolean = {
    val bucket = buckets(bucketIdx)
    var i = 0
    while (i < bucketSize) {
      if (bucket(i) == 0) {
        bucket(i) = fp
        return true
      }
      i += 1
    }
    false
  }

  private def bucketContains(bucketIdx: Int, fp: Int): Boolean = {
    val bucket = buckets(bucketIdx)
    var i = 0
    while (i < bucketSize) {
      if (bucket(i) == fp) return true
      i += 1
    }
    false
  }

  private def deleteFromBucket(bucketIdx: Int, fp: Int): Boolean = {
    val bucket = buckets(bucketIdx)
    var i = 0
    while (i < bucketSize) {
      if (bucket(i) == fp) {
        bucket(i) = 0
        return true
      }
      i += 1
    }
    false
  }

  private def kickInsert(startBucket: Int, startFp: Int): Boolean = {
    var i = startBucket
    var fp = startFp
    val rand = new Random(System.nanoTime())

    var kicks = 0
    while (kicks < maxKicks) {
      // Pick random entry to kick
      val bucket = buckets(i)
      val kickIdx = rand.nextInt(bucketSize)
      val kickedFp = bucket(kickIdx)
      bucket(kickIdx) = fp

      // Relocate kicked fingerprint
      fp = kickedFp
      i = alternateIndex(i, fp)

      // Try to insert kicked fingerprint
      if (insertIntoBucket(i, fp)) {
        count += 1
        return true
      }

      kicks += 1
    }

    // Filter is too full
    false
  }

  private def intToBytes(i: Int): Array[Byte] = {
    Array(
      (i >> 24).toByte,
      (i >> 16).toByte,
      (i >> 8).toByte,
      i.toByte
    )
  }

  /** MurmurHash3 finalization mix */
  private def murmurHash3(data: Array[Byte], seed: Int): Int = {
    var h = seed
    var i = 0
    while (i + 4 <= data.length) {
      var k = (data(i) & 0xFF) |
              ((data(i + 1) & 0xFF) << 8) |
              ((data(i + 2) & 0xFF) << 16) |
              ((data(i + 3) & 0xFF) << 24)
      k = mixK1(k)
      h = mixH1(h, k)
      i += 4
    }

    // Remaining bytes
    var k = 0
    val remaining = data.length - i
    if (remaining >= 3) k ^= (data(i + 2) & 0xFF) << 16
    if (remaining >= 2) k ^= (data(i + 1) & 0xFF) << 8
    if (remaining >= 1) {
      k ^= (data(i) & 0xFF)
      k = mixK1(k)
      h ^= k
    }

    fmix(h ^ data.length)
  }

  private def mixK1(k: Int): Int = {
    var k1 = k
    k1 *= 0xcc9e2d51
    k1 = Integer.rotateLeft(k1, 15)
    k1 *= 0x1b873593
    k1
  }

  private def mixH1(h: Int, k: Int): Int = {
    var h1 = h ^ k
    h1 = Integer.rotateLeft(h1, 13)
    h1 = h1 * 5 + 0xe6546b64
    h1
  }

  private def fmix(h: Int): Int = {
    var h1 = h
    h1 ^= h1 >>> 16
    h1 *= 0x85ebca6b
    h1 ^= h1 >>> 13
    h1 *= 0xc2b2ae35
    h1 ^= h1 >>> 16
    h1
  }
}

object CuckooFilter {

  /** Create an empty Cuckoo Filter with specified capacity and error rate.
    *
    * @param expectedItems Expected number of items to store
    * @param errorRate Target false positive rate (e.g., 0.0001 for 0.01%)
    */
  def apply(expectedItems: Int, errorRate: Double = 0.0001): CuckooFilter = {
    // Choose fingerprint size based on error rate
    // FP rate ≈ 2 * bucketSize / 2^fingerprintBits
    val fingerprintBits = errorRate match {
      case e if e <= 0.00000001 => 32  // ~1 in 500 million
      case e if e <= 0.000001   => 24  // ~1 in 1.6 million
      case e if e <= 0.00001    => 20  // ~1 in 100,000
      case e if e <= 0.0001     => 16  // ~1 in 10,000
      case e if e <= 0.001      => 12  // ~1 in 500
      case _                    => 8   // ~1 in 33
    }

    val bucketSize = 4

    // Calculate number of buckets (power of 2, ~95% load factor max)
    val minBuckets = math.ceil(expectedItems.toDouble / bucketSize / 0.95).toInt
    val numBuckets = nextPowerOf2(math.max(minBuckets, 16))

    val buckets = Array.fill(numBuckets)(Array.fill(bucketSize)(0))
    new CuckooFilter(numBuckets, bucketSize, fingerprintBits, buckets)
  }

  /** Create an empty Cuckoo Filter with low error rate.
    * Uses 16-bit fingerprints for ~1 in 10,000 error rate.
    */
  def lowError(expectedItems: Int): CuckooFilter = {
    apply(expectedItems, errorRate = 0.0001)
  }

  /** Deserialize from byte array */
  def fromBytes(bytes: Array[Byte]): CuckooFilter = {
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    val numBuckets = buffer.getInt()
    val bucketSize = buffer.getInt()
    val fingerprintBits = buffer.getInt()
    buffer.getInt() // stored count — read to advance the buffer position; value unused

    val bytesPerFp = (fingerprintBits + 7) / 8
    val buckets = Array.fill(numBuckets)(Array.fill(bucketSize)(0))

    for {
      i <- 0 until numBuckets
      j <- 0 until bucketSize
    } {
      buckets(i)(j) = bytesPerFp match {
        case 1 => buffer.get() & 0xFF
        case 2 => buffer.getShort() & 0xFFFF
        case 3 =>
          val high = buffer.get() & 0xFF
          val low = buffer.getShort() & 0xFFFF
          (high << 16) | low
        case _ => buffer.getInt()
      }
    }

    new CuckooFilter(numBuckets, bucketSize, fingerprintBits, buckets)
  }

  /** Create empty filter (no capacity, for default values) */
  def empty: CuckooFilter = apply(16, 0.0001)

  private def nextPowerOf2(n: Int): Int = {
    var v = n - 1
    v |= v >> 1
    v |= v >> 2
    v |= v >> 4
    v |= v >> 8
    v |= v >> 16
    v + 1
  }
}
