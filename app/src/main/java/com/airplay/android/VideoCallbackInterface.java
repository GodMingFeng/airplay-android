package com.airplay.android;

public interface VideoCallbackInterface {
    void onVideo(byte[] video, long ptsUs);
    void onVideoFormat(int width, int height);
    /** @param rtpTime sender's 44100Hz timestamp of these samples, used to line them up with video */
    void onAudio(byte[] audio, long rtpTime);
    void onAudioFormat(int sampleRate, int channels);
    void onSpsPps(byte[] spsPps);
}
