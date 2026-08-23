package dev.libredirect.mobile.core.url

import org.junit.Assert.assertNull
import org.junit.Test

class QueryStringTest {
    @Test
    fun `malformed percent escape is treated as an unusable value`() {
        assertNull(QueryString.find("q=%ZZ", "q"))
    }
}
