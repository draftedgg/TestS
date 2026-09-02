package com.mcpanel

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Minimal HTTP + API layer. HttpURLConnection only, no third-party deps. */
object Apis {
    const val PAPER_API = "https://api.papermc.io/v2"
    const val FABRIC_META = "https://meta.fabricmc.net/v2"
    const val FORGE_PROMOS = "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json"
    const val FORGE_MAVEN = "https://maven.minecraftforge.net"
    const val NEOFORGE_MAVEN = "https://maven.neoforged.net"
    const val MODRINTH = "https://api.modrinth.com/v2"

    fun get(url: String, timeoutMs: Int = 15000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.setRequestProperty("User-Agent", "MCPanel/0.1 (Android sideload; Termux Minecraft panel)")
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode} $url")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    /** Streamed download with 3 retries + jar magic-byte validation. Returns true on success. */
    fun downloadToFile(url: String, dest: File): Boolean {
        dest.parentFile?.mkdirs()
        for (attempt in 1..3) {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 20000
                c.readTimeout = 30000
                c.setRequestProperty("User-Agent", "MCPanel/0.1")
                try {
                    if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
                    dest.outputStream().use { out -> c.inputStream.use { it.copyTo(out, 65536) } }
                } finally { c.disconnect() }
                if (dest.length() > 0 && isJar(dest)) return true
            } catch (_: Exception) { dest.delete() }
        }
        return dest.exists() && dest.length() > 0 && isJar(dest)
    }

    private fun isJar(f: File): Boolean = try {
        f.inputStream().use { s ->
            val b = ByteArray(2); s.read(b) == 2 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()
        }
    } catch (_: Exception) { false }

    // ── Paper ─────────────────────────────────────────────────────────
    private fun versionRank(v: String): List<Int> = v.split('.').map { it.toIntOrNull() ?: 0 }

    fun paperVersions(): List<String> = try {
        val a = JSONObject(get("$PAPER_API/projects/paper")).getJSONArray("versions")
        (0 until a.length()).map { a.getString(it) }
            .filter { it.matches(Regex("\\d+\\.\\d+(\\.\\d+)?")) }
            .sortedByDescending { versionRank(it) }
    } catch (_: Exception) { emptyList() }

    fun paperLatestBuild(version: String): String? = try {
        val builds = JSONObject(get("$PAPER_API/projects/paper/versions/$version/builds")).getJSONArray("builds")
        builds.getJSONObject(builds.length() - 1).getInt("build").toString()
    } catch (_: Exception) { null }

    fun paperJarUrl(version: String, build: String) =
        "$PAPER_API/projects/paper/versions/$version/builds/$build/downloads/paper-$version-$build.jar"

    // ── Fabric ────────────────────────────────────────────────────────
    fun fabricVersions(): List<String> = try {
        val a = JSONArray(get("$FABRIC_META/versions/game"))
        (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optBoolean("stable") }.map { it.getString("version") }
    } catch (_: Exception) { emptyList() }

    fun fabricInstallerUrl(): String? = try {
        val a = JSONArray(get("$FABRIC_META/versions/installer"))
        val pick = (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optBoolean("stable") } ?: a.getJSONObject(0)
        pick.getString("url")
    } catch (_: Exception) { null }

    fun fabricLoaderVersion(): String? = try {
        val a = JSONArray(get("$FABRIC_META/versions/loader"))
        val pick = (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optBoolean("stable") } ?: a.getJSONObject(0)
        pick.getString("version")
    } catch (_: Exception) { null }

    // ── Forge ─────────────────────────────────────────────────────────
    fun forgeVersions(): List<String> = try {
        val o = JSONObject(get(FORGE_PROMOS)).getJSONObject("promos")
        o.keys().asSequence().filter { it.endsWith("-recommended") || it.endsWith("-latest") }
            .map { it.substringBefore('-') }.distinct()
            .filter { it.matches(Regex("\\d+\\.\\d+(\\.\\d+)?")) }
            .sortedByDescending { versionRank(it) }.toList()
    } catch (_: Exception) { emptyList() }

    fun forgeBuild(mc: String): String? = try {
        val o = JSONObject(get(FORGE_PROMOS)).getJSONObject("promos")
        o.optString("$mc-recommended", o.optString("$mc-latest", "")).ifEmpty { null }
    } catch (_: Exception) { null }

    fun forgeInstallerUrl(mc: String, build: String) = "$FORGE_MAVEN/net/minecraftforge/forge/$mc-$build/forge-$mc-$build-installer.jar"

    // ── NeoForge ──────────────────────────────────────────────────────
    fun neoforgeVersions(): List<String> = try {
        val a = JSONArray(get("$NEOFORGE_MAVEN/api/maven/versions/releases/net/neoforged/neoforge"))
        (0 until a.length()).map { a.getString(it) }.filter { !it.contains("beta") && !it.contains("alpha") && !it.contains("rc") }
            .sortedByDescending { versionRank(it) }
    } catch (_: Exception) { emptyList() }

    fun neoforgeInstallerUrl(v: String) = "$NEOFORGE_MAVEN/releases/net/neoforged/neoforge/$v/neoforge-$v-installer.jar"

    // ── Modrinth ──────────────────────────────────────────────────────
    fun modrinthSearch(query: String, mcVersion: String, loader: String, limit: Int = 15): List<ModResult> {
        val facetLoader = if (loader == "paper") "bukkit" else loader
        val facetType = if (loader == "paper") "plugin" else "mod"
        val facets = URLEncoder.encode("""[["project_type:$facetType"],["categories:$facetLoader"],["versions:$mcVersion"]]""", "UTF-8")
        return try {
            val a = JSONObject(get("$MODRINTH/search?query=${URLEncoder.encode(query, "UTF-8")}&facets=$facets&limit=$limit")).getJSONArray("hits")
            (0 until a.length()).map { val o = a.getJSONObject(it); ModResult(o.getString("slug"), o.getString("title"), o.optString("description")) }
        } catch (_: Exception) { emptyList() }
    }

    fun modrinthDownloadUrl(slug: String, mcVersion: String, loader: String): String? {
        val facetLoader = if (loader == "paper") "bukkit" else loader
        return try {
            val gv = URLEncoder.encode("""["${mcVersion}"]""", "UTF-8")
            val ld = URLEncoder.encode("""["${facetLoader}"]""", "UTF-8")
            val a = JSONArray(get("$MODRINTH/project/$slug/version?game_versions=$gv&loaders=$ld"))
            if (a.length() == 0) null else {
                val files = a.getJSONObject(0).getJSONArray("files")
                (0 until files.length()).map { files.getJSONObject(it) }
                    .firstOrNull { it.optBoolean("primary") }?.getString("url") ?: files.getJSONObject(0).getString("url")
            }
        } catch (_: Exception) { null }
    }

    data class ModResult(val slug: String, val title: String, val description: String)
}
