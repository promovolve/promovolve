package promovolve.taxonomy

import scala.io.Source
import scala.util.Using

/** IAB Ad Product Taxonomy 2.0.
  *
  * Loads ad product categories from the official IAB taxonomy file.
  * Used by advertisers to declare what product/service they're selling.
  *
  * @see https://github.com/InteractiveAdvertisingBureau/Taxonomies/tree/main/Ad%20Product%20Taxonomies
  */
case class AdProductCategory(
    id: String,
    parentId: Option[String],
    name: String,
    tier1: String,
    tier2: Option[String] = None,
    tier3: Option[String] = None
)

object AdProductTaxonomy {
  private lazy val categories: Map[String, AdProductCategory] = loadTaxonomy()

  /** Get all ad product categories. */
  def getAll: List[AdProductCategory] =
    categories.values.toList.sortBy(_.id.toIntOption.getOrElse(Int.MaxValue))

  /** Get an ad product category by ID. */
  def get(id: String): Option[AdProductCategory] = categories.get(id)

  /** Search ad product categories by query (matches ID or name). */
  def search(query: String): List[AdProductCategory] = {
    val q = query.toLowerCase
    categories.values.filter { cat =>
      cat.id.toLowerCase.contains(q) || cat.name.toLowerCase.contains(q)
    }.toList.sortBy(_.id.toIntOption.getOrElse(Int.MaxValue))
  }

  /** Load ad product categories from the IAB Ad Product Taxonomy 2.0 TSV.
    *
    * File format: Unique ID, Parent ID, Name, Tier 1, Tier 2, Tier 3
    */
  private def loadTaxonomy(): Map[String, AdProductCategory] = {
    val resourcePath = "/iab/ad_product_taxonomy_2.0.tsv"
    val stream = Option(getClass.getResourceAsStream(resourcePath))

    stream match {
      case Some(is) =>
        Using(Source.fromInputStream(is, "UTF-8")) { source =>
          source.getLines()
            .drop(1) // Skip header
            .flatMap { line =>
              val cols = line.split("\t", -1)
              if (cols.length >= 4) {
                val id = cols(0).trim
                val parentId = Option(cols(1).trim).filter(_.nonEmpty)
                val name = cols(2).trim
                val tier1 = cols(3).trim
                val tier2 = if (cols.length > 4) Option(cols(4).trim).filter(_.nonEmpty) else None
                val tier3 = if (cols.length > 5) Option(cols(5).trim).filter(_.nonEmpty) else None

                if (id.nonEmpty && name.nonEmpty) {
                  Some(id -> AdProductCategory(id, parentId, name, tier1, tier2, tier3))
                } else None
              } else None
            }
            .toMap
        }.getOrElse(Map.empty)

      case None =>
        System.err.println(s"Warning: Ad Product Taxonomy not found at $resourcePath")
        Map.empty
    }
  }

  /** Check if taxonomy is loaded. */
  def isLoaded: Boolean = categories.nonEmpty

  /** Get count of loaded categories. */
  def count: Int = categories.size
}
