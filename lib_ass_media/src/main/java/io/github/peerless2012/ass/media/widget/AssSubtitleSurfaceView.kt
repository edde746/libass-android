package io.github.peerless2012.ass.media.widget

import android.content.Context
import android.graphics.PixelFormat
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.media3.common.util.UnstableApi
import io.github.peerless2012.ass.media.AssHandler

/**
 * Subtitle overlay rendered through a dedicated [SurfaceView] layer.
 *
 * Uses a SurfaceFlinger layer directly, which lets the atlas-based pipeline
 * vsync-align its swap with the corresponding video frame via
 * `eglPresentationTimeANDROID`. Use [AssSubtitleTextureView] instead when the
 * host app does its own compositing (e.g. Flutter) and needs the subtitle
 * overlay to obey normal View z-ordering.
 */
@UnstableApi
class AssSubtitleSurfaceView : SurfaceView, AssSubtitleRender, SurfaceHolder.Callback {

    private val assHandler: AssHandler
    private var pipeline: AssAtlasPipeline? = null

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
        setZOrderMediaOverlay(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        holder.addCallback(this)
    }

    override fun requestRender(presentationTimeUs: Long, releaseTimeNs: Long) {
        pipeline?.requestRender(presentationTimeUs, releaseTimeNs)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val rect = holder.surfaceFrame
        pipeline = AssAtlasPipeline(holder.surface, rect.width(), rect.height(), assHandler)
            .also { it.start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        pipeline?.onSurfaceSizeChanged(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        pipeline?.releaseAndWait()
        pipeline = null
    }
}
