package com.example.vgamepad;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.ClipboardManager;
import android.util.Log;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import rikka.shizuku.Shizuku;

/**
 * 半自动激活界面。
 *
 * 能自动做的：检测状态、生成命令、复制、跳转设置页、启动手柄。
 * 不能自动做的：以 shell 身份执行那条命令 —— 那需要 Shizuku SDK，
 * 而当前编译环境（AIDE）不支持 AIDL 和远程依赖。
 *
 * 所以这里的设计是：把"还差什么"算清楚，把用户要做的收敛成一步。
 */
public class MainActivity extends Activity {

    // 下面两个 extra 仍会带上，方便调试时看出"这次是被悬浮窗叫起来的"；
    // 但**真正驱动弹框的是 FloatingService 的一次性令牌**，
    // 不用 extra 取值 —— AMS 那份副本清不掉，会导致重复弹框。
    public static final String EXTRA_RENAME_ID = "vg_rename_id";
    public static final String EXTRA_RENAME_NAME = "vg_rename_name";
    public static final String EXTRA_RENAME_COMBO_SLOT = "vg_rename_combo_slot";
    /** 单个按键改名：元素下标（同样只作判空，实际走令牌）。 */
    public static final String EXTRA_RENAME_KEY_ELEM = "vg_rename_key_elem";

    /** 本次界面是不是被悬浮窗叫起来改名的。是的话关掉时要把手柄放回来。 */
    /** 同 sShouldReturnToGame：跨 Activity 重建保留，见那里的说明。 */
    private static boolean sRenameFromPad = false;

    /**
     * 文件选择器已经弹出、正等 onActivityResult 回来。
     *
     * 【为什么必须挡住 onStop 里那次恢复】
     *   弹出系统文件选择器后，本界面会被它盖住 -> onStop。
     *   而 onStop 里原本的逻辑是"界面看不见了就恢复悬浮窗并清标记"，
     *   那是给"改完名直接回桌面/游戏"用的。
     *
     *   导入这条路会因此被提前收尾：sRenameFromPad 被清成 false，
     *   等选完文件回到 onActivityResult 调 finishRename() 时，
     *   开头 `if (!sRenameFromPad) return;` 直接 return ——
     *   moveTaskToBack 根本没执行，于是人留在主界面，看起来"没返回游戏"。
     */
    private static boolean sAwaitingImportResult = false;

    /**
     * 本界面当前是不是在前台（onResume / onStop 维护）。
     *
     * 用来判断"这次跳转是不是从游戏里发起的"：
     *   - 跳转发起时本界面**已经在**前台 → 用户本来就在 app 里，
     *     完成后不该退回游戏（那等于退出软件）
     *   - 不在前台 → 是从游戏的悬浮窗跳过来的，完成后该退回游戏
     */
    private static boolean sResumed = false;

    /**
     * 本次操作完成后要不要退回游戏。见 sResumed。
     *
     * 【必须是 static】
     *   选文件那段路上 Activity 可能被系统销毁重建，实例字段会回到默认 false，
     *   于是"该退回游戏"被丢掉，用户就卡在主界面了。
     *   静态字段跨重建保留，进程还在就还在。
     */
    private static boolean sShouldReturnToGame = false;

    private static final int REQ_OVERLAY = 1001;
    private static final int REQ_SHIZUKU = 1002;

    private TextView mStep;
    private TextView mAdvice;
    private TextView mChecks;
    private TextView mCmdText;
    private Button mStart;

    private boolean mChecking;
    /** 点「自动激活」后，权限一拿到就立刻启动守护进程 */
    private boolean mPendingDaemon;

    /** 点「自动停止」后，权限一拿到就立刻去杀守护进程 */
    private boolean mPendingStop;

    /** 停止命令的执行结果（动态创建） */
    private TextView mStopResult;

    /** 当前穿透模式（动态创建） */
    private TextView mPassMode;

    // ---------------- 主界面编辑模式 ----------------
    //
    // 用途：主界面按钮太多时，把用不上的藏起来，并让剩下的重新紧凑排列。
    //
    // 【为什么所有控件都用代码创建，不碰 XML】
    //   本工程只要给 XML 加了带 android:id 的控件，R8 打包就必崩在
    //     Packaging error: StringIndexOutOfBoundsException
    //   （详见 addStopSection 的注释）。所以新 UI 一律 new 出来插进现有容器。
    //
    // 【X 标记为什么用 ViewOverlay】
    //   要在按钮上画个叉，又不能破坏它原有的层级/背景。
    //   getOverlay().add() 是在 View 之上叠加绘制，不动结构、不抢背景，
    //   移除时 remove 即可 —— 比套一层 FrameLayout 安全得多。
    private boolean mUiEditMode = false;
    /** 可编辑（可隐藏）的控件集合 */
    private final java.util.ArrayList<View> mUiEditables =
            new java.util.ArrayList<View>();
    /** 「编辑主界面 / 完成」按钮自身：不可隐藏，编辑时也不变暗 */
    private View mUiEditBtn = null;
    /**
     * 不可隐藏的三件套：「主界面」标题 + 编辑按钮 + 下方说明。
     * 全藏了就再也进不来这个编辑模式，必须留着。
     */
    private final java.util.ArrayList<View> mUiProtected =
            new java.util.ArrayList<View>();
    /** 每个 View 当前挂着的 X 标记，用于精确移除 */
    private final java.util.HashMap<View, android.graphics.drawable.Drawable> mUiXMarks =
            new java.util.HashMap<View, android.graphics.drawable.Drawable>();
    private static final String PREF_UI = "ui_edit";
    /** 一次生命周期内协议只弹一次，避免 onResume 反复弹 */
    private boolean mAgreeShown = false;
    /** 协议对话框里那个「同意」按钮，看完三项才放开 */
    private android.widget.Button mAgreeBtn = null;
    private static final String PREF_UI_HIDE = "hide_";
    /**
     * 新格式的 key 前缀。
     *
     * 【和 PadLayout 的存档迁移是同一套思路】
     *   PadLayout 那边用下标当 key 存摆位，往数组中间插元素会让老存档整段错位，
     *   于是它备了一套 SCHEMA + 区间位移的迁移（见 PadLayout 的 MIG_* 说明）。
     *   这里的主界面隐藏状态也是"用序号当 key"，问题一模一样。
     *
     * 【老 key 错在哪】
     *   老 key 用的是 **box 的子控件序号**（indexOfChild）。
     *   这样只要往容器里插一个控件 —— 哪怕那个控件根本不可隐藏 ——
     *   后面所有控件的序号都会 +1，用户存好的隐藏设置整体串位：
     *   明明藏的是"复制命令"，结果藏成了"自动激活"。
     *
     * 【新 key 改用"可编辑项序号"】
     *   只有能被隐藏的控件才进 mUiEditables，序号按这个集合排。
     *   不可隐藏的控件（比如「关于」那一行）压根不进集合，
     *   所以它插在任何位置都不会挪动别人的序号 —— 从根上不再依赖容器的物理顺序。
     *
     * 换前缀而不复用 "hide_"：新老 key 值域重叠，
     *   共用一个前缀的话迁移时无法区分、也无法安全地清理旧数据。
     */
    private static final String PREF_UI_HIDE_V2 = "hide_v2_";
    /** 主界面隐藏存档的格式版本。老存档没有这个字段（读到 0）。 */
    private static final int UI_SCHEMA = 1;
    private static final String PREF_UI_SCHEMA = "uisch";


    // Java 1.7，不能用 lambda，只能写成匿名内部类字段。

