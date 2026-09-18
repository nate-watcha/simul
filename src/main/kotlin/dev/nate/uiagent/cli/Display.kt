package dev.nate.uiagent.cli

/**
 * A forced emulator display: physical pixels + density. What the app actually responds to is
 * the dp width (= width / (density/160)) — the Material window size class — so a profile must
 * always pin BOTH size and density; forcing only the pixel size on a device with a different
 * density would still produce a different layout.
 *
 * The runner applies the profile via `adb shell wm size/density` before scenarios run and
 * resets it afterwards, and ONLY on emulators — resizing a person's physical device screen is
 * never acceptable collateral for a test run.
 */
data class DisplayProfile(val width: Int, val height: Int, val density: Int) {
    /** Canonical text form, also stamped into traces: "720x1280@320". */
    override fun toString() = "${width}x${height}@${density}"

    val dpWidth: Int get() = width * 160 / density

    companion object {
        /**
         * Canonical portrait profiles per size class, aligned with the target app's design
         * system (SMALL <600dp, MEDIUM 600..799dp, LARGE >=800dp —
         * note the 800dp boundary, not Material's 840). The app derives its size class from
         * the current window metrics in dp, so pinning size+density pins the branch it takes.
         */
        val SIZE_CLASSES = mapOf(
            "small" to DisplayProfile(720, 1280, 320), // 360dp — phone (all traces so far)
            "medium" to DisplayProfile(1280, 1920, 320), // 640dp — small tablet / unfolded
            "large" to DisplayProfile(1600, 2560, 240), // 1066dp — tablet
        )

        /**
         * Resolve the config `display:` block. `sizeClass` picks a canonical profile;
         * explicit `size`/`density` override its fields (or stand alone if both given).
         * Returns null when the block is absent; throws on a malformed block — a config
         * that silently applies no profile would defeat the whole safety net.
         */
        fun fromConfig(sizeClass: String?, size: String?, density: String?): DisplayProfile? {
            if (sizeClass == null && size == null && density == null) return null
            val base = sizeClass?.let {
                SIZE_CLASSES[it.lowercase().trim()]
                    ?: throw IllegalArgumentException(
                        "config display.sizeClass '$it' unknown (use ${SIZE_CLASSES.keys.joinToString("|")})")
            }
            val wh = size?.let {
                parseSize(it) ?: throw IllegalArgumentException("config display.size '$it' is not WxH")
            }
            val dpi = density?.let {
                it.trim().toIntOrNull()?.takeIf { d -> d > 0 }
                    ?: throw IllegalArgumentException("config display.density '$it' is not a positive integer")
            }
            val width = wh?.first ?: base?.width
            val height = wh?.second ?: base?.height
            val den = dpi ?: base?.density
            if (width == null || height == null || den == null) {
                throw IllegalArgumentException(
                    "config display needs sizeClass, or both size and density (got size=$size density=$density)")
            }
            return DisplayProfile(width, height, den)
        }

        fun parseSize(s: String): Pair<Int, Int>? =
            Regex("(\\d+)\\s*x\\s*(\\d+)").matchEntire(s.trim())
                ?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }

        /** Inverse of [toString]: "720x1280@320" -> profile. Null on anything else. */
        fun parse(s: String): DisplayProfile? {
            val at = s.lastIndexOf('@')
            if (at <= 0) return null
            val wh = parseSize(s.substring(0, at)) ?: return null
            val dpi = s.substring(at + 1).trim().toIntOrNull()?.takeIf { it > 0 } ?: return null
            return DisplayProfile(wh.first, wh.second, dpi)
        }
    }
}
