package com.smartsup.controller.update

import com.smartsup.controller.model.ReleaseAsset
import com.smartsup.controller.model.ReleaseInfo
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class GitHubReleaseClient(
    private val repository: String,
    private val tokenProvider: () -> String?,
) {
    fun fetchLatestRelease(): ReleaseInfo {
        val connection = openConnection(
            url = "https://api.github.com/repos/$repository/releases/latest",
            accept = "application/vnd.github+json",
        )
        return connection.inputStream.bufferedReader().use { reader ->
            val json = JSONObject(reader.readText())
            val assetsJson = json.getJSONArray("assets")
            val assets = buildList {
                for (index in 0 until assetsJson.length()) {
                    val assetJson = assetsJson.getJSONObject(index)
                    add(
                        ReleaseAsset(
                            name = assetJson.getString("name"),
                            apiUrl = assetJson.getString("url"),
                            downloadUrl = assetJson.getString("browser_download_url"),
                        ),
                    )
                }
            }
            ReleaseInfo(
                tagName = json.getString("tag_name"),
                htmlUrl = json.getString("html_url"),
                assets = assets,
            )
        }
    }

    fun download(
        url: String,
        destination: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        apiAssetDownload: Boolean = false,
    ) {
        destination.parentFile?.mkdirs()
        val connection = openConnection(
            url = url,
            accept = if (apiAssetDownload) {
                "application/octet-stream"
            } else {
                "application/vnd.github+json"
            },
        )
        val totalBytes = connection.contentLengthLong
        var readBytesTotal = 0L

        connection.inputStream.use { input ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                while (true) {
                    val readBytes = input.read(buffer)
                    if (readBytes < 0) {
                        break
                    }
                    output.write(buffer, 0, readBytes)
                    readBytesTotal += readBytes
                    onProgress(readBytesTotal, totalBytes)
                }
            }
        }
    }

    fun fetchText(
        url: String,
        apiAssetDownload: Boolean = false,
    ): String {
        val connection = openConnection(
            url = url,
            accept = if (apiAssetDownload) {
                "application/octet-stream"
            } else {
                "application/vnd.github+json"
            },
        )
        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    private fun openConnection(url: String, accept: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", accept)
        connection.setRequestProperty("User-Agent", "SmartSUP-Android")
        tokenProvider()?.takeIf { it.isNotBlank() }?.let { token ->
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        connection.connect()
        if (connection.responseCode !in 200..299) {
            error("GitHub 请求失败：HTTP ${connection.responseCode}")
        }
        return connection
    }

    private companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val DOWNLOAD_BUFFER_SIZE = 16 * 1024
    }
}
