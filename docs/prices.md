# Price tracking

Covers `service/prices/` and the Precios view.

## `service/prices/`

**price tracking** (the "📉 Precios" view, `web/PriceController`, `/api/prices`): the user
pastes a product link, `PriceTrackingService.add` reads it at once (a page that can't be read is refused with
422 + why, nothing saved) and `PriceCheckScheduler` re-reads every product 2 min after start-up and then every
6 h while the app runs (`app.prices.first-check-after`/`check-every`/`pause-between`; `app.prices.scheduled=false`
in tests, so tests never reach real shops). `ProductPageParser` reads schema.org JSON-LD (`Product` offers,
`AggregateOffer.lowPrice`, `ProductGroup.hasVariant` — cheapest offer in stock) or product meta tags, never the
visible text. `PageFetcher` downloads only public internet addresses (loopback/LAN/link-local refused on every
redirect hop, followed by hand: the URL comes from the user), sends browser-like headers including
`Accept-Encoding: gzip, deflate` and decompresses, and turns 403/429 or a Cloudflare/Akamai/PerimeterX page into
"this shop blocks programs". Amazon links are refused up front (Amazon blocks programs; the message points to Keepa or camelcamelcamel). **Which shops
work** (checked 2026-09-25): CholloStyle yes; Zalando and idealo publish the data but answer 403 to the JDK's
HTTP client (curl with identical headers got 200 — they fingerprint the connection); Zara, PcComponentes, HSN
wall off every program. Deliberately **not** worked around (no curl/browser impersonation): those
shops are left to their own e-mail alerts. A `PricePoint` is stored on the first
read and then only when price or stock changes; a failed check keeps the last good price and sets
`last_error`. The DTO's `previous_price`/`changed_at` come from the history, and so does `restocked_at` (in stock
now after a sold-out point: the first in-stock point after the last sold-out one; null otherwise). Frontend: cards
(`priceCardHtml`), a card is "news" when it dropped or came back in stock ("Ha vuelto a haber stock") in the last
`PRICE_NEWS_DAYS` (7), or is at/below its target while in stock;
the menu badge (`#prices-badge`) counts those and is filled at start-up (`refreshPriceBadge`, silent on
failure). The chart is a stepped line of the points plus one at `checked_at`. The fake backend returns copies
for `/api/prices` (a real server never shares objects with the page).
