package promovolve.api.guard

import org.apache.pekko.actor.typed.ActorRef

/**
 * Simple trait for replay detection - checks if a nonce has been seen before.
 *
 * Use cases:
 * - Tracking URL replay detection (prevent double-counting impressions)
 * - Idempotency keys for API requests
 * - Nonce validation for signed tokens
 */
trait ReplayGuard {

  /**
   * Check if this nonce is a replay (seen before).
   *
   * @param nonce Unique identifier (e.g., hash of tracking URL parameters)
   * @param replyTo Actor to receive the result
   *
   * Reply: true = first time seen (valid), false = replay (reject)
   */
  def validate(nonce: Long, replyTo: ActorRef[Boolean]): Unit

  /**
   * Check if this string key is a replay.
   * Convenience method that hashes the string to a Long nonce.
   */
  def validateKey(key: String, replyTo: ActorRef[Boolean]): Unit
}

object ReplayGuard {

  /** Hash a string to a 64-bit nonce using FNV-1a. */
  def hash(s: String): Long = {
    val data = s.getBytes("UTF-8")
    var hash = 0xCBF29CE484222325L // FNV offset basis
    val prime = 0x100000001B3L // FNV prime
    var i = 0
    while (i < data.length) {
      hash ^= (data(i) & 0xFF).toLong
      hash *= prime
      i += 1
    }
    hash
  }
}
