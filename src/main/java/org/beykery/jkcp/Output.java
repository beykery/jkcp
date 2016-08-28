/**
 * out
 */
package org.beykery.jkcp;

import io.netty.buffer.ByteBuf;

/**
 *
 * @author beykery
 */
public interface Output
{

  /**
   * kcp的底层输出
   *
   * @param msg
   * @param kcp
   * @param user
   */
  void out(ByteBuf msg, Kcp kcp, Object user);
}
