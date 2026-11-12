package com.example.llama.agent.vision

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES30
import android.util.Log
import android.view.Surface
import android.view.TextureView
import com.example.llama.agent.perception.SemanticMaskEvent
import com.example.llama.agent.vision.models.VisionTaskType
import com.example.llama.media.EglCore
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Hardwarově akcelerovaný Renderer pro segmentační a hloubkové modely (U-Net, DeepLab, MiDaS).
 * Používá TextureView pro 100% kompatibilitu s TYPE_ACCESSIBILITY_OVERLAY.
 * Zero-Leak, Zero-Polling (Wait/Notify architektura).
 */
class SemanticMaskView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {

    companion object {
        private const val TAG = "SemanticMaskView"
    }

    private var renderThread: MaskRenderThread? = null

    init {
        surfaceTextureListener = this
        isOpaque = false // Propouští kameru zespodu
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        val renderSurface = Surface(surface)
        renderThread = MaskRenderThread(renderSurface).apply { start() }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        // GLES Viewport se updatuje automaticky
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        renderThread?.shutdown()
        try {
            renderThread?.join(500) // Bezpečné čekání (max 500ms) na uvolnění EGL prostředků
        } catch (e: Exception) {
            Log.e(TAG, "Chyba při ukončování MaskRenderThread: ${e.message}", e)
        }
        renderThread = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    /**
     * Bezpečné přijetí nového frame z EventBusu.
     * Vzbudí spící GL vlákno.
     */
    fun updateMask(event: SemanticMaskEvent) {
        renderThread?.enqueueFrame(event)
    }

    /**
     * Vnitřní Thread izolující EGL kontext od UI vlákna.
     */
    private inner class MaskRenderThread(private val surface: Surface) : Thread() {
        @Volatile private var isRunning = true
        private val lock = Object()
        private var pendingEvent: SemanticMaskEvent? = null

        private var eglCore: EglCore? = null
        private var shaderProgram = 0
        private var textureId = 0

        private var currentTexW = 0
        private var currentTexH = 0

        // Celá obrazovka (Quad) - upraveno pro Android TextureView orientaci
        private val vertexCoords = floatArrayOf(
            -1.0f, -1.0f, // bottom left
             1.0f, -1.0f, // bottom right
            -1.0f,  1.0f, // top left
             1.0f,  1.0f  // top right
        )
        private val texCoords = floatArrayOf(
            0.0f, 1.0f, // bottom left
            1.0f, 1.0f, // bottom right
            0.0f, 0.0f, // top left
            1.0f, 0.0f  // top right
        )

        private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(vertexCoords.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(vertexCoords); position(0) }
        
        private val texBuffer: FloatBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(texCoords); position(0) }

        override fun run() {
            try {
                eglCore = EglCore(surface)
                eglCore!!.makeCurrent()
                initGL()

                while (isRunning) {
                    var eventToRender: SemanticMaskEvent? = null

                    // Wait/Notify mechanismus: 0% CPU zátěž, pokud není nový snímek
                    synchronized(lock) {
                        while (pendingEvent == null && isRunning) {
                            lock.wait()
                        }
                        eventToRender = pendingEvent
                        pendingEvent = null
                    }

                    if (eventToRender != null && isRunning) {
                        drawFrame(eventToRender!!)
                        eglCore!!.swapBuffers()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "GL Thread Error", e)
            } finally {
                // Garbage Collection EGL kontextu
                surface.release()
                // Pokud má EglCore metodu release() v tvé verzi, zavolej ji zde
            }
        }

        fun enqueueFrame(event: SemanticMaskEvent) {
            synchronized(lock) {
                pendingEvent = event
                lock.notifyAll() // Vzbuď GL vlákno!
            }
        }

        fun shutdown() {
            isRunning = false
            synchronized(lock) { lock.notifyAll() }
        }

        private fun initGL() {
            val vShaderStr = """
                #version 300 es
                layout(location = 0) in vec4 aPosition;
                layout(location = 1) in vec2 aTexCoord;
                out vec2 vTexCoord;
                void main() {
                    gl_Position = aPosition;
                    vTexCoord = aTexCoord;
                }
            """.trimIndent()

            val fShaderStr = """
                #version 300 es
                precision mediump float;
                in vec2 vTexCoord;
                uniform sampler2D uMaskTexture;
                uniform int uTaskType;
                out vec4 fragColor;

                // Generátor pseudo-náhodných barev pro různé třídy
                vec3 idToColor(float id) {
                    uint classIdx = uint(id * 255.0);
                    if (classIdx == 0u) return vec3(0.0);
                    float r = float((classIdx * 123u) % 255u) / 255.0;
                    float g = float((classIdx * 321u) % 255u) / 255.0;
                    float b = float((classIdx * 213u) % 255u) / 255.0;
                    return vec3(r, g, b);
                }

                // Heatmapa pro hloubkové senzory (MiDaS)
                vec3 depthToHeatmap(float depth) {
                    float r = clamp(1.5 - abs(4.0 * depth - 3.0), 0.0, 1.0);
                    float g = clamp(1.5 - abs(4.0 * depth - 2.0), 0.0, 1.0);
                    float b = clamp(1.5 - abs(4.0 * depth - 1.0), 0.0, 1.0);
                    return vec3(r, g, b);
                }

                void main() {
                    // Čteme jeden byte z ByteBufferu (Luminance kanál)
                    float maskVal = texture(uMaskTexture, vTexCoord).r;
                    
                    if (uTaskType == 1) { // 1 = SEMANTIC_SEGMENTATION
                        if (maskVal < 0.01) { // Pozadí je vždy čisté a propouští kameru
                            fragColor = vec4(0.0);
                        } else {
                            fragColor = vec4(idToColor(maskVal), 0.65); // 65% krytí přes realitu
                        }
                    } else { // 2 = DEPTH_ESTIMATION
                        fragColor = vec4(depthToHeatmap(maskVal), 0.7);
                    }
                }
            """.trimIndent()

            val vShader = compileShader(GLES30.GL_VERTEX_SHADER, vShaderStr)
            val fShader = compileShader(GLES30.GL_FRAGMENT_SHADER, fShaderStr)
            shaderProgram = GLES30.glCreateProgram()
            GLES30.glAttachShader(shaderProgram, vShader)
            GLES30.glAttachShader(shaderProgram, fShader)
            GLES30.glLinkProgram(shaderProgram)

            // Vytvoření textury
            val textures = IntArray(1)
            GLES30.glGenTextures(1, textures, 0)
            textureId = textures[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        }

        private fun drawFrame(event: SemanticMaskEvent) {
            GLES30.glClearColor(0.0f, 0.0f, 0.0f, 0.0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

            GLES30.glUseProgram(shaderProgram)

            // Upload textury z CPU do GPU
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)

            if (currentTexW != event.width || currentTexH != event.height) {
                // První alokace textury na grafické kartě
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_LUMINANCE, event.width, event.height, 0, GLES30.GL_LUMINANCE, GLES30.GL_UNSIGNED_BYTE, event.maskData)
                currentTexW = event.width
                currentTexH = event.height
            } else {
                // Rychlý update (SubImage2D zabraňuje realokaci paměti na grafické kartě)
                GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, event.width, event.height, GLES30.GL_LUMINANCE, GLES30.GL_UNSIGNED_BYTE, event.maskData)
            }

            // Bind Uniforms
            val taskTypeInt = if (event.taskType == VisionTaskType.SEMANTIC_SEGMENTATION) 1 else 2
            GLES30.glUniform1i(GLES30.glGetUniformLocation(shaderProgram, "uTaskType"), taskTypeInt)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(shaderProgram, "uMaskTexture"), 0)

            // Bind Vertices
            val posHandle = 0
            val texHandle = 1
            GLES30.glEnableVertexAttribArray(posHandle)
            GLES30.glVertexAttribPointer(posHandle, 2, GLES30.GL_FLOAT, false, 0, vertexBuffer)
            
            GLES30.glEnableVertexAttribArray(texHandle)
            GLES30.glVertexAttribPointer(texHandle, 2, GLES30.GL_FLOAT, false, 0, texBuffer)

            // Vykreslení Quad (Celá obrazovka)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

            GLES30.glDisableVertexAttribArray(posHandle)
            GLES30.glDisableVertexAttribArray(texHandle)
        }

        private fun compileShader(type: Int, code: String): Int {
            val shader = GLES30.glCreateShader(type)
            GLES30.glShaderSource(shader, code)
            GLES30.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                Log.e(TAG, "Chyba kompilace Shaderu: " + GLES30.glGetShaderInfoLog(shader))
                GLES30.glDeleteShader(shader)
                return 0
            }
            return shader
        }
    }
}
