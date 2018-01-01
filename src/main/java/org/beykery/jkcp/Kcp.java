/**
 *  KCP - A Better ARQ Protocol Implementation
 *  skywind3000 (at) gmail.com, 2010-2011
 *  Features:
 *  + Average RTT reduce 30% - 40% vs traditional ARQ like tcp.
 *  + Maximum RTT reduce three times vs tcp.
 *  + Lightweight, distributed as a single source file.
 */
package org.beykery.jkcp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import java.util.LinkedList;

/**
 *
 * @author beykery
 */
public class Kcp
{

    public static final int IKCP_RTO_NDL = 30;  // no delay min rto
    public static final int IKCP_RTO_MIN = 100; // normal min rto
    public static final int IKCP_RTO_DEF = 200;
    public static final int IKCP_RTO_MAX = 60000;
    public static final int IKCP_CMD_PUSH = 81; // cmd: push data
    public static final int IKCP_CMD_ACK = 82; // cmd: ack
    public static final int IKCP_CMD_WASK = 83; // cmd: window probe (ask)
    public static final int IKCP_CMD_WINS = 84; // cmd: window size (tell)
    public static final int IKCP_ASK_SEND = 1;  // need to send IKCP_CMD_WASK
    public static final int IKCP_ASK_TELL = 2;  // need to send IKCP_CMD_WINS
    public static final int IKCP_WND_SND = 32;
    public static final int IKCP_WND_RCV = 32;
    public static final int IKCP_MTU_DEF = 1400;
    public static final int IKCP_ACK_FAST = 3;
    public static final int IKCP_INTERVAL = 100;
    public static final int IKCP_OVERHEAD = 24;
    public static final int IKCP_DEADLINK = 10;
    public static final int IKCP_THRESH_INIT = 2;
    public static final int IKCP_THRESH_MIN = 2;
    public static final int IKCP_PROBE_INIT = 7000;   // 7 secs to probe window size
    public static final int IKCP_PROBE_LIMIT = 120000; // up to 120 secs to probe window

    private int conv;
    private int mtu;
    private int mss;
    private int state;
    private int snd_una;
    private int snd_nxt;
    private int rcv_nxt;
    private int ts_recent;
    private int ts_lastack;
    private int ssthresh;
    private int rx_rttval;
    private int rx_srtt;
    private int rx_rto;
    private int rx_minrto;
    private int snd_wnd;
    private int rcv_wnd;
    private int rmt_wnd;
    private int cwnd;
    private int probe;
    private int current;
    private int interval;
    private int ts_flush;
    private int xmit;
    private int nodelay;
    private int updated;
    private int ts_probe;
    private int probe_wait;
    private final int dead_link;
    private int incr;
    private final LinkedList<Segment> snd_queue = new LinkedList<>();
    private final LinkedList<Segment> rcv_queue = new LinkedList<>();
    private final LinkedList<Segment> snd_buf = new LinkedList<>();
    private final LinkedList<Segment> rcv_buf = new LinkedList<>();
    private final LinkedList<Integer> acklist = new LinkedList<>();
    private ByteBuf buffer;
    private int fastresend;
    private int nocwnd;
    private boolean stream;//流模式
    private final Output output;
    private final Object user;//远端地址
    private int nextUpdate;//the next update time.

    private static int _ibound_(int lower, int middle, int upper)
    {
        return Math.min(Math.max(lower, middle), upper);
    }

    private static int _itimediff(int later, int earlier)
    {
        return later - earlier;
    }

    /**
     * SEGMENT
     */
    class Segment
    {

        private int conv = 0;
        private byte cmd = 0;
        private int frg = 0;
        private int wnd = 0;
        private int ts = 0;
        private int sn = 0;
        private int una = 0;
        private int resendts = 0;
        private int rto = 0;
        private int fastack = 0;
        private int xmit = 0;
        private ByteBuf data;

        private Segment(int size)
        {
            if (size > 0)
            {
                this.data = PooledByteBufAllocator.DEFAULT.buffer(size);
            }
        }

