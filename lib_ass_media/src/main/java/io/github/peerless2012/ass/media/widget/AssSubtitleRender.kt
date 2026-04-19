package io.github.peerless2012.ass.media.widget

interface AssSubtitleRender {

    /**
     * Request a subtitle render aligned to an upcoming video frame.
     *
     * @param presentationTimeUs Track-relative video frame PTS in microseconds.
     * @param releaseTimeNs `System.nanoTime()`-domain target display time for the video frame,
     *                      or `C.TIME_UNSET` when unavailable.
     */
    fun requestRender(presentationTimeUs: Long, releaseTimeNs: Long)

}
