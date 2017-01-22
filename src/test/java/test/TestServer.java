/**
 * 测试
 */
package test;

import io.netty.buffer.ByteBuf;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import org.beykery.jkcp.KcpOnUdp;
import org.beykery.jkcp.KcpServer;

/**
 *
 * @author beykery
 */
public class TestServer extends KcpServer
{

  public TestServer(int port, int workerSize)
  {
    super(port, workerSize);
  }

  @Override
  public void handleReceive(ByteBuf bb, KcpOnUdp kcp)
  {
    String content = bb.toString(Charset.forName("utf-8"));
    System.out.println("msg:" + content + " from " + kcp+" order "+bb.order());
    kcp.send(bb);//echo
  }

  @Override
  public void handleException(Throwable ex, KcpOnUdp kcp)
  {
    System.out.println(ex);
  }

  @Override
  public void handleClose(KcpOnUdp kcp)
  {
    System.out.println("客户端离开:" + kcp);
    System.out.println("waitSnd:" + kcp.getKcp().waitSnd());
  }

  /**
   * 测试
   *
   * @param args
   */
  public static void main(String[] args)
  {
    TestServer s = new TestServer(2222, 1);
    s.noDelay(1, 10, 2, 1);
    s.wndSize(64, 64);
    s.setTimeout(10 * 1000);
    s.setMtu(512);
    s.setConv(121106);
    s.setOrder(ByteOrder.LITTLE_ENDIAN);//此处设置为小头才能与kcp协议兼容，kcp里面的IWORDS_BIG_ENDIAN跟此处意义并不相同(可能还是要改成跟原作kcp一样的思路，只使用小端编码)
    s.start();
  }
}
