package com.example.nasbrowser.model

/** The three ES view families. */
enum class ViewKind(val label: String) {
    ICON("图标"),
    LIST("列表"),
    DETAIL("详情")
}

/** ES offers every family in three sizes; [spanDp] also drives the adaptive grid columns. */
enum class ViewScale(
    val label: String,
    val iconDp: Int,
    val rowIconDp: Int,
    val rowHeightDp: Int,
    val textSp: Float,
    val spanDp: Int
) {
    LARGE("大", 88, 48, 96, 16f, 116),
    MEDIUM("中", 64, 38, 76, 14f, 88),
    SMALL("小", 44, 28, 58, 12f, 68)
}

/**
 * ES File Explorer's 视图 dialog shows a 3 x 3 matrix: 图标 / 列表 / 详情, each in 大 / 中 / 小.
 * The scale only changes icon size, row height and text size — the three families keep their own
 * row layout, so no extra XML variants are needed.
 */
enum class ViewMode(val key: String, val kind: ViewKind, val scale: ViewScale) {
    ICON_LARGE("ICON_LARGE", ViewKind.ICON, ViewScale.LARGE),
    ICON_MEDIUM("ICON_MEDIUM", ViewKind.ICON, ViewScale.MEDIUM),
    ICON_SMALL("ICON_SMALL", ViewKind.ICON, ViewScale.SMALL),

    LIST_LARGE("LIST_LARGE", ViewKind.LIST, ViewScale.LARGE),
    LIST_MEDIUM("LIST_MEDIUM", ViewKind.LIST, ViewScale.MEDIUM),
    LIST_SMALL("LIST_SMALL", ViewKind.LIST, ViewScale.SMALL),

    DETAIL_LARGE("DETAIL_LARGE", ViewKind.DETAIL, ViewScale.LARGE),
    DETAIL_MEDIUM("DETAIL_MEDIUM", ViewKind.DETAIL, ViewScale.MEDIUM),
    DETAIL_SMALL("DETAIL_SMALL", ViewKind.DETAIL, ViewScale.SMALL);

    val label: String get() = "${kind.label}(${scale.label})"

    val layoutRes: Int get() = when (kind) {
        ViewKind.ICON -> com.example.nasbrowser.R.layout.item_file_grid
        ViewKind.LIST -> com.example.nasbrowser.R.layout.item_file
        ViewKind.DETAIL -> com.example.nasbrowser.R.layout.item_file_detail
    }

    val isGrid: Boolean get() = kind == ViewKind.ICON

    companion object {
        /** ES opens every folder in the compact list/detail row, including on unfolded foldables. */
        val DEFAULT = LIST_SMALL

        fun fromKey(k: String?): ViewMode {
            if (k == null) return DEFAULT
            values().firstOrNull { it.key == k }?.let { return it }
            // Migrate the three v4 keys so existing users keep a sensible view.
            return when (k) {
                "LIST" -> LIST_SMALL
                "LARGE_ICON" -> ICON_LARGE
                "DETAIL" -> DETAIL_SMALL
                else -> DEFAULT
            }
        }

        fun of(kind: ViewKind, scale: ViewScale): ViewMode =
            values().first { it.kind == kind && it.scale == scale }
    }
}
