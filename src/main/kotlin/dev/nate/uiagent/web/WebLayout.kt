package dev.nate.uiagent.web

import dev.nate.uiagent.Bounds
import dev.nate.uiagent.LogicalElement
import dev.nate.uiagent.Origin
import dev.nate.uiagent.Point
import dev.nate.uiagent.WebOverflow
import dev.nate.uiagent.json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

/**
 * Web-page element extraction and conversion to [LogicalElement]s.
 *
 * The extraction JS runs once per observation via Runtime.evaluate and returns viewport,
 * readyState and elements in a single round trip. Two real-world lessons baked in (spike M0):
 *  - React apps attach click listeners, so `[onclick]` finds nothing; ARIA roles and `label`
 *    elements must be part of the selector.
 *  - Custom radios/checkboxes are zero-size `<input>`s wrapped in a `<label>`: the label is
 *    the visible, tappable element; the input carries the state. We emit the label with the
 *    input's state lifted onto it.
 * Non-interactive visible text is extracted too (as `text` nodes) so Verify-style steps and
 * replay evidence work on web screens exactly like on native ones.
 */
object WebLayout {

    /**
     * Element-extraction JS. Returns {"iw": innerWidth, "ih": innerHeight, "sy": scrollY,
     * "sh": document scrollHeight, "rs": readyState, "els": [...]}. The scroll geometry feeds
     * [scrollHint]: only on-screen elements are extracted (like a11y), so the model otherwise has
     * no way to know a page continues below the fold (실측: a payment page whose only submit
     * button sat two screens down — the model tapped page titles instead of scrolling).
     */
    val EXTRACT_JS: String = """
        (() => {
          const SEL = 'a,button,input,select,textarea,label,[onclick],[role=button],[role=link],' +
                      '[role=checkbox],[role=radio],[role=tab],[role=switch],[role=option],[role=menuitem]';
          const vis = e => { const r = e.getBoundingClientRect();
            return r.width > 0 && r.height > 0 && r.bottom > 0 && r.top < innerHeight &&
                   r.right > 0 && r.left < innerWidth; };
          const firstLine = s => ((s || '') + '').split('\n').map(t => t.trim()).filter(Boolean)[0] || null;
          const rectOf = e => (({x, y, width, height}) => ({x, y, width, height}))(e.getBoundingClientRect());
          const els = [];
          for (const e of document.querySelectorAll(SEL)) {
            if (!vis(e)) continue;
            // a control wrapped by a label is represented by that label
            if (e.tagName !== 'LABEL' && e.closest('label')) continue;
            const inner = e.tagName === 'LABEL'
              ? (e.querySelector('input,select,textarea') ||
                 (e.htmlFor ? document.getElementById(e.htmlFor) : null))
              : null;
            const ctl = inner || e;
            els.push({
              label: firstLine(e.innerText) || firstLine(e.value) || e.getAttribute('aria-label')
                     || e.placeholder || (inner && inner.placeholder) || null,
              attrId: e.id || (inner && inner.id) || null,
              tag: ctl.tagName.toLowerCase(),
              type: ctl.type || null,
              role: e.getAttribute('role'),
              rect: rectOf(e),
              state: { checked: ctl.checked === true || e.getAttribute('aria-checked') === 'true',
                       selected: e.getAttribute('aria-selected') === 'true',
                       disabled: ctl.disabled === true,
                       focused: ctl === document.activeElement || e === document.activeElement }
            });
          }
          // Click handlers on plain elements (React onClick) leave no attribute; their one visible
          // trace is the pointer cursor. The outermost pointer element that is not a control (nor
          // inside one) is a click host. Page-sized wrappers are skipped: a pointer cursor set on
          // the body would otherwise turn every top-level container into a "button".
          const hosts = new Set();
          for (const e of document.querySelectorAll('body *')) {
            if (e.closest(SEL)) continue;
            if (getComputedStyle(e).cursor !== 'pointer') continue;
            const p = e.parentElement;
            if (p && p !== document.body && getComputedStyle(p).cursor === 'pointer') continue;
            if (!vis(e)) continue;
            const r = e.getBoundingClientRect();
            if (r.width * r.height > innerWidth * innerHeight * 0.5) continue;
            hosts.add(e);
            els.push({ label: firstLine(e.innerText) || e.getAttribute('aria-label') || null,
                       attrId: e.id || null, tag: 'div', type: null, role: 'pointer', rect: rectOf(e),
                       state: { checked: false, selected: e.getAttribute('aria-selected') === 'true',
                                disabled: false, focused: e === document.activeElement } });
          }
          const hostOf = e => { for (let a = e; a; a = a.parentElement) if (hosts.has(a)) return a; return null; };
          const union = (a, b) => { const x = Math.min(a.x, b.x), y = Math.min(a.y, b.y);
            return { x, y, width: Math.max(a.x + a.width, b.x + b.width) - x, height: Math.max(a.y + a.height, b.y + b.height) - y }; };
          const texts = [];
          const range = document.createRange();
          for (const e of document.querySelectorAll('body *')) {
            if (texts.length >= 120) break;
            if (!vis(e)) continue;
            let own = '';
            // The element's box also wraps its child elements (a badge, a struck-out price), which
            // misplaces the text in reading order; measure the text nodes themselves.
            let box = null;
            for (const n of e.childNodes) if (n.nodeType === 3) {
              own += n.textContent;
              if (!n.textContent.trim()) continue;
              range.selectNodeContents(n);
              const r = range.getBoundingClientRect();
              if (r.width > 0 && r.height > 0) box = box ? union(box, r) : { x: r.x, y: r.y, width: r.width, height: r.height };
            }
            own = own.replace(/\s+/g, ' ').trim();
            if (!own) continue;
            // drop text that merely repeats its interactive host's label
            const host = e.closest(SEL) || hostOf(e);
            if (host && firstLine(host.innerText) === own) continue;
            texts.push({ label: own.slice(0, 80), attrId: e.id || null, tag: 'text', type: null,
                         role: null, rect: box || rectOf(e),
                         state: { checked: false, selected: false, disabled: false, focused: false } });
          }
          const se = document.scrollingElement || document.documentElement;
          return JSON.stringify({ iw: innerWidth, ih: innerHeight, sy: Math.round(scrollY),
                                  sh: se ? se.scrollHeight : 0,
                                  rs: document.readyState, els: els.concat(texts) });
        })()
    """.trimIndent()

