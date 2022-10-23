package com.klipper4a.repository

import android.content.Context
import android.os.Build
import android.util.Pair
import com.klipper4a.BuildConfig
import com.klipper4a.R
import com.klipper4a.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.inject
import java.io.*
import java.net.URL
import java.util.zip.ZipInputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory

interface BootstrapRepository {
    val commandsFlow: SharedFlow<String>
    suspend fun setupBootstrap(progress: MutableStateFlow<Int>)
    fun runCommand(command: String, prooted: Boolean = true, root: Boolean = true, bash: Boolean = false): Process
    fun runProot(command: String, root: Boolean): Process
    fun copyResToBootstrap(resId: Int, destinationRelativePath: String)
    fun copyRes(resId: Int, destinationRelativePath: String)
    fun ensureHomeDirectory()
    fun resetSSHPassword(newPassword: String)
    val isBootstrapInstalled: Boolean
    val isSSHConfigured: Boolean
    val isArgonFixApplied: Boolean
    val isKlipperInstalled: Boolean
    val isMoonrakerInstalled: Boolean
    val isMainsailInstalled: Boolean
}

class BootstrapRepositoryImpl(private val logger: LoggerRepository, private val githubRepository: GithubRepository, val context: Context) : BootstrapRepository {
    companion object {
        private val FILES_PATH = "/data/data/com.klipper4a/files"
        val PREFIX_PATH = "$FILES_PATH/bootstrap"
        val HOME_PATH = "$FILES_PATH/home"
        const val DISTRO_NAME = "ubuntu"
    }
    private val filesPath: String by lazy { context.getExternalFilesDir(null).absolutePath }
    private var _commandsFlow = MutableSharedFlow<String>(100)
    override val commandsFlow: SharedFlow<String>
        get() = _commandsFlow

    private fun shouldUsePre5Bootstrap(): Boolean {
        if (getArchString() != "arm" && getArchString() != "i686") {
            return false
        }

        return Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP
    }

    private suspend fun getLatestRelease(repo: String, arch: String, distro: String): GithubAsset? {
        val bootstrapReleases = githubRepository.getNewestReleases(repo)
        val arch = getArchString()

        val release = bootstrapReleases.firstOrNull {
            it.assets.any { asset -> asset.name.contains(arch) && asset.name.contains(distro) }
        }

        return release?.assets?.first { asset -> asset.name.contains(arch) && asset.name.contains(distro) }
    }

    private fun httpsConnection(urlPrefix: String): HttpsURLConnection {
        val sslcontext = SSLContext.getInstance("TLSv1")
        sslcontext.init(null, null, null)
        val noSSLv3Factory: SSLSocketFactory = TLSSocketFactory()

        HttpsURLConnection.setDefaultSSLSocketFactory(noSSLv3Factory)
        val connection: HttpsURLConnection = URL(urlPrefix).openConnection() as HttpsURLConnection
        connection.sslSocketFactory = noSSLv3Factory

        return connection
    }

