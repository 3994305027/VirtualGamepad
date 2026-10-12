package com.example.vgamepad;

import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

import uhid.purejava.Device;

/**
 * Runs as uid 2000 (adb shell) or 0 (root) via:
 *   CLASSPATH=<your.apk> app_process / com.example.vgamepad.GpadDaemon
 *
 * Creates the UHID gamepad, then listens on 127.0.0.1:19820 for text commands:
 *   b <index> <0|1>     button
 *   a <axis> <float>    axis 0=X 1=Y 2=Z 3=Rz, range -1.0..1.0
 *   h <hat>             hat 0..7, 8 = neutral
 *   t <trigger> <float> trigger 0=L2 1=R2, range 0.0..1.0
 *   r                   reset all
 * Every line is followed by an immediate report push.
 */
public class GpadDaemon {

    private static final String TAG = "VGamepad";
    public static final int PORT = 19820;

    /**
     * HID 映射协议版本。改过 GamepadReport 的报告布局 / 描述符就 +1。
     *
     * 描述符是创建 /dev/uhid 设备时一次性注册的，改了代码必须重启守护进程才生效，
     * 光重装 app 没用。app 连上后会发 "?" 问一次版本，对不上就在浮层上橙字提示。
     *
     * 但这个自检的能力边界必须搞清楚，别指望它：
     *   app 里这个常量和 daemon 里的在同一个 APK 里编译，两者天生相等。
     *   所以它只能抓到"新 app + 旧进程（pkill 没杀干净）"这一种情况；
     *   "APK 压根没重新编译安装"它永远抓不到 —— 那段检查代码只在新 APK 里，
     *   没装上去就是不存在，不可能自己提示自己。
     * 判断是否跑的新代码，请用终端横幅里的 protocol 和设备名（见 main 里的输出），
     * 那两条都不依赖 app。
     *   1 = 初版（Z/Rz 当右摇杆，按钮顺排）
     *   2 = Rx/Ry 当右摇杆，按钮位号对齐 Xbox 布局（错的，见下）
     *   3 = 按 Android CDD 排：右摇杆 = Z/Rz，扳机 = Brake/Accelerator，
     *       VID/PID 换成没有厂商 kl 的通用 id（修 L2/R2 没反应）
     *   4 = 新增**第二个 uhid 设备**（虚拟键盘）+ k 命令。
     *       手柄那份描述符一个字节都没动，所以已验证的映射不受影响；
     *       升版本只是为了让 app 能识别出"旧进程不支持键盘按键"。
     *   5 = 键盘从 boot 协议的 6 键 Array 换成 **NKRO bitmap**（30 字节报告）。
     *       这是唯一一个"会改变键盘设备能力"的版本：6 键上限没了，
     *       换成一位一键，理论上可全键无冲。
     *       手柄那份描述符仍然一个字节没动。
     *   6 = 新增**第三个 uhid 设备**（虚拟鼠标）+ m / c 命令。
     *       手柄和键盘两份描述符仍然一个字节都没动。
     *
     *   【为什么必须升版本 + 必须重启守护进程】
     *     描述符是建 /dev/uhid 设备时一次性注册的，改了不重启就是旧布局。
     *     而且这次 app 和守护进程对"键盘报告有几个字节"的理解会不一致 ——
     *     旧进程收到新 app 的 usage 会照旧往 6 格里塞，第 7 个键照样丢。
     *     所以版本必须对上，对不上就在界面上橙字提示。
     */
    public static final int PROTOCOL_VERSION = 6;

