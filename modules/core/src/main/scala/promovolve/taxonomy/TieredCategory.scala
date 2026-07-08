package promovolve.taxonomy

import scala.annotation.tailrec
import scala.collection.mutable
import scala.io.Source
import scala.util.Using

case class TieredCategory(
    id: String,
    name: String,
    parent: Option[TieredCategory],
    children: mutable.Map[String, TieredCategory]
) {
  override def toString: String = parent
    .fold(s"$name($id)")(p => s"$name($id) -> $p")

  override def hashCode(): Int = id.hashCode
}

object TieredCategory {
  private val nodeMap = mutable.Map[String, TieredCategory]()

  /** IAB 1.0 top-level IDs → Content Taxonomy 2.1 top-level IDs.
    * Kept as a stepping stone — `normalize` chains this through
    * [[Iab2xTo3xMigration]] to land at the active 3.0 ID. */
  private val iab1ToContentTaxonomy21: Map[String, String] = Map(
    "IAB1"  -> "239",  // Arts & Entertainment → Hobbies & Interests (closest match)
    "IAB2"  -> "1",    // Automotive
    "IAB3"  -> "52",   // Business
    "IAB4"  -> "123",  // Careers
    "IAB5"  -> "132",  // Education
    "IAB6"  -> "186",  // Family & Parenting → Family and Relationships
    "IAB7"  -> "223",  // Health & Fitness → Healthy Living
    "IAB8"  -> "210",  // Food & Drink
    "IAB9"  -> "239",  // Hobbies & Interests
    "IAB10" -> "274",  // Home & Garden
    "IAB11" -> "379",  // Law, Gov't & Politics → News and Politics
    "IAB12" -> "379",  // News → News and Politics
    "IAB13" -> "391",  // Personal Finance
    "IAB14" -> "453",  // Society → Religion & Spirituality (closest match)
    "IAB15" -> "464",  // Science
    "IAB16" -> "422",  // Pets
    "IAB17" -> "483",  // Sports
    "IAB18" -> "552",  // Style & Fashion
    "IAB19" -> "596",  // Technology & Computing
    "IAB20" -> "653",  // Travel
    "IAB21" -> "441",  // Real Estate
    "IAB22" -> "473",  // Shopping
    "IAB23" -> "453",  // Religion & Spirituality
  )

  private val Iab1Pattern = """^(IAB\d+)(?:-\d+)?$""".r

  /** Normalize any legacy IAB id to its current Content Taxonomy 3.0 id.
    *
    * Resolution chain:
    *   - IAB 1.0 ("IAB19", "IAB19-1") → 2.x → 3.0
    *   - 2.x numeric id (in the migration table) → 3.0
    *   - 3.0 id (or unknown) → pass through unchanged
    *
    * Descriptive-vector legacy ids (Channel/Type/Media/etc.) map to no
    * 3.0 topical id. We return them unchanged here so callers see them in
    * logs and can drop them via [[Iab2xTo3xMigration.toV30]] explicitly —
    * silently rewriting to "" would mask bad data.
    */
  def normalize(id: String): String = {
    val viaIab1 = id match {
      case Iab1Pattern(topLevel) => iab1ToContentTaxonomy21.getOrElse(topLevel, id)
      case _                     => id
    }
    Iab2xTo3xMigration.resolve(viaIab1) match {
      case Iab2xTo3xMigration.Resolution.Mapped(v30, _) => v30
      case _                                            => viaIab1
    }
  }

  /** Get all categories in the taxonomy. */
  def getAll: List[TieredCategory] = nodeMap.values.toList

  /** Get a category by ID. */
  def get(id: String): Option[TieredCategory] = nodeMap.get(normalize(id))

  def getAncestors(id: String): List[TieredCategory] = nodeMap
    .get(normalize(id))
    .map { node =>
      @tailrec
      def loop(
          current: TieredCategory,
          visited: Set[String],
          acc: List[TieredCategory]
      ): List[TieredCategory] = current.parent match {
        case Some(p) if !visited.contains(p.id) =>
          loop(p, visited + p.id, p :: acc)
        case _ => acc
      }
      loop(node, Set.empty, Nil).reverse
    }
    .getOrElse(Nil)

  // Load IAB Content Taxonomy 3.0 (unified topical taxonomy; descriptive
  // vectors moved out and are no longer part of this tree). Legacy 2.x ids
  // are routed through Iab2xTo3xMigration in `normalize` above.
  loadCategoriesFromTSV("/iab_content_taxonomy/3_0.tsv")

  /** Drop any id that is an ancestor of another id in the set.
    *
    * Use this when the LLM returns a mix of specific leaves and their tier-1
    * ancestors (e.g. `{497 Horse Racing, 496 Equine Sports, 483 Sports}`).
    * For targeting purposes we want only the most specific intent — keeping
    * 483 would make the campaign bid on every Sports descendant (Baseball,
    * Soccer, …) via auctioneer ancestor expansion, blowing up over-broad.
    *
    * Ids unknown to the taxonomy pass through unchanged (we can't make a
    * judgement about them; better to keep than silently drop).
    */
  def keepMostSpecific(ids: Set[String]): Set[String] = {
    if (ids.size < 2) return ids
    val normalized = ids.map(normalize)
    normalized.filterNot { id =>
      normalized.exists { other =>
        other != id && getAncestors(other).exists(_.id == id)
      }
    }
  }

  def getAllDescendants(id: String): List[TieredCategory] = nodeMap
    .get(normalize(id))
    .map { node =>
      @tailrec
      def loop(
          stack: List[TieredCategory],
          visited: Set[String],
          acc: List[TieredCategory]
      ): List[TieredCategory] = stack match {
        case Nil => acc
        case head :: tail if !visited.contains(head.id) =>
          loop(
            head.children.values.toList ++ tail,
            visited + head.id,
            head :: acc
          )
        case _ :: tail => loop(tail, visited, acc)
      }
      loop(node.children.values.toList, Set.empty, Nil).reverse
    }
    .getOrElse(Nil)

  private def loadCategoriesFromTSV(resourcePath: String): Unit =
    Using(Source.fromURL(getClass.getResource(resourcePath))) { source =>
      // Two header lines: relational-id banner + column names
      for (line <- source.getLines().drop(2)) {
        val columns = line.split("\t").map(_.trim)
        if (columns.length >= 3 && columns(0).nonEmpty) {
          val id       = columns(0)
          val parentId = if (columns(1).nonEmpty) Some(columns(1)) else None
          val name     = columns(2)
          insert(id, name, parentId)
        }
      }
    }

  private def insert(id: String, name: String, parentId: Option[String]): Unit = {
    val parent  = parentId.flatMap(nodeMap.get)
    val newNode = TieredCategory(id, name, parent, mutable.Map.empty)
    parent.foreach(_.children(id) = newNode)
    nodeMap(id)                   = newNode
  }
}
