/**
 * LP Analyzer — Dual extraction strategies.
 *
 * Call extractSections('heading') for heading-based (top-down from headings)
 * Call extractSections('image') for image-based (bottom-up from images)
 */
window.extractSections = function (strategy) {
  "use strict";

  var SKIP_TAGS = new Set(["SCRIPT", "STYLE", "NOSCRIPT", "SVG", "IFRAME"]);
  var CHROME_TAGS = new Set(["NAV", "FOOTER"]);
  var CHROME_ROLES = new Set(["navigation", "contentinfo"]);

  // ─── Shared helpers ───

  function isChrome(el) {
    if (CHROME_TAGS.has(el.tagName)) return true;
    var role = el.getAttribute("role");
    if (role && CHROME_ROLES.has(role)) return true;
    var cls = (el.className || "").toString().toLowerCase();
    // Narrow list — "header" and "menu" were too greedy and killed
    // hero-* / promo-menu-* sections that are actually content.
    if (/\b(navbar|global-nav|footer|breadcrumb|sidebar|cookie)\b/.test(cls)) return true;
    return false;
  }

  function isInsideChrome(el) {
    var current = el;
    while (current && current !== document.body) {
      if (isChrome(current)) return true;
      current = current.parentElement;
    }
    return false;
  }

  function resolveUrl(src) {
    try { return new URL(src, document.baseURI || window.location.href).href; }
    catch (_) { return null; }
  }

  // CSS-background images often live on empty/positioned elements
  // (slideshow frames, decorative overlays) that have 0×0 own-dimensions.
  // Walk up to the nearest laid-out ancestor so the entry carries a real
  // size and doesn't sort to the bottom of an area-ordered list.
  function sizeFromBgHost(el) {
    var w = el.offsetWidth || 0;
    var h = el.offsetHeight || 0;
    var cur = el.parentElement;
    var hops = 0;
    while ((w === 0 || h === 0) && cur && cur !== document.body && hops < 6) {
      if (cur.offsetWidth) w = cur.offsetWidth;
      if (cur.offsetHeight) h = cur.offsetHeight;
      cur = cur.parentElement;
      hops++;
    }
    return { width: w, height: h };
  }

  function collectText(el) {
    var parts = [];
    var walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT, {
      acceptNode: function (node) {
        var parent = node.parentElement;
        if (parent && SKIP_TAGS.has(parent.tagName)) return NodeFilter.FILTER_REJECT;
        if (parent && /^H[1-6]$/.test(parent.tagName)) return NodeFilter.FILTER_REJECT;
        return NodeFilter.FILTER_ACCEPT;
      }
    });
    var node;
    while ((node = walker.nextNode())) {
      var t = (node.nodeValue || "").replace(/[\u00A0\s]+/g, " ").trim();
      if (t.length > 0) parts.push(t);
    }
    return parts.join(" ").trim();
  }

  function findHeading(el) {
    for (var i = 1; i <= 6; i++) {
      var h = el.querySelector("h" + i);
      if (h) return (h.innerText || "").replace(/[\u00A0\s]+/g, " ").trim();
    }
    return "";
  }

  function collectImages(container) {
    var imgs = [];
    var seen = new Set();

    container.querySelectorAll("img").forEach(function (img) {
      var src = img.currentSrc // the srcset variant the browser ACTUALLY loaded (== what the analysis captured for R2)
        || img.getAttribute("src")
        || img.getAttribute("data-src")
        || img.getAttribute("data-lazy-src")
        || img.getAttribute("data-original");
      if (!src) return;
      var resolved = resolveUrl(src);
      if (!resolved || !resolved.startsWith("http") || seen.has(resolved)) return;
      var w = img.naturalWidth || img.width || img.offsetWidth || 0;
      var h = img.naturalHeight || img.height || img.offsetHeight || 0;
      if (w === 1 || h === 1) return;
      seen.add(resolved);
      imgs.push({ src: resolved, width: w, height: h, alt: (img.getAttribute("alt") || "").trim() });
    });

    container.querySelectorAll("picture source[srcset]").forEach(function (source) {
      var srcset = source.getAttribute("srcset");
      if (!srcset) return;
      var parts = srcset.split(",").map(function(s) { return s.trim().split(/\s+/)[0]; });
      var src = parts[parts.length - 1];
      var resolved = resolveUrl(src);
      if (!resolved || !resolved.startsWith("http") || seen.has(resolved)) return;
      seen.add(resolved);
      var pic = source.closest("picture");
      var imgEl = pic ? pic.querySelector("img") : null;
      imgs.push({
        src: resolved,
        width: imgEl ? (imgEl.naturalWidth || imgEl.offsetWidth || 0) : 0,
        height: imgEl ? (imgEl.naturalHeight || imgEl.offsetHeight || 0) : 0,
        alt: imgEl ? (imgEl.getAttribute("alt") || "").trim() : ""
      });
    });

    [container].concat(Array.from(container.querySelectorAll("*")).slice(0, 200)).forEach(function (el) {
      try {
        var style = window.getComputedStyle(el);
        var bg = style.backgroundImage;
        if (!bg || bg === "none") bg = el.getAttribute("style") || "";
        if (!bg) return;
        var urlRe = /url\(\s*["']?([^"')\s]+)["']?\s*\)/g;
        var m;
        while ((m = urlRe.exec(bg)) !== null) {
          var raw = m[1];
          if (raw.startsWith("data:")) continue;
          var resolved = resolveUrl(raw);
          if (!resolved || !resolved.startsWith("http") || seen.has(resolved)) continue;
          seen.add(resolved);
          var sz = sizeFromBgHost(el);
          imgs.push({ src: resolved, width: sz.width, height: sz.height, alt: "" });
        }
      } catch (_) {}
    });

    // Drop non-editorial junk so the advertiser picks from real content
    // images, not UI furniture. Conservative: only removes images we can
    // POSITIVELY identify as junk — unknown-size images (lazy-loaded,
    // naturalWidth still 0) are kept rather than guessed away.
    imgs = imgs.filter(function (img) {
      var w = img.width || 0, h = img.height || 0;
      // Too small to be a content/product image: favicons, icons, small
      // logos, share buttons. naturalWidth is intrinsic, so this is reliable.
      if (w && h && (w < 120 || h < 120)) return false;
      // Extreme aspect ratio: sprite sheets, hairline rules, ultra-thin bars.
      if (w && h && Math.max(w, h) / Math.min(w, h) > 6) return false;
      var hay = (img.src + " " + (img.alt || "")).toLowerCase();
      // Strong non-content signals — always drop.
      if (/favicon|sprite|spinner|loader|placeholder|tracking|1x1|pixel\.|\/icons?\//.test(hay)) return false;
      // Logos/avatars/badges: drop only when also small-ish (a large hero with
      // "logo" in its path is probably real content; a 64px one isn't).
      if (/logo|avatar|badge/.test(hay) && w && h && (w < 300 || h < 300)) return false;
      return true;
    });
    imgs.sort(function (a, b) { return (b.width * b.height) - (a.width * a.height); });
    return imgs;
  }

  function dedup(sections) {
    sections.sort(function (a, b) { return a.top - b.top; });
    var seen = new Set();
    var result = [];
    sections.forEach(function (s) {
      var key = s.heading || s.text.substring(0, 50);
      if (!key) { result.push(s); return; }
      if (seen.has(key)) return;
      seen.add(key);
      result.push(s);
    });
    return result;
  }

  // ═══════════════════════════════════════════════════════════════
  // STRATEGY: HEADING (top-down from headings)
  // ═══════════════════════════════════════════════════════════════

  function extractByHeading() {
    var headings = document.querySelectorAll("h1, h2, h3, h4, h5, h6");
    var usedContainers = new Set();
    var sections = [];

    headings.forEach(function (heading) {
      if (isInsideChrome(heading)) return;
      var headingText = (heading.innerText || "").replace(/[\u00A0\s]+/g, " ").trim();
      if (!headingText) return;

      var container = heading.parentElement;
      if (!container || container === document.body) return;

      var siblingText = collectText(container);
      if (siblingText.length < 10 && container.parentElement && container.parentElement !== document.body) {
        container = container.parentElement;
        siblingText = collectText(container);
      }

      if (usedContainers.has(container)) return;
      usedContainers.add(container);

      var images = collectImages(container);

      // Skip heading-only items (news list, blog titles) — need images or real text
      if (images.length === 0 && siblingText.length === 0) return;

      var rect = container.getBoundingClientRect();
      sections.push({
        heading: headingText,
        text: siblingText.substring(0, 2500),
        images: images,
        top: Math.round(rect.top + window.scrollY)
      });
    });

    // Also find orphan images not inside any heading's container
    document.querySelectorAll("img").forEach(function (img) {
      if (isInsideChrome(img)) return;
      var parent = img;
      var isUsed = false;
      while (parent && parent !== document.body) {
        if (usedContainers.has(parent)) { isUsed = true; break; }
        parent = parent.parentElement;
      }
      if (isUsed) return;

      var src = img.getAttribute("src") || img.getAttribute("data-src") || img.getAttribute("data-lazy-src");
      if (!src) return;
      var resolved = resolveUrl(src);
      if (!resolved || !resolved.startsWith("http")) return;
      var w = img.naturalWidth || img.width || img.offsetWidth || 0;
      var h = img.naturalHeight || img.height || img.offsetHeight || 0;
      if (w === 1 || h === 1) return;

      var container = img.parentElement;
      while (container && container !== document.body) {
        var text = collectText(container);
        if (text.length >= 10) break;
        container = container.parentElement;
      }
      if (!container || container === document.body) return;
      if (usedContainers.has(container)) return;
      usedContainers.add(container);

      var images = collectImages(container);
      var text = collectText(container);
      var rect = container.getBoundingClientRect();
      sections.push({ heading: "", text: text.substring(0, 2500), images: images, top: Math.round(rect.top + window.scrollY) });
    });

    sections = dedup(sections);

    // Same hero fallback as the image strategy: og:image + doc-wide CSS
    // background sweep + raw <style> textContent scan. Catches hero
    // slideshow frames (empty <p> with media-queried backgrounds) and
    // SP/PC variants that getComputedStyle only surfaces for one branch.
    var attached = new Set();
    sections.forEach(function(s) { s.images.forEach(function(img) { attached.add(img.src); }); });
    var heroImgs = collectHeroImages(attached);
    if (heroImgs.length > 0) {
      var h1 = document.querySelector("h1");
      sections = [{
        heading: (h1 && h1.innerText.trim()) || "",
        text: "",
        images: heroImgs,
        top: 0
      }].concat(sections);
    }

    return sections;
  }

  // ═══════════════════════════════════════════════════════════════
  // STRATEGY: IMAGE (bottom-up from images)
  // ═══════════════════════════════════════════════════════════════

  function extractByImage() {
    // Collect all substantial images on the page
    var allImages = [];
    var seenSrc = new Set();

    document.querySelectorAll("img").forEach(function (img) {
      var src = img.currentSrc // the srcset variant the browser ACTUALLY loaded (== what the analysis captured for R2)
        || img.getAttribute("src")
        || img.getAttribute("data-src")
        || img.getAttribute("data-lazy-src")
        || img.getAttribute("data-original");
      if (!src) return;
      var resolved = resolveUrl(src);
      if (!resolved || !resolved.startsWith("http") || seenSrc.has(resolved)) return;
      var w = img.naturalWidth || img.width || img.offsetWidth || 0;
      var h = img.naturalHeight || img.height || img.offsetHeight || 0;
      if (w === 1 || h === 1) return;
      seenSrc.add(resolved);
      allImages.push({ el: img, src: resolved, width: w, height: h, alt: (img.getAttribute("alt") || "").trim() });
    });

    // Also CSS background images — sweep every element (scripts/styles
    // excluded). Widened regex matches any url(...), not just absolute
    // http(s), and resolveUrl() makes relative paths absolute. Inline
    // style= attributes are a fallback when getComputedStyle returns
    // "none" (can happen when a parent cascade overrides).
    var bgUrlRe = /url\(\s*["']?([^"')\s]+)["']?\s*\)/g;
    document.querySelectorAll("*").forEach(function (el) {
      if (!el || el.tagName === "SCRIPT" || el.tagName === "STYLE" || el.tagName === "NOSCRIPT") return;
      try {
        var style = window.getComputedStyle(el);
        var bg = style.backgroundImage;
        if (!bg || bg === "none") bg = el.getAttribute("style") || "";
        if (!bg) return;
        var m;
        while ((m = bgUrlRe.exec(bg)) !== null) {
          var raw = m[1];
          if (raw.startsWith("data:")) continue;
          var resolved = resolveUrl(raw);
          if (!resolved || !resolved.startsWith("http") || seenSrc.has(resolved)) continue;
          seenSrc.add(resolved);
          var sz = sizeFromBgHost(el);
          allImages.push({ el: el, src: resolved, width: sz.width, height: sz.height, alt: "" });
        }
        bgUrlRe.lastIndex = 0;
      } catch (_) {}
    });

    // Walk up from each image to find content boundary
    var boundaryMap = new Map();

    function hasEnoughText(el) {
      var heading = findHeading(el);
      if (heading.length > 0) return true;
      return collectText(el).length > 0;
    }

    allImages.forEach(function (img) {
      var current = img.el.parentElement;
      var boundary = null;
      while (current && current !== document.body) {
        if (isChrome(current)) break;
        if (hasEnoughText(current)) { boundary = current; break; }
        current = current.parentElement;
      }
      if (!boundary) return;

      if (!boundaryMap.has(boundary)) {
        var heading = findHeading(boundary);
        var text = collectText(boundary);
        if (heading && text.startsWith(heading)) text = text.substring(heading.length).trim();
        boundaryMap.set(boundary, {
          heading: heading, text: text.substring(0, 2500), images: [],
          top: Math.round(boundary.getBoundingClientRect().top + window.scrollY)
        });
      }

      var entry = boundaryMap.get(boundary);
      if (!entry.images.some(function(e) { return e.src === img.src; })) {
        entry.images.push({ src: img.src, width: img.width, height: img.height, alt: img.alt });
      }
    });

    // Also text-only sections
    var textCandidates = document.querySelectorAll("section, article, [role='main'], main");
    if (textCandidates.length === 0) {
      var parents = new Set();
      document.querySelectorAll("div > h1, div > h2, div > h3").forEach(function(h) {
        if (h.parentElement) parents.add(h.parentElement);
      });
      textCandidates = Array.from(parents);
    }
    textCandidates.forEach(function (el) {
      if (isChrome(el) || boundaryMap.has(el)) return;
      var heading = findHeading(el);
      var text = collectText(el);
      if (text.length === 0 && !heading) return;
      var childSections = el.querySelectorAll("section, article");
      if (childSections.length > 6) return;
      boundaryMap.set(el, {
        heading: heading, text: text.substring(0, 2500), images: [],
        top: Math.round(el.getBoundingClientRect().top + window.scrollY)
      });
    });

    var sections = Array.from(boundaryMap.values());
    sections.forEach(function (s) {
      s.images.sort(function (a, b) { return (b.width * b.height) - (a.width * a.height); });
    });

    // Filter out noise: need images or substantial text
    sections = sections.filter(function(s) {
      return s.images.length > 0 || s.text.length > 0;
    });

    // Hero fallback: surface the obvious big images even when they
    // live outside a detected text section — og:image, body/html
    // background, and the largest <img> in main/article. Dedupe
    // against images already attached to sections.
    var attached = new Set();
    sections.forEach(function(s) {
      s.images.forEach(function(img) { attached.add(img.src); });
    });
    var heroImgs = collectHeroImages(attached);
    if (heroImgs.length > 0) {
      var heroSection = {
        heading: (document.querySelector("h1") && document.querySelector("h1").innerText.trim()) || "",
        text: "",
        images: heroImgs,
        top: 0
      };
      // Prepend so the hero lands first in the returned sections.
      sections = [heroSection].concat(sections);
    }

    return dedup(sections);
  }

  function collectHeroImages(alreadyAttached) {
    var heroes = [];
    var seen = new Set();
    var push = function(src, w, h) {
      if (!src || !src.startsWith("http")) return;
      if (seen.has(src) || alreadyAttached.has(src)) return;
      seen.add(src);
      heroes.push({ src: src, width: w || 0, height: h || 0, alt: "" });
    };

    // 1. og:image / twitter:image
    var ogSelectors = [
      'meta[property="og:image"]',
      'meta[property="og:image:url"]',
      'meta[property="og:image:secure_url"]',
      'meta[name="twitter:image"]',
      'meta[name="twitter:image:src"]'
    ];
    ogSelectors.forEach(function(sel) {
      document.querySelectorAll(sel).forEach(function(m) {
        var raw = m.getAttribute("content");
        if (!raw) return;
        push(resolveUrl(raw), 1200, 630); // typical og:image dims
      });
    });

    // 2. background-image sweep — every element with a url() in its
    // computed background gets a candidate. Covers the common "hero
    // as CSS background" case without having to guess which class the
    // site used (.hero / .mv / .key-visual / .cover / etc.). Relative
    // URLs are resolved against the document base so `url('./hero.jpg')`
    // works too. image-set() stacks and multi-layer backgrounds yield
    // multiple URLs per element.
    var bgUrlRegex = /url\(\s*["']?([^"')\s]+)["']?\s*\)/g;
    document.querySelectorAll("*").forEach(function(el) {
      if (!el || el.tagName === "SCRIPT" || el.tagName === "STYLE" || el.tagName === "NOSCRIPT") return;
      if (isInsideChrome(el)) return;
      try {
        var style = window.getComputedStyle(el);
        // Check both backgroundImage (shorthand-resolved) and
        // backgroundImage on each layer. The shorthand catches most.
        var bg = style.backgroundImage;
        if (!bg || bg === "none") {
          // Fallback to inline style= attribute, which getComputedStyle
          // may strip if the parent's cascade overrides it.
          bg = el.getAttribute("style") || "";
        }
        if (!bg) return;
        var m;
        while ((m = bgUrlRegex.exec(bg)) !== null) {
          var raw = m[1];
          if (raw.startsWith("data:")) continue; // skip inline data urls
          var resolved = resolveUrl(raw);
          if (!resolved || !resolved.startsWith("http")) continue;
          var sz = sizeFromBgHost(el);
          push(resolved, sz.width, sz.height);
        }
        bgUrlRegex.lastIndex = 0;
      } catch (_) {}
    });

    // 3. Every meaningful <img> on the page — the editor wants as
    // rich an image pool as possible. Anything bigger than a tracking
    // pixel counts; we sort the final list by size so the hero falls
    // out naturally on top.
    var root = document.querySelector("main") || document.querySelector("article") || document.body;
    var imgCandidates = [];
    root.querySelectorAll("img").forEach(function(img) {
      var src = img.getAttribute("src") || img.getAttribute("data-src") || img.getAttribute("data-lazy-src") || img.getAttribute("data-original");
      if (!src) return;
      var resolved = resolveUrl(src);
      if (!resolved || !resolved.startsWith("http")) return;
      var w = img.naturalWidth || img.width || img.offsetWidth || 0;
      var h = img.naturalHeight || img.height || img.offsetHeight || 0;
      if (w === 1 && h === 1) return; // tracking pixel
      imgCandidates.push({ src: resolved, w: w, h: h });
    });
    imgCandidates.sort(function(a, b) { return (b.w * b.h) - (a.w * a.h); });
    imgCandidates.forEach(function(c) { push(c.src, c.w, c.h); });

    // 4. Scan every <style> block's raw textContent for url(...) — this
    // catches media-queried variants (PC and SP hero slideshows, retina
    // sources, etc.) that getComputedStyle only surfaces for the
    // currently-matched @media branch. CSSOM's cssRules walk is
    // unreliable here: some inline <style> blocks (e.g. jexer's, placed
    // inside body) expose rules whose cssText comes back without the
    // url() declarations intact. Raw text matching bypasses all that.
    try {
      var styleRe = /url\(\s*["']?([^"')\s]+)["']?\s*\)/g;
      document.querySelectorAll("style").forEach(function(styleEl) {
        var css = styleEl.textContent || "";
        if (!css) return;
        var m;
        while ((m = styleRe.exec(css)) !== null) {
          var raw = m[1];
          if (raw.startsWith("data:")) continue;
          if (/\.(woff2?|ttf|otf|eot)(\?|$)/i.test(raw)) continue; // skip fonts
          var resolved = resolveUrl(raw);
          if (!resolved || !resolved.startsWith("http")) continue;
          push(resolved, 0, 0);
        }
      });
    } catch (_) {}

    return heroes;
  }

  // ─── Dispatch ───
  if (strategy === "image") return extractByImage();
  return extractByHeading();
};

