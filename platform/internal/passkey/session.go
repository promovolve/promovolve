package passkey

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"sync"
	"time"

	"github.com/go-webauthn/webauthn/webauthn"
)

var ErrSessionExpired = errors.New("ceremony session expired or unknown")

// sessionTTL bounds how long a begin→finish ceremony may take. Five minutes
// matches the default WebAuthn timeout hint.
const sessionTTL = 5 * time.Minute

type sessionEntry struct {
	data    webauthn.SessionData
	payload any
	expires time.Time
}

// SessionStore holds in-flight ceremony state in memory, keyed by a random
// token round-tripped through the begin/finish JSON.
//
// In-memory is deliberate: the platform runs as a single pod (replicas: 1 in
// k8s/platform-deployment.yaml). A restart mid-ceremony fails that one
// ceremony and the user retries. If the platform ever scales beyond one
// replica, this must move to a webauthn_sessions table.
type SessionStore struct {
	mu       sync.Mutex
	sessions map[string]sessionEntry
}

func NewSessionStore() *SessionStore {
	s := &SessionStore{sessions: make(map[string]sessionEntry)}
	go s.sweep()
	return s
}

// Put stores ceremony state and returns its one-time token. payload carries
// pending form fields (request-account, setup) so the user row is only
// created at finish, atomically with the credential.
func (s *SessionStore) Put(data webauthn.SessionData, payload any) (string, error) {
	buf := make([]byte, 32)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	token := hex.EncodeToString(buf)

	s.mu.Lock()
	defer s.mu.Unlock()
	s.sessions[token] = sessionEntry{data: data, payload: payload, expires: time.Now().Add(sessionTTL)}
	return token, nil
}

// Take retrieves and deletes ceremony state — a token is single-use whether
// the finish succeeds or not.
func (s *SessionStore) Take(token string) (webauthn.SessionData, any, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	entry, ok := s.sessions[token]
	delete(s.sessions, token)
	if !ok || time.Now().After(entry.expires) {
		return webauthn.SessionData{}, nil, ErrSessionExpired
	}
	return entry.data, entry.payload, nil
}

func (s *SessionStore) sweep() {
	for range time.Tick(time.Minute) {
		now := time.Now()
		s.mu.Lock()
		for token, entry := range s.sessions {
			if now.After(entry.expires) {
				delete(s.sessions, token)
			}
		}
		s.mu.Unlock()
	}
}
