package sh.hutch.postmaster.services

import io.agroal.api.AgroalDataSource
import io.quarkus.test.TestTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.ws.rs.NotFoundException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import sh.hutch.postmaster.models.TypeInput
import sh.hutch.postmaster.repositories.TypeRepository
import sh.hutch.postmaster.testsupport.row

// REQ Types 3-8; CLAR D2 (colour by picker or hex, description optional,
// names unique, soft delete), Q16 (Bill can't be deleted), D20 (names unique
// ignoring case, enforced in the service)
@QuarkusTest
class TypeServiceTest {
  @Inject
  lateinit var service: TypeService

  @Inject
  lateinit var types: TypeRepository

  @Inject
  lateinit var dataSource: AgroalDataSource

  @Inject
  lateinit var entityManager: EntityManager

  @Test
  @TestTransaction
  fun `a type is created with a name, optional description and a colour`() {
    val type = service.create(TypeInput("School camp", "  ", "#ABCDEF"))

    assertEquals("School camp", type.name)
    assertNull(type.description, "a blank description is stored as none (CLAR D2)")
    assertEquals("#abcdef", type.colour)
  }

  @Test
  fun `a name is required and limited to 100 characters`() {
    val blank = assertThrows<ValidationException> { service.create(TypeInput(" ", null, "#123456")) }
    assertEquals(setOf("name"), blank.errors.keys)
    assertThrows<ValidationException> { service.create(TypeInput("x".repeat(101), null, "#123456")) }
    service.create(TypeInput("y".repeat(100), null, "#123456"))
  }

  @Test
  fun `the colour must be an RGB hex code`() {
    // REQ Types 8: "set directly as a RGB hex code or through an HTML colour picker"
    for (colour in listOf(null, "", "red", "#12345", "#1234567", "123456", "#GGGGGG")) {
      val error = assertThrows<ValidationException> { service.create(TypeInput("Colour $colour", null, colour)) }
      assertTrue("colour" in error.errors, "$colour rejected: ${error.errors}")
    }
  }

  @Test
  fun `names that differ only in case are rejected`() {
    // CLAR D20
    val error = assertThrows<DuplicateNameException> { service.create(TypeInput("BILL", null, "#123456")) }

    assertEquals("name", error.field)
    service.create(TypeInput("Camping", null, "#123456"))
    assertThrows<DuplicateNameException> { service.create(TypeInput("camping", null, "#123456")) }
  }

  @Test
  fun `renaming onto another type's name is rejected, but changing a type's own case is not`() {
    val ebay = types.findActiveByName("Ebay")!!

    assertThrows<DuplicateNameException> { service.update(ebay.id!!, TypeInput("payslip", null, "#123456")) }
    assertEquals("EBAY", service.update(ebay.id!!, TypeInput("EBAY", "Ebay business", "#123456")).name)
  }

  @Test
  @TestTransaction
  fun `a deleted type's name can be used again`() {
    // CLAR D20: only active types count
    val showfit = types.findActiveByName("Showfit")!!
    service.delete(showfit.id!!)

    assertEquals("showfit", service.create(TypeInput("showfit", null, "#123456")).name)
  }

  @Test
  fun `deleting a type keeps it in the database, marked deleted`() {
    // REQ Types 5: "soft deleted, not removed from the database"
    val legal = types.findActiveByName("Legal")!!

    service.delete(legal.id!!)

    val row = dataSource.row("SELECT deleted, deleted_at FROM Types WHERE id = ?", legal.id)
    assertEquals(true, row["deleted"])
    assertTrue(row["deleted_at"] != null)
    assertTrue(service.list().none { it.id == legal.id })
    assertThrows<NotFoundException> { service.get(legal.id!!) }
  }

  @Test
  fun `the Bill type can't be deleted`() {
    // CLAR Q16: Bill is a system type
    assertThrows<OperationNotAllowedException> { service.delete(types.findBill()!!.id!!) }
  }

  @Test
  fun `unknown types aren't found`() {
    assertThrows<NotFoundException> { service.get(999_999) }
    assertThrows<NotFoundException> { service.update(999_999, TypeInput("X", null, "#123456")) }
    assertThrows<NotFoundException> { service.delete(999_999) }
  }
}
