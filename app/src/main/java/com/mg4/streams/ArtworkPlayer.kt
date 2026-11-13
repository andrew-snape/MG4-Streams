package com.mg4.streams

import android.net.Uri
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The player as the media session sees it, with the song's cover art added to the metadata.
 * Changing the art never touches the playlist, so the stream doesn't reload and the stream title
 * (ICY) is kept. Call [setArtwork] on the main thread.
 */
@UnstableApi
class ArtworkPlayer(player: Player) : ForwardingPlayer(player) {

    private val listeners = CopyOnWriteArraySet<Player.Listener>()
    private var artwork: Uri? = null

    override fun addListener(listener: Player.Listener) {
        super.addListener(listener)
        listeners += listener
    }

    override fun removeListener(listener: Player.Listener) {
        super.removeListener(listener)
        listeners -= listener
    }

    override fun getMediaMetadata(): MediaMetadata {
        val metadata = super.getMediaMetadata()
        val art = artwork ?: return metadata
        return metadata.buildUpon().setArtworkUri(art).build()
    }

    /** Cover art for the song playing, or null for the station logo (the item's artwork). */
    fun setArtwork(uri: Uri?) {
        if (uri == artwork) return
        artwork = uri
        val metadata = mediaMetadata
        val events = Player.Events(FlagSet.Builder().add(Player.EVENT_MEDIA_METADATA_CHANGED).build())
        listeners.forEach {
            it.onMediaMetadataChanged(metadata)
            it.onEvents(this, events)
        }
    }
}
