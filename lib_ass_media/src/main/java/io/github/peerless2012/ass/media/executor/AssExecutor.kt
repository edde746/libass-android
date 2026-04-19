package io.github.peerless2012.ass.media.executor

import io.github.peerless2012.ass.AssFrame
import io.github.peerless2012.ass.AssRender
import io.github.peerless2012.ass.AssTexType
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Executor to render.
 */
class AssExecutor(private val render: AssRender) {

    private val assFrameNotChange = AssFrame(null, 0)

    private val executor = Executors.newSingleThreadExecutor()

    private val executorService = ExecutorCompletionService<AssFrame?>(executor)

    private var lastFrame: AssFrame? = null

    private var executorBusy = false

    private val task = AssTask(render)

    public fun renderFrame(presentationTimeUs: Long, type: AssTexType): AssFrame? {
        if (executorBusy) {
            // render thread is busy, keep last content
            return assFrameNotChange
        }
        val future = executorService.submit {
            executorBusy = true
            lastFrame = render.renderFrame(presentationTimeUs / 1000, type)
            executorBusy = false
            lastFrame
        }
        // 15 ms is just under one 60 Hz frame period — if libass runs long we fall back
        // to the previous frame and let the next tick catch up rather than stalling GL.
        val assFrame: AssFrame? = try {
            future.get(15, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            lastFrame ?: assFrameNotChange
        }
        lastFrame = null
        return assFrame
    }

    public fun asyncRenderFrame(presentationTimeUs: Long, type: AssTexType, callback: (AssFrame?) -> Unit) {
        if (task.executorBusy) {
            // render thread is busy, keep last content
            callback.invoke(assFrameNotChange)
        } else {
            task.presentationTimeUs = presentationTimeUs
            task.callback = callback
            task.type = type
            // execute render task
            executor.execute(task)
        }
    }

    public fun shutdown() {
        executor.shutdown()
    }

}