package com.example.vgamepad;

/**
 * Full-featured virtual gamepad HID report.
 *
 * Report layout (13 bytes, little-endian for 16-bit fields):
 *   [0]     buttons 1..8   (bit0=A  bit1=B  bit2=未用  bit3=X  bit4=Y
 *                           bit5=未用 bit6=L1 bit7=R1)
 *   [1]     buttons 9..16  (bit0=L2 bit1=R2 bit2=SELECT bit3=START
 *                           bit4=MODE bit5=THUMBL bit6=THUMBR)
 *   [2..3]  X   左摇杆 X    int16 (-32767..32767)
 *   [4..5]  Y   左摇杆 Y    int16
 *   [6..7]  Z   右摇杆 X    int16
 *   [8..9]  Rz  右摇杆 Y    int16
 *   [10]    hat switch, low nibble (0=N..7=NW, 8=neutral)
 *   [11]    Brake       L2 扳机   0..255
 *   [12]    Accelerator R2 扳机   0..255
 *
 * 这一版按 Android CDD（兼容性定义文档 7.2.6）的官方映射来排，不再猜：
 *
 *   CDD 原文（Analog Controls 表）：
 *     左扳机 L2   = HID usage 0x02 0x00C5 (Brake)       -> AXIS_LTRIGGER
 *     右扳机 R2   = HID usage 0x02 0x00C4 (Accelerator) -> AXIS_RTRIGGER
 *     左摇杆      = 0x01 0x0030 / 0x0031 (X / Y)        -> AXIS_X  / AXIS_Y
 *     右摇杆      = 0x01 0x0032 / 0x0035 (Z / Rz)       -> AXIS_Z  / AXIS_RZ
 *
 * 1) 右摇杆是 Z / Rz，不是 Rx / Ry。
 *    "Rx/Ry 当右摇杆 + Z/Rz 当扳机" 是 xpad / Xbox（PC、Linux 桌面）那边的约定，
 *    不是 Android 的。Android 官方文档写得非常直白：
 *        "对于右摇杆，Android 会将水平移动报告为 AXIS_Z 事件，
 *         将垂直移动报告为 AXIS_RZ 事件。"
 *    上一版按 Xbox 约定排，只有恰好被 ROM 里的 Vendor_045e_Product_028e.kl
 *    兜住时才看着正常；那份 kl 一旦不生效，右摇杆就成了 RX/RY（游戏读不到），
 *    而扳机写在 Z/Rz 上、被游戏当成右摇杆 —— 于是"按 L2 右摇杆动、
 *    推右摇杆没反应"这种对调现象就出现了。
 *
 * 2) 扳机必须用 Simulation 页的 Brake / Accelerator。
 *    Linux hid-input.c 里 0xC4 -> ABS_GAS、0xC5 -> ABS_BRAKE，
 *    Android 再把它们暴露成 AXIS_GAS(=AXIS_RTRIGGER) / AXIS_BRAKE(=AXIS_LTRIGGER)。
 *    之前用 Z/Rz 当扳机，Android 侧看到的是 AXIS_Z / AXIS_RZ —— 那是右摇杆的位置，
 *    所以只认 AXIS_BRAKE / AXIS_GAS 的游戏和测试器完全没反应。
 *    （L2/R2 现在仍然同时发按钮和扳机轴，只认按钮的游戏也能用。）
 *
 * 3) 按钮位号不能按 "A B X Y L1 R1 L2 R2 ..." 顺排。
 *    HID button N 会被 Linux 映射成 BTN_GAMEPAD + (N-1)，
 *    再经 Generic.kl 变成 Android KEYCODE，顺序是：
 *        A B C X Y Z L1 R1 L2 R2 SELECT START MODE THUMBL THUMBR
 *    顺排的话 X 落在 BUTTON_C、Y 落在 BUTTON_X、L1 落在 BUTTON_Y，整体串位。
 *    所以下面的常量直接写"该占的位号"，中间 C、Z 两位空着不用。
 *    这一条没变，上一版改对了。
 */
public final class GamepadReport {

    public static final int REPORT_SIZE = 13;

    /** 按钮在报告里占的位号（不是顺序编号），传给 setButton(index, pressed)。 */
    public static final int BTN_A = 0;        // HID button 1  -> KEYCODE_BUTTON_A
    public static final int BTN_B = 1;        // HID button 2  -> KEYCODE_BUTTON_B
    public static final int BTN_C = 2;
    public static final int BTN_Z = 5;
    public static final int BTN_X = 3;        // HID button 4  -> KEYCODE_BUTTON_X
    public static final int BTN_Y = 4;        // HID button 5  -> KEYCODE_BUTTON_Y
    public static final int BTN_L1 = 6;       // HID button 7  -> KEYCODE_BUTTON_L1
    public static final int BTN_R1 = 7;       // HID button 8  -> KEYCODE_BUTTON_R1
    public static final int BTN_L2 = 8;       // HID button 9  -> KEYCODE_BUTTON_L2
    public static final int BTN_R2 = 9;       // HID button 10 -> KEYCODE_BUTTON_R2
    public static final int BTN_SELECT = 10;  // HID button 11 -> KEYCODE_BUTTON_SELECT
    public static final int BTN_START = 11;   // HID button 12 -> KEYCODE_BUTTON_START
    public static final int BTN_MODE = 12;    // HID button 13 -> KEYCODE_BUTTON_MODE
    public static final int BTN_THUMBL = 13;  // HID button 14 -> KEYCODE_BUTTON_THUMBL
    public static final int BTN_THUMBR = 14;  // HID button 15 -> KEYCODE_BUTTON_THUMBR

