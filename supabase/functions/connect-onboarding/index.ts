// connect-onboarding — create (once) the caller's Stripe Connect Express account and return an
// onboarding Account Link URL. Stripe handles KYC/identity; payouts go to this account. The
// account.updated webhook flips profiles.connect_onboarding_complete when Stripe confirms
// details_submitted + charges_enabled — publishing stays blocked until then.
// Returns { url }. Deploy: npx supabase functions deploy connect-onboarding

import { admin, gate, json, requireUser, siteUrl, stripe } from "../_shared/creator.ts";

Deno.serve(async (req) => {
  const early = gate(req);
  if (early) return early;

  const db = admin();
  const uid = await requireUser(req, db);
  if (!uid) return json(401, { error: "sign in first" });

  // Payouts only make sense for (at least once) subscribed creators.
  const { data: sub } = await db
    .from("creator_subscriptions")
    .select("status")
    .eq("user_id", uid)
    .maybeSingle();
  if (!sub || !["active", "trialing"].includes(sub.status)) {
    return json(403, { error: "an active Verified Creator subscription is required first" });
  }

  const { data: profile } = await db
    .from("profiles")
    .select("stripe_connect_account_id")
    .eq("id", uid)
    .single();

  const s = stripe();
  let accountId: string | null = profile?.stripe_connect_account_id ?? null;
  if (!accountId) {
    const { data: userRes } = await db.auth.admin.getUserById(uid);
    const account = await s.accounts.create({
      type: "express",
      country: Deno.env.get("CONNECT_DEFAULT_COUNTRY") ?? "AU",
      email: userRes?.user?.email ?? undefined,
      metadata: { user_id: uid },
      capabilities: { transfers: { requested: true } },
    });
    accountId = account.id;
    await db.from("profiles").update({ stripe_connect_account_id: accountId }).eq("id", uid);
  }

  const link = await s.accountLinks.create({
    account: accountId,
    type: "account_onboarding",
    refresh_url: siteUrl("/creator.html?connect=refresh"),
    return_url: siteUrl("/creator.html?connect=return"),
  });
  return json(200, { url: link.url });
});
