// Brand-kit view + edit modal. Lists the kit's colors and fonts;
// authors can rename / re-color / add / remove rows, then save (which
// writes localStorage and notifies the props-panel chips to refresh).
//
// No server round-trip — purely local. When the Go shell injects a
// server-side kit later, this modal can be expanded to PUT changes
// back to the API; for now editing while a server kit is present
// only updates localStorage (which is then ignored on next reload).

import {
  loadBrandKit,
  notifyBrandKitChanged,
  saveBrandKit,
  type BrandKit,
  type BrandKitColor,
} from "../brand-kit";
import { tokens } from "./tokens";
import { openAssetModal } from "./asset-modal";

const MODAL_ID = "cd-brand-kit-modal";

export function openBrandKitModal(campaignId: string): void {
  const existing = document.getElementById(MODAL_ID);
  if (existing) existing.remove();

  // Local working copy — only persisted when the user clicks Save.
  const working: BrandKit = structuredClone(loadBrandKit(campaignId));

  const overlay = document.createElement("div");
  overlay.id = MODAL_ID;
  overlay.style.cssText = [
    "position: fixed",
    "inset: 0",
    "background: rgba(0,0,0,0.5)",
    "z-index: 999",
    "display: flex",
    "align-items: center",
    "justify-content: center",
    // Body-mounted (outside #designer-root): set the UI font so the dialog
    // doesn't inherit the document default in the prod shell.
    `font-family: ${tokens.sans}`,
  ].join(";");

  const modal = document.createElement("div");
  modal.style.cssText = [
    `background: ${tokens.ink800}`,
    `border: 1px solid ${tokens.ink500}`,
    "border-radius: 8px",
    "width: 480px",
    "max-height: 78vh",
    "padding: 16px 18px 18px",
    "display: flex",
    "flex-direction: column",
    "gap: 10px",
    `color: ${tokens.ink100}`,
  ].join(";");

  const header = document.createElement("div");
  header.style.cssText = "display:flex;align-items:center;justify-content:space-between;";
  const title = document.createElement("h3");
  title.textContent = "Brand kit";
  title.style.cssText = "margin:0;font-size:14px;font-weight:600;";
  const close = document.createElement("button");
  close.textContent = "×";
  close.style.cssText = `background:none;border:none;color:${tokens.ink300};font-size:22px;line-height:1;cursor:pointer;padding:0 4px;`;
  close.addEventListener("click", () => overlay.remove());
  header.append(title, close);

  const body = document.createElement("div");
  body.style.cssText = "overflow-y:auto;display:flex;flex-direction:column;gap:14px;";

  const logoSection = document.createElement("div");
  body.appendChild(logoSection);
  const colorsSection = document.createElement("div");
  body.appendChild(colorsSection);
  const fontsSection = document.createElement("div");
  body.appendChild(fontsSection);

  const renderLogo = (): void => {
    logoSection.innerHTML = "";
    logoSection.appendChild(sectionLabel("Logo"));
    if (working.logoUrl) {
      const img = document.createElement("img");
      img.src = working.logoUrl;
      img.alt = "Brand logo";
      img.style.cssText = `max-width:140px;max-height:64px;object-fit:contain;background:${tokens.ink900};border:1px solid ${tokens.ink500};border-radius:4px;padding:6px;display:block;margin-bottom:6px;`;
      logoSection.appendChild(img);
      const rowEl = document.createElement("div");
      rowEl.style.cssText = "display:flex;gap:8px;";
      rowEl.append(
        ghostButton("Change…", pickLogo),
        ghostButton("Remove", () => { working.logoUrl = undefined; renderLogo(); }),
      );
      logoSection.appendChild(rowEl);
    } else {
      logoSection.appendChild(ghostButton("Select logo", pickLogo));
    }
  };
  function pickLogo(): void {
    // Reuse the Images library (upload + pick), route the chosen asset to
    // the brand logo instead of the creative's main image.
    openAssetModal(null, (asset) => { working.logoUrl = asset.cdnUrl; renderLogo(); });
  }
  renderLogo();

  const renderColors = (): void => {
    colorsSection.innerHTML = "";
    colorsSection.appendChild(sectionLabel("Colors"));
    for (let i = 0; i < working.colors.length; i++) {
      colorsSection.appendChild(buildColorRow(working, i, renderColors));
    }
    const add = ghostButton("+ add color", () => {
      working.colors.push({ name: "New", value: "#000000" });
      renderColors();
    });
    colorsSection.appendChild(add);
  };

  const renderFonts = (): void => {
    fontsSection.innerHTML = "";
    fontsSection.appendChild(sectionLabel("Fonts"));
    for (let i = 0; i < working.fonts.length; i++) {
      fontsSection.appendChild(buildFontRow(working, i, renderFonts));
    }
    const add = ghostButton("+ add font", () => {
      working.fonts.push("Helvetica");
      renderFonts();
    });
    fontsSection.appendChild(add);
  };

  renderColors();
  renderFonts();

  const footer = document.createElement("div");
  footer.style.cssText = "display:flex;justify-content:flex-end;gap:8px;padding-top:6px;border-top:1px solid " + tokens.ink600 + ";";
  const save = document.createElement("button");
  save.textContent = "Save kit";
  save.type = "button";
  save.style.cssText = `background:${tokens.amber};color:${tokens.ink900};border:none;border-radius:4px;padding:6px 14px;font:inherit;font-weight:600;cursor:pointer;`;
  save.addEventListener("click", () => {
    saveBrandKit(working, campaignId);
    notifyBrandKitChanged(working);
    overlay.remove();
  });
  footer.append(save);

  modal.append(header, body, footer);
  overlay.appendChild(modal);
  overlay.addEventListener("click", (e) => {
    if (e.target === overlay) overlay.remove();
  });
  document.body.appendChild(overlay);
}

