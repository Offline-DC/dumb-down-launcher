# Map Beta → Commercial: Migration Plan

**Status:** proposal · **Author:** drafted for Jack · **Date:** 2026-06-16
**Scope:** US · **Design target:** 1k–10k active users
**Strategy:** **Launch on Stadia Maps alone** (tiles + geocoding + routing), with the transit
mode hidden. Add **self-hosted MOTIS** for transit as a post-launch phase. Everything fronted by
the existing **`offline-dc-twilio`** backend (Heroku), under a new `/map` route group.

> Phase the risk: get a commercial v1 out with **zero new servers to run** (the proxy lives in
> the backend you already run + a Stadia account). Transit — the only piece that needs a box —
> comes later, after launch.

> **Implementation status (2026-06-16):** the `/map` proxy is **built, integrated into
> `offline-dc-twilio`, and tested green against the live Stadia key** (tiles, geocoding,
> ETA, and Valhalla routing). What remains is deploying it and wiring the WebView. Details in
> §6–§8.

---

## 1. Why this exists

The map beta (`app/src/main/assets/map/index.html`, a Leaflet WebView) runs entirely off
**free public/demo endpoints**. Every one has a usage policy that forbids or throttles
sustained commercial traffic, with no SLA. Shipping a paid product against them is both a
reliability risk and a terms-of-service violation.

The plan moves the high-volume services (tiles, geocoding, routing) onto a **managed provider
(Stadia Maps)** behind a thin proxy **built into the existing `offline-dc-twilio` backend** as a
`/map` route group. **Transit is deferred:** at launch the transit travel mode is hidden, and a
later phase adds **self-hosted MOTIS** (the only service no OSM provider sells). The WebView
stops calling the public internet directly and only ever talks to the backend
(`/map/...` on the `offline-dc-twilio` host).

**Net result for launch: no new servers to operate** — the proxy rides on the backend you
already run, plus one Stadia account. The single transit box arrives only in the post-launch
phase, if/when we want transit.

---

## 2. What's running on demo endpoints today

Every external call the WebView makes, pulled directly from `index.html`:

| # | Service | Demo endpoint (current) | Code location | What it does | Replaced by | When |
|---|---------|-------------------------|---------------|--------------|-------------|------|
| 1 | **Base map tiles** | `https://{s}.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png` | `L.tileLayer(...)` ~L224 | Map imagery (highest request volume) | **Stadia** raster tiles | Launch |
| 2 | **Fuzzy place search** | `https://photon.komoot.io/api/` | `photonSearch()` ~L421 | Typo-tolerant name search | **Stadia** Autocomplete/Search | Launch |
| 3 | **Address search + POI details** | `https://nominatim.openstreetmap.org/search` and `/lookup` | `nominatimSearch()` ~L399, `openDetails()` ~L599 | Exact address geocoding + place details | **Stadia** Forward/Reverse Geocoding + Place Lookup | Launch |
| 4 | **Travel-time ETAs** | `https://routing.openstreetmap.de/{routed-car\|foot\|bike}/...` | `fetchModeTimes()` ~L728 | Drive/walk/bike ETA in the mode chooser | **Stadia** Routing (or Matrix) | Launch |
| 5 | **Turn-by-turn routing** | `https://valhalla1.openstreetmap.de/route` | `doRoute()` ~L808 | Full route geometry + named turn-by-turn | **Stadia** Routing (**Valhalla — same engine!**) | Launch |
| 6 | **Transit routing** | `https://api.transitous.org/api/v2/plan` | `transitPlanUrl()` ~L874, `doTransitRoute()` | Multimodal bus/metro/train itineraries | **Self-hosted MOTIS** | **Post-launch** |

Leaflet, fonts, and icons are already bundled locally — no migration needed.

Two big wins that make the launch migration cheap:
- **Stadia's routing engine *is* Valhalla** — the exact engine the app already targets.
  `doRoute()` already parses Valhalla's `trip`/`legs`/`maneuvers` JSON, so #5 is nearly a drop-in.
- **The app already degrades without transit.** The chooser shows "—" and selecting transit shows
  "no transit route found." So hiding transit at launch is a clean, low-risk change (see §7).

---

## 3. Target architecture

