// Designer UI translation (GH #498). English text is the key, as in the
// dashboard's internal/i18n; a missing entry renders the English key.
// The language comes from <html lang>, which creative-design.html renders
// from the user's resolved dashboard language.
//
// Only DISPLAYED text goes through t(). Stored values (item types, field
// names, CSS values, preset ids, brand-kit role names) stay English — a
// translated string that becomes data breaks lookups (the dashboard's
// 2026-07-17 brand-kit regression).

import { JA } from "./i18n/ja";

type Lang = "en" | "ja";

let cached: Lang | null = null;

function resolve(tag: string | null | undefined): Lang {
  return /^ja(-|$)/i.test(tag ?? "") ? "ja" : "en";
}

export function lang(): Lang {
  if (cached === null) {
    cached = resolve(typeof document === "undefined" ? "" : document.documentElement.lang);
  }
  return cached;
}

/** Test hook: force a language, or null to re-read <html lang>. */
export function setLang(tag: string | null): void {
  cached = tag === null ? null : resolve(tag);
}

/** Translate `key`, filling %s / %d placeholders in order. */
export function t(key: string, ...args: (string | number)[]): string {
  const s = (lang() === "ja" ? JA[key] : undefined) ?? key;
  let i = 0;
  return args.length ? s.replace(/%[sd]/g, (m) => (i < args.length ? String(args[i++]) : m)) : s;
}

/** Identity marker for a key kept in a table and translated later with
  * t(); lets the drift test find it. */
export function tk<T extends string>(key: T): T {
  return key;
}