// Dominant background colour of the LP. The creative is an
// extension of the LP and must match its colour scheme, so this
// value is applied to the creative's page backgrounds verbatim
// (advertiser cannot override).
//
// Strategy: walk html → body → main → first paint layer and take
// the first resolvable, non-transparent, non-pure-white backgroundColor.
// "Pure white" is rejected because most pages default to rgb(255,255,255)
// even when the real visual is a distinct colour — so we keep walking.
window.extractDominantColor = function () {
  "use strict";
  var candidates = [
    document.documentElement,
    document.body,
    document.querySelector("main"),
    document.querySelector("article"),
    document.querySelector("#main"),
    document.querySelector("#content"),
    document.querySelector(".container"),
  ].filter(Boolean);

  var isUsable = function (c) {
    if (!c) return false;
    if (c === "transparent" || c === "rgba(0, 0, 0, 0)") return false;
    // Pure white gets ignored — treat as "no specific scheme", keep
    // looking. Off-whites (#fafafa, etc.) are accepted.
    if (c === "rgb(255, 255, 255)" || c === "#ffffff" || c === "#fff") return false;
    return true;
  };

  for (var i = 0; i < candidates.length; i++) {
    var style = window.getComputedStyle(candidates[i]);
    var bg = style.backgroundColor;
    if (isUsable(bg)) return bg;
  }
  // Last resort: return body's background even if white, so callers
  // always get a concrete value.
  try {
    return window.getComputedStyle(document.body).backgroundColor || "";
  } catch (_) {
    return "";
  }
};