    /**
     * 不能用 Xbox 360 的 045E:028E。
     *
     * Android 自带 /system/usr/keylayout/Vendor_045e_Product_028e.kl，
     * 系统看到这个 VID/PID 就会加载它而不是 Generic.kl，后果有两个：
     *   1) 那份文件里没有 BTN_TL2(312) / BTN_TR2(313) 这两行，
     *      L2 / R2 按下后根本不产生任何 Android 按键 —— 这就是"L2/R2 按了没反应"。
     *   2) 它把 Z/Rz 重映射成 LTRIGGER/RTRIGGER（因为是给 xpad 布局用的），
     *      于是写在 Z/Rz 上的扳机、写在 Rx/Ry 上的右摇杆全靠这份文件兜着，
     *      换台没有这个文件的机器映射就整个翻过来。
     *
     * 换成一个任何 ROM 都不带 kl 的 id，系统必然回落到 Generic.kl，
     * 映射才是确定的（Generic.kl 里 312/313 是有的，对应 BUTTON_L2 / BUTTON_R2）。
     */
    private static final int VID = 0x1D6B;   // Linux Foundation（没有厂商 kl）
    private static final int PID = 0x0104;   // 没有厂商 kl 的产品号
    private static final int BUS_USB = 0x03;

    /** The connection currently being served, so a reconnect replaces the old one. */
    private static Socket sCurrent;

    /** 已经确认过"同时超过 6 键"这件事（诊断只打一次，见 serve 的 k 分支）。 */
    private static boolean sNkroNoticed;

