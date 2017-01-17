/**
 * 客户端
 */
package test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.util.ResourceLeakDetector;
import java.net.InetSocketAddress;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import org.beykery.jkcp.Kcp;
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
    System.out.println(content);
    ByteBuf buf = PooledByteBufAllocator.DEFAULT.buffer(2048);
    buf.writeBytes(content.getBytes(Charset.forName("utf-8")));
    kcp.send(buf);
    bb.release();
  }

  @Override
  public void handleException(Throwable ex, KcpOnUdp kcp)
  {
    System.out.println(ex);
  }

  @Override
  public void handleClose(KcpOnUdp kcp)
  {
    super.handleClose(kcp);
    System.out.println("服务器离开:" + kcp);
    System.out.println("waitSnd:" + kcp.getKcp().waitSnd());
  }

  @Override
  public void out(ByteBuf msg, Kcp kcp, Object user)
  {
    super.out(msg, kcp, user);
  }

  /**
   * tcpdump udp port 2225 -x -vv -s0 -w 1112.pcap
   *
   * @param args
   * @throws java.lang.InterruptedException
   */
  public static void main(String[] args) throws InterruptedException
  {
    ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.ADVANCED);
    TestClient tc = new TestClient(2225);
    tc.noDelay(1, 20, 2, 1);
    tc.wndSize(32, 32);
    tc.setTimeout(10 * 1000);
    tc.setMtu(512);
    tc.setConv(121106);
    tc.setOrder(ByteOrder.BIG_ENDIAN);
    tc.connect(new InetSocketAddress("localhost", 2222));
    tc.start();
    String content = "sdfkasd你好。。。。。。。";
    ByteBuf bb = PooledByteBufAllocator.DEFAULT.buffer(1500);
    bb.writeBytes(content.getBytes(Charset.forName("utf-8")));
    tc.send(bb);
  }
}
