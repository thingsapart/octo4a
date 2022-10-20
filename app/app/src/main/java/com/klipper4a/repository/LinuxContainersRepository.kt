package com.klipper4a.repository

import com.klipper4a.R
import com.klipper4a.utils.TLSSocketFactory
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory

data class LinuxContainerAsset(val distro: String, val release: String, val arch: String, val timestamp: String, val type: String, val downloadPath: String) {
    companion object {
        fun fromString(s: String, baseUrl: String): LinuxContainerAsset {
            val res = s.split(";").toTypedArray()
            val _distro = res.getOrElse(0) { "" }
            val _release = res.getOrElse(1) { "" }
            val _arch = res.getOrElse(2) { "" }
            val _type = res.getOrElse(3) { "" }
            val _timestamp = res.getOrElse(4) { "" }
            val _downloadPath = res.getOrElse(5) { "" }
            return LinuxContainerAsset(
                _distro,
                _release,
                _arch,
                _timestamp,
                _type,
                "$baseUrl$_downloadPath/rootfs.tar.xz"
            )
        }
    }
}


interface LinuxContainersRepository {
    suspend fun getNewest(distro: String, arch: String, release: String): LinuxContainerAsset?
}

class LinuxContainersRepositoryImpl(val httpClient: HttpClient): LinuxContainersRepository {
    //private val releasesUrl = "https://images.linuxcontainers.org/meta/1.0/index-user"
    //private val baseUrl = "https://images.linuxcontainers.org/"

    private val releasesUrl = "https://us.lxd.images.canonical.com/meta/1.0/index-user"
    private val baseUrl = "https://us.lxd.images.canonical.com/"

    private fun httpsConnection(urlPrefix: String): HttpsURLConnection {
        val sslcontext = SSLContext.getInstance("TLSv1")
        sslcontext.init(null, null, null)
        val noSSLv3Factory: SSLSocketFactory = TLSSocketFactory()

        HttpsURLConnection.setDefaultSSLSocketFactory(noSSLv3Factory)
        val connection: HttpsURLConnection = URL(urlPrefix).openConnection() as HttpsURLConnection
        connection.sslSocketFactory = noSSLv3Factory

        return connection
    }

    private fun translateArch(arch: String): String {
        return when (arch) {
            "aarch64" -> "arm64"
            "armv7a" -> "armhf"
            "i686" -> "i386"
            "x86_64" -> "amd64"
            else -> ""
        }
    }

    override suspend fun getNewest(distro: String, arch: String, release: String): LinuxContainerAsset? {
        val aarch = translateArch(arch)
        val res =  httpsConnection(releasesUrl).inputStream.bufferedReader().lineSequence().firstOrNull {
            val res = it.split(";").toTypedArray()
            val _distro = res.getOrElse(0) { "" }
            val _arch = res.getOrElse(2) { "" }
            val _downloadPath = res.getOrElse(5) { "" }
            val _release = res.getOrElse(1) { "" }
            val _type = res.getOrElse(3) { "" }

            _distro == distro && _arch == aarch && _downloadPath.isNotBlank() && _release == release && (_type == "default" || _type == "musl")
        }
        if (!res.isNullOrBlank()) return LinuxContainerAsset.fromString(res, baseUrl)
        return null
    }
}