    private fun transfer(inputStream: InputStream, outputStream: OutputStream) {
        var bytesRead = -1
        val buffer = ByteArray(1024)
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            outputStream.write(buffer, 0, bytesRead)
        }
        inputStream.close()
        outputStream.close()
    }

    override fun copyResToBootstrap(resId: Int, destinationRelativePath: String) {
        val out = FileOutputStream(PREFIX_PATH + "/bootstrap" + destinationRelativePath)
        transfer(context.resources.openRawResource(resId), out)
    }

    override fun copyRes(resId: Int, destinationRelativePath: String) {
        val out = FileOutputStream("$PREFIX_PATH/$destinationRelativePath")
        transfer(context.resources.openRawResource(resId), out)
    }

    override suspend fun setupBootstrap(progress: MutableStateFlow<Int>) {
        withContext(Dispatchers.IO) {
            val PREFIX_FILE = File(PREFIX_PATH)
            if (PREFIX_FILE.isDirectory) {
                return@withContext
            }

            progress.emit(0)

            try {
                val arch = getArchString()
                val asset = getLatestRelease("feelfreelinux/android-linux-bootstrap", arch, "bootstrap")

                val STAGING_PREFIX_PATH = "${FILES_PATH}/bootstrap-staging"
                val STAGING_PREFIX_FILE = File(STAGING_PREFIX_PATH)

                if (STAGING_PREFIX_FILE.exists()) {
                    deleteFolder(STAGING_PREFIX_FILE)
                }

                val buffer = ByteArray(8096)
                val symlinks = ArrayList<Pair<String, String>>(50)

                val connection = httpsConnection(asset!!.browserDownloadUrl)

                ZipInputStream(connection.inputStream).use { zipInput ->
                    var zipEntry = zipInput.nextEntry
                    while (zipEntry != null) {

                        val zipEntryName = zipEntry.name
                        val targetFile = File(STAGING_PREFIX_PATH, zipEntryName)
                        val isDirectory = zipEntry.isDirectory

                        ensureDirectoryExists(if (isDirectory) targetFile else targetFile.parentFile)

                        if (!isDirectory) {
                            FileOutputStream(targetFile).use { outStream ->
                                var readBytes = zipInput.read(buffer)
                                while ((readBytes) != -1) {
                                    outStream.write(buffer, 0, readBytes)
                                    readBytes = zipInput.read(buffer)
                                }
                            }
                        }
                        zipEntry = zipInput.nextEntry
                    }
                }

                if (!STAGING_PREFIX_FILE.renameTo(PREFIX_FILE)) {
                    throw RuntimeException("Unable to rename staging folder")
                }
                copyRes(R.raw.run_bootstrap, "run-distro.sh")
                copyRes(R.raw.run_bootstrap, "run-bootstrap.sh")
                runCommand("chmod a+x run-distro.sh run-bootstrap.sh", prooted = false).waitAndPrintOutput(logger)

                logger.log(this) { "Bootstrap extracted, setting it up..." }
                runCommand("ls", prooted = false).waitAndPrintOutput(logger)
                runCommand("chmod -R 700 .", prooted = false).waitAndPrintOutput(logger)
                if (shouldUsePre5Bootstrap()) {
                    runCommand("rm -r root && mv root-pre5 root", prooted = false).waitAndPrintOutput(logger)
                }
                progress.emit(5)

                logger.log(this) { "Downloading $DISTRO_NAME distro..." }

                // Not built for Android -- try this https://github.com/green-green-avk/build-proot-android/raw/master/packages/proot-android-${arch}.tar.gz
                // val prootAsset = getLatestRelease("proot-me/proot", arch, "proot")
                // val prootConnection = httpsConnection(prootAsset!!.browserDownloadUrl)
                // runCommand("mv $PREFIX_PATH/root/bin/proot $PREFIX_PATH/root/bin/proot.org", prooted = false).waitAndPrintOutput(logger)
                // transfer(prootConnection.inputStream, FileOutputStream(PREFIX_PATH + "/root/bin/proot"))
                // runCommand("chmod a+x $PREFIX_PATH/root/bin/proot", prooted = false).waitAndPrintOutput(logger)

                logger.log(this) { "Downloading $DISTRO_NAME distro..." }
                val distroAsset = getLatestRelease("termux/proot-distro", arch, DISTRO_NAME)
                val distroConnection = httpsConnection(distroAsset!!.browserDownloadUrl)
                runCommand("mv rootfs.tar.xz rootfs.tar.xz.org", prooted = false).waitAndPrintOutput(logger)
                transfer(distroConnection.inputStream, FileOutputStream(PREFIX_PATH + "/rootfs.tar.xz"))

                progress.emit(15)

                copyRes(R.raw.install_bootstrap, "install-bootstrap.sh")
                runCommand("sh install-bootstrap.sh", prooted = false).waitAndPrintOutput(logger)
                runCommand("cat /etc/lsb-release").waitAndPrintOutput(logger)

                copyResToBootstrap(R.raw.systemctl3, "/bin/systemctl.new")

                logger.log{ ">>>>>>>>>>>>>> SETTING UP BASE SYSTEM <<<<<<<<<<<<<<<" }

                copyResToBootstrap(R.raw.setup_base_system, "/root/setup-base-system.sh")
                runCommand("chmod 700 ./bootstrap/root/setup-base-system.sh", prooted = false).waitAndPrintOutput(logger)
                runCommand("/root/setup-base-system.sh").waitAndPrintOutput(logger)

                // Install some dependencies.
                ///runCommand("apt-get install -q -y dropbear curl bash sudo python3 python3-virtualenv virtualenv git unzip 2>&1").waitAndPrintOutput(logger)
                // python3-virtualenv doesn't seem to work well in (this?) proot - we're supplying our own hacky shim later.

                progress.emit(20)

                // Turn ssh on for easier debug
                if (BuildConfig.DEBUG) {
                    runCommand("passwd").setPassword("klipper")
                    runCommand("passwd klipper").setPassword("klipper")
                    // runCommand("/usr/sbin/sshd -p 2137")
                    runCommand("/usr/sbin/dropbear -p 8022")
                }

                logger.log(this) { "Bootstrap installation done" }

                progress.emit(35)

                return@withContext
            } catch (e: Exception) {
                throw(e)
            } finally {
            }
        }
    }

    private fun ensureDirectoryExists(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw RuntimeException("Unable to create directory: " + directory.absolutePath)
        }
    }

    /** Delete a folder and all its content or throw. Don't follow symlinks.  */
    @Throws(IOException::class)
    fun deleteFolder(fileOrDirectory: File) {
        if (fileOrDirectory.canonicalPath == fileOrDirectory.absolutePath && fileOrDirectory.isDirectory) {
            val children = fileOrDirectory.listFiles()

            if (children != null) {
                for (child in children) {
                    deleteFolder(child)
                }
            }
        }

        if (!fileOrDirectory.delete()) {
            throw RuntimeException("Unable to delete " + (if (fileOrDirectory.isDirectory) "directory " else "file ") + fileOrDirectory.absolutePath)
        }
    }

    override fun runCommand(command: String, prooted: Boolean, root: Boolean, bash: Boolean): Process {
        logger.log(this) { "$> ${command}" }

        val FILES = "/data/data/com.klipper4a/files"
        val directory = File(filesPath)
        if (!directory.exists()) {
            directory.mkdirs()
        }
        val pb = ProcessBuilder()
        pb.redirectErrorStream(true)
        pb.environment()["HOME"] = "$FILES/bootstrap"
        pb.environment()["TERM"] = "linux"
        pb.environment()["LANG"] = "'en_US.UTF-8'"
        pb.environment()["PWD"] = "$FILES/bootstrap"
        //pb.environment()["EXTRA_BIND"] = "-b $FILES/bootstrap/root:/root -b /data/data/com.klipper4a/files/serialpipe:/dev/ttyOcto4a -b /data/data/com.klipper4a/files/bootstrap/ioctlHook.so:/usr/lib/ioctlHook.so"
        pb.environment()["EXTRA_BIND"] = "-b /data/data/com.klipper4a/files/serialpipe:/dev/ttyOcto4a -b /data/data/com.klipper4a/files/bootstrap/ioctlHook.so:/usr/lib/ioctlHook.so"
        pb.environment()["PATH"] = "/sbin:/system/sbin:/product/bin:/apex/com.android.runtime/bin:/system/bin:/system/xbin:/odm/bin:/vendor/bin:/vendor/xbin"
        pb.directory(File("$FILES/bootstrap"))
        var user = "root"
        if (!root) user = "klipper"
        if (prooted) {
            // run inside proot
            //val shell = if (bash) "/bin/bash" else "/bin/sh"
            //pb.command("sh", "run-bootstrap.sh", user,  shell, "-c", command)
            pb.command("sh", "run-bootstrap.sh", user, command)
        } else {
            pb.command("sh", "-c", command)
        }
        return pb.start()
    }

    override fun runProot(command: String, root: Boolean): Process {
        logger.log(this) { ":$> ${command}" }

        val FILES = "/data/data/com.klipper4a/files"
        val directory = File(filesPath)
        if (!directory.exists()) {
            directory.mkdirs()
        }
        val pb = ProcessBuilder()
        pb.redirectErrorStream(true)
        pb.environment()["HOME"] = "$FILES/bootstrap"
        pb.environment()["TERM"] = "linux"
        pb.environment()["LANG"] = "'en_US.UTF-8'"
        pb.environment()["PWD"] = "$FILES/bootstrap"
        pb.environment()["EXTRA_BIND"] = "-b ${filesPath}:/root -b /data/data/com.klipper4a/files/serialpipe:/dev/ttyOcto4a -b /data/data/com.klipper4a/files/bootstrap/ioctlHook.so:/usr/lib/ioctlHook.so"
        pb.environment()["PATH"] = "/sbin:/system/sbin:/product/bin:/apex/com.android.runtime/bin:/system/bin:/system/xbin:/odm/bin:/vendor/bin:/vendor/xbin"
        pb.directory(File("$FILES/bootstrap"))
        var user = "root"
        if (!root) user = "klipper"

        if (!root) {
            pb.command("sh", "run-distro.sh", user, command)
        } else {
            pb.command("sh", "run-distro.sh", user, command)
        }

        return pb.start()
    }

    override fun ensureHomeDirectory() {
//        val homeFile = File(HOME_PATH)
//        if (!homeFile.exists()) {
//            homeFile.mkdir()
//        }
    }
    override val isSSHConfigured: Boolean
        get() {
            return File("/data/data/com.klipper4a/files/bootstrap/bootstrap/home/klipper/.ssh_configured").exists()
        }
    override val isArgonFixApplied: Boolean
        get() {
            return File("/data/data/com.octo4a/files/bootstrap/bootstrap/home/octoprint/.argon-fix").exists()
        }

    override fun resetSSHPassword(newPassword: String) {
        logger.log(this) { "Deleting password just in case" }
        runCommand("passwd -d klipper").waitAndPrintOutput(logger)
        runCommand("passwd klipper").setPassword(newPassword)
        runCommand("passwd -d root").waitAndPrintOutput(logger)
        runCommand("passwd root").setPassword(newPassword)
        runCommand("touch .ssh_configured", root = false)
        runCommand("touch .ssh_configured", root = true)
    }

    override val isBootstrapInstalled: Boolean
        get() = File("$FILES_PATH/bootstrap/bootstrap/system_status/base_system.installed").exists()
    override val isKlipperInstalled: Boolean
        get() = File("$FILES_PATH/bootstrap/bootstrap/system_status/klipper.installed").exists()
    override val isMoonrakerInstalled: Boolean
        get() = File("$FILES_PATH/bootstrap/bootstrap/system_status/moonraker.installed").exists()
    override val isMainsailInstalled: Boolean
        get() = File("$FILES_PATH/bootstrap/bootstrap/system_status/mainsail.installed").exists()
}