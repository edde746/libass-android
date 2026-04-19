package io.github.peerless2012.ass.media.widget

import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import io.github.peerless2012.ass.AssAtlasFrame
import io.github.peerless2012.ass.media.AssHandler
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

/**
 * Shared atlas-rendering pipeline used by [AssSubtitleSurfaceView] and
 * [AssSubtitleTextureView]. Runs libass on its own [HandlerThread] into a packed
 * ALPHA_8 texture atlas plus a single vertex stream, and a GL thread that uploads
 * both and issues one `glDrawArrays` per frame. When the backing Surface is a real
 * SurfaceFlinger layer (SurfaceView) the swap is pinned to the video's target
 * release time via [EGLExt.eglPresentationTimeANDROID] so SurfaceFlinger composes
 * the subtitle buffer on the same vsync as the corresponding video frame; for a
 * TextureView-backed SurfaceTexture the call is a no-op in scheduling terms but
 * still harmless.
 */
@UnstableApi
internal object AssAtlasPipelineConfig {
    /** Flip to `true` for `adb logcat -s AssSurfaceGlThread:D AssLibassThread:D` traces. */
    internal const val TIMING_LOGS = false

    /** Atlas row width. 2048 is guaranteed by GL ES 2.0. */
    internal const val ATLAS_MAX_W = 2048

    /** Atlas column height. 2048 × 4096 = 8 MB per atlas buffer. */
    internal const val ATLAS_MAX_H = 4096

    /** Preallocated vertex-stream capacity (192 bytes × 16384 = 3 MB per buffer). */
    internal const val MAX_QUADS = 16384

    /** Must match the byte layout produced by `nativeAssRenderFrameAtlas` in AssKt.c. */
    internal const val BYTES_PER_VERTEX = 32
    internal const val BYTES_PER_QUAD = BYTES_PER_VERTEX * 6
}

/** Payload handed from the libass worker to the GL thread. */
internal class AtlasPayload(
    val atlasBuf: ByteBuffer,
    val vertexBuf: ByteBuffer,
    var frame: AssAtlasFrame,
    var presentationTimeUs: Long,
    var releaseTimeNs: Long
)

/**
 * Owns both the libass worker and the GL thread. The two talk via a single-slot
 * atomic — a newer payload always replaces a pending one so the GL thread never
 * falls behind.
 */
@UnstableApi
internal class AssAtlasPipeline(
    surface: Surface,
    width: Int,
    height: Int,
    assHandler: AssHandler
) {
    // Two payloads, each with its own buffers, alternated by the libass worker so the
    // GL thread can keep reading the previously posted payload's buffers while the
    // worker writes into the other.
    private val payloads = Array(2) {
        AtlasPayload(
            atlasBuf = ByteBuffer.allocateDirect(
                AssAtlasPipelineConfig.ATLAS_MAX_W * AssAtlasPipelineConfig.ATLAS_MAX_H
            ).order(ByteOrder.nativeOrder()),
            vertexBuf = ByteBuffer.allocateDirect(
                AssAtlasPipelineConfig.MAX_QUADS * AssAtlasPipelineConfig.BYTES_PER_QUAD
            ).order(ByteOrder.nativeOrder()),
            frame = AssAtlasFrame(0, 0, 0, 0),
            presentationTimeUs = 0L,
            releaseTimeNs = C.TIME_UNSET
        )
    }

    private val pendingPayload = AtomicReference<AtlasPayload?>(null)
    private val glThread = AtlasGlThread(surface, width, height, assHandler) { pendingPayload.getAndSet(null) }
    private val libassThread = AtlasLibassThread(
        assHandler,
        slots = payloads,
        onFrameReady = { payload ->
            pendingPayload.set(payload)
            glThread.triggerDraw()
        }
    )

    fun start() {
        glThread.start()
        libassThread.start()
    }

    fun requestRender(presentationTimeUs: Long, releaseTimeNs: Long) {
        libassThread.enqueue(presentationTimeUs, releaseTimeNs)
    }

    fun onSurfaceSizeChanged(width: Int, height: Int) {
        glThread.onSurfaceSizeChanged(width, height)
    }

    fun releaseAndWait() {
        libassThread.releaseAndWait()
        glThread.releaseAndWait()
    }
}

/**
 * Stops a [Handler] synchronously: posts [releaseWhat] with an [Ack], waits up to 1 s
 * for the handler to invoke [onReleased] (on its own looper) and signal the latch.
 */
