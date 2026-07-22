package main

import (
	"bytes"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"math/rand"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"regexp"
	"strconv"
	"strings"
	"sync/atomic"
	"syscall"
	"time"
)

type batchImp struct {
	ID string `json:"id"`
	W  int    `json:"w"`
	H  int    `json:"h"`
}

type batchServeReq struct {
	Pub    string     `json:"pub"`
	URL    string     `json:"url"`
	Imp    []batchImp `json:"imp"`
	UID    *string    `json:"uid,omitempty"`
	Domain *string    `json:"domain,omitempty"`
}

type serveRes struct {
	AssetURL   string `json:"assetUrl"`
	ClickURL   string `json:"clickUrl"`
	ImpURL     string `json:"impUrl"`
	CTAURL     string `json:"ctaUrl"`
	CreativeID string `json:"creativeId"`
	LandingURL string `json:"landingUrl"`
}

type batchImpResult struct {
	ID     string    `json:"id"`
	Winner *serveRes `json:"winner"`
}

type batchServeRes struct {
	Seatbid []batchImpResult `json:"seatbid"`
}

type mountBeaconReq struct {
	V       int    `json:"v"`
	Pub     string `json:"pub"`
	Stage   string `json:"stage"`
	OK      bool   `json:"ok"`
	Slots   int    `json:"slots"`
	Served  int    `json:"served"`
	Mounted int    `json:"mounted"`
}

// GET /v1/internal/fraud-suspects/{siteId}
type fraudSuspectCount struct {
	Reason string `json:"reason"`
	Count  int64  `json:"count"`
}

type fraudSuspectSummary struct {
	SiteID   string              `json:"siteId"`
	Total    int64               `json:"total"`
	ByReason []fraudSuspectCount `json:"byReason"`
}

// GET /v1/internal/fraud-flags
type fraudFlagView struct {
	ID       int64   `json:"id"`
	SiteID   string  `json:"siteId"`
	Signal   string  `json:"signal"`
	Severity float64 `json:"severity"`
	Evidence string  `json:"evidence"`
}

type fraudFlagList struct {
	Flags []fraudFlagView `json:"flags"`
}

type pageSlots struct {
	PageURL string
	Slots   []batchImp
}

var (
	slotRe = regexp.MustCompile(`data-promovolve-slot="([^"]+)"[^>]*data-w="(\d+)"[^>]*data-h="(\d+)"`)
	hrefRe = regexp.MustCompile(`href="([^"]+)"`)
)

// A real browser UA. Without one, Go's default "Go-http-client/1.1"
// matches the Layer-0 BotUaMatcher and every simulated impression is
// marked suspect(bot) — silently excluded from money and learning.
const browserUA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

// A UA the BotUaMatcher catches — the cheat scenario's lazy-bot phase.
const botUA = "curl/8.7.1"

