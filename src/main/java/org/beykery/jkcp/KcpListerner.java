/**
 *
 */
package org.beykery.jkcp;

import io.netty.buffer.ByteBuf;

/**
 *
 * @author beykery
 */
public interface KcpListerner
{

  /**
   * kcp message
   *
   * @param bb the data
   * @param kcp
   */
  public void handleReceive(ByteBuf bb, KcpOnUdp kcp);

  /**
   *
   * 异常
   *
   * @param ex
   */
  public void handleException(Throwable ex);

  /**
   * 关闭
   *
   * @param kcp
   */
  public void handleClose(KcpOnUdp kcp);
}
