package com.example.llama.agent.vision

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.util.Log
import kotlinx.coroutines.*
import com.example.llama.agent.perception.PerceptionEventBus
import com.example.llama.agent.perception.VisionUpdateEvent
import com.example.llama.agent.perception.SemanticMaskEvent

/**
 * SmartVisionOverlayManager
 *
 * Zodpovídá za vykreslování vizuální zpětné vazby multimodálního agenta.
 * Využívá [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY] pro neinvazivní 
 * zobrazení (např. obdélníky rozpoznaných objektů) nad ostatními aplikacemi.
 * Nastavení [WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE] propouští dotyky na UI níže.
 *
 * Integruje [BoundingBoxView] pro kreslení detekcí na Canvas.
 */
class SmartVisionOverlayManager(private val context: Context) {

    companion object {
        private const val TAG = "SmartVisionOverlay"
    }

    private var windowManager: WindowManager? = null
    private var overlayView: FrameLayout? = null
    private var boundingBoxView: BoundingBoxView? = null
    private var semanticMaskView: SemanticMaskView? = null
    private var poseSkeletonView: PoseSkeletonView? = null


    private var isShowing = false
    private var overlayScope: CoroutineScope? = null

    init {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    /**
     * Vytvoří a zobrazí průhlednou vrstvu s BoundingBoxView.
     * Vyžaduje spuštění z kontextu AccessibilityService, jinak může vyžadovat SYSTEM_ALERT_WINDOW oprávnění.
     */
    fun showOverlay() {
        if (isShowing) return

        try {
            val bbView = BoundingBoxView(context)
            val smView = SemanticMaskView(context) // NOVÉ
            val psView = PoseSkeletonView(context) // NOVÉ

            val container = FrameLayout(context).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                addView(smView, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))
                addView(psView, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))
                addView(bbView, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))
            }
            
            overlayView = container
            boundingBoxView = bbView
            semanticMaskView = smView
            poseSkeletonView = psView

            // Android O a novější: TYPE_ACCESSIBILITY_OVERLAY
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                // FLAG_NOT_TOUCHABLE zajistí, že vrstva "nepřekáží" v používání telefonu
                // FLAG_NOT_FOCUSABLE zajistí, že nebere focus z input polí
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }

            windowManager?.addView(overlayView, params)
            
            // Poznámka: Původně tu byl pokus o dynamické Touch Insets přes OnComputeInternalInsetsListener,
            // což je ale na Android 10+ zablokované (hidden API). Doteky se proto neodečítají přes WindowManager.
            // FLAG_NOT_TOUCHABLE v params naštěstí zajišťuje plnou průchodnost doteků pro uživatele.
            
            isShowing = true
            Log.i(TAG, "✅ SmartVision overlay zobrazen s Interaktivním BoundingBoxView.")
            
            // 🔴 NOVÉ: Zapnout poslech Eventů na UI vlákně 🔴
            startListening()

        } catch (e: Exception) {
            Log.e(TAG, "Nelze zobrazit SmartVision overlay: ${e.message}", e)
        }
    }

    /**
     * Skryje a odstraní vrstvu ze systému
     */
    fun hideOverlay() {
        if (!isShowing) return
        try {
            stopListening() // Vypnout posluchač (Zero-Leak)
            poseSkeletonView?.clearSkeletons()
            boundingBoxView?.clearDetections()
            overlayView?.let { windowManager?.removeView(it) }
            overlayView = null
            boundingBoxView = null
            semanticMaskView = null
            poseSkeletonView = null
            isShowing = false
            Log.i(TAG, "SmartVision overlay skryt.")
        } catch (e: Exception) {
            Log.e(TAG, "Chyba při skrývání overlaye: ${e.message}")
        }
    }

    /**
     * Vykreslí seznam detekcí přes BoundingBoxView.
     * Thread-safe — lze volat z libovolného vlákna (CameraX, coroutine).
     *
     * @param detections Seznam detekcí k vykreslení
     */
    fun drawDetections(detections: List<BoundingBoxView.Detection>) {
        if (!isShowing) {
            Log.w(TAG, "drawDetections voláno ale overlay není viditelný — ignorováno")
            return
        }
        boundingBoxView?.updateDetections(detections)
        // Vynutíme přepočet touchable region (insets listener)
        overlayView?.requestLayout()
    }

    /**
     * Vyčistí všechny vykreslené detekce.
     */
    fun clearDetections() {
        boundingBoxView?.clearDetections()
    }

    /**
     * Getter pro stav viditelnosti overlay.
     */
    fun isVisible(): Boolean = isShowing

    /**
     * Aktivuje bezpečný, na Main Thread vázaný Collector.
     */
    private fun startListening() {
        overlayScope?.cancel() // Idempotentní reset
        overlayScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        
        overlayScope?.launch {
            PerceptionEventBus.events.collect { event ->
                if (isShowing) {
                    when (event) {
                        is VisionUpdateEvent -> {
                            boundingBoxView?.updateDetections(event.detections)
                        }
                        is SemanticMaskEvent -> semanticMaskView?.updateMask(event) // NOVÉ napojení EGL
                        is com.example.llama.agent.perception.PoseUpdateEvent -> poseSkeletonView?.updateSkeletons(event.skeletons) // NOVÉ napojení
                        is com.example.llama.agent.perception.UiActionEvent -> {
                            // Ostatní UI akce mohou být zpracovány zde
                        }
                        else -> { /* Ignorujeme ostatní typy eventů v této vrstvě */ }
                    }
                }
            }
        }
    }

    private fun stopListening() {
        overlayScope?.cancel()
        overlayScope = null
    }
}