```
   ┌────────────────────────┐
   │  Android WebView        │   (dumb-down-launcher, index.html)
   │  index.html (Leaflet)   │   only ever calls the backend's /map/* routes
   └───────────┬─────────────┘
               │  HTTPS, app key (x-app-key / ?k=) / Play Integrity later
               ▼
   ┌────────────────────────────────────────────────────┐
   │  offline-dc-twilio  (Heroku, existing backend)       │
   │  /map route group:                                   │
   │  • API gateway / reverse proxy                       │
   │  • holds Stadia API key (never on-device)            │
   │  • app-key auth + per-key rate limiting              │
   │  • caching (tiles, geocodes, ETAs)                   │
   └───────┬──────────────────────────────┬───────────────┘
           │                              ┊  (post-launch)
           ▼                              ▼
   ┌──────────────────────────┐   ┌ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┐
   │  STADIA MAPS (managed)    │     MOTIS  (self-hosted)
   │  • raster tiles           │   │ • transit routing only   │
   │  • geocoding / search     │     • US GTFS feeds + OSM
   │  • Valhalla routing + ETA │   │ • added in a later phase │
   └──────────────────────────┘   └ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┘
        LAUNCH STACK                   POST-LAUNCH ADD-ON
```

### Why the proxy still matters

- **Key safety.** The Stadia key must never ship in the APK. The backend holds it; the app
  authenticates with a shared app-key instead.
- **Cost control.** Caching tiles/geocodes server-side and rate-limiting per app-key keeps the
  Stadia bill predictable and blocks abuse via a leaked endpoint.
- **One stable contract.** The app codes against the backend's `/map/*` routes. When transit
  (MOTIS) is added later, only the backend + a small app toggle change — no rearchitecting.
- **Reuses existing infra.** No second app/dyno/pipeline to manage — it ships with the backend
  you already deploy.

---

## 4. Stadia Maps (tiles + geocoding + routing) — the launch stack

### 4.1 Plans (verified 2026-06-16)

| Plan | Price | Credits/mo | Commercial use | Overage |
|------|-------|-----------|----------------|---------|
| Free | $0 | 200,000 | ❌ not allowed | — |
| **Starter** | **$20/mo** | 1,000,000 | ✅ | +3¢/1000 |
| **Standard** | **$80/mo** | 7,500,000 | ✅ | +2¢/1000 |
| **Professional** | **$250/mo** | 25,000,000 | ✅ | +1.5¢/1000 |

Everything is one universal **credit** currency. Relevant per-call costs:

| Stadia feature | Credit cost | Used for |
|----------------|-------------|----------|
| Raster basemap tile | **1 / tile** | #1 map tiles |
| **Autocomplete Search (v2)** | **1 / req** | #2/#3 search — cheapest path |
| Forward / Reverse Geocoding | 20 / req | #3 address search, details enrichment |
| Place Lookup | 20 / GID | #3 details |
| Standard Routing | 20 / req | #4 ETA, #5 turn-by-turn |
| Time/Distance Matrix | 10 / element | #4 ETA (alternative) |

**Cost-saving note:** the app currently uses *forward geocoding* (20 credits). Switching the
search box to **Autocomplete v2 (1 credit)** cuts search cost ~20×. Keep forward geocoding only
for the house-number address path where exactness matters.

### 4.2 Endpoint mapping (Stadia)