    /** Hat switch values (HID: clockwise from North). */
    public static final int HAT_N = 0;
    public static final int HAT_NE = 1;
    public static final int HAT_E = 2;
    public static final int HAT_SE = 3;
    public static final int HAT_S = 4;
    public static final int HAT_SW = 5;
    public static final int HAT_W = 6;
    public static final int HAT_NW = 7;
    public static final int HAT_NEUTRAL = 8;

    /**
     * HID report descriptor（Android CDD 规范布局，89 字节）：
     *   16 buttons + X/Y/Z/Rz (16-bit) + hat switch + Brake/Accelerator triggers.
     *
     * 用法顺序（Usage）决定数据顺序，报告共 13 字节：
     *   16 个按钮 = 2 字节，X/Y/Z/Rz 各 16 位 = 8 字节，
     *   hat 4 位 + 4 位填充 = 1 字节，Brake/Accelerator 各 8 位 = 2 字节。
     *
     * Usage 与 Linux / Android 轴的对应关系（改这里之前先读一遍 CDD 7.2.6）：
     *   0x30 X            -> ABS_X  (0x00) -> AXIS_X        左摇杆 横
     *   0x31 Y            -> ABS_Y  (0x01) -> AXIS_Y        左摇杆 纵
     *   0x32 Z            -> ABS_Z  (0x02) -> AXIS_Z        右摇杆 横
     *   0x35 Rz           -> ABS_RZ (0x05) -> AXIS_RZ       右摇杆 纵
     *   0x39 Hat          -> ABS_HAT0X/Y   -> AXIS_HAT_X/Y  十字键
     *   0x02/0xC5 Brake   -> ABS_BRAKE(0x0a) -> AXIS_BRAKE   = AXIS_LTRIGGER  L2
     *   0x02/0xC4 Accel   -> ABS_GAS  (0x09) -> AXIS_GAS     = AXIS_RTRIGGER  R2
     */
    public static final byte[] DESCRIPTOR = new byte[]{
            0x05, 0x01, 0x09, 0x05, (byte) 0xA1, 0x01, 0x05, 0x09, 0x19, 0x01, 0x29, 0x10,
            0x15, 0x00, 0x25, 0x01, 0x75, 0x01, (byte) 0x95, 0x10, (byte) 0x81, 0x02, 0x05, 0x01,
            // X / Y = 左摇杆，Z / Rz = 右摇杆（Android 规定，不是 Rx/Ry）
            0x09, 0x30, 0x09, 0x31, 0x09, 0x32, 0x09, 0x35, 0x16, 0x00, (byte) 0x80, 0x26,
            (byte) 0xFF, 0x7F, 0x75, 0x10, (byte) 0x95, 0x04, (byte) 0x81, 0x02, 0x09, 0x39, 0x15, 0x00,
            0x25, 0x07, 0x35, 0x00, 0x46, 0x3B, 0x01, 0x65, 0x14, 0x75, 0x04, (byte) 0x95,
            0x01, (byte) 0x81, 0x42, 0x65, 0x00, 0x75, 0x01, (byte) 0x95, 0x04, (byte) 0x81, 0x01,
            // Brake / Accelerator = L2 / R2 扳机（切到 Simulation 页 0x02）
            0x05, 0x02, 0x09, (byte) 0xC5, 0x09, (byte) 0xC4, 0x15, 0x00, 0x26, (byte) 0xFF, 0x00,
            0x75, 0x08, (byte) 0x95, 0x02, (byte) 0x81, 0x02, (byte) 0xC0,
    };

    private final byte[] mBuf = new byte[REPORT_SIZE];
    private int mButtons;
    private int mHat = HAT_NEUTRAL;

    public GamepadReport() {
        mBuf[10] = (byte) HAT_NEUTRAL;
    }

    /** Press or release a button. index = one of BTN_* constants (0..15). */
    public void setButton(int index, boolean pressed) {
        if (index < 0 || index > 15) return;
        if (pressed) mButtons |= (1 << index);
        else mButtons &= ~(1 << index);
        mBuf[0] = (byte) (mButtons & 0xFF);
        mBuf[1] = (byte) ((mButtons >> 8) & 0xFF);
    }

    /** Set an axis from a normalized -1.0f..1.0f value. axis: 0=X 1=Y 2=Z(右摇杆X) 3=Rz(右摇杆Y) */
    public void setAxis(int axis, float value) {
        if (axis < 0 || axis > 3) return;
        if (value > 1f) value = 1f;
        if (value < -1f) value = -1f;
        int v = Math.round(value * 32767f);
        int off = 2 + axis * 2;
        mBuf[off] = (byte) (v & 0xFF);
        mBuf[off + 1] = (byte) ((v >> 8) & 0xFF);
    }

    /** D-Pad. Use HAT_* constants, HAT_NEUTRAL to release. */
    public void setHat(int hat) {
        mHat = (hat < 0 || hat > 8) ? HAT_NEUTRAL : hat;
        mBuf[10] = (byte) (mBuf[10] & 0xF0 | (mHat & 0x0F));
    }

    /** Trigger from normalized 0.0f..1.0f. trigger: 0 = L2(Brake), 1 = R2(Accelerator) */
    public void setTrigger(int trigger, float value) {
        if (value < 0f) value = 0f;
        if (value > 1f) value = 1f;
        int v = Math.round(value * 255f);
        if (trigger == 0) mBuf[11] = (byte) v;
        else if (trigger == 1) mBuf[12] = (byte) v;
    }

    public void reset() {
        mButtons = 0;
        mHat = HAT_NEUTRAL;
        java.util.Arrays.fill(mBuf, (byte) 0);
        mBuf[10] = (byte) HAT_NEUTRAL;
    }

    /** Return the live buffer. Do not cache it; contents change in place. */
    public byte[] buffer() {
        return mBuf;
    }
}
