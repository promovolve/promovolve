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

// walletDays is how many settled days the wallet strip shows behind today,
// and how many it projects ahead.
const walletDays = 14

// walletForecast reads the balance like a forecast: the last walletDays
// settled days, today (delivered, not yet charged), then the days ahead at
// the recent average until the balance runs out. Each day's colour is its
// charge against that average. charged maps YYYY-MM-DD to the settled
// amount; today is in the account's zone. Returns the strip and the
// projected days of runway (-1 when there is no recent spend).
// ponytail: average of the last walletDays calendar days; a campaign that
// started yesterday reads as slow spend until the window fills.
func walletForecast(lang string, charged map[string]float64, balance, pending float64, today time.Time) (forecast, int) {
	var sum float64
	f := forecast{}
	for i := walletDays; i >= 1; i-- {
		sum += charged[today.AddDate(0, 0, -i).Format("2006-01-02")]
	}
	avg := sum / walletDays
	for i := walletDays; i >= 1; i-- {
		d := today.AddDate(0, 0, -i)
		v := charged[d.Format("2006-01-02")]
		f.Cells = append(f.Cells, forecastCell{Level: intensity(v, avg),
			Title: i18n.T(lang, "%s — %s charged", d.Format("01-02"), fmtMoney(v))})
	}
	f.Cells = append(f.Cells, forecastCell{Level: intensity(pending, avg),
		Title: i18n.T(lang, "Today — %s so far, charged after midnight", fmtMoney(pending))})
	f.NowPct = (float64(walletDays) + today.Sub(time.Date(today.Year(), today.Month(), today.Day(), 0, 0, 0, 0, today.Location())).Hours()/24) / float64(2*walletDays+1) * 100

	left := balance - pending
	runway := -1
	if avg > 0 {
		runway = int(math.Max(left, 0) / avg)
	}
	for i := 1; i <= walletDays; i++ {
		c := forecastCell{Title: today.AddDate(0, 0, i).Format("01-02")}
		if runway >= i {
			c.Level, c.Projected = intensity(avg, avg), true
			c.Title = i18n.T(lang, "%s — about %s at this pace", c.Title, fmtMoney(avg))
		}
		f.Cells = append(f.Cells, c)
	}

	switch {
	case left <= 0:
		f.Alert = "warning"
		f.Outlook = i18n.T(lang, "The balance is used up once today is charged")
	case runway < 0:
		f.Outlook = i18n.T(lang, "No charges in the last %d days", walletDays)
	case runway < 1:
		f.Alert = "warning"
		f.Outlook = i18n.T(lang, "Runs out tomorrow at this pace")
	default:
		if runway <= 7 {
			f.Alert = "advisory"
		}
		f.Outlook = i18n.T(lang, "Lasts about %d days at this pace, until around %s", runway, today.AddDate(0, 0, runway).Format("01-02"))
	}
	return f, runway
}
