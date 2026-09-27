package gy.pig.spark

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger

/**
 * SSP amounts are parsed as whole, non-negative numbers with overflow-checked arithmetic.
 * `JSONObject.getLong` used to truncate decimals, parse strings and saturate huge values, and
 * `(msat + 999) / 1000` could then wrap to a negative fee that became a 1-sat fee.
 */
class SspAmountParsingTests {

    @Test
    fun onlyWholeNonNegativeNumbersAreAccepted() {
        assertEquals(0L, wholeNonNegativeLong(0))
        assertEquals(1_500L, wholeNonNegativeLong(1_500))
        assertEquals(Long.MAX_VALUE, wholeNonNegativeLong(Long.MAX_VALUE))
        assertEquals(42L, wholeNonNegativeLong(BigInteger.valueOf(42)))
        // Whole numbers written with a fraction or exponent, as Swift's exact `as? Int64` accepts.
        assertEquals(1_500L, wholeNonNegativeLong(1500.0))
        assertEquals(1_500L, wholeNonNegativeLong(BigDecimal("1.5e3")))

        // What Android's org.json yields for 1e19, 12.5 and 10000000000000000000: Double.
        assertNull(wholeNonNegativeLong(1.0E19))
        assertNull(wholeNonNegativeLong(12.5))
        assertNull(wholeNonNegativeLong(-1.0))
        assertNull(wholeNonNegativeLong(Double.NaN))
        assertNull(wholeNonNegativeLong(Double.POSITIVE_INFINITY))
        assertNull(wholeNonNegativeLong(9.007199254740994E15)) // beyond 2^53: no longer exact
        assertNull(wholeNonNegativeLong(-1))
        assertNull(wholeNonNegativeLong(-1L))
        assertNull(wholeNonNegativeLong("1000"))
        assertNull(wholeNonNegativeLong(true))
        assertNull(wholeNonNegativeLong(null))
        assertNull(wholeNonNegativeLong(BigInteger.ONE.shiftLeft(63)))
        assertNull(wholeNonNegativeLong(BigInteger.valueOf(-5)))
        assertNull(wholeNonNegativeLong(BigDecimal("12.5")))
        assertNull(wholeNonNegativeLong(BigDecimal("1e19")))
        assertNull(wholeNonNegativeLong(BigDecimal("-1")))
    }

    private fun lightningResponse(originalValue: String) =
        JSONObject("""{"lightning_send_fee_estimate":{"fee_estimate":{"original_value":$originalValue,"original_unit":"MILLISATOSHI"}}}""")

    @Test
    fun lightningFeeEstimatesRoundUpWholeMillisats() {
        assertEquals(2L, lightningFeeEstimateSats(lightningResponse("1500")))
        assertEquals(1L, lightningFeeEstimateSats(lightningResponse("1000")))
        assertEquals(0L, lightningFeeEstimateSats(lightningResponse("0")))
        assertEquals(2L, lightningFeeEstimateSats(lightningResponse("1500.0")))
        assertEquals(9_223_372_036_854_775L, lightningFeeEstimateSats(lightningResponse("9223372036854774808")))
    }

    @Test
    fun lightningFeeEstimatesThatAreNotWholeNonNegativeNumbersAreRefused() {
        for (value in listOf("1e19", "12.5", "-1", "\"1500\"", "10000000000000000000", "true", "null")) {
            val error = expectSparkError(value) { lightningFeeEstimateSats(lightningResponse(value)) }
            assertTrue(value, error is SparkError.InvalidResponse)
        }
        expectSparkError { lightningFeeEstimateSats(JSONObject("""{"lightning_send_fee_estimate":{}}""")) }
        expectSparkError { lightningFeeEstimateSats(JSONObject("{}")) }
    }

    @Test
    fun aLightningFeeEstimateWhoseRoundingWouldOverflowIsUntrusted() {
        // Long.MAX_VALUE msat: `+ 999` used to wrap negative and end up as a 1-sat fee.
        val error = expectSparkError { lightningFeeEstimateSats(lightningResponse(Long.MAX_VALUE.toString())) }
        assertTrue(error is SparkError.UntrustedResponse)
    }

    private fun withdrawalResponse(userFee: String, l1Fee: String) = JSONObject(
        """{"coop_exit_fee_estimates":{"speed_fast":{""" +
            """"user_fee":{"original_value":$userFee,"original_unit":"SATOSHI"},""" +
            """"l1_broadcast_fee":{"original_value":$l1Fee,"original_unit":"SATOSHI"}}}}""",
    )

    @Test
    fun withdrawalFeeEstimatesAreTheSumOfTwoWholeNonNegativeNumbers() {
        assertEquals(1_950L, withdrawalFeeEstimateSats(withdrawalResponse("1500", "450")))
        assertEquals(0L, withdrawalFeeEstimateSats(withdrawalResponse("0", "0")))

        for ((user, l1) in listOf("1e19" to "1", "12.5" to "1", "-1" to "1", "\"1500\"" to "1", "1" to "\"450\"", "1" to "-450")) {
            val error = expectSparkError("$user + $l1") { withdrawalFeeEstimateSats(withdrawalResponse(user, l1)) }
            assertTrue("$user + $l1", error is SparkError.InvalidResponse)
        }
        expectSparkError { withdrawalFeeEstimateSats(JSONObject("""{"coop_exit_fee_estimates":{"speed_fast":{}}}""")) }

        // Each part is in range, the sum is not: refused instead of wrapping negative.
        val overflow = expectSparkError { withdrawalFeeEstimateSats(withdrawalResponse(Long.MAX_VALUE.toString(), "1")) }
        assertTrue(overflow is SparkError.UntrustedResponse)
    }
}
