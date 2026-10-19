package dev.vesta.app

object MacSystem {
    fun totalMemoryBytes(): Long = runCatching {
        val process = ProcessBuilder("/usr/sbin/sysctl", "-n", "hw.memsize").start()
        process.inputStream.bufferedReader().readText().trim().toLong()
    }.getOrElse { Runtime.getRuntime().maxMemory() }
}