func main() {
	pub := flag.String("pub", "localhost-8888", "Publisher ID")
	api := flag.String("api", "http://localhost:8080/v1", "Core API base URL")
	interval := flag.Duration("interval", 3*time.Second, "Delay between requests per worker")
	ctr := flag.Float64("ctr", 0.10, "Click-through rate (0.0-1.0)")
	siteURL := flag.String("site", "http://localhost:8888", "Publisher site base URL")
	workers := flag.Int("workers", 1, "Number of parallel workers")
	noFilter := flag.Bool("no-filter", false,
		"Disable the ServeIndex pre-filter; hit every discovered page including those with no campaigns (matches old behavior).")
	ua := flag.String("ua", browserUA,
		"User-Agent for beacons. Default is a real browser so Layer-0 hygiene doesn't mark honest sim traffic as bot.")
	clickDelay := flag.Duration("click-delay", 250*time.Millisecond,
		"Imp→click delay (must stay above the Layer-1 100ms timing floor)")
	beacon := flag.Bool("beacon", true,
		"Send a mount beacon per simulated pageview (the ad tag's pageview heartbeat — the L2 detector's denominator)")
	mode := flag.String("mode", "normal",
		"normal = continuous traffic; cheat = bounded cheating-publisher regression (baseline, then bot/burst/chain fraud, then asserts Layer 0-2 caught it)")
	internalKey := flag.String("internal-key", os.Getenv("INTERNAL_API_KEY"),
		"X-Internal-Key for /v1/internal probes (cheat mode)")
	baseline := flag.Int("baseline", 60, "cheat: honest baseline pageviews before the fraud phases")
	expectL1 := flag.Bool("expect-l1", true,
		"cheat: assert chain/timing marks (requires FRAUD_ENGAGEMENT_GUARD_ENABLED=true on the API)")
	expectFlag := flag.Bool("expect-flag", true,
		"cheat: assert the L2 suspect_share flag appears (requires FRAUD_DETECTOR_ENABLED=true; set FRAUD_DETECTOR_INTERVAL_SECONDS=30 for a fast sweep)")
	flagTimeout := flag.Duration("flag-timeout", 5*time.Minute,
		"cheat: how long to wait for the detector's suspect_share flag")
	flag.Parse()

	client := &http.Client{Timeout: 5 * time.Second}

	fmt.Println("Discovering pages and ad slots...")
	pages := discoverPages(client, *siteURL)
	if len(pages) == 0 {
		fmt.Println("No pages with ad slots found. Is the publisher site running?")
		os.Exit(1)
	}

	// Filter to pages the cluster has ServeIndex entries for. Without
	// this, most simulate-traffic requests hit URLs with no campaigns
	// targeting them — pure noise that depresses the apparent fill rate
	// and starves measurement-based optimizers (sweep) of useful signal.
	if !*noFilter {
		filtered := filterServingPages(client, *api, *pub, pages)
		if len(filtered) == 0 {
			fmt.Println("WARNING: filter eliminated every page (no URL has ServeIndex candidates).")
			fmt.Println("Either no campaigns target your site's content, or ServeIndex hasn't")
			fmt.Println("been populated yet. Falling back to unfiltered to avoid an empty test.")
			fmt.Println("Re-run with -no-filter to silence this fallback.")
		} else {
			fmt.Printf("ServeIndex pre-filter: %d/%d pages have candidates; targeting only those.\n",
				len(filtered), len(pages))
			pages = filtered
		}
	}

	if *mode == "cheat" {
		os.Exit(runCheat(client, cheatOpts{
			api:         *api,
			pub:         *pub,
			pages:       pages,
			ua:          *ua,
			ctr:         *ctr,
			clickDelay:  *clickDelay,
			internalKey: *internalKey,
			baseline:    *baseline,
			expectL1:    *expectL1,
			expectFlag:  *expectFlag,
			flagTimeout: *flagTimeout,
		}))
	}

	totalSlots := 0
	for _, p := range pages {
		totalSlots += len(p.Slots)
	}

	fmt.Printf("\n=== Ad Traffic Simulator ===\n")
	fmt.Printf("  Publisher: %s\n", *pub)
	fmt.Printf("  Endpoint:  POST %s/serve/batch\n", *api)
	fmt.Printf("  Pages:     %d (%d slots total)%s\n", len(pages), totalSlots,
		map[bool]string{true: " [filtered]", false: " [unfiltered]"}[!*noFilter])
	fmt.Printf("  Workers:   %d\n", *workers)
	fmt.Printf("  Interval:  %s per worker\n", *interval)
	fmt.Printf("  CTR:       %.0f%%\n", *ctr*100)
	fmt.Printf("  Ctrl+C to stop\n\n")

	var imps, clicks, batches, errors int64

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)
	done := make(chan struct{})

	for w := 0; w < *workers; w++ {
		go func(workerID int) {
			rng := rand.New(rand.NewSource(time.Now().UnixNano() + int64(workerID)))
			for {
				select {
				case <-done:
					return
				default:
				}

				page := pages[rng.Intn(len(pages))]
				uid := fmt.Sprintf("sim-%d", rng.Intn(100))

				req := batchServeReq{
					Pub: *pub,
					URL: page.PageURL,
					Imp: page.Slots,
					UID: &uid,
				}

				atomic.AddInt64(&batches, 1)
				batchRes, err := serveBatch(client, *api, *ua, req)
				if err != nil {
					atomic.AddInt64(&errors, 1)
					time.Sleep(*interval)
					continue
				}
				if batchRes == nil { // 204 / 403: nothing to serve
					time.Sleep(*interval)
					continue
				}

				wins := 0
				for _, slot := range batchRes.Seatbid {
					if slot.Winner != nil && slot.Winner.ImpURL != "" {
						wins++
					}
				}
				if *beacon {
					sendMountBeacon(client, *api, *pub, *ua, len(page.Slots), wins)
				}

				for _, slot := range batchRes.Seatbid {
					if slot.Winner == nil || slot.Winner.ImpURL == "" {
						continue
					}
					ad := slot.Winner

					get(client, ad.ImpURL, *ua)
					atomic.AddInt64(&imps, 1)

					cid := ad.CreativeID
					if len(cid) > 12 {
						cid = cid[:12]
					}

					if rng.Float64() < *ctr && ad.ClickURL != "" {
						// A human doesn't click the instant the ad paints —
						// and the Layer-1 guard marks sub-100ms clicks.
						time.Sleep(*clickDelay + time.Duration(rng.Intn(200))*time.Millisecond)
						get(client, ad.ClickURL, *ua)
						c := atomic.AddInt64(&clicks, 1)
						i := atomic.LoadInt64(&imps)
						fmt.Printf("  [%s] CLICK  %s  slot=%-20s uid=%-6s  (%d imps, %d clicks)\n",
							time.Now().Format("15:04:05"), cid, slot.ID, uid, i, c)
					} else {
						i := atomic.LoadInt64(&imps)
						fmt.Printf("  [%s] IMP    %s  slot=%-20s uid=%-6s  (%d imps)\n",
							time.Now().Format("15:04:05"), cid, slot.ID, uid, i)
					}
				}

				time.Sleep(*interval)
			}
		}(w)
	}

	<-stop
	close(done)

	fmt.Printf("\n=== Summary ===\n")
	fmt.Printf("  Batch requests: %d\n", batches)
	fmt.Printf("  Impressions:    %d\n", imps)
	fmt.Printf("  Clicks:         %d\n", clicks)
	fmt.Printf("  Errors:         %d\n", errors)
	if imps > 0 {
		fmt.Printf("  Actual CTR:     %.1f%%\n", float64(clicks)/float64(imps)*100)
	}
}

