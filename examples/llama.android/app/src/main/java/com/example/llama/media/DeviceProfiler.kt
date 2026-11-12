package com.example.llama.media

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

object DeviceProfiler {

    data class DeviceCapabilities(
        val totalRamMb: Long,
        val isLowRamDevice: Boolean,
        val supportedHwEncoders: Set<String>, // Seznam MIME typů (např. video/hevc)
        val supportedHwDecoders: Set<String>,
        val maxHwResolutionHeight: Int
    )

    private var cachedCapabilities: DeviceCapabilities? = null

    /**
     * Zjistí skutečné limity hardwaru. Volá se jednorázově a výsledek se cachuje.
     * @param ignoreLowRam Bypasse nativní isLowRamDevice check (pro účely testování stability)
     */
    fun getCapabilities(context: Context, ignoreLowRam: Boolean = false): DeviceCapabilities {
        cachedCapabilities?.let { return it }

        // 1. Analýza RAM (Kritické pro SW export a 4K)
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)
        
        val totalRamMb = memInfo.totalMem / (1024 * 1024)
        // Telefon je "Low-End", pokud ho tak označí OS, nebo má méně než 3GB RAM (Lze override)
        val isLowRam = if (ignoreLowRam) false else (actManager.isLowRamDevice || totalRamMb < 3072)

        // 2. Analýza Kodeků (MediaCodecList)
        val hwEncoders = mutableSetOf<String>()
        val hwDecoders = mutableSetOf<String>()
        var maxHeight = 1080 // Default pro velmi staré telefony

        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        for (info in codecList.codecInfos) {
            // Zajímá nás pouze skutečný Hardware, ne Google SW emulace (c2.android.*)
            val isHw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                info.isHardwareAccelerated
            } else {
                !info.name.lowercase().startsWith("omx.google.") && !info.name.lowercase().startsWith("c2.android.")
            }

            if (!isHw) continue

            for (type in info.supportedTypes) {
                if (type.startsWith("video/")) {
                    if (info.isEncoder) hwEncoders.add(type) else hwDecoders.add(type)
                    
                    // Zjištění max rozlišení (pokud je to AVC/H264)
                    if (type == MediaFormat.MIMETYPE_VIDEO_AVC) {
                        try {
                            val caps = info.getCapabilitiesForType(type)
                            val vidCaps = caps.videoCapabilities
                            if (vidCaps.isSizeSupported(3840, 2160)) maxHeight = 2160
                        } catch (e: Exception) {
                            // Ignorovat pády z nekorektně nareportovaných MediaCodec profiles
                        }
                    }
                }
            }
        }

        // Pokud je málo RAM, uměle zastropujeme HW limit na 1080p, aby aplikace nespadla na OOM
        if (isLowRam && maxHeight > 1080) {
            maxHeight = 1080
        }

        val caps = DeviceCapabilities(totalRamMb, isLowRam, hwEncoders, hwDecoders, maxHeight)
        cachedCapabilities = caps
        return caps
    }
}
