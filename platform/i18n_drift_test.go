package platform

// The i18n drift guard: the Japanese catalog and the strings the
// dashboard actually renders must move together.
//
//  1. Every literal key used in templates ({{t "..."}}) or Go code
//     (i18n.T / jsonErrorT) must exist in the ja catalog — a missing key
//     silently falls back to English, which is exactly the drift this
//     test exists to catch.
//  2. Every catalog entry must be used somewhere (modulo dynamicKeys —
//     enum values rendered via {{t .Status}} that a literal scanner
//     cannot see).
//  3. Values must not contain ' " \ ` or </ — translations are
//     interpolated into Alpine attribute expressions where
//     html/template cannot protect them.
//  4. printf verbs in a value must match its key (indexed %[n]s forms
//     count as their verb), or T() panics at request time.

import (
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"testing"

	"github.com/hanishi/promovolve/platform/internal/i18n"
)

// dynamicKeys are catalog entries looked up through non-literal keys
// ({{t .Something}} / i18n.T(lang, someVar)); the scanner cannot see
// them, so they are exempt from the unused-key check. Keep this list
// honest — every entry should name where the dynamic lookup happens.
var dynamicKeys = map[string]string{
	"advertiser":                     "account-preferences.html landing-side radios ({{t .}})",
	"publisher":                      "account-preferences.html landing-side radios ({{t .}})",
	"7d":                             "report-range-picker preset labels ({{t .Label}}, report.go reportPresets)",
	"30d":                            "report-range-picker preset labels ({{t .Label}}, report.go reportPresets)",
	"This month":                     "report-range-picker preset labels ({{t .Label}}, report.go reportPresets)",
	"Active":                         "creatives.html {{t .ActiveStatus}}",
	"Paused":                         "creatives.html {{t .ActiveStatus}}",
	"Draft":                          "creatives.html {{t .ActiveStatus}}",
	"active":                         "creatives.html {{t .Status}} legacy fallback (core enum)",
	"paused":                         "creatives.html {{t .Status}} legacy fallback (core enum)",
	"topup":                          "wallet.html {{t .Kind}} (billing.TxnKind)",
	"settlement":                     "wallet.html {{t .Kind}} (billing.TxnKind)",
	"payout":                         "wallet.html {{t .Kind}} (billing.TxnKind)",
	"adjustment":                     "wallet.html {{t .Kind}} (billing.TxnKind)",
	"refund":                         "wallet.html {{t .Kind}} (billing.TxnKind)",
	"email already registered":       "handler.go handleLoginPost sentinel switch (i18n.T(lang, msg))",
	"invalid credentials":            "handler.go handleLoginPost sentinel switch (i18n.T(lang, msg))",
	"pending":                        "payout/site-request status pills ({{t .Status}})",
	"paid":                           "payout status pills ({{t .Status}})",
	"cancelled":                      "payout status pills ({{t .Status}})",
	"low_balance":                    "billing account status ({{t .Status}})",
	"suspended":                      "billing account status ({{t .Status}})",
	"rejected":                       "site-request status ({{t .Status}})",
	"Stable optimum":                 "site-observations argmax stability ({{t .Status}})",
	"Mildly variable":                "site-observations argmax stability ({{t .Status}})",
	"Highly variable":                "site-observations argmax stability ({{t .Status}})",
	"Insufficient data":              "site-observations argmax stability ({{t .Status}})",
	"Converging":                     "site-observations argmax stability ({{t .Status}})",
	"Benched — advertiser suspended": "approval preview-pane badge ({{t .Badge}}, handler.go)",
	"Benched — out of budget today, resumes at rollover": "approval preview-pane badge ({{t .Badge}}, handler.go)",
	"Benched — campaign budget spent for today":          "approval preview-pane badge ({{t .Badge}}, handler.go)",
	"Pin-only (below floor)":                             "approval preview-pane badge ({{t .Badge}})",
	"Auto-approved":                                      "approval preview-pane ({{t .ExtraBadge}})",
	"Not relevant to my content":                         "approval flagged reason ({{t $c.Reason}}, Decline form value)",
	"Campaign":                                           "trusted.html anchor type ({{t .Type}}) + report dict heads",
	"Landing domain":                                     "trusted.html anchor type ({{t .Type}})",
	"member":                                             "org/members.html + admin/users.html {{t .OrgRole}}",
}

