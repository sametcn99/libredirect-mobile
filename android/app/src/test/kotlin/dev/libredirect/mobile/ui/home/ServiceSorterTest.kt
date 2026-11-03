package dev.libredirect.mobile.ui.home

import dev.libredirect.mobile.ui.ServiceUiState
import org.junit.Assert.assertEquals
import org.junit.Test

class ServiceSorterTest {
    private fun service(
        id: String,
        name: String,
    ): ServiceUiState =
        ServiceUiState(
            routeId = id,
            name = name,
            enabled = true,
            frontends = emptyList(),
            selectedFrontendId = "",
        )

    @Test
    fun `popularity puts the most-used services first`() {
        val services =
            listOf(
                service("wikipedia", "Wikipedia"),
                service("reddit", "Reddit"),
                service("youtube", "YouTube"),
                service("urban-dictionary", "Urban Dictionary"),
                service("instagram", "Instagram"),
                service("twitter", "Twitter"),
            )

        val sorted = ServiceSorter.sort(services, ServiceSort.POPULARITY)

        assertEquals(
            listOf("YouTube", "Instagram", "Reddit", "Twitter", "Wikipedia", "Urban Dictionary"),
            sorted.map { it.name },
        )
    }

    @Test
    fun `popularity matching is case-insensitive`() {
        val services =
            listOf(
                service("other", "some service"),
                service("youtube", "youtube"),
            )

        val sorted = ServiceSorter.sort(services, ServiceSort.POPULARITY)

        assertEquals(listOf("youtube", "some service"), sorted.map { it.name })
    }

    @Test
    fun `unranked services keep their original relative order`() {
        val services =
            listOf(
                service("zeta", "Zeta"),
                service("alpha", "Alpha"),
                service("youtube", "YouTube"),
                service("mid", "Midway"),
            )

        val sorted = ServiceSorter.sort(services, ServiceSort.POPULARITY)

        assertEquals(listOf("YouTube", "Zeta", "Alpha", "Midway"), sorted.map { it.name })
    }

    @Test
    fun `name ascending sorts case-insensitively`() {
        val services =
            listOf(
                service("c", "cloUd"),
                service("a", "Apple"),
                service("b", "banana"),
            )

        val sorted = ServiceSorter.sort(services, ServiceSort.NAME_ASC)

        assertEquals(listOf("Apple", "banana", "cloUd"), sorted.map { it.name })
    }
}