// ==================== Cheating-publisher regression ====================
//
// The scripted "cheating publisher" scenario from
// docs/design/FRAUD_PREVENTION.md Phase 2: drives fraud-shaped traffic
// through the real serve→beacon path and asserts the fraud layers catch
// it. Phases run in an order that keeps the per-IP rate bucket honest —
// chain violations (which must NOT be rate-marked, hygiene wins over
// chain) run before the burst drains the bucket; bot-UA traffic
// short-circuits before the rate gate so it can run after.

type cheatOpts struct {
	api         string
	pub         string
	pages       []pageSlots
	ua          string
	ctr         float64
	clickDelay  time.Duration
	internalKey string
	baseline    int
	expectL1    bool
	expectFlag  bool
	flagTimeout time.Duration
}

func runCheat(client *http.Client, o cheatOpts) int {
	rng := rand.New(rand.NewSource(time.Now().UnixNano()))
	fmt.Printf("\n=== Cheating-Publisher Regression (site %s) ===\n\n", o.pub)

	// Phase 1: honest baseline — real-browser UA, human click timing,
	// one mount beacon per pageview.
	fmt.Printf("[1/6] Baseline: %d honest pageviews...\n", o.baseline)
	baseImps := 0
	for i := 0; i < o.baseline; i++ {
		page := o.pages[rng.Intn(len(o.pages))]
		res, err := serveBatch(client, o.api, o.ua, batchServeReq{Pub: o.pub, URL: page.PageURL, Imp: page.Slots})
		if err != nil || res == nil {
			time.Sleep(200 * time.Millisecond)
			continue
		}
		wins := winners(res)
		sendMountBeacon(client, o.api, o.pub, o.ua, len(page.Slots), len(wins))
		for _, ad := range wins {
			get(client, ad.ImpURL, o.ua)
			baseImps++
			if rng.Float64() < o.ctr && ad.ClickURL != "" {
				time.Sleep(o.clickDelay + time.Duration(rng.Intn(200))*time.Millisecond)
				get(client, ad.ClickURL, o.ua)
			}
		}
		time.Sleep(200 * time.Millisecond)
	}
	fmt.Printf("      %d impressions, clean UA, paced clicks\n", baseImps)

	// Phase 2: chain violations (Layer 1) — BEFORE the burst so these
	// clicks aren't rate-marked instead (hygiene wins over chain).
	// 2a: click with no impression → suspect(chain).
	// 2b: click <100ms after impression → suspect(timing).
	fmt.Println("[2/6] Chain violations: 20 clicks-without-impression + 20 instant clicks...")
	chainFired, timingFired := 0, 0
	for i := 0; i < 20; i++ {
		if ad := serveOne(client, o.api, o.ua, o.pub, o.pages, rng); ad != nil && ad.ClickURL != "" {
			get(client, ad.ClickURL, o.ua) // no impression first
			chainFired++
		}
		time.Sleep(150 * time.Millisecond)
	}
	for i := 0; i < 20; i++ {
		if ad := serveOne(client, o.api, o.ua, o.pub, o.pages, rng); ad != nil && ad.ClickURL != "" {
			get(client, ad.ImpURL, o.ua)
			get(client, ad.ClickURL, o.ua) // back-to-back: sub-100ms server delta
			timingFired++
		}
		time.Sleep(150 * time.Millisecond)
	}
	fmt.Printf("      fired %d chain + %d timing violations\n", chainFired, timingFired)

	// Phase 3: lazy bot — a UA that admits it (Layer 0 bot marking;
	// short-circuits before the rate gate, so it never drains the bucket).
	fmt.Println("[3/6] Bot traffic: 40 pageviews with a curl UA...")
	botImps := 0
	for i := 0; i < 40; i++ {
		page := o.pages[rng.Intn(len(o.pages))]
		res, err := serveBatch(client, o.api, botUA, batchServeReq{Pub: o.pub, URL: page.PageURL, Imp: page.Slots})
		if err != nil || res == nil {
			continue
		}
		for _, ad := range winners(res) {
			get(client, ad.ImpURL, botUA)
			botImps++
		}
	}
	fmt.Printf("      %d bot impressions\n", botImps)

	// Phase 4: burst — clean UA but far past the per-IP rate cap
	// (default 20/s, burst 60). No sleeps: the bucket drains, and every
	// beacon after that is marked suspect(rate).
	fmt.Println("[4/6] Rate burst: hammering impressions with no pacing...")
	burstImps := 0
	for i := 0; i < 120 && burstImps < 300; i++ {
		page := o.pages[rng.Intn(len(o.pages))]
		res, err := serveBatch(client, o.api, o.ua, batchServeReq{Pub: o.pub, URL: page.PageURL, Imp: page.Slots})
		if err != nil || res == nil {
			continue
		}
		for _, ad := range winners(res) {
			get(client, ad.ImpURL, o.ua)
			burstImps++
		}
	}
	fmt.Printf("      %d burst impressions\n", burstImps)

	// Phase 5: top up with bot impressions until the day's totals can
	// trip the L2 suspect_share signal (>=500 events, >=30%% marked) —
	// prior honest traffic on this site today dilutes the share, so this
	// adapts instead of assuming a fixed volume.
	fmt.Println("[5/6] Topping up until suspect share can trip Layer 2...")
	var sum *fraudSuspectSummary
	for round := 0; round < 15; round++ {
		s, err := probeSuspects(client, o.api, o.pub, o.internalKey)
		if err != nil {
			fmt.Printf("      probe failed: %v\n", err)
			return 1
		}
		sum = s
		if s.Total >= 600 && suspectShare(s) >= 0.35 {
			break
		}
		for i := 0; i < 25; i++ {
			page := o.pages[rng.Intn(len(o.pages))]
			res, err := serveBatch(client, o.api, botUA, batchServeReq{Pub: o.pub, URL: page.PageURL, Imp: page.Slots})
			if err != nil || res == nil {
				continue
			}
			for _, ad := range winners(res) {
				get(client, ad.ImpURL, botUA)
			}
		}
	}

	// Phase 6: verify.
	fmt.Println("[6/6] Verifying marks and detector flag...")
	fmt.Printf("      today's events for %s: total=%d suspect=%.0f%%\n", o.pub, sum.Total, suspectShare(sum)*100)
	counts := map[string]int64{}
	for _, c := range sum.ByReason {
		counts[c.Reason] = c.Count
		fmt.Printf("        %-12s %6d\n", c.Reason, c.Count)
	}

	pass := true
	check := func(ok bool, label string) {
		status := "PASS"
		if !ok {
			status = "FAIL"
			pass = false
		}
		fmt.Printf("      [%s] %s\n", status, label)
	}
	// Suspect-reason values are the long names from Suspect.scala
	// (bot_ua, rate_cap, chain, timing) — the same strings stored in
	// tracking_events.suspect_reason.
	check(counts["bot_ua"] > 0, "Layer 0: bot-UA impressions marked suspect(bot_ua)")
	// Rate cap is OBSERVATIONAL, not a hard gate: RequestRateGate is a
	// per-pod, per-IP token bucket. Behind a multi-pod load balancer a
	// single WAN client's burst splits across pods and the 20/s refill
	// outpaces it, so rate_cap marks are non-deterministic from here —
	// the authoritative rate-cap test is RequestRateGateSpec. We report
	// whether they showed up without failing the run on their absence.
	if counts["rate_cap"] > 0 {
		fmt.Printf("      [PASS] Layer 0: burst impressions marked suspect(rate_cap) (%d)\n", counts["rate_cap"])
	} else {
		fmt.Println("      [OBS ] Layer 0: no rate_cap marks — expected from a single client vs a multi-pod per-pod bucket; see RequestRateGateSpec")
	}
	if o.expectL1 {
		check(counts["chain"] > 0, "Layer 1: click-without-impression marked suspect(chain)")
		check(counts["timing"] > 0, "Layer 1: sub-100ms click marked suspect(timing)")
	} else {
		fmt.Println("      [SKIP] Layer 1 checks (-expect-l1=false)")
	}
	check(sum.Total >= 600 && suspectShare(sum) >= 0.35, "volume/share big enough for the L2 suspect_share signal")

	if o.expectFlag && pass {
		fmt.Printf("      waiting up to %s for the detector's suspect_share flag...\n", o.flagTimeout)
		deadline := time.Now().Add(o.flagTimeout)
		flagged := false
		for time.Now().Before(deadline) {
			if f := findFlag(client, o.api, o.pub, o.internalKey); f != nil {
				fmt.Printf("      flag #%d severity=%.2f: %s\n", f.ID, f.Severity, f.Evidence)
				flagged = true
				break
			}
			time.Sleep(10 * time.Second)
		}
		check(flagged, "Layer 2: suspect_share fraud flag written")
		if !flagged {
			fmt.Println("      hint: needs FRAUD_DETECTOR_ENABLED=true and a short FRAUD_DETECTOR_INTERVAL_SECONDS (e.g. 30) on the API")
		}
	} else if o.expectFlag {
		fmt.Println("      [SKIP] Layer 2 flag wait (earlier checks failed)")
	} else {
		fmt.Println("      [SKIP] Layer 2 flag wait (-expect-flag=false)")
	}

	if pass {
		fmt.Println("\n=== CHEAT SCENARIO: PASS — the fraud layers caught the cheating publisher ===")
		return 0
	}
	fmt.Println("\n=== CHEAT SCENARIO: FAIL ===")
	return 1
}

