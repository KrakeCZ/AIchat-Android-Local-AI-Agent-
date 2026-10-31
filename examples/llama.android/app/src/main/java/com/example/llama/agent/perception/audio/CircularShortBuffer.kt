package com.example.llama.agent.perception.audio

import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * Lock-free, Zero-I/O Circular Buffer for PCM Audio (Short).
 * Navrženo v C-style pro maximální výkon a nulový Garbage Collection (GC).
 * Využívá AtomicLong pro synchronizaci hlavy a ohonu, čímž eliminuje potřebu mutexů.
 */
class CircularShortBuffer(capacityElements: Int) {
    // Zarovnáme kapacitu na mocninu 2 (bitový AND maskování místo pomalého modula)
    private val capacity: Int = Integer.highestOneBit(capacityElements - 1) shl 1
    private val mask: Int = capacity - 1

    // Alokováno JEDNOU při startu. Nezahazuje se.
    private val buffer: ShortArray = ShortArray(capacity)

    // Absolutní pozice (nikdy se neresetují na 0, pouze plynou do +nekonečna).
    // Čtení se provádí pomocí masky (head & mask).
    private val head = AtomicLong(0) // Kde se zapisuje (Producer)
    private val tail = AtomicLong(0) // Kde se čte (Consumer)

    /**
     * Zápis nových vzorků.
     * @param data Zdrojové pole vzorků
     * @param length Počet vzorků k zápisu
     * @return Počet úspěšně zapsaných vzorků (0 pokud je buffer plný)
     */
    fun write(data: ShortArray, length: Int): Int {
        val currentTail = tail.get()
        val currentHead = head.get()

        // Zjistíme volné místo
        val availableSpace = capacity - (currentHead - currentTail).toInt()
        if (availableSpace <= 0) return 0 // Buffer Overrun

        val toWrite = min(length, availableSpace)

        // Zápis ve dvou fázích (pokud přeteče přes konec fyzického pole)
        val headIdx = (currentHead.toInt() and mask)
        val rightSpace = capacity - headIdx

        if (toWrite <= rightSpace) {
            System.arraycopy(data, 0, buffer, headIdx, toWrite)
        } else {
            System.arraycopy(data, 0, buffer, headIdx, rightSpace)
            System.arraycopy(data, rightSpace, buffer, 0, toWrite - rightSpace)
        }

        // Atomický posun hlavy - zviditelní data pro Consumer
        head.addAndGet(toWrite.toLong())
        return toWrite
    }

    /**
     * Čtení vzorků z bufferu. Zero-allocation (čte do předem alokovaného pole).
     * @param dest Cílové pole, kam se budou vzorky kopírovat
     * @param length Požadovaný počet vzorků
     * @return Skutečný počet přečtených vzorků
     */
    fun read(dest: ShortArray, length: Int): Int {
        val currentHead = head.get()
        val currentTail = tail.get()

        // Zjistíme dostupná data
        val availableData = (currentHead - currentTail).toInt()
        if (availableData <= 0) return 0 // Buffer Underrun (Prázdno)

        val toRead = min(length, availableData)

        // Čtení ve dvou fázích
        val tailIdx = (currentTail.toInt() and mask)
        val rightSpace = capacity - tailIdx

        if (toRead <= rightSpace) {
            System.arraycopy(buffer, tailIdx, dest, 0, toRead)
        } else {
            System.arraycopy(buffer, tailIdx, dest, 0, rightSpace)
            System.arraycopy(buffer, 0, dest, rightSpace, toRead - rightSpace)
        }

        // Atomický posun ohonu
        tail.addAndGet(toRead.toLong())
        return toRead
    }

    /**
     * Vrátí aktuální počet dostupných (nepřečtených) vzorků v bufferu.
     */
    fun available(): Int {
        return (head.get() - tail.get()).toInt()
    }

    /**
     * Vyprázdní buffer (reset hlavy a ohonu).
     */
    fun clear() {
        head.set(0)
        tail.set(0)
    }
}
