package com.airplay.android.server;

import android.os.SystemClock;
import android.util.Log;

import com.airplay.android.VideoCallbackInterface;
import com.airplay.android.VideoHolder;
import com.github.serezhka.jap2lib.AirPlay;
import com.github.serezhka.jap2lib.rtsp.AudioStreamInfo;
import com.github.serezhka.jap2lib.rtsp.MediaStreamInfo;
import com.github.serezhka.jap2lib.rtsp.VideoStreamInfo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.rtsp.RtspDecoder;
import io.netty.handler.codec.rtsp.RtspEncoder;
import io.netty.handler.codec.rtsp.RtspResponseStatuses;
import io.netty.handler.codec.rtsp.RtspVersions;

public class RtspControlServer {
    private static final String TAG = "RtspControlServer";
    /** Shared tag for the connection-timing trace; grep with `adb logcat -s AirPlayPerf`. */
    private static final String PERF = "AirPlayPerf";

    private final int airPlayPort;
    private final int airTunesPort;
    private final VideoCallbackInterface callback;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    /**
     * The receivers serving the session in progress. Only one is ever kept: the mirroring port is
     * fixed, so a second sender could not be served alongside the first in any case.
     *
     * <p>They have to be held onto rather than left to their threads, because each one owns a
     * socket and the audio one an AAC decoder as well. MediaCodec instances are a device wide
     * resource, and a session that walks off with one leaves fewer for the next.
     */
    private final Object sessionLock = new Object();
    private MirroringReceiver mirroringReceiver;
    private AudioReceiver audioReceiver;
    private AudioControlServer audioControlServer;

    public RtspControlServer(int airPlayPort, int airTunesPort, VideoCallbackInterface callback) {
        this.airPlayPort = airPlayPort;
        this.airTunesPort = airTunesPort;
        this.callback = callback;
    }

