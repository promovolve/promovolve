// Trusted Types support. Publishers can enforce
// `require-trusted-types-for 'script'` via CSP, which blocks raw string
// assignment to innerHTML / insertAdjacentHTML — the mount died with
// "This document requires 'TrustedHTML' assignment" (the hostile suite's
// trusted-types fixture reproduced it on every run).
//
// Every HTML string we assign is built by our own code from server-vetted
// creative data — never from page inputs — so a pass-through policy is the
// correct integration: it marks OUR strings as trusted without weakening
// the page's protection against anyone else's.
//
// Publishers that ALSO allowlist policy names (`trusted-types foo bar` in
// their CSP) must include "promovolve"; the integration guide says so.

interface TrustedTypesApi {
  createPolicy(
    name: string,
    rules: { createHTML(input: string): string },
  ): { createHTML(input: string): unknown };
}

const policy = (() => {
  try {
    const tt = (globalThis as { trustedTypes?: TrustedTypesApi }).trustedTypes;
    return tt ? tt.createPolicy("promovolve", { createHTML: (s) => s }) : null;
  } catch {
    // Duplicate policy name (bundle evaluated twice) or the page's
    // trusted-types allowlist excludes "promovolve". Fall back to plain
    // strings — enforcing pages then fail exactly as before the policy
    // existed, and the KNOWN failure shape stays diagnosable.
    return null;
  }
})();

/** Wrap an HTML string for a sink (innerHTML / insertAdjacentHTML).
  * Returns TrustedHTML where the API exists, the plain string elsewhere —
  * the cast keeps TypeScript's string-typed sinks happy either way. */
export function trustedHTML(html: string): string {
  return policy ? (policy.createHTML(html) as string) : html;
}
