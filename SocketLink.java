package com.example.vgamepad;

import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;

/**
 * Talks to GpadDaemon over localhost TCP.
 *
 * NOTE: Android requires android.permission.INTERNET even for 127.0.0.1.
 * Without it, new Socket(...) throws SocketException: Permission denied,
 * which is why the UI used to show "未连接" while the daemon was fine.
 */
public class SocketLink {

    private static final String TAG = "VGamepad";
    private static final String HOST = "127.0.0.1";
    private static final int PORT = GpadDaemon.PORT;

    private volatile Socket mSocket;
    private volatile OutputStream mOut;
    private volatile String mLastError;
    private volatile long mLastTry;
    private volatile int mSentCount;
    private volatile int mDaemonVersion = -1;
    /**
     * 对端已经关了（或写失败）。
     *
     * 【为什么必须单独记一个标志】
     *   Socket.isConnected() 只表示"曾经连上过"，Socket.isClosed()
     *   只表示"本地有没有 close()"。两个都**检测不到对端关闭**：
     *   守护进程重启 / 崩掉 / 被挤掉之后，本地 socket 依然是
     *   isConnected()==true && isClosed()==false。
     *
     *   以前 isConnected() 只看这两项，于是 app 抱着一个死连接
     *   一直返回 true —— 每秒的巡检认为"还连着"、从不重连，
     *   命令全部写进一条已经没人读的管道，按键彻底没反应，
     *   界面上却仍然显示绿点"已连接"。
     */
    private volatile boolean mRemoteClosed;
    /** 断线次数（诊断用，能在界面上看出"它断过"）。 */
    private volatile int mDropCount;
    private final Object mLock = new Object();

    private static final long RETRY_MS = 1500;

    /** All writes happen here so the UI thread never blocks on the socket. */
    private final java.util.concurrent.ExecutorService mSender =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    /** Close only the socket. Safe to call repeatedly; keeps the sender alive. */
    private void disconnect() {
        synchronized (mLock) {
            if (mSocket != null) {
                try {
                    mSocket.close();
                } catch (IOException ignored) {
                }
            }
            mSocket = null;
            mOut = null;
            mRemoteClosed = false;
        }
    }

    /** 断线次数。界面上能看出"这条连接断过几次"。 */
    public int getDropCount() {
        return mDropCount;
    }

    public void connect() throws IOException {
        disconnect();
        Socket s = new Socket(HOST, PORT);
        synchronized (mLock) {
            mSocket = s;
            mOut = s.getOutputStream();
        }
        mLastError = null;
        // 问一次守护进程的映射版本。它带着旧的 HID 描述符在跑时，
        // 设备语义还是旧的 —— 这种不一致必须让用户看见，否则会以为是代码坏了。
        mDaemonVersion = handshake(s);
        Log.i(TAG, "connected to daemon, protocol=" + mDaemonVersion);
        //
        // 【握手之后才起读线程】
        //   handshake 自己也要读一行（"VGPAD 4"），读线程如果同时读
        //   同一个 InputStream 会把这行抢走，握手就永远问不到版本号。
        //   所以顺序必须是：先握手、再交给读线程。
        startReader(s);
    }