        /**
         * encode a segment into buffer
         *
         * @param buf
         * @return
         */
        private int encode(ByteBuf buf)
        {
            int off = buf.writerIndex();
            buf.writeIntLE(conv);
            buf.writeByte(cmd);
            buf.writeByte(frg);
            buf.writeShortLE(wnd);
            buf.writeIntLE(ts);
            buf.writeIntLE(sn);
            buf.writeIntLE(una);
            buf.writeIntLE(data == null ? 0 : data.readableBytes());
            return buf.writerIndex() - off;
        }

        /**
         * 释放内存
         */
        private void release()
        {
            if (this.data != null && data.refCnt() > 0)
            {
                this.data.release(data.refCnt());
            }
        }
    }

    /**
     * create a new kcpcb
     *
     * @param output
     * @param user
     */
    public Kcp(Output output, Object user)
    {
        snd_wnd = IKCP_WND_SND;
        rcv_wnd = IKCP_WND_RCV;
        rmt_wnd = IKCP_WND_RCV;
        mtu = IKCP_MTU_DEF;
        mss = mtu - IKCP_OVERHEAD;
        rx_rto = IKCP_RTO_DEF;
        rx_minrto = IKCP_RTO_MIN;
        interval = IKCP_INTERVAL;
        ts_flush = IKCP_INTERVAL;
        ssthresh = IKCP_THRESH_INIT;
        dead_link = IKCP_DEADLINK;
        buffer = PooledByteBufAllocator.DEFAULT.buffer((mtu + IKCP_OVERHEAD) * 3);
        this.output = output;
        this.user = user;
    }

    /**
     * check the size of next message in the recv queue
     *
     * @return
     */
    public int peekSize()
    {
        if (rcv_queue.isEmpty())
        {
            return -1;
        }
        Segment seq = rcv_queue.getFirst();
        if (seq.frg == 0)
        {
            return seq.data.readableBytes();
        }
        if (rcv_queue.size() < seq.frg + 1)
        {
            return -1;
        }
        int length = 0;
        for (Segment item : rcv_queue)
        {
            length += item.data.readableBytes();
            if (item.frg == 0)
            {
                break;
            }
        }
        return length;
    }

    /**
     * user/upper level recv: returns size, returns below zero for EAGAIN
     *
     * @param buffer
     * @return
     */
    public int receive(ByteBuf buffer)
    {
        if (rcv_queue.isEmpty())
        {
            return -1;
        }
        int peekSize = peekSize();
        if (peekSize < 0)
        {
            return -2;
        }
        boolean recover = rcv_queue.size() >= rcv_wnd;
        // merge fragment.
        int c = 0;
        int len = 0;
        for (Segment seg : rcv_queue)
        {
            len += seg.data.readableBytes();
            buffer.writeBytes(seg.data);
            c++;
            if (seg.frg == 0)
            {
                break;
            }
        }
        if (c > 0)
        {
            for (int i = 0; i < c; i++)
            {
                rcv_queue.removeFirst().data.release();
            }
        }
        if (len != peekSize)
        {
            throw new RuntimeException("数据异常.");
        }
        // move available data from rcv_buf -> rcv_queue
        c = 0;
        for (Segment seg : rcv_buf)
        {
            if (seg.sn == rcv_nxt && rcv_queue.size() < rcv_wnd)
            {
                rcv_queue.add(seg);
                rcv_nxt++;
                c++;
            } else
            {
                break;
            }
        }
        if (c > 0)
        {
            for (int i = 0; i < c; i++)
            {
                rcv_buf.removeFirst();
            }
        }
        // fast recover
        if (rcv_queue.size() < rcv_wnd && recover)
        {
            // ready to send back IKCP_CMD_WINS in ikcp_flush
            // tell remote my window size
            probe |= IKCP_ASK_TELL;
        }
        return len;
    }

