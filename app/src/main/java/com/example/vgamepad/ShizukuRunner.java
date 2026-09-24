package com.example.vgamepad;

import android.content.pm.PackageManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import rikka.shizuku.Shizuku;

/**
 * 用 Shizuku 以 shell(2000) / root(0) 身份执行命令。
 *
 * 替代"手动复制命令到 Shizuku / Termux / 电脑 adb 里粘贴"这一步：
 * 拿到 Shizuku 授权后，本 app 自己把守护进程启动命令执行掉。
 *
 * 【能力边界】
 *   这**不是**"自我激活 Shizuku"。Shizuku 服务本身必须先由用户启动一次
 *   （无线调试配对 / root），这是它的安全设计，任何 app 都绕不过。
 *   本类做的是：服务已在跑的前提下，自动请求授权 + 自动执行命令。
 *
 * 【为什么用反射调 newProcess】
 *   13.1.5 里 Shizuku.newProcess(...) 是 private static（官方推 UserService，
 *   给它标了"准备移除"）。getMethod() 只找 public，永远找不到；
 *   必须 getDeclaredMethod + setAccessible(true)。
 *   这里做了三层降级，任一签名能用即可，避免不同版本签名不一致直接崩。
 *
 * 【为什么绝不 waitFor】
 *   守护进程是长期运行的，waitFor() 会把调用线程永久卡住。
 *   所以 exec() 只负责起进程 + 把两个流抽干，立刻返回。
 *   （stdout / stderr 两个都得抽，只抽一个的话另一个管道写满会把子进程憋死）
 */
public final class ShizukuRunner {

    private static final String TAG = "VGamepad";

    /** 授权请求码，任意固定值即可。 */
    public static final int REQ_PERMISSION = 8848;

    /** 持有已启动的进程，防止被 GC 回收导致进程被一起带走。 */
    private static volatile Process sDaemon;

    private ShizukuRunner() {
    }

    /** 收到一行输出的回调。注意在后台线程调用，别直接操作 UI。 */
    public interface LineSink {
        void onLine(String line);
    }

