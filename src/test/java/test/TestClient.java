/**
 * 测试
 */
package test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import static org.beykery.jkcp.Kcp.IKCP_OVERHEAD;
import org.beykery.jkcp.KcpOnUdp;
import org.beykery.jkcp.KcpServer;

/**
 *
 * @author beykery
 */
public class TestClient extends KcpServer
{

  public TestClient(int port, int workerSize)
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
    TestClient s = new TestClient(2223, 1);
    s.noDelay(1, 10, 2, 1);
    s.wndSize(64, 64);
    s.setTimeout(10 * 1000);
    InetSocketAddress addr = new InetSocketAddress("localhost",2222);
    s.connect(addr);
    s.start();
    
    ByteBuf bb = PooledByteBufAllocator.DEFAULT.buffer(100);
    bb.writeBytes("aabc".getBytes());
    s.send(bb, addr);
  }
}
