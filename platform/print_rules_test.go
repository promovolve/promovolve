package platform

// The printed-page guard (DESIGN.md, The Ink Only Rule): the dashboard is
// black and white, like a newspaper. Ad creatives are the only colour on
// the page, and they never come from these sources. This scans what a
// class grep can see — templates (including Alpine :class strings), the
// stylesheet source and the Go code that hands classes to templates; the
// browser check in dashboard-tests/printed-page.run.mjs reads what
// actually renders.

import (
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"testing"
)

var (
	hueUtility = regexp.MustCompile(`\b(text|bg|border|ring|outline|fill|stroke|from|to|accent|divide|placeholder)-(red|orange|amber|yellow|lime|green|emerald|teal|cyan|sky|blue|indigo|violet|purple|fuchsia|pink|rose)-\d{2,3}\b`)
	// Check marks stand in for words; emoji render in colour whatever the
	// text colour is. Monochrome symbols (★ ☆ · →) take the ink and pass.
	glyph      = regexp.MustCompile(`[✓✔✗✕✨⏸]|[\x{1F300}-\x{1FAFF}]`)
	upperClass = regexp.MustCompile(`class="[^"]*\buppercase\b`)
	hexColour  = regexp.MustCompile(`#([0-9a-fA-F]{6})\b`)
	rgbColour  = regexp.MustCompile(`rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)`)
	// A fixed colour in a utility doesn't follow the theme: grey #1a1a1a
	// text on a token background goes unreadable in dark.
	fixedColour = regexp.MustCompile(`\b(text|bg|border|ring|outline|fill|stroke)-\[(#|rgb)`)
	// Copy that names a colour the page no longer has.
	colourWord = regexp.MustCompile(`\{\{t "[^"]*\b(green|amber|yellow|red|blue|orange|purple|pink)\b`)
)

func TestTemplatesArePrintedPage(t *testing.T) {
	type src struct{ path, text string }
	var templates []src
	err := filepath.WalkDir("templates", func(p string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(p, ".html") {
			return err
		}
		b, err := os.ReadFile(p)
		templates = append(templates, src{p, string(b)})
		return err
	})
	if err != nil {
		t.Fatal(err)
	}
	pages, err := os.ReadFile("internal/handler/pages.go")
	if err != nil {
		t.Fatal(err)
	}
	css, err := os.ReadFile("tailwind.input.css")
	if err != nil {
		t.Fatal(err)
	}
	withGo := append(templates, src{"internal/handler/pages.go", string(pages)}, src{"tailwind.input.css", string(css)})

	// scan reports every line of files where match returns non-empty.
	scan := func(t *testing.T, files []src, match func(line string) string) {
		for _, f := range files {
			for i, line := range strings.Split(f.text, "\n") {
				if m := match(line); m != "" {
					t.Errorf("%s:%d: %s", f.path, i+1, m)
				}
			}
		}
	}
	first := func(re *regexp.Regexp) func(string) string {
		return func(line string) string { return re.FindString(line) }
	}

	t.Run("hue-utilities", func(t *testing.T) { scan(t, withGo, first(hueUtility)) })
	t.Run("glyphs", func(t *testing.T) { scan(t, withGo, first(glyph)) })
	t.Run("uppercase", func(t *testing.T) { scan(t, templates, first(upperClass)) })
	t.Run("fixed-colour-utilities", func(t *testing.T) { scan(t, templates, first(fixedColour)) })
	t.Run("colour-words", func(t *testing.T) { scan(t, templates, first(colourWord)) })
	t.Run("hex", func(t *testing.T) {
		scan(t, templates, func(line string) string {
			for _, m := range hexColour.FindAllStringSubmatch(line, -1) {
				h := strings.ToLower(m[1])
				if h[0:2] != h[2:4] || h[2:4] != h[4:6] {
					return m[0]
				}
			}
			for _, m := range rgbColour.FindAllStringSubmatch(line, -1) {
				r, _ := strconv.Atoi(m[1])
				g, _ := strconv.Atoi(m[2])
				b, _ := strconv.Atoi(m[3])
				if r != g || g != b {
					return m[0]
				}
			}
			return ""
		})
	})
	t.Run("no-grayscale-filter", func(t *testing.T) {
		if strings.Contains(string(css), "grayscale(") {
			t.Error("tailwind.input.css: greyscale comes from the tokens, never from filter: grayscale (it would grey the ad creatives too)")
		}
	})
}