    public static void main(String[] args) {
        int uid = android.os.Process.myUid();
        if (uid != 0 && uid != 2000) {
            System.err.println("Insufficient permission! Need adb (2000) or root (0), got " + uid);
            System.exit(255);
            return;
        }

        final GamepadReport report = new GamepadReport();
        // 报告长度取决于模式，必须在建设备之前定好（见下面 --kbd-boot）
        final boolean kbdBootMode = hasArg(args, "--kbd-boot");
        final KeyboardReport kbdReport = new KeyboardReport(kbdBootMode);
        Device device;
        Device kbdDevice;
        Device mouseDevice;
        final MouseReport mouseReport = new MouseReport();
        // 设备名带上协议版本号。
        //
        // 这是唯一不依赖 app 的"我跑的是哪一版"证据：
        //   app 里的版本自检只能发现"新 app 连上旧进程"，
        //   而"APK 根本没重新编译"这种情况它永远发现不了
        //   （那段检查代码本身就只存在于新 APK 里）。
        // 版本号写进设备名之后，用
        //   getevent -l / dumpsys input / cat /proc/bus/input/devices
        // 都能直接看到，跟 app 装没装、是不是新版完全无关。
        //
        // 附带好处：系统会按设备名找 Virtual_Gamepad_v3.kl，找不到，
        // 必然回落到 Generic.kl —— 正是我们想要的那份。
        final String devName = "Virtual Gamepad v" + PROTOCOL_VERSION;
        try {
            device = new Device(
                    1,
                    devName,
                    "vgamepad-p" + PROTOCOL_VERSION,
                    VID, PID, BUS_USB,
                    GamepadReport.DESCRIPTOR,
                    report.buffer(),
                    null,
                    null);
        } catch (IOException e) {
            System.err.println("cannot create uhid device: " + e);
            Log.e(TAG, "cannot create uhid device", e);
            System.exit(1);
            return;
        }

        // 第二个 uhid 设备：虚拟键盘。
        //
        // 【为什么不把手柄描述符改大】手柄那份已经实机验证过了，
        //   往里塞键盘 usage 要改报告布局，等于把验证过的东西推倒重来。
        //   单独开一个设备，手柄那边一个字节都不用动。
        //
        // 设备名同样带版本号，且和手柄不同名 —— 系统会按名字找 kl，
        // 找不到就回落 Generic.kl（我们正需要它那份键盘映射）。
        //
        // 【--kbd-boot：回退到 6KRO boot 布局】
        //   NKRO bitmap 不是 boot 协议，个别机器的 hid 栈可能处理不了。
        //   留这个参数当退路 —— 键盘还能用（只是回到最多 6 键），
        //   不至于整个键盘设备废掉。
        final String kbdName = "Virtual Gamepad Keys v" + PROTOCOL_VERSION
                + (kbdBootMode ? " (boot6)" : " (nkro)");
        try {
            kbdDevice = new Device(
                    2,
                    kbdName,
                    "vgamepad-keys-p" + PROTOCOL_VERSION,
                    VID, PID, BUS_USB,
                    kbdBootMode ? KeyboardReport.DESCRIPTOR_BOOT
                            : KeyboardReport.DESCRIPTOR,
                    kbdReport.buffer(),
                    null,
                    null);
        } catch (IOException e) {
            System.err.println("cannot create uhid keyboard: " + e);
            Log.e(TAG, "cannot create uhid keyboard", e);
            // 手柄已经建好了，不关掉会留一个没人管的僵尸设备
            try {
                device.close();
            } catch (Exception ignored) {
            }
            System.exit(1);
            return;
        }

        // 第三个 uhid 设备：虚拟鼠标。
        //
        // 【同样是单独开一个设备】手柄和键盘那份已验证的描述符一个字节都不动，
        //   鼠标的按键/轴语义完全不同（相对位移 vs 绝对轴），塞不进同一份报告。
        //
        // 【为什么用 mouse 而不是 touch】
        //   系统靠 BTN_LEFT + REL_X/REL_Y 把它识别成 cursor 设备，
        //   指针由 system_server 的 PointerController 画 —— 这才是"真鼠标"。
        final String mouseName = "Virtual Gamepad Mouse v" + PROTOCOL_VERSION;
        try {
            mouseDevice = new Device(
                    3,
                    mouseName,
                    "vgamepad-mouse-p" + PROTOCOL_VERSION,
                    VID, PID, BUS_USB,
                    MouseReport.DESCRIPTOR,
                    mouseReport.buffer(),
                    null,
                    null);
        } catch (IOException e) {
            System.err.println("cannot create uhid mouse: " + e);
            Log.e(TAG, "cannot create uhid mouse", e);
            try {
                device.close();
            } catch (Exception ignored) {
            }
            try {
                kbdDevice.close();
            } catch (Exception ignored) {
            }
            System.exit(1);
            return;
        }

        // 关键：把映射版本和布局直接打到终端。
        // 这是唯一不依赖 APK 的验证手段 —— app 里那个版本提示只在"app 是新的"
        // 时才存在，APK 没更新时它根本不会执行，所以必须在这里能亲眼看到。
        System.out.println("==============================================");
        System.out.println("VGPAD protocol=" + PROTOCOL_VERSION);
        System.out.println("  描述符 " + GamepadReport.DESCRIPTOR.length + " 字节, "
                + "校验 " + descriptorHash() + ", 报告 " + GamepadReport.REPORT_SIZE + " 字节");
        System.out.println("  id   : " + hexId(VID) + ":" + hexId(PID)
                + "   (没有厂商 kl，系统用 Generic.kl)");
        System.out.println("  左摇杆: X  Y            -> AXIS_X / AXIS_Y");
        System.out.println("  右摇杆: Z  Rz           -> AXIS_Z / AXIS_RZ   (不是 Rx/Ry)");
        System.out.println("  扳机  : Brake / Accel   -> AXIS_LTRIGGER / AXIS_RTRIGGER");
        System.out.println("        （同时也是 AXIS_BRAKE / AXIS_GAS）");
        System.out.println("  若上面不是 protocol=4，说明跑的还是旧 APK，");
        System.out.println("  请先在 AIDE 里重新编译安装，再回来执行本命令。");
        System.out.println("==============================================");
        System.out.println("设备 1（手柄）: " + devName);
        System.out.println("设备 2（键盘）: " + kbdName);
        System.out.println("设备 3（鼠标）: " + mouseName);
        System.out.println("   报告 " + MouseReport.REPORT_SIZE + " 字节"
                + "（按键 5 位 + X/Y/Wheel/水平滚轮 各 8 位相对）");
        System.out.println("   键盘报告 " + kbdReport.size() + " 字节"
                + (kbdBootMode ? "（boot 6KRO：修饰键 + 6 键数组，同时最多 6 键）"
                               : "（NKRO bitmap：修饰键 8 位 + 键位 224 位，无 6 键上限）"));
        System.out.println("   键盘按键由 app 动态创建，默认一个都没有");
        System.out.println("想知道跑的是哪一版，别看 app —— 看这里或下面两条，");
        System.out.println("它们都不依赖 app 是否更新：");
        System.out.println("  getevent -l                 列表里应能看到 " + devName);
        System.out.println("  dumpsys input | grep -i -A6 'gamepad'");
        System.out.println("        Name 应为 " + devName + "，KeyLayoutFile 应为 Generic.kl");
        System.out.println("==============================================");
        System.out.println("kernel opened device: " + device.isKernelOpened());
        Log.i(TAG, "uhid gamepad created, protocol=" + PROTOCOL_VERSION);

        // 自检会让右摇杆自己画圈 ~3 秒，很容易被误认为"摇杆一直在发信号"。
        // 默认关掉，需要确认设备存在时加参数 --selftest。
        if (hasArg(args, "--selftest")) {
            System.out.println("(自检开启：摇杆会自己转两圈，属正常)");
            selfTest(device, report);
        }

        // Start the socket listener on a background thread so the MAIN thread
        // can block on stdin, exactly like the upstream Hid command does.
        // Shizuku/Termux keep the process alive via the stdin pipe; a process
        // that only blocks on accept() gets reaped after about a second.
        startListener(device, report, kbdDevice, kbdReport, mouseDevice, mouseReport);

        // Heartbeat: if the process is still alive you will see this count up.
        startHeartbeat();

        keepAlive();
    }

