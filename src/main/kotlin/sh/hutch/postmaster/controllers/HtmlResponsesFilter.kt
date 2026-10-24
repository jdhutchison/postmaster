package sh.hutch.postmaster.controllers

import org.eclipse.microprofile.openapi.OASFactory
import org.eclipse.microprofile.openapi.OASFilter
import org.eclipse.microprofile.openapi.models.OpenAPI
import org.eclipse.microprofile.openapi.models.media.Content
import org.eclipse.microprofile.openapi.models.media.Schema

/**
 * Describes HTML responses in the OpenAPI document as strings. Pages and fragments return Qute
 * `TemplateInstance`s, which would otherwise be documented by Qute's internals; the component
 * schemas no operation refers to any more are removed. Registered with `mp.openapi.filter`.
 */
class HtmlResponsesFilter : OASFilter {
  override fun filterOpenAPI(openAPI: OpenAPI) {
    val used = mutableListOf<Schema>()
    openAPI.paths?.pathItems?.values?.forEach { item ->
      item.operations?.values?.forEach { operation ->
        operation.responses?.apiResponses?.values?.forEach { response ->
          response.content?.mediaTypes?.forEach { (type, media) ->
            if (type.startsWith("text/html")) {
              media.schema = OASFactory.createSchema().type(listOf(Schema.SchemaType.STRING))
            }
          }
          used += schemas(response.content)
        }
        used += schemas(operation.requestBody?.content)
        operation.parameters?.forEach { parameter -> parameter.schema?.let { used += it } }
      }
    }
    val components = openAPI.components?.schemas ?: return
    val kept = mutableSetOf<String>()
    val pending = ArrayDeque(used)
    while (pending.isNotEmpty()) {
      val schema = pending.removeFirst()
      schema.ref?.substringAfterLast('/')?.let { name ->
        if (kept.add(name)) {
          components[name]?.let { pending += it }
        }
      }
      pending += nested(schema)
    }
    (components.keys - kept).forEach { openAPI.components.removeSchema(it) }
  }

  private fun schemas(content: Content?): List<Schema> = content?.mediaTypes?.values?.mapNotNull { it.schema }.orEmpty()

  private fun nested(schema: Schema): List<Schema> =
    listOfNotNull(schema.items, schema.additionalPropertiesSchema) +
        schema.properties?.values.orEmpty() + schema.allOf.orEmpty() + schema.anyOf.orEmpty() + schema.oneOf.orEmpty()
}
