package dev.nate.uiagent.cli

import java.io.File

/**
 * The Firebase Analytics events the app sent during a scenario, read from its `logcat.txt`.
 *
 * Firebase Analytics hands every event to the Play services measurement process, and that process logs
 * each one — at VERBOSE under the tag `FA-SVC`, which `AdbOps.logcatClear` turns on with
 * `setprop log.tag.FA-SVC VERBOSE` (the app's own `FA` tag no longer logs events; measured with SDK
 * 183005). One line per event, with the automatic parameters mixed in:
 *
 * ```
 * 1793237858.113  1566 25221 V FA-SVC  : Logging event: origin=app,name=w_view,params=Bundle[{w_event_id=530, w_path=/notification, ga_event_origin(_o)=app, ga_screen_class(_sc)=MainNavActivity, ga_screen_id(_si)=6163959274523659770, w_referrer=/browse}]
 * ```
 *
 * The stamp is when the service received the event, not when the app called `logEvent` — the app batches
 * through its service connection, so a line can trail the gesture by a second or so (measured). Steps last
 * seconds, so an event lands on the step it belongs to; one fired at the very end of a step may show on the next.
 */
object AnalyticsEvents {

    /** One logged event: when (epoch ms, device clock), who logged it (`app` / `auto`), its name and parameters in logged order. */
    data class Logged(val atEpochMs: Long, val origin: String, val name: String, val params: Map<String, String>)

    private val LINE = Regex("^\\s*(\\d+)\\.(\\d{1,3})\\s+\\d+\\s+\\d+\\s+V\\s+FA-SVC\\s*:\\s+Logging event: origin=([^,]*),name=([^,]*),params=Bundle\\[\\{(.*)\\}\\]\\s*$")

    /** Bundle entries are `key=value` joined by `, `; a value may itself hold commas and braces (`w_cells={"1":3}`), so split only before the next key. */
    private val ENTRY = Regex(", (?=[A-Za-z_][\\w().]*=)")

    fun parseLine(line: String): Logged? {
        val m = LINE.find(line) ?: return null
        val at = m.groupValues[1].toLong() * 1000 + m.groupValues[2].padEnd(3, '0').toLong()
        return Logged(at, m.groupValues[3], m.groupValues[4], parseParams(m.groupValues[5]))
    }

    fun parseParams(bundle: String): Map<String, String> {
        if (bundle.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (entry in bundle.split(ENTRY)) {
            val k = entry.substringBefore('=')
            out[k] = entry.substringAfter('=', "")
        }
        return out
    }

    /** Every event in [logcat], in log order; empty when there is no file. */
    fun read(logcat: File): List<Logged> {
        if (!logcat.isFile) return emptyList()
        val out = ArrayList<Logged>()
        logcat.useLines { seq -> for (line in seq) parseLine(line)?.let { out += it } }
        return out
    }

    /**
     * Which step each event fell in. [stepStarts] is each executed step's start (epoch ms) by its number, in order;
     * an event before the first step is the setup's (key 0 — the app's launch events), one after the last step's
     * start is the last step's (the log is dumped after it ends).
     */
    fun bucket(events: List<Logged>, stepStarts: List<Pair<Int, Long>>): Map<Int, List<Logged>> {
        val out = LinkedHashMap<Int, MutableList<Logged>>()
        for (e in events) {
            val n = stepStarts.lastOrNull { (_, start) -> start <= e.atEpochMs }?.first ?: 0
            out.getOrPut(n) { ArrayList() } += e
        }
        return out
    }
}
