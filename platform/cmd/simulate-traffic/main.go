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

type pageSlots struct {
	PageURL string
	Slots   []batchImp
}

var (
	slotRe = regexp.MustCompile(`data-promovolve-slot="([^"]+)"[^>]*data-w="(\d+)"[^>]*data-h="(\d+)"`)
	hrefRe = regexp.MustCompile(`href="([^"]+)"`)
)

func main() {
	pub := flag.String("pub", "localhost-8888", "Publisher ID")
	api := flag.String("api", "http://localhost:8080/v1", "Core API base URL")
	interval := flag.Duration("interval", 3*time.Second, "Delay between requests per worker")
	ctr := flag.Float64("ctr", 0.10, "Click-through rate (0.0-1.0)")
	siteURL := flag.String("site", "http://localhost:8888", "Publisher site base URL")
	workers := flag.Int("workers", 1, "Number of parallel workers")
	noFilter := flag.Bool("no-filter", false,
		"Disable the ServeIndex pre-filter; hit every discovered page including those with no campaigns (matches old behavior).")
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
				body, _ := json.Marshal(req)

				atomic.AddInt64(&batches, 1)
				resp, err := client.Post(*api+"/serve/batch", "application/json", bytes.NewReader(body))
				if err != nil {
					atomic.AddInt64(&errors, 1)
					time.Sleep(*interval)
					continue
				}

				if resp.StatusCode == 204 || resp.StatusCode == 403 {
					resp.Body.Close()
					time.Sleep(*interval)
					continue
				}
				if resp.StatusCode != 200 {
					resp.Body.Close()
					atomic.AddInt64(&errors, 1)
					time.Sleep(*interval)
					continue
				}

				respBody, _ := io.ReadAll(resp.Body)
				resp.Body.Close()

				var batchRes batchServeRes
				if json.Unmarshal(respBody, &batchRes) != nil {
					atomic.AddInt64(&errors, 1)
					time.Sleep(*interval)
					continue
				}

				for _, slot := range batchRes.Seatbid {
					if slot.Winner == nil || slot.Winner.ImpURL == "" {
						continue
					}
					ad := slot.Winner

					if r, err := client.Get(ad.ImpURL); err == nil {
						r.Body.Close()
					}
					atomic.AddInt64(&imps, 1)

					cid := ad.CreativeID
					if len(cid) > 12 {
						cid = cid[:12]
					}

					if rng.Float64() < *ctr && ad.ClickURL != "" {
						if r, err := client.Get(ad.ClickURL); err == nil {
							r.Body.Close()
						}
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
		req := batchServeReq{Pub: pub, URL: p.PageURL, Imp: p.Slots}
		body, _ := json.Marshal(req)
		resp, err := client.Post(api+"/serve/batch", "application/json", bytes.NewReader(body))
		if err != nil {
			continue
		}
		if resp.StatusCode != 200 {
			resp.Body.Close()
			continue
		}
		respBody, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		var batchRes batchServeRes
		if json.Unmarshal(respBody, &batchRes) != nil {
			continue
		}
		hasWinner := false
		for _, slot := range batchRes.Seatbid {
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
