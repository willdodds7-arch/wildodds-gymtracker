# Payments — Verified Creator tier & marketplace

How money moves, what to configure by hand, and why it's built web-first.

## Architecture (decided 2026-07-22)

**"Netflix-style" web entitlement.** All purchases happen on the public website
(GitHub Pages + Supabase Edge Functions + Stripe); the Android app only *reads* the resulting
entitlements. The app contains **no purchase links, buttons, or checkout URLs** — Google Play's
Payments policy requires Play Billing for in-app digital purchases and forbids steering to
external checkout, and Australia's user-choice-billing program still carries a Google commission
that breaks the 10%-platform-fee model. Consuming externally-purchased entitlements in-app is
allowed (the old Netflix/Spotify pattern). **Do not add "buy on the website" links to the app.**

- **Subscription** — Stripe Billing, A$2.00/month AUD (`STRIPE_CREATOR_PRICE_ID`). Badge +
  selling rights gate on `status in ('active','trialing')`, derived server-side by the webhook
  into `profiles.is_verified_creator`. Cancel = Stripe Billing Portal (`billing-portal` fn).
- **Program sales** — Stripe Connect **Express** accounts per creator (`connect-onboarding` fn).
  Checkout uses **destination charges** with `application_fee_amount` = 10% (rounded half-up,
  `FeeMath.kt` = `_shared/creator.ts`), so Stripe splits 90/10 automatically and pays the
  creator out itself. No custom payout system exists, ever.
- **Webhook** — `stripe-webhook` fn: signature-verified, idempotent via the `stripe_events`
  id ledger. Purchases upsert on unique `stripe_payment_intent_id` (both
  `checkout.session.completed` and `payment_intent.succeeded` can arrive; only one row results).
- **Publish gate** (server-side only, `publish-program` fn): active subscription AND
  `connect_onboarding_complete` AND Creator Agreement accepted. Lapsed subscription →
  webhook auto-unpublishes the creator's listings.

## Env vars (Edge Function secrets)

Set via dashboard → Edge Functions → Secrets, or `npx supabase secrets set KEY=value`:

| Key | Value |
|---|---|
| `STRIPE_SECRET_KEY` | `sk_live_…` (or `sk_test_…`) — server-side only, never in app/site |
| `STRIPE_WEBHOOK_SECRET` | `whsec_…` from the webhook endpoint you create below |
| `STRIPE_CREATOR_PRICE_ID` | `price_…` for the A$2.00/month subscription |
| `SITE_BASE_URL` | `https://willdodds7-arch.github.io/wildodds-gymtracker` |
| `PLATFORM_FEE_PERCENT` | optional, default `10` |
| `CONNECT_DEFAULT_COUNTRY` | optional, default `AU` |
| `GST_INCLUSIVE_PRICING` | optional flag, default treat prices as GST-inclusive (display copy already says so) |

GitHub repo secrets (already exist for CI): `SUPABASE_URL`, `SUPABASE_ANON_KEY` — pages.yml now
also feeds them to `site/build_site.py` to generate the site's `config.js`.

## Manual setup steps (dashboard work I can't do for you)

1. **Apply migrations** in the Supabase SQL Editor, in order:
   `20260720000001_friends.sql` (still pending from the friends feature), then
   `20260721000001_creator_marketplace.sql`.
2. **Create the Stripe account** (or use the existing one) → enable **Connect** →
   platform profile, choose Express accounts.
3. **Create the product/price**: Product "Verified Creator", recurring price **A$2.00/month,
   AUD** → copy the price id into `STRIPE_CREATOR_PRICE_ID`.
4. **Enable the Billing customer portal** (Settings → Billing → Customer portal) and allow
   subscription cancellation in it.
5. **Add the webhook endpoint**: URL `https://<project-ref>.supabase.co/functions/v1/stripe-webhook`,
   events: `checkout.session.completed`, `customer.subscription.updated`,
   `customer.subscription.deleted`, `invoice.paid`, `invoice.payment_failed`,
   `payment_intent.succeeded`, `charge.refunded`, `account.updated`
   (tick "listen to Connect accounts" for `account.updated`). Copy the signing secret into
   `STRIPE_WEBHOOK_SECRET`.
6. **Deploy the functions** (no Docker needed):
   `npx supabase functions deploy creator-checkout billing-portal connect-onboarding program-checkout publish-program accept-creator-agreement --project-ref rvacjiqncoyttovyebud`
   and the webhook **without JWT verification**:
   `npx supabase functions deploy stripe-webhook --no-verify-jwt --project-ref rvacjiqncoyttovyebud`
7. **Set the Edge Function secrets** from the table above.
8. Test end-to-end in Stripe **test mode** (card 4242…) before flipping to live keys:
   subscribe on `/creator.html` → badge appears in app; onboard Connect (test data);
   publish from the app's Creator hub; buy from another account on `/marketplace.html`;
   check the 90/10 split under Stripe → Payments; refund it and watch the purchase row flip.
9. **Verify RLS**: un-`@Ignore` `FriendsRlsIntegrationTest` (friends tables) and eyeball the new
   tables' policies with `supabase/audit/rls_audit.sql`.

## Refunds

Issue refunds from the Stripe dashboard **with "Refund application fee" and "Reverse transfer"
ticked** so the creator's 90% and the platform's 10% both flow back; `charge.refunded` then
marks the purchase row refunded, which revokes the buyer's content access (RLS checks
`status='paid'`).

## GST / tax (business-side note — not user-facing advice)

- Register for GST once turnover reaches **A$75,000**; until then the A$2 price simply has no
  GST component. Display copy already says "includes GST where applicable" either way.
- The marketplace may qualify as an **Electronic Distribution Platform (EDP)**, which can make
  the platform (not the creator) responsible for GST on creators' sales.
- **TODO: confirm registration status and EDP treatment with an accountant before launch.**
- Creators are contractually responsible for their own income tax/GST (Creator Agreement).

## Compliance guardrails (baked into the code — keep them)

- The daily price ("less than 7¢ a day", ceil'd so it never understates) is **never** shown
  without the full "A$2.00/month, auto-renews, cancel any time" disclosure
  (`PricingConfig.disclosure()`, site `SUB_DISCLOSURE`, enforced by `CreatorLogicTest`).
- `is_verified_creator` is derived by the webhook from live Stripe status; clients can't set it
  (no RLS write path), and `publish-program` re-checks the subscription live.
- All Stripe keys are Edge-Function secrets; the webhook verifies signatures; all handlers are
  idempotent (`stripe_events` ledger + unique payment-intent upserts).
- Legal docs: privacy v1.1, ToS v1.1, Creator Agreement, Subscription & Billing Terms, Refunds
  Policy — in-app (Settings → Legal & privacy), on the site, acceptance of the Creator
  Agreement enforced server-side before publishing. **All six need lawyer review before launch**
  (TODO headers in each doc).
