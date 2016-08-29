/**
 * 维护kcp状态的线程
 */
package org.beykery.jkcp;

import io.netty.channel.socket.DatagramPacket;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

/**
 *
 * @author beykery
 */
public class KcpThread extends Thread
{

  private final Output out;
  private final LinkedBlockingQueue<DatagramPacket> inputs;
  private boolean running;
  private final Map<InetSocketAddress, KcpOnUdp> kcps;
  private final KcpListerner listerner;
  private int nodelay;
  private int interval = Kcp.IKCP_INTERVAL;
  private int resend;
  private int nc;
  private int sndwnd = Kcp.IKCP_WND_SND;
  private int rcvwnd = Kcp.IKCP_WND_RCV;
  private int mtu = Kcp.IKCP_MTU_DEF;
  private long timeout;//idle

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
   * kcp工作线程
   *
   * @param out
   * @param listerner
   */
  public KcpThread(Output out, KcpListerner listerner)
  {
    this.out = out;
    this.listerner = listerner;
    inputs = new LinkedBlockingQueue<>();
    kcps = new HashMap<>();
  }

  /**
   * 开启线程
   */
  @Override
  public synchronized void start()
  {
    if (!this.running)
    {
      this.running = true;
      super.start();
    }
  }

  /**
   * 关闭线程
   */
  public void close()
  {
    this.running = false;
  }

  @Override
  public void run()
  {
    while (this.running)
    {
      //input
      while (!this.inputs.isEmpty())
      {
        DatagramPacket dp = this.inputs.remove();
        KcpOnUdp ku = this.kcps.get(dp.sender());
        if (ku == null)
        {
          ku = new KcpOnUdp(this.out, dp.sender(), this.listerner);//初始化
          ku.noDelay(nodelay, interval, resend, nc);
          ku.wndSize(sndwnd, rcvwnd);
          ku.setMtu(mtu);
          ku.setTimeout(timeout);
          this.kcps.put(dp.sender(), ku);
        }
        ku.input(dp.content());
      }
      //update
      KcpOnUdp temp = null;
      for (KcpOnUdp ku : this.kcps.values())
      {
        ku.update();
        if (ku.isClosed())
        {
          temp = ku;
        }
      }
      if (temp != null)//删掉过时的kcp
      {
        this.kcps.remove((InetSocketAddress) temp.getKcp().getUser());
      }
      try
      {
        Thread.sleep(this.interval);
      } catch (InterruptedException ex)
      {
      }
    }
  }

  /**
   * 收到输入
   *
   * @param addr
   * @param content
   */
  void input(DatagramPacket dp)
  {
    this.inputs.add(dp);
  }

  public void setTimeout(long timeout)
  {
    this.timeout = timeout;
  }

  public long getTimeout()
  {
    return timeout;
  }

}
