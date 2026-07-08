window.extractContent = function ([regexString, target, opts]) {
    const options = Object.assign({
        withinOnly: true,           // text only from inside target
        includeShadowDom: false,    // traverse shadow roots
        includeIframes: false,      // traverse same-origin iframes
        dedupeLinks: true,         // remove duplicate (href,text) pairs
        maxLinks: 500,              // cap to avoid runaway pages
        useBaseElement: false,      // use <base href> for URL resolution (original = false)
        allowProtocolRelative: false// keep //host/path links? (original = false)
    }, opts || {});

    const base = options.useBaseElement ? (document.baseURI || window.location.href) : `${location.protocol}//${location.host}`;

    let linkRegex = null;
    if (typeof regexString === "string" && regexString.length > 0) {
        try {
            linkRegex = new RegExp(regexString);
        } catch { /* ignore invalid regex pattern */
        }
    }

    const targetElement = target ? document.querySelector(target) : null;

    const isSkippableTag = (el) => el && (el.tagName === "SCRIPT" || el.tagName === "STYLE" || el.tagName === "NOSCRIPT");

    const isInsideTarget = (node) => !!targetElement && (node === targetElement || targetElement.contains(node));

    const shouldKeepHref = (href) => {
        if (!href) return false;
        if (!options.allowProtocolRelative && /^\/\//.test(href)) return false;   // original behavior
        const low = href.trim().toLowerCase();
        return !(low.startsWith("javascript:") || low.startsWith("mailto:") || low.startsWith("tel:"));

    };

    const resolveHref = (href) => {
        try {
            return new URL(href, document.baseURI || window.location.href).href;
        } catch {
            return null;
        }
    };

    const links = [];
    const linkSet = new Set(); // for dedupe if enabled
    const pushLink = (href, text) => {
        const key = JSON.stringify({href, text});
        if (!options.dedupeLinks || !linkSet.has(key)) {
            links.push({href, text});
            if (options.dedupeLinks) linkSet.add(key);
        }
    };

    const textBuf = [];

    function walkRoot(root) {
        const walker = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT, {
            acceptNode(node) {
                if (node.nodeType === Node.ELEMENT_NODE && isSkippableTag(node)) {
                    return NodeFilter.FILTER_REJECT; // skip the whole subtree
                }
                return NodeFilter.FILTER_ACCEPT;
            }
        });

        for (let node = walker.currentNode; node; node = walker.nextNode()) {
            if (node.nodeType === Node.TEXT_NODE) {
                if (options.withinOnly ? isInsideTarget(node) : (!targetElement || isInsideTarget(node))) textBuf.push(node.nodeValue || "");
                continue;
            }

            const el = node;

            if (el.tagName === "A") {
                const hrefRaw = el.getAttribute("href");
                if (shouldKeepHref(hrefRaw)) {
                    const pass = !linkRegex || linkRegex.test(hrefRaw);
                    if (pass) {
                        const full = resolveHref(hrefRaw);
                        if (full) {
                            const aText = (el.innerText || "").replace(/[\u00A0\s]+/g, " ").trim();
                            pushLink(full, aText);
                        }
                    }
                }
                if (isInsideTarget(el)) {
                    const aText = (el.innerText || "").replace(/[\u00A0\s]+/g, " ").trim();
                    if (aText) textBuf.push(aText);
                }
            }

            if (options.includeShadowDom && el.shadowRoot) {
                walkRoot(el.shadowRoot);
            }
            if (links.length >= options.maxLinks) break;
        }
    }

    walkRoot(document.body);

    // optional: same-origin iframes
    if (options.includeIframes) {
        const iframes = Array.from(document.querySelectorAll("iframe"));
        for (const f of iframes) {
            try {
                if (f.contentDocument && f.contentDocument.body) {
                    walkRoot(f.contentDocument.body);
                }
            } catch { /* cross-origin — skip */
            }
            if (links.length >= options.maxLinks) break;
        }
    }

    const text = textBuf.join("").replace(/[\u00A0\s]+/g, " ").trim();
    return {text, links};
};

