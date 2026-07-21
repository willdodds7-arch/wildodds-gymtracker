// billing-portal — a Stripe Billing Portal session for the caller (cancel/resume the Verified
// Creator subscription, update card, view invoices). Cancellation via the portal sets
// cancel_at_period_end; access continues to the end of the paid period (ACL-friendly).
// Returns { url }. Deploy: npx supabase functions deploy billing-portal

import { admin, gate, json, requireUser, siteUrl, stripe } from "../_shared/creator.ts";

Deno.serve(async (req) => {
  const early = gate(req);
  if (early) return early;

  const db = admin();
  const uid = await requireUser(req, db);
  if (!uid) return json(401, { error: "sign in first" });

  const { data: profile } = await db
    .from("profiles")
    .select("stripe_customer_id")
    .eq("id", uid)
    .single();
  if (!profile?.stripe_customer_id) return json(404, { error: "no billing account yet" });

  const session = await stripe().billingPortal.sessions.create({
    customer: profile.stripe_customer_id,
    return_url: siteUrl("/creator.html"),
  });
  return json(200, { url: session.url });
});
