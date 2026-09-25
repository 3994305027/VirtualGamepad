package com.example.vgamepad;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;

/**
 * Owns two overlay windows:
 *   - a small draggable bubble (default, never blocks the screen)
 *   - the full-screen gamepad (only while expanded)
 *
 * Three independent ways to get out of the gamepad, so you can never get stuck:
 *   1. the red "收" button at the top of the gamepad
 *   2. the "收起手柄" action in the notification
 *   3. long-press the bubble -> stop the service completely
 */
public class FloatingService extends Service implements GamepadView.Sink {

    /** 供 MainActivity 回调用：改完名要通知悬浮窗刷新。 */
    private static FloatingService sInstance;

    /** 改名期间悬浮窗被临时设为 GONE，改完要恢复。 */
    private boolean mPadHiddenForRename;

    /**
     * 一次性的改名请求。
     *
     * 【为什么不用 Intent extra】
     *   Intent 的 extra 存在 AMS 那份副本里，Activity 自己改不掉。
     *   结果就是：改完名从最近任务切回来，又拿到同一份 extra，
     *   输入框再弹一次 —— 挡住主界面。
     *   （onCreate 里判 savedInstanceState 也挡不住，因为重开的是**新实例**，
     *    savedInstanceState 本来就是 null。）
     *
     *   用静态变量当令牌，取走就清掉，天然一次性。
     *   代价是进程被杀后请求会丢 —— 那种情况下只是不弹输入框，
     *   回到主界面而已，不会卡住。
     */
    private static int sPendingRenameId = -1;
    private static String sPendingRenameName;

    /** 取走改名请求（取完即清，保证只弹一次）。 */
    public static boolean consumeRenameRequest(int[] outId, String[] outName) {
        if (sPendingRenameId < 0) {
            return false;
        }
        outId[0] = sPendingRenameId;
        outName[0] = sPendingRenameName;
        sPendingRenameId = -1;
        sPendingRenameName = null;
        return true;
    }

    // ---- 组合键改名（和布局改名同一套令牌机制）----

    private static String sPendingKeyName = null;
    private static int sPendingComboSlot = -1;
    /** 正在改名的按键元素下标（app 界面回来时用）。 */
    private static int sPendingKeyElem = -1;
    /** 自定义底色的待处理请求：跳到 app 界面输 #RRGGBB。 */
    private static String sPendingColorHex = null;
    /** 这次取色走网页调色盘（true）还是手输（false）。 */
    private static boolean sPendingColorWeb = false;
    /** 这次取色会应用到几个键。 */
    private static int sPendingColorCount = 1;

    /**
     * 取走"按键改名"请求（取走即清，避免切回来又弹一次）。
     *
     * 和组合键改名同一套一次性令牌机制：extra 存在 AMS 副本里清不掉，
     * 改完从最近任务切回来会再拿到同一份。
     */
    public static boolean consumeRenameKeyRequest(int[] elemOut, String[] nameOut) {
        if (sPendingKeyElem < 0) {
            return false;
        }
        elemOut[0] = sPendingKeyElem;
        nameOut[0] = sPendingKeyName;
        sPendingKeyElem = -1;
        sPendingKeyName = null;
        return true;
    }
    private static String sPendingComboName;

    public static boolean consumeRenameComboRequest(int[] outSlot, String[] outName) {
        if (sPendingComboSlot < 0) {
            return false;
        }
        outSlot[0] = sPendingComboSlot;
        outName[0] = sPendingComboName;
        sPendingComboSlot = -1;
        sPendingComboName = null;
        return true;
    }

    private static final int NOTI_ID = 9001;
    private static final String CHANNEL_ID = "vgamepad";
    public static final String ACTION_TOGGLE = "com.example.vgamepad.TOGGLE";
    public static final String ACTION_STOP = "com.example.vgamepad.STOP";
    /** 直接展开手柄并进入布局编辑模式 */
    public static final String ACTION_EDIT = "com.example.vgamepad.EDIT";
    /**
     * ACTION_EDIT 的附加参数：true = 从 app 自己的界面点进来的，
     * 这时底下没有游戏画面，编辑界面会铺一层黑底，点「完成」直接收起浮层。
     */
    public static final String EXTRA_FROM_APP = "from_app";

    private WindowManager mWm;
    private GamepadView mPadView;
    private BubbleView mBubbleView;
    private WindowManager.LayoutParams mPadParams;
    private WindowManager.LayoutParams mBubbleParams;

    private final SocketLink mLink = new SocketLink();
    private final Handler mHandler = new Handler();

    private boolean mPadShown;
    private boolean mBubbleShown;

    // ---- 按键独立窗口 ----
    /**
     * 窗口硬上限。
     *
     * 【为什么从 96 降到 32】
     *   96 是按"键盘 82 个键"算的，但系统根本不允许一个 UID 开这么多
     *   TYPE_APPLICATION_OVERLAY 窗口 —— 实测开到几十个就被批量回收，
     *   接着 app 崩溃。32 是实测能稳住的数量。
     */
    private static final int MAX_KEY_WINDOWS = 32;

    /**
     * 启用穿透的窗口数阈值。
     *
     * 可点元素超过这个数，就**完全不建**独立窗口，退回全屏触摸模式：
     * 手柄布局 ~20 个 → 开窗口，空白能穿透；
     * 键盘布局 80+ 个 → 不开窗口，不穿透但绝对不崩。
     */
    private static final int MAX_PASS_THROUGH_KEYS = 24;

    private final KeyTouchView[] mKeyViews = new KeyTouchView[MAX_KEY_WINDOWS];
    private final WindowManager.LayoutParams[] mKeyParams =
            new WindowManager.LayoutParams[MAX_KEY_WINDOWS];
    private final int[] mKeyElem = new int[MAX_KEY_WINDOWS];
    private int mKeyCount;
    private Point mScreen;
    private int mBubbleSize;

    // ---- bubble drag state ----
    private float mDownRawX, mDownRawY;
    private int mDownX, mDownY;
    private boolean mMoved;
    private long mDownTime;
    private static final int DRAG_SLOP = 8;