window.extractSlots = function () {
  const slots = [];
  const vh = window.innerHeight || 0;
  const vw = window.innerWidth || 0;
  const docH = Math.max(
    document.documentElement.scrollHeight || 0,
    document.body ? document.body.scrollHeight : 0,
    1
  );

  // Semantic / ARIA → geometry → text-density fallback cascade.
  // Returns one of: article|main|aside|footer|header|nav|unknown
  function detectRegion(el, rect, yTopAbs) {
    for (let n = el; n; n = n.parentElement) {
      const tag = n.tagName ? n.tagName.toLowerCase() : '';
      if (tag === 'article' || tag === 'main' || tag === 'aside' ||
          tag === 'footer' || tag === 'header' || tag === 'nav') return tag;
      const role = n.getAttribute ? (n.getAttribute('role') || '').toLowerCase() : '';
      if (role === 'main' || role === 'article') return 'main';
      if (role === 'complementary') return 'aside';
      if (role === 'contentinfo') return 'footer';
      if (role === 'banner') return 'header';
      if (role === 'navigation') return 'nav';
    }
    // Geometry fallback — based on absolute y within document.
    const yFrac = yTopAbs / docH;
    if (yFrac < 0.15) return 'header';
    if (yFrac > 0.85) return 'footer';
    // Sidebar heuristic: narrow + offset away from center horizontally.
    const slotW = rect.width || 0;
    if (vw > 0 && slotW > 0 && slotW < vw * 0.4) {
      const cx = rect.left + slotW / 2;
      const offCenter = Math.abs(cx - vw / 2) / (vw / 2);
      if (offCenter > 0.4) return 'aside';
    }
    return 'unknown';
  }

  // Walks up looking for an ancestor with substantial text — coarse but
  // markup-agnostic. Returns 0..1 where 1 = lots of nearby text.
  function textDensityNearby(el) {
    let n = el.parentElement;
    let depth = 0;
    while (n && depth < 4) {
      const t = (n.innerText || '').replace(/\s+/g, ' ').trim();
      if (t.length > 200) return Math.min(1, t.length / 1500);
      n = n.parentElement;
      depth += 1;
    }
    return 0;
  }

  document.querySelectorAll('[data-promovolve-slot]').forEach(function (el) {
    var slotId = el.getAttribute('data-promovolve-slot');
    var width = parseInt(el.getAttribute('data-w')) || el.offsetWidth;
    var height = parseInt(el.getAttribute('data-h')) || el.offsetHeight;
    if (!slotId || width <= 0 || height <= 0) return;

    var rect = el.getBoundingClientRect();
    var yTop = Math.round(rect.top + (window.scrollY || 0));
    var aboveFold = vh > 0 && rect.top < vh && rect.bottom > 0;

    var visibleH = 0;
    var visibleW = 0;
    if (vh > 0 && vw > 0) {
      visibleH = Math.max(0, Math.min(rect.bottom, vh) - Math.max(rect.top, 0));
      visibleW = Math.max(0, Math.min(rect.right, vw) - Math.max(rect.left, 0));
    }
    var slotArea = (rect.width || width) * (rect.height || height);
    var initialViewability = slotArea > 0 ? (visibleH * visibleW) / slotArea : 0;
    if (initialViewability > 1) initialViewability = 1;
    if (initialViewability < 0) initialViewability = 0;

    var region = detectRegion(el, rect, yTop);
    var textDensity = textDensityNearby(el);

    slots.push({
      slotId: slotId,
      width: width,
      height: height,
      yTop: yTop,
      docHeight: docH,
      aboveFold: aboveFold,
      initialViewability: initialViewability,
      region: region,
      textDensity: textDensity
    });
  });
  return slots;
};