package com.sillyclient.runtime

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/**
 * 把 [AIStudioBuildProxy](https://github.com/starowo/AIStudioBuildProxy) 写进实例。
 *
 * 服务端固定在 `server` 分支 `edb301450724b0a194bfb55d5533682a12f4e5b6`，
 * 客户端固定在 `client` 分支 `c5d6d32698a62553816ba7ab46eb90bdd2c302a9`。
 * `express` 与 `ws` 由酒馆根目录的 `node_modules` 提供，不另装依赖，也不打包上游安装 zip。
 * 客户端清单关闭 `auto_update`：Android 没有 git，开启后酒馆每次启动都会尝试更新并弹出失败提示。
 * 已有同名目录且不是本安装器写入的旧版本时，保留用户文件。
 */
class AiStudioBuildProxyTransaction internal constructor(
    val applied: Boolean,
    val keptExisting: Boolean,
    private val rollbackAction: () -> Unit
) {
    private var active = applied

    @Synchronized
    fun commit() {
        active = false
    }

    @Synchronized
    fun rollback() {
        if (!active) return
        rollbackAction()
        active = false
    }

    companion object {
        fun noOp(keptExisting: Boolean) = AiStudioBuildProxyTransaction(false, keptExisting) {}
    }
}

object AiStudioBuildProxyInstaller {
    const val ID = "ai-studio-build-proxy"
    const val REVISION = 1
    const val ASSET_ROOT = "bundled-plugins/ai-studio-build-proxy"
    const val SERVER_COMMIT = "edb301450724b0a194bfb55d5533682a12f4e5b6"
    const val CLIENT_COMMIT = "c5d6d32698a62553816ba7ab46eb90bdd2c302a9"

    private const val MANIFEST_SHA256 = "3A80EB504F315FB1628FA06A40F3CEE25DB7B13B79A87C2B3089FC505258000B"
    private const val MARKER_NAME = ".sillyclient-bundle.json"
    private const val MARKER_SCHEMA = "sillyclient.bundled-plugin-owned"
    private const val MAX_FILE = 1024 * 1024

    private data class Side(
        val assetDir: String,
        val target: String,
        val entry: String,
        val files: Map<String, String>
    )

    private data class Snapshot(val file: File, val previous: ByteArray?)

    private data class Published(
        val directory: File,
        val createdDirectory: Boolean,
        val ownership: String,
        val hashes: Map<String, String>,
        val snapshots: List<Snapshot>
    )

    fun install(context: Context, serverDir: File): AiStudioBuildProxyTransaction =
        installInternal({ path -> context.assets.open(path).use { it.readBytes() } }, serverDir)

    internal fun installInternal(
        assetReader: (String) -> ByteArray,
        serverDir: File
    ): AiStudioBuildProxyTransaction {
        require(regularFile(File(serverDir, "server.js"))) { "实例还没有 server.js，不能内置 AI Studio 反代" }
        val manifestBytes = readAsset(assetReader, "$ASSET_ROOT/manifest.json")
        require(sha256(manifestBytes) == MANIFEST_SHA256) { "内置 AI Studio 反代清单校验失败" }
        val manifest = JSONObject(manifestBytes.toString(StandardCharsets.UTF_8))
        require(
            manifest.optString("schema") == "sillyclient.bundled-plugin" &&
                manifest.optInt("version") == 1 &&
                manifest.optString("id") == ID &&
                manifest.optInt("revision") == REVISION &&
                manifest.optString("repository") == "starowo/AIStudioBuildProxy" &&
                manifest.optString("license") == "MIT" &&
                manifest.optString("serverCommit") == SERVER_COMMIT &&
                manifest.optString("clientCommit") == CLIENT_COMMIT
        ) { "内置 AI Studio 反代清单校验失败" }
        val sides = listOf(
            side(manifest, "server"),
            side(manifest, "client")
        )
        val published = mutableListOf<Published>()
        var keptExisting = false
        try {
            for (pluginSide in sides) {
                val result = publish(pluginSide, serverDir, assetReader)
                if (result == null) keptExisting = true else published.add(result)
            }
            val transaction = AiStudioBuildProxyTransaction(
                applied = published.isNotEmpty(),
                keptExisting = keptExisting,
                rollbackAction = { published.asReversed().forEach(::rollback) }
            )
            return transaction
        } catch (error: Exception) {
            published.asReversed().forEach { item ->
                runCatching { rollback(item) }.onFailure { error.addSuppressed(it) }
            }
            throw error
        }
    }

    private fun side(manifest: JSONObject, name: String): Side {
        val record = manifest.getJSONObject(name)
        val filesJson = record.getJSONObject("files")
        val files = filesJson.keys().asSequence().associate { fileName ->
            validateFileName(fileName)
            val hash = filesJson.getString(fileName).uppercase()
            require(hash.matches(Regex("[A-F0-9]{64}"))) { "内置 AI Studio 反代清单校验失败" }
            fileName to hash
        }
        val entry = record.getString("entry")
        require(entry in files) { "内置 AI Studio 反代清单校验失败" }
        return Side(name, record.getString("target"), entry, files)
    }

