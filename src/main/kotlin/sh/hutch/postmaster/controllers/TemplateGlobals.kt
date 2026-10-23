@file:JvmName("TemplateGlobals")

package sh.hutch.postmaster.controllers

import io.quarkus.qute.TemplateGlobal
import org.eclipse.microprofile.config.ConfigProvider

// Values every template can use: {base} and {appVersion}

private val rootPath: String by lazy {
  ConfigProvider.getConfig().getOptionalValue("quarkus.http.root-path", String::class.java).orElse("/").trimEnd('/')
}

private val version: String by lazy {
  ConfigProvider.getConfig().getOptionalValue("quarkus.application.version", String::class.java).orElse("dev")
}

/** The root path without a trailing slash, e.g. `/mail`; prefix links with it. */
@TemplateGlobal
fun base(): String = rootPath

/** The application version, used to bust caches of the CSS and JavaScript. */
@TemplateGlobal
fun appVersion(): String = version
