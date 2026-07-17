// Package i18n is the dashboard's message catalog: English-text-as-key,
// with Japanese as the one translated locale. Zero dependencies.
//
// Developer guide (adding strings, the hard rules, the drift guard):
// dev_docs/I18N.md.
//
// Design (see the dashboard-i18n plan):
//   - The English string IS the key. English needs no catalog; a missing
//     key falls through to itself, so partially translated builds degrade
//     to English, never to a broken page.
//   - The catalog lives in ja.go as Go source — compile-time validated,
//     raw strings for multi-sentence prose, gofmt-stable diffs.
//   - Word-order differences use indexed printf verbs in the ja value:
//     key "Remove %s from %s?" → 「%[2]sから%[1]sを削除しますか？」.
//     T uses the *translated* string as the Sprintf format.
//   - Catalog values must not contain ' " \ ` or </ — translations are
//     interpolated into Alpine attribute expressions where html/template
//     cannot protect them (the drift test enforces this).
package i18n

import (
	"fmt"
	"strings"
)

// Langs the dashboard supports. "" on a user record means "auto" —
// resolve from the browser's Accept-Language.
const (
	LangEN = "en"
	LangJA = "ja"
)

// T translates key into lang. Unknown lang or missing key → the key
// itself (English). With args, the translated string is the Sprintf
// format — ja values may reorder with indexed verbs (%[2]s …).
func T(lang, key string, args ...any) string {
	s := key
	if lang == LangJA {
		if v, ok := ja[key]; ok {
			s = v
		}
	}
	if len(args) > 0 {
		return fmt.Sprintf(s, args...)
	}
	return s
}

// Resolve picks the request language: an explicit user preference wins;
// "" (auto) falls back to the browser's Accept-Language header, scanned
// in listed order for a primary subtag we support; default English.
// Deliberately minimal — no q-value sorting, no golang.org/x/text.
func Resolve(pref, acceptLanguage string) string {
	switch pref {
	case LangEN, LangJA:
		return pref
	}
	for _, part := range strings.Split(acceptLanguage, ",") {
		tag := strings.TrimSpace(part)
		if i := strings.IndexAny(tag, ";-"); i >= 0 {
			tag = tag[:i]
		}
		switch strings.ToLower(tag) {
		case LangJA:
			return LangJA
		case LangEN:
			return LangEN
		}
	}
	return LangEN
}

// Catalog exposes the ja map for the drift test (read-only by convention).
func Catalog() map[string]string { return ja }
