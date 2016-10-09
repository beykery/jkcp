/**
 * 维护kcp状态的线程
 */
package org.beykery.jkcp;

import io.netty.channel.socket.DatagramPacket;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 *
 * @author beykery
 */
public class KcpThread extends Thread
{

  private final Output out;
  private final LinkedBlockingQueue<DatagramPacket> inputs;
  private volatile boolean running;
  private final Map<InetSocketAddress, KcpOnUdp> kcps;
  private final KcpListerner listerner;
  private int nodelay;
  private int interval = Kcp.IKCP_INTERVAL;
  private int resend;
  private int nc;
  private int sndwnd = Kcp.IKCP_WND_SND;
  private int rcvwnd = Kcp.IKCP_WND_RCV;
  private int mtu = Kcp.IKCP_MTU_DEF;
  private boolean stream;
  private int minRto = Kcp.IKCP_RTO_MIN;
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
    kcps = new ConcurrentHashMap<>();
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
      long st = System.currentTimeMillis();
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
          ku.setMinRto(minRto);
          ku.setStream(stream);
          ku.setTimeout(timeout);
          this.kcps.put(dp.sender(), ku);
        }
        ku.input(dp.content());
      }
      //update
      KcpOnUdp temp = null;
      for (KcpOnUdp ku : this.kcps.values())
      {
        if (ku.isClosed())
        {
          temp = ku;
        } else
        {
          ku.update();
        }
      }
      if (temp != null)//删掉过时的kcp
      {
        this.kcps.remove((InetSocketAddress) temp.getKcp().getUser());
      }
      long end = System.currentTimeMillis();
      if (end - st < this.interval)
      {
        try
        {
          Thread.sleep(this.interval - end + st);
        } catch (InterruptedException ex)
        {
        }
      }
    }
    release();
  }

  /**
   * 收到输入
   *
   * @param addr
   * @param content
   */
  void input(DatagramPacket dp)
  {
    if (this.running)
    {
      this.inputs.add(dp);
    } else
    {
      dp.release();
    }
  }

  /**
   * stream mode
   *
   *
   * @param stream
   */
  public void setStream(boolean stream)
  {
    this.stream = stream;
  }

  public boolean isStream()
  {
    return stream;
  }

  public void setMinRto(int minRto)
  {
    this.minRto = minRto;
  }

  public void setTimeout(long timeout)
  {
    this.timeout = timeout;
  }

  public long getTimeout()
  {
    return timeout;
  }

  /**
   * 释放所有内存
   */
  private void release()
  {
    for (DatagramPacket dp : this.inputs)
    {
      dp.release();
    }
    this.inputs.clear();
    for (KcpOnUdp ku : this.kcps.values())
    {
      if (!ku.isClosed())
      {
        ku.release();
      }
    }
    this.kcps.clear();
  }

}
