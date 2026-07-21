// publish-program — create/update/publish/unpublish a marketplace listing. This is the ONLY
// write path for marketplace_programs (clients have no insert/update policies), so the seller
// gates are enforced HERE, server-side, and cannot be asserted by a client:
//   publish requires  (subscription active/trialing) AND (Connect onboarding complete)
//                     AND (Creator Agreement accepted).
// Body: { action: "upsert" | "publish" | "unpublish",
//         program: { id?, title, description, price_cents, days_per_week, total_weeks, program_json } }
// Deploy: npx supabase functions deploy publish-program

import { admin, gate, json, requireUser } from "../_shared/creator.ts";

Deno.serve(async (req) => {
  const early = gate(req);
  if (early) return early;

  const db = admin();
  const uid = await requireUser(req, db);
  if (!uid) return json(401, { error: "sign in first" });

  let body: { action?: string; program?: Record<string, unknown> };
  try {
    body = await req.json();
  } catch {
    return json(400, { error: "invalid body" });
  }
  const action = body.action;
  if (!action || !["upsert", "publish", "unpublish"].includes(action)) {
    return json(400, { error: "action must be upsert | publish | unpublish" });
  }

  // Live entitlement check — never a cached/client flag.
  const [{ data: sub }, { data: profile }] = await Promise.all([
    db.from("creator_subscriptions").select("status").eq("user_id", uid).maybeSingle(),
    db.from("profiles")
      .select("connect_onboarding_complete, creator_agreement_accepted_at")
      .eq("id", uid)
      .single(),
  ]);
  const subActive = !!sub && ["active", "trialing"].includes(sub.status);

  if (action === "publish" || action === "upsert") {
    if (!subActive) return json(403, { error: "Verified Creator subscription required" });
  }
  if (action === "publish") {
    if (!profile?.connect_onboarding_complete) {
      return json(403, { error: "complete Stripe payout onboarding first" });
    }
    if (!profile?.creator_agreement_accepted_at) {
      return json(403, { error: "accept the Creator Agreement first" });
    }
  }

  const p = body.program ?? {};
  const id = p.id != null ? Number(p.id) : null;

  if (action === "unpublish" || action === "publish") {
    if (!Number.isInteger(id)) return json(400, { error: "program.id required" });
    const { data: row } = await db
      .from("marketplace_programs")
      .select("id, creator_id")
      .eq("id", id!)
      .single();
    if (!row || row.creator_id !== uid) return json(404, { error: "listing not found" });
    const { error } = await db
      .from("marketplace_programs")
      .update({ status: action === "publish" ? "published" : "unpublished", updated_at: new Date().toISOString() })
      .eq("id", id!);
    if (error) return json(500, { error: "update failed" });
    return json(200, { id, status: action === "publish" ? "published" : "unpublished" });
  }

  // upsert (draft metadata + content)
  const title = String(p.title ?? "").trim();
  const priceCents = Number(p.price_cents);
  const programJson = String(p.program_json ?? "");
  if (title.length < 3 || title.length > 80) return json(400, { error: "title must be 3–80 chars" });
  if (!Number.isInteger(priceCents) || priceCents < 100 || priceCents > 50000) {
    return json(400, { error: "price must be A$1.00–A$500.00" });
  }
  if (programJson.length < 2 || programJson.length > 500_000) {
    return json(400, { error: "program content missing or too large" });
  }

  const fields = {
    creator_id: uid,
    title,
    description: String(p.description ?? "").slice(0, 2000),
    price_cents: priceCents,
    days_per_week: Number(p.days_per_week ?? 0) || 0,
    total_weeks: Number(p.total_weeks ?? 0) || 0,
    updated_at: new Date().toISOString(),
  };

  let listingId = id;
  if (listingId != null) {
    const { data: row } = await db
      .from("marketplace_programs").select("creator_id").eq("id", listingId).single();
    if (!row || row.creator_id !== uid) return json(404, { error: "listing not found" });
    const { error } = await db.from("marketplace_programs").update(fields).eq("id", listingId);
    if (error) return json(500, { error: "update failed" });
  } else {
    const { data: inserted, error } = await db
      .from("marketplace_programs")
      .insert({ ...fields, status: "draft" })
      .select("id")
      .single();
    if (error || !inserted) return json(500, { error: "insert failed" });
    listingId = inserted.id;
  }

  const { error: contentErr } = await db
    .from("marketplace_program_content")
    .upsert({ program_id: listingId, program_json: programJson });
  if (contentErr) return json(500, { error: "content save failed" });

  return json(200, { id: listingId, status: "draft" });
});
