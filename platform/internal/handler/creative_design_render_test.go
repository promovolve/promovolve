package handler

import (
	"github.com/hanishi/promovolve/platform/internal/i18n"
	"io"
	"strings"
	"testing"

	platform "github.com/hanishi/promovolve/platform"
)

// creative-design.html dies mid-stream on any referenced field the payload
// lacks — the browser gets a blank page, not an error. Both entry paths
// (fresh design POST and resume-draft) must render with the shared
// creativeDesignData, including the resume shape where the generation-time
// fields are empty. Regression test for the LPFontsJSON blank-page bug.
func TestCreativeDesignTemplateRenders(t *testing.T) {
	SetFS(platform.Templates, platform.Static)
	cases := map[string]creativeDesignData{
		"fresh design": {
			CampaignID:      "camp-1",
			LandingURL:      "https://example.com",
			CreativeName:    "Test Creative",
			BannerSize:      "300x250",
			PagesJSON:       `[{"headline":"h"}]`,
			LPTextSnapshot:  "snapshot text",
			CreativeID:      "",
			BannerScriptURL: "https://cdn.example/banner.js",
			BrandKitJSON:    `{"accent":"#fff"}`,
			TemplateID:      "tpl-1",
			LPFontsJSON:     `[{"family":"Shippori Mincho B1","weight":500,"hash":"abc"}]`,
		},
		"resumed draft (empty generation-time fields)": {
			CampaignID:       "camp-1",
			LandingURL:       "https://example.com",
			CreativeName:     "Draft",
			BannerSize:       "300x250",
			PagesJSON:        `[{"headline":"h"}]`,
			CreativeID:       "01KXTEST",
			BannerScriptURL:  "https://cdn.example/banner.js",
			BannerConfigJSON: `{"paperWeight":"heavy","logo":{"src":"https://cdn.example/logo.png","left":4,"top":4,"width":18,"height":12}}`,
		},
	}
	for name, data := range cases {
		if err := getPageStandalone(i18n.LangEN, "advertiser/creative-design.html").
			ExecuteTemplate(io.Discard, "creative-design.html", data); err != nil {
			t.Errorf("%s: creative-design.html failed to render: %v", name, err)
		}
	}
}

// A resumed draft's saved banner config must reach window.__DESIGNER__ —
// when it doesn't, the designer boots with defaults and the next save
// erases the stored blob (the vanishing brand-logo bug, fixed 2026-07-17).
func TestCreativeDesignCarriesBannerConfig(t *testing.T) {
	SetFS(platform.Templates, platform.Static)
	data := creativeDesignData{
		CampaignID:       "camp-1",
		LandingURL:       "https://example.com",
		CreativeName:     "Draft",
		BannerSize:       "300x250",
		PagesJSON:        `[]`,
		CreativeID:       "01KXTEST",
		BannerScriptURL:  "https://cdn.example/banner.js",
		BannerConfigJSON: `{"logo":{"src":"https://cdn.example/logo.png"},"paperWeight":"heavy"}`,
	}
	var sb strings.Builder
	if err := getPageStandalone(i18n.LangEN, "advertiser/creative-design.html").
		ExecuteTemplate(&sb, "creative-design.html", data); err != nil {
		t.Fatalf("render: %v", err)
	}
	out := sb.String()
	if !strings.Contains(out, "bannerConfigJson") {
		t.Fatal("__DESIGNER__ is missing the bannerConfigJson field")
	}
	if !strings.Contains(out, "logo.png") || !strings.Contains(out, "paperWeight") {
		t.Error("saved banner config did not reach the designer boot context")
	}
}
