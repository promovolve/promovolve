// Quick-pick color chips that surface the brand kit beneath color
// pickers in the props panel. Click a chip → applies that color.
// Re-renders on kit-changed notifications so editing the kit in the
// modal updates the chip rows immediately.

import { loadBrandKit, subscribeBrandKit, type BrandKit } from "../brand-kit";
import { tokens } from "./tokens";

interface ChipsParams {
  campaignId: string;
  onPick: (color: string) => void;
}

/** Mount a chip row into `parent`. Returns nothing — the row stays
  * attached for the panel's lifetime; the kit subscription is
  * cleaned up automatically when the parent leaves the DOM (the
  * subscription callback is a no-op once the row is gone). */
export function mountKitColorChips(parent: HTMLElement, { campaignId, onPick }: ChipsParams): void {
  const wrap = document.createElement("div");
  wrap.style.cssText = "display:flex;align-items:center;gap:4px;flex-wrap:wrap;margin:2px 0 8px 88px;";
  parent.appendChild(wrap);

  const render = (kit: BrandKit): void => {
    wrap.innerHTML = "";
    if (kit.colors.length === 0) return;
    const label = document.createElement("span");
    label.textContent = "kit";
    label.style.cssText = `font-size:9px;color:${tokens.ink400};letter-spacing:1px;text-transform:uppercase;margin-right:2px;`;
    wrap.appendChild(label);
    for (const c of kit.colors) {
      const chip = document.createElement("button");
      chip.type = "button";
      chip.title = c.name;
      chip.style.cssText = [
        "width: 16px",
        "height: 16px",
        "border-radius: 3px",
        `border: 1px solid ${tokens.ink500}`,
        `background: ${c.value}`,
        "cursor: pointer",
        "padding: 0",
      ].join(";");
      chip.addEventListener("click", () => onPick(c.value));
      wrap.appendChild(chip);
    }
  };

  render(loadBrandKit(campaignId));
  subscribeBrandKit((kit) => {
    // The wrap may have been detached when the props-panel re-renders;
    // skip the update silently in that case.
    if (wrap.isConnected) render(kit);
  });
}