private fun postShutdownAndWait(
    handler: Handler,
    releaseWhat: Int,
    onReleased: () -> Unit
) {
    val latch = Object()
    synchronized(latch) {
        handler.obtainMessage(releaseWhat, Ack(latch, onReleased)).sendToTarget()
        try {
            latch.wait(1_000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}

/** Transport for [postShutdownAndWait] — the handler callback calls [release] then notifies. */
private class Ack(val latch: Any, val release: () -> Unit)

/**
 * Runs libass off the GL thread into a packed atlas + vertex stream. Latest-wins:
 * older pending renders are dropped when a newer one arrives. Alternates between
 * the two [slots] only on successful content-changing renders, so a slot the GL
 * thread is still reading can never be overwritten by the next libass render.
 */
@UnstableApi
private class AtlasLibassThread(
    private val assHandler: AssHandler,
    private val slots: Array<AtlasPayload>,
    private val onFrameReady: (AtlasPayload) -> Unit
) : HandlerThread(TAG) {

    private lateinit var handler: Handler
    @Volatile private var pendingPtsUs = UNSET
    @Volatile private var pendingReleaseNs = C.TIME_UNSET
    private var writeSlot = 0
    private var lastNonEmpty: AtlasPayload? = null

    override fun start() {
        super.start()
        handler = Handler(looper) { msg ->
            when (msg.what) {
                MSG_RENDER -> drainAndRender()
                MSG_RELEASE -> {
                    val ack = msg.obj as Ack
                    ack.release()
                    quit()
                    synchronized(ack.latch) { (ack.latch as Object).notifyAll() }
                }
            }
            true
        }
    }

    fun enqueue(presentationTimeUs: Long, releaseTimeNs: Long) {
        if (!::handler.isInitialized) return
        pendingPtsUs = presentationTimeUs
        pendingReleaseNs = releaseTimeNs
        handler.removeMessages(MSG_RENDER)
        handler.sendEmptyMessage(MSG_RENDER)
    }

    private fun drainAndRender() {
        val pts = pendingPtsUs
        val releaseNs = pendingReleaseNs
        if (pts == UNSET) return
        pendingPtsUs = UNSET
        val t0 = System.nanoTime()
        val render = assHandler.render ?: return
        val slot = slots[writeSlot]
        val frame = render.renderFrameAtlas(
            pts / 1000,
            slot.atlasBuf,
            AssAtlasPipelineConfig.ATLAS_MAX_W,
            slot.vertexBuf
        )
        if (frame == null) {
            if (AssAtlasPipelineConfig.TIMING_LOGS) {
                Log.w(TAG, "renderFrameAtlas returned null for pts=${pts / 1000}ms — buffer overflow")
            }
            return
        }
        val t1 = System.nanoTime()

        // On changed == 0 libass didn't touch slot.atlasBuf / slot.vertexBuf, so we
        // hand the GL thread the last non-empty payload again — its identity matches
        // GL's last upload and it skips re-uploading. On changed != 0, consume this
        // slot and alternate so the GL thread keeps reading the previously posted
        // slot's buffers while libass writes the next frame into the other.
        val payloadToPost = if (frame.changed == 0) {
            lastNonEmpty ?: return
        } else {
            slot.frame = frame
            lastNonEmpty = slot
            writeSlot = 1 - writeSlot
            slot
        }
        payloadToPost.presentationTimeUs = pts
        payloadToPost.releaseTimeNs = releaseNs

        onFrameReady(payloadToPost)
        if (AssAtlasPipelineConfig.TIMING_LOGS) {
            Log.d(
                TAG,
                "pts=${pts / 1000}ms libassMs=${(t1 - t0) / 1_000_000} " +
                        "changed=${frame.changed} quads=${frame.quadCount} " +
                        "atlas=${frame.atlasWidth}x${frame.atlasHeight}"
            )
        }
    }

    fun releaseAndWait() {
        if (!::handler.isInitialized) {
            quit()
            return
        }
        postShutdownAndWait(handler, MSG_RELEASE) { /* nothing thread-local to tear down */ }
    }

    companion object {
        private const val TAG = "AssLibassThread"
        private const val MSG_RENDER = 1
        private const val MSG_RELEASE = 2
        private const val UNSET = Long.MIN_VALUE
    }
}

/**
 * Owns the EGL surface, uploads the atlas + vertex stream and issues a single
 * `glDrawArrays` per frame. When the Surface is a SurfaceView-backed
 * SurfaceFlinger layer the swap is pinned to the video's target release time via
 * [EGLExt.eglPresentationTimeANDROID]; for a SurfaceTexture (TextureView) backing
 * it is a no-op in scheduling terms.
 */
@UnstableApi
private class AtlasGlThread(
    private val surface: Surface,
    @Volatile private var width: Int,
    @Volatile private var height: Int,
    private val assHandler: AssHandler,
    private val takePending: () -> AtlasPayload?
) : HandlerThread(TAG) {

    private lateinit var handler: Handler
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private val renderer = AtlasRenderer(assHandler)
    private var lastUploadedPayload: AtlasPayload? = null

    override fun start() {
        super.start()
        handler = Handler(looper) { msg ->
            try {
                when (msg.what) {
                    MSG_INIT -> initEgl()
                    MSG_DRAW -> drawAndSwap()
                    MSG_SIZE_CHANGED -> sizeChanged(width, height)
                    MSG_RELEASE -> {
                        val ack = msg.obj as Ack
                        ack.release()
                        quit()
                        synchronized(ack.latch) { (ack.latch as Object).notifyAll() }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "GL thread error", e)
                releaseEgl()
            }
            true
        }
        handler.sendEmptyMessage(MSG_INIT)
    }

    fun onSurfaceSizeChanged(width: Int, height: Int) {
        this.width = width
        this.height = height
        handler.sendEmptyMessage(MSG_SIZE_CHANGED)
    }

    fun triggerDraw() {
        if (!::handler.isInitialized) return
        handler.removeMessages(MSG_DRAW)
        handler.sendEmptyMessage(MSG_DRAW)
    }

    fun releaseAndWait() {
        if (!::handler.isInitialized) {
            quit()
            return
        }
        postShutdownAndWait(handler, MSG_RELEASE) { releaseEgl() }
    }

    private fun initEgl() {
        try {
            eglDisplay = GlUtil.getDefaultEglDisplay()
            eglContext = GlUtil.createEglContext(eglDisplay)
            eglSurface = GlUtil.createEglSurface(eglDisplay, surface, C.COLOR_TRANSFER_SDR, false)
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            renderer.onSurfaceCreated()
            sizeChanged(width, height)
        } catch (e: GlUtil.GlException) {
            Log.e(TAG, "Failed to initialize EGL", e)
        }
    }

    private fun sizeChanged(width: Int, height: Int) {
        renderer.onSurfaceChanged(width, height)
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            GlUtil.clearFocusedBuffers()
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        }
    }

    private fun drawAndSwap() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
        val payload = takePending() ?: return

        // Render immediately (GL commands queue on the GPU).
        val t0 = System.nanoTime()
        val reuse = payload === lastUploadedPayload
        renderer.onDrawFrame(payload, reuseUploads = reuse)
        lastUploadedPayload = payload
        val t1 = System.nanoTime()

        // Hold the swap until close to the video's target release time. For
        // TextureView-backed pipelines (Flutter, Compose overlays, etc.) the host
        // compositor picks up the new buffer on its very next UI vsync after the
        // swap, so if we swapped immediately — often 15-25 ms before the video's
        // target vsync — the subtitle would appear one frame early. The swap
        // happens ~8 ms before the target vsync, comfortably inside the
        // (T-1)→T compositor window but leaving a small safety margin so we
        // never slip past T. SurfaceView + eglPresentationTimeANDROID still
        // honors the hint independently; the held swap is a no-op penalty for
        // that path.
        if (payload.releaseTimeNs != C.TIME_UNSET) {
            val targetSwapNs = payload.releaseTimeNs - SWAP_LEAD_NS
            val sleepNs = targetSwapNs - System.nanoTime()
            if (sleepNs > 0) {
                try {
                    Thread.sleep(sleepNs / 1_000_000L, (sleepNs % 1_000_000L).toInt())
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }

        if (payload.releaseTimeNs != C.TIME_UNSET) {
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, payload.releaseTimeNs)
        }
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        val t2 = System.nanoTime()
        if (AssAtlasPipelineConfig.TIMING_LOGS) {
            val leadMs = if (payload.releaseTimeNs == C.TIME_UNSET) -1L
                         else (payload.releaseTimeNs - t2) / 1_000_000
            Log.d(
                TAG,
                "pts=${payload.presentationTimeUs / 1000}ms quads=${payload.frame.quadCount} " +
                        "reused=$reuse drawMs=${(t1 - t0) / 1_000_000} " +
                        "swapMs=${(t2 - t1) / 1_000_000} leadMs=$leadMs"
            )
        }
    }

    private fun releaseEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            try {
                renderer.onSurfaceDestroyed()
                GlUtil.destroyEglSurface(eglDisplay, eglSurface)
                GlUtil.destroyEglContext(eglDisplay, eglContext)
            } catch (e: GlUtil.GlException) {
                Log.e(TAG, "Failed to release EGL", e)
            } finally {
                eglDisplay = EGL14.EGL_NO_DISPLAY
                eglContext = EGL14.EGL_NO_CONTEXT
                eglSurface = EGL14.EGL_NO_SURFACE
            }
        }
    }

    companion object {
        private const val TAG = "AssSurfaceGlThread"
        private const val MSG_INIT = 1
        private const val MSG_DRAW = 2
        private const val MSG_SIZE_CHANGED = 3
        private const val MSG_RELEASE = 4
        /** How long before the video's target vsync we aim to finish the swap. */
        private const val SWAP_LEAD_NS = 8_000_000L
    }
}

/**
 * GL-side work for the atlas-based path. Maintains a single atlas texture and a
 * single vertex buffer; uploads them per frame (unless the payload identity
 * matches the last upload) and issues one `glDrawArrays` for the whole frame.
 */
@UnstableApi
private class AtlasRenderer(private val assHandler: AssHandler) {

    private val vertexShaderCode = """
        attribute vec2 a_Position;
        attribute vec2 a_TexCoord;
        attribute vec4 a_Color;
        uniform vec2 u_SurfaceSize;
        varying vec2 v_TexCoord;
        varying vec4 v_Color;
        void main() {
            vec2 clip = (a_Position / u_SurfaceSize) * 2.0 - 1.0;
            clip.y = -clip.y;
            gl_Position = vec4(clip, 0.0, 1.0);
            v_TexCoord = a_TexCoord;
            v_Color = a_Color;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        precision mediump float;
        varying vec2 v_TexCoord;
        varying vec4 v_Color;
        uniform sampler2D u_Texture;
        void main() {
            float alpha = texture2D(u_Texture, v_TexCoord).a;
            gl_FragColor = v_Color * alpha;
        }
    """.trimIndent()

    private var surfaceSize = Size.ZERO
    private lateinit var glProgram: GlProgram

    private var atlasTexId = 0
    private var vertexBufferId = 0

    private var aPosition = 0
    private var aTexCoord = 0
    private var aColor = 0
    private var uTexture = 0
    private var uSurfaceSize = 0

    private var atlasAllocatedW = 0
    private var atlasAllocatedH = 0

    fun onSurfaceCreated() {
        glProgram = GlProgram(vertexShaderCode, fragmentShaderCode)
        GlUtil.checkGlError()
        glProgram.use()

        aPosition = glProgram.getAttributeArrayLocationAndEnable("a_Position")
        aTexCoord = glProgram.getAttributeArrayLocationAndEnable("a_TexCoord")
        aColor = glProgram.getAttributeArrayLocationAndEnable("a_Color")
        uTexture = glProgram.getUniformLocation("u_Texture")
        uSurfaceSize = glProgram.getUniformLocation("u_SurfaceSize")

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        atlasTexId = tex[0]
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, atlasTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glUniform1i(uTexture, 0)

        val buf = IntArray(1)
        GLES20.glGenBuffers(1, buf, 0)
        vertexBufferId = buf[0]

        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        surfaceSize = Size(width, height)
        assHandler.render?.setFrameSize(width, height)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUniform2f(uSurfaceSize, width.toFloat(), height.toFloat())
    }

    fun onDrawFrame(payload: AtlasPayload, reuseUploads: Boolean) {
        GlUtil.clearFocusedBuffers()

        val frame = payload.frame
        val quadCount = frame.quadCount
        if (quadCount == 0) return

        if (!reuseUploads) {
            uploadAtlas(payload.atlasBuf, frame.atlasWidth, frame.atlasHeight)
            uploadVertices(payload.vertexBuf, quadCount)
        }

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexBufferId)
        val stride = AssAtlasPipelineConfig.BYTES_PER_VERTEX
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, stride, 0)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, stride, 8)
        GLES20.glVertexAttribPointer(aColor, 4, GLES20.GL_FLOAT, false, stride, 16)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, quadCount * 6)
    }

    private fun uploadAtlas(atlasBuf: ByteBuffer, atlasW: Int, atlasH: Int) {
        atlasBuf.position(0).limit(atlasW * atlasH)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, atlasTexId)
        if (atlasW == atlasAllocatedW && atlasH == atlasAllocatedH) {
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D, 0, 0, 0, atlasW, atlasH,
                GLES20.GL_ALPHA, GLES20.GL_UNSIGNED_BYTE, atlasBuf
            )
        } else {
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_ALPHA,
                atlasW, atlasH, 0,
                GLES20.GL_ALPHA, GLES20.GL_UNSIGNED_BYTE, atlasBuf
            )
            atlasAllocatedW = atlasW
            atlasAllocatedH = atlasH
        }
    }

    private fun uploadVertices(vertexBuf: ByteBuffer, quadCount: Int) {
        val size = quadCount * AssAtlasPipelineConfig.BYTES_PER_QUAD
        vertexBuf.position(0).limit(size)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexBufferId)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, size, vertexBuf, GLES20.GL_STREAM_DRAW)
    }

    fun onSurfaceDestroyed() {
        if (atlasTexId != 0) {
            val tex = intArrayOf(atlasTexId)
            GLES20.glDeleteTextures(1, tex, 0)
            atlasTexId = 0
        }
        if (vertexBufferId != 0) {
            val buf = intArrayOf(vertexBufferId)
            GLES20.glDeleteBuffers(1, buf, 0)
            vertexBufferId = 0
        }
        if (::glProgram.isInitialized) glProgram.delete()
    }
}
