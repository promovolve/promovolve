package promovolve.tools

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import promovolve.tools.ClassifyEval.{Expect, Verdict}

class ClassifyEvalSpec extends AnyWordSpec with Matchers {

  private def cats(expected: String, actual: String*) =
    ClassifyEval.scoreCategories(Expect("u", expected.split('|').toVector, Vector.empty, ""), actual.toVector)

  "scoreCategories" should {

    "call a sibling of the expected category WRONG, even beside it" in {
      cats("657", "653", "660") shouldBe Verdict.Wrong // Seoul under North America
      cats("657", "671", "657", "659") shouldBe Verdict.Wrong // Japan under Europe
    }

    "not judge unrelated extras or ancestors" in {
      cats("657", "653", "181", "657") shouldBe Verdict.Hit
      cats("210|218|657", "653", "210", "657") shouldBe Verdict.Hit
      cats("654|663", "663", "654") shouldBe Verdict.Hit
      cats("657", "653") shouldBe Verdict.Broad
    }
  }
}
