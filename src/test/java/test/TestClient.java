/**
 * 客户端
 */
package test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import org.beykery.jkcp.KcpClient;
import org.beykery.jkcp.KcpOnUdp;

/**
 *
 * @author beykery
 */
public class TestClient extends KcpClient
{

  public TestClient(int port)
  {
    super(port);
  }

  @Override
  public void handleReceive(ByteBuf bb, KcpOnUdp kcp)
  {
    String content = bb.toString(Charset.forName("utf-8"));
    System.out.println("msg:" + content + " from " + kcp);
    kcp.send(bb);
  }

  @Override
  public void handleException(Throwable ex)
  {
    System.out.println(ex.fillInStackTrace());
  }

  @Override
  public void handleClose(KcpOnUdp kcp)
  {
    System.out.println("服务器离开:" + kcp);
    System.out.println("waitSnd:" + kcp.getKcp().waitSnd());
    this.close();
  }

  public static void main(String[] args)
  {
    TestClient tc = new TestClient(2225);
    tc.noDelay(1, 10, 2, 1);
    tc.wndSize(64, 64);
    tc.setTimeout(10 * 1000);
    tc.connect(new InetSocketAddress("119.29.153.92", 2222));
    // tc.connect(new InetSocketAddress("localhost", 2222));
    tc.start();
    ByteBuf bb = PooledByteBufAllocator.DEFAULT.buffer(255);
    bb.writeBytes("abcd".getBytes());
    tc.send(bb);
  }
}