    /**
     * user/upper level send, returns below zero for error
     *
     * @param buffer
     * @return
     */
    public int send(ByteBuf buffer)
    {
        if (buffer.readableBytes() == 0)
        {
            return -1;
        }
        // append to previous segment in streaming mode (if possible)
        if (this.stream && !this.snd_queue.isEmpty())
        {
            Segment seg = snd_queue.getLast();
            if (seg.data != null && seg.data.readableBytes() < mss)
            {
                int capacity = mss - seg.data.readableBytes();
                int extend = (buffer.readableBytes() < capacity) ? buffer.readableBytes() : capacity;
                seg.data.writeBytes(buffer, extend);
                if (buffer.readableBytes() == 0)
                {
                    return 0;
                }
            }
        }
        int count;
        if (buffer.readableBytes() <= mss)
        {
            count = 1;
        } else
        {
            count = (buffer.readableBytes() + mss - 1) / mss;
        }
        if (count > 255)
        {
            return -2;
        }
        if (count == 0)
        {
            count = 1;
        }
        //fragment
        for (int i = 0; i < count; i++)
        {
            int size = buffer.readableBytes() > mss ? mss : buffer.readableBytes();
            Segment seg = new Segment(size);
            seg.data.writeBytes(buffer, size);
            seg.frg = this.stream ? 0 : count - i - 1;
            snd_queue.add(seg);
        }
        buffer.release();
        return 0;
    }

    /**
     * update ack.
     *
     * @param rtt
     */
    private void update_ack(int rtt)
    {
        if (rx_srtt == 0)
        {
            rx_srtt = rtt;
            rx_rttval = rtt / 2;
        } else
        {
            int delta = rtt - rx_srtt;
            if (delta < 0)
            {
                delta = -delta;
            }
            rx_rttval = (3 * rx_rttval + delta) / 4;
            rx_srtt = (7 * rx_srtt + rtt) / 8;
            if (rx_srtt < 1)
            {
                rx_srtt = 1;
            }
        }
        int rto = rx_srtt + Math.max(interval, 4 * rx_rttval);
        rx_rto = _ibound_(rx_minrto, rto, IKCP_RTO_MAX);
    }

    private void shrink_buf()
    {
        if (snd_buf.size() > 0)
        {
            snd_una = snd_buf.getFirst().sn;
        } else
        {
            snd_una = snd_nxt;
        }
    }

    private void parse_ack(int sn)
    {
        if (_itimediff(sn, snd_una) < 0 || _itimediff(sn, snd_nxt) >= 0)
        {
            return;
        }
        for (int i = 0; i < snd_buf.size(); i++)
        {
            Segment seg = snd_buf.get(i);
            if (sn == seg.sn)
            {
                snd_buf.remove(i);
                seg.data.release(seg.data.refCnt());
                break;
            }
            if (_itimediff(sn, seg.sn) < 0)
            {
                break;
            }
        }
    }

    private void parse_una(int una)
    {
        int c = 0;
        for (Segment seg : snd_buf)
        {
            if (_itimediff(una, seg.sn) > 0)
            {
                c++;
            } else
            {
                break;
            }
        }
        if (c > 0)
        {
            for (int i = 0; i < c; i++)
            {
                Segment seg = snd_buf.removeFirst();
                seg.data.release(seg.data.refCnt());
            }
        }
    }

    private void parse_fastack(int sn)
    {
        if (_itimediff(sn, snd_una) < 0 || _itimediff(sn, snd_nxt) >= 0)
        {
            return;
        }
        for (Segment seg : this.snd_buf)
        {
            if (_itimediff(sn, seg.sn) < 0)
            {
                break;
            } else if (sn != seg.sn)
            {
                seg.fastack++;
            }
        }
    }

    /**
     * ack append
     *
     * @param sn
     * @param ts
     */
    private void ack_push(int sn, int ts)
    {
        acklist.add(sn);
        acklist.add(ts);
    }

    private void parse_data(Segment newseg)
    {
        int sn = newseg.sn;
        if (_itimediff(sn, rcv_nxt + rcv_wnd) >= 0 || _itimediff(sn, rcv_nxt) < 0)
        {
            newseg.release();
            return;
        }
        int n = rcv_buf.size() - 1;
        int temp = -1;
        boolean repeat = false;
        for (int i = n; i >= 0; i--)
        {
            Segment seg = rcv_buf.get(i);
            if (seg.sn == sn)
            {
                repeat = true;
                break;
            }
            if (_itimediff(sn, seg.sn) > 0)
            {
                temp = i;
                break;
            }
        }
        if (!repeat)
        {
            if (temp == -1)
            {
                rcv_buf.addFirst(newseg);
            } else
            {
                rcv_buf.add(temp + 1, newseg);
            }
        } else
        {
            newseg.release();
        }
        // move available data from rcv_buf -> rcv_queue
        int c = 0;
        for (Segment seg : rcv_buf)
        {
            if (seg.sn == rcv_nxt && rcv_queue.size() < rcv_wnd)
            {
                rcv_queue.add(seg);
                rcv_nxt++;
                c++;
            } else
            {
                break;
            }
        }
        if (0 < c)
        {
            for (int i = 0; i < c; i++)
            {
                rcv_buf.removeFirst();
            }
        }
    }

