package dev.libredirect.mobile.core.url

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueryStringTest {
    @Test
    fun `malformed percent escape is treated as an unusable value`() {
        assertNull(QueryString.find("q=%ZZ", "q"))
    }

    @Test
    fun `percent-encoded names are decoded before matching`() {
        assertEquals("a b", QueryString.find("%71=a+b", "q"))
    }

    @Test
    fun `plus is decoded as a space in values`() {
        assertEquals("kitten videos", QueryString.find("q=kitten+videos", "q"))
    }

    @Test
    fun `non-ASCII values round-trip through encode and find`() {
        val encoded = QueryString.encode("çift güven")
        assertEquals("çift güven", QueryString.find("k=$encoded", "k"))
    }
}
