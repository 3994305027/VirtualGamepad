package com.example.vgamepad;

import android.graphics.Rect;
import android.graphics.Region;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 让一个窗口"只有指定区域吃触摸，其余穿透到下层"。
 *
 * 【为什么需要这个】
 *   原来每个按键开一个独立窗口，键盘布局 80+ 个键 = 80+ 个
 *   TYPE_APPLICATION_OVERLAY 窗口，系统有数量上限，开到几十个就会被
 *   批量回收 —— 表现就是"窗口一个个消失，然后 app 崩溃"。
 *
 *   系统底层其实支持"单窗口 + 部分区域可触摸"：
 *   WindowState.getTouchableRegion() 里 TOUCHABLE_INSETS_REGION 分支就是干这个的，
 *   状态栏、输入法都在用。但这个 API 是 @hide 的，只能反射。
 *
 *   成功的话：整个手柄**只需要 1 个窗口**，按键能点、其余全部穿透，
 *   而且不受 Android 12 那条 "alpha <= 0.8 才能穿透" 的限制
 *   （region 外的区域窗口根本不遮挡，不是"穿透"）。
 *
 * 【失败怎么办】
 *   Android 9+ 有 hidden API 限制，反射可能被拦。
 *   所有方法都返回 boolean 表示成败，调用方据此回退到多窗口模式，
 *   所以失败也只会回到原来的行为，不会更差。
 */
public final class TouchRegionHelper {

    private static final String TAG = "VGamepad.Region";

    /** InternalInsetsInfo.TOUCHABLE_INSETS_REGION 的值。 */
    private static final int TOUCHABLE_INSETS_REGION = 3;

    /**
     * 让宿主补一次真正的 relayoutWindow。
     *
     * 【为什么 helper 自己搞不定，必须交给宿主】
     *   touchableRegion 的提交发生在 ViewRootImpl.relayoutWindow()，
     *   而收集发生在同一次 traversal 里更早的
     *   dispatchOnComputeInternalInsets()。
     *   requestLayout() 只负责调度 traversal —— 窗口尺寸没变、
     *   LayoutParams 也没变（params == null）时，relayoutWindow()
     *   根本不会被调用，刚收集好的 region 就一直卡在
     *   mGivenInternalInsets 里提交不上去。
     *
     *   能强制走 relayoutWindow 的只有"改 LayoutParams 后
     *   updateViewLayout"，而这个操作属于宿主（窗口是宿主加的），
     *   helper 只持有一个 View 干不了，所以回调出去。
     */
    public interface RelayoutHook {
        void requestRelayout(View host);
    }

    private Object mListener;
    /** listener 注册在哪一个 host 上。收起再显示会换 View 实例，必须跟着换。 */
    private View mListenerHost;
    private View mHost;
    /**
     * 按键区的并集。
     *
     * 【这是持久状态，绝不能被临时需求改写】
     *   VTO 每次 traversal 都会执行 target.set(mRegion) 把它刷进
     *   ViewRootImpl 的 mGivenInternalInsets。一旦被写成"整屏"，
     *   之后每次收集到的都是整屏，退出编辑时改回来还得再凑
     *   "一次 traversal 收集 + 一次 relayout 提交"，时序凑不齐就永远回不来。
     */
    private final Region mRegion = new Region();
    /**
     * 临时整屏档：编辑 / 列表打开期间为 true。
     *
     * 【为什么必须和 mRegion 分开】
     *   编辑期间确实需要"整屏可点"，但这只是临时需求。
     *   曾经直接把 mRegion 设成整屏 —— 面板是灵敏了，
     *   代价是退出编辑后 region 被粘在整屏，穿透再也回不来
     *   （必须收起重开）。后来改成完全不设 —— 穿透回来了，
     *   但编辑期间只剩按键区可点，面板变得"要点几次才有反应"。
     *
     *   两个需求放在一个变量里必然打架。分开之后：
     *     进入编辑：mFullScreen=true，listener 输出整屏，mRegion 不受影响
     *     退出编辑：mFullScreen=false，下次 traversal 就输出按键区
     *   各自干净，谁也不粘谁。
     */
    private boolean mFullScreen;
    private RelayoutHook mHook;

