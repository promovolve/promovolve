package org

import "strings"

// Freemail domains are blocked at account request: an org is identified by
// its email domain, so a shared consumer domain can never anchor one. Exact
// matches plus a few provider families matched by prefix (yahoo.co.jp etc.).
var freemailExact = map[string]struct{}{
	"gmail.com": {}, "googlemail.com": {},
	"aol.com": {}, "icloud.com": {}, "me.com": {}, "mac.com": {},
	"proton.me": {}, "protonmail.com": {}, "pm.me": {}, "tutanota.com": {}, "tuta.io": {},
	"mail.com": {}, "email.com": {}, "usa.com": {},
	"mail.ru": {}, "inbox.ru": {}, "list.ru": {}, "bk.ru": {},
	"zoho.com": {}, "zohomail.com": {},
	"qq.com": {}, "foxmail.com": {}, "163.com": {}, "126.com": {}, "sina.com": {}, "sohu.com": {},
	"naver.com": {}, "daum.net": {}, "hanmail.net": {},
	"msn.com": {}, "fastmail.com": {}, "hey.com": {}, "hushmail.com": {},
	"rediffmail.com": {}, "web.de": {}, "t-online.de": {}, "freenet.de": {},
	"laposte.net": {}, "orange.fr": {}, "wanadoo.fr": {}, "free.fr": {},
	"libero.it": {}, "virgilio.it": {}, "terra.com": {}, "uol.com.br": {}, "bol.com.br": {},
	"docomo.ne.jp": {}, "ezweb.ne.jp": {}, "au.com": {}, "softbank.ne.jp": {},
	"i.softbank.jp": {}, "ymobile.ne.jp": {}, "nifty.com": {}, "biglobe.ne.jp": {},
	"excite.com": {}, "lycos.com": {}, "rocketmail.com": {}, "duck.com": {},
}

// freemailPrefixes catch provider families with many country TLDs:
// yahoo.com/.co.jp/.fr…, outlook.com/.jp…, hotmail.*, live.*, gmx.*, yandex.*.
var freemailPrefixes = []string{
	"yahoo.", "ymail.", "outlook.", "hotmail.", "live.", "gmx.", "yandex.",
}

// IsFreemailDomain reports whether the domain belongs to a consumer email
// provider (case-insensitive).
func IsFreemailDomain(domain string) bool {
	d := strings.ToLower(strings.TrimSpace(domain))
	if _, ok := freemailExact[d]; ok {
		return true
	}
	for _, p := range freemailPrefixes {
		if strings.HasPrefix(d, p) {
			return true
		}
	}
	return false
}
