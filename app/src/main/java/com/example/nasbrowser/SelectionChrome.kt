package com.example.nasbrowser

/**
 * ES-style multi-selection chrome, shared by the SMB browser, the local browser and the
 * recycle bin.
 *
 * In ES a long press turns the blue tab strip into "1/62  全选 / 区间选择 / 取消" and the five
 * bottom slots into 复制 / 剪切 / 删除 / 重命名 / 更多. The page supplies the state; the
 * Activity owns the actual top bar and bottom toolbar widgets.
 */
data class SelAction(
    val label: String,
    val icon: Int,
    val onClick: () -> Unit
)

data class SelectionState(
    val selected: Int,
    val total: Int,
    val onSelectAll: () -> Unit,
    val onRange: () -> Unit,
    val onCancel: () -> Unit,
    val actions: List<SelAction>
)

interface SelectionHost {
    fun publishSelection(state: SelectionState?)
}

/**
 * Reusable selection bookkeeping.
 *
 * [keys] returns the currently displayed items in display order; it is also what "全选" and
 * "区间选择" operate on, so both work on filtered / searched / recycle-bin lists.
 */
class SelectionHelper(
    private val keys: () -> List<String>,
    private val publish: (SelectionState?) -> Unit,
    private val actions: () -> List<SelAction>,
    private val onChange: () -> Unit,
    private val onRangeStart: () -> Unit = {}
) {
    val selected = LinkedHashSet<String>()
    var rangeMode = false
        private set
    private var anchor = -1
    private var total = 0

    fun isActive(): Boolean = selected.isNotEmpty()
    fun isSelected(key: String): Boolean = key in selected

    fun setTotal(n: Int) {
        total = n
        emit()
    }

    /** Tap on a row / its round checkbox. In range mode the first tap is the anchor. */
    fun toggle(key: String, index: Int) {
        val all = keys()
        if (rangeMode) {
            if (anchor < 0) {
                anchor = index
                selected.add(key)
            } else {
                val from = minOf(anchor, index)
                val to = maxOf(anchor, index)
                for (i in from..to) if (i in all.indices) selected.add(all[i])
                rangeMode = false
                anchor = -1
            }
        } else {
            if (!selected.remove(key)) selected.add(key)
        }
        emit()
    }

    fun selectAll() {
        selected.addAll(keys())
        rangeMode = false
        anchor = -1
        emit()
    }

    fun startRange() {
        rangeMode = true
        anchor = -1
        onRangeStart()
        emit()
    }

    fun cancel() {
        selected.clear()
        rangeMode = false
        anchor = -1
        emit()
    }

    /** Drop selections that scrolled out of the current listing (e.g. after a refresh). */
    fun refresh() {
        val visible = keys().toHashSet()
        selected.retainAll(visible)
        emit()
    }

    private fun emit() {
        onChange()
        publish(
            if (selected.isEmpty()) null
            else SelectionState(
                selected = selected.size,
                total = maxOf(total, keys().size),
                onSelectAll = ::selectAll,
                onRange = ::startRange,
                onCancel = ::cancel,
                actions = actions()
            )
        )
    }
}
