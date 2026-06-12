package com.smartsup.controller.model

data class UpdateUiState(
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val esp32Uploading: Boolean = false,
    val latestVersionName: String? = null,
    val appUpdateAvailable: Boolean = false,
    val appAssetName: String? = null,
    val firmwareAssetName: String? = null,
    val appDownloadUrl: String? = null,
    val firmwareDownloadUrl: String? = null,
    val appAssetApiUrl: String? = null,
    val firmwareAssetApiUrl: String? = null,
    val progressText: String = "",
    val message: String = "未检查更新",
)

data class ReleaseAsset(
    val name: String,
    val apiUrl: String,
    val downloadUrl: String,
)

data class ReleaseInfo(
    val tagName: String,
    val htmlUrl: String,
    val assets: List<ReleaseAsset>,
) {
    val apkAsset: ReleaseAsset?
        get() = assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }

    val firmwareAsset: ReleaseAsset?
        get() = assets.firstOrNull {
            it.name.endsWith(".bin", ignoreCase = true) &&
                (it.name.contains("firmware", ignoreCase = true) ||
                    it.name.contains("esp32", ignoreCase = true))
        } ?: assets.firstOrNull { it.name.endsWith(".bin", ignoreCase = true) }
}
