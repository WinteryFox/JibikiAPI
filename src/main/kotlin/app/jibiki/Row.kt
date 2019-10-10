package app.jibiki

class Row {
    private val columns: MutableMap<String, Any?> = mutableMapOf()

    fun <T> get(key: String): T {
        return columns[key] as T
    }

    fun addColumn(key: String, data: Any?) {
        columns[key] = data
    }
}