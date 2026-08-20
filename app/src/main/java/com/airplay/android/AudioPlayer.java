package com.airplay.android;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

public class AudioPlayer {
    private static final String TAG = "AudioPlayer";
    private AudioTrack audioTrack;

    public AudioPlayer() {
        try {
            int sampleRate = 44100;
            int channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
            int audioFormat = AudioFormat.ENCODING_PCM_16BIT;

            int minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat);
            int bufferSize = Math.max(minBufferSize * 4, 480 * 4 * 4);

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
            audioTrack.play();
            Log.i(TAG, "AudioTrack initialized, bufferSize=" + bufferSize);
        } catch (Exception e) {
            Log.e(TAG, "Failed to init AudioTrack", e);
        }
    }

    public void play(byte[] audio) {
        if (audioTrack != null && audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
            try {
                audioTrack.write(audio, 0, audio.length);
            } catch (Exception e) {
                Log.e(TAG, "Error writing audio", e);
            }
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
