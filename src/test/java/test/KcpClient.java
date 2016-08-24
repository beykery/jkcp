/**
 * kcp client
 */
package test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import org.beykery.jkcp.KcpOnUdp;

/**
 *
 * @author beykery
 */
public class KcpClient extends KcpOnUdp
{

  public static void main(String[] args) throws InterruptedException
  {
    KcpClient client = new KcpClient(2224);//bind
    client.noDelay(1, 10, 2, 1);//fast
    client.wndSize(64, 64);//wnd
    InetSocketAddress addr = new InetSocketAddress("localhost", 2222);//send to 2222
    String test = "hi,你好,八格牙路!";
    ByteBuf bb = PooledByteBufAllocator.DEFAULT.buffer(512);
    bb.writeBytes(test.getBytes(Charset.forName("utf-8")));
    client.send(bb, addr);
    while (true)
    {
      client.update();
      Thread.sleep(10);
    }
  }

  public KcpClient(int port)
  {
    super(port);
  }

  @Override
  protected void handleReceive(ByteBuf bb, InetSocketAddress addr)
  {
    String content = bb.toString(Charset.forName("utf-8"));
    System.out.println("receive:" + content);
    this.send(bb, addr);//echo
  }

}
