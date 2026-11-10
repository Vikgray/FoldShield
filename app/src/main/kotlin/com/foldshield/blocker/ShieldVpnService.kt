package com.foldshield.blocker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

class ShieldVpnService : VpnService() {

    companion object {
        @JvmField val blockedCount = AtomicInteger(0)
        @JvmField val totalQueries = AtomicInteger(0)
        @Volatile @JvmField var loadedDomainCount = 0
        @Volatile @JvmField var isRunning = false

        private const val VPN_DNS_IP = "10.111.222.1"
        private const val VPN_INTERFACE_IP = "10.111.222.2"
        private const val UPSTREAM_DNS = "1.1.1.1"
        private const val CHANNEL_ID = "foldshield_vpn"
        private const val NOTIF_ID = 1
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var workerThread: Thread? = null
    @Volatile private var running = false

    private val blocklist = HashSet<String>()
    private val whitelist = HashSet<String>()

    private var upstreamSocket: DatagramSocket? = null
    private var notifHandler: Handler? = null
    private var notifUpdater: Runnable? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_SHIELD") {
            stopVpn()
            return START_NOT_STICKY
        }

        if (intent?.action == "UPDATE_WHITELIST") {
            loadWhitelist()
            return START_STICKY
        }

        if (intent?.action == "RELOAD_BLOCKLIST") {
            loadBlocklist()
            try {
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIF_ID, buildNotification())
            } catch (_: Exception) {}
            return START_STICKY
        }

        if (vpnInterface == null) {
            createNotificationChannel()
            try {
                val n = buildNotification()
                if (Build.VERSION.SDK_INT >= 34) {
                    startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                } else {
                    startForeground(NOTIF_ID, n)
                }
            } catch (_: Exception) {}

            loadBlocklist()
            loadWhitelist()
            startVpn()
            startNotificationUpdater()
        }
        return START_STICKY
    }

    private fun loadWhitelist() {
        whitelist.clear()
        val prefs = getSharedPreferences("foldshield_prefs", Context.MODE_PRIVATE)
        val saved = prefs.getStringSet("whitelist_domains", emptySet()) ?: emptySet()
        saved.forEach { whitelist.add(it.lowercase()) }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, "FoldShield VPN", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Статус локального DNS-фильтра"
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): android.app.Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        var piFlags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) piFlags = piFlags or PendingIntent.FLAG_IMMUTABLE
        val openPi = PendingIntent.getActivity(this, 0, openIntent, piFlags)

        val stopIntent = Intent(this, ShieldVpnService::class.java).apply { action = "STOP_SHIELD" }
        val stopPi = PendingIntent.getService(this, 1, stopIntent, piFlags)

        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FoldShield активен")
            .setContentText("Заблокировано: ${blockedCount.get()} · доменов: $loadedDomainCount")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(openPi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Остановить", stopPi)

        return b.build()
    }

    private fun startNotificationUpdater() {
        notifHandler = Handler(Looper.getMainLooper())
        notifUpdater = object : Runnable {
            override fun run() {
                if (!running) return
                try {
                    val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(NOTIF_ID, buildNotification())
                } catch (_: Exception) {}
                notifHandler?.postDelayed(this, 2000)
            }
        }
        notifHandler?.postDelayed(notifUpdater!!, 2000)
    }

    private fun stopNotificationUpdater() {
        notifUpdater?.let { notifHandler?.removeCallbacks(it) }
        notifHandler = null
        notifUpdater = null
    }

    private fun loadBlocklist() {
        blocklist.clear()
        val builtin = arrayOf(
            "doubleclick.net", "googleadservices.com", "googlesyndication.com",
            "google-analytics.com", "googletagmanager.com", "googletagservices.com",
            "app-measurement.com", "adservice.google.com", "firebaselogging.googleapis.com",
            "amazon-adsystem.com", "adnxs.com", "adsrvr.org", "criteo.com", "criteo.net",
            "taboola.com", "outbrain.com", "scorecardresearch.com", "quantserve.com",
            "moatads.com", "adsymptotic.com", "rubiconproject.com", "pubmatic.com",
            "openx.net", "33across.com", "sharethrough.com", "smartadserver.com", "teads.tv",
            "inmobi.com", "adcolony.com", "applovin.com", "vungle.com",
            "chartboost.com", "ironsrc.com", "fyber.com", "supersonicads.com",
            "startapp.com", "mopub.com", "tapjoy.com", "unityads.unity3d.com", "unityads.unity.com",
            "appsflyer.com", "adjust.com", "branch.io", "kochava.com",
            "mixpanel.com", "amplitude.com", "segment.com", "heap.io",
            "samsungads.com", "events.samsungads.com", "config.samsungads.com",
            "samsungrs.com", "gld.push.samsungosp.com", "dls.di.atlas.samsung.com",
            "an.yandex.ru", "ads.yandex.ru", "adfox.ru", "adfox.yandex.ru",
            "adsdk.yandex.ru", "awaps.yandex.ru"
        )
        builtin.forEach { blocklist.add(it.lowercase()) }

        try {
            val customFile = File(filesDir, "custom_blocklist.txt")
            if (customFile.exists()) {
                customFile.inputStream().use { input ->
                    parseStream(input)
                }
            }
        } catch (_: Exception) {}

        try {
            val resId = resources.getIdentifier("blocklist", "raw", packageName)
            if (resId != 0) {
                resources.openRawResource(resId).use { input ->
                    parseStream(input)
                }
            }
        } catch (_: Exception) {}

        loadedDomainCount = blocklist.size
    }

    private fun parseStream(input: java.io.InputStream) {
        BufferedReader(InputStreamReader(input)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                var l = line!!.trim()
                if (l.isEmpty() || l.startsWith("#") || l.startsWith("!")) continue
                if (l.startsWith("||")) l = l.substring(2)
                if (l.endsWith("^")) l = l.substring(0, l.length - 1)
                val parts = l.split(Regex("\\s+"))
                var domain = if (parts.size >= 2) parts.last() else parts[0]
                if (domain.startsWith("*.")) domain = domain.substring(2)
                if (domain.isEmpty() || !domain.contains(".")) continue
                if (domain.matches(Regex("[0-9.]+"))) continue
                blocklist.add(domain.lowercase())
            }
        }
    }

    private fun startVpn() {
        try {
            val builder = Builder()
                .setSession("FoldShield Local DNS")
                .addAddress(VPN_INTERFACE_IP, 24)
                .addDnsServer(VPN_DNS_IP)
                .addRoute(VPN_DNS_IP, 32)
                .addDisallowedApplication(packageName)
                .setMtu(1500)
                .setBlocking(true)

            vpnInterface = builder.establish()
            if (vpnInterface != null) {
                running = true
                isRunning = true
                blockedCount.set(0)
                totalQueries.set(0)
                workerThread = Thread { packetLoop() }.also { it.start() }
            } else stopVpn()
        } catch (_: Exception) {
            stopVpn()
        }
    }

    private fun packetLoop() {
        try {
            val fd = vpnInterface?.fileDescriptor ?: return
            FileInputStream(fd).use { input ->
                FileOutputStream(fd).use { output ->
                    val buffer = ByteArray(32767)
                    while (running) {
                        val len = try { input.read(buffer) } catch (_: Exception) {
                            if (!running) break else continue
                        }
                        if (len <= 0) { Thread.sleep(10); continue }
                        try { handlePacket(buffer, len, output) } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun handlePacket(packet: ByteArray, length: Int, out: FileOutputStream) {
        if (length < 28) return
        if (((packet[0].toInt() shr 4) and 0x0F) != 4) return
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < 20 || length < ihl + 8) return

        val ipTotalLen = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        if (ipTotalLen < ihl + 8 || ipTotalLen > length) return

        val dstIp = ((packet[16].toInt() and 0xFF) shl 24) or
                ((packet[17].toInt() and 0xFF) shl 16) or
                ((packet[18].toInt() and 0xFF) shl 8) or
                (packet[19].toInt() and 0xFF)
        if (dstIp != 0x0A6FDE01) return

        if ((packet[9].toInt() and 0xFF) != 17) return

        val udpOffset = ihl
        val dstPort = ((packet[udpOffset + 2].toInt() and 0xFF) shl 8) or (packet[udpOffset + 3].toInt() and 0xFF)
        if (dstPort != 53) return

        val dnsOffset = ihl + 8
        val dnsLen = ipTotalLen - dnsOffset
        if (dnsLen < 12) return

        val dnsQuery = ByteArray(dnsLen)
        System.arraycopy(packet, dnsOffset, dnsQuery, 0, dnsLen)

        var domain = parseDomain(dnsQuery) ?: return
        if (domain.isEmpty()) return
        if (domain.endsWith(".")) domain = domain.dropLast(1)

        totalQueries.incrementAndGet()

        val dnsResponse: ByteArray = if (isBlocked(domain)) {
            blockedCount.incrementAndGet()
            buildDnsError(dnsQuery, 3)
        } else {
            forwardToUpstream(dnsQuery) ?: buildDnsError(dnsQuery, 2)
        }

        val responsePacket = buildResponsePacket(packet, ihl, udpOffset, ipTotalLen, dnsResponse) ?: return
        try { out.write(responsePacket) } catch (_: Exception) {}
    }

    private fun forwardToUpstream(query: ByteArray): ByteArray? {
        return try {
            if (upstreamSocket == null || upstreamSocket!!.isClosed) {
                upstreamSocket = DatagramSocket().also {
                    protect(it)
                    it.soTimeout = 3000
                }
            }
            val upstream = InetAddress.getByName(UPSTREAM_DNS)
            upstreamSocket!!.send(DatagramPacket(query, query.size, upstream, 53))

            val buf = ByteArray(4096)
            val inPacket = DatagramPacket(buf, buf.size)
            upstreamSocket!!.receive(inPacket)
            buf.copyOf(inPacket.length)
        } catch (_: Exception) { null }
    }

    private fun parseDomain(dns: ByteArray): String? {
        return try {
            var pos = 12
            val sb = StringBuilder()
            while (pos < dns.size) {
                val len = dns[pos].toInt() and 0xFF
                if (len == 0) break
                if (len > 63) return null
                pos++
                if (pos + len > dns.size) return null
                if (sb.isNotEmpty()) sb.append('.')
                sb.append(String(dns, pos, len))
                pos += len
            }
            sb.toString()
        } catch (_: Exception) { null }
    }

    private fun isBlocked(domain: String): Boolean {
        var cur = domain.lowercase()

        var checkWhitelist = cur
        while (true) {
            if (whitelist.contains(checkWhitelist)) return false
            val dot = checkWhitelist.indexOf('.')
            if (dot < 0) break
            checkWhitelist = checkWhitelist.substring(dot + 1)
            if (checkWhitelist.indexOf('.') < 0) break
        }

        while (true) {
            if (blocklist.contains(cur)) return true
            val dot = cur.indexOf('.')
            if (dot < 0) break
            cur = cur.substring(dot + 1)
            if (cur.indexOf('.') < 0) break
        }
        return false
    }

    private fun buildDnsError(query: ByteArray, rcode: Int): ByteArray {
        val response = query.copyOf()
        response[2] = 0x81.toByte()
        response[3] = (0x80 or (rcode and 0x0F)).toByte()
        for (i in 6..11) response[i] = 0
        return response
    }

    private fun buildResponsePacket(request: ByteArray, ihl: Int, udpOffset: Int, ipTotalLen: Int, dnsPayload: ByteArray): ByteArray? {
        return try {
            val totalLen = ihl + 8 + dnsPayload.size
            val pkt = ByteArray(totalLen)
            System.arraycopy(request, 0, pkt, 0, ihl)
            pkt[2] = ((totalLen shr 8) and 0xFF).toByte()
            pkt[3] = (totalLen and 0xFF).toByte()
            pkt[8] = 64
            pkt[10] = 0; pkt[11] = 0

            val srcIP = ByteArray(4)
            val dstIP = ByteArray(4)
            System.arraycopy(pkt, 12, srcIP, 0, 4)
            System.arraycopy(pkt, 16, dstIP, 0, 4)
            System.arraycopy(dstIP, 0, pkt, 12, 4)
            System.arraycopy(srcIP, 0, pkt, 16, 4)

            var sum = 0
            var i = 0
            while (i < ihl) {
                sum += ((pkt[i].toInt() and 0xFF) shl 8) or (pkt[i + 1].toInt() and 0xFF)
                i += 2
            }
            while ((sum shr 16) != 0) sum = (sum and 0xFFFF) + (sum shr 16)
            sum = sum.inv() and 0xFFFF
            pkt[10] = ((sum shr 8) and 0xFF).toByte()
            pkt[11] = (sum and 0xFF).toByte()

            pkt[udpOffset] = 0; pkt[udpOffset + 1] = 53
            pkt[udpOffset + 2] = request[udpOffset]
            pkt[udpOffset + 3] = request[udpOffset + 1]
            val udpLen = 8 + dnsPayload.size
            pkt[udpOffset + 4] = ((udpLen shr 8) and 0xFF).toByte()
            pkt[udpOffset + 5] = (udpLen and 0xFF).toByte()
            pkt[udpOffset + 6] = 0; pkt[udpOffset + 7] = 0

            System.arraycopy(dnsPayload, 0, pkt, udpOffset + 8, dnsPayload.size)
            pkt
        } catch (_: Exception) { null }
    }

    private fun stopVpn() {
        running = false
        isRunning = false
        stopNotificationUpdater()
        workerThread?.interrupt()
        workerThread = null
        upstreamSocket?.close()
        upstreamSocket = null
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null

        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}

        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}