// Dominant body text colour of the LP. Mirrors extractDominantColor
// but reads the `color` property instead of `backgroundColor`. The
// creative applies this verbatim to its text so typography matches
// the LP's style.
// og:image / twitter:image / schema.org Product.image — the page's
// own canonical hero. Used as a fallback when DOM-scraped images fail
// to load (e.g. Akamai 502 on asset subdomains).
window.extractOgImage = function () {
  "use strict";
  var selectors = [
    'meta[property="og:image"]',
    'meta[property="og:image:secure_url"]',
    'meta[name="og:image"]',
    'meta[name="twitter:image"]',
    'meta[property="twitter:image"]',
    'link[rel="image_src"]',
  ];
  for (var i = 0; i < selectors.length; i++) {
    var el = document.querySelector(selectors[i]);
    if (el) {
      var val = el.getAttribute("content") || el.getAttribute("href");
      if (val) {
        try { return new URL(val, document.baseURI).href; }
        catch (_) { return val; }
      }
    }
  }
  // JSON-LD schema.org fallback — many product pages expose image in
  // a script[type="application/ld+json"] block.
  try {
    var scripts = document.querySelectorAll('script[type="application/ld+json"]');
    for (var j = 0; j < scripts.length; j++) {
      var data = JSON.parse(scripts[j].textContent || "null");
      if (!data) continue;
      var nodes = Array.isArray(data) ? data : [data];
      for (var k = 0; k < nodes.length; k++) {
        var img = nodes[k] && nodes[k].image;
        if (typeof img === "string") return new URL(img, document.baseURI).href;
        if (Array.isArray(img) && img.length > 0 && typeof img[0] === "string")
          return new URL(img[0], document.baseURI).href;
        if (img && typeof img === "object" && typeof img.url === "string")
          return new URL(img.url, document.baseURI).href;
      }
    }
  } catch (_) {}
  return "";
};

