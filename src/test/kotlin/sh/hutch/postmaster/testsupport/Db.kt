package sh.hutch.postmaster.testsupport

import java.sql.Connection
import java.sql.ResultSet
import javax.sql.DataSource

/** Runs a query and returns each row as a map of lower-cased column label to value */
fun DataSource.rows(sql: String, vararg params: Any?): List<Map<String, Any?>> =
    connection.use { it.rows(sql, *params) }

fun Connection.rows(sql: String, vararg params: Any?): List<Map<String, Any?>> =
    prepareStatement(sql).use { statement ->
      params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
      statement.executeQuery().use { it.toMaps() }
    }

/** The single row a query returns; fails if there are none or several */
fun DataSource.row(sql: String, vararg params: Any?): Map<String, Any?> {
  val rows = rows(sql, *params)
  check(rows.size == 1) { "Expected one row from '$sql' but got ${rows.size}" }
  return rows.first()
}

/** Runs an insert, update or delete and returns the number of rows changed */
fun DataSource.update(sql: String, vararg params: Any?): Int =
    connection.use { connection ->
      connection.prepareStatement(sql).use { statement ->
        params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeUpdate()
      }
    }

private fun ResultSet.toMaps(): List<Map<String, Any?>> {
  val meta = metaData
  val result = mutableListOf<Map<String, Any?>>()
  while (next()) {
    val row = linkedMapOf<String, Any?>()
    for (column in 1..meta.columnCount) {
      row[meta.getColumnLabel(column).lowercase()] = getObject(column)
    }
    result += row
  }
  return result
}

/** Application tables as written in the migrations (identifiers keep their case in the test database) */
fun DataSource.applicationTables(): Set<String> =
    connection.use { connection ->
      connection.metaData.getTables(null, null, "%", arrayOf("TABLE", "BASE TABLE")).use { tables ->
        val names = mutableSetOf<String>()
        while (tables.next()) {
          val schema = tables.getString("TABLE_SCHEM")
          val name = tables.getString("TABLE_NAME")
          if (!schema.equals("INFORMATION_SCHEMA", ignoreCase = true) &&
              !name.equals("flyway_schema_history", ignoreCase = true)
          ) {
            names += name
          }
        }
        names
      }
    }

/** Column names of [table], in the case they were declared */
fun DataSource.columns(table: String): Set<String> =
    connection.use { connection ->
      connection.metaData.getColumns(null, null, table, "%").use { columns ->
        val names = mutableSetOf<String>()
        while (columns.next()) {
          names += columns.getString("COLUMN_NAME")
        }
        names
      }
    }
