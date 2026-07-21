// creator-checkout — start a Stripe Checkout session for the Verified Creator subscription
// (A$2.00/month, price id from STRIPE_CREATOR_PRICE_ID). Web-only flow: the Android app never
// calls this and never links to it (Google Play policy). Returns { url } to redirect to.
//
// Auth: caller's JWT. The Stripe customer is created lazily and stored on profiles.
// Deploy: npx supabase functions deploy creator-checkout

import { admin, gate, json, requireUser, siteUrl, stripe } from "../_shared/creator.ts";

Deno.serve(async (req) => {
  const early = gate(req);
  if (early) return early;

  const db = admin();
  const uid = await requireUser(req, db);
  if (!uid) return json(401, { error: "sign in first" });

  const { data: profile } = await db
    .from("profiles")
    .select("stripe_customer_id, username")
    .eq("id", uid)
    .single();

  const s = stripe();

  // Existing active/trialing subscription → nothing to buy; send them to manage instead.
  const { data: sub } = await db
    .from("creator_subscriptions")
    .select("status")
    .eq("user_id", uid)
    .maybeSingle();
  if (sub && ["active", "trialing"].includes(sub.status)) {
    return json(409, { error: "already subscribed" });
  }

  let customerId: string | null = profile?.stripe_customer_id ?? null;
  if (!customerId) {
    const { data: userRes } = await db.auth.admin.getUserById(uid);
    const customer = await s.customers.create({
      email: userRes?.user?.email ?? undefined,
      metadata: { user_id: uid },
    });
    customerId = customer.id;
    await db.from("profiles").update({ stripe_customer_id: customerId }).eq("id", uid);
  }

  const session = await s.checkout.sessions.create({
    mode: "subscription",
    customer: customerId,
    line_items: [{ price: Deno.env.get("STRIPE_CREATOR_PRICE_ID")!, quantity: 1 }],
    subscription_data: { metadata: { user_id: uid } },
    metadata: { user_id: uid, kind: "creator_subscription" },
    success_url: siteUrl("/checkout-success.html?kind=subscription"),
    cancel_url: siteUrl("/creator.html?cancelled=1"),
  });

  return json(200, { url: session.url });
});
