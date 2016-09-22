/**
 * udp for kcp
 */
package org.beykery.jkcp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author beykery
 */
public class KcpOnUdp
{

  private static final Logger LOG = LoggerFactory.getLogger(KcpOnUdp.class);
  private final Kcp kcp;//kcp的状态
  private final Queue<ByteBuf> received;//输入
  private final Queue<ByteBuf> sendList;
  private long timeout;//超时设定
  private long lastTime;//上次超时检查时间
  private final KcpListerner listerner;
  private volatile boolean needUpdate;
  private volatile boolean closed;
  private String sessionId;
  private final Map<Object, Object> session;

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
    this.kcp.noDelay(nodelay, interval, resend, nc);
  }

  /**
   * set maximum window size: sndwnd=32, rcvwnd=32 by default
   *
   * @param sndwnd
   * @param rcvwnd
   */
  public void wndSize(int sndwnd, int rcvwnd)
  {
    this.kcp.wndSize(sndwnd, rcvwnd);
  }

  /**
   * change MTU size, default is 1400
   *
   * @param mtu
   */
  public void setMtu(int mtu)
  {
    this.kcp.setMtu(mtu);
  }

  /**
   * stream模式
   *
   * @param stream
   */
  public void setStream(boolean stream)
  {
    this.kcp.setStream(stream);
  }

  /**
   * 流模式
   *
   * @return
   */
  public boolean isStream()
  {
    return this.kcp.isStream();
  }

  /**
   * rto设置
   *
   * @param rto
   */
  public void setMinRto(int rto)
  {
    this.kcp.setMinRto(rto);
  }

  /**
   * kcp for udp
   *
   * @param out
   * @param user
   * @param listerner
   */
  public KcpOnUdp(Output out, Object user, KcpListerner listerner)
  {
    this.listerner = listerner;
    kcp = new Kcp(121106, out, user);
    received = new LinkedBlockingQueue<>();
    sendList = new LinkedBlockingQueue<>();
    this.session = new HashMap<>();
  }

  /**
   * send data to addr
   *
   * @param bb
   */
  public void send(ByteBuf bb)
  {
    if (!closed)
    {
      this.sendList.add(bb);
      this.needUpdate = true;
    }
  }

  /**
   * update one kcp
   *
   * @param addr
   * @param kcp
   */
  void update()
  {
    //input
    while (!this.received.isEmpty())
    {
      ByteBuf dp = this.received.remove();
      kcp.input(dp);
      dp.release();
    }
    //receive
    int len;
    while ((len = kcp.peekSize()) > 0)
    {
      ByteBuf bb = PooledByteBufAllocator.DEFAULT.buffer(len);
      int n = kcp.receive(bb);
      if (n > 0)
      {
        this.listerner.handleReceive(bb, this);
        this.lastTime = System.currentTimeMillis();
      } else
      {
        bb.release();
      }
    }
    //send
    while (!this.sendList.isEmpty())
    {
      ByteBuf bb = sendList.remove();
      this.kcp.send(bb);
    }
    //update kcp status
    int cur = (int) System.currentTimeMillis();
    if (this.needUpdate || cur >= kcp.getNextUpdate())
    {
      kcp.update(cur);
      kcp.setNextUpdate(kcp.check(cur));
      this.needUpdate = false;
    }
    //check timeout
    if (this.timeout > 0 && lastTime > 0 && System.currentTimeMillis() - this.lastTime > this.timeout)
    {
      this.closed = true;
      this.release();
      this.listerner.handleClose(this);
    }
  }

  /**
   * 输入
   *
   * @param content
   */
  void input(ByteBuf content)
  {
    if (!this.closed)
    {
      this.received.add(content);
      this.needUpdate = true;
    } else
    {
      content.release();
    }
  }

  public boolean isClosed()
  {
    return closed;
  }

  public Kcp getKcp()
  {
    return kcp;
  }

  public void setTimeout(long timeout)
  {
    this.timeout = timeout;
  }

  public long getTimeout()
  {
    return timeout;
  }

  @Override
  public String toString()
  {
    return this.kcp.toString();
  }

  public String getSessionId()
  {
    return sessionId;
  }

  public void setSessionId(String sessionId)
  {
    this.sessionId = sessionId;
  }

  public Map<Object, Object> getSessionMap()
  {
    return session;
  }

  public Object getSession(Object k)
  {
    return this.session.get(k);
  }

  public Object setSession(Object k, Object v)
  {
    return this.session.put(k, v);
  }

  public boolean containsSessionKey(Object k)
  {
    return this.session.containsKey(k);
  }

  public boolean containsSessionValue(Object v)
  {
    return this.session.containsValue(v);
  }

  boolean needUpdate()
  {
    return this.needUpdate;
  }

  /**
   * 释放内存
   */
  void release()
  {
    this.kcp.release();
    for (ByteBuf item : this.received)
    {
      item.release();
    }
    for (ByteBuf item : this.sendList)
    {
      item.release();
    }
  }
}
