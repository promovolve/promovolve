package billing

import "context"

// conversions.go — advertiser-self-reported conversions backing the CPA/ROAS
// columns on the advertiser report page (Tier 0). These are aggregate numbers
// the advertiser attributes to a campaign; Promovolve never tracks users
// across sites, so this is display-only and campaign-level by design — never
// billed on, never reconciled.

// ConvAgg is a per-campaign roll-up of self-reported conversions over a range.
type ConvAgg struct {
	Count       int64
	ValueMicros int64
}

// ByCampaign returns, per campaign id, the summed conversion count and value
// the advertiser reported for [from, to] (inclusive, YYYY-MM-DD).
func (s *Service) ByCampaign(ctx context.Context, advertiserID, from, to string) (map[string]ConvAgg, error) {
	rows, err := s.pool.Query(ctx,
		`SELECT campaign_id, COALESCE(SUM(conversions),0), COALESCE(SUM(value_micros),0)
		 FROM campaign_conversions
		 WHERE advertiser_id=$1 AND conv_date>=$2::date AND conv_date<=$3::date
		 GROUP BY campaign_id`,
		advertiserID, from, to)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make(map[string]ConvAgg)
	for rows.Next() {
		var id string
		var a ConvAgg
		if err := rows.Scan(&id, &a.Count, &a.ValueMicros); err != nil {
			return nil, err
		}
		out[id] = a
	}
	return out, rows.Err()
}

// UpsertConversions records (or restates) the conversions an advertiser
// attributes to one campaign on one day. Re-reporting the same day overwrites,
// so numbers can be corrected as the advertiser's own attribution settles.
func (s *Service) UpsertConversions(ctx context.Context, advertiserID, campaignID, date string, count, valueMicros int64, note string) error {
	_, err := s.pool.Exec(ctx,
		`INSERT INTO campaign_conversions
		   (advertiser_id, campaign_id, conv_date, conversions, value_micros, source, note)
		 VALUES ($1, $2, $3::date, $4, $5, 'manual', $6)
		 ON CONFLICT (advertiser_id, campaign_id, conv_date) DO UPDATE
		   SET conversions  = EXCLUDED.conversions,
		       value_micros = EXCLUDED.value_micros,
		       note         = EXCLUDED.note,
		       source       = 'manual',
		       updated_at   = NOW()`,
		advertiserID, campaignID, date, count, valueMicros, note)
	return err
}
