package com.example.applocker.data

import android.content.Context
import android.util.Log
import java.security.MessageDigest
import java.util.Random
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Offline one-time support authorization.
 *
 * A per-device secret is generated on first use and stored in the app's private
 * prefs (accessible only to the app). To authorize an action (e.g. entering
 * Settings to change date / clear data), the device generates a random challenge
 * code. The user reads that code to support over the phone; support computes
 * HMAC-SHA256(secret, code) truncated to 6 decimal digits and reads it back;
 * the device validates locally. The challenge is single-use and expires.
 */
object SupportOtp {

    private const val TAG = "SupportOtp"
    private const val PREFS = "support_otp_prefs"
    private const val KEY_SECRET = "secret_hex"
    private const val KEY_CHALLENGE_CODE = "challenge_code"
    private const val KEY_CHALLENGE_EXPIRY = "challenge_expiry"
    private const val CHALLENGE_LEN = 8
    private const val ANSWER_LEN = 6
    private const val EXPIRY_MS = 5 * 60 * 1000L

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun getSecret(context: Context): String {
        val prefs = prefs(context)
        prefs.getString(KEY_SECRET, null)?.takeIf { it.isNotEmpty() }?.let { return it }
        val secret = Random().nextLong().toString(16) +
            Random().nextLong().toString(16) +
            java.lang.Long.toHexString(System.currentTimeMillis())
        prefs.edit().putString(KEY_SECRET, secret).apply()
        Log.i(TAG, "Generated new per-device support secret")
        return secret
    }

    /** Returns true if there is an active, unexpired challenge. */
    fun hasActiveChallenge(context: Context): Boolean {
        val prefs = prefs(context)
        val code = prefs.getString(KEY_CHALLENGE_CODE, null) ?: return false
        val expiry = prefs.getLong(KEY_CHALLENGE_EXPIRY, 0L)
        return code.isNotEmpty() && System.currentTimeMillis() < expiry
    }

    /**
     * Generates a new single-use challenge code (read to support) and returns it.
     * Invalidates any previous challenge.
     */
    fun newChallenge(context: Context): String {
        val sb = StringBuilder()
        val rnd = Random()
        repeat(CHALLENGE_LEN) { sb.append(rnd.nextInt(10)) }
        val code = sb.toString()
        prefs(context).edit()
            .putString(KEY_CHALLENGE_CODE, code)
            .putLong(KEY_CHALLENGE_EXPIRY, System.currentTimeMillis() + EXPIRY_MS)
            .apply()
        Log.i(TAG, "New support challenge generated")
        return code
    }

    /**
     * Validates the support answer for the current challenge, then invalidates it
     * (single-use). Returns true only if there is an active challenge and the
     * answer equals HMAC-SHA256(secret, code) truncated to 6 decimal digits.
     */
    fun validate(context: Context, answer: String): Boolean {
        val prefs = prefs(context)
        val code = prefs.getString(KEY_CHALLENGE_CODE, null) ?: return false
        val expiry = prefs.getLong(KEY_CHALLENGE_EXPIRY, 0L)
        if (code.isNullOrEmpty() || System.currentTimeMillis() >= expiry) {
            invalidate(context)
            return false
        }
        val expected = computeAnswer(getSecret(context), code)
        val ok = answer.trim() == expected
        invalidate(context)
        return ok
    }

    fun invalidate(context: Context) {
        prefs(context).edit()
            .remove(KEY_CHALLENGE_CODE)
            .remove(KEY_CHALLENGE_EXPIRY)
            .apply()
    }

    /**
     * answer = first ANSWER_LEN decimal digits of HMAC-SHA256(key=secret, msg=code)
     * Support replicates this formula offline.
     */
    fun computeAnswer(secret: String, code: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal(code.toByteArray(Charsets.UTF_8))
        // Convert the last 4 bytes of the digest to an integer < 10^ANSWER_LEN.
        val idx = digest.size - 4
        val value = ((digest[idx].toInt() and 0xFF) shl 24) or
                ((digest[idx + 1].toInt() and 0xFF) shl 16) or
                ((digest[idx + 2].toInt() and 0xFF) shl 8) or
                (digest[idx + 3].toInt() and 0xFF)
        val mod = value % Math.pow(10.0, ANSWER_LEN.toDouble()).toInt()
        return String.format("%0${ANSWER_LEN}d", mod)
    }
}
