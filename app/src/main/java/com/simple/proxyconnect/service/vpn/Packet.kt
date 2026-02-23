package com.simple.proxyconnect.service.vpn

import java.nio.ByteBuffer

/*
 * IP / TCP / UDP packet parsing and building utilities.
 * All multi-byte fields use network byte order (big-endian).
 */

// ── Protocol numbers ─────────────────────────────────────
const val PROTO_ICMP = 1
const val PROTO_TCP = 6
const val PROTO_UDP = 17

// ── TCP flag bits ────────────────────────────────────────
const val TCP_FIN = 0x01
const val TCP_SYN = 0x02
const val TCP_RST = 0x04
const val TCP_PSH = 0x08
const val TCP_ACK = 0x10

// ── Header data classes ──────────────────────────────────

data class Ipv4Header(
    val headerLength: Int,   // bytes
    val totalLength: Int,
    val identification: Int,
    val protocol: Int,       // 6=TCP, 17=UDP
    val sourceIp: Int,
    val destIp: Int
)

data class TcpHeader(
    val sourcePort: Int,
    val destPort: Int,
    val seqNumber: Long,     // unsigned 32-bit stored in Long
    val ackNumber: Long,
    val dataOffset: Int,     // bytes
    val flags: Int,
    val window: Int
) {
    val isSyn get() = flags and TCP_SYN != 0
    val isAck get() = flags and TCP_ACK != 0
    val isFin get() = flags and TCP_FIN != 0
    val isRst get() = flags and TCP_RST != 0
}

data class UdpHeader(
    val sourcePort: Int,
    val destPort: Int,
    val length: Int
)

// ── Packet utilities ─────────────────────────────────────

object Packet {

    /* ====================== PARSING ====================== */

    fun parseIpv4(data: ByteArray): Ipv4Header? {
        if (data.size < 20) return null
        val version = (data[0].toInt() ushr 4) and 0xF
        if (version != 4) return null
        val ihl = (data[0].toInt() and 0xF) * 4
        if (data.size < ihl) return null
        val totalLength = u16(data, 2)
        val identification = u16(data, 4)
        val protocol = data[9].toInt() and 0xFF
        val srcIp = i32(data, 12)
        val dstIp = i32(data, 16)
        return Ipv4Header(ihl, totalLength, identification, protocol, srcIp, dstIp)
    }

    fun parseTcp(data: ByteArray, ipHeaderLen: Int): TcpHeader? {
        val o = ipHeaderLen
        if (data.size < o + 20) return null
        val srcPort = u16(data, o)
        val dstPort = u16(data, o + 2)
        val seqNum = u32(data, o + 4)
        val ackNum = u32(data, o + 8)
        val dataOffset = ((data[o + 12].toInt() ushr 4) and 0xF) * 4
        val flags = data[o + 13].toInt() and 0x3F
        val window = u16(data, o + 14)
        return TcpHeader(srcPort, dstPort, seqNum, ackNum, dataOffset, flags, window)
    }

    fun parseUdp(data: ByteArray, ipHeaderLen: Int): UdpHeader? {
        val o = ipHeaderLen
        if (data.size < o + 8) return null
        return UdpHeader(u16(data, o), u16(data, o + 2), u16(data, o + 4))
    }

    /* ===================== BUILDING ====================== */

