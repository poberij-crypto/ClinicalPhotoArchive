package com.clinicalphotoarchive.data

import org.junit.Assert.*
import org.junit.Test

class CategoryNamesTest {
    @Test fun normalizesUnicodeWhitespaceAndCase() {
        val name = CategoryNames.normalize("  Диабетическая\u00a0 \tстопа  ")
        assertEquals("Диабетическая стопа", name.name)
        assertEquals("диабетическая стопа", name.key)
    }
    @Test fun normalizesCanonicalUnicode() {
        assertEquals("Й", CategoryNames.normalize("И\u0306").name)
    }
    @Test fun rejectsBlankReservedAndTooLongNames() {
        for (input in listOf("\u00a0", " БЕЗ   КАТЕГОРИИ ", "a".repeat(101))) {
            assertThrows(IllegalArgumentException::class.java) { CategoryNames.normalize(input) }
        }
        assertEquals(100, CategoryNames.normalize("a".repeat(100)).name.length)
    }
}
