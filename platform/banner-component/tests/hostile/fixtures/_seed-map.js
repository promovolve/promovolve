// Shared seeding: create the banner element with a fixture creative.
// Kept as a plain external script so strict-CSP fixtures (which forbid
// inline scripts) can use it too.
(function () {
  var T = function (t, top) { return { type: "text", text: t, left: 8, top: top, width: 84, fontSize: 9, color: "#f5c518", fontWeight: 800 }; };
  // Real creatives carry the poster in the portrait master
  // (banners["mobile-expanded"]); `layout` is the hidden 16:9 wide master.
  // The wide master here is deliberately WRONG: if it ever reaches the
  // map, the probe sees "WIDE MASTER ONLY" in the map root and fails.
  var PAGES = [
    { bg: "#1a1a2e",
      layout: [T("WIDE MASTER ONLY", 40)],
      banners: { "mobile-expanded": [T("MAP TOP", 12), T("MAP MIDDLE", 46), T("MAP BOTTOM", 80)] } },
  ];
  function seed() {
    var slot = document.getElementById("slot");
    if (!slot) return;
    var el = document.createElement("expandable-magazine-banner");
    el.setAttribute("pages", JSON.stringify(PAGES));
    el.setAttribute("width", "300");
    el.setAttribute("height", "250");
    el.setAttribute("config", JSON.stringify({ format: "map" }));
    el.setAttribute("landing-url", "/tests/hostile/fixtures/_lp.html");
    slot.appendChild(el);
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", seed);
  else seed();
})();