    /**
     * Build a complete IPv4 + TCP packet.
     * @param mss if > 0, include MSS TCP option (used in SYN-ACK)
     */
    fun buildTcpPacket(
        srcIp: Int, dstIp: Int,
        srcPort: Int, dstPort: Int,
        seqNum: Long, ackNum: Long,
        flags: Int,
        payload: ByteArray = EMPTY,
        window: Int = 65535,
        mss: Int = 0
    ): ByteArray {
        val hasMss = mss > 0
        val tcpHdrLen = if (hasMss) 24 else 20
        val ipHdrLen = 20
        val totalLen = ipHdrLen + tcpHdrLen + payload.size
        val pkt = ByteArray(totalLen)

        // ── IP header ──
        pkt[0] = 0x45.toByte()                       // v4, IHL=5
        w16(pkt, 2, totalLen)                          // total length
        w16(pkt, 4, ipId())                            // identification
        pkt[6] = 0x40.toByte()                         // DF
        pkt[8] = 64                                    // TTL
        pkt[9] = PROTO_TCP.toByte()
        w32(pkt, 12, srcIp)
        w32(pkt, 16, dstIp)
        val ipCk = checksum(pkt, 0, ipHdrLen)
        w16(pkt, 10, ipCk)

        // ── TCP header ──
        val t = ipHdrLen
        w16(pkt, t, srcPort)
        w16(pkt, t + 2, dstPort)
        w32(pkt, t + 4, (seqNum and 0xFFFFFFFFL).toInt())
        w32(pkt, t + 8, (ackNum and 0xFFFFFFFFL).toInt())
        pkt[t + 12] = ((tcpHdrLen / 4) shl 4).toByte()  // data offset
        pkt[t + 13] = (flags and 0xFF).toByte()
        w16(pkt, t + 14, window)
        // checksum & urgent = 0

        if (hasMss) {
            pkt[t + 20] = 2          // Kind = MSS
            pkt[t + 21] = 4          // Length = 4
            w16(pkt, t + 22, mss)
        }

        // ── payload ──
        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, pkt, t + tcpHdrLen, payload.size)
        }

        // ── TCP checksum (over pseudo-header + segment) ──
        val tcpLen = tcpHdrLen + payload.size
        val tcpCk = tcpChecksum(srcIp, dstIp, pkt, t, tcpLen)
        w16(pkt, t + 16, tcpCk)

        return pkt
    }

    /**
     * Build a complete IPv4 + UDP packet.
     */
    fun buildUdpPacket(
        srcIp: Int, dstIp: Int,
        srcPort: Int, dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val ipHdrLen = 20
        val udpHdrLen = 8
        val udpLen = udpHdrLen + payload.size
        val totalLen = ipHdrLen + udpLen
        val pkt = ByteArray(totalLen)

        // ── IP header ──
        pkt[0] = 0x45.toByte()
        w16(pkt, 2, totalLen)
        w16(pkt, 4, ipId())
        pkt[6] = 0x40.toByte()       // DF
        pkt[8] = 64                   // TTL
        pkt[9] = PROTO_UDP.toByte()
        w32(pkt, 12, srcIp)
        w32(pkt, 16, dstIp)
        val ipCk = checksum(pkt, 0, ipHdrLen)
        w16(pkt, 10, ipCk)

        // ── UDP header ──
        val u = ipHdrLen
        w16(pkt, u, srcPort)
        w16(pkt, u + 2, dstPort)
        w16(pkt, u + 4, udpLen)
        // checksum = 0 (optional for IPv4)

        // ── payload ──
        System.arraycopy(payload, 0, pkt, u + udpHdrLen, payload.size)

        return pkt
    }

    /**
     * Build an IPv4 ICMP Destination Unreachable (Port Unreachable) packet.
     * ICMP payload includes original IP header + first 8 bytes of original payload.
     */
    fun buildIcmpPortUnreachable(
        srcIp: Int,
        dstIp: Int,
        originalPacket: ByteArray
    ): ByteArray {
        val ipHdrLen = 20
        val quotedLen = minOf(28, originalPacket.size)
        val icmpLen = 8 + quotedLen
        val totalLen = ipHdrLen + icmpLen
        val pkt = ByteArray(totalLen)

        // IP header
        pkt[0] = 0x45.toByte()
        w16(pkt, 2, totalLen)
        w16(pkt, 4, ipId())
        pkt[6] = 0x00.toByte()
        pkt[8] = 64
        pkt[9] = PROTO_ICMP.toByte()
        w32(pkt, 12, srcIp)
        w32(pkt, 16, dstIp)
        w16(pkt, 10, checksum(pkt, 0, ipHdrLen))

        // ICMP header
        val o = ipHdrLen
        pkt[o] = 3 // Type: Destination Unreachable
        pkt[o + 1] = 3 // Code: Port Unreachable
        // checksum at o+2..o+3
        // unused 4 bytes are zero (o+4..o+7)

        // quoted original packet bytes
        System.arraycopy(originalPacket, 0, pkt, o + 8, quotedLen)

        // ICMP checksum
        w16(pkt, o + 2, checksum(pkt, o, icmpLen))
        return pkt
    }

    /* ============== SOCKS-style target address ============ */

    fun buildTargetAddress(ip: Int, port: Int): ByteArray {
        val buf = ByteArray(7)
        buf[0] = 0x01 // IPv4
        w32(buf, 1, ip)
        w16(buf, 5, port)
        return buf
    }

    /* =================== Helpers ========================= */

    fun ipToString(ip: Int): String =
        "${(ip ushr 24) and 0xFF}.${(ip ushr 16) and 0xFF}.${(ip ushr 8) and 0xFF}.${ip and 0xFF}"

    // ── unsigned reads ──
    private fun u16(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or
        ((b[o + 1].toLong() and 0xFF) shl 16) or
        ((b[o + 2].toLong() and 0xFF) shl 8) or
        (b[o + 3].toLong() and 0xFF)

    private fun i32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or
        ((b[o + 1].toInt() and 0xFF) shl 16) or
        ((b[o + 2].toInt() and 0xFF) shl 8) or
        (b[o + 3].toInt() and 0xFF)

    // ── writes (big-endian) ──
    private fun w16(b: ByteArray, o: Int, v: Int) {
        b[o] = ((v ushr 8) and 0xFF).toByte()
        b[o + 1] = (v and 0xFF).toByte()
    }

    private fun w32(b: ByteArray, o: Int, v: Int) {
        b[o] = ((v ushr 24) and 0xFF).toByte()
        b[o + 1] = ((v ushr 16) and 0xFF).toByte()
        b[o + 2] = ((v ushr 8) and 0xFF).toByte()
        b[o + 3] = (v and 0xFF).toByte()
    }

    // ── IP identification counter ──
    @Volatile private var idCounter = 1
    private fun ipId(): Int {
        val id = idCounter
        idCounter = (idCounter + 1) and 0xFFFF
        return id
    }

    // ── Internet checksum (RFC 1071) ──
    private fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        var rem = length
        while (rem > 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2; rem -= 2
        }
        if (rem > 0) sum += (data[i].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.toInt().inv() and 0xFFFF
    }

    // ── TCP checksum with pseudo-header ──
    private fun tcpChecksum(srcIp: Int, dstIp: Int, data: ByteArray, tcpOff: Int, tcpLen: Int): Int {
        var sum = 0L
        // pseudo-header
        sum += ((srcIp ushr 16).toLong() and 0xFFFF)
        sum += (srcIp.toLong() and 0xFFFF)
        sum += ((dstIp ushr 16).toLong() and 0xFFFF)
        sum += (dstIp.toLong() and 0xFFFF)
        sum += PROTO_TCP.toLong()
        sum += tcpLen.toLong()
        // tcp segment
        var i = tcpOff
        var rem = tcpLen
        while (rem > 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2; rem -= 2
        }
        if (rem > 0) sum += (data[i].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.toInt().inv() and 0xFFFF
    }

    private val EMPTY = ByteArray(0)
}
