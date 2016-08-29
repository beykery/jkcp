/**
 * 测试
 */
package test;

import io.netty.buffer.ByteBuf;
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
    System.out.println("msg:" + content);
    bb.release();
  }

  @Override
  public void handleException(Throwable ex)
  {
    System.out.println(ex.fillInStackTrace());
  }

  @Override
  public void handleClose(KcpOnUdp kcp)
  {
    System.out.println("客户端离开:" + kcp);
  }

  /**
   * 测试
   *
   * @param args
   */
  public static void main(String[] args)
  {
    TestServer s = new TestServer(2222, 3);
    s.noDelay(1, 10, 2, 1);
    s.wndSize(64, 64);
    s.setTimeout(10 * 1000);
    s.start();
  }
}
