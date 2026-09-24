package com.example.vgamepad;

/**
 * 虚拟键盘的 HID 报告（和手柄是**两个独立设备**，互不干扰）。
 *
 * 【为什么不把手柄描述符改大】
 *   手柄那份描述符已经实机验证过（右摇杆、L2/R2 都正常），
 *   往里塞键盘 usage 会改变报告布局，等于把已验证的东西推倒重来。
 *   单独开一个 uhid 设备，手柄那边一个字节都不用动。
 *
 * 【报告布局：NKRO bitmap（默认）】30 字节
 *   [0]     修饰键 bitmap：bit0=LCtrl bit1=LShift bit2=LAlt bit3=LGUI
 *                          bit4=RCtrl bit5=RShift bit6=RAlt bit7=RGUI
 *                          位号 = usage - 0xE0
 *   [1]     保留，恒为 0
 *   [2..29] 28 字节 = 224 位的**键 bitmap**，覆盖 usage 0x00..0xDF
 *           usage u -> 第 (2 + u/8) 字节的第 (u%8) 位
 *
 *   【为什么改 bitmap】
 *     原来是 boot 协议的 6 键 Array，同时最多 6 个普通键 ——
 *     那是 Array 只有 6 格的硬限制，不是代码写死的，塞不下就是塞不下。
 *     bitmap 一位一个键，224 位，够全键盘同时按下（NKRO）。
 *
 *   【为什么普通键只到 0xDF(223) 而修饰键单独占 [0]】
 *     修饰键 usage 是 0xE0..0xE7，和 [0] 那个 bitmap 一一对应；
 *     普通键 bitmap 声明到 0xDF 就不与之重叠，避免同一个 usage
 *     在两个 field 里各声明一次（Linux 会各映射一份，行为不可预期）。
 *     0xDF = 223 远大于实际用到的最大普通键（Menu 0x65、F12 0x45）。
 *
 *   【兼容性】
 *     bitmap 报告**不符合 boot 协议**，BIOS / 老系统不认。
 *     但 Android 走的是 Linux hid-input，4.0 之后 NKRO bitmap 已修好
 *     （torvalds/linux 6ce901eb61aa），正常映射成 Linux keycode，
 *     再由 Generic.kl 映射成 KEYCODE_* —— 和 6KRO 走的是同一条路。
 *     万一某台机器不认，守护进程加 --kbd-boot 参数可回退到旧的 6KRO 描述符。
 *
 * 【boot 6KRO 回退】见 DESCRIPTOR_BOOT 和 mBootMode。
 */
public final class KeyboardReport {

    /** NKRO bitmap 报告 30 字节。 */
    public static final int REPORT_SIZE = 30;
    /** boot 协议 6KRO 报告 8 字节（回退模式用）。 */
    public static final int REPORT_SIZE_BOOT = 8;

    /** 修饰键的 usage 起点：0xE0=LCtrl 0xE1=LShift 0xE2=LAlt 0xE3=LGUI ... */
    private static final int MOD_MIN = 0xE0;
    private static final int MOD_MAX = 0xE7;

    /** 键 bitmap 覆盖的 usage 上限（含）。修饰键不在这个区间里。 */
    private static final int KEY_MAX = 0xDF;

    /**
     * NKRO bitmap 键盘描述符（30 字节报告）。
     *
     * 结构：修饰键 bitmap(8b) + 保留(8b) + 键 bitmap(224b)。
     *
     * 刻意不做的事：
     *   - 不加 Report ID：带 ID 的键盘在部分系统上修饰键会丢；
     *   - 不加 Padding 位：macOS 会静默失败，Linux/Windows 正常，
     *     既然只跑 Android，不引入这个不确定性。
     */
    public static final byte[] DESCRIPTOR = new byte[]{
            0x05, 0x01,        // Usage Page (Generic Desktop)
            0x09, 0x06,        // Usage (Keyboard)
            (byte) 0xA1, 0x01, // Collection (Application)
            0x05, 0x07,        //   Usage Page (Key Codes)
            0x19, (byte) 0xE0, //   Usage Minimum (224)
            0x29, (byte) 0xE7, //   Usage Maximum (231)
            0x15, 0x00,        //   Logical Minimum (0)
            0x25, 0x01,        //   Logical Maximum (1)
            0x75, 0x01,        //   Report Size (1)
            (byte) 0x95, 0x08, //   Report Count (8)
            (byte) 0x81, 0x02, //   Input (Data,Var,Abs)      <- 修饰键 bitmap [0]
            (byte) 0x95, 0x01, //   Report Count (1)
            0x75, 0x08,        //   Report Size (8)
            (byte) 0x81, 0x01, //   Input (Const,Array,Abs)   <- 保留字节 [1]
            0x05, 0x07,        //   Usage Page (Key Codes)
            0x19, 0x00,        //   Usage Minimum (0)
            0x29, (byte) 0xDF, //   Usage Maximum (223)
            0x15, 0x00,        //   Logical Minimum (0)
            0x25, 0x01,        //   Logical Maximum (1)
            (byte) 0x95, (byte) 0xE0, // Report Count (224)
            0x75, 0x01,        //   Report Size (1)
            (byte) 0x81, 0x02, //   Input (Data,Var,Abs)      <- 224 位键 bitmap [2..29]
            (byte) 0xC0        // End Collection
    };

