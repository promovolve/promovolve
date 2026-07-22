package promovolve.fraud

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-pod token-bucket rate gate for the serve/track hot path
 * (docs/design/FRAUD_PREVENTION.md, Layer 0).
 *
 * NOT the cluster-singleton TokenBucketLimiter (that trades latency
 * for global exactness — right for Gemini quotas, wrong for a
 * microsecond budget on every serve). This is a lock-free-ish local
 * map: with N api pods a client effectively gets N× the configured
 * rate, which is fine — the cap is a hygiene mark, not an SLA, and
 * the economics layer sees the true totals.
 *
 * Privacy: callers pass an opaque key (hash the IP before calling —
 * raw IPs must not be retained). Over-cap requests are MARKED by the
 * caller, never refused.
 *
 * Memory: entries idle past `idleEvictMillis` are pruned lazily on
 * write, amortized — no timer thread.
 */
final class RequestRateGate(
    ratePerSec: Double,
    burst: Double,
    idleEvictMillis: Long = 10 * 60 * 1000L,
    nowMillis: () => Long = () => System.currentTimeMillis()
) {

  private final class Bucket(var tokens: Double, var lastMillis: Long)

  private val buckets = new ConcurrentHashMap[String, Bucket]()
  private val lastSweep = new AtomicLong(0L)
  private val SweepEveryMillis = 60 * 1000L

  /** Take one token; false = over cap (caller marks, does not refuse). */
  def allow(key: String): Boolean = {
    val now = nowMillis()
    maybeSweep(now)
    val b = buckets.computeIfAbsent(key, _ => new Bucket(burst, now))
    b.synchronized {
      val elapsed = math.max(0L, now - b.lastMillis)
      b.tokens = math.min(burst, b.tokens + elapsed / 1000.0 * ratePerSec)
      b.lastMillis = now
      if (b.tokens >= 1.0) {
        b.tokens -= 1.0
        true
      } else false
    }
  }

  private def maybeSweep(now: Long): Unit = {
    val last = lastSweep.get()
    if (now - last > SweepEveryMillis && lastSweep.compareAndSet(last, now)) {
      val it = buckets.entrySet().iterator()
      while (it.hasNext) {
        val e = it.next()
        val idle = e.getValue.synchronized(now - e.getValue.lastMillis)
        if (idle > idleEvictMillis) it.remove()
      }
    }
  }

  private[fraud] def size: Int = buckets.size()
}