    /** 反射是否可用（只探测一次）。 */
    private static Boolean sSupported;

    /**
     * 探测反射是否可用。
     *
     * 只做 Class.forName，不实际注册 —— 注册失败还能回退，
     * 但类加载都失败就完全没戏了。
     */
    public static boolean isSupported() {
        if (sSupported != null) {
            return sSupported;
        }
        boolean ok = false;
        try {
            Class.forName("android.view.ViewTreeObserver$OnComputeInternalInsetsListener");
            Class<?> infoClass = Class.forName("android.view.ViewTreeObserver$InternalInsetsInfo");
            infoClass.getDeclaredField("touchableRegion");
            infoClass.getDeclaredField("mTouchableInsets");
            ok = true;
        } catch (Throwable t) {
            Log.w(TAG, "反射不可用: " + t);
        }
        sSupported = ok;
        return ok;
    }

    public void setRelayoutHook(RelayoutHook h) {
        mHook = h;
    }

    /**
     * 宿主换了 View 实例（收起再显示、重建悬浮窗）时调用。
     *
     * 新的 View 有自己的 ViewTreeObserver（每个 ViewRootImpl 一份），
     * 旧的 listener 挂在上一个 VTO 上，对新 host 完全无效，
     * 但 mListener != null 会让 ensureListener() 直接复用 —— 必须显式清掉。
     */
    public void resetForHost() {
        unregisterListener();
        mHost = null;
        // 新窗口还没进过编辑，回到游玩档
        mFullScreen = false;
    }

    /**
     * 把 host 窗口的可触摸区域设为若干矩形的并集。
     *
     * @param rects 窗口坐标系下的矩形（相对窗口左上角）
     */
    public boolean apply(View host, Rect[] rects, int count) {
        if (!isSupported() || host == null) {
            return false;
        }
        mHost = host;
        // 回到游玩：退出整屏档，之后每次 traversal 输出的就是按键区。
        // 必须放在 probe() 之前 —— probe 是手动 invoke listener，
        // 它会读这个标志，置晚了自检到的就是整屏，判断失真。
        mFullScreen = false;
        mRegion.setEmpty();
        for (int i = 0; i < count && i < rects.length; i++) {
            Rect r = rects[i];
            if (r != null && !r.isEmpty()) {
                mRegion.union(r);
            }
        }
        if (!ensureListener()) {
            return false;
        }
        // 【自检一：字段到底设没设进去】
        //   ensureListener 只要没抛异常就返回 true，但它证明不了系统真采纳了。
        //   listener 是等下一帧 layout 才回调的，真到那时如果反射设字段被
        //   hidden API 限制拦住，异常会被 invoke 里的 catch 吞掉 ——
        //   结果就是：apply() 返回 true、诊断显示"最优"，
        //   但 region 从头到尾没生效，整屏都被窗口吃掉（按键能点、空白不穿透）。
        if (!probe()) {
            // 【必须真正注销，不能只置空字段】
            //   只写 mListener = null 的话，旧代理**仍然挂在
            //   ViewTreeObserver 上**，每次 traversal 照样回调它，
            //   而它读到的 mRegion 是刚才 setEmpty() 之后的空值、
            //   mFullScreen 又是 false ——
            //   于是输出"空 region" = 整屏都不接触摸 = **全部穿过去**。
            //
            //   这就是"切到手柄后按键完全点不到、诊断显示全屏"的真因：
            //   最糟的不是不穿透，是**连按键都点不了**。
            Log.w(TAG, "自检失败 -> 注销 listener，避免空 region 整屏穿透");
            unregisterListener();
            return false;
        }
        return requestRecompute();
    }

