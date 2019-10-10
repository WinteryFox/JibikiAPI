package app.jibiki

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.pool.HikariPool
import org.intellij.lang.annotations.Language
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.SQLException

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
                    statement.setObject(index + 1, arg)
                }

                val rows = mutableListOf<Row>()
                val set = statement.executeQuery()
                while (set.next()) {
                    val row = Row()
                    val md = set.metaData
                    for (i in 1..md.columnCount) {
                        var column: Any? = null
                        try {
                            column = set.getObject(i)
                        } catch (exception: SQLException) {

                        }

                        row.addColumn(md.getColumnName(i), column)
                    }
                    rows.add(row)
                }
                return rows
            }
        }
    }
}