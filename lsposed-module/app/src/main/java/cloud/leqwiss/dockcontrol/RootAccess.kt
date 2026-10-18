package cloud.leqwiss.dockcontrol

import java.util.concurrent.TimeUnit

object RootAccess {
    fun requestAccess(onResult: (Boolean, String) -> Unit) {
        runCommand("id", onResult)
    }

    fun runCommand(command: String, onResult: (Boolean, String) -> Unit) {
        Thread {
            var process: Process? = null
            try {
                val startedProcess = ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start()
                process = startedProcess
                val finished = startedProcess.waitFor(10, TimeUnit.SECONDS)
                if (!finished) {
                    startedProcess.destroy()
                    onResult(false, "Root request timed out")
                    return@Thread
                }
                val output = startedProcess.inputStream.bufferedReader().readText().trim()
                onResult(startedProcess.exitValue() == 0, output)
            } catch (throwable: Throwable) {
                onResult(false, throwable.message ?: "Root is unavailable")
            } finally {
                process?.destroy()
            }
        }.start()
    }
}
