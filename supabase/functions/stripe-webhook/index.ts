// stripe-webhook — the single source of truth for entitlements and purchase records.
//
// Security: NO user auth (Stripe calls it) — instead every request's signature is verified with
// STRIPE_WEBHOOK_SECRET before anything is read. Deploy with JWT verification OFF:
//   npx supabase functions deploy stripe-webhook --no-verify-jwt
//
// Idempotency: every event id is inserted into stripe_events first; a duplicate delivery hits the
// primary-key conflict and returns 200 without re-processing (never double-credits a purchase).
//
// Handled events:
//   checkout.session.completed        → record program purchases (mode=payment)
//   payment_intent.succeeded          → fallback purchase record (same idempotent upsert)
//   customer.subscription.updated/deleted, invoice.paid, invoice.payment_failed
//                                     → sync creator_subscriptions + derive profiles.is_verified_creator
//   charge.refunded                   → mark the purchase refunded (fee/transfer reversal is done
//                                       from the Stripe dashboard; we record the outcome)
//   account.updated                   → profiles.connect_onboarding_complete

import Stripe from "https://esm.sh/stripe@14.25.0?target=denonext";
import { admin, stripe } from "../_shared/creator.ts";

const cryptoProvider = Stripe.createSubtleCryptoProvider();

Deno.serve(async (req) => {
  if (req.method !== "POST") return new Response("method not allowed", { status: 405 });

  const signature = req.headers.get("stripe-signature");
  if (!signature) return new Response("missing signature", { status: 400 });

  const body = await req.text();
  const s = stripe();
  let event: Stripe.Event;
  try {
    event = await s.webhooks.constructEventAsync(
      body,
      signature,
      Deno.env.get("STRIPE_WEBHOOK_SECRET")!,
      undefined,
      cryptoProvider,
    );
  } catch {
    return new Response("bad signature", { status: 400 });
  }

  const db = admin();

  // Idempotency ledger: first delivery inserts; retries conflict and stop here.
  const { error: ledgerErr } = await db
    .from("stripe_events")
    .insert({ id: event.id, type: event.type });
  if (ledgerErr) return new Response(JSON.stringify({ received: true, duplicate: true }), { status: 200 });

  try {
    switch (event.type) {
      case "checkout.session.completed": {
        const session = event.data.object as Stripe.Checkout.Session;
        if (session.mode === "payment" && session.metadata?.kind === "program_purchase") {
          await recordPurchase(db, {
            programId: Number(session.metadata.program_id),
            buyerId: session.metadata.buyer_id,
            creatorId: session.metadata.creator_id,
            grossCents: session.amount_total ?? 0,
            feeCents: Number(session.metadata.platform_fee_cents ?? 0),
            currency: session.currency ?? "aud",
            paymentIntentId: String(session.payment_intent),
          });
        }
        break;
      }
      case "payment_intent.succeeded": {
        const pi = event.data.object as Stripe.PaymentIntent;
        if (pi.metadata?.kind === "program_purchase") {
          await recordPurchase(db, {
            programId: Number(pi.metadata.program_id),
            buyerId: pi.metadata.buyer_id,
            creatorId: pi.metadata.creator_id,
            grossCents: pi.amount_received || pi.amount,
            feeCents: Number(pi.metadata.platform_fee_cents ?? 0),
            currency: pi.currency ?? "aud",
            paymentIntentId: pi.id,
          });
        }
        break;
      }
      case "customer.subscription.updated":
      case "customer.subscription.deleted": {
        const sub = event.data.object as Stripe.Subscription;
        await syncSubscription(db, s, sub);
        break;
      }
      case "invoice.paid":
      case "invoice.payment_failed": {
        const invoice = event.data.object as Stripe.Invoice;
        const subId = typeof invoice.subscription === "string" ? invoice.subscription : invoice.subscription?.id;
        if (subId) {
          const sub = await s.subscriptions.retrieve(subId);
          await syncSubscription(db, s, sub);
        }
        break;
      }
      case "charge.refunded": {
        const charge = event.data.object as Stripe.Charge;
        const piId = typeof charge.payment_intent === "string" ? charge.payment_intent : charge.payment_intent?.id;
        if (piId && charge.refunded) {
          await db
            .from("purchases")
            .update({ status: "refunded", refunded_at: new Date().toISOString() })
            .eq("stripe_payment_intent_id", piId);
        }
        break;
      }
      case "account.updated": {
        const account = event.data.object as Stripe.Account;
        const complete = !!account.details_submitted && !!account.charges_enabled;
        await db
          .from("profiles")
          .update({ connect_onboarding_complete: complete })
          .eq("stripe_connect_account_id", account.id);
        break;
      }
      default:
        break; // acknowledged but not our concern
    }
  } catch (e) {
    // Non-2xx → Stripe retries. The stripe_events row blocks re-processing of anything that DID
    // complete, so remove the ledger row to allow a clean retry of this failed handler.
    await db.from("stripe_events").delete().eq("id", event.id);
    return new Response(`handler error: ${e instanceof Error ? e.message : "unknown"}`, { status: 500 });
  }

  return new Response(JSON.stringify({ received: true }), { status: 200 });
});