    @Override
    public void onCreate() {
        sInstance = this;
        super.onCreate();
        mWm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        connectAsync();
        startForegroundCompat();
        showBubble();
        mHandler.post(mStatusTick);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String a = intent.getAction();
            if (ACTION_TOGGLE.equals(a)) {
                toggle();
            } else if (ACTION_EDIT.equals(a)) {
                showPad(true, intent.getBooleanExtra(EXTRA_FROM_APP, false));
            } else if (ACTION_STOP.equals(a)) {
                stopSelf();
            }
        }
        return START_STICKY;
    }

    private void startForegroundCompat() {
        Notification n = buildNotification();
        startForeground(NOTI_ID, n);
    }

    private Notification buildNotification() {
        Context ctx = this;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Virtual Gamepad", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }

        Intent toggle = new Intent(this, FloatingService.class).setAction(ACTION_TOGGLE);
        PendingIntent piToggle = PendingIntent.getService(
                this, 1, toggle, pendingFlags());
        Intent stop = new Intent(this, FloatingService.class).setAction(ACTION_STOP);
        PendingIntent piStop = PendingIntent.getService(
                this, 2, stop, pendingFlags());

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(ctx, CHANNEL_ID);
        } else {
            b = new Notification.Builder(ctx);
        }
        b.setContentTitle("虚拟手柄")
                .setContentText(mPadShown ? "点此收起手柄" : "点此展开手柄")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(piToggle)
                .setOngoing(true);
        if (Build.VERSION.SDK_INT >= 16) {
            b.addAction(0, mPadShown ? "收起" : "展开", piToggle);
            b.addAction(0, "停止", piStop);
        }
        return b.build();
    }

    private int pendingFlags() {
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            f |= PendingIntent.FLAG_IMMUTABLE;
        }
        return f;
    }

    private void refreshNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTI_ID, buildNotification());
    }

    private int overlayType() {
        if (Build.VERSION.SDK_INT >= 26) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        return WindowManager.LayoutParams.TYPE_PHONE;
    }

    // ---------------- bubble ----------------

    private void showBubble() {
        if (mBubbleShown) return;
        hidePadQuiet();   // 互斥：任何时刻最多只能有一个覆盖层窗口
        mScreen = screenSize();
        //
        // 【悬浮球大小 / 样式读「悬浮窗」布局里那颗 G】
        //   写死 52dp 的话，在悬浮窗布局里把 G 调大调小对真球毫无影响。
        PadLayout.BubbleStyle bSty = PadLayout.bubbleStyle(
                this, refW(), refH(), refH() > refW());
        mBubbleSize = (int) (dp(52) * (bSty.scale > 0.2f ? bSty.scale : 1f));
        if (mBubbleSize < dp(28)) {
            mBubbleSize = (int) dp(28);      // 太小点不中
        }
        mBubbleView = new BubbleView(this);
        mBubbleView.applyStyle(bSty);
        mBubbleParams = new WindowManager.LayoutParams(
                mBubbleSize, mBubbleSize,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        mBubbleParams.gravity = Gravity.TOP | Gravity.LEFT;
        // 悬浮球与「收」按钮共用同一个位置存档：点开在哪，收起就在哪
        // portrait 判定也用悬浮窗尺寸：存档是相对悬浮窗的，
        // 横竖屏两份的分流口径自然也该跟着悬浮窗走。
        // 用 mScreen 判的话，万一两者的横竖判定不一致（旋转未同步），
        // 就会读到另一份存档 —— 表现为"位置突然跳到别处"。
        float[] rel = PadLayout.collapseRel(
                this, refW(), refH(), refH() > refW());
        // 【参考系必须和存档一致】
        //   rel 是相对悬浮窗尺寸的比例，这里就乘悬浮窗尺寸（refW/refH），
        //   不能乘 mScreen —— 横屏下两者不等，气泡会飘到别处。
        //   只有 clamp 用 mScreen：那是"别让气泡出屏"的硬边界，
        //   跟位置口径无关。
        final boolean fPortrait = mScreen.y > mScreen.x;

        // 【主路径：绝对定位法】
        //   已测过气泡原点 + 有「收」的绝对坐标存档 → 直接反解 params，一步到位。
        //   不依赖累加、不受切换朝向影响 —— 切回横屏第一次就准。
        int[] bOrigin = loadBubbleOrigin(fPortrait);
        float[] cAbs = loadCollapseAbs(fPortrait);
        boolean absOk = (bOrigin != null && cAbs != null);
        if (absOk) {
            mBubbleParams.x = clampInt(
                    (int) cAbs[0] - mBubbleSize / 2 - bOrigin[0],
                    0, Math.max(0, mScreen.x - mBubbleSize));
            mBubbleParams.y = clampInt(
                    (int) cAbs[1] - mBubbleSize / 2 - bOrigin[1],
                    0, Math.max(0, mScreen.y - mBubbleSize));
        } else {
            // 兜底：比例法（首次启动、还没测过气泡原点时走这条）。
            // 不减任何偏差 —— 偏差那套已被"存气泡原点"方案取代，
            // 两者混用会互相污染（累加值会被绝对法读到，越补越偏）。
            mBubbleParams.x = clampInt((int) (rel[0] * refW()) - mBubbleSize / 2,
                    0, Math.max(0, mScreen.x - mBubbleSize));
            mBubbleParams.y = clampInt((int) (rel[1] * refH()) - mBubbleSize / 2,
                    0, Math.max(0, mScreen.y - mBubbleSize));
        }

        mBubbleView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        mDownRawX = e.getRawX();
                        mDownRawY = e.getRawY();
                        mDownX = mBubbleParams.x;
                        mDownY = mBubbleParams.y;
                        mMoved = false;
                        mDownTime = System.currentTimeMillis();
                        mBubbleView.setPressed(true);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - mDownRawX;
                        float dy = e.getRawY() - mDownRawY;
                        if (!mMoved && Math.abs(dx) + Math.abs(dy) > DRAG_SLOP) {
                            mMoved = true;
                        }
                        if (mMoved) {
                            mBubbleParams.x = clampInt((int) (mDownX + dx),
                                    0, mScreen.x - mBubbleSize);
                            mBubbleParams.y = clampInt((int) (mDownY + dy),
                                    0, mScreen.y - mBubbleSize);
                            mWm.updateViewLayout(mBubbleView, mBubbleParams);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        mBubbleView.setPressed(false);
                        long dur = System.currentTimeMillis() - mDownTime;
                        if (dur > 600 && !mMoved) {
                            // long press -> stop everything
                            toast("已停止虚拟手柄");
                            stopSelf();
                        } else if (mMoved) {
                            // 拖动过就把新位置记下来，「收」按钮下次会出现在同一处
                            saveBubblePos();
                        } else {
                            showPad(false);
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        mBubbleView.setPressed(false);
                        return true;
                }
                return false;
            }
        });

        mWm.addView(mBubbleView, mBubbleParams);
        mBubbleShown = true;

        // addView 之后才能拿到真实屏幕坐标（未 attach 时 getLocationOnScreen 返回 0）
        postBubbleCorrection();
    }

    /**
     * 气泡显示（或旋转）之后实测原点、把位置校正到目标绝对坐标。
     *
     * 抽成方法是为了让 showBubble 和 onConfigurationChanged 共用同一套逻辑 ——
     * 之前旋转走的是另一段"纯比例法、完全没校正"的旧代码，
     * 于是每次旋转完第一次位置必偏，得手动开合一次才恢复。
     */
    private void postBubbleCorrection() {
        if (mBubbleView == null) {
            return;
        }
        mBubbleView.post(new Runnable() {
            @Override
            public void run() {
                if (!mBubbleShown || mBubbleView == null) {
                    return;
                }
                int[] loc = new int[2];
                mBubbleView.getLocationOnScreen(loc);
                int bx = loc[0] + mBubbleSize / 2;
                int by = loc[1] + mBubbleSize / 2;
                int ox = loc[0] - mBubbleParams.x;   // 气泡窗口原点
                int oy = loc[1] - mBubbleParams.y;
                

                boolean portrait = mScreen.y > mScreen.x;

                // 【目标坐标优先取存档】
                //   mLastCollapseAbs 是上次收起时测的，切换朝向那一瞬
                //   它记的还是旧朝向的值，拿它当目标会"越补越错"。
                //   存档按朝向分开，读当前朝向那份才对；
                //   内存值只在朝向匹配时兜底。
                float[] saved = loadCollapseAbs(portrait);
                final float fTx;
                final float fTy;
                if (saved != null) {
                    fTx = saved[0];
                    fTy = saved[1];
                } else if (mLastCollapseAbsX >= 0 && mLastCollapseAbsY >= 0
                        && mLastCollapsePortrait == portrait) {
                    fTx = mLastCollapseAbsX;
                    fTy = mLastCollapseAbsY;
                } else {
                    
                    return;
                }
                

                // 【第一步：存下气泡窗口原点】
                //   系统常量（气泡尺寸固定 → 被挤进安全区的位置固定），
                //   只跟朝向有关。存一次，以后直接反解。
                if (!(ox == 0 && oy == 0)) {
                    saveBubbleOrigin(portrait, ox, oy);
                    
                }

                // 【第二步：按绝对坐标反解 params】
                //   屏幕坐标 = 原点 + params  →  params = 屏幕坐标 − 原点
                int wantX = (int) fTx - mBubbleSize / 2 - ox;
                int wantY = (int) fTy - mBubbleSize / 2 - oy;
                if (wantX != mBubbleParams.x || wantY != mBubbleParams.y) {
                    mBubbleParams.x = clampInt(wantX,
                            0, Math.max(0, mScreen.x - mBubbleSize));
                    mBubbleParams.y = clampInt(wantY,
                            0, Math.max(0, mScreen.y - mBubbleSize));
                    try {
                        mWm.updateViewLayout(mBubbleView, mBubbleParams);
                        
                    } catch (Exception ignored) {
                        return;
                    }
                }

                // 【第三步：二轮校验】clamp 截断或原点微调会有残差，
                // 120ms 后再测一次补掉。肉眼基本无感。
                mBubbleView.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (!mBubbleShown || mBubbleView == null) {
                            return;
                        }
                        int[] l2 = new int[2];
                        mBubbleView.getLocationOnScreen(l2);
                        int bx2 = l2[0] + mBubbleSize / 2;
                        int by2 = l2[1] + mBubbleSize / 2;
                        int dx2 = (int) fTx - bx2;
                        int dy2 = (int) fTy - by2;
                        
                        if (dx2 == 0 && dy2 == 0) {
                            return;
                        }
                        mBubbleParams.x = clampInt(mBubbleParams.x + dx2,
                                0, Math.max(0, mScreen.x - mBubbleSize));
                        mBubbleParams.y = clampInt(mBubbleParams.y + dy2,
                                0, Math.max(0, mScreen.y - mBubbleSize));
                        try {
                            mWm.updateViewLayout(mBubbleView, mBubbleParams);
                            
                        } catch (Exception ignored) {
                        }
                    }
                }, 120);
            }
        });
    }

    private void hideBubble() {
        if (!mBubbleShown) return;
        try {
            mWm.removeView(mBubbleView);
        } catch (Exception ignored) {
        }
        mBubbleShown = false;
        mBubbleView = null;
    }

    // ---------------- gamepad ----------------

    private void showPad() {
        showPad(false, false);
    }

    private void showPad(boolean edit) {
        showPad(edit, false);
    }

    /**
     * 展开时待套用的「收」按钮位置（屏幕绝对像素），< 0 表示没有。
     *
     * 在 hideBubble() **之前**从气泡上取下来 —— 气泡一销毁就测不到位置了。
     */
    private float mPendingCollapseAbsX = -1f;
    private float mPendingCollapseAbsY = -1f;
    private boolean mPendingCollapseValid = false;

    /**
     * 把气泡位置换算成「收」按钮的位置并套用。
     *
     * 必须 post：刚 addView 时 padView 还没 layout，宽高是 0，
     * getLocationOnScreen 也拿不到真实原点。
     */
    private void applyPendingCollapse() {
        if (!mPendingCollapseValid || mPadView == null) {
            return;
        }
        int pw = mPadView.getWidth();
        int ph = mPadView.getHeight();
        if (pw <= 0 || ph <= 0) {
            // 还没 layout 完，下一帧再来
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    applyPendingCollapse();
                }
            });
            return;
        }
        int[] loc = new int[2];
        mPadView.getLocationOnScreen(loc);
        float rx = (mPendingCollapseAbsX - loc[0]) / pw;
        float ry = (mPendingCollapseAbsY - loc[1]) / ph;
        mPendingCollapseValid = false;
        mPadView.setCollapseRel(rx, ry);
    }

    private void showPad(boolean edit, boolean fromApp) {
        if (mPadShown) {
            // 【改名期间悬浮窗被设成 GONE 了】
            //   用户改完名停在主界面，这时点「启动」/「编辑」，
            //   窗口还在（mPadShown 为 true）只是不可见 ——
            //   不放出来的话点了按钮却什么都没出现。
            if (mPadHiddenForRename) {
                mPadHiddenForRename = false;
                if (mPadView != null) {
                    mPadView.setVisibility(android.view.View.VISIBLE);
                }
            }
            if (edit && mPadView != null) {
                mPadView.setEditMode(true, fromApp);
            }
            return;
        }
        // 【展开前先把气泡位置记下来】
        //   顺序必须在 hideBubble() 之前，气泡销毁后就测不到了。
        mPendingCollapseValid = false;
        if (mBubbleShown) {
            float[] bc = bubbleAbsCenter();
            if (bc != null) {
                mPendingCollapseAbsX = bc[0];
                mPendingCollapseAbsY = bc[1];
                mPendingCollapseValid = true;
            }
        }
        mPadView = new GamepadView(this, this);
        mPadView.setPassThroughHost(mPassHost);
        mPadView.setEditMode(edit, fromApp);
        mPadParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        mPadParams.gravity = Gravity.TOP | Gravity.LEFT;
        // 记下初始 flag，restorePadFlags() 要用它还原
        mPadBaseFlags = mPadParams.flags;
        mWm.addView(mPadView, mPadParams);
        mPadShown = true;
        // 气泡在哪，「收」就在哪 —— 展开的反向同步，见 applyPendingCollapse
        if (mPendingCollapseValid) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    applyPendingCollapse();
                }
            });
        }
        // 【新 View = 新 ViewRootImpl = 新 ViewTreeObserver】
        //   旧的 insets listener 挂在上一个 VTO 上，对新窗口无效。
        //   不显式清掉的话 ensureListener() 会因为 mListener != null 直接复用，
        //   于是新窗口的 VTO 上根本没有 listener —— region 永远收不到，
        //   而 apply() 因为 probe 是手动 invoke 的，照样返回 true。
        if (mRegionHelper != null) {
            mRegionHelper.resetForHost();
        }
        mRegionActive = false;
        hideBubble();
        refreshNotification();
        scheduleKeySync();
    }

    /** Remove the gamepad window without showing the bubble (used for mutual exclusion). */
    private void hidePadQuiet() {
        if (!mPadShown) return;
        removeAllKeyWindows();
        try {
            mWm.removeView(mPadView);
        } catch (Exception ignored) {
        }
        mPadShown = false;
        mPadView = null;
        if (mLink != null) mLink.reset();
    }

    private void hidePad() {
        if (!mPadShown) return;
        // 收起前把「收」按钮的最新位置同步给悬浮球，保证两者永远同位置
        if (mPadView != null) {
            // 先记下"当前在用哪个布局"，再同步位置。
            // 顺序不能反：setCollapseRel 内部要读这个 id 来决定写哪份存档，
            // 晚了就写进默认布局去了。
            PadLayout.saveCurrentLayoutId(this, mPadView.currentLayoutId());
            mScreen = screenSize();
            // 收起时优先用「收」按钮的真实像素尺寸做参考 —— 它才是存档口径。
            // mPadView 还在，直接问它最准（比 refW/refH 的缓存更实时）。
            int pw = mPadView.getWidth();
            int ph = mPadView.getHeight();
            if (pw <= 0 || ph <= 0) {
                pw = refW();
                ph = refH();
            }
            float[] rel = mPadView.collapseRel();
            // 【朝向判定必须用 mScreen，不能用 padView 尺寸】
            //   刚旋转完那一瞬，padView 可能还是旧朝向的尺寸（未重新 layout），
            //   拿 ph > pw 判定会得出"还是竖屏"，于是把横屏的坐标
            //   写进竖屏存档、反之亦然 —— 槽位串了，下次读出来就是错的。
            //   mScreen = screenSize() 是刚取的实时值，可靠。
            //   showBubble 读的时候也是用 mScreen 判，两边口径必须一致。
            final boolean nowPortrait = mScreen.y > mScreen.x;
            PadLayout.setCollapseRel(this, pw, ph, nowPortrait, rel[0], rel[1]);

            int[] vloc = new int[2];
            mPadView.getLocationOnScreen(vloc);
            mLastCollapseAbsX = (int) (vloc[0] + rel[0] * pw);
            mLastCollapseAbsY = (int) (vloc[1] + rel[1] * ph);
            mLastCollapsePortrait = nowPortrait;
            
            saveCollapseAbs(mLastCollapseAbsX, mLastCollapseAbsY, nowPortrait);
            // 记下 padView 原点：拖气泡后换算 rel 要用（那时 padView 已销毁）
            savePadOrigin(nowPortrait, vloc[0], vloc[1]);
        }
        hidePadQuiet();
        showBubble();
        refreshNotification();
    }

    private void toggle() {
        if (mPadShown) hidePad();
        else showPad();
    }

    @Override
    public void onCollapse() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                hidePad();
            }
        });
    }

    // ---------------- socket ----------------

    private void connectAsync() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    mLink.connect();
                } catch (final IOException e) {
                    mLink.setLastError(e.getClass().getSimpleName() + ": " + e.getMessage());
                    Log.w("VGamepad", "initial connect failed", e);
                }
            }
        }).start();
    }

    /** Push connection state to the overlay every second. */
    private final Runnable mStatusTick = new Runnable() {
        @Override
        public void run() {
            boolean ok = mLink.isConnected();
            if (!ok) {
                // keep trying so starting the daemon later just works
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        mLink.ensureConnected();
                    }
                }).start();
            }
            if (ok && !mPrevLinkOk) {
                // 刚刚重连成功 —— 补推一次持续型状态，
                // 否则重连后摇杆/扳机在守护进程那边是 0，而手机这边还停在中位以外。
                resendHeldState();
            }
            mPrevLinkOk = ok;
            if (mPadView != null) {
                // 守护进程还带着旧描述符在跑：报告和描述符对不上，
                // 按键映射就会乱。必须让用户看见，而不是默默错下去。
                //
                // 【这里踩过一个坑】原来写的是 stale = (dver != PROTOCOL_VERSION)，
                // 没加 ok 判断。守护进程没启动时 dver 是初始值 -1，
                // -1 != 3 成立 -> 一上来就显示橙点"旧进程 v-1≠3"。
                // 实际上那会儿根本没有进程，谈不上新旧。
                // 版本对不上只有在"连上了、也问到版本号了"的前提下才有意义。
                int dver = mLink.getDaemonVersion();
                boolean stale = ok && dver != GpadDaemon.PROTOCOL_VERSION;
                mPadView.setStatus(ok, mLink.getSentCount(), mLink.getLastError(), stale, dver);
            }
            mHandler.postDelayed(this, 1000);
        }
    };

    private void toast(final String msg) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(FloatingService.this, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---------------- 屏幕 / 位置工具 ----------------

    private Point screenSize() {
        Point p = new Point();
        try {
            Display d = mWm.getDefaultDisplay();
            if (d != null) {
                d.getSize(p);
            }
        } catch (Exception ignored) {
        }
        if (p.x <= 0 || p.y <= 0) {
            p.x = getResources().getDisplayMetrics().widthPixels;
            p.y = getResources().getDisplayMetrics().heightPixels;
        }
        return p;
    }

    /** 悬浮球被拖到哪，就把「收」按钮的位置存档更新到哪。 */
    private void saveBubblePos() {
        if (mScreen == null) {
            mScreen = screenSize();
        }
        // 【关键修正】用气泡的**屏幕绝对坐标**换算 rel，不能用 params。
        //
        // params.x/y 是相对**气泡窗口**原点的，而 rel 是给 padView 用的
        // （padView 是另一个窗口，原点不同 —— 实测 Y 差 110）。
        // 之前直接拿 params 除尺寸，等于漏减了 padView 原点，
        // 于是"拖完气泡再点开，收按钮往上偏一截"。
        //
        // 正确公式：rel = (气泡屏幕坐标 − padView原点) / padView尺寸
        // 这样"收"按钮算回来的屏幕位置 = padView原点 + rel×尺寸 = 气泡位置，
        // 两者严格重合。
        boolean portrait = mScreen.y > mScreen.x;
        float[] c = bubbleAbsCenter();
        int[] origin = loadPadOrigin(portrait);
        float rx;
        float ry;
        if (c != null && origin != null && refW() > 0 && refH() > 0) {
            rx = (c[0] - origin[0]) / (float) refW();
            ry = (c[1] - origin[1]) / (float) refH();
            
        } else {
            // 拿不到绝对坐标（极少数情况）：退回旧算法，至少不崩
            rx = (mBubbleParams.x + mBubbleSize / 2f) / Math.max(1, refW());
            ry = (mBubbleParams.y + mBubbleSize / 2f) / Math.max(1, refH());
            
        }
        PadLayout.setCollapseRel(this, Math.max(1, refW()), Math.max(1, refH()),
                portrait, rx, ry);
        // 绝对坐标那份也必须更新 —— 否则下次显示时校正会把它拽回拖动前的位置。
        // 用实测屏幕坐标，不能拿 params 直接当屏幕坐标（原点被安全区挤过）。
        // c 在上面已经取过了（同一个方法内不能重名），这里直接复用。
        if (c != null) {
            saveCollapseAbs(c[0], c[1], portrait);
        }
    }

    private static int clampInt(int v, int lo, int hi) {
        if (hi < lo) return lo;
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    // 悬浮窗（GamepadView）的真实尺寸，由 onPadSize() 上报。
    //
    // 【位置存档的参考系】
    //   「收」按钮的 rx/ry 是相对**悬浮窗**尺寸的，
    //   所以悬浮球换算回像素时也必须乘悬浮窗尺寸。
    //   之前乘的是 screenSize() —— 横屏 / 有导航栏时它和悬浮窗尺寸不等，
    //   于是"点开在 A、收起跑到 B"（横屏下表现为气泡往上飘）。
    private int mPadW = 0;
    private int mPadH = 0;

    @Override
    public void onPadSize(int w, int h) {
        if (w > 0 && h > 0) {
            mPadW = w;
            mPadH = h;
        }
    }

    /**
     * 位置换算该用的参考尺寸。
     *
     * 悬浮窗尺寸已知就用它（和存档口径一致）；
     * 还没展开过时退回屏幕尺寸 —— 只在首次启动那一瞬发生，
     * 展开过一次之后就永远是准确值。
     */
    private int refW() {
        return mPadW > 0 ? mPadW : (mScreen != null ? mScreen.x : 0);
    }

    private int refH() {
        return mPadH > 0 ? mPadH : (mScreen != null ? mScreen.y : 0);
    }

    /**
     * 把"给布局改名"交给 app 界面。
     *
     * 悬浮窗上弹不出可用的输入法（NOT_FOCUSABLE + 悬浮窗盖在输入法之上），
     * 所以启动 MainActivity，让它在正常窗口里弹系统输入框。
     *
     * 【先把悬浮窗藏起来】
     *   悬浮窗是全屏且盖在最上层，不藏的话它会盖住 app 的输入框，
     *   改名界面根本点不到。
     *
     * 【用 setVisibility 而不是 removeView】
     *   删掉窗口再重建的话，编辑模式、当前选中的键、正在开着的
     *   布局列表全都会丢 —— 改完名回来还得重新一路点进去。
     *   设成 GONE 只是不画、不接收触摸，View 实例和里面的状态全在，
     *   恢复时一行 setVisibility(VISIBLE) 就回到原样。
     */
    @Override
    public void requestRenameLayout(int layoutId, String currentName) {
        try {
            if (mPadShown && mPadView != null) {
                mPadView.setVisibility(android.view.View.GONE);
                mPadHiddenForRename = true;
            }
            sPendingRenameId = layoutId;
            sPendingRenameName = currentName;
            Intent it = new Intent(this, MainActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            it.putExtra(MainActivity.EXTRA_RENAME_ID, layoutId);
            it.putExtra(MainActivity.EXTRA_RENAME_NAME, currentName);
            startActivity(it);
        } catch (Exception ignored) {
        }
    }

    /** 导入请求的一次性令牌（和改名同理，避免从最近任务切回来重复弹）。 */
    private static boolean sPendingImport = false;

    public static boolean consumeImportRequest() {
        boolean r = sPendingImport;
        sPendingImport = false;
        return r;
    }

    @Override
    public void requestImportLayout() {
        try {
            // 【导入：收起成悬浮球，而不是只设 GONE】
            //
            //   选文件要离开游戏，这段路上一片黑、什么都没有的话，
            //   用户会以为悬浮窗崩了。收成球（G 图标）看得见、点得到，
            //   导入完回来一眼就知道"点它就能把手柄叫回来"。
            //
            //   顺带这也更干净：hidePad() 会把 GamepadView 整个销毁，
            //   回来后点球重新展开 = 新建一个 View，布局列表自然重新读盘，
            //   刚导入的那套一定在列表里，不用再手动刷新。
            //
            //   和改名 / 搜索不同 —— 那两个是"原地改完接着用"，
            //   窗口状态得留着（GONE），所以它们仍走 mPadHiddenForRename。
            if (mPadShown) {
                hidePad();
            }
            sPendingImport = true;
            Intent it = new Intent(this, MainActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(it);
        } catch (Exception ignored) {
        }
    }

    // ---- 列表搜索 ----
    //   和改名 / 导入同一套路：悬浮窗上弹不出输入法，只能跳到 Activity
    //   用 EditText 输入。一次性令牌，避免从最近任务切回来又弹一次。
    private static boolean sPendingSearch = false;

    public static boolean consumeSearchRequest() {
        boolean r = sPendingSearch;
        sPendingSearch = false;
        return r;
    }

    @Override
    public void requestSearchList() {
        try {
            if (mPadShown && mPadView != null) {
                mPadView.setVisibility(android.view.View.GONE);
                mPadHiddenForRename = true;
            }
            sPendingSearch = true;
            Intent it = new Intent(this, MainActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(it);
        } catch (Exception ignored) {
        }
    }

    /**
     * app 那边输完搜索词，交回悬浮窗。
     *
     * 【不能在这里直接 setVisibility(VISIBLE)】
     *   此刻 Activity 还盖在上面，放出来会挡住它。
     *   真正的恢复在 MainActivity.finishRename() 之后
     *   （restorePadAfterRename 里，那时已经退回游戏了）。
     */
    public static void deliverSearchQuery(String q) {
        FloatingService fs = sInstance;
        if (fs == null || fs.mPadView == null) {
            return;
        }
        fs.mPadView.applySearchQuery(q);
    }

    /** app 界面改完名后通知悬浮窗刷新列表（此时悬浮窗还是隐藏的）。 */
    public static void notifyLayoutRenamed() {
        FloatingService fs = sInstance;
        if (fs != null && fs.mPadView != null) {
            fs.mPadView.refreshLayoutList();
        }
    }

    /**
     * 组合键改名：和布局改名走同一条路 —— 悬浮窗里弹不出输入法，
     * 所以跳到 app 界面去输，改完由 MainActivity 回写进来。
     */
    @Override
    public void requestRenameCombo(int slot, String currentName) {
        try {
            if (mPadShown && mPadView != null) {
                mPadView.setVisibility(android.view.View.GONE);
                mPadHiddenForRename = true;
            }
            sPendingComboSlot = slot;
            sPendingComboName = currentName;
            Intent it = new Intent(this, MainActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            it.putExtra(MainActivity.EXTRA_RENAME_COMBO_SLOT, slot);
            it.putExtra(MainActivity.EXTRA_RENAME_NAME, currentName);
            startActivity(it);
        } catch (Exception ignored) {
        }
    }

    /**
     * 单个按键改名：和组合键改名同一条路，只是回写目标换成元素下标。
     */
    @Override
    public void requestRenameKey(int element, String currentName) {
        try {
            if (mPadShown && mPadView != null) {
                mPadView.setVisibility(android.view.View.GONE);
                mPadHiddenForRename = true;
            }
            sPendingKeyElem = element;
            sPendingKeyName = currentName;
            Intent it = new Intent(this, MainActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            it.putExtra(MainActivity.EXTRA_RENAME_KEY_ELEM, element);
            it.putExtra(MainActivity.EXTRA_RENAME_NAME, currentName);
            startActivity(it);
        } catch (Exception ignored) {
        }
    }

    /** 取走自定义底色请求（取走即清，只弹一次）。 */
    public static boolean consumeColorRequest(String[] hexOut, boolean[] webOut,
            int[] countOut) {
        if (sPendingColorHex == null) {
            return false;
        }
        hexOut[0] = sPendingColorHex;
        webOut[0] = sPendingColorWeb;
        countOut[0] = sPendingColorCount;
        sPendingColorHex = null;
        sPendingColorWeb = false;
        sPendingColorCount = 1;
        return true;
    }

    /** app 界面输完颜色回写（此时悬浮窗还是隐藏的）。 */
    public static void notifyColorSet(String hex) {
        FloatingService fs = sInstance;
        if (fs != null && fs.mPadView != null) {
            fs.mPadView.setBtnColorHex(hex);
        }
    }

    /**
     * 自定义底色：和改名同一条路 —— 悬浮窗弹不出输入法，跳 app 界面去输。
     */
    @Override
    public void requestColorHex(String currentHex, boolean useWeb, int count) {
        try {
            if (mPadShown && mPadView != null) {
                mPadView.setVisibility(android.view.View.GONE);
                mPadHiddenForRename = true;
            }
            sPendingColorHex = currentHex;
            sPendingColorWeb = useWeb;
            sPendingColorCount = count;
            Intent it = new Intent(this, MainActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(it);
        } catch (Exception ignored) {
        }
    }

    /** app 界面改完按键名后回写（此时悬浮窗还是隐藏的）。 */
    public static void notifyKeyRenamed(int elem, String name) {
        FloatingService fs = sInstance;
        if (fs != null && fs.mPadView != null) {
            fs.mPadView.renameKeyName(elem, name);
        }
    }

    /** app 界面改完组合键名后回写（此时悬浮窗还是隐藏的）。 */
    public static void notifyComboRenamed(int slot, String name) {
        FloatingService fs = sInstance;
        if (fs != null && fs.mPadView != null) {
            fs.mPadView.renameComboSlot(slot, name);
        }
    }

    /**
     * 改名界面关掉后把悬浮窗放出来。
     * 由 MainActivity 在**已经退回游戏之后**（onStop 且 isFinishing）调用，
     * 早一步的话悬浮窗会盖在 app 界面上。
     */
    public static void restorePadAfterRename() {
        FloatingService fs = sInstance;
        if (fs == null || !fs.mPadHiddenForRename) {
            return;
        }
        fs.mPadHiddenForRename = false;
        if (fs.mPadShown && fs.mPadView != null) {
            fs.mPadView.setVisibility(android.view.View.VISIBLE);
            fs.mPadView.refreshLayoutList();
            fs.mPadView.invalidate();
        } else {
            // 期间被收起了：那就正常显示气泡 / 手柄
            fs.showPad();
        }
    }

    @Override
    public void onButton(int index, boolean pressed) {
        mLink.button(index, pressed);
    }

    /**
     * 最近一次推给守护进程的持续型状态（摇杆 / 扳机 / 十字键）。
     *
     * 【为什么 app 这边也要记一份】
     *   这些是"状态"不是"事件"：守护进程那边只在收到命令时更新，
     *   它重启 / 断线重连之后自己的报告是空的（复位过），
     *   而手机这边摇杆可能还停在中位以外的地方。
     *   不重推的话，重连后摇杆位置就丢了 —— 手柄在那儿、报告里却是 0。
     *   所以断线重连成功后立刻按这份缓存重推一遍。
     */
    private final float[] mLastAxis = new float[4];
    private final float[] mLastTrig = new float[2];
    private int mLastHat = 8;
    /** 上一次巡检时的连接状态，用来发现"刚刚重连成功"这一刻。 */
    private boolean mPrevLinkOk;

    /** 重连成功后把持续型状态补推一遍。 */
    private void resendHeldState() {
        if (mLink == null || !mLink.isConnected()) {
            return;
        }
        for (int i = 0; i < mLastAxis.length; i++) {
            if (mLastAxis[i] != 0f) {
                mLink.axis(i, mLastAxis[i]);
            }
        }
        for (int i = 0; i < mLastTrig.length; i++) {
            if (mLastTrig[i] != 0f) {
                mLink.trigger(i, mLastTrig[i]);
            }
        }
        if (mLastHat != 8) {
            mLink.hat(mLastHat);
        }
        Log.w("VGamepad", "reconnected -> resent held state (drop #"
                + mLink.getDropCount() + ")");
    }

    @Override
    public void onAxis(int axis, float value) {
        if (axis >= 0 && axis < mLastAxis.length) {
            mLastAxis[axis] = value;
        }
        mLink.axis(axis, value);
    }

    @Override
    public void onHat(int hat) {
        mLastHat = hat;
        mLink.hat(hat);
    }

    @Override
    public void onTrigger(int trigger, float value) {
        if (trigger >= 0 && trigger < mLastTrig.length) {
            mLastTrig[trigger] = value;
        }
        mLink.trigger(trigger, value);
    }

    @Override
    public void onKey(int usage, boolean pressed) {
        mLink.key(usage, pressed);
    }

    @Override
    public void onMouseMove(int dx, int dy) {
        mLink.mouseMove(dx, dy);
    }

    @Override
    public void onMouseButton(int btn, boolean pressed) {
        mLink.mouseButton(btn, pressed);
    }

    @Override
    public void onMouseWheel(int notches) {
        mLink.mouseWheel(notches);
    }

    /**
     * 手机横竖屏切换时（本 app 不再锁死竖屏）刷新一次屏幕尺寸。
     * 覆盖层是 MATCH_PARENT 的，尺寸会跟着变，GamepadView 会根据新的
     * 宽高自动换用横屏 / 竖屏那套布局；这里只是把悬浮球按相对位置放回原处。
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mScreen = screenSize();
        scheduleKeySync();
        if (mPadShown && mPadView != null && mPadParams != null) {
            try {
                mWm.updateViewLayout(mPadView, mPadParams);
            } catch (Exception ignored) {
            }
        }
        if (mBubbleShown && mBubbleView != null && mBubbleParams != null) {
            // 【原来这里是纯比例法，完全没有原点校正】
            //   旋转后气泡位置按 rel×mScreen 重算，却没减掉气泡窗口原点的偏移
            //   （横屏实测 110px），所以每次转完第一次必偏，
            //   得手动开合一次、走 hidePad→showBubble 那条有校正的路径才恢复。
            //
            //   现在改成和 showBubble 完全相同的逻辑：
            //   先用绝对坐标反解，再 post 一轮实测校正。
            final boolean portrait = mScreen.y > mScreen.x;
            float[] rel = PadLayout.collapseRel(
                    this, mScreen.x, mScreen.y, portrait);
            int[] bOrigin = loadBubbleOrigin(portrait);
            float[] cAbs = loadCollapseAbs(portrait);
            
            if (bOrigin != null && cAbs != null) {
                mBubbleParams.x = clampInt(
                        (int) cAbs[0] - mBubbleSize / 2 - bOrigin[0],
                        0, Math.max(0, mScreen.x - mBubbleSize));
                mBubbleParams.y = clampInt(
                        (int) cAbs[1] - mBubbleSize / 2 - bOrigin[1],
                        0, Math.max(0, mScreen.y - mBubbleSize));
            } else {
                // 兜底：比例法（首次启动走这条）。
                // 不减任何偏差 —— 偏差那套已被"存气泡原点"方案取代，
                // 两者混用会互相污染：累加出来的值会被绝对法读到，越补越偏。
                mBubbleParams.x = clampInt(
                        (int) (rel[0] * mScreen.x) - mBubbleSize / 2,
                        0, Math.max(0, mScreen.x - mBubbleSize));
                mBubbleParams.y = clampInt(
                        (int) (rel[1] * mScreen.y) - mBubbleSize / 2,
                        0, Math.max(0, mScreen.y - mBubbleSize));
            }
            try {
                mWm.updateViewLayout(mBubbleView, mBubbleParams);
            } catch (Exception ignored) {
            }
            // 旋转后布局要过一帧才稳定，post 里再实测校正一次
            postBubbleCorrection();
        }
    }

    // ---------------- 按键独立窗口 ----------------

    /**
     * 开关存在 SharedPreferences 里，主界面和悬浮窗读同一份。默认开。
     *
     * 【用独立的配置文件，不复用 PadLayout.PREFS】
     *   那个是 private，跨类访问编译不过；
     *   就算放开，把"界面偏好"和"按键摆放存档"混在一个文件里也不合适 ——
     *   清存档时会连偏好一起清掉。
     */
    private static final String CFG = "vgamepad_cfg";
    /** 排查穿透问题时用：adb logcat -s VGamepad.Sync:* */
    private static final String TAG = "VGamepad.Sync";

    /** 上一次收起时「收」按钮的屏幕绝对坐标，供气泡出来后算 DELTA。 */
    private int mLastCollapseAbsX = -1;
    private int mLastCollapseAbsY = -1;
    /** mLastCollapseAbsX/Y 是在哪个朝向下测的 —— 朝向不匹配就不能用。 */
    private boolean mLastCollapsePortrait = true;

    // ---------------- 悬浮窗原点存档 ----------------
    //
    // 【为什么必须存】
    //   实测：padView 原点 (110, 0)，气泡窗口原点 (110, 110) ——
    //   Y 差 110px（气泡尺寸固定，被系统挤进安全区）。
    //
    //   拖动气泡后要把位置换算回 padView 的比例存档，公式是：
    //       rel = (气泡屏幕坐标 − padView原点) / padView尺寸
    //   但拖气泡时 padView 已经销毁，getLocationOnScreen 拿不到了 ——
    //   所以收起时先把原点记下来，拖完再用。
    //
    //   之前没存，saveBubblePos 直接用 params（气泡坐标系）当屏幕坐标算 rel，
    //   结果少减了 padView 原点的 110，于是"拖完气泡再打开，收按钮往上偏"。
    private static final String PREF_PAD_ORIGIN_P = "pad_origin_p";
    private static final String PREF_PAD_ORIGIN_L = "pad_origin_l";

    private void savePadOrigin(boolean portrait, int ox, int oy) {
        try {
            getSharedPreferences(CFG, MODE_PRIVATE).edit()
                    .putString(portrait ? PREF_PAD_ORIGIN_P : PREF_PAD_ORIGIN_L,
                            ox + "," + oy).apply();
        } catch (Exception ignored) {
        }
    }

    /** 读 padView 原点；没记录过返回 null（首次启动会发生）。 */
    private int[] loadPadOrigin(boolean portrait) {
        try {
            SharedPreferences p = getSharedPreferences(CFG, MODE_PRIVATE);
            String v = p.getString(
                    portrait ? PREF_PAD_ORIGIN_P : PREF_PAD_ORIGIN_L, null);
            if (v != null) {
                String[] a = v.split(",");
                if (a.length == 2) {
                    return new int[]{Integer.parseInt(a[0].trim()),
                            Integer.parseInt(a[1].trim())};
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // ---------------- 「收」按钮的屏幕绝对坐标存档 ----------------
    //
    // 【为什么需要它，而光靠比例法不行】
    //   日志实测：悬浮窗原点 = (110, 0)，气泡窗口原点 = (110, 110)。
    //   X 相同但 Y 差 110px —— 气泡尺寸固定 132dp，被系统挤进安全区，
    //   原点随之下移；全屏悬浮窗则能延伸到 y=0。
    //   两个窗口原点是不同的常量，任何"按比例换算"都补不上这个差值。
    //
    //   所以存「收」的屏幕绝对像素，气泡 attach 后实测自己的原点反解 params。
    //   校正量是实测出来的，不是猜的 —— 竖屏本来准时测出来是 0，不会动。
    private static final String PREF_BUBBLE_ABS_P = "bubble_abs_p";
    private static final String PREF_BUBBLE_ABS_L = "bubble_abs_l";

    private float[] loadCollapseAbs(boolean portrait) {
        try {
            SharedPreferences p = getSharedPreferences(CFG, MODE_PRIVATE);
            String v = p.getString(portrait ? PREF_BUBBLE_ABS_P : PREF_BUBBLE_ABS_L, null);
            if (v == null) {
                return null;
            }
            String[] a = v.split(",");
            if (a.length == 2) {
                return new float[]{Float.parseFloat(a[0]), Float.parseFloat(a[1])};
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void saveCollapseAbs(float absX, float absY, boolean portrait) {
        try {
            getSharedPreferences(CFG, MODE_PRIVATE).edit()
                    .putString(portrait ? PREF_BUBBLE_ABS_P : PREF_BUBBLE_ABS_L,
                            absX + "," + absY).apply();
        } catch (Exception ignored) {
        }
    }

    // ---------------- 气泡窗口原点存档（关键：一次测准，永久有效）----------------
    //
    // 【为什么之前"切回横屏第一次还是偏"】
    //   原方案是把偏差**累加**进存档。但每次测量都依赖 mLastCollapseAbsX/Y，
    //   切朝向那一瞬间它记的还是旧朝向的值，测出来的偏差是错的，
    //   累加进去反而把本来正确的存档**污染**了 —— 于是每次切回来都要重测一轮。
    //
    // 【更好的做法：存"原点"而不是"偏差"】
    //   气泡窗口原点是系统决定的常量（气泡尺寸固定、被挤进安全区的位置固定），
    //   只跟朝向有关。测一次存下来，之后每次直接用：
    //
    //     屏幕坐标 = 窗口原点 + params
    //     → params = 目标屏幕坐标 − 气泡原点      ← 一步到位，不用累加
    //
    //   切回横屏时 bubbleOrigin_l 早就在，第一次就能算准。
    private static final String PREF_BUBBLE_ORIGIN_P = "bubble_origin_p";
    private static final String PREF_BUBBLE_ORIGIN_L = "bubble_origin_l";

    /** 读气泡窗口原点；没测过返回 null。 */
    private int[] loadBubbleOrigin(boolean portrait) {
        try {
            SharedPreferences p = getSharedPreferences(CFG, MODE_PRIVATE);
            String v = p.getString(
                    portrait ? PREF_BUBBLE_ORIGIN_P : PREF_BUBBLE_ORIGIN_L, null);
            if (v != null) {
                String[] a = v.split(",");
                if (a.length == 2) {
                    return new int[]{Integer.parseInt(a[0].trim()),
                            Integer.parseInt(a[1].trim())};
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void saveBubbleOrigin(boolean portrait, int ox, int oy) {
        try {
            getSharedPreferences(CFG, MODE_PRIVATE).edit()
                    .putString(portrait ? PREF_BUBBLE_ORIGIN_P : PREF_BUBBLE_ORIGIN_L,
                            ox + "," + oy).apply();
        } catch (Exception ignored) {
        }
    }

    /**
     * 气泡中心的屏幕绝对坐标。
     *
     * 不能直接拿 params.x/y 当屏幕坐标 —— 那是相对气泡窗口原点的，
     * 而气泡原点被安全区挤过（实测 Y 偏移 110），必须实测。
     */
    private float[] bubbleAbsCenter() {
        if (mBubbleView == null) {
            return null;
        }
        int[] loc = new int[2];
        mBubbleView.getLocationOnScreen(loc);
        if (loc[0] == 0 && loc[1] == 0) {
            return null;
        }
        return new float[]{loc[0] + mBubbleSize / 2f, loc[1] + mBubbleSize / 2f};
    }

    /**
     * 调试开关：触摸窗口用哪个坐标系。
     *
     * true  = 屏幕坐标（mPx + getLocationOnScreen 偏移）
     * false = View 内坐标（mPx 直接用）
     *
     * 配合「显示判定框」用：切一下看红框套不套得住按键，
     * 哪个对一眼就能看出来，不用再推理。
     */
    public static boolean screenCoordEnabled(Context c) {
        // 已确认：View 铺满屏幕、从 (0,0) 起，View 内坐标 == 屏幕坐标。
        // 之前加偏移补偿是误判（真正原因是同步任务没跑），默认不再启用。
        return c.getSharedPreferences(CFG, Context.MODE_PRIVATE)
                .getBoolean("screen_coord", false);
    }

    public static void setScreenCoordEnabled(Context c, boolean on) {
        c.getSharedPreferences(CFG, Context.MODE_PRIVATE)
                .edit().putBoolean("screen_coord", on).apply();
        notifyKeyWindowPrefChanged();
    }

    /**
     * 调试开关：把触摸窗口画成红色判定框。
     *
     * 触摸窗口是隐形的，位置对不对只能靠推理 —— 推错了无数次。
     * 画出来之后一眼就能看出来，比任何日志都直接。
     */
    public static boolean debugRectsEnabled(Context c) {
        return c.getSharedPreferences(CFG, Context.MODE_PRIVATE)
                .getBoolean("debug_rects", false);
    }

    public static void setDebugRectsEnabled(Context c, boolean on) {
        c.getSharedPreferences(CFG, Context.MODE_PRIVATE)
                .edit().putBoolean("debug_rects", on).apply();
        notifyKeyWindowPrefChanged();
    }


    public static boolean keyWindowsEnabled(Context c) {
        return c.getSharedPreferences(CFG, Context.MODE_PRIVATE)
                .getBoolean("key_windows", true);
    }

    public static void setKeyWindowsEnabled(Context c, boolean on) {
        c.getSharedPreferences(CFG, Context.MODE_PRIVATE)
                .edit().putBoolean("key_windows", on).apply();
        notifyKeyWindowPrefChanged();
    }

    /**
     * 手动校准偏移量（dp）。
     *
     * 【为什么需要这个】
     *   自动偏移（GamepadView 的 getLocationOnScreen）在不同 ROM 上表现不一致：
     *   有的系统覆盖层窗口从 (0,0) 开始，有的被状态栏 / 刘海安全区挤开，
     *   还有的 View 还没布局完就取到了 0。推断出来的值不可靠。
     *
     *   与其继续猜，不如把微调权交给用户：调一次，之后横竖屏各存一组，永久生效。
     *
     * @param portrait true = 取竖屏那组，false = 取横屏那组
     */
    public static int getManualOffsetDp(Context c, boolean portrait, boolean x) {
        String k = (portrait ? "kw_off_" : "kw_off_l_") + (x ? "x" : "y");
        return c.getSharedPreferences(CFG, Context.MODE_PRIVATE).getInt(k, 0);
    }

    public static void setManualOffsetDp(Context c, boolean portrait, boolean x, int dp) {
        String k = (portrait ? "kw_off_" : "kw_off_l_") + (x ? "x" : "y");
        c.getSharedPreferences(CFG, Context.MODE_PRIVATE).edit().putInt(k, dp).apply();
        notifyKeyWindowPrefChanged();
    }

    /** 自动算出的偏移（px），仅供主界面显示诊断用。 */
    public static int getAutoOffsetPx(Context c, boolean x) {
        return c.getSharedPreferences(CFG, Context.MODE_PRIVATE)
                .getInt(x ? "kw_auto_x" : "kw_auto_y", 0);
    }

    private int manualOffsetPx(boolean x) {
        // 偏移补偿已停用（它是在补一个由同步 bug 造成的假误差）。
        // 保留方法是为了让调用点不用大改，且随时能恢复。
        return 0;
    }

    /**
     * 强制关掉单窗口 region 方案，一律走多窗口（第 2 级）。
     *
     * 【这是个逃生舱，不是默认项】
     *   region 方案依赖 ViewRootImpl 的 insets 回调，属于框架内部行为，
     *   国产 ROM 改过的版本上表现可能不一致。万一它在你的机器上就是不生效，
     *   勾上这个开关就绕开它，改走"按键开小窗口 + 全屏加 NOT_TOUCHABLE"，
     *   这条路机制简单、不依赖任何反射，只是按键多了会卡。
     */
    public static boolean forceMultiWindow(Context c) {
        return c.getSharedPreferences(CFG, Context.MODE_PRIVATE)
                .getBoolean("force_multi_window", false);
    }

    public static void setForceMultiWindow(Context c, boolean on) {
        c.getSharedPreferences(CFG, Context.MODE_PRIVATE)
                .edit().putBoolean("force_multi_window", on).apply();
        notifyKeyWindowPrefChanged();
    }

    /** 单窗口模式的反射工具。创建失败就是 null。 */
    private TouchRegionHelper mRegionHelper;
    /** 窗口初始 flag，用于从 region 模式还原。 */
    private int mPadBaseFlags;
    /**
     * 上一次同步时"是否该走穿透"的结果。
     *
     * 用来检测 关闭面板 → 回到游玩 这个跳变。
     * 这一跳必须补一次延迟重同步：面板刚关的那一帧，
     * ViewTreeObserver 的 insets 回调往往还没跑完，
     * 此时设的 region 会被随后那次回调覆盖掉 ——
     * 表现就是"关掉编辑面板后背景不穿透了，
     * 得按收起、再点气泡重新显示才恢复"。
     */
    private boolean mPrevWant;
    /** 延迟重同步任务。 */
    private final Runnable mReapply = new Runnable() {
        @Override
        public void run() {
            syncKeyWindows();
        }
    };

    /**
     * region 提交前的等待：要等 apply() 触发的那次 traversal 跑完，
     * 让 dispatchOnComputeInternalInsets() 先把新 region 收集好，
     * 之后补的这次 relayout 提交的才是新值。
     *
     * 取两帧多一点，够 traversal 走完，又不至于让用户看出延迟。
     */
    private static final long REGION_COMMIT_DELAY_MS = 60L;

    /**
     * 强制提交一次 region。
     *
     * 【这就是"关掉编辑面板后不穿透"的根因所在】
     *   touchableRegion 走的是 ViewTreeObserver 的 insets 回调，
     *   收集（dispatchOnComputeInternalInsets）和提交（relayoutWindow）
     *   分属同一次 traversal 的前后两段，而且**只有真正发生 relayout
     *   才会提交**。requestLayout() 在窗口尺寸和 flag 都没变时不会触发
     *   relayout，新 region 就一直没机会提交。
     *
     *   退出编辑面板时更糟：clear() 设的"整屏"被随后那次
     *   updateViewLayout 提交上去了，apply() 设回的按键区却没有 ——
     *   窗口于是停在"整屏可触摸"，表现就是完全不穿透。
     *
     *   updateViewLayout() 会让 params != null，强制走一次 relayoutWindow，
     *   把当前已收集的 region 真正推给 WMS。
     *
     * 【为什么"收起再显示"能好】
     *   showPad() 是 new GamepadView + addView，必然带一次完整 relayout，
     *   顺带把 region 提交了；而且新窗口没有"编辑期间残留的整屏 insets"
     *   这个脏状态。它恢复的是状态，不是修好了逻辑。
     */
    private final Runnable mRegionCommit = new Runnable() {
        @Override
        public void run() {
            if (!mPadShown || mPadView == null || mPadParams == null) {
                mRegionCommitCount = 0;
                return;
            }
            try {
                mWm.updateViewLayout(mPadView, mPadParams);
            } catch (Throwable ignored) {
            }
            // 【多次确认，而不是赌一次】
            //   relayout 提交的是"上一次 traversal 收集到的 region"。
            // 切换布局这类场景下 mRegion 是新算出来的，单次提交
            //   很可能赶在 traversal 之前，提交的还是旧值。
            //   连着补几次（间隔递增）把新值推上去，开销就是几次
            //   updateViewLayout，可以忽略。
            if (mRegionActive && mRegionCommitCount < REGION_COMMIT_MAX) {
                mRegionCommitCount++;
                mHandler.postDelayed(mRegionCommit,
                        REGION_COMMIT_DELAY_MS * mRegionCommitCount);
            } else {
                mRegionCommitCount = 0;
            }
        }
    };

    private int mRegionCommitCount;
    private static final int REGION_COMMIT_MAX = 3;

    /**
     * 收敛任务：连着几轮"重设 region + 强制提交"。
     *
     * 【为什么需要它】
     *   "列表取消能穿透、编辑完成不能"说明单次提交的时序凑不齐：
     *   面板刚关那段时间 View 会被反复重绘（面板消失、滑条重排、
     *   选中圈清除…），每一次 traversal 都可能把刚设好的 region 顶掉。
     *   编辑退出的重绘明显比列表取消密集，所以只有它中招。
     *
     *   与其去猜"到底要等多久"，不如连续确认几轮：
     *   每轮都重新 apply（触发 requestLayout 收集）+ 安排一次提交，
     *   只要有一轮赶在重绘间隙命中就收敛了，后面几轮只是重复确认。
     *   开销是几次 updateViewLayout，可忽略。
     */
    private final Runnable mRegionSettle = new Runnable() {
        @Override
        public void run() {
            if (!mPadShown || mPadView == null || mPadParams == null) {
                mSettleCount = 0;
                return;
            }
            boolean want = keyWindowsEnabled(FloatingService.this)
                    && !mPadHiddenForRename
                    && mPadView.isPassThroughActive();
            if (!want) {
                // 期间又进了编辑/列表，停下让正常流程接手
                mSettleCount = 0;
                Log.w(TAG, "settle: 又回到 want=false，停止收敛");
                return;
            }
            if (mRegionHelper != null) {
                int n = mPadView.buildTouchRegion(mRegionRects);
                if (n > 0) {
                    boolean ok = mRegionHelper.apply(mPadView, mRegionRects, n);
                    // 【收敛期第 1 级刚成功：必须清掉降级残留】
                    //   退出编辑那次同步里，第 1 级可能还没准备好就失败了，
                    //   于是降级到第 2 级并 addView 了一堆 KeyTouchView。
                    //   收敛期间第 1 级成功，但如果不显式删除，
                    //   那批小窗口会一直挂在屏幕上 ——
                    //   表现就是"加了按钮用单窗口，删掉按钮又变多窗口"。
                    if (ok && !mRegionActive) {
                        Log.w(TAG, "settle: 第1级成功，清理降级残留的多窗口");
                        removeAllKeyWindows();
                        updatePadTouchable();
                    }
                    mRegionActive = ok;
                }
                requestRegionCommit();
            }
            if (mSettleCount < REGION_SETTLE_MAX) {
                mSettleCount++;
                mHandler.postDelayed(mRegionSettle, REGION_SETTLE_DELAY_MS);
            } else {
                Log.w(TAG, "settle: 收敛结束 mRegionActive=" + mRegionActive);
                mSettleCount = 0;
            }
        }
    };

    private int mSettleCount;
    /** 收敛轮数。5 轮 × 80ms 覆盖约 400ms，足够穿过面板关闭后的重绘洪峰。 */
    private static final int REGION_SETTLE_MAX = 5;
    private static final long REGION_SETTLE_DELAY_MS = 80L;

    private void startRegionSettle() {
        mSettleCount = 0;
        mHandler.removeCallbacks(mRegionSettle);
        mHandler.postDelayed(mRegionSettle, REGION_SETTLE_DELAY_MS);
    }

    /** 安排一轮 region 强制提交（重复调用只会重新起一轮，不会叠加）。 */
    private void requestRegionCommit() {
        if (!mPadShown || mPadView == null || mPadParams == null) {
            return;
        }
        mRegionCommitCount = 0;
        mHandler.removeCallbacks(mRegionCommit);
        mHandler.postDelayed(mRegionCommit, REGION_COMMIT_DELAY_MS);
    }
    /**
     * 单窗口 region 模式是否生效。
     *
     * 【为什么要有这个标志】
     *   多窗口模式：全屏窗口要让位，加 FLAG_NOT_TOUCHABLE 交给小窗口。
     *   单窗口模式：全屏窗口**就是**唯一接触摸的那个，必须保持可触摸 ——
     *   透不穿透全靠 touchableRegion，不靠 FLAG_NOT_TOUCHABLE。
     *
     *   之前没区分，region 模式照样被加上 NOT_TOUCHABLE，
     *   结果"显示最优"却一个键都点不到。
     */
    private boolean mRegionActive;
    /** 复用矩形数组，避免每次同步都新建一堆对象。 */
    private final android.graphics.Rect[] mRegionRects =
            new android.graphics.Rect[PadLayout.N];

    /**
     * 尝试用"单窗口 + touchable region"实现穿透。
     *
     * @return true = 成功，调用方不要再建小窗口
     */
    private boolean trySingleWindowRegion() {
        if (mPadView == null || mPadShown == false) {
            return false;
        }
        if (forceMultiWindow(this)) {
            // 逃生舱：主界面勾了「强制多窗口」，直接跳过 region 方案
            return false;
        }
        if (!TouchRegionHelper.isSupported()) {
            return false;
        }
        try {
            if (mRegionHelper == null) {
                mRegionHelper = new TouchRegionHelper();
                mRegionHelper.setRelayoutHook(new TouchRegionHelper.RelayoutHook() {
                    @Override
                    public void requestRelayout(View host) {
                        // 交给主线程延迟执行：必须晚于当次 traversal
                        requestRegionCommit();
                    }
                });
            }
            int n = mPadView.buildTouchRegion(mRegionRects);
            if (n <= 0) {
                mRegionActive = false;
                Log.w(TAG, "region: 没有可用矩形，回退多窗口");
                return false;
            }
            mRegionActive = mRegionHelper.apply(mPadView, mRegionRects, n);
            Log.w(TAG, "region: apply n=" + n + " -> mRegionActive=" + mRegionActive);
            if (!mRegionActive) {
                // 第 1 级这次没成，先别急着认命降级 ——
                // 起一轮收敛再试。region 只需要 1 个窗口，
                // 不像多窗口那样受 MAX_PASS_THROUGH_KEYS 限制，
                // 值得多等几帧。键数多的时候降级会落到第 3 级
                // （removeAllKeyWindows + 整屏可触摸），
                // 配合 mRegionActive 残留会伪装成"单窗口能用"，
                // 键数一少降级到第 2 级就露馅了。
                startRegionSettle();
            }
            return mRegionActive;
        } catch (Throwable t) {
            // 反射任何一步出问题都安全回退，绝不让 app 崩
            mRegionActive = false;
            return false;
        }
    }

    /**
     * region 模式下的 flag 调整。
     *
     * 【为什么必须去掉 FLAG_NOT_TOUCH_MODAL】
     *   InputMonitor 计算触摸区域时有这么一条（AOSP）：
     *     useSurfaceBoundsAsTouchRegion = (flags & FLAG_NOT_TOUCH_MODAL) != 0 || ...
     *   一旦为真，就**直接用整个窗口矩形**当触摸区域，
     *   我们通过 InternalInsetsInfo 设的 touchableRegion 被完全忽略。
     *
     *   我们的窗口本来就是 MATCH_PARENT 全屏，"整个窗口矩形" = 整个屏幕，
     *   于是：按键能点、空白处也被吃掉 —— 正是你观察到的现象。
     *
     *   去掉这个 flag 之后，才会走到 TOUCHABLE_INSETS_REGION 分支，
     *   我们设的区域才生效；区域外的触摸自然落到下层游戏。
     */
    /**
     * 撤销 applyRegionFlags 的改动，把窗口 flag 恢复成默认。
     *
     * 编辑 / 列表打开时必须调用 —— 否则窗口一直处于 region 模式，
     * 配合一个空的（或整屏的）region，行为会很怪。
     */
    private void restorePadFlags() {
        if (mPadParams == null) {
            return;
        }
        if (mPadParams.flags == mPadBaseFlags) {
            return;
        }
        mPadParams.flags = mPadBaseFlags;
        if (mPadShown && mPadView != null) {
            try {
                mWm.updateViewLayout(mPadView, mPadParams);
            } catch (Exception ignored) {
            }
        }
    }

    private void applyRegionFlags() {
        if (mPadParams == null) {
            return;
        }
        int f = mPadParams.flags;
        f &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        f &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        if (f != mPadParams.flags) {
            mPadParams.flags = f;
            if (mPadShown && mPadView != null) {
                try {
                    mWm.updateViewLayout(mPadView, mPadParams);
                } catch (Exception ignored) {
                }
            }
        }
        // 两种分支都得补一次强制提交：
        //   变了 —— 这次 relayout 提交的可能是上一轮收集好的旧 region；
        //   没变 —— 压根没发生 relayout，新 region 更是提交不上去。
        requestRegionCommit();
    }

    /** 当前生效的穿透模式，写进配置给主界面诊断显示。 */
    public static String getPassMode(Context c) {
        return c.getSharedPreferences(CFG, Context.MODE_PRIVATE)
                .getString("pass_mode", "未知（悬浮窗还没运行过）");
    }

    private void setPassMode(String mode) {
        getSharedPreferences(CFG, Context.MODE_PRIVATE)
                .edit().putString("pass_mode", mode).apply();
    }

    /** 把自动偏移写进配置，主界面读出来给诊断看。 */
    private void recordAutoOffset(int ax, int ay) {
        getSharedPreferences(CFG, Context.MODE_PRIVATE)
                .edit().putInt("kw_auto_x", ax).putInt("kw_auto_y", ay).apply();
    }

    public static void notifyKeyWindowPrefChanged() {
        FloatingService fs = sInstance;
        if (fs != null) {
            fs.scheduleKeySync();
        }
    }

    private final GamepadView.PassThroughHost mPassHost = new GamepadView.PassThroughHost() {
        @Override
        public void onPassThroughChanged() {
            scheduleKeySync();
        }
    };

    private final KeyTouchView.Sink mKeySink = new KeyTouchView.Sink() {
        @Override
        public void onKeyTouch(int elem, int action, float x, float y) {
            if (mPadView != null) {
                mPadView.onKeyTouch(elem, action, x, y);
            }
        }
    };

    private final Runnable mKeySync = new Runnable() {
        @Override
        public void run() {
            syncKeyWindows();
        }
    };

    /**
     * 待新建的窗口队列（每 5 个一组：elem,l,t,r,b）。
     *
     * 【为什么要分批建】
     *   每个 addView 都是一次到 system_server 的 Binder 调用。
     *   键盘布局 80+ 个键 = 80+ 次跨进程调用，一次性做完会**阻塞主线程
     *   几百毫秒** —— 表现就是"点开后要等一会儿按键才响应"，
     *   看起来像在等守护进程，其实是在等窗口一个一个建完。
     *
     *   全屏画面只有 1 次 addView，所以它永远"一点就显示"。
     *   这就是两者体感不同的真正原因。
     */
    private final int[] mPendingAdd = new int[MAX_KEY_WINDOWS * 5];
    /** 下一个要建的位置 / 入队位置。用两个指针实现 FIFO，保证按键按编号顺序建。 */
    private int mPendingHead;
    private int mPendingTail;
    private boolean mFlushing;
    /** 每帧最多新建几个窗口。16 个实测既不卡，又能很快建完。 */
    private static final int ADD_PER_FRAME = 16;

    /** 同步任务是否已经排队。 */
    private boolean mKeySyncPosted;
    /** 是否正在同步中（防止同步过程里再次触发同步，形成递归）。 */
    private boolean mSyncing;

    /**
     * 合并同一帧里的多次通知，避免连续 remove/add 窗口。
     *
     * 【为什么不能用 removeCallbacks + post】
     *   原来那写法会把**还没跑**的任务取消掉再重新排。
     *   invalidate() 一密集（拖动、转屏、切布局时每帧都调），
     *   任务每次都在执行前被取消，永远轮不到它跑 ——
     *   结果就是窗口一直停在最初那个可能错误的坐标上不动。
     *
     *   改成"已排队就不重复排"：保证任务一定会执行到。
     */
    private void scheduleKeySync() {
        if (mKeySyncPosted) {
            return;
        }
        mKeySyncPosted = true;
        mHandler.post(mKeySync);
    }

    private void removeKeyWindowAt(int j) {
        if (j < 0 || j >= mKeyCount) {
            return;
        }
        try {
            mWm.removeView(mKeyViews[j]);
        } catch (Exception ignored) {
        }
        for (int k = j; k < mKeyCount - 1; k++) {
            mKeyViews[k] = mKeyViews[k + 1];
            mKeyParams[k] = mKeyParams[k + 1];
            mKeyElem[k] = mKeyElem[k + 1];
        }
        mKeyCount--;
    }

    private void removeAllKeyWindows() {
        // 待建队列也要清：不然收起后队列里的窗口还会被建出来
        mPendingHead = 0;
        mPendingTail = 0;
        if (mKeyCount <= 0) {
            return;
        }
        if (mPadView != null) {
            mPadView.releaseKeyWindowTouches();
        }
        while (mKeyCount > 0) {
            removeKeyWindowAt(mKeyCount - 1);
        }
    }

    private void addKeyWindow(int elem, int l, int t, int r, int b) {
        if (mKeyCount >= MAX_KEY_WINDOWS) {
            return;
        }
        KeyTouchView v = new KeyTouchView(this);
        v.bind(elem, mKeySink);
        v.setDebug(debugRectsEnabled(this));
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                r - l, b - t, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSPARENT);
        p.gravity = Gravity.TOP | Gravity.LEFT;
        p.x = l;
        p.y = t;
        try {
            mWm.addView(v, p);
        } catch (Exception ignored) {
            return;
        }
        mKeyViews[mKeyCount] = v;
        mKeyParams[mKeyCount] = p;
        mKeyElem[mKeyCount] = elem;
        mKeyCount++;
    }

    /**
     * 让全屏那个绘制窗口吃不吃触摸。
     *
     * 开穿透时给它加 FLAG_NOT_TOUCHABLE：画面照画，但不再拦截任何触摸，
     * 触摸全部由按键小窗口接管。
     */
    /**
     * 单窗口 region 模式是否可用（反射探测通过）。
     *
     * 【为什么区分"当前生效"和"可用"】
     *   编辑模式下 mRegionActive 会被清掉（region 设成整屏），
     *   但退出编辑后要能重新启用 —— 所以不能只看 mRegionActive。
     *   这里问的是"这个设备支不支持"，跟当前状态无关。
     */
    private boolean regionSupported() {
        return TouchRegionHelper.isSupported();
    }

    private void setPadTouchable(boolean touchable) {
        // 【单窗口模式下，这套"实体化 / 透明化"切换完全不适用】
        //
        // 它是为多窗口设计的：
        //   游玩 → 全屏窗口透明(不可点)，由小窗口接点击
        //   编辑 → 小窗口删掉，全屏窗口"实体化"(可点)
        //
        // 但单窗口模式下，全屏窗口**自始至终**都是唯一接触摸的那个：
        //   游玩 → 靠 touchableRegion 让按键可点、空白穿透
        //   编辑 → 靠 region 设成整屏，全屏可点
        //
        // 这里再动 FLAG_NOT_TOUCHABLE 只会把 region 模式的设置覆盖掉 ——
        // 表现就是"关掉编辑面板后背景不穿透了，得收起重开才恢复"。
        //
        // 【只挡 mRegionActive，不再挡 regionSupported()】
        //   regionSupported() 只是 Class.forName 探测，绝大多数设备都返回 true。
        //   拿它当闸门等于把第 2 级（多窗口 + NOT_TOUCHABLE）这条退路**永久焊死**：
        //   一旦 region 实际没生效（hidden API 被拦、提交时机错过、listener 挂错 host…），
        //   小窗口建好了却拿不到 NOT_TOUCHABLE，全屏继续吃满触摸，
        //   于是"显示最优、一个键都点不到"或"完全不穿透"就再也救不回来。
        //   改成只看 mRegionActive —— 它才是"这一刻真的在走 region 方案"。
        if (mRegionActive) {
            return;
        }
        if (mPadParams == null) {
            return;
        }
        int f = mPadParams.flags;
        if (touchable) {
            f &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            f |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        if (f == mPadParams.flags) {
            return;
        }
        mPadParams.flags = f;
        if (mPadShown && mPadView != null) {
            try {
                mWm.updateViewLayout(mPadView, mPadParams);
            } catch (Exception ignored) {
            }
        }
    }

    private void syncKeyWindows() {
        mKeySyncPosted = false;
        // 重入保护：syncKeyWindows 里会改窗口，可能触发 View 的
        // invalidate -> notifyHost -> scheduleKeySync，不加锁会无限递归。
        if (mSyncing) {
            return;
        }
        mSyncing = true;
        try {
            boolean want = keyWindowsEnabled(this)
                    && mPadShown && !mPadHiddenForRename
                    && mPadView != null && mPadView.isPassThroughActive();
            Log.w(TAG, "sync: want=" + want + " mPrevWant=" + mPrevWant
                    + " blocker=" + (mPadView != null
                            ? mPadView.passThroughBlocker() : "mPadView=null")
                    + " hiddenForRename=" + mPadHiddenForRename);
            syncKeyWindowsInner();
            // 关掉编辑/列表面板回到游玩的那一跳：
            // 补一次延迟重同步，把 region 重新设一遍。
            // 面板关闭那一帧 insets 回调还没跑完，
            // 现在设的 region 会被它覆盖（覆盖回"整屏可触摸"）。
            if (want && !mPrevWant) {
                mHandler.removeCallbacks(mReapply);
                mHandler.postDelayed(mReapply, 250);
                // 再加一轮收敛：面板刚关那段时间里 View 会被反复重绘，
                // 单次提交很可能被随后的 traversal 顶掉。
                startRegionSettle();
            }
            mPrevWant = want;
        } finally {
            mSyncing = false;
        }
    }

    private void syncKeyWindowsInner() {
        boolean want = keyWindowsEnabled(this)
                && mPadShown && !mPadHiddenForRename
                && mPadView != null && mPadView.isPassThroughActive();
        if (!want) {
            mRegionActive = false;
            removeAllKeyWindows();
            if (mRegionHelper != null && mPadView != null) {
                // 进整屏档（编辑/列表期间整屏可点）。
                // 注意：helper 内部只翻一个临时开关，按键区不会被改写。
                mRegionHelper.clear(mPadView);
            }
            Log.w(TAG, "syncInner: !want -> 进整屏档（编辑/列表），"
                    + "region 临时放开但按键区保留");
            // 还原窗口 flag，让软件自己的编辑/列表面板能正常接收触摸
            restorePadFlags();
            setPassMode("未启用（编辑中 / 列表打开 / 未开启）");
            return;
        }

        // ============ 逃生舱：用户手动勾选「强制多窗口」============
        //   这是**唯一**会用到多窗口的路径。region 方案已经稳定，
        //   多窗口只在极个别 ROM 上失效时才由用户主动切换过来。
        //   （多窗口曾因窗口数过多被系统批量回收导致崩溃，不能自动降级过去。）
        final boolean forceMw = forceMultiWindow(this);
        if (forceMw) {
            mRegionActive = false;
            if (mRegionHelper != null && mPadView != null) {
                mRegionHelper.clear(mPadView);
            }
            restorePadFlags();
            setPassMode("多窗口模式（手动强制）— 若按键太多可能崩溃");
            // 落到下面的多窗口建窗流程
        } else {
            // ============ 第 1 级：单窗口 + touchable region ============
            // 整个手柄只用 1 个窗口，按键能点、其余穿透。
            // 【不受键数限制】键盘布局 80+ 个键也照样走这条路 ——
            //   region 是 1 个窗口内部的区域划分，跟按键多少无关，
            //   完全不存在多窗口那种"窗口太多被回收"的问题。
            int pickAll = mPadView.countPickable();
            if (pickAll > 0 && trySingleWindowRegion()) {
                removeAllKeyWindows();
                updatePadTouchable();
                setPassMode("单窗口 + 触摸区域（最优）— 共 " + pickAll
                        + " 个键，1 个窗口");
                return;
            }

            // ============ 第 2 级：全屏模式（安全降级，不穿透）============
            //   region 不可用时的兜底：整屏可触摸，功能弱但**绝不崩**。
            //   宁可不穿透，也不退回多窗口（多窗口有崩溃风险）。
            removeAllKeyWindows();
            // 【关键：这里必须显式设成"可触摸"】
            //   不能直接调 updatePadTouchable() —— 它里面的 ready 判断
            //   是给多窗口准备的：ready 为真就把全屏设成 NOT_TOUCHABLE，
            //   好让小窗口接管。但这一档**一个小窗口都没建**，
            //   全屏被设成不可触摸之后没有任何东西接得住 ——
            //   于是整屏穿透，"按键全部点不到"，正是你遇到的现象。
            //
            //   这一档的职责是"至少按键能用"，所以窗口必须保持可触摸。
            setPadTouchable(true);
            // 文案说明：
            //   这一档代码做的是 removeAllKeyWindows + 整屏可触摸，
            //   按设计**不该**穿透。但实测常有穿透 —— 那是上一次成功的
            //   touchableRegion 还残留在 ViewRootImpl 里，
            //   我们既没有主动设置它、也没有能力清除它。
            //   所以如果你在这档测出能穿透，那就是纯粹吃到了系统 bug，
            //   不是我们实现的。随时可能在某次系统更新后消失。
            setPassMode("第 2 级：全屏模式（" + mPadView.countPickable()
                    + " 个键）— 本档按设计不穿透；若你实测仍能穿透，"
                    + "那是吃到了系统 bug，非本程序实现，随时可能失效");
            return;
        }
        int pickCount = mPadView.countPickable();
        // 【多窗口路径的崩溃防线】
        //   窗口数超过 MAX_KEY_WINDOWS(32) 就装不下了，
        //   硬建会被系统批量回收 —— 键盘布局当初就是这么崩的。
        //   手动强制多窗口时也要守住这条线，宁可不穿透也不能崩。
        if (pickCount > MAX_KEY_WINDOWS) {
            removeAllKeyWindows();
            updatePadTouchable();
            setPassMode("多窗口模式已放弃（" + pickCount
                    + " 个键 > " + MAX_KEY_WINDOWS + "，会崩）— 退回全屏不穿透");
            return;
        }

        // 【先不禁用全屏触摸】
        //   等所有小窗口都建好之后再禁用（见 flushPendingAdds 末尾）。
        //   否则在建窗口的那几帧里：全屏已失效、小窗口只建了一部分，
        //   于是只有先建好的那批按键能点、其余全部点不到 ——
        //   「编」(13)「收」(14)「布」(15) 编号靠前，落在第一批，
        //   就出现了"只有编能点"的怪现象。
        //   建好之前由全屏窗口兜底：已建好的走小窗口，没建好的走全屏。

        final boolean debug = debugRectsEnabled(this);

        // 手动校准：在自动偏移之后**再**叠一层用户微调值
        final int offX = manualOffsetPx(true);
        final int offY = manualOffsetPx(false);

        int[] buf = new int[MAX_KEY_WINDOWS * 5];
        int n = mPadView.collectKeyWindows(buf, screenCoordEnabled(this));
        recordAutoOffset(mPadView.getViewOffsetX(), mPadView.getViewOffsetY());
        boolean[] matched = new boolean[MAX_KEY_WINDOWS];
        for (int k = 0; k < n; k++) {
            int elem = buf[k * 5];
            int l = buf[k * 5 + 1] + offX;
            int t = buf[k * 5 + 2] + offY;
            int r = buf[k * 5 + 3] + offX;
            int b = buf[k * 5 + 4] + offY;
            int found = -1;
            for (int j = 0; j < mKeyCount; j++) {
                if (!matched[j] && mKeyElem[j] == elem) {
                    found = j;
                    break;
                }
            }
            if (found >= 0) {
                matched[found] = true;
                // 开关变了要让已有的窗口跟着变（不然得重建才看得到）
                mKeyViews[found].setDebug(debug);
                WindowManager.LayoutParams p = mKeyParams[found];
                if (p.x != l || p.y != t || p.width != r - l || p.height != b - t) {
                    p.x = l;
                    p.y = t;
                    p.width = r - l;
                    p.height = b - t;
                    try {
                        mWm.updateViewLayout(mKeyViews[found], p);
                    } catch (Exception ignored) {
                    }
                }
            } else {
                // 不在这里直接 addView —— 攒起来分帧建，避免一次阻塞主线程
                if (mPendingTail < MAX_KEY_WINDOWS) {
                    int base = mPendingTail * 5;
                    mPendingAdd[base] = elem;
                    mPendingAdd[base + 1] = l;
                    mPendingAdd[base + 2] = t;
                    mPendingAdd[base + 3] = r;
                    mPendingAdd[base + 4] = b;
                    mPendingTail++;
                }
            }
        }
        // 先删掉不再需要的，再建新的 —— 反过来的话窗口数会瞬间超限
        for (int j = mKeyCount - 1; j >= 0; j--) {
            if (!matched[j]) {
                removeKeyWindowAt(j);
            }
        }
        flushPendingAdds();
    }

    /** 分帧建窗口：每帧最多 ADD_PER_FRAME 个，剩余的下帧继续。 */
    private void flushPendingAdds() {
        if (mFlushing) {
            return;
        }
        mFlushing = true;
        try {
            int done = 0;
            while (mPendingHead < mPendingTail && done < ADD_PER_FRAME) {
                // FIFO：从头部取，按键按编号升序建。
                // 之前写的是从尾部取 —— 那是**反序**，
                // 摇杆(0,1)、ABXY 这些核心键反倒最后才建好。
                int base = mPendingHead * 5;
                addKeyWindow(mPendingAdd[base], mPendingAdd[base + 1],
                        mPendingAdd[base + 2], mPendingAdd[base + 3],
                        mPendingAdd[base + 4]);
                mPendingHead++;
                done++;
            }
        } finally {
            mFlushing = false;
        }
        if (mPendingHead < mPendingTail) {
            // 还没建完：全屏继续兜底（保持可触摸）
            mHandler.post(mFlushRunnable);
        } else {
            // 全部就位 —— 这时才让全屏失效，空白开始穿透到游戏
            updatePadTouchable();
        }
    }

    /**
     * 决定全屏窗口吃不吃触摸。
     *
     * 只有"小窗口已全部建好"时才禁用全屏触摸、让空白穿透。
     * 建窗口期间保持全屏可触摸，保证每个按键从头到尾都能点。
     */
    private void updatePadTouchable() {
        // 【单窗口 region 模式：窗口必须保持可触摸】
        //   它就是唯一接触摸的窗口，透不穿透由 touchableRegion 决定。
        //   给它加 NOT_TOUCHABLE 就等于把所有按键一起废掉。
        if (mRegionActive) {
            applyRegionFlags();
            return;
        }
        // 元素太多时多窗口装不下，全屏保持可触摸（不穿透但不崩）
        if (mPadView != null && mPadView.countPickable() > MAX_KEY_WINDOWS) {
            setPadTouchable(true);
            return;
        }
        boolean ready = keyWindowsEnabled(this)
                && mPadShown && !mPadHiddenForRename
                && mPadView != null && mPadView.isPassThroughActive()
                && mPendingHead >= mPendingTail;
        // 【最后一道防线：没有小窗口就不能把全屏设成不可触摸】
        //   ready 为真意味着"小窗口都建好了，全屏可以让位"。
        //   但如果 mKeyCount 是 0（降级档 / 还没建 / 刚被清空），
        //   让位之后没有任何窗口接得住 —— 整屏穿透，按键全部点不到。
        //   这种情况宁可不穿透，也要保住"按键能按"。
        if (ready && mKeyCount == 0) {
            Log.w(TAG, "updatePadTouchable: ready 但 mKeyCount=0，"
                    + "强制保持可触摸（否则整屏穿透，按键全点不到）");
            setPadTouchable(true);
            return;
        }
        Log.w(TAG, "updatePadTouchable: mRegionActive=" + mRegionActive
                + " ready=" + ready + " mKeyCount=" + mKeyCount
                + " -> touchable=" + !ready);
        setPadTouchable(!ready);
    }

    private final Runnable mFlushRunnable = new Runnable() {
        @Override
        public void run() {
            flushPendingAdds();
        }
    };

    @Override
    public void onDestroy() {
        sInstance = null;
        mHandler.removeCallbacks(mStatusTick);
        hidePad();
        hideBubble();
        mLink.close();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
