package com.example.vgamepad;

import android.content.Context;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 用纯原生 API 检测激活前置条件。
 *
 * 这里刻意不依赖 Shizuku SDK —— AIDE 编译不了 AIDL 和远程依赖，
 * 所以所有判断都退化成"能不能做到"的替代检测：
 *
 *   Shizuku 是否可用  -> 包名是否存在（无法知道是否已激活）
 *   无线调试是否开启  -> 读 Settings.Global（只能读到开关，读不到配对状态）
 *   守护进程是否活着  -> 直接连一次 127.0.0.1:19820（这个最准）
 *
 * 最后一项是唯一 100% 可靠的，所以 UI 以它为准，
 * 前两项只用来决定"下一步该引导用户去哪"。
 */
public final class ActivationHelper {

    /**
     * Shizuku **app** 的包名：`moe.shizuku.privileged.api`。
     *
     * 从 v12 一路到最新的 13.6 都是这个，app 侧没换过。
     * 容易混淆的是另一件事：v11 时 **API 库**的 Java 包名从
     * `moe.shizuku.api` 改成了 `rikka.shizuku`（SDK 依赖也叫 dev.rikka.shizuku）。
     * 那是给开发者引用的库，跟手机上装的这个 app 不是一回事。
     *
     * 这里做成数组是为了留个口子：万一以后官方改包名、或者用户装的是
     * 换了包名的衍生版，往里加一个就行，不用改检测逻辑。
     */
    public static final String[] SHIZUKU_PKGS = {
            "moe.shizuku.privileged.api"
    };

    /** 主包名，给只想拿一个字符串用的地方。 */
    public static final String SHIZUKU_PKG = SHIZUKU_PKGS[0];

    public static final int PORT = 19820;

    private ActivationHelper() {
    }

