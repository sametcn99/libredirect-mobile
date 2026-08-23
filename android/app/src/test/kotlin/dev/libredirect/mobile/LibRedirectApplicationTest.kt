package dev.libredirect.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class LibRedirectApplicationTest {
    @Test
    fun `application package matches namespace`() {
        assertEquals("dev.libredirect.mobile", LibRedirectApplication::class.java.`package`?.name)
    }
}
