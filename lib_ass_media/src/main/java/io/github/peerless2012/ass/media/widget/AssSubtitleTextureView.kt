package io.github.peerless2012.ass.media.widget

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import androidx.media3.common.util.UnstableApi
import io.github.peerless2012.ass.media.AssHandler

/**
 * Subtitle overlay rendered through a [TextureView].
 *
 * The view composites as a regular Android view — it obeys the view hierarchy's
 * z-order, which is typically what hosts need when the app does its own
 * compositing on top of the video (Flutter, Jetpack Compose overlays, devices
 * where SurfaceView overlay promotion is unreliable). For players that render
 * video through a dedicated SurfaceView, [AssSubtitleSurfaceView] offers tighter
 * vsync-accurate alignment.
 */
@UnstableApi
class AssSubtitleTextureView : TextureView, AssSubtitleRender, TextureView.SurfaceTextureListener {

    private val assHandler: AssHandler
    private var pipeline: AssAtlasPipeline? = null
    private var surface: Surface? = null

    constructor(context: Context, assHandler: AssHandler) : this(context, null, assHandler)

    constructor(context: Context, attrs: AttributeSet?, assHandler: AssHandler) : this(
        context, attrs, 0, assHandler
    )

    constructor(
        context: Context,
        attrs: AttributeSet?,
        defStyleAttr: Int,
        assHandler: AssHandler
    ) : super(context, attrs, defStyleAttr) {
        this.assHandler = assHandler
        isOpaque = false
        surfaceTextureListener = this
    }

    override fun requestRender(presentationTimeUs: Long, releaseTimeNs: Long) {
        pipeline?.requestRender(presentationTimeUs, releaseTimeNs)
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        val s = Surface(surfaceTexture)
        surface = s
        pipeline = AssAtlasPipeline(s, width, height, assHandler).also { it.start() }
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        pipeline?.onSurfaceSizeChanged(width, height)
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        pipeline?.releaseAndWait()
        pipeline = null
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
    }
}