window.extractTextColor = function () {
  "use strict";
  var candidates = [
    document.querySelector("main p"),
    document.querySelector("article p"),
    document.querySelector("p"),
    document.querySelector("main"),
    document.querySelector("article"),
    document.body,
  ].filter(Boolean);

  var isUsable = function (c) {
    if (!c) return false;
    if (c === "transparent" || c === "rgba(0, 0, 0, 0)") return false;
    return true;
  };

  for (var i = 0; i < candidates.length; i++) {
    var style = window.getComputedStyle(candidates[i]);
    var col = style.color;
    if (isUsable(col)) return col;
  }
  try {
    return window.getComputedStyle(document.body).color || "";
  } catch (_) {
    return "";
  }
};

// Brand palette of the LP: the handful of colours that define the
// page's look, ordered by visual prominence. Seeds the creative's
// brand kit so the ad reads as an extension of the LP. Returns a
// JSON-stringified array of up to 6 lowercase #rrggbb strings (the
// Scala side just .toString + parses, mirroring the other extractors).
//
// Prominence = summed on-screen area of the elements painting each
// colour, sampling both backgrounds and text/link/button foregrounds.
// Near-duplicate colours are merged (channels quantised to a coarse
// grid) so a page's slight tonal variations collapse to one swatch.
window.extractPalette = function () {
  "use strict";
  try {
    var toHex = function (css) {
      if (!css) return null;
      css = ("" + css).trim();
      if (css.charAt(0) === "#") {
        if (css.length === 4)
          return ("#" + css[1] + css[1] + css[2] + css[2] + css[3] + css[3]).toLowerCase();
        if (css.length >= 7) return css.slice(0, 7).toLowerCase();
        return null;
      }
      var m = css.match(/rgba?\(([^)]+)\)/i);
      if (!m) return null;
      var p = m[1].split(",").map(function (s) { return parseFloat(s.trim()); });
      if (p.length >= 4 && p[3] === 0) return null; // fully transparent
      var h = function (n) {
        n = Math.max(0, Math.min(255, Math.round(n || 0)));
        return (n < 16 ? "0" : "") + n.toString(16);
      };
      return ("#" + h(p[0]) + h(p[1]) + h(p[2])).toLowerCase();
    };
    // Quantise to a coarse grid so near-identical tones merge. Key is
    // for dedup only; we keep the first (most prominent) literal value.
    var quant = function (hex) {
      var r = parseInt(hex.slice(1, 3), 16);
      var g = parseInt(hex.slice(3, 5), 16);
      var b = parseInt(hex.slice(5, 7), 16);
      var q = function (n) { return Math.round(n / 24) * 24; };
      return q(r) + "," + q(g) + "," + q(b);
    };

    var weights = {};   // quantKey -> total area
    var literal = {};   // quantKey -> representative hex (highest single area)
    var bestArea = {};  // quantKey -> that representative's area

    var bump = function (css, area) {
      var hex = toHex(css);
      if (!hex || area <= 0) return;
      var k = quant(hex);
      weights[k] = (weights[k] || 0) + area;
      if (!(k in bestArea) || area > bestArea[k]) {
        bestArea[k] = area;
        literal[k] = hex;
      }
    };

    var els = document.querySelectorAll(
      "body, header, main, section, article, div, h1, h2, h3, a, button, [role=button], .btn"
    );
    var max = Math.min(els.length, 1200); // bound work on huge pages
    for (var i = 0; i < max; i++) {
      var el = els[i];
      var rect = el.getBoundingClientRect();
      var area = Math.max(0, rect.width) * Math.max(0, rect.height);
      if (area <= 0) continue;
      var st = window.getComputedStyle(el);
      bump(st.backgroundColor, area);
      // Foreground only matters where there's actual text.
      if (el.textContent && el.textContent.trim()) bump(st.color, area * 0.35);
    }

    var keys = Object.keys(weights).sort(function (a, b) {
      return weights[b] - weights[a];
    });
    var out = [];
    for (var j = 0; j < keys.length && out.length < 6; j++) {
      var v = literal[keys[j]];
      if (out.indexOf(v) === -1) out.push(v);
    }
    return JSON.stringify(out);
  } catch (_) {
    return "[]";
  }
};

