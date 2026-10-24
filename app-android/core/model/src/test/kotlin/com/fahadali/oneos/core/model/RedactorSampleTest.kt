package com.fahadali.oneos.core.model

import org.junit.Assert.assertTrue
import org.junit.Test

/** The whole synthetic Samsung capture of [RedactorSample] through the redactor. */
class RedactorSampleTest {
    private fun redactor() = Redactor("sample-salt".toByteArray())

    @Test
    fun noPlantedSecretSurvivesAnyFile() {
        val out = RedactorSample.redactAll(redactor())
        val leaks = out.flatMap { (rel, text) ->
            RedactorSample.secrets.filter { text.contains(it, ignoreCase = true) }.map { "$rel: $it" }
        }
        assertTrue("leaked:\n" + leaks.joinToString("\n"), leaks.isEmpty())
    }

    @Test
    fun technicalLinesComeThroughUnchanged() {
        val out = RedactorSample.redactAll(redactor())
        val lost = RedactorSample.kept.flatMap { (rel, lines) ->
            val got = out.getValue(rel).lines().toSet()
            lines.filterNot { it in got }.map { "$rel: $it" }
        }
        assertTrue("changed:\n" + lost.joinToString("\n"), lost.isEmpty())
    }
}
