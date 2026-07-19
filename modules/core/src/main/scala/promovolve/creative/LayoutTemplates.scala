package promovolve.creative

/**
 * Server-side mirror of the layout-template catalog the Go dashboard
 * shows on the LP-to-Creative first step. The dashboard fetches the
 * list, the user picks one, and the chosen template's slot spec
 * gets injected into the Gemini auto-layout prompt so generated
 * compositions respect the intended structure.
 *
 * Source-of-truth notes:
 *   - The TypeScript designer keeps a richer `LayoutTemplate` with
 *     concrete `items[]` for the post-hoc visual picker. That copy
 *     lives in `platform/creative-designer/src/layout-templates.ts`.
 *   - This Scala copy carries only what the dashboard + Gemini call
 *     need: id, label, orientation hint, and the slot spec used in
 *     the prompt.
 *   - Both copies must stay in sync on `id`, `name`, `orientation`,
 *     and `slots`. A drift would surface as a picker labeled one
 *     thing in the dashboard generating layouts the designer's own
 *     template picker can't reproduce.
 */
object LayoutTemplates {

  enum Orientation:
    case Any, Landscape, Portrait, Square

  enum SlotRole:
    case Headline, Subheadline, Body, Cta, Hero, Accent, Badge, Divider

  enum SlotRegion:
    case TopLeft, Top, TopRight
    case Left, Center, Right
    case BottomLeft, Bottom, BottomRight

  enum Prominence:
    case Primary, Secondary

  case class Slot(role: SlotRole, region: SlotRegion, prominence: Option[Prominence] = None)

  case class Template(
      id: String,
      name: String,
      description: String,
      orientation: Orientation,
      slots: Vector[Slot]
  )

  // Mirror of the TypeScript designer catalog
  // (platform/creative-designer/src/layout-templates.ts) on id, name,
  // orientation, and slots — so a template picked in the dashboard editor is
  // always one the designer's own picker can reproduce. The TS copy
  // additionally carries concrete per-aspect `items[]`; this Scala copy needs
  // only the slot spec injected into the Gemini auto-layout prompt. Keep the
  // two in sync (see LayoutTemplatesSpec) — the designer is the source of
  // truth for the set; it intentionally has no CTA-button templates (delivery
  // is a whole-sheet tap, so "the copy and image ARE the ad").
  val all: Vector[Template] = Vector(
    Template(
      id = "promo",
      name = "Promo / Sale",
      description = "Hero image left, sale badge + headline + body right. Mobile: image top, badge floats over.",
      orientation = Orientation.Any,
      slots = Vector(
        Slot(SlotRole.Hero, SlotRegion.Left, Some(Prominence.Primary)),
        Slot(SlotRole.Headline, SlotRegion.Right, Some(Prominence.Primary)),
        Slot(SlotRole.Body, SlotRegion.Right,
          Some(Prominence.Secondary))
      )
    ),
    Template(
      id = "mobile-hero-top",
      name = "Mobile · Hero Top",
      description = "Image fills the top half. Tag, headline, body stacked beneath. Editorial mobile layout.",
      orientation = Orientation.Portrait,
      slots = Vector(
        Slot(SlotRole.Hero, SlotRegion.Top, Some(Prominence.Primary)),
        Slot(SlotRole.Headline, SlotRegion.Center, Some(Prominence.Primary)),
        Slot(SlotRole.Body, SlotRegion.Bottom,
          Some(Prominence.Secondary))
      )
    ),
    Template(
      id = "mobile-fullbleed-overlay",
      name = "Mobile · Full-Bleed Overlay",
      description = "Image fills the page. Headline + body sit on a dark gradient scrim at the bottom. Cinematic.",
      orientation = Orientation.Portrait,
      slots = Vector(
        Slot(SlotRole.Hero, SlotRegion.Center, Some(Prominence.Primary)),
        Slot(SlotRole.Headline, SlotRegion.Bottom, Some(Prominence.Primary)),
        Slot(SlotRole.Body, SlotRegion.Bottom, Some(Prominence.Secondary))
      )
    )
  )

  def findById(id: String): Option[Template] = all.find(_.id == id)

  /**
   * Render a template's slot spec as a single line of plain English
   * suitable for inclusion in an LLM prompt. Mirrors the TypeScript
   * `slotsAsPromptLine` exactly so the prompt fragment matches what
   * the designer's documentation says about the chosen template.
   */
  def slotsAsPromptLine(template: Template): String = {
    if (template.slots.isEmpty) ""
    else {
      val phrases = template.slots.map { s =>
        val prom = s.prominence.map(p => s" (${p.toString.toLowerCase})").getOrElse("")
        s"${s.role.toString.toLowerCase}$prom in ${regionToWire(s.region)}"
      }
      s"Layout intent: ${phrases.mkString("; ")}."
    }
  }

  // Wire format for region — kebab-case strings matching what the
  // TypeScript copy emits. Used both for prompt text and for the
  // dashboard's JSON response.
  def regionToWire(r: SlotRegion): String = r match {
    case SlotRegion.TopLeft     => "top-left"
    case SlotRegion.Top         => "top"
    case SlotRegion.TopRight    => "top-right"
    case SlotRegion.Left        => "left"
    case SlotRegion.Center      => "center"
    case SlotRegion.Right       => "right"
    case SlotRegion.BottomLeft  => "bottom-left"
    case SlotRegion.Bottom      => "bottom"
    case SlotRegion.BottomRight => "bottom-right"
  }

  def roleToWire(r: SlotRole): String = r match {
    case SlotRole.Headline    => "headline"
    case SlotRole.Subheadline => "subheadline"
    case SlotRole.Body        => "body"
    case SlotRole.Cta         => "cta"
    case SlotRole.Hero        => "hero"
    case SlotRole.Accent      => "accent"
    case SlotRole.Badge       => "badge"
    case SlotRole.Divider     => "divider"
  }

  def orientationToWire(o: Orientation): String = o match {
    case Orientation.Any       => "any"
    case Orientation.Landscape => "landscape"
    case Orientation.Portrait  => "portrait"
    case Orientation.Square    => "square"
  }
}
