// Shared seeding: create the banner element with a fixture creative.
// Kept as a plain external script so strict-CSP fixtures (which forbid
// inline scripts) can use it too.
(function () {
  var PAGES = [
    { bg: "#1a1a2e", layout: [
      { type: "text", text: "HOSTILE ENV", left: 8, top: 20, width: 84, fontSize: 13, color: "#f5c518", fontWeight: 800 },
      { type: "text", text: "environment fixture", left: 8, top: 50, width: 84, fontSize: 7, color: "#e8e8e8" },
      { type: "rect", left: 0, top: 70, width: 100, height: 30,
        fill: "linear-gradient(to top, rgba(0,0,0,0.9), rgba(0,0,0,0))" },
    ]},
    { bg: "#2d132c", layout: [
      { type: "text", text: "Page 2", left: 8, top: 44, width: 84, fontSize: 11, color: "#fff", fontWeight: 700 },
    ]},
    { bg: "#0b3d2e", layout: [
      { type: "text", text: "Page 3", left: 8, top: 44, width: 84, fontSize: 11, color: "#fff", fontWeight: 700 },
    ]},
  ];
  function seed() {
    var slot = document.getElementById("slot");
    if (!slot) return;
    var el = document.createElement("expandable-magazine-banner");
    el.setAttribute("pages", JSON.stringify(PAGES));
    el.setAttribute("width", "300");
    el.setAttribute("height", "250");
    slot.appendChild(el);
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", seed);
  else seed();
})();
