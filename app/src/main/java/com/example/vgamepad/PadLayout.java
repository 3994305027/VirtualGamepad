package com.example.vgamepad;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 手柄上所有可编辑元素的位置 / 大小 / 透明度。
 *
 * 坐标一律按"相对屏幕宽高的比例"保存（0.0 ~ 1.0），这样旋转屏幕、
 * 换设备都不会错位。横屏和竖屏各存一套，互不影响。
 *
 * 第一次使用时没有存档，就用 reset() 里的默认布局（分横竖两套）。
 */
public final class PadLayout {

    /**
     * L2 / R2 扳机滑轨的高度 = 按钮半径 × 这个值。
     * 必须和 GamepadView 里的 TRACK_H 一致：这里用它算默认位置和收边，
     * 那边用它画图形，两边对不上就会有半截轨道露在屏幕外面。
     */
    public static final float TRACK_H_RATIO = 3.0f;

    /** L2 / R2 画成扳机滑轨，其余都是圆按钮。布局收边要区别对待。 */
    public static boolean isTrigger(int i) {
        return i == I_L2 || i == I_R2;
    }

    /** 固定元素（摇杆、ABXY、肩键……）的个数，不含动态创建的键盘按键。 */
    public static final int N_FIXED = 22;

    /**
     * 最多能创建多少个键盘按键。
     *
     * 受两处限制：
     *   1) 键盘报告是 NKRO bitmap，一位一个键，没有 6 键上限。
     *      但"创建 16 个、每次按 6 个"是允许的，所以这里可以大于 6。
     *   2) 编辑模式的按键列表**没有滚动**（layoutPickList 按屏高自适应行高），
     *      元素太多行高会被压到点不准。20 + 16 = 36 是实测还能点的上限。
     */
    public static final int MAX_KEYS = 110;

    /**
     * 键盘槽位的结束下标（不含）。
     *
     * 之前 N = N_FIXED + MAX_KEYS，键盘槽位直接顶到数组末尾。
     * 现在末尾要多放一个「模式切换」按钮，所以显式划出边界：
     *   0 .. N_FIXED-1        固定手柄元素
     *   I_KEY0 .. N_KEY_END-1 键盘槽位
     *   I_LAYOUT (= N_KEY_END)  布局切换按钮「布」
     *   N                     数组总长
     *
     * 【为什么模式按钮放末尾而不是塞进 N_FIXED】
     *   塞进固定区会让 N_FIXED 22 -> 23，I_KEY0 跟着 22 -> 23，
     *   于是**老存档里 index 22 起的键盘按键全部错位一格**
     *   （原来 22 是第一个键盘键，变成模式按钮了）。
     *   放末尾则 0..53 一个不动，老存档直接兼容。
     */
    public static final int N_KEY_END = N_FIXED + MAX_KEYS;

    /** 动态键盘按键的起始下标：I_KEY0 .. N_KEY_END - 1 */
    public static final int I_KEY0 = N_FIXED;

    /**
     * 布局切换按钮（运行时换布局，标签「布」）。
     *
     * 【原来这里还有一个 I_MODE（模式切换「键」），已删除】
     *   有了「布局切换」，它就是多余的了：点「布」能切到任意布局，
     *   包括默认手柄和默认键盘 —— 模式按钮能做的事它全都能做，
     *   多留一个入口反而让人分不清该点哪个。
     *
     * I_LAYOUT 接替了原来 I_MODE 的槽位（N_KEY_END），
     * 所以「布」就显示在原「键」的位置上，N 也少了 1。
     *
     * 默认隐藏，去「常用工具 -> 隐藏按钮」放出。
     */
    public static final int I_LAYOUT = N_KEY_END;

    /**
     * 手柄元素的**副本槽位**。
     *
     * 为什么要额外一段：手柄元素原本是一个下标 = 一种键（I_A=3 就是 A 键），
     * 全局唯一，没法"建第二个 A"。要让它们也能像键盘键那样增删，
     * 就得有空槽位可用。
     *
     * 放在**数组末尾**（I_LAYOUT 之后）是必须的：
     * 插在前面的话 I_KEY0 / N_KEY_END / I_LAYOUT 全都会移位，
     * 老存档里"下标 22 = 某个键盘键"的对应关系就全乱了。
     */
    public static final int PAD_EXTRA_START = I_LAYOUT + 1;
    /** 最多再建 32 个手柄键副本。 */
    public static final int MAX_PAD_EXTRA = 32;

    /**
     * 「穿透开关」按钮：切「按键点按穿透」，绿√ / 红×。
     *
     * 它必须是一个**真元素**（而不是状态行上的一个装饰），因为：
     *   穿透开着的时候，悬浮窗的 touchableRegion 只覆盖按键区域 ——
     *   画在状态行上的按钮根本不在 region 里，点上去直接落到游戏，
     *   表现为"完全点不到"。成了元素就会自动进 region，点击才有。
     *   顺带就有了隐藏 / 调大小 / 拖动这些能力，跟「编 / 收 / 布」一致。
     *
     * 放在**数组最末尾**（副本槽位之后）：见 MIG_* 的说明。
     */
    public static final int I_PASS = PAD_EXTRA_START + MAX_PAD_EXTRA;

    /**
     * 空白按钮槽位段。
     *
     * 空白按钮 = 只有按钮背景、没有名字、按下去什么都不发的元素。
     * 用来占位 / 挡住游戏里某块区域 / 先摆好位置之后再说。
     *
     * 【为什么又是末尾】和 I_PASS 同理：摆位存档用下标当 key，
     * 插在中间会让老存档整段错位。放末尾则 0..I_PASS 一个不动。
     */
    public static final int BLANK_START = I_PASS + 1;
    /** 最多 16 个空白按钮。一个屏幕上摆十几个空白已经够乱了。 */
    public static final int MAX_BLANK = 16;

    /**
     * 组合键槽位段。
     *
     * 组合键 = 按一下同时发出好几个键（手柄键 / 键盘键混着都行）。
     *
     * 【为什么又放末尾】和空白按钮同理：摆位存档用下标当 key，
     * 插在中间会让老存档整段错位。放末尾则 0..BLANK_START+MAX_BLANK 全不动。
     */
    public static final int COMBO_START = BLANK_START + MAX_BLANK;
    /** 最多 16 个组合键。 */
    public static final int MAX_COMBO = 16;
    /**
     * 一个组合键里最多放几个动作（键 或 延迟）。
     *
     * 【为什么是 32 而不是 8】
     *   原先用 8 是"一只手按不了那么多"这种假设，但组合键的用途
     *   不只是"同时按住" —— 加了「延迟」之后它更像一段小宏
     *   （按下 A → 等 100ms → 按 B → 等 50ms → 松开 A…），
     *   一条序列里光延迟就能占掉一半格子，8 个很快就不够。
     *
     * 【改这个数不影响老存档】
     *   存档是按槽位存成 "类型:编码,类型:编码,…" 的字符串，
     *   读的时候按 j 顺序填回去，不是按一维数组的下标写的。
     *   老存档最多 8 项，读进来落在前 8 格，不会错位。
     */
    public static final int MAX_COMBO_ACTS = 32;
    /** 十字架组合键的方向数：上 / 下 / 左 / 右。 */
    public static final int COMBO_DIRS = 4;
    public static final int DIR_UP = 0;
    public static final int DIR_DOWN = 1;
    public static final int DIR_LEFT = 2;
    public static final int DIR_RIGHT = 3;
    /** 动作类型 4：结束（把前面还没松开的键全部松开）。 */
    public static final int ACT_RELEASE = 4;
    /** 动作类型 5：结束指定的**手柄**键（code = 那个键的编码）。 */
    public static final int ACT_RELEASE_PAD = 5;
    /** 动作类型 6：结束指定的**键盘**键（code = 那个键的编码）。 */
    public static final int ACT_RELEASE_KEY = 6;
    /**
     * 动作类型 7：把**摇杆**推到某个方向。
     *
     * 【摇杆不是按钮】onButton 只能表达"按下/松开"，而摇杆要的是轴值
     * （AXIS_X / AXIS_Y）。之前选了左摇杆会走 buttonIndexOfProto 的
     * default 分支发出 BTN_START —— 既不是摇杆，还是个张冠李戴的键。
     *
     * code = 摇杆原型下标（I_LS / I_RS），tag = 方向号 0..7（见 GamepadView.DIR_NAMES）。
     */
    public static final int ACT_STICK_DIR = 7;
    /**
     * 动作类型 8：把**十字键**拨到某个方向。
     *
     * 同理，十字键走的是 hat 事件（onHat），不是按钮。
     *
     * code = I_DPAD，tag = hat 值 0..7（8 = 居中/松开）。
     */
    public static final int ACT_HAT_DIR = 8;
    /**
     * 动作类型 9：让**摇杆**回中（松开前面推住的方向）。
     *
     * 【为什么要有它】摇杆不回中的话，序列里"推住 → 等一会儿"之后
     *   只有播完才回中，想在中间提前回中做不到。
     */
    public static final int ACT_RELEASE_STICK = 9;
    /** 动作类型 10：让**十字键**回中（hat = 8）。 */
    public static final int ACT_RELEASE_HAT = 10;
    /**
     * 动作类型 11：摇杆推到**自定义坐标**（高级模式）。
     *
     * 预设只有八个方向，够用但推不满斜角的任意角度。
     * code = 摇杆原型下标；tag = 打包后的 X / Y（见 packStick）。
     */
    public static final int ACT_STICK_XY = 11;

    /**
     * 把 X / Y（各 -100..100，单位百分比）打包进一个 int。
     *
     * tag 只有一格 int 可用，而动作表是 int[]，不好再开一列；
     * 各占 8 位足够（0..200），高 8 位放 X、低 8 位放 Y。
     */
    public static int packStick(int x, int y) {
        int px = Math.max(0, Math.min(200, x + 100));
        int py = Math.max(0, Math.min(200, y + 100));
        return (px << 8) | py;
    }

    /** 从 tag 解出 X（-100..100）。 */
    public static int stickXOf(int tag) {
        return ((tag >> 8) & 0xFF) - 100;
    }

    /** 从 tag 解出 Y（-100..100）。 */
    public static int stickYOf(int tag) {
        return (tag & 0xFF) - 100;
    }

    /**
     * 8 个方向单位向量（x, y 成对）。斜向已归一化，
     * 否则斜着推会比正上下左右"超速"（合成长度 1.41）。
     *
     * 顺序和 Android 的 hat 值一致：0=上 1=右上 2=右 3=右下
     * 4=下 5=左下 6=左 7=左上。
     */
    public static final float[] DIR_X = {0f, 0.7071f, 1f, 0.7071f, 0f, -0.7071f, -1f, -0.7071f};
    public static final float[] DIR_Y = {-1f, -0.7071f, 0f, 0.7071f, 1f, 0.7071f, 0f, -0.7071f};

    /**
     * 动作类型：延迟。
     *
     * 和键（1 手柄 / 2 键盘）并列，编码位存的是**毫秒数**。
     * 执行时它不发任何键，只让后面的动作晚一点发。
     */
    public static final int ACT_DELAY = 3;

    /** 延迟可选档位（毫秒）。点延迟行从这些里挑。 */
    public static final int[] DELAY_PRESETS = {
            30, 50, 80, 100, 150, 200, 300, 500, 800, 1000, 1500, 2000
    };

    /**
     * 悬浮球（G）自己的槽位。
     *
     * 【为什么必须单独一个槽位，而不是复用 I_COLLAPSE】
     *   复用就是"「收」在悬浮窗布局里改个名字换个色"，于是：
     *     - 点全部重置会把它打回「收」（reset 按模板重建界面按钮），
     *       用户改的蓝底白字 G 全没了 —— 这就是之前"原形毕露"的原因
     *     - G 和「收」共用一套 scale / 颜色 / 名字，改一个另一个跟着变，
     *       而它们本该是不同的东西
     *   独立槽位之后 G 有自己的外观存档，重置也只会重置回"默认的 G"。
     *
     * 【为什么又放末尾】和空白 / 组合键同理：摆位存档用下标当 key，
     *   放末尾则 0..COMBO_START+MAX_COMBO-1 一个不动，老存档直接兼容。
     */
    public static final int I_FLOAT = COMBO_START + MAX_COMBO;

    /*
      鼠标布局的三个元素。同样放末尾，理由同上：
      摆位存档用下标当 key，放末尾则 0..I_FLOAT 一个不动，老存档直接兼容。

      它们不是"手柄按键" —— I_MOUSE_PAD 是拖动区（发相对位移），
      左右键发的是鼠标设备的按键位，都不走 GamepadReport。
      所以不放进 N_FIXED，也不进副本区，单独占末尾三个槽位。
    */
    public static final int I_MOUSE_PAD = I_FLOAT + 1;

    /**
     * 触摸板默认透明度。
     *
     * 触摸板是块大面积矩形，铺在下半屏会挡住游戏画面，
     * 所以它比别的按键默认淡一半 —— 看得见边界、又不挡视线。
     * 想更淡/更实去「选中 → 属性 → 透明度」调，跟别的一样。
     */
    public static final float MOUSE_PAD_ALPHA = 0.5f;
    public static final int I_MOUSE_L = I_FLOAT + 2;
    public static final int I_MOUSE_R = I_FLOAT + 3;
    public static final int I_MOUSE_M = I_FLOAT + 4;
    public static final int I_MOUSE_WU = I_FLOAT + 5;
    public static final int I_MOUSE_WD = I_FLOAT + 6;

    /** 数组总长：... + G + 鼠标六个 */
    public static final int N = I_MOUSE_WD + 1;

    /** 鼠标元素槽位：触摸板 / 左键 / 右键 / 中键 / 滚轮上 / 滚轮下 */
    public static boolean isMouseSlot(int i) {
        return i >= I_MOUSE_PAD && i <= I_MOUSE_WD;
    }

    /** 鼠标元素的默认名字，创建 / 删除时用。 */
    public static String mouseNameOf(int i) {
        if (i == I_MOUSE_PAD) return "触摸板";
        if (i == I_MOUSE_L) return "左键";
        if (i == I_MOUSE_R) return "右键";
        if (i == I_MOUSE_M) return "中键";
        if (i == I_MOUSE_WU) return "滚轮上";
        if (i == I_MOUSE_WD) return "滚轮下";
        return "";
    }

    /** 这个鼠标槽位是不是空的（可以往里建一个）。 */
    public int allocMouseSlot() {
        for (int i = I_MOUSE_PAD; i <= I_MOUSE_WD; i++) {
            if (padType[i] == 0) return i;
        }
        return -1;
    }

    /** 鼠标元素还剩几个空位。 */
    public int mouseFreeCount() {
        int n = 0;
        for (int i = I_MOUSE_PAD; i <= I_MOUSE_WD; i++) {
            if (padType[i] == 0) n++;
        }
        return n;
    }

    /** 这个鼠标元素当前是否启用（模板决定，不可增删）。 */
    public boolean isMouseUsed(int i) {
        return isMouseSlot(i) && padType[i] != 0;
    }

    // ---- 存档迁移（下标位移）----
    //
    // 摆位存档是用**下标当 key** 存的（"3" = A 键的位置），而下标同时
    // 又是元素身份 —— 两者绑死。所以往数组**中间**插一个新元素时，
    // 老存档里 "3" 之后的所有 key 全都错位一格：按键乱跑、建过的副键消失。
    //
    // 这个机制就是为了解决"想插在中间"的情况：
    //   老存档（没有 sch 字段 / sch=0）里，把 [MIG_FROM, MIG_TO] 这段
    //   整体往后挪 MIG_SHIFT 位，其余不动。挪完写回 sch = SCHEMA。
    //
    // 【当前配置为什么是 -1】
    //   这次的 I_PASS 放在末尾，老下标一个都没动，不需要迁移。
    //   留着这套代码是为了下次：真要在中间插元素时，
    //   把这三个常量填成实际区间即可，不用再临时想办法。
    private static final int MIG_FROM = -1;
    private static final int MIG_TO = -1;
    private static final int MIG_SHIFT = 1;
    /** 存档结构版本。老存档没有这个字段（读到 0），据此判断要不要迁移。 */
    private static final int SCHEMA = 1;

    public static final int I_LS = 0;
    public static final int I_RS = 1;
    public static final int I_DPAD = 2;
    public static final int I_A = 3;
    public static final int I_B = 4;
    public static final int I_Y = 5;
    public static final int I_X = 6;
    public static final int I_L1 = 7;
    public static final int I_R1 = 8;
    public static final int I_L2 = 9;
    public static final int I_R2 = 10;
    public static final int I_SEL = 11;
    public static final int I_STA = 12;
    /** 「收」按钮：收起手柄，位置与悬浮球共享 */
    public static final int I_COLLAPSE = 13;
    /** 「编」按钮：进入布局编辑模式 */
    public static final int I_EDIT = 14;
    /** L3：左摇杆按下（HID BTN_THUMBL） */
    public static final int I_L3 = 15;
    /** R3：右摇杆按下（HID BTN_THUMBR） */
    public static final int I_R3 = 16;
    /**
     * L2 / R2 的"无轨道版"：就是普通的圆按钮，按下 = 满值，松开 = 0。
     *
     * 和 I_L2 / I_R2（带轨道的扳机）是**两个独立的按钮**，各自有位置、
     * 大小、透明度、隐藏状态，互不干扰。默认隐藏，
     * 想要"点一下就扣到底"的手感就去隐藏列表里把它放出来（同时把带轨道的藏了）。
     */
    public static final int I_L2B = 17;
    public static final int I_R2B = 18;
    /**
     * HOME（手柄上的「导航键」/ PS 键 / Xbox 键那个位置）。
     *
     * 发的是 HID button 13 -> KEYCODE_BUTTON_MODE，也就是标准手柄的 MODE 键。
     * 大部分游戏不认它（少数用来唤出菜单），所以默认隐藏，
     * 需要的时候去编辑模式的「隐藏按钮」里放出来。
     */
    public static final int I_HOME = 19;
    public static final int I_Z = 20;
    public static final int I_C = 21;

    /**
     * 缩放区间。
     *
     * 【下限 0.5 -> 0.15】键盘模式下整片键按"最密行能放下"反推，
     * 竖屏算出来只有 0.295 —— 比旧下限 0.5 还小。
     * 于是 sliderValue 返回负数被夹成 0，滑条显示 0%；
     * 而 clampScale 在存档读取时又把它夹回 0.5，
     * 键一"刷新页面"就凭空大了 69%（0.295 -> 0.5），看着像被改过。
     */
    /**
     * 键盘矩形键的默认半宽 / 半高倍率。
     * 1.19 是"键宽 84% 反推"的值：键刚好填满格子。
     * 存档缺字段时回退到这两个值，和 resetKeyboard() 生成的一致 ——
     * 否则 load 路径下键会从 1.19 塌成 1f，键变小、**缝隙变大**。
     */
    public static final float DEF_RECT_W = 1.19f;
    public static final float DEF_RECT_H = 1.06f;

    public static final float MIN_SCALE = 0.15f;
    public static final float MAX_SCALE = 2.2f;
    /**
     * 透明度滑条能拖到的最低值 = 25/255。
     *
     * 原来是 0.15（≈38/255）。用户要的是"最低 25"，
     * 也就是 25 分制下正好落在整数 25 上 —— 按 255 制看刻度才对得齐。
     */
    public static final float MIN_ALPHA = 25f / 255f;

    /**
     * 默认布局的版本号。改动 reset() 里的默认摆放后 +1，
     * 老存档会和它对不上，从而自动用新默认布局，
     * 用户不用手动进编辑模式点「重置」。
     *   2 = 左右摇杆位置对调
     *   3 = 整改为分区布局（左半一列 / 右半一列），并按可用空间自适应
     *   4 = 十字节/左摇杆/右摇杆三处轮换，L/R 1/2 下移，编与收挂到 L1/R1 下，
     *        顶部加保护区
     *   5 = 新增 L3 / R3 两个键；底部不再受编辑面板限制，整体下移
     *   6 = 横屏 L3/R3 归位到 L2/R2 那一排，SEL/STA 挪到底部中间（竖屏没动）
     *   7 = 横屏主操作区改成"左右两列、每列上下叠放"，和竖屏一致
     *   8 = 横屏左列（十字键 + 左摇杆）从 0.24w 往左挪到 0.20w
     *   9 = L2/R2 改成竖直扳机滑轨（高 = 3 个按钮半径），
     *        横屏肩键排要下移才装得下（竖屏算出来位置没变，所以竖屏仍是 5）
     *   10 = 默认布局的 B / X 对调位置（B 到右、X 到左）。
     *        横竖都要改，所以两边一起升到 10。
     *
     * 横竖各用一个版本号：这样改横屏默认布局时，
     * 只作废横屏那份存档，你手动调好的竖屏位置不会被连带清掉。
     */
    // 11 = 竖屏右摇杆 0.68w -> 0.72w（往右挪）。
    //      必须升版本号：你已经存过竖屏布局，不升的话 load() 会拿存档里
    //      的旧 x 盖回来，这次挪动就完全看不出效果。
    //      代价是竖屏手调过的位置会回到默认 —— 横屏那份不受影响（还是 10）。
    // N 少了 1（I_MODE 删除、I_LAYOUT 挪到 N_KEY_END），
    // 存档里元素下标的意义变了 —— 不升版本的话，
    // 「布」会读到原「模式」的坐标、「模式」的老数据又没人认领。
    private static final int LAYOUT_VERSION_PORTRAIT = 14;
    private static final int LAYOUT_VERSION_LANDSCAPE = 13;

    /**
     * 存档分四种：竖屏手柄 / 横屏手柄 / 竖屏键盘 / 横屏键盘。
     *
     * 手柄那两个沿用老 key（"portrait" / "landscape"）—— 老存档直接兼容。
     * 键盘那两个是新 key，第一次切过去没有存档，走全键盘默认布局。
     */
    private static final String KEY_KP = "keyboard_portrait";
    private static final String KEY_KL = "keyboard_landscape";

    /**
     * 键盘布局的版本号。默认布局改了就 +1，让老存档失效重新生成。
     *
     * v3 -> v4：21 格三段布局（修饰键按权重排、小键盘补进右侧空位）、
     *            行距 2.2 -> 1.3 倍、字号按名字长短自适应
     * v4 -> v5：键之间留缝（默认宽倍率 1.19 -> 竖屏 1.00 / 横屏 1.10），
     *            倍率区间放宽 0.3~8 -> 0.08~10 并在载入时钳制
     * v2 -> v3：82 键布局（加 F 区 + 导航列）、竖屏拉开行距、
     *            键盘键默认字号调小、「编/收」不再被误隐藏
     * v1 -> v2：修了"固定手柄元素在键盘模式下全堆在左上角"的 bug
     * （reset 提前 return，0..21 那批没赋值 rx/ry）。
     * 上一版存下的键盘布局里那批键的坐标是 (0,0)，必须让它失效重生成。
     */
    private static final int LAYOUT_VERSION_KEY_P = 8;
    private static final int LAYOUT_VERSION_KEY_L = 8;

