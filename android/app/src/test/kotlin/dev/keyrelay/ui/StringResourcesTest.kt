package dev.keyrelay.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

class StringResourcesTest {
    private fun keys(path: String) = Regex("<(?:string|plurals) name=\"([^\"]+)\"").findAll(File(path).readText()).map { it.groupValues[1] }.toSortedSet()

    @Test
    fun turkishStrings_haveSameKeysAsDefault() =
        assertEquals(keys("src/main/res/values/strings.xml"), keys("src/main/res/values-tr/strings.xml"))
}