    /**
     *
     * when you received a low level packet (eg. UDP packet), call it
     *
     * @param data
     * @return
     */
    public int input(ByteBuf data)
    {
        int una_temp = snd_una;
        int flag = 0, maxack = 0;
        if (data == null || data.readableBytes() < IKCP_OVERHEAD)
        {
            return -1;
        }
        while (true)
        {
            boolean readed = false;
            int ts;
            int sn;
            int len;
            int una;
            int conv_;
            int wnd;
            byte cmd;
            byte frg;
            if (data.readableBytes() < IKCP_OVERHEAD)
            {
                break;
            }
            conv_ = data.readIntLE();
            if (this.conv != conv_)
            {
                return -1;
            }
            cmd = data.readByte();
            frg = data.readByte();
            wnd = data.readShortLE();
            ts = data.readIntLE();
            sn = data.readIntLE();
            una = data.readIntLE();
            len = data.readIntLE();
            if (data.readableBytes() < len)
            {
                return -2;
            }
            switch ((int) cmd)
            {
                case IKCP_CMD_PUSH:
                case IKCP_CMD_ACK:
                case IKCP_CMD_WASK:
                case IKCP_CMD_WINS:
                    break;
                default:
                    return -3;
            }
            rmt_wnd = wnd & 0x0000ffff;
            parse_una(una);
            shrink_buf();
            switch (cmd)
            {
                case IKCP_CMD_ACK:
                    if (_itimediff(current, ts) >= 0)
                    {
                        update_ack(_itimediff(current, ts));
                    }
                    parse_ack(sn);
                    shrink_buf();
                    if (flag == 0)
                    {
                        flag = 1;
                        maxack = sn;
                    } else if (_itimediff(sn, maxack) > 0)
                    {
                        maxack = sn;
                    }
                    break;
                case IKCP_CMD_PUSH:
                    if (_itimediff(sn, rcv_nxt + rcv_wnd) < 0)
                    {
                        ack_push(sn, ts);
                        if (_itimediff(sn, rcv_nxt) >= 0)
                        {
                            Segment seg = new Segment(len);
                            seg.conv = conv_;
                            seg.cmd = cmd;
                            seg.frg = frg & 0x000000ff;
                            seg.wnd = wnd;
                            seg.ts = ts;
                            seg.sn = sn;
                            seg.una = una;
                            if (len > 0)
                            {
                                seg.data.writeBytes(data, len);
                                readed = true;
                            }
                            parse_data(seg);
                        }
                    }
                    break;
                case IKCP_CMD_WASK:
                    // ready to send back IKCP_CMD_WINS in Ikcp_flush
                    // tell remote my window size
                    probe |= IKCP_ASK_TELL;
                    break;
                case IKCP_CMD_WINS:
                    // do nothing
                    break;
                default:
                    return -3;
            }
            if (!readed)
            {
                data.skipBytes(len);
            }
        }
        if (flag != 0)
        {
            parse_fastack(maxack);
        }
        if (_itimediff(snd_una, una_temp) > 0)
        {
            if (this.cwnd < this.rmt_wnd)
            {
                if (this.cwnd < this.ssthresh)
                {
                    this.cwnd++;
                    this.incr += mss;
                } else
                {
                    if (this.incr < mss)
                    {
                        this.incr = mss;
                    }
                    this.incr += (mss * mss) / this.incr + (mss / 16);
                    if ((this.cwnd + 1) * mss <= this.incr)
                    {
                        this.cwnd++;
                    }
                }
                if (this.cwnd > this.rmt_wnd)
                {
                    this.cwnd = this.rmt_wnd;
                    this.incr = this.rmt_wnd * mss;
                }
            }
        }
        return 0;
    }

