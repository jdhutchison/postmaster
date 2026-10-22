package sh.hutch.postmaster.db

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText

/** Reads the production Flyway migration scripts from the classpath */
object MigrationScripts {
  fun all(): Map<String, String> {
    val url = MigrationScripts::class.java.getResource("/db/migration")
        ?: error("db/migration is not on the classpath")
    val dir = Path.of(url.toURI())
    return Files.list(dir).use { files ->
      files.filter { it.name.endsWith(".sql") }
          .sorted()
          .toList()
          .associate { it.name to it.readText() }
    }
  }

  /** The body of `CREATE TABLE <table> ( ... )`, from whichever script creates it */
  fun createTable(table: String): String {
    val start = Regex("CREATE TABLE\\s+$table\\s*\\(", RegexOption.IGNORE_CASE)
    for (script in all().values) {
      val match = start.find(script) ?: continue
      var depth = 1
      var index = match.range.last + 1
      while (depth > 0) {
        when (script[index]) {
          '(' -> depth++
          ')' -> depth--
        }
        index++
      }
      return script.substring(match.range.last + 1, index - 1)
    }
    error("No migration creates table $table")
  }

  /** The declaration that follows [column]'s name in a CREATE TABLE body */
  fun columnDeclaration(createTableBody: String, column: String): String {
    val line = createTableBody.lines().map { it.trim() }.firstOrNull { it.startsWith("$column ") }
        ?: error("Column $column not found")
    return line.removePrefix("$column ").trim()
  }
}