func winners(res *batchServeRes) []*serveRes {
	var out []*serveRes
	for _, slot := range res.Seatbid {
		if slot.Winner != nil && slot.Winner.ImpURL != "" {
			out = append(out, slot.Winner)
		}
	}
	return out
}

func serveOne(client *http.Client, api, ua, pub string, pages []pageSlots, rng *rand.Rand) *serveRes {
	page := pages[rng.Intn(len(pages))]
	res, err := serveBatch(client, api, ua, batchServeReq{Pub: pub, URL: page.PageURL, Imp: page.Slots})
	if err != nil || res == nil {
		return nil
	}
	if w := winners(res); len(w) > 0 {
		return w[rng.Intn(len(w))]
	}
	return nil
}

func suspectShare(s *fraudSuspectSummary) float64 {
	if s.Total == 0 {
		return 0
	}
	var suspect int64
	for _, c := range s.ByReason {
		if c.Reason != "clean" {
			suspect += c.Count
		}
	}
	return float64(suspect) / float64(s.Total)
}

func probeSuspects(client *http.Client, api, pub, key string) (*fraudSuspectSummary, error) {
	req, _ := http.NewRequest("GET", api+"/internal/fraud-suspects/"+url.PathEscape(pub), nil)
	if key != "" {
		req.Header.Set("X-Internal-Key", key)
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		body, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("fraud-suspects status %d: %s", resp.StatusCode, strings.TrimSpace(string(body)))
	}
	var sum fraudSuspectSummary
	if err := json.NewDecoder(resp.Body).Decode(&sum); err != nil {
		return nil, err
	}
	return &sum, nil
}