    /**
     * 已安装的 Shizuku 包名，一个都没找到返回 null。
     *
     * 注意返回值是 null 时**不能**断定"没装"：
     *   - Android 11+ 没声明 <queries> -> 查不到（本工程已声明）
     *   - 国产 ROM 的"读取应用列表"权限没给 -> 也查不到
     *   - 真的没装 -> 同样查不到
     * 这三种情况 PackageManager 都只是抛 NameNotFoundException，无法区分。
     * 所以 UI 上只能说"没检测到"，真正的验证交给「打开 Shizuku」按钮
     * （startActivity 不走包可见性，点了就知道）。
     */
    private static String findShizukuPkg(Context c) {
        PackageManager pm = c.getPackageManager();
        for (int i = 0; i < SHIZUKU_PKGS.length; i++) {
            try {
                pm.getPackageInfo(SHIZUKU_PKGS[i], 0);
                return SHIZUKU_PKGS[i];
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return null;
    }

    /** Shizuku app 是否已安装。查不到返回 false —— 可能是没装，也可能是查不到。 */
    public static boolean isShizukuInstalled(Context c) {
        return findShizukuPkg(c) != null;
    }

    /**
     * 直接尝试打开 Shizuku。
     *
     * 这是最可靠的"装没装"验证：startActivity 不受 Android 11 包可见性限制，
     * 也不受多数 ROM 的应用列表管控影响 —— 装了就打开，没装会抛异常。
     *
     * @return true = 已经发出去了；false = 没装 / 打不开
     */
    public static boolean launchShizuku(Context c) {
        Intent i = shizukuLaunchIntent(c);
        if (i == null) {
            return false;
        }
        try {
            c.startActivity(i);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        } catch (SecurityException e) {
            return false;
        }
    }

    /**
     * 构造打开 Shizuku 的 Intent。
     *
     * 关键：查不到包时**不要**直接返回 null。
     * 包可见性 / ROM 的"读取应用列表"管控会让我们查不到，但 app 其实是装着的。
     * 所以兜底走"隐式 intent + setPackage"：
     *   startActivity 时的解析由系统以自身身份完成，不受调用方的可见性限制，
     *   这是被拦截时仍然能把 app 拉起来的路子。
     */
    public static Intent shizukuLaunchIntent(Context c) {
        for (int k = 0; k < SHIZUKU_PKGS.length; k++) {
            String pkg = SHIZUKU_PKGS[k];
            PackageManager pm = c.getPackageManager();
            Intent i = pm.getLaunchIntentForPackage(pkg);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                return i;
            }
        }
        // 查不到（被拦截 / 没装）：仍构造一个"指定包名的 launcher intent"。
        // 装了就能打开，没装会在 startActivity 时抛 ActivityNotFoundException。
        Intent fallback = new Intent(Intent.ACTION_MAIN);
        fallback.addCategory(Intent.CATEGORY_LAUNCHER);
        fallback.setPackage(SHIZUKU_PKGS[0]);
        fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return fallback;
    }

    /**
     * 无线调试是否已开启（Android 11+）。
     * 读不到就返回 false —— 宁可让用户多走一步，也别误报"已开启"。
     */
    public static boolean isWirelessDebuggingOn(Context c) {
        if (Build.VERSION.SDK_INT < 30) {
            return adbEnabled(c);
        }
        try {
            String v = Settings.Global.getString(c.getContentResolver(), "adb_wifi_enabled");
            if (v == null) {
                return adbEnabled(c);
            }
            return "1".equals(v) || "true".equalsIgnoreCase(v);
        } catch (Exception e) {
            return adbEnabled(c);
        }
    }

    private static boolean adbEnabled(Context c) {
        try {
            return Settings.Global.getInt(c.getContentResolver(),
                    Settings.Global.ADB_ENABLED, 0) == 1;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean canDrawOverlay(Context c) {
        if (Build.VERSION.SDK_INT >= 23) {
            return Settings.canDrawOverlays(c);
        }
        return true;
    }

    /**
     * 守护进程是否活着。这是唯一可靠的判断：连得上就一定是活的。
     * 必须在后台线程调用 —— 同步 socket，带 800ms 超时。
     */
    public static boolean isDaemonAlive() {
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", PORT), 800);
            return true;
        } catch (IOException e) {
            return false;
        } catch (RuntimeException e) {
            return false;
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** 生成守护进程启动命令。CLASSPATH 指向已安装 APK，不依赖 pm path。 */
    /**
     * 停止守护进程的命令。
     *
     * pkill 去杀，ps 去确认。用 ; 而不是 && ——
     * pkill 没匹配到进程时返回非 0，接 && 的话后面那条确认命令就不执行了。
     */
    /**
     * 停止守护进程的命令。
     *
     * 【必须写成 [G]padDaemon，不能直接写 GpadDaemon】
     *   执行这条命令的 shell，它自己的命令行是
     *       sh -c "pkill -f GpadDaemon;ps -A | grep -i gpad"
     *   里面就含 "GpadDaemon" 这个字符串。pkill -f 匹配的是完整命令行，
     *   于是它**把自己也杀了** —— 表现为：
     *     · 守护进程没被杀到（sh 先死，后面的命令没跑完）
     *     · 再点一次报 "process hasn't exited"（进程状态混乱）
     *
     *   写成 [G]padDaemon 后，正则要匹配的是 "GpadDaemon"，
     *   而 sh 命令行里的字符序列是 "[G]padDaemon"，对不上，于是不会自杀。
     *   同理 grep 也写成 [g]pad，避免把 sh 自己列出来造成"还在运行"的假象。
     *
     * 【为什么杀两遍】
     *   先 TERM 给个体面退出的机会，再 KILL 强杀兜底 ——
     *   有些情况下守护进程卡在 IO 上，TERM 收不到。
     */
    public static String buildStopCommand() {
        return "pkill -f '[G]padDaemon';"
                + "sleep 0.3;"
                + "pkill -9 -f '[G]padDaemon';"
                + "sleep 0.2;"
                + "(ps -A 2>/dev/null || ps) | grep -i '[g]pad' "
                + "|| echo '守护进程已停止'";
    }

    public static String buildDaemonCommand(Context c) {
        String apk = c.getApplicationInfo().sourceDir;
        return "CLASSPATH=" + apk + " app_process / com.example.vgamepad.GpadDaemon";
    }

    // ------------------------------------------------------------------
    // 状态机
    // ------------------------------------------------------------------

    public static final int STEP_DONE = 0;         // 一切就绪
    public static final int STEP_OVERLAY = 1;      // 先去授权悬浮窗
    public static final int STEP_ENABLE_ADB = 2;   // 先去开无线调试
    public static final int STEP_RUN_COMMAND = 3;  // 去执行那条命令
    public static final int STEP_CHECKING = 4;     // 正在检测

    public static final class State {
        public boolean overlay;
        /** 只代表"查到了"，false 不等于没装 —— 可能是被系统拦截了查询。 */
        public boolean shizukuInstalled;
        public boolean wirelessAdb;
        public boolean daemonAlive;
        public int nextStep;

        /** 一句人话告诉用户现在该干嘛。 */
        public String advice() {
            switch (nextStep) {
                case STEP_OVERLAY:
                    return "需要先授予悬浮窗权限，否则手柄显示不出来。";
                case STEP_ENABLE_ADB:
                    return shizukuInstalled
                            ? "Shizuku 已安装，但它需要先激活。打开 Shizuku 按指引启动即可。"
                            // 不再断言"未安装"：查不到还可能是没给"读取应用列表"权限。
                            // 说"未安装"会让人以为要去装一个，而其实装了只是我们看不见。
                            : "没检测到 Shizuku（可能没装，也可能系统不让我们查应用列表）。\n"
                            + "不想折腾就用电脑 adb / Termux / LADB 执行下面的命令，效果一样。\n"
                            + "想验证到底装没装，点「打开 Shizuku」。";
                case STEP_RUN_COMMAND:
                    return "复制下面这条命令，在 Shizuku（或 Termux / LADB / 电脑 adb）里执行。";
                case STEP_DONE:
                    return "守护进程已运行，可以直接启动手柄。";
                default:
                    return "正在检测…";
            }
        }
    }

    /** 计算当前状态和下一步。isDaemonAlive 是同步 IO，请在后台线程调用。 */
    public static State evaluate(Context c) {
        State s = new State();
        s.overlay = canDrawOverlay(c);
        s.shizukuInstalled = isShizukuInstalled(c);
        s.wirelessAdb = isWirelessDebuggingOn(c);
        s.daemonAlive = isDaemonAlive();

        if (!s.overlay) {
            s.nextStep = STEP_OVERLAY;
        } else if (s.daemonAlive) {
            s.nextStep = STEP_DONE;
        } else if (!s.wirelessAdb && !s.shizukuInstalled) {
            // 两条路都没有 -> 先引导开无线调试
            s.nextStep = STEP_ENABLE_ADB;
        } else {
            s.nextStep = STEP_RUN_COMMAND;
        }
        return s;
    }
}