    /**
     * boot 协议 6KRO 描述符（8 字节报告）—— 只在 --kbd-boot 时用。
     *
     * 留着当退路：万一某台机器的 hid 栈对 bitmap 键盘处理有问题，
     * 加这个参数就能退回原来那份已验证过的布局，而不是彻底没有键盘。
     */
    public static final byte[] DESCRIPTOR_BOOT = new byte[]{
            0x05, 0x01,        // Usage Page (Generic Desktop)
            0x09, 0x06,        // Usage (Keyboard)
            (byte) 0xA1, 0x01, // Collection (Application)
            0x05, 0x07,        //   Usage Page (Key Codes)
            0x19, (byte) 0xE0, //   Usage Minimum (224)
            0x29, (byte) 0xE7, //   Usage Maximum (231)
            0x15, 0x00,        //   Logical Minimum (0)
            0x25, 0x01,        //   Logical Maximum (1)
            0x75, 0x01,        //   Report Size (1)
            (byte) 0x95, 0x08, //   Report Count (8)
            (byte) 0x81, 0x02, //   Input (Data,Var,Abs)      <- 修饰键 bitmap
            (byte) 0x95, 0x01, //   Report Count (1)
            0x75, 0x08,        //   Report Size (8)
            (byte) 0x81, 0x01, //   Input (Const,Array,Abs)   <- 保留字节
            0x05, 0x07,        //   Usage Page (Key Codes)
            0x19, 0x00,        //   Usage Minimum (0)
            0x29, (byte) 0xDD, //   Usage Maximum (221)
            0x15, 0x00,        //   Logical Minimum (0)
            0x25, (byte) 0xDD, //   Logical Maximum (221)
            (byte) 0x95, 0x06, //   Report Count (6)
            0x75, 0x08,        //   Report Size (8)
            (byte) 0x81, 0x00, //   Input (Data,Array,Abs)    <- 6 键数组
            (byte) 0xC0        // End Collection
    };

    private final byte[] mBuf;
    private final boolean mBoot;

    /** 默认 NKRO bitmap 模式。 */
    public KeyboardReport() {
        this(false);
    }

    /**
     * @param boot true = 用旧的 6KRO boot 布局（8 字节），false = NKRO bitmap（30 字节）
     */
    public KeyboardReport(boolean boot) {
        mBoot = boot;
        mBuf = new byte[boot ? REPORT_SIZE_BOOT : REPORT_SIZE];
    }

    /** 当前报告长度（字节）。守护进程建设备时要按这个传。 */
    public int size() {
        return mBuf.length;
    }

    /** 是否是 6KRO 回退模式。 */
    public boolean isBootMode() {
        return mBoot;
    }

    /**
     * 按下 / 松开一个键。
     *
     * @param usage HID keyboard usage（0x00..0xDF 或 0xE0..0xE7）
     */
    public void setKey(int usage, boolean pressed) {
        if (usage <= 0 || (usage > KEY_MAX && usage < MOD_MIN) || usage > MOD_MAX) {
            return;
        }
        if (mBoot) {
            setKeyBoot(usage, pressed);
            return;
        }
        if (usage >= MOD_MIN) {
            // 修饰键：[0] 的一位
            int bit = usage - MOD_MIN;
            if (pressed) mBuf[0] |= (byte) (1 << bit);
            else mBuf[0] &= (byte) ~(1 << bit);
            return;
        }
        // 普通键：224 位 bitmap 的一位。
        //   bitmap 没有"格子用完"这回事 —— 这就是换成它的原因。
        int idx = 2 + (usage >> 3);
        int bit = usage & 7;
        if (idx >= mBuf.length) {
            return;
        }
        if (pressed) mBuf[idx] |= (byte) (1 << bit);
        else mBuf[idx] &= (byte) ~(1 << bit);
    }

    /** 6KRO 回退：沿用原来的 6 格数组逻辑。 */
    private void setKeyBoot(int usage, boolean pressed) {
        if (usage >= MOD_MIN) {
            int bit = usage - MOD_MIN;
            if (pressed) mBuf[0] |= (byte) (1 << bit);
            else mBuf[0] &= (byte) ~(1 << bit);
            return;
        }
        if (pressed) {
            if (indexOfBoot(usage) >= 0) return;   // 已经按着，不重复占格
            int free = indexOfBoot(0);
            if (free < 0) return;                  // 6 格满，丢弃
            mBuf[2 + free] = (byte) usage;
        } else {
            int at = indexOfBoot(usage);
            if (at < 0) return;
            mBuf[2 + at] = 0;
            compactBoot();
        }
    }

    public void reset() {
        java.util.Arrays.fill(mBuf, (byte) 0);
    }

    private int indexOfBoot(int usage) {
        for (int i = 0; i < 6; i++) {
            if ((mBuf[2 + i] & 0xFF) == usage) {
                return i;
            }
        }
        return -1;
    }

    /** 把 6 个格子里的空洞压掉（只 boot 模式用得上）。 */
    private void compactBoot() {
        int w = 0;
        for (int i = 0; i < 6; i++) {
            int v = mBuf[2 + i] & 0xFF;
            if (v != 0) {
                mBuf[2 + w] = (byte) v;
                w++;
            }
        }
        for (int i = w; i < 6; i++) {
            mBuf[2 + i] = 0;
        }
    }

    /**
     * 当前按下的普通键个数（诊断用）。
     * bitmap 模式下一个个数出来，用来在终端上确认"确实超过了 6 个"。
     */
    public int pressedCount() {
        if (mBoot) {
            int n = 0;
            for (int i = 0; i < 6; i++) {
                if ((mBuf[2 + i] & 0xFF) != 0) n++;
            }
            return n;
        }
        int n = 0;
        for (int i = 2; i < mBuf.length; i++) {
            int b = mBuf[i] & 0xFF;
            while (b != 0) {
                n += (b & 1);
                b >>>= 1;
            }
        }
        return n;
    }

    /** Return the live buffer. Do not cache it; contents change in place. */
    public byte[] buffer() {
        return mBuf;
    }
}