    private int wnd_unused()
    {
        if (rcv_queue.size() < rcv_wnd)
        {
            return rcv_wnd - rcv_queue.size();
        }
        return 0;
    }

    /**
     * force flush
     */
    public void forceFlush()
    {
        int cur = current;
        int change = 0;
        int lost = 0;
        Segment seg = new Segment(0);
        seg.conv = conv;
        seg.cmd = IKCP_CMD_ACK;
        seg.wnd = wnd_unused();
        seg.una = rcv_nxt;
        // flush acknowledges
        int c = acklist.size() / 2;
        for (int i = 0; i < c; i++)
        {
            if (buffer.readableBytes() + IKCP_OVERHEAD > mtu)
            {
                this.output.out(buffer, this, user);
                buffer = PooledByteBufAllocator.DEFAULT.buffer((mtu + IKCP_OVERHEAD) * 3);
            }
            seg.sn = acklist.get(i * 2 + 0);
            seg.ts = acklist.get(i * 2 + 1);
            seg.encode(buffer);
        }
        acklist.clear();
        // probe window size (if remote window size equals zero)
        if (rmt_wnd == 0)
        {
            if (probe_wait == 0)
            {
                probe_wait = IKCP_PROBE_INIT;
                ts_probe = current + probe_wait;
            } else if (_itimediff(current, ts_probe) >= 0)
            {
                if (probe_wait < IKCP_PROBE_INIT)
                {
                    probe_wait = IKCP_PROBE_INIT;
                }
                probe_wait += probe_wait / 2;
                if (probe_wait > IKCP_PROBE_LIMIT)
                {
                    probe_wait = IKCP_PROBE_LIMIT;
                }
                ts_probe = current + probe_wait;
                probe |= IKCP_ASK_SEND;
            }
        } else
        {
            ts_probe = 0;
            probe_wait = 0;
        }
        // flush window probing commands
        if ((probe & IKCP_ASK_SEND) != 0)
        {
            seg.cmd = IKCP_CMD_WASK;
            if (buffer.readableBytes() + IKCP_OVERHEAD > mtu)
            {
                this.output.out(buffer, this, user);
                buffer = PooledByteBufAllocator.DEFAULT.buffer((mtu + IKCP_OVERHEAD) * 3);
            }
            seg.encode(buffer);
        }
        // flush window probing commands
        if ((probe & IKCP_ASK_TELL) != 0)
        {
            seg.cmd = IKCP_CMD_WINS;
            if (buffer.readableBytes() + IKCP_OVERHEAD > mtu)
            {
                this.output.out(buffer, this, user);
                buffer = PooledByteBufAllocator.DEFAULT.buffer((mtu + IKCP_OVERHEAD) * 3);
            }
            seg.encode(buffer);
        }
        probe = 0;
        // calculate window size
        int cwnd_temp = Math.min(snd_wnd, rmt_wnd);
        if (nocwnd == 0)
        {
            cwnd_temp = Math.min(cwnd, cwnd_temp);
        }
        // move data from snd_queue to snd_buf
        c = 0;
        for (Segment item : snd_queue)
        {
            if (_itimediff(snd_nxt, snd_una + cwnd_temp) >= 0)
            {
                break;
            }
            Segment newseg = item;
            newseg.conv = conv;
            newseg.cmd = IKCP_CMD_PUSH;
            newseg.wnd = seg.wnd;
            newseg.ts = cur;
            newseg.sn = snd_nxt++;
            newseg.una = rcv_nxt;
            newseg.resendts = cur;
            newseg.rto = rx_rto;
            newseg.fastack = 0;
            newseg.xmit = 0;
            snd_buf.add(newseg);
            c++;
        }
        if (c > 0)
        {
            for (int i = 0; i < c; i++)
            {
                snd_queue.removeFirst();
            }
        }
        // calculate resent
        int resent = (fastresend > 0) ? fastresend : Integer.MAX_VALUE;
        int rtomin = (nodelay == 0) ? (rx_rto >> 3) : 0;
        // flush data segments
        for (Segment segment : snd_buf)
        {
            boolean needsend = false;
            if (segment.xmit == 0)
            {
                needsend = true;
                segment.xmit++;
                segment.rto = rx_rto;
                segment.resendts = cur + segment.rto + rtomin;
            } else if (_itimediff(cur, segment.resendts) >= 0)
            {
                needsend = true;
                segment.xmit++;
                xmit++;
                if (nodelay == 0)
                {
                    segment.rto += rx_rto;
                } else
                {
                    segment.rto += rx_rto / 2;
                }
                segment.resendts = cur + segment.rto;
                lost = 1;
            } else if (segment.fastack >= resent)
            {
                needsend = true;
                segment.xmit++;
                segment.fastack = 0;
                segment.resendts = cur + segment.rto;
                change++;
            }
            if (needsend)
            {
                segment.ts = cur;
                segment.wnd = seg.wnd;
                segment.una = rcv_nxt;
                int need = IKCP_OVERHEAD + segment.data.readableBytes();
                if (buffer.readableBytes() + need > mtu)
                {
                    this.output.out(buffer, this, user);
                    buffer = PooledByteBufAllocator.DEFAULT.buffer((mtu + IKCP_OVERHEAD) * 3);
                }
                segment.encode(buffer);
                if (segment.data.readableBytes() > 0)
                {
                    buffer.writeBytes(segment.data.duplicate());
                }
                if (segment.xmit >= dead_link)
                {
                    state = -1;
                }
            }
        }
        // flash remain segments
        if (buffer.readableBytes() > 0)
        {
            this.output.out(buffer, this, user);
            buffer = PooledByteBufAllocator.DEFAULT.buffer((mtu + IKCP_OVERHEAD) * 3);
        }
        // update ssthresh
        if (change != 0)
        {
            int inflight = snd_nxt - snd_una;
            ssthresh = inflight / 2;
            if (ssthresh < IKCP_THRESH_MIN)
            {
                ssthresh = IKCP_THRESH_MIN;
            }
            cwnd = ssthresh + resent;
            incr = cwnd * mss;
        }
        if (lost != 0)
        {
            ssthresh = cwnd / 2;
            if (ssthresh < IKCP_THRESH_MIN)
            {
                ssthresh = IKCP_THRESH_MIN;
            }
            cwnd = 1;
            incr = mss;
        }
        if (cwnd < 1)
        {
            cwnd = 1;
            incr = mss;
        }
    }

