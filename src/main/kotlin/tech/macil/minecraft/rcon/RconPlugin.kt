package tech.macil.minecraft.rcon

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.Logger
import org.bstats.bukkit.Metrics
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin

import java.io.*
import java.util.logging.*
import kotlin.concurrent.thread

class RconPlugin : JavaPlugin() {
    private var webServer: WebServer? = null

    override fun onEnable() {
        saveDefaultConfig()

        Metrics(this)

        var listenAddress: String? = config.getString("listenAddress")
        if (listenAddress == "all") listenAddress = null
        val port = config.getInt("port")

        val webServer = WebServer(listenAddress, port) { command, consumer, remoteIp -> handleCommand(command, consumer, remoteIp) }
        this.webServer = webServer

        webServer.start()
    }

    override fun onDisable() {
        val webServer = this.webServer
        if (webServer != null) {
            this.webServer = null
            webServer.stop()
        }
    }

    private fun handleCommand(command: String, output: OutputStream, remoteIp: String) {
        logger.log(Level.INFO, "rcon($remoteIp): $command")

        thread {
            try {
                PrintWriter(output, false).use { outputPrintWriter ->
                    val appender = RconAppender(outputPrintWriter)
                    try {
                        (LogManager.getRootLogger() as Logger).addAppender(appender)

                        try {
                            server.scheduler.callSyncMethod(this) {
                                server.dispatchCommand(Bukkit.getConsoleSender(), command)
                            }.get()
                            waitForLogsToFlush()
                        } catch (e: Exception) {
                            e.printStackTrace(outputPrintWriter)
                        }
                    } finally {
                        (LogManager.getRootLogger() as Logger).removeAppender(appender)
                    }
                }
            } catch (e: Exception) {
                logger.log(Level.SEVERE, "Unknown error in connection thread", e)
            }
        }
    }

    private fun waitForLogsToFlush() {
        // It seems like the output of some commands is only delivered to our appender
        // asynchronously. I couldn't find a direct way to wait on whatever loggers involved
        // to flush, but I found that just waiting on the next game tick seems to (maybe just
        // mostly) solve the problem in practice.

        // There's a similar issue that some plugins' commands (especially any plugins using a
        // database) don't output any results until some unknown time later. This doesn't help
        // much for those and I don't really intend for that case to get fixed. It's up to the
        // client to hold the connection open longer in those cases.
        server.scheduler.callSyncMethod(this) { null }.get()
    }
}
