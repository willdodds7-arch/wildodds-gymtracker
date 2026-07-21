package com.wildodds.gymtracker.data.creator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 90/10 split, pricing-display rules, and the publish gate — pure logic. */
class CreatorLogicTest {

  // ── FeeMath: the 10% platform / 90% creator split ────────────────────────────

  @Test
  fun `fee plus earnings always equals gross`() {
    for (gross in intArrayOf(100, 250, 999, 1000, 1234, 4999, 50000)) {
      val fee = FeeMath.platformFeeCents(gross)
      val net = FeeMath.creatorEarningsCents(gross)
      assertEquals("split must be lossless for $gross", gross, fee + net)
    }
  }

  @Test
  fun `fee is 10 percent rounded half-up`() {
    assertEquals(100, FeeMath.platformFeeCents(1000))   // A$10.00 → A$1.00
    assertEquals(25, FeeMath.platformFeeCents(250))     // A$2.50 → A$0.25
    assertEquals(100, FeeMath.platformFeeCents(999))    // 99.9 → 100
    assertEquals(12, FeeMath.platformFeeCents(123))     // 12.3 → 12
    assertEquals(13, FeeMath.platformFeeCents(125))     // 12.5 → 13 (half-up)
  }

  @Test
  fun `creator keeps 90 percent`() {
    assertEquals(900, FeeMath.creatorEarningsCents(1000))
    assertEquals(4500, FeeMath.creatorEarningsCents(5000))
  }

  // ── PricingConfig: §4 display rules ──────────────────────────────────────────

  @Test
  fun `monthly display is A$2 00`() {
    assertEquals("A$2.00", PricingConfig.monthlyDisplay())
  }

  @Test
  fun `daily figure never understates the true cost`() {
    // True daily cost at 30 days = 6.67¢; the ceil'd figure must be >= that.
    val trueDaily = PricingConfig.MONTHLY_PRICE_CENTS / 30.0
    assertTrue(PricingConfig.dailyCentsCeil() >= trueDaily)
    assertEquals(7, PricingConfig.dailyCentsCeil())
    assertEquals("less than 7¢ a day", PricingConfig.dailyCopy())
  }

  @Test
  fun `disclosure states monthly price, auto-renewal and cancellation`() {
    val d = PricingConfig.disclosure()
    assertTrue("must state the true monthly price", d.contains("A$2.00 per month"))
    assertTrue("must state auto-renewal", d.contains("Auto-renews", ignoreCase = true))
    assertTrue("must state cancellation", d.contains("cancel", ignoreCase = true))
  }

  // ── CreatorGate: badge + publish gating ──────────────────────────────────────

  @Test
  fun `verified only while active or trialing`() {
    assertTrue(CreatorGate.isVerified("active"))
    assertTrue(CreatorGate.isVerified("trialing"))
    assertFalse(CreatorGate.isVerified("past_due"))
    assertFalse(CreatorGate.isVerified("canceled"))
    assertFalse(CreatorGate.isVerified("incomplete"))
    assertFalse(CreatorGate.isVerified(null))
  }

  @Test
  fun `publishing needs subscription AND connect AND agreement`() {
    assertTrue(CreatorGate.canPublish("active", connectComplete = true, agreementAccepted = true))
    assertFalse(CreatorGate.canPublish("active", connectComplete = false, agreementAccepted = true))
    assertFalse(CreatorGate.canPublish("active", connectComplete = true, agreementAccepted = false))
    assertFalse(CreatorGate.canPublish("canceled", connectComplete = true, agreementAccepted = true))
    assertFalse(CreatorGate.canPublish(null, connectComplete = false, agreementAccepted = false))
  }

  @Test
  fun `blocked reason explains the first missing step`() {
    assertNull(CreatorGate.blockedReason("active", true, true))
    assertEquals("Verified Creator subscription needed", CreatorGate.blockedReason(null, true, true))
    assertEquals("Creator Agreement not accepted yet", CreatorGate.blockedReason("active", false, false))
    assertEquals("Payout setup with Stripe not finished", CreatorGate.blockedReason("active", false, true))
  }

  // ── EarningsSummary ──────────────────────────────────────────────────────────

  @Test
  fun `earnings summary sums paid sales and counts refunds separately`() {
    fun row(id: Long, gross: Int, status: String) = PurchaseRow(
      id = id, buyerId = "b", programId = 1, creatorId = "c",
      grossAmountCents = gross,
      platformFeeCents = FeeMath.platformFeeCents(gross),
      creatorEarningsCents = FeeMath.creatorEarningsCents(gross),
      status = status
    )
    val summary = EarningsSummary.from(listOf(
      row(1, 1000, "paid"), row(2, 2500, "paid"), row(3, 1000, "refunded")
    ))
    assertEquals(2, summary.salesCount)
    assertEquals(1, summary.refundedCount)
    assertEquals(3500, summary.grossCents)
    assertEquals(350, summary.platformFeeCents)
    assertEquals(3150, summary.netCents)
    assertEquals(summary.grossCents, summary.platformFeeCents + summary.netCents)
  }
}
