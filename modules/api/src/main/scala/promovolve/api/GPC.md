# Global Privacy Control (GPC)

Promovolve respects the Global Privacy Control (GPC) signal, a browser-level
opt-out mechanism for users who do not wish to have their data sold or shared.

A note on scope: Promovolve serves **contextual** ads (matched to the page's
content category) on a **CPM-only** basis. It does not build a per-viewer
profile, does not personalize by individual, and holds no server-side viewer
identity — so there is structurally nothing to "sell or share" about an
individual. Honoring GPC here is therefore a conservative, user-respecting
posture rather than a strict requirement: when the signal is present, we simply
decline to serve. No identifier is read, stored, or required to do so.

## How It Works

```
┌──────────────────────────────────────────────────────────────────┐
│  User's Browser                                                  │
│                                                                  │
│  1. Load publisher site (news.com)                               │
│     ──────────────────────────► news.com server                  │
│     ◄──────────────────────────  returns HTML + JS               │
│                                                                  │
│  2. JS executes, calls promovolve directly:                      │
│                                                                  │
│     POST https://ads.promovolve.com/v1/serve/batch               │
│           │                                                      │
│           │  Sec-GPC: 1  (browser adds automatically)            │
│           ▼                                                      │
│     ┌─────────────────┐                                          │
│     │   Promovolve    │                                          │
│     │   Ad Server     │                                          │
│     └────────┬────────┘                                          │
│              │                                                   │
│              ▼                                                   │
│     204 No Content  (no ad served)                               │
│                                                                  │
│  3. JS receives empty response, shows nothing                    │
└──────────────────────────────────────────────────────────────────┘
```

### Key Points

- **Browser sends header automatically**: Once a user enables GPC in their
  browser, the `Sec-GPC: 1` header is attached to every HTTP request the browser
  makes.

- **No identity involved**: The decision is made purely on the presence of the
  header. No user identifier is passed, read, or stored — the opt-out is honored
  without ever identifying the viewer.

- **No publisher changes required**: The browser sends the header directly to
  Promovolve; the publisher does not need to read or forward anything.

- **Early exit**: When `Sec-GPC: 1` is detected, `ServeRoutes` returns
  `204 No Content` immediately without calling the `AdServer`, minimizing server
  load.

## Browser Support

| Browser         | GPC Support                          | Default |
|-----------------|--------------------------------------|---------|
| **Firefox**     | Native (Settings > Privacy)          | Off     |
| **Brave**       | Native                               | On      |
| **DuckDuckGo**  | Native (desktop & mobile)            | On      |
| **Chrome**      | Via extension (Privacy Badger, etc.) | N/A     |
| **Safari**      | Not natively supported yet           | N/A     |
| **Edge**        | Via extension                        | N/A     |

### How Users Enable GPC

**Firefox**: Settings → Privacy & Security → "Tell websites not to sell or share my data"

**Brave**: On by default (can be toggled in Shields settings)

**DuckDuckGo**: On by default

**Chrome/Edge**: Install Privacy Badger or similar extension

## Legal Recognition

GPC is legally recognized as a valid opt-out signal in several jurisdictions:

| Jurisdiction         | Regulation | Status                                    |
|----------------------|------------|-------------------------------------------|
| California (US)      | CCPA/CPRA  | Legally binding opt-out signal            |
| Colorado (US)        | CPA        | Recognized as universal opt-out mechanism |
| Connecticut (US)     | CTDPA      | Recognized as universal opt-out mechanism |
| European Union       | GDPR       | Can be interpreted as withdrawal of consent |

## Implementation

The GPC check guards the serve path, `POST /v1/serve/batch` — one request per
page load, all slots. It is implemented in `ServeRoutes.scala`:

```scala
val routes: Route =
  concat(
    pathPrefix("v1" / "serve") {
      // POST /v1/serve/batch — one request per page load, all slots.
      path("batch") {
        post {
          optionalHeaderValueByName("Sec-GPC") {
            case Some("1") => complete(StatusCodes.NoContent)
            case _         =>
              entity(as[BatchServeReq]) { req =>
                // Normal ad serving flow (joint auction via AdServer.BatchSelect)
                ...
              }
          }
        }
      }
    },
    ...
  )
```

The batch endpoint is the **only** serve route: the legacy single-slot
`GET /v1/serve` was removed when serving consolidated on the batch path (even
admin tooling posts a one-slot batch request).

## Testing

```bash
# With GPC header (should return 204)
curl -X POST -H "Sec-GPC: 1" -H "Content-Type: application/json" \
  -d '{"pub":"test","url":"http://example.com","imp":[{"id":"top","w":728,"h":90}]}' \
  "http://localhost:8080/v1/serve/batch"

# Without GPC header (normal ad serving)
curl -X POST -H "Content-Type: application/json" \
  -d '{"pub":"test","url":"http://example.com","imp":[{"id":"top","w":728,"h":90}]}' \
  "http://localhost:8080/v1/serve/batch"
```

Also testable in Firefox (enable GPC in Settings → Privacy & Security) or Brave
(GPC on by default).

## Why there is no server-side opt-out registry

An earlier design considered a server-side do-not-target registry keyed on a
hashed user identifier (`uid`), so logged-in users could opt out
browser-independently. We deliberately do **not** build this:

- It would require ingesting a per-user identifier at serve time — reintroducing
  exactly the server-side viewer identity the architecture avoids by design.
- It would create a new store of hashed-email PII, with its own access/deletion
  obligations.
- It solves a problem the architecture already solves structurally: a
  do-not-target registry exists to let a user opt out of being profiled, but
  Promovolve builds no per-viewer profile to begin with. Adding a tracking
  identifier in order to suppress tracking that does not happen is incoherent.

The header-based opt-out above is sufficient and requires no identity. Privacy
here is structural ("can't"), not a retained promise ("won't").

## References

- [GPC Specification](https://globalprivacycontrol.github.io/gpc-spec/)
- [California Attorney General GPC FAQ](https://oag.ca.gov/privacy/ccpa)
- [GPC.org](https://globalprivacycontrol.org/)
