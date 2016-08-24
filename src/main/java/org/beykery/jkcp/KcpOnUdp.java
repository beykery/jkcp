/**
 * udp for kcp
 */
package org.beykery.jkcp;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author beykery
 */
public abstract class KcpOnUdp implements Output
{

  private static final Logger LOG = LoggerFactory.getLogger(KcpOnUdp.class);

  private final NioDatagramChannel channel;
  private final InetSocketAddress addr;
  private final Map<InetSocketAddress, Kcp> kcps;
  private final Map<InetSocketAddress, Queue<DatagramPacket>> received;
  private final Lock dataLock = new ReentrantLock();
  private final Lock kcpLock = new ReentrantLock();
  private int nodelay;
  private int interval = Kcp.IKCP_INTERVAL;
  private int resend;
  private int nc;
  private int sndwnd = Kcp.IKCP_WND_SND;
  private int rcvwnd = Kcp.IKCP_WND_RCV;
  private int mtu = Kcp.IKCP_MTU_DEF;

  /**
   * kcp for udp
   *
   * @param port
   */
  public KcpOnUdp(int port)
  {
    this.kcps = new HashMap<>();
    received = new HashMap<>();
    final NioEventLoopGroup nioEventLoopGroup = new NioEventLoopGroup();
    Bootstrap bootstrap = new Bootstrap();
    bootstrap.channel(NioDatagramChannel.class);
    bootstrap.group(nioEventLoopGroup);
    bootstrap.handler(new ChannelInitializer<NioDatagramChannel>()
    {

      @Override
      protected void initChannel(NioDatagramChannel ch) throws Exception
      {
        ChannelPipeline cp = ch.pipeline();
        cp.addLast(new KcpOnUdp.UdpHandler());
      }
    });
    ChannelFuture sync = bootstrap.bind(port).syncUninterruptibly();
    channel = (NioDatagramChannel) sync.channel();
    addr = channel.localAddress();
    Runtime.getRuntime().addShutdownHook(new Thread(new Runnable()
    {
      @Override
      public void run()
      {
        nioEventLoopGroup.shutdownGracefully();
      }
    }));
  }

  /**
   * close
   *
   * @return
   */
  public ChannelFuture close()
  {
    return this.channel.close();
  }

  /**
   * kcp call
   *
   * @param msg
   * @param kcp
   * @param user
   */
  @Override
  public void out(ByteBuf msg, Kcp kcp, Object user)
  {
    DatagramPacket temp = new DatagramPacket(msg, (InetSocketAddress) user, this.addr);
    this.channel.writeAndFlush(temp);
  }

  /**
   * one kcp per addr
   *
   * @param addr
   * @return
   */
  private Kcp getKcp(InetSocketAddress addr)
  {
    Kcp kcp = null;
    kcpLock.lock();
    try
    {
      kcp = kcps.get(addr);
      if (kcp == null)
      {
        kcp = new Kcp(121106, KcpOnUdp.this, addr);
        //mode setting
        kcp.noDelay(nodelay, interval, resend, nc);
        kcp.wndSize(sndwnd, rcvwnd);
        kcp.setMtu(mtu);
        kcps.put(addr, kcp);
      }
    } finally
    {
      kcpLock.unlock();
    }
    return kcp;
  }

  /**
   * send data to addr
   *
   * @param bb
   * @param addr
   */
  public void send(ByteBuf bb, InetSocketAddress addr)
  {
    Kcp kcp = this.getKcp(addr);
    kcp.send(bb);
  }

  /**
   * update every tick
   */
  public void update()
  {
    kcpLock.lock();
    try
    {
      for (Map.Entry<InetSocketAddress, Kcp> en : this.kcps.entrySet())
      {
        update(en.getKey(), en.getValue());
      }
    } finally
    {
      kcpLock.unlock();
    }
  }

  /**
   * update one kcp
   *
   * @param addr
   * @param kcp
   */
  private void update(InetSocketAddress addr, Kcp kcp)
  {
    //input
    dataLock.lock();
    try
    {
      Queue<DatagramPacket> q = this.received.get(addr);
      while (q != null && q.size() > 0)
      {
        DatagramPacket dp = q.remove();
        kcp.input(dp.content());
      }
    } finally
    {
      dataLock.unlock();
    }
    //receive
    int len;
    while ((len = kcp.peekSize()) > 0)
    {
      ByteBuf bb = PooledByteBufAllocator.DEFAULT.buffer(len);
      int n = kcp.receive(bb);
      if (n > 0)
      {
        this.handleReceive(bb, addr);
      } else
      {
        bb.release();
      }
    }
    //update kcp status
    int cur = (int) System.currentTimeMillis();
    if (kcp.isNeedUpdate() || cur >= kcp.getNextUpdate())
    {
      kcp.update(cur);
      kcp.setNextUpdate(kcp.check(cur));
      kcp.setNeedUpdate(false);
    }
  }

  /**
   * kcp message
   *
   * @param bb the data 
   * @param addr the sender
   */
  protected abstract void handleReceive(ByteBuf bb, InetSocketAddress addr);

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
  public void noDelay(int nodelay, int interval, int resend, int nc)
  {
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
  public void wndSize(int sndwnd, int rcvwnd)
  {
    this.sndwnd = sndwnd;
    this.rcvwnd = rcvwnd;
  }

  /**
   * change MTU size, default is 1400
   *
   * @param mtu
   */
  public void setMtu(int mtu)
  {
    this.mtu = mtu;
  }

  /**
   * receive DatagramPacket
   *
   * @param dp
   */
  private void onReceive(DatagramPacket dp)
  {
    this.dataLock.lock();
    try
    {
      Queue<DatagramPacket> q = this.received.get(dp.sender());
      if (q == null)
      {
        q = new LinkedList<>();
        received.put(dp.sender(), q);
        this.getKcp(dp.sender());//the first receive,init the kcp.
      }
      q.add(dp);
    } finally
    {
      dataLock.unlock();
    }
  }

  /**
   * handler
   */
  class UdpHandler extends ChannelInboundHandlerAdapter
  {

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception
    {
      DatagramPacket dp = (DatagramPacket) msg;
      KcpOnUdp.this.onReceive(dp);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception
    {
      LOG.error(cause.toString());
    }
  }

}
