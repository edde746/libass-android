package io.github.peerless2012.ass

/**
 * Result of a packed-atlas render. The atlas pixel data is stored in the direct ByteBuffer
 * that was passed into [AssRender.renderFrameAtlas]; the vertex stream is in the other.
 *
 * @param atlasWidth  packed atlas width in pixels (0 when [changed] == 0)
 * @param atlasHeight packed atlas height in pixels
 * @param quadCount   number of quads; the vertex buffer holds [quadCount] * 6 vertices
 * @param changed     libass change flag (0 = no change, 1 = positions, 2 = content)
 */
class AssAtlasFrame(
    val atlasWidth: Int,
    val atlasHeight: Int,
    val quadCount: Int,
    val changed: Int,
)
