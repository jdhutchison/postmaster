package sh.hutch.postmaster.repositories

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import io.quarkus.panache.common.Sort
import jakarta.enterprise.context.ApplicationScoped
import sh.hutch.postmaster.entities.CalendarEntity
import sh.hutch.postmaster.entities.PromptEntity
import sh.hutch.postmaster.entities.SettingEntity
import sh.hutch.postmaster.entities.TypeEntity
import sh.hutch.postmaster.util.Names

// "Active" means not soft-deleted. Name lookups compare name_key, the lower-cased name, so they
// ignore case the same way on MariaDB and H2 (CLARIFICATIONS.md D20).

@ApplicationScoped
class SettingRepository : PanacheRepository<SettingEntity> {
  fun findByKey(key: String): SettingEntity? = find("settingKey", key).firstResult()

  fun listOrdered(): List<SettingEntity> = listAll(Sort.by("position").and("id"))
}

@ApplicationScoped
class TypeRepository : PanacheRepository<TypeEntity> {
  /** Active types ordered by name, ignoring case. */
  fun listActive(): List<TypeEntity> = list("deleted = false order by nameKey, id")

  fun findActive(id: Long): TypeEntity? = find("id = ?1 and deleted = false", id).firstResult()

  fun findActiveByName(name: String): TypeEntity? =
    find("nameKey = ?1 and deleted = false", Names.key(name)).firstResult()

  fun findBySystemKey(key: String): TypeEntity? = find("systemKey", key).firstResult()

  fun findBill(): TypeEntity? = findBySystemKey(TypeEntity.BILL_KEY)
}

@ApplicationScoped
class CalendarRepository : PanacheRepository<CalendarEntity> {
  /** Active calendars ordered by name, ignoring case. */
  fun listActive(): List<CalendarEntity> = list("deleted = false order by nameKey, id")

  /** Active calendars that are enabled for email processing. */
  fun listEnabled(): List<CalendarEntity> = list("deleted = false and enabled = true order by nameKey, id")

  fun findActive(id: Long): CalendarEntity? = find("id = ?1 and deleted = false", id).firstResult()

  fun findActiveByName(name: String): CalendarEntity? =
    find("nameKey = ?1 and deleted = false", Names.key(name)).firstResult()
}

@ApplicationScoped
class PromptRepository : PanacheRepository<PromptEntity> {
  /** Active prompts ordered by name, ignoring case. */
  fun listActive(): List<PromptEntity> = list("deleted = false order by lower(name), id")

  fun findActive(id: Long): PromptEntity? = find("id = ?1 and deleted = false", id).firstResult()

  /** The active system prompt with this key. */
  fun findByKey(promptKey: String): PromptEntity? =
    find("promptKey = ?1 and deleted = false", promptKey).firstResult()

  /** The first active prompt with this name, ignoring case. Prompt names need not be unique. */
  fun findActiveByName(name: String): PromptEntity? =
    find("lower(name) = ?1 and deleted = false order by id", Names.key(name)).firstResult()
}
