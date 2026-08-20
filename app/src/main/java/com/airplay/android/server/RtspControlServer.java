package com.airplay.android.server;

import android.util.Log;

import com.airplay.android.VideoCallbackInterface;
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
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.rtsp.RtspDecoder;
import io.netty.handler.codec.rtsp.RtspEncoder;
import io.netty.handler.codec.rtsp.RtspResponseStatuses;
import io.netty.handler.codec.rtsp.RtspVersions;

public class RtspControlServer {
    private static final String TAG = "RtspControlServer";

    private final int airPlayPort;
    private final int airTunesPort;
    private final VideoCallbackInterface callback;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

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
                        AirPlay airPlay = new AirPlay();
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
        if (bossGroup != null) bossGroup.shutdownGracefully();
        if (workerGroup != null) workerGroup.shutdownGracefully();
    }

    private class RtspHandler extends ChannelInboundHandlerAdapter {

        private final AirPlay airPlay;

        RtspHandler(AirPlay airPlay) {
            this.airPlay = airPlay;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (!(msg instanceof FullHttpRequest)) {
                super.channelRead(ctx, msg);
                return;
            }

            FullHttpRequest request = (FullHttpRequest) msg;
            String method = request.method().toString();
            String uri = request.uri();
            Log.i(TAG, "Request: " + method + " " + uri);

            try {
                if (handleRequest(ctx, request)) {
                    return;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error handling " + method + " " + uri, e);
            } finally {
                request.release();
            }

            super.channelRead(ctx, msg);
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

            Log.w(TAG, "Unhandled: " + method + " " + uri);
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

                    // Start mirroring receiver
                    MirroringReceiver receiver = new MirroringReceiver(airPlayPort, airPlay, callback);
                    Thread mirroringThread = new Thread(receiver);
                    mirroringThread.start();

                    // Wait for server socket to bind
                    Thread.sleep(50);

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
                    AudioReceiver audioReceiver = new AudioReceiver(airPlay, callback, monitor,
                            sampleRate, channels, isAacEld);
                    Thread audioThread = new Thread(audioReceiver);
                    audioThread.start();
                    synchronized (monitor) { monitor.wait(5000); }

                    // Start audio control server (UDP)
                    final Object controlMonitor = new Object();
                    AudioControlServer audioControl = new AudioControlServer(controlMonitor);
                    Thread audioControlThread = new Thread(audioControl);
                    audioControlThread.start();
                    synchronized (controlMonitor) { controlMonitor.wait(5000); }

                    airPlay.rtspSetupAudio(new ByteBufOutputStream(response.content()),
                            audioReceiver.getPort(), audioControl.getPort());
                    return sendResponse(ctx, request, response);
                }
            }
            return false;
        }

        private void handleTeardown(FullHttpRequest request) {
            try {
                MediaStreamInfo info = airPlay.rtspGetMediaStreamInfo(
                        new ByteBufInputStream(request.content()));
                Log.i(TAG, "TEARDOWN: " + (info != null ? info.getStreamType() : "all"));
            } catch (Exception e) {
                Log.w(TAG, "Error in teardown", e);
            }
        }

        private DefaultFullHttpResponse createResponse(FullHttpRequest request) {
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(
                    RtspVersions.RTSP_1_0, RtspResponseStatuses.OK);
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