    private static final Object LOCK = new Object();

    private static boolean hasArg(String[] args, String name) {
        if (args == null) return false;
        for (String a : args) {
            if (name.equals(a)) return true;
        }
        return false;
    }

    private static String hexId(int v) {
        return "0x" + Integer.toHexString(v & 0xFFFF).toUpperCase();
    }

    /**
     * 端口被占用时，问一句对方的版本号。
     * 能问出来就把版本号打出来，问不出来说明是更老的进程 —— 两种都要按旧进程处理，
     * 但打出来你就知道"卡住的到底是哪一版"，不用猜。
     */
    private static void probeExistingDaemon() {
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new java.net.InetSocketAddress(
                    java.net.InetAddress.getByName("127.0.0.1"), PORT), 1200);
            s.setSoTimeout(1500);
            s.getOutputStream().write("?\n".getBytes("UTF-8"));
            s.getOutputStream().flush();
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream()));
            String line = r.readLine();
            if (line != null && line.startsWith("VGPAD ")) {
                System.out.println("!! 占着端口的是 protocol=" + line.substring(6).trim()
                        + " 的旧守护进程（当前代码是 " + PROTOCOL_VERSION + "）");
            } else {
                System.out.println("!! 占着端口的进程不认识版本查询，是比 protocol=1 更老的进程");
            }
        } catch (Exception e) {
            System.out.println("!! 占着端口的进程没有应答版本查询（很可能是旧版守护进程）");
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** 描述符的短校验值，用来在终端上分辨跑的是哪一份代码。 */
    private static String descriptorHash() {
        int h = 0;
        // 用下标而不是 for-each：byte[] 的 for-each 虽然通常会被编译成数组访问，
        // 但既然 R8 已经因为拆箱崩过一次（Cannot constrain type: @Nullable Integer
        // by constraint: INT），这里就彻底不碰包装类型。
        byte[] d = GamepadReport.DESCRIPTOR;
        for (int i = 0; i < d.length; i++) {
            h = h * 31 + (d[i] & 0xFF);
        }
        return Integer.toHexString(h & 0xFFFF);
    }

    /**
     * Block forever so the process is never reaped.
     * Prefer stdin (matches upstream, keeps Shizuku/Termux happy);
     * if stdin is /dev/null or closed, fall back to a wait that never returns.
     */
    private static void keepAlive() {
        try {
            int r = System.in.read();
            if (r == -1) {
                System.out.println("stdin closed -> parking forever");
                synchronized (LOCK) {
                    LOCK.wait();
                }
            }
        } catch (Exception e) {
            System.out.println("stdin error (" + e + ") -> parking forever");
            synchronized (LOCK) {
                try {
                    LOCK.wait();
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    private static void startHeartbeat() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 1; ; i++) {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    System.out.println("alive " + (i * 5) + "s, clients=" + sClients
                            + ", cmds=" + sCmdCount
                            + (sCurrent != null ? " (connected)" : " (waiting)"));
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private static volatile int sClients;
    private static volatile int sCmdCount;

    private static void startListener(final Device device, final GamepadReport report,
                                      final Device kbdDevice, final KeyboardReport kbdReport,
                                      final Device mouseDevice, final MouseReport mouseReport) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                ServerSocket server = null;
                try {
                    server = new ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1"));
                    System.out.println("listening on 127.0.0.1:" + PORT);
                    while (true) {
                        Socket socket = server.accept();
                        sClients++;
                        System.out.println("client connected (#" + sClients + ")");
                        // Only one client at a time: drop the previous one so a stale
                        // (never closed) connection can never wedge the daemon.
                        Socket prev;
                        synchronized (GpadDaemon.class) {
                            prev = sCurrent;
                            sCurrent = socket;
                        }
                        if (prev != null) {
                            try {
                                prev.close();
                            } catch (IOException ignored) {
                            }
                        }
                        socket.setSoTimeout(0);
                        // 【必须在单独的线程里 serve】
                        //   原来这里是直接在本线程 serve() 的，而 serve() 会一直
                        //   阻塞到这个客户端断开为止。于是 accept() 根本执行不到 ——
                        //   第二个连接只能待在 backlog 里没人接。
                        //
                        //   TCP 握手是内核完成的，所以 app 那边 connect() **成功**，
                        //   看起来"连上了"；但发过去的 "?" 永远等不到回答，
                        //   2.5 秒超时后版本号取到 -1 ——
                        //   界面就显示"连上的是旧进程（不认识版本查询）"。
                        //
                        //   明明是新进程、版本号也一致，却报"问不到版本"，
                        //   根因就在这里：不是版本旧，是没人应答。
                        final Socket fs = socket;
                        Thread ct = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    serve(fs, device, report, kbdDevice, kbdReport,
                                          mouseDevice, mouseReport);
                                } catch (Exception e) {
                                    System.out.println("client handler: " + e);
                                }
                            }
                        });
                        ct.setDaemon(true);
                        ct.start();
                    }
                } catch (Exception e) {
                    // Print the WHOLE thing: a silent death here is what made
                    // this bug so hard to see.
                    System.out.println("LISTENER DIED: " + e);
                    if (e instanceof java.net.BindException) {
                        // 绝不能继续活着！
                        // 以前这里只是打印提示然后继续运行 —— 结果是：
                        //   1) 它已经创建了一个 uhid 设备却没人跟它通信，
                        //      变成一个"僵尸手柄"留在系统里，测试器可能读到的正是它；
                        //   2) 终端上看不到失败，用户以为启动成功了。
                        // 现在：销毁刚创建的设备，然后直接退出。
                        System.out.println("!! 端口 " + PORT + " 已被占用："
                                + "上一个守护进程还活着，本次启动无效，即将退出。");
                        probeExistingDaemon();
                        System.out.println("!! 旧进程带着旧描述符，"
                                + "必须杀掉它再启动，改过的映射才会生效：");
                        System.out.println("   pkill -f GpadDaemon");
                        System.out.println("   ps -A | grep -i gpad    # 确认没有残留");
                        System.out.println("然后回到 app 点「复制命令」重新执行。");
                        // 两个设备都要关：只关手柄的话，键盘会变成一个没人管的僵尸设备
                        try {
                            device.close();
                        } catch (Exception ignore) {
                        }
                        try {
                            kbdDevice.close();
                        } catch (Exception ignore) {
                        }
                        System.exit(3);
                    }
                    e.printStackTrace(System.out);
                    Log.e(TAG, "listener died", e);
                } finally {
                    if (server != null) {
                        try {
                            server.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    /**
     * Runs in the background: sweeps both sticks in a circle and blinks A/B/X/Y.
     * If you see movement in a gamepad tester, the virtual device is alive and
     * any remaining problem is on the UI side.
     */
    private static void selfTest(final Device device, final GamepadReport report) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    for (int i = 0; i < 60; i++) {
                        double a = i * Math.PI / 15.0;
                        report.setAxis(0, (float) Math.cos(a));
                        report.setAxis(1, (float) Math.sin(a));
                        report.setAxis(2, (float) Math.cos(-a));
                        report.setAxis(3, (float) Math.sin(-a));
                        report.setButton(0, (i / 10) % 2 == 0);
                        device.sendReport(report.buffer());
                        Thread.sleep(50);
                    }
                    report.reset();
                    device.sendReport(report.buffer());
                    System.out.println("self-test done");
                } catch (Exception e) {
                    System.out.println("self-test failed: " + e);
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private static void serve(Socket socket, Device device, GamepadReport report,
                              Device kbdDevice, KeyboardReport kbdReport,
                              Device mouseDevice, MouseReport mouseReport) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        String line;
        try {
            while ((line = in.readLine()) != null) {
                if (line.length() == 0) continue;
                // 版本查询：只回一句话，不推报告
                if (line.equals("?")) {
                    try {
                        socket.getOutputStream()
                                .write(("VGPAD " + PROTOCOL_VERSION + "\n").getBytes("UTF-8"));
                        socket.getOutputStream().flush();
                    } catch (IOException ignored) {
                    }
                    continue;
                }
                // 键盘命令单独走：它推的是**键盘设备**的报告，
                // 和手柄那份互不相干，没必要每次都把手柄报告也重发一遍。
                if (line.length() > 1 && line.charAt(0) == 'k' && line.charAt(1) == ' ') {
                    String[] p = line.split(" ");
                    if (p.length >= 3) {
                        try {
                            logCommand(line);
                            kbdReport.setKey(Integer.parseInt(p[1]), p[2].equals("1"));
                            kbdDevice.sendReport(kbdReport.buffer());
                            //
                            // 【诊断：证明 6 键上限确实破了】
                            //   只打一次，避免刷屏。看到这行就说明
                            //   同一时刻有 7 个以上键处于按下状态 ——
                            //   旧的 6KRO 布局下第 7 个键会被静默丢弃。
                            int nk = kbdReport.pressedCount();
                            if (nk > 6 && !sNkroNoticed) {
                                sNkroNoticed = true;
                                System.out.println("[kbd] NKRO ok: "
                                        + nk + " keys pressed at once (>6)");
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "bad key command: " + line);
                        }
                    }
                    continue;
                }
                /*
                  鼠标命令。和键盘一样单独走，推的是**鼠标设备**的报告。

                  【为什么不合并进手柄那条路径】
                    鼠标是增量模型：发完一条就得把位移清掉，
                    否则下一帧会把同一段位移重复计入，光标会一直飘。
                    而手柄是状态模型，报告里存的是"当前位置"，必须保留。
                    两者的生命周期相反，不能共用一个 buffer 语义。

                  【命令】
                    m <dx> <dy>            相对移动（wheel / pan 默认 0）
                    m <dx> <dy> <wheel>    带垂直滚轮
                    c <btn> <0|1>          按键：0=左 1=右 2=中 3/4=侧键
                */
                if (line.length() > 1 && line.charAt(0) == 'm' && line.charAt(1) == ' ') {
                    String[] p = line.split(" ");
                    if (p.length >= 3) {
                        try {
                            logCommand(line);
                            mouseReport.setMove(Integer.parseInt(p[1]),
                                    Integer.parseInt(p[2]));
                            mouseReport.setWheel(p.length >= 4
                                    ? Integer.parseInt(p[3]) : 0);
                            mouseDevice.sendReport(mouseReport.buffer());
                            // 位移是一次性的：发完立刻归零。
                            // 按键状态**不清** —— 按下后不松就一直按着。
                            mouseReport.clearDelta();
                        } catch (Exception e) {
                            Log.w(TAG, "bad mouse command: " + line);
                        }
                    }
                    continue;
                }
                if (line.length() > 1 && line.charAt(0) == 'c' && line.charAt(1) == ' ') {
                    String[] p = line.split(" ");
                    if (p.length >= 3) {
                        try {
                            logCommand(line);
                            mouseReport.setButton(Integer.parseInt(p[1]),
                                    p[2].equals("1"));
                            mouseDevice.sendReport(mouseReport.buffer());
                            mouseReport.clearDelta();
                        } catch (Exception e) {
                            Log.w(TAG, "bad mouse button command: " + line);
                        }
                    }
                    continue;
                }
                try {
                    // 把收到的命令打到终端（节流，避免刷屏），
                    // 这样"摇杆自己发信号"到底是 app 发的还是别的原因，一眼可见。
                    logCommand(line);
                    parse(line, report);
                    device.sendReport(report.buffer());
                } catch (RuntimeException e) {
                    Log.w(TAG, "bad command: " + line);
                }
            }
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            //
            // 【只有"当前客户端"断开才复位，旧连接退出不许清状态】
            //   以前这里无条件 reset + 推报告。
            //   而连接是"新的来、旧的走"：新客户端一接入就把上一个挤掉，
            //   被挤掉那个的 serve 线程随后走到这里 —— 它一复位，
            //   刚连上、正在玩的那个客户端的手柄状态就被抹掉了。
            //
            //   典型场景：`nc 127.0.0.1 19820` 先连上（占着当前客户端），
            //   再点"启动虚拟手柄"让 app 接入 —— nc 被挤掉、走 finally、
            //   把 app 刚建立的状态清成空报告。之后 nc 退出再清一次。
            //   表现出来就是"按键没反应"。
            //
            //   复位只该发生在"最后一个客户端走了"的时候。
            boolean wasCurrent;
            synchronized (GpadDaemon.class) {
                wasCurrent = (sCurrent == socket);
                if (wasCurrent) {
                    sCurrent = null;
                }
            }
            if (!wasCurrent) {
                System.out.println("stale client gone (not current) -> keep report as is");
                return;
            }
            report.reset();
            try {
                device.sendReport(report.buffer());
            } catch (IOException ignored) {
            }
            // 键盘也要松开：不复位的话，app 崩掉或被杀时键会一直"按着"
            kbdReport.reset();
            try {
                kbdDevice.sendReport(kbdReport.buffer());
            } catch (IOException ignored) {
            }
            // 鼠标同理：按键要松开（位移本来就是一次性的，reset 一并清掉）
            mouseReport.reset();
            try {
                mouseDevice.sendReport(mouseReport.buffer());
            } catch (IOException ignored) {
            }
        }
    }

    /** 命令日志节流：最多每 400ms 打一条，避免刷屏。 */
    private static long sLastLog;
    private static int sDropped;

    private static void logCommand(String line) {
        long now = System.currentTimeMillis();
        if (now - sLastLog < 400L) {
            sDropped++;
            return;
        }
        sLastLog = now;
        System.out.println("cmd: " + line + (sDropped > 0 ? "  (+" + sDropped + " 条省略)" : ""));
        sDropped = 0;
    }

    private static void parse(String line, GamepadReport r) {
        sCmdCount++;
        String[] p = line.split(" ");
        if (p[0].equals("b") && p.length >= 3) {
            r.setButton(Integer.parseInt(p[1]), p[2].equals("1"));
        } else if (p[0].equals("a") && p.length >= 3) {
            r.setAxis(Integer.parseInt(p[1]), Float.parseFloat(p[2]));
        } else if (p[0].equals("h") && p.length >= 2) {
            r.setHat(Integer.parseInt(p[1]));
        } else if (p[0].equals("t") && p.length >= 3) {
            r.setTrigger(Integer.parseInt(p[1]), Float.parseFloat(p[2]));
        } else if (p[0].equals("r")) {
            r.reset();
        }
    }
}
