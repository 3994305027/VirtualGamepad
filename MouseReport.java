package com.example.vgamepad;

/**
 * 虚拟鼠标 HID 报告（第三个 uhid 设备）。
 *
 * 报告布局（5 字节）：
 *   [0]     按键位图    bit0=左键 bit1=右键 bit2=中键 bit3=侧键1 bit4=侧键2
 *                       bit5..7 = 填充（描述符里声明为 Cnst，必须留 0）
 *   [1]     X 相对位移  int8 (-127..127)
 *   [2]     Y 相对位移  int8
 *   [3]     Wheel       int8  垂直滚轮
 *   [4]     AC Pan      int8  水平滚轮（Consumer 页 0x0C/0x0238）
 *
 * 【和手柄的本质区别：增量模型，不是状态模型】
 *   手柄摇杆 setAxis 传的是"当前处于 -1..1 的哪个位置"，
 *   丢一帧下一帧自然补回来。
 *   鼠标这里传的是"这一下往右走了几格"，丢一条就少走一段，
 *   而且**不会自愈** —— 光标位置永远差那么一点。
 *   所以调用方必须保证每条都送到，不能像摇杆那样随意丢帧。
 *
 * 【为什么是相对而不是绝对】
 *   Android 的鼠标就是相对设备：只有 REL_X / REL_Y，没有"屏幕坐标"这个概念。
 *   指针画在哪、怎么加速，全是 system_server 的 PointerController 的事。
 *   App 只管"往哪边走了多少"，不需要、也读不到指针当前位置
 *   —— 这正是它比"画个准星自己算坐标"简单得多的原因。
 */
public final class MouseReport {

    public static final int REPORT_SIZE = 5;

    /** 按键位号（传给 setButton）。 */
    public static final int BTN_LEFT = 0;
    public static final int BTN_RIGHT = 1;
    public static final int BTN_MIDDLE = 2;
    public static final int BTN_SIDE1 = 3;
    public static final int BTN_SIDE2 = 4;

    /**
     * HID report descriptor（67 字节）：
     *   5 个按键位 + 3 位填充 = 1 字节
     *   X / Y / Wheel 各 8 位相对 = 3 字节
     *   AC Pan（水平滚轮）8 位相对 = 1 字节
     *
     * 0x81 0x06 = Input (Data,Var,Relative) —— 相对轴必须用它，
     * 写成 0x02（Absolute）的话系统会当成绝对设备，鼠标指针不会动。
     */
    public static final byte[] DESCRIPTOR = new byte[]{
            0x05, 0x01, 0x09, 0x02, (byte) 0xA1, 0x01, 0x09, 0x01, (byte) 0xA1, 0x00, 0x05, 0x09,
            0x19, 0x01, 0x29, 0x05, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, (byte) 0x95, 0x05,
            (byte) 0x81, 0x02, 0x75, 0x03, (byte) 0x95, 0x01, (byte) 0x81, 0x03, 0x05, 0x01, 0x09, 0x30,
            0x09, 0x31, 0x09, 0x38, 0x15, (byte) 0x81, 0x25, 0x7F, 0x75, 0x08, (byte) 0x95, 0x03,
            (byte) 0x81, 0x06, 0x05, 0x0C, 0x0A, 0x38, 0x02, 0x15, (byte) 0x81, 0x25, 0x7F, 0x75,
            0x08, (byte) 0x95, 0x01, (byte) 0x81, 0x06, (byte) 0xC0, (byte) 0xC0,
    };

    private final byte[] mBuf = new byte[REPORT_SIZE];
    private int mButtons;

    /** Press or release a button. index = one of BTN_* constants (0..4). */
    public void setButton(int index, boolean pressed) {
        if (index < 0 || index > 4) return;
        if (pressed) mButtons |= (1 << index);
        else mButtons &= ~(1 << index);
        mBuf[0] = (byte) (mButtons & 0x1F);
    }

    /**
     * 相对位移。dx / dy 会被截到 -127..127（一个 int8 装不下更大的）。
     *
     * 【调用方注意】截断了就等于丢了一段位移，光标会少走 ——
     * 手指一次划很远时，宁可拆成几条小的发出去，也别发一条超范围的。
     */
    public void setMove(int dx, int dy) {
        mBuf[1] = (byte) clamp(dx);
        mBuf[2] = (byte) clamp(dy);
    }

    /** 垂直滚轮，正 = 向上滚。 */
    public void setWheel(int v) {
        mBuf[3] = (byte) clamp(v);
    }

    /** 水平滚轮（AC Pan），正 = 向右。 */
    public void setPan(int v) {
        mBuf[4] = (byte) clamp(v);
    }

    private static int clamp(int v) {
        if (v > 127) return 127;
        if (v < -127) return -127;
        return v;
    }

    /**
     * 清掉这一条的位移和滚轮，但**保留按键状态**。
     *
     * 按键是跨帧的（按下后不松就一直按着），位移是一次性的（发完就归零，
     * 否则下一帧会被重复计入）。两者生命周期不同，必须分开清。
     */
    public void clearDelta() {
        mBuf[1] = 0;
        mBuf[2] = 0;
        mBuf[3] = 0;
        mBuf[4] = 0;
    }

    public void reset() {
        mButtons = 0;
        java.util.Arrays.fill(mBuf, (byte) 0);
    }

    /** Return the live buffer. Do not cache it; contents change in place. */
    public byte[] buffer() {
        return mBuf;
    }
}
