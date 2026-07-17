package db

import (
	"context"
	"log/slog"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

func Connect(databaseURL string) (*pgxpool.Pool, error) {
	cfg, err := pgxpool.ParseConfig(databaseURL)
	if err != nil {
		return nil, err
	}
	cfg.MaxConns = 10
	cfg.MinConns = 2
	cfg.MaxConnLifetime = 30 * time.Minute

	// Retry the initial connect with backoff. On a fresh cluster the platform
	// can start before the DB Service DNS resolves or Postgres accepts
	// connections ("hostname resolving error" / connection refused); exiting
	// then just produces CrashLoopBackOff noise. Wait it out instead (~60s).
	const maxAttempts = 20
	const backoff = 3 * time.Second

	var lastErr error
	for attempt := 1; attempt <= maxAttempts; attempt++ {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		pool, err := pgxpool.NewWithConfig(ctx, cfg)
		if err == nil {
			if err = pool.Ping(ctx); err == nil {
				cancel()
				if attempt > 1 {
					slog.Info("database connected after retry", "attempt", attempt)
				}
				return pool, nil
			}
			pool.Close()
		}
		cancel()
		lastErr = err
		slog.Warn("database not ready, retrying",
			"attempt", attempt, "maxAttempts", maxAttempts, "error", err)
		time.Sleep(backoff)
	}
	return nil, lastErr
}

func Migrate(pool *pgxpool.Pool) error {
	ctx := context.Background()
	_, err := pool.Exec(ctx, `
		CREATE TABLE IF NOT EXISTS platform_users (
			id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
			email         TEXT UNIQUE NOT NULL,
			password_hash TEXT,
			role          TEXT NOT NULL CHECK (role IN ('advertiser', 'publisher', 'admin')),
			display_name  TEXT NOT NULL DEFAULT '',
			-- Links to Promovolve core entities
			advertiser_id TEXT,
			publisher_id  TEXT,
			-- Account-request lifecycle: pending until an admin approves
			status          TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('pending', 'active', 'rejected')),
			company_name    TEXT NOT NULL DEFAULT '',
			website_url     TEXT NOT NULL DEFAULT '',
			contact_name    TEXT NOT NULL DEFAULT '',
			request_message TEXT NOT NULL DEFAULT '',
			reviewed_by     UUID,
			reviewed_at     TIMESTAMPTZ,
			created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
			updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
		);

		-- Idempotent upgrades for databases created before the approval flow.
		ALTER TABLE platform_users ALTER COLUMN password_hash DROP NOT NULL;

		-- role is a GRANT ('admin' | 'user'), not an operating side: sides
		-- live on the org (orgs.advertiser_id/publisher_id) and the member's
		-- landing side on org_members.preferred_side. requested_side records
		-- the one side-shaped fact that genuinely belongs to the user row —
		-- what a pending account request asked for. Migration order matters:
		-- backfill requested_side from the legacy role values, then collapse
		-- them to 'user', then retighten the CHECK.
		ALTER TABLE platform_users ADD COLUMN IF NOT EXISTS requested_side TEXT
			CHECK (requested_side IN ('advertiser', 'publisher'));
		UPDATE platform_users SET requested_side = role
			WHERE role IN ('advertiser', 'publisher') AND requested_side IS NULL;
		ALTER TABLE platform_users DROP CONSTRAINT IF EXISTS platform_users_role_check;
		UPDATE platform_users SET role = 'user' WHERE role <> 'admin';
		ALTER TABLE platform_users ADD CONSTRAINT platform_users_role_check
			CHECK (role IN ('admin', 'user'));
		ALTER TABLE platform_users ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'active';
		ALTER TABLE platform_users ADD COLUMN IF NOT EXISTS company_name TEXT NOT NULL DEFAULT '';
		ALTER TABLE platform_users ADD COLUMN IF NOT EXISTS website_url TEXT NOT NULL DEFAULT '';
		ALTER TABLE platform_users ADD COLUMN IF NOT EXISTS contact_name TEXT NOT NULL DEFAULT '';
		ALTER TABLE platform_users ADD COLUMN IF NOT EXISTS request_message TEXT NOT NULL DEFAULT '';
		ALTER TABLE platform_users ADD COLUMN IF NOT EXISTS reviewed_by UUID;
		ALTER TABLE platform_users ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMPTZ;

		CREATE TABLE IF NOT EXISTS webauthn_credentials (
			id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
			user_id       UUID NOT NULL REFERENCES platform_users(id) ON DELETE CASCADE,
			credential_id BYTEA UNIQUE NOT NULL,
			-- Full webauthn.Credential (public key, sign count, flags...) as JSON;
			-- avoids schema churn when the library adds fields.
			credential    JSONB NOT NULL,
			name          TEXT NOT NULL DEFAULT '',
			created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
			last_used_at  TIMESTAMPTZ
		);
		CREATE INDEX IF NOT EXISTS idx_webauthn_credentials_user ON webauthn_credentials(user_id);

		-- Platform margin, append-only and effective-dated: the current margin is
		-- the latest row with effective_from <= now(). Basis points avoid float drift.
		CREATE TABLE IF NOT EXISTS platform_margin_history (
			id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
			margin_bps     INTEGER NOT NULL CHECK (margin_bps >= 0 AND margin_bps < 10000),
			effective_from TIMESTAMPTZ NOT NULL DEFAULT NOW(),
			created_by     UUID REFERENCES platform_users(id),
			created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
		);
		CREATE INDEX IF NOT EXISTS idx_margin_effective ON platform_margin_history(effective_from DESC);

		-- One-time passkey re-registration links, minted by an admin and delivered
		-- manually (no email infrastructure).
		CREATE TABLE IF NOT EXISTS recovery_tokens (
			id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
			user_id    UUID NOT NULL REFERENCES platform_users(id) ON DELETE CASCADE,
			token_hash TEXT UNIQUE NOT NULL,
			created_by UUID REFERENCES platform_users(id),
			created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
			expires_at TIMESTAMPTZ NOT NULL,
			used_at    TIMESTAMPTZ
		);

		-- created_by may name an org member (invite links), and removing a
		-- member deletes their user row — the attribution FK must not block
		-- that. Re-created idempotently for databases made before this rule.
		ALTER TABLE recovery_tokens DROP CONSTRAINT IF EXISTS recovery_tokens_created_by_fkey;
		ALTER TABLE recovery_tokens ADD CONSTRAINT recovery_tokens_created_by_fkey
			FOREIGN KEY (created_by) REFERENCES platform_users(id) ON DELETE SET NULL;

		-- The api_keys feature was removed 2026-07-08: keys could be minted
		-- but nothing ever authenticated with them, and the dormant auth path
		-- resolved identity from user-row entity columns — wrong under the
		-- org model. Dropped rather than left as a trap.
		DROP TABLE IF EXISTS api_keys;

		-- Billing & settlement (docs/design/BILLING.md). Amounts are BIGINT
		-- micro-dollars (1000000 = $1) so ledger arithmetic stays exact.

		-- One account per advertiser wallet / publisher payable, plus the three
		-- platform singletons ('cash', 'revenue', 'clearing' — settlement gross
		-- in transit between the advertiser's local billing day and the
		-- publisher's). balance_micros is materialized from ledger_entries
		-- inside the same transaction; the entries are authoritative (see
		-- billing.Reconcile).
		CREATE TABLE IF NOT EXISTS billing_accounts (
			id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
			owner_type     TEXT NOT NULL CHECK (owner_type IN ('advertiser', 'publisher', 'platform')),
			owner_id       TEXT NOT NULL,
			currency       TEXT NOT NULL DEFAULT 'USD',
			balance_micros BIGINT NOT NULL DEFAULT 0,
			status         TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'low_balance', 'suspended')),
			created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
			UNIQUE (owner_type, owner_id)
		);

		-- Append-only journal: every money movement is one transaction whose
		-- signed entries (positive = debit, negative = credit) sum to zero.
		-- Corrections are new 'adjustment' rows, never updates or deletes.
		CREATE TABLE IF NOT EXISTS ledger_transactions (
			id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
			kind            TEXT NOT NULL CHECK (kind IN ('topup', 'settlement', 'payout', 'adjustment', 'refund')),
			idempotency_key TEXT NOT NULL UNIQUE,
			memo            TEXT NOT NULL DEFAULT '',
			created_by      UUID REFERENCES platform_users(id),
			created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
		);

		CREATE TABLE IF NOT EXISTS ledger_entries (
			id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
			txn_id        UUID NOT NULL REFERENCES ledger_transactions(id),
			account_id    UUID NOT NULL REFERENCES billing_accounts(id),
			amount_micros BIGINT NOT NULL CHECK (amount_micros <> 0)
		);
		CREATE INDEX IF NOT EXISTS idx_ledger_entries_txn ON ledger_entries(txn_id);
		CREATE INDEX IF NOT EXISTS idx_ledger_entries_account ON ledger_entries(account_id);

		-- Local-day settlement (docs/design/BILLING.md): every entity settles
		-- on ITS OWN local days via instant-chained windows, so the old single
		-- 3-leg settlement transaction splits into two independent one-sided
		-- settlements meeting at the platform 'clearing' account. The UTC-day
		-- predecessors (daily_settlements, billing_settlement_days) were
		-- dropped 2026-07 with the ledger reset — historical demo data was
		-- declared disposable, so there is no dual-format migration.
		DROP TABLE IF EXISTS daily_settlements;
		DROP TABLE IF EXISTS billing_settlement_days;

		-- Per-entity settlement cursor: everything before settled_until is
		-- booked. Windows chain on instants ([settled_until, next local
		-- midnight)), so coverage is gapless and overlap-free by construction;
		-- a timezone change moves only future boundaries (one short/long
		-- window, booked exactly once) and DST needs no special-casing.
		CREATE TABLE IF NOT EXISTS settlement_cursors (
			owner_type    TEXT NOT NULL CHECK (owner_type IN ('advertiser', 'publisher')),
			owner_id      TEXT NOT NULL,
			settled_until TIMESTAMPTZ NOT NULL,
			timezone      TEXT NOT NULL DEFAULT '',
			updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
			PRIMARY KEY (owner_type, owner_id)
		);

		-- Settled-window journal: health panel + the retro re-settle list.
		-- Retro passes MUST replay these recorded windows (never recompute
		-- boundaries from the current zone — after a zone change that would
		-- book the same events twice under different idempotency keys). A row
		-- is written only after every cell of the window posted, so a crash
		-- mid-window retries and dedupes. Empty windows are recorded too.
		-- rows_skipped counts metering cells with no publisher mapping
		-- (alerted, unbilled on BOTH sides until the mapping exists).
		CREATE TABLE IF NOT EXISTS settlement_windows (
			id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
			owner_type   TEXT NOT NULL CHECK (owner_type IN ('advertiser', 'publisher')),
			owner_id     TEXT NOT NULL,
			window_from  TIMESTAMPTZ NOT NULL,
			window_to    TIMESTAMPTZ NOT NULL,
			local_date   DATE NOT NULL,
			timezone     TEXT NOT NULL DEFAULT '',
			rows_settled INTEGER NOT NULL,
			rows_skipped INTEGER NOT NULL DEFAULT 0,
			gross_micros BIGINT NOT NULL,
			settled_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
			UNIQUE (owner_type, owner_id, window_from)
		);
		CREATE INDEX IF NOT EXISTS idx_settlement_windows_recent
			ON settlement_windows(owner_type, owner_id, window_to DESC);

		-- Advertiser-side settlement cells: the wallet is debited gross for
		-- the advertiser's local-day window; the gross parks in 'clearing'
		-- until the publisher's own window books it. local_date is a LABEL
		-- (the window's local day in the timezone column) — uniqueness rides
		-- on window_from, because a zone change can give two windows the same
		-- label. publisher_id is carried for lineage/alerting only.
		CREATE TABLE IF NOT EXISTS advertiser_settlements (
			id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
			advertiser_id TEXT NOT NULL,
			campaign_id   TEXT NOT NULL,
			site_id       TEXT NOT NULL,
			publisher_id  TEXT NOT NULL DEFAULT '',
			window_from   TIMESTAMPTZ NOT NULL,
			window_to     TIMESTAMPTZ NOT NULL,
			local_date    DATE NOT NULL,
			timezone      TEXT NOT NULL DEFAULT '',
			impressions   BIGINT NOT NULL,
			gross_micros  BIGINT NOT NULL,
			txn_id        UUID REFERENCES ledger_transactions(id),
			created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
			UNIQUE (advertiser_id, window_from, campaign_id, site_id)
		);
		CREATE INDEX IF NOT EXISTS idx_advertiser_settlements_day
			ON advertiser_settlements(advertiser_id, local_date DESC);

		-- Publisher-side settlement cells: 'clearing' drains gross; the
		-- publisher is credited net and the platform books the fee.
		-- margin_bps snapshots the rate applied (effective at window end) so
		-- later margin changes never reprice settled history.
		CREATE TABLE IF NOT EXISTS publisher_settlements (
			id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
			publisher_id  TEXT NOT NULL,
			site_id       TEXT NOT NULL,
			campaign_id   TEXT NOT NULL,
			advertiser_id TEXT NOT NULL,
			window_from   TIMESTAMPTZ NOT NULL,
			window_to     TIMESTAMPTZ NOT NULL,
			local_date    DATE NOT NULL,
			timezone      TEXT NOT NULL DEFAULT '',
			impressions   BIGINT NOT NULL,
			gross_micros  BIGINT NOT NULL,
			margin_bps    INTEGER NOT NULL,
			fee_micros    BIGINT NOT NULL,
			net_micros    BIGINT NOT NULL,
			txn_id        UUID REFERENCES ledger_transactions(id),
			created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
			UNIQUE (publisher_id, window_from, site_id, campaign_id, advertiser_id)
		);
		CREATE INDEX IF NOT EXISTS idx_publisher_settlements_day
			ON publisher_settlements(publisher_id, local_date DESC);

		-- Advertiser-self-reported conversions → CPA/ROAS on the report page.
		-- Aggregate counts the advertiser attributes to a campaign on a day;
		-- Promovolve never tracks users across sites, so this is the finest
		-- granularity that stays in-policy (campaign-level, not per-site). Not
		-- billed on — display only. UNIQUE (advertiser, campaign, day) → the
		-- form upserts, so restating a day's number overwrites it.
		CREATE TABLE IF NOT EXISTS campaign_conversions (
			id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
			advertiser_id TEXT NOT NULL,
			campaign_id   TEXT NOT NULL,
			conv_date     DATE NOT NULL,
			conversions   BIGINT NOT NULL,
			value_micros  BIGINT NOT NULL DEFAULT 0,
			source        TEXT NOT NULL DEFAULT 'manual',
			note          TEXT NOT NULL DEFAULT '',
			updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
			created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
			UNIQUE (advertiser_id, campaign_id, conv_date)
		);
		CREATE INDEX IF NOT EXISTS idx_campaign_conversions_range
			ON campaign_conversions(advertiser_id, conv_date DESC);

		-- Payout lifecycle: created (ledger already moved payable -> cash),
		-- then paid once the operator sends the transfer, or cancelled via a
		-- reversing adjustment.
		CREATE TABLE IF NOT EXISTS payouts (
			id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
			publisher_id  TEXT NOT NULL,
			amount_micros BIGINT NOT NULL CHECK (amount_micros > 0),
			currency      TEXT NOT NULL DEFAULT 'USD',
			period_start  DATE NOT NULL,
			period_end    DATE NOT NULL,
			status        TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'paid', 'cancelled')),
			external_ref  TEXT NOT NULL DEFAULT '',
			txn_id        UUID REFERENCES ledger_transactions(id),
			created_by    UUID REFERENCES platform_users(id),
			created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
			paid_at       TIMESTAMPTZ
		);
		CREATE INDEX IF NOT EXISTS idx_payouts_publisher ON payouts(publisher_id, created_at DESC);

		-- Operator-wide scalar knobs (billing). Unlike platform_margin_history
		-- these don't reprice anything, so plain upsert beats effective-dating.
		CREATE TABLE IF NOT EXISTS platform_settings (
			key        TEXT PRIMARY KEY,
			value      TEXT NOT NULL,
			updated_by UUID REFERENCES platform_users(id),
			updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
		);

		-- Free-form per-operator payout details (bank fields differ per
		-- country); min_payout_micros gates the admin payout queue.
		CREATE TABLE IF NOT EXISTS payout_methods (
			publisher_id      TEXT PRIMARY KEY,
			method            TEXT NOT NULL,
			details           JSONB NOT NULL DEFAULT '{}',
			min_payout_micros BIGINT NOT NULL DEFAULT 50000000,
			updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
		);

		-- Publisher site requests: adding a site only records intent here; the
		-- core SiteEntity is created at admin approval (mirrors platform_users'
		-- account-request lifecycle). Approved rows are kept for audit and
		-- filtered out of the publisher page (the core site is the live record).
		CREATE TABLE IF NOT EXISTS site_requests (
			id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
			publisher_id  TEXT NOT NULL,
			requested_by  UUID,
			site_id       TEXT NOT NULL,
			domain        TEXT NOT NULL,
			page_url      TEXT NOT NULL,
			status        TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'rejected')),
			reject_reason TEXT NOT NULL DEFAULT '',
			reviewed_by   UUID,
			reviewed_at   TIMESTAMPTZ,
			created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
			updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
		);
		-- One live pending card per (publisher, site). Denied/approved rows do
		-- not block a re-request; two different publishers MAY both hold a
		-- pending row for the same site_id — the second approval fails at core
		-- (site_id_taken) and the error surfaces on the admin queue.
		CREATE UNIQUE INDEX IF NOT EXISTS uniq_site_requests_pending
			ON site_requests(publisher_id, site_id) WHERE status = 'pending';
		CREATE INDEX IF NOT EXISTS idx_site_requests_publisher ON site_requests(publisher_id, created_at DESC);
		CREATE INDEX IF NOT EXISTS idx_site_requests_status ON site_requests(status, created_at);

		-- Per-user preferences (v1: timezone; display_name predates this).
		-- '' = unset, timestamps render in UTC as before.
		ALTER TABLE platform_users ADD COLUMN IF NOT EXISTS timezone TEXT NOT NULL DEFAULT '';
		-- Dashboard language ('en'/'ja'; '' = auto — follow the browser).
		ALTER TABLE platform_users ADD COLUMN IF NOT EXISTS locale TEXT NOT NULL DEFAULT '';

		-- Organizations: exactly one per email domain. The org — not the
		-- individual user — owns the core advertiser/publisher entities; members
		-- share them. Either side may be NULL: sides are only added through
		-- operator approval (first account request, or an org side request).
		CREATE TABLE IF NOT EXISTS orgs (
			id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
			domain        TEXT UNIQUE NOT NULL,
			name          TEXT NOT NULL DEFAULT '',
			advertiser_id TEXT,
			publisher_id  TEXT,
			created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
			updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
		);
		-- Operator suspension of the whole company: members still authenticate
		-- but see a notice with the reason; both sides' serving freezes.
		-- Reversible — resume clears the fields.
		ALTER TABLE orgs ADD COLUMN IF NOT EXISTS suspended BOOLEAN NOT NULL DEFAULT FALSE;
		ALTER TABLE orgs ADD COLUMN IF NOT EXISTS suspend_reason TEXT NOT NULL DEFAULT '';
		ALTER TABLE orgs ADD COLUMN IF NOT EXISTS suspended_at TIMESTAMPTZ;
		ALTER TABLE orgs ADD COLUMN IF NOT EXISTS suspended_by TEXT NOT NULL DEFAULT '';
		-- Org timezone (IANA); '' = UTC. Drives budget rollover + pacing day
		-- on the core AND both sides' local billing days (the settler reads it
		-- for advertiser and publisher settlement windows alike — budget day
		-- == billing day). Operator-changeable only.
		ALTER TABLE orgs ADD COLUMN IF NOT EXISTS timezone TEXT NOT NULL DEFAULT '';

		-- Org membership: each user belongs to at most one org. org_role is the
		-- in-org role (billing + member management), NOT the platform operator
		-- role. Enforced invariant (in code): an org always keeps >=1 admin.
		CREATE TABLE IF NOT EXISTS org_members (
			org_id     UUID NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
			user_id    UUID NOT NULL REFERENCES platform_users(id) ON DELETE CASCADE,
			org_role   TEXT NOT NULL DEFAULT 'member' CHECK (org_role IN ('admin', 'member')),
			invited_by UUID REFERENCES platform_users(id) ON DELETE SET NULL,
			created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
			PRIMARY KEY (org_id, user_id)
		);
		CREATE UNIQUE INDEX IF NOT EXISTS uniq_org_members_user ON org_members(user_id);
		-- Login lands on the member's LAST-USED side (written by switch-side).
		-- Seeded at membership creation from the side the account arrived
		-- for, so it's always present; backfilled below for older rows.
		ALTER TABLE org_members ADD COLUMN IF NOT EXISTS preferred_side TEXT
			CHECK (preferred_side IN ('advertiser', 'publisher'));
		UPDATE org_members m SET preferred_side = u.requested_side
			FROM platform_users u
			WHERE u.id = m.user_id AND m.preferred_side IS NULL AND u.requested_side IS NOT NULL;

		-- An org admin's request to add the org's OTHER side (a publisher org
		-- asking for an advertiser account or vice versa). Never automatic —
		-- reviewed by the platform operator like any first account request.
		CREATE TABLE IF NOT EXISTS org_side_requests (
			id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
			org_id       UUID NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
			side         TEXT NOT NULL CHECK (side IN ('advertiser', 'publisher')),
			requested_by UUID REFERENCES platform_users(id) ON DELETE SET NULL,
			message      TEXT NOT NULL DEFAULT '',
			status       TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'rejected')),
			reviewed_by  UUID,
			reviewed_at  TIMESTAMPTZ,
			created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
		);
		CREATE UNIQUE INDEX IF NOT EXISTS uniq_org_side_requests_pending
			ON org_side_requests(org_id, side) WHERE status = 'pending';

		-- Actor-attributed audit trail: who did what, kept even after the user
		-- row is deleted (no FK on actor_id by design). Covers admin view-as
		-- sessions and org members' billing-relevant actions.
		CREATE TABLE IF NOT EXISTS audit_log (
			id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
			actor_id    UUID,
			actor_email TEXT NOT NULL DEFAULT '',
			org_id      UUID,
			action      TEXT NOT NULL,
			target      TEXT NOT NULL DEFAULT '',
			detail      TEXT NOT NULL DEFAULT '',
			created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
		);
		CREATE INDEX IF NOT EXISTS idx_audit_log_created ON audit_log(created_at DESC);

		-- Backfill for pre-org databases (idempotent): every active
		-- advertiser/publisher user that owns entities becomes the admin of the
		-- org for their email domain. Same-domain users merge into one org,
		-- pooling their sides — the new model's semantics.
		INSERT INTO orgs (domain, name, advertiser_id, publisher_id)
		SELECT split_part(email, '@', 2),
		       COALESCE(MAX(NULLIF(company_name, '')), MAX(NULLIF(display_name, '')), ''),
		       MAX(advertiser_id), MAX(publisher_id)
		FROM platform_users u
		WHERE status = 'active' AND role IN ('advertiser', 'publisher')
		  AND (advertiser_id IS NOT NULL OR publisher_id IS NOT NULL)
		  AND NOT EXISTS (SELECT 1 FROM org_members m WHERE m.user_id = u.id)
		GROUP BY split_part(email, '@', 2)
		ON CONFLICT (domain) DO UPDATE SET
			advertiser_id = COALESCE(orgs.advertiser_id, EXCLUDED.advertiser_id),
			publisher_id  = COALESCE(orgs.publisher_id,  EXCLUDED.publisher_id),
			updated_at    = NOW();

		INSERT INTO org_members (org_id, user_id, org_role)
		SELECT o.id, u.id, 'admin'
		FROM platform_users u
		JOIN orgs o ON o.domain = split_part(u.email, '@', 2)
		WHERE u.status = 'active' AND u.role IN ('advertiser', 'publisher')
		  AND (u.advertiser_id IS NOT NULL OR u.publisher_id IS NOT NULL)
		ON CONFLICT DO NOTHING;
	`)
	return err
}
