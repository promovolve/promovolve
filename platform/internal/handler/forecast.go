package handler

import (
	"math"
	"time"

	"github.com/hanishi/promovolve/platform/internal/i18n"
)

// forecastCell is one hour of a day's delivery on the campaigns page:
// spend coloured on the JMA intensity scale, hours after now drawn as the
// "at this pace" projection.
type forecastCell struct {
	Hour      int
	Level     int // 0-7, the jma-* colour step
	Projected bool
	Title     string
}

// forecast is a campaign's (or the account's) day as a forecast strip plus
// the outlook in words. Alert is "", "advisory" or "warning" (JMA levels).
type forecast struct {
	Cells   []forecastCell
	Outlook string
	Alert   string
	NowPct  float64 // the now marker's position across the 24 hours, %
}

// intensity puts an hour's spend on the 0-7 scale relative to an even
// spread of the daily budget (budget/24 per hour), so every strip reads on
// the same scale whatever its budget or currency.
func intensity(spend, evenHour float64) int {
	if spend <= 0 {
		return 0
	}
	if evenHour <= 0 {
		return 3
	}
	r := spend / evenHour
	switch {
	case r < 0.25:
		return 1
	case r < 0.6:
		return 2
	case r < 1.2: // around the even pace
		return 3
	case r < 2:
		return 4
	case r < 3:
		return 5
	case r < 4:
		return 6
	}
	return 7
}

// buildForecast lays out today's 24 hours (hours[h] = spend delivered in
// local hour h) against the daily budget, and projects the rest of the day
// at the pace so far. now is in the account's zone; budget <= 0 means no
// daily cap. A paused campaign gets no projection.
// ponytail: linear pace over the elapsed day; ignores the traffic shape
// (evenings deliver more), so the outlook is a rough "at this pace".
func buildForecast(lang string, hours [24]float64, budget float64, now time.Time, active bool) forecast {
	dayStart := time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, now.Location())
	elapsed := math.Max(now.Sub(dayStart).Hours(), 0.25)
	nowHour := now.Hour()
	even := budget / 24

	var spent float64
	reachedHour := -1
	for h, s := range hours {
		spent += s
		if budget > 0 && reachedHour < 0 && spent >= budget {
			reachedHour = h
		}
	}
	pace := spent / elapsed

	f := forecast{Cells: make([]forecastCell, 24), NowPct: elapsed / 24 * 100}
	for h := range f.Cells {
		f.Cells[h] = forecastCell{Hour: h, Level: intensity(hours[h], even),
			Title: i18n.T(lang, "%02d:00 — %s", h, fmtMoney(hours[h]))}
	}

	switch {
	case !active:
		f.Outlook = i18n.T(lang, "Paused")
	case reachedHour >= 0:
		f.Alert = "warning"
		f.Outlook = i18n.T(lang, "Budget reached during the %02d:00 hour", reachedHour)
	case pace <= 0:
		f.Outlook = i18n.T(lang, "No delivery yet today")
	default:
		// Project the hours after now at the current pace, stopping where
		// the budget would run out.
		left := 24 - elapsed
		reach := math.Inf(1)
		if budget > 0 {
			reach = (budget - spent) / pace // hours from now
		}
		for h := nowHour + 1; h < 24; h++ {
			if float64(h)-elapsed >= reach {
				break
			}
			f.Cells[h] = forecastCell{Hour: h, Level: intensity(pace, even), Projected: true,
				Title: i18n.T(lang, "%02d:00 — about %s at this pace", h, fmtMoney(pace))}
		}
		if reach < left {
			f.Outlook = i18n.T(lang, "Spends its full budget around %s", now.Add(time.Duration(reach*float64(time.Hour))).Format("15:04"))
		} else {
			f.Outlook = i18n.T(lang, "About %s by midnight", fmtMoney(spent+pace*left))
		}
	}
	return f
}
