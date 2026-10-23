package sh.hutch.postmaster.controllers

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Named
import org.jboss.logging.Logger
import sh.hutch.postmaster.services.SettingKeys
import sh.hutch.postmaster.services.SettingsService
import sh.hutch.postmaster.util.RelativeTime
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Formats instants for pages: relative (`14m ago`), absolute in the `processing.timezone` setting
 * (`Fri 23 Oct 2026, 06:40 AEDT`) and ISO 8601 for `<time datetime>`. Used by the `{#time}` tag.
 */
@ApplicationScoped
@Named("times")
class TimeDisplay(private val settings: SettingsService) {
  @Volatile
  private var cachedZone: ZoneId? = null

  @Volatile
  private var cachedUntil: Instant = Instant.MIN

  fun relative(instant: Instant): String = RelativeTime.format(instant, Instant.now())

  fun absolute(instant: Instant): String = ABSOLUTE.withZone(zone()).format(instant)

  fun iso(instant: Instant): String = DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.SECONDS))

  /** The display timezone, re-read from the settings at most once a minute. */
  fun zone(): ZoneId {
    val now = Instant.now()
    val zone = cachedZone
    if (zone != null && now.isBefore(cachedUntil)) {
      return zone
    }
    val fresh = try {
      settings.getZoneId(SettingKeys.PROCESSING_TIMEZONE)
    } catch (e: RuntimeException) {
      LOG.warnf("Using UTC for display: %s", e.message)
      ZoneId.of("UTC")
    }
    cachedZone = fresh
    cachedUntil = now.plus(ZONE_CACHE)
    return fresh
  }

  companion object {
    private val LOG = Logger.getLogger(TimeDisplay::class.java)
    private val ZONE_CACHE = Duration.ofSeconds(60)
    private val ABSOLUTE = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm z", Locale.ENGLISH)
  }
}
