package com.medlenx.lab.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class MedexSearchTest {
    @Test fun formatsPrefixesAndNeutralizesFtsOperators() {
        assertEquals("\"Napa*\" \"Extra*\"", medexMatchExpression("Napa Extra"))
        assertEquals("\"OR*\" \"foo*\"", medexMatchExpression("OR \"foo\"*"))
        assertEquals("", medexMatchExpression("  - * \"  "))
        assertEquals("\"বাংলা*\" \"250*\"", medexMatchExpression("বাংলা 250"))
    }
}
