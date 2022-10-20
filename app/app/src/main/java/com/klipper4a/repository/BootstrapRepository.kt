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
    fun copyResToHome(resId: Int, destinationRelativePath: String)
    fun copyRes(resId: Int, destinationRelativePath: String)
    fun ensureHomeDirectory()
    fun resetSSHPassword(newPassword: String)
    val isBootstrapInstalled: Boolean
    val isSSHConfigured: Boolean
    val isArgonFixApplied: Boolean
}

class BootstrapRepositoryImpl(private val logger: LoggerRepository, private val githubRepository: GithubRepository, private val linuxContainersRepository: LinuxContainersRepository, val context: Context) : BootstrapRepository {
    companion object {
        private val FILES_PATH = "/data/data/com.klipper4a/files"
        val PREFIX_PATH = "$FILES_PATH/bootstrap"
        //const val DISTRO_NAME = "debian"
        //const val DISTRO_RELEASE = "bookworm"
        const val DISTRO_NAME = "ubuntu"
        const val DISTRO_RELEASE = "jammy"
    }
    //private val filesPath: String by lazy { context.getExternalFilesDir(null).absolutePath }
    private val filesPath: String by lazy { "$FILES_PATH/bootstrap/bootstrap/" }
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
        val out = FileOutputStream("$PREFIX_PATH/bootstrap$destinationRelativePath")
        transfer(context.resources.openRawResource(resId), out)
    }

    override fun copyResToHome(resId: Int, destinationRelativePath: String) {
        val out = FileOutputStream("$filesPath/home/$destinationRelativePath")
        transfer(context.resources.openRawResource(resId), out)
    }

    override fun copyRes(resId: Int, destinationRelativePath: String) {
        val out = FileOutputStream("$PREFIX_PATH/$destinationRelativePath")
        transfer(context.resources.openRawResource(resId), out)
    }

    override suspend fun setupBootstrap(progress: MutableStateFlow<Int>) {
        ensureHomeDirectory()

        withContext(Dispatchers.IO) {
            val PREFIX_FILE = File(PREFIX_PATH)
            if (PREFIX_FILE.isDirectory) {
                return@withContext
            }

            progress.emit(0)

            try {
                logger.log(this) { "Getting bootstrap rootfs and utils..." }
                val arch = getArchString()
                /*
                val asset = getLatestRelease("feelfreelinux/android-linux-bootstrap", arch, "bootstrap")
                val connection = httpsConnection(asset!!.browserDownloadUrl)
                val zipInputStream = ZipInputStream(connection.inputStream)
                 */

                val zipInputStream = ZipInputStream(context.getResources().openRawResource(
                    when (arch) {
                        "aarch64" -> R.raw.bootstrap_aarch64
                        "armv7a" -> R.raw.bootstrap_armv7a
                        "i686" -> R.raw.bootstrap_i686
                        "x86_64" -> R.raw.bootstrap_x86_64
                        else -> -1
                    }
                ))

                val STAGING_PREFIX_PATH = "${FILES_PATH}/bootstrap-staging"
                val STAGING_PREFIX_FILE = File(STAGING_PREFIX_PATH)

                if (STAGING_PREFIX_FILE.exists()) {
                    deleteFolder(STAGING_PREFIX_FILE)
                }

                val buffer = ByteArray(8096)
                val symlinks = ArrayList<Pair<String, String>>(50)
                zipInputStream.use { zipInput ->
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

                // Stage 0: Alpine Pre-Bootstrap.
                runCommand("chmod -R 700 .", prooted = false).waitAndPrintOutput(logger)
                runCommand("sh install-bootstrap.sh", prooted = false).waitAndPrintOutput(logger)

                // Stage 1: Install full distro.
                copyRes(R.raw.install_full, "bootstrap/install-full.sh")
                runCommand("chmod a+x bootstrap/install-full.sh", prooted = false).waitAndPrintOutput(logger)

                logger.log(this) { "Getting rootfs images..." }
                val distroAsset = linuxContainersRepository.getNewest(DISTRO_NAME, arch, DISTRO_RELEASE)
                if (distroAsset != null) {
                    logger.log(this) { "Found: ${distroAsset.distro} ${distroAsset.arch} ${distroAsset.release}: ${distroAsset.downloadPath}" }
                } else {
                    logger.log(this) { "No suitable distro found!" }
                    return@withContext
                }

                progress.emit(5)

                logger.log(this) { "Downloading $DISTRO_NAME $DISTRO_RELEASE $arch distro..." }
                runCommand("sh /install-full.sh '${distroAsset!!.downloadPath}'").waitAndPrintOutput(logger)
                runCommand("cd ${PREFIX_PATH}; mv bootstrap/full-distro . && mv bootstrap bootstrap-min && mv full-distro bootstrap", prooted = false).waitAndPrintOutput(logger)

                copyRes(R.raw.run_bootstrap, "run-distro.sh")
                runCommand("chmod a+x run-distro.sh", prooted = false).waitAndPrintOutput(logger)

                // Stage 2: Set up and install packages.
                logger.log(this) { "Bootstrap extracted, setting it up..." }
                runCommand("ls", prooted = false).waitAndPrintOutput(logger)
                if (shouldUsePre5Bootstrap()) {
                    runCommand("rm -r root && mv root-pre5 root", prooted = false).waitAndPrintOutput(logger)
                }

                /*
                runCommand("mv $PREFIX_PATH/root/bin/proot $PREFIX_PATH/root/bin/proot.org", prooted = false).waitAndPrintOutput(logger)
                when (arch) {
                    "aarch64" -> copyRes(R.raw.proot_aarch64, "/root/bin/proot")
                    "armv7a" -> copyRes(R.raw.proot_armv7a, "/root/bin/proot")
                    "i686" -> copyRes(R.raw.proot_i686, "/root/bin/proot")
                    "x86_64" -> copyRes(R.raw.proot_x86_64, "/root/bin/proot")
                    else -> logger.log { "WARNING: No updated proot for $arch found" }
                }
                runCommand("chmod a+x $PREFIX_PATH/root/bin/proot", prooted = false).waitAndPrintOutput(logger)
                */

                // Termux-Proot-Distro
                /*
                logger.log(this) { "Downloading $DISTRO_NAME distro..." }
                val distroAsset = getLatestRelease("termux/proot-distro", arch, DISTRO_NAME)
                val distroConnection = httpsConnection(distroAsset!!.browserDownloadUrl)
                runCommand("mv rootfs.tar.xz rootfs.tar.xz.org", prooted = false).waitAndPrintOutput(logger)
                transfer(distroConnection.inputStream, FileOutputStream(PREFIX_PATH + "/rootfs.tar.xz"))
                */

                /*
                // LinuxContainers
                logger.log(this) { "Getting rootfs images..." }
                val distroAsset = linuxContainersRepository.getNewest(DISTRO_NAME, arch, DISTRO_RELEASE)
                if (distroAsset != null) {
                    logger.log(this) { "Found: ${distroAsset.distro} ${distroAsset.arch} ${distroAsset.release}: ${distroAsset.downloadPath}" }
                } else {
                    logger.log(this) { "No suitable distro found!" }
                    return@withContext
                }

                val distroConnection = httpsConnection(distroAsset!!.downloadPath)
                runCommand("mv rootfs.tar.xz rootfs.tar.xz.org", prooted = false).waitAndPrintOutput(logger)
                transfer(distroConnection.inputStream, FileOutputStream("$PREFIX_PATH/rootfs.tar.xz"))
                logger.log(this) { "Downloaded." }

                progress.emit(10)

                runCommand("sh install-bootstrap.sh", prooted = false).waitAndPrintOutput(logger)
                runCommand("cat /etc/lsb-release").waitAndPrintOutput(logger)
                logger.log(this) { "Installed $DISTRO_NAME $DISTRO_RELEASE $arch bootstrap. Configuring..." }
                */

                progress.emit(15)

                // Install some dependencies.
                copyResToBootstrap(R.raw.nop, "/usr/bin/nop.sh")
                runCommand("chmod a+x /usr/bin/nop.sh; ln -s /usr/bin/nop.sh /usr/bin/update-rc.d; ln -s /usr/bin/nop.sh /usr/bin/deb-systemd-helper; ln -s /usr/bin/nop.sh pkg-config-dpkghook").waitAndPrintOutput(logger)

                runCommand("apt-get update --allow-releaseinfo-change").waitAndPrintOutput(logger)
                runCommand("apt-get install -q -y --reinstall adduser").waitAndPrintOutput(logger)
                runCommand("cat /etc/resolv.conf").waitAndPrintOutput(logger)
                Thread.sleep(5_000)

                runCommand("apt-get install -q -y dropbear curl bash sudo git unzip inetutils-traceroute 2>&1").waitAndPrintOutput(logger)
                Thread.sleep(5_000)

                val sshdProcess = runCommand("/usr/sbin/dropbear -p 8022", bash = false)

                // python3-virtualenv doesn't seem to work well in (this?) proot - we're supplying our own hacky shim later.

                // Update PROOT.
                // Not built for Android -- try this https://github.com/green-green-avk/build-proot-android/raw/master/packages/proot-android-${arch}.tar.gz
                // val prootAsset = getLatestRelease("proot-me/proot", arch, "proot")
                // val prootConnection = httpsConnection(prootAsset!!.browserDownloadUrl)
                // runCommand("mv $PREFIX_PATH/root/bin/proot $PREFIX_PATH/root/bin/proot.org", prooted = false).waitAndPrintOutput(logger)
                // transfer(prootConnection.inputStream, FileOutputStream(PREFIX_PATH + "/root/bin/proot"))
                // runCommand("chmod a+x $PREFIX_PATH/root/bin/proot", prooted = false).waitAndPrintOutput(logger)
                //copyResToBootstrap(R.raw.update_proot, "/update-proot.sh")
                //runCommand("chmod a+x /update-proot.sh").waitAndPrintOutput(logger)
                //runCommand("/update-proot.sh 'https://github.com/green-green-avk/build-proot-android/raw/master/packages/proot-android-${arch}.tar.gz'").waitAndPrintOutput(logger)
                //runCommand("mkdir $PREFIX_PATH/root/org; mv $PREFIX_PATH/root/* $PREFIX_PATH/root/org", prooted = false).waitAndPrintOutput(logger)
                //runCommand("cp -r $PREFIX_PATH/bootstrap/proot/root/* $PREFIX_PATH/root", prooted = false).waitAndPrintOutput(logger)

                progress.emit(20)

                runCommand("echo 'ADDUSER:'; which adduser").waitAndPrintOutput(logger)

                // Setup docker-systemctl-replacement systemctl simulation.
                runCommand("mv /bin/systemctl /bin/systemctl.org").waitAndPrintOutput(logger)
                copyResToBootstrap(R.raw.systemctl3, "/bin/systemctl")
                runCommand("chmod a+x /bin/systemctl").waitAndPrintOutput(logger)

                // Setup ssh.
                runCommand("ssh-keygen -A 2>&1").waitAndPrintOutput(logger)

                // Add klipper user.
                //runCommand("sh add-user.sh klipper", prooted = false).waitAndPrintOutput(logger)
                runCommand("mkdir /home/klipper; useradd -U -m -d /home/klipper klipper").waitAndPrintOutput(logger)
                runCommand("echo 'klipper     ALL=(ALL) NOPASSWD:ALL' >> /etc/sudoers", root = true, bash = false).waitAndPrintOutput(logger)
                runCommand("passwd").setPassword("klipper")
                runCommand("passwd klipper").setPassword("klipper")

                // Pre-install some Klipper required packages.
                runCommand("apt-get install -q -y python3 python3-virtualenv virtualenv python3-dev libffi-dev build-essential libncurses-dev libusb-dev avrdude gcc-avr binutils-avr avr-libc stm32flash libnewlib-arm-none-eabi gcc-arm-none-eabi binutils-arm-none-eabi libusb-1.0 pkg-config dfu-util 2>&1").waitAndPrintOutput(logger)

                // Turn ssh on for easier debug
                if (BuildConfig.DEBUG) {
//                    runCommand("passwd").setPassword("klipper")
//                    runCommand("passwd klipper").setPassword("klipper")
//                    runCommand("/usr/sbin/sshd -p 2137")
                }

                logger.log(this) { "Bootstrap installation done" }

                progress.emit(35)

                sshdProcess.destroy()

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
        pb.environment()["EXTRA_BIND"] = "-b ${filesPath}/home:/home -b /data/data/com.klipper4a/files/serialpipe:/dev/ttyOcto4a -b /data/data/com.klipper4a/files/bootstrap/ioctlHook.so:/usr/lib/ioctlHook.so"
        pb.environment()["PATH"] = "/sbin:/system/sbin:/product/bin:/apex/com.android.runtime/bin:/system/bin:/system/xbin:/odm/bin:/vendor/bin:/vendor/xbin"
        pb.directory(File("$FILES/bootstrap"))
        var user = "root"
        if (!root) user = "klipper"
        if (prooted) {
            // run inside proot
            val shell = if (bash) "/bin/bash" else "/bin/sh"
            pb.command("sh", "run-bootstrap.sh", user,  shell, "-c", command)
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
        pb.environment()["EXTRA_BIND"] = "-b ${filesPath}/home:/home -b /data/data/com.klipper4a/files/serialpipe:/dev/ttyOcto4a -b /data/data/com.klipper4a/files/bootstrap/ioctlHook.so:/usr/lib/ioctlHook.so"
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
        var homeFile = File("$filesPath/home")
        if (!homeFile.exists()) homeFile.mkdir()
        homeFile = File("$filesPath/home/klipper")
        if (!homeFile.exists()) homeFile.mkdir()
    }
    override val isSSHConfigured: Boolean
        get() {
            return File("$filesPath/home/klipper/.ssh_configured").exists()
        }
    override val isArgonFixApplied: Boolean
        get() {
            return File("$filesPath/home/octoprint/.argon-fix").exists()
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
        get() = File("$FILES_PATH/bootstrap/bootstrap").exists()
}