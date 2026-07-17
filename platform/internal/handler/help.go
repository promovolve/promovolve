package handler

// In-app Help: the canonical user guides (docs/guides/, embedded as
// platform/help/*.md) rendered server-side with goldmark. One source of
// truth feeds the repo docs and both dashboards — no separate docs
// framework, no hand-written help HTML to drift.

import (
	"bytes"
	"embed"
	"html/template"
	"net/http"
	"regexp"
	"strings"
	"sync"

	"github.com/yuin/goldmark"
	"github.com/yuin/goldmark/extension"
	"github.com/yuin/goldmark/parser"

	"github.com/hanishi/promovolve/platform/internal/model"
)

var helpFSVar embed.FS

// SetHelpFS hands the embedded guide markdown to the handler (mirrors SetFS).
func SetHelpFS(help embed.FS) { helpFSVar = help }

type helpTOCEntry struct{ ID, Title string }

type helpPage struct {
	HTML template.HTML
	TOC  []helpTOCEntry
}

var (
	helpOnce  sync.Once
	helpPages map[string]helpPage
)

var helpHeadingRe = regexp.MustCompile(`(?m)^## +(.+?) *$`)

// renderHelp converts one guide to HTML + a section TOC. goldmark's
// WithAutoHeadingID produces GitHub-style ids, so the TOC anchors match.
func renderHelp(md []byte) helpPage {
	gm := goldmark.New(
		goldmark.WithExtensions(extension.GFM),
		goldmark.WithParserOptions(parser.WithAutoHeadingID()),
	)
	var buf bytes.Buffer
	if err := gm.Convert(md, &buf); err != nil {
		return helpPage{HTML: template.HTML("<p>help unavailable</p>")}
	}
	var toc []helpTOCEntry
	for _, m := range helpHeadingRe.FindAllStringSubmatch(string(md), -1) {
		title := strings.TrimSpace(m[1])
		// Mirror goldmark's auto-id: lowercase, spaces→dashes, strip
		// everything but letters/digits/dashes.
		id := strings.ToLower(title)
		id = strings.ReplaceAll(id, " ", "-")
		id = regexp.MustCompile(`[^a-z0-9-]`).ReplaceAllString(id, "")
		toc = append(toc, helpTOCEntry{ID: id, Title: title})
	}
	return helpPage{HTML: template.HTML(buf.String()), TOC: toc}
}

func loadHelpPages() {
	helpPages = map[string]helpPage{}
	for side, file := range map[string]string{
		"advertiser": "help/advertiser.md",
		"publisher":  "help/publisher.md",
	} {
		if md, err := helpFSVar.ReadFile(file); err == nil {
			helpPages[side] = renderHelp(md)
		}
	}
}

// HelpPage serves the side's guide inside the dashboard chrome.
func (h *Handler) HelpPage(side model.Role) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		user, _ := h.sessionUser(r)
		if user == nil {
			http.Redirect(w, r, "/login", http.StatusSeeOther)
			return
		}
		helpOnce.Do(loadHelpPages)
		page := helpPages[string(side)]
		h.render(w, r, "help.html", pageData{
			Title:    "Help",
			Nav:      "help",
			User:     user,
			HelpHTML: page.HTML,
			HelpTOC:  page.TOC,
		})
	}
}
