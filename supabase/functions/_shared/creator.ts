// Shared helpers for the Verified Creator / marketplace Edge Functions.
//
// Env (set via `npx supabase secrets set` or the dashboard — see docs/payments.md):
//   STRIPE_SECRET_KEY        sk_… (server-side ONLY; never ships in the app or site)
//   STRIPE_WEBHOOK_SECRET    whsec_… (stripe-webhook only)
//   STRIPE_CREATOR_PRICE_ID  price_… for the A$2.00/month Verified Creator subscription
//   SITE_BASE_URL            e.g. https://willdodds7-arch.github.io/wildodds-gymtracker
//   PLATFORM_FEE_PERCENT     default "10"
//   CONNECT_DEFAULT_COUNTRY  default "AU"
// SUPABASE_URL / SUPABASE_SERVICE_ROLE_KEY are injected by Supabase automatically.

import Stripe from "https://esm.sh/stripe@14.25.0?target=denonext";
import { createClient, SupabaseClient } from "https://esm.sh/@supabase/supabase-js@2";

export const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

export function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS, "Content-Type": "application/json" },
  });
}

export function admin(): SupabaseClient {
  return createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
    { auth: { autoRefreshToken: false, persistSession: false } },
  );
}

export function stripe(): Stripe {
  return new Stripe(Deno.env.get("STRIPE_SECRET_KEY")!, {
    apiVersion: "2023-10-16",
    httpClient: Stripe.createFetchHttpClient(),
  });
}

/** Resolve the calling user from their JWT, or null. Never trust ids in the request body. */
export async function requireUser(req: Request, db: SupabaseClient): Promise<string | null> {
  const jwt = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
  if (!jwt) return null;
  const { data, error } = await db.auth.getUser(jwt);
  if (error || !data?.user) return null;
  return data.user.id;
}

export function feePercent(): number {
  const raw = Number(Deno.env.get("PLATFORM_FEE_PERCENT") ?? "10");
  return Number.isFinite(raw) && raw > 0 && raw < 100 ? raw : 10;
}

/** Platform fee in cents: percentage of gross, rounded half-up. Mirrors FeeMath.kt in the app. */
export function platformFeeCents(grossCents: number, pct: number = feePercent()): number {
  return Math.round((grossCents * pct) / 100);
}

export function siteUrl(path: string): string {
  const base = (Deno.env.get("SITE_BASE_URL") ?? "").replace(/\/$/, "");
  return `${base}${path}`;
}

/** Standard preamble: OPTIONS/CORS + POST-only. Returns a Response to short-circuit, or null. */
export function gate(req: Request): Response | null {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "POST") return json(405, { error: "method not allowed" });
  return null;
}