- **Tiles (#1):** Stadia raster basemap (Leaflet-compatible). Pick a dark style close to the
  current Voyager look (Alidade/Stamen styles, or a custom style). Proxy long-caches.
- **Search (#2/#3):** Stadia Autocomplete v2 for the fuzzy path; Forward Geocoding for the
  house-number path. Both accept a `focus.point.lat/lon` bias — wire it from the app's existing
  GPS-derived bias (replaces the Nominatim `viewbox` / Photon `lat,lon`).
- **Details (#3):** Stadia Place Lookup / Reverse Geocoding for hours/phone/website enrichment in
  `openDetails()`.
- **ETA (#4) + turn-by-turn (#5):** Stadia Routing (Valhalla). For chooser ETAs, call routing per
  mode (as today) or use one Matrix call. For full routes, the response is Valhalla's native shape
  — `doRoute()` keeps working with at most cosmetic changes.

---

## 5. Transit — deferred to post-launch (self-hosted MOTIS)

Transit (#6) is **not in the launch.** At launch the transit travel mode is hidden (§7), so the
app ships drive/walk/bike only and never calls a transit endpoint.

When we're ready to add transit, we self-host **MOTIS** — the same open-source engine (MIT
licensed) that powers Transitous, which the app already targets. We chose self-hosting over
Google's transit API because Google requires its results to be displayed on a Google map, which
our OSM/Leaflet basemap isn't (full decision record below).

### 5.1 What the MOTIS phase will need (later)

- **Street data:** the US OpenStreetMap extract (~12 GB) — for walking first/last-mile legs.
- **Transit data:** GTFS feeds for the metros we serve (Transitous `sources`, Mobility Database,
  or transit.land). Start with DC/WMATA, expand per metro.
- **One small box:** transit-only MOTIS is light — ~16–32 GB RAM NVMe handles a good set of US
  metros at this scale. Containerized (official Docker image), firewalled to the proxy only,
  with a weekly GTFS refresh job.
- **App re-enable:** un-hide the transit mode (§7) and point `/map/transit/plan` at MOTIS. Because
  MOTIS returns the **same `itineraries[].legs[]` shape** the app already parses
  (`bestTransitItinerary`, `buildTransitRoute`, etc.), this is a near-passthrough with no
  normalization layer.

### Why not Google Routes (transit) — decision record

We considered buying transit from Google's Routes API to avoid running any box. **Ruled out**
after checking Google's official Routes API policy (developers.google.com/maps/documentation/routes/policies,
last updated 2026-05-27): *"Routes API results displayed on a map must be shown on a Google
Map."* Our map draws transit routes as Leaflet polylines over an OSM/Stadia basemap
(`buildTransitRoute()` adds `L.polyline` legs), so using Google transit data would require
replacing the entire basemap with Google Maps — defeating the Stadia/OSM stack and adding
Google map-load costs. Google also forbids caching itinerary results (only `place_id` is
storable). Self-hosting MOTIS avoids all of this and matches the app's existing API shape.

---

## 6. The proxy: `/map` in `offline-dc-twilio` (BUILT)

Rather than a separate app, the proxy is a **`/map` route group inside the existing
`offline-dc-twilio` backend** — no second app, dyno, or pipeline to manage. It follows the
repo's conventions (CommonJS `.js`, `tsc`/`allowJs` build, `loadConfig.js` precedence) and is
already implemented and tested.

- **Mount point:** `app.use("/map", mapRoutes)` in `src/app.js`, registered **before** the global
  ops CORS and `opsAuthGate`, so the map routes are self-contained (own permissive CORS, own JSON
  parsing, own app-key auth + rate limiting) and bypass the ops session gate.
- **Config:** secrets (`STADIA_API_KEY`, `MAP_APP_KEY`) in `config.env.local` / Heroku config
  vars; non-secret defaults (`STADIA_TILE_STYLE`, cache TTLs, rate limits) in `config.env`.
- **Build/run:** unchanged — ships with the backend's existing `tsc` build and `npm start`.

### 6.1 Files added (in `offline-dc-twilio/src/`)

```
src/
  app.js                       # + require + app.use("/map", mapRoutes) (early mount)
  routes/mapRoutes.js          # /map router: healthz, tiles, geocode/{search,lookup}, eta, route
  util/stadia.js               # Stadia client (holds the key; tiles/geocode/route/matrix)
  util/mapCache.js             # in-memory TTL cache for geocode/eta/route
  middleware/mapAuth.js        # shared app-key auth (x-app-key header or ?k=)
  middleware/mapRateLimit.js   # in-memory per-key fixed-window limiter
config.env                     # + STADIA_*/MAP_* non-secret defaults
config.env.local               # + STADIA_API_KEY, MAP_APP_KEY (gitignored)
```

Transit will add `util/motis.js` + a `/map/transit/plan` route in the post-launch phase.

### 6.2 Public contract (what the WebView calls)

All under the backend host, prefixed `/map`:

| Proxy endpoint | Upstream | Notes |
|----------------|----------|-------|
| `GET /map/tiles/{z}/{x}/{y}.png` | Stadia raster | Long-cache (~30d); put a CDN in front. Drop `{s}` subdomains (single origin). Auth via `?k=`. |
| `GET /map/geocode/search?q=&lat=&lon=&kind=fuzzy\|address` | Stadia Autocomplete v2 / Forward Geocoding | Picks engine by `kind` (preserves house-number→address logic). Returns `{results:[...]}` in the app's place shape. |
| `GET /map/geocode/lookup?id=<gid>` | Stadia Place | Details enrichment (Pelias has no OSM extratags — see §10). |
| `GET /map/eta?from=lng,lat&to=lng,lat&profile=car\|foot\|bike` | Stadia Matrix (Valhalla) | Returns `{durationSeconds}`. |
| `POST /map/route` (body `{locations,costing}`) | Stadia Routing (Valhalla) | Returns Valhalla trip JSON unchanged (drop-in for `doRoute`). |
| `GET /map/transit/plan?from=lat,lng&to=lat,lng&n=3` | self-hosted MOTIS `/api/v2/plan` | **Post-launch.** Not implemented yet; transit mode hidden until then. |
| `GET /map/healthz` | Stadia | Public; uptime monitoring. |

### 6.3 Cross-cutting concerns

- **App auth.** The WebView ships to phones, so the proxy is reachable by anyone who reads the
  APK. Implemented: a shared `MAP_APP_KEY` (`x-app-key` header, or `?k=` for tile `<img>`
  requests). Add **Google Play Integrity** attestation around GA so only genuine installs get
  through.
- **Rate limiting.** Implemented: in-memory per-key fixed-window limiter scoped to `/map`. For
  multiple dynos, move to Redis so the window is shared.
- **Caching.** Implemented: in-memory TTL cache for geocode/eta/route + long `Cache-Control` on
  tiles. Keeps Stadia credits low:
  - *Tiles* (highest volume) — add a CDN in front + 30-day cache. Confirm Stadia's terms allow
    proxy/CDN caching of tiles (§10).
  - *Geocode/ETA* — short read-through cache on identical queries.
  - *Route* — short TTL.
- **Attribution.** Keep OSM + Stadia attribution on the map. (Info screen carries OSM/CARTO credit
  at ~L173 and a transitous credit at ~L174 — update to Stadia; drop the transit credit until the
  MOTIS phase.)

---

## 7. WebView changes (`index.html`)

Minimal. One base-URL constant, repoint the five launch call sites, and hide the transit mode.
State machine, key handling, and rendering are untouched. Routing stays Valhalla, so `doRoute()`
keeps working.

```js
var API = 'https://<backend-host>';   // the offline-dc-twilio host (e.g. its Heroku domain)
var APP_KEY = '<MAP_APP_KEY>';         // same value as the backend's MAP_APP_KEY config var
```

**Hide transit at launch.** `modeRows()` currently returns `PROFILES.concat([TRANSIT])`. For the
launch build, return just `PROFILES` (drive/walk/bike) — or gate `TRANSIT` behind a
`var TRANSIT_ENABLED = false;` flag so re-enabling it later is a one-line change. The transit
code (`doTransitRoute`, `buildTransitRoute`, etc.) can stay in the file, dormant, for the
post-launch phase.

| Current call | Change to |
|--------------|-----------|
| CARTO tile URL (`{s}.basemaps.cartocdn.com/...`) | `API + '/map/tiles/{z}/{x}/{y}.png?k=' + APP_KEY` (drop `{s}`/`subdomains`) |
| `photonSearch()` → photon.komoot.io | `API + '/map/geocode/search?kind=fuzzy&q=…&lat=…&lon=…'` |
| `nominatimSearch()` → nominatim `/search` | `API + '/map/geocode/search?kind=address&q=…&lat=…&lon=…'` |
| `openDetails()` → nominatim `/lookup` | `API + '/map/geocode/lookup?id=…'` (or skip — see note) |
| `fetchModeTimes()` → routing.openstreetmap.de | `API + '/map/eta?from=…&to=…&profile=car\|foot\|bike'` |
| `doRoute()` → valhalla1.openstreetmap.de | `API + '/map/route'` (POST same body; response unchanged) |
| `transitPlanUrl()` → api.transitous.org | *(no change yet — transit mode hidden until the MOTIS phase)* |

Send the app-key on every JSON call via an `x-app-key` header (small `fetch` wrapper); tiles
carry it as `?k=` since `<img>` requests can't set headers. The proxy already returns geocode
results in the app's place shape, so `photonToPlace`/the Nominatim mapper are no longer needed.
Optionally switch the fuzzy path to Stadia Autocomplete v2 to cut credits. (The full before/after
diff was delivered with the backend; `openDetails()` can stay as-is since results no longer carry
`osmId`/`osmType`, so its enrichment fetch simply won't fire.)

---

## 8. Rollout phases

Each phase is independently shippable: until a service is migrated, its proxy route can pass
through to the current demo endpoint, so the app keeps working throughout.

### Launch (commercial v1 — Stadia only, no transit)

- **Phase 0 — Proxy build. ✅ DONE.** `/map` route group built into `offline-dc-twilio`
  (`mapRoutes.js`, `stadia.js`, `mapAuth.js`, `mapRateLimit.js`, `mapCache.js`), app-key auth +
  in-memory rate limit + cache, `/map/healthz`.
- **Phase 1 — Tiles via Stadia. ✅ DONE (code).** `/map/tiles` implemented with
  `alidade_smooth_dark` + long cache. Remaining: put a CDN in front in prod.
- **Phase 2 — Geocoding via Stadia. ✅ DONE.** `/map/geocode/search` (fuzzy + address) with GPS
  bias and `/map/geocode/lookup`, normalized to the app's place shape. Tested.
- **Phase 3 — Routing via Stadia. ✅ DONE.** `/map/eta` (Matrix) + `/map/route` (Valhalla trip).
  Verified parity — same Valhalla shape `doRoute()` already parses.
- **Phase 4 — Deploy + wire app + harden → SHIP.** Remaining work: deploy `offline-dc-twilio`
  with `STADIA_API_KEY` + `MAP_APP_KEY` set; apply the WebView edits (§7) incl. hiding transit;
  add a CDN for tiles; add Play Integrity attestation, uptime monitoring on `/map/healthz`,
  alerting, and a Stadia spend cap. **This is the commercial launch.**

### Post-launch

- **Phase 5 — Transit via self-hosted MOTIS.** Stand up the MOTIS box (US OSM + launch metros'
  GTFS), add `util/motis.js` + a `/map/transit/plan` route, set up the weekly GTFS refresh, then
  flip `TRANSIT_ENABLED = true` to re-expose the transit mode. Validate against current Transitous
  output (same engine, should match).

---

## 9. Cost (rough, monthly)

### At launch (Stadia only)

Itemized:

| Item | Cost/mo |
|------|---------|
| Heroku dyno (Basic; Eco ~$5, Standard-1X $25 for headroom) | ~$7 |
| Heroku Redis (cache + rate-limit) — or $0 with in-memory to start | ~$15 |
| CDN for tiles (Cloudflare free tier) | $0 |
| **Stadia** (Starter $20 / Standard $80) | **$20–$80** |

**Realistic launch total: ~$30–$90/mo** — well under $100 for most of the 1k–10k range, with
zero servers to patch or refresh.

The key lever is **tile caching.** Tiles are the highest-volume call but the most cacheable —
users share the same tiles and they rarely change, so once a CDN + 30-day cache is warm, most
tile requests never reach Stadia. That collapses the per-session cost from a naive ~120 credits
(uncached: ~60 tiles + 40 geocode + 20 route) to roughly **~30–50 credits** (a few uncached
tiles + 1-credit autocomplete searches + one 20-credit route).

Worked example: ~5,000 users × ~10 sessions/mo = 50,000 sessions × ~40 credits ≈ **2M
credits/mo**, comfortably inside **Standard ($80, 7.5M credits)**. Lighter usage near 1k users
fits **Starter ($20, 1M credits)**.

You only approach a couple hundred $/mo at the **top** of the range (≈10k active, heavy use)
*and* if caching underperforms enough to need **Professional ($250, 25M credits)** — that's the
ceiling, not the expected case.

### When transit is added (Phase 5)

- **MOTIS box:** one small VPS — on the order of **$20–60/mo** (cheaper on Hetzner-class than
  hyperscalers), plus ~a few hours/month of ops (largely automatable via the refresh job).

---

## 10. Risks & open questions

- **Stadia tile caching/proxying.** Confirm their terms allow serving tiles through our proxy/CDN
  (most providers allow client + CDN caching; verify the proxy pattern specifically).
- **No transit at launch.** Users won't have bus/metro/train directions in v1. Acceptable given
  drive/walk/bike cover the core use case, and the app degrades cleanly. Set expectations and
  prioritize the MOTIS phase if transit demand is high.
- **App-key leakage.** A static key in the APK will leak; Play Integrity is the real fix — land it
  before heavy marketing.
- **Style match.** Stadia's stock styles won't perfectly match the current CARTO Voyager dark
  look; budget a little time for a custom style so the map feels unchanged.

### Pre-existing issue worth flagging (not backend)

The map bundles **`helvetica_now_text_black.ttf`** — **Helvetica Now**, a commercial Monotype
typeface. Shipping it in a sold app needs a proper Monotype app-embedding license. Unrelated to
the geo backend, but it's already in the repo and is a real IP risk for a commercial release;
confirm coverage or swap to an open font (e.g. Inter).

---

## 11. Immediate next steps

Phases 0–3 are built and tested in `offline-dc-twilio` (the `/map` route group). What's left:

1. Deploy `offline-dc-twilio` with `STADIA_API_KEY` and a generated `MAP_APP_KEY` set as Heroku
   config vars; verify `GET /map/healthz` returns `{ok:true}`.
2. Apply the WebView edits (§7) in `dumb-down-launcher/.../map/index.html`: point at the backend's
   `/map/*` routes, send the app-key, and hide the transit mode.
3. Harden for launch (Phase 4): CDN in front of tiles, Play Integrity attestation, monitoring on
   `/map/healthz`, a Stadia spend cap. **Then ship.**
4. After launch, schedule the MOTIS transit phase (Phase 5) when transit demand justifies the box.
