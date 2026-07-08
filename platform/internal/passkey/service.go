package passkey

import (
	"context"
	"fmt"
	"io"

	"github.com/go-webauthn/webauthn/protocol"
	"github.com/go-webauthn/webauthn/webauthn"

	"github.com/hanishi/promovolve/platform/internal/model"
)

// UserStore is the slice of the user layer the passkey service needs to
// resolve discoverable-login user handles back to accounts.
type UserStore interface {
	GetByID(ctx context.Context, id string) (*model.User, error)
}

type Service struct {
	wa       *webauthn.WebAuthn
	repo     *Repository
	users    UserStore
	Sessions *SessionStore
}

func NewService(rpID string, rpOrigins []string, repo *Repository, users UserStore) (*Service, error) {
	wa, err := webauthn.New(&webauthn.Config{
		RPDisplayName: "PromoVolve",
		RPID:          rpID,
		RPOrigins:     rpOrigins,
	})
	if err != nil {
		return nil, fmt.Errorf("webauthn config: %w", err)
	}
	return &Service{wa: wa, repo: repo, users: users, Sessions: NewSessionStore()}, nil
}

func (s *Service) Repo() *Repository { return s.repo }

// waUser adapts a platform user + their stored credentials to the
// webauthn.User interface. The user handle is the UUID string (36 bytes,
// stable, under the spec's 64-byte cap).
type waUser struct {
	user  *model.User
	creds []webauthn.Credential
}

func (u waUser) WebAuthnID() []byte { return []byte(u.user.ID) }

func (u waUser) WebAuthnName() string { return u.user.Email }

func (u waUser) WebAuthnDisplayName() string {
	if u.user.DisplayName != "" {
		return u.user.DisplayName
	}
	return u.user.Email
}

func (u waUser) WebAuthnCredentials() []webauthn.Credential { return u.creds }

func wrap(user *model.User, stored []StoredCredential) waUser {
	creds := make([]webauthn.Credential, len(stored))
	for i, sc := range stored {
		creds[i] = sc.Credential
	}
	return waUser{user: user, creds: creds}
}

// BeginRegistration starts a creation ceremony for a user (who may not be
// persisted yet — the request-account flow registers before the row exists).
// Resident keys are required so login can be username-less; existing
// credentials are excluded so a device isn't registered twice.
func (s *Service) BeginRegistration(ctx context.Context, user *model.User, payload any) (*protocol.CredentialCreation, string, error) {
	var stored []StoredCredential
	if user.ID != "" {
		var err error
		stored, err = s.repo.ListByUser(ctx, user.ID)
		if err != nil {
			return nil, "", err
		}
	}
	wu := wrap(user, stored)

	exclusions := make([]protocol.CredentialDescriptor, len(wu.creds))
	for i, c := range wu.creds {
		exclusions[i] = c.Descriptor()
	}

	creation, session, err := s.wa.BeginRegistration(wu,
		webauthn.WithResidentKeyRequirement(protocol.ResidentKeyRequirementRequired),
		webauthn.WithAuthenticatorSelection(protocol.AuthenticatorSelection{
			ResidentKey:      protocol.ResidentKeyRequirementRequired,
			UserVerification: protocol.VerificationPreferred,
		}),
		webauthn.WithConveyancePreference(protocol.PreferNoAttestation),
		webauthn.WithExclusions(exclusions),
	)
	if err != nil {
		return nil, "", err
	}

	token, err := s.Sessions.Put(*session, payload)
	if err != nil {
		return nil, "", err
	}
	return creation, token, nil
}

// TakeSession redeems a ceremony token (single-use). Callers that stashed a
// payload at begin recover it here, reconstruct the pending user from it,
// then validate with FinishRegistration.
func (s *Service) TakeSession(token string) (webauthn.SessionData, any, error) {
	return s.Sessions.Take(token)
}

// FinishRegistration validates the creation response against a redeemed
// ceremony session and returns the new credential. The caller persists it
// (possibly in one transaction with the user row).
func (s *Service) FinishRegistration(user *model.User, session webauthn.SessionData, credentialBody io.Reader) (*webauthn.Credential, error) {
	parsed, err := protocol.ParseCredentialCreationResponseBody(credentialBody)
	if err != nil {
		return nil, err
	}
	return s.wa.CreateCredential(waUser{user: user}, session, parsed)
}

// loginTarget marks a ceremony as restricted to one account (email-directed
// login); absent payload means discoverable (username-less).
type loginTarget struct {
	UserID string
}

// BeginLogin starts a discoverable (username-less) assertion ceremony.
func (s *Service) BeginLogin() (*protocol.CredentialAssertion, string, error) {
	assertion, session, err := s.wa.BeginDiscoverableLogin()
	if err != nil {
		return nil, "", err
	}
	token, err := s.Sessions.Put(*session, nil)
	if err != nil {
		return nil, "", err
	}
	return assertion, token, nil
}

// BeginLoginFor starts an assertion restricted to one account's credentials
// (allowCredentials), so the browser only offers that account's passkeys.
// Errors if the account has none — callers fall back to discoverable login
// to avoid leaking which emails exist.
func (s *Service) BeginLoginFor(ctx context.Context, user *model.User) (*protocol.CredentialAssertion, string, error) {
	stored, err := s.repo.ListByUser(ctx, user.ID)
	if err != nil {
		return nil, "", err
	}
	if len(stored) == 0 {
		return nil, "", fmt.Errorf("no credentials for account")
	}
	assertion, session, err := s.wa.BeginLogin(wrap(user, stored))
	if err != nil {
		return nil, "", err
	}
	token, err := s.Sessions.Put(*session, loginTarget{UserID: user.ID})
	if err != nil {
		return nil, "", err
	}
	return assertion, token, nil
}

// FinishLogin validates an assertion, resolves the account (via the ceremony's
// login target, or the resident key's user handle for discoverable logins),
// persists the updated sign count, and returns the user.
func (s *Service) FinishLogin(ctx context.Context, sessionToken string, credentialBody io.Reader) (*model.User, error) {
	session, payload, err := s.Sessions.Take(sessionToken)
	if err != nil {
		return nil, err
	}
	parsed, err := protocol.ParseCredentialRequestResponseBody(credentialBody)
	if err != nil {
		return nil, err
	}

	loadUser := func(id string) (*model.User, []StoredCredential, error) {
		u, err := s.users.GetByID(ctx, id)
		if err != nil {
			return nil, nil, err
		}
		stored, err := s.repo.ListByUser(ctx, u.ID)
		if err != nil {
			return nil, nil, err
		}
		return u, stored, nil
	}

	var user *model.User
	var cred *webauthn.Credential

	if target, ok := payload.(loginTarget); ok {
		u, stored, err := loadUser(target.UserID)
		if err != nil {
			return nil, err
		}
		user = u
		cred, err = s.wa.ValidateLogin(wrap(u, stored), session, parsed)
		if err != nil {
			return nil, err
		}
	} else {
		cred, err = s.wa.ValidateDiscoverableLogin(func(rawID, userHandle []byte) (webauthn.User, error) {
			u, stored, err := loadUser(string(userHandle))
			if err != nil {
				return nil, err
			}
			user = u
			return wrap(u, stored), nil
		}, session, parsed)
		if err != nil {
			return nil, err
		}
	}

	if err := s.repo.UpdateCredential(ctx, cred); err != nil {
		return nil, err
	}
	return user, nil
}
