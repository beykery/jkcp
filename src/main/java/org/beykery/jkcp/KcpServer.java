/**
 * kcp服务器
 */
package org.beykery.jkcp;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.kqueue.KQueueDatagramChannel;
import io.netty.channel.kqueue.KQueueEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author beykery
 */
public abstract class KcpServer implements Output, KcpListerner {
    private final AtomicLong counter = new AtomicLong(0);
    private final List<Channel> channels;
    private InetSocketAddress localAddress;
    private int nodelay;
    private int interval = Kcp.IKCP_INTERVAL;
    private int resend;
    private int nc;
    private int sndwnd = Kcp.IKCP_WND_SND;
    private int rcvwnd = Kcp.IKCP_WND_RCV;
    private int mtu = Kcp.IKCP_MTU_DEF;
    private boolean stream;
    private int minRto = Kcp.IKCP_RTO_MIN;
    private KcpThread[] workers;
    private volatile boolean running;
    private long timeout;

    /**
     * server
     *
     * @param port
     * @param workerSize
     */
    public KcpServer(int port, int workerSize) {
        boolean epoll = Epoll.isAvailable();
        boolean kqueue = KQueue.isAvailable();
        int bonds = (epoll || kqueue) ? Runtime.getRuntime().availableProcessors() : 1;
        channels = new ArrayList<>(bonds);
        final EventLoopGroup group = epoll ? new EpollEventLoopGroup() : (kqueue ? new KQueueEventLoopGroup() : new NioEventLoopGroup());
        if (port <= 0 || workerSize <= 0) {
            throw new IllegalArgumentException("参数非法");
        }
        this.workers = new KcpThread[workerSize];
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.channel(epoll ? EpollDatagramChannel.class : (kqueue ? KQueueDatagramChannel.class : NioDatagramChannel.class));
        bootstrap.group(group);
        bootstrap.option(ChannelOption.SO_BROADCAST, true)
                .option(ChannelOption.SO_RCVBUF, 1024 * 1024);
        if (epoll || kqueue) {
            bootstrap.option(EpollChannelOption.SO_REUSEPORT, true);
        }
        bootstrap.handler(new ChannelInitializer<Channel>() {

            @Override
            protected void initChannel(Channel ch) {
                ChannelPipeline cp = ch.pipeline();
                cp.addLast(new KcpServer.UdpHandler());
            }
        });

        for (int i = 0; i < bonds; i++) {
            try {
                ChannelFuture f = bootstrap.bind(port).await();
                Channel channel = f.channel();
                localAddress = (InetSocketAddress) channel.localAddress();
                channels.add(channel);
            } catch (InterruptedException e) {
                throw new RuntimeException("init failed . can not bind channel to port .");
            }
        }
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                group.shutdownGracefully();
            }
        }));
    }

    /**
     * 开始
     */
    public void start() {
        if (!this.running) {
            this.running = true;
            for (int i = 0; i < this.workers.length; i++) {
                workers[i] = new KcpThread(this, this, localAddress);
                workers[i].setName("kcp thread " + i);
                workers[i].wndSize(sndwnd, rcvwnd);
                workers[i].noDelay(nodelay, interval, resend, nc);
                workers[i].setMtu(mtu);
                workers[i].setTimeout(timeout);
                workers[i].setMinRto(minRto);
                workers[i].setStream(stream);
                workers[i].start();
            }
        }
    }

    /**
     * close
     *
     * @return
     */
    public void close() {
        if (this.running) {
            this.running = false;
            for (KcpThread kt : this.workers) {
                kt.close();
            }
            this.workers = null;
            for (Channel c : channels) {
                c.close();
            }
        }
    }

    /**
     * kcp call
     *
     * @param msg
     * @param kcp
     * @param user
     */
    @Override
    public void out(ByteBuf msg, Kcp kcp, Object user) {
        DatagramPacket temp = new DatagramPacket(msg, (InetSocketAddress) user, this.localAddress);
        Channel channel = channel();
        channel.writeAndFlush(temp);
    }

    /**
     * select one channel
     *
     * @return
     */
    private Channel channel() {
        long cur = counter.getAndAdd(1);
        return channels.get((int) (cur % channels.size()));
    }

    /**
     * fastest: ikcp_nodelay(kcp, 1, 20, 2, 1) nodelay: 0:disable(default),
     * 1:enable interval: internal update timer interval in millisec, default is
     * 100ms resend: 0:disable fast resend(default), 1:enable fast resend nc:
     * 0:normal congestion control(default), 1:disable congestion control
     *
     * @param nodelay
     * @param interval
     * @param resend
     * @param nc
     */
    public void noDelay(int nodelay, int interval, int resend, int nc) {
        this.nodelay = nodelay;
        this.interval = interval;
        this.resend = resend;
        this.nc = nc;
    }

    /**
     * set maximum window size: sndwnd=32, rcvwnd=32 by default
     *
     * @param sndwnd
     * @param rcvwnd
     */
    public void wndSize(int sndwnd, int rcvwnd) {
        this.sndwnd = sndwnd;
        this.rcvwnd = rcvwnd;
    }

    /**
     * change MTU size, default is 1400
     *
     * @param mtu
     */
    public void setMtu(int mtu) {
        this.mtu = mtu;
    }

    /**
     * stream mode
     *
     * @param stream
     */
    public void setStream(boolean stream) {
        this.stream = stream;
    }

    public boolean isStream() {
        return stream;
    }

    public void setMinRto(int minRto) {
        this.minRto = minRto;
    }

    public void setTimeout(long timeout) {
        this.timeout = timeout;
    }

    public long getTimeout() {
        return this.timeout;
    }

    /**
     * 发送
     *
     * @param bb
     * @param ku
     */
    public void send(ByteBuf bb, KcpOnUdp ku) {
        ku.send(bb);
    }

    /**
     * receive DatagramPacket
     *
     * @param dp
     */
    private void onReceive(DatagramPacket dp) {
        if (this.running) {
            InetSocketAddress sender = dp.sender();
            int hash = sender.hashCode();
            hash = hash < 0 ? -hash : hash;
            this.workers[hash % workers.length].input(dp);
        } else {
            dp.release();
        }
    }

    /**
     * handler
     */
    public class UdpHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            DatagramPacket dp = (DatagramPacket) msg;
            KcpServer.this.onReceive(dp);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            KcpServer.this.handleException(cause, null);
        }
    }
}
