package handler

import (
	"github.com/hanishi/promovolve/platform/internal/i18n"
	"io"
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
			CampaignID:      "camp-1",
			LandingURL:      "https://example.com",
			CreativeName:    "Draft",
			BannerSize:      "300x250",
			PagesJSON:       `[{"headline":"h"}]`,
			CreativeID:      "01KXTEST",
			BannerScriptURL: "https://cdn.example/banner.js",
		},
	}
	for name, data := range cases {
		if err := getPageStandalone(i18n.LangEN, "advertiser/creative-design.html").
			ExecuteTemplate(io.Discard, "creative-design.html", data); err != nil {
			t.Errorf("%s: creative-design.html failed to render: %v", name, err)
		}
	}
}
