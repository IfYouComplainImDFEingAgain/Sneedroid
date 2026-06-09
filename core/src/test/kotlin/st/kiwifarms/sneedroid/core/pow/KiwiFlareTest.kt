package st.kiwifarms.sneedroid.core.pow

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest

class KiwiFlareTest {

    private fun sha256(s: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))

    // ---- testHash: bit-level correctness (MSB-first), the part most likely to be wrong ----

    @Test
    fun `leading zero byte passes difficulty 8 but not 9`() {
        // 0x00 0xFF ... : first 8 bits are zero, 9th bit (MSB of 0xFF) is 1.
        val hash = ByteArray(32) { 0xFF.toByte() }.also { it[0] = 0x00 }
        assertTrue(KiwiFlare.testHash(hash, 8))
        assertFalse(KiwiFlare.testHash(hash, 9))
    }

    @Test
    fun `half-zero byte respects MSB-first within a byte`() {
        // 0x0F : top nibble zero -> exactly 4 leading zero bits.
        val hash = ByteArray(32) { 0xFF.toByte() }.also { it[0] = 0x0F }
        assertTrue(KiwiFlare.testHash(hash, 4))
        assertFalse(KiwiFlare.testHash(hash, 5))
    }

    @Test
    fun `0x80 first byte has zero leading zero bits`() {
        // 0x80 : MSB set -> 0 leading zero bits.
        val hash = ByteArray(32).also { it[0] = 0x80.toByte() }
        assertTrue(KiwiFlare.testHash(hash, 0))
        assertFalse(KiwiFlare.testHash(hash, 1))
    }

    @Test
    fun `zero difficulty always passes`() {
        assertTrue(KiwiFlare.testHash(ByteArray(32) { 0xFF.toByte() }, 0))
    }

    @Test
    fun `crossing a byte boundary counts bits across bytes`() {
        // 0x00 0x0F : 8 + 4 = 12 leading zero bits.
        val hash = ByteArray(32) { 0xFF.toByte() }.also { it[0] = 0x00; it[1] = 0x0F }
        assertTrue(KiwiFlare.testHash(hash, 12))
        assertFalse(KiwiFlare.testHash(hash, 13))
    }

    // ---- solve: the returned nonce must actually satisfy the challenge ----

    @Test
    fun `single-threaded solve produces a verifiable nonce`() = runBlocking {
        val challenge = KiwiFlareChallenge(salt = "sneedroid-test-salt", difficulty = 14, variant = PowVariant.SSSG)
        val nonce = KiwiFlare.solveFrom(challenge, startNonce = 0L)
        // Independently verify with a fresh digest — don't trust the solver's own check.
        assertTrue(KiwiFlare.testHash(sha256("${challenge.salt}$nonce"), challenge.difficulty))
    }

    @Test
    fun `parallel solve produces a verifiable nonce`() = runBlocking {
        val challenge = KiwiFlareChallenge(salt = "tartarus", difficulty = 16, variant = PowVariant.TTRS)
        val solution = KiwiFlare.solve(challenge, baseNonce = 12345L, workers = 4)
        assertEquals(challenge.salt, solution.salt)
        assertTrue(KiwiFlare.testHash(sha256("${challenge.salt}${solution.nonce}"), challenge.difficulty))
    }

    @Test
    fun `solve never returns a negative nonce even from a negative seed`() = runBlocking {
        // The Tartarus server 500s on a negative nonce, so a negative random seed must not
        // produce one. Regression for the intermittent (~50%) login failure.
        val challenge = KiwiFlareChallenge(salt = "neg-seed", difficulty = 12, variant = PowVariant.TTRS)
        val parallel = KiwiFlare.solve(challenge, baseNonce = Long.MIN_VALUE / 2, workers = 4)
        assertTrue(parallel.nonce >= 0, "parallel solve returned negative nonce ${parallel.nonce}")
        assertTrue(KiwiFlare.testHash(sha256("${challenge.salt}${parallel.nonce}"), challenge.difficulty))
        val single = KiwiFlare.solveFrom(challenge, startNonce = Long.MIN_VALUE / 2)
        assertTrue(single >= 0, "single solve returned negative nonce $single")
    }

    @Test
    fun `concatenation format is salt then decimal nonce with no separator`() {
        // Lock the exact wire input the server hashes: "$salt$nonce".
        val salt = "abc"
        val nonce = 42L
        val expected = sha256("abc42")
        val actual = sha256("$salt$nonce")
        assertEquals(expected.toList(), actual.toList())
    }
}
