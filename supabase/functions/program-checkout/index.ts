// program-checkout — Stripe Checkout for buying one marketplace program. Destination charge to
// the creator's Connect Express account with application_fee_amount = 10% of the price, so
// Stripe itself splits the money: 90% to the creator, 10% to the platform. Web-only (the app
// never links here — Google Play policy). Returns { url }.
// Deploy: npx supabase functions deploy program-checkout

import { admin, gate, json, platformFeeCents, requireUser, siteUrl, stripe } from "../_shared/creator.ts";

Deno.serve(async (req) => {
  const early = gate(req);
  if (early) return early;

  const db = admin();
  const uid = await requireUser(req, db);
  if (!uid) return json(401, { error: "sign in first" });

  let programId: number;
  try {
    const body = await req.json();
    programId = Number(body?.program_id);
    if (!Number.isInteger(programId)) throw new Error();
  } catch {
    return json(400, { error: "program_id required" });
  }

  const { data: program } = await db
    .from("marketplace_programs")
    .select("id, creator_id, title, price_cents, currency, status")
    .eq("id", programId)
    .single();
  if (!program || program.status !== "published") return json(404, { error: "program not found" });
  if (program.creator_id === uid) return json(400, { error: "that's your own program" });

  const { data: existing } = await db
    .from("purchases")
    .select("id")
    .eq("buyer_id", uid)
    .eq("program_id", programId)
    .eq("status", "paid")
    .maybeSingle();
  if (existing) return json(409, { error: "already purchased" });

  const { data: creator } = await db
    .from("profiles")
    .select("stripe_connect_account_id, connect_onboarding_complete")
    .eq("id", program.creator_id)
    .single();
  if (!creator?.stripe_connect_account_id || !creator.connect_onboarding_complete) {
    return json(409, { error: "creator payouts not set up" });
  }

  const fee = platformFeeCents(program.price_cents);
  const meta = {
    kind: "program_purchase",
    program_id: String(program.id),
    buyer_id: uid,
    creator_id: program.creator_id,
    platform_fee_cents: String(fee),
  };

  const session = await stripe().checkout.sessions.create({
    mode: "payment",
    line_items: [{
      quantity: 1,
      price_data: {
        currency: program.currency,
        unit_amount: program.price_cents,
        product_data: { name: program.title, description: "Wild Odds training program" },
      },
    }],
    payment_intent_data: {
      application_fee_amount: fee,
      transfer_data: { destination: creator.stripe_connect_account_id },
      metadata: meta,
    },
    metadata: meta,
    success_url: siteUrl("/checkout-success.html?kind=program"),
    cancel_url: siteUrl("/marketplace.html?cancelled=1"),
  });

  return json(200, { url: session.url });
});
