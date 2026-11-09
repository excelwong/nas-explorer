package com.example.nasbrowser.model

/**
 * Sorting keys for the file list. Directories are always kept above files; this enum selects the
 * secondary key and its direction. The eight combinations mirror a classic desktop file manager
 * (name / type / size / modified time, ascending or descending).
 */
enum class SortMode(
    val key: String,
    val label: String,
    val field: Field,
    val ascending: Boolean
) {
    NAME_ASC("NAME_ASC", "名称  ↑  A → Z", Field.NAME, true),
    NAME_DESC("NAME_DESC", "名称  ↓  Z → A", Field.NAME, false),
    TYPE_ASC("TYPE_ASC", "类型  ↑  A → Z", Field.TYPE, true),
    TYPE_DESC("TYPE_DESC", "类型  ↓  Z → A", Field.TYPE, false),
    SIZE_ASC("SIZE_ASC", "大小  ↑  小 → 大", Field.SIZE, true),
    SIZE_DESC("SIZE_DESC", "大小  ↓  大 → 小", Field.SIZE, false),
    DATE_ASC("DATE_ASC", "修改时间  ↑  旧 → 新", Field.DATE, true),
    DATE_DESC("DATE_DESC", "修改时间  ↓  新 → 旧", Field.DATE, false);

    enum class Field(val label: String) {
        NAME("名称"), TYPE("类型"), SIZE("大小"), DATE("修改时间")
    }

    companion object {
        fun fromKey(k: String?): SortMode = values().firstOrNull { it.key == k } ?: NAME_ASC
        fun of(field: Field, asc: Boolean): SortMode =
            values().first { it.field == field && it.ascending == asc }
    }
}
