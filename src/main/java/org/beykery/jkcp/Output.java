/**
 * out
 */
package org.beykery.jkcp;

import io.netty.buffer.ByteBuf;

/**
 *
 * @author beykery
 */
interface Output
{

  void out(ByteBuf msg, Kcp kcp, Object user);
}
