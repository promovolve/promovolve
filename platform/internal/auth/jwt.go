package auth

import (
	"errors"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/hanishi/promovolve/platform/internal/model"
)

var ErrInvalidToken = errors.New("invalid or expired token")

type jwtClaims struct {
	UserID       string        `json:"uid"`
	Email        string        `json:"email"`
	Role         model.Role    `json:"role"`
	AdvertiserID string        `json:"advId,omitempty"`
	PublisherID  string        `json:"pubId,omitempty"`
	OrgID        string        `json:"org,omitempty"`
	OrgRole      model.OrgRole `json:"orgRole,omitempty"`
	// ActorID marks a read-only admin view-as session: uid/email are the
	// VIEWED account, ActorID the operator driving it.
	ActorID string `json:"act,omitempty"`
	jwt.RegisteredClaims
}

type JWTService struct {
	secret []byte
	expiry time.Duration
}

func NewJWTService(secret string, expiry time.Duration) *JWTService {
	return &JWTService{
		secret: []byte(secret),
		expiry: expiry,
	}
}

// SessionOpts carries the org-session claims that don't live on the user row.
type SessionOpts struct {
	Org     *model.Org
	OrgRole model.OrgRole
	// Side is the active side (advertiser|publisher) the session operates;
	// it becomes the token's role claim so every role check follows it.
	Side model.Role
	// ActorID mints a read-only admin view-as session (the operator's user id).
	ActorID string
}

// Issue mints a plain token from the user row alone — platform admins and
// the pre-org fallback. Org members go through IssueSession.
func (s *JWTService) Issue(u *model.User) (string, error) {
	advID := ""
	if u.AdvertiserID != nil {
		advID = *u.AdvertiserID
	}
	pubID := ""
	if u.PublisherID != nil {
		pubID = *u.PublisherID
	}
	return s.sign(jwtClaims{
		UserID:       u.ID,
		Email:        u.Email,
		Role:         u.Role,
		AdvertiserID: advID,
		PublisherID:  pubID,
	})
}

// IssueSession mints an org session: the role claim is the ACTIVE SIDE and
// the entity ids are the ORG's (both sides — the org owns them; page access
// per side is enforced by the role claim).
func (s *JWTService) IssueSession(u *model.User, opts SessionOpts) (string, error) {
	c := jwtClaims{
		UserID:  u.ID,
		Email:   u.Email,
		Role:    opts.Side,
		OrgID:   opts.Org.ID,
		OrgRole: opts.OrgRole,
		ActorID: opts.ActorID,
	}
	if opts.Org.AdvertiserID != nil {
		c.AdvertiserID = *opts.Org.AdvertiserID
	}
	if opts.Org.PublisherID != nil {
		c.PublisherID = *opts.Org.PublisherID
	}
	return s.sign(c)
}

func (s *JWTService) sign(claims jwtClaims) (string, error) {
	claims.RegisteredClaims = jwt.RegisteredClaims{
		ExpiresAt: jwt.NewNumericDate(time.Now().Add(s.expiry)),
		IssuedAt:  jwt.NewNumericDate(time.Now()),
		Subject:   claims.UserID,
	}
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	return token.SignedString(s.secret)
}

func (s *JWTService) Validate(tokenString string) (*model.Claims, error) {
	token, err := jwt.ParseWithClaims(tokenString, &jwtClaims{}, func(t *jwt.Token) (any, error) {
		if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, ErrInvalidToken
		}
		return s.secret, nil
	})
	if err != nil {
		return nil, ErrInvalidToken
	}

	jc, ok := token.Claims.(*jwtClaims)
	if !ok || !token.Valid {
		return nil, ErrInvalidToken
	}
	return &model.Claims{
		UserID:       jc.UserID,
		Email:        jc.Email,
		Role:         jc.Role,
		AdvertiserID: jc.AdvertiserID,
		PublisherID:  jc.PublisherID,
		OrgID:        jc.OrgID,
		OrgRole:      jc.OrgRole,
		ActorID:      jc.ActorID,
	}, nil
}
