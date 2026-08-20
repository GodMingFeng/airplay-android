package com.airplay.android;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

public class AudioPlayer {
    private static final String TAG = "AudioPlayer";
    private static final int START_THRESHOLD_FRAMES = 8; // Reference: AUDIO_QUEUE_START_THRESHOLD
    private AudioTrack audioTrack;
    private int framesBuffered = 0;
    private boolean playbackStarted = false;

    public AudioPlayer() {
        try {
            int sampleRate = 44100;
            int channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
            int audioFormat = AudioFormat.ENCODING_PCM_16BIT;

            int minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat);
            // Larger buffer for smoother playback (reference uses ~400ms worth)
            int bufferSize = Math.max(minBufferSize * 4, 480 * 2 * 2 * 20); // 20 frames

            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();

            AudioFormat format = new AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .setEncoding(audioFormat)
                    .build();

            audioTrack = new AudioTrack(attrs, format, bufferSize, AudioTrack.MODE_STREAM, 0);
            // Don't call play() yet - wait for start threshold
            Log.i(TAG, "AudioTrack initialized, bufferSize=" + bufferSize +
                    ", startThreshold=" + START_THRESHOLD_FRAMES + " frames");
        } catch (Exception e) {
            Log.e(TAG, "Failed to init AudioTrack", e);
        }
    }

    public void play(byte[] audio) {
        if (audioTrack == null) return;
        try {
            if (!playbackStarted) {
                framesBuffered++;
                if (framesBuffered >= START_THRESHOLD_FRAMES) {
                    audioTrack.play();
                    playbackStarted = true;
                    Log.i(TAG, "Playback started after " + framesBuffered + " frames buffered");
                }
            }
            // Non-blocking write to prevent stalling the playback thread
            if (playbackStarted) {
                audioTrack.write(audio, 0, audio.length, AudioTrack.WRITE_NON_BLOCKING);
            } else {
                // Pre-fill buffer before starting
                audioTrack.write(audio, 0, audio.length);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error writing audio", e);
        }
    }

    public void release() {
        if (audioTrack != null) {
            try {
                audioTrack.stop();
                audioTrack.release();
            } catch (Exception e) { /* ignore */ }
            audioTrack = null;
        }
    }
}
