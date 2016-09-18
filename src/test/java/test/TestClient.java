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

  int pre = -1;

  @Override
  public void handleReceive(ByteBuf bb, KcpOnUdp kcp)
  {
    String content = bb.toString(Charset.forName("utf-8"));
    System.out.println("msg:" + content + " from " + kcp);
    int index=content.indexOf('a');
    if(index<0)//失败,一半消息
    {
      System.out.println("error..........");
      bb.release();
      return;
    }
    int t = Integer.parseInt(content.substring(0, index));
    if (t != pre + 1)
    {
      System.out.println("error!...............");
    }
    pre = t;
    ByteBuf buf=PooledByteBufAllocator.DEFAULT.buffer(2048);
    buf.writeBytes(String.valueOf(t+1).getBytes());
    buf.writeBytes(content.substring(index).getBytes());
    kcp.send(buf);
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
    System.out.println("服务器离开:" + kcp);
    System.out.println("waitSnd:" + kcp.getKcp().waitSnd());
    this.close();
  }

  /**
   * tcpdump udp port 2225 -x -vv -s0 -w 1112.pcap
   *
   * @param args
   */
  public static void main(String[] args)
  {
    TestClient tc = new TestClient(2225);
    tc.noDelay(1, 10, 2, 1);
    tc.wndSize(64, 64);
    tc.setTimeout(10 * 1000);
    tc.setMtu(1000);
    tc.connect(new InetSocketAddress("119.29.153.92", 2222));
    //tc.connect(new InetSocketAddress("localhost", 2222));
    tc.start();
    ByteBuf bb = PooledByteBufAllocator.DEFAULT.buffer(1500);
    bb.writeBytes(String.valueOf(0).getBytes());
    int len = 1500;
    StringBuilder sb = new StringBuilder();
    sb.append('a');
    for (int i = 0; i < len - 2 - 4; i++)
    {
      sb.append('c');
    }
    sb.append('z');
    bb.writeBytes(sb.toString().getBytes());
    tc.send(bb);
  }
}
