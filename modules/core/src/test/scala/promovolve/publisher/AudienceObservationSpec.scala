package promovolve.publisher

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.{ Clock, Instant, LocalDate, ZoneOffset }

/**
 * Tier 3 of docs/design/GEOGRAPHIC_CONTEXT.md — the observed-audience
 * counter.
 *
 * The assertions that matter here are not about arithmetic. They are about
 * the shape of what this component can emit: sums keyed by
 * (site, country, day) and nothing else. If a future change makes it
 * possible to get a per-reader or per-request value out of this class,
 * these tests should be what fails.
 */
class AudienceObservationSpec extends AnyWordSpec with Matchers {

  private val day = LocalDate.of(2026, 8, 20)
  private def fixedClock(d: LocalDate) =
    Clock.fixed(Instant.parse(s"${d}T12:00:00Z"), ZoneOffset.UTC)

  private def counter(d: LocalDate = day) = new AudienceObservationCounter(fixedClock(d))

  "the counter" should {

    "sum observations per site and country" in {
      val c = counter()
      c.record("site-a", "JP")
      c.record("site-a", "JP")
      c.record("site-a", "US")
      c.record("site-b", "JP")
      c.drain().toSet shouldBe Set(
        AudienceObservation("site-a", "JP", day, 2),
        AudienceObservation("site-a", "US", day, 1),
        AudienceObservation("site-b", "JP", day, 1)
      )
    }

    "normalise country case" in {
      val c = counter()
      c.record("site-a", "jp")
      c.record("site-a", "JP")
      c.drain() shouldBe Vector(AudienceObservation("site-a", "JP", day, 2))
    }

    // A malformed row in the ASN dump must not be able to invent a country.
    "ignore anything that is not a two-letter code" in {
      val c = counter()
      c.record("site-a", "")
      c.record("site-a", "JPN")
      c.record("site-a", "J")
      c.record("", "JP")
      c.drain() shouldBe empty
    }

    // Drain-and-reset, not read-then-clear: a beacon landing between the
    // two would be lost.
    "reset on drain so counts are never double-flushed" in {
      val c = counter()
      c.record("site-a", "JP")
      c.drain() should have size 1
      c.drain() shouldBe empty
      c.size shouldBe 0
    }

    "key counts by day" in {
      val c1 = counter(LocalDate.of(2026, 8, 20))
      c1.record("site-a", "JP")
      val c2 = counter(LocalDate.of(2026, 8, 21))
      c2.record("site-a", "JP")
      c1.drain().head.day shouldBe LocalDate.of(2026, 8, 20)
      c2.drain().head.day shouldBe LocalDate.of(2026, 8, 21)
    }

    "have nothing to say before any observation" in {
      counter().drain() shouldBe empty
    }

    // The privacy property, asserted as a property rather than a comment:
    // 10k observations of one country collapse to a single row carrying a
    // count. There is no arrangement of inputs that yields a per-reader
    // artefact, because there is no per-reader state.
    "collapse many observations into one aggregate row" in {
      val c = counter()
      (1 to 10000).foreach(_ => c.record("site-a", "JP"))
      val drained = c.drain()
      drained should have size 1
      drained.head shouldBe AudienceObservation("site-a", "JP", day, 10000)
    }
  }
}