    /** Shizuku 服务是否在运行（binder 活着）。 */
    public static boolean isAlive() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            // Shizuku 类没初始化时会抛，不能让它把 app 带崩
            Log.w(TAG, "pingBinder failed", t);
            return false;
        }
    }

    /** 是否已经拿到授权。 */
    public static boolean hasPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                return false;
            }
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            Log.w(TAG, "checkSelfPermission failed", t);
            return false;
        }
    }

    /**
     * 请求授权。结果通过 Shizuku.OnRequestPermissionResultListener 回来
     * （见 MainActivity），v11 以下走普通运行时权限那条路，
     * 结果从 Activity.onRequestPermissionsResult 转发过来。
     */
    public static void requestPermission(android.app.Activity a) {
        try {
            if (Shizuku.isPreV11() || Shizuku.getVersion() < 11) {
                a.requestPermissions(
                        new String[]{rikka.shizuku.ShizukuProvider.PERMISSION},
                        REQ_PERMISSION);
            } else {
                Shizuku.requestPermission(REQ_PERMISSION);
            }
        } catch (Throwable t) {
            Log.e(TAG, "requestPermission failed", t);
        }
    }

    /**
     * 以 shell/root 身份启动一条命令（不等待结束）。
     *
     * @return null 表示已成功启动；非 null 是错误信息。
     */
    public static String exec(String cmd, LineSink sink) {
        try {
            Process p = open(cmd, sink);
            if (p == null) {
                return "无法创建进程。\n" + describeNewProcess();
            }
            sDaemon = p;
            pump(p.getInputStream(), sink);
            pump(p.getErrorStream(), sink);
            return null;
        } catch (Throwable t) {
            Log.e(TAG, "exec failed", t);
            return t.getClass().getName() + ": " + t.getMessage()
                    + "\n" + describeNewProcess();
        }
    }

    /**
     * 以 shell/root 身份执行一条**短**命令，等它结束并返回输出。
     *
     * 和 exec() 的区别就一个字：这里**会**等。
     * exec() 是给守护进程用的（长期运行，waitFor 会永久卡死调用线程）；
     * 停止 / 查进程这类命令跑完就退出，必须等结果才知道杀干净没有。
     *
     * @param timeoutMs 超时上限，防止 pkill 卡住时把线程挂死
     */
    public static String execSync(String cmd, int timeoutMs) {
        try {
            Process p = open(cmd, null);
            if (p == null) {
                return "无法创建进程。\n" + describeNewProcess();
            }
            // 两个流都要抽，且要**并发**抽：
            // 先抽完 stdout 再抽 stderr 的话，stderr 的管道写满会把子进程憋死。
            final StringBuilder out = new StringBuilder();
            Thread t1 = pumpTo(p.getInputStream(), out);
            Thread t2 = pumpTo(p.getErrorStream(), out);
            int code = -1;
            boolean exited = false;
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                try {
                    code = p.exitValue();
                    exited = true;
                    break;
                } catch (RuntimeException notYet) {
                    // 进程还没退出。标准实现抛 IllegalThreadStateException，
                    // 但部分 ROM / Shizuku 的 Process 包装类抛的是
                    //   java.lang.IllegalArgumentException: process hasn't exited
                    // 只 catch 前者的话，后者会漏到最外层变成"失败: ..."，
                    // 明明只是"还没跑完"却被当成执行失败。
                    // 这里统一按 RuntimeException 吃掉 —— 轮询本来就是为了等。
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ignored) {
                        break;
                    }
                }
            }
            if (!exited) {
                code = -1;
                try {
                    p.destroy();   // 超时，强杀
                } catch (Throwable ignored) {
                }
            }
            try {
                t1.join(500);
            } catch (InterruptedException ignored) {
            }
            try {
                t2.join(500);
            } catch (InterruptedException ignored) {
            }
            String r = out.toString().trim();
            String tail = exited ? ("\n[退出码 " + code + "]")
                    : ("\n[超时未退出，已强制结束]"
                        + " —— 若守护进程仍在，请再点一次" );
            return (r.length() == 0 ? "（无输出）" : r) + tail;
        } catch (Throwable t) {
            Log.e(TAG, "execSync failed", t);
            return "失败: " + t;
        }
    }

    private static Thread pumpTo(final java.io.InputStream in, final StringBuilder out) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                BufferedReader r = null;
                try {
                    r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
                    String line;
                    while ((line = r.readLine()) != null) {
                        out.append(line).append('\n');
                    }
                } catch (java.io.IOException ignored) {
                } finally {
                    if (r != null) {
                        try {
                            r.close();
                        } catch (java.io.IOException ignored) {
                        }
                    }
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** 列出 Shizuku 类上所有 newProcess 的签名 —— 调试用，全失败时打出来。 */
    public static String describeNewProcess() {
        StringBuilder sb = new StringBuilder();
        try {
            Class<?> c = Class.forName("rikka.shizuku.Shizuku");
            Method[] ms = c.getDeclaredMethods();
            for (int i = 0; i < ms.length; i++) {
                if (!"newProcess".equals(ms[i].getName())) {
                    continue;
                }
                sb.append("newProcess(");
                Class<?>[] ps = ms[i].getParameterTypes();
                for (int j = 0; j < ps.length; j++) {
                    if (j > 0) {
                        sb.append(", ");
                    }
                    sb.append(ps[j].getSimpleName());
                }
                sb.append(") -> ")
                        .append(ms[i].getReturnType().getSimpleName())
                        .append("  [")
                        .append(Modifier.toString(ms[i].getModifiers()))
                        .append("]\n");
            }
            if (sb.length() == 0) {
                sb.append("（没有 newProcess 方法，该版本可能已移除）\n");
            }
        } catch (Throwable t) {
            sb.append("反射失败: ").append(t);
        }
        return sb.toString();
    }

    /** 按三种可能的签名依次尝试创建进程。 */
    private static Process open(String command, LineSink sink) throws Exception {
        Class<?> cls = Class.forName("rikka.shizuku.Shizuku");

        // 方式 1：newProcess(String[], String[], String)
        Method m1 = find(cls, "newProcess",
                String[].class, String[].class, String.class);
        if (m1 != null) {
            Object r = m1.invoke(null,
                    new String[]{"sh", "-c", command}, null, null);
            note(sink, "用 newProcess(String[],String[],String)");
            return (Process) r;
        }

        // 方式 2：newProcess(String[])
        Method m2 = find(cls, "newProcess", String[].class);
        if (m2 != null) {
            Object r = m2.invoke(null, (Object) new String[]{"sh", "-c", command});
            note(sink, "用 newProcess(String[])");
            return (Process) r;
        }

        // 方式 3：newProcess(List<String>)
        Method m3 = find(cls, "newProcess", List.class);
        if (m3 != null) {
            java.util.ArrayList<String> cmd = new java.util.ArrayList<String>();
            cmd.add("sh");
            cmd.add("-c");
            cmd.add(command);
            Object r = m3.invoke(null, cmd);
            note(sink, "用 newProcess(List<String>)");
            return (Process) r;
        }
        return null;
    }

    /** 找到就 setAccessible(true)，找不到返回 null。 */
    private static Method find(Class<?> cls, String name, Class<?>... params) {
        try {
            Method m = cls.getDeclaredMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void note(LineSink sink, String s) {
        Log.i(TAG, s);
        if (sink != null) {
            sink.onLine(s);
        }
    }

    private static void pump(final java.io.InputStream in, final LineSink sink) {
        if (in == null) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                BufferedReader r = null;
                try {
                    r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (sink != null) {
                            sink.onLine(line);
                        }
                    }
                } catch (java.io.IOException ignored) {
                    // 进程被销毁时必然抛，属于正常退出路径
                } finally {
                    if (r != null) {
                        try {
                            r.close();
                        } catch (java.io.IOException ignored) {
                        }
                    }
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }
}
