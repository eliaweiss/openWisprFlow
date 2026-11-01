package app.openwisprflow

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.hypot

/**
 * Optional screen context (HLD section 4, Field context): a bounded walk of the focused field's
 * own window, collecting visible text and labels, nearest to the field first. Other windows (the
 * keyboard, the bubble, other apps) are never visited. Runs only while a dictation is in progress,
 * off the main thread, and nothing it collects is logged or stored.
 */
object ScreenContextReader {

    const val MAX_NODES = 200
    const val MAX_DEPTH = 20
    const val MAX_CHARS = 4_000
    private const val MAX_NODE_CHARS = 200

    fun collect(service: AccessibilityService): List<String> {
        val focused = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return emptyList()
        if (focused.isPassword) return emptyList()
        val root = focused.window?.root ?: return emptyList()
        val focusBounds = Rect().also(focused::getBoundsInScreen)

        val found = mutableListOf<Pair<String, Double>>()
        val queue = ArrayDeque(listOf(root to 0))
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val (node, depth) = queue.removeFirst()
            visited++
            nodeText(node, focused)?.let { text ->
                val bounds = Rect().also(node::getBoundsInScreen)
                found += text to distance(bounds, focusBounds)
            }
            if (depth < MAX_DEPTH) {
                for (i in 0 until node.childCount) node.getChild(i)?.let { queue.addLast(it to depth + 1) }
            }
        }

        var chars = 0
        return found.sortedBy { it.second }.map { it.first }.takeWhile { text ->
            chars += text.length
            chars <= MAX_CHARS
        }
    }

    /** Visible text or label of a node, skipping the target field itself, passwords and placeholders. */
    private fun nodeText(node: AccessibilityNodeInfo, focused: AccessibilityNodeInfo): String? {
        if (node == focused || !node.isVisibleToUser || node.isPassword) return null
        val text = if (node.isShowingHintText) null else node.text?.toString()
        val label = text?.takeIf { it.isNotBlank() } ?: node.contentDescription?.toString()
        return label?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_NODE_CHARS)
    }

    private fun distance(a: Rect, b: Rect): Double =
        hypot((a.exactCenterX() - b.exactCenterX()).toDouble(), (a.exactCenterY() - b.exactCenterY()).toDouble())
}
