package com.klipper4a.repository

import android.R.attr.path
import android.R.attr.start
import android.content.Context
import android.os.Environment
import android.util.Log
import com.google.gson.JsonParser
import com.klipper4a.R
import com.klipper4a.serial.VSPPty
import com.klipper4a.serial.VirtualSerialDriver
import com.klipper4a.utils.*
import com.klipper4a.utils.preferences.MainPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.FileWriter
import java.lang.Exception
import java.lang.reflect.Field
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.math.roundToInt


enum class KlipperServerStatus(val value: Int, val progress: Boolean=false) {
    InstallingBootstrap(0),
    InstallingKlipper(1),
    InstallingMoonraker(2),
    InstallingMainsail(3),
    BootingUp(4, true),
    Running(5),
    ShuttingDown(6, true),
    Stopped(7),
    Corrupted(8)
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
    val klipperVersion: StateFlow<String>
    val usbDeviceStatus: StateFlow<KlipperUsbDeviceStatus>
    val registeredExtensions: StateFlow<List<RegisteredExtension>>
    val cameraServerStatus: StateFlow<Boolean>
    val extrasStatus: StateFlow<KlipperExtrasStatus>

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

    override suspend fun beginInstallation() {
        withContext(Dispatchers.IO) {
            if (!bootstrapRepository.isBootstrapInstalled) {
                logger.log { "No bootstrap detected, proceeding with installation" }
                _serverState.emit(KlipperServerStatus.InstallingBootstrap)
                bootstrapRepository.apply {
                    setupBootstrap()
                }
                logger.log { "Bootstrap installed" }

                _serverState.emit(KlipperServerStatus.InstallingKlipper)
                bootstrapRepository.apply {
                    logger.log { "Copying setup script files to bootstrap..." }
                    runCommand("mkdir scripts", root=false, bash = false).waitAndPrintOutput(logger)

                    copyResToBootstrap(R.raw.install_klipper, "/home/klipper/scripts/install_klipper.sh")
                    copyResToBootstrap(R.raw.install_mainsail, "/home/klipper/scripts/install_mainsail.sh")
                    copyResToBootstrap(R.raw.install_moonraker, "/home/klipper/scripts/install_moonraker.sh")
                    copyResToBootstrap(R.raw.ld_preload, "/home/klipper/scripts/ld_preload.sh")
                    copyResToBootstrap(R.raw.kiauh_preamble, "/home/klipper/scripts/kiauh_preamble.sh")
                    copyResToBootstrap(R.raw.get_kiauh, "/home/klipper/get_kiauh.sh")

                    runCommand("cd /home/klipper; chown klipper get_kiauh.sh; chmod a+x get_kiauh.sh; chown klipper /home/klipper/scripts/*", bash=false).waitAndPrintOutput(logger)

                    val gitCmd = runCommand("bash ./get_kiauh.sh", root=false, prooted=true, bash=false)
                    gitCmd.waitAndPrintOutput(
                        logger
                    )
                    gitCmd.waitFor()
                    runCommand("ls; pwd; cd bootstrap; pwd; ls; cd bootstrap; pwd; ls; cd home/klipper; ls; pwd", prooted=false, bash = false).waitAndPrintOutput(logger)
                    runCommand("ls; pwd; cd kiauh; pwd; ls", root=false, bash = false).waitAndPrintOutput(logger)
                    runCommand("cd kiauh; ls", root=false, bash = false).waitFor()

                    runCommand("cd kiauh; bash ./install_klipper.sh", root=false).waitAndPrintOutput(logger)
                }

                _serverState.emit(KlipperServerStatus.InstallingMoonraker)
                bootstrapRepository.apply {
                    runCommand("cd kiauh; bash ./install_moonraker.sh", root=false).waitAndPrintOutput(logger)
                }

                _serverState.emit(KlipperServerStatus.InstallingMainsail)
                bootstrapRepository.apply {
                    runCommand("cd kiauh; bash ./install_mainsail.sh", root=false).waitAndPrintOutput(logger)
                }

                _serverState.emit(KlipperServerStatus.BootingUp)
                vspPty.cancelPtyThread()
                Thread.sleep(10)
                vspPty.runPtyThread()
                startKlipper()
                logger.log { "Dependencies installed" }
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
                Thread.sleep(1_000)
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

    private fun stuff() {
        bootstrapRepository.apply {
            copyResToBootstrap(R.raw.get_kiauh, "/home/klipper/get_kiauh.sh")

            runCommand(
                "cd /home/klipper; chown klipper get_kiauh.sh; chmod a+x get_kiauh.sh; chown klipper /home/klipper/scripts/*",
                bash = false
            ).waitAndPrintOutput(logger)

            val gitCmd =
                runCommand("./get_kiauh.sh")
            gitCmd.waitAndPrintOutput(
                logger
            )
            gitCmd.waitFor()
        }
    }

    override fun startSSH() {
        stopSSH()
        //systemctl("start", "dropbear").waitAndPrintOutput(logger)
        //systemctl("status", "ssh").waitAndPrintOutput(logger)
        sshdProcess = bootstrapRepository.runCommand("/usr/sbin/dropbear -p 8022", bash = false)

        //bootstrapRepository.runCommand("service ssh status").waitAndPrintOutput(logger)
        //bootstrapRepository.runCommand("service ssh start").waitAndPrintOutput(logger)
        //bootstrapRepository.runCommand("service ssh status").waitAndPrintOutput(logger)
        bootstrapRepository.runCommand("cd /home/klipper; sh ./get_kiauh.sh", bash = false).waitAndPrintOutput(logger)
    }

    override fun stopSSH() {
        // Kills ssh demon
        //systemctl("stop", "ssh").waitAndPrintOutput(logger)
        //systemctl("status", "ssh").waitAndPrintOutput(logger)

        sshdProcess?.destroy()
        bootstrapRepository.runCommand("kill -9 $(pidof dropbear)").waitAndPrintOutput(logger)
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