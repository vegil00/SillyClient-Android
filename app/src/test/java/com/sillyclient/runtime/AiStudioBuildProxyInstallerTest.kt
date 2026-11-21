package com.sillyclient.runtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class AiStudioBuildProxyInstallerTest {
    private val assets = File("src/main/assets/${AiStudioBuildProxyInstaller.ASSET_ROOT}")

    private fun readAsset(path: String): ByteArray {
        val relative = path.removePrefix("${AiStudioBuildProxyInstaller.ASSET_ROOT}/")
        val file = File(assets, relative)
        require(file.isFile) { "Asset file not found: ${file.absolutePath}" }
        return file.readBytes()
    }

    private fun instance(): File {
        val root = Files.createTempDirectory("sillyclient-aistudio-proxy").toFile()
        File(root, "server.js").writeText("export {}\n")
        return root
    }

    @Test
    fun bundledManifestMatchesPinnedCommits() {
        val manifest = JSONObject(readAsset("${AiStudioBuildProxyInstaller.ASSET_ROOT}/manifest.json")
            .toString(StandardCharsets.UTF_8))
        assertEquals(AiStudioBuildProxyInstaller.ID, manifest.getString("id"))
        assertEquals(AiStudioBuildProxyInstaller.REVISION, manifest.getInt("revision"))
        assertEquals(AiStudioBuildProxyInstaller.SERVER_COMMIT, manifest.getString("serverCommit"))
        assertEquals(AiStudioBuildProxyInstaller.CLIENT_COMMIT, manifest.getString("clientCommit"))
        val clientManifest = JSONObject(readAsset(
            "${AiStudioBuildProxyInstaller.ASSET_ROOT}/client/manifest.json"
        ).toString(StandardCharsets.UTF_8))
        assertFalse(clientManifest.getBoolean("auto_update"))
        assertTrue(readAsset("${AiStudioBuildProxyInstaller.ASSET_ROOT}/server/server.js")
            .toString(StandardCharsets.UTF_8).contains("127.0.0.1"))
    }

    @Test
    fun freshInstallWritesServerPluginAndClientExtension() {
        val serverDir = instance()
        try {
            val transaction = AiStudioBuildProxyInstaller.installInternal(::readAsset, serverDir)
            assertTrue(transaction.applied)
            assertFalse(transaction.keptExisting)
            transaction.commit()

            val serverEntry = File(serverDir, "plugins/AIStudioBuildProxy/index.js")
            val clientManifest = File(serverDir, "public/scripts/extensions/third-party/AIStudioBuildProxy/manifest.json")
            val license = File(serverDir, "plugins/AIStudioBuildProxy/LICENSE")
            assertTrue(serverEntry.isFile)
            assertTrue(clientManifest.isFile)
            assertTrue(license.isFile)
            assertEquals(readAsset("${AiStudioBuildProxyInstaller.ASSET_ROOT}/server/index.js").toList(), serverEntry.readBytes().toList())
            assertFalse(JSONObject(clientManifest.readText()).getBoolean("auto_update"))
            assertEquals(1, JSONObject(File(serverDir, "plugins/AIStudioBuildProxy/.sillyclient-bundle.json").readText()).getInt("revision"))

            val again = AiStudioBuildProxyInstaller.installInternal(::readAsset, serverDir)
            assertFalse(again.applied)
            assertTrue(again.keptExisting)
            again.commit()
        } finally {
            serverDir.deleteRecursively()
        }
    }

    @Test
    fun existingUserCopyIsPreserved() {
        val serverDir = instance()
        try {
            val serverPlugin = File(serverDir, "plugins/AIStudioBuildProxy").apply { mkdirs() }
            val clientExtension = File(serverDir, "public/scripts/extensions/third-party/AIStudioBuildProxy").apply { mkdirs() }
            File(serverPlugin, "index.js").writeText("user-server")
            File(clientExtension, "manifest.json").writeText("""{"auto_update":true,"js":"index.js"}""")
            File(serverPlugin, "notes.txt").writeText("keep")

            val transaction = AiStudioBuildProxyInstaller.installInternal(::readAsset, serverDir)
            assertFalse(transaction.applied)
            assertTrue(transaction.keptExisting)
            transaction.rollback()

            assertEquals("user-server", File(serverPlugin, "index.js").readText())
            assertEquals("keep", File(serverPlugin, "notes.txt").readText())
            assertTrue(JSONObject(File(clientExtension, "manifest.json").readText()).getBoolean("auto_update"))
            assertFalse(File(serverPlugin, ".sillyclient-bundle.json").exists())
        } finally {
            serverDir.deleteRecursively()
        }
    }

    @Test
    fun rollbackRemovesOnlyTheFilesThisInstallCreated() {
        val serverDir = instance()
        try {
            val transaction = AiStudioBuildProxyInstaller.installInternal(::readAsset, serverDir)
            assertTrue(transaction.applied)
            transaction.rollback()
            assertFalse(File(serverDir, "plugins/AIStudioBuildProxy").exists())
            assertFalse(File(serverDir, "public/scripts/extensions/third-party/AIStudioBuildProxy").exists())
            assertTrue(File(serverDir, "server.js").isFile)
        } finally {
            serverDir.deleteRecursively()
        }
    }

    @Test
    fun ownedOlderRevisionIsReplacedAndCanRollBack() {
        val serverDir = instance()
        try {
            val serverPlugin = File(serverDir, "plugins/AIStudioBuildProxy").apply { mkdirs() }
            File(serverPlugin, "index.js").writeText("old-server")
            File(serverPlugin, ".sillyclient-bundle.json").writeText(
                """{"schema":"sillyclient.bundled-plugin-owned","version":1,"id":"ai-studio-build-proxy","revision":0,"ownership":"previous"}"""
            )
            val transaction = AiStudioBuildProxyInstaller.installInternal(::readAsset, serverDir)
            assertTrue(transaction.applied)
            assertFalse(transaction.keptExisting)
            assertEquals(
                readAsset("${AiStudioBuildProxyInstaller.ASSET_ROOT}/server/index.js").decodeToString(),
                File(serverPlugin, "index.js").readText()
            )
            assertTrue(File(serverDir, "public/scripts/extensions/third-party/AIStudioBuildProxy/index.js").isFile)

            transaction.rollback()
            assertEquals("old-server", File(serverPlugin, "index.js").readText())
            assertEquals(0, JSONObject(File(serverPlugin, ".sillyclient-bundle.json").readText()).getInt("revision"))
            assertFalse(File(serverDir, "public/scripts/extensions/third-party/AIStudioBuildProxy").exists())
        } finally {
            serverDir.deleteRecursively()
        }
    }

    @Test
    fun interruptedInstallRollsBackBothSides() {
        val serverDir = instance()
        try {
            var clientReads = 0
            val error = runCatching {
                AiStudioBuildProxyInstaller.installInternal({ path ->
                    if ("/client/" in path) {
                        clientReads += 1
                        if (clientReads == 2) throw IOException("interrupted")
                    }
                    readAsset(path)
                }, serverDir)
            }.exceptionOrNull()
            assertTrue(error is IOException)
            assertFalse(File(serverDir, "plugins/AIStudioBuildProxy").exists())
            assertFalse(File(serverDir, "public/scripts/extensions/third-party/AIStudioBuildProxy").exists())
        } finally {
            serverDir.deleteRecursively()
        }
    }
}
