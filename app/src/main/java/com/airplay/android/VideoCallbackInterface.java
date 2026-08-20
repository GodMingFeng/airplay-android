package com.airplay.android;

public interface VideoCallbackInterface {
    void onVideo(byte[] video);
    void onVideoFormat(int width, int height);
    void onAudio(byte[] audio);
    void onAudioFormat(int sampleRate, int channels);
    void onSpsPps(byte[] spsPps);
}
