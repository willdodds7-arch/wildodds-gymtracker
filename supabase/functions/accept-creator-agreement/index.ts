// accept-creator-agreement — records the caller's acceptance of the Creator Agreement (version +
// timestamp on their profiles row). Publishing is blocked server-side (publish-program) until
// this has been called. Body: { version: string }.
// Deploy: npx supabase functions deploy accept-creator-agreement

import { admin, gate, json, requireUser } from "../_shared/creator.ts";

Deno.serve(async (req) => {
  const early = gate(req);
  if (early) return early;

  const db = admin();
  const uid = await requireUser(req, db);
  if (!uid) return json(401, { error: "sign in first" });

  let version = "1.0";
  try {
    const body = await req.json();
    if (typeof body?.version === "string" && body.version.length <= 20) version = body.version;
  } catch { /* default version */ }

  const { error } = await db
    .from("profiles")
    .update({
      creator_agreement_accepted_at: new Date().toISOString(),
      creator_agreement_version: version,
    })
    .eq("id", uid);
  if (error) return json(500, { error: "failed to record acceptance" });
  return json(200, { accepted: true, version });
});
