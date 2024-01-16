/**
 * 测试
 */
package test;

import io.netty.buffer.ByteBuf;

import java.nio.charset.Charset;

import org.beykery.jkcp.KcpOnUdp;
import org.beykery.jkcp.KcpServer;

/**
 * @author beykery
 */
public class TestServer extends KcpServer {

    public TestServer(int port, int workerSize) {
        super(port, workerSize);
    }

    @Override
    public void handleReceive(ByteBuf bb, KcpOnUdp kcp) {
        if (c == 0) {
            start = System.currentTimeMillis();
        }
        c++;
        String content = bb.toString(Charset.forName("utf-8"));
        System.out.println("msg:" + content + " kcp--> " + kcp);
        if (c < 10000) {
            kcp.send(bb);//echo
        } else {
            System.out.println("cost:" + (System.currentTimeMillis() - start));
            kcp.close();
        }
    }

    @Override
    public void handleException(Throwable ex, KcpOnUdp kcp) {
        System.out.println(ex);
    }

    @Override
    public void handleClose(KcpOnUdp kcp) {
        System.out.println("客户端离开:" + kcp);
        System.out.println("waitSnd:" + kcp.getKcp().waitSnd());
    }

    private static long start;
    private static int c = 0;

    /**
     * 测试
     *
     * @param args
     */
    public static void main(String[] args) {
        TestServer s = new TestServer(2222, 1);
        s.noDelay(1, 10, 2, 1);
        s.setMinRto(10);
        s.wndSize(64, 64);
        s.setTimeout(10 * 1000);
        s.setMtu(512);
        s.start();
    }
}
