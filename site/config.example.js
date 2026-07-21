// Runtime config for the marketplace/creator pages. The REAL config.js is generated at deploy
// time by site/build_site.py from the SUPABASE_URL / SUPABASE_ANON_KEY repo secrets (see
// pages.yml) — the anon key is public-by-design (RLS does the access control), but we still
// keep it out of the source tree. Pricing display strings live here so copy can change without
// touching page markup; the AUTHORITATIVE price is the Stripe Price object.
window.WO_CONFIG = {
  SUPABASE_URL: "https://YOUR-PROJECT.supabase.co",
  SUPABASE_ANON_KEY: "sb_publishable_...",
  // Display strings (keep consistent with the app's PricingConfig.kt):
  SUB_PRICE_MONTHLY: "A$2.00",
  SUB_DAILY_COPY: "less than 7¢ a day",
  SUB_DISCLOSURE: "A$2.00 per month, billed monthly in AUD. Your subscription auto-renews every month until cancelled. Cancel any time from Manage subscription below — you keep access until the end of the period you've paid for. Prices include GST where applicable.",
  PLATFORM_FEE_PERCENT: 10,
  CREATOR_AGREEMENT_VERSION: "1.0",
};
