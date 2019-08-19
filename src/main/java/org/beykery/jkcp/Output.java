/**
 * out
 */
package org.beykery.jkcp;

import io.netty.buffer.ByteBuf;

/**
 * @author beykery
 */
public interface Output {

    /**
     * kcp的底层输出
     *
     * @param msg  消息
     * @param kcp  kcp对象
     * @param user 远端地址
     */
    void out(ByteBuf msg, Kcp kcp, Object user);
}
