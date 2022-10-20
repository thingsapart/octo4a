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
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.utils.IOUtils
import java.io.*
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.GZIPInputStream
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

        const val USER = "ubuntu"
        //const val USER = "klipper"

        //const val DISTRO_NAME = "debian"
        //const val DISTRO_RELEASE = "bookworm"

        const val DISTRO_NAME = "ubuntu"
        const val DISTRO_RELEASE = "jammy"
        //const val DISTRO_RELEASE = "focal"

        // Installation options, mostly for testing different configs.
        // Use pre-bootstrap to extract full distro?
        // Built-in Tar/XZ seems to not work for symlinks, minitar fails converting UTF-8 pathnames
        // despite LANG='... env flags. Might get it to work withou pre-bootstrap with more trial&error.
        const val PRE_BOOTSTRAP = false

        // Use a script instead of individual operations called from app?
        const val useBaseSetupScript = true

        // Try the termux proot? (Missing libraries, probably won't run)
        const val termuxProot = false
    }

    //private val filesPath: String by lazy { context.getExternalFilesDir(null).absolutePath }
    private val filesPath: String by lazy { if (PRE_BOOTSTRAP) { "$FILES_PATH/bootstrap/bootstrap/full-distro" } else { "$FILES_PATH/bootstrap/bootstrap/" } }
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
        val out = FileOutputStream("$filesPath/$destinationRelativePath")
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

    private fun unpackZip(zipInputStream: ZipInputStream) {
        val PREFIX_FILE = File(PREFIX_PATH)
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
    }

    private fun unpackTarMin(tarInputStream: TarArchiveInputStream) {
        val PREFIX_FILE = File("$PREFIX_PATH/bootstrap")
        val STAGING_PREFIX_PATH = "${FILES_PATH}/bootstrap/bootstrap-staging"
        val STAGING_PREFIX_FILE = File(STAGING_PREFIX_PATH)

        if (STAGING_PREFIX_FILE.exists()) {
            deleteFolder(STAGING_PREFIX_FILE)
        }

        val buffer = ByteArray(8096)
        val symlinks = ArrayList<Pair<String, String>>(50)
        tarInputStream.use { tarInput ->
            var tarEntry = tarInput.nextEntry
            while (tarEntry != null) {

                val zipEntryName = tarEntry.name
                val targetFile = File(STAGING_PREFIX_PATH, zipEntryName)
                val isDirectory = tarEntry.isDirectory

                ensureDirectoryExists(if (isDirectory) targetFile else targetFile.parentFile)

                if (!isDirectory) {
                    FileOutputStream(targetFile).use { outStream ->
                        var readBytes = tarInput.read(buffer)
                        while ((readBytes) != -1) {
                            outStream.write(buffer, 0, readBytes)
                            readBytes = tarInput.read(buffer)
                        }
                    }
                }
                tarEntry = tarInput.nextEntry
            }
        }

        if (!STAGING_PREFIX_FILE.renameTo(PREFIX_FILE)) {
            throw RuntimeException("Unable to rename staging folder")
        }
    }

    private fun parsePerms(mode: Int): Set<PosixFilePermission>? {
        val ret: MutableSet<PosixFilePermission> = HashSet()
        if (mode and 1 > 0) {
            ret.add(PosixFilePermission.OTHERS_EXECUTE)
        }
        if (mode and 2 > 0) {
            ret.add(PosixFilePermission.OTHERS_WRITE)
        }
        if (mode and 4 > 0) {
            ret.add(PosixFilePermission.OTHERS_READ)
        }
        if (mode and 8 > 0) {
            ret.add(PosixFilePermission.GROUP_EXECUTE)
        }
        if (mode and 16 > 0) {
            ret.add(PosixFilePermission.GROUP_WRITE)
        }
        if (mode and 32 > 0) {
            ret.add(PosixFilePermission.GROUP_READ)
        }
        if (mode and 64 > 0) {
            ret.add(PosixFilePermission.OWNER_EXECUTE)
        }
        if (mode and 128 > 0) {
            ret.add(PosixFilePermission.OWNER_WRITE)
        }
        if (mode and 256 > 0) {
            ret.add(PosixFilePermission.OWNER_READ)
        }
        return ret
    }

    private fun unpackTarEntry(
        tis: TarArchiveInputStream,
        entry: TarArchiveEntry,
        outputDir: File,
        base: String,
        symlinksDisabled: Boolean,
        level: Int,
        logLevel: Int = 0
    ) {
        val target = File(outputDir, entry.name)
        val found = target.canonicalPath
        if (!found.startsWith(base)) {
            logger.log {"Invalid location $found is outside of $base" }
            return
        }
        if (entry.isDirectory) {
            if (logLevel > 2) logger.log { "Extracting dir $target" }
            ensureDirectoryExists(target)
            for (e in entry.directoryEntries) {
                unpackTarEntry(tis, e, target, base, symlinksDisabled, level = level + 1)
            }
        } else if (entry.isSymbolicLink) {
            if (symlinksDisabled) {
                if (logLevel > 0) logger.log { "Symlinks disabled skipping $target" }
            } else {
                var path = entry.linkName
                /*if (path.startsWith('/')) {
                    path = "${"../".repeat(level)}$path"
                    logger.log { "Extracting sym link $path (${entry.linkName})" }
                }*/
                val src: Path = target.toPath()
                val dest: Path = Paths.get(path)
                if (logLevel > 1) logger.log { "Extracting sym link $target to $dest" }
                // Create symbolic link relative to tar parent dir
                Files.createSymbolicLink(src, dest)
            }
        } else if (entry.isFile) {
            if (logLevel > 2) logger.log { "Extracting file $target" }
            ensureDirectoryExists(target.parentFile)
            BufferedOutputStream(FileOutputStream(target)).use { outputStream ->
                IOUtils.copy(
                    tis,
                    outputStream
                )
            }
        } else {
            logger.log { "$entry is not a currently supported tar entry type." }
        }
        val p: Path = target.toPath()
        if (Files.exists(p)) {
            try {
                //We created it so lets chmod it properly
                val mode = entry.mode
                Files.setPosixFilePermissions(p, parsePerms(mode))
            } catch (e: java.lang.UnsupportedOperationException) {
                logger.log { "Filesystem does not support permissions ${p.toString()}" }
                //Ignored the file system we are on does not support this, so don't do it.
            } catch (e: java.nio.file.AccessDeniedException) {
                logger.log { "chmod -- Permission denied:  ${p.toString()}" }
            }
        }
    }

    private fun unpackTar(tarInputStream: TarArchiveInputStream, symlinksDisabled: Boolean = false) {
        val PREFIX_FILE = File("$PREFIX_PATH/bootstrap")
        //val STAGING_PREFIX_PATH = "${FILES_PATH}/bootstrap/bootstrap-staging"
        val STAGING_PREFIX_PATH = "${FILES_PATH}/bootstrap/bootstrap"
        val STAGING_PREFIX_FILE = File(STAGING_PREFIX_PATH)
        var outputDir = STAGING_PREFIX_PATH

        if (STAGING_PREFIX_FILE.exists()) {
            deleteFolder(STAGING_PREFIX_FILE)
        }

        val buffer = ByteArray(8096)
        val symlinks = ArrayList<Pair<String, String>>(50)
        tarInputStream.use { tarInput ->
            var tarEntry: TarArchiveEntry? = tarInput.nextEntry as TarArchiveEntry
            while (tarEntry != null) {
                unpackTarEntry(tarInputStream, tarEntry, STAGING_PREFIX_FILE, STAGING_PREFIX_PATH, symlinksDisabled, level = 0);
                tarEntry = tarInput.getNextTarEntry()
            }
        }

        //if (!STAGING_PREFIX_FILE.renameTo(PREFIX_FILE)) {
        //    throw RuntimeException("Unable to rename staging folder")
        //}
    }

    private suspend fun installPreBootstrap(arch: String, download: Boolean = false) {
        val zipInputStream =
                if (download) {
                    val asset =
                        getLatestRelease("feelfreelinux/android-linux-bootstrap", arch, "bootstrap")
                    val connection = httpsConnection(asset!!.browserDownloadUrl)
                    ZipInputStream(connection.inputStream)
                } else {
                    ZipInputStream(
                        context.getResources().openRawResource(
                            when (arch) {
                                "aarch64" -> R.raw.bootstrap_aarch64
                                "armv7a" -> R.raw.bootstrap_armv7a
                                "i686" -> R.raw.bootstrap_i686
                                "x86_64" -> R.raw.bootstrap_x86_64
                                else -> -1
                            }
                        )
                    )
                }
        unpackZip(zipInputStream)
    }

    private fun androidArchToLinuxArch(arch: String): String {
        when (arch) {
            "aarch64" -> return "aarch64"
            "armv7a" -> return "armhf"
            "x86", "i686" -> return "x86"
            "x86_64" -> return "x86_64"
            else -> return ""
        }
    }

        private suspend fun downloadTermuxProotDistro(arch: String) {
            // Termux-Proot-Distro
            logger.log(this) { "Downloading $DISTRO_NAME distro..." }
        val distroAsset = getLatestRelease("termux/proot-distro", arch, DISTRO_NAME)
        val distroConnection = httpsConnection(distroAsset!!.browserDownloadUrl)
        runCommand(
            "mv rootfs.tar.xz rootfs.tar.xz.org",
            prooted = false
        ).waitAndPrintOutput(logger)
        transfer(
            distroConnection.inputStream,
            FileOutputStream(PREFIX_PATH + "/rootfs.tar.xz")
        )
    }

    private fun updateProot(arch: String) {
        // Backup existing proot, if exists.
        runCommand(
            "mv $PREFIX_PATH/root/bin/proot $PREFIX_PATH/root/bin/proot.org",
            prooted = false
        ).waitAndPrintOutput(logger)
        when (arch) {
            "aarch64" -> copyRes(R.raw.proot_aarch64, "/root/bin/proot")
            "armv7a" -> copyRes(R.raw.proot_armv7a, "/root/bin/proot")
            "x86", "i686" -> copyRes(R.raw.proot_i686, "/root/bin/proot")
            "x86_64" -> copyRes(R.raw.proot_x86_64, "/root/bin/proot")
            else -> logger.log { "WARNING: No updated proot for $arch found" }
        }
        runCommand(
            "chmod a+x $PREFIX_PATH/root/bin/proot",
            prooted = false
        ).waitAndPrintOutput(logger)
    }

    override suspend fun setupBootstrap(progress: MutableStateFlow<Int>) {
        val bootstrapDir = File(PREFIX_PATH)
        //if (bootstrapDir.isDirectory) runCommand("rm -rf $bootstrapDir; ls -al $bootstrapDir", prooted=false).waitAndPrintOutput(logger)

        ensureHomeDirectory()

        withContext(Dispatchers.IO) {
            val PREFIX_FILE = File(PREFIX_PATH)
            if (PREFIX_FILE.isDirectory) {
                return@withContext
            }

            progress.emit(0)

            try {
                val arch = getArchString()

                val distroAsset = linuxContainersRepository.getNewest(DISTRO_NAME, arch, DISTRO_RELEASE)
                if (distroAsset != null) {
                    logger.log(this) { "Found: ${distroAsset.distro} ${distroAsset.arch} ${distroAsset.release}: ${distroAsset.downloadPath}" }
                } else {
                    logger.log(this) { "No suitable distro found!" }
                    return@withContext
                }

                if (PRE_BOOTSTRAP) {
                    logger.log(this) { "Getting bootstrap rootfs and utils..." }

                    if (!bootstrapDir.isDirectory) {
                        installPreBootstrap(arch, download = false)

                        // Stage 0: Alpine Pre-Bootstrap.
                        runCommand("chmod -R 700 .", prooted = false).waitAndPrintOutput(logger)
                        runCommand(
                            "sh install-bootstrap.sh",
                            prooted = false
                        ).waitAndPrintOutput(logger)

                        logger.log(this) { "Getting rootfs images..." }

                        // Stage 1: Install full distro.
                        copyRes(R.raw.install_full, "bootstrap/install-full.sh")
                        runCommand(
                            "chmod a+x bootstrap/install-full.sh",
                            prooted = false
                        ).waitAndPrintOutput(logger)

                        runCommand("sh /install-full.sh '${distroAsset!!.downloadPath}'").waitAndPrintOutput(
                            logger
                        )
                    }
                } else {
                    logger.log(this) { "Downloading $DISTRO_NAME $DISTRO_RELEASE $arch distro..." }
                    //var linuxArch = androidArchToLinuxArch(arch)
                    //val url = "https://dl-cdn.alpinelinux.org/alpine/v3.14/releases/$linuxArch/alpine-minirootfs-3.14.2-$linuxArch.tar.gz"
                    val url = distroAsset!!.downloadPath

                    logger.log(this) { "URL $url" }
                    val connection = httpsConnection(url)

                    if (false) {
                        val useButOverwritePrebootstrap = false
                        if (!useButOverwritePrebootstrap) {
                            runCommand("mkdir -p root/bin", prooted = false).waitAndPrintOutput(
                                logger
                            )
                            updateProot(arch)
                            runCommand("chmod -R 700 .", prooted = false).waitAndPrintOutput(logger)
                            runCommand("mkdir bootstrap", prooted = false).waitAndPrintOutput(logger)
                            copyRes(R.raw.run_distro, "run-bootstrap.sh")
                            copyRes(R.raw.fake_proc_stat, "fake_proc_stat")
                            runCommand("chmod 700 *", prooted = false).waitAndPrintOutput(logger)
                        } else {
                            installPreBootstrap(arch, download = false)
                            runCommand("chmod -R 700 .", prooted = false).waitAndPrintOutput(logger)
                            runCommand(
                                "sh install-bootstrap.sh",
                                prooted = false
                            ).waitAndPrintOutput(logger)
                        }

                        unpackTar(TarArchiveInputStream(XZCompressorInputStream(connection.inputStream)))

                        runCommand("rm $filesPath/etc/resolv.conf", prooted = false).waitAndPrintOutput(logger)
                        runCommand("mkdir $filesPath/etc/", prooted = false).waitAndPrintOutput(logger)
                        copyResToBootstrap(R.raw.resolv, "/etc/resolv.conf")

                        //runCommand("cd ${PREFIX_PATH}; mv bootstrap/full-distro . && mv bootstrap bootstrap-min && mv full-distro bootstrap", prooted = false).waitAndPrintOutput(logger)
                    } else {
                        if (false) {
                            // untar in prebootstrap
                            installPreBootstrap(arch, download = false)

                            copyRes(R.raw.install_bootstrap, "install-distro.sh")
                            runCommand(
                                "chmod a+x install-distro.sh",
                                prooted = false
                            ).waitAndPrintOutput(logger)

                            transfer(
                                XZCompressorInputStream(connection.inputStream),
                                FileOutputStream("$PREFIX_PATH/rootfs.tar.xz")
                            )

                            runCommand("chmod -R 700 .", prooted = false).waitAndPrintOutput(logger)
                            runCommand(
                                "sh install-distro.sh",
                                prooted = false
                            ).waitAndPrintOutput(logger)
                        } else {
                            installPreBootstrap(arch, download = false)
                            downloadTermuxProotDistro(arch)
                            runCommand("chmod -R 700 .", prooted = false).waitAndPrintOutput(logger)
                            runCommand(
                                "sh install-bootstrap.sh",
                                prooted = false
                            ).waitAndPrintOutput(logger)
                        }
                    }
                }

                progress.emit(5)

                copyRes(R.raw.run_distro, "run-distro.sh")
                runCommand("chmod a+x run-distro.sh", prooted = false).waitAndPrintOutput(logger)

                // Stage 2: Set up and install packages.
                logger.log(this) { "Bootstrap extracted, setting it up..." }
                if (shouldUsePre5Bootstrap()) {
                    runCommand("rm -r root && mv root-pre5 root", prooted = false).waitAndPrintOutput(logger)
                }

                logger.log(this) { "Installed $DISTRO_NAME $DISTRO_RELEASE $arch bootstrap. Configuring..." }
                exec("cat /etc/lsb-release")
                debugProot("ls -al /bin/sh", root=true).waitAndPrintOutput(logger)
                debugProot("/bin/sh -c ls -al /", root=true).waitAndPrintOutput(logger)

                progress.emit(15)

                // Install some dependencies.
                copyResToBootstrap(R.raw.nop, "/usr/bin/nop.sh")

                if (useBaseSetupScript) {
                    copyRes(R.raw.install_full, "bootstrap/install-full.sh")
                    //runCommand("chmod a+x bootstrap/install-full.sh", prooted = false).waitAndPrintOutput(logger)

                    copyResToBootstrap(R.raw.base_setup, "/base-setup.sh")
                    exec("/bin/sh /base-setup.sh")
                } else {
                    exec("apt-get update --allow-releaseinfo-change")
                    exec("apt-get install -q -y --reinstall adduser")
                    exec("cat /etc/resolv.conf")
                    Thread.sleep(5_000)

                    exec("apt-get install -q -y dropbear curl bash sudo git unzip inetutils-traceroute")
                    Thread.sleep(5_000)

                    // python3-virtualenv doesn't seem to work well in (this?) proot - we're supplying our own hacky shim later.

                    // Update PROOT.
                    // Not built for Android -- try this https://github.com/green-green-avk/build-proot-android/raw/master/packages/proot-android-${arch}.tar.gz
                    // val prootAsset = getLatestRelease("proot-me/proot", arch, "proot")
                    // val prootConnection = httpsConnection(prootAsset!!.browserDownloadUrl)
                    // runCommand("mv $PREFIX_PATH/root/bin/proot $PREFIX_PATH/root/bin/proot.org", prooted = false).waitAndPrintOutput(logger)
                    // transfer(prootConnection.inputStream, FileOutputStream(PREFIX_PATH + "/root/bin/proot"))
                    // runCommand("chmod a+x $PREFIX_PATH/root/bin/proot", prooted = false).waitAndPrintOutput(logger)
                    copyResToBootstrap(R.raw.update_proot, "/update-proot.sh")
                    runCommand("chmod a+x /update-proot.sh").waitAndPrintOutput(logger)
                    runCommand("/update-proot.sh 'https://github.com/green-green-avk/build-proot-android/raw/master/packages/proot-android-${arch}.tar.gz'").waitAndPrintOutput(logger)
                    runCommand("mkdir $PREFIX_PATH/root/org; mv $PREFIX_PATH/root/* $PREFIX_PATH/root/org", prooted = false).waitAndPrintOutput(logger)
                    runCommand("cp -r $PREFIX_PATH/bootstrap/proot/root/* $PREFIX_PATH/root", prooted = false).waitAndPrintOutput(logger)

                    progress.emit(20)

                    // Setup docker-systemctl-replacement systemctl simulation.
                    exec("mv /bin/systemctl /bin/systemctl.org")
                    copyResToBootstrap(R.raw.systemctl3, "/bin/systemctl")
                    exec("chmod a+x /bin/systemctl")

                    // Setup ssh.
                    exec("ssh-keygen -A")

                    // Add klipper user.
                    //runCommand("sh add-user.sh klipper", prooted = false).waitAndPrintOutput(logger)
                    exec("mkdir /home/klipper")
                    exec("useradd -U -m -d /home/klipper klipper")
                    exec("echo 'klipper     ALL=(ALL) NOPASSWD:ALL' >> /etc/sudoers")
                }
                val sshdProcess = runProot("/usr/sbin/dropbear -p 8022", root=true)
                sshdProcess.waitAndPrintOutput(logger)

                exec("/usr/sbin/dropbear -p 8022", root=true)

                runProot("passwd", root=true).setPassword("klipper")
                runProot("passwd klipper", root=true).setPassword("klipper")

                // Pre-install some Klipper required packages.
                exec("apt-get install -q -y python3 python3-virtualenv virtualenv python3-dev libffi-dev build-essential libncurses-dev libusb-dev avrdude gcc-avr binutils-avr avr-libc stm32flash libnewlib-arm-none-eabi gcc-arm-none-eabi binutils-arm-none-eabi libusb-1.0 pkg-config dfu-util")

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
        if (!root) user = USER
        if (prooted) {
            // run inside proot
            val shell = if (bash) "/bin/bash" else "/bin/sh"
            pb.command("sh", "run-bootstrap.sh", user,  shell, "-c", command)
        } else {
            pb.command("sh", "-c", command)
        }
        return pb.start()
    }

    private fun runProot2(command: String, root: Boolean): Process {
        logger.log(this) { ":$> ${command}" }

        val FILES = "/data/data/com.klipper4a/files"
        val HOME_BASE = "${filesPath}/home"
        val directory = File(filesPath)
        if (!directory.exists()) directory.mkdirs()
        if (!File(HOME_BASE).exists()) File(HOME_BASE).mkdirs()

        val pb = ProcessBuilder()
        pb.redirectErrorStream(true)
        pb.environment()["HOME"] = "$FILES/bootstrap"
        pb.environment()["TERM"] = "linux"
        pb.environment()["LANG"] = "'en_US.UTF-8'"
        pb.environment()["PWD"] = "$FILES/bootstrap"
        //pb.environment()["EXTRA_BIND"] = "-b ${HOME_BASE}:/home -b /data/data/com.klipper4a/files/serialpipe:/dev/ttyOcto4a -b /data/data/com.klipper4a/files/bootstrap/ioctlHook.so:/usr/lib/ioctlHook.so"
        pb.environment()["EXTRA_BIND"] = "-b ${filesPath}/home:/home -b /data/data/com.klipper4a/files/serialpipe:/dev/ttyOcto4a -b /data/data/com.klipper4a/files/bootstrap/ioctlHook.so:/usr/lib/ioctlHook.so"
        pb.environment()["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/sbin:/product/bin:/apex/com.android.runtime/bin:/system/bin:/system/xbin:/odm/bin:/vendor/bin:/vendor/xbin"
        pb.directory(File("$FILES/bootstrap"))
        var user = "root"
        if (!root) user = USER

        if (!root) {
            pb.command("sh", "run-distro.sh", user, command)
        } else {
            pb.command("sh", "run-distro.sh", user, command)
        }

        return pb.start()
    }

    override fun runProot(command: String, root: Boolean): Process {
        logger.log(this) { ":$> ${command}" }

        copyRes(R.raw.run_distro, "run-bootstrap.sh")
        runCommand("chmod u+x run-bootstrap.sh", prooted = false).waitAndPrintOutput(logger)

        return runCommand(command, root)


        val FILES = "/data/data/com.klipper4a/files"
        val HOME_BASE = "${filesPath}/home"
        val directory = File(filesPath)
        val tmpDirectory = File("$FILES/bootstrap/tmp")
        if (!directory.exists()) directory.mkdirs()
        if (!tmpDirectory.exists()) tmpDirectory.mkdirs()
        if (!File(HOME_BASE).exists()) File(HOME_BASE).mkdirs()

        var USER = "root"
        var HOME = "/root"
        if (!root) {
            USER = "klipper"
            HOME = "/home/klipper"
        }
        val OP = if (root) "-0 " else ""

        //val EXTRA_BIND = "-b ${filesPath}/home:/home -b /data/data/com.klipper4a/files/serialpipe:/dev/ttyOcto4a -b /data/data/com.klipper4a/files/bootstrap/ioctlHook.so:/usr/lib/ioctlHook.so"
        //var command = "./root/bin/proot -r bootstrap/full-distro $OP -b /dev -b /proc -b /sys -b /system -b /vendor -b /storage -b $FILES/bootstrap/fake_proc_stat:/proc/stat $EXTRA_BIND --link2symlink -p -L -w $HOME $command"
        var command =
            if (PRE_BOOTSTRAP) {
                "./root/bin/proot -r bootstrap/full-distro/ $OP-b /dev -b /proc --link2symlink -w $HOME $command"
            } else {
                "./root/bin/proot -r bootstrap/ $OP-b /dev -b /proc --link2symlink -w $HOME /usr/bin/dash -c $command"
            }

        logger.log { "EXEC: $command" }

        val pb = ProcessBuilder(command.split(' '))
        pb.redirectErrorStream(true)
        //pb.environment()["HOME"] = "$FILES/bootstrap"
        pb.environment()["TERM"] = "linux"
        pb.environment()["LANG"] = "'en_US.UTF-8'"
        pb.environment()["PWD"] = "$FILES/bootstrap"
        //pb.environment()["EXTRA_BIND"] = "-b ${HOME_BASE}:/home -b /data/data/com.klipper4a/files/serialpipe:/dev/ttyOcto4a -b /data/data/com.klipper4a/files/bootstrap/ioctlHook.so:/usr/lib/ioctlHook.so"
        //pb.environment()["EXTRA_BIND"] = "-b ${filesPath}/home:/home -b /data/data/com.klipper4a/files/serialpipe:/dev/ttyOcto4a -b /data/data/com.klipper4a/files/bootstrap/ioctlHook.so:/usr/lib/ioctlHook.so"
        pb.environment()["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/sbin:/product/bin:/apex/com.android.runtime/bin:/system/bin:/system/xbin:/odm/bin:/vendor/bin:/vendor/xbin"
        pb.environment()["PROOT_TMP_DIR"] = "$FILES/bootstrap/tmp"
        if (PRE_BOOTSTRAP) {
            pb.environment()["PROOT_L2S_DIR"] = "$FILES/bootstrap/bootstrap/full-distro/.proot.meta"
            pb.environment()["HOME"] = "$FILES/bootstrap"
        } else {
            pb.environment()["PROOT_L2S_DIR"] = "$FILES/bootstrap/bootstrap/.proot.meta"
            pb.environment()["HOME"] = "$HOME"
        }
        pb.directory(File("$FILES/bootstrap"))

        return pb.start()
    }

    private fun debugProot(command: String, root: Boolean): Process {
        logger.log(this) { ":$> ${command}" }

        val FILES = "/data/data/com.klipper4a/files"
        val HOME_BASE = "${filesPath}/home"
        val directory = File(filesPath)
        if (!directory.exists()) directory.mkdirs()
        if (!File(HOME_BASE).exists()) File(HOME_BASE).mkdirs()

        val pb = ProcessBuilder()
        pb.redirectErrorStream(true)
        pb.environment()["HOME"] = "$FILES/bootstrap"
        pb.environment()["TERM"] = "linux"
        pb.environment()["LANG"] = "'en_US.UTF-8'"
        pb.environment()["PWD"] = "$FILES/bootstrap"
        //pb.environment()["EXTRA_BIND"] = "-b ${HOME_BASE}:/home -b /data/data/com.klipper4a/files/serialpipe:/dev/ttyOcto4a -b /data/data/com.klipper4a/files/bootstrap/ioctlHook.so:/usr/lib/ioctlHook.so"
        pb.environment()["EXTRA_BIND"] = "-b ${filesPath}/home:/home -b /data/data/com.klipper4a/files/serialpipe:/dev/ttyOcto4a -b /data/data/com.klipper4a/files/bootstrap/ioctlHook.so:/usr/lib/ioctlHook.so -v 2"
        pb.environment()["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/sbin:/product/bin:/apex/com.android.runtime/bin:/system/bin:/system/xbin:/odm/bin:/vendor/bin:/vendor/xbin"
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

    private fun exec(cmd: String, root: Boolean = true) {
        runProot(cmd, root).waitAndPrintOutput(logger)
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
        get() = File("$FILES_PATH/bootstrap").exists()
}