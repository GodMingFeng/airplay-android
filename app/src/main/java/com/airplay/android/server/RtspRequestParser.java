package com.airplay.android.server;

import android.util.Log;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class RtspRequestParser {
    private static final String TAG = "RtspRequestParser";

    public static RtspRequest parse(DataInputStream in) throws IOException {
        // Read the request line
        String requestLine = readLine(in);
        if (requestLine == null || requestLine.isEmpty()) {
            return null;
        }

        String[] parts = requestLine.trim().split("\\s+");
        if (parts.length < 3) {
            Log.w(TAG, "Invalid request line: " + requestLine);
            return null;
        }

        RtspRequest request = new RtspRequest();
        request.method = parts[0];
        request.uri = parts[1];

        // Read headers
        int contentLength = 0;
        String line;
        while ((line = readLine(in)) != null) {
            if (line.isEmpty()) break; // End of headers
            int colonIdx = line.indexOf(':');
            if (colonIdx > 0) {
                String key = line.substring(0, colonIdx).trim();
                String value = line.substring(colonIdx + 1).trim();
                request.headers.put(key, value);

                if ("CSeq".equalsIgnoreCase(key)) {
                    try { request.cseq = Integer.parseInt(value); } catch (NumberFormatException e) { /* ignore */ }
                }
                if ("Content-Length".equalsIgnoreCase(key)) {
                    try { contentLength = Integer.parseInt(value); } catch (NumberFormatException e) { /* ignore */ }
                }
            }
        }

        // Read body
        if (contentLength > 0) {
            request.body = new byte[contentLength];
            in.readFully(request.body);
        } else {
            request.body = new byte[0];
        }

        return request;
    }

    private static String readLine(DataInputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\r') {
                int next = in.read();
                if (next == '\n') {
                    break;
                }
                sb.append((char) c);
                if (next != -1) sb.append((char) next);
            } else if (c == '\n') {
                break;
            } else {
                sb.append((char) c);
            }
        }
        if (c == -1 && sb.length() == 0) return null;
        return sb.toString();
    }
}
