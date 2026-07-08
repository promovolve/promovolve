// Right-docked sidebar. Hosts the Properties / Animation tabs.
// Layers lives inside the Properties tab as a collapsible region
// below the per-item props panel — it owns its own click-to-collapse
// header (see ui/layers.ts) so the props panel above keeps the
// vertical real estate authors actually use most of the time.
//
// Assets aren't a tab: the asset library is the modal that pops
// from the Image button on the left tool rail (see openAssetModal
// in ui/asset-modal.ts).
//
// Callers mount their content into the returned `propsSection`,
// `animationSection`, and `layersSection` elements.

import { tokens } from "./tokens";

const TABS = [
  { key: "properties", label: "Properties" },
  { key: "animation",  label: "Animation" },
] as const;
type TabKey = typeof TABS[number]["key"];

export interface SidebarHandle {
  // Creative-wide settings (one per banner). Sits at the very top of
  // the Properties tab so authors see it before page-level or
  // item-level controls — most-general scope first.
  bannerConfigSection: HTMLElement;
  pageBgSection: HTMLElement;
  propsSection: HTMLElement;
  animationSection: HTMLElement;
  layersSection: HTMLElement;
}

export function mountSidebar(host: HTMLElement): SidebarHandle {
  const panel = document.createElement("aside");
  panel.className = "cd-sidebar";
  // Mounts into the flex sidebarHost from renderShell(), which
  // already sizes to 320px wide. The panel itself is a flex column
  // filling that host with a top tab bar, scrollable tab body, and
  // bottom Layers section.
  panel.style.cssText = [
    "flex: 1 1 auto",
    "min-height: 0",
    "display: flex",
    "flex-direction: column",
    `background: ${tokens.ink800}`,
    `border-left: 1px solid ${tokens.ink500}`,
    `color: ${tokens.ink200}`,
    "font-size: 12px",
    "overflow: hidden",
    `font-family: ${tokens.sans}`,
  ].join(";");

  // Tabs
  const tabsBar = document.createElement("div");
  tabsBar.setAttribute("role", "tablist");
  tabsBar.style.cssText = [
    "display: flex",
    `border-bottom: 1px solid ${tokens.ink500}`,
    "flex: 0 0 auto",
  ].join(";");
  const tabBtns = new Map<TabKey, HTMLButtonElement>();
  for (const tab of TABS) {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.setAttribute("role", "tab");
    btn.textContent = tab.label;
    btn.dataset.tab = tab.key;
    btn.style.cssText = [
      "flex: 1",
      "padding: 10px 8px",
      "background: transparent",
      "border: none",
      "border-bottom: 2px solid transparent",
      "font: inherit",
      "font-size: 12px",
      "cursor: pointer",
      "transition: color .12s, border-color .12s",
    ].join(";");
    btn.addEventListener("click", () => setTab(tab.key));
    tabsBar.appendChild(btn);
    tabBtns.set(tab.key, btn);
  }
  panel.appendChild(tabsBar);

  // Tab body — only one tab's contents are visible at a time. Scrolls
  // with no visible bar (Figma/Sketch convention): wheel/trackpad still
  // scroll, but the bar doesn't compete for attention with the panel
  // contents at this width.
  const tabBody = document.createElement("div");
  tabBody.className = "cd-sidebar-scroll";
  tabBody.style.cssText = [
    "flex: 1 1 auto",
    "overflow: auto",
    "min-height: 0",
    "scrollbar-width: none",
    "-ms-overflow-style: none",
  ].join(";");
  // WebKit scrollbar can only be hidden via a pseudo-element rule.
  const scrollbarStyle = document.createElement("style");
  scrollbarStyle.textContent = `.cd-sidebar-scroll::-webkit-scrollbar { display: none; width: 0; height: 0; }`;
  panel.appendChild(scrollbarStyle);

  // ── Collapsible top-level sections (strict accordion: one open) ──
  // The Properties tab has no visible scrollbar, so each top-level
  // section is a .cd-group and the sidebar-level accordion delegation
  // (below) collapses the others when one opens — the panel never
  // exceeds one section's height. The sidebar owns the section header;
  // each panel mounts flat into the group's body (handed back to the
  // caller). Per-item props (Transform/Text/…) and the Layers list keep
  // their own nested behavior under their own parent.
  const makeSection = (label: string, withCount = false): { group: HTMLElement; body: HTMLElement } => {
    const group = document.createElement("section");
    group.className = "cd-group";
    group.style.cssText = `border-bottom:1px solid ${tokens.ink500};display:flex;flex-direction:column;`;

    const header = document.createElement("button");
    header.type = "button";
    header.className = "cd-group-header";
    header.style.cssText = `width:100%;display:flex;align-items:center;justify-content:space-between;gap:6px;background:none;border:none;color:${tokens.ink300};font:inherit;font-size:10px;letter-spacing:2px;padding:10px 14px 8px;cursor:pointer;text-transform:uppercase;`;
    const labelSpan = document.createElement("span");
    labelSpan.textContent = label;
    const right = document.createElement("span");
    right.style.cssText = "display:flex;align-items:center;gap:8px;";
    if (withCount) {
      const count = document.createElement("span");
      count.className = "cd-group-count";
      count.style.cssText = `color:${tokens.ink400};font-size:10px;font-family:${tokens.sans};letter-spacing:0;`;
      right.appendChild(count);
    }
    const caret = document.createElement("span");
    caret.textContent = "▾";
    caret.className = "cd-group-caret";
    caret.style.cssText = `font-size:11px;color:${tokens.ink400};transition:transform 0.15s;`;
    right.appendChild(caret);
    header.append(labelSpan, right);

    const body = document.createElement("div");
    body.className = "cd-group-body";
    body.style.cssText = "display:flex;flex-direction:column;min-height:0;";

    group.append(header, body);
    return { group, body };
  };

  const bannerG = makeSection("Banner");
  const pageBgG = makeSection("Page Background");
  const propsG  = makeSection("Properties");
  const layersG = makeSection("Layers", true);   // count badge in header
  const propTabGroups = [bannerG, pageBgG, propsG, layersG];

  const bannerConfigSection = bannerG.body;
  const pageBgSection = pageBgG.body;
  const propsSection = propsG.body;
  const layersSection = layersG.body;

  // Animation tab — single non-collapsible section.
  const animationSection = document.createElement("section");
  animationSection.className = "cd-sidebar-animation";

  for (const g of propTabGroups) tabBody.appendChild(g.group);
  tabBody.appendChild(animationSection);
  panel.appendChild(tabBody);

  // Initial state: open Page Background (bulky + commonly edited),
  // collapse the rest. The delegated handler takes over on click.
  const setSectionOpen = (g: { group: HTMLElement; body: HTMLElement }, open: boolean): void => {
    g.body.style.display = open ? "" : "none";
    const caret = g.group.querySelector<HTMLElement>(":scope > .cd-group-header > .cd-group-caret");
    if (caret) caret.style.transform = open ? "" : "rotate(-90deg)";
  };
  for (const g of propTabGroups) setSectionOpen(g, g === pageBgG);

  // Single-open accordion delegated at the sidebar level, but scoped
  // to sibling .cd-group elements that share the same parent <section>.
  // That keeps the per-item props sections (Transform / Text / Typography
  // / Shadow) mutually exclusive without closing Layers, which lives in
  // its own layersSection and should stay open while the author clicks
  // through the layer list. Without this scoping, clicking a layer row
  // (or any prop header) collapsed Layers as collateral damage.
  tabBody.addEventListener("click", (e) => {
    const target = e.target as HTMLElement | null;
    const header = target?.closest<HTMLElement>(".cd-group-header");
    if (!header || !tabBody.contains(header)) return;
    const section = header.parentElement as HTMLElement | null;
    if (!section || !section.classList.contains("cd-group")) return;
    const body = section.querySelector<HTMLElement>(":scope > .cd-group-body");
    const willOpen = body !== null && body.style.display === "none";
    // One section is ALWAYS open: clicking the already-open header is a
    // no-op instead of a collapse — the strict accordion otherwise allows
    // an all-collapsed panel, which reads as "my properties disappeared".
    if (!willOpen) return;
    // Limit the close-all sweep to siblings under the same parent
    // <section> (propsSection, layersSection, ...). Peers in other
    // sidebar sections are left alone.
    const peerHost = section.parentElement;
    if (!peerHost) return;
    for (const s of peerHost.querySelectorAll<HTMLElement>(":scope > .cd-group")) {
      const b = s.querySelector<HTMLElement>(":scope > .cd-group-body");
      const c = s.querySelector<HTMLElement>(":scope > .cd-group-header > .cd-group-caret");
      const open = s === section ? willOpen : false;
      if (b) b.style.display = open ? "" : "none";
      if (c) c.style.transform = open ? "" : "rotate(-90deg)";
    }
  });

  host.appendChild(panel);

  function setTab(key: TabKey): void {
    for (const [k, btn] of tabBtns) {
      const active = k === key;
      btn.setAttribute("aria-selected", active ? "true" : "false");
      btn.style.color = active ? tokens.ink100 : tokens.ink300;
      btn.style.fontWeight = active ? "600" : "400";
      btn.style.borderBottomColor = active ? tokens.amber : "transparent";
    }
    // Toggle the whole collapsible group (not its body) so each
    // section's open/closed accordion state survives a tab switch.
    for (const g of propTabGroups) g.group.style.display = key === "properties" ? "" : "none";
    animationSection.style.display = key === "animation" ? "" : "none";
  }
  setTab("properties");

  // Selecting a component on the canvas opens the Properties tab + section
  // so the author immediately sees that item's controls. Without this,
  // selection lands silently behind whatever section was last open
  // (commonly Layers) and the props panel stays hidden in a collapsed
  // section. Fired by the canvas overlay only — clicking a layer ROW does
  // NOT fire it, so browsing the Layers list keeps Layers open.
  document.addEventListener("cd:component-selected", () => {
    setTab("properties");
    for (const g of propTabGroups) setSectionOpen(g, g === propsG);
  });

  // Clicking the creative's BACKGROUND (empty canvas, deselect) surfaces the
  // Page Background section — the background is what the author just picked.
  // Also keeps the always-one-open invariant across deselection: without
  // this, Properties stays "open" with an emptied body and the sidebar
  // reads as all-collapsed.
  document.addEventListener("cd:background-selected", () => {
    setTab("properties");
    for (const g of propTabGroups) setSectionOpen(g, g === pageBgG);
  });

  return { bannerConfigSection, pageBgSection, propsSection, animationSection, layersSection };
}