    /**
     * 当场调用一次 listener，验证反射设字段是否真的可行。
     *
     * Android 9+ 有 hidden API 限制，InternalInsetsInfo 的字段可能在黑名单里，
     * setAccessible 会失败。这种失败必须在这里抓出来，不能等到运行时才发现。
     */
    private boolean probe() {
        try {
            Class<?> listenerClass =
                    Class.forName("android.view.ViewTreeObserver$OnComputeInternalInsetsListener");
            Class<?> infoClass =
                    Class.forName("android.view.ViewTreeObserver$InternalInsetsInfo");

            Object info = infoClass.getDeclaredConstructor().newInstance();
            Method m = listenerClass.getMethod("onComputeInternalInsets", infoClass);
            m.invoke(mListener, info);

            Field insetField = infoClass.getDeclaredField("mTouchableInsets");
            insetField.setAccessible(true);
            int v = (Integer) insetField.get(info);

            Field regionField = infoClass.getDeclaredField("touchableRegion");
            regionField.setAccessible(true);
            Region r = (Region) regionField.get(info);

            boolean ok = (v == TOUCHABLE_INSETS_REGION) && r != null && !r.isEmpty();
            if (!ok) {
                Log.w(TAG, "自检失败: inset=" + v + " region=" + r);
            }
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "自检抛异常(hidden API 被拦): " + t);
            return false;
        }
    }

    /**
     * 进入"整屏可触摸"档（编辑 / 列表打开时调用）。
     *
     * 【只翻开关，不动 mRegion】
     *   这一版的写法是 v2、v3 两次尝试之后定下来的，两种都不对：
     *
     *   v2：把 mRegion 设成整屏
     *       -> 编辑面板灵敏，但退出编辑后 region 被粘在整屏，
     *          穿透再也回不来（必须收起重开）。
     *   v3：完全不设，指望 FLAG_NOT_TOUCH_MODAL 屏蔽 region
     *       -> 穿透回来了，但编辑期间只剩按键区可点，
     *          面板"要点几次才有反应"。
     *
     *   根子是"临时需求"和"持久状态"被塞进了同一个变量。
     *   现在用独立的 mFullScreen 开关：listener 每次回调时现判断，
     *   mRegion 从头到尾只存按键区，谁都不污染谁。
     *
     * 【千万别 setEmpty()】
     *   空 region 的含义是"没有任何可触摸区域"——
     *   结果是整个窗口都不吃触摸，**连软件自己的界面都点不到**，
     *   触摸全部穿透到后面的游戏去。
     *   这个 bug 的具体表现：画布上点「布」「编」按钮时，
     *   编辑/列表面板弹出来了却完全点不动，点哪都穿到后面。
     */
    public void clear(View host) {
        if (!isSupported() || host == null) {
            return;
        }
        mHost = host;
        mFullScreen = true;
        requestRecompute();
    }

    /**
     * 整屏档用的宽 / 高。
     *
     * 拿不到尺寸（还没 layout 过）时用足够大的值兜底，
     * 保证覆盖整屏 —— 宁可多覆盖，也不能留下点不到的死角。
     */
    private int fullW() {
        int w = mHost != null ? mHost.getWidth() : 0;
        return w > 0 ? w : 5000;
    }

    private int fullH() {
        int h = mHost != null ? mHost.getHeight() : 0;
        return h > 0 ? h : 5000;
    }

    /** 创建并注册动态代理 listener。host 换了就重新注册。 */
    private boolean ensureListener() {
        if (mListener != null && mListenerHost == mHost) {
            return true;
        }
        // 【收起再显示会换 View 实例，旧 listener 必须注销】
        //   之前只看 "mListener != null" 就直接复用，于是收起再显示之后
        //   listener 还挂在**旧 GamepadView** 的 ViewTreeObserver 上，
        //   新的 host 压根没注册过 —— apply() 却照样返回 true，
        //   变成典型的"假成功"：诊断显示最优，region 从头到尾没生效。
        if (mListener != null) {
            unregisterListener();
        }
        try {
            Class<?> listenerClass =
                    Class.forName("android.view.ViewTreeObserver$OnComputeInternalInsetsListener");

            mListener = Proxy.newProxyInstance(
                    TouchRegionHelper.class.getClassLoader(),
                    new Class<?>[]{listenerClass},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            // 方法签名是 onComputeInternalInsets(InternalInsetsInfo)
                            if (args == null || args.length < 1 || args[0] == null) {
                                return null;
                            }
                            try {
                                Object info = args[0];
                                Field regionField =
                                        info.getClass().getDeclaredField("touchableRegion");
                                Field insetField =
                                        info.getClass().getDeclaredField("mTouchableInsets");
                                regionField.setAccessible(true);
                                insetField.setAccessible(true);

                                Region target = (Region) regionField.get(info);
                                if (target != null) {
                                    if (mFullScreen) {
                                        // 临时整屏档：编辑面板期间整屏可点。
                                        // 注意是**临时**的 —— mRegion 一个字节都不动，
                                        // 退出编辑把 mFullScreen 置回 false 就恢复按键区。
                                        target.set(0, 0, fullW(), fullH());
                                    } else if (mRegion.isEmpty()) {
                                        // 【空 region 兜底】
                                        //   空 region = 整屏都不接触摸 = 按键全点不到，
                                        //   这是**最糟**的状态（比不穿透还糟）。
                                        //   任何路径导致 mRegion 空了，都退化成整屏可点：
                                        //   至少按键还能用，穿透失效而已。
                                        target.set(0, 0, fullW(), fullH());
                                    } else {
                                        target.set(mRegion);
                                    }
                                }
                                insetField.set(info, TOUCHABLE_INSETS_REGION);
                            } catch (Throwable t) {
                                Log.w(TAG, "设置 region 失败: " + t);
                            }
                            return null;
                        }
                    });

            Method add = ViewTreeObserver.class.getDeclaredMethod(
                    "addOnComputeInternalInsetsListener", listenerClass);
            add.setAccessible(true);
            add.invoke(mHost.getViewTreeObserver(), mListener);
            mListenerHost = mHost;
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "注册 listener 失败: " + t);
            mListener = null;
            mListenerHost = null;
            return false;
        }
    }

    /** 从旧 host 的 ViewTreeObserver 上摘掉 listener。失败不影响后续重注册。 */
    private void unregisterListener() {
        if (mListener == null || mListenerHost == null) {
            return;
        }
        try {
            Class<?> listenerClass =
                    Class.forName("android.view.ViewTreeObserver$OnComputeInternalInsetsListener");
            Method remove = ViewTreeObserver.class.getDeclaredMethod(
                    "removeOnComputeInternalInsetsListener", listenerClass);
            remove.setAccessible(true);
            remove.invoke(mListenerHost.getViewTreeObserver(), mListener);
        } catch (Throwable t) {
            // 旧 host 可能已经 detach，VTO 成了 dead observer，移除无效但不会崩
            Log.w(TAG, "注销 listener 失败: " + t);
        }
        mListener = null;
        mListenerHost = null;
    }

    /**
     * 触发重新计算 insets，并让宿主补一次强制 relayout。
     *
     * listener 是在 layout 完成后回调的，不重新 requestLayout 的话
     * 新设的 region 要等下一次布局才生效。
     *
     * 【只 requestLayout 不够】
     *   它调度的是 traversal，而 region 是在 traversal 尾部的
     *   relayoutWindow() 才提交给 WMS 的；窗口尺寸和 flag 都没变时
     *   那一步会被跳过。所以这里回调宿主补一次 updateViewLayout，
     *   并且必须**晚于**当次 traversal（宿主用 postDelayed 实现），
     *   否则提交的还是上一次收集好的旧 region。
     */
    private boolean requestRecompute() {
        if (mHost == null) {
            return false;
        }
        try {
            mHost.requestLayout();
        } catch (Throwable t) {
            Log.w(TAG, "requestLayout 失败: " + t);
            return false;
        }
        if (mHook != null) {
            try {
                mHook.requestRelayout(mHost);
            } catch (Throwable t) {
                Log.w(TAG, "relayout 回调失败: " + t);
            }
        }
        return true;
    }
}
