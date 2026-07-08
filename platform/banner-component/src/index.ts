// Entry point. Registers <expandable-magazine-banner> and the shared
// <magazine-preview> wrapper on load. Both ship in the one bundle that
// the publisher approval page and the advertiser designer already load,
// so a single <script> gives both pages the same preview component.
import { ExpandableMagazineBanner } from "./banner";
import { MagazinePreview } from "./magazine-preview";

if (!customElements.get("expandable-magazine-banner")) {
  customElements.define("expandable-magazine-banner", ExpandableMagazineBanner);
}
if (!customElements.get("magazine-preview")) {
  customElements.define("magazine-preview", MagazinePreview);
}

export { ExpandableMagazineBanner, MagazinePreview };
export { EXPAND_EFFECTS } from "./types";
export type { Page, LayoutItem, BannerConfig, TextItem, ImageItem, RectItem, CircleItem, MotionTarget, ExpandAnimation } from "./types";
