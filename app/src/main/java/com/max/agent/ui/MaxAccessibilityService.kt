package com.max.agent.ui

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MaxAccessibilityService : AccessibilityService() {

    data class ScreenContent(
        val packageName: String,
        val eventType: Int,
        val text: String,
        val nodeTree: UINode? = null
    )

    data class UINode(
        val className: String,
        val text: String,
        val contentDesc: String,
        val viewId: String,
        val isClickable: Boolean,
        val isFocusable: Boolean,
        val bounds: String,
        val children: List<UINode>
    )

    override fun onServiceConnected() {
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        _isActive.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return
        val budget = intArrayOf(MAX_NODES)
        
        _screenContent.value = ScreenContent(
            packageName = event.packageName?.toString() ?: "",
            eventType = event.eventType,
            text = runCatching { event.text.joinToString(" ") }.getOrDefault(""),
            nodeTree = buildNodeTree(root, 0, budget)
        )
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        _isActive.value = false
    }

    private fun buildNodeTree(
        node: AccessibilityNodeInfo,
        depth: Int,
        budget: IntArray
    ): UINode {
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)

        val children = if (depth >= MAX_DEPTH || budget[0] <= 0) {
            emptyList()
        } else {
            val childCount = node.childCount
            val list = ArrayList<UINode>(childCount.coerceAtMost(budget[0]))
            for (i in 0 until childCount) {
                if (budget[0] <= 0) break
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                budget[0] -= 1
                list.add(buildNodeTree(child, depth + 1, budget))
            }
            list
        }
        
        return UINode(
            className = node.className?.toString() ?: "",
            text = node.text?.toString() ?: "",
            contentDesc = node.contentDescription?.toString() ?: "",
            viewId = node.viewIdResourceName ?: "",
            isClickable = node.isClickable,
            isFocusable = node.isFocusable,
            bounds = bounds.toShortString(),
            children = children
        )
    }

    companion object {
        private const val MAX_DEPTH = 50
        private const val MAX_NODES = 2000

        private val _isActive = MutableStateFlow(false)
        val isActive: StateFlow<Boolean> = _isActive

        private val _screenContent = MutableStateFlow<ScreenContent?>(null)
        val screenContent: StateFlow<ScreenContent?> = _screenContent

        fun getClickableNodes(node: UINode?): List<UINode> {
            if (node == null) return emptyList()
            val result = mutableListOf<UINode>()
            if (node.isClickable) result += node
            node.children.forEach { result += getClickableNodes(it) }
            return result
        }

        fun findByText(node: UINode?, text: String): UINode? {
            if (node == null) return null
            if (node.text.contains(text, ignoreCase = true) || node.contentDesc.contains(text, ignoreCase = true)) return node
            return node.children.firstNotNullOfOrNull { findByText(it, text) }
        }
    }
}
