package sh.hutch.postmaster.repositories

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped
import sh.hutch.postmaster.domain.RuleType
import sh.hutch.postmaster.entities.ConditionEntity
import sh.hutch.postmaster.entities.RuleActionEntity
import sh.hutch.postmaster.entities.RuleEntity
import sh.hutch.postmaster.util.Names

@ApplicationScoped
class RuleRepository : PanacheRepository<RuleEntity> {
  /** Active rules of a type, highest priority first, then oldest first. */
  fun listActive(ruleType: RuleType): List<RuleEntity> =
    list("ruleType = ?1 and deleted = false order by priority desc, id asc", ruleType)

  /** Active, enabled rules of a type, in the order they are applied. */
  fun listEnabled(ruleType: RuleType): List<RuleEntity> =
    list("ruleType = ?1 and deleted = false and enabled = true order by priority desc, id asc", ruleType)

  fun findActive(id: Long): RuleEntity? = find("id = ?1 and deleted = false", id).firstResult()

  fun findActiveByName(ruleType: RuleType, name: String): RuleEntity? =
    find("ruleType = ?1 and nameKey = ?2 and deleted = false", ruleType, Names.key(name)).firstResult()
}

@ApplicationScoped
class ConditionRepository : PanacheRepository<ConditionEntity> {
  fun listForRule(ruleId: Long): List<ConditionEntity> = list("rule.id = ?1 order by position, id", ruleId)
}

@ApplicationScoped
class RuleActionRepository : PanacheRepository<RuleActionEntity> {
  fun listActiveForRule(ruleId: Long): List<RuleActionEntity> =
    list("rule.id = ?1 and deleted = false order by position, id", ruleId)

  fun listEnabledForRule(ruleId: Long): List<RuleActionEntity> =
    list("rule.id = ?1 and deleted = false and enabled = true order by position, id", ruleId)
}
