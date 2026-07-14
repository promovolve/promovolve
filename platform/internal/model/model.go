package model

import "time"

// Role is used for two distinct things that share a vocabulary:
//   - the platform_users.role GRANT column, whose only stored values are
//     RoleAdmin and RoleUser — a user row never stores a side, because once
//     an org holds both sides any single per-user value is ambiguous;
//   - SIDES (RoleAdvertiser/RolePublisher) everywhere else: org capabilities,
//     the session's active side (the JWT role claim), requested_side, and
//     preferred_side.
type Role string

const (
	RoleAdvertiser Role = "advertiser"
	RolePublisher  Role = "publisher"
	RoleAdmin      Role = "admin"
	RoleUser       Role = "user"
)

type UserStatus string

const (
	StatusPending  UserStatus = "pending"
	StatusActive   UserStatus = "active"
	StatusRejected UserStatus = "rejected"
)

type User struct {
	ID           string  `json:"id"`
	Email        string  `json:"email"`
	PasswordHash string  `json:"-"` // empty for passkey-only users
	Role         Role    `json:"role"`
	DisplayName  string  `json:"displayName"`
	// IANA zone for rendering timestamps ("" = UTC). A per-user preference;
	// stored data stays UTC, only display converts.
	Timezone string `json:"timezone,omitempty"`
	AdvertiserID *string `json:"advertiserId,omitempty"`
	PublisherID  *string `json:"publisherId,omitempty"`
	// RequestedSide is the side a pending account request asked for — the
	// only side-shaped fact that belongs on the user row. Approve provisions
	// from it; meaningless after activation (the org's sides take over).
	RequestedSide Role       `json:"requestedSide,omitempty"`
	Status        UserStatus `json:"status"`
	// Account-request metadata, filled by the request-access form.
	CompanyName    string     `json:"companyName,omitempty"`
	WebsiteURL     string     `json:"websiteUrl,omitempty"`
	ContactName    string     `json:"contactName,omitempty"`
	RequestMessage string     `json:"requestMessage,omitempty"`
	ReviewedBy     *string    `json:"reviewedBy,omitempty"`
	ReviewedAt     *time.Time `json:"reviewedAt,omitempty"`
	CreatedAt      time.Time  `json:"createdAt"`
	UpdatedAt      time.Time  `json:"updatedAt"`

	// Session-scoped fields, filled by the session resolver and never
	// persisted. For org members Role is overridden in-memory to the active
	// side and AdvertiserID/PublisherID to the ORG's entities, so every
	// existing handler and template keeps working unchanged.
	Org        *Org    `json:"-"`
	OrgRole    OrgRole `json:"-"`
	ActiveSide Role    `json:"-"`
	// ViewAsBy is the operator's email when this session is a read-only
	// admin "view as" of the account; drives the banner in layout.html.
	ViewAsBy string `json:"-"`
}

// HasBothSides reports whether the user's org holds both an advertiser and a
// publisher account (shows the side switcher).
func (u *User) HasBothSides() bool {
	return u.Org != nil && u.Org.HasSide(RoleAdvertiser) && u.Org.HasSide(RolePublisher)
}

// CanBilling gates the money pages: advertiser Wallet and publisher Earnings
// are org-admin-only. Users outside any org (platform operator, pre-org
// accounts) are unrestricted.
func (u *User) CanBilling() bool {
	return u.Org == nil || u.OrgRole == OrgRoleAdmin
}

// IsOrgAdmin reports whether the user administers their org (invite/remove/
// promote members, request the org's other side).
func (u *User) IsOrgAdmin() bool {
	return u.Org != nil && u.OrgRole == OrgRoleAdmin
}

// SiteRequest is a publisher's request to add a site, pending admin review.
// The core SiteEntity is only created at approval (mirrors the account-request
// lifecycle on User). Status strings live in the siterequest package.
type SiteRequest struct {
	ID           string     `json:"id"`
	PublisherID  string     `json:"publisherId"`
	RequestedBy  *string    `json:"requestedBy,omitempty"`
	SiteID       string     `json:"siteId"`
	Domain       string     `json:"domain"`
	PageURL      string     `json:"pageUrl"`
	Status       string     `json:"status"`
	RejectReason string     `json:"rejectReason,omitempty"`
	ReviewedBy   *string    `json:"reviewedBy,omitempty"`
	ReviewedAt   *time.Time `json:"reviewedAt,omitempty"`
	CreatedAt    time.Time  `json:"createdAt"`
	UpdatedAt    time.Time  `json:"updatedAt"`
}

