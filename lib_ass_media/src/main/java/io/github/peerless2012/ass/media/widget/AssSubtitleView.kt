package io.github.peerless2012.ass.media.widget
import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.type.AssRenderType

class AssSubtitleView: FrameLayout {

    private val assHandler: AssHandler

    private var assSubtitleRender: AssSubtitleRender? = null

    constructor(context: Context, assHandler: AssHandler) : this(context, null, assHandler)

    constructor(context: Context, attrs: AttributeSet?, assHandler: AssHandler) : this(
        context,
        attrs,
        0,
        assHandler
    )

    @OptIn(UnstableApi::class)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, assHandler: AssHandler) :
            super(context, attrs, defStyleAttr) {
        this.assHandler = assHandler
        val view = when (assHandler.renderType) {
            AssRenderType.OVERLAY_CANVAS -> {
                AssSubtitleCanvasView(context, attrs, defStyleAttr, assHandler)
            }
            AssRenderType.OVERLAY_OPEN_GL -> {
                // Default to SurfaceView so the subtitle overlay is a real
                // SurfaceFlinger layer and `eglPresentationTimeANDROID` can pin the
                // swap to the video's target vsync. Hosts where this SurfaceView's
                // z-order clashes with their own compositor can instantiate
                // [AssSubtitleTextureView] directly instead.
                AssSubtitleSurfaceView(context, attrs, defStyleAttr, assHandler)
            }
            else -> {
                null
            }
        }
        view?.let {
            assSubtitleRender = it
            val params = LayoutParams(MarginLayoutParams.MATCH_PARENT,
                MarginLayoutParams.MATCH_PARENT)
            addView(it, params)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        assHandler.videoFrameCallback = { presentationTimeUs, releaseTimeNs ->
            assSubtitleRender?.requestRender(presentationTimeUs, releaseTimeNs)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        assHandler.videoFrameCallback = null
    }

}