    public void start() throws Exception {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .localAddress(new InetSocketAddress(airTunesPort))
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        long t0 = SystemClock.elapsedRealtime();
                        AirPlay airPlay = new AirPlay();
                        Log.i(PERF, "new AirPlay() (Ed25519 key gen) took "
                                + (SystemClock.elapsedRealtime() - t0) + "ms");
                        ch.pipeline().addLast(
                                new RtspDecoder(),
                                new RtspEncoder(),
                                new HttpObjectAggregator(64 * 1024),
                                new RtspHandler(airPlay)
                        );
                    }
                })
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true);

        bootstrap.bind().sync();
        Log.i(TAG, "Netty RTSP control server listening on port " + airTunesPort);
    }

    public void stop() {
        endVideo();
        endAudio();
        if (bossGroup != null) bossGroup.shutdownGracefully();
        if (workerGroup != null) workerGroup.shutdownGracefully();
    }

    /** Stops the mirroring receiver and lets go of the video decoder and the surface. */
    private void endVideo() {
        MirroringReceiver mirroring;
        synchronized (sessionLock) {
            mirroring = mirroringReceiver;
            mirroringReceiver = null;
        }
        if (mirroring != null) {
            Log.i(TAG, "Ending the video session");
            mirroring.stop();
        }
        VideoHolder.notifySessionEnded();
    }

    /**
     * Stops the audio receiver and its control channel, and lets go of the AAC decoder and the
     * AudioTrack with them. The receiver goes first, so that nothing is still being written by the
     * time the track is handed back.
     */
    private void endAudio() {
        AudioReceiver audio;
        AudioControlServer control;
        synchronized (sessionLock) {
            audio = audioReceiver;
            audioReceiver = null;
            control = audioControlServer;
            audioControlServer = null;
        }
        if (audio == null && control == null) return;
        Log.i(TAG, "Ending the audio session");
        if (audio != null) audio.stop();
        if (control != null) control.stop();
        VideoHolder.releaseAudio();
    }

    private class RtspHandler extends ChannelInboundHandlerAdapter {

        private final AirPlay airPlay;
        /** When this control connection opened, the zero point for every timing below. */
        private long connectStartMs;

        RtspHandler(AirPlay airPlay) {
            this.airPlay = airPlay;
        }

        /**
         * A fresh control connection means a fresh session, so anything the last one left behind
         * goes now. Senders do not always send TEARDOWN, and one that simply vanished would
         * otherwise keep its receivers running for the lifetime of the app.
         */
        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            connectStartMs = SystemClock.elapsedRealtime();
            Log.i(PERF, "Control connection opened from " + ctx.channel().remoteAddress());
            endVideo();
            endAudio();
            super.channelActive(ctx);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            Log.i(TAG, "Control connection closed");
            endVideo();
            endAudio();
            super.channelInactive(ctx);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (!(msg instanceof FullHttpRequest)) {
                ctx.fireChannelRead(msg);
                return;
            }

            FullHttpRequest request = (FullHttpRequest) msg;
            String method = request.method().toString();
            String uri = request.uri();
            Log.i(TAG, "Request: " + method + " " + uri);
            long reqStartMs = SystemClock.elapsedRealtime();
            Log.i(PERF, "+" + (reqStartMs - connectStartMs) + "ms since connect -> "
                    + method + " " + uri);

            try {
                if (!handleRequest(ctx, request)) {
                    // Everything gets an answer, even what is not recognised: a sender left
                    // without one waits out its own timeout instead of carrying on
                    Log.w(TAG, "Unhandled: " + method + " " + uri);
                    sendResponse(ctx, request,
                            createResponse(request, RtspResponseStatuses.NOT_IMPLEMENTED));
                }
            } catch (Exception e) {
                // Nothing has been written at this point: every path that answers does so as its
                // last act, so there is no risk of a second response confusing the sender
                Log.e(TAG, "Error handling " + method + " " + uri, e);
                sendResponse(ctx, request,
                        createResponse(request, RtspResponseStatuses.INTERNAL_SERVER_ERROR));
            } finally {
                Log.i(PERF, "handled " + method + " " + uri + " in "
                        + (SystemClock.elapsedRealtime() - reqStartMs) + "ms");
                // This handler is the end of the pipeline, so the buffer is released here and the
                // message is not passed on. Doing both would hand the tail something already freed.
                request.release();
            }
        }

        private boolean handleRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
            String method = request.method().toString();
            String uri = request.uri();

            switch (uri) {
                case "/info": {
                    DefaultFullHttpResponse response = createResponse(request);
                    airPlay.info(new ByteBufOutputStream(response.content()));
                    return sendResponse(ctx, request, response);
                }
                case "/pair-setup": {
                    DefaultFullHttpResponse response = createResponse(request);
                    airPlay.pairSetup(new ByteBufOutputStream(response.content()));
                    return sendResponse(ctx, request, response);
                }
                case "/pair-verify": {
                    DefaultFullHttpResponse response = createResponse(request);
                    airPlay.pairVerify(new ByteBufInputStream(request.content()),
                            new ByteBufOutputStream(response.content()));
                    return sendResponse(ctx, request, response);
                }
                case "/fp-setup": {
                    DefaultFullHttpResponse response = createResponse(request);
                    airPlay.fairPlaySetup(new ByteBufInputStream(request.content()),
                            new ByteBufOutputStream(response.content()));
                    return sendResponse(ctx, request, response);
                }
                case "/feedback":
                case "/audioMode": {
                    DefaultFullHttpResponse response = createResponse(request);
                    return sendResponse(ctx, request, response);
                }
            }

            if ("SETUP".equals(method)) {
                return handleSetup(ctx, request);
            } else if ("RECORD".equals(method)) {
                DefaultFullHttpResponse response = createResponse(request);
                response.headers().add("Audio-Latency", "11025");
                response.headers().add("Audio-Jack-Status", "connected; type=analog");
                return sendResponse(ctx, request, response);
            } else if ("GET_PARAMETER".equals(method)) {
                DefaultFullHttpResponse response = createResponse(request);
                response.content().writeBytes("volume: 1.000000\r\n".getBytes(StandardCharsets.US_ASCII));
                return sendResponse(ctx, request, response);
            } else if ("SET_PARAMETER".equals(method)) {
                DefaultFullHttpResponse response = createResponse(request);
                return sendResponse(ctx, request, response);
            } else if ("FLUSH".equals(method)) {
                DefaultFullHttpResponse response = createResponse(request);
                return sendResponse(ctx, request, response);
            } else if ("TEARDOWN".equals(method)) {
                handleTeardown(request);
                DefaultFullHttpResponse response = createResponse(request);
                return sendResponse(ctx, request, response);
            }

            return false;
        }

        private boolean handleSetup(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
            DefaultFullHttpResponse response = createResponse(request);

            // Try to get media stream info from body
            request.content().markReaderIndex();
            MediaStreamInfo mediaStreamInfo = null;
            try {
                mediaStreamInfo = airPlay.rtspGetMediaStreamInfo(
                        new ByteBufInputStream(request.content()));
            } catch (Exception e) {
                Log.d(TAG, "getMediaStreamInfo failed (expected for encryption setup): " + e.getMessage());
            }

            if (mediaStreamInfo == null) {
                // Encryption setup - reset reader index and parse ekey/eiv
                request.content().resetReaderIndex();
                airPlay.rtspSetupEncryption(new ByteBufInputStream(request.content()));
                Log.i(TAG, "Encryption setup done");
                return sendResponse(ctx, request, response);
            }

            switch (mediaStreamInfo.getStreamType()) {
                case VIDEO: {
                    VideoStreamInfo videoInfo = (VideoStreamInfo) mediaStreamInfo;
                    Log.i(TAG, "Setting up VIDEO stream, connectionID: " + videoInfo.getStreamConnectionID());
                    callback.onVideoFormat(1920, 1080);

                    // Start mirroring receiver. The socket is bound now, on this thread, so the
                    // port is already accepting by the time the reply goes back and the sender's
                    // connection is not refused; the thread it runs on then only accepts and drains.
                    MirroringReceiver receiver = new MirroringReceiver(airPlayPort, airPlay, callback);
                    receiver.open();
                    synchronized (sessionLock) {
                        mirroringReceiver = receiver;
                    }
                    Thread mirroringThread = new Thread(receiver);
                    mirroringThread.start();

                    airPlay.rtspSetupVideo(new ByteBufOutputStream(response.content()),
                            airPlayPort, airTunesPort, 7011);
                    return sendResponse(ctx, request, response);
                }
                case AUDIO: {
                    AudioStreamInfo audioInfo = (AudioStreamInfo) mediaStreamInfo;
                    Log.i(TAG, "Setting up AUDIO stream, format: " + audioInfo.getAudioFormat() +
                            ", compression: " + audioInfo.getCompressionType());

                    int sampleRate = 44100;
                    int channels = 2;
                    try {
                        String fmtName = audioInfo.getAudioFormat().name();
                        String[] parts = fmtName.split("_");
                        for (String part : parts) {
                            try {
                                int val = Integer.parseInt(part);
                                if (val >= 8000 && val <= 96000) sampleRate = val;
                                else if (val == 1 || val == 2) channels = val;
                            } catch (NumberFormatException e) { /* skip */ }
                        }
                    } catch (Exception e) { /* default */ }

                    boolean isAacEld = audioInfo.getCompressionType() == AudioStreamInfo.CompressionType.AAC_ELD;
                    Log.i(TAG, "Audio params: sampleRate=" + sampleRate + ", channels=" + channels + ", isAacEld=" + isAacEld);
                    callback.onAudioFormat(sampleRate, channels);

                    // Start audio receiver (UDP)
                    final Object monitor = new Object();
                    AudioReceiver receiver = new AudioReceiver(airPlay, callback, monitor,
                            sampleRate, channels, isAacEld);
                    Thread audioThread = new Thread(receiver);
                    audioThread.start();
                    long audioWaitStart = SystemClock.elapsedRealtime();
                    synchronized (monitor) { monitor.wait(5000); }
                    Log.i(PERF, "AUDIO SETUP waited " + (SystemClock.elapsedRealtime() - audioWaitStart)
                            + "ms for the audio socket (cap 5000), port=" + receiver.getPort());

                    // Start audio control server (UDP)
                    final Object controlMonitor = new Object();
                    AudioControlServer control = new AudioControlServer(controlMonitor);
                    Thread audioControlThread = new Thread(control);
                    audioControlThread.start();
                    long controlWaitStart = SystemClock.elapsedRealtime();
                    synchronized (controlMonitor) { controlMonitor.wait(5000); }
                    Log.i(PERF, "AUDIO SETUP waited " + (SystemClock.elapsedRealtime() - controlWaitStart)
                            + "ms for the control socket (cap 5000), port=" + control.getPort());

                    synchronized (sessionLock) {
                        audioReceiver = receiver;
                        audioControlServer = control;
                    }

                    airPlay.rtspSetupAudio(new ByteBufOutputStream(response.content()),
                            receiver.getPort(), control.getPort());
                    return sendResponse(ctx, request, response);
                }
            }
            return false;
        }

        private void handleTeardown(FullHttpRequest request) {
            MediaStreamInfo info = null;
            try {
                info = airPlay.rtspGetMediaStreamInfo(new ByteBufInputStream(request.content()));
            } catch (Exception e) {
                Log.w(TAG, "Could not read the teardown body, treating it as a full teardown", e);
            }
            Log.i(TAG, "TEARDOWN: " + (info != null ? info.getStreamType() : "all"));
            // A body naming one stream tears down only that one; an empty one ends the session
            if (info == null || info.getStreamType() == MediaStreamInfo.StreamType.VIDEO) {
                endVideo();
            }
            if (info == null || info.getStreamType() == MediaStreamInfo.StreamType.AUDIO) {
                endAudio();
            }
        }

        private DefaultFullHttpResponse createResponse(FullHttpRequest request) {
            return createResponse(request, RtspResponseStatuses.OK);
        }

        private DefaultFullHttpResponse createResponse(FullHttpRequest request,
                                                       HttpResponseStatus status) {
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(
                    RtspVersions.RTSP_1_0, status);
            response.headers().clear();
            String cSeq = request.headers().get("CSeq");
            if (cSeq != null) {
                response.headers().add("CSeq", cSeq);
            }
            return response;
        }

        private boolean sendResponse(ChannelHandlerContext ctx, FullHttpRequest request,
                                       FullHttpResponse response) {
            HttpUtil.setContentLength(response, response.content().readableBytes());
            ctx.writeAndFlush(response);
            Log.d(TAG, "Sent response for " + request.method() + " " + request.uri());
            return true;
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            Log.e(TAG, "Channel exception", cause);
            ctx.close();
        }
    }
}