var (
	// Matches action form {{t "..."}}, pipeline form (t "..."), and
	// assignment/pipe forms {{$x = t "..."}} / {{... | t "..."}}.
	tmplKeyRe = regexp.MustCompile(`[({=|]\s*t\s+"((?:[^"\\]|\\.)*)"`)
	goKeyRe   = regexp.MustCompile(`(?:i18n\.T|jsonErrorT)\([^"\n]+"((?:[^"\\]|\\.)*)"`)
	// Page titles are plain literals at the call sites; renderStatus
	// translates them centrally, so they count as used keys.
	titleKeyRe = regexp.MustCompile(`Title:\s*"((?:[^"\\]|\\.)*)"\s*,`)
	verbRe     = regexp.MustCompile(`%(?:\[\d+\])?[sdvfq%]`)
)

func scanUsedKeys(t *testing.T) map[string]bool {
	t.Helper()
	used := map[string]bool{}
	scan := func(root, ext string, re *regexp.Regexp) {
		err := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
			if err != nil || d.IsDir() || !strings.HasSuffix(path, ext) {
				return err
			}
			if strings.HasSuffix(path, "_test.go") {
				return nil
			}
			b, err := os.ReadFile(path)
			if err != nil {
				return err
			}
			for _, m := range re.FindAllStringSubmatch(string(b), -1) {
				key := strings.ReplaceAll(m[1], `\"`, `"`)
				used[key] = true
			}
			return nil
		})
		if err != nil {
			t.Fatalf("scan %s: %v", root, err)
		}
	}
	scan("templates", ".html", tmplKeyRe)
	scan("internal", ".go", goKeyRe)
	scan("internal", ".go", titleKeyRe)
	return used
}

func verbs(s string) []string {
	raw := verbRe.FindAllString(s, -1)
	out := make([]string, 0, len(raw))
	for _, v := range raw {
		if v == "%%" {
			continue
		}
		// Normalize away the index: %[2]s counts as %s.
		if i := strings.IndexByte(v, ']'); i >= 0 {
			v = "%" + v[i+1:]
		}
		out = append(out, v)
	}
	sort.Strings(out)
	return out
}

func TestI18nCatalogCoversUsedKeys(t *testing.T) {
	used := scanUsedKeys(t)
	catalog := i18n.Catalog()
	var missing []string
	for k := range used {
		if _, ok := catalog[k]; !ok {
			missing = append(missing, k)
		}
	}
	sort.Strings(missing)
	if len(missing) > 0 {
		t.Errorf("keys used but missing from the ja catalog (%d) — add them to internal/i18n/ja.go:\n  %s",
			len(missing), strings.Join(missing, "\n  "))
	}
}

func TestI18nCatalogHasNoUnusedKeys(t *testing.T) {
	used := scanUsedKeys(t)
	var unused []string
	for k := range i18n.Catalog() {
		if !used[k] && dynamicKeys[k] == "" {
			unused = append(unused, k)
		}
	}
	sort.Strings(unused)
	if len(unused) > 0 {
		t.Errorf("catalog keys no longer used anywhere (%d) — delete them or register in dynamicKeys:\n  %s",
			len(unused), strings.Join(unused, "\n  "))
	}
}

func TestI18nValuesAreAlpineSafe(t *testing.T) {
	for k, v := range i18n.Catalog() {
		for _, bad := range []string{`'`, `"`, `\`, "`", "</"} {
			if strings.Contains(v, bad) {
				t.Errorf("catalog value for %q contains %q — forbidden (Alpine attribute contexts get no JS escaping); use 「」 instead", k, bad)
			}
		}
	}
}

func TestI18nPrintfVerbsMatch(t *testing.T) {
	for k, v := range i18n.Catalog() {
		kv, vv := verbs(k), verbs(v)
		if strings.Join(kv, ",") != strings.Join(vv, ",") {
			t.Errorf("printf verbs differ for key %q: key has %v, value has %v", k, kv, vv)
		}
	}
}
