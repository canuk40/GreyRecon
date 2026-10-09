package com.greyrecon.app.engine.quality

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class QualityResult(
    val target: String,
    val method: Method,
    val sent: Int,
    val received: Int,
    val minMs: Double,
    val avgMs: Double,
    val maxMs: Double,
    /** Mean absolute difference between consecutive round trips -- what actually breaks calls and games. */
    val jitterMs: Double,
    val samples: List<Double>,
) {
    enum class Method { ICMP, TCP }

    val lossPercent: Double get() = if (sent == 0) 0.0 else (sent - received) * 100.0 / sent

    /** True when nothing answered at all -- silence, which is not the same as a bad connection. */
    val noResponse: Boolean get() = received == 0

    /**
     * A letter grade, because "38 ms, 4 ms jitter, 0% loss" is three numbers and "B" is a
     * decision. Loss is weighted hardest: a link that drops packets is broken in a way that a
     * merely slow one is not.
     */
    val grade: String
        get() = when {
            // Deliberately not "F". Plenty of healthy routers filter ICMP and serve no open TCP
            // port; grading those as a failing connection is simply wrong, and was observed on
            // the first real device this ran against.
            received == 0 -> "?"
            lossPercent >= 5 -> "F"
            lossPercent >= 1 -> "D"
            avgMs < 20 && jitterMs < 5 -> "A"
            avgMs < 50 && jitterMs < 15 -> "B"
            avgMs < 120 && jitterMs < 30 -> "C"
            else -> "D"
        }

    val summary: String
        get() = if (received == 0) {
            "$target did not answer. Many routers filter ping and expose no open port -- this usually means silent, not broken."
        } else {
            "%.0f ms avg · %.0f ms jitter · %.0f%% loss".format(avgMs, jitterMs, lossPercent)
        }
}

/**
 * Measures how good the connection actually is, rather than how fast the link claims to be.
 *
 * "Why is my WiFi bad" is the other half of why people open a network app, and GreyRecon has had
 * nothing to say about it. Deliberately measuring latency, jitter and loss rather than throughput:
 * a speed test burns the user's data to produce a number their ISP already advertises, while
 * jitter and loss are what actually ruin calls, games and video, are cheap to measure, and are the
 * numbers nobody shows them.
 *
 * Two measurement paths. ICMP via the system `ping` binary is preferred -- it is unprivileged on
 * Android, it is what the network itself treats as a ping, and it reports real loss. Where `ping`
 * is unavailable or blocked (some OEM builds, some networks drop ICMP entirely) it falls back to
 * timing TCP connects, which is still an honest round trip but measures a slightly different
 * thing: a refused connection still completes the handshake path, so TCP-mode loss is closer to
 * "unreachable" than to "dropped". The method used is reported so the UI can say which it was.
 */
object ConnectionQuality {

    suspend fun measure(
        target: String,
        count: Int = 10,
        timeoutSeconds: Int = 2,
        tcpFallbackPorts: List<Int> = DEFAULT_TCP_PORTS,
    ): QualityResult = withContext(Dispatchers.IO) {
        icmp(target, count, timeoutSeconds) ?: tcp(target, count, timeoutSeconds, tcpFallbackPorts)
    }

    private fun icmp(target: String, count: Int, timeoutSeconds: Int): QualityResult? = runCatching {
        val process = ProcessBuilder("ping", "-c", count.toString(), "-W", timeoutSeconds.toString(), target)
            .redirectErrorStream(true)
            .start()
        // Generous ceiling: count * timeout plus slack, then kill rather than hang the UI.
        val finished = process.waitFor((count * timeoutSeconds + 5).toLong(), TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return null
        }
        val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
        val times = TIME_PATTERN.findAll(output).mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList()
        if (times.isEmpty()) return null
        build(target, QualityResult.Method.ICMP, count, times)
    }.getOrNull()

    private fun tcp(target: String, count: Int, timeoutSeconds: Int, ports: List<Int>): QualityResult {
        // Find a port that answers at all before spending the full sample budget. A home gateway
        // that ignores ICMP will usually still answer DNS or its own admin UI, and probing only
        // port 80 reported a perfectly healthy router as unreachable.
        val port = ports.firstOrNull { candidate ->
            runCatching {
                Socket().use { it.connect(InetSocketAddress(target, candidate), timeoutSeconds * 1000); true }
            }.getOrDefault(false)
        } ?: return build(target, QualityResult.Method.TCP, count, emptyList())

        val times = mutableListOf<Double>()
        repeat(count) {
            val started = System.nanoTime()
            val ok = runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(target, port), timeoutSeconds * 1000)
                    true
                }
            }.getOrDefault(false)
            if (ok) times += (System.nanoTime() - started) / 1_000_000.0
        }
        return build(target, QualityResult.Method.TCP, count, times)
    }

    private fun build(
        target: String,
        method: QualityResult.Method,
        sent: Int,
        times: List<Double>,
    ): QualityResult {
        if (times.isEmpty()) {
            return QualityResult(target, method, sent, 0, 0.0, 0.0, 0.0, 0.0, emptyList())
        }
        // Mean absolute consecutive difference, which is what RFC 3550 approximates and what a
        // user perceives -- the standard deviation would hide a link that alternates cleanly
        // between two very different latencies.
        val jitter = if (times.size < 2) {
            0.0
        } else {
            times.zipWithNext { a, b -> abs(b - a) }.average()
        }
        return QualityResult(
            target = target,
            method = method,
            sent = sent,
            received = times.size,
            minMs = times.min(),
            avgMs = times.average(),
            maxMs = times.max(),
            jitterMs = jitter,
            samples = times,
        )
    }

    /** Standard deviation, exposed for callers that want it alongside the consecutive-diff jitter. */
    fun standardDeviation(samples: List<Double>): Double {
        if (samples.size < 2) return 0.0
        val mean = samples.average()
        return sqrt(samples.sumOf { (it - mean) * (it - mean) } / (samples.size - 1))
    }

    /** 53 first: a home gateway almost always answers DNS even when it ignores everything else. */
    private val DEFAULT_TCP_PORTS = listOf(53, 443, 80, 8080)

    /** Matches both "time=12.3 ms" and the "time=12 ms" some busybox pings emit. */
    private val TIME_PATTERN = Regex("""time[=<]\s*([0-9]+(?:\.[0-9]+)?)\s*ms""", RegexOption.IGNORE_CASE)
}