// Claims represents the JWT token payload.
//
// For org sessions Role is the ACTIVE SIDE (advertiser|publisher) the member
// chose, not a property of the person — the same login can switch sides when
// the org holds both accounts. ActorID, when set, marks a read-only admin
// "view as" session: UserID/Email describe the viewed account (so every page
// renders exactly as that user sees it) while ActorID names the operator.
type Claims struct {
	UserID       string  `json:"uid"`
	Email        string  `json:"email"`
	Role         Role    `json:"role"`
	AdvertiserID string  `json:"advId,omitempty"`
	PublisherID  string  `json:"pubId,omitempty"`
	OrgID        string  `json:"org,omitempty"`
	OrgRole      OrgRole `json:"orgRole,omitempty"`
	ActorID      string  `json:"act,omitempty"`
}

// OrgRole is a member's role INSIDE their org (billing + member management),
// unrelated to the platform operator's RoleAdmin.
type OrgRole string

const (
	OrgRoleAdmin  OrgRole = "admin"
	OrgRoleMember OrgRole = "member"
)

// Org is one customer organization: exactly one per email domain, holding the
// advertiser and/or publisher core entities its members operate.
// DefaultMaxOrgMembers caps the org (first admin + invitees) unless the
// platform operator overrides it in /admin/settings (platform_settings
// key org_max_members — see org.Repository.MaxMembers).
const DefaultMaxOrgMembers = 5

type Org struct {
	ID           string    `json:"id"`
	Domain       string    `json:"domain"`
	Name         string    `json:"name"`
	AdvertiserID *string   `json:"advertiserId,omitempty"`
	PublisherID  *string   `json:"publisherId,omitempty"`
	CreatedAt    time.Time `json:"createdAt"`
	UpdatedAt    time.Time `json:"updatedAt"`
	// Operator suspension: members still authenticate but every dashboard
	// request renders a notice with SuspendReason; serving on both sides
	// is frozen. Reversible.
	Suspended     bool       `json:"suspended"`
	SuspendReason string     `json:"suspendReason,omitempty"`
	SuspendedAt   *time.Time `json:"suspendedAt,omitempty"`
	SuspendedBy   string     `json:"suspendedBy,omitempty"`
}

// Location resolves the user's timezone preference; unset or invalid
// zones fall back to UTC so a bad value can never break a render.
func (u *User) Location() *time.Location {
	if u == nil || u.Timezone == "" {
		return time.UTC
	}
	loc, err := time.LoadLocation(u.Timezone)
	if err != nil {
		return time.UTC
	}
	return loc
}

// HasSide reports whether the org holds the core account for a side.
func (o *Org) HasSide(side Role) bool {
	switch side {
	case RoleAdvertiser:
		return o.AdvertiserID != nil && *o.AdvertiserID != ""
	case RolePublisher:
		return o.PublisherID != nil && *o.PublisherID != ""
	}
	return false
}

// DefaultSide picks the side a session lands on when none was chosen (or the
// chosen one no longer exists). preferred wins when the org holds it.
func (o *Org) DefaultSide(preferred Role) Role {
	if o.HasSide(preferred) {
		return preferred
	}
	if o.HasSide(RoleAdvertiser) {
		return RoleAdvertiser
	}
	return RolePublisher
}

// OrgMembership links one platform user to their org.
type OrgMembership struct {
	OrgID   string  `json:"orgId"`
	UserID  string  `json:"userId"`
	OrgRole OrgRole `json:"orgRole"`
	// PreferredSide is the member's last-used side (written on side-switch);
	// empty until they first switch.
	PreferredSide Role      `json:"preferredSide,omitempty"`
	InvitedBy     *string   `json:"invitedBy,omitempty"`
	CreatedAt     time.Time `json:"createdAt"`
}

// DefaultSide picks the member's landing side for a fresh session: their
// preferred side (seeded at membership creation, updated on switch),
// clamped to the sides the org actually holds.
func (m *OrgMembership) DefaultSide(o *Org) Role {
	return o.DefaultSide(m.PreferredSide)
}

// OrgSideRequest is an org admin's request to add the org's other side
// (advertiser or publisher), pending platform-operator review — the second
// side is never automatic.
type OrgSideRequest struct {
	ID          string     `json:"id"`
	OrgID       string     `json:"orgId"`
	Side        Role       `json:"side"`
	RequestedBy *string    `json:"requestedBy,omitempty"`
	Message     string     `json:"message,omitempty"`
	Status      string     `json:"status"`
	ReviewedBy  *string    `json:"reviewedBy,omitempty"`
	ReviewedAt  *time.Time `json:"reviewedAt,omitempty"`
	CreatedAt   time.Time  `json:"createdAt"`
	// Joined for the admin queue display.
	OrgDomain      string `json:"orgDomain,omitempty"`
	OrgName        string `json:"orgName,omitempty"`
	RequesterEmail string `json:"requesterEmail,omitempty"`
}
