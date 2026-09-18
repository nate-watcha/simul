package dev.nate.uiagent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * JSONL run trace: every observation, gesture, tool call and verdict, one JSON object per
 * line. Keep it append-only and flat.
 */
class Trace(private val file: File?) {

    fun event(type: String, build: (MutableMap<String, String>) -> Unit = {}) {
        if (file == null) return
        val fields = linkedMapOf<String, String>()
        build(fields)
        val obj: JsonObject = buildJsonObject {
            put("ts", System.currentTimeMillis())
            put("type", type)
            fields.forEach { (k, v) -> put(k, v) }
        }
        file.appendText(obj.toString() + "\n")
    }

    companion object {
        val DISABLED = Trace(null)

        /** trace file `uiagent-trace-<epoch>.jsonl` inside [dir] (created if missing). */
        fun inDir(dir: File): Trace {
            dir.mkdirs()
            return Trace(File(dir, "uiagent-trace-${System.currentTimeMillis()}.jsonl"))
        }
    }
}