func findFlag(client *http.Client, api, pub, key string) *fraudFlagView {
	req, _ := http.NewRequest("GET", api+"/internal/fraud-flags", nil)
	if key != "" {
		req.Header.Set("X-Internal-Key", key)
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return nil
	}
	var list fraudFlagList
	if json.NewDecoder(resp.Body).Decode(&list) != nil {
		return nil
	}
	for i := range list.Flags {
		if list.Flags[i].SiteID == pub && list.Flags[i].Signal == "suspect_share" {
			return &list.Flags[i]
		}
	}
	return nil
}

// ==================== HTTP helpers ====================

func get(client *http.Client, rawURL, ua string) {
	req, err := http.NewRequest("GET", rawURL, nil)
	if err != nil {
		return
	}
	req.Header.Set("User-Agent", ua)
	if r, err := client.Do(req); err == nil {
		r.Body.Close()
	}
}

// serveBatch POSTs /serve/batch. Returns (nil, nil) on 204/403 — a
// valid "nothing to serve" — and an error on transport/other statuses.
func serveBatch(client *http.Client, api, ua string, sreq batchServeReq) (*batchServeRes, error) {
	body, _ := json.Marshal(sreq)
	req, err := http.NewRequest("POST", api+"/serve/batch", bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("User-Agent", ua)
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode == 204 || resp.StatusCode == 403 {
		return nil, nil
	}
	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("serve/batch status %d", resp.StatusCode)
	}
	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}
	var batchRes batchServeRes
	if err := json.Unmarshal(respBody, &batchRes); err != nil {
		return nil, err
	}
	return &batchRes, nil
}

