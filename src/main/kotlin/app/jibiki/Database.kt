package app.jibiki

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.pool.HikariPool
import org.intellij.lang.annotations.Language
import java.sql.ResultSet
import java.sql.ResultSetMetaData

class Database {
    private val pool = HikariPool(HikariConfig().apply {
        jdbcUrl = "jdbc:postgresql://localhost/jmdict"
        username = "postgres"
        password = ""
        maximumPoolSize = 10
        poolName = "Jibiki Connection Pool"
    })

    fun query(@Language("PostgreSQL") sql: String, vararg args: Any): List<Row> {
        pool.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, arg ->
                    when (arg) {
                        is String -> statement.setString(index + 1, arg)
                        is Int -> statement.setInt(index + 1, arg)
                    }
                }

                val rows = mutableListOf<Row>()
                val set = statement.executeQuery()
                while (set.next()) {
                    val row = Row()
                    val md = set.metaData
                    for (i in 1..md.columnCount)
                        row.addColumn(md.getColumnName(i), set.getObject(i))
                    rows.add(row)
                }
                return rows
            }
        }
    }
}