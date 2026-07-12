// delete-account — Phase 5 account lifecycle.
//
// Deletes the caller's account and ALL their data, immediately and irreversibly (no grace period).
// Runs with the service-role key (injected by Supabase into the function env) so it can remove the
// auth.users row, which a client cannot. Deleting that row cascades to every owned table
// (profiles / sync_rows / analytics_events all have `on delete cascade` from auth.users); we also
// delete each explicitly first for defence-in-depth and so the effect is obvious in the code.
//
// Auth: the caller's JWT is taken from the Authorization header and verified; a user can only ever
// delete THEMSELVES (the uid comes from their token, never from the request body).
//
// Rate limit: a tiny in-memory per-instance limiter — a backstop against accidental rapid calls,
// not a security control (the operation is idempotent and self-scoped anyway).
//
// Deploy (no Docker needed): `npx supabase functions deploy delete-account --project-ref <ref>`.

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

// Per-instance rate limit: max 3 delete calls per uid per minute.
const recentCalls = new Map<string, number[]>();
function rateLimited(uid: string): boolean {
  const now = Date.now();
  const hits = (recentCalls.get(uid) ?? []).filter((t) => now - t < 60_000);
  hits.push(now);
  recentCalls.set(uid, hits);
  return hits.length > 3;
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "POST") {
    return new Response(JSON.stringify({ error: "method not allowed" }), {
      status: 405,
      headers: { ...CORS, "Content-Type": "application/json" },
    });
  }

  const authHeader = req.headers.get("Authorization") ?? "";
  const jwt = authHeader.replace(/^Bearer\s+/i, "");
  if (!jwt) {
    return new Response(JSON.stringify({ error: "missing bearer token" }), {
      status: 401,
      headers: { ...CORS, "Content-Type": "application/json" },
    });
  }

  const admin = createClient(SUPABASE_URL, SERVICE_ROLE_KEY, {
    auth: { autoRefreshToken: false, persistSession: false },
  });

  // Resolve the caller from their own token — never trust a uid in the body.
  const { data: userData, error: userErr } = await admin.auth.getUser(jwt);
  const uid = userData?.user?.id;
  if (userErr || !uid) {
    return new Response(JSON.stringify({ error: "invalid token" }), {
      status: 401,
      headers: { ...CORS, "Content-Type": "application/json" },
    });
  }

  if (rateLimited(uid)) {
    return new Response(JSON.stringify({ error: "rate limited" }), {
      status: 429,
      headers: { ...CORS, "Content-Type": "application/json" },
    });
  }

  // Explicit deletes first (defence-in-depth; the auth.users delete would cascade anyway).
  await admin.from("analytics_events").delete().eq("user_id", uid);
  await admin.from("sync_rows").delete().eq("user_id", uid);
  await admin.from("profiles").delete().eq("id", uid);

  // The account itself — this cascade guarantees nothing owned by uid survives.
  const { error: delErr } = await admin.auth.admin.deleteUser(uid);
  if (delErr) {
    return new Response(JSON.stringify({ error: delErr.message }), {
      status: 500,
      headers: { ...CORS, "Content-Type": "application/json" },
    });
  }

  return new Response(JSON.stringify({ deleted: true }), {
    status: 200,
    headers: { ...CORS, "Content-Type": "application/json" },
  });
});
