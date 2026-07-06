# Privacy & compliance — pre-release checklist

Accounts (Phase 5) plus health data (Phase 4) and future social features (Phase 6+) create real
legal and store-policy obligations. The in-app **Settings → Privacy & data** screen is a
**placeholder** and says so. Complete the items below before publishing or syncing real user data.

## Must-have before public release
- [ ] **Real privacy policy + terms.** Written/reviewed, hosted at a stable URL, linked from the
      in-app Privacy screen (replace the placeholder text in `SettingsScreen.PrivacyPolicySheet`).
- [ ] **Data export.** Let a signed-in user download all their data (Room export already exists
      locally; add server-side export of synced rows). GDPR/CCPA "right to access / portability".
- [ ] **Account deletion.** In-app "delete my account" that erases all server-side rows (the
      `on delete cascade` FKs handle the DB; also delete the `auth.users` row) and signs out. Required
      by Google Play's account-deletion policy.
- [ ] **Health-data consent.** Explicit, separate opt-in **before** any Health Connect data leaves
      the device. Today HR/recovery data is local-only (Phase 4) — keep it that way until consent +
      a health-data section in the policy exist. Google Play "Health apps" + Health Connect policy.
- [ ] **Data inventory statement.** Exactly what is stored, where (device vs Supabase), and for how
      long; surfaced in the policy and the Play "Data safety" form.

## Should-have
- [ ] Email confirmation / password reset flows enabled in Supabase Auth.
- [ ] Rate-limiting / abuse protection on any social/sharing endpoints (Phase 6+).
- [ ] Re-confirm RLS coverage whenever a new table is added (every table: RLS on + owner policies).
- [ ] Audit third-party SDKs (Supabase, Health Connect, Coil, PdfBox) for their data handling in the
      Play "Data safety" disclosure.
- [ ] Decide data residency / region for the Supabase project if targeting EU users.

## Notes
- Anonymous/local mode must remain the default and fully functional with **none** of the above —
  these obligations attach only once an account or cloud sync is actually used.