    /**
     * 「默认鼠标」布局自己的版本号。
     *
     * 【为什么要单独开一个】
     *   LAYOUT_DEFAULT_MOUSE = 30，而 LAYOUT_USER_START = 2 ——
     *   30 >= 2，于是 versionFor() 把它判成"用户布局"，
     *   返回 USER_LAYOUT_VERSION，而那个常量是**永不递增**的。
     *
     *   后果很隐蔽：改了 resetMouse()（中键竖排 / 触摸板形状 /
     *   透明度 50%），代码里全都对，但用户第一次进「默认鼠标」存下的
     *   那份存档版本号永远对得上，读的一直是旧布局 ——
     *   界面上看到的还是改之前的形状（形状栏里就是那个 长3.87 宽2.30）。
     *
     *   现在鼠标布局走自己的版本号，以后改 resetMouse 就 +1。
     */
    private static final int LAYOUT_VERSION_MOUSE_P = 6;
    private static final int LAYOUT_VERSION_MOUSE_L = 6;

    /** 当前是键盘模式（true）还是手柄模式（false）。 */
    public boolean keyboardMode;

    /** 形状：0 = 圆（手柄键），1 = 圆角矩形（键盘键）。 */
    public static final int SHAPE_CIRCLE = 0;
    public static final int SHAPE_RECT = 1;

    /**
     * 每个按钮标签的字号倍率。默认 1f。
     *
     * 键盘模式下整片键被缩得很小（scale 0.3 左右），
     * 但字号原来是按**屏幕基准半径**算的、不跟 scale 走，
     * 于是 50px 的键上顶着 60px 的字，直接溢出。
     * 有了这个就能单独把字调小，不用把键也一起缩。
     */
    public final float[] textScale = new float[N];

    /** 字号倍率的允许区间。 */
    public static final float MIN_TEXT_SCALE = 0.3f;
    public static final float MAX_TEXT_SCALE = 1.8f;

    /**
     * 矩形键的半宽 = 半径 × 这个值。默认 1.10f。
     *
     * 空格键这类"占好几个格子"的宽键，光调 scale 是做不出来的 ——
     * scale 会把高度一起放大，键就变成一个大方块。
     * 所以宽度单独存一个倍率，高度不受影响。
     */
    public final float[] widthMul = new float[N];

    /**
     * 矩形键的半高 = 半径 × 这个值。默认 1.06f。
     * 和 widthMul 分开存，才能做出"扁的""竖长的"键。
     */
    public final float[] heightMul = new float[N];

    /**
     * 宽 / 高倍率的允许区间。
     *
     * 下限原来 0.3，"拖到 0% 还是不够小"；而且只要实际值落到区间外，
     * sliderValue 就会算出负数、被 clamp 成 0 —— 滑条显示 0%，
     * 但键还是原来那个大小，看着就像"没生效"。
     * 下限放到 0.08：既能真的缩得很小，也让区间覆盖住几乎所有实际值。
     *
     * 上限 10：空格键按权重算是 6 格 × 1.00 ≈ 6，横屏 6.6，留足余量。
     */
    // ------------------------------------------------------------------
    // 摇杆：固定 / 浮动
    // ------------------------------------------------------------------

    /**
     * 摇杆是否"固定"在原地。
     *
     * 固定（默认）：摇杆待在摆好的位置不动，手指在任何地方按下都推它 ——
     * 这是现在的样子，也是大多数手柄 app 的默认。
     *
     * 浮动（关掉）：手指落在摇杆的范围圈内按下时，摇杆**瞬移到手指位置**，
     * 然后跟着手指走。抬手就留在那儿，下次再从新位置开始。
     */
    public final boolean[] stickFixed = new boolean[N];

    /**
     * 浮动摇杆的"激活范围"，单位是摇杆半径的倍数。
     *
     * 按下点落在这个圈内才触发瞬移；圈外按下不响应 ——
     * 于是这个圈就是摇杆的"热区"，调大 = 好按但容易误触，
     * 调小 = 精准但得按准。
     */
    public final float[] stickRange = new float[N];

    /** 范围倍率的区间：0.5 = 半径的一半，3 = 三倍半径（大半个屏幕） */
    public static final float MIN_STICK_RANGE = 0.5f;
    public static final float MAX_STICK_RANGE = 3f;
    /** 默认 1.5：比摇杆本身大一圈，拇指摸过去不用太准。 */
    public static final float DEF_STICK_RANGE = 1.5f;

    public static float clampStickRange(float v) {
        if (v < MIN_STICK_RANGE) return MIN_STICK_RANGE;
        if (v > MAX_STICK_RANGE) return MAX_STICK_RANGE;
        return v;
    }

    /** 这两个下标是摇杆（左 / 右）。加新摇杆时补这里。 */
    public static boolean isStick(int i) {
        return i == I_LS || i == I_RS;
    }

        /**
     * 【默认鼠标】触摸板的形状倍率。
     *
     * 「形状」面板里那个「长」就是 widthMul、「宽」就是 heightMul，
     * 所以这两个值直接决定了形状栏里显示的数字：长3.87 宽2.30。
     *
     * 比例 3.87 : 2.30 ≈ 1.68 : 1 —— 比 2:1 稍"矮胖"一点，
     * 竖屏下半屏摆着刚好，不会顶到左右键那一排。
     */
    public static final float MOUSE_PAD_W_MUL = 3.87f;
    public static final float MOUSE_PAD_H_MUL = 2.30f;

    public static final float MIN_SIZE_MUL = 0.08f;
    public static final float MAX_SIZE_MUL = 10f;

    /** 把倍率夹回区间。load 时无条件调用，保证滑条永远读得到合法值。 */
    public static float clampMul(float v) {
        if (v < MIN_SIZE_MUL) return MIN_SIZE_MUL;
        if (v > MAX_SIZE_MUL) return MAX_SIZE_MUL;
        return v;
    }

    // ---- 倍率 <-> 滑条位置：用对数映射，不能用线性 ----
    //
    // 线性的问题：区间 0.08~10 里，常用值 1.0 只落在 9%，
    // 1~7 全挤在最左边那一小段，手指稍微动一下就跳很多，根本调不准。
    //
    // 倍率本质上是"比例量"（1x 是基准，2x 是两倍，0.5x 是一半），
    // 用对数才符合直觉：1x 落在中间，往左是指数级变小，往右指数级变大。
    private static final double MUL_LOG_RANGE = Math.log(MAX_SIZE_MUL / MIN_SIZE_MUL);

    private static final double SCALE_LOG_RANGE = Math.log(MAX_SCALE / MIN_SCALE);

    /**
     * scale 也用对数映射（和宽高倍率一致）。
     *
     * 线性时 0.295 在 0.15~2.2 区间里只占 7%，滑块几乎顶着最左端，
     * 手指稍微一动就跳到很大的值 —— 键"唰"地涨一圈。
     * 对数后 0.295 落在 25% 左右，1.0 落在 70%，小尺寸那段的分辨率高得多。
     */
    public static float scaleToSlider(float v) {
        if (v < MIN_SCALE) v = MIN_SCALE;
        if (v > MAX_SCALE) v = MAX_SCALE;
        return (float) (Math.log(v / MIN_SCALE) / SCALE_LOG_RANGE);
    }

    public static float sliderToScale(float t) {
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;
        return clampScale((float) (MIN_SCALE * Math.exp(t * SCALE_LOG_RANGE)));
    }

    /** 倍率 -> 滑条位置（0~1） */
    public static float mulToSlider(float v) {
        v = clampMul(v);
        return (float) (Math.log(v / MIN_SIZE_MUL) / MUL_LOG_RANGE);
    }

    /** 滑条位置（0~1） -> 倍率 */
    public static float sliderToMul(float t) {
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;
        return clampMul((float) (MIN_SIZE_MUL * Math.exp(t * MUL_LOG_RANGE)));
    }

    /**
     * 每个按钮的形状。默认 SHAPE_CIRCLE。
     *
     * 键盘键用矩形有两个好处：
     *   1) 圆内切于格子、四角留白，一整片键看着散；矩形把格子填满，像"一块键盘"
     *   2) 真实键盘键就是方的
     */
    public final int[] shape = new int[N];

    // ==================================================================
    // 多布局
    //
    // 原来只有 4 个固定变体（竖/横 × 手柄/键盘），用户没法"存好几套、随时换"。
    // 现在每个布局有 id + 名字 + 模板类型，竖屏 / 横屏各存一份实际摆位。
    //
    // id 0 / 1 保留给内置的「默认手柄」「默认键盘」，且复用原来的存档 key
    // （KEY_P / KEY_L / KEY_KP / KEY_KL）—— 这样老存档一个字节都不用迁移，
    // 升级后用户原来摆好的位置还在。
    // ==================================================================
    public static final int LAYOUT_DEFAULT_PAD = 0;
    public static final int LAYOUT_DEFAULT_KB = 1;
    public static final int LAYOUT_USER_START = 2;
    /**
     * 「悬浮窗」布局：专门调悬浮球（G）样式的内置布局。
     *
     * 【为什么取 31 而不是 2】
     *   id 必须 < LAYOUT_USER_START 才算内置，但 2 已经被"第一个用户布局"
     *   占了几百个版本 —— 挪 LAYOUT_USER_START 会让老用户的 id=2 布局
     *   被当成悬浮窗布局，存档直接串味。
     *   取一个大号（远在用户布局号段之外）就没这个冲突：
     *   用户布局从 2 递增，nextLayoutId 会跳过这一号。
     */
    public static final int LAYOUT_FLOAT = 31;
    /**
     * 「默认鼠标」布局：触摸板 + 左键 + 右键。
     *
     * 取 30 而不是 3：用户布局从 2 递增，取 3 迟早会撞上，
     * 取一个远在用户号段之外的大号就没这个冲突（nextLayoutId 会跳过内置号）。
     */
    public static final int LAYOUT_DEFAULT_MOUSE = 30;
    public static final int LAYOUT_MAX = 40;

    /** 新建布局时的三种模板。 */
    public static final int TPL_BLANK = 0;      // 空白：只有界面按钮，没有游戏键
    public static final int TPL_PAD = 1;        // 手柄模板 = 默认手柄那套
    public static final int TPL_KEYBOARD = 2;   // 键盘模板 = 全键盘
    public static final int TPL_MOUSE = 3;      // 鼠标模板 = 触摸板 + 左键 + 右键

    /**
     * 用户布局的版本号。
     *
     * 内置布局走 LAYOUT_VERSION_* —— 默认布局一改，老存档作废重新生成。
     * 但**用户自己摆的布局不能被版本号冲掉**，否则每次升级 app，
     * 用户辛苦摆的自定义布局全没了。所以给个独立的、永不递增的常量。
     */
    private static final int USER_LAYOUT_VERSION = 4;

    /** 当前布局 id。0 = 默认手柄，1 = 默认键盘，>=2 = 用户新建。 */
    public int layoutId = LAYOUT_DEFAULT_PAD;
    public String layoutName = "";

    /**
     * 横竖屏同步：把**另一个方向**的存档按勾选的按钮和属性复制到当前方向。
     *
     * 【为什么不在 JSON 层整份搬】
     *   用户要的是"只同步勾的那几个按钮、只同步勾的那几项属性"，
     *   整份搬会把没勾选的按钮也改掉。所以逐个下标、逐个字段地写。
     *
     * 【位置为什么不复制】
     *   横竖屏比例不同，位置搬过去按钮会跑到屏幕外。
     *   但如果目标方向**压根没有这个按钮的记录**（那个方向还没建过），
     *   又完全不给位置，rx/ry 会留下 0 —— 按钮飞到屏幕左上角。
     *   所以这种"目标还没有"的情况沿用源方向的位置当落点，
     *   比落在 (0,0) 好找得多。
     *
     * @param srcPortrait **源**方向：true = 竖屏那份是源
     * @param sel      哪些按钮要同步，下标 = 元素下标
     * @param dims     哪些属性要复制，见 GamepadView 的 SD_* 常量
     * @return 实际同步了几个按钮（0 = 源方向没有存档 / 没勾任何按钮）
     *
     * 【参数必须直接给"源"，不能给"目标"再反推】
     *   原来签名是 portrait = 目标、内部 srcPortrait = !portrait。
     *   调用方手上拿的是"源是不是竖屏"，传进去就被当成了目标 ——
     *   于是选"从竖屏复制到横屏"实际写成横屏→竖屏，被改的是竖屏。
     *   两个方向的名字只差一个字，反推一次就反了，所以这里取消反推。
     */
    public static int syncFromOtherOrient(Context ctx, int layoutId, boolean keyboard,
                                          boolean srcPortrait, boolean[] sel, boolean[] dims) {
        if (ctx == null || sel == null || dims == null) {
            return 0;
        }
        boolean dstPortrait = !srcPortrait;
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String src = p.getString(variantKey(srcPortrait, layoutId, keyboard), null);
            if (src == null) {
                return 0;
            }
            org.json.JSONObject so = new org.json.JSONObject(src);
            String dstKey = variantKey(dstPortrait, layoutId, keyboard);
            String dst = p.getString(dstKey, null);
            org.json.JSONObject d = (dst == null)
                    ? new org.json.JSONObject() : new org.json.JSONObject(dst);
            int n = 0;
            for (int i = 0; i < N; i++) {
                if (!sel[i]) {
                    continue;
                }
                org.json.JSONObject se = so.optJSONObject(String.valueOf(i));
                if (se == null) {
                    continue;    // 源方向没有这个按钮，没什么可复制的
                }
                org.json.JSONObject de = d.optJSONObject(String.valueOf(i));
                boolean fresh = de == null;
                if (de == null) {
                    de = new org.json.JSONObject();
                    d.put(String.valueOf(i), de);
                }
                if (dims.length > 0 && dims[0]) {          // 大小
                    de.put("s", se.optDouble("s", 1f));
                }
                if (dims.length > 1 && dims[1]) {          // 透明度
                    de.put("a", se.optDouble("a", 1f));
                }
                if (dims.length > 2 && dims[2]) {          // 字体
                    de.put("ts", se.optDouble("ts", 1f));
                }
                //
                // 【身份字段必须跟着复制】
                //   pt / k / bl / cn / ca 决定"这个槽位上是什么按钮"。
                //   只复制大小/透明/形状的话，目标方向那个槽位还是空槽位 ——
                //   用户建的键盘键、空白、组合键根本不会出现，
                //   看着就像"同步了但没复制过来"。
                //   身份是"让按钮存在"的前提，不归勾选的属性管。
                de.put("pt", se.optInt("pt", 0));
                de.put("k", se.optInt("k", 0));
                if (se.has("bl")) {
                    de.put("bl", se.optBoolean("bl", false));
                }
                if (se.has("cn")) {
                    de.put("cn", se.optString("cn", ""));
                }
                if (se.has("ca")) {
                    de.put("ca", se.optString("ca", ""));
                }
                if (dims.length > 3 && dims[3]) {          // 其他信息
                    de.put("sp", se.optInt("sp", SHAPE_CIRCLE));
                    de.put("h", se.optBoolean("h", false));
                    de.put("sf", se.optBoolean("sf", true));
                    de.put("sr", se.optDouble("sr", DEF_STICK_RANGE));
                    de.put("wm", se.optDouble("wm", 1f));
                    de.put("hm", se.optDouble("hm", 1f));
                }
                // 目标方向第一次出现这个按钮：沿用源方向的位置当落点，
                // 免得 rx/ry 留 0 飞到左上角
                if (fresh && se.has("x") && se.has("y")) {
                    de.put("x", se.optDouble("x"));
                    de.put("y", se.optDouble("y"));
                }
                n++;
            }
            if (n > 0) {
                p.edit().putString(dstKey, d.toString()).apply();
            }
            return n;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String variantKey(boolean portrait, boolean keyboard) {
        return variantKey(portrait, LAYOUT_DEFAULT_PAD, keyboard);
    }

    private static String variantKey(boolean portrait, int layoutId, boolean keyboard) {
        // 内置两个沿用老 key，保证老存档兼容
        if (layoutId == LAYOUT_DEFAULT_PAD) {
            return portrait ? KEY_P : KEY_L;
        }
        if (layoutId == LAYOUT_DEFAULT_KB) {
            return portrait ? KEY_KP : KEY_KL;
        }
        return "L" + layoutId + (portrait ? "_P" : "_L");
    }

    private static int versionFor(boolean portrait, boolean keyboard) {
        return versionFor(portrait, LAYOUT_DEFAULT_PAD, keyboard);
    }

    private static int versionFor(boolean portrait, int layoutId, boolean keyboard) {
        // 鼠标布局必须排在"用户布局"判断**前面**：
        // 它的 id=30 也 >= LAYOUT_USER_START，会被误判成用户布局，
        // 从而拿到永不递增的版本号，改了默认布局也不生效。
        if (layoutId == LAYOUT_DEFAULT_MOUSE) {
            return portrait ? LAYOUT_VERSION_MOUSE_P : LAYOUT_VERSION_MOUSE_L;
        }
        if (layoutId >= LAYOUT_USER_START) {
            return USER_LAYOUT_VERSION;
        }
        if (keyboard) {
            return portrait ? LAYOUT_VERSION_KEY_P : LAYOUT_VERSION_KEY_L;
        }
        return portrait ? LAYOUT_VERSION_PORTRAIT : LAYOUT_VERSION_LANDSCAPE;
    }

    private static int versionFor(boolean portrait) {
        return versionFor(portrait, false);
    }

    /**
     * 无轨道版 L2 / R2 在**旧版**（还没有滑轨那个年代）的位置，比例坐标。
     * [0] = L2B，[1] = R2B。
     *
     * 默认摆放把它们挂在肩键列第 4 行，是为了不和带轨道版叠在一起；
     * 但老用户可能就想让它们待在原来那一格，所以留了这份坐标，
     * 编辑模式的「回退旧版位置」直接取来用。
     */
    private final float[] mLegacyRx = new float[2];
    private final float[] mLegacyRy = new float[2];

    /** 取无轨道版的旧版位置（比例坐标）。idx: 0 = L2B，1 = R2B。 */
    public float legacyRx(int idx) {
        return mLegacyRx[idx];
    }

    public float legacyRy(int idx) {
        return mLegacyRy[idx];
    }

    public static final String[] NAMES = {
            "左摇杆", "右摇杆", "方向键",
            "A", "B", "Y", "X",
            "L1", "R1", "L2(轨)", "R2(轨)",
            "SELECT", "START",
            "收起", "编辑",
            "L3", "R3",
            "L2(键)", "R2(键)",
            "HOME",
            "Z", "C"
    };

    // ------------------------------------------------------------------
    // 动态键盘按键
    // ------------------------------------------------------------------

    /**
     * 可选的键盘按键：**HID keyboard usage**，不是 Android keycode。
     *
     * 为什么用 usage 而不是 KEYCODE_*：守护进程要把 usage 填进 boot 协议的
     * 报告里的 bitmap 位，Linux hid-input 再按 hid_keyboard[] 表
     * 映射成 Linux keycode。
     * 直接传 usage 就不需要在守护进程里维护"Android keycode -> HID usage"
     * 的反查表，少一层出错的地方。
     *
     * 32 项是刻意的：编辑模式的按键列表没有滚动，项越多行高越矮，
     * 再往上加会点不准（见 MAX_KEYS 的注释）。
     */
        /**
     * 键表顺序：**字母 A-Z 在最前面**（游戏最常用），其后按类别分组：
     * 常用控制键 -> 方向键 -> 数字 -> 符号 -> F1~F12 -> 编辑/导航
     * -> 修饰键 -> 小键盘。
     *
     * 分隔符用 | 而不是逗号：键名里**真的有逗号**这个符号键，
     * 用逗号会被当成分隔符拆开。
     */
    public static final String KEY_NAMES_CSV = "A|B|C|D|E|F|G|H|I|J|K|L|M|N|O|P|Q|R|S|T|U|V|W|X|Y|Z|空格|Shift|Ctrl|Alt|Esc|Tab|回车|退格|↑|↓|←|→|1|2|3|4|5|6|7|8|9|0|-|=|[|]|\\|;|'|`|,|.|/|F1|F2|F3|F4|F5|F6|F7|F8|F9|F10|F11|F12|Insert|Home|PgUp|Delete|End|PgDn|PrtSc|ScrLk|Pause|Caps|RShift|RCtrl|RAlt|Win|RWin|Num0|Num1|Num2|Num3|Num4|Num5|Num6|Num7|Num8|Num9|Num.|Num/|Num*|Num-|Num+|NumEnt|NumLk|Menu";

    public static final String KEY_USAGES_CSV = "04|05|06|07|08|09|0A|0B|0C|0D|0E|0F|10|11|12|13|14|15|16|17|18|19|1A|1B|1C|1D|2C|E1|E0|E2|29|2B|28|2A|52|51|50|4F|1E|1F|20|21|22|23|24|25|26|27|2D|2E|2F|30|31|33|34|35|36|37|38|3A|3B|3C|3D|3E|3F|40|41|42|43|44|45|49|4A|4B|4C|4D|4E|46|47|48|39|E5|E4|E6|E3|E7|62|59|5A|5B|5C|5D|5E|5F|60|61|63|54|55|56|57|58|53|65";

    private static String[] parseNames() {
        return KEY_NAMES_CSV.split("\\|", -1);
    }

    private static int[] parseUsages() {
        String[] parts = KEY_USAGES_CSV.split("\\|", -1);
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Integer.parseInt(parts[i].trim(), 16);
        }
        return out;
    }

    public static final String[] KEY_NAMES = parseNames();
    public static final int[] KEY_USAGES = parseUsages();


    /** 修饰键的 usage 下限：0xE0=LCtrl 0xE1=LShift 0xE2=LAlt ... */
    private static final int MOD_MIN = 0xE0;

    /**
     * 每个元素对应的 HID keyboard usage，**0 表示这个槽位没被使用**。
     *
     * 只有动态键盘按键（下标 >= I_KEY0）会用到，固定元素恒为 0。
     * 它是"这个按钮存不存在"的唯一依据 —— 不能拿 hidden 代替：
     * hidden 是"存在但游戏里不显示"，而这里是"压根没创建"。
     */
    public final int[] keyCode = new int[N];

    /**
     * 每个下标的**手柄元素类型**。0 = 空槽位，否则 = 原型下标 + 1。
     *
     * 例：I_A = 3，所以一个 A 键（不管它在 3 号位还是副本槽位）padType = 4。
     *
     * 有了它，"下标"和"是什么键"就解耦了：
     *   - 原来：下标 3 就是 A 键，删了就没了，也建不出第二个
     *   - 现在：下标只是位置，padType 决定它是什么键、怎么画、发什么 HID
     *
     * 固定区 0..N_FIXED-1 初始 = 自身（i+1），副本槽位初始 = 0。
     * 老存档没有这个字段，load 时按同样规则回填，见 load()。
     */
    public final int[] padType = new int[N];

    /** 原型下标 -> padType。 */
    public static int typeOfElem(int proto) {
        return proto + 1;
    }

    /** padType -> 原型下标。 */
    public static int protoOfType(int type) {
        return type - 1;
    }

    /**
     * 这个下标是不是"手柄元素槽位"（固定区 或 副本区）。
     *
     * 【副本区上界必须是 BLANK_START，不能写 N】
     *   空白按钮槽位（166..181）排在副本区之后，它们的 padType 恒为 0。
     *   写成 i < N 的话，空白按钮也会被判成"手柄槽位"，
     *   而 isPadUsed = padType != 0 = false，
     *   于是 shouldDraw / pickable 把它当"空手柄槽位"直接 return false：
     *   按钮本体不画、也点不中，只有选中框照画 ——
     *   屏幕上就是一个"有框、没实体、点不动"的空气。
     *   （I_PASS = BLANK_START - 1，写成 i <= I_PASS 也等价。）
     */
    public static boolean isPadSlot(int i) {
        return (i >= 0 && i < N_FIXED)
                || (i >= PAD_EXTRA_START && i < BLANK_START);
    }