function sectionLabel(text: string): HTMLElement {
  const l = document.createElement("div");
  l.textContent = text;
  l.style.cssText = `text-transform:uppercase;font-size:10px;letter-spacing:1.5px;color:${tokens.ink400};margin-bottom:6px;`;
  return l;
}

function buildColorRow(kit: BrandKit, idx: number, rerender: () => void): HTMLElement {
  const row = document.createElement("div");
  row.style.cssText = "display:flex;align-items:center;gap:8px;margin-bottom:4px;";
  const c: BrandKitColor = kit.colors[idx]!;

  const colorInput = document.createElement("input");
  colorInput.type = "color";
  colorInput.value = normalizeHex(c.value);
  colorInput.style.cssText = `width:36px;height:28px;border:1px solid ${tokens.ink500};background:${tokens.ink900};border-radius:4px;cursor:pointer;`;
  colorInput.addEventListener("input", () => { kit.colors[idx]!.value = colorInput.value; });

  const nameInput = document.createElement("input");
  nameInput.type = "text";
  nameInput.value = c.name;
  nameInput.placeholder = "Color name";
  nameInput.style.cssText = `flex:1;background:${tokens.ink900};color:${tokens.ink100};border:1px solid ${tokens.ink500};border-radius:4px;padding:4px 6px;font:inherit;`;
  nameInput.addEventListener("input", () => { kit.colors[idx]!.name = nameInput.value; });

  const remove = removeButton(() => { kit.colors.splice(idx, 1); rerender(); });
  row.append(colorInput, nameInput, remove);
  return row;
}

function buildFontRow(kit: BrandKit, idx: number, rerender: () => void): HTMLElement {
  const row = document.createElement("div");
  row.style.cssText = "display:flex;align-items:center;gap:8px;margin-bottom:4px;";
  const input = document.createElement("input");
  input.type = "text";
  input.value = kit.fonts[idx]!;
  input.placeholder = "font-family";
  input.style.cssText = `flex:1;background:${tokens.ink900};color:${tokens.ink100};border:1px solid ${tokens.ink500};border-radius:4px;padding:4px 6px;font:${kit.fonts[idx]}, inherit;`;
  input.addEventListener("input", () => {
    kit.fonts[idx] = input.value;
    input.style.fontFamily = input.value;
  });
  const remove = removeButton(() => { kit.fonts.splice(idx, 1); rerender(); });
  row.append(input, remove);
  return row;
}

function removeButton(onClick: () => void): HTMLButtonElement {
  const b = document.createElement("button");
  b.type = "button";
  b.textContent = "×";
  b.title = "Remove";
  b.style.cssText = `background:transparent;color:${tokens.ink300};border:1px solid ${tokens.ink500};border-radius:4px;width:26px;height:26px;cursor:pointer;font-size:14px;line-height:1;`;
  b.addEventListener("click", onClick);
  return b;
}

function ghostButton(text: string, onClick: () => void): HTMLButtonElement {
  const b = document.createElement("button");
  b.type = "button";
  b.textContent = text;
  b.style.cssText = `background:transparent;color:${tokens.ink300};border:1px dashed ${tokens.ink500};border-radius:4px;padding:5px 10px;font:inherit;font-size:11px;cursor:pointer;margin-top:6px;`;
  b.addEventListener("click", onClick);
  return b;
}

// <input type="color"> only accepts opaque #rrggbb. Strip alpha;
// expand 3-digit hex; fall back to black for non-hex values so the
// picker stays interactive even when the kit was hand-edited with
// a CSS named color.
function normalizeHex(c: string): string {
  if (!c) return "#000000";
  if (/^#[0-9a-fA-F]{8}$/.test(c)) return c.slice(0, 7);
  if (/^#[0-9a-fA-F]{6}$/.test(c)) return c;
  if (/^#[0-9a-fA-F]{3}$/.test(c)) return "#" + c.slice(1).split("").map((ch) => ch + ch).join("");
  return "#000000";
}
