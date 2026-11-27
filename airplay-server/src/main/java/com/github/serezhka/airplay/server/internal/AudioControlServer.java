package com.github.serezhka.airplay.server.internal;

import com.github.serezhka.airplay.server.internal.handler.audio.AudioControlHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;

@Slf4j
public class AudioControlServer implements Runnable {

    private volatile java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);

    private Thread thread;
    private int port;

    public void start() throws InterruptedException {
        started = new java.util.concurrent.CountDownLatch(1);
        thread = new Thread(this);
        thread.start();
        if (!started.await(15, java.util.concurrent.TimeUnit.SECONDS)) {
            throw new IllegalStateException("AudioControlServer did not start in time");
        }
    }

    public void stop() {
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    @Override
    public void run() {
        var bootstrap = new Bootstrap();
        var workerGroup = eventLoopGroup();

        try {
            bootstrap
                    .group(workerGroup)
                    .channel(datagramChannelClass())
                    .localAddress(new InetSocketAddress(0)) // bind random port
                    .handler(new ChannelInitializer<DatagramChannel>() {
                        @Override
                        public void initChannel(final DatagramChannel ch) {
                            ch.pipeline().addLast("audioControlHandler", new AudioControlHandler());
                        }
                    });

            var channelFuture = bootstrap.bind().sync();

            log.info("AirPlay audio control server listening on port: {}",
                    port = ((InetSocketAddress) channelFuture.channel().localAddress()).getPort());

            started.countDown();

            channelFuture.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            log.debug("AirPlay audio control server interrupted");
        } finally {
            started.countDown();
            log.debug("AirPlay audio control server stopped");
            workerGroup.shutdownGracefully();
        }
    }

    public int getPort() {
        return port;
    }

    private EventLoopGroup eventLoopGroup() {
        return NettyEventLoops.create();
    }

    private Class<? extends DatagramChannel> datagramChannelClass() {
        return NioDatagramChannel.class;
    }
}