    /** 这个槽位是否已经放了一个手柄元素。 */
    public boolean isPadUsed(int i) {
        return isPadSlot(i) && padType[i] != 0;
    }

    /** 界面按钮（编 / 收 / 布）：不是手柄元素，不能删也不能建。 */
    public static boolean isUiButton(int i) {
        return i == I_EDIT || i == I_COLLAPSE || i == I_LAYOUT || i == I_PASS
                || i == I_FLOAT;
    }

    /** usage -> 在 KEY_NAMES 里的下标，找不到返回 -1。 */
    public static int keyIndexOf(int usage) {
        for (int i = 0; i < KEY_USAGES.length; i++) {
            if (KEY_USAGES[i] == usage) {
                return i;
            }
        }
        return -1;
    }

    /** usage 对应的显示名，查不到就显示十六进制，不至于空白。 */
    public static String keyName(int usage) {
        int i = keyIndexOf(usage);
        return i >= 0 ? KEY_NAMES[i] : ("0x" + Integer.toHexString(usage).toUpperCase());
    }

    /**
     * 按键名 -> HID usage。找不到返回 -1。
     *
     * 生成全键盘时用：布局表里写的是"Q""空格"这种名字，
     * 存进 keyCode 的必须是 usage。
     */
    public static int usageOfName(String name) {
        for (int i = 0; i < KEY_NAMES.length; i++) {
            if (KEY_NAMES[i].equals(name)) {
                return KEY_USAGES[i];
            }
        }
        return -1;
    }

    /** 是否是修饰键（走 bitmap 而不是 6 键数组）。 */
    public static boolean isModifier(int usage) {
        return usage >= MOD_MIN;
    }

    /**
     * 元素的显示名。
     *
     * 键盘按键的名字取决于它被创建时选了哪个键，
     * 不像固定元素那样能写死在 NAMES 里，所以这里判断一下。
     */
    /** 键盘按键在列表里显示时加的后缀。 */
    public static final String KEY_SUFFIX = "[键盘]";

    /** 这个下标是不是"键盘槽位"（区别于固定元素和模式按钮）。 */
    public static boolean isKeySlot(int i) {
        return i >= I_KEY0 && i < N_KEY_END;
    }

    public String nameOf(int i) {
        // 列表里写全称「布局」。
        //
        // 画布上那个按钮写的是「布」（drawUiButtons 里写死的单字），
        // 因为圆按钮直径有限，两个字以上的中文会顶出键外。
        // 但列表里空间充裕，写「布」根本看不出是什么 ——
        // 它指的是"切换布局"，不是"布匹"。
        if (i == I_LAYOUT) {
            return "布局";
        }
        // 列表里写全称「穿透」。画布上画的是「透√ / 透×」两个字符。
        if (i == I_PASS) {
            return "穿透";
        }
        // G：默认就叫「G」，改名后显示改的名字（改名写的是 customName）。
        if (i == I_FLOAT) {
            String cn = (customName != null && i < customName.length
                    && customName[i] != null) ? customName[i].trim() : "";
            return cn.length() > 0 ? cn : "G";
        }

        // 手柄元素：按 padType 取名字，不是按下标 ——
        // 副本槽位上的 A 键也要显示 "A"。
        if (isPadSlot(i) && padType[i] != 0) {
            int proto = protoOfType(padType[i]);
            String base = (proto >= 0 && proto < NAMES.length) ? NAMES[proto] : "";
            return base;
        }
        // 空白按钮：列表里显示成「空白」好认（建的时候选的就是它）。
        // 画布上不画名字 —— 见 drawBlankBtn()。
        if (isBlankUsed(i)) {
            // 改了名就显示改的名字（提示牌那块就是这么写字的）。
            // 空白按钮本来不画字，见 drawBlankBtn()。
            String bn = (customName != null && i < customName.length
                    && customName[i] != null) ? customName[i].trim() : "";
            return bn.length() > 0 ? bn : "空白";
        }
        // 组合键：列表里显示它自己的名字（可能是自动拼的 "A+B"）
        if (isComboUsed(i)) {
            return comboName[i];
        }
        if (isKeySlot(i) && keyCode[i] != 0) {
            // 加 [键盘] 是为了在"重置单个 / 互换位置 / 隐藏按钮"这类列表里
            // 一眼区分：哪些是手柄自带的固定键（改位置后能重置），
            // 哪些是后建的键盘键（可以删）。两者混在一个列表里，
            // 光看 "A" 分不出是手柄 A 键还是键盘 A 键。
            return keyName(keyCode[i]) + KEY_SUFFIX;
        }
        // NAMES 只有 N_FIXED 项（键盘按键的名字是动态的，写不进去）。
        // 正常流程不会走到这里（空槽位既进不了列表也选不中），
        // 但加个兜底，免得哪天多了条路径就 ArrayIndexOutOfBounds 崩掉。
        return (i >= 0 && i < NAMES.length) ? NAMES[i] : "";
    }

    /** 这个槽位是否已经创建了一个键盘按键。 */
    public boolean isKeySlotUsed(int i) {
        return isKeySlot(i) && keyCode[i] != 0;
    }