async function recordPurchase(db: ReturnType<typeof admin>, p: {
  programId: number; buyerId: string; creatorId: string;
  grossCents: number; feeCents: number; currency: string; paymentIntentId: string;
}) {
  if (!Number.isInteger(p.programId) || !p.buyerId || !p.creatorId || !p.paymentIntentId) return;
  // Unique stripe_payment_intent_id makes this a no-op when both checkout.session.completed and
  // payment_intent.succeeded arrive (either order) — never a double credit.
  await db.from("purchases").upsert({
    buyer_id: p.buyerId,
    program_id: p.programId,
    creator_id: p.creatorId,
    gross_amount_cents: p.grossCents,
    platform_fee_cents: p.feeCents,
    creator_earnings_cents: p.grossCents - p.feeCents,
    currency: p.currency,
    stripe_payment_intent_id: p.paymentIntentId,
    status: "paid",
  }, { onConflict: "stripe_payment_intent_id", ignoreDuplicates: true });
}

async function syncSubscription(db: ReturnType<typeof admin>, s: Stripe, sub: Stripe.Subscription) {
  // Resolve the app user: subscription metadata first, then the customer's profile row.
  let userId = sub.metadata?.user_id ?? null;
  if (!userId) {
    const customerId = typeof sub.customer === "string" ? sub.customer : sub.customer?.id;
    if (customerId) {
      const { data } = await db.from("profiles").select("id").eq("stripe_customer_id", customerId).maybeSingle();
      userId = data?.id ?? null;
    }
  }
  if (!userId) return;

  await db.from("creator_subscriptions").upsert({
    user_id: userId,
    stripe_subscription_id: sub.id,
    status: sub.status,
    current_period_end: sub.current_period_end ? new Date(sub.current_period_end * 1000).toISOString() : null,
    cancel_at_period_end: !!sub.cancel_at_period_end,
    updated_at: new Date().toISOString(),
  }, { onConflict: "user_id" });

  // Derive the badge server-side — the client never asserts it.
  const verified = ["active", "trialing"].includes(sub.status);
  const { data: profile } = await db.from("profiles").select("verified_since").eq("id", userId).single();
  await db.from("profiles").update({
    is_verified_creator: verified,
    verified_since: verified ? (profile?.verified_since ?? new Date().toISOString()) : profile?.verified_since ?? null,
  }).eq("id", userId);

  // Lapsed subscription → their listings come down until they resubscribe (spec: only active
  // subscribers may sell). Re-publishing after renewal is one tap in the app.
  if (!verified) {
    await db.from("marketplace_programs")
      .update({ status: "unpublished", updated_at: new Date().toISOString() })
      .eq("creator_id", userId)
      .eq("status", "published");
  }
}