// Font faces used by the LP — heading family first, then body. Seeds
// the creative's brand-kit font list so typography matches the LP.
// Returns a JSON-stringified array of family names (the first token of
// each computed font-family stack, quotes stripped), deduped, up to 4.
window.extractFonts = function () {
  "use strict";
  try {
    // 400 omitted (no suffix = Regular). Folding the computed weight into
    // the family name as a named instance ("Montserrat" @100 -> "Montserrat
    // Thin") means the whole pipeline carries weight via one string; the
    // catalog (server + banner + editor) peels the suffix back to the base
    // family + numeric weight and self-hosts that exact face.
    var WEIGHT_NAME = { 100: "Thin", 200: "ExtraLight", 300: "Light", 500: "Medium",
      600: "SemiBold", 700: "Bold", 800: "ExtraBold", 900: "Black" };
    var WEIGHTY = { thin:1,hairline:1,extralight:1,ultralight:1,light:1,regular:1,normal:1,
      book:1,medium:1,semibold:1,demibold:1,bold:1,extrabold:1,ultrabold:1,black:1,heavy:1,
      extra:1,ultra:1,semi:1,demi:1,italic:1,oblique:1 };
    var firstFamily = function (stack) {
      if (!stack) return null;
      var first = ("" + stack).split(",")[0].trim().replace(/^["']|["']$/g, "");
      return first || null;
    };
    var hasWeightToken = function (fam) {
      var toks = fam.toLowerCase().split(/\s+/);
      return toks.length > 1 && !!WEIGHTY[toks[toks.length - 1]];
    };
    var roundWeight = function (w) {
      var n = parseInt(w, 10);
      if (!n || isNaN(n)) return 400;
      return Math.min(900, Math.max(100, Math.round(n / 100) * 100));
    };
    var picked = [];
    var add = function (sel) {
      var el = typeof sel === "string" ? document.querySelector(sel) : sel;
      if (!el) return;
      var st = window.getComputedStyle(el);
      var fam = firstFamily(st.fontFamily);
      if (!fam) return;
      if (!hasWeightToken(fam)) {
        var name = WEIGHT_NAME[roundWeight(st.fontWeight)];
        if (name) fam = fam + " " + name; // e.g. "Montserrat" -> "Montserrat Thin"
      }
      if (picked.indexOf(fam) === -1) picked.push(fam);
    };
    add("h1");
    add("h2");
    add("main p");
    add("article p");
    add("p");
    add(document.body);
    return JSON.stringify(picked.slice(0, 4));
  } catch (_) {
    return "[]";
  }
};