    // ---------------------------------------------------------------- parse

    data class WebRect(val x: Double, val y: Double, val width: Double, val height: Double)

    data class WebElement(
        val label: String?,
        val attrId: String?,
        val tag: String,
        val type: String?,
        val role: String?,
        val rect: WebRect,
        val checked: Boolean,
        val disabled: Boolean,
        val focused: Boolean,
        /** aria-selected — web tabs/options use this where native uses "selected". */
        val selected: Boolean = false,
    )

    data class WebPage(
        val innerWidth: Int,
        val readyState: String,
        val elements: List<WebElement>,
        /** Viewport height, document scroll offset and total scroll height, in CSS px (0 = unknown). */
        val innerHeight: Int = 0,
        val scrollY: Int = 0,
        val scrollHeight: Int = 0,
    )

    /**
     * Content-independent note telling the model the page extends past the viewport, or null when
     * it fits (or geometry is unknown). Measured in screens so it reads the same at every dpr.
     */
    fun scrollHint(page: WebPage): String? {
        val o = overflow(page) ?: return null
        val ih = o.viewportPx
        val parts = buildList {
            if (o.below) add("${screens(o.belowPx, ih)} below")
            if (o.above) add("${screens(o.abovePx, ih)} above")
        }
        if (parts.isEmpty()) return null
        return "the web page continues past the viewport (${parts.joinToString(", ")})"
    }

    /** Page extent past the viewport; null when the geometry is unknown (older fixtures). */
    fun overflow(page: WebPage): WebOverflow? {
        val ih = page.innerHeight.takeIf { it > 0 } ?: return null
        return WebOverflow(belowPx = page.scrollHeight - page.scrollY - ih, abovePx = page.scrollY, viewportPx = ih)
    }

    private fun screens(px: Int, ih: Int): String {
        val n = px.toDouble() / ih
        if (n < 1) return "less than 1 screen"
        val rounded = String.format(java.util.Locale.ROOT, "%.1f", n)
        return "about $rounded screen" + if (rounded.substringBefore('.').toInt() >= 2) "s" else ""
    }

