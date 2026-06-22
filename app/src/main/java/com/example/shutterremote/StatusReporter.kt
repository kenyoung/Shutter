package com.example.shutterremote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Pushes one-line status/alert datagrams to the Linux workstation over UDP.
 *
 * UDP is connectionless on purpose: a dropped packet just means one skipped
 * update, and there is no socket to reconnect after a Wi-Fi blip or a
 * workstation reboot — which is exactly what an unattended overnight feed wants.
 * All I/O runs on a private IO scope (networking on the main thread throws), and
 * every send swallows its own errors so an unreachable workstation can never
 * interrupt the imaging session.
 *
 * A blank/null [host] means LAN broadcast (255.255.255.255); otherwise it is a
 * unicast IP or hostname, resolved once off the main thread and cached.
 */
class StatusReporter(private val host: String?, private val port: Int) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val socket = DatagramSocket().apply { broadcast = host.isNullOrBlank() }

    @Volatile
    private var address: InetAddress? = null

    /** Queue a payload for delivery and return immediately. */
    fun send(payload: String) {
        scope.launch {
            try {
                val dest = address ?: resolve().also { address = it }
                val bytes = payload.toByteArray(Charsets.UTF_8)
                socket.send(DatagramPacket(bytes, bytes.size, dest, port))
            } catch (_: Exception) {
                // Workstation unreachable / no network — the session carries on.
            }
        }
    }

    private fun resolve(): InetAddress =
        InetAddress.getByName(if (host.isNullOrBlank()) "255.255.255.255" else host)

    fun close() {
        scope.cancel()
        socket.close()
    }
}