func sendMountBeacon(client *http.Client, api, pub, ua string, slots, served int) {
	body, _ := json.Marshal(mountBeaconReq{
		V: 1, Pub: pub, Stage: "render", OK: served > 0,
		Slots: slots, Served: served, Mounted: served,
	})
	req, err := http.NewRequest("POST", api+"/beacon/mount", bytes.NewReader(body))
	if err != nil {
		return
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("User-Agent", ua)
	if r, err := client.Do(req); err == nil {
		r.Body.Close()
	}
}

func discoverPages(client *http.Client, siteURL string) []pageSlots {
	visited := make(map[string]bool)
	var queue []string

	root, err := fetchPage(client, siteURL)
	if err != nil {
		return nil
	}
	visited[siteURL] = true
	queue = append(queue, siteURL)
	for _, href := range hrefRe.FindAllStringSubmatch(root, -1) {
		abs := resolveLocalHref(siteURL, href[1])
		if abs == "" || visited[abs] {
			continue
		}
		visited[abs] = true
		queue = append(queue, abs)
	}

	var pages []pageSlots
	for _, pageURL := range queue {
		var html string
		if pageURL == siteURL {
			html = root
		} else {
			h, err := fetchPage(client, pageURL)
			if err != nil {
				continue
			}
			html = h
		}
		var slots []batchImp
		for _, m := range slotRe.FindAllStringSubmatch(html, -1) {
			w, _ := strconv.Atoi(m[2])
			h, _ := strconv.Atoi(m[3])
			if w == 0 || h == 0 {
				continue
			}
			slots = append(slots, batchImp{ID: m[1], W: w, H: h})
		}
		if len(slots) > 0 {
			pages = append(pages, pageSlots{PageURL: pageURL, Slots: slots})
		}
	}
	return pages
}

func fetchPage(client *http.Client, pageURL string) (string, error) {
	resp, err := client.Get(pageURL)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return "", fmt.Errorf("status %d", resp.StatusCode)
	}
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return "", err
	}
	return string(body), nil
}

