package com.example.llama.agent.perception.audio

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Nízkoúrovňový asynchronní zapisovač audia na disk.
 * Minimalizuje I/O Bottlenecky využitím:
 * 1. Java NIO FileChannel pro přímý paměťový přenos.
 * 2. Pre-alokovaného Direct ByteBufferu (mimo GC Java haldy).
 * 3. Velkých asynchronních bloků (chunking).
 */
class AsyncWavMuxer(
    private val outputFile: File,
    private val sampleRate: Int = 16000
) {
    companion object {
        private const val TAG = "AsyncWavMuxer"
        // 16kB chunk size for disk write (optimal for flash storage block sizes)
        private const val CHUNK_SIZE_SHORTS = 8192 
    }

    private var muxerJob: Job? = null
    private var fileChannel: FileChannel? = null
    private var totalDataBytesWritten = 0L

    /** Pauza nahrávání — data z producenta se zahazují, WAV soubor neobsahuje ticho. */
    private val isPaused = AtomicBoolean(false)

    fun pause()  { isPaused.set(true);  Log.d(TAG, "Muxer pozastaven") }
    fun resume() { isPaused.set(false); Log.d(TAG, "Muxer obnoven") }

    // Direct ByteBuffer zabraňuje JNI GC pauzám při zápisu na disk
    private val directIoBuffer = ByteBuffer.allocateDirect(CHUNK_SIZE_SHORTS * 2).apply {
        order(ByteOrder.LITTLE_ENDIAN)
    }
    private val shortArrayBuffer = ShortArray(CHUNK_SIZE_SHORTS)

    fun start() {
        if (muxerJob?.isActive == true) return

        try {
            val fos = FileOutputStream(outputFile)
            fileChannel = fos.channel
            
            // Zápis provizorní WAV hlavičky (velikost souboru se upraví na konci)
            writeWavHeader(fileChannel!!, 0)

            muxerJob = CoroutineScope(Dispatchers.IO).launch {
                Log.i(TAG, "Asynchronní Muxer spuštěn: ${outputFile.absolutePath}")
                kotlinx.coroutines.awaitCancellation()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Nelze spustit Muxer", e)
        }
    }

    /**
     * Push method volaná z Engine (producenta). 
     * Rychlý zápis přes Direct ByteBuffer.
     * Data se zahazují pokud je muxer pozastaven (isPaused == true).
     */
    fun pushAudioData(data: ShortArray, length: Int) {
        if (isPaused.get()) return
        if (muxerJob?.isActive == true && fileChannel?.isOpen == true) {
            writeToDisk(data, length)
        }
    }

    private fun writeToDisk(data: ShortArray, length: Int) {
        directIoBuffer.clear()
        val shortBuffer = directIoBuffer.asShortBuffer()
        shortBuffer.put(data, 0, length)
        directIoBuffer.position(0)
        directIoBuffer.limit(length * 2)
        
        while (directIoBuffer.hasRemaining()) {
            fileChannel?.write(directIoBuffer)
        }
        totalDataBytesWritten += (length * 2)
    }

    suspend fun stop() {
        // Nejprve musíme korektně a s jistotou zastavit zapisovací smyčku
        muxerJob?.cancelAndJoin()
        muxerJob = null
        
        // Až poté opravíme WAV hlavičku a soubor zavřeme (zamezí ClosedChannelException)
        try {
            fileChannel?.position(0)
            writeWavHeader(fileChannel!!, totalDataBytesWritten)
            fileChannel?.close()
            Log.i(TAG, "Muxer bezpečně ukončen, WAV hlavička uzavřena. Total bytes: $totalDataBytesWritten")
        } catch (e: Exception) {
            Log.e(TAG, "Chyba při uzavírání Muxeru", e)
        }
    }

    /**
     * Vytvoří standardní 44-bajtovou WAV PCM hlavičku
     */
    private fun writeWavHeader(channel: FileChannel, totalAudioLen: Long) {
        val totalDataLen = totalAudioLen + 36
        val channels = 1
        val byteRate = sampleRate * channels * 2

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        header[20] = 1; header[21] = 0
        header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (2 * channels).toByte(); header[33] = 0
        header[34] = 16; header[35] = 0
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

        val buf = ByteBuffer.wrap(header)
        while (buf.hasRemaining()) {
            channel.write(buf)
        }
    }
}
