package com.klipper4a.repository

import android.app.Activity
import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.klipper4a.R
import com.klipper4a.serial.VSPPty
import com.klipper4a.utils.*
import com.klipper4a.utils.preferences.MainPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.reflect.Field
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.math.roundToInt

fun Context.toast(message: CharSequence): Unit {
    val handler = Handler(Looper.getMainLooper());
    handler.post {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

enum class KlipperServerStatus(val value: Int, val progress: Boolean=false) {
    InstallingBootstrap(0),
    InstalledBootstrap(1),
    InstallingKlipper(2),
    InstalledKlipper(3),
    InstallingMoonraker(4),
    InstalledMoonraker(5),
    InstallingMainsail(6),
    InstalledMainsail(7),
    BootingUp(8, true),
    Running(9),
    ShuttingDown(10, true),
    Stopped(11),
    Corrupted(12)
}

enum class KlipperExtrasStatus {
    NotInstalled,
    Installing,
    Installed
}

data class KlipperUsbDeviceStatus(val isAttached: Boolean, val port: String = "")

fun KlipperServerStatus.getInstallationProgress(): Int {
    return ((value.toDouble() / 4) * 100).roundToInt()
}

fun KlipperServerStatus.isInstallationFinished(): Boolean {
    return value == ServerStatus.Running.value
}

interface KlipperHandlerRepository {
    val serverState: StateFlow<KlipperServerStatus>
    val installationProgress: StateFlow<Int>
    val klipperVersion: StateFlow<String>
    val usbDeviceStatus: StateFlow<KlipperUsbDeviceStatus>
    val registeredExtensions: StateFlow<List<RegisteredExtension>>
    val cameraServerStatus: StateFlow<Boolean>
    val extrasStatus: StateFlow<KlipperExtrasStatus>

    suspend fun setInstallationProgress(progress: Int)

    suspend fun beginInstallation()
    fun startKlipper()
    fun stopKlipper()
    fun klipperIsRunning(): Boolean

    fun startKlippy()
    fun stopKlippy()
    fun klippyIsRunning(): Boolean
    fun startMainsail()
    fun stopMainsail()
    fun mainsailIsRunning(): Boolean
    fun startMoonraker()
    fun stopMoonraker()
    fun moonrakerIsRunning(): Boolean
    fun startSSH()
    fun stopSSH()
    fun installExtras()
    fun usbAttached(port: String)
    fun getExtrasStatus()
    fun usbDetached()
    fun resetSSHPassword(password: String)
    fun getConfigValue(value: String): String
    var isCameraServerRunning: Boolean
    val isSSHConfigured: Boolean

    suspend fun installMainsail()
    suspend fun installMoonraker()
    suspend fun installKlipper()

    suspend fun start()
}

class KlipperHandlerRepositoryImpl(
    val context: Context,
    private val preferences: MainPreferences,
    private val logger: LoggerRepository,
    private val bootstrapRepository: BootstrapRepository,
    private val githubRepository: GithubRepository,
    private val extensionsRepository: ExtensionsRepository,
    private val fifoEventRepository: FIFOEventRepository) : KlipperHandlerRepository {
    private val externalStorageSymlinkPath = Environment.getExternalStorageDirectory().path + "/Klipper"
    private val klipperStoragePath = "${context.getExternalFilesDir(null).absolutePath}/.klipper"
    private val vspPty by lazy { VSPPty() }

    private var _serverState = MutableStateFlow(KlipperServerStatus.InstallingBootstrap)
    private var _installationProgress = MutableStateFlow(0)
    private var _extensionsState = MutableStateFlow(listOf<RegisteredExtension>())
    private var _klipperVersion = MutableStateFlow("...")
    private var _usbDeviceStatus = MutableStateFlow(KlipperUsbDeviceStatus(false))
    private var _cameraServerStatus = MutableStateFlow(false)
    private var _extrasStatus = MutableStateFlow(KlipperExtrasStatus.NotInstalled)
    private var wakeLock = Octo4aWakeLock(context, logger)

    private var klipperProcess: Process? = null
    private var sshdProcess: Process? = null
    private var fifoThread: Thread? = null

    override val serverState: StateFlow<KlipperServerStatus> = _serverState
    override val installationProgress: StateFlow<Int> = _installationProgress
    override val registeredExtensions: StateFlow<List<RegisteredExtension>> = _extensionsState
    override val klipperVersion: StateFlow<String> = _klipperVersion
    override val usbDeviceStatus: StateFlow<KlipperUsbDeviceStatus> = _usbDeviceStatus
    override val cameraServerStatus: StateFlow<Boolean> = _cameraServerStatus
    override val extrasStatus: StateFlow<KlipperExtrasStatus> = _extrasStatus

    override var isCameraServerRunning: Boolean
        get() = _cameraServerStatus.value
        set(value) {
            _cameraServerStatus.value = value
        }

    private suspend fun preinstallKiauh() {
        bootstrapRepository.apply {
            runCommand(
                "mkdir -p bootstrap/root/kiauh/scripts",
                prooted = false,
                bash = false
            ).waitAndPrintOutput(logger)

            copyResToBootstrap(R.raw.kiauh_preamble, "/root/scripts/kiauh_preamble.sh")
            copyResToBootstrap(R.raw.get_kiauh, "/root/get_kiauh.sh")
            runCommand("ls -al /root/scripts/", root = true).waitAndPrintOutput(logger)

            runCommand("chmod a+x get_kiauh.sh", root = true).waitAndPrintOutput(logger)
        }
    }

    override suspend fun installKlipper() {
        if (!bootstrapRepository.isBootstrapInstalled) {
            _serverState.emit(KlipperServerStatus.InstallingBootstrap)
            context.toast("Bootstrap installation not found, continue to reinstall")
            return
        }

        _serverState.emit(KlipperServerStatus.InstallingKlipper)
        if (!bootstrapRepository.isKlipperInstalled) {
            withContext(Dispatchers.IO) {
                preinstallKiauh()

                bootstrapRepository.apply {
                    logger.log { "Copying setup script files to bootstrap..." }

                    // Scripts for calling Kiauh directly without the TUI.
                    runCommand("ls -al /root/scripts", root=true).waitAndPrintOutput(logger)
                    copyResToBootstrap(R.raw.install_klipper, "/root/scripts/install_klipper.sh")
                    copyResToBootstrap(R.raw.ld_preload, "/root/scripts/ld_preload.sh")
                    runCommand("chmod 700 /root/scripts/*", root = true).waitAndPrintOutput(logger)

                    setInstallationProgress(25)

                    // Hacky virtualenv shim, just symlinks the system binaries and runs pip as root.
                    // Virtualenv otherwise fails with permission denied in proot.
                    copyResToBootstrap(R.raw.virtualenv, "/root/virtualenv")

                    runCommand("cd /root; bash ./get_kiauh.sh", root=true).waitAndPrintOutput(logger)

                    setInstallationProgress(35)

                    //runProot("cd kiauh; echo 'yes' | bash ./install_klipper.sh", root=true).waitAndPrintOutput(logger)
                    runCommand("cd /root/kiauh; echo 'yes' | ./install_klipper.sh", root=true).waitForDoneInstallingAndPrintOutput(logger)

                    logger.log { "Klipper installed" }
                }
            }
        }
        setInstallationProgress(40)
        _serverState.emit(KlipperServerStatus.InstalledKlipper)
    }

    override suspend fun installMoonraker() {
        if (!bootstrapRepository.isKlipperInstalled) {
            _serverState.emit(KlipperServerStatus.InstallingKlipper)
            context.toast("Klipper installation not found, continue to reinstall")
            return
        }

        _serverState.emit(KlipperServerStatus.InstallingMoonraker)
        if (!bootstrapRepository.isMoonrakerInstalled) {
            bootstrapRepository.apply {
                logger.log { "Installing Moonraker" }
                copyResToBootstrap(R.raw.install_moonraker, "/root/scripts/install_moonraker.sh")
                runCommand("chmod 700 /root/scripts/*", root = true).waitAndPrintOutput(logger)

                // Moonraker will refuse to server directories outside /home (e.g. /root).
                runCommand("mkdir /etc/moonraker").waitAndPrintOutput(logger)
                runCommand(
                    "cd /root/scripts; ./install_moonraker.sh -d /etc/moonraker/printer_data",
                    root = true
                ).waitForDoneInstallingAndPrintOutput(logger)
                logger.log { "Moonraker installed" }
            }
        }
        setInstallationProgress(85)
        _serverState.emit(KlipperServerStatus.InstalledMoonraker)
    }

    override suspend fun installMainsail() {
        if (!bootstrapRepository.isMoonrakerInstalled) {
            _serverState.emit(KlipperServerStatus.InstallingMoonraker)
            context.toast("Moonraker installation not found, continue to reinstall")
            return
        }

        _serverState.emit(KlipperServerStatus.InstallingMainsail)
        if (!bootstrapRepository.isMainsailInstalled) {
            bootstrapRepository.apply {
                logger.log { "Installing Mainsail" }
                copyResToBootstrap(R.raw.install_mainsail_from_kiauh, "/root/scripts/install_mainsail.sh")
                runCommand("chmod 700 /root/scripts/*", root = true).waitAndPrintOutput(logger)

                runCommand(
                    "cd /root/scripts; ./install_mainsail.sh",
                    root = true
                ).waitForDoneInstallingAndPrintOutput(logger)
                logger.log { "Mainsail installed" }
            }
        }
        setInstallationProgress(95)
        _serverState.emit(KlipperServerStatus.InstalledMainsail)
    }

    override suspend fun beginInstallation() {
        _serverState.emit(KlipperServerStatus.InstallingBootstrap)
        if (!bootstrapRepository.isBootstrapInstalled || !bootstrapRepository.isKlipperInstalled || !bootstrapRepository.isMoonrakerInstalled || !bootstrapRepository.isMainsailInstalled) {
            if (!bootstrapRepository.isBootstrapInstalled) {
                withContext(Dispatchers.IO) {
                    logger.log { "No bootstrap detected, proceeding with installation" }
                    bootstrapRepository.apply {
                        setupBootstrap(_installationProgress)
                        runCommand("mkdir -p /root/scripts", root = true).waitAndPrintOutput(logger)
                    }
                    logger.log { "Bootstrap installed" }
                }
                setInstallationProgress(15)
            }
            _serverState.emit(KlipperServerStatus.InstalledBootstrap)
        } else {
            getExtrasStatus()
            startKlipper()
            if (preferences.enableSSH) {
                logger.log { "Enabling ssh" }
                startSSH()
            }
            extensionsRepository.startUpNecessaryExtensions()
        }
    }

    override suspend fun start() {
        _serverState.emit(KlipperServerStatus.InstallingMoonraker)
        bootstrapRepository.apply {
            logger.log { "Installing Moonraker" }
            runCommand("cd /root/kiauh; ./install_moonraker.sh", root=true).waitForDoneInstallingAndPrintOutput(logger)
            logger.log { "Moonraker installed" }

            setInstallationProgress(85)
        }
    }

    override suspend fun setInstallationProgress(progress: Int) {
        _installationProgress.emit(progress)
    }

    override fun getExtrasStatus() {
        val file = File("/data/data/com.klipper4a/files/bootstrap/bootstrap/usr/bin/gcc")

        if(file.exists()) {
            _extrasStatus.value = KlipperExtrasStatus.Installed
        } else if (_extrasStatus.value != KlipperExtrasStatus.Installing) {
            _extrasStatus.value = KlipperExtrasStatus.NotInstalled
        }
    }

    override fun installExtras() {
        /*
        if (_extrasStatus.value == KlipperExtrasStatus.NotInstalled) {
            Thread {
                _extrasStatus.value = KlipperExtrasStatus.Installing
                bootstrapRepository.runCommand("curl -s https://raw.githubusercontent.com/feelfreelinux/octo4a/master/scripts/setup-plugin-extras.sh | bash -s")
                    .waitAndPrintOutput(
                        logger
                    )
                _extrasStatus.value = KlipperExtrasStatus.Installed
            }.start()
        }
         */
    }

    fun getPid(p: Process): Int {
        var pid = -1
        try {
            val f: Field = p.javaClass.getDeclaredField("pid")
            f.isAccessible = true
            pid = f.getInt(p)
            f.isAccessible = false
        } catch (ignored: Throwable) {
            pid = try {
                val m: Matcher = Pattern.compile("pid=(\\d+)").matcher(p.toString())
                if (m.find()) m.group(1).toInt() else -1
            } catch (ignored2: Throwable) {
                -1
            }
        }
        return pid
    }

    override fun startKlipper() {
        if (!isInstalledProperly) {
            _serverState.value = KlipperServerStatus.Corrupted
            return
        }
        wakeLock.acquire()

        if (klipperIsRunning()) {
            logger.log { "Failed to start. Klipper already running." }
        }
        bootstrapRepository.run {
            vspPty.createEventPipe()
        }
        _serverState.value = KlipperServerStatus.BootingUp
        startKlippy()
        startMoonraker()
        startMainsail()
        Thread {
            while (!klipperIsRunning()) {
                Thread.sleep(10_000)
            }
            _serverState.value = KlipperServerStatus.Running
        }.start()
        if (fifoThread?.isAlive != true) {
            fifoThread = Thread {
                fifoEventRepository.handleFifoEvents()
            }
            fifoThread?.start()
        }
    }

    private fun serviceIsRunning(service: String): Boolean {
        return systemctl("status", "klipper").getOutputAsString().contains("Active: active")
    }

    private fun systemctl(action: String, service: String): Process {
        //return bootstrapRepository.runCommand("systemctl ${action} ${service}")
        return bootstrapRepository.runCommand("service ${service} ${action}")
    }

    override fun klipperIsRunning(): Boolean {
        return klippyIsRunning() && moonrakerIsRunning() && mainsailIsRunning()
    }

    override fun startKlippy() {
        systemctl("start", "klipper")
    }

    override fun stopKlippy() {
        systemctl("stop", "klipper")
    }

    override fun klippyIsRunning(): Boolean {
        return serviceIsRunning("klipper")
    }

    override fun startMainsail() {
        systemctl("start", "nginx")
    }

    override fun stopMainsail() {
        systemctl("stop", "nginx")
    }

    override fun mainsailIsRunning(): Boolean {
        return serviceIsRunning("nginx")
    }

    override fun startMoonraker() {
        systemctl("start", "moonraker")
    }

    override fun stopMoonraker() {
        systemctl("stop", "moonraker")
    }

    override fun moonrakerIsRunning(): Boolean {
        return serviceIsRunning("moonraker")
    }

    override fun getConfigValue(value: String): String {
        return ""
    }

    override fun stopKlipper() {
        wakeLock.remove()

        _serverState.value = KlipperServerStatus.ShuttingDown

        stopMainsail()
        stopMoonraker()
        stopKlippy()

        Thread {
            while(!Thread.interrupted()) {
                Thread.sleep(500)

                if (!serviceIsRunning("klipper")) {
                    break
                }
            }
        }.start()
    }

    override fun resetSSHPassword(password: String) {
        bootstrapRepository.resetSSHPassword(password)
    }

    override fun startSSH() {
        stopSSH()
        //systemctl("start", "dropbear").waitAndPrintOutput(logger)
        //systemctl("status", "ssh").waitAndPrintOutput(logger)
        sshdProcess = bootstrapRepository.runCommand("/usr/sbin/dropbear -p 8022", bash = false)

        //bootstrapRepository.runCommand("service ssh status").waitAndPrintOutput(logger)
        //bootstrapRepository.runCommand("service ssh start").waitAndPrintOutput(logger)
        //bootstrapRepository.runCommand("service ssh status").waitAndPrintOutput(logger)
    }

    override fun stopSSH() {
        // Kills ssh demon
        //systemctl("stop", "ssh").waitAndPrintOutput(logger)
        //systemctl("status", "ssh").waitAndPrintOutput(logger)

        sshdProcess?.destroy()
        // bootstrapRepository.runCommand("kill -9 $(pidof dropbear)").waitAndPrintOutput(logger)
        logger.log(this) { "killed sshd" }
    }

    override fun usbAttached(port: String) {
        _usbDeviceStatus.value = KlipperUsbDeviceStatus(true, port)
    }

    override fun usbDetached() {
        _usbDeviceStatus.value = KlipperUsbDeviceStatus(false)
    }

    override val isSSHConfigured: Boolean
        get() = bootstrapRepository.isSSHConfigured

    // Validate installation
    val isInstalledProperly: Boolean
        get() = !systemctl("status", "klipper").getOutputAsString().contains("could not be found")
}