    /** Shizuku 授权结果（v11+ 走这条）。 */
    private final Shizuku.OnRequestPermissionResultListener mPermListener =
            new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    final boolean granted =
                            grantResult == PackageManager.PERMISSION_GRANTED;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            refresh();
                            if (!granted) {
                                toast("你在 Shizuku 里拒绝了授权。\n"
                                        + "也可以用下面的命令在 Termux / 电脑 adb 里执行。");
                                mPendingDaemon = false;
                                return;
                            }
                            if (mPendingDaemon) {
                                mPendingDaemon = false;
                                startDaemonViaShizuku();
                            }
                            if (mPendingStop) {
                                mPendingStop = false;
                                stopDaemonAuto();
                            }
                        }
                    });
                }
            };

    /** Shizuku 的 Binder 到手（服务就绪）。 */
    private final Shizuku.OnBinderReceivedListener mBinderListener =
            new Shizuku.OnBinderReceivedListener() {
                @Override
                public void onBinderReceived() {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Shizuku.removeBinderReceivedListener(mBinderListener);
                            afterBinderReady();
                        }
                    });
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 新建实例 = 之前没在前台（界面消失时 onStop 已置 false，
        // 这里再兜一次底，防止异常路径下残留 true 把 finishRename 带偏）。
        sResumed = false;
        setContentView(R.layout.activity_main);

        mStep = (TextView) findViewById(R.id.tv_step);
        mAdvice = (TextView) findViewById(R.id.tv_advice);
        mChecks = (TextView) findViewById(R.id.tv_checks);
        mCmdText = (TextView) findViewById(R.id.cmd_text);
        mStart = (Button) findViewById(R.id.btn_start);

        mCmdText.setText(ActivationHelper.buildDaemonCommand(this));

        // 停止区用**代码动态创建**，不往 XML 里加控件。
        //
        // 原因：实测只要 XML 新增了带 android:id 的控件，aapt 就会重新生成
        // R.java，而重新生成后的 R8 必定崩在
        //     Packaging error: StringIndexOutOfBoundsException: length=0; index=0
        // 能编过的版本都有一个共同点：一个字节都没碰过 XML。
        //
        // 所以新 UI 一律用 new View(this) 创建，再插进现有容器。
        addStopSection();
        addKeyWindowToggle();
        updateShellNote(boxOfOverlay());
        // 必须在上面那些 addXxx 之后：它们动态插控件，
        // 装完才能收集"哪些控件可隐藏"。
        addMainEditButton();
        addAboutButton();
        // 必须在 applyUiHidden 之前：先搬好存档，再按存档决定谁显示
        migrateUiHiddenSchema();
        applyUiHidden();

        bind(R.id.btn_copy, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyCommand();
            }
        });

        // 用运行时查找而不是 R.id.btn_shizuku：
        // 万一 R.java 还是旧的（没重新生成），直接引用字段会编译不过，
        // 而 getIdentifier 拿到 0 时 bind() 会安静跳过 —— 少个按钮不影响别的。
        bind(idOf("btn_auto"), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                autoActivate();
            }
        });

        bind(idOf("btn_shizuku"), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openShizuku();
            }
        });

        // Shizuku 授权结果回调。v11+ 走这个；v11 以下走普通运行时权限，
        // 结果在 onRequestPermissionsResult 里转发到同一个 listener。
        //
        // 包 try/catch：Shizuku 的静态初始化在某些环境下会抛异常
        // （binder 没就绪、服务版本不匹配等）。onCreate 里抛异常 = 启动即崩，
        // 代价太大；这里失败只是"拿不到授权回调"，手动命令那条路还通。
        try {
            Shizuku.addRequestPermissionResultListener(mPermListener);
        } catch (Throwable t) {
            Log.w("VGamepad", "注册 Shizuku 监听失败，自动激活不可用", t);
        }

        bind(R.id.btn_overlay, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestOverlay();
            }
        });

        bind(R.id.btn_edit, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startEdit();
            }
        });

        mStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startPad();
            }
        });

        // 【重建时不再弹输入框】
        //   moveTaskToBack 之后 Activity 留在后台，进程可能被系统回收。
        //   从最近任务切回来时 onCreate 会重跑，getIntent() 拿到的还是
        //   当初那个带改名 extra 的 Intent —— 于是输入框又弹一次，
        //   挡住主界面，正是"返回软件发现还在输入框"的原因。
        //   savedInstanceState != null 说明是重建，这次就不弹了。
        handleRenameIntent(getIntent());
    }

    /**
     * 按键独立窗口开关。
     *
     * 【同样用代码动态创建，不碰 XML】
     *   本工程只要动 XML 就可能触发 R8 打包崩溃（见 addStopSection 的说明），
     *   所以新 UI 一律 new 出来再插进现有容器。
     */
    private void addKeyWindowToggle() {
        LinearLayout box = boxOfOverlay();
        if (box == null) {
            return;
        }
        android.view.View anchor = findViewById(R.id.btn_overlay);
        int at = box.indexOfChild(anchor);
        if (at < 0) {
            at = box.getChildCount();
        }
        float d = getResources().getDisplayMetrics().density;

        android.widget.TextView title = new android.widget.TextView(this);
        title.setText("按键独立窗口");
        title.setTextSize(13f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (18 * d);
        box.addView(title, at++, lp);

        android.widget.CheckBox cb = new android.widget.CheckBox(this);
        cb.setText("按键点按穿透：只有按键本身吃触摸，点空白处会穿透到游戏");
        cb.setTextSize(12f);
        cb.setChecked(FloatingService.keyWindowsEnabled(this));
        cb.setOnCheckedChangeListener(
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(android.widget.CompoundButton b,
                                                 boolean checked) {
                        FloatingService.setKeyWindowsEnabled(MainActivity.this, checked);
                    }
                });
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp2.topMargin = (int) (4 * d);
        box.addView(cb, at++, lp2);

        // 坦白说明：这个穿透效果是借系统 touchableRegion 的"漏洞"实现的，
        // 不是文档承诺的能力 —— 不同 ROM / 系统版本随时可能失效。
        android.widget.TextView bugNote = new android.widget.TextView(this);
        String bugTxt = "本功能是以 bug 来达到效果的，你不要指望它很稳定，"
                + "这只是个实验性bug功能";
        android.text.SpannableString bugSpan =
                new android.text.SpannableString(bugTxt);
        int st = bugTxt.indexOf("实验性");
        if (st >= 0) {
            bugSpan.setSpan(new android.text.style.StrikethroughSpan(),
                    st, st + "实验性".length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        bugNote.setText(bugSpan);
        bugNote.setTextSize(11f);
        bugNote.setTextColor(0xFFB00020);
        LinearLayout.LayoutParams lpBug = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lpBug.topMargin = (int) (2 * d);
        box.addView(bugNote, at++, lpBug);

        // 卡顿提示要紧跟「按键独立窗口」的主勾选框，
        // 不能放在调试区下面 —— 放那儿会让人以为是**调试**导致的卡顿。
        android.widget.TextView note = new android.widget.TextView(this);
        note.setText("开启后可能会异常卡顿，如接受不了，请关闭");
        note.setTextSize(11f);
        LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp3.topMargin = (int) (2 * d);
        box.addView(note, at++, lp3);

        mPassMode = new android.widget.TextView(this);
        mPassMode.setTextSize(11f);
        mPassMode.setTypeface(Typeface.MONOSPACE);
        mPassMode.setBackgroundColor(0x22000000);
        mPassMode.setPadding(6, 6, 6, 6);
        LinearLayout.LayoutParams lpM = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lpM.topMargin = (int) (8 * d);
        box.addView(mPassMode, at++, lpM);
        refreshPassMode();

        android.widget.CheckBox dbg = new android.widget.CheckBox(this);
        dbg.setText("显示判定框（调试用：把触摸窗口画成红色）");
        dbg.setTextSize(12f);
        dbg.setChecked(FloatingService.debugRectsEnabled(this));
        dbg.setOnCheckedChangeListener(
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(android.widget.CompoundButton b,
                                                 boolean checked) {
                        FloatingService.setDebugRectsEnabled(MainActivity.this, checked);
                    }
                });
        LinearLayout.LayoutParams lpD = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lpD.topMargin = (int) (6 * d);
        box.addView(dbg, at++, lpD);

        // 逃生舱：region 方案在部分 ROM 上不生效时，用它绕开
        android.widget.CheckBox fm = new android.widget.CheckBox(this);
        fm.setText("强制多窗口模式（仅当区域方案在你的设备上失效时才勾）");
        fm.setTextSize(12f);
        fm.setChecked(FloatingService.forceMultiWindow(this));
        fm.setOnCheckedChangeListener(
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(android.widget.CompoundButton b,
                                                 boolean checked) {
                        FloatingService.setForceMultiWindow(MainActivity.this, checked);
                    }
                });
        LinearLayout.LayoutParams lpF = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lpF.topMargin = (int) (6 * d);
        box.addView(fm, at++, lpF);

        android.widget.TextView fmNote = new android.widget.TextView(this);
        fmNote.setText("勾上后不走反射，机制简单但按键多时更卡。"
                + "用来判断问题是不是出在单窗口区域方案上。");
        fmNote.setTextSize(11f);
        LinearLayout.LayoutParams lpFN = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lpFN.topMargin = (int) (2 * d);
        box.addView(fmNote, at++, lpFN);


    }


    // ---------------- 主界面编辑模式：实现 ----------------

    /**
     * 在主界面插入「编辑主界面」按钮。
     *
     * 放在「悬浮窗权限」下面 —— 它是主界面的入口类操作，
     * 跟权限、启动那几个按钮放一起最顺手。
     */
    private void addMainEditButton() {
        LinearLayout box = boxOfOverlay();
        if (box == null) {
            return;
        }
        View anchor = findViewById(R.id.btn_overlay);
        int at = box.indexOfChild(anchor);
        if (at < 0) {
            at = box.getChildCount();
        }
        float d = getResources().getDisplayMetrics().density;

        TextView title = new TextView(this);
        title.setText("主界面");
        title.setTextSize(13f);
        LinearLayout.LayoutParams lpT = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lpT.topMargin = (int) (18 * d);
        box.addView(title, at++, lpT);
        mUiProtected.add(title);

        Button btn = new Button(this);
        btn.setText("编辑主界面");
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mUiEditMode) {
                    exitUiEdit();
                } else {
                    enterUiEdit();
                }
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (4 * d);
        box.addView(btn, at++, lp);
        mUiEditBtn = btn;
        mUiProtected.add(btn);

        TextView note = new TextView(this);
        note.setText("隐藏用不上的按钮，完成后自动紧凑重排。"
                + "本按钮自身不可隐藏。");
        note.setTextSize(11f);
        LinearLayout.LayoutParams lpN = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lpN.topMargin = (int) (2 * d);
        box.addView(note, at++, lpN);
        mUiProtected.add(note);
    }

    /**
     * 收集"可以隐藏"的控件：主界面里**几乎全部**控件。
     *
     * 状态卡片、标题、说明文字、命令区、所有按钮 —— 都能藏。
     * 只有「编辑主界面」那一组（按钮 + 上方标题 + 下方说明）不可隐藏：
     * 把它们也藏了就再也找不到这个功能入口，等于把自己锁在外面。
     *
     * 【存档 key 用布局索引，不用"第几个可编辑项"】
     *   可编辑范围一变，按类型编号会整体错位 ——
     *   之前存的隐藏状态会跑到别的控件上去。
     *   用 box.indexOfChild 的位置索引，只要不往中间插控件就一直对得上。
     */
    private void collectEditables() {
        mUiEditables.clear();
        LinearLayout box = boxOfOverlay();
        if (box == null) {
            return;
        }
        for (int i = 0; i < box.getChildCount(); i++) {
            View v = box.getChildAt(i);
            if (mUiProtected.contains(v)) {
                continue;   // 编辑入口三件套：不可隐藏
            }
            if (v.getId() == View.NO_ID) {
                v.setId(0x00A00000 + i);
            }
            // 编辑模式下点它 = 切换隐藏，不能触发它原本的功能
            v.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View vv, MotionEvent e) {
                    if (!mUiEditMode) {
                        return false;   // 正常模式：交给原来的 OnClickListener
                    }
                    if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                        setUiHidden(vv, !isUiHidden(vv));
                        refreshXMark(vv);
                        // 松手时清掉按下态，否则会一直"亮着"
                        vv.setPressed(false);
                    } else if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        vv.setPressed(true);
                    }
                    return true;   // 吃掉事件，不触发原功能
                }
            });
            mUiEditables.add(v);
        }
    }

    /**
     * 存档 key 用**布局位置索引**，不用控件 id。
     *
     * 动态创建的控件 id 是按遍历顺序补发的，可编辑范围一变就可能重排；
     * 而 indexOfChild 是它在容器里的实际位置，只要不往中间插控件就稳定。
     */
    /**
     * 把老格式（box 子控件序号）的隐藏状态一次性搬到新格式（可编辑项序号）。
     *
     * 【为什么必须"先读后写"】
     *   新老 key 的前缀不同，但值域都是小整数，
     *   边读边写的话可能把刚写进去的新值又当老值读出来。
     *   所以先把老值全读进内存，再统一写新 key、清老 key。
     *
     * 【只搬一次】
     *   用 uisch 记版本。跑过之后再进来 sch >= UI_SCHEMA，直接返回。
     */
    private void migrateUiHiddenSchema() {
        android.content.SharedPreferences sp =
                getSharedPreferences(PREF_UI, MODE_PRIVATE);
        int sch = sp.getInt(PREF_UI_SCHEMA, 0);
        if (sch >= UI_SCHEMA) {
            return;
        }
        LinearLayout box = boxOfOverlay();
        collectEditables();

        // 老值：box 序号 -> 是否隐藏
        java.util.HashMap<Integer, Boolean> oldVals =
                new java.util.HashMap<Integer, Boolean>();
        boolean hasOld = false;
        for (int i = 0; i < (box != null ? box.getChildCount() : 0); i++) {
            String k = PREF_UI_HIDE + i;
            if (sp.contains(k)) {
                oldVals.put(i, sp.getBoolean(k, false));
                hasOld = true;
            }
        }

        android.content.SharedPreferences.Editor ed = sp.edit();
        if (hasOld) {
            for (int j = 0; j < mUiEditables.size(); j++) {
                View v = mUiEditables.get(j);
                int oldIdx = (box != null) ? box.indexOfChild(v) : -1;
                Boolean h = (oldIdx >= 0) ? oldVals.get(oldIdx) : null;
                // 老存档里没记过的就当 false，不凭空造 true
                ed.putBoolean(PREF_UI_HIDE_V2 + j, h != null && h);
            }
        }
        // 清掉所有老 key
        for (String k : new java.util.ArrayList<String>(sp.getAll().keySet())) {
            if (k.startsWith(PREF_UI_HIDE)) {
                ed.remove(k);
            }
        }
        ed.putInt(PREF_UI_SCHEMA, UI_SCHEMA);
        ed.apply();
    }

    private String hideKey(View v) {
        int idx = mUiEditables.indexOf(v);
        if (idx < 0) {
            // 集合被清空过（退出编辑时清的）：重新收集一次再找。
            // collectEditables 是幂等的，重复调用不会重复挂监听。
            collectEditables();
            idx = mUiEditables.indexOf(v);
        }
        if (idx < 0) {
            idx = v.getId();   // 兜底，理论上不会发生
        }
        return PREF_UI_HIDE_V2 + idx;
    }

    private boolean isUiHidden(View v) {
        return getSharedPreferences(PREF_UI, MODE_PRIVATE)
                .getBoolean(hideKey(v), false);
    }

    private void setUiHidden(View v, boolean hidden) {
        getSharedPreferences(PREF_UI, MODE_PRIVATE).edit()
                .putBoolean(hideKey(v), hidden).apply();
    }

    /** 进编辑：画面变暗，可编辑项亮起并全部显形，已隐藏的打 X。 */
    private void enterUiEdit() {
        collectEditables();
        mUiEditMode = true;
        if (mUiEditBtn instanceof Button) {
            ((Button) mUiEditBtn).setText("完成");
        }
        LinearLayout box = boxOfOverlay();
        if (box == null) {
            return;
        }
        for (int i = 0; i < box.getChildCount(); i++) {
            View v = box.getChildAt(i);
            if (mUiProtected.contains(v)) {
                continue;
            }
            if (mUiEditables.contains(v)) {
                // 编辑时先全部显形，才能看到（和取消）之前藏起来的
                v.setVisibility(View.VISIBLE);
                refreshXMark(v);
            }
        }
        // 【不再整体压暗】
        //   试过给内容区压一层半透明黑底，实际观感是"界面脏了"而不是
        //   "进入编辑态" —— 控件可读性反而下降。
        //   现在只靠两点标识编辑态：按钮文字变「完成」+ 隐藏项盖红叉。
        //   隐藏项在退出编辑时才真正消失，编辑过程中都看得见，够用了。
    }

    /** 出编辑：清掉 X，按隐藏状态应用 GONE —— LinearLayout 自动收紧空白。 */
    private void exitUiEdit() {
        mUiEditMode = false;
        if (mUiEditBtn instanceof Button) {
            ((Button) mUiEditBtn).setText("编辑主界面");
        }
        LinearLayout box = boxOfOverlay();
        if (box == null) {
            return;
        }
        for (View v : mUiEditables) {
            setXMark(v, false);
            v.setVisibility(isUiHidden(v) ? View.GONE : View.VISIBLE);
        }
        mUiEditables.clear();
    }

    /** 启动时把上次隐藏的结果应用上。 */
    private void applyUiHidden() {
        collectEditables();
        for (View v : mUiEditables) {
            v.setVisibility(isUiHidden(v) ? View.GONE : View.VISIBLE);
        }
    }

    private void refreshXMark(View v) {
        setXMark(v, isUiHidden(v));
    }

    /**
     * 用 ViewOverlay 在控件上叠一个红叉。
     * 不动层级、不抢背景，移除时精确 remove 同一个 Drawable 实例。
     */
    private void setXMark(View v, boolean on) {
        android.graphics.drawable.Drawable old = mUiXMarks.get(v);
        if (old != null) {
            v.getOverlay().remove(old);
            mUiXMarks.remove(v);
        }
        if (!on) {
            return;
        }
        final XMarkDrawable d = new XMarkDrawable();
        mUiXMarks.put(v, d);
        if (v.getWidth() > 0 && v.getHeight() > 0) {
            d.setBounds(0, 0, v.getWidth(), v.getHeight());
            v.getOverlay().add(d);
        } else {
            // 还没 layout 完，等一帧再挂，否则 bounds 是 0 画不出来
            v.post(new Runnable() {
                @Override
                public void run() {
                    View host = null;
                    for (View k : mUiXMarks.keySet()) {
                        if (mUiXMarks.get(k) == d) {
                            host = k;
                            break;
                        }
                    }
                    if (host != null && host.getWidth() > 0) {
                        d.setBounds(0, 0, host.getWidth(), host.getHeight());
                        host.getOverlay().add(d);
                    }
                }
            });
        }
    }

    /** 覆盖在控件上的红色 X（两条对角线）。 */
    private static class XMarkDrawable extends android.graphics.drawable.Drawable {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        XMarkDrawable() {
            mPaint.setColor(0xFFE53935);
            mPaint.setStyle(Paint.Style.STROKE);
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            if (b.isEmpty()) {
                return;
            }
            float s = Math.min(b.width(), b.height()) * 0.06f + 2f;
            mPaint.setStrokeWidth(s);
            float pad = Math.min(b.width(), b.height()) * 0.22f;
            c.drawLine(b.left + pad, b.top + pad, b.right - pad, b.bottom - pad, mPaint);
            c.drawLine(b.right - pad, b.top + pad, b.left + pad, b.bottom - pad, mPaint);
        }

        @Override
        public void setAlpha(int alpha) {
            mPaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            mPaint.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** 一条偏移滑条：-200dp .. +200dp。 */

    /** 显示悬浮窗那边实际算出的自动偏移，方便判断还差多少。 */

    /**
     * 悬浮窗那边点了「改名」，就会带这两个 extra 把本界面拉起来。
     *
     * 为什么非要绕这一圈：悬浮窗上弹不出可用的输入法，
     * 只有正常 Activity 里的 EditText 才能调起系统输入法。
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleRenameIntent(intent);
        handleImportRequest();
        handleSearchRequest();
    }

    /** 导入：悬浮窗点了「导入」并确认后，由这里弹出系统文件选择器。 */
    private static final int REQ_IMPORT_LAYOUT = 4711;

    private void handleImportRequest() {
        if (!FloatingService.consumeImportRequest()) {
            return;
        }
        sRenameFromPad = true;
        // 跳转发起时本界面若已在前台，说明用户本来就在 app 里，
        // 选完不该退出去（那等于把软件关了），只把手柄放回来即可。
        sShouldReturnToGame = !sResumed;
        sAwaitingImportResult = true;
        try {
            Intent it = new Intent(Intent.ACTION_GET_CONTENT);
            // 类型写成拼接形式：源码里若出现连续的斜杠和星号，
            // 静态检查会把它当成注释开头，把后面一段代码全吃掉。
            it.setType("*" + "/" + "*");
            it.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(it, "选择布局存档"),
                    REQ_IMPORT_LAYOUT);
        } catch (Exception e) {
            sAwaitingImportResult = false;
            android.widget.Toast.makeText(this, "打不开文件选择器",
                    android.widget.Toast.LENGTH_SHORT).show();
            finishRename();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_IMPORT_LAYOUT) {
            return;
        }
        // 结果已经回来了，onStop 可以恢复正常收尾
        sAwaitingImportResult = false;
        if (res != RESULT_OK || data == null || data.getData() == null) {
            finishRename();
            return;
        }
        String msg = "导入成功";
        try {
            StringBuilder sb = new StringBuilder();
            java.io.InputStream in =
                    getContentResolver().openInputStream(data.getData());
            if (in != null) {
                java.io.BufferedReader br =
                        new java.io.BufferedReader(new java.io.InputStreamReader(in));
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
                br.close();
            }
            int id = PadLayout.importLayoutJson(this, sb.toString());
            if (id < 0) {
                msg = "导入失败：不是本应用导出的布局存档，或布局数量已满";
            } else {
                FloatingService.notifyLayoutRenamed();
                // 导入完成就自动回游戏（finishRename 里按 sShouldReturnToGame 走）。
                // 手柄此刻是收成球的状态，所以两种情形都要提示"点球展开" ——
                // 回到游戏后球浮在那儿，不点它手柄是不会自己铺开的。
                msg = "已导入为「" + PadLayout.findMeta(this, id).name
                        + "」，点悬浮球重新打开";
            }
        } catch (Exception e) {
            msg = "导入失败：" + e.getMessage();
        }
        android.widget.Toast.makeText(this, msg,
                android.widget.Toast.LENGTH_LONG).show();
        finishRename();
    }

    /**
     * 搜索：悬浮窗点了「搜索」并确认后，在这里弹输入框。
     *
     * 和改名同样的原因：只有 Activity 里的 EditText 能调起输入法。
     * 输完把词交回悬浮窗，然后 finishRename() 自动退回游戏。
     */
    private void handleSearchRequest() {
        if (!FloatingService.consumeSearchRequest()) {
            return;
        }
        sRenameFromPad = true;
        sShouldReturnToGame = !sResumed;
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setSingleLine(true);
        et.setHint("留空 = 取消筛选");
        new android.app.AlertDialog.Builder(this)
                .setTitle("搜索列表项")
                .setView(et)
                .setPositiveButton("搜索", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        String q = et.getText() == null ? "" : et.getText().toString();
                        FloatingService.deliverSearchQuery(q);
                        finishRename();
                    }
                })
                .setNegativeButton("取消", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        finishRename();
                    }
                })
                .setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(android.content.DialogInterface d) {
                        finishRename();
                    }
                })
                .show();
    }

    private void handleRenameIntent(Intent it) {   // it 仅用于判空，不取 extra
        // 【改名请求走一次性令牌，不走 Intent extra】
        //   extra 存在 AMS 的副本里，Activity 清不掉；改完名从最近任务
        //   切回来时会再拿到同一份，输入框又弹出来挡住主界面。
        //   静态令牌取走即清，天然只弹一次。
        //
        // 组合键改名和布局改名是两套令牌，先试组合键那份。
        // 同一时刻只会有一个请求，谁先都行，但顺序要固定，别看运气。
        int[] slotOut = new int[]{-1};
        String[] cNameOut = new String[]{null};
        if (FloatingService.consumeRenameComboRequest(slotOut, cNameOut)) {
            showComboRenameDialog(slotOut[0], cNameOut[0]);
            return;
        }
        int[] keyOut = new int[]{-1};
        String[] keyNameOut = new String[]{null};
        if (FloatingService.consumeRenameKeyRequest(keyOut, keyNameOut)) {
            showKeyRenameDialog(keyOut[0], keyNameOut[0]);
            return;
        }
        String[] colorOut = new String[]{null};
        boolean[] webOut = new boolean[]{false};
        int[] cntOut = new int[]{1};
        if (FloatingService.consumeColorRequest(colorOut, webOut, cntOut)) {
            if (webOut[0]) {
                showColorPickerWeb(colorOut[0], cntOut[0]);
            } else {
                showColorDialog(colorOut[0]);
            }
            return;
        }
        int[] idOut = new int[]{-1};
        String[] nameOut = new String[]{null};
        if (!FloatingService.consumeRenameRequest(idOut, nameOut)) {
            return;
        }
        final int id = idOut[0];
        String cur = nameOut[0];
        sRenameFromPad = true;
        sShouldReturnToGame = !sResumed;

        final android.widget.EditText et = new android.widget.EditText(this);
        et.setText(cur == null ? "" : cur);
        et.setSingleLine(true);
        et.selectAll();
        // 这里用的是 Activity 的 context，window token 是合法的，
        // 所以系统 AlertDialog 能正常 show（悬浮窗里用 Service context 会崩）。
        new android.app.AlertDialog.Builder(this)
                .setTitle("布局改名")
                .setView(et)
                .setNegativeButton("取消", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        finishRename();
                    }
                })
                .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        String n = et.getText().toString().trim();
                        if (!n.isEmpty()) {
                            PadLayout.renameLayout(MainActivity.this, id, n);
                            FloatingService.notifyLayoutRenamed();
                        }
                        finishRename();
                    }
                })
                .setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(android.content.DialogInterface d) {
                        finishRename();
                    }
                })
                .show();
    }

    /**
     * 组合键改名：同样跳到本界面输（悬浮窗弹不出输入法）。
     *
     * 和布局改名唯一的差别是回写目标 —— 布局写进布局元信息，
     * 组合键写进悬浮窗里那份 PadLayout 的 comboName。
     */
    /**
     * 单个按键改名：和组合键改名唯一的差别是回写目标 ——
     * 组合键写 comboName，按键写 customName（保留它原本映射的键）。
     */
    private void showKeyRenameDialog(final int elem, String currentName) {
        sRenameFromPad = true;
        sShouldReturnToGame = !sResumed;

        final android.widget.EditText et = new android.widget.EditText(this);
        et.setText(currentName == null ? "" : currentName);
        et.setSingleLine(true);
        et.selectAll();
        new android.app.AlertDialog.Builder(this)
                .setTitle("按键改名")
                .setView(et)
                .setNegativeButton("取消", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        finishRename();
                    }
                })
                .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        String n = et.getText().toString().trim();
                        // 空串 = "改回默认名"（displayName 见空就退回默认），
                        // 所以这里照写，不做非空过滤。
                        FloatingService.notifyKeyRenamed(elem, n);
                        finishRename();
                    }
                })
                .setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(android.content.DialogInterface d) {
                        finishRename();
                    }
                })
                .show();
    }

    /**
     * 自定义底色：手输 #AARRGGBB。
     *
     * 【Android 没有标准的系统调色盘 Activity】
     *   公开的 Intent 里没有 "pick a color" 这一类（只有取图片/取联系人这些），
     *   android.graphics.Color 也只有解析方法、没有取色界面。
     *   部分厂商 ROM 自带，但没有统一的 Action，不能依赖。
     *   所以走输入框：#RRGGBB、RRGGBB、#RGB 都认，也认 #AARRGGBB。
     */
    /**
     * 网页调色盘：用 WebView 打开自带的 assets/color_picker.html，
     * 靠 HTML 的 &lt;input type="color"&gt; 拿到系统取色器。
     *
     * 【为什么绕这么一圈】
     *   Android 没有公开的"取色" Intent，也没有系统取色 Activity；
     *   但 WebView 实现了 HTML 的 color input，会弹出原生取色面板。
     *   页面放在 assets 里用 file:// 加载，不需要网络权限。
     *
     * 【为什么整块放进 Dialog 而不是 startActivity】
     *   复用现有的 finishRename() 收尾逻辑（改完把悬浮窗放回来），
     *   不用新开一个 Activity 再去 manifest 里注册、再处理返回栈。
     */
    private void showColorPickerWeb(String currentHex, int count) {
        sRenameFromPad = true;
        sShouldReturnToGame = !sResumed;

        String init = (currentHex == null || currentHex.trim().isEmpty())
                ? "FFFFFF" : currentHex.trim().replace("#", "");
        // Java 那边给的一律是 8 位 #AARRGGBB。
        // 前两位拆出来给页面的透明度滑条当初值，后 6 位给 <input type="color">
        // —— 它只认 6 位，给 8 位会取不到色。
        // 这样滑条一开始就在当前透明度的位置上，不会"打开就是 100%"。
        String rgb = init;
        String aa = "FF";
        if (init.length() >= 8) {
            aa = init.substring(0, 2);
            rgb = init.substring(init.length() - 6);
        }
        // 初值走 URL 查询参数：比等页面加载完再 evaluateJavascript 简单，
        // 也不会出现"先闪一下白色再变成当前色"。
        final String url = "file:///android_asset/color_picker.html?c=" + rgb
                + "&a=" + aa + "&n=" + Math.max(1, count);

        /*
          【重写 onCheckIsTextEditor：告诉输入法"这里能打字"】

          输入框点了却什么也不弹，但接上实体键盘又能输入 ——
          说明焦点是真的进去了，只是 InputMethodManager 没把 WebView
          认成"文本编辑器"，于是软键盘压根不启动。
          （硬件键盘走的是按键分发，不经过 IMM 的这一步判断，所以能输。）
        */
        final android.webkit.WebView wv = new android.webkit.WebView(this) {
            @Override
            public boolean onCheckIsTextEditor() {
                return true;
            }
        };
        wv.setFocusable(true);
        // 触摸模式下也要能拿焦点，否则点了输入框焦点被 Dialog 抢回去
        wv.setFocusableInTouchMode(true);
        wv.getSettings().setJavaScriptEnabled(true);
        // 【要开 DOM storage，页面的 localStorage 才可用】
        //   取色页用它记住"透明度用百分制还是 255 制"。
        //   file:// 页面下 localStorage 默认是不可用的，不开的话
        //   每次打开都回到百分制 —— 用户选了 255 制也不生效。
        wv.getSettings().setDomStorageEnabled(true);
        // JS -> Java：页面点「用这个颜色」时回调到这里
        wv.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void showIme() {
                // 页面里输入框拿到焦点时主动叫一下，
                // 不依赖 IMM 自己判断该不该弹。
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            android.view.inputmethod.InputMethodManager imm =
                                    (android.view.inputmethod.InputMethodManager)
                                            getSystemService(INPUT_METHOD_SERVICE);
                            if (imm != null) {
                                imm.showSoftInput(wv,
                                        android.view.inputmethod.InputMethodManager
                                                .SHOW_IMPLICIT);
                            }
                        } catch (Exception ignored) {
                        }
                    }
                });
            }

            @android.webkit.JavascriptInterface
            public void onColor(final String hex) {
                // JS 回调不在主线程，回写要切回去
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        FloatingService.notifyColorSet(hex);
                        finishRename();
                    }
                });
            }
        }, "Android");
        wv.loadUrl(url);

        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("挑个颜色")
                .setView(wv)
                .setNegativeButton("取消", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        wv.destroy();
                        finishRename();
                    }
                })
                .setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(android.content.DialogInterface d) {
                        wv.destroy();
                        finishRename();
                    }
                })
                .create();
        // 对话框关闭（含返回键之外的路径）也要把 WebView 收掉，
        // 否则它还在后台跑 JS。
        dlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface d) {
                try {
                    wv.destroy();
                } catch (Exception ignored) {
                }
            }
        });
        dlg.show();
        /*
          【Dialog 默认带 FLAG_ALT_FOCUSABLE_IM —— 必须清掉】

          这个 flag 的含义是"本窗口表现得好像不需要和输入法交互"：
          IME 不弹起，窗口自己去占那块空间。
          于是点了输入框键盘不出来，但焦点是真的在 ——
          接上实体键盘照样能打字，正好是这个现象。
        */
        dlg.getWindow().clearFlags(
                android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        // 键盘弹起来后把对话框顶上去，别让输入筐被盖住
        dlg.getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        wv.requestFocus();
    }

    private void showColorDialog(String currentHex) {
        sRenameFromPad = true;
        sShouldReturnToGame = !sResumed;

        final String cur = (currentHex == null) ? "#FFFFFFFF" : currentHex;
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setText(cur);
        et.setSingleLine(true);
        et.selectAll();

        /*
          【输入框左边放一块实时预览】

          以前只有一个光秃秃的输入框：改了内容看不出效果，
          只能确定了回到手柄上才知道改成了什么。
          现在按输入内容实时画一块色块，透明度也照实画
          （底下垫灰白格子，透不透一眼看得出来）。
        */
        final android.widget.ImageView preview =
                new android.widget.ImageView(this);
        int pv = (int) (getResources().getDisplayMetrics().density * 40f);
        preview.setLayoutParams(new android.widget.LinearLayout.LayoutParams(pv, pv));
        preview.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);

        // 两行辅助文字：把前两位翻译成百分比和十进制，省得自己算
        final android.widget.TextView info =
                new android.widget.TextView(this);
        info.setTextSize(13f);
        final int padL = (int) (getResources().getDisplayMetrics().density * 28f);
        final int padT = (int) (getResources().getDisplayMetrics().density * 6f);
        info.setPadding(padL, padT, 0, 0);
        info.setText(alphaInfoOf(cur));

        final android.widget.LinearLayout row =
                new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        /*
          【整块往右挪一点】

          对话框自带的左边距偏小，色块贴着边看着局促；
          三块（预览 / 输入框 / 说明文字）用同一个左边距，
          才会对齐成一条竖线 —— 之前说明文字是 0，和上面错开。
        */
        row.setPadding(padL, padT,
                (int) (getResources().getDisplayMetrics().density * 16f), 0);
        row.addView(preview);
        row.addView(et);
        setColorPreview(preview, cur);

        final android.widget.LinearLayout box =
                new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.addView(row);
        box.addView(info);

        // 边打边更新预览和两行数字，不用等确定
        et.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s_, int a, int b, int c) { }
            @Override
            public void onTextChanged(CharSequence s_, int a, int b, int c) { }
            @Override
            public void afterTextChanged(android.text.Editable s_) {
                String v = s_.toString().trim();
                info.setText(alphaInfoOf(v));
                setColorPreview(preview, v);
            }
        });

        new android.app.AlertDialog.Builder(this)
                .setTitle("底色（#AARRGGBB）")
                .setMessage("前两位是透明度（00~FF），后六位是颜色。")
                .setView(box)
                .setNegativeButton("取消", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        finishRename();
                    }
                })
                .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        String v = et.getText().toString().trim();
                        if (v.isEmpty()) {
                            finishRename();
                            return;
                        }
                        FloatingService.notifyColorSet(v);
                        finishRename();
                    }
                })
                .setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(android.content.DialogInterface d) {
                        finishRename();
                    }
                })
                .show();
    }

    /**
     * 把色值的前两位翻译成百分比和十进制两种写法。
     *
     * 【为什么两种都给】
     *   面板上那条滑条可以在百分 / 十进制之间切换，
     *   只有一种的话，切到另一种的人还得自己换算。
     *   分母按 256（和面板一致）：0xC0 = 192 而不是 191。
     */
    private static String alphaInfoOf(String hex) {
        if (hex == null) {
            return "透明度：—";
        }
        String t = hex.trim();
        if (t.startsWith("#")) {
            t = t.substring(1);
        }
        if (t.length() < 8) {
            return "透明度：不带 = 完全不透明（十进制 255）";
        }
        int a;
        try {
            a = Integer.parseInt(t.substring(0, 2), 16);
        } catch (Exception e) {
            return "透明度：前两位不是有效的十六进制";
        }
        /*
          【字节值本身就是那个"十进制数"，不用再换算】

          面板那条滑条显示的是 realA × 256，而写进颜色前两位的
          字节也是 realA × 256 —— 两者本来就是同一个东西。
          所以 0xC0 就显示 192，不再乘 256/255（那样会变 193，差 1）。
        */
        int dec = a;
        int pct = Math.round(a * 100f / 256f);
        String extra = "";
        if (a == 0) {
            extra = "（完全透明 = 看不见）";
        } else if (pct <= 10) {
            extra = "（太低，几乎看不见）";
        } else if (a == 255) {
            extra = "（完全不透明）";
        }
        return "透明度：" + pct + "% ／ 十进制 " + dec + extra;
    }

    /**
     * 把色值画成预览色块。
     *
     * 【底下垫灰白格子】
     *   不垫的话，半透明的色块叠在白色对话框背景上，
     *   看着和不透明的差别很小 —— 透明度等于白调了。
     *   垫格子后透出来的部分一眼能看出来。
     */
    /**
     * 造一块"棋盘格 + 用户色"的预览图。
     *
     * 抽成可以指定尺寸，是因为要用两处：
     *   大的那块放在输入框左边（40dp），
     *   迷你的那块塞进输入框里当行内图标（20dp）。
     */
    private android.graphics.Bitmap makeColorChip(String hex, int size) {
        int argb = 0x00000000;
        if (hex != null) {
            String t = hex.trim();
            if (t.startsWith("#")) {
                t = t.substring(1);
            }
            if (t.length() == 8 || t.length() == 6) {
                try {
                    long v = Long.parseLong(t, 16);
                    if (t.length() == 6) {
                        v |= 0xFF000000L;     // 没写透明度就当不透明
                    }
                    argb = (int) v;
                } catch (Exception ignored) {
                    argb = 0x00000000;
                }
            }
        }
        android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(
                size, size, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bm);

        // 灰白格子底
        android.graphics.Paint pt = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        int cell = Math.max(4, size / 4);
        for (int y = 0; y < size; y += cell) {
            for (int x = 0; x < size; x += cell) {
                boolean odd = ((x / cell) + (y / cell)) % 2 == 0;
                pt.setColor(odd ? 0xFFDDDDDD : 0xFF999999);
                c.drawRect(x, y, x + cell, y + cell, pt);
            }
        }
        // 上面盖用户那个色
        pt.setColor(argb);
        c.drawRect(0, 0, size, size, pt);
        // 描个边，色太淡时也能看清块在哪
        pt.setStyle(android.graphics.Paint.Style.STROKE);
        pt.setColor(0xFF666666);
        pt.setStrokeWidth(2f);
        c.drawRect(1, 1, size - 1, size - 1, pt);
        return bm;
    }

    private void setColorPreview(android.widget.ImageView iv, String hex) {
        final int size = (int) (getResources().getDisplayMetrics().density * 40f);
        iv.setImageBitmap(makeColorChip(hex, size));
    }

    /** 把色值的前两位翻译成"几成透明"，让人看得懂，不用自己算 0xC0。 */
    private static String alphaHintOf(String hex) {
        if (hex == null) {
            return "";
        }
        String t = hex.trim();
        if (t.startsWith("#")) {
            t = t.substring(1);
        }
        if (t.length() < 8) {
            return "不带透明度 = 完全不透明";
        }
        int a;
        try {
            a = Integer.parseInt(t.substring(0, 2), 16);
        } catch (Exception e) {
            return "";
        }
        int pct = Math.round(a * 100f / 256f);
        String extra = "";
        if (a == 0) {
            extra = "（完全透明 = 看不见）";
        } else if (pct <= 10) {
            extra = "（太低，几乎看不见）";
        } else if (a == 255) {
            extra = "（完全不透明）";
        }
        return "透明度 " + pct + "%" + extra;
    }

    private void showComboRenameDialog(final int slot, String currentName) {
        sRenameFromPad = true;
        sShouldReturnToGame = !sResumed;

        final android.widget.EditText et = new android.widget.EditText(this);
        et.setText(currentName == null ? "" : currentName);
        et.setSingleLine(true);
        et.selectAll();
        new android.app.AlertDialog.Builder(this)
                .setTitle("组合键改名")
                .setView(et)
                .setNegativeButton("取消", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        finishRename();
                    }
                })
                .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        String n = et.getText().toString().trim();
                        if (!n.isEmpty()) {
                            FloatingService.notifyComboRenamed(slot, n);
                        }
                        finishRename();
                    }
                })
                .setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(android.content.DialogInterface d) {
                        finishRename();
                    }
                })
                .show();
    }

    /**
     * 改完（或取消）后：先回到主界面，稍停一下，再自动退回之前的游戏。
     *
     * 【为什么要回主界面中转】
     *   直接 finish() 的话，用户刚输完字就被瞬间甩回游戏，
     *   连"改成功了没"都看不着 —— 尤其点「取消」时会以为程序抽了一下。
     *   停在主界面看一眼，再自动退回去，过程才是连贯的。
     *
     * 【为什么用 moveTaskToBack 而不是 finish】
     *   finish() 是把本界面销毁，系统多半会回到上一个应用（正好是游戏），
     *   但这依赖 task 栈 —— 栈里只有自己时就退回桌面了。
     *   moveTaskToBack(true) 是明确的"把整个任务移到后台"，
     *   不管栈里有什么都直接露出下面那个应用，行为稳定。
     */
    private void finishRename() {
        // 直接退回游戏，不在主界面停留。
        //
        // moveTaskToBack 把整个任务移到后台，下面那个应用（游戏）直接露出来。
        if (!sRenameFromPad || isFinishing()) {
            return;
        }
        // 【本来就在 app 内：不要退回游戏】
        //   moveTaskToBack + finish 会把整个任务移到后台并销毁本界面，
        //   用户本来就在软件里操作时，这等于直接退出软件 ——
        //   实测就是从"改个名"变成"软件没了"。
        //   这种情况下只把手柄从 GONE 放回来即可，界面留在原地。
        if (!sShouldReturnToGame) {
            sRenameFromPad = false;
            FloatingService.restorePadAfterRename();
            return;
        }
        moveTaskToBack(true);
        // moveTaskToBack 会触发 onStop，悬浮窗在那儿恢复。
        //
        // 再 finish() 掉自己：不销毁的话 Activity 带着**还开着的对话框**
        // 留在后台，之后从最近任务切回来又看见输入框。
        // finish 在 moveTaskToBack 之后调，界面已经看不见了，不会闪。
        finish();
    }

    /**
     * 回到游戏后再把手柄放出来。
     *
     * 【为什么在 onStop 而不是 finish() 之后立刻恢复】
     *   finish() 只是"请求关闭"，界面这会儿还看得见。
     *   这时候把悬浮窗放出来，它会盖在还没退掉的 app 界面上。
     *   onStop 表示本界面已经真正看不见了，此时才轮到游戏露出来，
     *   悬浮窗这时出现才是对的位置。
     */
    @Override
    protected void onStop() {
        super.onStop();
        sResumed = false;
        // 【不能要求 isFinishing()】
        //   按返回键 -> finish() -> onStop，isFinishing 为 true；
        //   但按 Home 键直接回桌面/游戏，onStop 时 isFinishing 是 false。
        //   只认 isFinishing 的话，Home 键那条路悬浮窗会永远藏在 GONE 状态。
        //   onStop 本身就表示"本界面已经看不见了"，够用了。
        // 正在等文件选择器结果时不要收尾 —— 见 sAwaitingImportResult 的说明
        if (sRenameFromPad && !sAwaitingImportResult) {
            sRenameFromPad = false;
            FloatingService.restorePadAfterRename();
        }
    }

    private void bind(int id, View.OnClickListener l) {
        if (id == 0) {
            return;
        }
        View v = findViewById(id);
        if (v != null) {
            v.setOnClickListener(l);
        }
    }

    /**
     * 按名字取 id，取不到返回 0。
     *
     * 用于新增的控件：直接写 R.id.xxx 的话，一旦 R.java 是旧的（资源没重新编译），
     * 就会报 "Unknown member 'xxx' of 'R.id'" 而整个工程编译不过。
     * 这样写最坏情况只是那个按钮没绑上，不至于编译失败。
     */
    private int idOf(String name) {
        return getResources().getIdentifier(name, "id", getPackageName());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 【sResumed = true 必须放在 handleXxx 之后】
        //
        //   这几个 handleXxx 读 sResumed 来判断"这次跳转发起时本界面是否在前台"。
        //   放在前面的话，Activity 是**新建**时读到的一定是刚置的 true
        //   （新建路径是 onCreate → onResume，压根不走 onNewIntent），
        //   于是"从游戏悬浮窗跳过来"被误判成"用户本来就在 app 里"，
        //   导入完不退回游戏，而是停在主界面。
        //
        //   放在后面：进来时 sResumed 必为 false（onCreate / onStop 都置过），
        //   handleXxx 读到 false → 判定为"从游戏发起" → 完成后退回游戏。
        //   而用户确实正在用 app 时，Activity 已在前台、没走过 onStop，
        //   sResumed 仍是上次 onResume 留下的 true → 判定为"本就在 app 里"。
        //
        //   导入令牌也要在这儿取一次：Activity 是新建的情况下走 onCreate，
        //   拿不到 onNewIntent，令牌会一直挂着没人处理。
        handleImportRequest();
        handleSearchRequest();
        // 从设置页返回时自动重测，用户不用手动点
        refresh();
        refreshPassMode();
        // 放最后：前面那些都是"进入时要处理的事"，
        // 协议弹窗是盖在最上层的，等界面稳定了再弹最稳妥。
        showAgreementIfNeeded();
        /*
          日志放协议之后：协议是"能不能用"的前置条件，
          日志是"这次改了什么"的说明。两个都弹的话，先看协议。
        */
        showChangelogIfUpdated();
    }

    /** 显示当前实际生效的穿透模式，用来判断反射成没成功。 */
    private void refreshPassMode() {
        if (mPassMode != null) {
            mPassMode.setText("当前穿透模式：" + FloatingService.getPassMode(this));
        }
    }

    @Override
    protected void onDestroy() {
        // 不摘掉的话 Activity 会被 Shizuku 的静态 listener 一直持有，造成泄漏
        Shizuku.removeRequestPermissionResultListener(mPermListener);
        Shizuku.removeBinderReceivedListener(mBinderListener);
        super.onDestroy();
    }

    /**
     * Shizuku v11 以下走的是普通运行时权限，结果从这里回来，
     * 转发给同一个 listener，这样两条路的后续处理是同一份代码。
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        if (requestCode == ShizukuRunner.REQ_PERMISSION
                && permissions != null && permissions.length > 0
                && grantResults != null && grantResults.length > 0) {
            mPermListener.onRequestPermissionResult(requestCode, grantResults[0]);
            return;
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    // ------------------------------------------------------------------
    // 状态刷新
    // ------------------------------------------------------------------

    private void refresh() {
        if (mChecking) {
            return;
        }
        mChecking = true;
        mStep.setText("正在检测…");

        final Context ctx = this;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final ActivationHelper.State s = ActivationHelper.evaluate(ctx);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mChecking = false;
                        applyState(s);
                    }
                });
            }
        }).start();
    }

    private void applyState(ActivationHelper.State s) {
        StringBuilder sb = new StringBuilder();
        sb.append(mark(s.overlay)).append(" 悬浮窗权限\n");
        // 注意措辞：查不到不代表没装（可能只是没给"读取应用列表"权限），
        // 所以写"能查到"而不是"已安装"，避免误导。
        sb.append(mark(s.shizukuInstalled)).append(" 能查到 Shizuku（查不到≠没装）\n");
        sb.append(mark(s.wirelessAdb)).append(" 无线调试已开启\n");
        sb.append(mark(s.daemonAlive)).append(" 守护进程运行中");
        // Shizuku 这一行只在真的连上时才显示，否则满屏都是"未连接"反而看不清
        if (ShizukuRunner.isAlive()) {
            boolean granted = ShizukuRunner.hasPermission();
            sb.append("\n").append(mark(granted))
                    .append(" Shizuku 服务已运行、")
                    .append(granted ? "已授权" : "未授权");
        }
        mChecks.setText(sb.toString());

        mAdvice.setText(s.advice());

        switch (s.nextStep) {
            case ActivationHelper.STEP_OVERLAY:
                mStep.setText("第 1 步：点「悬浮窗权限」去授权");
                break;
            case ActivationHelper.STEP_ENABLE_ADB:
                mStep.setText("第 1 步：准备一个能执行命令的环境");
                break;
            case ActivationHelper.STEP_RUN_COMMAND:
                mStep.setText("第 2 步：点「复制命令」，粘贴到 Shizuku / Termux / 电脑 adb 执行");
                break;
            case ActivationHelper.STEP_DONE:
                mStep.setText("就绪：点「启动悬浮手柄」");
                break;
            default:
                mStep.setText("检测中");
                break;
        }

        mStart.setEnabled(s.overlay);
    }

    private static String mark(boolean ok) {
        return ok ? "[√]" : "[×]";
    }

    /**
     * 直接尝试拉起 Shizuku。
     *
     * 检测列表里那一行说"没检测到"不代表没装 —— 系统可能根本不让我们查
     * 应用列表。这个按钮绕过查询直接 startActivity，
     * 拉起来了就是装着，抛异常就是真没装（或没给授权）。
     */
    private void openShizuku() {
        if (ActivationHelper.launchShizuku(this)) {
            Toast.makeText(this, "已尝试打开 Shizuku。能打开就说明装着，"
                    + "只是本 app 查不到它（应用列表权限被限制）", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "打不开 Shizuku —— 多半是真没装。\n"
                + "也可以用 Termux / LADB / 电脑 adb 执行下面的命令，效果一样。",
                Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------
    // 动作
    // ------------------------------------------------------------------

    private void startPad() {
        if (!ActivationHelper.canDrawOverlay(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show();
            requestOverlay();
            return;
        }
        startService(new Intent(this, FloatingService.class));
        Toast.makeText(this, "已启动。状态条显示绿色即为连接成功", Toast.LENGTH_LONG).show();
    }

    /** 直接展开手柄并进入编辑模式，方便在没有游戏画面时也能摆按键。 */
    private void startEdit() {
        if (!ActivationHelper.canDrawOverlay(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show();
            requestOverlay();
            return;
        }
        startService(new Intent(this, FloatingService.class)
                .setAction(FloatingService.ACTION_EDIT)
                .putExtra(FloatingService.EXTRA_FROM_APP, true));
        Toast.makeText(this, "编辑模式：拖动按键移动，点选后用下方滑条调大小/透明度，改完点「完成」",
                Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------
    // 自动激活：拿 Shizuku 权限 -> 自己启动守护进程
    // ------------------------------------------------------------------

    /**
     * 自动激活。
     *
     * 流程：主动要一次 Binder -> 握手 -> 看有没有授权
     *      -> 已授权就直接启动守护进程
     *      -> 未授权就发请求，等用户在 Shizuku 里点「允许」，回调里接着启动
     *
     * 必须说明的边界：非 root 设备上，**app 无法替用户完成授权**。
     * Shizuku 的授权一定得人在 Shizuku 界面点一次（或它自己弹通知）。
     * 我们能做到的是"把请求发出去 + 授权后自动接着干"，不是"绕过授权"。
     */
    private void autoActivate() {
        setStep("正在连接 Shizuku…");
        if (ShizukuRunner.isAlive()) {
            // binder 已经在，直接往下走
            afterBinderReady();
            return;
        }
        // binder 是异步拿到的：用 sticky 版本，
        // 已经在就立刻回调，还没到就等 Shizuku 服务就绪。
        Shizuku.addBinderReceivedListenerSticky(mBinderListener);
        // 兜个底：3 秒还没等到就明确告诉用户服务没在跑，
        // 免得界面一直卡在"正在连接"。
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!ShizukuRunner.isAlive()) {
                    Shizuku.removeBinderReceivedListener(mBinderListener);
                    setStep("Shizuku 服务未运行");
                    toast("Shizuku 服务没在跑 —— 这一步必须你手动启动一次。\n"
                            + "请在 Shizuku 里启动服务后再回来点「自动激活」，\n"
                            + "或者用下面的命令在 Termux / 电脑 adb 里执行。");
                    openShizuku();
                }
            }
        }, 3000);
    }

    /** binder 到手之后：有授权就启动守护进程，没授权就发起请求。 */
    private void afterBinderReady() {
        if (ShizukuRunner.hasPermission()) {
            startDaemonViaShizuku();
            return;
        }
        mPendingDaemon = true;
        setStep("等待你在 Shizuku 里点「允许」…");
        toast("已发起授权请求。请在 Shizuku 里点「允许」，\n"
                + "授权后会自动启动守护进程。");
        ShizukuRunner.requestPermission(this);
    }

    /** 用 Shizuku 的 shell 权限启动守护进程。 */
    private void startDaemonViaShizuku() {
        setStep("正在启动守护进程…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 用和手动粘贴完全一样的那条命令，执行路径不变，风险最低。
                    // （sh -c 里 "CLASSPATH=xxx app_process ..." 是合法的环境变量前缀写法）
                    final String cmd = ActivationHelper.buildDaemonCommand(MainActivity.this);
                    final String err = ShizukuRunner.exec(cmd, new ShizukuRunner.LineSink() {
                        @Override
                        public void onLine(String line) {
                            Log.i("VGamepad", "daemon: " + line);
                        }
                    });
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (err != null) {
                                setStep("启动失败");
                                toast("启动失败：" + err);
                                return;
                            }
                            setStep("守护进程已启动，点「启动悬浮手柄」");
                            toast("守护进程已通过 Shizuku 启动。\n回来看状态条是否为绿色。");
                            refresh();
                        }
                    });
                } catch (final Throwable t) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setStep("启动失败");
                            toast("启动失败：" + t);
                        }
                    });
                }
            }
        }).start();
    }

    private void setStep(final String s) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mStep.setText(s);
            }
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    @SuppressWarnings("deprecation")
    private void copyCommand() {
        String cmd = ActivationHelper.buildDaemonCommand(this);
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setText(cmd);
        }
        Toast.makeText(this, "命令已复制", Toast.LENGTH_SHORT).show();
    }

    /**
     * 动态创建停止区，插到「悬浮窗权限」按钮前面。
     *
     * 容器怎么拿：不新增 id 就没法 findViewById，
     * 但 btn_overlay 的 parent 就是那个 LinearLayout —— 用 getParent() 反查即可。
     */
    private void addStopSection() {
        View anchor = findViewById(R.id.btn_overlay);
        if (!(anchor != null && anchor.getParent() instanceof LinearLayout)) {
            return;
        }
        LinearLayout box = (LinearLayout) anchor.getParent();
        int at = box.indexOfChild(anchor);
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (10 * d);

        TextView title = new TextView(this);
        title.setText("守护进程停止命令");
        title.setTextSize(13f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (18 * d);
        box.addView(title, at++, lp);

        TextView cmd = new TextView(this);
        cmd.setText(ActivationHelper.buildStopCommand());
        cmd.setTextSize(11f);
        cmd.setTypeface(Typeface.MONOSPACE);
        cmd.setBackgroundColor(0x22000000);
        cmd.setPadding(pad, pad, pad, pad);
        cmd.setTextIsSelectable(true);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp2.topMargin = (int) (4 * d);
        box.addView(cmd, at++, lp2);

        Button btn = new Button(this);
        btn.setText("复制停止命令");
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyStopCommand();
            }
        });
        LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp3.topMargin = (int) (6 * d);
        box.addView(btn, at++, lp3);

        Button btnAuto = new Button(this);
        btnAuto.setText("自动停止（用 Shizuku 杀掉守护进程）");
        btnAuto.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopDaemonAuto();
            }
        });
        LinearLayout.LayoutParams lp4 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp4.topMargin = (int) (6 * d);
        box.addView(btnAuto, at++, lp4);

        mStopResult = new TextView(this);
        mStopResult.setTextSize(11f);
        mStopResult.setTypeface(Typeface.MONOSPACE);
        mStopResult.setBackgroundColor(0x22000000);
        mStopResult.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp5 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp5.topMargin = (int) (6 * d);
        box.addView(mStopResult, at++, lp5);
    }

    /** 悬浮窗按钮所在的那个 LinearLayout（顺带作为动态插入的容器）。 */
    /**
     * 主界面底部的「关于 / 开源许可」入口。
     *
     * 【为什么放主界面】
     *   这是给"拿到 APK 的人"看的，而拿到 APK 的人打开的就是主界面。
     *   塞进悬浮窗的工具列表里没人找得到 —— 那是操作按键的地方，不是看说明的地方。
     *
     * 【为什么用代码创建而不改 XML】
     *   本工程只要给 XML 新增带 android:id 的控件，aapt 就会重生成 R.java，
     *   之后 R8 必定崩在 Packaging error（见 addStopSection 的详细说明）。
     *   所以一律 new 出来再插进现有容器。
     */
    private void addAboutButton() {
        LinearLayout box = boxOfOverlay();
        if (box == null) {
            return;
        }
        float d = getResources().getDisplayMetrics().density;

        /*
          找标题「虚拟手柄」，把它那一行换成"标题 + 右上角关于"的横排。

          【为什么是"替换"而不是"插入"】
            隐藏状态的存档 key 用的是 box.indexOfChild 的位置索引
            （见 collectEditables 的说明）。在顶部新插一个控件的话，
            后面所有控件的位置都会 +1，用户已经存好的隐藏设置会整体错位。
            这里把标题移除、在同一个下标换成一个横排容器 ——
            子控件总数不变，后面的索引一个都不动。
        */
        int at = -1;
        View title = null;
        for (int i = 0; i < box.getChildCount(); i++) {
            View v = box.getChildAt(i);
            if (v instanceof TextView) {
                String t = ((TextView) v).getText() == null
                        ? "" : ((TextView) v).getText().toString().trim();
                if (t.equals("虚拟手柄")) {
                    title = v;
                    at = i;
                    break;
                }
            }
        }

        /*
          右上角改成「⋯」菜单。
          直接写 "⋯" 这个字符：用 "..." 三个点在某些字库里会显示成
          三个很矮的点、看不清；U+22EF 是专门的中线省略号。
        */
        final TextView about = new TextView(this);
        String menuTxt = "⋯";
        about.setText(menuTxt);
        about.setTextSize(20f);
        about.setTextColor(0xFF1A73E8);
        about.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        // 加大点击热区：三个点本身太窄，按原大小很难点中
        int pd = (int) (12 * d);
        about.setPadding(pd, (int) (4 * d), pd, (int) (4 * d));
        about.setContentDescription("更多");
        about.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTopMenu(v);
            }
        });

        if (title == null || at < 0) {
            // 找不到标题（文案以后改了）就退到末尾追加。
            // 追加不影响已有下标，索引仍然对得上。
            box.addView(about);
            mUiProtected.add(about);
            return;
        }

        box.removeView(title);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        // 标题吃掉剩下的宽度，把关于挤到最右边
        LinearLayout.LayoutParams lpT = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(title, lpT);
        row.addView(about, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        box.addView(row, at);

        /*
          整行都不可隐藏 —— 这是给"拿到 APK 的人"看许可证的地方，
          一旦能藏起来，使用者就再也找不到开源声明，发布即为不合规。
        */
        mUiProtected.add(row);
    }

    /* ================= 更新日志 ================= */

    /** 日志条目。 */
    private static class LogEntry {
        int code;            // 版本号；-1 = 历史条目（仅首次显示）
        String title;        // 版本标题，如 "26.9.24.13.17"
        StringBuilder body = new StringBuilder();
    }

    /**
     * 解析 assets/更新日志.txt。
     *
     * 文件格式（倒序，新的在前）：
     *   @@VER <versionCode> <标题>
     *   正文若干行
     *
     * 【为什么 versionCode 会有 -1】
     *   早期条目没有可靠的 versionCode（项目早期没记录），
     *   给它们编一个只会是假的。-1 的含义是"历史条目，
     *   只在用户第一次看日志时出现"，之后不再重复打扰。
     */
    private java.util.ArrayList<LogEntry> parseChangelog() {
        java.util.ArrayList<LogEntry> out = new java.util.ArrayList<LogEntry>();
        String raw = readAssetText("更新日志.txt");
        if (raw.length() == 0 || raw.charAt(0) == '（') {
            return out;   // 读失败了（readAssetText 会返回「读取…失败」）
        }
        String[] lines = raw.split("\n");
        LogEntry cur = null;
        for (String ln : lines) {
            if (ln.startsWith("@@VER ")) {
                cur = new LogEntry();
                String[] a = ln.substring(6).trim().split("\\s+", 2);
                try {
                    cur.code = Integer.parseInt(a[0]);
                } catch (Exception e) {
                    cur.code = -1;
                }
                cur.title = (a.length > 1) ? a[1] : "";
                out.add(cur);
            } else if (cur != null) {
                cur.body.append(ln).append('\n');
            }
        }
        return out;
    }

    /** 组一段日志正文：标题 + 内容。 */
    private String renderLog(java.util.ArrayList<LogEntry> list) {
        if (list.isEmpty()) {
            return "没有可显示的更新内容。";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            LogEntry e = list.get(i);
            if (i > 0) {
                sb.append('\n');
            }
            sb.append('【').append(e.title).append("】\n");
            sb.append(e.body.toString().trim()).append('\n');
        }
        return sb.toString();
    }

    /**
     * 挑出"这次该显示"的条目。
     *
     * 【规则】
     *   · versionCode == -1 的历史条目：只在 lastSeen == 0 时出现
     *     （即用户第一次看日志，通常是老版本直接升级上来）
     *   · 有 versionCode 的：只显示 > lastSeen 的
     *
     * 首次（lastSeen=0）：历史 + 当前，全显示
     * 之后升级：只显示新版本那几条，历史不再重复
     */
    private java.util.ArrayList<LogEntry> newEntriesSince(int lastSeen) {
        java.util.ArrayList<LogEntry> all = parseChangelog();
        java.util.ArrayList<LogEntry> out = new java.util.ArrayList<LogEntry>();
        for (LogEntry e : all) {
            if (e.code < 0) {
                if (lastSeen == 0) {
                    out.add(e);
                }
            } else if (e.code > lastSeen) {
                out.add(e);
            }
        }
        return out;
    }

    private static final String PREF_LOG = "changelog";
    private static final String PREF_LOG_KEY = "seen";
    private boolean mLogShown = false;

    private int seenVersion() {
        return getSharedPreferences(PREF_LOG, MODE_PRIVATE).getInt(PREF_LOG_KEY, 0);
    }

    private void markSeen(int code) {
        getSharedPreferences(PREF_LOG, MODE_PRIVATE).edit()
                .putInt(PREF_LOG_KEY, code).apply();
    }

    /** 当前 APK 的 versionCode。取不到就返回 0（按"首次"处理，最安全）。 */
    private int currentVersionCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 有更新就弹一次日志。
     *
     * 【为什么放这里而不是 onCreate】
     *   和协议弹窗同理：界面没到前台时弹，某些 ROM 上会黑屏或被悬浮窗盖住。
     *   mLogShown 保证一次生命周期只弹一次。
     */
    private void showChangelogIfUpdated() {
        if (mLogShown) {
            return;
        }
        int cur = currentVersionCode();
        if (cur <= 0) {
            return;                 // 拿不到版本就不弹，免得每次都弹
        }
        int seen = seenVersion();
        java.util.ArrayList<LogEntry> list = newEntriesSince(seen);
        if (list.isEmpty()) {
            // 没有新内容也要记账，否则下次还会重新判断一遍
            if (seen < cur) {
                markSeen(cur);
            }
            return;
        }
        mLogShown = true;
        showLogDialog(false, new Runnable() {
            @Override
            public void run() {
                // 看完才算看过；中途退出下次还会再弹
            }
        });
    }

    /**
     * 日志对话框。
     *
     * @param all      true = 历史更新（全部）；false = 只显示新增
     */
    private void showLogDialog(boolean all, final Runnable onClose) {
        java.util.ArrayList<LogEntry> list =
                all ? parseChangelog() : newEntriesSince(seenVersion());
        final String text = renderLog(list);
        float d = getResources().getDisplayMetrics().density;

        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setText(text);
        tv.setTextIsSelectable(true);
        tv.setTextSize(11f);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        int p = (int) (12 * d);
        tv.setPadding(p, p, p, p);

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(tv);
        // 和「关于」页一样：setTextIsSelectable 之后链接点击会被吞，
        // 顺序是 先 selectable 再 movementMethod
        // 这里正文没有链接，但保持一致省得出意外
        applyAboutLinks(tv);

        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this)
                .setTitle(all ? "历史更新" : "本次更新")
                .setView(sv)
                .setPositiveButton("知道了", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        markSeen(currentVersionCode());
                        if (onClose != null) {
                            onClose.run();
                        }
                    }
                });
        // 只看新增时，给一个直接看全部的入口
        if (!all) {
            b.setNeutralButton("历史更新",
                    new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int w) {
                            markSeen(currentVersionCode());
                            showLogDialog(true, null);
                        }
                    });
        }
        b.show();
    }

    /**
     * 右上角「⋯」的弹出菜单。
     *
     * 【为什么用 PopupMenu 而不是对话框】
     *   菜单就该贴在按钮旁边、点空白处消失，这两个特性对话框都没有。
     *   PopupMenu 是标准做法，也不需要改 XML。
     *
     * 【为什么不用反射硬塞图标】
     *   PopupMenu 默认不显示图标，要显示得反射调 setForceShowIcon。
     *   反射在厂商 ROM 上可能失效，纯文字菜单足够，不做这个。
     */
    private void showTopMenu(View anchor) {
        android.widget.PopupMenu pm =
                new android.widget.PopupMenu(this, anchor);
        pm.getMenu().add("检查更新");
        pm.getMenu().add("关于 / 开源许可");
        pm.getMenu().add("历史更新");
        pm.setOnMenuItemClickListener(
                new android.widget.PopupMenu.OnMenuItemClickListener() {
                    @Override
                    public boolean onMenuItemClick(android.view.MenuItem item) {
                        String t = item.getTitle() == null
                                ? "" : item.getTitle().toString();
                        if (t.equals("历史更新")) {
                            showLogDialog(true, null);
                        } else if (t.equals("检查更新")) {
                            checkUpdate();
                        } else {
                            showAboutDialog();
                        }
                        return true;
                    }
                });
        pm.show();
    }

    /* ================= 外链 ================= */

    /** 仓库主页。 */
    private static final String URL_REPO =
            "https://github.com/3994305027/VirtualGamepad";
    /** 网盘（123云盘）。Release 说明里没另写网盘时就用它。 */
    private static final String URL_NETDISK =
            "https://1834362934.share.123pan.cn/123pan/6OZQTd-K23hH";
    /** 作者 B 站主页。 */
    private static final String URL_BILIBILI = "https://b23.tv/r6uKjwj";

    /** 用浏览器打开一个地址。打不开（没装浏览器等）就提示一句，不崩。 */
    private void openUrl(String url) {
        try {
            startActivity(new android.content.Intent(
                    android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        } catch (Exception e) {
            toast("打不开这个地址");
        }
    }

    /* ================= 检查更新 ================= */

    /*
      检查更新的顺序：Release API -> jsDelivr -> raw。

      【为什么 Release API 排第一】
        它是实时的，发完 Release 立刻能查到；version.json 还要再传一次文件，
        容易和 Release 对不上（忘了改 / 改错数字）。
        而且 APK 地址直接从 assets 里取，不用手写。

      【为什么后面还要留 version.json】
        Release API 未认证时每小时只有 60 次（按 IP 算），被限流就查不了；
        另外国内裸连 api.github.com 常常不通。留两条后路。

      【jsDelivr 排在 raw 前面】
        raw.githubusercontent.com 有 CDN 缓存 + 国内经常连不上；
        jsDelivr 是 GitHub 的公共镜像，国内可达性通常更好。
    */
    private static final String API_LATEST =
            "https://api.github.com/repos/3994305027/VirtualGamepad/releases/latest";

    private static final String UPDATE_URLS[] = {
            "https://cdn.jsdelivr.net/gh/3994305027/VirtualGamepad@main/version.json",
            "https://raw.githubusercontent.com/3994305027/VirtualGamepad/main/version.json",
    };

    /** 拉下来的版本信息。 */
    private static class UpdateInfo {
        int code;
        String name = "";
        String apkUrl = "";        // GitHub 上的 APK 直链
        String netdiskUrl = "";    // 网盘地址（可选，给用户多一条路）
        String changelog = "";
        String from = "";          // 走通的是哪个源，出问题时好排查
    }

    /** 已注册过下载完成广播就别重复注册，否则一次下载会收到多回调。 */
    private boolean mDlReceiverReg = false;

    private final android.content.BroadcastReceiver mDlReceiver =
            new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context c, android.content.Intent i) {
                    if (!android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(
                            i.getAction())) {
                        return;
                    }
                    long id = i.getLongExtra(
                            android.app.DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                    if (id < 0) {
                        return;
                    }
                    installDownloaded(id);
                }
            };

    /** Android 8.0+ 装 APK 前要确认有"未知来源"授权。 */
    private boolean canInstallFromUnknown() {
        return getPackageManager().canRequestPackageInstalls();
    }

    /** 没授权就跳系统设置页，让用户自己打开。 */
    private void askInstallPermission() {
        try {
            startActivity(new android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    android.net.Uri.parse("package:" + getPackageName())));
            toast("请在设置里允许「安装未知应用」，然后回来重试");
        } catch (Exception e) {
            toast("无法打开设置页，请手动在系统设置里允许安装未知应用");
        }
    }

    /**
     * 检查更新。只在这里发起网络请求 ——
     * 合规声明里写的是"仅用户主动点击时联网"，所以不做启动时自动查。
     */
    private void checkUpdate() {
        final android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setMessage("正在检查更新…");
        pd.setCancelable(true);
        pd.show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                final UpdateInfo info = fetchUpdate();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            pd.dismiss();
                        } catch (Exception ignored) {
                        }
                        if (info == null) {
                            toast("检查更新失败：两个源都连不上，请检查网络");
                            return;
                        }
                        if (info.code <= currentVersionCode()) {
                            toast("已是最新版本（" + info.name + "）");
                            return;
                        }
                        showUpdateDialog(info);
                    }
                });
            }
        }).start();
    }

    /** Release API 优先，失败再依次试 version.json。全失败返回 null。 */
    private UpdateInfo fetchUpdate() {
        UpdateInfo info = fetchLatestRelease();
        if (info != null) {
            return info;
        }
        for (String base : UPDATE_URLS) {
            info = fetchOne(base + "?t=" + System.currentTimeMillis());
            if (info != null) {
                return info;
            }
        }
        return null;
    }

    /**
     * 读 GitHub Releases API 的 latest。
     *
     * 【versionCode 从哪来】
     *   API 只给 tag_name（如 v26.9.25.10.0），给不出 versionCode。
     *   versionName 是字符串，不能直接比大小。
     *   所以约定：在 Release 说明里写一行
     *       versionCode: 385080
     *   这里把它抠出来。没写就返回 null，继续用 version.json。
     *
     * 【网盘】
     *   同样约定一行
     *       网盘: https://...
     *   有就给用户多一个下载入口 —— 你在网盘和 GitHub 同步发布时用得上。
     */
    private UpdateInfo fetchLatestRelease() {
        java.net.HttpURLConnection c = null;
        try {
            java.net.URL url = new java.net.URL(API_LATEST);
            c = (java.net.HttpURLConnection) url.openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestMethod("GET");
            // GitHub API 要求带 User-Agent，不带一律 403
            c.setRequestProperty("User-Agent", "VirtualGamepad");
            c.setRequestProperty("Accept", "application/vnd.github+json");
            int code = c.getResponseCode();
            if (code != 200) {
                // 403 多半是限流（未认证 60 次/小时），交给后面的源兜底
                return null;
            }
            String body = readAll(c.getInputStream());

            org.json.JSONObject o = new org.json.JSONObject(body);
            UpdateInfo info = new UpdateInfo();

            String tag = o.optString("tag_name", "");
            info.name = tag.startsWith("v") ? tag.substring(1) : tag;

            // APK 地址：在 assets 里找第一个 .apk
            org.json.JSONArray assets = o.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    org.json.JSONObject a = assets.optJSONObject(i);
                    if (a == null) {
                        continue;
                    }
                    String n = a.optString("name", "");
                    if (n.toLowerCase().endsWith(".apk")) {
                        info.apkUrl = a.optString("browser_download_url", "");
                        break;
                    }
                }
            }
            if (info.apkUrl.length() == 0) {
                return null;   // 没挂 APK 就不算一个可用 Release
            }

            String note = o.optString("body", "");
            info.changelog = note.trim();
            info.code = intFromNote(note, "versionCode");
            info.netdiskUrl = strFromNote(note, "网盘");
            info.from = "api.github.com";

            if (info.code <= 0) {
                return null;   // 没写 versionCode 就没法比对，走 version.json
            }
            return info;
        } catch (Exception e) {
            Log.w("VGamepad", "读 Release 失败", e);
            return null;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /** 从 Release 说明里抠一个整数，如 "versionCode: 385080"。 */
    private static int intFromNote(String note, String key) {
        for (String ln : note.split("\n")) {
            String t = ln.trim();
            int i = t.indexOf(':');
            int j = t.indexOf('：');
            int p = -1;
            if (i >= 0 && j >= 0) {
                p = Math.min(i, j);
            } else {
                p = (i >= 0) ? i : j;
            }
            if (p < 0) {
                continue;
            }
            if (!t.substring(0, p).trim().equalsIgnoreCase(key)) {
                continue;
            }
            try {
                return Integer.parseInt(t.substring(p + 1).trim());
            } catch (Exception ignored) {
                return 0;
            }
        }
        return 0;
    }

    /** 从 Release 说明里抠一个地址，如 "网盘: https://..."。冒号支持中英文。 */
    private static String strFromNote(String note, String key) {
        for (String ln : note.split("\n")) {
            String t = ln.trim();
            int i = t.indexOf(':');
            int j = t.indexOf('：');
            int p = -1;
            if (i >= 0 && j >= 0) {
                p = Math.min(i, j);
            } else {
                p = (i >= 0) ? i : j;
            }
            if (p < 0) {
                continue;
            }
            if (!t.substring(0, p).trim().equalsIgnoreCase(key)) {
                continue;
            }
            String v = t.substring(p + 1).trim();
            if (v.startsWith("http://") || v.startsWith("https://")) {
                return v;
            }
        }
        return "";
    }

    /** 把流读成字符串。 */
    private static String readAll(java.io.InputStream is) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) {
            bo.write(buf, 0, n);
        }
        is.close();
        return new String(bo.toByteArray(), "UTF-8");
    }

    private UpdateInfo fetchOne(String urlStr) {
        java.net.HttpURLConnection c = null;
        try {
            java.net.URL url = new java.net.URL(urlStr);
            c = (java.net.HttpURLConnection) url.openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestMethod("GET");
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            if (code != 200) {
                return null;
            }
            java.io.InputStream is = c.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
            is.close();

            org.json.JSONObject o = new org.json.JSONObject(
                    new String(bo.toByteArray(), "UTF-8"));
            UpdateInfo info = new UpdateInfo();
            info.code = o.optInt("versionCode", 0);
            info.name = o.optString("versionName", "");
            info.apkUrl = o.optString("apkUrl", "");
            info.changelog = o.optString("changelog", "");
            info.netdiskUrl = o.optString("netdisk", "");
            info.from = urlStr;
            // 版本号或下载地址缺一个就没法更新，当成失败去试下一个源
            if (info.code <= 0 || info.apkUrl.length() == 0) {
                return null;
            }
            return info;
        } catch (Exception e) {
            Log.w("VGamepad", "检查更新失败 " + urlStr, e);
            return null;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private void showUpdateDialog(final UpdateInfo info) {
        StringBuilder sb = new StringBuilder();
        sb.append("新版本：").append(info.name).append("\n\n");
        if (info.changelog.length() > 0) {
            sb.append(info.changelog).append("\n\n");
        }
        sb.append("来源：").append(hostOf(info.from));

        /*
          两个下载入口，一定都有：
            GitHub Release  -> DownloadManager 直接下 APK 并叫起安装器
            网盘            -> 跳转浏览器
          Release 说明里另写了「网盘: ...」就用写的那个，
          没写就用内置的默认网盘地址 —— 保证网盘入口始终存在。
        */
        final String netdisk = (info.netdiskUrl.length() > 0)
                ? info.netdiskUrl : URL_NETDISK;

        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this)
                .setTitle("发现新版本")
                .setMessage(sb.toString())
                .setPositiveButton("GitHub 下载",
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                startDownload(info.apkUrl, info.name);
                            }
                        })
                .setNeutralButton("网盘下载",
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                openUrl(netdisk);
                            }
                        })
                .setNegativeButton("以后再说", null);
        b.show();
    }

    /** 只取域名部分，给用户看是哪个源通的，不暴露完整 URL。 */
    private static String hostOf(String url) {
        try {
            return new java.net.URL(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }

    private void startDownload(String apkUrl, String name) {
        if (!canInstallFromUnknown()) {
            askInstallPermission();
            return;
        }
        if (apkUrl == null || apkUrl.length() == 0) {
            toast("下载地址为空，无法更新");
            return;
        }
        try {
            android.app.DownloadManager dm =
                    (android.app.DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            android.app.DownloadManager.Request r =
                    new android.app.DownloadManager.Request(
                            android.net.Uri.parse(apkUrl));
            r.setMimeType("application/vnd.android.package-archive");
            r.setTitle("虚拟手柄 " + name);
            r.setNotificationVisibility(
                    android.app.DownloadManager.Request
                            .VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            // 放公共 Download 目录：不用 FileProvider，也就不用加 res/xml，
            // 本工程对 res/ 的改动很敏感（加带 id 的控件会让 R8 崩）
            r.setDestinationInExternalPublicDir(
                    android.os.Environment.DIRECTORY_DOWNLOADS,
                    "VirtualGamepad-" + name + ".apk");
            long id = dm.enqueue(r);

            // 注册一次就够，重复注册会让一次下载回调好几次
            if (!mDlReceiverReg) {
                registerReceiver(mDlReceiver, new android.content.IntentFilter(
                        android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE));
                mDlReceiverReg = true;
            }
            toast("已开始下载，完成后会提示安装");
        } catch (Exception e) {
            Log.w("VGamepad", "下载失败", e);
            toast("下载失败：" + e.getMessage());
        }
    }

    /** 下载完成后叫起系统安装器。 */
    private void installDownloaded(long id) {
        try {
            android.app.DownloadManager dm =
                    (android.app.DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            /*
              getUriForDownloadedFile 是 API 24 才有的，返回 content:// URI。
              minSdk 是 26，所以一定有 —— 也就不需要 FileProvider，
              不需要在 res/xml 下加文件。
              自己拼 file:// 在 targetSdk 24+ 会抛 FileUriExposedException。
            */
            android.net.Uri uri = dm.getUriForDownloadedFile(id);
            if (uri == null) {
                toast("找不到下载的文件");
                return;
            }
            if (!canInstallFromUnknown()) {
                askInstallPermission();
                return;
            }
            android.content.Intent i = new android.content.Intent(
                    android.content.Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            Log.w("VGamepad", "安装失败", e);
            toast("安装失败：" + e.getMessage());
        }
    }

    @Override
    protected void onDestroy() {
        // Activity 销毁时一定要反注册，否则 receiver 会一直挂着它，造成泄漏
        if (mDlReceiverReg) {
            try {
                unregisterReceiver(mDlReceiver);
            } catch (Exception ignored) {
            }
            mDlReceiverReg = false;
        }
        super.onDestroy();
    }

    /** 读 assets 下的文本文件；读不到就返回一句说明，不崩。 */
    private String readAssetText(String name) {
        try {
            java.io.InputStream is = getAssets().open(name);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
            is.close();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return "（读取 " + name + " 失败：" + e.getMessage() + "）";
        }
    }

    /**
     * 「关于」对话框：两个页签，NOTICE 和 Apache-2.0 全文。
     *
     * 用 AlertDialog + ScrollView 而不是新建一个 Activity ——
     * 新 Activity 得改 AndroidManifest，而本工程对构建产物的稳定性要求很高，
     * 能不动配置就不动。
     */
    /**
     * 许可证三页签的整体控件（提示 + 页签 + 可滚动正文）。
     *
     * 「关于」对话框和「首次使用协议」对话框用的是同一套，
     * 抽出来是为了防止两处各写一份、以后改一处忘一处
     * （比如刚补的 r3 监听，就容易只在其中一处加上）。
     */
    private static class LicensePager {
        View root;
        android.widget.TextView tv;
        android.widget.ScrollView sv;
        android.widget.RadioButton r1;
        android.widget.RadioButton r2;
        android.widget.RadioButton r3;
    }

    /**
     * 造一份三页签的许可证阅读器。
     *
     * @param tipText  顶部那行灰字提示；传 null 就用默认文案
     * @param onPage  每次换页的回调（可以是 null），参数是当前页号 0/1/2
     */
    private LicensePager buildLicensePager(String tipText,
            final PageListener onPage) {
        final String notice = readAssetText("licenses/NOTICE");
        final String apache = readAssetText("licenses/Apache-2.0.txt");
        final String disc = readAssetText("licenses/DISCLAIMER.md");
        float d = getResources().getDisplayMetrics().density;

        LicensePager p = new LicensePager();

        p.tv = new android.widget.TextView(this);
        p.tv.setTextIsSelectable(true);
        p.tv.setTextSize(11f);
        p.tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        p.tv.setPadding((int) (12 * d), (int) (10 * d), (int) (12 * d), (int) (10 * d));

        p.sv = new android.widget.ScrollView(this);
        p.sv.addView(p.tv);
        p.tv.setText(notice);
        applyAboutLinks(p.tv);

        android.widget.TextView tip = new android.widget.TextView(this);
        tip.setText(tipText != null ? tipText
                : "蓝色链接可点击（用浏览器打开），正文可长按选中复制");
        tip.setTextSize(11f);
        tip.setPadding((int) (12 * d), (int) (6 * d), (int) (12 * d), 0);
        tip.setTextColor(0xFF666666);

        final android.widget.RadioGroup rg =
                new android.widget.RadioGroup(this);
        rg.setOrientation(android.widget.RadioGroup.HORIZONTAL);
        rg.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), 0);

        p.r1 = new android.widget.RadioButton(this);
        p.r1.setText("NOTICE（依赖与权限）");
        p.r1.setTextSize(12f);
        p.r2 = new android.widget.RadioButton(this);
        p.r2.setText("Apache-2.0 全文");
        p.r2.setTextSize(12f);
        p.r3 = new android.widget.RadioButton(this);
        p.r3.setText("免责声明");
        p.r3.setTextSize(12f);

        /*
          【必须显式给 id，否则三个单选按钮会同时被勾上】
          RadioGroup 靠 id 记录"当前选中的是哪个"，勾一个时拿 id 反查、
          把上一个取消掉。new RadioButton(this) 的 id 默认是 NO_ID(-1)，
          全是 -1 时分不清谁是谁 —— 只勾新的、不取消旧的。
        */
        p.r1.setId(View.generateViewId());
        p.r2.setId(View.generateViewId());
        p.r3.setId(View.generateViewId());
        // weight=1：三个平分一行，谁也不会被挤没
        android.widget.RadioGroup.LayoutParams lpR =
                new android.widget.RadioGroup.LayoutParams(
                        0, android.widget.RadioGroup.LayoutParams.WRAP_CONTENT, 1f);
        rg.addView(p.r1, lpR);
        rg.addView(p.r2, lpR);
        rg.addView(p.r3, lpR);

        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.addView(tip);
        root.addView(rg);
        root.addView(p.sv);
        p.root = root;

        // 三份文本捕获成 final，供回调里直接取用
        final String[] texts = new String[]{notice, apache, disc};
        final LicensePager fp = p;

        /*
          【只挂 RadioGroup 监听，不要再给按钮各挂一份、也不要手动 setChecked(false)】

          之前为了"保险"，除了 RadioGroup 回调，还给三个按钮各挂了一个
          CompoundButton 回调，并且在里面手动把另外两个 setChecked(false)。
          结果反而坏了 —— 见 switchPage 上面的说明。

          互斥这件事 RadioGroup 本来就做，前提是三个按钮都有 id（前面已设）。
          再叠加一层手动取消，等于两套机制互相打断。
        */
        rg.setOnCheckedChangeListener(
                new android.widget.RadioGroup.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(android.widget.RadioGroup g, int id) {
                        switchPage(id, fp, texts, onPage);
                    }
                });

        /*
          用 rg.check() 而不是 r1.setChecked(true)：
            check() 会走完整的 setCheckedId 流程，mCheckedId 被正确记录；
            直接 setChecked 只改按钮自己的状态，RadioGroup 那边的记录
            可能不同步（首次点击时就会表现异常）。
        */
        rg.check(p.r1.getId());   // 触发一次，让正文显示第一页
        return p;
    }

    private interface PageListener {
        void onPage(int page);
    }

    /**
     * 页签切换：换正文 + 通知外部。
     *
     * 【这里绝不能手动 setChecked(false)】
     *
     *   手动取消会触发 RadioGroup 内部的 CheckedStateTracker，
     *   而那个 tracker 有个要命的特点：**它不检查 isChecked**，
     *   只要被调用就把 mCheckedId 设成"当前这个 view 的 id"。
     *
     *   于是点第 2 个时：
     *     ① 按钮自己 setChecked(true) → 我们的回调 → 显示第 2 页 ✓
     *     ② 我们手动把第 1 个 setChecked(false)
     *     ③ 这触发 tracker → mCheckedId 被设成**第 1 个**的 id
     *     ④ 回到 ① 的后续，tracker 再跑：
     *        "mCheckedId != -1" → 把第 1 个取消（无害）
     *     ⑤ 但此时若再来一次 tracker，它会去取消 **第 2 个**
     *        —— 用户刚点的那个被取消了，圈就灭了。
     *
     *   而正文是 ① 里就设好的，不受影响 —— 于是出现
     *   "文本切了但没有任何一个圈是亮的"。
     *
     *   且因为 ③ 把 mCheckedId 记成了旧值，下一次点击的时序又不一样，
     *   所以表现为"有时亮有时不亮"，交替出现。
     *
     * 【正确做法】互斥交给 RadioGroup，这里只管换正文。
     *
     * pager 和 texts 由参数传入而不是存成 Activity 字段 ——
     * 字段的话对话框还开着就可能被清掉，之后切页签会静默失效。
     */
    private void switchPage(int id, LicensePager p, String[] texts,
                            PageListener onPage) {
        int page = pageOf(id, p.r1, p.r2, p.r3);
        showAboutPage(page, p.tv, p.sv, texts[0], texts[1], texts[2]);
        if (onPage != null) {
            onPage.onPage(page);
        }
    }

    /*
      ---- 首次使用协议 ----

      用 int 版本号而不是 boolean：以后协议内容有实质更新时，
      把 AGREE_VERSION +1 就能让老用户重新确认一遍；
      换成 boolean 的话只能靠"清数据"才能让协议再弹出来。
    */
    private static final String PREF_AGREE = "agreement";
    private static final String PREF_AGREE_KEY = "ver";
    private static final int AGREE_VERSION = 1;

    private boolean hasAgreed() {
        return getSharedPreferences(PREF_AGREE, MODE_PRIVATE)
                .getInt(PREF_AGREE_KEY, 0) >= AGREE_VERSION;
    }

    private void setAgreed() {
        getSharedPreferences(PREF_AGREE, MODE_PRIVATE).edit()
                .putInt(PREF_AGREE_KEY, AGREE_VERSION).apply();
    }

    /**
     * 首次启动弹出使用条款。没看过 / 版本变过就弹。
     *
     * 【为什么放在 onResume 而不是 onCreate】
     *   onCreate 里弹，界面还没真正显示到前台，某些 ROM 上会出现
     *   "弹了但屏幕是黑的"或者弹窗被后续启动的悬浮窗盖住。
     *   onResume 时界面已经可见，弹窗一定在最上层。
     *   用 mAgreeShown 保证一次生命周期内只弹一次（onResume 会多次触发）。
     */
    private void showAgreementIfNeeded() {
        if (hasAgreed() || mAgreeShown) {
            return;
        }
        mAgreeShown = true;
        showAgreementDialog();
    }

    /**
     * 使用条款对话框：三项必须都看过，才能点「同意」。
     *
     * 【为什么强制看完三项】
     *   只给一个"同意"按钮的话，用户一秒点掉，等于没告知 ——
     *   真出纠纷时这份协议起不到任何作用。
     *   要求三个页签都翻过才能继续，才算真的"看过"。
     */
    private void showAgreementDialog() {
        final boolean[] seen = new boolean[3];
        // 一进来显示的就是第 0 页，算已看
        seen[0] = true;

        LicensePager pager = buildLicensePager(
                "请先阅读以下三项，全部看完后才能继续。蓝色链接可点击。",
                new PageListener() {
                    @Override
                    public void onPage(int page) {
                        if (page >= 0 && page < seen.length) {
                            seen[page] = true;
                        }
                        updateAgreeButton(seen);
                    }
                });

        mAgreeBtn = null;
        final android.app.AlertDialog dlg =
                new android.app.AlertDialog.Builder(this)
                        .setTitle("使用条款与开源许可")
                        .setView(pager.root)
                        .setCancelable(false)      // 返回键也关不掉
                        .setPositiveButton("同意并继续",
                                new android.content.DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(
                                            android.content.DialogInterface d, int w) {
                                        setAgreed();
                                    }
                                })
                        .setNegativeButton("拒绝并退出",
                                new android.content.DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(
                                            android.content.DialogInterface d, int w) {
                                        // 直接退出整个应用，不留后台
                                        finishAffinity();
                                    }
                                })
                        .show();

        /*
          AlertDialog 的按钮要等 show() 之后才存在，
          而且要等 onShow 才拿到实例 —— 所以在这里取并置灰。
        */
        dlg.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                .setEnabled(false);
        mAgreeBtn = dlg.getButton(
                android.content.DialogInterface.BUTTON_POSITIVE);
        updateAgreeButton(seen);
    }

    /** 三项都看过才放开「同意」；没看完时给出还剩几项。 */
    private void updateAgreeButton(boolean[] seen) {
        if (mAgreeBtn == null) {
            return;
        }
        int left = 0;
        for (boolean b : seen) {
            if (!b) {
                left++;
            }
        }
        mAgreeBtn.setEnabled(left == 0);
        mAgreeBtn.setText(left == 0 ? "同意并继续" : ("还需阅读 " + left + " 项"));
    }

    private void showAboutDialog() {
        LicensePager pager = buildLicensePager(null, null);

        String ver = "";
        try {
            ver = "  v" + getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }

        /*
          底部加一排外链：仓库 / 网盘 / B站。

          【为什么不用对话框自带的按钮】
            AlertDialog 只有 Positive / Negative / Neutral 三个位，
            这里要三个链接，再加「关闭」就是四个，放不下。

          【为什么不改成 ListView】
            ListView 要 new Adapter，还要处理点击；
            三个固定入口用横排按钮最直接，也不用改 XML。
        */
        android.widget.LinearLayout links =
                new android.widget.LinearLayout(this);
        links.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        float d = getResources().getDisplayMetrics().density;
        int lp = (int) (8 * d);
        links.setPadding(lp, lp, lp, 0);

        addLinkButton(links, "仓库", URL_REPO);
        addLinkButton(links, "网盘", URL_NETDISK);
        addLinkButton(links, "B站", URL_BILIBILI);

        // 外链排在正文下面
        pager.root.addView(links);

        new android.app.AlertDialog.Builder(this)
                .setTitle("关于" + ver)
                .setView(pager.root)
                .setPositiveButton("关闭", null)
                .show();
    }

    /** 往 links 里加一个等宽的链接按钮。weight=1 让三个平分一行。 */
    private void addLinkButton(android.widget.LinearLayout parent,
                               final String text, final String url) {
        android.widget.Button b = new android.widget.Button(this);
        b.setText(text);
        b.setTextSize(12f);
        b.setAllCaps(false);
        b.setBackgroundColor(0x00000000);       // 透明底，不破坏对话框观感
        b.setTextColor(0xFF1A73E8);
        android.widget.LinearLayout.LayoutParams pm =
                new android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        b.setLayoutParams(pm);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openUrl(url);
            }
        });
        parent.addView(b);
    }

    /**
     * 把文本里的网址变成可点链接（蓝色、点击用浏览器打开）。
     *
     * 【为什么要自己写一遍，不能只在 XML 里配 autoLink】
     *   这个 TextView 是代码 new 出来的，没有 XML 属性可用。
     *
     * 【为什么只认 http/https】
     *   Linkify 默认的 WEB_URLS 还会匹配 "www." 开头和纯域名，
     *   而许可证正文里满是 "13.1.5"、"2021" 这类数字 ——
     *   默认规则会把它们也变成链接，点开是无效地址。
     *   用自定义 Pattern 限定死只认完整网址，避免误伤。
     *
     * 【为什么一定要 setMovementMethod】
     *   Linkify 只负责把文字染蓝、加上 URLSpan；真正处理点击的是
     *   MovementMethod。默认的 ArrowKeyMovementMethod 会优先走
     *   "选中文本"的交互，把点击吞掉 —— 表现就是"颜色变了但点了没反应"。
     *   LinkMovementMethod 才会在点击时去启动浏览器。
     */
    private void applyAboutLinks(android.widget.TextView tv) {
        java.util.regex.Pattern url = java.util.regex.Pattern.compile(
                "https?://[^\\s，。）)】\"']+");
        android.text.util.Linkify.addLinks(tv, url, "http://");
        tv.setMovementMethod(
                android.text.method.LinkMovementMethod.getInstance());
        tv.setLinksClickable(true);
    }

    /**
     * 切换「关于」对话框里显示哪一份文本。
     *
     * 抽成一个函数是因为有三个地方会调它（初始化、RadioGroup 回调、
     * 两个按钮各自的回调），分开写迟早会有一处忘了滚回顶部。
     */
    /** 三个页签里当前是第几个。用 id 反查，别用"是不是等于某个"的硬判断。 */
    private static int pageOf(int id, View r1, View r2, View r3) {
        if (id == r2.getId()) {
            return 1;
        }
        if (id == r3.getId()) {
            return 2;
        }
        return 0;
    }

    private void showAboutPage(int page, android.widget.TextView tv,
                               final android.widget.ScrollView sv,
                               String notice, String apache, String disc) {
        tv.setText(page == 1 ? apache : (page == 2 ? disc : notice));
        /*
          必须放在 setText 之后：setText 会把上一次加的 URLSpan 全部清掉，
          不重新加一遍的话，切回来链接就变回普通文字了。
        */
        applyAboutLinks(tv);

        /*
          【为什么换页会跳到最底部】

          这个 TextView 开了 setTextIsSelectable(true) —— 为了让许可证全文
          能选中复制。代价是它的内容变成了 Editable，于是：

            1. 换页 setText 之后，光标 / 选区停在**文本末尾**；
            2. 而 fullScroll(FOCUS_UP) 内部会 requestFocus 给这个 TextView；
            3. TextView 获得焦点时会 bringTextIntoView()，把光标滚进可视区
               —— 光标在末尾，于是整块被拉到最底部。

          也就是说：不是"没滚到顶"，是"滚到顶了又被光标拽回底部"。
          （NOTICE 那页短、容易被忽略，Apache 全文长，一跳就很明显。）

          改法两步，都不依赖上面这套时序推测：
            · 换页把光标压回开头，让它没理由往下拽；
            · 用 scrollTo(0,0) 代替 fullScroll —— 后者夹带焦点逻辑，
              前者只是纯粹地把滚动位置设成 0。
        */
        try {
            // 光标归位到开头。放在 setText 之后：setText 会把它挪到末尾
            android.text.Selection.setSelection(
                    (android.text.Spannable) tv.getText(), 0);
        } catch (Exception ignored) {
            // 文本不是 Spannable（理论上不会发生）：忽略，靠下面的 scrollTo
        }

        sv.scrollTo(0, 0);
        sv.post(new Runnable() {
            @Override
            public void run() {
                sv.scrollTo(0, 0);
                // 内容刚换，布局可能还没走完 —— 再压一帧兜住
                sv.post(new Runnable() {
                    @Override
                    public void run() {
                        sv.scrollTo(0, 0);
                    }
                });
            }
        });
    }

    private LinearLayout boxOfOverlay() {
        View anchor = findViewById(R.id.btn_overlay);
        if (anchor != null && anchor.getParent() instanceof LinearLayout) {
            return (LinearLayout) anchor.getParent();
        }
        return null;
    }

    /**
     * 把主界面底部那段"本 app 无法自行以 shell 身份执行命令"改掉。
     *
     * 那段话是早期没接 Shizuku SDK 时写的，现在已经能自动激活、自动停止了，
     * 留着会误导。
     *
     * 【为什么不直接改 XML】那个 TextView 没有 id，而本工程只要动 XML 就可能
     * 触发 R8 崩溃（见 addStopSection 的说明）。所以只能在代码里遍历找它。
     * 用"是否包含特定文字"来认，比按下标猜稳。
     */
    private void updateShellNote(LinearLayout box) {
        if (box == null) {
            return;
        }
        for (int i = 0; i < box.getChildCount(); i++) {
            View v = box.getChildAt(i);
            if (!(v instanceof TextView)) {
                continue;
            }
            CharSequence t = ((TextView) v).getText();
            if (t != null && t.toString().indexOf("无法自行以 shell") >= 0) {
                ((TextView) v).setText("说明：点「自动激活」，本 app 会通过 Shizuku "
                        + "自行执行启动命令；若 Shizuku 未运行，也可以复制命令粘贴到 "
                        + "Shizuku / Termux / LADB / 电脑 adb 中执行。"
                        + "执行后守护进程会挂起不返回，回到本页会自动重新检测。");
                return;
            }
        }
    }

    private void setStopResult(String t) {
        if (mStopResult != null) {
            mStopResult.setText(t);
        }
    }

    /**
     * 用 Shizuku 直接杀掉守护进程。
     *
     * 和「自动激活」是同一套前提：Shizuku 服务必须已经在跑。
     * 服务没起的话，app 自己没有任何办法以 shell 身份执行命令，
     * 只能提示去手动复制（这就是旁边那个复制按钮的用途）。
     */
    private void stopDaemonAuto() {
        if (!ShizukuRunner.isAlive()) {
            setStopResult("Shizuku 服务未运行，本 app 无法自己执行。\n"
                    + "请点上面的「复制停止命令」，粘贴到 Shizuku / Termux / 电脑 adb 执行。");
            return;
        }
        if (!ShizukuRunner.hasPermission()) {
            setStopResult("正在请求 Shizuku 授权…\n允许后会自动继续。");
            mPendingStop = true;
            ShizukuRunner.requestPermission(this);
            return;
        }
        setStopResult("正在停止…");
        // 短命令可以等，但不能在 UI 线程上等，会 ANR
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String r = ShizukuRunner.execSync(
                        ActivationHelper.buildStopCommand(), 5000);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setStopResult(r);
                        refresh();
                    }
                });
            }
        }).start();
    }

    @SuppressWarnings("deprecation")
    private void copyStopCommand() {
        String cmd = ActivationHelper.buildStopCommand();
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setText(cmd);
        }
        Toast.makeText(this, "停止命令已复制", Toast.LENGTH_SHORT).show();
    }

    private void requestOverlay() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(i, REQ_OVERLAY);
        } else {
            Toast.makeText(this, "悬浮窗权限已授予", Toast.LENGTH_SHORT).show();
        }
    }
}
