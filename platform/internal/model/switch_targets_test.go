package model

import (
	"slices"
	"testing"
)

func TestSwitchTargets(t *testing.T) {
	adv, pub := "adv-1", "pub-1"
	both := &Org{AdvertiserID: &adv, PublisherID: &pub}
	pubOnly := &Org{PublisherID: &pub}
	cases := []struct {
		name string
		u    User
		want []Role
	}{
		{"member of a one-side org", User{Org: pubOnly}, nil},
		{"member of a two-side org", User{Org: both}, []Role{RoleAdvertiser, RolePublisher}},
		{"operator with no org", User{IsOperator: true}, nil},
		{"operator session, org at their domain", User{IsOperator: true, OperatorOrg: pubOnly}, []Role{RoleAdmin, RolePublisher}},
		{"operator on a side of a two-side org", User{IsOperator: true, Org: both}, []Role{RoleAdvertiser, RolePublisher}},
		{"operator on a one-side org", User{IsOperator: true, Org: pubOnly}, nil},
	}
	for _, c := range cases {
		if got := c.u.SwitchTargets(); !slices.Equal(got, c.want) {
			t.Errorf("%s: SwitchTargets() = %v, want %v", c.name, got, c.want)
		}
	}
}
