package com.example.vgamepad;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/**
 * The small draggable bubble shown when the gamepad is collapsed.
 * Tap it to expand the gamepad, long-press to stop the service entirely.
 */
public class BubbleView extends View {

    private final Paint mBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean mPressed;

    /** 样式来自「悬浮窗」布局里那颗 G，见 PadLayout.bubbleStyle()。 */
    private int mBaseColor = 0xFF336699;
    private String mLabel = "G";
    private int mShape = PadLayout.SHAPE_CIRCLE;
    private boolean mShowLabel = true;
    /** 字号倍率，来自布局里那个「字体」滑条。 */
    private float mTextScale = 1f;
    /** 透明度。applyStyle 里存一份，drawBall 要用。默认和 G 一致（75%）。 */
    private float mAlpha = PadLayout.FLOAT_BALL_ALPHA;
    /** 按下态就是常态色提亮，不再另写一支色 —— 换个底色也跟得上。 */
    private static final int PRESSED_LIFT = 0x22;

    /**
     * 套用悬浮窗布局里 G 的样式。
     *
     * 【为什么不能继续写死】
     *   写死的话用户在悬浮窗布局里调的底色 / 名字 / 形状全都不会生效，
     *   那个布局就只是"看着能改、实际没用"。
     */
    public void applyStyle(PadLayout.BubbleStyle bs) {
        if (bs == null) {
            return;
        }
        mBaseColor = bs.color;
        mLabel = (bs.label == null || bs.label.length() == 0) ? "G" : bs.label;
        mShape = bs.shape;
        mShowLabel = bs.showLabel;
        mTextScale = (bs.textScale > 0.1f) ? bs.textScale : 1f;
        if ((bs.color & 0x00FFFFFF) == 0) {
            // 颜色整个是 0（读档失败之类的）就退回内置的蓝，
            // 不然球画成一个黑点。透明度不走这里，见 mAlpha。
            mBaseColor = 0xFF336699;
        } else {
            // 【颜色一律压成不透明】透明度只有 mAlpha 一个来源。
            mBaseColor = 0xFF000000 | (bs.color & 0x00FFFFFF);
        }
        mAlpha = clamp01(bs.alpha);
        invalidate();
    }

    private static float clamp01(float v) {
        return v < 0.05f ? 0.05f : (v > 1f ? 1f : v);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    public BubbleView(Context c) {
        super(c);
        mBgPaint.setStyle(Paint.Style.FILL);
        mBgPaint.setColor(mBaseColor);
        mRingPaint.setStyle(Paint.Style.STROKE);
        mRingPaint.setStrokeWidth(3f);
        mRingPaint.setColor(0xE0FFFFFF);
        // 【字色跟着底色明暗走】
        //   写死白字的话，把 G 调成浅黄 / 白色底就完全看不清了。
        mTextPaint.setColor(0xE0FFFFFF);
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        mTextPaint.setTextSize(26f);
    }

    public void setPressed(boolean pressed) {
        if (mPressed != pressed) {
            mPressed = pressed;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth();
        float h = getHeight();
        float r = Math.min(w, h) / 2f - 3f;
        drawBall(c, w / 2f, h / 2f, r, mBgPaint, mRingPaint, mTextPaint,
                mBaseColor, mShape, mLabel, mShowLabel, mTextScale,
                mAlpha, mPressed, getResources().getDisplayMetrics().density);
    }

    /**
     * 画这颗球。
     *
     * 【必须和布局里那颗 G 走同一个函数】
     *   两边各写一遍的话，样式再怎么同步也总有对不上的细节
     *   （描边色、描边是 STROKE 还是 FILL、字对齐、字号算法）。
     *   事实就是：GamepadView 里重写第二遍时漏了 ring 的
     *   setStyle(STROKE) 和 setColor —— 默认黑色 FILL，
     *   画完填充又画一个黑实心圆把底色整块盖掉，字也没居中。
     *   共用一份代码就不会再出现这种"抄漏了"的偏差。
     *
     * 【画笔由调用方传，样式在这里无条件设】
     *   onDraw 里 new Paint 会每次重绘都分配，所以复用调用方那几支。
     *   但样式全部在这里设，调用方不用记得初始化 —— 漏一个就画歪。
     */
    public static void drawBall(Canvas c, float cx, float cy, float r,
            Paint bg, Paint ring, Paint text,
            int color, int shape, String label, boolean showLabel,
            float textScale, float alpha, boolean pressed, float density) {
        int col = color;
        if (col == 0) {
            col = 0xFF336699;
        }
        // 【颜色一律压成不透明：透明度只有一个来源，就是参数 alpha】
        //   以前颜色自己也带 alpha（默认 0xC0336699），绘制时再和参数相乘 ——
        //   于是 UI 上写着 75%，屏幕上却是 75%×75%，两边对不上。
        //   现在颜色只管 rgb，透明度全交给 alpha（见 applyPickedColor）。
        col = 0xFF000000 | (col & 0x00FFFFFF);
        if (pressed) {
            int rr = Math.min(255, ((col >> 16) & 0xFF) + PRESSED_LIFT);
            int gg = Math.min(255, ((col >> 8) & 0xFF) + PRESSED_LIFT);
            int bb = Math.min(255, (col & 0xFF) + PRESSED_LIFT);
            col = (col & 0xFF000000) | (rr << 16) | (gg << 8) | bb;
        }
        float a = alpha < 0.05f ? 0.05f : (alpha > 1f ? 1f : alpha);
        int ai = Math.round(255f * a);

        bg.setStyle(Paint.Style.FILL);
        bg.setColor(col);
        bg.setAlpha(ai);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(3f);
        ring.setColor(0xE0FFFFFF);
        ring.setAlpha(ai);
        if (shape == PadLayout.SHAPE_RECT) {
            float rr8 = 8f * density;
            c.drawRoundRect(cx - r, cy - r, cx + r, cy + r, rr8, rr8, bg);
            c.drawRoundRect(cx - r, cy - r, cx + r, cy + r, rr8, rr8, ring);
        } else {
            c.drawCircle(cx, cy, r, bg);
            c.drawCircle(cx, cy, r, ring);
        }
        if (!showLabel) {
            return;
        }
        String lb = (label == null || label.length() == 0) ? "G" : label;
        text.setStyle(Paint.Style.FILL);
        text.setColor(0xE0FFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
        text.setAlpha(ai);
        // 字号跟着球大小走：名字长了要缩，否则顶出球外
        float d = r * 2f;
        // 【上限沿用原来那个 26（px）】
        //   乘 density 变成 26dp 之后，高分屏上字号从 26px 涨到 65px，
        //   整整大 2.5 倍 —— 球上的字本来就按"固定 26px"定的，
        //   不是随屏幕缩放的。改回 26 才是原来那个观感。
        float base = 26f;
        float ts = Math.min(base, d * 0.42f / Math.max(1f, lb.length() * 0.6f));
        float size = Math.max(10f, Math.min(ts, d * 0.42f));
        if (textScale > 0.1f) {
            size *= textScale;
        }
        text.setTextSize(size);
        Paint.FontMetrics fm = text.getFontMetrics();
        c.drawText(lb, cx, cy - (fm.ascent + fm.descent) / 2, text);
    }
}