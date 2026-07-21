package com.wildodds.gymtracker.ui.legal

/**
 * The bundled legal documents. The [asset] is the filename as it lands in the APK's assets: the
 * build maps the contents of the repo-root `/legal` directory to the assets root (see
 * app/build.gradle.kts sourceSets), so these are bare filenames, not under a `legal/` subfolder.
 * Offline — read from assets, never fetched.
 */
enum class LegalDoc(val key: String, val title: String, val asset: String) {
  PRIVACY("privacy", "Privacy Policy", "privacy-policy.md"),
  TERMS("terms", "Terms of Service", "terms-of-service.md"),
  HEALTH("health", "Health & Fitness Data Notice", "health-data-notice.md"),
  LICENSES("licenses", "Open-Source Licenses", "open-source-licenses.md"),
  SUPPORT("support", "Support", "support.md"),
  CREATOR_AGREEMENT("creator_agreement", "Creator Agreement", "creator-agreement.md"),
  BILLING("billing", "Subscription & Billing Terms", "subscription-billing-terms.md"),
  REFUNDS("refunds", "Refunds Policy", "refunds-policy.md");

  companion object {
    fun byKey(key: String?): LegalDoc? = entries.firstOrNull { it.key == key }
  }
}
