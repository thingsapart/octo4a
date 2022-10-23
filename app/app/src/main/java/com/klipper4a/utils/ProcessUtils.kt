package com.klipper4a.utils

// import com.bugsnag.android.Bugsnag
import com.klipper4a.repository.LogType
import com.klipper4a.repository.LoggerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

suspend fun <T> withIO(block: suspend CoroutineScope.() -> T) = withContext(Dispatchers.IO, block)

fun Process.waitAndPrintOutput(logger: LoggerRepository, type: LogType = LogType.BOOTSTRAP): String {
    var outputStr = ""
    inputStream.reader().forEachLine {
        logger.log(this, type) { it }
        outputStr += it
    }
    return outputStr
}

fun Process.waitForDoneInstallingAndPrintOutput(logger: LoggerRepository, type: LogType = LogType.BOOTSTRAP): String {
    var outputStr = ""
    val reader = BufferedReader(InputStreamReader(inputStream))
    var line = ""
    while ( reader.readLine()?.also { line = it } != null) {
        logger.log(this, type) { line }
        outputStr += line

        if (line.contains(">> DONE INSTALLING")) {
            break
        }
    }
    inputStream.close()
    destroy()

    return outputStr
}

fun Process.getOutputAsString(): String {
    val log = StringBuilder()
    var line: String?
    while (inputStream.bufferedReader().readLine().also { line = it } != null) {
        log.append(line + "\n")
    }
    log.append(">>exit\n")

    return log.toString()
}

fun Process.setPassword(password: String) {
    outputStream.bufferedWriter().apply {
        write("$password\n")
        flush()
        write("$password\n")
        flush()
    }
}
fun Process.isRunning(): Boolean {
    try {
        exitValue()
    } catch (_: Throwable) {
        return true
    }
    return false
}