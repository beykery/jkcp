/**
 * server for kcp
 */
package test;

import io.netty.buffer.ByteBuf;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import org.beykery.jkcp.KcpOnUdp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/**
 * 
 * @author beykery
 */
public class KcpServer extends KcpOnUdp
{

  private static final Logger LOG = LoggerFactory.getLogger(KcpServer.class);

  public static void main(String[] args) throws InterruptedException
  {
    KcpServer server = new KcpServer(2222);
    server.noDelay(1, 10, 2, 1);//fast
    server.wndSize(64, 64);//wnd
    while(true)
    {
      server.update();
      Thread.sleep(10);
    }
  }

  public KcpServer(int port)
  {
    super(port);
  }

  @Override
  protected void handleReceive(ByteBuf bb, InetSocketAddress addr)
  {
    System.out.println(bb.readableBytes());
    String content=bb.toString(Charset.forName("utf-8"));
    System.out.println("receive:"+content);
    
    this.send(bb, addr);//echo
  }

}