func resolveLocalHref(base, href string) string {
	if href == "" || strings.HasPrefix(href, "#") || strings.HasPrefix(href, "mailto:") || strings.HasPrefix(href, "javascript:") {
		return ""
	}
	baseU, err := url.Parse(base)
	if err != nil {
		return ""
	}
	hrefU, err := url.Parse(href)
	if err != nil {
		return ""
	}
	abs := baseU.ResolveReference(hrefU)
	if abs.Host != baseU.Host {
		return ""
	}
	abs.Fragment = ""
	abs.RawQuery = ""
	path := abs.Path
	if path != "/" && !strings.HasSuffix(path, ".html") && !strings.HasSuffix(path, "/") {
		return ""
	}
	return abs.String()
}

// filterServingPages probes each page once via /serve/batch (the same
// endpoint workers use) and keeps only the pages where at least one slot
// returned a winner. This is end-to-end: the page is kept iff it would
// actually have a winning bid. Cuts wasted traffic on URLs no campaign
// targets — major efficiency win for measurement-based optimizers (sweep)
// which need impression density per candidate window.
//
// One detail: we *don't* fire the impression URLs from the probe — these
// aren't real impressions, just discovery. Otherwise the probe itself
// would inflate impression counts on every simulator startup.
func filterServingPages(client *http.Client, api, pub string, pages []pageSlots) []pageSlots {
	out := make([]pageSlots, 0, len(pages))
	for _, p := range pages {
		res, err := serveBatch(client, api, browserUA, batchServeReq{Pub: pub, URL: p.PageURL, Imp: p.Slots})
		if err != nil || res == nil {
			continue
		}
		hasWinner := false
		for _, slot := range res.Seatbid {
			if slot.Winner != nil && slot.Winner.CreativeID != "" {
				hasWinner = true
				break
			}
		}
		if hasWinner {
			out = append(out, p)
		}
	}
	return out
}