    /** 找第一个空槽位，没有返回 -1。 */
    public int allocKeySlot() {
        for (int i = I_KEY0; i < N_KEY_END; i++) {
            if (keyCode[i] == 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 在屏幕中间创建一个键盘按键。
     *
     * @return 分配到的元素下标，-1 = 槽位满了。
     */
    public int addKey(int usage, int w, int h) {
        int i = allocKeySlot();
        if (i < 0) {
            return -1;
        }
        keyCode[i] = usage;
        // 屏幕中间。后续想挪直接拖，所以不用挑精确位置。
        //
        // 直接写比例而不用 setPx()：setPx 依赖 mBottomLimit / mTopLimit，
        // 这两个只在 reset() 里赋值。addKey 是在编辑模式下临时调用的，
        // 那时它们还是上一次 reset() 的值（甚至可能是 0），夹出来会跑位。
        // 0.5 / 0.45 稳稳在屏幕内，不需要夹。
        rx[i] = 0.5f;
        ry[i] = 0.45f;
        scale[i] = 1f;
        alpha[i] = 1f;
        hidden[i] = false;
        // 键盘模式下新建的键跟着用矩形，和全键盘那批一致
        shape[i] = keyboardMode ? SHAPE_RECT : SHAPE_CIRCLE;
        widthMul[i] = keyboardMode ? (portrait ? 1.00f : 1.10f) : 1f;
        heightMul[i] = keyboardMode ? 1.06f : 1f;
        return i;
    }

    // ------------------------------------------------------------------
    // 手柄元素的创建 / 删除
    // ------------------------------------------------------------------

    /**
     * 可创建的手柄元素类型（列表里的选项）。
     *
     * 【排除了界面按钮】编 / 收 / 布 是操作界面用的，不是游戏键，
     *   建第二个「编」没有意义，代码里它们的绘制和点击也都是硬编码的。
     *
     * 【排除了 SELECT / START？不排除】它们就是普通按键，多建一个完全可以。
     */
    public static final int[] PAD_CANDIDATES = {
            I_A, I_B, I_X, I_Y,
            I_L1, I_R1, I_L3, I_R3,
            I_L2, I_R2,
            I_SEL, I_STA, I_HOME,
            I_Z, I_C,
            I_LS, I_RS, I_DPAD,
    };

    /**
     * 找第一个空的手柄槽位（先找固定区被删空的，再找副本区）。
     *
     * 【必须跳过界面按钮】
     *   I_COLLAPSE(13) / I_EDIT(14) 的下标也在 0..N_FIXED-1 里，
     *   而它们的 padType 恒为 0（它们不是手柄元素）——
     *   不跳过的话，"新建手柄按键"会把槽位 13 或 14 分配出去，
     *   新键直接盖在「收」或「编」的位置上，把那两个按钮顶掉。
     */
    public int allocPadSlot() {
        // 固定区优先：删掉 A 再建 A，回到原来的位置，符合直觉
        for (int i = 0; i < N_FIXED; i++) {
            if (isUiButton(i)) {
                continue;
            }
            if (padType[i] == 0) {
                return i;
            }
        }
        // 上界 BLANK_START：空白按钮槽位不是手柄副本槽位，
        // 分配出去会让同一个下标既是手柄键又是空白按钮。
        for (int i = PAD_EXTRA_START; i < BLANK_START; i++) {
            if (padType[i] == 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 创建一个手柄元素。
     *
     * @param proto 原型下标（I_A / I_LS / I_DPAD …）
     * @return 分配到的下标，-1 = 槽位满了
     */
    public int addPad(int proto) {
        int i = allocPadSlot();
        if (i < 0) {
            return -1;
        }
        padType[i] = typeOfElem(proto);
        // 屏幕中间，和 addKey 一致：建完自己拖到想要的位置。
        // 直接写比例不用 setPx，理由同 addKey（mTopLimit 这时不可靠）。
        rx[i] = 0.5f;
        ry[i] = 0.45f;
        scale[i] = 1f;
        alpha[i] = 1f;
        hidden[i] = false;
        shape[i] = SHAPE_CIRCLE;
        textScale[i] = 1f;
        widthMul[i] = 1f;
        heightMul[i] = 1f;
        stickFixed[i] = true;
        stickRange[i] = DEF_STICK_RANGE;
        return i;
    }

    /**
     * 删掉一个手柄元素，槽位回收（padType 归 0）。
     *
     * 界面按钮（编 / 收 / 布）不给删 —— 删了就再也进不了编辑模式、
     * 也收不起悬浮窗，等于把 app 锁死。
     */
    public void removePad(int i) {
        if (!isPadSlot(i) || isUiButton(i)) {
            return;
        }
        padType[i] = 0;
        hidden[i] = false;
    }

    /** 删掉一个键盘按键，槽位回收（keyCode 归 0）。 */
    public void removeKey(int i) {
        if (i < I_KEY0 || i >= N_KEY_END) {
            return;
        }
        keyCode[i] = 0;
        hidden[i] = false;
    }

    // ------------------------------------------------------------------
    // 空白按钮
    // ------------------------------------------------------------------

    /**
     * 空白按钮的占用标记。true = 这个槽位上有一个空白按钮。
     *
     * 【为什么是 boolean 而不是名字】
     *   以前这里叫 comboName，靠"名字非空"判断占用。空白按钮压根没有名字，
     *   再用名字当占用标记就得塞个占位串，语义绕。
     *   它也不像手柄键那样有 padType 可判 —— 它不映射任何键，
     *   所以单开一个标记最直接。
     */
    public final boolean[] blankUsed = new boolean[N];

    /**
     * 这个元素是**布局系统自带**的，不是用户建的。
     *
     * 【为什么要这个标志】
     *   提示牌（悬浮窗布局里那块说明牌）在模板里并不存在 —— 它是
     *   ensureFloatHint() 额外建出来的。重置单个时拿"模板默认值"去覆盖它，
     *   def 里那个空白槽位是空的：shape 被写成圆形、宽高倍率写成 1，
     *   于是牌子变成正圆，文字还在但挤成一团。
     *
     *   而"模板默认值"本来就不该管它 —— 它是布局自己摆上去的，
     *   重置该回到"布局给它定的样子"，不是"空白键的默认值"。
     *
     * 【存盘】
     *   不存的话重启后认不出来，又会被当普通空白键重置。
     *   参见存档里的 "so" 字段。
     */
    public final boolean[] sysOwned = new boolean[N];

    /**
     * 这个下标是不是空白按钮槽位。
     *
     * 【上界必须是 COMBO_START，不能写 N】
     *   组合键槽位排在空白之后，写成 i < N 会把组合键也判成"空白槽位"，
     *   而它们的 blankUsed 是 false —— 于是被当"没建的空白槽位"挡掉：
     *   本体不画、也点不中，只有选中框照画，屏幕上又是一团空气。
     *   这就是当初组合键"建得出来却看不见"的同一个坑。
     */
    public static boolean isBlankSlot(int i) {
        return i >= BLANK_START && i < COMBO_START;
    }

    /** 这个槽位上有没有一个空白按钮。 */
    public boolean isBlankUsed(int i) {
        return isBlankSlot(i) && blankUsed[i];
    }

    /** 找第一个空的空白槽位，没有返回 -1。 */
    public int allocBlankSlot() {
        for (int i = BLANK_START; i < COMBO_START; i++) {
            if (!isBlankUsed(i)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 建一个空白按钮。返回槽位下标，满了返回 -1。
     *
     * 初始位置屏幕中间、默认圆形、不隐藏 —— 和 addKey / addPad 一致，
     * 建完自己拖到想要的地方。
     */
    public int addBlank() {
        int i = allocBlankSlot();
        if (i < 0) {
            return -1;
        }
        blankUsed[i] = true;
        rx[i] = 0.5f;
        ry[i] = 0.45f;
        scale[i] = 1f;
        alpha[i] = 1f;
        hidden[i] = false;
        shape[i] = SHAPE_CIRCLE;
        textScale[i] = 1f;
        widthMul[i] = 1f;
        heightMul[i] = 1f;
        return i;
    }

    /** 删掉一个空白按钮，槽位回收。 */
    public void removeBlank(int i) {
        if (!isBlankSlot(i)) {
            return;
        }
        blankUsed[i] = false;
        hidden[i] = false;
    }

    // ---- 组合键 ----
    //
    // 组合键 = 按一下同时发出好几个键。它自己占一个槽位，
    // 具体发什么存在动作表里，不在 padType / keyCode 里 ——
    // 那两个是"一个元素 = 一个键"的模型，装不下多个。

    /**
     * 组合键的名字。null 或空串 = 这个槽位没被占用。
     *
     * 【为什么用名字当占用标记，而空白按钮用 boolean】
     *   组合键一定有名字（默认"组合键"，加了键会拼成 A+B 这种），
     *   拿它当标记不需要额外字段；空白按钮压根没有名字，所以另开 boolean。
     */
    public final String[] comboName = new String[N];

    /**
     * 组合键的动作表。每个槽位 MAX_COMBO_ACTS 行，每行 3 列：
     *   [j*3 + 0] = 类型：0 未设置 / 1 手柄键 / 2 键盘键 / 5 结束手柄键 /
     *               6 结束键盘键
     *   [j*3 + 1] = 编码：手柄是 padType，键盘是 HID usage
     *   [j*3 + 2] = 序号：类型是"结束"时，表示结束的是第几个同名键
     *               （序列里有两个 A 时，结束第 2 个就存 2）
     *
     * 【为什么要第三列】
     *   两个 A 的编码完全一样，光靠"类型 + 编码"分不出停的是哪个。
     *   显示时按位置数一遍不可靠：结束行自己占一格，数的时候会把
     *   自己的位置算进去，于是永远数成第 1 个 —— 表现就是
     *   "点 A[2] 的结束，建出来却是 结束A[1]"。
     *
     * 用一维 int[] 而不是二维数组：存档要按槽位一个 JSONObject 写进去，
     * 一维编成字符串更直接，也避免二维数组在 JSON 里嵌套。
     */
    //
    // 【十字架组合键：一个槽位要放 4 条序列】
    //   上 / 下 / 左 / 右各一条，所以动作表多了"方向"这一维。
    //   普通组合键只用 dir 0，其余三格空着 —— 老存档不受影响。
    //   只给 16 个组合键槽位开空间，不用 N（182），避免白占 280KB。
    public final int[] comboAct = new int[MAX_COMBO * COMBO_DIRS * MAX_COMBO_ACTS * 3];

    //
    // ---- 每个按键自己的「显示」三件套 ----
    //   改名 / 改底色 / 关掉按键名。都是"这个键长什么样"，
    //   和"它映射哪个键"无关，所以单独放，不动 padType / keyCode。
    //
    /** 自定义显示名。null 或空 = 用默认名（A / B / 空格…）。 */
    public final String[] customName = new String[N];
    /**
     * 自定义底色。0 = 用默认那支画笔的颜色。
     *
     * 【为什么用 0 而不是存默认色值】
     *   默认色有三支（常态 / 按下 / 描边），而且以后可能整体换配色。
     *   存 0 表示"没设过，走默认"，换配色时老存档自动跟着变。
     */
    public final int[] btnColor = new int[N];
    /**
     * 穿透按钮的两支底色：开 / 关各一支。
     *
     * 为什么单独存、不塞进 btnColor[I_PASS]：
     *   这一个按钮有两种状态色，一支装不下。0 = 没设过，走内置的绿 / 红。
     */
    public int passOnColor = 0;
    public int passOffColor = 0;
    /** 按键名要不要显示。默认 true（显示）。 */
    public final boolean[] showLabel = new boolean[N];
    {
        for (int i = 0; i < N; i++) {
            showLabel[i] = true;
        }
    }

    /** 这个键现在显示成什么名字（没改过就给默认名）。 */
    public String displayName(int i, String defLabel) {
        if (i < 0 || i >= N) {
            return defLabel;
        }
        String cn = customName[i];
        return (cn == null || cn.isEmpty()) ? defLabel : cn;
    }

    /** 这个键的名字现在要不要显示。 */
    public boolean labelOn(int i) {
        return i >= 0 && i < N && showLabel[i];
    }


    public final boolean[] comboCross = new boolean[N];
    /** 十字架的斜角要不要响应（默认开）：开了左上 = 左 + 上 一起触发。 */
    public final boolean[] comboDiag = new boolean[N];

    /** 动作表下标：(槽位, 方向, 第几个动作, 第几列)。 */
    private static int actIdx(int i, int dir, int j, int col) {
        return ((i - COMBO_START) * COMBO_DIRS + dir) * MAX_COMBO_ACTS * 3
                + j * 3 + col;
    }

    /**
     * 这个下标是不是组合键槽位。
     *
     * 【上界必须写 COMBO_START + MAX_COMBO，不能写 N】
     *   N 现在比组合键段多一格（末尾多了 I_FLOAT 那个 G）。
     *   写成 i < N 的话 G 会被判成组合键槽位，于是
     *   reset() 里那句 setComboAct(i, d, j, 0, 0) 会按
     *   (i - COMBO_START) = MAX_COMBO 去索引 comboAct —— 直接越界崩溃。
     *   表现为"一点悬浮球就炸"。
     */
    public static boolean isComboSlot(int i) {
        return i >= COMBO_START && i < COMBO_START + MAX_COMBO;
    }

    /** 这个槽位上有没有一个组合键。 */
    public boolean isComboUsed(int i) {
        return isComboSlot(i) && comboName[i] != null && !comboName[i].isEmpty();
    }

    /** 找第一个空的组合键槽位，没有返回 -1。 */
    public int allocComboSlot() {
        for (int i = COMBO_START; i < COMBO_START + MAX_COMBO; i++) {
            if (!isComboUsed(i)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 建一个组合键。返回槽位下标，满了返回 -1。
     *
     * 初始位置、形状、大小都和 addBlank / addKey 一致（屏幕中间、圆形），
     * 建完自己拖到想要的地方。
     */
    public int addCombo(String name) {
        int i = allocComboSlot();
        if (i < 0) {
            return -1;
        }
        comboName[i] = (name == null || name.isEmpty()) ? "组合键" : name;
        // 动作表清零：槽位回收过，别把上一个组合键的键带过来
        for (int d = 0; d < COMBO_DIRS; d++) {
            for (int j = 0; j < MAX_COMBO_ACTS; j++) {
                setComboAct(i, d, j, 0, 0);
            }
        }
        rx[i] = 0.5f;
        ry[i] = 0.45f;
        scale[i] = 1f;
        alpha[i] = 1f;
        hidden[i] = false;
        shape[i] = SHAPE_CIRCLE;
        textScale[i] = 1f;
        widthMul[i] = 1f;
        heightMul[i] = 1f;
        return i;
    }

    /** 删掉一个组合键，槽位和动作表一起回收。 */
    public void removeCombo(int i) {
        if (!isComboSlot(i)) {
            return;
        }
        comboName[i] = null;
        comboCross[i] = false;
        comboDiag[i] = true;
        for (int d = 0; d < COMBO_DIRS; d++) {
            for (int j = 0; j < MAX_COMBO_ACTS; j++) {
                setComboAct(i, d, j, 0, 0);
            }
        }
        hidden[i] = false;
    }

    /** 读第 j 个动作的类型（0 未设置 / 1 手柄 / 2 键盘）。 */
    public int comboActType(int i, int dir, int j) {
        if (!isComboSlot(i) || j < 0 || j >= MAX_COMBO_ACTS
                || dir < 0 || dir >= COMBO_DIRS) {
            return 0;
        }
        return comboAct[actIdx(i, dir, j, 0)];
    }

    /** 读第 j 个动作的编码。 */
    public int comboActCode(int i, int dir, int j) {
        if (!isComboSlot(i) || j < 0 || j >= MAX_COMBO_ACTS
                || dir < 0 || dir >= COMBO_DIRS) {
            return 0;
        }
        return comboAct[actIdx(i, dir, j, 1)];
    }

    /** 读第 j 个动作的序号（结束动作用它记住"停的是第几个同名键"）。 */
    public int comboActTag(int i, int dir, int j) {
        if (!isComboSlot(i) || j < 0 || j >= MAX_COMBO_ACTS
                || dir < 0 || dir >= COMBO_DIRS) {
            return 0;
        }
        return comboAct[actIdx(i, dir, j, 2)];
    }

    /** 写第 j 个动作（序号默认 0）。超出范围直接忽略，不越界。 */
    public void setComboAct(int i, int dir, int j, int type, int code) {
        setComboAct(i, dir, j, type, code, 0);
    }

    /** 写第 j 个动作，带序号。超出范围直接忽略，不越界。 */
    public void setComboAct(int i, int dir, int j, int type, int code, int tag) {
        if (!isComboSlot(i) || j < 0 || j >= MAX_COMBO_ACTS
                || dir < 0 || dir >= COMBO_DIRS) {
            return;
        }
        comboAct[actIdx(i, dir, j, 0)] = type;
        comboAct[actIdx(i, dir, j, 1)] = code;
        if (tag < 0) {
            tag = 0;
        }
        comboAct[actIdx(i, dir, j, 2)] = tag;
    }

    /** 这个组合键里已经设了几个键（未设置的行不算）。 */
    public int comboActCount(int i, int dir) {
        if (!isComboSlot(i)) {
            return 0;
        }
        int n = 0;
        for (int j = 0; j < MAX_COMBO_ACTS; j++) {
            if (comboActType(i, dir, j) != 0) {
                n++;
            }
        }
        return n;
    }

    /**
     * 组合键的自动名字：把里面的键名用 "+" 连起来。
     * 一个键都没有时返回默认名。
     */
    public String comboAutoName(int i, int dir) {
        if (!isComboSlot(i)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int j = 0; j < MAX_COMBO_ACTS; j++) {
            int t = comboActType(i, dir, j);
            if (t == 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('+');
            }
            // 自动名里延迟用短写法：全写"等待 100ms"的话
            // A+等待 100ms+B 这种名字会把按钮撑爆
            sb.append(t == ACT_DELAY
                    ? (comboActCode(i, dir, j) + "ms")
                    : actShortName(t, comboActCode(i, dir, j),
                            comboActTag(i, dir, j)));
        }
        return sb.length() > 0 ? sb.toString() : "组合键";
    }

    /**
     * 单个动作的短名。
     *
     * 手柄键存的是 padType（和 padType[] 同一套），要经 protoOfType 换成原型下标
     * 再查 NAMES —— 副本槽位上的键也是同一个 padType，所以这里能直接对上。
     */
    /** 带方向号的短名（摇杆 / 十字键要用 tag 里的方向）。 */
    public static String actShortName(int type, int code, int tag) {
        if (type == 1 && tag > 0) {
            int proto = protoOfType(code);
            // 扳机：tag 存的是按下力度百分比，写进名字才看得出多重
            if (proto == I_L2 || proto == I_R2) {
                return ((proto >= 0 && proto < NAMES.length) ? NAMES[proto] : "?")
                        + " " + tag + "%";
            }
        }
        if (type == ACT_STICK_XY) {
            int proto = (code == I_RS) ? I_RS : I_LS;
            String base = (proto >= 0 && proto < NAMES.length) ? NAMES[proto] : "?";
            return base + " X" + stickXOf(tag) + " Y" + stickYOf(tag);
        }
        if (type == ACT_RELEASE_STICK) {
            int proto = (code == I_RS) ? I_RS : I_LS;
            String base = (proto >= 0 && proto < NAMES.length) ? NAMES[proto] : "?";
            return "结束" + base;
        }
        if (type == ACT_RELEASE_HAT) {
            return "结束十字键";
        }
        if (type == ACT_STICK_DIR) {
            int proto = (code == I_RS) ? I_RS : I_LS;
            String base = (proto >= 0 && proto < NAMES.length) ? NAMES[proto] : "?";
            return base + " " + dirName(tag);
        }
        if (type == ACT_HAT_DIR) {
            return "十字键 " + dirName(tag);
        }
        return actShortName(type, code);
    }

    /** 方向号 -> 中文短名。 */
    public static String dirName(int d) {
        String[] n = {"上", "右上", "右", "右下", "下", "左下", "左", "左上"};
        return (d >= 0 && d < n.length) ? n[d] : "?";
    }

    public static String actShortName(int type, int code) {
        if (type == 1) {
            int proto = protoOfType(code);
            return (proto >= 0 && proto < NAMES.length) ? NAMES[proto] : "?";
        }
        if (type == 2) {
            return keyName(code);
        }
        // 延迟：编码位就是毫秒数
        if (type == ACT_DELAY) {
            return "等待 " + code + "ms";
        }
        // 结束（老数据，无指定目标）：松开前面还按着的所有键
        if (type == ACT_RELEASE) {
            return "结束";
        }
        // 结束指定键：code 就是要松开的那个键
        if (type == ACT_RELEASE_PAD || type == ACT_RELEASE_KEY) {
            return "结束" + actShortName(type == ACT_RELEASE_PAD ? 1 : 2, code);
        }
        return "?";
    }

    private static final String PREFS = "pad_layout";
    private static final String KEY_P = "portrait";
    private static final String KEY_L = "landscape";

    public final float[] rx = new float[N];
    public final float[] ry = new float[N];
    public final float[] scale = new float[N];
    public final float[] alpha = new float[N];
    /**
     * 隐藏标记：true 的按钮在编辑模式下画叉（还能看见、还能拖，
     * 否则就再也找不回来了），非编辑模式下完全不画也点不到。
     */
    public final boolean[] hidden = new boolean[N];

    public boolean portrait;

    /** reset() 期间临时用：可用下边界、顶部保护下沿 与 各元素默认半径 */
    private float mBottomLimit;
    private float mTopLimit;
    private final float[] mRadius = new float[N];

    // ------------------------------------------------------------------
    // 默认布局
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // 「固定显示」—— 各模板的**初始状态**覆盖
    // ------------------------------------------------------------------
    //
    // 作用域：凡是"回到初始状态"的路径都受它影响 ——
    //   重置全部 / 新建布局 / 按模板新建。
    // 它**不会**立刻改当前布局：改完只是记下来，下次回到初始状态才生效。
    //
    // 存 4 组整数，每组是一串 CSV：
    //   hid  强制隐藏
    //   del  删掉（手柄：padType 归 0；键盘：keyCode 归 0）
    //   add  额外创建（放在屏幕中心，不指定位置）
    //   adH  上面这些"额外创建"里再隐藏掉的一部分
    //
    // 手柄元素用**下标**标识；键盘按键用 **HID usage** 标识
    // （键盘键是动态分配的槽位，下标不稳定，只有 usage 是它的身份）。

    /** kind：见上面的说明。 */
    private static String fxKey(int tpl, String kind) {
        return "fx_" + tpl + "_" + kind;
    }

    private static java.util.HashSet<Integer> fxParse(String csv) {
        java.util.HashSet<Integer> r = new java.util.HashSet<Integer>();
        if (csv == null || csv.length() == 0) {
            return r;
        }
        for (String t : csv.split(",")) {
            t = t.trim();
            if (t.length() == 0) {
                continue;
            }
            try {
                r.add(Integer.valueOf(Integer.parseInt(t)));
            } catch (NumberFormatException ignored) {
            }
        }
        return r;
    }

    private static String fxJoin(java.util.Set<Integer> v) {
        StringBuilder b = new StringBuilder();
        for (Integer i : v) {
            if (b.length() > 0) {
                b.append(',');
            }
            b.append(i.intValue());
        }
        return b.toString();
    }

    public static java.util.HashSet<Integer> fxLoad(Context ctx, int tpl, String kind) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return fxParse(sp.getString(fxKey(tpl, kind), ""));
    }

    public static void fxSave(Context ctx, int tpl, String kind, java.util.Set<Integer> v) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        sp.edit().putString(fxKey(tpl, kind), fxJoin(v)).apply();
    }

    /** 在集合里加 / 删一个值，返回改完之后在不在集合里。 */
    public static boolean fxToggle(Context ctx, int tpl, String kind, int v) {
        java.util.HashSet<Integer> set = fxLoad(ctx, tpl, kind);
        boolean has = set.contains(Integer.valueOf(v));
        if (has) {
            set.remove(Integer.valueOf(v));
        } else {
            set.add(Integer.valueOf(v));
        }
        fxSave(ctx, tpl, kind, set);
        return !has;
    }

    public static boolean fxHas(Context ctx, int tpl, String kind, int v) {
        return fxLoad(ctx, tpl, kind).contains(Integer.valueOf(v));
    }

    /**
     * 把「固定显示」的覆盖套到 this 上。在 reset() 末尾调用。
     *
     * @param ctx 传 null 表示不套用（比如只想要一份干干净净的默认布局做参照）
     */
    /**
     * 「透」按钮的默认外观：扁矩形 0.89(长) × 0.53(宽)，大小 65%，字体 30%。
     *
     * 【必须设成 SHAPE_RECT】
     *   reset() 开头把所有元素初始化成 SHAPE_CIRCLE + widthMul/heightMul = 1，
     *   圆形分支下这两个倍数是被忽略的（画的是正圆），
     *   不切形状的话设了也不生效，还是个圆。
     *
     * 【为什么抽成方法】
     *   摆这个按钮的地方有四处（手柄带轨道 / 无轨道 / 横屏 / 键盘模式），
     *   每处都抄一遍五个赋值，改其中一个就会不一致 ——
     *   表现为"横屏下是扁的、竖屏下是圆的"。
     */
    private void applyPassDefault() {
        shape[I_PASS] = SHAPE_RECT;
        widthMul[I_PASS] = 0.89f;    // 长
        heightMul[I_PASS] = 0.53f;   // 宽
        // 【这两个不是比例，是按"滑条刻度"反推的真值】
        //   面板上显示的是滑条位置 0~100，不是百分比：
        //     - 大小走对数映射（0.15~2.2），写 0.65 显示出来是 55
        //     - 字体滑条最小值就是 0.3，写 0.30 显示出来是 0（最左端）
        //   所以这里按刻度 65 / 30 反推，让面板读数正好是 65 和 30。
        scale[I_PASS] = sliderToScale(0.65f);
        textScale[I_PASS] = MIN_TEXT_SCALE
                + 0.30f * (MAX_TEXT_SCALE - MIN_TEXT_SCALE);
    }

    private void applyFixedInit(Context ctx, int tpl, String ns) {
        if (ctx == null) {
            return;
        }
        java.util.Set<Integer> hid = fxLoad(ctx, tpl, ns + "hid");
        java.util.Set<Integer> del = fxLoad(ctx, tpl, ns + "del");
        java.util.Set<Integer> add = fxLoad(ctx, tpl, ns + "add");
        java.util.Set<Integer> adh = fxLoad(ctx, tpl, ns + "adH");

        boolean isKb = ns.equals("key");
        java.util.Set<Integer> shw = fxLoad(ctx, tpl, ns + "shw");

        // 0) 强制显示。必须排在隐藏**之前** ——
        //    「穿透」这类模板里默认 hidden=true 的功能键，
        //    只有先清掉默认、再（可能）置回隐藏，才能被强制放出来。
        //    反过来的话 shw 会被 hid 盖掉，永远出不来。
        for (Integer v : shw) {
            int i = v.intValue();
            if (isKb) {
                for (int k = I_KEY0; k < N_KEY_END; k++) {
                    if (keyCode[k] == i) {
                        hidden[k] = false;
                    }
                }
            } else if (i >= 0 && i < N) {
                hidden[i] = false;
            }
        }

        // 1) 删除。手柄按下标，键盘按 usage。
        for (Integer v : del) {
            int i = v.intValue();
            if (isKb) {
                for (int k = I_KEY0; k < N_KEY_END; k++) {
                    if (keyCode[k] == i) {
                        keyCode[k] = 0;
                    }
                }
            } else if (i >= 0 && i < N_FIXED && !isUiButton(i)) {
                // 【界面按钮不给删】删了「编」就再也进不了编辑模式
                padType[i] = 0;
                hidden[i] = false;
            }
        }
        // 2) 隐藏
        for (Integer v : hid) {
            int i = v.intValue();
            if (isKb) {
                for (int k = I_KEY0; k < N_KEY_END; k++) {
                    if (keyCode[k] == i) {
                        hidden[k] = true;
                    }
                }
            } else if (i >= 0 && i < N) {
                hidden[i] = true;
            }
        }
        // 3) 额外创建：放在屏幕中心，不指定位置（建完自己拖）
        //
        //   【为什么固定写在 0.5 / 0.45】
        //     reset() 里 mTopLimit/mBottomLimit 这时已经赋好值了，
        //     但 addPad / addKey 本来就是"建完自己拖"的语义，
        //     写死屏幕中心和它们的默认行为一致，也不需要夹边界。
        for (Integer v : add) {
            int i = v.intValue();
            if (isKb) {
                int slot = addKey(i, 1, 1);
                if (slot >= 0 && adh.contains(Integer.valueOf(i))) {
                    hidden[slot] = true;
                }
            } else {
                int slot = addPad(i);
                if (slot >= 0 && adh.contains(Integer.valueOf(i))) {
                    hidden[slot] = true;
                }
            }
        }
    }

    /**
     * 按当前宽高与方向铺一套默认布局（像素算完立刻转成比例）。
     *
     * @param bottomLimit 可用区域的下边界（像素）。编辑模式的面板要占掉屏幕底部
     *                    一段，把它的顶边传进来，底部按键会自动往上让，
     *                    不会钻到面板底下被盖住。传 <=0 表示不限制。
     * @param topLimit 顶部保护区下沿（像素）。按键一律排在这条线以下，
     *                 免得贴到屏幕顶上（会有状态栏 / 挖孔，拖动时也抓不回来）。
     */
    public void reset(int w, int h, boolean portrait, float bottomLimit, float topLimit) {
        reset(w, h, portrait, bottomLimit, topLimit, false);
    }

    /**
     * @param keyboard true = 生成全键盘布局，false = 手柄布局
     */
    public void reset(int w, int h, boolean portrait, float bottomLimit, float topLimit,
                      boolean keyboard) {
        reset(w, h, portrait, bottomLimit, topLimit, keyboard ? TPL_KEYBOARD : TPL_PAD);
    }

    /** 带 ctx 的 boolean 版：末尾会套用「固定显示」的覆盖。 */
    public void reset(int w, int h, boolean portrait, float bottomLimit, float topLimit,
                      boolean keyboard, Context ctx) {
        reset(w, h, portrait, bottomLimit, topLimit,
                keyboard ? TPL_KEYBOARD : TPL_PAD, ctx);
    }

    /**
     * @param tpl 模板：TPL_PAD / TPL_KEYBOARD / TPL_BLANK
     *
     * BLANK = 空白模板：界面按钮（编 / 收 / 模式）照常摆，
     * 但**一个游戏键都不生成**，用户从零开始往上面加。
     */
    public void reset(int w, int h, boolean portrait, float bottomLimit, float topLimit,
                      int tpl) {
        reset(w, h, portrait, bottomLimit, topLimit, tpl, null);
    }

    /**
     * @param ctx 传非 null 时，末尾会套用「固定显示」里存的初始状态覆盖。
     *            传 null = 只要一份干净的模板默认（比如拿它当参照算默认位置）。
     */
    public void reset(int w, int h, boolean portrait, float bottomLimit, float topLimit,
                      int tpl, Context ctx) {
        this.portrait = portrait;
        boolean keyboard = tpl == TPL_KEYBOARD;
        this.keyboardMode = keyboard;
        for (int i = 0; i < N; i++) {
            scale[i] = 1f;
            alpha[i] = 1f;
            hidden[i] = false;
            shape[i] = SHAPE_CIRCLE;
            textScale[i] = 1f;
            // 1.0 = 正圆手柄键。矩形键由 resetKeyboard 另行设成填满格子的值。
            widthMul[i] = 1f;
            heightMul[i] = 1f;
            stickFixed[i] = true;
            stickRange[i] = DEF_STICK_RANGE;
            // 固定区 = 自身类型；副本区/键盘槽位 = 0（空）
            padType[i] = (i < N_FIXED && !isUiButton(i)) ? typeOfElem(i) : 0;
            // 空白按钮是"用户建的"，重置一律清掉 —— 和键盘键、副本手柄键一致。
            // 不清的话重置后画布上会留着一批看不见来源的按钮。
            blankUsed[i] = false;
            // 系统自带标记同理清空，随后由 ensureFloatHint / floatBallStyle
            // 重新给真正自带的那些元素打上。
            sysOwned[i] = false;
            // 组合键同理，一并清掉。动作表也要清零，
            // 否则下次占用这个槽位时会带着上一次的键。
            comboName[i] = null;
            comboCross[i] = false;
            comboDiag[i] = true;
            // 显示三件套：重置一律回到默认（用默认名 / 默认色 / 显示名字）。
            // 它们是"外观"，重置就该恢复原样，留着会让人以为模板自带花名。
            customName[i] = "";
            btnColor[i] = 0;
            showLabel[i] = true;
            for (int d = 0; d < COMBO_DIRS; d++) {
                for (int j = 0; j < MAX_COMBO_ACTS; j++) {
                    setComboAct(i, d, j, 0, 0);
                }
            }
            //
            // 【空白 / 组合键槽位要有默认位置】
            //   它们是"用户后建的"，reset 里没有任何 setPx 覆盖到，
            //   rx/ry 一直是数组初值 0。而 load 是"先 reset 再读存档覆盖"：
            //   存档里没有 x/y 的条目就会留下 0 —— 画出来就是屏幕左上角 (0,0)，
            //   看上去像"按钮飞到角上"。
            //
            //   （同步信息时目标方向可能是新建的条目，正好没有 x/y。）
            if (isBlankSlot(i) || isComboSlot(i)) {
                rx[i] = 0.5f;
                ry[i] = 0.45f;
            }
        }
        // 穿透开 / 关两支颜色也回到内置绿 / 红
        passOnColor = 0;
        passOffColor = 0;
        // 【「布」默认显示，不再默认隐藏】
        //   它是切到别的布局的唯一入口。藏起来的话，
        //   想换布局只能先进编辑模式再去「隐藏按钮」里放出来 ——
        //   等于每次重置后都要绕一圈。而且重置一次就藏一次，
        //   用户会以为功能坏了。想要它不显示，手动在「隐藏按钮」里关掉即可，
        //   那个设置是存盘的，不会被重置冲掉。
        hidden[I_LAYOUT] = false;
        /*
          鼠标模板。
          先走 resetBlank 清出一块干净的画布（手柄键 / 键盘键全删），
          再摆上触摸板 + 左键 + 右键三个元素。

          鼠标元素走的是第三个 uhid 设备（MouseReport），和手柄那份报告
          没有任何关系，所以放进手柄按键的候选表里是不合适的 —— 那里
          每一项都对应 GamepadReport 的某个按钮/轴。
          它们有自己的候选表（创建 → 鼠标键），六个固定种类：
          触摸板 / 左键 / 右键 / 中键 / 滚轮上 / 滚轮下，
          每种最多一个，可删、删了还能再建回来。
        */
        if (tpl == TPL_MOUSE) {
            resetBlank(w, h, portrait, bottomLimit, topLimit);
            resetMouse(w, h, portrait);
            applyFixedInit(ctx, tpl, "pad");
            applyFixedInit(ctx, tpl, "key");
            floatBallStyle(this);
            applyFloatVisibility(this);
            return;
        }
        if (tpl == TPL_BLANK) {
            resetBlank(w, h, portrait, bottomLimit, topLimit);
            // 空白模板没有现成的键可隐藏 / 删除，只有"添加"有意义。
            // 手柄键和键盘键两套命名空间都套一下 —— 空白上面两种都能加。
            applyFixedInit(ctx, tpl, "pad");
            applyFixedInit(ctx, tpl, "key");
            floatBallStyle(this);
            applyFloatVisibility(this);
            return;
        }
        if (keyboard) {
            // 【固定手柄元素必须显式隐藏】
            // resetKeyboard() 只摆键盘键，不动 0..21 那批固定元素。
            // 而这里提前 return 了，它们的 rx/ry 还是新建时的 0
            // —— 于是摇杆、ABXY、肩键**全部堆在屏幕左上角 (0,0)**。
            // 编辑模式下隐藏元素照样显示、照样能拖，就变成"一堆按钮挤在左上角"。
            //
            // 所以键盘模式下把它们全部隐藏：既是"键盘模式不需要手柄键"的语义，
            // 也顺带解决了坐标全 0 的问题（万一被放出来也在屏幕中间，不在角上）。
            //
            // 【改成删除，不是隐藏】
            // 手柄元素现在可以删、也可以新建，隐藏就是自找麻烦：
            //   - 「隐藏按钮」列表里会列出一堆被藏起来的手柄键，
            //     用户想找自己建的键盘键得在一堆"看不见的手柄键"里翻
            //   - 编辑模式下隐藏元素照样显示，键盘布局上会浮着一圈
            //     半透明的手柄键，看着像没删干净
            //   - 想用了还得手动一个个放出来
            // 直接删掉（padType 归 0）：列表干净，需要时从「手柄按键…」建。
            for (int i = 0; i < N_FIXED; i++) {
                // 【「编」「收」不能删】
                // 它俩下标 13/14 也在 0..N_FIXED-1 里。跟着一起删掉的话：
                // 非编辑模式下就**看不到「编」按钮，也就再也进不了编辑模式**，
                // 「收」同理，悬浮窗都收不起来 —— 等于把 app 锁死。
                // 它们是界面工具，不是手柄按键，键盘模式同样需要。
                if (isUiButton(i)) {
                    continue;
                }
                padType[i] = 0;
                hidden[i] = false;
                rx[i] = 0.5f;
                ry[i] = 0.45f;
            }
            // 副本槽位也一并清空：键盘模板不该带着别的手柄键进来
            // 上界 BLANK_START：不清空白按钮的 hidden，那是用户自己的设置
            for (int i = PAD_EXTRA_START; i < BLANK_START; i++) {
                padType[i] = 0;
                hidden[i] = false;
            }
            // 【limit 必须先赋值】setPx() 里的 clampY 依赖 mTopLimit/mBottomLimit，
            // 而这两个只在 reset() 后半段赋值，这里提前 return 了 ——
            // 不补上的话 mBottomLimit 还是 0，所有 y 都会被夹到同一条线上。
            mBottomLimit = bottomLimit > 0f ? bottomLimit : h;
            mTopLimit = topLimit > 0f ? topLimit : 0f;
            resetKeyboard(w, h, portrait);
            // 两个命名空间都套：键盘模板上也能预先加几个手柄键
            // （"键"那套的 del/hid 在这里不起作用 —— 固定手柄键已经全删了）
            applyFixedInit(ctx, tpl, "key");
            applyFixedInit(ctx, tpl, "pad");
            floatBallStyle(this);
            applyFloatVisibility(this);
            return;
        }
        // 无轨道版默认隐藏：它是"想要点一下就扣到底"时的替代品，
        // 平时用带轨道的。放出来 / 收回去都在编辑模式的「隐藏按钮」里操作。
        hidden[I_L2B] = true;
        hidden[I_R2B] = true;
        // HOME 默认隐藏：多数游戏不认 MODE 键，显示出来反而占位。
        hidden[I_HOME] = true;
        hidden[I_Z] = true;
        hidden[I_C] = true;
        applyFloatVisibility(this);

        mBottomLimit = bottomLimit > 0f ? bottomLimit : h;
        mTopLimit = topLimit > 0f ? topLimit : 0f;
        mRadius[PadLayout.I_LS] = Math.min(w, h) * (portrait ? 0.13f : 0.115f);
        mRadius[PadLayout.I_RS] = mRadius[PadLayout.I_LS];
        mRadius[PadLayout.I_DPAD] = Math.min(w, h) * (portrait ? 0.11f : 0.10f);

        float m = Math.min(w, h);
        // 半径必须和 GamepadView.radiusOf() 完全一致，否则收边会算少
        // （竖屏按钮实际更大，按小半径收边就会有一截露在面板底下）
        float btnR = m * (portrait ? 0.065f : 0.058f);
        for (int i = 0; i < N; i++) {
            if (mRadius[i] <= 0f) {
                mRadius[i] = btnR;
            }
        }
        // 肩键 L2/L1（和 R2/R1）用"固定像素间距"而不是屏幕比例：
        // 平板那种又大又方的屏上，按比例算出来的间距会小于两个按钮的判定圈，
        // 视觉上就会粘在一起。
        float shoulderGap = btnR * 3.0f;
        // 「编 / 收」这一行用略小一点的间距，省出来的高度留给游戏按键
        float editGap = btnR * 2.6f;
        float off = btnR * 2.1f;

        if (portrait) {
            // 底部一排整体下移（原来 0.70h）。
            // 现在底部不再给编辑面板让路，屏幕最下面那条也能用了，
            // 所以把左摇杆和 ABXY 一起往下压，手指不用抬那么高。
            // 0.78h 是留了余量的：再往下会被系统手势条 / 虚拟导航栏吃掉。
            float bottomY = Math.min(clampY(w, h, 0.78f * h, mRadius[I_LS]),
                    clampY(w, h, 0.78f * h, off + btnR));
            // 摇杆按"底部组往上让出一个身位"来放，而不是写死屏幕比例
            float stickY = bottomY - off - btnR - mRadius[I_LS] - m * 0.08f;

            // 上半：L/R 1/2 整体下移，让出顶部保护区；
            // 「编」挂在 L1 下面、「收」挂在 R1 下面。
            float sTop = shoulderTop(w, h, stickY, btnR, shoulderGap, editGap);

            setPx(w, h, I_L2, 0.075f * w, sTop);
            // L3 在 L2 右边，横向间距和肩键列的纵向间距一致（shoulderGap），
            // 看起来是一排而不是散着的。
            setPx(w, h, I_L3, 0.075f * w + shoulderGap, sTop);
            setPx(w, h, I_L1, 0.075f * w, sTop + shoulderGap);
            setPx(w, h, I_EDIT, 0.075f * w, sTop + shoulderGap + editGap);
            // 「布」在「编」右边，横向错开一个肩键间距。
            // 默认隐藏（多数人不换布局），
            // 需要时去「常用工具 -> 隐藏按钮」里放出。
            setPx(w, h, I_LAYOUT, 0.075f * w + shoulderGap,
                    sTop + shoulderGap + editGap);
            scale[I_LAYOUT] = 0.9f;
            // 穿透按钮挂在「布」正下方，和「编 / 收 / 布」同一列。
            // 默认隐藏：它是调试向开关，不该一上来就占地方，
            // 需要时去「常用工具 -> 隐藏按钮」放出。
            setPx(w, h, I_PASS, 0.075f * w + shoulderGap,
                    sTop + shoulderGap + editGap * 2f);
            applyPassDefault();
            hidden[I_PASS] = true;
            // 旧版（无轨道）位置：sTop 那一格，但按"圆按钮"算顶端限制，
            // 所以不一定等于带轨道版的 sTop（横屏差约 31px）。先放一次把坐标记下来。
            float legacySTop = shoulderTop(w, h, stickY, btnR, shoulderGap, editGap, false);
            setPx(w, h, I_L2B, 0.075f * w, legacySTop);
            mLegacyRx[0] = rx[I_L2B];
            mLegacyRy[0] = ry[I_L2B];
            setPx(w, h, I_R2B, 0.925f * w, legacySTop);
            mLegacyRx[1] = rx[I_R2B];
            mLegacyRy[1] = ry[I_R2B];

            // 无轨道版挂在「编」下面（肩键列第 4 行）。
            // 不放回 sTop 那一格：那里现在是带轨道版，
            // 两个都显示时会完全叠在一起、分不清谁是谁。
            // 它默认隐藏，放出来也只是偶尔用，挂在列尾最省事，也不会挡到游戏区。
            setPx(w, h, I_L2B, 0.075f * w, sTop + shoulderGap + editGap * 2f);
            setPx(w, h, I_R2, 0.925f * w, sTop);
            // R3 在 R2 左边，同样用 shoulderGap
            setPx(w, h, I_R3, 0.925f * w - shoulderGap, sTop);
            setPx(w, h, I_R1, 0.925f * w, sTop + shoulderGap);
            setPx(w, h, I_COLLAPSE, 0.925f * w, sTop + shoulderGap + editGap);
            setPx(w, h, I_R2B, 0.925f * w, sTop + shoulderGap + editGap * 2f);
            // SELECT / START 在顶部中间（0.09h）。
            // 手机上 sTop ≈ 0.155h，和 0.09h 差得远，判定圈不会碰到 L3 / R3；
            // 4:3 平板竖屏上 sTop 会被顶部保护区顶到和它几乎同高，
            // 那一排 6 个大按钮就有点挤（约 66px 的重叠区归 SELECT）。
            // 你用的是手机，不受影响，所以这儿没为平板再挪位置。
            setPx(w, h, I_SEL, 0.40f * w, 0.09f * h);
            setPx(w, h, I_STA, 0.60f * w, 0.09f * h);
            // HOME 在 SEL / STA 中间的正下方（x 取两者正中）。
            // 垂直间距用 editGap（= btnR * 2.6）而不是 off（= btnR * 2.1）：
            // 两个判定圈都是 1.25r，中心距要 > 2.5r 才不重叠，
            // 2.1r 差了约 0.4r，刚好会咬在一起。2.6r 有余量。
            setPx(w, h, I_HOME, 0.50f * w, 0.09f * h + editGap);

            // 三处轮换：十字键去原右摇杆位、左摇杆去原十字键位、右摇杆去原左摇杆位
            setPx(w, h, I_DPAD, 0.28f * w, stickY);
            setPx(w, h, I_LS, 0.28f * w, bottomY);
            // 右摇杆 0.68w -> 0.72w（往右挪一点，右手拇指落点更顺）。
            // 0.74w 会撞上 R2B：1600×2560、2048×2732 这类高屏上
            // 肩键列被顶部保护区压下来，正好和右摇杆判定圈咬在一起（实测 2 处重叠）。
            // 0.72w 在全部 10 种竖屏比例下都干净，右边还留 162px 余量。
            setPx(w, h, I_RS, 0.72f * w, stickY);
            setDiamond(w, h, 0.76f * w, bottomY, off);
        } else {
            // 横屏也改成和竖屏一样的"左右两列、每列上下叠放"：
            //   左列：十字键（上）+ 左摇杆（下）
            //   右列：右摇杆（上）+ ABXY（下）
            //
            // 上一版我说"横屏 1080 高，上下叠放会重叠" —— 这个结论是错的。
            // 它成立的前提是旧布局那套横向比例（十字键 0.18w、左摇杆 0.36w、
            // 右摇杆 0.80w、ABXY 0.58w，四个各占一格）。改成两列之后，
            // 垂直方向只需要 topGuard + 十字键判定圈 + 列间距 + ABXY 半高，
            // 1080 高的横屏算下来约 790px，放得下，不用缩小摇杆。
            // 【0.79h -> 0.745h：整组上移】
            //   横屏只有 1080 高，0.79 那套是按竖屏手感调的，
            //   放到横屏上左摇杆 / ABXY 离底边太近 —— 拇指压到底才能推满，
            //   而且横屏底下常常就是系统手势条 / 虚拟导航栏，容易误触。
            //   上移约 5% 屏高后，拇指自然弯曲就能覆盖，不用把手掌抬起来。
            //
            //   stickY 是 bottomY - colGap，跟着一起上移，
            //   所以十字键 / 右摇杆（上面那排）也同步抬高了。
            float bottomY = Math.min(clampY(w, h, 0.745f * h, mRadius[I_LS]),
                    clampY(w, h, 0.745f * h, off + btnR * 1.25f));

            // 列内上下两个元素的最小中心间距，取两列里更吃紧的那个。
            // 判定倍率和 hitElement() 里的必须一致，否则算少了还是会重叠。
            float colGap = Math.max(
                    mRadius[I_DPAD] * 1.15f + mRadius[I_LS] * 1.35f,   // 左列：十字键 <-> 左摇杆
                    off + mRadius[I_RS] * 1.35f + btnR * 1.25f         // 右列：右摇杆 <-> ABXY 的 Y 键
            ) + Math.min(w, h) * 0.02f;                                // 再留点余量，别刚好相切
            float stickY = bottomY - colGap;
            float minStick = mTopLimit + mRadius[I_DPAD] * 1.15f + Math.min(w, h) * 0.015f;
            if (stickY < minStick) {
                stickY = minStick;
            }
            float sTop = shoulderTop(w, h, stickY, btnR, shoulderGap, editGap);

            setPx(w, h, I_L2, 0.05f * w, sTop);
            // L3 / R3 不再挂在 SELECT / START 下面，改成和竖屏一致：
            //   L3 在 L2 右边、R3 在 R2 左边，跟肩键同一排。
            // 挂在 SEL/STA 下面时，它俩在屏幕中间偏上，和肩键列是斜的，
            // 看不出是一组；放回肩键那一排之后，左边 L1/L2/L3、
            // 右边 R1/R2/R3 各自成组，一眼能认。
            setPx(w, h, I_L3, 0.05f * w + shoulderGap, sTop);
            setPx(w, h, I_L1, 0.05f * w, sTop + shoulderGap);
            setPx(w, h, I_EDIT, 0.05f * w, sTop + shoulderGap + editGap);
            setPx(w, h, I_LAYOUT, 0.05f * w + shoulderGap,
                    sTop + shoulderGap + editGap);
            scale[I_LAYOUT] = 0.9f;
            setPx(w, h, I_PASS, 0.05f * w + shoulderGap,
                    sTop + shoulderGap + editGap * 2f);
            applyPassDefault();
            hidden[I_PASS] = true;
            // 旧版（无轨道）位置，同样先放一次记下来
            float legacySTop = shoulderTop(w, h, stickY, btnR, shoulderGap, editGap, false);
            setPx(w, h, I_L2B, 0.05f * w, legacySTop);
            mLegacyRx[0] = rx[I_L2B];
            mLegacyRy[0] = ry[I_L2B];
            setPx(w, h, I_R2B, 0.95f * w, legacySTop);
            mLegacyRx[1] = rx[I_R2B];
            mLegacyRy[1] = ry[I_R2B];

            // 无轨道版挂在「编」下面（和竖屏一致），不放回 sTop 那一格
            setPx(w, h, I_L2B, 0.05f * w, sTop + shoulderGap + editGap * 2f);
            setPx(w, h, I_R2, 0.95f * w, sTop);
            setPx(w, h, I_R3, 0.95f * w - shoulderGap, sTop);
            setPx(w, h, I_R1, 0.95f * w, sTop + shoulderGap);
            setPx(w, h, I_COLLAPSE, 0.95f * w, sTop + shoulderGap + editGap);
            setPx(w, h, I_R2B, 0.95f * w, sTop + shoulderGap + editGap * 2f);
            // SEL / STA 挪到底部中间（原来在 0.15h 顶部）。
            // 顶部那两条本来是给 L3/R3 让位的，现在 L3/R3 回肩键排了，
            // 顶上就空出来了；SEL/STA 是次要键，放底部中间不挡主操作区。
            setPx(w, h, I_SEL, 0.40f * w, 0.88f * h);
            setPx(w, h, I_STA, 0.60f * w, 0.88f * h);
            // 横屏：HOME 就在 SEL / STA 中间（同一行，x 取正中）。
            // 横屏高度紧张，再往下会顶到屏幕底边，所以不额外下移。
            setPx(w, h, I_HOME, 0.50f * w, 0.88f * h);

            // 两列各自上下叠放；右摇杆和 ABXY 同列（x 相同），
            // 这样右摇杆到 Y 键是纯垂直距离，最省高度。
            // 左列从 0.24w 挪到 0.20w：原来是按两列对称排的，
            // 实际握持时左手更靠边，往左挪一点拇指落点更顺。
            setPx(w, h, I_DPAD, 0.20f * w, stickY);
            setPx(w, h, I_LS, 0.20f * w, bottomY);
            setPx(w, h, I_RS, 0.76f * w, stickY);
            setDiamond(w, h, 0.76f * w, bottomY, off);
        }

        // 已创建的键盘按键：重置到屏幕中间。
        //
        // 【不在这里清空 keyCode】"重置全部"的语义是"把所有东西摆回默认位置"，
        // 不是"把你创建的键都删掉" —— 删除是「常用工具 -> 删除」的事。
        // 真要清空的话，用户辛辛苦苦建的一排键一次误触就没了。
        for (int i = I_KEY0; i < N_KEY_END; i++) {
            if (keyCode[i] != 0) {
                rx[i] = 0.5f;
                ry[i] = 0.45f;
            }
        }
        // 手柄模板：末尾套用「固定显示」的覆盖。
        // 放在最后 —— 必须在所有 setPx 之后，不然覆盖里"删掉/隐藏"
        // 的决定会被后面的摆放又写回来。
        applyFixedInit(ctx, tpl, "pad");
        // 手柄模板上也能预先加几个键盘键（比如常玩的游戏要按的字母）
        applyFixedInit(ctx, tpl, "key");
        floatBallStyle(this);
    }

    /**
     * 肩键那一列的起始 y：想往下移，但整列必须留在游戏区上方。
     *
     * 「编 / 收」挂在 L1 / R1 下面，如果这一列压到游戏区里，就会和右摇杆
     * （判定圈是半径的 1.35 倍）抢触摸 —— 手指明明想推摇杆，却把整个手柄收起来了。
     * 所以矮屏上宁可少下移一点，也要保证这两排不打架。
     */
    private float shoulderTop(int w, int h, float firstGameY, float btnR,
                              float shoulderGap, float editGap) {
        return shoulderTop(w, h, firstGameY, btnR, shoulderGap, editGap, true);
    }

    /**
     * @param triggerHalf true = 按滑轨半高（1.5r）算顶端限制，给带轨道的 L2/R2 用；
     *                    false = 按普通圆按钮半径算，这是**无轨道时代**的算法，
     *                    用来复现旧版位置（「回退旧版位置」要用）。
     */
    private float shoulderTop(int w, int h, float firstGameY, float btnR,
                              float shoulderGap, float editGap, boolean triggerHalf) {
        float margin = Math.min(w, h) * 0.015f;
        float wanted = h * (h > w ? 0.155f : 0.16f);
        // sTop 是 L2 / R2 的**中心**，而它俩现在是竖直滑轨（高 = 3 个按钮半径），
        // 所以顶端比中心还要再高 1.5 个半径。按普通的 btnR 算 minTop 的话，
        // 轨道上半截会伸进状态栏 / 挖孔那一条，滑块也点不全。
        float half = triggerHalf ? btnR * TRACK_H_RATIO / 2f : btnR;
        float minTop = mTopLimit + half + margin;
        float clearance = mRadius[I_LS] + btnR * 0.85f + shoulderGap + editGap + margin * 2f;
        float maxTop = firstGameY - clearance;
        if (maxTop < minTop) {
            maxTop = minTop;
        }
        return Math.max(minTop, Math.min(wanted, maxTop));
    }

    /**
     * 写入一个元素的中心坐标。
     *
     * 横竖都做收边：既不会顶出屏幕，也不会越过 bottomLimit 钻到编辑面板底下。
     * 顺序上先夹 y 再夹 x，这样矮屏（比如 16:9 竖屏）上按键会自动往上让，
     * 不会出现"按钮画在面板下面、编辑时点不到"的情况。
     */
    /**
     * ABXY 菱形整体摆放：先夹住中心，再按固定像素偏移放四个键。
     *
     * 不能对四个键各自收边 —— 矮屏上 A 会被面板顶上去、B/X 不会，
     * 菱形就被压扁了，A 和 B 的判定圈还会叠在一起。
     *
     * @param off 中心到四个键的距离（像素）
     */
    private void setDiamond(int w, int h, float cxPx, float cyPx, float off) {
        float r = mRadius[I_A] > 0f ? mRadius[I_A] : Math.min(w, h) * 0.058f;
        float margin = Math.min(w, h) * 0.015f;
        float span = off + r;
        float lo = mTopLimit + span + margin;
        float hi = mBottomLimit - span - margin;
        if (hi < lo) {
            hi = lo;
        }
        float cy = Math.max(lo, Math.min(cyPx, hi));
        float x = Math.max(off + r + margin, Math.min(cxPx, w - off - r - margin));
        rx[I_A] = x / w;
        ry[I_A] = (cy + off) / h;
        // B 和 X 对调（默认布局）：现在 B 在右、X 在左。
        // 只是换这两个索引写入的坐标，按键的身份（发给游戏的 BTN_B / BTN_X）
        // 完全不变 —— 标签仍然跟着索引走，所以屏幕上 B 就显示在右边。
        rx[I_B] = (x + off) / w;
        ry[I_B] = cy / h;
        rx[I_Y] = x / w;
        ry[I_Y] = (cy - off) / h;
        rx[I_X] = (x - off) / w;
        ry[I_X] = cy / h;

        float d = off * 1.8f;
        rx[I_Z] = (x - d) / w;
        ry[I_Z] = (cy - off) / h;

        float zcX = x - d;
        float zcY = cy + off;
        float need = r * 2.5f;
        if (!portrait && rx[I_STA] > 0f
                && Math.hypot(zcX - rx[I_STA] * w, zcY - ry[I_STA] * h) < need) {
            zcX = x - d - off * 1.3f;
            zcY = cy - off;
        }
        rx[I_C] = zcX / w;
        ry[I_C] = zcY / h;
    }

    /** 只算纵向收边后的中心 y，不写任何数组。给需要"先定底部再往上排"的场景用。 */
    private float clampY(int w, int h, float py, float r) {
        float margin = Math.min(w, h) * 0.015f;
        float lo = mTopLimit + r + margin;
        float hi = mBottomLimit - r - margin;
        if (hi < lo) {
            hi = lo;
        }
        return Math.max(lo, Math.min(py, hi));
    }

    private void setPx(int w, int h, int i, float px, float py) {
        float r = mRadius[i] > 0f ? mRadius[i] : Math.min(w, h) * 0.058f;
        // L2 / R2 是竖直滑轨，纵向占位是轨道半高（1.5r），不是圆的半径。
        // 这个局部变量**不能叫 ry** —— 那会和字段 ry[] 同名，
        // 方法末尾的 ry[i] = py / h 就会被解析成"给 float 当下标用"，
        // 直接编译不过：'float' is not an array type。
        float halfY = isTrigger(i) ? r * TRACK_H_RATIO / 2f : r;
        float margin = Math.min(w, h) * 0.015f;
        float lo = mTopLimit + halfY + margin;
        float hi = mBottomLimit - halfY - margin;
        if (hi < lo) {
            hi = lo;
        }
        if (py < lo) py = lo;
        if (py > hi) py = hi;
        // 横向要单独算边距，不能拿纵向的 lo 来夹 x。
        // 原来这里写的是 px < lo，而 lo 里含 mTopLimit（顶部保护区高度），
        // 竖屏下 mTopLimit 有 84px，结果 x=81 的肩键被硬推到 x=170，
        // 设计上想贴边的按钮全跑到离边 1.5 个按钮直径的地方去了。
        // 现在 x 只按"半径 + 余量"收边，贴边就是真的贴边。
        float xlo = r + margin;
        if (xlo > w / 2f) xlo = w / 2f;
        if (px < xlo) px = xlo;
        if (px > w - xlo) px = w - xlo;
        rx[i] = px / w;
        ry[i] = py / h;
    }

    // ------------------------------------------------------------------
    // 键盘模式：全键盘默认布局
    // ------------------------------------------------------------------

    /**
     * 60% 键盘的行定义。写名字而不是 usage —— 可读，改起来也直观。
     *
     * 不用完整 104 键：手机上放不下，塞满了每个键才十几 dp，点不准。
     * 60% 布局（约 63 键）覆盖游戏用得到的全部键：
     * 字母、数字、符号、F 区走编辑列表另外加，方向键在右下角。
     */
    private static final String[][] KB_ROWS = {
            {"Esc", "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12"},
            {"`", "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "-", "=", "退格"},
            {"Tab", "Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P", "[", "]", "\\"},
            {"Caps", "A", "S", "D", "F", "G", "H", "J", "K", "L", ";", "'", "回车"},
            {"Shift", "Z", "X", "C", "V", "B", "N", "M", ",", ".", "/", "RShift"},
            // RWin 紧跟普通 Win：它是 Win 的右手版本，摆在一起才找得到。
            // 平时用不到，所以默认隐藏（见 resetKeyboard 末尾）。
            // RWin 挪到右侧修饰键组，紧挨 Menu 左边：
            // 它和 RAlt / RCtrl 是同一侧的键，摆在 Win 旁边反而找不着。
            {"Ctrl", "Win", "Alt", "空格", "RAlt", "RWin", "Menu", "RCtrl"}
    };

    /**
     * 修饰键行的宽度权重（单位 = 格）。
     *
     * 之前这行是 6 个键均分整行，结果 Ctrl/Win 各占 2.3 格，
     * 又宽又空；空格反而和普通键差不多宽，一点都不像键盘。
     * 按真实键盘给权重：Ctrl/Win/Alt 1.5 格，空格 6 格。
     * 合计 14 格，正好和主区其他行对齐。
     */
    // 总权重必须 = 14（主区列数），整行才刚好铺满。
    // 加了 RWin 就从空格键匀出 1 格，Ctrl / Win / Alt / RWin 各 1.4。
    // 总和必须 = 14（主区列数），整行刚好铺满。
    // 8 个键：Ctrl / Win / RWin / Alt / 空格 / RAlt / Menu / RCtrl
    private static final float[] KB_ROW_MOD_W = {1.3f, 1.3f, 1.3f, 4.1f, 1.5f, 1.5f, 1.5f, 1.5f};

    /** 哪一行走自定义权重（上面那个数组）。 */
    private static final int KB_MOD_ROW = 5;

    /**
     * 编辑 / 导航区：3 列，行和主区对齐。
     * null = 该格空着（方向键上下那两块）。
     */
    private static final String[][] KB_NAV = {
            {null, null, null},
            {"Insert", "Home", "PgUp"},
            {"Delete", "End", "PgDn"},
            {null, null, null},
            {null, "↑", null},
            {"←", "↓", "→"}
    };

    /**
     * 小键盘：4 列 × 5 行，顶部和主区第 0 行对齐。
     * 17 个键，放在右侧原来空着的那块。
     */
    private static final String[][] KB_NUMPAD = {
            {"NumLk", "Num/", "Num*", "Num-"},
            {"Num7", "Num8", "Num9", "Num+"},
            {"Num4", "Num5", "Num6", "NumEnt"},
            {"Num1", "Num2", "Num3", null},
            {"Num0", "Num.", null, null}
    };

    /**
     * 生成全键盘布局。
     *
     * 键位按**行均分**排，不按等宽 —— 各行键数不同（14/14/13/12/6），
     * 等宽的话短行右边会空一大截，看着像没排满。
     *
     * 键做小一点（scale 0.48）：63 个键铺满一行时，
     * 默认按钮大小会互相重叠，根本点不到。
     */
    /**
     * 放一个键盘键，成功返回下一个槽位；名字没对上就原样返回（跳过）。
     *
     * @param wMul 半宽倍率，见 widthMul 字段的说明
     */
    private int placeK(int slot, String name, float cx, float cy,
                       float kScale, float wMul, int w, int h) {
        int u = usageOfName(name);
        if (u < 0) {
            return slot;                    // 名字没对上就跳过，别塞个坏键
        }
        keyCode[slot] = u;
        rx[slot] = cx / w;
        ry[slot] = cy / h;
        scale[slot] = kScale;
        shape[slot] = SHAPE_RECT;
        // 竖屏键密（14 键一行、格宽只有 49px），按 1.19 填满格子会完全无缝，
        // 手指按下去分不清是哪一个。竖屏整体收 6% 留出可见的缝。
        float shrink = portrait ? 0.94f : 1f;
        widthMul[slot] = wMul * shrink;
        heightMul[slot] = DEF_RECT_H * shrink;
        textScale[slot] = textScaleFor(name, kScale);
        alpha[slot] = 1f;
        hidden[slot] = false;
        return slot + 1;
    }

    /**
     * 字号按名字长短自适应。
     *
     * 之前所有键同一个字号，"Insert""Delete""NumEnt" 这种长名字直接顶出键外，
     * 而 "Q" 又显得空。现在按"显示宽度"分档：中文按 2 格算（比 ASCII 宽一倍）。
     *
     * 分档系数是照"名字宽度 × 0.55 × 字号 < 键宽"反推的，
     * 长名字压到 0.52 倍才装得下（Insert 6 格宽）。
     */
    private static float textScaleFor(String name, float kScale) {
        float base = kScale < 0.6f ? 0.70f : 0.90f;
        int wid = 0;
        for (int i = 0; i < name.length(); i++) {
            wid += name.charAt(i) > 0x2000 ? 2 : 1;
        }
        // 按"键能装下多宽的字"反推，而不是拍死档位。
        //
        // 档位版在竖屏会溢出：竖屏格宽只有 49px，Insert 六个字符，
        // 按档位给 0.52 倍 -> 字号 22px -> 文字宽 71px，比键还宽。
        // 改成按 kScale（键的缩放比）算：键越窄，字跟着越小。
        // 系数 3.8 是照"字宽 ≈ 0.55 × 字号 × 字符数 ≤ 键宽 × 0.9"反推的。
        float fit = 3.8f * kScale / wid;
        float ts = Math.min(base, fit);
        if (ts < 0.20f) ts = 0.20f;
        return ts;
    }

    /**
     * 空白模板：只留界面按钮，其余全部隐藏。
     *
     * 「编」「收」必须留着 —— 不然进了空白布局就再也出不去（进不了编辑、
     * 也收不起悬浮窗）。「模式」也留着并**默认显示**：空白布局下它是唯一
     * 能快速切到别的布局的入口，藏起来等于把用户锁死在这张白纸上。
     *
     * 手柄键不删（keyCode 保持 0 = 空槽位）只是隐藏，
     * 这样用户之后想加手柄键，在「隐藏按钮」里放出来就行。
     */
    private void resetBlank(int w, int h, boolean portrait,
                            float bottomLimit, float topLimit) {
        mBottomLimit = bottomLimit > 0f ? bottomLimit : h;
        mTopLimit = topLimit > 0f ? topLimit : 0f;
        float m = Math.min(w, h);
        float btnR = m * (portrait ? 0.065f : 0.058f);
        for (int i = 0; i < N; i++) {
            if (mRadius[i] <= 0f) {
                mRadius[i] = btnR;
            }
        }
        // 【改成删除，不是隐藏】理由同键盘模板：
        //   隐藏的话编辑模式下会浮着一圈半透明手柄键，像没清干净；
        //   「隐藏按钮」列表里还会挤满"看不见的手柄键"。
        //   空白模板就该是"真的什么都没有"。
        for (int i = 0; i < N; i++) {
            if (isUiButton(i)) {
                continue;
            }
            padType[i] = 0;      // 手柄元素删掉（含副本槽位）
            keyCode[i] = 0;      // 键盘槽位清空
            hidden[i] = false;
            rx[i] = 0.5f;
            ry[i] = 0.45f;
        }
        layoutUiButtons(w, h, portrait, /*showLayoutBtn=*/ true);
    }

    /**
     * 摆鼠标布局的三个元素：触摸板（大矩形）+ 左键 + 右键。
     *
     * 【位置：为什么触摸板在下半屏、左右键在上】
     *   和笔记本一致 —— 拇指在下方拖触摸板，食指在上方点左右键。
     *   悬浮窗通常贴着屏幕边，上半部分留给游戏画面。
     *
     * 【halfW / halfH 由 radius × 倍率算出】
     *   矩形没有独立的宽高字段，只能靠 mRadius × widthMul / heightMul。
     *   所以先定想要的半宽半高，再反解出倍率，别直接写倍率的数
     *   —— 那样横竖屏比例一变就变形。
     */
    private void resetMouse(int w, int h, boolean portrait) {
        // ---- 触摸板 ----
        padType[I_MOUSE_PAD] = typeOfElem(I_MOUSE_PAD);
        hidden[I_MOUSE_PAD] = false;
        shape[I_MOUSE_PAD] = SHAPE_RECT;
        // 长方形：形状倍率定死成 长3.87 宽2.30（比例约 1.68:1）。
        //
        // 不再各自按 w、h 取比例 —— 那样窄屏上算出来接近正方形，
        // 看着是个大方块，而且形状栏里的数字随屏幕变，对不上。
        //
        // 反过来推半径：先按屏宽定一个期望半宽，半径 = 半宽 / 3.87；
        // 再算出的半高若超过屏幕能给的，就按半高反推半径。
        float wantHW = w * (portrait ? 0.42f : 0.32f);
        float maxHH = h * (portrait ? 0.12f : 0.20f);
        float padR = wantHW / MOUSE_PAD_W_MUL;
        if (padR * MOUSE_PAD_H_MUL > maxHH) {
            padR = maxHH / MOUSE_PAD_H_MUL;
        }
        mRadius[I_MOUSE_PAD] = padR;
        widthMul[I_MOUSE_PAD] = MOUSE_PAD_W_MUL;
        heightMul[I_MOUSE_PAD] = MOUSE_PAD_H_MUL;
        rx[I_MOUSE_PAD] = 0.5f;
        ry[I_MOUSE_PAD] = portrait ? 0.66f : 0.62f;
        customName[I_MOUSE_PAD] = "触摸板";
        showLabel[I_MOUSE_PAD] = true;
        alpha[I_MOUSE_PAD] = MOUSE_PAD_ALPHA;

        // ---- 左键 / 右键 ----
        float btnR = Math.min(w, h) * 0.075f;
        int[] btns = {I_MOUSE_L, I_MOUSE_R};
        float[] bx = {0.26f, 0.74f};
        String[] bn = {"左键", "右键"};
        for (int k = 0; k < btns.length; k++) {
            int i = btns[k];
            padType[i] = typeOfElem(i);
            hidden[i] = false;
            shape[i] = SHAPE_CIRCLE;
            mRadius[i] = btnR;
            widthMul[i] = 1f;
            heightMul[i] = 1f;
            rx[i] = bx[k];
            ry[i] = portrait ? 0.34f : 0.36f;
            customName[i] = bn[k];
            showLabel[i] = true;
        }

        // ---- 中键 / 滚轮上 / 滚轮下 ----
        // 排在左右键下面一行，中键居中，两个滚轮分列两侧。
        // 竖着排一列：滚轮上 / 中键 / 滚轮下。
        // 中键那一行和左右键齐平（ry 与左右键相同），滚轮分列它上下 ——
        // 滚一下、按一下的动线是竖直的，横排会误触。
        int[] extra = {I_MOUSE_WU, I_MOUSE_M, I_MOUSE_WD};
        String[] en = {"滚轮上", "中键", "滚轮下"};
        float[] eyP = {0.24f, 0.34f, 0.44f};
        float[] eyL = {0.26f, 0.38f, 0.50f};
        for (int k = 0; k < extra.length; k++) {
            int i = extra[k];
            padType[i] = typeOfElem(i);
            hidden[i] = false;
            shape[i] = SHAPE_CIRCLE;
            mRadius[i] = btnR * 0.8f;
            widthMul[i] = 1f;
            heightMul[i] = 1f;
            rx[i] = 0.5f;
            ry[i] = portrait ? eyP[k] : eyL[k];
            customName[i] = en[k];
            showLabel[i] = true;
        }
    }

    /**
     * 往一个空的鼠标槽位里建一个元素。
     *
     * 六个槽位（触摸板/左/右/中/滚轮上/滚轮下）是固定的，
     * 建的时候找第一个空的填进去 —— 所以同一个种类只能有一个，
     * 建过了再建会返回 -1，提示先删。
     */
    public int addMouse(int which, float r) {
        int i = allocMouseSlot();
        if (i < 0) return -1;
        resetMouseSlot(i, which);
        if (r > 0) mRadius[i] = r;
        return i;
    }

    /** 把某个鼠标槽位填成指定种类（建 / 模板重置都走这里）。 */
    public void resetMouseSlot(int i, int which) {
        if (!isMouseSlot(i)) return;
        if (which < I_MOUSE_PAD || which > I_MOUSE_WD) return;
        padType[i] = typeOfElem(which);
        hidden[i] = false;
        shape[i] = (which == I_MOUSE_PAD) ? SHAPE_RECT : SHAPE_CIRCLE;
        scale[i] = 1f;
        alpha[i] = (which == I_MOUSE_PAD) ? MOUSE_PAD_ALPHA : 1f;
        textScale[i] = 1f;
        widthMul[i] = 1f;
        heightMul[i] = 1f;
        if (which == I_MOUSE_PAD) {
            widthMul[i] = 1.6f;
            heightMul[i] = 0.7f;
        }
        showLabel[i] = true;
        customName[i] = mouseNameOf(which);
    }

    /** 删掉一个鼠标元素，槽位回收（padType 归 0）。 */
    public void removeMouse(int i) {
        if (!isMouseSlot(i)) return;
        padType[i] = 0;
        hidden[i] = false;
    }

    /**
     * 摆「编」「收」「模式」三个界面按钮。
     * 编/模式在左上，收在右上，间距 2×按钮半径保证不重叠。
     * 「布」的默认可见性规则：
     *   系统默认手柄    -> 隐藏（默认布局不换，按钮放出来只挡视线）
     *   系统默认键盘    -> 显示（键盘布局里它是切回手柄的唯一入口）
     *   用户新建的三种模板（空白 / 手柄 / 键盘）-> **都显示**
     *     （建了新布局就是为了换，藏起来反而找不到）
     *
     * @param showLayoutBtn 新建布局 / 键盘布局传 true，系统默认手柄传 false
     */
    private void layoutUiButtons(int w, int h, boolean portrait, boolean showLayoutBtn) {
        float uex = 0.06f * w;
        float uy = mTopLimit + Math.min(w, h) * 0.045f;
        float ugap = Math.min(w, h) * 0.065f * 2.0f;
        setPx(w, h, I_EDIT, uex, uy);
        setPx(w, h, I_COLLAPSE, 0.94f * w, uy);
        // 「布」在「编」右边错开一格（就是原来「模式」那个位置）。
        // 界面按钮现在只有三个：编 / 布 / 收。
        setPx(w, h, I_LAYOUT, uex + ugap, uy);
        scale[I_LAYOUT] = 0.9f;
        // 空白布局里默认放出来：白纸上没有任何游戏键，
        // 藏起「布」的话就没法切回别的布局，等于把自己锁死
        hidden[I_LAYOUT] = !showLayoutBtn;
        setPx(w, h, I_PASS, uex + ugap * 2f, uy);
        applyPassDefault();
        hidden[I_PASS] = true;
    }

    private void resetKeyboard(int w, int h, boolean portrait) {
        float left = w * 0.02f;
        float right = w * 0.98f;
        float usableW = right - left;
        float top = portrait ? h * 0.42f : h * 0.14f;
        float bottom = h * 0.96f;
        float usableH = bottom - top;

        int rowCount = KB_ROWS.length;

        float mainCols = 14f;
        float navCols = 3f;
        float numCols = 4f;

        // 【竖屏：导航区 + 小键盘拆到主区下方，主区独占屏宽】
        //
        // 原来三段并排 21 格，竖屏格宽只有 49px，键也 49px —— 太小按不准。
        // 拆开之后主区独占 14 格，格宽 74px、键直径约 62px，大了约一半。
        //
        // 横屏保持三段并排：横屏本来就宽，21 格也够大，拆开反而浪费横向空间。
        boolean splitSide = portrait;

        float kw;
        float rowsUsed;
        if (splitSide) {
            kw = usableW / mainCols;
            // 附加区：导航和小键盘并排放在主区下方，行数取两者较大值。
            // 导航有 2 行是全空的（方向键上下那两块），跳过不算，
            // 否则会白占两行、把行距挤小。
            int navUsed = 0;
            for (String[] row : KB_NAV) {
                for (String s : row) {
                    if (s != null) {
                        navUsed++;
                        break;
                    }
                }
            }
            rowsUsed = rowCount + Math.max(navUsed, KB_NUMPAD.length);
        } else {
            kw = usableW / (mainCols + navCols + numCols);
            rowsUsed = rowCount;
        }

        float baseR = Math.min(w, h) * 0.065f;      // 要和 GamepadView 的 mBtnR 一致
        float kScale = (kw * 0.42f) / baseR;
        if (kScale > 1.0f) kScale = 1.0f;
        if (kScale < 0.20f) kScale = 0.20f;

        // 【键之间要留缝】
        // 原来写死 1.19，而 kScale 又是按格子宽反推的，
        // 两者一乘正好 100% 填满 —— 相邻键**完全贴死、没有缝**。
        // 竖屏格宽只有 49px、键也是 49px，按下去分不清是哪一个。
        // 竖屏更需要缝，给得更保守。
        float wMulStd = portrait ? 1.00f : 1.10f;

        // 行距 1.30 倍键直径。
        //
        // 2.2 倍（上一版）键之间空得太厉害，整个键盘下面剩一大片没用上；
        // 1.0 倍又粘在一起、按下去分不清是哪一个。
        // 1.3 倍是"看得出是分开的，又不至于散架"。
        float keyD = baseR * kScale * 2f;
        float rowH = keyD * 1.30f;
        if (rowH * rowsUsed > usableH) {
            rowH = usableH / rowsUsed;              // 空间不够就压缩
        }
        if (rowH < keyD * 1.12f) {
            // 压缩到比"刚好不叠"还小 —— 说明是键太大而不是行距太大，
            // 这时该缩键，不能硬抬 rowH（抬了就会溢出屏幕底部）。
            float room = usableH / rowsUsed;
            if (room < keyD * 1.12f) {
                keyD = room / 1.12f;
                kScale = keyD / (baseR * 2f);
                if (kScale < 0.20f) {
                    kScale = 0.20f;
                    keyD = baseR * kScale * 2f;
                }
                rowH = room;
            } else {
                rowH = keyD * 1.12f;                // 至少别让相邻行叠上
            }
        }
        float kbH = rowH * rowsUsed;
        if (kbH > usableH) {
            rowH = usableH / rowsUsed;
            kbH = usableH;
        }
        float rowTop = portrait ? top + (usableH - kbH) / 2f : bottom - kbH;
        // 附加区（导航 + 小键盘）的起始 y：紧贴主区最后一行下面
        float extraTop = rowTop + rowH * rowCount;

        int slot = I_KEY0;

        // ---- 主区 ----
        float mainW = mainCols * kw;
        for (int r = 0; r < rowCount; r++) {
            String[] row = KB_ROWS[r];
            float y = rowTop + rowH * (r + 0.5f);
            if (r == KB_MOD_ROW) {
                // 修饰键行：按权重排，不再 6 个均分整行
                float acc = 0f;
                for (int c = 0; c < row.length && slot < N_KEY_END; c++) {
                    float wgt = KB_ROW_MOD_W[c];
                    slot = placeK(slot, row[c], left + (acc + wgt / 2f) * kw, y,
                            kScale, wgt * wMulStd, w, h);
                    acc += wgt;
                }
            } else {
                float cell = mainW / row.length;
                for (int c = 0; c < row.length && slot < N_KEY_END; c++) {
                    slot = placeK(slot, row[c], left + cell * (c + 0.5f), y,
                            kScale, (cell / kw) * wMulStd, w, h);
                }
            }
        }

        // ---- 编辑 / 导航区（3 格）----
        // 竖屏：放到主区下方，小键盘左侧；横屏：仍在主区右边。
        float numLeft = splitSide ? (right - numCols * kw)
                : (left + mainW + navCols * kw);
        float navLeft = splitSide ? (numLeft - navCols * kw) : (left + mainW);
        int navRow = 0;   // 竖屏下跳过全空行，用这个游标紧凑排列
        for (int r = 0; r < KB_NAV.length && slot < N_KEY_END; r++) {
            boolean any = false;
            for (String s : KB_NAV[r]) {
                if (s != null) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                continue;
            }
            float y = splitSide ? extraTop + rowH * (navRow + 0.5f)
                    : rowTop + rowH * (r + 0.5f);
            navRow++;
            for (int c = 0; c < KB_NAV[r].length && slot < N_KEY_END; c++) {
                if (KB_NAV[r][c] == null) {
                    continue;
                }
                slot = placeK(slot, KB_NAV[r][c], navLeft + kw * (c + 0.5f), y,
                        kScale, wMulStd, w, h);
            }
        }

        // ---- 小键盘（4 格）----
        // 竖屏：在主区下方、导航右侧（贴右边缘，和真实键盘一致）；
        // 横屏：在导航右边。
        for (int r = 0; r < KB_NUMPAD.length && slot < N_KEY_END; r++) {
            float y = splitSide ? extraTop + rowH * (r + 0.5f)
                    : rowTop + rowH * (r + 0.5f);
            for (int c = 0; c < KB_NUMPAD[r].length && slot < N_KEY_END; c++) {
                if (KB_NUMPAD[r][c] == null) {
                    continue;
                }
                slot = placeK(slot, KB_NUMPAD[r][c], numLeft + kw * (c + 0.5f), y,
                        kScale, wMulStd, w, h);
            }
        }

        // 剩下的槽位清空：
        // 「重置全部」在键盘模式下的语义是"摆回默认全键盘"，
        // 不清的话用户删掉的键回不来、手动加的键又会残留。
        for (int i = slot; i < N_KEY_END; i++) {
            keyCode[i] = 0;
            // 三个都要清。之前只清了 widthMul / textScale，漏了 heightMul 和 shape
            // —— 删掉再建的键会**残留上一次的形状**（比如上一个是宽键，
            // 新建的普通键也跟着变宽）。
            widthMul[i] = 1f;
            heightMul[i] = 1f;
            stickFixed[i] = true;
            stickRange[i] = DEF_STICK_RANGE;
            shape[i] = SHAPE_CIRCLE;
            textScale[i] = 1f;
        }

        // 【键盘模式也要摆好「编」「收」「模式」】
        // 原来 reset() 一进 keyboard 分支就 return，这三个按钮的 rx/ry 全是 0，
        // 于是全挤在屏幕左上角 (0,0)，既点不到也切不回来 —— 等于进了键盘模式就出不去。
        // 这里补上：编/模式 在左上，收 在右上。
        // 间距必须 > 两个半径（直径）才不重叠：编和模式都是 0.85*btnR，
        // 所以取 2.0*0.85*btnR ≈ 1.7*btnR，再留 20% 余量 -> 2.0*btnR。
        float uex = 0.06f * w;
        float uy = mTopLimit + Math.min(w, h) * 0.045f;
        float ugap = Math.min(w, h) * 0.065f * 2.0f;
        setPx(w, h, I_EDIT, uex, uy);
        setPx(w, h, I_COLLAPSE, 0.94f * w, uy);

        // 键盘模式下「布」默认显示：它是切回手柄布局的唯一入口，
        // 藏起来就出不去了（键盘布局里没有「模式」按钮了）。
        setPx(w, h, I_LAYOUT, uex + ugap, uy);
        scale[I_LAYOUT] = 0.9f;
        hidden[I_LAYOUT] = false;
        setPx(w, h, I_PASS, uex + ugap * 2f, uy);
        applyPassDefault();
        hidden[I_PASS] = true;

        // 【RWin 默认显示】
        //   藏起来反而更奇怪：想用时得先去「隐藏按钮」里翻一遍，
        //   而它和 Menu 一样属于"平时用不到、用到时找不到更麻烦"的键。
    }

    // ------------------------------------------------------------------
    // 存取
    // ------------------------------------------------------------------

    /** 记住当前布局 id（切换布局 / 收起时调，下次打开直接还原）。 */
    public static void saveCurrentLayoutId(Context ctx, int layoutId) {
        if (ctx == null) {
            return;
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(PREF_CUR_LAYOUT, layoutId).apply();
    }

    /**
     * 上次用的布局 id。
     *
     * 取回来会**校验这个布局还在不在** ——
     * 用户可能把它删了，或者存档被清过。
     * 直接返回一个不存在的 id，load() 内部虽然会兜底回默认手柄，
     * 但那样"记住"就成了假的：界面显示默认，下次还是记住这个死 id。
     * 所以这里就先退回去，让上层拿到一个有效 id。
     */
    public static int loadCurrentLayoutId(Context ctx) {
        int def = LAYOUT_DEFAULT_PAD;
        if (ctx == null) {
            return def;
        }
        int id = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(PREF_CUR_LAYOUT, def);
        if (id != LAYOUT_DEFAULT_PAD && id != LAYOUT_DEFAULT_KB
                && findMeta(ctx, id) == null) {
            // 布局没了：清掉记录并返回默认
            saveCurrentLayoutId(ctx, def);
            return def;
        }
        return id;
    }

    public static PadLayout load(Context ctx, int w, int h, boolean portrait) {
        return load(ctx, w, h, portrait, 0f, 0f);
    }

    public static PadLayout load(Context ctx, int w, int h, boolean portrait,
                                 float bottomLimit, float topLimit) {
        return load(ctx, w, h, portrait, false, bottomLimit, topLimit);
    }

    public static PadLayout load(Context ctx, int w, int h, boolean portrait,
                                 boolean keyboard, float bottomLimit, float topLimit) {
        return load(ctx, w, h, portrait,
                keyboard ? LAYOUT_DEFAULT_KB : LAYOUT_DEFAULT_PAD,
                bottomLimit, topLimit);
    }

    /**
     * 按布局 id 加载。模板类型由布局自己的 meta 决定，
     * 不再靠调用方传 keyboard —— 用户建的"空白"布局也得能正确还原。
     */
    public static PadLayout load(Context ctx, int w, int h, boolean portrait,
                                 int layoutId, float bottomLimit, float topLimit) {
        LayoutMeta meta = findMeta(ctx, layoutId);
        if (meta == null) {
            // 布局被删了 / 存档坏了：退回默认手柄，别加载不出来
            layoutId = LAYOUT_DEFAULT_PAD;
            meta = findMeta(ctx, layoutId);
        }
        boolean keyboard = meta != null && meta.tpl == TPL_KEYBOARD;
        boolean blank = meta != null && meta.tpl == TPL_BLANK;
        boolean mouse = meta != null && meta.tpl == TPL_MOUSE;
        PadLayout l = new PadLayout();
        l.layoutId = layoutId;
        l.layoutName = meta != null ? meta.name : "";
        // 传 ctx：末尾会套用「固定显示」里存的初始状态覆盖。
        // 新建布局（还没写过摆位存档）走的正是这条路。
        l.reset(w, h, portrait, bottomLimit, topLimit,
                keyboard ? TPL_KEYBOARD
                        : (blank ? TPL_BLANK : (mouse ? TPL_MOUSE : TPL_PAD)), ctx);
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String s = p.getString(variantKey(portrait, layoutId, keyboard), null);
            if (s == null) {
                // 【用户新建的布局，第一次加载时把「布」放出来】
                //
                //   createLayout() 只写 meta，不写摆位存档，所以新建后
                //   切过去走的就是这条 s == null 路径，拿到的是模板默认：
                //   手柄模板 -> 「布」隐藏。可用户建了新布局就是为了换，
                //   藏起来等于找不到入口。
                //
                //   三种模板（空白 / 手柄 / 键盘）一律显示。
                //   save 一次把这个初值固定下来 —— 之后用户手动隐藏，
                //   存档里就有 h=true 了，不会再被这里覆盖。
                if (layoutId >= LAYOUT_USER_START) {
                    l.hidden[I_LAYOUT] = false;
                }
                applyGlobalCollapse(ctx, l, portrait);
                if (layoutId == LAYOUT_FLOAT) {
                    initFloatBall(l);
                    l.rx[I_FLOAT] = l.rx[I_COLLAPSE];
                    l.ry[I_FLOAT] = l.ry[I_COLLAPSE];
                    l.save(ctx);
                } else if (layoutId >= LAYOUT_USER_START) {
                    l.save(ctx);
                }
                return l;
            }
            JSONObject o = new JSONObject(s);
            // 版本对不上说明默认布局改过了，直接用新的默认，别拿老存档盖回去。
            // 只比当前变体那一个版本号，改横屏不会清掉竖屏的手动摆放。
            if (o.optInt("v", 0) != versionFor(portrait, layoutId, keyboard)) {
                applyGlobalCollapse(ctx, l, portrait);
                return l;
            }
            // 【存档迁移：老存档没有 sch 字段，按区间把下标整体后移】
            //   见 MIG_* 的说明。当前区间是 -1（不迁移），
            //   只会补写 sch 字段，不动任何已存的位置。
            int sch = o.optInt("sch", 0);
            if (sch == 0 && MIG_FROM >= 0 && MIG_TO >= MIG_FROM) {
                JSONObject from = o.optJSONObject(String.valueOf(MIG_FROM));
                // 区间起点都没存过 = 这份存档本来就没有要迁移的东西，
                // 别为了"看起来迁移过"而凭空造数据。
                if (from != null) {
                    JSONObject n = new JSONObject(o.toString());
                    for (int i = MIG_TO; i >= MIG_FROM; i--) {
                        JSONObject e = n.optJSONObject(String.valueOf(i));
                        n.remove(String.valueOf(i));
                        if (e != null) {
                            n.put(String.valueOf(i + MIG_SHIFT), e);
                        }
                    }
                    o = n;
                }
            }
            for (int i = 0; i < N; i++) {
                JSONObject e = o.optJSONObject(String.valueOf(i));
                if (e == null) {
                    continue;
                }
                l.rx[i] = (float) e.optDouble("x", l.rx[i]);
                l.ry[i] = (float) e.optDouble("y", l.ry[i]);
                l.scale[i] = clampScale((float) e.optDouble("s", l.scale[i]));
                l.alpha[i] = clampAlpha((float) e.optDouble("a", l.alpha[i]));
                l.hidden[i] = e.optBoolean("h", false);
                // 老存档没有 "k"，optInt 返回 0 = 空槽位，自动兼容。
                l.keyCode[i] = e.optInt("k", 0);
                // 老存档没有 "sp"，默认 0 = 圆，自动兼容。
                l.shape[i] = e.optInt("sp", SHAPE_CIRCLE);
                // 老存档没有 "ts"，默认 1f，自动兼容。
                l.textScale[i] = (float) e.optDouble("ts", 1f);
                l.customName[i] = e.optString("dn", "");
                l.btnColor[i] = e.optInt("bc", 0);
                // 老存档没有 po / pf，optInt 给 0 = 走内置绿 / 红，行为不变
                if (i == I_PASS) {
                    l.passOnColor = e.optInt("po", 0);
                    l.passOffColor = e.optInt("pf", 0);
                }
                l.showLabel[i] = !e.optBoolean("nl", false);
                // 老存档没有 "wm"，默认 1f，自动兼容。
                // clampMul 兜底：值一旦落到区间外，滑条会显示 0% 但键没变，
                // 看着像"调节失灵"。夹回区间就不会有这个错觉。
                // 回退值按形状给：圆形 1f（正圆），矩形 1.19/1.06。
                // 一律回退 1f 的话，矩形键会在 load 路径下缩水、缝隙变大。
                float defW = l.shape[i] == SHAPE_RECT ? DEF_RECT_W : 1f;
                float defH = l.shape[i] == SHAPE_RECT ? DEF_RECT_H : 1f;
                l.widthMul[i] = clampMul((float) e.optDouble("wm", defW));
                l.heightMul[i] = clampMul((float) e.optDouble("hm", defH));
                // 【老存档兼容】没有 "pt" 字段时按"固定区 = 自身"回填。
                // 老存档里固定元素必然都在（那时还不能删），
                // 副本槽位在那时候也还不存在，所以一律 0。
                int defPt = (i < N_FIXED && !isUiButton(i)) ? typeOfElem(i) : 0;
                l.padType[i] = e.optInt("pt", defPt);
                // 老存档没有 bl 字段：optBoolean 给 false = 空槽位，直接兼容
                // （空白按钮不存名字也不存动作，只需要一个占用标记，
                //   不再读 cn / ca，那些残留数据自动被忽略）
                if (isBlankSlot(i)) {
                    l.blankUsed[i] = e.optBoolean("bl", false);
                }
                // "so" 老存档没有，默认 false = 用户自建的元素，直接兼容
                l.sysOwned[i] = e.optBoolean("so", false);
                // 组合键：名字 + 动作表。老存档没有 cn，读到空串 = 空槽位。
                if (isComboSlot(i)) {
                    // 【optString 可能返回 ""，不能当占用】
                    //   isComboUsed 判的是"非空"，空串正好是未占用，直接兼容。
                    String cn = e.optString("cn", "");
                    l.comboName[i] = cn;
                    l.comboCross[i] = e.optBoolean("cx", false);
                    l.comboDiag[i] = e.optBoolean("dg", true);
                    String ca = e.optString("ca", "");
                    if (!ca.isEmpty()) {
                        String[] parts = ca.split(",");
                        for (int j = 0; j < MAX_COMBO_ACTS && j < parts.length; j++) {
                            String[] kv = parts[j].split(":");
                            // 老存档是 2 列（没有序号），读到 2 段就当序号 0，
                            // 结束动作会退回"按编码找"，不影响老数据。
                            if (kv.length >= 2) {
                                l.setComboAct(i, 0, j,
                                        Integer.parseInt(kv[0]), Integer.parseInt(kv[1]),
                                        kv.length >= 3 ? Integer.parseInt(kv[2]) : 0);
                            }
                        }
                    }
                    // 十字架的三条：下 / 左 / 右（上在 ca 里）
                    JSONArray da = e.optJSONArray("cd");
                    if (da != null) {
                        for (int d = 1; d < COMBO_DIRS && d <= da.length(); d++) {
                            String ds = da.optString(d - 1, "");
                            if (ds.isEmpty()) {
                                continue;
                            }
                            String[] parts = ds.split(",");
                            for (int j = 0; j < MAX_COMBO_ACTS && j < parts.length; j++) {
                                String[] kv = parts[j].split(":");
                                if (kv.length >= 2) {
                                    l.setComboAct(i, d, j,
                                            Integer.parseInt(kv[0]),
                                            Integer.parseInt(kv[1]),
                                            kv.length >= 3 ? Integer.parseInt(kv[2]) : 0);
                                }
                            }
                        }
                    }
                }
                // 老存档没有 sf / sr：optBoolean 默认给 true = 固定，
                // 正好就是老版本的行为，不用迁移。
                if (isStick(i)) {
                    l.stickFixed[i] = e.optBoolean("sf", true);
                    l.stickRange[i] =
                            clampStickRange((float) e.optDouble("sr", DEF_STICK_RANGE));
                }
            }
        } catch (Exception ignored) {
            // 存档损坏就用默认布局，不至于让手柄打不开
        }
        // 【不再清掉"空着"的元素】
        //   空白按钮本来就不装键，不存在"填没填"的问题。
        //   读盘时删掉它，用户刚建的按钮一重启就没了。
        applyGlobalCollapse(ctx, l, portrait);
        // 【显隐只认 layoutId，不看存档】
        //   存档里的 hidden 是上一次存盘时的样子，若布局 id 对不上，
        //   就会出现"切到悬浮窗布局看不见 G"或"手柄布局上多出一颗球"。
        applyFloatVisibility(l);
        // G 的位置恒等于「收」
        l.rx[I_FLOAT] = l.rx[I_COLLAPSE];
        l.ry[I_FLOAT] = l.ry[I_COLLAPSE];
        migrateColorAlpha(l);
        return l;
    }

    /**
     * 老存档迁移：把"颜色里自带的 alpha"搬到滑条上。
     *
     * 【为什么要搬】
     *   以前透明度有两个来源（颜色自带的 + 滑条），画的时候相乘 ——
     *   于是滑条写着 80%、看到的却是 64%，来回拖也对不上。
     *   现在只留滑条这一个来源，颜色一律存成不透明。
     *
     * 【只搬"没动过滑条"的那些】
     *   alpha[i] == 1 说明用户压根没调过透明度，那点透明度就只可能来自颜色。
     *   已经调过滑条的说明用户有明确的意图，不能拿颜色的 alpha 盖掉。
     */
    private static void migrateColorAlpha(PadLayout l) {
        for (int i = 0; i < N; i++) {
            int cc = l.btnColor[i];
            if (cc == 0) {
                continue;
            }
            int ca = (cc >>> 24) & 0xFF;
            if (ca == 0 || ca == 255) {
                continue;                       // 全透明 / 不透明，没什么可搬的
            }
            if (l.alpha[i] < 0.999f) {
                l.btnColor[i] = 0xFF000000 | (cc & 0x00FFFFFF);
                continue;                       // 滑条调过，以滑条为准
            }
            l.alpha[i] = Math.max(MIN_ALPHA, ca / 256f);
            l.btnColor[i] = 0xFF000000 | (cc & 0x00FFFFFF);
        }
        // 穿透那两支同理（按钮只有一个，透明度也只有一个）
        int[] ps = {l.passOnColor, l.passOffColor};
        for (int k = 0; k < ps.length; k++) {
            int cc = ps[k];
            if (cc == 0) {
                continue;
            }
            int ca = (cc >>> 24) & 0xFF;
            if (ca == 0 || ca == 255) {
                continue;
            }
            if (l.alpha[I_PASS] >= 0.999f) {
                l.alpha[I_PASS] = Math.max(MIN_ALPHA, ca / 256f);
            }
        }
        l.passOnColor = (l.passOnColor == 0) ? 0 : (0xFF000000 | (l.passOnColor & 0x00FFFFFF));
        l.passOffColor = (l.passOffColor == 0) ? 0 : (0xFF000000 | (l.passOffColor & 0x00FFFFFF));
    }

    /**
     * 悬浮窗布局的强制外观：只留 G（= 收那个位置）和「编」。
     *
     * 【为什么「编」必须留】
     *   藏了它就再进不去编辑模式，而这个布局存在的意义就是进去调 G ——
     *   等于把自己锁在外面。
     */
    private static void applyFloatLayout(PadLayout l) {
        // 【「布」和「透」在这个布局里都放出来】
        //   「布」是切走的唯一出口：这个布局里没有游戏键，
        //   藏起它就只能靠「常用工具 -> 布局切换」绕一圈。
        //   「透」是全局开关，跟布局无关，这里也该能顺手切。
        l.hidden[I_LAYOUT] = false;
        l.hidden[I_PASS] = false;
        l.hidden[I_EDIT] = false;
        // 【「收」在悬浮窗布局里隐藏】
        //   G 的位置就是「收」的位置，两个同位置都显示会叠在一起 ——
        //   看着像一个按钮，点下去却分不清中的是谁。
        //   这个布局里点 G = 收起（onDown 里映射），功能没丢。
        l.hidden[I_COLLAPSE] = true;
        l.hidden[I_FLOAT] = false;
    }

    /**
     * 悬浮窗布局里那块提示牌的文字。
     *
     * 它是个空白按钮（正方形），作用只是告诉用户"这里改的是收起后的球"，
     * 不然这个布局除了孤零零一颗 G 什么都没有，看不出是干嘛的。
     */
    public static final String FLOAT_HINT_TEXT =
            "本布局修改“G”会同步修改缩小之后的“G”";
    /** 长方形横条：宽高倍率不等就是长方形，字多所以拉得很扁。 */
    private static final float FLOAT_HINT_W = 7.04f;
    private static final float FLOAT_HINT_H = 1.95f;
    /** 认牌子的标记。老存档里是上一版文案，靠前缀认出来，避免重复建。 */
    private static final String FLOAT_HINT_PREFIX = "本布局修改";

    /** 别的布局里 G 一律隐藏：它是悬浮窗布局专属的。 */
    private static void hideFloatBall(PadLayout l) {
        l.hidden[I_FLOAT] = true;
    }

    /**
     * 按 layoutId 决定 G /「收」/「布」/「透」的显隐。
     *
     * 【reset 之后必须再调一次】
     *   floatBallStyle() 为了"别在手柄布局上凭空多一颗球"而把
     *   hidden[I_FLOAT] 设成 true，reset 又把所有 hidden 先清成 false；
     *   两者叠加的结果是：只要在悬浮窗布局里点「重置全部」，
     *   G 就被 reset 设成隐藏 —— 明明在这个布局里它才是主角。
     *
     *   所以显隐不能靠 reset 内部那次设置，必须在 reset 走完之后
     *   按 layoutId 再定一遍。三处 return 都要调。
     */
    public static void applyFloatVisibility(PadLayout l) {
        if (l.layoutId == LAYOUT_FLOAT) {
            applyFloatLayout(l);
            ensureFloatHint(l);
        } else {
            hideFloatBall(l);
        }
    }

    /**
     * 确保悬浮窗布局里有那块提示牌。
     *
     * 【按名字找，找不到才建】
     *   reset() 会把 blankUsed 全清掉（提示牌也是空白按钮），
     *   所以每次 reset 之后都得补回来，不然"重置全部"就没提示了。
     *   已经有一块就不重复建 —— 否则每次 load 都多一块，很快堆满 16 个槽位。
     */
    private static void ensureFloatHint(PadLayout l) {
        // 【按前缀认，不按全等】
        //   文案会改（之前是"本布局修改的是缩小后的悬浮窗"），
        //   写死全等的话改一次文案，老存档就认不出已有那块牌子，
        //   load 一次补一块，16 个槽位很快堆满。
        //   所以找到旧牌子就**就地改文案**，不再新建。
        for (int i = BLANK_START; i < COMBO_START; i++) {
            if (!l.isBlankUsed(i)) {
                continue;
            }
            String cn = (l.customName[i] == null) ? "" : l.customName[i].trim();
            if (!cn.startsWith(FLOAT_HINT_PREFIX)) {
                continue;
            }
            if (FLOAT_HINT_TEXT.equals(cn)) {
                return;                 // 已经是最新的，不用动
            }
            applyFloatHintStyle(l, i);  // 旧文案：更新文字和形状
            return;
        }
        int i = l.addBlank();
        if (i < 0) {
            return;      // 16 个槽位满了，不建就是了
        }
        applyFloatHintStyle(l, i);
        // 屏幕中间：界面按钮都在顶部一排，G 在「收」那个位置（右上），
        // 中间是空的，放这儿不会压到任何东西。
        l.rx[i] = 0.5f;
        l.ry[i] = 0.5f;
    }

    /** 套用提示牌的文字 / 形状 / 大小。 */
    private static void applyFloatHintStyle(PadLayout l, int i) {
        l.customName[i] = FLOAT_HINT_TEXT;
        l.shape[i] = SHAPE_RECT;
        l.widthMul[i] = FLOAT_HINT_W;
        l.heightMul[i] = FLOAT_HINT_H;
        l.scale[i] = 1f;
        l.alpha[i] = 1f;
        l.hidden[i] = false;
        l.textScale[i] = 1f;
        // 【打上"系统自带"标记】
        //   不打的话重置单个会拿空白键的模板默认值（圆形 / 倍率 1）覆盖它，
        //   牌子变成正圆、文字挤成一团 —— 模板里本来就没这块牌子，
        //   拿模板默认值覆盖它是张冠李戴。
        l.sysOwned[i] = true;
    }

    /**
     * 把一个"系统自带"的元素重置回布局给它定的样子。
     *
     * @param dims 勾选了哪几项（RD_POS / RD_SIZE / RD_ALPHA / RD_TEXT / RD_OTHER）
     * @return true = 已处理，调用方不用再走"按模板默认值重置"那条路
     */
    public static boolean resetSysOwned(PadLayout l, int i, boolean[] dims) {
        if (i < 0 || i >= N || !l.sysOwned[i]) {
            return false;
        }
        if (isFloatHintIndex(l, i)) {
            if (dims == null || dims[0]) {          // RD_POS
                l.rx[i] = 0.5f;
                l.ry[i] = 0.5f;
            }
            if (dims == null || dims[1]) {          // RD_SIZE
                l.scale[i] = 1f;
            }
            if (dims == null || dims[2]) {          // RD_ALPHA
                l.alpha[i] = 1f;
            }
            if (dims == null || dims[3]) {          // RD_TEXT
                l.textScale[i] = 1f;
            }
            if (dims == null || dims[4]) {          // RD_OTHER
                l.shape[i] = SHAPE_RECT;
                l.widthMul[i] = FLOAT_HINT_W;
                l.heightMul[i] = FLOAT_HINT_H;
                l.hidden[i] = false;
                l.customName[i] = FLOAT_HINT_TEXT;
            }
            return true;
        }
        // G：显隐由 layoutId 决定，其余维度按它自己的默认样式回退。
        // 【只有勾了"其他信息"才动颜色和名字】
        //   只勾了位置就把配色一起清掉，等于"改个位置把外观也改了"，
        //   和别的按钮的重置口径对不上。
        if (i == I_FLOAT) {
            if (dims == null || dims[0]) {          // RD_POS：位置恒等于「收」
                l.rx[i] = l.rx[I_COLLAPSE];
                l.ry[i] = l.ry[I_COLLAPSE];
            }
            if (dims == null || dims[1]) {          // RD_SIZE
                l.scale[i] = 1f;
            }
            if (dims == null || dims[2]) {          // RD_ALPHA
                // 回到球本来的 75%，不是 100% ——
                // 重置成全不透明的话球会糊掉下面一块画面。
                l.alpha[i] = FLOAT_BALL_ALPHA;
            }
            if (dims == null || dims[3]) {          // RD_TEXT
                l.textScale[i] = 1f;
            }
            if (dims == null || dims[4]) {          // RD_OTHER：配色 / 名字 / 形状
                floatBallStyle(l);
            }
            applyFloatVisibility(l);
            return true;
        }
        return false;
    }

    /** 这块牌子是不是提示牌（按文案前缀认，改过文案也认得出）。 */
    private static boolean isFloatHintIndex(PadLayout l, int i) {
        if (!isBlankSlot(i) || !l.blankUsed[i]) {
            return false;
        }
        String cn = (l.customName[i] == null) ? "" : l.customName[i].trim();
        return cn.startsWith(FLOAT_HINT_PREFIX);
    }

    /**
     * G（悬浮球）的默认样式：蓝底白字「G」。
     *
     * 【为什么不新建槽位】
     *   G 就是 I_COLLAPSE 在悬浮窗布局里的样子 —— 同一个槽位、同一份
     *   全局位置，所以"拖 G 影响收、拖收影响 G"是天然成立的，不用同步。
     *   而 scale / alpha / shape / btnColor / customName 这些是**按布局**
     *   分开存的，所以 G 改成蓝色不会把别的布局里的「收」也染蓝。
     *   加新槽位反而会让所有老存档的下标整体错位。
     */
    private static void initFloatBall(PadLayout l) {
        floatBallStyle(l);
        applyFloatLayout(l);
    }

    /**
     * G 的默认样式：蓝底白字「G」。
     *
     * 【reset 里必须调它，而且要无条件调】
     *   之前 G 是复用 I_COLLAPSE 的，reset 按模板重建界面按钮时
     *   把它的名字和颜色一起清了 —— 于是"点全部重置就变回「收」"。
     *   独立槽位 + 每次 reset 都补默认样式，重置后才是"默认的 G"。
     *
     * 【只设样式，不动显隐】
     *   显隐由 layoutId 决定（见 load 里的 applyFloatLayout / hideFloatBall），
     *   这里套 applyFloatLayout 的话，重置手柄布局会把「收」「布」一起藏了。
     */
    private static void floatBallStyle(PadLayout l) {
        // 【默认就叫 G，不写 customName】
        //   写了 customName 的话，"改名后想改回默认"就得判空再退回，
        //   而 nameOf() 已经兜了 customName 为空 -> "G" 这一层。
        l.customName[I_FLOAT] = "";
        // 【和悬浮球原来的底色一致：#336699 + 75% 不透明度】
        //   之前用 0xFF1565C0（亮蓝），比原来那个深钢蓝扎眼得多。
        //   而且球本来就是半透明的（原来写死成 0xC0336699，alpha = 0xC0），
        //   用不透明色会糊掉下面游戏的画面。
        //
        //   【alpha 放滑条里，不放颜色里】
        //     透明度只有一个来源（见 applyPickedColor）。颜色存成不透明，
        //     0.75 交给 alpha[I_FLOAT] —— 取色器初值是拿 alpha 拼出来的，
        //     这样打开取色器看到的 75% 才和屏幕上一致，往返也不漂。
        l.btnColor[I_FLOAT] = 0xFF336699;
        l.showLabel[I_FLOAT] = true;
        l.scale[I_FLOAT] = 1f;
        l.alpha[I_FLOAT] = FLOAT_BALL_ALPHA;
        l.shape[I_FLOAT] = SHAPE_CIRCLE;
        l.textScale[I_FLOAT] = 1f;
        // 【默认隐藏】
        //   reset() 开头把 hidden 全设成 false，若不在这里显式藏起来，
        //   手柄 / 键盘布局上会凭空多出一颗 G —— 和「收」叠在一起。
        //   真正的显隐由 layoutId 决定（load 末尾的 applyFloatLayout / hideFloatBall）。
        l.hidden[I_FLOAT] = true;
        // 位置不自己存：G 的位置恒等于「收」，见 GamepadView.computeGeometry
        l.rx[I_FLOAT] = l.rx[I_COLLAPSE];
        l.ry[I_FLOAT] = l.ry[I_COLLAPSE];
        // G 也是布局自带的（悬浮窗布局专属），打上标记，
        // 重置单个时走 resetSysOwned() 而不是拿模板默认值覆盖。
        l.sysOwned[I_FLOAT] = true;
    }

    /** G 默认的不透明度。球本来就是半透明的，滑条上写的就是它。 */
    public static final float FLOAT_BALL_ALPHA = 0.75f;

    /**
     * 这个元素的默认不透明度（没设过色、也没动过滑条时是多少）。
     *
     * 只有 G 例外：球本来就是半透明的（75%），
     * 恢复默认时给 100% 会糊掉下面一块画面。
     */
    public static float defaultAlphaOf(int i) {
        return (i == I_FLOAT) ? FLOAT_BALL_ALPHA : 1f;
    }

    /** 是不是那颗 G（悬浮球）。和它在哪个布局无关 —— 它只在悬浮窗布局显示。 */
    public boolean isFloatBall(int i) {
        return i == I_FLOAT;
    }

    /**
     * 悬浮球（收起后那个 G）的样式。
     *
     * 【必须有一处能读它，否则"悬浮窗布局"就是自娱自乐】
     *   BubbleView 以前把底色写死成 0xC0336699、字写死成 "G"，
     *   和布局里改的一丁点关系都没有 ——
     *   用户在悬浮窗布局里把 G 调成红色，收起后还是那个蓝球。
     *   所以这里专门导出一份样式，让 BubbleView 读它。
     */
    public static class BubbleStyle {
        public int color;
        public String label;
        public float scale;
        public float alpha;
        public int shape;
        public boolean showLabel;
        /** 字号倍率。不带上它的话「字体」滑条对悬浮球无效，只在布局里生效。 */
        public float textScale = 1f;
    }

    /** 读悬浮球样式：取自「悬浮窗」布局里 G（I_COLLAPSE）的那几个字段。 */
    public static BubbleStyle bubbleStyle(Context ctx, int w, int h, boolean portrait) {
        BubbleStyle bs = new BubbleStyle();
        bs.color = 0xFF336699;   // 和 BubbleView 原来的底色一致（alpha 单列）
        bs.label = "G";
        bs.scale = 1f;
        bs.alpha = FLOAT_BALL_ALPHA;
        bs.shape = SHAPE_CIRCLE;
        bs.showLabel = true;
        try {
            // 走正常 load：没存过会顺带把默认（蓝底白字 G）写进存档，
            // 之后用户改了就读到改过的值。
            PadLayout l = load(ctx, w, h, portrait, LAYOUT_FLOAT, 0f, 0f);
            int i = I_FLOAT;
            if (l.btnColor[i] != 0) {
                bs.color = l.btnColor[i];
            }
            String nm = l.customName[i];
            String base = l.nameOf(i);
            bs.label = (nm != null && nm.trim().length() > 0) ? nm.trim() : base;
            bs.scale = l.scale[i];
            bs.alpha = l.alpha[i];
            bs.shape = l.shape[i];
            bs.showLabel = l.showLabel[i];
            bs.textScale = (l.textScale[i] > 0.1f) ? l.textScale[i] : 1f;
        } catch (Exception ignored) {
            // 读不出来就用内置样式，不至于让悬浮球显示不出来
        }
        return bs;
    }

    /**
     * 把全局那份「收」按钮位置套到布局上。
     *
     * 所有 load 出口都要走一遍 —— 少一个就会出现
     * "某些布局读的是自己的旧存档、某些读全局" 的不一致。
     */
    private static void applyGlobalCollapse(Context ctx, PadLayout l, boolean portrait) {
        float[] rel = loadCollapseRelRaw(ctx, portrait);
        l.rx[I_COLLAPSE] = rel[0];
        l.ry[I_COLLAPSE] = rel[1];
    }

    /**
     * 存一份布局。
     *
     * 【这里绝对不要顺手写"当前布局 id"】
     *   之前图省事加过，结果被所有调用点写脏：
     *   setCollapseRel() 只是想把「收」按钮的位置同步给悬浮球，
     *   它 load 的是默认布局，save 一下就把 id 记成了默认 ——
     *   于是"切到自己布局 → 收起 → id 被覆盖成默认 → 再打开是默认"。
     *
     *   save() 的语义只是"存这个布局的摆放"，
     *   "当前在用哪个"是另一件事，必须由切换动作显式写入。
     */
    /**
     * 当前布局用的是哪个模板。
     *
     * 【不能只看 keyboardMode】
     *   它是 boolean，只有"键盘 / 非键盘"两种。
     *   空白模板的 keyboardMode 也是 false，和手柄模板一样 ——
     *   于是"在空白布局上点重置全部"会按非键盘 = 手柄模板重建，
     *   凭空多出一整套手柄键。得回到 meta 里读真正的模板号。
     */
    public int tplOf(Context ctx) {
        LayoutMeta m = findMeta(ctx, layoutId);
        if (m == null) {
            return keyboardMode ? TPL_KEYBOARD : TPL_PAD;
        }
        return m.tpl;
    }

    public void save(Context ctx) {
        try {
            JSONObject o = new JSONObject();
            // 【收按钮位置同步到全局】
            //   用户在画布上拖了「收」，得让悬浮球跟着走。
            //   只在"值确实变了"时写 —— 见下面 save 尾部的比对。
            o.put("v", versionFor(portrait, layoutId, keyboardMode));
            // 结构版本：老存档没有这个字段，load 时据此判断要不要做下标迁移
            o.put("sch", SCHEMA);
            for (int i = 0; i < N; i++) {
                JSONObject e = new JSONObject();
                e.put("x", rx[i]);
                e.put("y", ry[i]);
                e.put("s", scale[i]);
                e.put("a", alpha[i]);
                e.put("h", hidden[i]);
                e.put("k", keyCode[i]);
                e.put("sp", shape[i]);
                e.put("ts", textScale[i]);
                if (customName[i] != null && !customName[i].isEmpty()) {
                    e.put("dn", customName[i]);
                }
                if (btnColor[i] != 0) {
                    e.put("bc", btnColor[i]);
                }
                // 穿透按钮：开 / 关两支颜色，存在它自己那一条里
                if (i == I_PASS) {
                    if (passOnColor != 0) {
                        e.put("po", passOnColor);
                    }
                    if (passOffColor != 0) {
                        e.put("pf", passOffColor);
                    }
                }
                if (!showLabel[i]) {
                    e.put("nl", true);
                }
                e.put("wm", widthMul[i]);
                e.put("hm", heightMul[i]);
                e.put("pt", padType[i]);
                // 只有摇杆存这两个，别的元素存了也是死数据
                if (isStick(i)) {
                    e.put("sf", stickFixed[i]);
                    e.put("sr", stickRange[i]);
                }
                // 空白按钮：只占用标记，没有别的数据
                if (isBlankUsed(i)) {
                    e.put("bl", true);
                }
                // 系统自带标记（提示牌 / G）：不存的话重启后认不出来，
                // 重置单个又会拿模板默认值把它改成圆形。
                if (sysOwned[i]) {
                    e.put("so", true);
                }
                // 组合键：名字 + 动作表（编码成 "类型:编码,类型:编码,..."）
                if (isComboUsed(i)) {
                    e.put("cn", comboName[i]);
                    // dir 0 = 上（十字架）/ 唯一那条（普通），走老字段 ca
                    StringBuilder ca = new StringBuilder();
                    for (int j = 0; j < MAX_COMBO_ACTS; j++) {
                        if (j > 0) {
                            ca.append(',');
                        }
                        ca.append(comboActType(i, 0, j))
                                .append(':')
                                .append(comboActCode(i, 0, j))
                                .append(':')
                                .append(comboActTag(i, 0, j));
                    }
                    e.put("ca", ca.toString());
                    if (comboCross[i]) {
                        e.put("cx", true);
                        // 斜角关了才写，省得每个存档都多一个字段；
                        // optBoolean 默认 true 正好是"默认开启"
                        if (!comboDiag[i]) {
                            e.put("dg", false);
                        }
                        // 下 / 左 / 右 三条
                        JSONArray da = new JSONArray();
                        for (int d = 1; d < COMBO_DIRS; d++) {
                            StringBuilder sb = new StringBuilder();
                            for (int j = 0; j < MAX_COMBO_ACTS; j++) {
                                if (j > 0) {
                                    sb.append(',');
                                }
                                sb.append(comboActType(i, d, j))
                                        .append(':')
                                        .append(comboActCode(i, d, j))
                                        .append(':')
                                        .append(comboActTag(i, d, j));
                            }
                            da.put(sb.toString());
                        }
                        e.put("cd", da);
                    }
                }
                o.put(String.valueOf(i), e);
            }
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            p.edit().putString(variantKey(portrait, layoutId, keyboardMode),
                    o.toString()).apply();

            // 【收按钮位置 -> 全局】
            //
            //   「收」是全局共享的（和悬浮球同一个位置），
            //   所以任何一次 save 都要把它同步到全局。
            //
            //   【有个顺序陷阱】
            //     load() 末尾会把全局位置**覆盖**到布局上，
            //     于是读进来的 rx[I_COLLAPSE] 已经等于全局值了。
            //     如果这里无条件回写，就等于"用全局覆盖全局"，看似无害；
            //     但 FloatingService 在收起时可能先改了全局（拖过悬浮球）、
            //     之后才 save —— 那样旧值就会把新值盖回去。
            //   所以只在"确实和全局不一样"时才写：
            //     不一样 = 用户刚在画布上拖过收按钮 -> 以拖的为准
            //     一样   = 没动过 -> 不动全局
            float[] g = loadCollapseRelRaw(ctx, portrait);
            if (Math.abs(g[0] - rx[I_COLLAPSE]) > 1e-4f
                    || Math.abs(g[1] - ry[I_COLLAPSE]) > 1e-4f) {
                saveCollapseRel(ctx, portrait, rx[I_COLLAPSE], ry[I_COLLAPSE]);
            }
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------
    // 布局列表（名字 / 模板类型）的增删改
    //
    // 列表本身单独存一个 pref，和"每个布局的实际摆位"分开：
    // 改名 / 删除只是动列表，不用去解析那上百个键的存档。
    // ------------------------------------------------------------------
    private static final String PREFS_LAYOUTS = "pad_layout_list";

    /** 记住"当前正在用哪个布局"，下次打开还用它。 */
    private static final String PREF_CUR_LAYOUT = "cur_layout_id";
    private static final String KEY_LIST = "list";

    public static final class LayoutMeta {
        public int id;
        public String name;
        public int tpl;
        public boolean builtin;
        /**
         * 创建（或导入）时间，毫秒；<= 0 表示"未知"。
         *
         * 旧存档没有这一项，读出来是 0 —— 不能拿它当"1970 年"显示，
         * 所以统一约定 <= 0 就是未知。
         */
        public long time;
        /** 这份是导入来的（显示"导入于"，否则显示"创建于"）。 */
        public boolean imported;

        LayoutMeta(int id, String name, int tpl, boolean builtin) {
            this(id, name, tpl, builtin, 0L, false);
        }

        LayoutMeta(int id, String name, int tpl, boolean builtin,
                   long time, boolean imported) {
            this.id = id;
            this.name = name;
            this.tpl = tpl;
            this.builtin = builtin;
            this.time = time;
            this.imported = imported;
        }
    }

    /** 内置两个永远在最前面，且删不掉。 */
    public static java.util.ArrayList<LayoutMeta> loadMetas(Context ctx) {
        java.util.ArrayList<LayoutMeta> out = new java.util.ArrayList<LayoutMeta>();
        out.add(new LayoutMeta(LAYOUT_DEFAULT_PAD, "默认手柄", TPL_PAD, true));
        out.add(new LayoutMeta(LAYOUT_DEFAULT_KB, "默认键盘", TPL_KEYBOARD, true));
        // 悬浮窗布局：内置，所以布局列表里不显示「删 / 改名 / 同步」，
        // 唯一能做的就是点一下切过去。
        out.add(new LayoutMeta(LAYOUT_FLOAT, "悬浮窗", TPL_BLANK, true));
        out.add(new LayoutMeta(LAYOUT_DEFAULT_MOUSE, "默认鼠标", TPL_MOUSE, true));
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS_LAYOUTS, Context.MODE_PRIVATE);
            String s = p.getString(KEY_LIST, null);
            if (s == null) {
                return out;
            }
            org.json.JSONArray a = new org.json.JSONArray(s);
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                int id = o.optInt("id", -1);
                // 内置两个由上面硬编码给出，存档里再出现就跳过，避免重复
                if (id < LAYOUT_USER_START) {
                    continue;
                }
                out.add(new LayoutMeta(id, o.optString("n", "布局" + id),
                        o.optInt("t", TPL_PAD), false,
                        // 旧存档没有 ct / im，optLong 返回 0 -> 显示"未知"
                        o.optLong("ct", 0L), o.optBoolean("im", false)));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void saveMetas(Context ctx, java.util.ArrayList<LayoutMeta> metas) {
        try {
            org.json.JSONArray a = new org.json.JSONArray();
            for (LayoutMeta m : metas) {
                if (m.builtin) {
                    continue;           // 内置的不存，每次由代码给出
                }
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("id", m.id);
                o.put("n", m.name);
                o.put("t", m.tpl);
                if (m.time > 0) {
                    o.put("ct", m.time);
                }
                if (m.imported) {
                    o.put("im", true);
                }
                a.put(o);
            }
            SharedPreferences p = ctx.getSharedPreferences(PREFS_LAYOUTS, Context.MODE_PRIVATE);
            p.edit().putString(KEY_LIST, a.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** 下一个可用的用户布局 id（取现有最大 +1，删掉再建不会撞号）。 */
    private static int nextLayoutId(java.util.ArrayList<LayoutMeta> metas) {
        int max = LAYOUT_USER_START - 1;
        for (LayoutMeta m : metas) {
            if (m.id > max) {
                max = m.id;
            }
        }
        int id = max + 1;
        // 别撞上悬浮窗布局那个号
        if (id == LAYOUT_FLOAT) {
            id++;
        }
        return id;
    }

    public static int createLayout(Context ctx, String name, int tpl) {
        java.util.ArrayList<LayoutMeta> metas = loadMetas(ctx);
        if (metas.size() >= LAYOUT_MAX) {
            return -1;
        }
        int id = nextLayoutId(metas);
        metas.add(new LayoutMeta(id, name, tpl, false,
                System.currentTimeMillis(), false));
        saveMetas(ctx, metas);
        return id;
    }

    // ------------------------------------------------------------------
    // 布局导出 / 导入（分享给别人、或装回别人给的布局）
    // ------------------------------------------------------------------

    /** 导出文件里的标记，导入时靠它认出"这是本 app 的布局存档"。 */
    public static final String EXPORT_APP = "vgamepad";
    public static final int EXPORT_FMT = 1;

    /**
     * 导出一份布局为 JSON 字符串（含竖屏 + 横屏两份摆位）。
     *
     * 【为什么两份都要】
     *   存档本来就按朝向分开存（"L3_P" / "L3_L"），只导一份的话
     *   对方转到另一个朝向就是空的、被打回默认布局。
     *
     * 【为什么原样存 JSON 字符串，不拆开重编码】
     *   摆位字段十几个（x/y/s/a/h/k/sp/ts/wm/hm/pt），一个个搬容易漏，
     *   而且以后加字段还得同步改两处。原样打包，读的时候原样写回最稳。
     *
     * @return null 表示这个布局还没有任何摆位存档（没打开过）
     */
    public static String exportLayoutJson(Context ctx, int layoutId) {
        try {
            LayoutMeta m = findMeta(ctx, layoutId);
            if (m == null) {
                return null;
            }
            boolean kb = (m.tpl == TPL_KEYBOARD);
            SharedPreferences p =
                    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            org.json.JSONArray vs = new org.json.JSONArray();
            String sp = p.getString(variantKey(true, layoutId, kb), null);
            String sl = p.getString(variantKey(false, layoutId, kb), null);
            if (sp != null) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("p", true);
                o.put("d", sp);
                vs.put(o);
            }
            if (sl != null) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("p", false);
                o.put("d", sl);
                vs.put(o);
            }
            if (vs.length() == 0) {
                return null;
            }
            org.json.JSONObject root = new org.json.JSONObject();
            root.put("app", EXPORT_APP);
            root.put("fmt", EXPORT_FMT);
            root.put("name", m.name);
            root.put("tpl", m.tpl);
            root.put("time", System.currentTimeMillis());
            root.put("v", vs);
            return root.toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 把导出的 JSON 装回来，建成一个新布局。
     *
     * 【版本号必须换成 USER_LAYOUT_VERSION】
     *   导出的可能是内置布局（"默认手柄"），它的存档版本号和内置默认布局绑定；
     *   load() 里一旦发现版本号对不上就整份丢弃、回退默认。
     *   装成用户布局后 key 变了、期望版本也变了，不改就白导一场。
     *
     * @return 新布局 id，负数表示失败（格式不对 / 布局数已满）
     */
    public static int importLayoutJson(Context ctx, String json) {
        try {
            org.json.JSONObject root = new org.json.JSONObject(json);
            if (!EXPORT_APP.equals(root.optString("app", ""))) {
                return -1;
            }
            String name = root.optString("name", "导入的布局");
            if (name.trim().isEmpty()) {
                name = "导入的布局";
            }
            int tpl = root.optInt("tpl", TPL_PAD);
            if (tpl != TPL_BLANK && tpl != TPL_PAD && tpl != TPL_KEYBOARD) {
                tpl = TPL_PAD;
            }
            org.json.JSONArray vs = root.optJSONArray("v");
            if (vs == null || vs.length() == 0) {
                return -1;
            }
            int id = createLayout(ctx, name, tpl);
            if (id < 0) {
                return -1;
            }
            boolean kb = (tpl == TPL_KEYBOARD);
            SharedPreferences p =
                    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            SharedPreferences.Editor ed = p.edit();
            for (int i = 0; i < vs.length(); i++) {
                org.json.JSONObject o = vs.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                boolean portrait = o.optBoolean("p", true);
                String d = o.optString("d", null);
                if (d == null || d.length() == 0) {
                    continue;
                }
                org.json.JSONObject inner = new org.json.JSONObject(d);
                inner.put("v", USER_LAYOUT_VERSION);
                ed.putString(variantKey(portrait, id, kb), inner.toString());
            }
            ed.apply();
            // 标记为"导入的"，并补记导入时间。
            // createLayout 里已经写过一次创建时间，这里要覆盖成导入时刻 ——
            // 否则导入的布局显示的是"刚刚创建于"，语义不对。
            markImported(ctx, id);
            return id;
        } catch (Exception ignored) {
            return -1;
        }
    }

    /** 把布局标成"导入来的"，时间记为当前。 */
    private static void markImported(Context ctx, int id) {
        java.util.ArrayList<LayoutMeta> metas = loadMetas(ctx);
        for (LayoutMeta m : metas) {
            if (m.id == id && !m.builtin) {
                m.imported = true;
                m.time = System.currentTimeMillis();
                break;
            }
        }
        saveMetas(ctx, metas);
    }

    /**
     * 列表上显示的时间后缀；内置布局返回 ""（不显示）。
     *
     * 格式到分钟：布局列表一行放不下秒，也没人关心几秒建的。
     */
    public static String metaTimeText(LayoutMeta m) {
        if (m == null || m.builtin) {
            return "";
        }
        if (m.time <= 0) {
            return "未知";
        }
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm", java.util.Locale.getDefault());
            return (m.imported ? "导入于 " : "创建于 ") + f.format(new java.util.Date(m.time));
        } catch (Exception ignored) {
            return "未知";
        }
    }

    public static void renameLayout(Context ctx, int id, String name) {
        java.util.ArrayList<LayoutMeta> metas = loadMetas(ctx);
        for (LayoutMeta m : metas) {
            if (m.id == id && !m.builtin) {
                m.name = name;
                break;
            }
        }
        saveMetas(ctx, metas);
    }

    /** 删除布局：列表项 + 竖屏横屏两份摆位存档一起清。 */
    public static void deleteLayout(Context ctx, int id) {
        if (id < LAYOUT_USER_START) {
            return;                     // 内置的不给删
        }
        java.util.ArrayList<LayoutMeta> metas = loadMetas(ctx);
        for (int i = 0; i < metas.size(); i++) {
            if (metas.get(i).id == id) {
                metas.remove(i);
                break;
            }
        }
        saveMetas(ctx, metas);
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            p.edit().remove("L" + id + "_P").remove("L" + id + "_L").apply();
        } catch (Exception ignored) {
        }
    }

    public static LayoutMeta findMeta(Context ctx, int id) {
        java.util.ArrayList<LayoutMeta> metas = loadMetas(ctx);
        for (LayoutMeta m : metas) {
            if (m.id == id) {
                return m;
            }
        }
        return null;
    }

    public static void clear(Context ctx) {
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            p.edit().remove(KEY_P).remove(KEY_L).remove(KEY_KP).remove(KEY_KL).apply();
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------
    // 「收」按钮 <-> 悬浮球 位置共享
    // ------------------------------------------------------------------

    /** 取「收」按钮的相对位置，供悬浮球对齐。 */
    // ==================================================================
    // 「收」按钮 <-> 悬浮球 位置共享
    //
    // 【全局存一份，不跟布局走】
    //
    // 原来各布局各存一份，读写还不一致：
    //   写（收起时 setCollapseRel）按**当前布局**
    //   读（悬浮球定位 collapseRel）走 3 参数重载 = **默认手柄**
    // 于是只有默认手柄布局里"拖收按钮 -> 收起 -> 悬浮球跟着动"成立，
    // 切到别的布局就是单方面的：收按钮动了，悬浮球不动。
    //
    // 悬浮球全局只有一个，位置自然是全局的；
    // 「收」和它共享，那也该全局。
    // 各布局各存一份还会导致切布局时收按钮乱跳。
    // ==================================================================

    /** 全局存档 key：竖屏 / 横屏各一份 */
    private static final String PREF_COLLAPSE_REL_P = "collapse_rel_p";
    private static final String PREF_COLLAPSE_REL_L = "collapse_rel_l";

    /** 默认位置：顶部中间偏上，和 reset() 里的一致 */
    private static final float COLLAPSE_DEF_RX = 0.5f;
    private static final float COLLAPSE_DEF_RY = 0.045f;

    /**
     * 直接读 Preferences 里的全局位置，**不走 load()**。
     *
     * 【为什么单独拆一个】
     *   collapseRel() 的兼容回退要调 load()，而 load() 末尾要应用全局位置、
     *   又要读它 —— 直接互调就是无限递归。
     *   所以 load() 只调这个只读 Preferences 的版本。
     */
    private static float[] loadCollapseRelRaw(Context ctx, boolean portrait) {
        float[] def = {COLLAPSE_DEF_RX, COLLAPSE_DEF_RY};
        if (ctx == null) {
            return def;
        }
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String v = p.getString(portrait ? PREF_COLLAPSE_REL_P : PREF_COLLAPSE_REL_L, null);
            if (v == null) {
                // 【老存档迁移】
                //   全局 key 还没有 -> 读默认手柄那份存档里「收」的位置，
                //   用户之前摆过的位置不至于丢。
                return legacyCollapseRel(ctx, portrait, def);
            }
            String[] a = v.split(",");
            if (a.length == 2) {
                return new float[]{Float.parseFloat(a[0]), Float.parseFloat(a[1])};
            }
        } catch (Exception ignored) {
        }
        return def;
    }

    /** 迁移用：从默认手柄布局的存档里读「收」的位置。 */
    private static float[] legacyCollapseRel(Context ctx, boolean portrait, float[] def) {
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String s = p.getString(variantKey(portrait, LAYOUT_DEFAULT_PAD, false), null);
            if (s != null) {
                JSONObject o = new JSONObject(s);
                JSONObject e = o.optJSONObject(String.valueOf(I_COLLAPSE));
                if (e != null) {
                    return new float[]{(float) e.optDouble("x", def[0]),
                            (float) e.optDouble("y", def[1])};
                }
            }
        } catch (Exception ignored) {
        }
        return def;
    }

    /** 写入全局位置。 */
    public static void saveCollapseRel(Context ctx, boolean portrait, float rx, float ry) {
        if (ctx == null) {
            return;
        }
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(portrait ? PREF_COLLAPSE_REL_P : PREF_COLLAPSE_REL_L,
                            rx + "," + ry).apply();
        } catch (Exception ignored) {
        }
    }

    /** 悬浮球定位用：读全局位置。 */
    public static float[] collapseRel(Context ctx, int w, int h, boolean portrait) {
        return loadCollapseRelRaw(ctx, portrait);
    }

    /** 悬浮球被拖动后写回全局，所有布局的「收」都跟着走。 */
    public static void setCollapseRel(Context ctx, int w, int h, boolean portrait,
                                      float rx, float ry) {
        saveCollapseRel(ctx, portrait, rx, ry);
    }

    // ------------------------------------------------------------------

    public static float clampScale(float v) {
        if (v < MIN_SCALE) return MIN_SCALE;
        if (v > MAX_SCALE) return MAX_SCALE;
        return v;
    }

    public static float clampAlpha(float v) {
        if (v < MIN_ALPHA) return MIN_ALPHA;
        if (v > 1f) return 1f;
        return v;
    }
}
