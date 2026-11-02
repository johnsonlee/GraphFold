package io.johnsonlee.graphfold.engine.source

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class PhysicalKotlinPsiTextTest {
    @Test fun `leading BOM and CR separators become whitespace without shifting physical UTF16 offsets`() {
        val text = "\uFEFF// 😀\r\n\tval text = \"\uFEFF\"\rfun value() = 1"
        val parsed = physicalKotlinPsiText(text)
        assertEquals(" // 😀\n\n\tval text = \"\uFEFF\"\nfun value() = 1", parsed)
        assertEquals(text.length, parsed.length)
        assertEquals(text.indexOf("val"), parsed.indexOf("val"))
        assertEquals(text.indexOf("fun"), parsed.indexOf("fun"))
    }

    @Test fun `empty and LF inputs need no coordinate adaptation and embedded BOM is preserved`() {
        listOf("", "package sample\nfun value() = 1", "val value = \"\uFEFF😀\"").forEach { text ->
            assertEquals(text, physicalKotlinPsiText(text))
        }
        assertEquals(" \uFEFF", physicalKotlinPsiText("\uFEFF\uFEFF"))
    }
}
