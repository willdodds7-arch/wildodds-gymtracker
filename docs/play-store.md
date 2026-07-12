# Google Play — compliance worksheet

Master worksheet for the Play Console. Sections marked **TODO (Phase 7)** are filled in when the
release engineering phase lands; the account-deletion pieces below are required now (Phase 5) and
are done.

## Required URLs

| Purpose | URL | Status |
|---|---|---|
| Account deletion (public) | `https://willdodds7-arch.github.io/wildodds-gymtracker/account-deletion.html` | live once the Pages workflow runs (enable Pages: repo Settings → Pages → Source = GitHub Actions) |
| Privacy Policy | `https://willdodds7-arch.github.io/wildodds-gymtracker/privacy.html` | stub live; full text in Phase 6 |
| Support | `https://willdodds7-arch.github.io/wildodds-gymtracker/support.html` | stub live |

> Enable Pages once: GitHub repo → **Settings → Pages → Build and deployment → Source: GitHub
> Actions**. The `pages.yml` workflow then publishes `/site` on every push that touches it. Also
> replace the `REPLACE-ME.example` contact placeholders in the site pages with a real support
> address before submitting to Play.

## Account deletion (Play "Data deletion" policy) — DONE

- **In-app:** Settings → Account → **Delete account** (type `DELETE` + re-enter password →
  `delete-account` Edge Function → immediate, irreversible; optional local-data wipe).
- **Web (for users without the app):** the account-deletion URL above, with an email request path.
- **What's deleted:** account (auth.users), profile, synced training data, analytics events —
  the auth.users delete cascades every owned table. No grace period; stated plainly in both paths.

## Data Safety — TODO (Phase 7)

Full datum-by-datum table (collected/shared, purpose, optional?, encrypted in transit, deletable)
is written in Phase 7. Inputs already true: account data (required), analytics (optional, consent-
gated, no PII), all over HTTPS, all deletable via the flows above.

## Remaining Play items — TODO (Phase 7)

Health-apps declaration, IARC content rating, target-audience (13+) rationale, reviewer app-access
instructions, store listing copy + screenshot list, signed AAB / keystore procedure, target/compile
SDK at Play's current requirement.
