package com.wildodds.gymtracker.data.creator

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Pricing shown in-app for the Verified Creator tier. Display-only — the AUTHORITATIVE price is
 * the Stripe Price object (STRIPE_CREATOR_PRICE_ID); keep these in sync with it and with the
 * website's config (site/build_site.py CONFIG_JS). Centralised so copy is never hard-coded in
 * screens and can change in one place.
 *
 * IMPORTANT (Google Play policy): the app displays this information but must never link to, or
 * direct users toward, the web checkout. Purchase copy in-app is limited to "not available in
 * the app" phrasing — see CreatorHubScreen.
 */
object PricingConfig {
  const val MONTHLY_PRICE_CENTS = 200
  const val CURRENCY = "AUD"
  const val PLATFORM_FEE_PERCENT = 10

  fun monthlyDisplay(): String = "A$" + "%.2f".format(MONTHLY_PRICE_CENTS / 100.0)

  /**
   * Daily-cost framing, computed with ceil so it can never understate the true cost
   * (A$2.00 / 30 ≈ 6.7¢ → "less than 7¢ a day"). §4 rule: never a rounded-DOWN daily figure.
   */
  fun dailyCentsCeil(): Int = ceil(MONTHLY_PRICE_CENTS / 30.0).toInt()
  fun dailyCopy(): String = "less than ${dailyCentsCeil()}¢ a day"

  /** The Australian-Consumer-Law disclosure that must sit beside any subscription mention. */
  fun disclosure(): String =
    "${monthlyDisplay()} per month, billed monthly in $CURRENCY. Auto-renews every month until " +
      "cancelled; cancel any time and keep access until the end of the paid period. " +
      "Prices include GST where applicable."
}

/** The 90/10 split, in one place — mirrored by _shared/creator.ts on the server. */
object FeeMath {
  /** Platform fee in cents: [pct]% of [grossCents], rounded half-up. */
  fun platformFeeCents(grossCents: Int, pct: Int = PricingConfig.PLATFORM_FEE_PERCENT): Int =
    (grossCents * pct / 100.0).roundToInt()

  fun creatorEarningsCents(grossCents: Int, pct: Int = PricingConfig.PLATFORM_FEE_PERCENT): Int =
    grossCents - platformFeeCents(grossCents, pct)
}

/**
 * Client-side mirror of the publish gate — for enabling/explaining UI only. The REAL gate runs
 * server-side in the publish-program Edge Function; nothing the client asserts is trusted.
 */
object CreatorGate {
  val ACTIVE_STATUSES = setOf("active", "trialing")

  fun isVerified(subStatus: String?): Boolean = subStatus in ACTIVE_STATUSES

  fun canPublish(subStatus: String?, connectComplete: Boolean, agreementAccepted: Boolean): Boolean =
    isVerified(subStatus) && connectComplete && agreementAccepted

  /** The first missing step, as user-facing copy — or null when ready to publish. */
  fun blockedReason(subStatus: String?, connectComplete: Boolean, agreementAccepted: Boolean): String? = when {
    !isVerified(subStatus) -> "Verified Creator subscription needed"
    !agreementAccepted -> "Creator Agreement not accepted yet"
    !connectComplete -> "Payout setup with Stripe not finished"
    else -> null
  }
}

// ── Server rows ────────────────────────────────────────────────────────────────

@Serializable
data class CreatorSubscriptionRow(
  @SerialName("user_id") val userId: String,
  val status: String,
  @SerialName("current_period_end") val currentPeriodEnd: String? = null,
  @SerialName("cancel_at_period_end") val cancelAtPeriodEnd: Boolean = false
)

@Serializable
data class CreatorProfileRow(
  @SerialName("is_verified_creator") val isVerifiedCreator: Boolean = false,
  @SerialName("verified_since") val verifiedSince: String? = null,
  @SerialName("connect_onboarding_complete") val connectOnboardingComplete: Boolean = false,
  @SerialName("creator_agreement_accepted_at") val creatorAgreementAcceptedAt: String? = null
)

@Serializable
data class MarketplaceListing(
  val id: Long,
  @SerialName("creator_id") val creatorId: String,
  val title: String,
  val description: String = "",
  @SerialName("price_cents") val priceCents: Int,
  val currency: String = "aud",
  val status: String = "draft",
  @SerialName("days_per_week") val daysPerWeek: Int = 0,
  @SerialName("total_weeks") val totalWeeks: Int = 0
) {
  fun priceDisplay(): String = "A$" + "%.2f".format(priceCents / 100.0)
}

@Serializable
data class PurchaseRow(
  val id: Long,
  @SerialName("buyer_id") val buyerId: String,
  @SerialName("program_id") val programId: Long,
  @SerialName("creator_id") val creatorId: String,
  @SerialName("gross_amount_cents") val grossAmountCents: Int,
  @SerialName("platform_fee_cents") val platformFeeCents: Int,
  @SerialName("creator_earnings_cents") val creatorEarningsCents: Int,
  val status: String = "paid",
  @SerialName("created_at") val createdAt: String? = null
)

@Serializable
data class CreatorPublicRow(
  val id: String,
  val username: String? = null,
  @SerialName("is_verified_creator") val isVerifiedCreator: Boolean = false
)

@Serializable
data class MarketplaceContentRow(
  @SerialName("program_id") val programId: Long,
  @SerialName("program_json") val programJson: String
)

// ── Aggregates for the UI ─────────────────────────────────────────────────────

data class CreatorStatus(
  val signedIn: Boolean,
  val subStatus: String? = null,
  val cancelAtPeriodEnd: Boolean = false,
  val currentPeriodEnd: String? = null,
  val isVerified: Boolean = false,
  val connectComplete: Boolean = false,
  val agreementAccepted: Boolean = false
)

data class EarningsSummary(
  val salesCount: Int,
  val refundedCount: Int,
  val grossCents: Int,
  val platformFeeCents: Int,
  val netCents: Int
) {
  companion object {
    fun from(purchases: List<PurchaseRow>): EarningsSummary {
      val paid = purchases.filter { it.status == "paid" }
      return EarningsSummary(
        salesCount = paid.size,
        refundedCount = purchases.count { it.status == "refunded" },
        grossCents = paid.sumOf { it.grossAmountCents },
        platformFeeCents = paid.sumOf { it.platformFeeCents },
        netCents = paid.sumOf { it.creatorEarningsCents }
      )
    }
  }
}
