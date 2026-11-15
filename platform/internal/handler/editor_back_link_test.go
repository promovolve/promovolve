package handler

import (
	"strings"
	"testing"

	"github.com/hanishi/promovolve/platform"
	"github.com/hanishi/promovolve/platform/internal/i18n"
	"github.com/hanishi/promovolve/platform/internal/model"
)

// The editor's back link must always lead somewhere: to the campaign's
// creatives once it has any, else back to Campaigns (an empty Creatives
// list would just send the advertiser to this editor again).
func TestCreativeEditorBackLink(t *testing.T) {
	SetFS(platform.Templates, platform.Static)
	adv := &model.User{Email: "adv@test", Role: model.RoleAdvertiser}
	for _, c := range []struct {
		noCreatives bool
		want        string
	}{
		{true, `href="/advertiser/campaigns"`},
		{false, `href="/advertiser/creatives?campaignId=c1"`},
	} {
		data := pageData{Title: "Creative Editor", Nav: "creatives", User: adv, CampaignID: "c1", NoCreatives: c.noCreatives}
		var sb strings.Builder
		if err := getPage(i18n.LangJA, "advertiser/creative-editor.html").ExecuteTemplate(&sb, "layout", data); err != nil {
			t.Fatalf("render failed: %v", err)
		}
		out := sb.String()
		i := strings.Index(out, `class="page-sub"`)
		if i < 0 {
			t.Fatal("no page-sub line")
		}
		sub := out[i : i+strings.Index(out[i:], "</p>")]
		if !strings.Contains(sub, c.want) {
			t.Errorf("noCreatives=%v: back link %s missing", c.noCreatives, c.want)
		}
	}
}