    /**
     * flush pending data
     */
    public void flush()
    {
        //if (updated != 0)
        {
            forceFlush();
        }
    }

    /**
     * update state (call it repeatedly, every 10ms-100ms), or you can ask
     * ikcp_check when to call it again (without ikcp_input/_send calling).
     *
     * @param current current timestamp in millisec.
     */
    public void update(long current)
    {
        this.current = (int) current;
        if (updated == 0)
        {
            updated = 1;
            ts_flush = this.current;
        }
        int slap = _itimediff(this.current, ts_flush);
        if (slap >= 10000 || slap < -10000)
        {
            ts_flush = this.current;
            slap = 0;
        }
        if (slap >= 0)
        {
            ts_flush += interval;
            if (_itimediff(this.current, ts_flush) >= 0)
            {
                ts_flush = this.current + interval;
            }
            flush();
        }
    }

    /**
     * Determine when should you invoke ikcp_update: returns when you should
     * invoke ikcp_update in millisec, if there is no ikcp_input/_send calling.
     * you can call ikcp_update in that time, instead of call update repeatly.
     * Important to reduce unnacessary ikcp_update invoking. use it to schedule
     * ikcp_update (eg. implementing an epoll-like mechanism, or optimize
     * ikcp_update when handling massive kcp connections)
     *
     * @param current
     * @return
     */
    public int check(long current)
    {
        int cur = (int) current;
        if (updated == 0)
        {
            return cur;
        }
        int ts_flush_temp = this.ts_flush;
        int tm_packet = 0x7fffffff;
        if (_itimediff(cur, ts_flush_temp) >= 10000 || _itimediff(cur, ts_flush_temp) < -10000)
        {
            ts_flush_temp = cur;
        }
        if (_itimediff(cur, ts_flush_temp) >= 0)
        {
            return cur;
        }
        int tm_flush = _itimediff(ts_flush_temp, cur);
        for (Segment seg : snd_buf)
        {
            int diff = _itimediff(seg.resendts, cur);
            if (diff <= 0)
            {
                return cur;
            }
            if (diff < tm_packet)
            {
                tm_packet = diff;
            }
        }
        int minimal = tm_packet < tm_flush ? tm_packet : tm_flush;
        if (minimal >= interval)
        {
            minimal = interval;
        }
        return cur + minimal;
    }