    private fun publish(side: Side, serverDir: File, assetReader: (String) -> ByteArray): Published? {
        if (Files.isSymbolicLink(File(serverDir, side.target).toPath())) return null
        val directory = resolveInside(serverDir, side.target)
        if (directory.exists() && !directory.isDirectory) {
            throw IllegalStateException("AI Studio 反代目标不是目录")
        }
        val entry = resolveInside(directory, side.entry)
        val marker = resolveInside(directory, MARKER_NAME)
        val ownedRevision = ownedRevision(marker)
        if (regularFile(entry) && (ownedRevision == null || ownedRevision >= REVISION)) return null
        if (hasLink(directory, side.files.keys + MARKER_NAME)) return null
        val ownership = UUID.randomUUID().toString()
        val createdDirectory = !directory.exists()
        val snapshots = (side.files.keys + MARKER_NAME).map { name ->
            val file = resolveInside(directory, name)
            Snapshot(file, if (regularFile(file)) file.readBytes() else null)
        }
        val hashes = linkedMapOf<String, String>()
        val published = Published(directory, createdDirectory, ownership, hashes, snapshots)
        try {
            directory.mkdirs()
            require(directory.isDirectory && ManagedFiles.isWithin(directory, serverDir)) { "AI Studio 反代路径越界" }
            for ((name, expected) in side.files) {
                val bytes = readAsset(assetReader, "$ASSET_ROOT/${side.assetDir}/$name")
                require(sha256(bytes) == expected) { "内置 AI Studio 反代资源校验失败" }
                if (side.assetDir == "client" && name == "manifest.json") {
                    val clientManifest = JSONObject(bytes.toString(StandardCharsets.UTF_8))
                    require(clientManifest.has("auto_update") && !clientManifest.getBoolean("auto_update")) {
                        "内置 AI Studio 反代资源校验失败"
                    }
                }
                val target = resolveInside(directory, name)
                writeAtomic(target, bytes)
                hashes[name] = sha256(bytes)
            }
            val markerJson = JSONObject()
                .put("schema", MARKER_SCHEMA)
                .put("version", 1)
                .put("id", ID)
                .put("revision", REVISION)
                .put("ownership", ownership)
            writeAtomic(marker, "${markerJson.toString()}\n".toByteArray(StandardCharsets.UTF_8))
            return published
        } catch (error: Exception) {
            runCatching { restorePublished(published, strict = false) }.onFailure { error.addSuppressed(it) }
            throw error
        }
    }

    private fun rollback(published: Published) {
        restorePublished(published, strict = true)
    }

    private fun restorePublished(published: Published, strict: Boolean) {
        if (strict) {
            val marker = File(published.directory, MARKER_NAME)
            if (!regularFile(marker) || ownedOwnership(marker) != published.ownership) return
            for ((name, hash) in published.hashes) {
                val file = File(published.directory, name)
                if (!regularFile(file) || sha256(file.readBytes()) != hash) return
            }
        }
        published.snapshots.asReversed().forEach { restore(it) }
        val remaining = published.directory.list()
        if (published.createdDirectory && remaining != null && remaining.isEmpty() && !published.directory.delete() && strict) {
            throw IllegalStateException("AI Studio 反代回滚后目录未删除")
        }
    }

    private fun ownedRevision(marker: File): Int? {
        val json = readMarker(marker) ?: return null
        return json.optInt("revision", -1).takeIf { it >= 0 }
    }

    private fun ownedOwnership(marker: File): String? = readMarker(marker)?.optString("ownership")

    private fun readMarker(marker: File): JSONObject? {
        if (!regularFile(marker) || marker.length() > 4096) return null
        return runCatching {
            val json = JSONObject(marker.readText())
            if (json.optString("schema") != MARKER_SCHEMA || json.optString("id") != ID) null else json
        }.getOrNull()
    }

    private fun hasLink(directory: File, names: Iterable<String>): Boolean {
        if (Files.isSymbolicLink(directory.toPath())) return true
        return names.any { name ->
            val file = File(directory, name)
            file.exists() && Files.isSymbolicLink(file.toPath())
        }
    }

    private fun readAsset(assetReader: (String) -> ByteArray, path: String): ByteArray {
        val bytes = assetReader(path)
        require(bytes.size in 1..MAX_FILE) { "内置 AI Studio 反代资源校验失败" }
        return bytes
    }

    private fun validateFileName(name: String) {
        require(name.matches(Regex("[A-Za-z0-9._-]+")) && name != "." && name != "..") {
            "内置 AI Studio 反代清单校验失败"
        }
    }

    private fun resolveInside(root: File, relativePath: String): File {
        require(
            relativePath.isNotBlank() &&
                !relativePath.startsWith("/") &&
                !relativePath.contains('\\') &&
                relativePath.split('/').none { it.isEmpty() || it == "." || it == ".." }
        ) { "AI Studio 反代路径无效" }
        val resolved = File(root, relativePath)
        require(ManagedFiles.isWithin(resolved, root)) { "AI Studio 反代路径越界" }
        return resolved
    }

    private fun regularFile(file: File): Boolean =
        Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02X".format(it) }

    private fun writeAtomic(target: File, bytes: ByteArray) {
        val parent = requireNotNull(target.parentFile) { "AI Studio 反代路径无效" }
        check(parent.isDirectory || parent.mkdirs()) { "无法创建 AI Studio 反代目录" }
        val temporary = File(parent, ".${target.name}.${UUID.randomUUID()}.tmp")
        temporary.writeBytes(bytes)
        try {
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: Exception) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun restore(snapshot: Snapshot) {
        if (snapshot.previous == null) {
            if (regularFile(snapshot.file)) check(snapshot.file.delete()) { "无法回滚 AI Studio 反代文件" }
        } else {
            writeAtomic(snapshot.file, snapshot.previous)
        }
    }
}
