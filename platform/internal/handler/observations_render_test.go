package handler

// Render smoke test for the observations page's traffic-shape section:
// executes the template against the real embedded layout with a learned
// and a still-learning shape so template/data mismatches fail in CI.

import (
	"github.com/hanishi/promovolve/platform/internal/i18n"
	"strings"
	"testing"

	platform "github.com/hanishi/promovolve/platform"
	"github.com/hanishi/promovolve/platform/internal/model"
)

func shapeBars(peak int, now int) []shapeBar {
	out := make([]shapeBar, 24)
	for i := range out {
		pct := 20
		if i == peak {
			pct = 100
		}
		out[i] = shapeBar{Hour: i, HeightPct: pct, Title: "tooltip", IsNow: i == now}
	}
	return out
}

func TestObservationsTrafficShapeRenders(t *testing.T) {
	SetFS(platform.Templates, platform.Static)
	pub := &model.User{Email: "pub@test", Role: model.RolePublisher}

	for _, tc := range []struct {
		name  string
		shape *trafficShapeView
		want  string
	}{
		{"learned", &trafficShapeView{Weekday: shapeBars(14, 14), Weekend: shapeBars(20, -1)}, "Traffic shape (learned)"},
		{"learning", &trafficShapeView{Weekday: shapeBars(0, 0), Weekend: shapeBars(0, -1), Learning: true}, "Still learning"},
		{"absent", nil, ""},
	} {
		data := pageData{
			Title: "Floor Decisions", Nav: "sites", User: pub,
			FloorObservations: &floorObservationsData{SiteID: "site-1", TrafficShape: tc.shape},
		}
		var sb strings.Builder
		for _, tlang := range []string{i18n.LangEN, i18n.LangJA} {
			if err := getPage(tlang, "publisher/site-observations.html").ExecuteTemplate(&sb, "layout", data); err != nil {
				t.Fatalf("%s: render failed: %v", tc.name, err)
			}
		}
		out := sb.String()
		if tc.want != "" && !strings.Contains(out, tc.want) {
			t.Errorf("%s: rendered page missing %q", tc.name, tc.want)
		}
		if tc.shape == nil && strings.Contains(out, "Traffic shape (learned)") {
			t.Errorf("absent: shape section rendered without data")
		}
	}
}
