package io.github.peerless2012.ass.media.widget

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
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
 * Subtitle overlay rendered through a dedicated [SurfaceView] layer.
 *
 * Pipeline: `VideoFrameMetadataListener` → libass worker → GL thread. The worker packs
 * every libass bitmap into a single ALPHA_8 atlas plus a vertex stream in one native
 * call; the GL thread uploads both and issues one `glDrawArrays` per frame, pinning the
 * swap to the video's target release time via [EGLExt.eglPresentationTimeANDROID] so
 * SurfaceFlinger composes the subtitle and the video on the same vsync.
 */
@UnstableApi
class AssSubtitleSurfaceView : SurfaceView, AssSubtitleRender, SurfaceHolder.Callback {

    private val assHandler: AssHandler

    constructor(context: Context, assHandler: AssHandler) : this(context, null, assHandler)

    constructor(context: Context, attrs: AttributeSet?, assHandler: AssHandler) : this(
        context,
        attrs,
        0,
        assHandler
    )

    constructor(
        context: Context,
        attrs: AttributeSet?,
        defStyleAttr: Int,
        assHandler: AssHandler
    ) : super(context, attrs, defStyleAttr) {
        this.assHandler = assHandler
        setZOrderMediaOverlay(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        holder.addCallback(this)
    }

    private var pipeline: Pipeline? = null

    override fun requestRender(presentationTimeUs: Long, releaseTimeNs: Long) {
        pipeline?.requestRender(presentationTimeUs, releaseTimeNs)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val rect = holder.surfaceFrame
        pipeline = Pipeline(
            holder.surface,
            rect.width(),
            rect.height(),
            assHandler
        ).also { it.start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        pipeline?.onSurfaceSizeChanged(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        pipeline?.releaseAndWait()
        pipeline = null
    }

    companion object {
        /** Flip to `true` for `adb logcat -s AssSurfaceGlThread:D AssLibassThread:D` traces. */
        internal const val TIMING_LOGS = false

        /** Atlas row width. 2048 is guaranteed by GL ES 2.0; 4 MB per atlas at max height. */
        internal const val ATLAS_MAX_W = 2048

        /** Atlas column height. 2048 × 4096 = 8 MB per atlas buffer. */
        internal const val ATLAS_MAX_H = 4096

        /** Preallocated vertex-stream capacity. 16384 quads × 192 bytes = 3 MB per vertex buffer. */
        internal const val MAX_QUADS = 16384

        /** Must match the byte layout produced by `nativeAssRenderFrameAtlas` in AssKt.c. */
        internal const val BYTES_PER_VERTEX = 32
        internal const val BYTES_PER_QUAD = BYTES_PER_VERTEX * 6
    }
}

/** Payload handed from the libass worker to the GL thread. */
private class AtlasPayload(
    val atlasBuf: ByteBuffer,
    val vertexBuf: ByteBuffer,
    var frame: AssAtlasFrame,
    var presentationTimeUs: Long,
    var releaseTimeNs: Long
)

/**
 * Owns both the libass worker and the GL thread. The two talk via a single-slot atomic —
 * a newer payload always replaces a pending one so the GL thread never falls behind.
 */
private class Pipeline(
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
                AssSubtitleSurfaceView.ATLAS_MAX_W * AssSubtitleSurfaceView.ATLAS_MAX_H
            ).order(ByteOrder.nativeOrder()),
            vertexBuf = ByteBuffer.allocateDirect(
                AssSubtitleSurfaceView.MAX_QUADS * AssSubtitleSurfaceView.BYTES_PER_QUAD
            ).order(ByteOrder.nativeOrder()),
            frame = AssAtlasFrame(0, 0, 0, 0),
            presentationTimeUs = 0L,
            releaseTimeNs = C.TIME_UNSET
        )
    }

    private val pendingPayload = AtomicReference<AtlasPayload?>(null)
    private val glThread = GlThread(surface, width, height, assHandler) { pendingPayload.getAndSet(null) }
    private val libassThread = LibassThread(
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
 * Runs libass off the GL thread into a packed atlas + vertex stream. Latest-wins: older
 * pending renders are dropped when a newer one arrives. Alternates between the two
 * [slots] only on successful content-changing renders, so a slot the GL thread is still
 * reading can never be overwritten by the next libass render.
 */
private class LibassThread(
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
            AssSubtitleSurfaceView.ATLAS_MAX_W,
            slot.vertexBuf
        )
        if (frame == null) {
            if (AssSubtitleSurfaceView.TIMING_LOGS) {
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
        if (AssSubtitleSurfaceView.TIMING_LOGS) {
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
 * Owns the EGL surface for the [SurfaceView]. Uploads the atlas + vertex stream and
 * issues a single `glDrawArrays` per frame, pinning the swap to the video's target
 * release time via [EGLExt.eglPresentationTimeANDROID].
 */
private class GlThread(
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
        val t0 = System.nanoTime()
        val reuse = payload === lastUploadedPayload
        renderer.onDrawFrame(payload, reuseUploads = reuse)
        lastUploadedPayload = payload
        val t1 = System.nanoTime()
        if (payload.releaseTimeNs != C.TIME_UNSET) {
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, payload.releaseTimeNs)
        }
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        val t2 = System.nanoTime()
        if (AssSubtitleSurfaceView.TIMING_LOGS) {
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
    }
}

/**
 * GL-side work for the atlas-based path. Maintains a single atlas texture and a single
 * vertex buffer; uploads them per frame (unless the payload identity matches the last
 * upload) and issues one `glDrawArrays` for the whole frame.
 */
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

    // Resolved once in onSurfaceCreated.
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
        val stride = AssSubtitleSurfaceView.BYTES_PER_VERTEX
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
        val size = quadCount * AssSubtitleSurfaceView.BYTES_PER_QUAD
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
