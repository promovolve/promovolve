package handler

import "testing"

// A stale view-as (or suspended-org) cookie must never block signing in or
// recovering an account: those ceremonies mint a fresh session and never
// act as the cookie's user. Adding a passkey to the CURRENT account does act
// as that user (in view-as: the viewed member), so it stays guarded.
func TestSignInCeremonyBypassesSessionGuard(t *testing.T) {
	for path, want := range map[string]bool{
		"/webauthn/login/begin":            true,
		"/webauthn/login/finish":           true,
		"/webauthn/recover/begin":          true,
		"/webauthn/recover/finish":         true,
		"/webauthn/setup/finish":           true,
		"/webauthn/request-account/finish": true,
		"/webauthn/passkeys/begin":         false,
		"/webauthn/passkeys/finish":        false,
		"/org/invite":                      false,
	} {
		if got := signInCeremony(path); got != want {
			t.Errorf("signInCeremony(%q) = %v, want %v", path, got, want)
		}
	}
}
