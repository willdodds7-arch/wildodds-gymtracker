# Google Play — Data Safety form answers

These answers reflect the shipped app (offline-first, no backend). Confirm each in the Play Console
before submitting.

## Data collection & sharing
- **Does your app collect or share any of the required user data types?** → **No.**
  The app has no backend and no analytics uploader. Training data stays in a local database; the
  "usage statistics" setting only writes events to a local table that is never transmitted.
- **Does your app collect data?** → No.
- **Does your app share data with third parties?** → No, with one user-initiated caveat noted below.

## Security
- **Is all data encrypted in transit?** → Not applicable to app-collected data (the app collects
  none). The optional AI request the user initiates uses HTTPS.
- **Can users request data deletion?** → Yes — data is on-device; uninstalling or clearing app
  storage removes it, and in-app controls delete programs/logs.

## The AI Import caveat (declare in the listing / policy, not as app-collected data)
Google's Data Safety form is about data **your app** collects/shares. The AI-assisted import does not
send data to **you**; it lets the **user** send content they choose to a third-party AI provider
(Anthropic) using **the user's own API key**. This is a user-configured, opt-in integration:
- It is **off** unless the user adds their own key and uses the AI path (the default importer is
  on-device).
- Disclose it plainly in the store description and privacy policy (done). Do **not** declare it as
  data your app collects, because your app/servers never receive it.

## Health data
- If Health Connect is enabled by the user, the app reads granted health data (e.g. heart rate) and
  uses it **only on-device**. It is not collected or shared by the app. Ensure the Health Connect /
  health-permissions declarations and the in-app Health & Fitness Data Notice are attached as
  required by Play's Health apps policy.

## Account & ads
- **Account required?** No.
- **Ads?** No.
- **In-app purchases?** No.

## Content rating
- Complete the IARC questionnaire: no user-generated content sharing, no ads, no purchases → expect
  "Everyone / PEGI 3".
