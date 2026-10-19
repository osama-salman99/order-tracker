# order-tracker

Every online order in one timeline, read straight from the mailbox.

Shops and delivery companies each write their emails their own way, in Dutch or English, with
no common format. This app reads them all and works out, for each message, which shop it is
from, which order and parcel it is about, what stage the order has reached, and when it is
expected. The home page lists orders by expected delivery, nearest arrival at the bottom.

## How it works

- **Reading mail.** Every minute it reads new mail under a Gmail label (`label:Orders` by
  default) through the Gmail API. Mail can also be pushed in by an email-forwarding worker
  at `POST /ingest-email`, which needs a shared secret.
- **Every message is kept as received.** The raw email is archived on disk, and every row
  records which version of the reading rules last read it. When the rules improve, they are
  run again over the whole archive, after a copy of the database is saved.
- **Reading rules, not a trained model.** Plain hand-written rules under `extract/` find the
  shop, order number, tracking number, stage, delivery date, items, total and product photo.
  A message that turns out not to be an order (a newsletter, a game purchase, a hosting bill)
  is set aside.
- **One order, many emails.** The confirmation, the shipping notice and the carrier's
  "coming Tuesday" are joined into one order through their shared order and tracking numbers.
- **Carrier tracking.** For PostNL parcels it asks PostNL's tracking service for the delivery
  day and time slot, which the emails never include.
- **Corrections by hand.** An order can be marked delivered or not delivered, dismissed, or
  linked to the replacement that a shop sent for it.
- **Product photos** are downloaded once and served from disk, so viewing the page never
  contacts a shop.

## Settings

| Setting | What it is for |
|---|---|
| `SSO_SIGNING_KEY` | Key that checks the shared login cookie. Every page needs a login. |
| `ORDERS_GMAIL_CLIENT_ID`, `_CLIENT_SECRET`, `_REFRESH_TOKEN` | Gmail access. Leave blank to rely on forwarded mail only. |
| `ORDERS_GMAIL_QUERY` | Which mail counts as order mail (default `label:Orders`) |
| `ORDERS_INGEST_TOKEN` | Secret for `POST /ingest-email`. Blank means the endpoint refuses everything. |
| `ORDERS_SELF_ADDRESSES` | Your own addresses, so a hand-forwarded email is credited to the real shop |
| `ORDERS_DELIVERY_POSTCODE` | Postcode added to PostNL tracking links |

## Building

Needs Java 25.

```
./gradlew clean build --parallel
java -jar build/libs/order-tracker-*.jar
```

Data (the H2 database, the email archive, backups and photos) is kept under `data/`.
