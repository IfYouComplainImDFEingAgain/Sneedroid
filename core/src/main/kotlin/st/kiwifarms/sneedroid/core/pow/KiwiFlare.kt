package st.kiwifarms.sneedroid.core.pow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The two on-wire proof-of-work variants. `TTRS` is "Tartarus".
 * Determined by whether the root `<html>` element has `id="sssg"` or `id="ttrs"`.
 */
enum class PowVariant { SSSG, TTRS }

/**
 * A KiwiFlare / Tartarus proof-of-work challenge, as scraped from `GET /`.
 *
 * @param salt       the challenge string concatenated before the nonce
 * @param difficulty the number of leading zero **bits** required in the SHA-256 hash
 * @param variant    which submission endpoint/format to use
 * @param patience   max time the solver may run before giving up
 */
data class KiwiFlareChallenge(
    val salt: String,
    val difficulty: Int,
    val variant: PowVariant,
    val patience: Duration = 5.minutes,
)

/** A solved challenge: the winning nonce for [salt]. */
data class KiwiFlareSolution(val salt: String, val nonce: Long)

/**
 * Port of the C# bot's `KiwiFlare` PoW
 * (`KfChatDotNetBot/Services/KiwiFlare.cs`), itself adapted from `y-a-t-s/firebird`.
 *
 * Algorithm: find a 64-bit nonce such that the SHA-256 of the UTF-8 bytes of
 * `"$salt$nonce"` has at least [KiwiFlareChallenge.difficulty] leading zero bits,
 * counted **MSB-first within each byte**.
 */
object KiwiFlare {

    /**
     * Returns true iff the first [difficulty] bits of [hash] are all zero,
     * most-significant-bit first within each byte. Mirrors `TestHash` in the C# bot.
     */
    fun testHash(hash: ByteArray, difficulty: Int): Boolean {
        require(difficulty >= 0)
        require(difficulty <= hash.size * 8) { "difficulty exceeds hash width" }
        for (i in 0 until difficulty) {
            val byteIndex = i / 8
            val bitIndex = 7 - (i % 8) // MSB first within each byte
            if ((hash[byteIndex].toInt() and (1 shl bitIndex)) != 0) return false
        }
        return true
    }

    /**
     * Single-threaded solve. Starts at [startNonce] (defaults to a caller-supplied
     * value so tests are deterministic) and increments until a nonce satisfies the
     * difficulty. Cooperatively cancellable via the surrounding coroutine, and via
     * [shouldStop] for non-coroutine callers/tests.
     */
    suspend fun solveFrom(
        challenge: KiwiFlareChallenge,
        startNonce: Long,
        shouldStop: () -> Boolean = { false },
    ): Long {
        val md = MessageDigest.getInstance("SHA-256")
        val saltBytes = challenge.salt.toByteArray(Charsets.UTF_8)
        // The server rejects a negative nonce (HTTP 500), so never search negative space.
        var nonce = startNonce.coerceAtLeast(0)
        var counter = 0
        while (true) {
            nonce++
            md.update(saltBytes)
            md.update(nonce.toString().toByteArray(Charsets.UTF_8))
            if (testHash(md.digest(), challenge.difficulty)) return nonce
            // digest() already reset the MessageDigest for the next round.
            if (++counter and 0x3FFF == 0) {
                if (shouldStop()) throw CancellationException("PoW stopped")
                coroutineContext.ensureActive()
            }
        }
    }

    /**
     * Parallel solve across [workers] coroutines (default: all CPUs). Each worker
     * starts from a disjoint nonce base and strides; the first to find a valid nonce
     * wins and the rest are cancelled. Returns a [KiwiFlareSolution].
     *
     * @param baseNonce random base; pass a fixed value in tests for determinism.
     */
    suspend fun solve(
        challenge: KiwiFlareChallenge,
        baseNonce: Long,
        workers: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
    ): KiwiFlareSolution = coroutineScope {
        // The server rejects a negative nonce (HTTP 500), so never search negative space.
        val start = baseNonce.coerceAtLeast(0)
        // Each worker scans a strided subsequence so no two workers test the same nonce.
        val deferreds = (0 until workers).map { w ->
            async(Dispatchers.Default) {
                val md = MessageDigest.getInstance("SHA-256")
                val saltBytes = challenge.salt.toByteArray(Charsets.UTF_8)
                var nonce = start + w
                var counter = 0
                while (isActive) {
                    md.update(saltBytes)
                    md.update(nonce.toString().toByteArray(Charsets.UTF_8))
                    if (testHash(md.digest(), challenge.difficulty)) return@async nonce
                    nonce += workers
                    if (++counter and 0x3FFF == 0) coroutineContext.ensureActive()
                }
                null
            }
        }
        // Whichever worker finishes first with a non-null result wins.
        val winner = select<Long?> {
            deferreds.forEach { d -> d.onAwait { it } }
        } ?: deferreds.awaitAll().filterNotNull().first()
        deferreds.forEach { it.cancel() }
        KiwiFlareSolution(challenge.salt, winner)
    }
}