    /**
     * change MTU size, default is 1400
     *
     * @param mtu
     * @return
     */
    public int setMtu(int mtu)
    {
        if (mtu < 50 || mtu < IKCP_OVERHEAD)
        {
            return -1;
        }
        ByteBuf buf = PooledByteBufAllocator.DEFAULT.buffer((mtu + IKCP_OVERHEAD) * 3);
        this.mtu = mtu;
        mss = mtu - IKCP_OVERHEAD;
        if (buffer != null)
        {
            buffer.release();
        }
        this.buffer = buf;
        return 0;
    }

    /**
     * conv
     *
     * @param conv
     */
    public void setConv(int conv)
    {
        this.conv = conv;
    }

    /**
     * conv
     *
     * @return
     */
    public int getConv()
    {
        return conv;
    }

    /**
     * interval per update
     *
     * @param interval
     * @return
     */
    public int interval(int interval)
    {
        if (interval > 5000)
        {
            interval = 5000;
        } else if (interval < 10)
        {
            interval = 10;
        }
        this.interval = interval;
        return 0;
    }

    /**
     * fastest: ikcp_nodelay(kcp, 1, 20, 2, 1) nodelay: 0:disable(default),
     * 1:enable interval: internal update timer interval in millisec, default is
     * 100ms resend: 0:disable fast resend(default), 1:enable fast resend nc:
     * 0:normal congestion control(default), 1:disable congestion control
     *
     * @param nodelay
     * @param interval
     * @param resend
     * @param nc
     * @return
     */
    public int noDelay(int nodelay, int interval, int resend, int nc)
    {
        if (nodelay >= 0)
        {
            this.nodelay = nodelay;
            if (nodelay != 0)
            {
                rx_minrto = IKCP_RTO_NDL;
            } else
            {
                rx_minrto = IKCP_RTO_MIN;
            }
        }
        if (interval >= 0)
        {
            if (interval > 5000)
            {
                interval = 5000;
            } else if (interval < 10)
            {
                interval = 10;
            }
            this.interval = interval;
        }
        if (resend >= 0)
        {
            fastresend = resend;
        }
        if (nc >= 0)
        {
            nocwnd = nc;
        }
        return 0;
    }

    /**
     * set maximum window size: sndwnd=32, rcvwnd=32 by default
     *
     * @param sndwnd
     * @param rcvwnd
     * @return
     */
    public int wndSize(int sndwnd, int rcvwnd)
    {
        if (sndwnd > 0)
        {
            snd_wnd = sndwnd;
        }
        if (rcvwnd > 0)
        {
            rcv_wnd = rcvwnd;
        }
        return 0;
    }

    /**
     * get how many packet is waiting to be sent
     *
     * @return
     */
    public int waitSnd()
    {
        return snd_buf.size() + snd_queue.size();
    }

    public void setNextUpdate(int nextUpdate)
    {
        this.nextUpdate = nextUpdate;
    }

    public int getNextUpdate()
    {
        return nextUpdate;
    }

    public Object getUser()
    {
        return user;
    }

    public boolean isStream()
    {
        return stream;
    }

    public void setStream(boolean stream)
    {
        this.stream = stream;
    }

    public void setMinRto(int min)
    {
        rx_minrto = min;
    }

    @Override
    public String toString()
    {
        return this.user.toString();
    }

    /**
     * 释放内存
     */
    void release()
    {
        if (buffer.refCnt() > 0)
        {
            this.buffer.release(buffer.refCnt());
        }
        for (Segment seg : this.rcv_buf)
        {
            seg.release();
        }
        for (Segment seg : this.rcv_queue)
        {
            seg.release();
        }
        for (Segment seg : this.snd_buf)
        {
            seg.release();
        }
        for (Segment seg : this.snd_queue)
        {
            seg.release();
        }
    }
}
