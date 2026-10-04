package org.openbased.playback;

public enum PlaybackMode {
    /** The original file is served as-is, with Range support. */
    DIRECT_PLAY,
    /** Streams are copied into a container the client supports. */
    REMUX,
    /** Streams are re-encoded into codecs the client supports. */
    TRANSCODE
}
