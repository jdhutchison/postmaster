@file:JvmName("TemplateExtensions")

package sh.hutch.postmaster.controllers

import io.quarkus.qute.TemplateExtension
import sh.hutch.postmaster.models.Setting

// Helpers used in templates

/** An HTML id for the setting's value, e.g. `setting-imap-password`. */
@TemplateExtension
fun domId(setting: Setting): String = "setting-" + setting.key.replace(Regex("[^A-Za-z0-9_-]"), "-")

/** Whether the setting is encrypted. */
@TemplateExtension
fun encrypted(setting: Setting): Boolean = setting.type.code == "encrypted"
