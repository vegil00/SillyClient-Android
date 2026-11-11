package com.sillyclient.ui

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class DeepSeekModelOptionsTest {

    @Test
    fun catalogKeepsCurrentModelsAheadOfAcceptedLegacyNames() {
        assertEquals(
            listOf(
                "deepseek-flash",
                "deepseek-v4-pro",
                "deepseek-v4-flash",
                "deepseek-v4-flash-vision-exp",
            ),
            DeepSeekModelOptions.modelIds,
        )
    }

    @Test
    fun missingIdsPreservesCatalogOrderAndLeavesApiModelsUntouched() {
        assertEquals(DeepSeekModelOptions.modelIds, DeepSeekModelOptions.missingIds(emptyList()))
        assertEquals(
            listOf("deepseek-v4-pro", "deepseek-v4-flash-vision-exp"),
            DeepSeekModelOptions.missingIds(
                listOf("deepseek-flash", "custom-router", "deepseek-v4-flash"),
            ),
        )
        assertTrue(DeepSeekModelOptions.missingIds(DeepSeekModelOptions.modelIds).isEmpty())
    }

    @Test
    fun scriptNamesTheDeepSeekSelectAndEmbedsTheCatalogOnce() {
        val script = DeepSeekModelOptions.script()
        val encoded = JSONArray(DeepSeekModelOptions.modelIds).toString()
        assertTrue(script.contains("const models = $encoded;"))
        assertTrue(script.contains("const selectId = '${DeepSeekModelOptions.SELECT_ID}';"))
        assertFalse(script.contains("dispatchEvent"))
        assertEquals(1, Regex("deepseek-v4-flash-vision-exp").findAll(script).count())
    }

    @Test
    fun scriptFillsPlaceholderWithoutClobberingAConnectedSelection() {
        val node = locateNode()
        assumeTrue("node is required to execute the injected script", node != null)
        val executable = checkNotNull(node)

        val harness = File.createTempFile("deepseek-model-options", ".mjs")
        val generated = File.createTempFile("deepseek-model-options", ".js")
        harness.deleteOnExit()
        generated.deleteOnExit()
        javaClass.getResourceAsStream("/deepseek-model-options.test.mjs")!!.use { input ->
            harness.writeBytes(input.readBytes())
        }
        generated.writeText(DeepSeekModelOptions.script())

        val process = ProcessBuilder(
            executable,
            harness.absolutePath,
            generated.absolutePath,
            JSONArray(DeepSeekModelOptions.modelIds).toString(),
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        assertEquals(output, 0, exit)
        assertTrue(output.contains("deepseek model options ok"))
    }

    private fun locateNode(): String? {
        val candidates = listOfNotNull(System.getenv("NODE"), "node")
        return candidates.firstOrNull { candidate ->
            runCatching {
                ProcessBuilder(candidate, "--version").start().waitFor() == 0
            }.getOrDefault(false)
        }
    }
}