    /**
     * 起一个只读不处理数据的线程，用途是**检测对端关闭**。
     *
     * 守护进程平时不会主动往这边发东西，所以这个线程基本一直阻塞在
     * readLine() 上；一旦对端关闭（进程重启 / 被杀 / 我们这边被挤掉），
     * readLine() 立刻返回 null（或抛异常），我们就能马上知道"断了"。
     *
     * 这是唯一能可靠发现"对端没了"的办法 —— 见 mRemoteClosed 的说明。
     */
    private void startReader(final Socket s) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader r = new BufferedReader(
                            new InputStreamReader(s.getInputStream(), "UTF-8"));
                    while (r.readLine() != null) {
                        // 守护进程只在被问 "?" 时回话，那种读已经被
                        // handshake 拿走了。这里读到什么都不处理，
                        // 只为等那个"返回 null = 对端关了"的瞬间。
                    }
                    onRemoteClose(s);
                } catch (Exception e) {
                    // socket.close() 打断阻塞读也会走到这里
                    onRemoteClose(s);
                }
            }
        });
        t.setDaemon(true);
        t.setName("vgpad-reader");
        t.start();
    }

    /**
     * 确认这条连接死了。
     *
     * 【为什么要把 mLastTry 清成 0】
     *   ensureConnected() 有 1.5 秒的重连节流，靠 mLastTry 记上次尝试时间。
     *   刚断那一会儿 mLastTry 很可能是刚才的值，不清零就得白等最多 1.5 秒；
     *   清零之后下一次巡检会立刻重连。
     */
    private void onRemoteClose(Socket s) {
        synchronized (mLock) {
            // 只认"还是我这一代 socket"的断开。
            // 旧连接被替换 / 主动 disconnect 时，上一代读线程也会走到这里，
            // 不判的话它会把新连接误标成已断。
            if (mSocket != s) {
                return;
            }
            mRemoteClosed = true;
        }
        mDropCount++;
        mLastTry = 0L;
        Log.w(TAG, "daemon closed the connection (drop #" + mDropCount + "), will reconnect");
    }

    /**
     * 返回守护进程报告的协议版本，问不到就返回 -1。
     *
     * 【为什么重试一次】
     *   守护进程那边可能正忙着（刚 accept 完、还在关上一个连接），
     *   第一次 "?" 赶在它没准备好的时候发过去就没了下文。
     *   重试一次能挡掉这种"刚好错开"的偶发失败 ——
     *   它表现出来就是"连上了但问不到版本号"，很容易被误判成旧进程。
     */
    private static int handshake(Socket s) {
        int v = askVersion(s, 2500);
        if (v >= 0) {
            return v;
        }
        try {
            Thread.sleep(300);
        } catch (InterruptedException ignored) {
        }
        return askVersion(s, 2500);
    }

    /** 问一次版本号。 */
    private static int askVersion(Socket s, int timeoutMs) {
        try {
            s.setSoTimeout(timeoutMs);
            s.getOutputStream().write("?\n".getBytes("UTF-8"));
            s.getOutputStream().flush();
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), "UTF-8"));
            String line = r.readLine();
            s.setSoTimeout(0);
            if (line != null) {
                line = line.trim();
                // 容忍 "VGPAD 4"、"VGPAD4"、"VGPAD  4" 这类写法差异，
                // 别因为一个空格就把"问到了版本"判成"问不到"。
                if (line.startsWith("VGPAD")) {
                    String num = line.substring(5).trim();
                    if (num.length() > 0) {
                        return Integer.parseInt(num);
                    }
                }
            }
            return -1;
        } catch (Exception e) {
            // 旧版守护进程不认识 "?"，会一直不回 —— 超时就当它未知
            try {
                s.setSoTimeout(0);
            } catch (Exception ignored) {
            }
            return -1;
        }
    }

    /** 守护进程的映射协议版本，-1 = 问不到（通常是旧版守护进程）。 */
    public int getDaemonVersion() {
        return mDaemonVersion;
    }

    /**
     * Called before every send. If the daemon was started after us, this
     * reconnects automatically instead of silently dropping every command.
     */
    public boolean ensureConnected() {
        if (isConnected()) return true;
        long now = System.currentTimeMillis();
        if (now - mLastTry < RETRY_MS) return false;
        mLastTry = now;
        try {
            connect();
            return true;
        } catch (IOException e) {
            mLastError = describe(e);
            return false;
        }
    }

    /** Keep the exception class name: "Permission denied" alone is easy to misread. */
    private static String describe(IOException e) {
        String n = e.getClass().getSimpleName();
        String m = e.getMessage();
        if (m == null) m = "";
        if (m.contains("Permission denied") || m.contains("EACCES") || m.contains("EPERM")) {
            return n + ": " + m + " (缺少 INTERNET 权限?)";
        }
        return n + ": " + m;
    }

    public String getLastError() {
        return mLastError;
    }

    /** Record an error from outside (e.g. the initial connect attempt). */
    public void setLastError(String msg) {
        mLastError = msg;
    }

    public boolean isConnected() {
        synchronized (mLock) {
            return mSocket != null && mSocket.isConnected() && !mSocket.isClosed()
                    && !mRemoteClosed;
        }
    }

    public void send(final String cmd) {
        if (!ensureConnected()) {
            return;
        }
        try {
            mSender.execute(new Runnable() {
                @Override
                public void run() {
                    synchronized (mLock) {
                        if (mOut == null) return;
                        try {
                            mOut.write((cmd + "\n").getBytes("UTF-8"));
                            mOut.flush();
                            mSentCount++;
                        } catch (IOException e) {
                            mLastError = e.getMessage();
                            Log.w(TAG, "send failed", e);
                            // 写不进去 = 这条连接已经废了。
                            // 不标死的话它会被当成"还连着"，之后的命令继续往里写、
                            // 继续失败，界面却一直显示已连接。
                            onRemoteClose(mSocket);
                        }
                    }
                }
            });
        } catch (RuntimeException e) {
            mLastError = "sender rejected: " + e.getMessage();
            Log.w(TAG, "sender rejected", e);
        }
    }

    public int getSentCount() {
        return mSentCount;
    }

    public void button(int index, boolean pressed) {
        send("b " + index + " " + (pressed ? "1" : "0"));
    }

    public void axis(int axis, float value) {
        send("a " + axis + " " + value);
    }

    public void hat(int hat) {
        send("h " + hat);
    }

    public void trigger(int trigger, float value) {
        send("t " + trigger + " " + value);
    }

    /**
     * 键盘按键。
     *
     * @param usage HID keyboard usage（0x04='A' ... 0x2C=空格，
     *              0xE0=LCtrl ...）—— 不是 Android keycode。
     *              守护进程直接把它填进键盘报告的 bitmap（NKRO，无 6 键上限），
     *              中间不做任何转换，也就没有转换错的机会。
     */
    public void key(int usage, boolean pressed) {
        send("k " + usage + " " + (pressed ? "1" : "0"));
    }

    /**
     * 鼠标相对移动。
     *
     * 【为什么是 int 而不是 float】
     *   MouseReport 里位移是 int8（-127..127），传小数没有意义。
     *   而且这里是增量：发一条就走一段，丢一条就少走一段，
     *   和 axis() 那种"当前位置"的语义完全不同，别混用。
     */
    public void mouseMove(int dx, int dy) {
        send("m " + dx + " " + dy);
    }

    /** 鼠标按键：0=左 1=右 2=中 3/4=侧键。 */
    public void mouseButton(int btn, boolean pressed) {
        send("c " + btn + " " + (pressed ? "1" : "0"));
    }

    /**
     * 鼠标滚轮。复用 m 命令的第三个参数：
     * daemon 那边 "m dx dy wheel"，传 0 位移只带滚轮就是纯滚动。
     */
    public void mouseWheel(int notches) {
        send("m 0 0 " + notches);
    }

    public void reset() {
        send("r");
    }

    /** Full teardown. Only call this when the service is being destroyed. */
    public void close() {
        disconnect();
        mSender.shutdownNow();
    }
}
