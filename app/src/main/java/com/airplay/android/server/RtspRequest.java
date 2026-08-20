package com.airplay.android.server;

public class RtspRequest {
    public String method;
    public String uri;
    public int cseq;
    public byte[] body;
    public java.util.Map<String, String> headers = new java.util.HashMap<>();
}
