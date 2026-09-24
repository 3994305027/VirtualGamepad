package com.example.vgamepad;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/**
 * 一个按键 = 一个窗口。
 *
 * 【为什么要有这个类】
 *   原来整个手柄是一个 MATCH_PARENT 的全屏窗口，屏幕任何位置都落在它上面，
 *   于是空白处也被"吃掉"了 —— 点空白不会传到下面的游戏。
 *   现在改成：画面仍由那个全屏窗口统一绘制（绘制代码一行不用改），
 *   但触摸交给**每个按键各自的小窗口**，窗口只覆盖按键本身。
 *   窗口之外的区域没有任何覆盖层，点击自然就落到游戏上了。
 *
 * 【正常情况完全透明】
 *   默认只负责吃触摸，不画任何东西（PixelFormat.TRANSPARENT）。
 *
 * 【调试模式会画出判定框】
 *   触摸窗口是隐形的，光靠推理根本不知道它到底落在哪。
 *   开了「显示判定框」之后，这里会把窗口边界画成红色 ——
 *   和画面上的按键一对照，偏没偏、偏多少，一眼就看出来了。
 */
public class KeyTouchView extends View {

    public interface Sink {
        void onKeyTouch(int elem, int action, float x, float y);
    }

    private int mElem = -1;
    private Sink mSink;
    private boolean mDebug;
    private Paint mFill;
    private Paint mStroke;
    private Paint mCross;

    public KeyTouchView(Context c) {
        super(c);
        // View 默认不调用 onDraw（没背景时），必须显式关掉这个优化
        setWillNotDraw(false);
    }

    void bind(int elem, Sink sink) {
        mElem = elem;
        mSink = sink;
    }

    /** 调试开关：true 时画出判定框。 */
    void setDebug(boolean on) {
        if (mDebug == on) {
            return;
        }
        mDebug = on;
        invalidate();
    }

    private void ensurePaints() {
        if (mFill != null) {
            return;
        }
        mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        mFill.setColor(0x33FF0000);      // 半透明红填充
        mFill.setStyle(Paint.Style.FILL);

        mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        mStroke.setColor(0xFFFF0000);    // 实心红边框
        mStroke.setStyle(Paint.Style.STROKE);
        mStroke.setStrokeWidth(2f);

        mCross = new Paint(Paint.ANTI_ALIAS_FLAG);
        mCross.setColor(0xFFFFFF00);     // 黄色十字，标中心
        mCross.setStyle(Paint.Style.STROKE);
        mCross.setStrokeWidth(2f);
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (!mDebug) {
            return;
        }
        ensurePaints();
        float w = getWidth();
        float h = getHeight();
        if (w <= 0f || h <= 0f) {
            return;
        }
        c.drawRect(0f, 0f, w, h, mFill);
        // 边框往里缩 1px，免得被窗口边缘裁掉看不见
        c.drawRect(1f, 1f, w - 1f, h - 1f, mStroke);
        float cx = w / 2f;
        float cy = h / 2f;
        c.drawLine(cx - 6f, cy, cx + 6f, cy, mCross);
        c.drawLine(cx, cy - 6f, cx, cy + 6f, mCross);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (mSink == null || mElem < 0) {
            return false;
        }
        // 每个小窗口里同时只会有一个手指，取第 0 个指针就够。
        mSink.onKeyTouch(mElem, e.getActionMasked(), e.getRawX(), e.getRawY());
        return true;
    }
}