    /** Parse the EXTRACT_JS result string. Null on malformed input (observation failure). */
    fun parsePage(value: String): WebPage? {
        val o = runCatching { json.parseToJsonElement(value) }.getOrNull() as? JsonObject ?: return null
        val iw = o["iw"]?.jsonPrimitive?.intOrNull ?: return null
        val rs = o["rs"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        val els = (o["els"] as? JsonArray)?.mapNotNull { el ->
            val e = el as? JsonObject ?: return@mapNotNull null
            val r = e["rect"] as? JsonObject ?: return@mapNotNull null
            val rect = WebRect(
                r["x"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null,
                r["y"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null,
                r["width"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null,
                r["height"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null,
            )
            val st = e["state"] as? JsonObject
            WebElement(
                label = e["label"]?.jsonPrimitive?.contentOrNull,
                attrId = e["attrId"]?.jsonPrimitive?.contentOrNull,
                tag = e["tag"]?.jsonPrimitive?.contentOrNull ?: "div",
                type = e["type"]?.jsonPrimitive?.contentOrNull,
                role = e["role"]?.jsonPrimitive?.contentOrNull,
                rect = rect,
                checked = st?.get("checked")?.jsonPrimitive?.booleanOrNull == true,
                disabled = st?.get("disabled")?.jsonPrimitive?.booleanOrNull == true,
                focused = st?.get("focused")?.jsonPrimitive?.booleanOrNull == true,
                selected = st?.get("selected")?.jsonPrimitive?.booleanOrNull == true,
            )
        } ?: emptyList()
        return WebPage(
            iw, rs, els,
            innerHeight = o["ih"]?.jsonPrimitive?.intOrNull ?: 0,
            scrollY = o["sy"]?.jsonPrimitive?.intOrNull ?: 0,
            scrollHeight = o["sh"]?.jsonPrimitive?.intOrNull ?: 0,
        )
    }

    // ---------------------------------------------------------------- convert

    /**
     * Convert extracted web elements into screen-space [LogicalElement]s. CSS px map to
     * physical px via `scale = webviewRect.width / innerWidth` (validated: scale == dpr),
     * offset by the WebView's on-screen origin. Ids are placeholders — the merge step
     * re-sorts and re-assigns them.
     */
    fun toLogicalElements(page: WebPage, webviewRect: Bounds): List<LogicalElement> {
        if (page.innerWidth <= 0) return emptyList()
        val scale = webviewRect.width.toDouble() / page.innerWidth
        return page.elements.map { e ->
            val x0 = webviewRect.x0 + (e.rect.x * scale).roundToInt()
            val y0 = webviewRect.y0 + (e.rect.y * scale).roundToInt()
            val x1 = webviewRect.x0 + ((e.rect.x + e.rect.width) * scale).roundToInt()
            val y1 = webviewRect.y0 + ((e.rect.y + e.rect.height) * scale).roundToInt()
            LogicalElement(
                id = -1,
                label = e.label,
                resourceId = e.attrId,
                interactions = interactionsOf(e),
                state = statesOf(e),
                kind = if (e.tag == "text") "label" else null,
                center = Point((x0 + x1) / 2, (y0 + y1) / 2),
                bounds = Bounds(x0, y0, x1, y1),
                origin = Origin.WEB,
            )
        }
    }

    /** tag/type/role -> interactions, mirroring the native vocabulary. disabled drops clickable. */
    internal fun interactionsOf(e: WebElement): List<String> {
        val out = mutableListOf<String>()
        val checkable = e.type == "radio" || e.type == "checkbox" ||
            e.role in setOf("checkbox", "radio", "switch")
        val typable = (e.tag == "input" && e.type !in setOf("radio", "checkbox", "submit", "button")) ||
            e.tag == "textarea"
        when {
            e.tag == "text" -> {}
            checkable -> { out += "clickable"; out += "checkable" }
            typable -> { out += "clickable"; out += "focusable" }
            else -> out += "clickable" // a, button, select, [onclick], remaining roles, pointer hosts
        }
        if (e.disabled) out.remove("clickable")
        return out
    }

    internal fun statesOf(e: WebElement): List<String> = buildList {
        if (e.checked) add("checked")
        if (e.selected) add("selected")
        if (e.focused) add("focused")
    }
}
