package sh.hutch.postmaster.config

import io.quarkus.runtime.annotations.RegisterForReflection

/**
 * Lets Jakarta Mail find its providers in the native image. Quarkus turns off GraalVM's automatic
 * `ServiceLoader` support, so the `META-INF/services` files Angus ships aren't included and every
 * IMAP or SMTP connection fails with "No provider of jakarta.mail.util.StreamProvider was found".
 * The files are included with `quarkus.native.resources.includes` (application.yaml), and the
 * classes they name are registered here so `ServiceLoader` can create them.
 */
@RegisterForReflection(
    classNames = [
      // META-INF/services/jakarta.mail.util.StreamProvider
      "org.eclipse.angus.mail.util.MailStreamProvider",
      // META-INF/services/jakarta.mail.Provider
      "org.eclipse.angus.mail.imap.IMAPProvider",
      "org.eclipse.angus.mail.imap.IMAPSSLProvider",
      "org.eclipse.angus.mail.smtp.SMTPProvider",
      "org.eclipse.angus.mail.smtp.SMTPSSLProvider",
      // META-INF/services/jakarta.activation.spi.*
      "org.eclipse.angus.activation.MailcapRegistryProviderImpl",
      "org.eclipse.angus.activation.MimeTypeRegistryProviderImpl"
    ]
)
class MailNativeImage
