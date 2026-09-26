package com.example.vgamepad;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.util.Log;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.SparseIntArray;
import android.view.MotionEvent;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 半透明全屏浮层：画手柄、把触摸翻译成按键 / 摇杆 / 十字键事件。
 *
 * 布局来自 PadLayout（相对坐标），支持编辑模式：
 *   拖动 = 移动，点选后底部面板出现两条滑条，直接拖滑条调大小和透明度。
 *
 * 方向键（十字键）的方向：显示和实际触发共用同一个 hat 值，
 * 不会出现"看着往左、实际往右"这种事。摇杆中心分别写着 L / R，不会认错。
 *
 * 「收」按钮（I_COLLAPSE）永远最后绘制、最先命中，图层优先级最高，
 * 且它的位置与悬浮球共享，展开 / 收起时位置不会跳。
 */
public class GamepadView extends PickList {



    /** 编辑面板底部的按钮 */
    private static final String[] TOOL_TEXT = {"", "", "", "", "常用工具", "完成"};

    /**
     * 工具按钮排两行：一行 5 个的话每个只有屏宽的 1/6，
     * 横屏面板高度也塞不下。
     *
     * 现在面板上只留「常用工具」和「完成」两个，其余全收进「常用工具」的
     * 列表里 —— 面板就两个大按钮，横屏矮面板上也点得准。
     * T_RESET / T_RESET_ONE / T_SWAP / T_LINK 四个不再直接画在面板上，
     * 但 TOOL_TEXT 里仍占位（下标对齐用），绘制时会跳过。
     */

    /** 「常用工具」列表里的条目。带 … 的点了会再弹按键列表。 */
    /** 只在选中无轨道版 L2(键) / R2(键) 时才出现的那一项 */
    /** 弹出"要创建哪个键"的列表 —— 键盘按键是动态创建的，不是固定元素 */
    /** 只在选中一个**已创建的**键盘按键时才出现的那一项 */
    /**
     * 批量删除：列出所有已创建的键盘按键，多选删除。
     * 和 TL_DELETE 的区别 —— 那个是"删当前选中的这一个"，
     * 这个是一次清掉好几个，不用反复选中再删。
     */
    /** 布局切换：列出所有布局，点一下切过去（还能改名 / 删除 / 新建） */
    /**
     * 选中按钮：从列表里点一个就直接选中它。
     *
     * 用途是**救急** —— 按钮被缩到极小、或者叠在一起时，
     * 画布上根本点不中，但编辑面板的滑条又必须先选中才能调。
     * 没有这个入口，那个按钮就永远改不回来了。
     */
    /** 手柄按键…：列出可创建的手柄元素，点一个就建一份 */
    /**
     * 按键创建…：手柄键和键盘键合起来的统一入口。
     *
     * 原来是两个并列项（键盘按键… / 手柄按键…），占两行还不好区分 ——
     * 进去了才知道自己进的是哪张表。现在合并成一个，
     * 点进去先选类型（LIST_CREATE），再进对应的候选表。
     *
     * TL_PAD / TL_KEY 保留：它们现在是 LIST_CREATE 的两种去向，
     * 仍然是各自列表的 mode，只是不再直接出现在工具列表里。
     */
    /**
     * 多选调节…：勾选至少两个按钮，一次性把大小 / 透明度 / 字号刷成同一档。
     *
     * 和「形状」那种单键面板的区别：那个一次只改一个，
     * 想把一片键盘键调成统一大小得挨个点十几次。
     */
    /** 多选形状…：勾选至少两个按钮，一次性刷成同一种形状。 */
    /**
     * 网格 / 吸附…：打开网格配置面板。
     *
     * 网格只是**编辑期的辅助线**，运行时不画。
     * 吸附是拖完松手那一刻把按键中心对齐到最近的网格交叉点
     * （或已有按键的中心线），让手动摆的键位看着整齐。
     */
    /**
     * 仅常用工具列表：按用途分组，工具项多了不好找。
     *   全部 / 重置 / 位置 / 按钮 / 其他
     */






    /**
     * 每个工具项属于哪一类（下标和 TL_* 对应）。
     *
     *   全部(0) 重置(1) 位置(2) 按钮(3) 其他(4)
     *
     * 「其他」= 布局切换。它管的是整套布局，不是单个按钮，
     * 塞进"按钮"类会让人以为它也是增删某个键。
     */





















    /**
     * 常用工具列表的**显示顺序**。
     *
     * 原来直接按 TL_* 的数值排，而 TL_* 是"想到一个加一个"攒出来的，
     * 结果 TL_CAT 是 1,1,2,2,3,2,3,3,3,3,4,2,3,3 ——
     * 照这个顺序加分组标题会变成"重置 / 位置 / 按钮 / 位置 / 按钮 / 其他…"，
     * 「按钮」这个标题要重复出现 4 次，比不加还乱。
     *
     * 所以显示顺序和常量值解耦：这里按分组把同类的排在一起，
     * 14 项一项不少，只是位置变了。
     */











    /**
     * 【下标必须和 TL_* 常量值严格对齐】
     *
     *   TL_TEXT[TL_CREATE] 取到的是第 17 项，中间少一个就会整体错位 ——
     *   表现为"点的是网格/吸附，弹出来的是固定显示"。
     *
     *   下标 8 / 9（TL_KEY / TL_PAD）现在不在工具列表里了，
     *   它们由「按键创建…」分流进入，但位置必须占着，不能删。
     */




















    /**
     * 「重置单个 / 互换位置 / 关联挪动」都改成列表操作：
     * 点工具 -> 弹出按键列表勾选 -> 点「确定」执行。
     *
     * 一开始做的是"点工具后再去画布上点按钮"，问题是按钮可能叠在一起、
     * 也可能被拖到屏幕外点不到，列表里点名字反而稳。
     */
    /**
     * 多选：勾哪几个就选中哪几个。
     *
     * 选中之后：拖任一个 -> 整批一起走；滑条 -> 作用于整批；
     * 「更多选项 -> 形状」-> 把整批刷成同一种。
     */
    /** 互换位置：恰好两个 */
    /** 重置单个：多选，把选中的恢复默认位置 */
    /** 隐藏按钮：多选，选中的在编辑模式打叉、非编辑模式完全隐藏 */
    /** 「常用工具」列表：点一项就执行（或再弹按键列表） */
    /**
     * 选择要创建哪个键盘按键（不是"勾选已有元素"，
     * 而是从 KEY_NAMES 里挑一个键名，点了就立刻创建）。
     */
    /**
     * 批量删除键盘按键：多选，至少选一个。
     * 列表里只列**已创建的**键盘按键（固定手柄键不能删）。
     */
    /** 布局列表（从常用工具进）：可切换 / 改名 / 删除 */
    /** 布局列表（从「布」按钮进）：只能切换 */
    /** 新建布局时选模板 */
    /**
     * 选中按钮：列出所有按钮（**含隐藏的**），点一项就选中它。
     *
     * 单选语义（点了立刻生效并关列表），不是勾再确定。
     */
    /**
     * 手柄元素列表：从候选里挑一种键，点了就立刻创建一份。
     *
     * 和 LIST_KEY 同构（都是"挑一个键名然后创建"），
     * 区别是列表内容来自 PAD_CANDIDATES 而不是 KEY_NAMES。
     */
    /** 多选调节：勾至少两个，确定后进入统一调节面板 */
    /** 多选形状：勾至少两个，确定后选圆形 / 四边形 */
    /**
     * 挑「布局调节」的中心按钮。
     *
     * 只在**已勾选的那批**里单选 —— 中心必须是参与者之一，
     * 拿一个没参与缩放的按钮当锚点没有意义。
     */
    /**
     * 网格 / 吸附设置：单列选项列表，有多少项列表就多长。
     *
     * 之前做成了复用调节面板的两条滑条 —— 选项一多就塞不下，
     * 而且滑条那套是"调数值"的语义，跟开关项混在一起很别扭。
     */
    /** 「固定显示」第一级：选模板（空白 / 手柄 / 键盘） */
    /** 「固定显示」第二级：勾这个模板的初始状态 */
    /**
     * 「重置单个」的第一步：先勾要重置哪些**属性**。
     *
     * 原来只能重置位置，想"只把大小调回默认、位置留着"就得整个重置再手摆回去。
     * 选完属性才进选按钮的列表（LIST_RESET_ONE）。
     */
    // 【必须和 LIST_FIX_TPL / LIST_FIX 错开】
    //   原来写 17，而 17 已经被 LIST_FIX_TPL 占了 —— switch 里两个
    //   case 编译不过（Duplicate case '17'）。
    /**
     * 按键创建的**类型选择**：只有「手柄按键」「键盘按键」两项。
     *
     * 选完直接 openList(LIST_PAD) / openList(LIST_KEY)，
     * 所以这一层自己不建任何东西，只负责分流。
     */
    /**
     * LIST_CREATE 的四行名字。顺序即 pos：0 = 手柄，1 = 键盘，2 = 空白，3 = 组合键。
     *
     * 【按顺序取，别按下标硬编码判断】
     *   下面 toggleListItem 里用的是 CT_PAD / CT_KEY / CT_BLANK / CT_COMBO 常量，
     *   加项时改数组和常量即可，不会出现"名字加了一项、分支没跟上"。
     */
    /**
     * 横竖屏同步第一步：勾哪些按钮。
     *
     * 列的是"当前方向已经存在的按钮"—— 另一个方向没有的按钮同步过去
     * 也无从复制，所以列表只按当前布局来列。
     */
    //
    // 【必须和 LIST_MORE(21) / LIST_COMBO_EDIT(24) 错开】
    //   21 早就被 LIST_MORE 占了，switch 里两个 case 21 编译不过。
    //   新加模式前先把上面这些值扫一遍。
    /**
     * 横竖屏同步第二步：勾要复制哪些信息。
     *
     * 和「重置单个」的属性列表是同一套维度，只是**没有位置**（横竖屏比例
     * 不同，位置复制过去会跑到屏幕外），文案也从"重置"改成"复制"。
     */
    /**
     * 横竖屏同步第零步：选方向（横→竖 还是 竖→横）。
     *
     * 【必须显式选，不能靠"当前方向"反推】
     *   反推的话用户根本不知道自己点了之后是往哪边搬 ——
     *   当前竖屏就默认"横屏搬到竖屏"，但用户可能是想反过来。
     *   而且当前方向**没有存档**时，反推出来的方向压根无数据可复制。
     */
    /** 同步方向：源是横屏（搬到竖屏）。 */
    private static final int SDIR_FROM_LAND = 0;
    /** 同步方向：源是竖屏（搬到横屏）。 */
    //
    // 【写完整中文，别用箭头】
    //   "横屏 → 竖屏" 看着像"横屏变成了竖屏"（转屏），
    //   其实是"把横屏那份复制过来"，方向容易被理解反。
    /** 同步方向：源是不是竖屏（true = 从竖屏搬到横屏）。 */
    /**
     * 源方向的那份布局快照。
     *
     * 【挑按钮必须按源方向列】
     *   列表原本是按 mLayout（= 目标方向）建的，于是"只在源方向建过的按钮"
     *   压根不出现在列表里 —— 而那正是要同步过来的对象，一个都选不到。
     */

    // ---- 横竖屏同步：要复制哪些信息（没有位置）----
    private static final int SD_SIZE = 0;
    private static final int SD_ALPHA = 1;
    private static final int SD_TEXT = 2;
    private static final int SD_OTHER = 3;

    /** 当前正在同步哪一份布局。 */
    private static final int CT_KEY = 1;


    /**
     * 「更多选项」：编辑面板左上角那个入口点开的列表。
     *
     * 原来那个位置直接写死「形状」，点一下就进形状子模式。
     * 现在换成「更多选项」，形状只是列表里的其中一项 ——
     * 因为将来还要往这儿塞别的调节项，一个位置写死一种功能放不下。
     *
     * 加项的方法：往 MORE_NAMES 里补一个名字，再到 toggleListItem 的
     * LIST_MORE 分支里加一个 case。
     */
    /** 「更多选项 -> 外观」子列表：改名 / 改背景颜色 / 按键名。 */
    /** 进「外观」时记下选中的槽位：子列表里点项要改的就是它。 */
    /** LIST_MORE 固定行：形状（所有元素都有）。 */
    private static final int MORE_SHAPE = 0;
    /** LIST_MORE 仅摇杆才有的行：摇杆设置。跟在形状后面。 */
    private static final int MORE_STICK = 1;

    /**
     * 组合键编辑列表：改名字、增删里面的键。
     *
     * 行布局：
     *   第 0 行    名字（点一下去改名）
     *   第 1..n 行 每个键一行（点名字区重选，点右边「删」去掉）
     *   最后一行   「＋ 添加按键」
     */
    /** 组合键里加键时先选「手柄 / 键盘」。 */
    /** 组合键动作类型：和 PadLayout 里的 1 / 2 / 3 对应。 */
    private static final int COMBO_ACT_KEY = 2;
    /** 延迟。编码位存毫秒数 —— 和 PadLayout.ACT_DELAY 必须是同一个值。 */
    /** 动作类型 4：结束（松开前面还按着的键）。 */
    private static final int COMBO_ACT_RELEASE = 4;
    /** 摇杆推方向 / 十字键拨方向：和 PadLayout 的 7 / 8 对应。 */
    /** 结束摇杆（回中）/ 结束十字键（回中）：和 PadLayout 的 9 / 10 对应。 */
    private static final int COMBO_ACT_RELEASE_STICK = 9;
    /** 摇杆推到自定义坐标：和 PadLayout.ACT_STICK_XY 对应。 */
    /** 挑摇杆先选「预设 / 高级」。 */
    /** 高级：自己设 X / Y，带预览。 */
    /** 扳机：设按下力度（0..100%）。 */
    /** 建组合键时先选「按钮 / 十字架」。 */
    /** 十字架组合键的主列表（名字 / 上下左右 / 斜角）。 */
    /** 改背景颜色：固定几档，点一项生效。 */
    /** 穿透按钮取色前先挑"设哪一支"（开 / 关）。 */
    /**
     * 可选底色。「默认」= 清掉自定义色（存 0），回到统一配色。
     *
     * 【为什么是固定档位而不是取色器】
     *   悬浮窗里摆不下取色盘（空间不够，而且要精细输入）。
     *   按钮配色本来就是"一眼分得清哪块是哪个功能"，十几档够用。
     */




    /**
     * 和 BTN_COLOR_NAMES 一一对应。第一项 0 = 不覆盖，走默认画笔。
     *
     * 两个哨兵都不是真颜色：
     *   -1 = 手输十六进制
     *   -2 = 网页调色盘（用 WebView 里的 <input type="color">）
     */






    private static final String[] CROSS_DIR_NAMES = {"上", "下", "左", "右"};
    /** 高级模式里正在调的 X / Y，各 -100..100（百分比）。 */
    /** 正在调的扳机力度（0..100）。 */
    //
    // 【正在编辑哪个方向】
    //   十字架组合键有四条序列（上 / 下 / 左 / 右），各占一个方向号。
    //   普通组合键只有一条，固定用 0。
    //   所有动作表读写都带上它，省得每个调用点各传各的、传错就互相覆盖。
    /** 是不是在编辑十字架某一条方向的序列（那时列表里没有「名字」行）。 */
    private static final int COMBO_ACT_RELEASE_HAT = 10;
    /** 8 个方向的中文名，顺序和 Android hat 值一致。 */
    /** 挑方向列表：给摇杆 / 十字键选推哪个方向。 */
    /** 正在播的动作属于哪个组合键 / 哪一格（sendComboAct 要靠它读方向号）。 */
    /** 正在挑方向的动作格位；NONE = 没在挑。 */
    /**
     * 十字架组合键当前点亮哪几条臂。位掩码：bit0=上 bit1=右 bit2=下 bit3=左。
     *
     * 【为什么要单独存，不能用 mHat】
     *   mHat 是全局唯一的方向键状态，十字架按下时要按**落点**同时点亮两条
     *   （斜角），根本塞不进一个 hat 值；而且多个十字架各按各的，
     *   共用一个变量会互相覆盖。所以按槽位存，松手清零。
     */
    private final int[] mCrossArms = new int[PadLayout.N];
    private static int crossArmBit(int dir) {
        switch (dir) {
            case PadLayout.DIR_UP:    return 1 << 0;
            case PadLayout.DIR_RIGHT: return 1 << 1;
            case PadLayout.DIR_DOWN:  return 1 << 2;
            case PadLayout.DIR_LEFT:  return 1 << 3;
            default:                  return 0;
        }
    }

    /** 正在挑方向的是哪个原型（I_LS / I_RS / I_DPAD）。 */
    /** 结束指定的手柄键（code = 那个键）。 */
    private static final int COMBO_ACT_RELEASE_PAD = 5;
    /** 结束指定的键盘键（code = 那个键）。 */
    private static final int COMBO_ACT_RELEASE_KEY = 6;
    /** 三个添加行的种类。 */
    private static final int ADD_KEY = 0;
    private static final int ADD_DELAY = 1;
    /**
     * 组合键里挑「延迟多久」的列表。
     *
     * 延迟行点一下弹这个，选完写回那一格，退回编辑列表。
     */
    /** 没有正在挑的格子。 */

    /**
     * 「更多选项」当前该显示哪几行。
     *
     * 摇杆（左 / 右）多一项「摇杆设置」，别的元素没有 ——
     * 给普通按钮显示"摇杆设置"点了没反应，不如不显示。
     */
    /**
     * 组合键才有的行：编辑它里面放哪些键。
     *
     * 【这不是固定下标，别拿它去比 pos】
     *   这一行是 comboMoreNames() **追加**在 moreNames() 后面的，
     *   实际下标 = moreNames().length（选中摇杆时 2，否则 1）。
     *   常量值 2 只在"正好选中了摇杆"时才碰巧对得上 ——
     *   组合键永远不是摇杆，所以对组合键来说它恒为 1。
     */

    /**
     * 「更多选项」当前该显示哪几行 —— 绘制 / 行数 / 点击三处都取这一份。
     *
     * 【为什么合成一份表】
     *   原来「编辑组合键」是 comboMoreNames() 追加在 moreNames() 后面的，
     *   而点击处又拿 MORE_COMBO(=2) 这种写死的下标去比 ——
     *   行数一变（摇杆多一行）下标就错位，点了像点了空气。
     *   现在行表只有这一份，点击改成**按名字**匹配，加行不用再同步下标。
     */
    /** 「外观」子列表的三行。 */
    /**
     * 「外观」子列表的行。
     *
     *   形状也属于外观：它改的就是这个键长什么样（圆 / 方、宽高倍率）。
     *   放在第一位，跟改颜色、改名字挨着，找起来顺。
     */



    String[] moreRowNames() {
        java.util.ArrayList<String> a = new java.util.ArrayList<String>();
        // 【外观永远排第一】
        //   改名 / 颜色 / 形状 / 按键名是最高频的调节，
        //   不管后面再加什么工具（摇杆设置、编辑组合键…），
        //   它都固定在第 1 位，位置不会因为我加行就往下掉。
        //
        //   （形状已并入外观：它改的也是这个键长什么样，单列反而分散。）
        a.add("外观");
        // 【优先级固定在第 2 位】
        //   谁盖住谁是叠放时最先要调的，位置不能因为我往后加行就往下掉。
        // 【行尾带上当前值】
        //   进去之前就能看到现在是多少，不用专门点进去看一眼再退出来。
        //   多选时没有一个"当前值"可显示，保持原样。
        a.add((mSel != NONE) ? ("优先级：" + mLayout.prio[mSel]) : "优先级");
        if (selHasStick()) {
            a.add("摇杆设置");
        }
        // 【只在单选组合键时给】
        //   多选没有"当前键"，编辑谁不明确；而且整批改键表没什么意义。
        if (mSel != NONE && PadLayout.isComboSlot(mSel)
                && mLayout.isComboUsed(mSel)) {
            a.add("编辑组合键");
        }
        return a.toArray(new String[a.size()]);
    }
    /** 互换位置只允许选两个 */

    // ---- 网格 / 吸附设置列表的行 ----
    private static final int GI_SHOW = 0;
    private static final int GI_COLS = 1;
    private static final int GI_ROWS = 2;
    private static final int GI_SNAP = 3;
    /** 吸附是否也认网格线（关掉就只认"和别的键同轴"） */
    private static final int GI_FOLLOW = 4;
    /** 吸附是否看齐其它按钮的中心线（关掉就只剩网格 / 完全不吸） */
    private static final int GI_ELEM = 5;
    /** 吸附灵敏度档位 */
    private static final int GI_SENS = 6;

    // ---- 重置单个：要重置哪些属性 ----
    private static final int RD_POS = 0;
    private static final int RD_SIZE = 1;
    private static final int RD_ALPHA = 2;
    private static final int RD_TEXT = 3;
    /**
     * 其他信息：位置 / 大小 / 透明 / 字体**之外**的那一堆 ——
     * 形状、隐藏、摇杆固定与范围。
     *
     * 不再往下细分了：这些属性平时很少动，挨个列出来占满一屏，
     * 真要调的时候往往是一起恢复默认。
     *
     * 【不碰身份类字段】padType / keyCode / 组合键动作表是"这个按钮是谁"，
     * 重置它们等于把按钮变成另一个键，不是"恢复默认"。
     */
    private static final int RD_OTHER = 4;



    /**
     * 编辑面板三档。
     *
     * FULL      全面板：滑条 + 工具按钮
     * BAR       收成一条窄边：右上角「+」「v」，左边一行提示文字
     * MIN       只剩右下角一个圆形「+」：没有面板背景、没有文字，
     *            整个屏幕下方完全让给游戏按键
     */
    private static final int PANEL_BAR = 1;
    private static final int PANEL_MIN = 2;

    /** 编辑面板上的两条滑条：大小和透明度都用它调，不用反复点按钮 */
    /**
     * 形状子模式：true 时滑条区变成「宽 / 高」，并在第三行给圆形 / 四边形两个选项。
     *
     * 常规模式只有大小/透明/字体三条，再加宽高就 5 条，面板放不下；
     * 单独开一屏更清爽，而且形状相关的调节集中在一处更好找。
     */
    /**
     * 是否停在"摇杆设置"子模式（第一行固定开关，第二行范围滑条）。
     *
     * 只对摇杆（左 / 右）有意义 —— 别的元素在「更多选项」里看不到这一项。
     */
    /** 左上角「更多选项」入口按钮（原来这里直接写死「形状」） */

    /**
     * 子模式（形状 / 摇杆设置）里的「返回」按钮。
     *
     * 【为什么必须有】
     *   从「更多选项」点进形状，面板变成"长 / 宽 + 圆形 / 四边形"，
     *   但没有任何控件能回到常规面板 —— 只能再点一次「更多选项 → 形状」
     *   把它切回去。那是"再点一次切回"，不是"返回"，
     *   以后子模式一多（形状 / 摇杆设置 / …）根本记不住哪个是哪个。
     *
     *   所以每个子模式都给一个明确的「返回」，点了直接回常规面板。
     */

    /**
     * 当前是不是停在某个子模式里（形状 / 摇杆设置 / 优先级）。
     * 决定要不要画「返回」。
     */
    boolean inSubMode() {
        return mShapeMode || mStickCfg || mPrioMode;
    }

    /** 退出子模式，回到常规编辑面板。 */
    private void exitSubMode() {
        mShapeMode = false;
        mStickCfg = false;
        mPrioMode = false;
        invalidate();
    }

    /**
     * 打开「优先级」子面板：面板原地换成优先级那条滑条。
     *
     * 【为什么走面板子模式而不是再弹一层列表】
     *   和形状 / 摇杆设置保持一致 —— 那两个都是原地换面板内容，
     *   唯独优先级弹一个独立列表，进去还要再点「确定」，
     *   同一个软件里两种调节方式，看着像两套东西。
     *
     *   走面板后拖完即生效并存档，不用确定，和别的滑条一样。
     */
    void openPrioPanel() {
        closeList();
        mShapeMode = false;
        mStickCfg = false;
        mPrioMode = true;
        mRayElem = NONE;
        setPanelState(PANEL_FULL);
        invalidate();
    }
    /** 形状模式里的「圆形」「四边形」两个选项 */

    // ------------------------------------------------------------------
    // 多选调节（批量刷大小 / 透明 / 字号 / 形状）
    // ------------------------------------------------------------------

    /**
     * 是否停在"多选调节"面板上。
     *
     * 这时三条滑条不再作用于单个 mSel，而是作用于 mSelSet 里勾中的那批。
     */

    /** 是否停在"多选形状"面板上（第三行显示圆形 / 四边形）。 */


    // ------------------------------------------------------------------
    // 网格 / 吸附
    // ------------------------------------------------------------------

    /** 是否停在"网格 / 吸附"配置面板上。 */
    /** 编辑期是否画网格线。 */
    private boolean mGridShow;
    /** 拖完松手时是否自动吸附到网格 / 邻近按键的中心线。 */
    private boolean mGridSnap;
    /** 网格列数（竖向线把屏幕切成几列）。 */
    private int mGridCols = 8;
    /** 网格行数。 */
    private int mGridRows = 12;
    /** 列数 / 行数的 − + 四个步进按钮。 */
    /** 吸附是否也认网格线。关掉时网格纯装饰，吸附只认"和别的键同轴"。 */
    private boolean mGridFollow;
    /** 吸附是否看齐其它按钮的中心线。关掉后只剩网格吸附（跟随网格开着才有用）。 */
    private boolean mSnapElem = true;
    /**
     * 吸附灵敏度档位（0..SENS_NAMES.length-1），默认 1 = 微微。
     *
     * 【为什么默认是最小可用档而不是中间档】
     *   吸附太灵敏会把键硬拽走 —— 想微调位置的时候手指一松就跳开了，
     *   比不吸附还难用。默认给"够得着但不粘手"的最小档。
     */
    private int mSnapLevel = 1;
    /** 灵敏度倍率（按半径算），和档位一一对应，上限刻意压低。 */
    private static final float[] SENS_RATIO = {0.15f, 0.22f, 0.30f, 0.40f, 0.50f};
    private static final float[] SENS_MIN_DP = {5f, 8f, 11f, 15f, 19f};
    private static final String[] SENS_NAMES = {"极微", "微微", "轻微", "中等", "较高"};
    private static final float SENS_MAX_DP = 36f;
    /** 网格设置列表每行右侧的 − / +（只有数值行有，开关行留空）。 */
    /** 「固定显示」：当前在配哪个模板 */
    /** 第二级列表的行：code 见 fixCodeOf()，名字另存一份省得每次算 */
    /** 从 LIST_PAD / LIST_KEY 挑完键后回到 LIST_FIX，而不是真的建到当前布局上 */
    /** 正在编辑哪个组合键（槽位下标）。 */
    /**
     * 上一次由 syncComboAutoName **自动写进去**的名字。
     *
     * 用来判断"这个名字还是不是自动的"：当前名字还等于它就说明没被手动改过。
     * 手动改名时置 null，链条断掉，之后加键不再覆盖用户的名字。
     */
    /** 正在给组合键的第几格挑键（COMBO_PICK_NONE = 没在挑）。 */
    /** 第二级正在配「功能键」（跨模板），不是某个具体模板 */
    /**
     * 每个 code 在**干净模板**里的默认 hidden。
     *
     * 【为什么要记】像 L2(键) 这种，模板里本来就是 hidden=true。
     * 不记的话"没写任何覆盖"会被当成"显示" ——
     * 于是那一行的循环是 显示 -> 隐藏 -> 删除，
     * 点一下变「隐藏」（本来就是隐藏，看着没反应），
     * 再点变「删除」，**永远没有真正把它显示出来的那一档**。
     */
    private final java.util.HashMap<Integer, Boolean> mFixDefHide =
            new java.util.HashMap<Integer, Boolean>();
    /** 功能键那页每行右侧的三个模板胶囊 */

    // ---- 列表搜索 ----
    //   键盘候选键有 104 个、手柄元素也有二十几个，一个个翻太慢，
    //   所以给"条目多"的列表加搜索。空串 = 不筛选。
    //
    // 【为什么要跳到 app】
    //   悬浮窗上弹不出可用的输入法，只有 Activity 里的 EditText 能调起
    //   （和改名 / 导入同一个道理），所以要离开游戏，得先确认一次。
    /** 显示位置 -> 原始位置的映射（只在有搜索词时生效） */
    /** 标题栏右上角的「搜索」按钮 */
    /** 胶囊底色要按状态换（绿/红/灰），不能借用别处正在用的 paint */


    // ---- 列表里可直接拖的调节条 / 预览盘 ----
    //
    // 扳机力度和摇杆坐标用 −/+ 一步步点太慢，改成直接在条 / 盘上
    // 点一下或拖一把。这里存它们在**内容坐标**里的几何（和 mListItemRects
    // 同一套，命中时要减掉 scroll，见 hitListSub）。
    /** 当前手指压在哪个控件上（ADJ_NONE = 没有，走原来的滚动 / 点击）。 */
    /** 正在拖控件的是哪根手指（避免第二根手指乱入把值带跑）。 */
    /** 扳机力度条（内容坐标）。 */
    /** 摇杆预览盘：外接矩形（内容坐标）。 */
    /** 预览盘半径（内容坐标）。 */

    // ---- 对齐射线（吸附可视化）----
    /**
     * 正在拖动的那个元素，拖动中才非 NONE。
     * 用它决定"要不要画全场的对齐线"—— 没拖动时不画，免得一堆线糊住画面。
     */
    /**
     * 本次吸附命中了谁的竖线 / 横线（NONE 表示没吸上）。
     * 命中时那条线画成高亮色，一眼能看出"是跟这个键对齐的"。
     */
    private int mSnapHitX = NONE;
    private int mSnapHitY = NONE;
    private final Paint mRayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRayHitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private static final int GRID_COLS_MIN = 2;
    private static final int GRID_COLS_MAX = 40;
    private static final int GRID_ROWS_MIN = 2;
    private static final int GRID_ROWS_MAX = 40;
    private static final String PREFS_GRID = "grid_cfg";
    private static final String GK_SHOW = "show";
    private static final String GK_SNAP = "snap";
    private static final String GK_COLS = "cols";
    private static final String GK_ROWS = "rows";
    private static final String GK_FOLLOW = "follow";
    private static final String GK_ELEM = "snap_elem";
    private static final String GK_SENS = "sens";
    /**
     * 多选的目标集合，单独存一份。
     *
     * 不能用 mListSel —— 那是列表的勾选状态，closeList() 会清空，
     * 而调节面板要在列表关掉之后继续用这批目标。
     */
    /** 改底色时的选中快照（见 askCustomColor 注释）。 */
    /** 这次改底色走网页调色盘还是手输（见 askCustomColor）。 */
    private boolean mColorUseWeb = false;
    /**
     * 打开取色器时那个元素的**当前色（含 alpha）**。
     *
     * 【网页调色盘要拿它补回透明度】
     *   <input type="color"> 只能表达 #RRGGBB，物理上带不了 alpha。
     *   而 G 的默认色 0xC0336699 自带 75% 透明度 —— 走一趟网页取色
     *   就被写成完全不透明，看着和原来不是一个色（更深/更实）。
     *   所以网页回传时把这里的 alpha 补回去。
     */
    private int mColorInit = 0xFFFFFFFF;
    /** 0 = 改普通 btnColor；1 = 改穿透开色；2 = 改穿透关色。 */
    /** 这次改底色会应用到几个键（见 askCustomColor）。 */
    /**
     * 「布局调节」开关。
     *
     *   关：大小滑条 = 原地改各自尺寸，位置不动，间距也不动。
     *   开：大小滑条 = 整体缩放倍率 —— 按钮连同彼此的间距一起
     *       朝中心按钮收缩/放大，等价于把这一片当成一个整体来缩放。
     */
    /**
     * 整体缩放的中心按钮（在 mSelSet 里挑一个）。
     *
     * 收缩是"朝它靠拢"：它自己位置不动，其余按钮按倍率向它聚。
     * 所以它决定了这一片缩完之后停在哪。
     */
    /** 开启「布局调节」那一刻的快照：rx / ry / scale。k 永远相对它算。 */
    private final float[] mAdjSnapRx = new float[PadLayout.N];
    private final float[] mAdjSnapRy = new float[PadLayout.N];
    private final float[] mAdjSnapScale = new float[PadLayout.N];
    /** 当前整体缩放倍率。 */
    /** 整体缩放倍率的区间。下限留到 0.35 才够把一片键压得很紧。 */
    private static final float ADJ_K_MIN = 0.35f;
    private static final float ADJ_K_MAX = 1.60f;
    /**
     * 三条滑条在非布局模式下"统一刷成多少"的缓存。
     *
     * 不缓存的话，滑条值取自第一个选中元素，拖动后各元素虽然一致了，
     * 但一旦某个元素被单独改过，下次进来滑条会跳到那个元素的值上，
     * 看起来像"面板没记住我上次的设置"。
     */
    /** 「布局调节」开关按钮（提示行左端，占原「形状」入口的位置）。 */
    /** 「中心：XX」按钮，点它去挑中心按钮。 */

    private static final int SL_SIZE = 0;
    private static final int SL_ALPHA = 1;
    /** 标签字号倍率 */
    private static final int SL_TEXT_SIZE = 2;
    /** 形状模式下的两条：宽 / 高（复用 SL_SIZE / SL_ALPHA 两个下标） */
    private static final int SL_W = 0;
    private static final int SL_H = 1;
    /**
     * 透明度那条显示成百分还是 256 制。默认百分（false）。
     *
     * 只影响**显示**：底下存的 alpha 一直是 0..1 的浮点，
     * 切换单位不会改动任何真实值，也不存在换算误差。
     */
    private boolean mAlphaRawUnit = false;
    /** 数值文字的热区，点它切单位。 */
    final RectF[] mSlValRect = new RectF[SL_COUNT];
    /** 形状模式下的两条：长 = 宽倍率，宽 = 高倍率（第三格用不到，留空） */
    /** 摇杆设置下的第一条：范围。第二条以后留着扩展。 */

    private static final float DEAD = 0.12f;
    private static final int DRAG_SLOP = 6;

    /**
     * L2 / R2 画成扳机：竖直胶囊形滑轨 + 里面一个圆形滑块，
     * 模拟真实手柄扳机的按压行程。
     * TRACK_H 是"轨道高度 ÷ 滑块半径"，3 表示轨道高 = 3 个滑块半径，
     * 扣掉滑块本身直径（2r）后，可滑动行程正好是 1 个 r。
     * 和 PadLayout.TRACK_H_RATIO 是同一个数，那边算布局、这边画图形。
     */
    private static final float TRACK_H = PadLayout.TRACK_H_RATIO;
    /**
     * 带轨道的扳机：点一下（不滑） = 最微弱的一档，按住往上滑 = 越滑越深。
     *
     * 这是"按下变满值"之前的那一版机制。现在两个版本并存：
     *   带轨道（L2 / R2）      -> 这一套，可以精细控制按压程度
     *   不带轨道（L2B / R2B）  -> 普通圆按钮，按下就是满值，松开归 0
     * 想要哪种手感，就去「隐藏按钮」里把对应的放出来、另一个藏起来。
     */
    private static final float TRIG_MIN = 0.15f;

    /**
     * 传给 PadLayout 的 bottomLimit：<=0 表示"底部不限制"。
     *
     * 屏幕下沿那条不再留给编辑面板 —— 面板是临时的（右上角「−」能收成窄边），
     * 按键位置是长期生效的，让长期的给临时的让路说不过去。
     * 现在纵向只有顶部 mTopGuard 一条约束。
     */

    private final SparseIntArray mPointers = new SparseIntArray();

    private final Paint mStickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBtnPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBtnOnPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTextHaloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mCollapsePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** G / 悬浮球专用：样式全在 BubbleView.drawBall 里设，这里只提供画笔。 */
    private final Paint mBallPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBallRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBallTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 穿透开关：开 = 绿，关 = 红。颜色跟着开关状态走，所以只留一支。 */
    private final Paint mPassPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /**
     * 界面按钮的**层序表**：下标越小越靠上（越晚画 / 越先命中）。
     *
     * 【为什么必须是一张表】
     *   绘制顺序和点击顺序原来各写一遍：
     *     绘制：drawUiButtons 里的调用先后
     *     点击：hitElement 里的 if 链先后
     *   两套各自维护，加一个按钮就得记得改两处 —— 漏改就打架，
     *   表现就是"画面上看到 A 在上面，点下去却是 B"。
     *
     *   现在只维护这一张表：
     *     绘制：逆序遍历（下标大的先画，被后画的盖住 → 在下层）
     *     命中：正序遍历（下标小的先判 → 上层优先）
     *   同一次遍历、方向相反，天然互为镜像，不可能再不一致。
     *
     * 以后加界面按钮，只往这里插一个下标即可，别再去改两个地方。
     */
    private static final int[] UI_Z = {
            PadLayout.I_COLLAPSE,   // 最上：任何情况下都盖在别的按钮之上
            PadLayout.I_EDIT,
            PadLayout.I_LAYOUT,
            PadLayout.I_PASS,
            PadLayout.I_FLOAT,      // G：和「收」同位置，但只在悬浮窗布局显示
    };

    /** 界面按钮的命中判定：按 UI_Z 顺序，越靠上越先命中。 */
    private int hitUiButton(float x, float y) {
        for (int k = 0; k < UI_Z.length; k++) {
            int i = UI_Z[k];
            if (pickable(i)
                    && dist(x, y, mPx[i], mPy[i]) <= radiusOf(i) * 1.25f) {
                return i;
            }
        }
        return NONE;
    }
    /** 触摸板拖动的上一采样点（像素）。增量模型：发完就挪到当前点。 */
    private float mMouseLastX;
    private float mMouseLastY;

    /**
     * 【本次手势的起点】手指按下那一刻的位置（像素）。
     *
     * 预览线从这里发射，而不是从触摸板中心 ——
     * 手指在触摸板最左边按下，线就以最左边那个点为原点。
     * 这样"线"和"手指实际划过的那一段"是重合的：
     * 手指从哪儿开始划，画面上就从哪儿开始长，
     * 目光不用在"手指"和"板中心"之间来回跳。
     */
    private float mMouseOriginX;
    private float mMouseOriginY;

    /**
     * 【测试开关】触摸板的两种发送时机。
     *   false = 边滑边发：每来一个 MOVE 就发一条相对位移
     *   true  = 松手才发：滑动全程只记账，手指抬起时一次性吐出去
     *
     * 它存在的唯一目的是验证一个系统行为：
     * 只要 App 发出鼠标事件，系统就认为"输入源变了"，
     * 于是给悬浮窗发 ACTION_CANCEL，当前手势就此终止 ——
     * 手指还按在屏幕上，但 MOVE 再也不会来，
     * 表现就是"滑一小段就卡住，得抬手重来"。
     *
     * 松手才发时滑动过程中一个鼠标事件都没有。
     * 如果这样滑动变得顺滑，就证实取消确实是鼠标事件引起的。
     *
     * 切换：在触摸板上**双击**（快点两下，别拖动）。
     */
    private static boolean sMouseDefer = true;

    /**
     * 触摸板拖动时要不要画落点预览。
     *
     * 由「设置」里的开关控制（默认关）。关掉之后滑动时只走鼠标事件，
     * 不画那个点 / 箭头 —— 预览本身不影响鼠标功能，纯显示。
     *
     * 读的是全局 settings，和 MainActivity 那两个开关同一份文件，
     * 所以主界面改完、悬浮窗这边立刻生效（SharedPreferences 有内存缓存，
     * 每次画读一次的开销可以忽略）。
     */
    public static final String PREF_MOUSE_PREVIEW = "mouse_pad_preview";
    public static final boolean MOUSE_PREVIEW_DEFAULT = false;

    static boolean mousePreviewOn(android.content.Context ctx) {
        if (ctx == null) return MOUSE_PREVIEW_DEFAULT;
        return ctx.getSharedPreferences("settings", 0)
                .getBoolean(PREF_MOUSE_PREVIEW, MOUSE_PREVIEW_DEFAULT);
    }

    /** 松手才发模式下累计的位移（已乘过灵敏度）。 */
    private float mMouseAccX;
    private float mMouseAccY;

    /** 本次手势里手指是否真的移动过 —— 用来区分"拖动"和"点两下"。 */
    private boolean mMouseMoved;
    /** 上一次轻点触摸板的时间，用来认双击。 */
    private long mMouseTapMs;

    /**
     * 鼠标槽位 -> MouseReport 的按键号。
     * 触摸板不是按键，返回 -1；滚轮也不是按键，同样返回 -1。
     */
    private static int mouseButtonOf(int elem) {
        if (elem == PadLayout.I_MOUSE_L) return MouseReport.BTN_LEFT;
        if (elem == PadLayout.I_MOUSE_R) return MouseReport.BTN_RIGHT;
        if (elem == PadLayout.I_MOUSE_M) return MouseReport.BTN_MIDDLE;
        return -1;
    }

    /** 滚轮槽位 -> 一次滚几格（正 = 向上）。不是滚轮返回 0。 */
    private static int mouseWheelOf(int elem) {
        if (elem == PadLayout.I_MOUSE_WU) return 2;
        if (elem == PadLayout.I_MOUSE_WD) return -2;
        return 0;
    }

    /**
     * 触摸板灵敏度：手指移动 1px 对应鼠标几格。
     * 1.0 会觉得太慢（手指要划很长才能穿过屏幕），1.5 是笔记本触摸板的手感。
     */
    private static final float MOUSE_SENS = 1.5f;

    /** 位移是 int8，超了会被截断 —— 截断就等于丢一段，光标少走。 */
    private static int clampDelta(int v) {
        if (v > 127) return 127;
        if (v < -127) return -127;
        return v;
    }

    private final Paint mEditPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStatusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStatusTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStatusHaloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 关联挪动成员的标记圈 */
    private final Paint mLinkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 编辑模式下给隐藏按钮打的红叉 */
    private final Paint mHiddenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /**
     * 组合键编辑列表里「删」按钮的底色。
     *
     * 【为什么不共用 mToolBtnPaint】
     *   它在别处会被反复 setColor / setAlpha，画到组合键这行时状态不确定 ——
     *   表现为矩形存在（能点中）但画不出来（看不见）。
     *   这里是固定的浅底 + 深描边，只给「删」用。
     */
    private final Paint mComboDelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 编辑期的网格参考线。半透明白，压在游戏画面上也能看清又不糊住细节。 */
    private final Paint mGridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mArmPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mArmOnPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /**
     * 十字键四条臂、摇杆帽这类"白色实心块"的灰色描边。
     *
     * 不能直接用 mRingPaint：那个是 5f 固定宽度，用在细小的十字臂上
     * 会把整条臂糊住。这里宽度按元素半径现算（见 drawDpad / drawStick）。
     */
    private final Paint mOutlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /**
     * 摇杆"浮动范围"预览圈。
     *
     * 关掉固定摇杆后手上有条范围滑条，但如果画布上什么都不画，
     * 拖滑条就只是数字在动、看不出范围到底多大 —— 等于没有预览。
     */
    private final Paint mRangePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSlThumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 禁用滑条的轨道 / 滑块：比正常态暗一档，但还看得见。 */
    private final Paint mSlTrackDimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSlThumbDimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private volatile boolean mConnected;
    private volatile boolean mStale;
    private volatile int mDaemonVer = -1;
    private volatile int mSent;
    private volatile String mErr;

    // 布局
    private final int[] mDown = new int[PadLayout.N];
    private float mStickR;
    private float mBtnR;
    private float mDpR;

    // 摇杆当前偏移
    private float mLsKx, mLsKy;
    private float mRsKx, mRsKy;
    /** roundStick() 的返回缓冲，避免每帧 new（见那里注释） */
    private final float[] mStickTmp = new float[2];
    private int mHat = 8;
    /** L2 / R2 当前按压程度 0..1（只有这两个下标在用） */
    private final float[] mTrigVal = new float[PadLayout.N];
    /** 按下那一下的手指 y 和当时的按压值，滑动时按位移增量算新值 */
    private final float[] mTrigAnchorY = new float[PadLayout.N];
    private final float[] mTrigAnchorT = new float[PadLayout.N];

    // 编辑模式状态
    /** 从 app 自己的界面里打开编辑模式：铺一层黑底，否则底下是白花花的设置页，按键看不清 */
    private float mDragDx, mDragDy;
    private float mDownX, mDownY;
    private boolean mMoved;
    /** 摇杆设置里的「固定摇杆」开关行，占滑条第一条的位置。 */
    /**
     * 多选时提示行右段的「布局调节」/「中心」。
     *
     * 【为什么不复用 mAdjLayoutBtnRect / mAdjAnchorBtnRect】
     *   那两个是贴着提示行左端放的，而多选时左端已经被「更多选项」+「返回」
     *   占了 —— 复用就会叠在一起。网格配置面板仍然用原来那两个（它不画更多选项）。
     */
    /** 面板底边（已扣掉安全边距），背景只画到这里 */
    /** 底部安全边距：面板要抬到它上面，避开系统手势条 */
    private float mSafeBottom;
    /** 展开 / 收起两种高度，以及"收/编"的方形按钮位置 */
    private float mPanelHExpanded;
    private float mPanelHCollapsed;
    /** 收起态右上角那个「v」：再收小一档，只留右下角加号 */
    /** 最小态唯一的圆形加号，贴在右下角 */
    private final RectF mPanelFabRect = new RectF();
    /** 面板当前是 FULL / BAR / MIN 哪一档 */
    /** 顶部保护区下沿：按键不许拖到这条线以上 */
    private int mSlDrag = NONE;
    private int mSlDragPointer = NONE;
    /**
     * 关联挪动：这一组元素拖动时同步位移（含主元素自己）。
     *
     * 这里刻意用 boolean[] 而不是 Set<Integer>：
     * Set<Integer> 里是 @Nullable Integer，拿来当下标用要拆箱，
     * R8 会报 "Cannot constrain type: @Nullable java.lang.Integer for value: vNNN
     * by constraint: INT"，直接编译不过。用 boolean[] 全程是 int，没有装箱。
     */
    /** 当前打开的列表模式（LIST_*），LIST_NONE = 没开 */

    // ---- 多布局 ----
    /** 打开列表那一刻的布局清单快照。列表开着时增删改都先动它。 */


    /** 布局列表每行右侧的「改名」「删」两个小按钮（按行下标） */
    /** 布局列表每行「同步」按钮（在「改名」左边）。 */
    /** 横竖屏同步：第一步勾了哪些按钮（元素下标）。 */
    /** 横竖屏同步：第二步勾了哪些属性（SD_*）。 */
    /** 布局列表每行最右侧的「分享」按钮（导出当前布局到 Download） */
    /**
     * 组合键编辑列表里每个键行右侧的「删」按钮（按行下标）。
     *
     * 长度按"最多行数"给：名字 1 行 + 最多 MAX_COMBO_ACTS 个键行 + 添加行。
     */
    /**
     * 组合键编辑列表里，每行右侧的「结束」按钮（在「删」的左边）。
     *
     * 【为什么是每行一个，而不是底部一个「添加结束」行】
     *   底部添加行只能追加到末尾，插不进两行之间 ——
     *   而"在 A 和延迟之间停"要的正是精确插入，末尾追加做不到。
     *   点哪一行的「结束」，就插在那一行后面。
     */
    /**
     * 布局列表左上角的「导入」。
     *
     * 占的是别的列表里「全选」的位置 —— 布局列表是点行切换，没有全选，
     * 那个角一直是空的，正好放它。
     */

    // ==================================================================
    // 列表右上角的分类筛选
    //
    // 两类列表各有一套分类：
    //   · 按钮列表（重置单个 / 关联挪动 / 隐藏 / 批量删除）
    //       全部 / 手柄 / 键盘
    //       手柄 = 固定元素 + 界面按钮（编 收 模式 布）
    //       键盘 = 自己创建的键盘按键
    //   · 布局列表（布局切换 / 「布」按钮）
    //       全部 / 手柄 / 键盘 / 空白 —— 按**模板**分，多一个"空白"
    // ==================================================================
    /**
     * 按钮列表里的「空白」= 用户建的空白按钮。
     *
     * 【和布局列表的 CAT_BLANK 撞过 3】
     *   原来 CAT_UI 也是 3，两个含义共用一个下标，靠"列表类型不同"来区分
     *   —— 一旦按钮列表也想有「空白」，这个共用就彻底不够用了。
     *   现在空白 = 3（两类列表同义：按钮列表指空白按钮，布局列表指空白模板），
     *   功能 = 5，各自独立，不再靠"哪个列表在用"来解释下标含义。
     */
    /** 仅按钮列表有：组合键（含十字架） */
    /** 仅按钮列表有：功能键 = 编 / 布 / 收 / 透 */
    /** 按钮列表的分类数 */
    /** 布局列表的分类数：按模板分，只有 4 项，没有「组合」「功能」 */

    /**
     * 分类名。
     *
     * 下标 3 在两类列表里含义不同：
     *   按钮列表 -> 「功能」（编 / 布 / 收）
     *   布局列表 -> 「空白」（模板类型）
     * 共用一个数组，靠列表类型区分，见 catCount() / listItemName。
     */



    /** 当前选中的分类 */
    /** 分类下拉是否展开 */
    /** 右上角那个「全部 v」按钮 */
    /** 展开后的分类行 */
    /**
     * 分类浮层的每一项。
     *
     * 【容量取两类列表的最大值】
     *   按钮/布局列表 4 项（CAT_MAX），常用工具 5 项（TCAT_MAX）。
     *   用 CAT_MAX=4 的话，工具列表展开第 5 项时写 mListCatItemRects[4]
     *   直接数组越界崩溃。
     */
    /** 左上角「全选」 */

    /**
     * 按分类筛过之后真正显示出来的列表。
     * mLayoutMetas 是完整快照，这个是"当前该画哪几行"。
     */



    /** 布局列表底部的「新建」按钮。
     *  不用 mListOkRect —— 那个走的是通用「确定」逻辑（confirmList），
     *  语义不对。 */
    /** 新建布局时选中的模板，默认手柄 */

    // ==================================================================
    // 内置提示条（banner）
    //
    // 【为什么不能用 Toast】
    //   GamepadView 自己就是铺满全屏的悬浮窗，Toast 是系统窗口，
    //   层级在悬浮窗**下面** —— 弹出来直接被自己盖住，什么都看不见。
    //   批量删除的"没有按钮"、导出的"已保存到…"这类提示就全丢了。
    //
    //   所以改成在自己画布最顶层画一条提示，盖住列表和对话框，
    //   几秒后自动消失。
    // ==================================================================
    /** 切换穿透后的小字提示，以及它的过期时间。 */
    private String mPassHint = null;
    private long mPassHintUntil = 0L;
    private static final long PASS_HINT_MS = 3000L;

    /** 到期时间戳（System.currentTimeMillis） */
    private static final long BANNER_MS = 2800L;

    /** 提示条隐藏任务：到点清掉文字并重绘。 */
    private final Runnable mBannerHide = new Runnable() {
        @Override
        public void run() {
            if (mBannerText != null
                    && System.currentTimeMillis() >= mBannerUntil) {
                mBannerText = null;
                invalidate();
            }
        }
    };

    // ==================================================================
    // 内置对话框
    //
    // 【为什么不能用 AlertDialog】
    //   GamepadView 是悬浮窗里的 View，context 是 **Service**（见
    //   FloatingService: new GamepadView(this, this)），不是 Activity。
    //   AlertDialog 需要一个 Activity 的 window token，用 Service context
    //   show() 会直接抛 BadTokenException:
    //     "Unable to add window -- token null is not valid"
    //   —— 点「改名」「删」都是一按就崩。
    //
    //   所以这里自己画一个对话框，和列表同一套画法，不依赖任何系统窗口。
    // ==================================================================
    /** 重置全部 */
    /** 重置单个（按勾选的那几个） */
    /** 删除当前选中的这一个键 */
    /** 批量删除（按勾选的那几个） */
    /**
     * 改名前的确认。
     *
     * 改名要跳到 app 界面才能打字，游戏会切到后台 ——
     * 有些游戏功耗大，一进后台就被系统杀掉，所以必须先让用户确认。
     */
    /** 导入前的确认，理由同上（要跳到 app 界面选文件）。 */
    /** 搜索：要跳到 app 里用 EditText 输入，同样会离开游戏 */
    /** 组合键改名：确认"要跳到 app 界面"。 */
    /** 单个按键改名（同样要跳到 app 界面输入，先确认）。 */
    /** 自定义底色：要跳到 app 界面输 #RRGGBB，同样先确认一次。 */
    /** 横竖屏同步：先勾按钮、再勾要复制的属性，走两级列表，不用对话框。 */

    /** 对话框标题（删除时是提示语，改名时是固定标题） */
    /** 删除确认时要删的布局 id */


    /**
     * 键盘列表「连续创建」开关：勾上后点一个键不关闭列表，可以接着点下一个。
     *
     * 建多个键时省掉反复打开列表：原来建 5 个要点 6 次
     * （打开→选→关闭，重复 5 遍），现在打开一次连着点 5 下就行。
     *
     * 只对 LIST_KEY 有意义 —— 别的列表是"勾完点确定"，本来就不关。
     * 退出键盘列表时会重置为 false，下次进来是默认（单次）行为。
     */
    /** 「连续创建」勾选框的矩形（只 LIST_KEY 时有效） */

    /** 列表面板的内边距。绘制标题时要拿它算可用宽度，故存一份。 */

    /**
     * 按键列表的第 pos 项对应哪个元素下标。
     *
     * 不能直接拿下标当下标用：动态键盘按键有 16 个槽位，
     * 没创建的那些是空的，不该出现在列表里（列出来是一片看不懂的空白）。
     * 所以开列表时先把"能列出来的"元素收集到这个数组里。
     */


    /** 十字键的"+"形轮廓（复用，避免每帧新建） */
    private Path mDpadPath;
    private float mListDownY;
    private float mListScrollAtDown;

    /**
     * 本次手指按下时，列表 / 对话框是不是**已经**开着。
     *
     * 【为什么需要】
     *   打开列表是在 ACTION_DOWN 里做的（点「常用工具」按钮那一刻），
     *   于是同一次手势的 ACTION_UP 到来时列表已经是打开状态 ——
     *   它会被当成"在列表上点了一下"处理。而列表是居中弹出的，
     *   「关闭 / 确定」正好压在刚才那个按钮的位置上，
     *   于是一按就开、一抬就关，菜单根本打不开。
     *
     *   只有"按下的那一刻就已经开着"才算真的在列表上点击。
     */
    private boolean mModalWasOpenAtDown;
    /**
     * 「重置单个」勾选的属性（RD_* 各占一位）。
     *
     * 为什么单独存一份：属性列表确定后要立刻开按钮列表（LIST_RESET_ONE），
     * 而 openList() 会把 mListSel 全清掉 —— 直接共用的话属性勾选就没了。
     */
    /** 列表里每项的勾选状态 */
    /** 列表项、确定、取消、整个列表面板的矩形 */
    /** 取较大的那个：键盘表的项数可能和元素总数不一样，按大的开才不会越界。 */


    /**
     * 当前列表的"上级"是哪一个（LIST_NONE = 顶层，没有上级）。
     *
     * 【为什么要有它】多层列表（常用工具 → 按键创建 → 手柄/键盘候选表）
     *   以前点「取消」一律 closeList()，
     *   直接掉回画布，前面走的路全白费。有上级时「取消」应该退回上级，
     *   按钮文字也相应变成「返回」。
     */
    /**
     * 每一层"它自己的上级"压成栈。
     *
     * 【为什么不能用白名单】
     *   listGoBack 原先是按 parent 猜上级的上级：
     *     parent == LIST_CREATE -> LIST_TOOL
     *     parent == LIST_FIX    -> LIST_FIX_TPL
     *     其余                  -> LIST_NONE
     *   组合键那条链（更多选项 -> 十字架 -> 某方向）里，
     *   十字架这一层没在白名单里，返回后它的上级被写成 NONE，
     *   于是被当成顶层列表 —— 底栏按顶层画，右下角冒出「确定」，
     *   而点它其实什么也不会发生（这个列表没有勾选语义）。
     *
     *   白名单每加一个新列表就得记着来补一处，漏一次就是一次这种 bug。
     */


    /** 「常用工具」列表里每个条目的矩形 */
    /**
     * 常用工具列表里的分组小标题。
     *
     * 下标和 mToolListRects 对齐：mToolGroupRects[i] 非空 = 第 i 项上方
     * 要画一条分组标题（它是本组第一个可见项）。
     * 标题只是视觉分隔，不参与命中 —— 点上去什么都不发生。
     */
    /** 互换位置：记录已选的两个下标，没选的位置放 NONE */

    public GamepadView(Context context, Sink sink) {
        super(context);
        mSink = sink;
        for (int i = 0; i < T_COUNT; i++) {
            mToolRects[i] = new RectF();
        }
        for (int i = 0; i < SL_COUNT; i++) {
            mSlTrack[i] = new RectF();
            mSlHit[i] = new RectF();
            // 数值文字的热区：点它切换"百分制 / 256 制"
            mSlValRect[i] = new RectF();
        }
        mAlphaRawUnit = loadAlphaUnit();
        // 必须用数组的**实际长度**，不能用 PadLayout.N。
        //
        // 数组按 max(N, KEY_NAMES.length) 开 —— 键盘表 103 项、N 只有 54，
        // 所以数组有 103 格。而这里原来只填前 N 个，第 54~102 格是 null。
        // 于是：
        //   打开键盘列表（103 项）-> 写到第 55 项时 null.set() 崩
        //   打开别的列表（≤54 项）-> 项没事，但末尾那个清理循环
        //       for (i = n; i < mListItemRects.length; i++) 照样摸到 null，也崩
        // 表现为"所有列表一打开就崩"，跟点哪一项无关。
        for (int i = 0; i < mListItemRects.length; i++) {
            mListItemRects[i] = new RectF();
        }
        for (int i = 0; i < TL_COUNT; i++) {
            mToolListRects[i] = new RectF();
            mToolGroupRects[i] = new RectF();
        }
        for (int i = 0; i < mLayoutRenameRects.length; i++) {
            mLayoutRenameRects[i] = new RectF();
        }
        for (int i = 0; i < mLayoutSyncRects.length; i++) {
            mLayoutSyncRects[i] = new RectF();
        }
        // 
        //
        // 【每个数组必须用自己的长度循环】
        //   插入新循环时把别人的初始化行吞进自己循环体里，
        //   会让被吞的数组按**错误的长度**初始化 ——
        //   mLayoutDelRects / mLayoutShareRects 实际只有前 2 格有值，
        //   第 3 格起全是 null，一打开布局列表就 NPE 崩溃。
        for (int i = 0; i < mLayoutDelRects.length; i++) {
            mLayoutDelRects[i] = new RectF();
        }
        for (int i = 0; i < mLayoutShareRects.length; i++) {
            mLayoutShareRects[i] = new RectF();
        }
        for (int i = 0; i < mComboDelRects.length; i++) {
            mComboDelRects[i] = new RectF();
        }
        for (int i = 0; i < mComboEndRects.length; i++) {
            mComboEndRects[i] = new RectF();
        }
        for (int i = 0; i < mListCatItemRects.length; i++) {
            mListCatItemRects[i] = new RectF();
        }
        for (int i = 0; i < GI_COUNT; i++) {
            mGridMinusRects[i] = new RectF();
            mGridPlusRects[i] = new RectF();
        }
        for (int i = 0; i < mFixTplRects.length; i++) {
            for (int k = 0; k < mFixTplRects[i].length; k++) {
                mFixTplRects[i][k] = new RectF();
            }
        }

        // 网格配置是跨会话保留的（下次进编辑还是上次的格子和开关）
        loadGridCfg();

        mRingPaint.setStyle(Paint.Style.STROKE);
        // 稍微加粗一点：灰色比原来的半透明白暗，细了在亮背景上会糊掉。
        mRingPaint.setStrokeWidth(5f);
        // 灰色而不是白色：白色描边压在浅色游戏画面（雪地、天空、UI 白底）上
        // 会和背景融掉，看不清按键边界；中灰在明暗背景上都有对比。
        // 不透明度给到 E6（约 90%），再低就起不到勾边的作用了。
        mRingPaint.setColor(0xE68C8C8C);

        mStickPaint.setStyle(Paint.Style.FILL);
        mStickPaint.setColor(0xC0FFFFFF);

        mBtnPaint.setStyle(Paint.Style.FILL);
        mBtnPaint.setColor(0x66FFFFFF);

        mBtnOnPaint.setStyle(Paint.Style.FILL);
        mBtnOnPaint.setColor(0xE044D0FF);

        // 按钮里的字一律用深色（近黑），再描一圈半透明白边，
        // 这样压在亮的游戏画面上也读得出来，不会再出现"白底白字"。
        mTextPaint.setStyle(Paint.Style.FILL);
        mTextPaint.setColor(0xFF212121);
        mTextPaint.setTextAlign(Paint.Align.CENTER);

        mTextHaloPaint.setStyle(Paint.Style.STROKE);
        mTextHaloPaint.setColor(0xCCFFFFFF);
        mTextHaloPaint.setTextAlign(Paint.Align.CENTER);

        mCollapsePaint.setStyle(Paint.Style.FILL);
        // 【颜色一律存成不透明】
        //   drawRound 最后会 setA(effAlpha(i)) **覆盖**掉这里的 alpha，
        //   所以写在颜色里的 0xCC 从来没生效过 ——
        //   屏幕上看着是 100%，取色器却按 80% 显示，两个对不上。
        //   透明度只有一个来源：面板那条滑条（见 effAlpha / applyPickedColor）。
        mCollapsePaint.setColor(0xFFEF9A9A);
        mPassPaint.setStyle(Paint.Style.FILL);

        mEditPaint.setStyle(Paint.Style.FILL);
        mEditPaint.setColor(0xFFB39DDB);

        mStatusPaint.setStyle(Paint.Style.FILL);
        mStatusTextPaint.setStyle(Paint.Style.FILL);
        mStatusTextPaint.setTextAlign(Paint.Align.LEFT);
        mStatusTextPaint.setColor(0xFFFFFFFF);
        mStatusHaloPaint.setStyle(Paint.Style.STROKE);
        mStatusHaloPaint.setTextAlign(Paint.Align.LEFT);
        mStatusHaloPaint.setColor(0xCC000000);

        mSelPaint.setStyle(Paint.Style.STROKE);
        mSelPaint.setStrokeWidth(3f);
        mSelPaint.setColor(0xFFFFEB3B);

        // 关联挪动的成员：青色虚线圈，和「选中」的黄圈区分开
        mLinkPaint.setStyle(Paint.Style.STROKE);
        mLinkPaint.setStrokeWidth(4f);
        mLinkPaint.setColor(0xFF00E5FF);

        // 隐藏按钮的红叉
        mHiddenPaint.setStyle(Paint.Style.STROKE);
        mHiddenPaint.setStrokeWidth(5f);
        mHiddenPaint.setColor(0xFFFF5252);

        mPanelPaint.setStyle(Paint.Style.FILL);
        mPanelPaint.setColor(0xE8000000);

        mToolBtnPaint.setStyle(Paint.Style.FILL);
        mToolBtnPaint.setColor(0xCCE0E0E0);
        mComboDelPaint.setStyle(Paint.Style.FILL);
        mComboDelPaint.setColor(0xFFE0E0E0);

        mGridPaint.setStyle(Paint.Style.STROKE);
        mGridPaint.setColor(0x40FFFFFF);

        // 对齐射线：普通是半透明青色，命中（真的吸上了）换成实心亮青。
        // 用虚线效果更好认 —— 和网格实线一眼能分开。
        mRayPaint.setStyle(Paint.Style.STROKE);
        mRayPaint.setColor(0x9900E5FF);
        mRayPaint.setStrokeWidth(dp(1.2f));
        mRayHitPaint.setStyle(Paint.Style.STROKE);
        mRayHitPaint.setColor(0xFF00E5FF);
        mRayHitPaint.setStrokeWidth(dp(2.2f));

        mBarTextPaint.setStyle(Paint.Style.FILL);
        mBarTextPaint.setColor(0xFF212121);
        mBarTextPaint.setTextAlign(Paint.Align.CENTER);

        mOutlinePaint.setStyle(Paint.Style.STROKE);
        // 和按键环同一个灰，视觉上是一套
        mOutlinePaint.setColor(0xE68C8C8C);

        mScrollBarPaint.setStyle(Paint.Style.FILL);
        mScrollBarPaint.setColor(0x99FFFFFF);

        mArmPaint.setStyle(Paint.Style.FILL);
        mArmPaint.setColor(0x99FFFFFF);

        mArmOnPaint.setStyle(Paint.Style.FILL);
        mArmOnPaint.setColor(0xE044D0FF);

        mSlTrackPaint.setStyle(Paint.Style.FILL);
        mSlTrackPaint.setColor(0xCC5A5A5A);
        mRangePaint.setStyle(Paint.Style.STROKE);
        mRangePaint.setColor(0xFF00E5FF);

        mSlFillPaint.setStyle(Paint.Style.FILL);
        mSlFillPaint.setColor(0xCC44B0FF);

        mSlThumbPaint.setStyle(Paint.Style.FILL);
        mSlThumbPaint.setColor(0xFFFFFFFF);

        mSlTrackDimPaint.setStyle(Paint.Style.FILL);
        mSlTrackDimPaint.setColor(0xCC333333);
        mSlThumbDimPaint.setStyle(Paint.Style.FILL);
        mSlThumbDimPaint.setColor(0xFF808080);
    }

    // ------------------------------------------------------------------
    // 尺寸 / 布局
    // ------------------------------------------------------------------

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) {
            return;
        }
        mW = w;
        mH = h;
        // 尺寸真正确定了 —— 这才是同步按键窗口最可靠的时机。
        // 光靠 addView 之后那一次不够：那时 View 可能还没 measure/layout，
        // mW/mH 是 0，算出来的窗口位置全错。
        notifyHost();
        // 把真实尺寸告诉 Service：悬浮球换算位置要用**同一个**参考系。
        if (mSink != null) {
            mSink.onPadSize(w, h);
        }
        mPortrait = h > w;

        // 先算面板几何：滑条 / 按钮的排布要用。
        // 注意这里不再把面板上沿传给布局当底部限制 —— 底部不阻挡按键，
        // 默认布局和拖动都只在顶部受 mTopGuard 约束。
        computePanelMetrics();

        // 手机横竖屏切换后换一套布局存档，两边的摆放互不干扰。
        // 键盘模式要跟着带过去：正在用全键盘时转屏，
        // 应该载入"横屏键盘"那份，而不是掉回手柄布局。
        if (mLayout == null || mLayout.portrait != mPortrait) {
            // 【用记住的布局，不是每次都默认手柄】
            //   之前只传 kb（是否键盘模式），于是 load 内部按
            //   "键盘模式 -> LAYOUT_DEFAULT_KB，否则 -> LAYOUT_DEFAULT_PAD"
            //   挑一个**内置默认**。用户自己建的布局、或者切到默认键盘，
            //   收起再打开就掉了回去。
            //
            //   现在先取上次用的 id。转屏时如果当前布局有键盘变体，
            //   仍要跟着切到对应的横/竖屏存档 —— 这个由 load 内部按
            //   layoutId 自己处理，不在这里判断。
            int id = PadLayout.loadCurrentLayoutId(getContext());
            mLayout = PadLayout.load(getContext(), w, h, mPortrait,
                    id, NO_BOTTOM_LIMIT, mTopGuard);
            // 当前布局可能和"上次切换时"不一致（比如第一次运行），
            // 记下来保证下次一致
            PadLayout.saveCurrentLayoutId(getContext(), mLayout.layoutId);
        }
        computeGeometry();
        // 旋转时列表还开着的话，行列数和矩形都得按新尺寸重算
        if (mListMode != LIST_NONE) {
            layoutList();
        }
    }


    /** 只算编辑面板本身的位置和尺寸，不依赖布局存档。 */
    private void computePanelMetrics() {
        mBarH = Math.max(dp(54f), Math.min(mW, mH) * 0.085f);
        // 展开时做得高一些：滑条排在面板上半部，位置比之前明显靠上；
        // 「重置 / 完成」仍固定在面板底边，不跟着往上跑。
        // 同时按屏高封顶：矮屏（比如横屏）上面板不能吃掉半块屏幕。
        mPanelHExpanded = Math.min(mBarH * 2.9f, mH * 0.26f);
        // 内容需要的最低高度：提示行 + **三条**滑条 + 两行工具按钮。
        // 720p 那种矮横屏按 h*0.26 算只有 187px，第二行工具会压到滑条上，
        // 所以给个下限。加「字体」滑条后从 2 条变 3 条，下限 96dp -> 128dp：
        // 96dp 时三条滑条会被挤到 15dp 一条，根本拖不动。
        // 编辑面板是临时的，矮屏上占多一点可以接受。
        mPanelHExpanded = Math.max(mPanelHExpanded, dp(128f));
        // 收起后只剩一条窄边，底部按键全都露出来，不再被挡住
        mPanelHCollapsed = mBarH * 0.62f;
        mSafeBottom = Math.max(dp(12f), mH * 0.02f);
        mPanelBottom = mH - mSafeBottom;
        mPanelH = (mPanelState == PANEL_FULL) ? mPanelHExpanded : mPanelHCollapsed;
        mPanelTop = mPanelBottom - mPanelH;
        // 最小态那个加号：右下角，尺寸和右上角那个「+」完全一样（ts 同一套算法）。
        // 之前做成大圆（直径取 min(w,h)*8.5%）反而突兀，也挡住更多按键。
        float fabD = mBarH * 0.5f;
        mPanelFabRect.set((float) mW - dp(8f) - fabD,
                (float) mH - mSafeBottom - fabD,
                (float) mW - dp(8f),
                (float) mH - mSafeBottom);
        // 顶部保护区：按键一律排在这条线以下，
        // 免得贴到状态栏 / 挖孔那儿，也免得拖上去以后抓不回来
        mTopGuard = Math.max(dp(26f), mH * 0.035f);
    }

    /** 把 G 的像素坐标拉到和「收」一致。拖动 / relayout 之后都要调。 */

    void computeGeometry() {
        float s = Math.min(mW, mH);
        // 竖屏时屏幕窄，整体放大一点更好按
        mStickR = s * (mPortrait ? 0.13f : 0.115f);
        mBtnR = s * (mPortrait ? 0.065f : 0.058f);
        mDpR = s * (mPortrait ? 0.11f : 0.10f);

        for (int i = 0; i < PadLayout.N; i++) {
            mPx[i] = mLayout.rx[i] * mW;
            mPy[i] = mLayout.ry[i] * mH;
        }
        // 【G 的位置恒等于「收」】
        //   它不存自己的位置：这里直接取「收」的坐标，
        //   于是"拖 G 影响收、拖收影响 G"是同一个数，不需要双向同步。
        //   拖动时把位移写进 I_COLLAPSE 的 rx/ry（见 ACTION_MOVE），
        //   下一帧这里就会把 G 带过去。
        syncFloatBallPx();
        mTextPaint.setTextSize(mBtnR * 0.85f);
        mTextHaloPaint.setTextSize(mBtnR * 0.85f);
        mTextHaloPaint.setStrokeWidth(Math.max(2f, mBtnR * 0.16f));
        layoutTools();
    }


    /**
     * 【按 padType 而不是按下标】
     *   副本槽位上的摇杆，下标不是 I_LS，但半径必须还是 mStickR ——
     *   否则它会被当成普通圆按钮，摇杆看起来比别的小一圈，
     *   判定圈也对不上（hitElement 里摇杆用了 1.35 倍）。
     *
     *   界面按钮（编 / 收 / 布）的 padType 是 0，走 elem 分支拿到 0.85 倍。
     */
    float radiusOf(int i) {
        float sc = mLayout.scale[i];
        int proto = (mLayout != null && mLayout.padType[i] != 0)
                ? PadLayout.protoOfType(mLayout.padType[i]) : i;
        //
        // 【十字架组合键按方向键的半径】
        //   它画出来就是个十字键，padType 却恒为 0（组合键槽位），
        //   proto 于是等于槽位下标（182+），只能落 default 拿 mBtnR ——
        //   比方向键的 mDpR 小一截，同一个十字一个大一一个小。
        if (PadLayout.isComboSlot(i) && mLayout != null
                && mLayout.isComboUsed(i) && mLayout.comboCross[i]) {
            return mDpR * sc;
        }
        switch (proto) {
            case PadLayout.I_LS:
            case PadLayout.I_RS:
                return mStickR * sc;
            case PadLayout.I_DPAD:
                return mDpR * sc;
            case PadLayout.I_COLLAPSE:
            case PadLayout.I_EDIT:
            case PadLayout.I_LAYOUT:
            // 【G 按悬浮球的真实大小画】
            //   球是 dp(52)，所以半径 dp(26)。
            //   之前跟「收」走 mBtnR*0.85 —— 那是屏幕短边的百分比，
            //   和球的绝对大小对不上，于是"预览"和收起后不是一个尺寸。
            case PadLayout.I_FLOAT:
                // 球那边是 min(w,h)/2 - 3px，这里不减 3 就比球大一圈
                return dp(26f) * sc - 3f;
            default:
                return mBtnR * sc;
        }
    }

    /**
     * 按钮的半宽 / 半高。
     *
     * 圆形时两者都等于 radiusOf()；矩形时按格子撑开一点 ——
     * 圆内切于格子，四角留白，一整片键看着散；
     * 矩形把格子填满，才像"一块键盘"。
     * 系数 1.10 / 1.06 是照 resetKeyboard() 里格子宽高算的（见那里的注释）。
     */
    /** 半宽 = 半径 × 宽倍率。圆形时倍率 1 就是正圆，改大就拉成横向椭圆。 */
    float halfW(int i) {
        return radiusOf(i) * mLayout.widthMul[i];
    }

    /** 半高 = 半径 × 高倍率。和 halfW 不等就是椭圆 / 长方形。 */
    float halfH(int i) {
        return radiusOf(i) * mLayout.heightMul[i];
    }

    /** 画一个按钮的底：圆形或圆角矩形，按 shape 选。 */
    private void drawButtonBody(Canvas c, int i, Paint p) {
        float hw = halfW(i);
        float hh = halfH(i);
        if (mLayout.shape[i] == PadLayout.SHAPE_RECT) {
            mTmpRect.set(mPx[i] - hw, mPy[i] - hh, mPx[i] + hw, mPy[i] + hh);
            c.drawRoundRect(mTmpRect, Math.min(hw, hh) * 0.30f,
                    Math.min(hw, hh) * 0.30f, p);
        } else {
            // 用 drawOval 而不是 drawCircle：宽高倍率不同时就是椭圆。
            // drawCircle 只有半径一个参数，做不出"拉长的圆"。
            mTmpRect.set(mPx[i] - hw, mPy[i] - hh, mPx[i] + hw, mPy[i] + hh);
            c.drawOval(mTmpRect, p);
        }
    }

    /** 按钮描边，形状跟 drawButtonBody 一致。 */
    private void drawButtonOutline(Canvas c, int i, Paint p) {
        float hw = halfW(i);
        float hh = halfH(i);
        if (mLayout.shape[i] == PadLayout.SHAPE_RECT) {
            mTmpRect.set(mPx[i] - hw, mPy[i] - hh, mPx[i] + hw, mPy[i] + hh);
            c.drawRoundRect(mTmpRect, Math.min(hw, hh) * 0.30f,
                    Math.min(hw, hh) * 0.30f, p);
        } else {
            mTmpRect.set(mPx[i] - hw, mPy[i] - hh, mPx[i] + hw, mPy[i] + hh);
            c.drawOval(mTmpRect, p);
        }
    }

    /** L2 / R2 是扳机（滑轨 + 滑块），其余都是圆按钮。 */
    /**
     * 是不是带轨道的扳机。
     *
     * 【按 padType，不是按下标】副本槽位上的 L2 也要按滑轨来收边和判定，
     * 否则它会被当成普通圆按钮：轨道上下两端点不到、收边也会算少。
     */
    boolean isTrigger(int i) {
        int proto = (mLayout != null && mLayout.padType[i] != 0)
                ? PadLayout.protoOfType(mLayout.padType[i]) : i;
        return proto == PadLayout.I_L2 || proto == PadLayout.I_R2;
    }

    /** 扳机轨道的半高。收边、命中判定都得用它，不能用圆的半径。 */
    float trackHalfH(int i) {
        return radiusOf(i) * TRACK_H / 2f;
    }

    /** 滑块在轨道里能走的距离 = 轨道高 - 滑块直径。 */
    private float trackTravel(int i) {
        float travel = radiusOf(i) * (TRACK_H - 2f);
        return travel < 1f ? 1f : travel;
    }

    /** 滑块中心的 y：t=0 贴轨道底，t=1 贴轨道顶。 */
    private float knobY(int i, float t) {
        float half = trackHalfH(i);
        float r = radiusOf(i);
        return mPy[i] + half - r - t * trackTravel(i);
    }

    // ------------------------------------------------------------------
    // 方向键：显示和触发共用同一个 hat 值
    // ------------------------------------------------------------------

    /**
     * 手指相对十字键中心的偏移 -> HID hat 值。
     *
     * HID 规定 hat 从正上方开始顺时针编号：
     *   0=上 1=右上 2=右 3=右下 4=下 5=左下 6=左 7=左上 8=松开。
     *
     * 屏幕的 y 轴是向下为正的，所以传 -dy 给 atan2 把 y 翻成"向上为正"，
     * 这样 atan2(dx, -dy) 得到的角度正好是"从上起、顺时针"，除以 45 度
     * 四舍五入就直接是 hat 编号，不需要再查表转换。
     */
    private static int hatFromDelta(float dx, float dy) {
        if (dx == 0f && dy == 0f) {
            return 8;
        }
        double deg = Math.toDegrees(Math.atan2(dx, -dy));
        if (deg < 0) {
            deg += 360.0;
        }
        return ((int) Math.round(deg / 45.0)) % 8;
    }

    // ------------------------------------------------------------------

    /**
     * 由 Service 每秒调用一次。
     *
     * @param stale 守护进程是旧版，HID 描述符还是老的，按键/摇杆映射对不上
     * @param daemonVersion 守护进程自报的映射版本，-1 = 问不到（也是旧版）
     */
    public void setStatus(boolean connected, int sent, String err,
                          boolean stale, int daemonVersion) {
        if (mConnected != connected || mStale != stale || mDaemonVer != daemonVersion
                || mSent != sent || (err != null && !err.equals(mErr))) {
            mConnected = connected;
            mStale = stale;
            mDaemonVer = daemonVersion;
            mSent = sent;
            mErr = err;
            invalidate();
        }
    }

    public boolean isEditMode() {
        return mEditMode;
    }

    public void setEditMode(boolean on) {
        setEditMode(on, false);
    }

    /**
     * @param fromApp true = 从 app 自己的界面点「编辑按键布局」进来的，
     *                底下没有游戏画面，铺一层黑底，并且「完成」直接收起浮层。
     */
    public void setEditMode(boolean on, boolean fromApp) {
        mFromApp = on && fromApp;
        if (mEditMode == on) {
            invalidate();
            return;
        }
        if (on) {
            mEditMode = true;
        } else {
            exitEditMode();
            return;
        }
        mSel = NONE;
                    mRayElem = NONE;
        mSlDrag = NONE;
        // 形状模式跟着一起退：下次进来停在形状屏会让人以为滑条少了一条。
        // 优先级同理 —— 它是子模式，留着会直接停在只有一条滑条的屏上。
        mShapeMode = false;
        mPrioMode = false;
        mSlDragPointer = NONE;
        // 关联 / 点选都是一次性的，进出编辑模式都清掉，
        // 免得下次进来还挂着上一轮的联动组。
        clearSelection();
        closeList();
        mPanelState = PANEL_FULL;
        releaseAll();
        invalidate();
    }

    /**
     * 退出编辑模式的**唯一**收尾入口。
     *
     * 【为什么必须单独抽出来】
     *   以前「完成」是在 onTool(T_DONE) 里手工逐项清的，
     *   清的项和 setEditMode() 对不上 —— 漏了 mDlgMode、mShapeMode、
     *   mListCatOpen、mPanelState。
     *
     *   漏 mDlgMode 的后果最直接：isPassThroughActive() 要求
     *       !mEditMode && mListMode == LIST_NONE && mDlgMode == DLG_NONE
     *   三者同时成立。编辑期间弹过确认框（重置全部 / 删除此键）、
     *   关掉之后再点「完成」，mDlgMode 还留着值 ->
     *   isPassThroughActive() 恒为 false -> 服务那边 want 一直是 false
     *   -> 根本不会去重建 region，穿透自然不回来。
     *
     *   而「布局列表点取消」走的是 closeList() + invalidate()，
     *   那条路径不涉及 mEditMode，所以一直正常 ——
     *   这正是"列表取消可以、编辑完成不行"的分野所在。
     */
    void exitEditMode() {
        mEditMode = false;
        mFromApp = false;
        mSel = NONE;
                    mRayElem = NONE;
        mSlDrag = NONE;
        mSlDragPointer = NONE;
        mShapeMode = false;
        mPrioMode = false;
        // 网格配置面板是编辑期的，退出编辑要一起收掉，
        // 否则下次进编辑会直接停在配置面板上。
        mGridCfg = false;
        // 对话框是编辑期间最容易残留的一个，必须显式清
        mDlgMode = DLG_NONE;
        mDlgTargetId = NONE;
        mListCatOpen = false;
        clearSelection();
        closeList();
        // 多选面板是编辑期间的子状态，退出编辑必须一起退干净，
        // 否则下次进编辑会直接停在批量面板上（而目标集合其实已经清空了）。
        mAdjLayoutMode = false;
        mAdjAnchor = NONE;
        for (int i = 0; i < PadLayout.N; i++) {
            mSelSet[i] = false;
        }
        mPanelState = PANEL_FULL;
        releaseAll();
        invalidate();
    }

    /** 供 Service 在显示悬浮球前读取「收」按钮的最新位置。 */
    public float[] collapseRel() {
        if (mLayout == null) {
            return new float[]{0.5f, 0.045f};
        }
        return new float[]{mLayout.rx[PadLayout.I_COLLAPSE], mLayout.ry[PadLayout.I_COLLAPSE]};
    }

    /** 当前正在用的布局 id，供收起时记录下来。 */

    /**
     * 把「收」按钮摆到指定比例位置（展开时对齐悬浮球用）。
     *
     * 【为什么需要】
     *   收起时是「气泡 = 收按钮的位置」，但**展开时没人管气泡在哪** ——
     *   「收」只按自己的全局存档摆。于是：
     *     竖屏收起（气泡在右上）→ 切横屏 → 展开
     *   横屏那份存档还没有，「收」就落到默认位置（顶部中间），
     *   跟气泡刚才待的地方没关系 —— 表现就是"收不知道飞到哪去了"。
     *
     *   所以展开时反向补一次：气泡在哪，「收」就在哪。
     *   两边对称了，"点开在哪、收起就在哪"才真的成立。
     */
    public void setCollapseRel(float rx, float ry) {
        if (mLayout == null || mW <= 0 || mH <= 0) {
            return;
        }
        float r = radiusOf(PadLayout.I_COLLAPSE);
        if (r <= 0f) {
            r = dp(24f);
        }
        // 留出一个半径的余量，避免贴边时按钮被切掉一半
        float mx = r / mW;
        float my = r / mH;
        if (rx < mx) rx = mx;
        if (rx > 1f - mx) rx = 1f - mx;
        if (ry < my) ry = my;
        if (ry > 1f - my) ry = 1f - my;
        // 顶部保护区（状态栏 / 刘海）也要让开
        float topMin = (mTopGuard + r) / mH;
        if (ry < topMin) ry = topMin;

        mLayout.rx[PadLayout.I_COLLAPSE] = rx;
        mLayout.ry[PadLayout.I_COLLAPSE] = ry;
        // 全局和布局内两份都要写：
        //   全局（saveCollapseRel）供下次 load() / 气泡定位读；
        //   布局内 rx/ry 是本次显示用的，不写的话下次 load 又被全局值覆盖回去。
        PadLayout.saveCollapseRel(getContext(), mPortrait, rx, ry);
        computeGeometry();
        mLayout.save(getContext());
        invalidate();
    }

    // ------------------------------------------------------------------
    // 命中测试：先编辑面板，再「收」/「编」，最后游戏按键
    // ------------------------------------------------------------------

    /**
     * 这个元素现在能不能被点到。
     *
     * 隐藏的按钮在**非编辑模式**下不该参与命中判定 —— 注意是"不参与"，
     * 不是"命中了返回空"。之前那段写的是"点在隐藏按钮上就 return NONE"，
     * 结果它虽然不生效，却把这次触摸吃掉了：压在别的按钮上面时，
     * 那个按钮也一起点不动，表现就是"点了没反应、谁都没触发"。
     * 隐藏 = 当它不存在，判定要继续往下走。
     *
     * 编辑模式下隐藏的仍然可点，否则就再也选不中、恢复不回来了。
     */

    // ---- 图层顺序：由优先级决定谁在上面、谁先命中 ----
    //
    // 【为什么要有这套排序】
    //   以前绘制和命中都是"按类型分几趟循环"写死的：
    //   空白 → 轴类 → 圆按钮 → 鼠标 → 键盘 → 组合键。
    //   于是触摸板（鼠标那趟）永远压在所有按键之上、也永远先命中 ——
    //   它铺在下半屏，被它盖住的键就点不到了。
    //
    //   现在给每个元素一个优先级，主排序键就是它；
    //   优先级相同时按"类型档位"排，档位的顺序和原来那几趟循环一致，
    //   所以没动过优先级的布局和以前一模一样。
    private final int[] mDrawOrd = new int[PadLayout.N];
    private final int[] mHitOrd = new int[PadLayout.N];
    private final long[] mOrdKey = new long[PadLayout.N];
    private int mOrdN = 0;

    /** 绘制档位（小的先画 = 在下层）。 */
    private int drawTierOf(int i) {
        if (mLayout.isBlankUsed(i)) return 0;
        if (mLayout.isMouseUsed(i)) return 3;
        if (mLayout.isKeySlotUsed(i)) return 4;
        if (mLayout.isComboUsed(i)) return 5;
        if (isAxisType(mLayout.padType[i])) return 1;
        return 2;
    }

    /** 命中档位（小的先判 = 上层）。顺序和原来那几趟循环一致。 */
    private int hitTierOf(int i) {
        if (mLayout.isMouseUsed(i)) return 0;
        int pt = mLayout.padType[i];
        if (isAxisType(pt)) {
            int proto = PadLayout.protoOfType(pt);
            if (proto == PadLayout.I_LS || proto == PadLayout.I_RS) return 1;
            if (proto == PadLayout.I_DPAD) return 2;
            return 3;
        }
        return 4;
    }

    /**
     * 重排绘制 / 命中顺序。
     *
     * 每帧重排一次：N 一百多个，排序开销远小于一次 drawText，
     * 但省掉了"改了优先级却没重建顺序"这种最难查的错位。
     */
    private void buildOrder(boolean forHit) {
        int n = 0;
        for (int i = 0; i < PadLayout.N; i++) {
            if (!pickable(i) || PadLayout.isUiButton(i)) {
                continue;
            }
            int pr = mLayout.prio[i];
            int tier = forHit ? hitTierOf(i) : drawTierOf(i);
            // 命中要"优先级高的先判"，取反后统一按升序排
            long pk = forHit ? (PadLayout.PRIO_MAX - pr) : pr;
            mOrdKey[n] = (pk << 12) | ((long) tier << 8) | (long) i;
            n++;
        }
        java.util.Arrays.sort(mOrdKey, 0, n);
        int[] dst = forHit ? mHitOrd : mDrawOrd;
        for (int j = 0; j < n; j++) {
            dst[j] = (int) (mOrdKey[j] & 0xFFL);
        }
        mOrdN = n;
    }

    /** 按元素类型分派命中判定：鼠标矩形 / 摇杆 1.35 / 十字键 1.15 / 扳机矩形 / 其余 1.25。 */
    private boolean hitAnyElem(int i, float x, float y) {
        if (mLayout.isMouseUsed(i)) {
            return Math.abs(x - mPx[i]) <= halfW(i)
                    && Math.abs(y - mPy[i]) <= halfH(i);
        }
        int pt = mLayout.padType[i];
        if (isAxisType(pt)) {
            int proto = PadLayout.protoOfType(pt);
            if (proto == PadLayout.I_LS || proto == PadLayout.I_RS) {
                return dist(x, y, mPx[i], mPy[i]) <= radiusOf(i) * 1.35f;
            }
            if (proto == PadLayout.I_DPAD) {
                return dist(x, y, mPx[i], mPy[i]) <= radiusOf(i) * 1.15f;
            }
            float r = radiusOf(i) * 1.25f;
            float hy = trackHalfH(i) + Math.min(mW, mH) * 0.01f;
            return x >= mPx[i] - r && x <= mPx[i] + r
                    && y >= mPy[i] - hy && y <= mPy[i] + hy;
        }
        return hitElem(i, x, y, 1.25f);
    }

    private int hitElement(float x, float y) {
        // 界面按钮：按 UI_Z 表判定，和绘制顺序同源 —— 见 UI_Z 的说明。
        int ui = hitUiButton(x, y);
        if (ui != NONE) {
            return ui;
        }
        /*
          鼠标三件套优先判定。
          它们不在 isPadSlot 范围内，pickable() 里 isPadUsed 会返回 false，
          后面那几趟循环根本看不到 —— 必须在这里单独判。
          触摸板是大矩形，用矩形判定而不是圆，否则四个角点不到。
        */
        /*
          所有元素按优先级从高到低判一次。

          【为什么换成一趟】
            以前是"鼠标 → 摇杆 → 十字键 → 扳机 → 其余"五趟写死的循环，
            触摸板永远第一个被判到 —— 它铺在下半屏，
            被它盖住的按键就永远点不到了，改优先级也救不回来。

            现在顺序由 buildOrder() 算出来，优先级是主排序键，
            所以把触摸板的优先级调低，它就真的排到按键后面去。
        */
        buildOrder(true);
        for (int k = 0; k < mOrdN; k++) {
            int i = mHitOrd[k];
            if (hitAnyElem(i, x, y)) {
                return i;
            }
        }
        return NONE;
    }

    /**
     * 统一的按钮命中判定，跟着形状走。
     *
     * 圆形用椭圆方程（宽高倍率不同时就是椭圆），矩形用矩形。
     * 之前圆形分支写死 dist <= radiusOf*1.25，宽高倍率一改就判不准；
     * A/B/X/Y 这些手柄键更是完全没判矩形 —— 用户把手柄键改成方形后会
     * "看着是方的、点起来还是圆的"。
     *
     * @param tol 圆形时的放大倍数（补偿手指点不准），矩形不用
     */
    private boolean hitElem(int i, float x, float y, float tol) {
        if (mLayout.shape[i] == PadLayout.SHAPE_RECT) {
            // 矩形不放大：已经填满格子，再放大会盖到相邻键的格子上
            float hw = halfW(i);
            float hh = halfH(i);
            return x >= mPx[i] - hw && x <= mPx[i] + hw
                    && y >= mPy[i] - hh && y <= mPy[i] + hh;
        }
        float hw = halfW(i) * tol;
        float hh = halfH(i) * tol;
        if (hw <= 0f || hh <= 0f) {
            return false;
        }
        float dx = (x - mPx[i]) / hw;
        float dy = (y - mPy[i]) / hh;
        return dx * dx + dy * dy <= 1f;
    }

    /** 同一时刻一个元素只认一根手指，第二根手指按上去不会重复触发。 */
    private boolean elemBusy(int elem) {
        for (int i = 0; i < mPointers.size(); i++) {
            if (mPointers.valueAt(i) == elem) {
                return true;
            }
        }
        return false;
    }


    private int hitSlider(float x, float y) {
        // 多选面板下 mSel 是 NONE（没有"当前键"），但滑条要能用 ——
        // 它作用于整批。所以这里给 isMulti() 开个口子。
        boolean hasTarget = isMulti() || mSel != NONE;
        if (!mEditMode || mPanelState != PANEL_FULL || !hasTarget) {
            return NONE;
        }
        int n = (mShapeMode && mSel != NONE) ? 2 : SL_COUNT;
        if (mPrioMode) {
            // 优先级子面板：整块面板就这一条
            n = 1;
        } else if (mStickCfg && selHasStick()) {
            // 固定摇杆开着时范围没有意义 —— 不给拖，
            // 拖了也是白拖（代码里不读这个值）。
            int ref = firstStickInSel();
            n = (ref != NONE && mLayout.stickFixed[ref]) ? 0 : 1;
        }
        for (int i = 0; i < n; i++) {
            if (mSlHit[i].contains(x, y)) {
                return i;
            }
        }
        return NONE;
    }

    private static float dist(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2;
        float dy = y1 - y2;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    // ------------------------------------------------------------------
    // 触摸 / 按键独立窗口（点击空白穿透到游戏）
    // ------------------------------------------------------------------

    /** 通知宿主（FloatingService）重算按键窗口。 */
    public interface PassThroughHost {
        void onPassThroughChanged();
    }

    private PassThroughHost mHost;

    public void setPassThroughHost(PassThroughHost h) {
        mHost = h;
    }

    /**
     * 是否真的有"还没发出来"的组合键动作。
     *
     * 【绝不能写成 mComboPend.isEmpty()】
     *   mComboPend 是**轨道的列表**（里面固定装 COMBO_LANES 条轨道），
     *   每条轨道才是真正的动作队列。所以外层 isEmpty() **恒为 false**。
     *   拿它当判据，notifyHost() 就永远走"推迟"分支、再也不通知宿主：
     *     notifyHost 哑 -> onPassThroughChanged 不触发
     *     -> syncKeyWindows 不跑 -> 触摸状态停在进编辑前的样子
     *   结果是编辑面板画得出来却完全点不到，而按键照常能点。
     */
    private boolean comboPending() {
        for (int L = 0; L < COMBO_LANES; L++) {
            if (!mComboPend.get(L).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void notifyHost() {
        //
        // 【组合键序列播放期间不触发穿透同步】
        //   同步链是 notifyHost -> FloatingService.scheduleKeySync
        //   -> syncKeyWindows -> trySingleWindowRegion -> region 提交
        //   -> mWm.updateViewLayout（还会连着补几次）。
        //   而 **updateViewLayout 会把当前手势判成 ACTION_CANCEL**，
        //   于是刚排好的延迟序列被 releaseAll 掐掉 ——
        //   这正是"长按也等不到延迟后面的键"的根因。
        //
        //   序列播放期间画面上并没有元素移动，不需要重算穿透区域，
        //   推迟到序列播完再补一次即可。
        if (comboPending()) {
            mComboDeferNotify = true;
            return;
        }
        if (mHost != null) {
            mHost.onPassThroughChanged();
        }
    }

    /** 序列播完补上被推迟的那次穿透同步。 */
    private void flushComboDeferNotify() {
        if (mComboDeferNotify) {
            mComboDeferNotify = false;
            if (mHost != null) {
                mHost.onPassThroughChanged();
            }
        }
    }

    /**
     * 是否处于"纯游玩"状态：没在编辑、没开列表、没弹对话框。
     *
     * 只有这三种覆盖 UI 都不在的时候，空白区域才可以让点击穿透到游戏里。
     * 编辑时要拖滑条、开列表时要点确定，那些情况下必须吃掉全部触摸。
     */
    public boolean isPassThroughActive() {
        return mLayout != null && !mEditMode
                && mListMode == LIST_NONE && mDlgMode == DLG_NONE;
    }

    /**
     * 当前是**什么**挡住了穿透。日志用，值本身不参与逻辑。
     *
     * "编辑完成不穿透、列表取消却正常"这类问题，靠这个一眼就能看出
     * 是状态残留（返回非 none）还是 region 提交时序（返回 none）。
     */
    public String passThroughBlocker() {
        if (mLayout == null) {
            return "mLayout=null";
        }
        if (mEditMode) {
            return "mEditMode=true";
        }
        if (mListMode != LIST_NONE) {
            return "mListMode=" + mListMode;
        }
        if (mDlgMode != DLG_NONE) {
            return "mDlgMode=" + mDlgMode;
        }
        return "none";
    }

    /**
     * 【为什么挂在 invalidate() 上】
     *   按键位置会在很多地方变：改布局、转屏、编辑完退出、切换布局……
     *   逐个去埋点必漏（这个项目已经因为"漏一处"出过好几次 bug）。
     *   invalidate() 是"画面要重画了"的唯一入口，画的东西变了它一定被调用，
     *   所以从这里通知最保险。重算时会比对坐标，没变就不动窗口。
     */
    @Override
    public void invalidate() {
        super.invalidate();
        notifyHost();
    }

    @Override
    public void postInvalidate() {
        super.postInvalidate();
        notifyHost();
    }

    /**
     * 本 View 左上角在屏幕上的位置。
     *
     * 【为什么要存这个】
     *   mPx / mPy 是**View 内坐标**，而 WindowManager 的 x/y 是**屏幕坐标**。
     *   全屏窗口并不总是从屏幕 (0,0) 开始 —— 状态栏、导航栏、刘海屏的
     *   cutout 安全区都会让它整体下移一点。
     *   直接拿 View 内坐标去开窗口，窗口就会比按键实际画的位置偏上，
     *   表现就是"点在按键上却没反应 / 生效的是旁边的键"。
     */
    private int mViewOffX;
    private int mViewOffY;

    /** 当前自动算出的 View 屏幕偏移（px），供主界面诊断显示。 */
    public int getViewOffsetX() {
        return mViewOffX;
    }

    public int getViewOffsetY() {
        return mViewOffY;
    }

    /** 刷新 View 在屏幕上的偏移。返回是否拿到了有效值。 */
    private boolean refreshViewOffset() {
        int[] loc = new int[2];
        getLocationOnScreen(loc);
        mViewOffX = loc[0];
        mViewOffY = loc[1];
        return true;
    }

    /**
     * 收集每个按键的命中矩形（**屏幕坐标**），供 FloatingService 开独立窗口。
     *
     * out 每 5 个一组：{elem, left, top, right, bottom}；返回组数。
     */
    /**
     * 把所有可点元素的矩形并进一个 Region（**View 内坐标**）。
     *
     * 供 TouchRegionHelper 用：单窗口模式下，只有这个 Region 内的区域
     * 会吃触摸，其余全部穿透到游戏。
     *
     * @return 实际填入的矩形数量
     */
    public int buildTouchRegion(android.graphics.Rect[] out) {
        if (mLayout == null || out == null) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < PadLayout.N && n < out.length; i++) {
            if (!pickable(i)) {
                continue;
            }
            // 和 collectKeyWindows 用**完全同一套**算法，保证两种模式手感一致。
            // 注意：半径变量叫 rad 不叫 r —— 下面 int r 是右边界，同块内会重名。
            float rad = radiusOf(i);
            float hw = rad * mLayout.widthMul[i];
            float hh = rad * mLayout.heightMul[i];
            if (isTrigger(i)) {
                // 扳机是竖着的轨道，高度不走圆半径那套
                hh = trackHalfH(i);
            }
            // 1.35 = 摇杆判定倍率（圆按钮 1.25、十字键 1.15），取最大值，
            // 保证区域完整盖住"能点到"的范围
            float mx = hw * 1.35f;
            float my = hh * 1.35f;
            //
            // 【浮动摇杆要把范围圈一起圈进来】
            //   关掉"固定摇杆"后，手指落在范围圈内（哪怕在摇杆本体外）
            //   也该激活它 —— 这是 hitFloatStick 判的。
            //   但穿透模式下窗口只接触摸区里的触摸：区域是上面这个
            //   hw*1.35 的矩形，圈内、矩形外的那一圈根本送不进来，
            //   hitFloatStick 压根不会被调用 —— 预览圈于是成了"骗人的"。
            //
            //   所以浮动摇杆按**范围圈半径**外扩，和 drawStickRange /
            //   hitFloatStick 用的是同一个 stickRange，三处口径一致。
            if (!mLayout.stickFixed[i] && isFloatStick(i)) {
                float rr = radiusOf(i) * mLayout.stickRange[i];
                if (rr > mx) mx = rr;
                if (rr > my) my = rr;
            }
            if (mx <= 0f || my <= 0f) {
                continue;
            }
            int l = (int) (mPx[i] - mx);
            int t = (int) (mPy[i] - my);
            int r = (int) (mPx[i] + mx + 0.5f);
            int b = (int) (mPy[i] + my + 0.5f);
            if (l < 0) l = 0;
            if (t < 0) t = 0;
            if (r > mW) r = mW;
            if (b > mH) b = mH;
            if (r <= l || b <= t) {
                continue;
            }
            android.graphics.Rect rect = out[n];
            if (rect == null) {
                rect = new android.graphics.Rect();
                out[n] = rect;
            }
            rect.set(l, t, r, b);
            n++;
        }
        return n;
    }

    /**
     * 数一数有多少个"可点"元素。
     *
     * 【为什么要在建窗口之前先数】
     *   Android 对单个 UID 的 TYPE_APPLICATION_OVERLAY 窗口数量有上限
     *   （不同 ROM 不一样，常见几十个）。键盘布局 80+ 个键时，
     *   一次性 addView 上去会被系统批量回收 ——
     *   表现就是"红色窗口一个个消失，然后 app 崩了"。
     *
     *   所以必须先数：超阈值就干脆一个窗口都不建，退回全屏触摸模式。
     *   先建再发现超了，就是崩溃本身。
     */

    /**
     * @param useScreenCoord true = 屏幕坐标（mPx + mViewOff）
     *                       false = View 内坐标（mPx 直接用）
     *
     * 【为什么要有这个开关】
     *   触摸窗口是隐形的，我一直不知道它到底该用哪个坐标系，
     *   推理错了好几次。做成开关后，配合「显示判定框」，
     *   勾一下就能用眼睛看出来哪个才是对的。
     */
    public int collectKeyWindows(int[] out, boolean useScreenCoord) {
        if (mLayout == null) {
            return 0;
        }
        refreshViewOffset();
        final float offX = useScreenCoord ? (float) mViewOffX : 0f;
        final float offY = useScreenCoord ? (float) mViewOffY : 0f;
        int max = out.length / 5;
        int n = 0;
        for (int i = 0; i < PadLayout.N; i++) {
            if (!pickable(i)) {
                continue;
            }
            float r = radiusOf(i);
            float hw = r * mLayout.widthMul[i];
            float hh = r * mLayout.heightMul[i];
            if (isTrigger(i)) {
                // 扳机是竖着的轨道，高度不是圆半径那套
                hh = trackHalfH(i);
            }
            // 1.35 是摇杆的判定倍率（圆按钮 1.25、十字键 1.15），
            // 取最大的那个，保证窗口完整盖住"能点到"的范围。
            float mx = hw * 1.35f;
            float my = hh * 1.35f;
            // 坐标系由开关决定：屏幕坐标 or View 内坐标
            float l = mPx[i] - mx + offX;
            float t = mPy[i] - my + offY;
            float rr = mPx[i] + mx + offX;
            float b = mPy[i] + my + offY;
            // 夹到 View 的范围内（mW/mH 是 View 尺寸，不是屏幕尺寸）
            float sl = offX;
            float st = offY;
            float sr = offX + (float) mW;
            float sb = offY + (float) mH;
            if (l < sl) l = sl;
            if (t < st) t = st;
            if (rr > sr) rr = sr;
            if (b > sb) b = sb;
            if (rr - l < 4f || b - t < 4f) {
                continue;
            }
            out[n * 5] = i;
            out[n * 5 + 1] = (int) l;
            out[n * 5 + 2] = (int) t;
            out[n * 5 + 3] = (int) rr;
            out[n * 5 + 4] = (int) b;
            n++;
            if (n >= max) {
                break;
            }
        }
        return n;
    }

    /**
     * 独立按键窗口传来的触摸。
     *
     * 和 onTouchEvent 走同一套 onDown / onMove / onUp，
     * 只是跳过了 hitElement —— 这个窗口本来就是为这一个键开的，
     * 落在它上面的触摸必然属于它，不需要再判定一遍。
     */
    public void onKeyTouch(int elem, int action, float rawX, float rawY) {
        if (mLayout == null || elem < 0 || elem >= PadLayout.N) {
            return;
        }
        // 小窗口给的是屏幕坐标（getRawX/getRawY），
        // 而 onDown/onMove 内部比的是 mPx/mPy（View 内坐标）——
        // 不减掉偏移的话，拿到的 y 会比手指实际位置大一个状态栏高度，
        // 表现就是"明明点在按键上，却像点到了它下面的位置"。
        float x = rawX - (float) mViewOffX;
        float y = rawY - (float) mViewOffY;
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mDownX = x;
                mDownY = y;
                mMoved = false;
                onDown(elem, x, y);
                break;
            case MotionEvent.ACTION_MOVE:
                onMove(elem, x, y);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                onUp(elem);
                break;
            default:
                return;
        }
        invalidate();
    }

    /**
     * 按键窗口被移除时调用（从游玩切到编辑、开列表、收起悬浮窗都会走到）。
     *
     * 【为什么需要】
     *   窗口是在手指还按着的时候被 remove 掉的，
     *   之后的 ACTION_UP 再也送不到那个 KeyTouchView ——
     *   不补一次的话，这个键会一直卡在"按下"状态（游戏里就是一直往前跑）。
     */
    public void releaseKeyWindowTouches() {
        if (mLayout == null) {
            return;
        }
        for (int i = 0; i < PadLayout.N; i++) {
            if (mDown[i] == 0) {
                continue;
            }
            // 界面按钮（编 / 收 / 布）只清状态，不走 onUp ——
            // onUp 里可能带着"收起悬浮窗"这类动作，误触发代价太大。
            if (PadLayout.isUiButton(i)) {
                mDown[i] = 0;
            } else {
                onUp(i);
                mDown[i] = 0;
            }
        }
        invalidate();
    }

    public boolean onTouchEvent(MotionEvent e) {
        if (mLayout == null) {
            return false;
        }
        int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                int idx = e.getActionIndex();
                float x = e.getX(idx);
                float y = e.getY(idx);
                int pid = e.getPointerId(idx);

                // 列表是模态的，必须放在所有面板判断之前。
                //
                // 列表面板是居中显示的（高度能占到 94% 屏高），它的
                // 「确定 / 取消」按钮落在屏幕下方 —— 那里同时也是编辑面板的区域。
                // 之前列表判断排在面板后面，结果点确定/取消时先被面板截胡，
                // 走了 hitSlider -> hitTool -> hitElement 一路，最后当成
                // "面板空白"处理掉，表现就是"点了没反应、穿透到后面"。
                // 不要求 mEditMode：「布」按钮在**非编辑模式**下也会开列表，
                // 带着 mEditMode 判定的话，那份列表吃不到触摸、点了没反应。
                // 对话框最优先：它盖在列表之上，触摸也得先给它
                mModalWasOpenAtDown = (mDlgMode != DLG_NONE)
                        || (mListMode != LIST_NONE);
                if (mDlgMode != DLG_NONE) {
                    return true;
                }
                if (mListMode != LIST_NONE) {
                    // 只记起点，不在这里响应点击（点击挪到 ACTION_UP）
                    mListDownY = y;
                    mListScrollAtDown = mListScroll;
                    mListDragging = false;
                    //
                    // 【力度条 / 预览盘：按下就生效】
                    //   它们不是列表行，点一下应该立刻跳到该位置，
                    //   而不是等抬手才响应（那样手感像"点了没反应"）。
                    mListAdjust = hitListAdjust(x, y);
                    if (mListAdjust != ADJ_NONE) {
                        mListAdjustPointer = pid;
                        applyListAdjust(mListAdjust, x, y);
                    } else {
                        mListAdjustPointer = NONE;
                    }
                    return true;
                }

                // 最小态：屏幕下方没有面板背景，只有右下角一个圆加号。
                // 加号以外的区域一律穿透，跟没有面板一样。
                if (mEditMode && mPanelState == PANEL_MIN) {
                    if (mPanelFabRect.contains(x, y)) {
                        setPanelState(PANEL_FULL);
                        return true;
                    }
                } else if (mEditMode && y >= mPanelTop) {
                    // 【按当前模式重排一次再用】
                    //   面板上这些矩形的排布依赖当前模式（有没有「返回」、
                    //   是不是多选、布局调节开没开）。以前只在尺寸变化时排一次，
                    //   模式变了矩形不动 —— 于是"画的是这副样子、点的是上一副"。
                    //   触摸比下一次 draw 先到也照样正确，因为这里是即时重排。
                    layoutTools();
                    // 面板上的控件先吃事件，不会误触到下面的游戏按键。
                    // 但面板的**空白区**要穿透 —— 底部不再阻挡按键之后，
                    // 按键可以被拖到面板底下，如果空白区也把触摸吃掉，
                    // 那些按键就再也选不中、拖不动了。
                    // 「+」永远是恢复到最大（不管现在收成什么样）
                    if (isMulti() && !mGridCfg && !inSubMode()
                            && !mMultiLayBtnRect.isEmpty()
                            && mMultiLayBtnRect.contains(x, y)) {
                        // 「布局调节」开关：
                        //   关 -> 开 时拍一次快照，让 k=1 就等于"此刻的样子"，
                        //        之后往回拖能精确回到开启前的状态。
                        mAdjLayoutMode = !mAdjLayoutMode;
                        if (mAdjLayoutMode) {
                            snapshotAdj();
                            mAdjK = 1f;
                        }
                        invalidate();
                        return true;
                    }
                    if (mGridCfg && mAdjLayoutBtnRect.contains(x, y)) {
                        mGridShow = !mGridShow;
                        saveGridCfg();
                        invalidate();
                        return true;
                    }
                    if (mGridCfg && mAdjAnchorBtnRect.contains(x, y)) {
                        mGridSnap = !mGridSnap;
                        saveGridCfg();
                        invalidate();
                        return true;
                    }
                    if (mGridCfg && mGridColMinusRect.contains(x, y)) {
                        if (mGridCols > GRID_COLS_MIN) mGridCols--;
                        saveGridCfg();
                        invalidate();
                        return true;
                    }
                    if (mGridCfg && mGridColPlusRect.contains(x, y)) {
                        if (mGridCols < GRID_COLS_MAX) mGridCols++;
                        saveGridCfg();
                        invalidate();
                        return true;
                    }
                    if (mGridCfg && mGridRowMinusRect.contains(x, y)) {
                        if (mGridRows > GRID_ROWS_MIN) mGridRows--;
                        saveGridCfg();
                        invalidate();
                        return true;
                    }
                    if (mGridCfg && mGridRowPlusRect.contains(x, y)) {
                        if (mGridRows < GRID_ROWS_MAX) mGridRows++;
                        saveGridCfg();
                        invalidate();
                        return true;
                    }
                    if (isMulti() && !mGridCfg && !inSubMode() && mAdjLayoutMode
                            && !mMultiCenBtnRect.isEmpty()
                            && mMultiCenBtnRect.contains(x, y)) {
                        // 去挑中心按钮：列表里只显示这批参与者
                        openList(LIST_ADJ_ANCHOR);
                        return true;
                    }
                    if (isMultiShape()) {
                        // 多选形状：点一个就把整批刷成它，然后退出面板
                        if (mShapeCircleRect.contains(x, y)) {
                            applyMultiShape(PadLayout.SHAPE_CIRCLE);
                            return true;
                        }
                        if (mShapeRectRect.contains(x, y)) {
                            applyMultiShape(PadLayout.SHAPE_RECT);
                            return true;
                        }
                    }
                    if (inSubMode() && mSubBackRect.contains(x, y)) {
                        exitSubMode();
                        return true;
                    }
                    if (mStickCfg && selHasStick() && mStickFixRect.contains(x, y)) {
                        // 切固定 / 浮动。关掉固定时范围滑条立刻可用（hitSlider 那边判的）。
                        //
                        // 多选时以第一个摇杆的当前状态为准，整批一起翻 ——
                        // 各翻各的会让本来一致的两个摇杆变得相反。
                        int ref = firstStickInSel();
                        final boolean nv = ref == NONE ? true : !mLayout.stickFixed[ref];
                        forEachSelStick(new StickOp() {
                            @Override
                            public void run(int k) {
                                mLayout.stickFixed[k] = nv;
                            }
                        });
                        saveAndDraw();
                        return true;
                    }
                    if (mShapeBtnRect.contains(x, y)) {
                        // 「更多选项」：形状只是其中一项，弹列表让人挑。
                        //
                        // 不再在这里直接切 mShapeMode ——
                        // 那个位置以后还要放别的调节，写死一种功能就加不进去了。
                        // 真正的切换挪到 toggleListItem 的 LIST_MORE 分支。
                        openList(LIST_MORE);
                        return true;
                    }
                    if (mShapeMode) {
                        if (mShapeCircleRect.contains(x, y)) {
                            mLayout.shape[mSel] = PadLayout.SHAPE_CIRCLE;
                            saveAndDraw();
                            return true;
                        }
                        if (mShapeRectRect.contains(x, y)) {
                            mLayout.shape[mSel] = PadLayout.SHAPE_RECT;
                            // 手柄键默认倍率是 1（正圆），直接切矩形会变正方形
                            // 且比键盘键窄，给个常规的矩形比例。
                            if (mLayout.widthMul[mSel] == 1f
                                    && mLayout.heightMul[mSel] == 1f) {
                                mLayout.widthMul[mSel] = 1.19f;
                                mLayout.heightMul[mSel] = 1.06f;
                            }
                            saveAndDraw();
                            return true;
                        }
                    }
                    if (mPanelToggleRect.contains(x, y)) {
                        setPanelState(mPanelState == PANEL_FULL ? PANEL_BAR : PANEL_FULL);
                        return true;
                    }
                    // 「v」：再收小一档，只剩右下角加号
                    if (mPanelState == PANEL_BAR && mPanelMinRect.contains(x, y)) {
                        setPanelState(PANEL_MIN);
                        return true;
                    }
                    // 先判数值热区：它压在滑条右端，
                    // 不先判的话点数字会变成"把滑条拖到 100%"。
                    boolean hitVal = false;
                    if (!mGridCfg && !mStickCfg && !mShapeMode) {
                        for (int i = 0; i < SL_COUNT; i++) {
                            if (mSlValRect[i].contains(x, y)) {
                                toggleAlphaUnit();
                                hitVal = true;
                                break;
                            }
                        }
                    }
                    if (hitVal) {
                        return true;
                    }
                    int sl = mGridCfg ? NONE : hitSlider(x, y);
                    if (sl != NONE) {
                        mSlDrag = sl;
                        mSlDragPointer = pid;
                        applySlider(sl, x);
                        return true;
                    }
                    int tool = hitTool(x, y);
                    if (tool != NONE) {
                        onTool(tool);
                        return true;
                    }
                    //
                    // 【面板空白就是空白 —— 不再去捞底下的按键】
                    //   以前这里会在面板空白处再查一次 hitElement()，
                    //   底下压着按键就选中它、并直接进入拖动。
                    //   本意是"按键被拖到面板底下后还能选得中"，但代价太大：
                    //     1) 想点面板空白（比如拖滑条时手指滑出去）却选中/拖动了
                    //        底下那个键，手感像面板没接管触摸；
                    //     2) 更糟的是 selectSingle() 会清掉多选 ——
                    //        多选完想点空白，整批选中一下就散了。
                    //   所以面板是实心的：点它没点到控件 = 什么都不发生。
                    //   被面板压住的键靠「收起面板」或「多选…」列表来选。
                    mRayElem = NONE;
                    return true;
                }

                int elem = hitElement(x, y);
                if (elem == NONE) {
                    // 没按到任何按钮本身 —— 再看看是不是落在某个浮动摇杆的
                    // 范围圈里。不查的话那个圈就是画着好看，按外面没反应。
                    elem = hitFloatStick(x, y);
                }
                if (elem == NONE || elemBusy(elem)) {
                    if (mEditMode) {
                        clearSelection();
                    mRayElem = NONE;
                        invalidate();
                        return true;
                    }
                    return false;
                }
                mDownX = x;
                mDownY = y;
                mMoved = false;

                if (mEditMode) {
                    // 【点已选中的 = 保持选中，点没选中的 = 改选它】
                    //
                    //   以前这里是"只要点了画布就 clear + 单选"，
                    //   于是多选状态下想拖动整批，一按下去多选就散了 ——
                    //   拖得动的只剩那一个，批量挪动根本没法用。
                    //
                    //   现在：按在已选中的按钮上 -> 选中集合原样保留，
                    //   拖的时候整批一起走；按在别的按钮上 -> 改成单选它。
                    if (!mSelSet[elem]) {
                        selectSingle(elem);
                        mAdjAnchor = elem;
                        snapshotAdj();
                    }
                    mDragDx = x - mPx[elem];
                    mDragDy = y - mPy[elem];
                    mPointers.put(pid, elem);
                    invalidate();
                    return true;
                }

                mPointers.put(pid, elem);
                onDown(elem, x, y);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                // 列表打开时全部吃掉：不然 MOVE 会掉进下面的拖拽分支，
                // 试着去拖一个不存在的元素。
                if (mDlgMode != DLG_NONE) {
                    return true;                 // 对话框打开时不让 MOVE 穿透
                }
                if (mListMode != LIST_NONE) {
                    //
                    // 【正在拖控件：只认按下的那根手指】
                    //   不然第二根手指一动会把值带跑，而且会顺带触发滚动。
                    if (mListAdjust != ADJ_NONE) {
                        int pi = (mListAdjustPointer == NONE) ? e.getActionIndex()
                                : e.findPointerIndex(mListAdjustPointer);
                        if (pi >= 0) {
                            applyListAdjust(mListAdjust, e.getX(pi), e.getY(pi));
                        }
                        return true;
                    }
                    float y = e.getY(e.getActionIndex() >= 0 ? e.getActionIndex() : 0);
                    if (!mListDragging && Math.abs(y - mListDownY) > DRAG_SLOP) {
                        mListDragging = true;
                    }
                    if (mListDragging) {
                        mListScroll = mListScrollAtDown - (y - mListDownY);
                        if (mListScroll < 0f) {
                            mListScroll = 0f;
                        }
                        if (mListScroll > mListScrollMax) {
                            mListScroll = mListScrollMax;
                        }
                        invalidate();
                    }
                    return true;
                }
                if (mSlDrag != NONE) {
                    int i = e.findPointerIndex(mSlDragPointer);
                    float x = i >= 0 ? e.getX(i) : e.getX(0);
                    applySlider(mSlDrag, x);
                    return true;
                }
                if (mEditMode) {
                    // 按记录的 pointerId 找手指，不默认取第 0 根
                    int id = NONE;
                    int elem = NONE;
                    for (int k = 0; k < mPointers.size(); k++) {
                        int pid = mPointers.keyAt(k);
                        if (e.findPointerIndex(pid) >= 0) {
                            id = pid;
                            elem = mPointers.get(pid);
                            break;
                        }
                    }
                    if (elem == NONE) {
                        return false;
                    }
                    int i = e.findPointerIndex(id);
                    if (i < 0) {
                        return false;
                    }
                    float x = e.getX(i);
                    float y = e.getY(i);
                    if (!mMoved && Math.abs(x - mDownX) + Math.abs(y - mDownY) > DRAG_SLOP) {
                        mMoved = true;
                    }
                    if (mMoved) {
                        mRayElem = elem;
                        // 【拖 G 实际改的是「收」的位置】
                        //   G 不存自己的位置（见 computeGeometry），
                        //   写 mLayout.rx[I_FLOAT] 等于写了个下一帧就被覆盖的死数。
                        int pi = (elem == PadLayout.I_FLOAT)
                                ? PadLayout.I_COLLAPSE : elem;
                        float nx = x - mDragDx;
                        float ny = y - mDragDy;
                        float dx = nx - mPx[pi];
                        float dy = ny - mPy[pi];
                        mPx[pi] = nx;
                        mPy[pi] = ny;
                        clampToScreen(pi);
                        mLayout.rx[pi] = mPx[pi] / mW;
                        mLayout.ry[pi] = mPy[pi] / mH;
                        // 【G 的像素坐标要当场跟着走】
                        //   它不存自己的位置，只在 computeGeometry 里取「收」的坐标。
                        //   而拖动不会触发 computeGeometry —— 拖「收」的时候
                        //   G 会留在原地，松手后（下次 relayout）才突然跳过去。
                        syncFloatBallPx();
                        if (pi != elem) {
                            mPx[elem] = mPx[pi];
                            mPy[elem] = mPy[pi];
                            mLayout.rx[elem] = mLayout.rx[pi];
                            mLayout.ry[elem] = mLayout.ry[pi];
                        }
                        // 关联挪动：同组其他按钮按同一个位移量一起走。
                        // 用位移量而不是"对齐坐标"，这样组内相对位置保持不变。
                        if (selCount() > 1 && mSelSet[elem]) {
                            for (int j = 0; j < PadLayout.N; j++) {
                                if (j == elem || !mSelSet[j]) {
                                    continue;
                                }
                                mPx[j] += dx;
                                mPy[j] += dy;
                                clampToScreen(j);
                                mLayout.rx[j] = mPx[j] / mW;
                                mLayout.ry[j] = mPy[j] / mH;
                            }
                        }
                        invalidate();
                    }
                    return true;
                }
                boolean used = false;
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int id = e.getPointerId(i);
                    int elem = mPointers.get(id, NONE);
                    if (elem != NONE) {
                        onMove(elem, e.getX(i), e.getY(i));
                        used = true;
                    }
                }
                /*
                  【必须重绘】
                    触摸板的落点预览画的是**手指的累计偏移**，
                    而这个累计值是在 onMove 里一点点加起来的 ——
                    加完不重绘的话，画面停留在按下那一帧（那时位移还是 0，
                    什么都没画），于是"点不跟着手指走"；
                    只有当别处碰巧触发重绘（比如同时按了别的键）它才闪一下，
                    看起来就成了"有时显示有时不显示"。

                    编辑模式拖按钮、独立按键窗口 onKeyTouch 那里都有 invalidate，
                    唯独主窗口的游玩模式 MOVE 漏了。
                 */
                if (used) {
                    invalidate();
                }
                return used;
            }
            case MotionEvent.ACTION_UP: {
                int idx = e.getActionIndex();
                int pid = e.getPointerId(idx);
                // 列表打开时全部吃掉：mPointers 里没有列表的项，
                // 走到下面会因为 elem == NONE 直接 return false。
                // 【本次抬手不响应"刚打开"的列表 / 对话框】
                //   按下那一刻它们还没开 —— 是这次按下的动作把它们打开的
                //   （比如点「常用工具」「编」这类按钮）。抬手时如果照常判定，
                //   手指位置恰好压在列表的「关闭 / 确定」上，就会立刻被关掉，
                //   表现为"菜单一闪而过、怎么点都打不开，只有最左/最右边缘能进"
                //   —— 因为边缘没有按钮。
                // 【必须同时满足两个条件才能"吞掉"这次抬手】
                //   1) modalNow：抬手时列表 / 对话框确实开着。
                //      否则就是普通交互（拖按钮、按游戏键），
                //      在这里 return 会把后面的清理全跳过 ——
                //      mPointers 不删、mDown 不清，按钮就"粘在手上跟着飞"。
                //   2) 这根手指没绑任何元素：正在拖的 / 正按着的键绝不能吞。
                boolean modalNow = (mDlgMode != DLG_NONE)
                        || (mListMode != LIST_NONE);
                if (!mModalWasOpenAtDown && modalNow
                        && mPointers.get(pid, NONE) == NONE) {
                    mListDragging = false;
                    return true;
                }
                if (mDlgMode != DLG_NONE) {
                    float dx = e.getX(idx);
                    float dy = e.getY(idx);
                    if (mDlgOkRect.contains(dx, dy)) {
                        confirmDlg();
                    } else if (mDlgCancelRect.contains(dx, dy)) {
                        closeDlg();
                    } else if (!mDlgPanelRect.contains(dx, dy)) {
                        // 点面板外面 = 取消，和系统对话框的手感一致
                        closeDlg();
                    }
                    return true;
                }
                if (mListMode != LIST_NONE) {
                    //
                    // 【拖完控件抬手：不走行点击】
                    //   否则抬手点落在控件上时会被 performListTap 当成
                    //   "点了这一行"再处理一次，等于调完又被翻回去。
                    if (mListAdjust != ADJ_NONE) {
                        mListAdjust = ADJ_NONE;
                        mListAdjustPointer = NONE;
                        return true;
                    }
                    if (!mListDragging) {
                        performListTap(e.getX(idx), e.getY(idx));
                    }
                    mListDragging = false;
                    return true;
                }
                if (mSlDrag != NONE && pid == mSlDragPointer) {
                    mSlDrag = NONE;
                    mSlDragPointer = NONE;
                    return true;
                }
                int elem = mPointers.get(pid, NONE);
                if (elem == NONE) {
                    return false;
                }
                mPointers.delete(pid);
                if (mEditMode) {
                    if (mMoved) {
                        // 松手这一刻才吸附：拖动过程中吸会让手感发粘，
                        // 手指一直在跟吸附力"拔河"。
                        // 【G 要吸附到「收」身上】
                        //   G 的坐标是派生的（不存盘、下一帧就被「收」覆盖），
                        //   对 G 调 snapElement 等于改了个马上失效的数 ——
                        //   看着就是"拖 G 不吸附"。所以换成它的真实锚点。
                        int si = (elem == PadLayout.I_FLOAT)
                                ? PadLayout.I_COLLAPSE : elem;
                        float ox = mPx[si];
                        float oy = mPy[si];
                        mRayElem = NONE;
                        mSnapHitX = NONE;
                        mSnapHitY = NONE;
                        snapElement(si);
                        float sdx = mPx[si] - ox;
                        float sdy = mPy[si] - oy;
                        mLayout.rx[si] = mPx[si] / mW;
                        mLayout.ry[si] = mPy[si] / mH;
                        if (si != elem) {
                            // 把 G 拉回来，它俩始终是同一个位置
                            mPx[elem] = mPx[si];
                            mPy[elem] = mPy[si];
                            mLayout.rx[elem] = mLayout.rx[si];
                            mLayout.ry[elem] = mLayout.ry[si];
                        }
                        // 关联组跟着走同一个位移，组内相对位置保持不变
                        if (selCount() > 1 && mSelSet[elem]) {
                            for (int j = 0; j < PadLayout.N; j++) {
                                if (j == elem || !mSelSet[j]) {
                                    continue;
                                }
                                mPx[j] += sdx;
                                mPy[j] += sdy;
                                clampToScreen(j);
                                mLayout.rx[j] = mPx[j] / mW;
                                mLayout.ry[j] = mPy[j] / mH;
                            }
                        }
                        invalidate();
                        mLayout.save(getContext());
                    }
                    return true;
                }
                onUp(elem);
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (mEditMode) {
                    return false;
                }
                int i = e.getActionIndex();
                int elem = hitElement(e.getX(i), e.getY(i));
                if (elem != NONE && !elemBusy(elem)) {
                    mPointers.put(e.getPointerId(i), elem);
                    onDown(elem, e.getX(i), e.getY(i));
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                int i = e.getActionIndex();
                int id = e.getPointerId(i);
                if (mSlDrag != NONE && id == mSlDragPointer) {
                    mSlDrag = NONE;
                    mSlDragPointer = NONE;
                    return true;
                }
                if (mEditMode) {
                    return false;
                }
                int elem = mPointers.get(id, NONE);
                if (elem != NONE) {
                    onUp(elem);
                    mPointers.delete(id);
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_CANCEL: {
                mListDragging = false;
                // 软取消：别把正在播的组合键序列掐掉（见 releaseAll 注释）
                Log.w("VGpad", "touch CANCEL（窗口 relayout 会送这个，"
                        + "组合键序列不会被掐断）");
                releaseAll(false);
                return true;
            }
        }
        return false;
    }

    private void clampToScreen(int i) {
        // 用完整半径收边（原来只收 0.55 个半径，按钮会有一半钻到面板底下）
        // 扳机是竖直滑轨，纵向要用轨道半高，不然上下两端会被切掉。
        float rx = radiusOf(i) + Math.min(mW, mH) * 0.015f;
        float ry = isTrigger(i) ? trackHalfH(i) + Math.min(mW, mH) * 0.015f : rx;
        // 底部一律不阻挡：按键可以一直拖到屏幕最下沿。
        // 以前编辑模式下会用面板上沿把按键顶到面板上方，
        // 结果屏幕最下面那条永远空着、想放按键却放不下去。
        // 面板是临时的（右上角「−」可以收成一条窄边），按键位置是长期生效的，
        // 让长期的东西给临时的让路，本末倒置。
        float bottom = mH - ry;
        if (bottom < ry) bottom = ry;
        // 顶部留一条保护区：不许把按键拖到这条线以上。
        // 那里有状态栏 / 挖孔，而且贴顶以后手指抓不住、就挪不回来了。
        float top = mEditMode ? mTopGuard + ry : ry;
        if (top > bottom) top = bottom;
        if (mPx[i] < rx) mPx[i] = rx;
        if (mPx[i] > mW - rx) mPx[i] = mW - rx;
        if (mPy[i] < top) mPy[i] = top;
        if (mPy[i] > bottom) mPy[i] = bottom;
    }

    /**
     * 把一个元素吸附到网格交叉点 / 邻近按键的中心线。
     *
     * 两个候选来源，取更近的那个：
     *   1. 网格交叉点 —— 让键位落在均匀的格子上
     *   2. 其他按键的中心 x / y —— 让几个键互相看齐
     *
     * 只对"已经很接近"的情况生效（阈值 = 小格边的 45%）：
     * 拖到一个格子正中间时不会硬拽到角上，那样反而更难摆。
     */
    private void snapElement(int i) {
        if (!mGridSnap || mW <= 0 || mH <= 0) {
            return;
        }
        // 【为什么不再看网格】
        //   网格是等分的，可键位本来就不等距 —— 按网格吸反而会把
        //   好不容易摆齐的一排键拽歪。吸附只认"和别的键同轴"这一件事，
        //   网格退回纯参考线（画出来看的，不参与计算）。
        float tol = snapTol();
        float bestX = mPx[i];
        float bestY = mPy[i];
        float dxMin = tol;
        float dyMin = tol;

        // 候选 1：网格线（只有「吸附跟随网格」开着才算）
        //
        // 默认关：网格是等分的，可键位本来就不等距，按网格吸会把
        // 摆齐的一排拽到格线上歪掉。想要"贴着格子摆"再开它。
        if (mGridFollow && mGridCols >= 2 && mGridRows >= 2) {
            float cw = (float) mW / mGridCols;
            float ch = (float) mH / mGridRows;
            float gx = Math.round(mPx[i] / cw) * cw;
            float gy = Math.round(mPy[i] / ch) * ch;
            float dgx = Math.abs(gx - mPx[i]);
            float dgy = Math.abs(gy - mPy[i]);
            if (dgx < dxMin) {
                dxMin = dgx;
                bestX = gx;
                mSnapHitX = NONE;
            }
            if (dgy < dyMin) {
                dyMin = dgy;
                bestY = gy;
                mSnapHitY = NONE;
            }
        }

        // 候选 2：其它按键的中心线（同轴就看齐）
        if (mSnapElem) {
            for (int j = 0; j < PadLayout.N; j++) {
                if (j == i || !pickable(j)) {
                    continue;
                }
                /*
                  【G 和「收」不能互相吸附】
                    它俩位置恒等（G 直接取「收」的坐标），距离永远是 0。
                    于是拖「收」时最近的候选就是 G、拖 G 时最近的候选就是「收」
                    —— 吸附到自己身上，坐标为 0 位移，表现就是
                    "别的键都吸，唯独「收」怎么拖都不动"。
                    同源 = 同一个位置，对齐它没有意义，直接跳过。
                */
                if (sameAnchor(i, j)) {
                    continue;
                }
                float ax = Math.abs(mPx[j] - mPx[i]);
                if (ax < dxMin) {
                    dxMin = ax;
                    bestX = mPx[j];
                    mSnapHitX = j;
                }
                float ay = Math.abs(mPy[j] - mPy[i]);
                if (ay < dyMin) {
                    dyMin = ay;
                    bestY = mPy[j];
                    mSnapHitY = j;
                }
            }
        }
        mPx[i] = bestX;
        mPy[i] = bestY;
        clampToScreen(i);
    }

    /**
     * 两个元素是不是**同一个位置**（G 与「收」）。
     *
     * G 不存自己的坐标，computeGeometry 里直接取「收」的 ——
     * 所以它俩永远重合，不能互为吸附候选。
     */
    private static boolean sameAnchor(int i, int j) {
        boolean a = (i == PadLayout.I_FLOAT || i == PadLayout.I_COLLAPSE);
        boolean b = (j == PadLayout.I_FLOAT || j == PadLayout.I_COLLAPSE);
        return a && b;
    }

    /**
     * 吸附容差。
     *
     * 用"当前这个键的半径"而不是固定像素：大键允许的手抖范围自然更大，
     * 小键也不会因为容差太宽被莫名其妙拽走。
     */
    private float snapTol() {
        float r = mSel == NONE ? dp(28f) : radiusOf(mSel);
        int lv = mSnapLevel;
        if (lv < 0) lv = 0;
        if (lv >= SENS_RATIO.length) lv = SENS_RATIO.length - 1;
        float v = r * SENS_RATIO[lv];
        // 上限压在 36dp：再高就变成"手一松键就跳走"，比不吸附还难摆
        return Math.max(dp(SENS_MIN_DP[lv]), Math.min(dp(SENS_MAX_DP), v));
    }

    // ------------------------------------------------------------------
    // 编辑面板：滑条 + 按钮
    // ------------------------------------------------------------------

    private float sliderValue(int i) {
        // 【优先级子面板排在 isMulti() 之前】
        //   多选时 isMulti() 为真，它会返回批量缓存值（大小 / 字体 / 透明），
        //   于是多选 + 优先级读到的其实是"大小" —— 和摇杆设置踩的是同一个坑。
        if (mPrioMode) {
            if (i != 0) {
                return 0f;
            }
            return PadLayout.clampPrio(curPrio()) / 100f;
        }
        //
        // 【摇杆设置必须排在 isMulti() 之前】
        //   多选时 isMulti() 为真，以前它排在最前面，直接返回批量缓存值
        //   （大小 / 字体 / 透明），于是多选 + 摇杆设置读到的其实是"大小"。
        //   单选因为不走 isMulti() 分支而正常 —— 这个差异就是顺序造成的。
        if (mStickCfg && selHasStick()) {
            // 只有第一条是范围
            if (i != 0) {
                return 0f;
            }
            int ref = firstStickInSel();
            if (ref == NONE) {
                return 0f;
            }
            return (mLayout.stickRange[ref] - PadLayout.MIN_STICK_RANGE)
                    / (PadLayout.MAX_STICK_RANGE - PadLayout.MIN_STICK_RANGE);
        }
        // 多选调节面板：三条滑条反映的是"这批统一刷到多少"的缓存值，
        // 不是某个具体按钮的值 —— 面板本来就是拿它们当批量的目标值用。
        if (isMulti()) {
            if (i == SL_SIZE) {
                return mAdjLayoutMode ? adjKToSlider(mAdjK) : mAdjSizeVal;
            }
            if (i == SL_TEXT_SIZE) {
                return mAdjTextVal;
            }
            return mAdjAlphaVal;
        }
        if (mSel == NONE) {
            return 0f;
        }
        if (mShapeMode) {
            float v = i == SL_W ? mLayout.widthMul[mSel] : mLayout.heightMul[mSel];
            // 再夹一次：load 虽然夹过，但 applySlider 之外还有 reset/addKey
            // 这些写入路径，统一在这里兜底最省心。
            return PadLayout.mulToSlider(v);
        }
        if (i == SL_SIZE) {
            return PadLayout.scaleToSlider(mLayout.scale[mSel]);
        }
        if (i == SL_TEXT_SIZE) {
            return (mLayout.textScale[mSel] - PadLayout.MIN_TEXT_SCALE)
                    / (PadLayout.MAX_TEXT_SCALE - PadLayout.MIN_TEXT_SCALE);
        }
        return (mLayout.alpha[mSel] - PadLayout.MIN_ALPHA) / (1f - PadLayout.MIN_ALPHA);
    }

    /**
     * 0..1 的不透明度 → 256 制下的整数（0..255）。
     *
     * 【为什么分母是 256 而不是 255】
     *   两位十六进制有 16×16 = 256 个取值，范围 0..255，满值是 0xFF = 255。
     *   按 255 换算的话 75% 得 191（0xBF），而默认色里写的是 0xC0（192）——
     *   显示数和真实字节值就差 1，"看着 75%，存进去却是 BF"。
     *   按 256 换算：0.75 × 256 = 192，正好等于 0xC0。
     *
     * 【为什么结果还是压在 255】
     *   一个字节最大就是 255。100% × 256 = 256 已经越界，
     *   落盘时程序会把它压回 255，显示 256 的话两边又对不上 —— 所以取 min。
     */
    private static int alpha256Of(float realA) {
        int n = Math.round(realA * 256f);
        return n > 255 ? 255 : (n < 0 ? 0 : n);
    }

    private boolean loadAlphaUnit() {
        try {
            android.content.SharedPreferences sp = getContext()
                    .getSharedPreferences("vgp_ui", 0);
            return sp.getBoolean("alpha_raw", false);
        } catch (Exception e) {
            return false;
        }
    }

    /** 记住用户选的单位，下次打开还是同一个（默认百分制）。 */
    private void saveAlphaUnit() {
        try {
            android.content.SharedPreferences sp = getContext()
                    .getSharedPreferences("vgp_ui", 0);
            sp.edit().putBoolean("alpha_raw", mAlphaRawUnit).apply();
        } catch (Exception ignored) {
        }
    }

    /**
     * 透明度那个数字：百分制 / 256 制，点一下切换。
     *
     * 【只切显示，不动真实值】
     *   alpha 一直是 0..1 的浮点，两种单位只是同一个数的两种写法。
     *   最低值是 MIN_ALPHA = 25/255，所以拖到底大致显示 25。
     */
    private void toggleAlphaUnit() {
        mAlphaRawUnit = !mAlphaRawUnit;
        saveAlphaUnit();
        invalidate();
    }

    /** 倍率 k <-> 滑条位置。k=1（原样）落在中间偏左一点，两边都能拖。 */
    private static float adjKToSlider(float k) {
        float v = (k - ADJ_K_MIN) / (ADJ_K_MAX - ADJ_K_MIN);
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static float adjSliderToK(float v) {
        return ADJ_K_MIN + v * (ADJ_K_MAX - ADJ_K_MIN);
    }

    /** 多选面板上的滑条：把值刷给整批，而不是单个 mSel。 */
    private void applyMultiSlider(int i, float v) {
        if (mLayout == null) {
            return;
        }
        if (i == SL_SIZE) {
            if (mAdjLayoutMode) {
                mAdjK = adjSliderToK(v);
                applyAdjScale(mAdjK);
            } else {
                mAdjSizeVal = v;
                float sc = PadLayout.sliderToScale(v);
                for (int k = 0; k < PadLayout.N; k++) {
                    if (mSelSet[k]) {
                        mLayout.scale[k] = sc;
                    }
                }
                computeGeometry();
            }
        } else if (i == SL_TEXT_SIZE) {
            mAdjTextVal = v;
            float ts = PadLayout.MIN_TEXT_SCALE
                    + v * (PadLayout.MAX_TEXT_SCALE - PadLayout.MIN_TEXT_SCALE);
            for (int k = 0; k < PadLayout.N; k++) {
                if (mSelSet[k]) {
                    mLayout.textScale[k] = ts;
                }
            }
        } else {
            mAdjAlphaVal = v;
            float a = PadLayout.MIN_ALPHA + v * (1f - PadLayout.MIN_ALPHA);
            for (int k = 0; k < PadLayout.N; k++) {
                if (mSelSet[k]) {
                    mLayout.alpha[k] = a;
                }
            }
        }
        mLayout.save(getContext());
        invalidate();
    }

    private void applySlider(int i, float x) {
        // 【优先级子面板排在 isMulti() 之前】同上：不然多选时拖的是"大小"。
        if (mPrioMode) {
            RectF pt = mSlTrack[0];
            float pv = pt.width() <= 0f ? 0f : (x - pt.left) / pt.width();
            if (pv < 0f) pv = 0f;
            if (pv > 1f) pv = 1f;
            applyPrio(Math.round(pv * 100f));
            return;
        }
        //
        // 【摇杆设置必须排在 isMulti() 之前】
        //   以前 isMulti() 在最前面，多选时直接走 applyMultiSlider()，
        //   i==0 是 SL_SIZE —— 于是"范围"那条实际改的是按键**大小**。
        //   单选因为不走那条分支而正常。
        if (mStickCfg && selHasStick()) {
            RectF ts = mSlTrack[i];
            float vs = ts.width() <= 0f ? 0f : (x - ts.left) / ts.width();
            if (vs < 0f) vs = 0f;
            if (vs > 1f) vs = 1f;
            if (i == 0) {
                // 刷给集合里**所有**摇杆：左右摇杆本来就该同步设成一样，
                // 一个一个调既麻烦又容易两边不一致。
                final float nv = PadLayout.clampStickRange(
                        PadLayout.MIN_STICK_RANGE
                                + vs * (PadLayout.MAX_STICK_RANGE
                                        - PadLayout.MIN_STICK_RANGE));
                int cnt = forEachSelStick(new StickOp() {
                    @Override
                    public void run(int k) {
                        mLayout.stickRange[k] = nv;
                    }
                });
                if (cnt > 0) {
                    saveAndDraw();
                }
            }
            return;
        }
        if (isMulti()) {
            RectF mt = mSlTrack[i];
            float v = mt.width() <= 0f ? 0f : (x - mt.left) / mt.width();
            if (v < 0f) v = 0f;
            if (v > 1f) v = 1f;
            applyMultiSlider(i, v);
            return;
        }
        if (mSel == NONE) {
            return;
        }
        RectF t = mSlTrack[i];
        float v = t.width() <= 0f ? 0f : (x - t.left) / t.width();
        if (v < 0f) v = 0f;
        if (v > 1f) v = 1f;
        if (mShapeMode) {
            float nv = PadLayout.sliderToMul(v);
            if (i == SL_W) {
                mLayout.widthMul[mSel] = nv;
            } else if (i == SL_H) {
                mLayout.heightMul[mSel] = nv;
            }
            saveAndDraw();
            return;
        }
        if (i == SL_SIZE) {
            mLayout.scale[mSel] = PadLayout.sliderToScale(v);
        } else if (i == SL_TEXT_SIZE) {
            mLayout.textScale[mSel] = PadLayout.MIN_TEXT_SCALE
                    + v * (PadLayout.MAX_TEXT_SCALE - PadLayout.MIN_TEXT_SCALE);
        } else {
            mLayout.alpha[mSel] = PadLayout.MIN_ALPHA + v * (1f - PadLayout.MIN_ALPHA);
        }
        saveAndDraw();
    }


    /** 「常用工具」列表里点了一项。带 … 的会再弹一层按键列表。 */

    /** 真正执行「重置全部」（确认后才走）。 */
    void resetAllNow() {
        // 【必须按模板号重建，不能只传 keyboardMode】
        //   keyboardMode 是 boolean，空白模板和手柄模板都是 false ——
        //   于是"空白布局点重置全部"会按手柄模板重建，凭空多出一整套键。
        int tpl = mLayout.tplOf(getContext());
        mLayout.reset(mW, mH, mPortrait, NO_BOTTOM_LIMIT, mTopGuard, tpl, getContext());
        computeGeometry();
        mLayout.save(getContext());
        clearSelection();
        mRayElem = NONE;
        closeList();
        mSlDrag = NONE;
        mSlDragPointer = NONE;
        invalidate();
    }

    // ------------------------------------------------------------------
    // 网格 / 吸附
    // ------------------------------------------------------------------

    /** 读网格配置。面板打开前调一次，保证显示的是上次设的值。 */
    private void loadGridCfg() {
        SharedPreferences p = getContext().getSharedPreferences(
                PREFS_GRID, Context.MODE_PRIVATE);
        mGridShow = p.getBoolean(GK_SHOW, false);
        mGridSnap = p.getBoolean(GK_SNAP, false);
        mGridCols = p.getInt(GK_COLS, 8);
        mGridRows = p.getInt(GK_ROWS, 12);
        // 跟随网格默认关：网格吸附会把不等距的键位拽歪，
        // 默认只认"和别的键同轴"这一条，想要贴格子摆再手动开。
        mGridFollow = p.getBoolean(GK_FOLLOW, false);
        mSnapElem = p.getBoolean(GK_ELEM, true);
        mSnapLevel = p.getInt(GK_SENS, 1);
        if (mSnapLevel < 0) mSnapLevel = 0;
        if (mSnapLevel >= SENS_NAMES.length) mSnapLevel = 1;
        if (mGridCols < GRID_COLS_MIN) mGridCols = GRID_COLS_MIN;
        if (mGridCols > GRID_COLS_MAX) mGridCols = GRID_COLS_MAX;
        if (mGridRows < GRID_ROWS_MIN) mGridRows = GRID_ROWS_MIN;
        if (mGridRows > GRID_ROWS_MAX) mGridRows = GRID_ROWS_MAX;
    }

    private void saveGridCfg() {
        getContext().getSharedPreferences(PREFS_GRID, Context.MODE_PRIVATE).edit()
                .putBoolean(GK_SHOW, mGridShow)
                .putBoolean(GK_SNAP, mGridSnap)
                .putInt(GK_COLS, mGridCols)
                .putInt(GK_ROWS, mGridRows)
                .putBoolean(GK_FOLLOW, mGridFollow)
                .putBoolean(GK_ELEM, mSnapElem)
                .putInt(GK_SENS, mSnapLevel)
                .apply();
    }

    // ------------------------------------------------------------------
    // 「固定显示」—— 配各模板的**初始状态**
    // ------------------------------------------------------------------
    //
    // 作用域：凡是"回到初始状态"的路径都吃这份配置 ——
    //   重置全部 / 新建布局 / 按模板新建。
    // 改完**不会**立刻改当前布局，只是记下来，下次回到初始状态才生效。

    /**
     * 行的 code 编码。之所以要编码，是因为三种东西的身份不同：
     *   手柄元素用下标、键盘按键用 usage、"额外添加"又是另一套集合。
     */
    private static final int FX_BASE_PAD = 0;      // + 元素下标
    private static final int FX_BASE_KEY = 1000;   // + HID usage
    private static final int FX_ADD_PAD = 2000;    // + 原型下标
    private static final int FX_ADD_KEY = 3000;    // + HID usage
    /** 功能键（界面按钮）：+ 在 UI_ELEMS 里的序号，不是元素下标 */
    private static final int FX_BASE_UI = 4000;
    /**
     * 固定显示里"额外创建"的鼠标 / 空白 / 组合键。
     *
     * 【为什么又开三段，不挤进 padadd】
     *   padadd 存的是**手柄原型下标**，交给 addPad() 动态分配槽位；
     *   而鼠标槽位是固定的六个、空白和组合键槽位是动态分配、
     *   下标每次 reset 都可能变 —— 三类都记不住"槽位下标"，
     *   只能记"种类 / 第几个"。混进 padadd 会被 addPad 当成原型建错东西。
     *
     * 【为什么排在 FX_ADD_KEY(3000) 之上】
     *   fixState / fixCycle 里 FX_ADD_KEY 那段用的是 `code >= FX_ADD_KEY`
     *   敞口判断，写在它下面会被当成"额外添加的键盘键"处理。
     *   三段都在 3000 之上，判定顺序里也必须排在 FX_ADD_KEY 之前。
     */
    private static final int FX_BASE_MOUSE = 5000;   // + 鼠标种类 which
    private static final int FX_BASE_BLANK = 6000;   // + 第几个（0 起）
    private static final int FX_BASE_COMBO = 7000;   // + 第几个（0 起）

    /**
     * 四个功能键 = 界面按钮。它们**不能删除** ——
     * 删了「编」就再也进不了编辑模式，「收」同理，悬浮窗收不起来，
     * 等于把 app 锁死。所以只有"显示 / 隐藏"两态，外加"跟随模板默认"。
     */
    // ---- 「固定显示」的统一状态配色 ----
    //   绿 = 强制显示 / 蓝 = 强制隐藏 / 红 = 删除 / 灰 = 跟随默认
    //
    // 【为什么红色给"删除"而不是"隐藏"】
    //   红色天然让人联想到"没了"，而这一页的隐藏是"还在、只是不显示"，
    //   用红色会被误读成删掉。所以隐藏改蓝色，红色留给真正的删除。



    /** 取一支只用来画状态底色的 paint（按颜色重设，不污染别的绘制）。 */
    Paint fxPaintOf(int color) {
        mFixCapPaint.setColor(color);
        return mFixCapPaint;
    }

    /** 状态 -> 底色。ST_* 直接当下标用。 */









    /** 图例：四档全列出来，模板页和功能键页共用同一套说明。 */







    private static final int[] UI_ELEMS = {
            PadLayout.I_EDIT, PadLayout.I_COLLAPSE,
            PadLayout.I_LAYOUT, PadLayout.I_PASS,
    };

    /** 第一级列表：三个模板 + 一个「功能键」入口 */



    /*
      【必须避开真实模板 id】
        这个值是"功能键"那一级列表的入口编号，
        直接拿去和 TPL_* 比（buildFixRows 里 tpl == FX_UI_ENTRY）。
        而 TPL_BLANK=0 / TPL_PAD=1 / TPL_KEYBOARD=2 / **TPL_MOUSE=3**。
        写 3 的话它就和鼠标模板撞号：点"功能键"和点"鼠标模板"
        走的是同一条路，鼠标模板永远选不到。
        挪到 4（模板 id 目前最大是 3），以后加新模板也照这个规矩避让。
    */
    private static final int FX_UI_ENTRY = 4;
    /**
     * 功能键那页的四个胶囊，对应四个真实模板。
     *
     * 【必须和 PickList.FX_TPL_SHORT 一一对应、长度一致】
     *   胶囊按 FX_TPL_SHORT 的长度画（PickList 是父类，看不到这个数组），
     *   点第 k 个胶囊时 tplIdx = k 又拿去索引这里 —— 少一项就越界。
     */
    private static final int[] FX_TPLS = {
            PadLayout.TPL_BLANK, PadLayout.TPL_PAD,
            PadLayout.TPL_KEYBOARD, PadLayout.TPL_MOUSE
    };

    /** 三态：0 显示 / 1 隐藏 / 2 删除 / 3 跟随模板默认（功能键才有） */





    int fixState(int tpl, int code) {
        android.content.Context ctx = getContext();
        if (code >= FX_BASE_PAD && code < FX_BASE_KEY) {
            int i = code - FX_BASE_PAD;
            if (PadLayout.fxHas(ctx, tpl, "paddel", i)) return ST_DEL;
            if (PadLayout.fxHas(ctx, tpl, "padshw", i)) return ST_SHOW;
            if (PadLayout.fxHas(ctx, tpl, "padhid", i)) return ST_HIDE;
            return ST_DEF;   // 没写覆盖 = 听模板自己的
        }
        if (code >= FX_BASE_KEY && code < FX_ADD_PAD) {
            int u = code - FX_BASE_KEY;
            if (PadLayout.fxHas(ctx, tpl, "keydel", u)) return ST_DEL;
            if (PadLayout.fxHas(ctx, tpl, "keyshw", u)) return ST_SHOW;
            if (PadLayout.fxHas(ctx, tpl, "keyhid", u)) return ST_HIDE;
            return ST_DEF;
        }
        // 额外添加的键：新键本来就是要加的，没有"默认隐藏"这回事，
        // 所以只有"显示 / 隐藏"两态，第三态是把它从名单里去掉。
        if (code >= FX_ADD_PAD && code < FX_ADD_KEY) {
            int p = code - FX_ADD_PAD;
            return PadLayout.fxHas(ctx, tpl, "padadH", p) ? ST_HIDE : ST_DEF;
        }
        if (code >= FX_ADD_KEY && code < FX_BASE_MOUSE) {
            int u = code - FX_ADD_KEY;
            return PadLayout.fxHas(ctx, tpl, "keyadH", u) ? ST_HIDE : ST_DEF;
        }
        //
        // 【额外创建的鼠标 / 空白 / 组合键：和"额外添加的键"同一套三态】
        //   在名单里 + 没标隐藏 = 显示（ST_DEF）
        //   在名单里 + 标了隐藏 = 隐藏（ST_HIDE）
        //   不在名单里           = 删除（ST_DEL，行消失）
        //
        //   以前这三类只有两态，点一下直接进 ST_DEL —— 于是"点一下就没了"。
        //   隐藏态存在各自的 adH 名单里，建出来时由 PadLayout 置 hidden。
        if (code >= FX_BASE_MOUSE && code < FX_BASE_BLANK) {
            int v = code - FX_BASE_MOUSE;
            if (PadLayout.fxHas(ctx, tpl, "msadH", v)) return ST_HIDE;
            return PadLayout.fxHas(ctx, tpl, "msadd", v) ? ST_DEF : ST_DEL;
        }
        if (code >= FX_BASE_BLANK && code < FX_BASE_COMBO) {
            int v = code - FX_BASE_BLANK;
            if (PadLayout.fxHas(ctx, tpl, "bladH", v)) return ST_HIDE;
            return PadLayout.fxHas(ctx, tpl, "bladd", v) ? ST_DEF : ST_DEL;
        }
        if (code >= FX_BASE_COMBO) {
            int v = code - FX_BASE_COMBO;
            if (PadLayout.fxHas(ctx, tpl, "cmadH", v)) return ST_HIDE;
            return PadLayout.fxHas(ctx, tpl, "cmadd", v) ? ST_DEF : ST_DEL;
        }
        return ST_SHOW;
    }

    /** 模板里那个键默认是隐藏的吗（用于决定循环怎么转）。 */
    private boolean fixDefHide(int code) {
        Boolean b = mFixDefHide.get(Integer.valueOf(code));
        return b != null && b.booleanValue();
    }

    /**
     * 点一行转下一态。
     *
     * 【两套循环，按"模板里默认是不是隐藏的"分开】
     *   默认显示：默认 -> 隐藏 -> 删除 -> 默认
     *   默认隐藏：默认 -> **强制显示** -> 删除 -> 默认
     *
     * 默认隐藏的那种**不能**走"隐藏"那一档 ——
     * 它默认就是隐藏的，再写一次 hid 界面上没变化，
     * 而"显示"这一档反而没了，正是之前 L2(键) 的毛病。
     */
    void fixCycle(int tpl, int code) {
        android.content.Context ctx = getContext();
        int cur = fixState(tpl, code);
        boolean defHide = fixDefHide(code);
        int next;
        if (defHide) {
            next = (cur == ST_DEF) ? ST_SHOW : (cur == ST_SHOW ? ST_DEL : ST_DEF);
        } else {
            next = (cur == ST_DEF) ? ST_HIDE : (cur == ST_HIDE ? ST_DEL : ST_DEF);
        }
        if (code >= FX_BASE_PAD && code < FX_BASE_KEY) {
            int i = code - FX_BASE_PAD;
            PadLayout.fxSave(ctx, tpl, "paddel", remove1(PadLayout.fxLoad(ctx, tpl, "paddel"), i));
            PadLayout.fxSave(ctx, tpl, "padhid", remove1(PadLayout.fxLoad(ctx, tpl, "padhid"), i));
            PadLayout.fxSave(ctx, tpl, "padshw", remove1(PadLayout.fxLoad(ctx, tpl, "padshw"), i));
            if (next == ST_DEL) {
                PadLayout.fxToggle(ctx, tpl, "paddel", i);
            } else if (next == ST_HIDE) {
                PadLayout.fxToggle(ctx, tpl, "padhid", i);
            } else if (next == ST_SHOW) {
                PadLayout.fxToggle(ctx, tpl, "padshw", i);
            }
        } else if (code >= FX_BASE_KEY && code < FX_ADD_PAD) {
            int u = code - FX_BASE_KEY;
            PadLayout.fxSave(ctx, tpl, "keydel", remove1(PadLayout.fxLoad(ctx, tpl, "keydel"), u));
            PadLayout.fxSave(ctx, tpl, "keyhid", remove1(PadLayout.fxLoad(ctx, tpl, "keyhid"), u));
            PadLayout.fxSave(ctx, tpl, "keyshw", remove1(PadLayout.fxLoad(ctx, tpl, "keyshw"), u));
            if (next == ST_DEL) {
                PadLayout.fxToggle(ctx, tpl, "keydel", u);
            } else if (next == ST_HIDE) {
                PadLayout.fxToggle(ctx, tpl, "keyhid", u);
            } else if (next == ST_SHOW) {
                PadLayout.fxToggle(ctx, tpl, "keyshw", u);
            }
        } else if (code >= FX_ADD_PAD && code < FX_ADD_KEY) {
            // 额外添加的手柄键：第三态 = 从"要添加"的名单里去掉
            int p = code - FX_ADD_PAD;
            if (next == ST_DEL) {
                PadLayout.fxSave(ctx, tpl, "padadd", remove1(PadLayout.fxLoad(ctx, tpl, "padadd"), p));
                PadLayout.fxSave(ctx, tpl, "padadH", remove1(PadLayout.fxLoad(ctx, tpl, "padadH"), p));
            } else {
                PadLayout.fxToggle(ctx, tpl, "padadH", p);
            }
        } else if (code >= FX_ADD_KEY && code < FX_BASE_MOUSE) {
            int u = code - FX_ADD_KEY;
            if (next == ST_DEL) {
                PadLayout.fxSave(ctx, tpl, "keyadd", remove1(PadLayout.fxLoad(ctx, tpl, "keyadd"), u));
                PadLayout.fxSave(ctx, tpl, "keyadH", remove1(PadLayout.fxLoad(ctx, tpl, "keyadH"), u));
            } else {
                PadLayout.fxToggle(ctx, tpl, "keyadH", u);
            }
        } else if (code >= FX_BASE_MOUSE && code < FX_BASE_BLANK) {
            //
            // 【和"额外添加的手柄键"同一套】显示 -> 隐藏 -> 删除 -> 显示
            //
            //   鼠标记的是"种类"（六个固定种类），不是序号 ——
            //   删掉哪个就是哪个，不需要重排。
            int v = code - FX_BASE_MOUSE;
            if (next == ST_DEL) {
                PadLayout.fxSave(ctx, tpl, "msadd",
                        remove1(PadLayout.fxLoad(ctx, tpl, "msadd"), v));
                PadLayout.fxSave(ctx, tpl, "msadH",
                        remove1(PadLayout.fxLoad(ctx, tpl, "msadH"), v));
            } else {
                PadLayout.fxToggle(ctx, tpl, "msadH", v);
            }
        } else if (code >= FX_BASE_BLANK && code < FX_BASE_COMBO) {
            int v = code - FX_BASE_BLANK;
            if (next == ST_DEL) {
                // 删掉点中的那个并把编号重排；隐藏名单要跟着一起排，
                // 否则"第 2 个是隐藏的"会错位到别的键上。
                PadLayout.fxSave(ctx, tpl, "bladd",
                        dropAndCompact(PadLayout.fxLoad(ctx, tpl, "bladd"), v));
                PadLayout.fxSave(ctx, tpl, "bladH",
                        dropAndCompact(PadLayout.fxLoad(ctx, tpl, "bladH"), v));
            } else {
                PadLayout.fxToggle(ctx, tpl, "bladH", v);
            }
        } else if (code >= FX_BASE_COMBO) {
            int v = code - FX_BASE_COMBO;
            if (next == ST_DEL) {
                PadLayout.fxSave(ctx, tpl, "cmadd",
                        dropAndCompact(PadLayout.fxLoad(ctx, tpl, "cmadd"), v));
                PadLayout.fxSave(ctx, tpl, "cmadH",
                        dropAndCompact(PadLayout.fxLoad(ctx, tpl, "cmadH"), v));
            } else {
                PadLayout.fxToggle(ctx, tpl, "cmadH", v);
            }
        }
        invalidate();
    }

    /**
     * 删掉 v 这一个，再把后面的往前挪，编号重新连成 0..n-1。
     *
     * 【为什么不能只删 v】
     *   这类名单存的是"第几个"，中间留洞的话下次建出来数量就对不上
     *   （size() 是 3 但实际只建出 1 个）。所以必须重排。
     *
     * 【为什么以前是 dropTail】
     *   那版把 v 及其之后的全部删掉，于是点第一个 = 全删光 ——
     *   用户看到的就是"点一下全没了"。删点中的那个再重排才对。
     *
     * 【形状分组各排各的】
     *   十字架的编码带 100 的偏移（PadLayout.FX_COMBO_CROSS_BASE），
     *   和普通的序号各成一串，删一个只影响自己那一串。
     */
    private static java.util.HashSet<Integer> dropAndCompact(
            java.util.HashSet<Integer> set, int v) {
        //
        // 【不能因为 v 不在集合里就直接返回】
        //   隐藏名单（bladH / cmadH）里未必有 v，但它后面的编号照样要往前挪 ——
        //   否则删掉第 1 个之后，"第 2 个隐藏"会错位成"第 1 个隐藏"。
        boolean cross = v >= PadLayout.FX_COMBO_CROSS_BASE;
        int base = cross ? PadLayout.FX_COMBO_CROSS_BASE : 0;
        int seq = v - base;
        java.util.ArrayList<Integer> all = new java.util.ArrayList<Integer>(set);
        java.util.Collections.sort(all);
        java.util.HashSet<Integer> out = new java.util.HashSet<Integer>();
        int n = 0;
        for (Integer k : all) {
            int kk = k.intValue();
            if ((kk >= PadLayout.FX_COMBO_CROSS_BASE) != cross) {
                out.add(Integer.valueOf(kk));   // 另一组不受影响，原样留着
                continue;
            }
            if (kk - base == seq) {
                continue;                        // 就是被点的那个
            }
            out.add(Integer.valueOf(base + n));
            n++;
        }
        return out;
    }

    /** HashSet 的迭代顺序不保证，行序会随机跳 —— 排成升序再列。 */
    private static java.util.ArrayList<Integer> sortedAsc(
            java.util.HashSet<Integer> set) {
        java.util.ArrayList<Integer> r = new java.util.ArrayList<Integer>(set);
        java.util.Collections.sort(r);
        return r;
    }

    /**
     * 往"额外创建"的名单里加一个（空白 / 组合键这类没有种类可选的）。
     *
     * 编号取 0..n-1 里第一个空的 —— 名单只表示"建几个"，
     * 中间不能有洞，否则 dropTail 从中间抽掉一个时后面的编号会错位。
     */
    void fixAddOne(String kind, boolean cross) {
        // 软上限：模板自己还会带一些，超了 addBlank/addCombo 会返回 -1 静默少建，
        // 与其让用户"加了却看不见"，不如在这儿就挡住。
        int cap = "bladd".equals(kind) ? PadLayout.MAX_BLANK
                : "cmadd".equals(kind) ? PadLayout.MAX_COMBO : 32;
        java.util.HashSet<Integer> set = PadLayout.fxLoad(getContext(), mFixTpl, kind);
        if (set.size() >= cap) {
            toastLocal("这类最多建 " + cap + " 个，已经到上限了");
            return;
        }
        // 只有组合键有形状这一维，借高位塞进同一个 set
        int base = (cross && "cmadd".equals(kind))
                ? PadLayout.FX_COMBO_CROSS_BASE : 0;
        int n = 0;
        while (set.contains(Integer.valueOf(base + n))) {
            n++;
        }
        set.add(Integer.valueOf(base + n));
        PadLayout.fxSave(getContext(), mFixTpl, kind, set);
    }

    private static java.util.HashSet<Integer> remove1(java.util.HashSet<Integer> set, int v) {
        set.remove(Integer.valueOf(v));
        return set;
    }

    /**
     * 重建第二级列表的行。
     *
     * 基准是一份**不带覆盖**的干净模板（reset 传 ctx=null），
     * 这样"删掉再想加回来"时还能找到它 —— 否则删了就从列表里消失了，
     * 用户没法撤销。
     */
    void buildFixRows(int tpl) {
        if (tpl == FX_UI_ENTRY) {
            mFixUiMode = true;
            buildFixUiRows();
            return;
        }
        mFixUiMode = false;
        mFixTpl = tpl;
        PadLayout base = new PadLayout();
        base.reset(mW, mH, mPortrait, NO_BOTTOM_LIMIT, mTopGuard, tpl);
        android.content.Context ctx = getContext();
        java.util.ArrayList<Integer> codes = new java.util.ArrayList<Integer>();
        java.util.ArrayList<String> names = new java.util.ArrayList<String>();

        // 1) 模板自带的手柄元素
        for (int i = 0; i < PadLayout.N_FIXED; i++) {
            if (PadLayout.isUiButton(i) || base.padType[i] == 0) {
                continue;
            }
            codes.add(Integer.valueOf(FX_BASE_PAD + i));
            names.add(base.nameOf(i));
            mFixDefHide.put(Integer.valueOf(FX_BASE_PAD + i),
                    Boolean.valueOf(base.hidden[i]));
        }
        // 2) 模板自带的键盘按键
        for (int i = PadLayout.I_KEY0; i < PadLayout.N_KEY_END; i++) {
            if (base.keyCode[i] == 0) {
                continue;
            }
            int u = base.keyCode[i];
            codes.add(Integer.valueOf(FX_BASE_KEY + u));
            names.add(PadLayout.keyName(u));
            mFixDefHide.put(Integer.valueOf(FX_BASE_KEY + u),
                    Boolean.valueOf(base.hidden[i]));
        }
        // 2.5) 模板自带的鼠标元素（触摸板 / 左键 / 右键 / 中键 / 滚轮上 / 滚轮下）
        //
        // 【为什么单列一段】
        //   1) 那段只遍历 0..N_FIXED(22)，而鼠标槽位在 I_FLOAT+1 ≈ 54，
        //      根本进不了循环 —— 选了鼠标模板会得到一个空列表，
        //      只剩两行「＋ 添加…」，看着像功能坏了。
        //
        // 【编号为什么复用 FX_BASE_PAD】
        //   鼠标槽位下标 54..59，而 FX_BASE_PAD=0、FX_BASE_KEY=1000，
        //   中间空了 900 多个编号，FX_BASE_PAD + 54 不会撞到键盘区。
        //   fixState / fixCycle 里那段 code >= FX_BASE_PAD && < FX_BASE_KEY
        //   的判断照样成立，读写的是 paddel / padshw / padhid 三份集合，
        //   applyFixedInit 里对 i < N 的元素一律生效 —— 鼠标槽位也在 N 内，
        //   所以"强制隐藏 / 强制显示 / 删除"这套机制本来就能管到它们，
        //   只是以前列表里没列出来。
        for (int i = PadLayout.I_MOUSE_PAD; i <= PadLayout.I_MOUSE_WD; i++) {
            if (!base.isMouseUsed(i)) {
                continue;
            }
            codes.add(Integer.valueOf(FX_BASE_PAD + i));
            names.add(base.nameOf(i));
            mFixDefHide.put(Integer.valueOf(FX_BASE_PAD + i),
                    Boolean.valueOf(base.hidden[i]));
        }
        // 3) 额外添加的手柄键
        for (Integer v : PadLayout.fxLoad(ctx, tpl, "padadd")) {
            codes.add(Integer.valueOf(FX_ADD_PAD + v.intValue()));
            names.add(PadLayout.NAMES[v.intValue()] + "（新）");
        }
        // 4) 额外添加的键盘键
        for (Integer v : PadLayout.fxLoad(ctx, tpl, "keyadd")) {
            codes.add(Integer.valueOf(FX_ADD_KEY + v.intValue()));
            names.add(PadLayout.keyName(v.intValue()) + "（新）");
        }
        // 4.1) 额外创建的鼠标 / 空白 / 组合键
        //
        // 【只记"种类 / 第几个"，不记槽位下标】
        //   鼠标是六个固定槽位但装哪种不固定（addMouse 挑第一个空的填），
        //   空白 / 组合键槽位更是每次 reset 都可能不一样 ——
        //   记下标下次就对不上了。所以鼠标记种类（which），
        //   空白 / 组合键记 0..n-1 的连续序号，只表示"建几个"。
        for (Integer v : sortedAsc(PadLayout.fxLoad(ctx, tpl, "msadd"))) {
            codes.add(Integer.valueOf(FX_BASE_MOUSE + v.intValue()));
            names.add(PadLayout.mouseNameOf(v.intValue()) + "（新）");
        }
        for (Integer v : sortedAsc(PadLayout.fxLoad(ctx, tpl, "bladd"))) {
            codes.add(Integer.valueOf(FX_BASE_BLANK + v.intValue()));
            names.add("空白 " + (v.intValue() + 1) + "（新）");
        }
        for (Integer v : sortedAsc(PadLayout.fxLoad(ctx, tpl, "cmadd"))) {
            int vc = v.intValue();
            boolean cross = vc >= PadLayout.FX_COMBO_CROSS_BASE;
            int seq = cross ? vc - PadLayout.FX_COMBO_CROSS_BASE : vc;
            codes.add(Integer.valueOf(FX_BASE_COMBO + vc));
            // 形状写进名字里 —— 不然两个都叫"组合键 1"，分不出哪个是十字架
            names.add("组合键 " + (seq + 1) + "（新" + (cross ? "·十字架" : "") + "）");
        }
        // 5) 一个"添加"入口
        //
        // 【只留一行，不按种类拆】
        //   手柄 / 键盘 / 鼠标 / 空白 / 组合键拆开就是五行，底部太长。
        //   点它直接进「按键创建」界面（LIST_CREATE），那五种都在里面，
        //   选完由 createPad / createKey / createMouse / createBlank /
        //   createCombo 各自的 mFixPicking 分支记进名单。
        codes.add(Integer.valueOf(FX_ACT_ADD));
        names.add("＋ 添加…");

        int n = codes.size();
        mFixCode = new int[n];
        mFixName = new String[n];
        for (int i = 0; i < n; i++) {
            mFixCode[i] = codes.get(i).intValue();
            mFixName[i] = names.get(i);
        }
    }

    /** 功能键那页：4 行，每行是「编 / 收 / 布 / 透」。 */
    private void buildFixUiRows() {
        PadLayout base = new PadLayout();
        base.reset(mW, mH, mPortrait, NO_BOTTOM_LIMIT, mTopGuard, PadLayout.TPL_PAD);
        int n = UI_ELEMS.length;
        mFixCode = new int[n];
        mFixName = new String[n];
        for (int i = 0; i < n; i++) {
            mFixCode[i] = FX_BASE_UI + i;
            mFixName[i] = base.nameOf(UI_ELEMS[i]);
        }
    }

    /** 某个功能键在某个模板下的状态：强制显示 / 强制隐藏 / 跟随默认。 */
    int fixUiState(int uiIdx, int tplIdx) {
        int ui = UI_ELEMS[uiIdx];
        int tpl = FX_TPLS[tplIdx];
        android.content.Context ctx = getContext();
        if (PadLayout.fxHas(ctx, tpl, "padshw", ui)) return ST_SHOW;
        if (PadLayout.fxHas(ctx, tpl, "padhid", ui)) return ST_HIDE;
        return ST_DEF;
    }

    /** 点一个功能键胶囊：默认 -> 显示 -> 隐藏 -> 默认。 */
    void fixUiCycle(int uiIdx, int tplIdx) {
        int ui = UI_ELEMS[uiIdx];
        int tpl = FX_TPLS[tplIdx];
        android.content.Context ctx = getContext();
        int cur = fixUiState(uiIdx, tplIdx);
        int next = (cur == ST_DEF) ? ST_SHOW : (cur == ST_SHOW ? ST_HIDE : ST_DEF);
        // 先把两个集合都清掉，再按新状态写一个 —— 不然"显示"和"隐藏"
        // 会同时留在两份集合里，applyFixedInit 里后写的赢，结果不可预期。
        PadLayout.fxSave(ctx, tpl, "padshw",
                remove1(PadLayout.fxLoad(ctx, tpl, "padshw"), ui));
        PadLayout.fxSave(ctx, tpl, "padhid",
                remove1(PadLayout.fxLoad(ctx, tpl, "padhid"), ui));
        if (next == ST_SHOW) {
            PadLayout.fxToggle(ctx, tpl, "padshw", ui);
        } else if (next == ST_HIDE) {
            PadLayout.fxToggle(ctx, tpl, "padhid", ui);
        }
        invalidate();
    }

    String fixRowName(int pos) {
        if (pos < 0 || pos >= mFixName.length) {
            return "";
        }
        int code = mFixCode[pos];
        if (isFixAct(code) || mFixUiMode) {
            return mFixName[pos];
        }
        String tail = "";
        int st = fixState(mFixTpl, code);
        if (st == ST_SHOW) tail = "  显示";
        else if (st == ST_HIDE) tail = "  隐藏";
        else if (st == ST_DEL) tail = "  删除";
        else if (st == ST_DEF && fixDefHide(code)) tail = "  默认隐藏";
        return mFixName[pos] + tail;
    }

    void openFixedCfg() {
        openList(LIST_FIX_TPL);
    }

    void openGridCfg() {
        loadGridCfg();
        openList(LIST_GRID);
    }

    /**
     * 画网格设置的一行：左边名字，右边是状态（开关行）或 − 数值 +（数值行）。
     *
     * 名字左对齐而不是居中：右边被控件占掉一块，居中的话文字会压上去。
     */
    void drawGridRow(Canvas c, int pos, RectF r) {
        boolean on = false;
        if (pos == GI_SHOW) on = mGridShow;
        else if (pos == GI_SNAP) on = mGridSnap;
        else if (pos == GI_FOLLOW) on = mGridFollow;
        else if (pos == GI_ELEM) on = mSnapElem;

        c.drawRoundRect(r, dp(8f), dp(8f), on ? mSlFillPaint : mSlTrackPaint);
        mBarTextPaint.setTextAlign(Paint.Align.LEFT);
        mBarTextPaint.setTextSize(dp(15f));
        mBarTextPaint.setColor(on ? 0xFFFFFFFF : 0xDDFFFFFF);
        Paint.FontMetrics f = mBarTextPaint.getFontMetrics();
        float ty = r.centerY() - (f.ascent + f.descent) / 2f;
        c.drawText(gridRowName(pos), r.left + dp(12f), ty, mBarTextPaint);

        if (gridRowIsToggle(pos)) {
            // 开关：右侧写"开 / 关"，勾选项背景已经是蓝色，够明确了
            mBarTextPaint.setTextAlign(Paint.Align.RIGHT);
            mBarTextPaint.setColor(on ? 0xFFFFFFFF : 0xFF9E9E9E);
            c.drawText(gridRowState(pos), r.right - dp(14f), ty, mBarTextPaint);
            mBarTextPaint.setTextAlign(Paint.Align.CENTER);
            return;
        }

        RectF rm = mGridMinusRects[pos];
        RectF rp = mGridPlusRects[pos];
        mBarTextPaint.setTextAlign(Paint.Align.CENTER);
        mBarTextPaint.setTextSize(dp(16f));
        Paint.FontMetrics f2 = mBarTextPaint.getFontMetrics();
        float by = (rm.top + rm.bottom) / 2f - (f2.ascent + f2.descent) / 2f;
        c.drawRoundRect(rm, dp(6f), dp(6f), mToolBtnPaint);
        c.drawRoundRect(rp, dp(6f), dp(6f), mToolBtnPaint);
        mBarTextPaint.setColor(0xFF212121);
        c.drawText("−", rm.centerX(), by, mBarTextPaint);
        c.drawText("+", rp.centerX(), by, mBarTextPaint);

        // 数值画在 − 和 + 之间
        mBarTextPaint.setTextSize(dp(14f));
        mBarTextPaint.setColor(0xFF1565C0);
        Paint.FontMetrics f3 = mBarTextPaint.getFontMetrics();
        c.drawText(gridRowState(pos), (rm.right + rp.left) / 2f,
                (rm.top + rm.bottom) / 2f - (f3.ascent + f3.descent) / 2f,
                mBarTextPaint);
        mBarTextPaint.setColor(0xFFFFFFFF);
    }

    /** 数值行的 − / +。改完立刻存档，不用点确定。 */
    void gridStep(int pos, int delta) {
        switch (pos) {
            case GI_COLS: {
                int v = mGridCols + delta;
                if (v < GRID_COLS_MIN) v = GRID_COLS_MIN;
                if (v > GRID_COLS_MAX) v = GRID_COLS_MAX;
                mGridCols = v;
                break;
            }
            case GI_ROWS: {
                int v = mGridRows + delta;
                if (v < GRID_ROWS_MIN) v = GRID_ROWS_MIN;
                if (v > GRID_ROWS_MAX) v = GRID_ROWS_MAX;
                mGridRows = v;
                break;
            }
            case GI_SENS: {
                int v = mSnapLevel + delta;
                if (v < 0) v = 0;
                if (v >= SENS_NAMES.length) v = SENS_NAMES.length - 1;
                mSnapLevel = v;
                break;
            }
            default:
                return;
        }
        saveGridCfg();
        invalidate();
    }

    /** 开关行：点整行切换。 */
    void gridToggle(int pos) {
        switch (pos) {
            case GI_SHOW:
                mGridShow = !mGridShow;
                break;
            case GI_SNAP:
                mGridSnap = !mGridSnap;
                break;
            case GI_FOLLOW:
                mGridFollow = !mGridFollow;
                break;
            case GI_ELEM:
                mSnapElem = !mSnapElem;
                break;
            default:
                // 数值行点名字区也给个"往大调一次"的反馈，比点了没反应好
                gridStep(pos, +1);
                return;
        }
        saveGridCfg();
        invalidate();
    }

    /** 网格 / 吸附设置某一行的标题（右侧的状态在绘制时另外画）。 */
    String gridRowName(int pos) {
        switch (pos) {
            case GI_SHOW:
                return "显示网格";
            case GI_COLS:
                return "网格列数";
            case GI_ROWS:
                return "网格行数";
            case GI_SNAP:
                return "自动吸附";
            case GI_FOLLOW:
                return "吸附跟随网格";
            case GI_ELEM:
                return "吸附看齐按钮";
            case GI_SENS:
                return "吸附灵敏度";
            default:
                return "";
        }
    }

    /** 这一行是不是"点整行就切换"的开关（不是就走 − / + 步进）。 */
    boolean gridRowIsToggle(int pos) {
        return pos == GI_SHOW || pos == GI_SNAP
                || pos == GI_FOLLOW || pos == GI_ELEM;
    }

    /** 开关行右侧的当前状态文字。 */
    private String gridRowState(int pos) {
        switch (pos) {
            case GI_SHOW:
                return mGridShow ? "开" : "关";
            case GI_SNAP:
                return mGridSnap ? "开" : "关";
            case GI_FOLLOW:
                return mGridFollow ? "开" : "关";
            case GI_ELEM:
                return mSnapElem ? "开" : "关";
            case GI_COLS:
                return String.valueOf(mGridCols);
            case GI_ROWS:
                return String.valueOf(mGridRows);
            case GI_SENS:
                return (mSnapLevel >= 0 && mSnapLevel < SENS_NAMES.length)
                        ? SENS_NAMES[mSnapLevel] : SENS_NAMES[1];
            default:
                return "";
        }
    }

    /**
     * 网格设置列表的布局：单列竖排，项数决定高度 —— 和别的列表一个路子。
     *
     * 数值行右侧放 − / +，开关行右侧放状态文字（点整行切换）。
     */

    void closeGridCfg() {
        mGridCfg = false;
        saveGridCfg();
        invalidate();
    }

    /**
     * 画编辑期的网格线。
     *
     * 只在编辑模式 + 开关打开时画，运行时不画 —— 它只是摆键位时的参考线。
     * 画在按键**下面**（调用点在按键绘制之前），不会盖住按钮。
     */
    private void drawGrid(Canvas c) {
        if (!mGridShow || mW <= 0 || mH <= 0) {
            return;
        }
        if (mGridCols < 2 || mGridRows < 2) {
            return;
        }
        mGridPaint.setStrokeWidth(dp(1f));
        float cw = (float) mW / mGridCols;
        float ch = (float) mH / mGridRows;
        for (int i = 1; i < mGridCols; i++) {
            float x = i * cw;
            c.drawLine(x, 0f, x, mH, mGridPaint);
        }
        for (int j = 1; j < mGridRows; j++) {
            float y = j * ch;
            c.drawLine(0f, y, mW, y, mGridPaint);
        }
    }

    // ------------------------------------------------------------------
    // 对齐射线
    //
    // 【吸附到底吸什么】
    //   不看网格了 —— 网格是等分的，可键位本来就不等距，按网格吸反而会把
    //   摆齐的一排拽歪。现在只认一件事：**和别的键同轴**（中心 x 相同或
    //   中心 y 相同）。网格退化成纯装饰参考线。
    //
    // 【为什么要画出来】
    //   吸附是"松手瞬间"才发生的，拖的过程中看不出会不会吸上 ——
    //   于是把两条中心线画成射线：自己的是亮色，靠近的候选是暗色，
    //   真吸上的那条变高亮。看得见才敢松手。
    // ------------------------------------------------------------------

    /**
     * 画对齐射线。
     *
     * 选中（或拖动中）的键画一对；拖动时把"够近、可能吸上"的候选键的线
     * 也画出来 —— 只画候选，不画全部：键盘布局有 80 多个键，
     * 全画出来是 160 多条线，整个屏幕糊成一片，反而看不见要对齐谁。
     */
    private void drawAlignRays(Canvas c) {
        if (!mEditMode || mSel == NONE || mW <= 0 || mH <= 0) {
            return;
        }
        int i = mSel;
        boolean dragging = (mRayElem == i);
        float tol = snapTol();

        // 实时算一次"现在会不会吸上"，用来决定哪条线高亮
        int hitX = NONE;
        int hitY = NONE;
        if (mGridSnap) {
            float dxMin = tol;
            float dyMin = tol;
            for (int j = 0; j < PadLayout.N; j++) {
                if (j == i || !pickable(j) || sameAnchor(i, j)) {
                    continue;
                }
                float ax = Math.abs(mPx[j] - mPx[i]);
                if (ax < dxMin) {
                    dxMin = ax;
                    hitX = j;
                }
                float ay = Math.abs(mPy[j] - mPy[i]);
                if (ay < dyMin) {
                    dyMin = ay;
                    hitY = j;
                }
            }
        }

        // 自己的两条射线（亮色）
        drawRayPair(c, i, true, hitX, hitY);

        if (!dragging) {
            return;
        }
        // 候选键的射线（暗色），只画"够近"的
        float near = Math.max(tol * 3f, dp(64f));
        for (int j = 0; j < PadLayout.N; j++) {
            if (j == i || !pickable(j)) {
                continue;
            }
            boolean nx = Math.abs(mPx[j] - mPx[i]) < near;
            boolean ny = Math.abs(mPy[j] - mPy[i]) < near;
            if (!nx && !ny) {
                continue;
            }
            // 自己那条轴没被算作候选的，就不画（避免多画一堆无关的线）
            drawRayPair(c, j, false,
                    nx ? (mGridSnap ? hitX : NONE) : NONE,
                    ny ? (mGridSnap ? hitY : NONE) : NONE);
        }
    }

    /**
     * 画一个元素的"十字"射线：竖线画在中心 x 上、横线画在中心 y 上。
     *
     * 中间留空（从选中圈边缘起画），不然线会从按钮正中间穿过去，
     * 盖住键帽上的字。
     */
    private void drawRayPair(Canvas c, int i, boolean self,
                             int hitX, int hitY) {
        float hw = halfW(i) + dp(5f);
        float hh = halfH(i) + dp(5f);
        if (isTrigger(i)) {
            // 扳机是滑轨形状，用轨道半高当竖向留空，圆半径当横向留空
            hh = trackHalfH(i) + dp(6f);
            hw = radiusOf(i) + dp(6f);
        }
        float x = mPx[i];
        float y = mPy[i];

        Paint pv = (self && hitX != NONE) ? mRayHitPaint : mRayPaint;
        Paint ph = (self && hitY != NONE) ? mRayHitPaint : mRayPaint;
        // 候选键统一用暗色，避免抢了"当前拖动这个"的视觉焦点
        if (!self) {
            pv = mRayPaint;
            ph = mRayPaint;
            mRayPaint.setAlpha(0x70);
        }

        // 竖线（中心 x 相同 = 同一列）
        if (y - hh > 0f) {
            c.drawLine(x, 0f, x, y - hh, pv);
        }
        if (y + hh < mH) {
            c.drawLine(x, y + hh, x, mH, pv);
        }
        // 横线（中心 y 相同 = 同一行）
        if (x - hw > 0f) {
            c.drawLine(0f, y, x - hw, y, ph);
        }
        if (x + hw < mW) {
            c.drawLine(x + hw, y, mW, y, ph);
        }

        if (!self) {
            mRayPaint.setAlpha(0xFF);
        }
    }

    /**
     * 网格配置面板上的两行步进器。
     *
     * 行内布局：左边标签 + 数值，右边 − / + 两个按钮。
     */
    private void drawGridStepRow(Canvas c, RectF row, String label, int val,
                                 RectF minus, RectF plus) {
        // 【为什么给文字加一块深色底】
        //   面板背景是浅灰，原来的深灰字（0xFF212121）在上面偏淡、字又小，
        //   和背景几乎糊成一团。这里改成"深色胶囊 + 白字"，
        //   不管底下列表深浅都能看清，比单纯调颜色稳。
        String txt = label + "  " + val;
        float ts = Math.max(dp(15f), row.height() * 0.44f);
        mBarTextPaint.setTextAlign(Paint.Align.LEFT);
        mBarTextPaint.setTextSize(ts);
        float tw = mBarTextPaint.measureText(txt);
        Paint.FontMetrics f = mBarTextPaint.getFontMetrics();
        float ty = row.centerY() - (f.ascent + f.descent) / 2f;

        float chipL = dp(10f);
        float chipR = chipL + tw + dp(18f);
        float chipT = row.centerY() - ts * 0.95f;
        float chipB = row.centerY() + ts * 0.95f;
        int save = c.save();
        mPanelPaint.setColor(0xEE1F1F1F);
        c.drawRoundRect(chipL, chipT, chipR, chipB, dp(8f), dp(8f), mPanelPaint);
        mPanelPaint.setColor(0xE8000000);
        c.restoreToCount(save);

        mBarTextPaint.setColor(0xFFFFFFFF);
        c.drawText(txt, chipL + dp(9f), ty, mBarTextPaint);

        mBarTextPaint.setTextAlign(Paint.Align.CENTER);
        mBarTextPaint.setTextSize(Math.min(row.height() * 0.40f, dp(15f)));
        Paint.FontMetrics f2 = mBarTextPaint.getFontMetrics();
        float by = row.centerY() - (f2.ascent + f2.descent) / 2f;
        c.drawRoundRect(minus, dp(6f), dp(6f), mToolBtnPaint);
        mBarTextPaint.setColor(0xFF212121);
        c.drawText("−", minus.centerX(), by, mBarTextPaint);
        c.drawRoundRect(plus, dp(6f), dp(6f), mToolBtnPaint);
        c.drawText("+", plus.centerX(), by, mBarTextPaint);
    }

    /** 网格 / 吸附面板的提示行。 */
    private String gridTip() {
        return "网格 " + mGridCols + " × " + mGridRows
                + (mGridShow ? "（已显示）" : "（未显示）")
                + " — 网格只作参考，不参与吸附"
                + (mGridSnap ? "；吸附开：与其它键同一行/列时自动对齐"
                             : "；吸附关");
    }

    // ------------------------------------------------------------------
    // 多选调节：面板开关 / 快照 / 整体缩放
    // ------------------------------------------------------------------

    /**
     * 列表勾完 -> 变成当前选中集合，停在常规面板。
     *
     * 面板长什么样由 selCount() 自己决定（1 个 = 单键面板，
     * 多个 = 批量面板），不用再单独开一个"批量开关"。
     */
    void openMultiAdj() {
        applySelectionFromList();
        closeList();
        mShapeMode = false;
        mRayElem = NONE;
        setPanelState(PANEL_FULL);
        invalidate();
    }

    /**
     * 同上，但顺手把「形状」子界面打开。
     *
     * 单选时进去 = 改这一个的形状；多选时进去 = 把整批刷成同一种 ——
     * 界面是同一个，只是作用范围跟着选中集合走。
     */
    void openMultiShape() {
        applySelectionFromList();
        closeList();
        mShapeMode = true;
        mRayElem = NONE;
        setPanelState(PANEL_FULL);
        invalidate();
    }

    /** 把整批刷成同一种形状。矩形要给个常规比例，否则手柄键会变正方形。 */
    private void applyMultiShape(int shape) {
        if (mLayout == null) {
            return;
        }
        int n = 0;
        for (int i = 0; i < PadLayout.N; i++) {
            if (!mSelSet[i]) {
                continue;
            }
            mLayout.shape[i] = shape;
            if (shape == PadLayout.SHAPE_RECT
                    && mLayout.widthMul[i] == 1f && mLayout.heightMul[i] == 1f) {
                mLayout.widthMul[i] = 1.19f;
                mLayout.heightMul[i] = 1.06f;
            }
            n++;
        }
        mLayout.save(getContext());
        closeMultiAdj();
        toastLocal("已把 " + n + " 个按钮设为"
                + (shape == PadLayout.SHAPE_CIRCLE ? "圆形" : "四边形"));
    }

    /** 取消选中（回到"什么都没选"）。 */
    private void closeMultiAdj() {
        clearSelection();
        invalidate();
    }

    /**
     * 拍快照：记下此刻的位置和尺寸。
     *
     * 整体缩放的倍率 k 永远相对这份快照算，而不是相对"上一次拖动的结果"。
     * 否则每次拖一点就在新结果上再乘一次，滑条往回拖也回不到原样
     * （浮点误差 + 累积舍入，来回拖几次尺寸就漂移了）。
     */
    void snapshotAdj() {
        for (int i = 0; i < PadLayout.N; i++) {
            mAdjSnapRx[i] = mLayout.rx[i];
            mAdjSnapRy[i] = mLayout.ry[i];
            mAdjSnapScale[i] = mLayout.scale[i];
        }
    }

    /**
     * 按倍率 k 整体缩放这一批。
     *
     *   位置：rx = 中心.rx + (原.rx − 中心.rx) × k
     *         —— 朝中心按钮聚拢，所以**连间距一起缩小**。
     *   尺寸：scale = 原.scale × k
     *         —— 按钮本身同步变小。
     *
     * 两者用同一个 k，才像"把这一片当一个整体缩放"，
     * 而不是"位置和大小各缩各的"。
     */
    void applyAdjScale(float k) {
        if (mAdjAnchor == NONE || mLayout == null) {
            return;
        }
        float ax = mAdjSnapRx[mAdjAnchor];
        float ay = mAdjSnapRy[mAdjAnchor];
        for (int i = 0; i < PadLayout.N; i++) {
            if (!mSelSet[i]) {
                continue;
            }
            if (i != mAdjAnchor) {
                mLayout.rx[i] = ax + (mAdjSnapRx[i] - ax) * k;
                mLayout.ry[i] = ay + (mAdjSnapRy[i] - ay) * k;
            }
            mLayout.scale[i] = mAdjSnapScale[i] * k;
        }
        computeGeometry();
    }


    /** 真正执行「重置单个」（确认后才走）。 */

    /** 真正执行「批量删除」（确认后才走）。 */

    /**
     * 创建一份手柄元素并选中它。
     *
     * 流程和 createKey 一致：建完立刻 computeGeometry() 把比例换成像素，
     * 否则新元素会画在 (0,0)。
     */
    void createPad(int pos) {
        if (pos < 0 || pos >= PadLayout.PAD_CANDIDATES.length) {
            return;
        }
        int proto = PadLayout.PAD_CANDIDATES[pos];
        // 组合键的挑选模式：不建到布局上，只把这个键填进正在挑的那一格
        if (mComboPickIdx != COMBO_PICK_NONE) {
            int slot = mComboPickIdx;
            mComboPickIdx = COMBO_PICK_NONE;
            //
            // 【摇杆 / 十字键不能直接当按钮发】
            //   它们要的是轴值 / hat 值，onButton 表达不了。
            //   之前照样写进动作表，执行时 buttonIndexOfProto 落 default
            //   发出 BTN_START —— 选了左摇杆结果发出 START 键。
            //   所以这两个先弹方向列表，确定方向后再写。
            if (proto == PadLayout.I_DPAD) {
                mComboDirSlot = slot;
                mComboDirProto = proto;
                openListBack(LIST_COMBO_DIR, LIST_COMBO_EDIT);
                return;
            }
            if (proto == PadLayout.I_L2 || proto == PadLayout.I_R2) {
                // 扳机是模拟量，先问扣多深
                mComboDirSlot = slot;
                mComboDirProto = proto;
                mTrigPct = 100;
                openListBack(LIST_COMBO_TRIG, LIST_COMBO_EDIT);
                return;
            }
            if (proto == PadLayout.I_LS || proto == PadLayout.I_RS) {
                // 摇杆有 8 向预设和自定义坐标两种，先问用哪种
                mComboDirSlot = slot;
                mComboDirProto = proto;
                mStickX = 0;
                mStickY = 0;
                openListBack(LIST_COMBO_STICK_MODE, LIST_COMBO_EDIT);
                return;
            }
            mLayout.setComboAct(mComboEditIdx, mComboEditDir, slot, COMBO_ACT_PAD,
                    PadLayout.typeOfElem(proto));
            syncComboAutoName();
            afterComboPick();
            return;
        }
        // 「固定显示」的挑选模式：不真的建，只把原型记进该模板的初始状态
        if (mFixPicking) {
            mFixPicking = false;
            PadLayout.fxToggle(getContext(), mFixTpl, "padadd", proto);
            buildFixRows(mFixTpl);
            openList(LIST_FIX);
            return;
        }
        int idx = mLayout.addPad(proto);
        if (idx < 0) {
            toastLocal("手柄按键已达上限（" + PadLayout.MAX_PAD_EXTRA + "个），先删一个再建");
            closeList();
            invalidate();
            return;
        }
        // 先清旧的选中，再选中新建的这个 ——
        // 反过来的话 clearSelection() 会把刚设的 mSel 一起清成 NONE，
        // 面板就变成"什么都没选"，新建的键还得再点一次才调得了。
        clearSelection();
        selectSingle(idx);
        computeGeometry();
        mLayout.save(getContext());
        if (mKeyKeepOpen) {
            mSel = idx;
            invalidate();
        } else {
            closeList();
            if (!mEditMode) {
                setEditMode(true, false);
            }
        }
        invalidate();
    }

    /**
     * 创建一个鼠标元素（触摸板 / 左键 / 右键 / 中键 / 滚轮上 / 滚轮下）。
     *
     * 【和 createPad 的关键区别】
     *   手柄按键有几十个副本槽位，同一种能建好几个；
     *   鼠标六个种类各占一个固定槽位，建过了再建就没有位置了 ——
     *   这时提示先删一个，而不是静默失败。
     */
    void createMouse(int pos) {
        if (pos < 0 || pos >= MOUSE_CANDIDATES.length) {
            return;
        }
        int which = MOUSE_CANDIDATES[pos];
        //
        // 【「固定显示」的挑选模式：不真的建，只把种类记进该模板的名单】
        //   必须排在下面"这个种类已经在布局上了"的检查**之前** ——
        //   那个检查看的是 mLayout（当前布局），而固定显示管的是模板，
        //   拿当前布局去判断"模板上有没有"会得出错的结论。
        if (mFixPicking) {
            mFixPicking = false;
            PadLayout.fxToggle(getContext(), mFixTpl, "msadd", which);
            buildFixRows(mFixTpl);
            openList(LIST_FIX);
            return;
        }
        // 这个种类已经在布局上了 -> 直接选中它，不重复建
        for (int i = PadLayout.I_MOUSE_PAD; i <= PadLayout.I_MOUSE_WD; i++) {
            if (mLayout.isMouseUsed(i)
                    && PadLayout.protoOfType(mLayout.padType[i]) == which) {
                clearSelection();
                selectSingle(i);
                computeGeometry();
                closeList();
                if (!mEditMode) {
                    setEditMode(true, false);
                }
                toastLocal(PadLayout.mouseNameOf(which) + " 已经有了");
                invalidate();
                return;
            }
        }
        int idx = mLayout.addMouse(which, Math.min(mW, mH) * 0.08f);
        if (idx < 0) {
            toastLocal("鼠标元素已达上限（" + MOUSE_CANDIDATES.length
                    + "个），先删一个再建");
            closeList();
            invalidate();
            return;
        }
        // 新建的放到屏幕中间，和 addPad 一致：建完自己拖到想要的位置
        mLayout.rx[idx] = 0.5f;
        mLayout.ry[idx] = 0.5f;
        clearSelection();
        selectSingle(idx);
        computeGeometry();
        mLayout.save(getContext());
        if (mKeyKeepOpen) {
            mSel = idx;
            invalidate();
        } else {
            closeList();
            if (!mEditMode) {
                setEditMode(true, false);
            }
        }
        invalidate();
    }

    /**
     * 在屏幕中间创建一个键盘按键并选中它。
     *
     * 创建完立刻选中：新建的键默认在屏幕正中，多半要挪，
     * 选中了才能直接拖 / 调大小 / 关联 / 删除。
     */
    void createKey(int keyPos) {
        if (keyPos < 0 || keyPos >= PadLayout.KEY_USAGES.length) {
            return;
        }
        // 组合键的挑选模式：把 usage 填进正在挑的那一格
        if (mComboPickIdx != COMBO_PICK_NONE) {
            int slot = mComboPickIdx;
            mComboPickIdx = COMBO_PICK_NONE;
            mLayout.setComboAct(mComboEditIdx, mComboEditDir, slot, COMBO_ACT_KEY,
                    PadLayout.KEY_USAGES[keyPos]);
            syncComboAutoName();
            afterComboPick();
            return;
        }
        // 同上：挑选模式只记 usage，不建到当前布局上
        if (mFixPicking) {
            mFixPicking = false;
            PadLayout.fxToggle(getContext(), mFixTpl, "keyadd",
                    PadLayout.KEY_USAGES[keyPos]);
            buildFixRows(mFixTpl);
            openList(LIST_FIX);
            return;
        }
        int idx = mLayout.addKey(PadLayout.KEY_USAGES[keyPos], mW, mH);
        if (idx < 0) {
            toastLocal("键盘按键已达上限（" + PadLayout.MAX_KEYS + "个），先删一个再建");
            closeList();
            invalidate();
            return;
        }
        // 同上：先清再选，顺序反了新建的键就没被选中
        clearSelection();
        selectSingle(idx);
        // 必须重算几何：addKey 写的是**比例** rx/ry，而绘制用的是**像素**
        // mPx/mPy，两者的转换只在 computeGeometry() 里做。
        // 少了这一句，mPx[idx] 还是数组初始值 0，按钮就画在屏幕左上角 (0,0)。
        // （拖动之所以能救回来，是因为拖动路径本身会重算。）
        computeGeometry();
        mLayout.save(getContext());
        if (mKeyKeepOpen) {
            // 连续模式：列表保持打开，滚动位置也留着，
            // 新建的键直接落在这里（默认屏幕中间），之后可以拖开。
            mSel = idx;
            invalidate();
        } else {
            closeList();
            invalidate();
        }
    }

    /**
     * 建一个空白按钮并选中它。
     *
     * 空白按钮 = 只有按钮背景、没有名字、按下去什么都不发。
     * 用途是当占位 / 装饰 / 遮挡块，或者先摆好位置以后再决定放什么。
     *
     * 流程和 createKey 一致：建完 computeGeometry() 把比例换成像素，
     * 否则新元素会画在 (0,0)；然后立刻选中，好直接拖 / 调大小 / 删除。
     */
    void createBlank() {
        // 「固定显示」的挑选模式：不真的建，只把"再建一个空白"记进名单
        if (mFixPicking) {
            mFixPicking = false;
            fixAddOne("bladd", false);
            buildFixRows(mFixTpl);
            openList(LIST_FIX);
            return;
        }
        int idx = mLayout.addBlank();
        if (idx < 0) {
            toastLocal("空白按钮已达上限（" + PadLayout.MAX_BLANK + "个），先删一个再建");
            closeList();
            invalidate();
            return;
        }
        // 同上：先清再选，顺序反了新建的键就没被选中
        clearSelection();
        selectSingle(idx);
        computeGeometry();
        mLayout.save(getContext());
        closeList();
        invalidate();
    }

    /**
     * 建一个组合键并选中它。
     *
     * 【点了就建，不弹任何"先选键"的界面】
     *   以前会先让你挑一个键，理由是"空着没意义"。但那是替用户做决定：
     *   组合键本来就是先摆出来、之后想加什么随时加。
     *   加键走「更多选项 -> 编辑组合键」。
     */
    void createCombo() {
        createCombo(false);
    }

    void createCombo(boolean cross) {
        // 「固定显示」的挑选模式：不真的建，只把"再建一个组合键"记进名单。
        //
        // 【形状是要记的】cross 由上层 LIST_COMBO_KIND 传进来，
        //   借高位（FX_COMBO_CROSS_BASE）塞进同一个 set，建出来就是选的形状。
        //   以前这版直接忽略了它，于是"问了形状却建出普通按钮" —— 假选项。
        if (mFixPicking) {
            mFixPicking = false;
            fixAddOne("cmadd", cross);
            buildFixRows(mFixTpl);
            openList(LIST_FIX);
            return;
        }
        int idx = mLayout.addCombo("组合键");
        if (idx >= 0) {
            mLayout.comboCross[idx] = cross;
            // 斜角默认开：关了只能点四个正方向
            mLayout.comboDiag[idx] = true;
        }
        if (idx < 0) {
            toastLocal("组合键已达上限（" + PadLayout.MAX_COMBO + "个），先删一个再建");
            closeList();
            invalidate();
            return;
        }
        clearSelection();
        selectSingle(idx);
        computeGeometry();
        mLayout.save(getContext());
        closeList();
        invalidate();
    }

    // ---- 组合键编辑列表 ----

    /**
     * 组合键编辑列表一共有几行。
     *
     *   第 0 行        名字
     *   第 1..n 行     每个动作一行（键 或 延迟）
     *   倒数第 2 行    「＋ 添加按键」（满了才不显示）
     *   最后一行       「＋ 添加延迟」（满了才不显示）
     */
    int comboEditRowCount() {
        if (mComboEditIdx == NONE || !mLayout.isComboUsed(mComboEditIdx)) {
            return 0;
        }
        int n = mLayout.comboActCount(mComboEditIdx, mComboEditDir);
        // 编辑十字架某条方向时没有「名字」行 —— 名字在上层列表改
        int rows = (mComboInSub ? 0 : 1) + n;
        // 满了就不再显示添加行，省得点了才发现加不进去
        if (n < PadLayout.MAX_COMBO_ACTS) {
            rows += 2;   // 添加按键 / 添加延迟（「结束」改由每行右侧的按钮插入）
        }
        return rows;
    }

    /** 这一行是不是"某个键"的行（不是名字行、也不是添加行）。 */
    boolean comboRowIsAct(int pos) {
        if (mComboEditIdx == NONE) {
            return false;
        }
        int n = mLayout.comboActCount(mComboEditIdx, mComboEditDir);
        return mComboInSub ? (pos >= 0 && pos < n) : (pos >= 1 && pos < 1 + n);
    }

    /** 组合键编辑列表第 pos 行 -> 动作表里的第几格。不是键行返回 -1。 */
    private int comboRowToAct(int pos) {
        if (mComboInSub) {
            int n = mLayout.comboActCount(mComboEditIdx, mComboEditDir);
            return (pos >= 0 && pos < n) ? pos : -1;
        }
        if (!comboRowIsAct(pos)) {
            return -1;
        }
        int p = 1;
        for (int j = 0; j < PadLayout.MAX_COMBO_ACTS; j++) {
            if (mLayout.comboActType(mComboEditIdx, mComboEditDir, j) == 0) {
                continue;
            }
            if (p == pos) {
                return j;
            }
            p++;
        }
        return -1;
    }

    /** 组合键编辑列表第 pos 行显示什么。 */
    /**
     * 在第 j 格之前，同类型同编码的键出现过几次（含第 j 格自己）。
     *
     * 给「结束A」算 [n] 用：n 表示"结束的是第几个 A"。
     * 只有 1 个就返回 1，调用处不拼后缀。
     */
    private int comboTargetKeyIndex(int j, int baseType, int code) {
        int nth = 0;
        for (int k = 0; k < PadLayout.MAX_COMBO_ACTS; k++) {
            int t = mLayout.comboActType(mComboEditIdx, mComboEditDir, k);
            if (t != baseType) {
                continue;
            }
            if (mLayout.comboActCode(mComboEditIdx, mComboEditDir, k) != code) {
                continue;
            }
            nth++;
            if (k == j) {
                return nth;
            }
        }
        return 1;
    }

    /**
     * 这一行**不要**走通用同名编号。
     *
     * · 「结束A[2]」的编号是"目标是第几个 A"，由 comboRowName 自己拼；
     *   再让通用逻辑加一遍会变成「结束A[2][1]」。
     * · 延迟行（等待 1000ms）压根不编号：它不对应任何按键，
     *   编号只会让人以为是"第几个键"。
     */
    boolean comboRowNoAutoIndex(int pos) {
        if (mListMode != LIST_COMBO_EDIT || mComboEditIdx == NONE) {
            return false;
        }
        int j = comboRowToAct(pos);
        if (j < 0) {
            return false;
        }
        int t = mLayout.comboActType(mComboEditIdx, mComboEditDir, j);
        return t == COMBO_ACT_DELAY || t == COMBO_ACT_RELEASE
                || t == COMBO_ACT_RELEASE_PAD || t == COMBO_ACT_RELEASE_KEY;
    }

    /**
     * 目标键在列表里显示成什么样（含 [n] 和 [手柄]/[键盘]）。
     *
     * 【为什么要照抄主按钮的显示名】
     *   主按钮显示 "A[2][手柄]"，结束行却只有 "结束A" ——
     *   两个 A 的时候根本看不出停的是哪一个。
     *   目标键显示什么，结束行就跟着显示什么，一眼能对上。
     *
     * 【只读 mLayout，不走 listItemName】
     *   listItemName -> comboRowName -> 本方法，再调回去就死循环了。
     */
    private String comboKeyDisplayName(int baseType, int code, int jSelf) {
        //
        // 【只拼 [数字]，不要 [手柄]/[键盘]】
        //   目标键那行自己已经写了 [手柄]，结束行再写一遍是重复信息；
        //   而且"结束"两个字后面拖一串后缀，反而看不出停的是哪个键。
        String base = PadLayout.actShortName(baseType, code);
        //
        // 【序号读存档里存的那个，不在这里数】
        //   结束行自己占一格，按位置数会把自己算进去，永远数成第 1 个 ——
        //   这就是"点 A[2] 的结束，建出来却是 结束A[1]"的原因。
        int nth = mLayout.comboActTag(mComboEditIdx, mComboEditDir, jSelf);
        int total = comboCountSameKey(baseType, code);
        if (nth <= 0) {
            nth = comboTargetKeyIndex(jSelf, baseType, code);  // 老数据兜底
        }
        return base + (total > 1 ? "[" + nth + "]" : "");
    }

    /** 序列里同类型同编码的键一共有几个。 */
    private int comboCountSameKey(int baseType, int code) {
        int n = 0;
        for (int k = 0; k < PadLayout.MAX_COMBO_ACTS; k++) {
            if (mLayout.comboActType(mComboEditIdx, mComboEditDir, k) != baseType) {
                continue;
            }
            if (mLayout.comboActCode(mComboEditIdx, mComboEditDir, k) != code) {
                continue;
            }
            n++;
        }
        return n;
    }

    String comboRowName(int pos) {
        if (mComboEditIdx == NONE || !mLayout.isComboUsed(mComboEditIdx)) {
            return "";
        }
        if (pos == 0 && !mComboInSub) {
            return "名字：" + mLayout.comboName[mComboEditIdx];
        }
        int j = comboRowToAct(pos);
        if (j < 0) {
            // 【两行添加行不能再共用一个文案】
            //   comboRowToAct 对"不是动作行"一律返回 -1，
            //   于是「＋ 添加按键」和「＋ 添加延迟」都是 -1，
            //   第二行跟着显示成"添加按键" —— 点了才发出加的是延迟，
            //   文字和行为对不上。
            //
            //   按位置区分：动作行占 1..n，接下来第一行是按键、第二行是延迟，
            //   和 comboEditClick 里的 wantDelay 用同一套算法，别各写一份。
            int k = comboAddKind(pos);
            if (k == ADD_DELAY) {
                return "＋ 添加延迟";
            }
            return "＋ 添加按键";
        }
        int t = mLayout.comboActType(mComboEditIdx, mComboEditDir, j);
        int code = mLayout.comboActCode(mComboEditIdx, mComboEditDir, j);
        // 摇杆 / 十字键：名字里带上方向，否则看不出推的是哪边
        if (t == COMBO_ACT_STICK_DIR || t == COMBO_ACT_HAT_DIR) {
            return PadLayout.actShortName(t, code,
                    mLayout.comboActTag(mComboEditIdx, mComboEditDir, j));
        }
        if (t == COMBO_ACT_DELAY) {
            return PadLayout.actShortName(t, code) + "[延迟]";
        }
        // 【「结束」写的是它结束的那个键，不是"松开前面的键"】
        //   点 A 那一行的「结束」，造出来的动作就是"结束 A"，
        //   行名要跟着显示成"结束 A"，否则看不出它到底停的是谁。
        if (t == COMBO_ACT_STICK_XY) {
            return PadLayout.actShortName(t, code,
                    mLayout.comboActTag(mComboEditIdx, mComboEditDir, j));
        }
        if (t == COMBO_ACT_RELEASE_STICK || t == COMBO_ACT_RELEASE_HAT) {
            return PadLayout.actShortName(t, code, 0);
        }
        if (t == COMBO_ACT_RELEASE_PAD || t == COMBO_ACT_RELEASE_KEY) {
            //
            // 【[n] 指的是"结束的是第几个同名键"，不是"第几个结束动作"】
            //   序列里有 A[1]、A[2] 时，「结束A[2]」才看得出停的是哪个；
            //   只写「结束A」两个同名键就分不清了。
            return "结束" + comboKeyDisplayName(
                    t == COMBO_ACT_RELEASE_PAD ? 1 : 2, code, j);   // 读 tag
        }
        if (t == COMBO_ACT_RELEASE) {
            return "结束（松开前面的键）";
        }
        return PadLayout.actShortName(t, code)
                + (t == COMBO_ACT_PAD ? "[手柄]" : "[键盘]");
    }

    /**
     * 这一行是不是「＋ 添加延迟」（是的话另一行就是「＋ 添加按键」）。
     *
     * 【行位算法只能有一份】
     *   绘制取行名和点击判断要的是同一个结论，各写一份 `1 + n + 1`
     *   迟早改一边漏一边 —— 上次就是这么把第二行显示成"添加按键"的。
     */
    /**
     * 这一行是三个添加行里的哪一个，不是添加行返回 -1。
     *
     * 【行位算法只有这一份】绘制取行名和点击判断都调它，
     * 各写一份迟早漏改一边（上次就是这么把第二行显示成"添加按键"的）。
     */
    private int comboAddKind(int pos) {
        if (mComboEditIdx == NONE || !mLayout.isComboUsed(mComboEditIdx)) {
            return -1;
        }
        int n = mLayout.comboActCount(mComboEditIdx, mComboEditDir);
        // 【起始行要看有没有「名字」行】
        //   普通组合键：第 0 行是名字，动作行占 1..n。
        //   十字架的某条方向（mComboInSub）：没有名字行，动作行占 0..n-1。
        //
        //   之前这里写死 1 + n，于是子列表里：
        //     真正的第一个添加行（pos == n）算成 -1，
        //     第二个（pos == n+1）算成 ADD_KEY ——
        //   两行都显示「＋ 添加按键」，「＋ 添加延迟」就没了，
        //   而且点第二行加出来的是按键，文字和行为还对不上。
        int base = mComboInSub ? 0 : 1;
        if (pos == base + n) {
            return ADD_KEY;
        }
        if (pos == base + n + 1) {
            return ADD_DELAY;
        }
        return -1;
    }

    /** 组合键编辑列表里点了一行。 */
    /**
     * 退回"组合键编辑列表"。
     *
     * 十字架的主列表是另一个模式（名字 / 四个方向 / 斜角），
     * 而编辑某条方向的序列时又是普通编辑列表 —— 三种情形各回各的地方，
     * 走同一个出口免得每处自己拼、拼错就跳到不相干的列表。
     */
    void openComboEditBack() {
        if (mComboInSub) {
            openListBack(LIST_COMBO_EDIT, LIST_COMBO_CROSS);
            return;
        }
        if (mComboEditIdx != NONE && mLayout != null
                && mLayout.comboCross[mComboEditIdx]) {
            openListBack(LIST_COMBO_CROSS, LIST_MORE);
            return;
        }
        openListBack(LIST_COMBO_EDIT, LIST_MORE);
    }

    /**
     * 十字架主列表第 pos 行的名字。
     *
     *   0      名字
     *   1..4   上 / 下 / 左 / 右（后面带这条序列的概要）
     *   5      斜角开关
     */
    String comboCrossRowName(int pos) {
        if (mComboEditIdx == NONE || !mLayout.isComboUsed(mComboEditIdx)) {
            return "";
        }
        if (pos == 0) {
            return "名字：" + mLayout.comboName[mComboEditIdx];
        }
        if (pos >= 1 && pos <= 4) {
            int d = pos - 1;
            int n = mLayout.comboActCount(mComboEditIdx, d);
            String s = CROSS_DIR_NAMES[d];
            return n > 0 ? s + "（" + mLayout.comboAutoName(mComboEditIdx, d) + "）" : s;
        }
        if (pos == 5) {
            return "启用斜角（" + (mLayout.comboDiag[mComboEditIdx] ? "开" : "关") + "）";
        }
        return "";
    }

    void comboEditClick(int pos) {
        if (mComboEditIdx == NONE || !mLayout.isComboUsed(mComboEditIdx)) {
            return;
        }
        int rows = comboEditRowCount();
        if (pos < 0 || pos >= rows) {
            return;
        }
        if (pos == 0 && !mComboInSub) {
            // 改名要跳到 app 界面（悬浮窗弹不出输入法）
            renameComboNow();
            return;
        }
        if (comboRowIsAct(pos)) {
            // 动作行：键去重选，延迟去改时长
            int j = comboRowToAct(pos);
            if (j < 0) {
                return;
            }
            mComboPickIdx = j;
            int at = mLayout.comboActType(mComboEditIdx, mComboEditDir, j);
            if (at == COMBO_ACT_DELAY) {
                openListBack(LIST_COMBO_DELAY, LIST_COMBO_EDIT);
            } else if (at == COMBO_ACT_RELEASE_PAD
                    || at == COMBO_ACT_RELEASE_KEY
                    || at == COMBO_ACT_RELEASE) {
                // 结束动作没有参数可调，点它只提示一句，别跳到候选表去
                toastLocal("「结束」没有可调的参数：它指定的键已经定好了");
            } else {
                openListBack(LIST_COMBO_TYPE, LIST_COMBO_EDIT);
            }
            return;
        }
        int addKind = comboAddKind(pos);
        // 添加行。先找一格空的，满了就提示。
        //
        // 【不能先建"未设置"的占位行再去挑】
        //   那样取消之后会留下一个空行；而且加键是改一项存一项，
        //   占位行没有意义。找到空格直接进候选表更直接。
        // 【取末尾，不是第一个空格】
        //   插入「结束」会把后面的动作整体后移，中间可能留空洞；
        //   取第一个空格等于把新动作塞进洞里（插到序列中间），
        //   和"新加的排在最后面"不符。统一走 comboFirstFreeSlot。
        int free = comboFirstFreeSlot();
        if (free < 0) {
            toastLocal("一个组合键最多放 " + PadLayout.MAX_COMBO_ACTS + " 个动作");
            invalidate();
            return;
        }
        mComboPickIdx = free;
        if (addKind == ADD_DELAY) {
            // 延迟不用挑键，直接给个默认档位，之后点那一行还能改
            mLayout.setComboAct(mComboEditIdx, mComboEditDir, free, COMBO_ACT_DELAY, 100);
            syncComboAutoName();
            mLayout.save(getContext());
            layoutList();
            invalidate();
        } else {
            openListBack(LIST_COMBO_TYPE, LIST_COMBO_EDIT);
        }
    }

    /**
     * 在组合键里刚填完一格之后往哪走。
     *
     * 【「连续添加」在组合键里也管用】
     *   正常的创建按钮流程里，勾选「连续添加」之后点了候选键，
     *   列表不关，可以接着点下一个 —— 组合键里加键复用的是同一张候选表，
     *   所以这个开关在这里也该生效。
     *
     *   开着：留在候选表，并把 mComboPickIdx 挪到下一格，
     *   于是下一个点到的键会填进新的一格，而不是覆盖刚填的那个。
     *   没空格了才退回编辑列表（并提示）。
     *
     *   关着：填完就退回编辑列表，和以前一样。
     */

    /** 高级摇杆：X(idx 0) / Y(idx 1) 步进，夹在 -100..100。 */
    void stickStep(int idx, int delta) {
        if (idx == 0) {
            mStickX = Math.max(-100, Math.min(100, mStickX + delta));
        } else {
            mStickY = Math.max(-100, Math.min(100, mStickY + delta));
        }
        invalidate();
    }

    /**
     * 高级摇杆列表布局：X / Y 两行带 − / +，下面一大块画预览。
     *
     * 单独排是因为预览要高得多（得画得下一个圆），
     * 通用分支的等高分栏放不下。
     */

    /** 画 X / Y 行（含 − / + 与数值）以及下面的摇杆预览。 */
    void drawStickXyRow(Canvas c, int pos, RectF r) {
        if (pos < 2) {
            c.drawRoundRect(r, dp(8f), dp(8f), mSlTrackPaint);
            mBarTextPaint.setTextAlign(Paint.Align.LEFT);
            mBarTextPaint.setTextSize(dp(15f));
            mBarTextPaint.setColor(0xFFFFFFFF);
            Paint.FontMetrics f = mBarTextPaint.getFontMetrics();
            float ty = r.centerY() - (f.ascent + f.descent) / 2f;
            c.drawText(listItemName(pos), r.left + dp(12f), ty, mBarTextPaint);

            RectF rm = mGridMinusRects[pos];
            RectF rp = mGridPlusRects[pos];
            mBarTextPaint.setTextAlign(Paint.Align.CENTER);
            mBarTextPaint.setTextSize(dp(16f));
            Paint.FontMetrics f2 = mBarTextPaint.getFontMetrics();
            float by = (rm.top + rm.bottom) / 2f - (f2.ascent + f2.descent) / 2f;
            c.drawRoundRect(rm, dp(6f), dp(6f), mToolBtnPaint);
            c.drawRoundRect(rp, dp(6f), dp(6f), mToolBtnPaint);
            mBarTextPaint.setColor(0xFF212121);
            c.drawText("−", rm.centerX(), by, mBarTextPaint);
            c.drawText("+", rp.centerX(), by, mBarTextPaint);
            mBarTextPaint.setTextSize(dp(13f));
            mBarTextPaint.setColor(0xFF1565C0);
            Paint.FontMetrics f3 = mBarTextPaint.getFontMetrics();
            c.drawText(String.valueOf(pos == 0 ? mStickX : mStickY),
                    (rm.right + rp.left) / 2f,
                    (rm.top + rm.bottom) / 2f - (f3.ascent + f3.descent) / 2f,
                    mBarTextPaint);
            return;
        }
        //
        // 预览盘：可以直接点 / 拖来定坐标。
        //
        //   原来只能在上面两行用 −/+ 一格一格挪，调个斜向要点十几次。
        //   现在盘本身就是控件：点哪就推到哪，按住拖着走连续调。
        //   几何用 mStickPadRect / mStickPadR（layoutStickXyList 算好的那份），
        //   和命中判定同一个圆 —— 两处各算一遍的话改了一边就错位。
        float cx = mStickPadRect.centerX();
        float cy = mStickPadRect.centerY();
        float rr = mStickPadR;
        mGridPaint.setStrokeWidth(dp(1f));
        c.drawCircle(cx, cy, rr, mGridPaint);
        c.drawLine(cx - rr, cy, cx + rr, cy, mGridPaint);
        c.drawLine(cx, cy - rr, cx, cy + rr, mGridPaint);
        // 盘内拖出来的点可能落在圆外（角上是 100/100），钳在 ±100 的方框里，
        // 和 −/+ 能达到的范围一致。
        float px = cx + (mStickX / 100f) * rr;
        float py = cy + (mStickY / 100f) * rr;
        c.drawCircle(px, py, dp(8f), mSlThumbPaint);
        c.drawCircle(px, py, dp(5f), mSlFillPaint);
        mBarTextPaint.setTextAlign(Paint.Align.CENTER);
        mBarTextPaint.setTextSize(dp(13f));
        mBarTextPaint.setColor(0xFFDDDDDD);
        Paint.FontMetrics f4 = mBarTextPaint.getFontMetrics();
        c.drawText("X " + mStickX + "%   Y " + mStickY + "%（可点可拖）",
                cx, r.bottom - dp(6f) - (f4.ascent + f4.descent) / 2f, mBarTextPaint);
    }

    /**
     * 扳机力度列表布局：整块就是一条可拖的横条。
     *
     * 【为什么把 −/+ 那一行去掉】
     *   调个 35% 要点七次，太慢。条本身就能点能拖，−/+ 就成了多余。
     *   摇杆那边保留 −/+ 是因为它有两个轴、还要配着预览盘看数值。
     */

    /** 画扳机力度那一条：整块就是一条可点可拖的横条。 */
    void drawTrigRow(Canvas c, int pos, RectF r) {
        if (pos != 0) {
            return;
        }
        RectF bar = mTrigBarRect;
        c.drawRoundRect(bar, bar.height() / 2f, bar.height() / 2f, mSlTrackPaint);
        if (mTrigPct > 0) {
            float fx = bar.left + bar.width() * mTrigPct / 100f;
            c.drawRoundRect(bar.left, bar.top, fx, bar.bottom,
                    bar.height() / 2f, bar.height() / 2f, mSlFillPaint);
        }
        // 圆钮：既是拖把，也让人一眼看出"这里能拖"
        float kx = bar.left + bar.width() * mTrigPct / 100f;
        c.drawCircle(kx, bar.centerY(), bar.height() / 2f + dp(2f), mSlThumbPaint);
        mBarTextPaint.setTextAlign(Paint.Align.CENTER);
        mBarTextPaint.setTextSize(dp(13f));
        mBarTextPaint.setColor(0xFFDDDDDD);
        Paint.FontMetrics f4 = mBarTextPaint.getFontMetrics();
        c.drawText("扣下 " + mTrigPct + "%", r.centerX(),
                r.bottom - dp(4f) - (f4.ascent + f4.descent) / 2f, mBarTextPaint);
    }

    /**
     * 当前要显示 / 要改的那个元素的优先级（面板子模式里那条滑条读它）。
     *
     * 【为什么不记一个"槽位快照"】
     *   面板子模式下 mSel 是会变的（点画布上别的键就换了选中项），
     *   记快照会让滑条一直读旧元素的值、拖了也改到旧元素上 ——
     *   形状 / 摇杆设置用的都是 mSel，跟着当前选中走，这里保持一致。
     *   多选时 mSel 是 NONE，退回看选中集合里的第一个。
     */
    private int curPrio() {
        if (mLayout == null) {
            return PadLayout.PRIO_DEFAULT;
        }
        int i = mSel;
        if (i == NONE) {
            for (int k = 0; k < PadLayout.N; k++) {
                if (mSelSet[k]) {
                    i = k;
                    break;
                }
            }
        }
        if (i == NONE) {
            return PadLayout.PRIO_DEFAULT;
        }
        return mLayout.prio[i];
    }

    /** 拖条出来的值刷给选中的元素，立刻生效并存档。 */
    void applyPrio(int v) {
        if (mLayout == null) {
            return;
        }
        int val = PadLayout.clampPrio(v);
        int one = mSel;
        // 界面按钮（编/布/透/收/G）永远最上层，调它没有意义 —— 说清楚，
        // 别让人拖了半天以为没生效。
        if (one != NONE && PadLayout.isUiButton(one)) {
            toastLocal("界面按钮始终在最上层");
            return;
        }
        boolean changed = false;
        if (isMulti()) {
            for (int i = 0; i < PadLayout.N; i++) {
                if (mSelSet[i] && mLayout.prio[i] != val) {
                    mLayout.prio[i] = val;
                    changed = true;
                }
            }
        } else {
            int i = mSel;
            if (i != NONE && mLayout.prio[i] != val) {
                mLayout.prio[i] = val;
                changed = true;
            }
        }
        if (changed) {
            mLayout.save(getContext());
        }
        invalidate();
    }

    /** 组合键里第一格还没设的动作，没有返回 -1。 */
    /**
     * 下一个动作该放在哪一格 = **末尾**。
     *
     * 【为什么不能取"第一个空格子"】
     *   删过一个动作之后中间会留下空洞，取第一个空格等于把新动作
     *   插到中间去 —— 和用户"新加的东西当然在最后面"的直觉相反。
     *   （删除时已经紧凑化了，这里取末尾双保险。）
     */
    int comboFirstFreeSlot() {
        if (mComboEditIdx == NONE || !mLayout.isComboUsed(mComboEditIdx)) {
            return -1;
        }
        int last = -1;
        for (int j = 0; j < PadLayout.MAX_COMBO_ACTS; j++) {
            if (mLayout.comboActType(mComboEditIdx, mComboEditDir, j) != 0) {
                last = j;
            }
        }
        if (last + 1 >= PadLayout.MAX_COMBO_ACTS) {
            return -1;
        }
        return last + 1;
    }

    /** 把动作表里非空的项往前压，消掉删除留下的空洞。 */
    private void comboCompact() {
        if (mComboEditIdx == NONE || !mLayout.isComboUsed(mComboEditIdx)) {
            return;
        }
        int w = 0;
        for (int j = 0; j < PadLayout.MAX_COMBO_ACTS; j++) {
            int t = mLayout.comboActType(mComboEditIdx, mComboEditDir, j);
            if (t == 0) {
                continue;
            }
            if (w != j) {
                mLayout.setComboAct(mComboEditIdx, mComboEditDir, w, t,
                        mLayout.comboActCode(mComboEditIdx, mComboEditDir, j));
                mLayout.setComboAct(mComboEditIdx, mComboEditDir, j, 0, 0);
            }
            w++;
        }
    }

    /**
     * 画某一行右侧的「结束」「删」。
     *
     * 【为什么单独成函数】
     *   布局、命中、绘制三处都要知道"这两个按钮在哪"，
     *   各写一份迟早对不上。矩形由 layoutList 统一排，这里只管画。
     */
    void drawComboRowButtons(Canvas c, int pos) {
        if (pos < 0 || pos >= mComboDelRects.length) {
            return;
        }
        Paint tp = mBarTextPaint;
        int oldSize = (int) tp.getTextSize();
        int oldColor = tp.getColor();
        Paint.Align oldAlign = tp.getTextAlign();

        RectF re = mComboEndRects[pos];
        if (!re.isEmpty()) {
            // 【不要用开关态】
            //   之前按"这一行后面有没有结束"上色，所有行会一起亮灭，
            //   看着像共用一个开关。「结束」就是个添加按钮，颜色固定。
            mComboDelPaint.setAlpha(255);
            mComboDelPaint.setColor(0xFF757575);
            c.drawRoundRect(re, dp(6f), dp(6f), mComboDelPaint);
            tp.setTextSize(dp(12f));
            tp.setAlpha(255);
            tp.setColor(0xFFFFFFFF);
            tp.setTextAlign(Paint.Align.CENTER);
            Paint.FontMetrics fe = tp.getFontMetrics();
            c.drawText("结束", re.centerX(),
                    re.centerY() - (fe.ascent + fe.descent) / 2f, tp);
        }
        RectF rd = mComboDelRects[pos];
        if (!rd.isEmpty()) {
            mComboDelPaint.setAlpha(255);
            mComboDelPaint.setColor(0xFFE0E0E0);
            c.drawRoundRect(rd, dp(6f), dp(6f), mComboDelPaint);
            tp.setTextSize(dp(13f));
            tp.setAlpha(255);
            tp.setColor(0xFFD32F2F);
            tp.setTextAlign(Paint.Align.CENTER);
            Paint.FontMetrics fDel = tp.getFontMetrics();
            c.drawText("删", rd.centerX(),
                    rd.centerY() - (fDel.ascent + fDel.descent) / 2f, tp);
        }
        tp.setTextSize(oldSize);
        tp.setColor(oldColor);
        tp.setTextAlign(oldAlign);
    }

    /** 这一行是"键"的动作（延迟行返回 false）。 */
    boolean comboRowIsKeyAct(int pos) {
        int j = comboRowToAct(pos);
        if (j < 0) {
            return false;
        }
        int t = mLayout.comboActType(mComboEditIdx, mComboEditDir, j);
        // 摇杆 / 十字键也能停（回中），所以它们的行也要有「结束」按钮。
        // 延迟行不在这批里 —— 等待不是"按着的东西"，停它没意义。
        return t == COMBO_ACT_PAD || t == COMBO_ACT_KEY
                || t == COMBO_ACT_STICK_DIR || t == COMBO_ACT_HAT_DIR
                || t == COMBO_ACT_STICK_XY;
    }



    /**
     * 点「结束」按钮：在序列**末尾**追加 / 移除一个「结束」。
     *
     * 【为什么是末尾，不是点哪行插哪行】
     *   插在某行后面意味着同一个序列里散落着多个"结束"，
     *   而实际用法就是"跑完这一段就松手"，放末尾才对得上。
     *   点哪一行的按钮都一样：往末尾加。
     */
    /**
     * 点「结束」按钮：往**当前列表末尾**追加一个「结束」。
     *
     * 【为什么不是"插在点击的那一行后面"】
     *   「结束」和「添加按键」「添加延迟」是同一种东西 —— 普通动作。
     *   点它就该跟加一个键一样，排在当前最后面。
     *   按行插入会让它变成"这一行的附属属性"，于是：
     *     · 每行的按钮颜色由同一个状态决定 -> 看着像共用开关
     *     · 点了之后它停在点击行后面，而不是最后面
     *   这两条都是错的。它就是个动作，没有"归属行"。
     *
     * 【"当前"末尾的意思】
     *   它不固定在末尾：之后再加键、再加延迟，都排在它后面，它不动。
     */
    void comboToggleEndAfter(int pos) {
        if (mComboEditIdx == NONE || !mLayout.isComboUsed(mComboEditIdx)) {
            return;
        }
        int free = comboFirstFreeSlot();
        if (free < 0) {
            toastLocal("动作已满，加不进「结束」了");
            invalidate();
            return;
        }
        //
        // 【结束的是"你点哪一行"，不是"它前面所有的键"】
        //   点 A 那行的「结束」 -> 造一个"结束 A"（类型 5/6 + A 的编码）。
        //   执行时只松开 A 这一个键，别的键继续按住。
        int j = comboRowToAct(pos);
        int tgtType = 0;
        int tgtCode = 0;
        if (j >= 0) {
            int at = mLayout.comboActType(mComboEditIdx, mComboEditDir, j);
            if (at == 1) {
                tgtType = COMBO_ACT_RELEASE_PAD;
                tgtCode = mLayout.comboActCode(mComboEditIdx, mComboEditDir, j);
            } else if (at == 2) {
                tgtType = COMBO_ACT_RELEASE_KEY;
                tgtCode = mLayout.comboActCode(mComboEditIdx, mComboEditDir, j);
            } else if (at == COMBO_ACT_STICK_DIR) {
                // 摇杆：code 就是原型下标（I_LS / I_RS），不需要序号
                tgtType = COMBO_ACT_RELEASE_STICK;
                tgtCode = mLayout.comboActCode(mComboEditIdx, mComboEditDir, j);
            } else if (at == COMBO_ACT_HAT_DIR) {
                tgtType = COMBO_ACT_RELEASE_HAT;
                tgtCode = mLayout.comboActCode(mComboEditIdx, mComboEditDir, j);
            } else if (at == COMBO_ACT_STICK_XY) {
                // 自定义坐标的摇杆：回中同样是两根轴归 0
                tgtType = COMBO_ACT_RELEASE_STICK;
                tgtCode = mLayout.comboActCode(mComboEditIdx, mComboEditDir, j);
            }
        }
        if (tgtType == 0) {
            // 只有"按键"行才有「结束」按钮，走到这里说明行位对不上
            toastLocal("只能给按键加「结束」");
            invalidate();
            return;
        }
        // 【必须把"第几个"存下来】
        //   两个 A 的编码一样，之后删除/插入会打乱位置，
        //   显示时再按位置数一遍会数错（而且结束行自己占一格也会干扰计数）。
        int nth = comboTargetKeyIndex(j,
                tgtType == COMBO_ACT_RELEASE_PAD ? COMBO_ACT_PAD : COMBO_ACT_KEY,
                tgtCode);
        mLayout.setComboAct(mComboEditIdx, mComboEditDir, free, tgtType, tgtCode, nth);
        // 诊断：把完整顺序打出来，方便确认它是不是落在末尾
        StringBuilder dbg = new StringBuilder();
        for (int k = 0; k < PadLayout.MAX_COMBO_ACTS; k++) {
            int t = mLayout.comboActType(mComboEditIdx, mComboEditDir, k);
            if (t == 0) {
                continue;
            }
            if (dbg.length() > 0) {
                dbg.append(" > ");
            }
            dbg.append(PadLayout.actShortName(t,
                    mLayout.comboActCode(mComboEditIdx, mComboEditDir, k)));
        }
        Log.w("VGpad", "combo end: row=" + pos + " slot=" + free
                + " seq=" + dbg);
        syncComboAutoName();
        mLayout.save(getContext());
        layoutList();
        invalidate();
    }

    /** 删掉组合键里的某一个键。 */
    void comboDelAct(int pos) {
        int j = comboRowToAct(pos);
        if (j < 0) {
            return;
        }
        mLayout.setComboAct(mComboEditIdx, mComboEditDir, j, 0, 0);
        // 【删除后把后面的整体前移，别留空洞】
        //   留洞的话，下一个新加的动作会填进洞里（那是"插到中间"），
        //   和"新加的东西排在最后面"的直觉相反。
        comboCompact();
        syncComboAutoName();
        // 行数变了，矩形要重排，否则最后一行会留着旧坐标变成幽灵按钮
        layoutList();
        invalidate();
    }

    /** 组合键改名：先把"要跳到 app 界面"确认一次。 */
    /**
     * 单个按键改名：和组合键 / 布局改名同一条路 —— 悬浮窗里弹不出输入法，
     * 跳到 app 界面去输，改完由 MainActivity 回写。
     */

    /**
     * 自定义底色：先确认"要跳出去"，再跳。
     *
     * 【选中的键要另存一份】
     *   跳到 app 界面期间悬浮窗会隐藏，回来时 mSelSet 可能已经被
     *   clearSelection 清掉（比如回来顺带做了别的），写色就写空了。
     *   所以按下"确定"的那一刻先把选中集合快照下来。
     */
    /**
     * 把当前选中集合快照进 mColorSel，返回选中个数。
     *
     * 【两条入口都要调】
     *   色板点选、手输 / 网页取色是两条独立的路径，而写色的
     *   applyPickedColor 只读 mColorSel。以前只有后一条路填，
     *   于是点预设色时集合是空的、循环一次都不跑 —— 看着像"没反应"。
     *   （改之前这段是直接读 mSelSet 的，抽函数时才漏掉。）
     */

    void askCustomColor(boolean web) {
        int n = fillColorSel();
        // 穿透那两支不是"选中的键"，写的是全局字段，不依赖选中集合
        if (n == 0 && mColorPassState == 0) {
            toastLocal("先选中要改的键");
            return;
        }
        mColorUseWeb = web;
        mDlgMode = DLG_CONFIRM_KEY_COLOR;
        mDlgTitle = (web ? "网页调色盘" : "自定义颜色")
                + "要跳到本应用界面，游戏会切到后台（可能被系统杀掉），继续？";
        mDlgTargetId = NONE;
        openDlg();
    }

    /**
     * 没设过自定义色时，这个元素**实际显示**的是什么颜色。
     *
     * 界面按钮的底色来自几支专用画笔（不经过 btnColor）：
     *   编 / 布 = 紫，收 = 红，透 = 绿 / 红（跟着开关状态）。
     * 取色器初值要用这个，否则一打开就是 #FFFFFF，和屏幕上看到的完全不符。
     */
    private int defaultColorOf(int i) {
        /*
          【返回"屏幕上真正看到的颜色"，alpha 位必须是 FF】

          这些专用画笔的颜色以前带 0xCC（看着像 80% 透明），
          但 drawRound 里 setA(effAlpha(i)) 会把 alpha **整体覆盖**，
          所以屏幕上其实是不透明的 —— 那个 0xCC 从来没生效过。
          取色器以前拿它当 80% 显示，于是：
            屏幕上 100%，取色器写着 80%，
            拖到 70% 再拖回 80%，反而和原来不一样了。
          现在一律返回不透明，真正的透明度由 effAlpha(i) 给。
        */
        if (i == PadLayout.I_EDIT || i == PadLayout.I_LAYOUT) {
            return 0xFFB39DDB;
        }
        if (i == PadLayout.I_COLLAPSE) {
            return 0xFFEF9A9A;
        }
        if (i == PadLayout.I_FLOAT) {
            return 0xFF336699;
        }
        if (i == PadLayout.I_PASS) {
            /*
              【按"正在改哪一支"给，不按当前开关状态】
                改的是「穿透开时的颜色」，初值就该是开那一支的默认绿；
                跟着当前状态走的话，开 / 关两支的初值永远一样，
                分不清自己在改哪一支。
            */
            boolean on = (mColorPassState == 2) ? false
                    : (mColorPassState == 1 ? true
                    : FloatingService.keyWindowsEnabled(getContext()));
            return on ? 0xFF81C784 : 0xFFE57373;
        }
        return 0xFFFFFFFF;
    }

    void doCustomColor() {
        if (mSink == null) {
            return;
        }
        int rgb = 0xFFFFFF;
        float a = 1f;
        if (mColorPassState == 1) {
            rgb = mLayout.passOnColor != 0 ? (mLayout.passOnColor & 0x00FFFFFF)
                    : (defaultColorOf(PadLayout.I_PASS) & 0x00FFFFFF);
            a = effAlpha(PadLayout.I_PASS);
        } else if (mColorPassState == 2) {
            rgb = mLayout.passOffColor != 0 ? (mLayout.passOffColor & 0x00FFFFFF)
                    : (defaultColorOf(PadLayout.I_PASS) & 0x00FFFFFF);
            a = effAlpha(PadLayout.I_PASS);
        } else {
            for (int i = 0; i < PadLayout.N; i++) {
                if (!mColorSel[i]) {
                    continue;
                }
                // 【没设过色不能一律给白】
                //   界面按钮（编 / 布 / 收 / 透）的底色是几支专用画笔写死的，
                //   不经过 btnColor —— btnColor 是 0（= 没设过）。
                //   一律回退白色的话，打开取色器看到的是 #FFFFFF，
                //   和屏幕上实际显示的紫 / 红 / 绿对不上，改一点就跳成另一个色。
                rgb = mLayout.btnColor[i] != 0 ? (mLayout.btnColor[i] & 0x00FFFFFF)
                        : (defaultColorOf(i) & 0x00FFFFFF);
                // 用 effAlpha 而不是 alpha[i]：前者就是画面上那一个，
                // 夹过下限之后不会出现"取色器里看着还有点、屏幕上已经没了"。
                a = effAlpha(i);
                break;
            }
        }
        /*
          【初值的 alpha 必须取滑条那个，不能取颜色里的】

          透明度只有一个来源（见 applyPickedColor）。颜色存的是不透明色，
          真正的透明度在 alpha[i] 里 —— 这里拼成 8 位 #AARRGGBB 传给取色器，
          它显示的才是"现在屏幕上看到的那个透明度"，往返也不会漂。
        */
        // 【字节 <-> 浮点一律用 256 做分母】
        //   按 255 算的话 75% 得 191(0xBF)，而默认色写的是 0xC0(192) ——
        //   显示数和真实字节差 1。见 alpha256Of() 的说明。
        //   满值要压回 255：一个字节最大就是 0xFF。
        int ab = Math.round(a * 256f);
        if (ab > 255) ab = 255;
        int cur = ((ab & 0xFF) << 24) | (rgb & 0x00FFFFFF);
        mColorInit = cur;
        // 【初值一律给 8 位 #AARRGGBB】
        //   只给 #336699 的话，用户照着输回来得到的是 0xFF336699 ——
        //   完全不透明，和屏幕上看到的那个半透明蓝不是一个色。
        //   带上前两位，手输那条路往返无损。
        //
        //   网页那条路：页面里有自己的透明度滑块，Java 这边把 alpha
        //   拆成 URL 参数给它当滑条初值（见 MainActivity.showColorPickerWeb）。
        String init = String.format("#%08X", cur);
        mSink.requestColorHex(init, mColorUseWeb, mColorSelCount);
    }

    /**
     * app 那边输完十六进制回写。
     *
     * 认这几种写法：#RRGGBB / RRGGBB / #RGB，也认带透明度的 #AARRGGBB。
     * 解析不出来就不动 —— 宁可没反应，也别写个 0 进去把按钮变透明。
     */
    public void setBtnColorHex(String hex) {
        Integer c = parseColorHex(hex);
        if (c == null) {
            return;
        }
        int col = c.intValue();
        // 【网页路径：页面带了透明度滑块，回传是 8 位，直接用】
        //   只有在页面**没给** alpha（回传 6 位）时才用原来的 alpha 补上，
        //   免得"只想换个色，透明度却被一起改掉"。
        if (mColorUseWeb && !hasAlphaDigits(hex)) {
            col = (mColorInit & 0xFF000000) | (col & 0x00FFFFFF);
        }
        applyPickedColor(col);
        invalidate();
    }

    /** 这串色值带不带 alpha（#AARRGGBB 算带，#RRGGBB / #RGB 算不带）。 */
    private static boolean hasAlphaDigits(String hex) {
        if (hex == null) {
            return false;
        }
        String t = hex.trim();
        if (t.startsWith("#")) {
            t = t.substring(1);
        }
        return t.length() >= 8;
    }

    /**
     * 把挑好的颜色写下去。三条路共用：色板点选、手输 #RRGGBB、网页调色盘。
     *
     * @param col 颜色值（含 alpha）。mColorPassState 决定写哪儿。
     */

    /** 解析颜色字符串，失败返回 null。 */
    private static Integer parseColorHex(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        try {
            // Color.parseColor 认 #RGB / #RRGGBB / #AARRGGBB 和 red/blue 等名字，
            // 但不认裸的 RRGGBB（没有 #），先补上再试。
            if (t.charAt(0) != '#') {
                t = "#" + t;
            }
            return Integer.valueOf(android.graphics.Color.parseColor(t));
        } catch (Exception e) {
            return null;
        }
    }

    /** app 那边输完按键名回来，由 Service 转交到这里。 */

    /** 这个元素没改过名时的默认显示名（给改名界面当"当前名"）。 */
    String defaultLabelOf(int i) {
        if (i < 0 || i >= PadLayout.N) {
            return "";
        }
        if (PadLayout.isComboSlot(i) && mLayout.isComboUsed(i)) {
            return mLayout.comboName[i];
        }
        if (mLayout.padType[i] != 0) {
            return padLabel(PadLayout.protoOfType(mLayout.padType[i]));
        }
        if (PadLayout.isKeySlot(i) && mLayout.isKeySlotUsed(i)) {
            return PadLayout.keyName(mLayout.keyCode[i]);
        }
        return "";
    }


    /** app 那边改完名回写进来。 */

    /** 打开某个组合键的编辑列表。 */

    /** 进入十字架某条方向的序列编辑。 */
    void openCrossDir(int dir) {
        mComboEditDir = dir;
        mComboInSub = true;
        mComboPickIdx = COMBO_PICK_NONE;
        mComboAutoPrev = null;   // 方向序列不参与自动名
        openListBack(LIST_COMBO_EDIT, LIST_COMBO_CROSS);
    }

    /**
     * 组合键里加/改一个键之后同步自动名。
     *
     * 【只在"当前名字就是自动算出来的"时才覆盖】
     *   用户自己改过的名字和自动名对不上，就不会被冲掉 ——
     *   不需要额外的"是否手动改过"标记位，存档格式也不用动。
     */

    /**
     * 在悬浮窗画布顶部显示一条提示。
     *
     * 不用 Toast：悬浮窗铺满全屏、层级高于 Toast，用 Toast 会被自己盖住。
     * 见上面「内置提示条」的说明。
     */
    void toastLocal(String msg) {
        if (msg == null || msg.isEmpty()) {
            return;
        }
        mBannerText = msg;
        mBannerUntil = System.currentTimeMillis() + BANNER_MS;
        removeCallbacks(mBannerHide);
        postDelayed(mBannerHide, BANNER_MS + 50);
        invalidate();
    }

    /**
     * 删掉当前选中的键盘按键，槽位回收。
     *
     * 删完要把选中状态清掉：mSel 还指着那个槽位的话，
     * 滑条会去改一个已经不存在的按钮，选中框也会画在屏幕中间。
     */
    /**
     * 打开批量删除列表。
     *
     * 一个键都没创建时**不打开列表**，直接提示：
     * 开一个空列表再让你点确定，是没意义的往返。
     */

    /** 当前已创建的键盘按键个数。 */
    /**
     * 能不能被删。
     *
     * 键盘按键：已创建的就能删。
     * 手柄元素：padType != 0 的就能删（固定区和副本区都一样）。
     * 界面按钮（编 / 收 / 布）：**不能删** —— 删了「编」就再也进不了
     *   编辑模式，删了「收」就收不起悬浮窗，等于把 app 锁死且救不回来。
     */
    boolean isDeletable(int i) {
        if (mLayout == null) {
            return false;
        }
        if (PadLayout.isKeySlot(i)) {
            return mLayout.isKeySlotUsed(i);
        }
        if (PadLayout.isBlankSlot(i)) {
            return mLayout.isBlankUsed(i);
        }
        //
        // 【组合键漏了】
        //   它不是手柄元素（padType 恒 0）、也不是键盘 / 空白槽位，
        //   落到最后那句 isPadUsed 判断必然 false ——
        //   于是批量删除列表里根本列不出它，删不掉。
        if (PadLayout.isComboSlot(i)) {
            return mLayout.isComboUsed(i);
        }
        if (PadLayout.isMouseSlot(i)) {
            return mLayout.isMouseUsed(i);
        }
        return mLayout.isPadUsed(i) && !PadLayout.isUiButton(i);
    }

    /** 可删元素的总数（键盘按键 + 手柄元素），用于"一个都没有就不开列表"。 */
    int deletableCount() {
        int n = 0;
        for (int i = 0; i < PadLayout.N; i++) {
            if (isDeletable(i)) {
                n++;
            }
        }
        return n;
    }

    private int keySlotCount() {
        int n = 0;
        for (int i = PadLayout.I_KEY0; i < PadLayout.N_KEY_END; i++) {
            if (mLayout.isKeySlotUsed(i)) {
                n++;
            }
        }
        return n;
    }


    /**
     * 把当前选中的无轨道版 L2(键) / R2(键) 挪回旧版位置。
     *
     * 旧版 = 还没有滑轨那个年代，L2/R2 就是普通圆按钮、待在肩键排第一格。
     * 现在那一格被带轨道版占了，所以默认摆放把无轨道版挪到了列尾（不重叠）。
     * 但如果你就是想让它在老地方（比如只用无轨道版、轨道版是隐藏的），
     * 用这个一键挪回去 —— 手动拖很难拖准，而且它默认藏着你也不好瞄。
     *
     * 只挪选中的那一个，另一个不动。
     */
    void revertToLegacyPos() {
        if (mSel != PadLayout.I_L2B && mSel != PadLayout.I_R2B) {
            return;
        }
        int idx = (mSel == PadLayout.I_L2B) ? 0 : 1;
        PadLayout def = new PadLayout();
        def.reset(mW, mH, mPortrait, NO_BOTTOM_LIMIT, mTopGuard);
        mLayout.rx[mSel] = def.legacyRx(idx);
        mLayout.ry[mSel] = def.legacyRy(idx);
        computeGeometry();
        clampToScreen(mSel);
        mLayout.rx[mSel] = mPx[mSel] / mW;
        mLayout.ry[mSel] = mPy[mSel] / mH;
        mLayout.save(getContext());
        invalidate();
    }

    /** 「回退旧版位置」这一项现在该不该显示：只在选中无轨道版时出现。 */
    boolean showRevertItem() {
        return mSel == PadLayout.I_L2B || mSel == PadLayout.I_R2B;
    }

    /**
     * 「删除此键」这一项现在该不该显示。
     *
     * 只有选中一个**已创建的**键盘按键时才出现 —— 对固定按钮点"删除"没意义，
     * 它们删不掉（HOME、L2(键) 这些想不用就隐藏，不是删除）。
     */
    /**
     * 「删除此键」对谁可用：键盘按键 或 手柄元素（界面按钮除外）。
     *
     * 界面按钮不给删：删了「编」就再也进不了编辑模式，
     * 删了「收」就收不起悬浮窗 —— 等于把 app 锁死，还没法救回来。
     */

    /** 某一项工具现在该不该占用一行。 */

    /**
     * 悬浮窗布局下还留着的几项。
     *
     * 这个布局的画布上**只有一颗 G**：互换位置要两个键、多选要多个、
     * 隐藏 / 删除 / 批量删除 / 按键创建 / 固定显示 都是对"一批键"的操作，
     * 摆在一颗球面前全是点了也没反应的死项 —— 索性不显示。
     *
     * 留的四项正好是"对单个东西能做的事"：
     *   重置全部（把 G 打回默认）/ 重置单个 / 选中按钮 / 网格吸附（摆位置）
     */












    /** 当前是不是「悬浮窗」布局。 */


    /** 工具菜单实际要画几行。条件显示的两项不一定都在。 */

    /**
     * 某一项归属哪个分组。
     *
     * 筛选到具体分类时全表都算那一组 —— 否则会出现
     * "筛了『位置』却顶着『重置』的标题"这种错乱。
     */

    /** 常用工具列表里要画几条分组标题。 */

    // ------------------------------------------------------------------
    // 按键列表（重置单个 / 互换位置 / 关联挪动 共用）
    // ------------------------------------------------------------------

    /** 打开列表。勾选状态按当前模式初始化，好让你看到（并取消）上一轮的选择。 */
    /**
     * 带"上级"打开一个列表。
     *
     * 【为什么不能写成 openList(mode); mListBack = back;】
     *   openList() 末尾会 layoutList() 排底栏，而那一刻 mListBack
     *   还是它自己刚重置的 NONE —— 于是按"没有上级"排：取消占满整行、
     *   确定矩形清空。之后再赋 mListBack 却没人重排，左边文字变成了
     *   「返回」而宽度还是整行，右边压根没有矩形，「完成」就凭空不见了。
     *   所以赋值之后必须再 layoutList() 一次。
     */


    /** 收集"能列出来的"元素：固定元素全部 + 已创建的键盘按键（空槽位跳过）。 */

    /**
     * 布局列表排版：单列，每行左侧是名字（点它=切过去），
     * 右侧两个小按钮「改名」「删」（只有从常用工具进来才有）。
     */

    /** 当前有搜索词吗（空串 = 不筛选）。 */
    boolean hasQuery() {
        return mListQuery != null && mListQuery.length() > 0;
    }

    /**
     * 显示位置 -> 原始位置。
     *
     * 列表**画的是筛选后的子集**，但底下的数据（mListItems / mFixCode /
     * mLayoutFiltered）用的还是原始下标。所以每次拿到"显示位置"都要先转一次，
     * 不转的话勾第 1 行会去改第 1 个**原始**项 —— 筛选一开就全串位。
     */
    /**
     * 是否走"通用筛选表"（mListFilter）。
     *
     * 布局列表**不走** —— 它本来就有自己的一套过滤（mLayoutFiltered），
     * 再叠一层通用筛选会两套下标打架。它的搜索直接在
     * reloadLayoutMetas() 里按名字过滤 mLayoutFiltered。
     */

    int rawPos(int pos) {
        if (hasQuery() && listUsesGenericFilter()
                && pos >= 0 && pos < mListFilterCount) {
            return mListFilter[pos];
        }
        return pos;
    }

    /**
     * 按搜索词重建筛选表。必须在 layoutList() **之前**跑 ——
     * 行矩形是按 listItemCount() 排的，顺序反了会按旧条数排一遍再筛。
     */

    /** 这个列表支持搜索吗。配置项就那几行的不搜，没意义。 */



    /** 第 pos 项显示的名字。 */
    /**
     * 第 pos 项显示的名字。
     *
     * 【所有按钮列表都会给隐藏项加「{隐藏}」】
     *   隐藏的按钮在非编辑模式下根本不画。列表里不标的话，
     *   比如「关联挪动」勾了一个隐藏键却看不到它动，会以为功能坏了。
     *   「隐藏按钮」这个列表尤其需要 —— 它本身就是显示 hidden 状态的，
     *   不标就只能靠"记住我勾过哪些"。
     */

    /**
     * 这一行的名字在本列表里是第几个同名的（从 1 起）。
     * 全列表只有它一个就返回 0，表示不用加编号。
     *
     * 按**当前显示**的条数遍历：搜索过滤之后编号跟着可见行重排，
     * 否则筛完编号会跳号（显示 [1][3] 这种）。
     */
    int sameNameIndex(int pos, String base) {
        int n = listItemCount();
        if (n <= 1 || base == null || base.isEmpty()) {
            return 0;
        }
        //
        // 【必须统计总数，不能只看"这一项排第几"】
        //   唯一项排第 1，光看 nth>0 就给它也加上 [1] ——
        //   表现为"明明没有重复项，名字后面却挂了个 [1]"。
        //   只有一个的时候压根不该编号。
        int nth = 0;
        int total = 0;
        for (int i = 0; i < n; i++) {
            String s = listItemNameRaw(rawPos(i));
            if (s == null) {
                continue;
            }
            if (s.endsWith("{隐藏}")) {
                s = s.substring(0, s.length() - "{隐藏}".length());
            }
            if (!base.equals(s)) {
                continue;
            }
            total++;
            if (i == pos) {
                nth = total;
            }
        }
        // 全列表只有它一个 -> 返回 0，不加编号
        return (total <= 1) ? 0 : nth;
    }


    /**
     * 按钮在列表里的名字。
     *
     * [键盘] 后缀由 PadLayout.nameOf() 加（区分手柄 A 键和键盘 A 键），
     * 这里**不要**再加一遍，否则会出现 "[键盘][键盘]"。
     * 本方法只补 {隐藏}。
     */

    /**
     * 退回上级列表。
     *
     * 上级的上级要一并恢复：从候选表退回「按键创建」之后，
     * 再点一次得回常用工具，不能凭空断掉。
     */



    /**
     * 右边那颗是不是被当成"关闭"用（而不是"确定"）。
     *
     * 子列表（有上级）里那些"点一项就生效"的（选手柄键、选方向、选种类…）
     * 压根没有勾选语义，走通用「确定」会一直灰着，以前就画成「关闭」。
     *
     * 【但底栏不该同时摆两颗退出】
     *   左边已经是「返回」（退一层），右边再来个「关闭」（整个列表全关），
     *   三个词（返回 / 关闭 / 取消）看着像三种功能，其实只有两种行为。
     *   现在这类子列表右边直接不画，退出统一交给左边那颗。
     */

    /**
     * 右边那颗干脆不画（连「关闭」也不画）。
     *
     * 和 listOkIsClose 的区别：那个是"有上级时把确定当关闭用"，
     * 这个是"压根没有确定语义"—— 点一项就跳转，勾都不用勾。
     *
     * 穿透选支列表就是这类：只有「开 / 关」两项，点一下直接进色板，
     * 摆个「确定」只会让人以为还要再确认一次（实际点它什么也不会发生）。
     */


    /** 列表面板：按键列表按列自适应排，工具列表单列竖排。 */

    /**
     * 列表里点了一项。
     *
     * @param pos 第几项（不是元素下标！LIST_KEY 时是键盘表的下标，
     *             其余模式要经 mListItems 映射）。
     */

    /**
     * 「选中按钮」列表里点了一项：选中它、进编辑模式、关列表。
     *
     * 【为什么要自动取消隐藏】
     *   隐藏的按钮在非编辑模式下根本不画，选中了也看不见、更拖不动。
     *   而这个入口的用途就是"救回点不中的按钮"，
     *   选完还是看不见的话等于白选。所以顺手放出来。
     *
     * 【为什么进编辑模式】
     *   选中是为了调滑条（大小 / 透明 / 字体 / 形状）。
     *   停在非编辑模式的话选中框一闪就没了，看不出选中了谁。
     */

    /** 「确定」能不能点：互换位置必须恰好选满两个。 */
    /**
     * 「确定」能不能点。
     *
     * 隐藏按钮、关联挪动：**允许一个都不选**。
     *   这两个是"按最终勾选状态整体覆盖"的语义，全不选 = 清空，是个正经操作。
     *   之前强制要求至少选一个，结果只有一个按钮被隐藏时，
     *   你想把它显示回来（取消勾选）反而点不了确定 —— 等于有去无回。
     *   关联挪动同理：全不选 = 解除联动。
     *
     * 重置单个：必须有选中才有意义（没选 = 什么都不用做）。
     * 互换位置：必须恰好两个。
     */
    /** 属性列表里勾了几项。 */
    private int resetDimCount() {
        int c = 0;
        for (int k = 0; k < RD_COUNT; k++) {
            if (mResetDims[k]) c++;
        }
        return c;
    }



    /** 命中了列表第几项（位置下标，不是元素下标）。NONE = 没命中。 */

    /**
     * 执行一次横竖屏同步：把**另一个方向**上勾中的按钮、勾中的属性
     * 复制进当前方向。
     *
     * 【点一次同步一次，不是持续生效】
     *   之前做成"设了就一直生效"，转屏时静默搬数据 ——
     *   哪天发现布局被改了，根本想不起是哪次设的。现在改成显式执行。
     *
     * 【源方向 = 另一个方向】
     *   当前竖屏就把横屏那份复制过来，反之亦然。
     *   源方向还没存过（那个方向一次都没编辑过）就提示，不动任何数据。
     */

    /** 点「确定」：按当前模式执行，然后关掉列表。 */
    // 模板选择由 confirmList 收尾，见下面的分支。


    /** 布局列表里的小按钮命中：矩形是内容坐标，要先卡可视区再换算。 */

    /**
     * 列表里"可直接拖的控件"的命中判定。返回 ADJ_NONE / ADJ_TRIG / ADJ_STICK。
     *
     * 【为什么要有一份独立判定】
     *   这两个控件不是列表行，点它们不该走"选中 / 点击行"那套，
     *   也不该被当成"拖列表滚动"。所以按下时先问一次，命中就交给
     *   applyListAdjust 全程接管这次手势。
     *
     * 坐标换算和 hitListSub 同一套：屏幕 y 减掉 viewRect.top 再加回 scroll。
     */

    /** 把手指位置换算成控件的值（点了就跳过去，拖着就连续变）。 */



    // ------------------------------------------------------------------
    // 多布局：切换 / 改名 / 删除 / 新建
    // ------------------------------------------------------------------

    /**
     * 按当前分类重算要显示的行。
     *
     * 分类只按模板类型（手柄 / 键盘）分。
     * 空白模板的布局不属于任何一类，只在「全部」里出现 ——
     * 它既不是手柄也不是键盘，硬塞进哪边都会让人找不着。
     */

    /** 当前列表要不要显示分类筛选。 */

    /** 是不是"按模板分"的布局列表（和"按按钮分"的列表区分开）。 */

    /**
     * 分类项数：常用工具 5 项，布局列表 4 项（按模板），按钮列表 6 项。
     *
     * 【不能一律返回 CAT_MAX】
     *   布局列表只有 4 项，返回 6 会画出两个空行 —— 点下去把 mListCat
     *   设成 4 / 5，而 metaMatchesCat 的 default 返回 true，
     *   看着像"选了组合但没变化"。
     */

    /** 第 i 个分类的名字：布局列表第 4 项是「空白」，按钮列表是「功能」。 */

    /**
     * 是否显示「全选」。
     *
     * 只给"多选且不限个数"的列表：重置单个 / 关联挪动 / 隐藏 / 批量删除。
     * 互换位置虽然也是多选，但**只能选两个**，全选没有意义 ——
     * 只会让人困惑（选了一堆却只认前两个）。
     */

    /** 按钮（元素）是否属于当前分类。 */

    /** 布局（模板）是否属于当前分类。 */

    /** 重新载入布局清单（改名 / 删除 / 新建之后都要调）。 */

    /** 切到某个布局：先存当前，再加载目标，然后重算几何。 */

    /** 改名：交给 app 界面去做（悬浮窗里弹不出输入法）。先确认，见下面说明。 */

    /** 确认之后真正跳过去改名。 */

    /** 导入：也要离开游戏去选文件，同样先确认。 */


    /** 搜索：也要跳到 app 界面用 EditText 输入，先确认一次。 */


    /**
     * app 那边输完搜索词回来，由 Service 转交到这里。
     *
     * 【必须重新 layoutList()】
     *   条目数变了，行矩形和滚动范围都得按新条数排，
     *   只 invalidate 的话底下几行的坐标还是旧的，点不到也画不出。
     */

    /**
     * 分享（导出）：把这套布局的竖屏 + 横屏两份摆位存成一个文件。
     *
     * 存到 Download/com.example.vgamepad/ 下，文件名是
     * 「布局名_年_月_日_时_分_秒.json」—— 时间戳保证同名布局导出多次不会互相覆盖。
     *
     * 这个操作不离开游戏（直接写文件），所以不需要那个"切后台"的确认。
     */

    /** app 界面改完名后回调这里：刷新列表并重画。 */

    /** 删除：先确认再删，防止手滑。 */

    /** 打开内置对话框：算好矩形、唤起软键盘（仅改名）。 */
    /** 对话框「确定」按钮的文案：删除类写「删除」，重置类写「重置」。 */


    /**
     * 打开横竖屏同步的第一步：勾要同步哪些按钮。
     *
     * 【走了列表而不是对话框】
     *   之前做成"两个互斥勾选项 + 持续生效"，问题是：
     *   一设就一直生效，转屏时静默搬数据，出问题时很难判断是谁改的。
     *   现在改成点一次同步一次，而且能挑按钮、挑属性，粒度更细。
     */



    /**
     * 画顶部提示条。
     *
     * 画在**所有东西之后**（列表、对话框都画完了才轮到它），
     * 否则"没有可删除的按钮"这类提示会被列表挡住 —— 和用 Toast 被悬浮窗
     * 盖住是同一个问题，只是换了个遮挡物。
     */



    /**
     * 对话框里的「确定」。
     *
     * 现在有五处用：删除布局 / 重置全部 / 重置单个 / 删除此键 / 批量删除。
     * 全是**不可逆**的操作 —— 重置会把手动摆的位置全清掉，
     * 删除会把创建的键抹掉，都做不得"手滑了再撤销"，所以都要先确认。
     */

    /** 按选中的模板新建一个布局并切过去。 */

    /**
     * 列表右上角的分类筛选。
     *
     * 收起时写「当前分类 v」，点一下**原地**变成「当前分类 ^」，
     * 并在按钮下方列出所有分类；再点那个「^」只是收起，不选中任何项。
     * 展开时点列表里某项 = 选中并收起；点别处 = 收起。
     */

    /** 左上角「全选 / 取消」：只对多选且不限个数的列表显示。 */

    /**
     * 换了分类：重建列表项、把滚动拉回顶部、清空已勾选项。
     *
     * 要清勾选：上一分类里勾的东西在下一分类里可能根本不显示，
     * 留着的话点「确定」会作用到看不见的按钮上。
     */

    /**
     * 「全选」按钮：当前分类里没全勾上就全勾，已经全勾了就全部取消。
     *
     * 做成切换而不是单向全选 —— 否则勾满之后想反选，
     * 只能一个个点掉，人手点的成本比点一次全选高得多。
     */

    /**
     * 全选按钮的文案：已经全勾上就写「取消」，否则写「全选」。
     * 不跟着变的话，用户点下去没反应（看着像坏了）。
     */


    /**
     * 把某个按钮恢复成默认位置。
     *
     * 只重置位置，不动大小和透明度 —— 这个功能的用途是
     * "不小心把按钮拖到屏幕外、又抓不回来"，大小是你调好的，不该被连坐。
     */
    void resetOne(int i) {
        boolean[] all = new boolean[RD_COUNT];
        for (int k = 0; k < RD_COUNT; k++) {
            all[k] = true;
        }
        resetOne(i, all);
    }

    /**
     * 按勾选的属性重置一个按钮。
     *
     * 【基准布局 def 必须按当前模板建】
     *   以前 def 一律建手柄模板（reset 没传模板），于是：
     *   - 键盘布局里重置键位，def 里那个槽位是空的、坐标 0
     *     -> 只能退回"屏幕中间"，键从键盘格里跳到屏幕中央
     *   - 大小 / 透明 / 字体也一样，键盘键的矩形倍率被重置成圆键的
     *   现在按 mLayout.tplOf() 建，键盘布局就拿到真正的格子坐标。
     *
     * 位置兜底：def 里这个槽位确实是空的（比如手柄模板上自己加的键盘键），
     * 才沿用"屏幕中间"这个约定，和 addKey() 一致。
     */
    void resetOne(int i, boolean[] dims) {
        // 【系统自带的元素不按模板默认值重置】
        //   提示牌是布局自己摆上去的，模板里根本没有它 —— 拿"空白键的默认值"
        //   （圆形 + 倍率 1）覆盖，牌子就变成正圆、文字挤成一团。
        //   G 同理。它们各自有"布局给它定的样子"，见 PadLayout.resetSysOwned。
        if (PadLayout.resetSysOwned(mLayout, i, dims)) {
            computeGeometry();
            mLayout.save(getContext());
            return;
        }
        PadLayout def = new PadLayout();
        // 【def 必须带 ctx】
        //   不带的话「固定显示」里存的初始状态覆盖（shw / hid / del / add）
        //   套不上去，def 里的「透」就是模板默认的 hidden=true。
        //   于是"明明设了穿透默认显示，重置单个之后它又藏起来" ——
        //   重置的语义是"回到初始状态"，而初始状态就是被固定显示改过的那个。
        def.reset(mW, mH, mPortrait, NO_BOTTOM_LIMIT, mTopGuard,
                mLayout.tplOf(getContext()), getContext());
        if (dims[RD_POS]) {
            // def.keyCode[i] != 0 = 模板里本来就有这个键，位置就照模板给
            if (PadLayout.isKeySlot(i) && def.keyCode[i] == 0) {
                mLayout.rx[i] = 0.5f;
                mLayout.ry[i] = 0.45f;
            } else {
                mLayout.rx[i] = def.rx[i];
                mLayout.ry[i] = def.ry[i];
            }
        }
        if (dims[RD_SIZE]) {
            mLayout.scale[i] = def.scale[i];
        }
        if (dims[RD_ALPHA]) {
            mLayout.alpha[i] = def.alpha[i];
        }
        if (dims[RD_TEXT]) {
            mLayout.textScale[i] = def.textScale[i];
        }
        if (dims[RD_OTHER]) {
            mLayout.shape[i] = def.shape[i];
            // 【显隐照常从 def 拷】
            //   def 现在带 ctx，固定显示的 shw / hid 已经套上，
            //   「透」这类默认隐藏但被设成"默认显示"的按钮才能被放出来。
            //   上面那些只由 layoutId 决定的（G / 收 / 布 / 透 在悬浮窗布局里
            //   的特殊安排）交给末尾的 applyFloatVisibility 归一 ——
            //   def 的 layoutId 不是悬浮窗，它对这几个的判断不作数。
            mLayout.hidden[i] = def.hidden[i];
            mLayout.stickFixed[i] = def.stickFixed[i];
            mLayout.stickRange[i] = def.stickRange[i];
            mLayout.widthMul[i] = def.widthMul[i];
            mLayout.heightMul[i] = def.heightMul[i];
        }
        // 【收尾再归一一次界面按钮的显隐】
        //   def 的 layoutId 不是当前布局，它对 G / 收 / 布 / 透 的安排不作数，
        //   这里按当前 layoutId 定死。
        PadLayout.applyFloatVisibility(mLayout);
        computeGeometry();
        mLayout.save(getContext());
    }

    /** 两个按钮整体对调：位置、大小、透明度全换，等于把它们俩互换身份。 */
    void swapElements(int a, int b) {
        float tx = mLayout.rx[a];
        float ty = mLayout.ry[a];
        float ts = mLayout.scale[a];
        float ta = mLayout.alpha[a];
        mLayout.rx[a] = mLayout.rx[b];
        mLayout.ry[a] = mLayout.ry[b];
        mLayout.scale[a] = mLayout.scale[b];
        mLayout.alpha[a] = mLayout.alpha[b];
        mLayout.rx[b] = tx;
        mLayout.ry[b] = ty;
        mLayout.scale[b] = ts;
        mLayout.alpha[b] = ta;
        computeGeometry();
        mLayout.save(getContext());
    }

    /** 切换面板三档。收起时顺手关掉列表，免得面板收着列表还浮在上面。 */
    void setPanelState(int state) {
        if (mPanelState == state) {
            return;
        }
        mPanelState = state;
        mSlDrag = NONE;
        mSlDragPointer = NONE;
        if (state != PANEL_FULL) {
            closeList();
        }
        computePanelMetrics();
        layoutTools();
        invalidate();
    }

    private void saveAndDraw() {
        if (mSel == NONE) {
            return;
        }
        clampToScreen(mSel);
        mLayout.rx[mSel] = mPx[mSel] / mW;
        mLayout.ry[mSel] = mPy[mSel] / mH;
        mLayout.save(getContext());
        invalidate();
    }

    // ------------------------------------------------------------------
    // 游戏输入
    // ------------------------------------------------------------------

    /**
     * 浮动摇杆瞬移前的原位（屏幕坐标），抬手时靠它把摇杆放回去。
     *
     * 只在真的瞬移过（mFloatMoved）才用 —— 没瞬移就恢复，
     * 等于把摇杆挪到 (0,0) 附近的旧值上。
     */
    private final float[] mFloatHomeX = new float[PadLayout.N];
    private final float[] mFloatHomeY = new float[PadLayout.N];
    /** 这个摇杆当前是不是处于"已瞬移、还没抬手"的状态。 */
    private final boolean[] mFloatMoved = new boolean[PadLayout.N];

    /**
     * 浮动摇杆的"瞬移"。只在关掉固定摇杆时生效。
     *
     * 判定：按下点落在以摇杆中心为圆心、半径 = 摇杆半径 × stickRange 的圈内。
     * 圈内 -> 把摇杆挪到手指位置并存盘；圈外 -> 不动（这个手指就不该激活它）。
     *
     * 【只有摇杆有这个行为】
     *   十字键、ABXY 那些本来就是"按哪儿都行"，瞬移没有意义。
     *   副本槽位上的摇杆也算（按 proto 判，不是按下标）。
     */
    /**
     * 找"手指落在哪个浮动摇杆的范围圈里"。
     *
     * 【为什么必须有这个】
     *   光有 applyFloatingStick 不够：它只在 onDown(elem) 里被调用，
     *   而 elem 来自 hitElement() —— 那个只认摇杆**本身的几何**。
     *   于是手指按在摇杆外面（但在范围圈内）时 hitElement 返回 NONE，
     *   根本走不到瞬移逻辑。表现就是：预览圈画得老大，实际只有摇杆
     *   那一小块按得动，圈是"骗人"的。
     *
     * 多个圈重叠时取"最贴合"的那个（比值最小），避免就近的反而选到远的。
     */
    /** 这个元素是不是摇杆（含副本槽位上的摇杆 —— 按 proto 判，不是按下标）。 */
    boolean isStickElem(int i) {
        if (mLayout == null || i < 0 || i >= PadLayout.N) {
            return false;
        }
        int proto = (mLayout.padType[i] != 0)
                ? PadLayout.protoOfType(mLayout.padType[i]) : i;
        return proto == PadLayout.I_LS || proto == PadLayout.I_RS;
    }

    /** 这个元素是不是"关掉了固定"的摇杆（含副本槽位，按 proto 判）。 */
    private boolean isFloatStick(int i) {
        return isStickElem(i) && !mLayout.stickFixed[i];
    }

    /**
     * 选中集合里有没有摇杆。
     *
     * 决定「更多选项」里要不要给「摇杆设置」这一行 ——
     * 多选时只要这批里混着摇杆就该给（只对摇杆生效，别的键跳过）。
     */

    /**
     * 选中集合里的第一个摇杆。
     *
     * 滑条取值 / 开关状态都以它为基准：多选时大家状态可能不一致，
     * 总得有个"读数从哪来"的口径，取第一个最直观（就是选中的第一个）。
     */

    /**
     * 对选中集合里的**所有摇杆**执行同一件事。
     *
     * @return 实际改了几个
     */

    /** forEachSelStick 的动作。 */

    private int hitFloatStick(float x, float y) {
        if (mEditMode || mLayout == null) {
            return NONE;
        }
        int best = NONE;
        float bestK = Float.MAX_VALUE;
        for (int i = 0; i < PadLayout.N; i++) {
            // 空槽位不用单独跳：padType==0 时 proto 就是下标 i，
            // 不可能等于 I_LS / I_RS，下面那句自然就过滤掉了。
            if (mLayout.hidden[i]) {
                continue;
            }
            if (!isFloatStick(i)) {
                continue;
            }
            float r = radiusOf(i) * mLayout.stickRange[i];
            if (r <= 0f) {
                continue;
            }
            float d = dist(x, y, mPx[i], mPy[i]);
            if (d > r) {
                continue;
            }
            float k = d / r;
            if (k < bestK) {
                bestK = k;
                best = i;
            }
        }
        return best;
    }

    private void applyFloatingStick(int elem, float x, float y) {
        if (mEditMode || mLayout == null || elem < 0 || elem >= PadLayout.N) {
            return;
        }
        int proto = (mLayout.padType[elem] != 0)
                ? PadLayout.protoOfType(mLayout.padType[elem]) : elem;
        if (proto != PadLayout.I_LS && proto != PadLayout.I_RS) {
            return;                       // 不是摇杆
        }
        if (mLayout.stickFixed[elem]) {
            return;                       // 固定摇杆：保持原样
        }
        float cx = mPx[elem];
        float cy = mPy[elem];
        float r = radiusOf(elem) * mLayout.stickRange[elem];
        if (dist(x, y, cx, cy) > r) {
            return;                       // 圈外按下：不激活
        }
        //
        // 【只是临时的，不存盘】
        //   瞬移只改屏幕坐标 mPx / mPy，不动 mLayout.rx / ry，
        //   也不调 save()。抬手时（onUp）靠 mFloatHomeX / Y 放回原位。
        //
        //   存盘的话摇杆会被永久挪走 —— 浮动摇杆要的是"随按随到、
        //   松手归位"，位置仍然是用户在编辑器里摆的那个。
        //
        //   同理也不用通知穿透窗口：手指已经按下去了，
        //   窗口位置影响的是**下一次**触摸，而那时摇杆已经回位了。
        mFloatHomeX[elem] = cx;
        mFloatHomeY[elem] = cy;
        mFloatMoved[elem] = true;
        mPx[elem] = x;
        mPy[elem] = y;
    }

    /**
     * 组合键按下：把里面每个键都发出去。
     *
     * 【逐个发，不做任何"空格子"检查】
     *   动作表里 type 为 0 的行会被 continue 跳过，一个键都没设时
     *   循环一次都不进 —— 按下自然什么都不发，不需要额外判断。
     *
     * 【守护进程侧会累积到同一份报告再一次性推出】
     *   所以对手柄来说这些键是"同一时刻一起按下"的。
     */
    // ---- 组合键的延迟序列 ----
    //
    //   组合键不再只是"同时按住好几个键"—— 中间可以插「等待 xx ms」，
    //   所以按下时要把后面的动作**延后发出**，而不是一股脑全推出去。
    //   实现上用一个主线程 Handler 排延迟，松开时把还没发的取消掉。

    /** 排延迟用的。主线程 Looper —— onButton/onKey 都要在主线程调。 */
    private final Handler mComboH = new Handler(Looper.getMainLooper());
    /**
     * 播放轨道。斜角要**同时**跑两条序列（左 + 上），
     * 所以留两条互不干扰的轨道：各有自己的待发列表、已发列表和代号。
     *
     * 普通组合键 / 十字架的正方向只用第 0 条。
     */
    private static final int COMBO_LANES = 2;

    private static java.util.ArrayList<java.util.ArrayList<Runnable>> newPendLanes() {
        java.util.ArrayList<java.util.ArrayList<Runnable>> l =
                new java.util.ArrayList<java.util.ArrayList<Runnable>>();
        for (int i = 0; i < COMBO_LANES; i++) {
            l.add(new java.util.ArrayList<Runnable>());
        }
        return l;
    }

    private static java.util.ArrayList<java.util.ArrayList<int[]>> newSentLanes() {
        java.util.ArrayList<java.util.ArrayList<int[]>> l =
                new java.util.ArrayList<java.util.ArrayList<int[]>>();
        for (int i = 0; i < COMBO_LANES; i++) {
            l.add(new java.util.ArrayList<int[]>());
        }
        return l;
    }

    /** 还没发出的动作（延迟中的），按轨道分开。 */
    private final java.util.ArrayList<java.util.ArrayList<Runnable>> mComboPend =
            newPendLanes();
    /**
     * 已经发出去的动作，松开时按它反向发一遍松开。按轨道分开。
     *
     * 不含还没发的：延迟中被松手的话，那些键压根没按下过，
     * 再发一次"松开"反而会让守护进程那边状态错乱。
     *
     * 每项 5 个 int：类型 / 编码 / 槽位 / 方向 / 格位。
     */
    private final java.util.ArrayList<java.util.ArrayList<int[]>> mComboSent =
            newSentLanes();
    /**
     * 序列代号，按轨道分开。每次按下/松开都 +1。
     *
     * 光 removeCallbacks 不够：已经 post 出去、正排队的 Runnable
     * 拿不到引用就取消不掉。代号一变，它们执行时自检发现代号不对就自行退出。
     */
    private final int[] mComboToken = new int[COMBO_LANES];
    /** 序列播放期间有被推迟的穿透同步，播完要补。 */
    private boolean mComboDeferNotify = false;

    /**
     * 发一个动作（按下 / 松开）。
     *
     * @param dir 方向号：摇杆坐标 / 十字键方向 / 结束序号存在 tag 里，
     *            而 tag 是"某个方向的第几格"，必须带进来才读得对。
     */
    private void sendComboAct(int type, int code, boolean pressed,
            int slot, int dir, int j) {
        if (type == COMBO_ACT_PAD) {
            int proto = PadLayout.protoOfType(code);
            //
            // 【老数据防御】以前摇杆 / 十字键也被存成类型 1，
            //   那时 buttonIndexOfProto 落 default 发出 BTN_START ——
            //   选左摇杆结果发出 START 键。这里直接跳过，不再误发。
            if (proto == PadLayout.I_LS || proto == PadLayout.I_RS
                    || proto == PadLayout.I_DPAD) {
                return;
            }
            mSink.onButton(buttonIndexOfProto(proto), pressed);
            if (proto == PadLayout.I_L2 || proto == PadLayout.I_R2) {
                // 力度来自 tag：老存档没设过是 0，当满值处理，行为不变
                int pct = (slot >= 0 && j >= 0)
                        ? mLayout.comboActTag(slot, dir, j) : 0;
                float tv = (pct <= 0 ? 100 : pct) / 100f;
                mSink.onTrigger(triggerIndexOf(proto), pressed ? tv : 0f);
            }
        } else if (type == COMBO_ACT_KEY) {
            mSink.onKey(code, pressed);
        } else if (type == COMBO_ACT_STICK_DIR) {
            int d = (slot >= 0 && j >= 0)
                    ? mLayout.comboActTag(slot, dir, j) : 0;
            if (d < 0) d = 0;
            if (d > 7) d = 7;
            int base = (code == PadLayout.I_RS) ? 2 : 0;
            mSink.onAxis(base, pressed ? PadLayout.DIR_X[d] : 0f);
            mSink.onAxis(base + 1, pressed ? PadLayout.DIR_Y[d] : 0f);
        } else if (type == COMBO_ACT_STICK_XY) {
            int tg = (slot >= 0 && j >= 0)
                    ? mLayout.comboActTag(slot, dir, j) : 0;
            int base = (code == PadLayout.I_RS) ? 2 : 0;
            mSink.onAxis(base, pressed ? PadLayout.stickXOf(tg) / 100f : 0f);
            mSink.onAxis(base + 1, pressed ? PadLayout.stickYOf(tg) / 100f : 0f);
        } else if (type == COMBO_ACT_RELEASE_STICK) {
            int base = (code == PadLayout.I_RS) ? 2 : 0;
            mSink.onAxis(base, 0f);
            mSink.onAxis(base + 1, 0f);
        } else if (type == COMBO_ACT_RELEASE_HAT) {
            setHat(8);
        } else if (type == COMBO_ACT_HAT_DIR) {
            int d = (slot >= 0 && j >= 0)
                    ? mLayout.comboActTag(slot, dir, j) : 0;
            if (d < 0) d = 0;
            if (d > 7) d = 7;
            setHat(pressed ? d : 8);
        }
    }

    /** 取消还没发出的动作，并把已发出的全部松开（所有轨道）。 */
    private void comboCancelAll() {
        for (int L = 0; L < COMBO_LANES; L++) {
            comboCancelLane(L);
        }
    }

    private void comboCancelLane(int lane) {
        mComboToken[lane]++;
        // 硬取消也要把推迟的同步补上，否则区域会停在序列开始前的状态
        mComboDeferNotify = false;
        java.util.ArrayList<Runnable> pend = mComboPend.get(lane);
        for (int k = 0; k < pend.size(); k++) {
            mComboH.removeCallbacks(pend.get(k));
        }
        pend.clear();
        java.util.ArrayList<int[]> sent = mComboSent.get(lane);
        for (int k = 0; k < sent.size(); k++) {
            int[] a = sent.get(k);
            sendComboAct(a[0], a[1], false,
                    a.length > 2 ? a[2] : NONE,
                    a.length > 3 ? a[3] : 0,
                    a.length > 4 ? a[4] : -1);
        }
        sent.clear();
    }

    /**
     * 序列播完后，键至少保持这么久再松开。
     *
     * 最后发出来的那个键如果"按下即松开"，游戏那一帧可能压根没采样到，
     * 表现为"序列里最后几个键没生效"。留 60ms 让它一定能被看见。
     */
    private static final long COMBO_HOLD_MS = 60L;

    /** 组合键按下：普通组合键，播第 0 条方向、第 0 条轨道。 */
    private void fireCombo(int i) {
        fireCombo(i, 0, 0);
    }

    /**
     * 组合键按下：按顺序发，遇到延迟就把后面的往后推。
     *
     * @param dir  播哪条方向的序列（十字架用；普通组合键恒 0）
     * @param lane 轨道号（斜角同时跑两条：比如左用 0、上用 1）
     */
    private void fireCombo(int i, int dir, int lane) {
        mDown[i] = 1;
        // 上一次的序列可能还在跑（快速连点），先收干净再排新的
        comboCancelLane(lane);
        final int slot = i;
        final int fdir = dir;
        final int flane = lane;
        final int token = mComboToken[lane];
        long acc = 0;
        boolean hasDelay = false;
        for (int j = 0; j < PadLayout.MAX_COMBO_ACTS; j++) {
            int t = mLayout.comboActType(i, dir, j);
            if (t == 0) {
                continue;
            }
            int code = mLayout.comboActCode(i, dir, j);
            if (t == COMBO_ACT_DELAY) {
                acc += code;
                hasDelay = true;
                continue;
            }
            //
            // 【结束动作：松开"指定的那一个键"，不是"前面所有键"】
            //   类型 5/6 带着目标键的编码，执行时只松那一个，
            //   序列里其它键继续按住。类型 4 是老数据，退回"全松"。
            if (t == COMBO_ACT_RELEASE_PAD || t == COMBO_ACT_RELEASE_KEY
                    || t == COMBO_ACT_RELEASE) {
                final int rt = t;
                final int rc = code;
                final long rd = acc;
                final int jj = j;
                Runnable rel = new Runnable() {
                    public void run() {
                        if (token != mComboToken[flane]) {
                            return;
                        }
                        if (rt == COMBO_ACT_RELEASE) {
                            comboReleaseSent(flane);
                        } else {
                            comboReleaseOne(flane,
                                    rt == COMBO_ACT_RELEASE_PAD ? 1 : 2,
                                    rc, mLayout.comboActTag(slot, fdir, jj));
                        }
                    }
                };
                if (rd <= 0) {
                    rel.run();
                } else {
                    mComboPend.get(flane).add(rel);
                    mComboH.postDelayed(rel, rd);
                }
                continue;
            }
            final int ft = t;
            final int fc = code;
            final long d = acc;
            final int jj = j;
            Runnable r = new Runnable() {
                public void run() {
                    // 代号变了说明中途松手（或又按了一次），这一发作废
                    if (token != mComboToken[flane]) {
                        return;
                    }
                    sendComboAct(ft, fc, true, slot, fdir, jj);
                    mComboSent.get(flane).add(new int[]{ft, fc, slot, fdir, jj});
                }
            };
            if (d <= 0) {
                // 【立刻发的不能进待发列表】
                //   留在里面的话，松手时 mComboPend 非空会被当成
                //   "序列还没播完"，于是已按下的键迟迟不松开。
                r.run();
            } else {
                mComboPend.get(flane).add(r);
                mComboH.postDelayed(r, d);
            }
        }
        //
        // 【为什么要有这个收尾】
        //   以前是"松手即中断"：按下 A → 延迟 100ms → B 还没来得及发，
        //   手指一抬 releaseCombo 就把待发的全取消了 ——
        //   于是"延迟后面的所有键都不触发"。
        //   现在按下启动序列、序列自己跑完，点一下就松手也能完整播完。
        if (hasDelay) {
            final long tailAt = acc + COMBO_HOLD_MS;
            Runnable tail = new Runnable() {
                public void run() {
                    if (token != mComboToken[flane]) {
                        return;
                    }
                    // 序列播完了：清掉待发，之后 releaseCombo 才能立即松开
                    mComboPend.get(flane).clear();
                    if (mDown[slot] == 1) {
                        // 手指还按着：保持，等松手再松开
                        return;
                    }
                    comboReleaseSent(flane);
                    flushComboDeferNotify();
                    invalidate();
                }
            };
            mComboPend.get(flane).add(tail);
            mComboH.postDelayed(tail, tailAt);
        }
        invalidate();
    }

    /** 把某条轨道上已发出去的动作全部松开，并清空记录。 */
    private void comboReleaseSent(int lane) {
        java.util.ArrayList<int[]> sent = mComboSent.get(lane);
        for (int k = 0; k < sent.size(); k++) {
            int[] a = sent.get(k);
            sendComboAct(a[0], a[1], false,
                    a.length > 2 ? a[2] : NONE,
                    a.length > 3 ? a[3] : 0,
                    a.length > 4 ? a[4] : -1);
        }
        sent.clear();
    }

    /** 只松开指定的那一个键，其它还按着的键不动。 */
    private void comboReleaseOne(int lane, int tType, int tCode, int nth) {
        java.util.ArrayList<int[]> sent = mComboSent.get(lane);
        int seen = 0;
        for (int k = 0; k < sent.size(); k++) {
            int[] a = sent.get(k);
            if (a[0] != tType || a[1] != tCode) {
                continue;
            }
            seen++;
            // nth<=0 是老数据：没记序号，按下顺序里第一个同编码的就是它
            if (nth <= 0 || seen == nth) {
                sendComboAct(a[0], a[1], false,
                        a.length > 2 ? a[2] : NONE,
                        a.length > 3 ? a[3] : 0,
                        a.length > 4 ? a[4] : -1);
                sent.remove(k);
                return;
            }
        }
    }

    /** 组合键松开。 */
    private void releaseCombo(int i) {
        mDown[i] = 0;
        mCrossArms[i] = 0;
        for (int L = 0; L < COMBO_LANES; L++) {
            if (mComboPend.get(L).isEmpty()) {
                // 序列已经播完（或者本来就没有延迟）：立即松开
                comboReleaseSent(L);
            }
            // 还有动作没发出来（延迟中）：什么都不做，
            // 交给收尾 Runnable 播完后统一松开。
        }
        invalidate();
    }

    private void onDown(int elem, float x, float y) {
        // 【点 G = 收起】和点「收」完全一样的效果。
        //   它是独立槽位，但功能上就是悬浮球本体。
        if (elem == PadLayout.I_FLOAT) {
            mDown[PadLayout.I_FLOAT] = 1;
            mSink.onCollapse();
            invalidate();
            return;
        }
        if (elem == PadLayout.I_COLLAPSE) {
            mDown[PadLayout.I_COLLAPSE] = 1;
            mSink.onCollapse();
            invalidate();
            return;
        }
        if (elem == PadLayout.I_EDIT) {
            setEditMode(true, false);
            return;
        }
        if (elem == PadLayout.I_PASS) {
            // 切穿透开关，并在状态行下面留一行小字。
            // 编辑模式下不切 —— 那时点它是"选中这个按钮准备拖动"。
            if (!mEditMode) {
                togglePassThrough();
            }
            return;
        }
        if (elem == PadLayout.I_LAYOUT) {
            // 「布」：弹出布局列表，点一个就切过去。
            // 编辑模式下不弹 —— 编辑时用「常用工具 -> 布局切换」，
            // 那个列表还能改名 / 删除；这里只管快速切换。
            if (!mEditMode) {
                openList(LIST_LAYOUT_PICK);
            } else {
                toastLocal("编辑模式请用「常用工具 -> 布局切换」");
            }
            return;
        }
        /*
          鼠标三件套。
          必须在 applyFloatingStick 和后面的 proto switch 之前拦下：
          它们的 proto 是 I_MOUSE_PAD 这种大下标，落进 default 会去调
          toButtonIndex(elem)，按 GamepadReport 的按钮表越界取值。
        */
        if (PadLayout.isMouseSlot(elem) && mLayout.isMouseUsed(elem)) {
            mDown[elem] = 1;
            if (elem == PadLayout.I_MOUSE_PAD) {
                // 触摸板：只记起点，位移在 onMove 里按增量发
                mMouseLastX = x;
                mMouseLastY = y;
                // 预览线的原点 = 手指按下的位置，不是触摸板中心
                mMouseOriginX = x;
                mMouseOriginY = y;
                mMouseAccX = 0;
                mMouseAccY = 0;
                mMouseMoved = false;
            } else {
                int b = mouseButtonOf(elem);
                if (b >= 0) {
                    mSink.onMouseButton(b, true);
                } else {
                    // 滚轮：没有"按住"这个概念，按一下滚一格。
                    // 每次点都发，长按不会连发 —— 要连续滚就连续点。
                    mSink.onMouseWheel(mouseWheelOf(elem));
                }
            }
            invalidate();
            return;
        }

        // 【浮动摇杆：按下时先瞬移】
        //
        //   关掉"固定摇杆"后，手指落在摇杆的范围圈内按下 ——
        //   摇杆**瞬移到手指位置**，然后照常跟着手指推。
        //   抬手就留在那儿，下次从新位置开始，这就是"浮动"。
        //
        //   必须在 onMove 之前做：onMove 是按"按下点到当前点"算偏移的，
        //   瞬移之后两者重合，推的起点才是 0，不会一按下就跳到满舵。
        applyFloatingStick(elem, x, y);

        // 【组合键必须在 switch 之前拦下】
        //   它的 padType 恒为 0，protoD 会等于槽位下标（182+），
        //   落到 default 分支去调 toButtonIndex(elem)，直接越界。
        if (PadLayout.isComboSlot(elem) && mLayout.isComboUsed(elem)) {
            if (mLayout.comboCross[elem]) {
                //
                // 【十字架：按落点分方向】
                //   斜角开了就同时跑两条（左 + 上），各占一条轨道；
                //   关了的话斜角落点什么都不做，只有四个正方向有反应。
                //   正中间的死区也不响应 —— 没设过的方向点了不该有动作。
                float rr = radiusOf(elem);
                float u = (x - mPx[elem]) / Math.max(1f, rr);
                float vv = (y - mPy[elem]) / Math.max(1f, rr);
                float th = 0.35f;
                boolean up = vv < -th;
                boolean dn = vv > th;
                boolean lf = u < -th;
                boolean rt = u > th;
                int d1 = -1;
                int d2 = -1;
                if (up && !dn) d1 = PadLayout.DIR_UP;
                else if (dn && !up) d1 = PadLayout.DIR_DOWN;
                if (lf && !rt) {
                    if (d1 < 0) d1 = PadLayout.DIR_LEFT; else d2 = PadLayout.DIR_LEFT;
                } else if (rt && !lf) {
                    if (d1 < 0) d1 = PadLayout.DIR_RIGHT; else d2 = PadLayout.DIR_RIGHT;
                }
                if (d1 < 0) {
                    return;
                }
                if (d2 >= 0 && !mLayout.comboDiag[elem]) {
                    return;
                }
                // 按下反馈：记下这一按点亮哪几条臂，松手才清。
                // 斜角是两条（左+上），正方向一条。
                mCrossArms[elem] = crossArmBit(d1)
                        | (d2 >= 0 ? crossArmBit(d2) : 0);
                mDown[elem] = 1;
                fireCombo(elem, d1, 0);
                if (d2 >= 0) {
                    fireCombo(elem, d2, 1);
                }
                invalidate();
                return;
            }
            fireCombo(elem);
            return;
        }

        // 【按 proto 分派，不是按下标】副本槽位上的摇杆也要能推。
        int protoD = (mLayout != null && mLayout.padType[elem] != 0)
                ? PadLayout.protoOfType(mLayout.padType[elem]) : elem;
        switch (protoD) {
            case PadLayout.I_LS:
                mLsKx = 0;
                mLsKy = 0;
                onMove(elem, x, y);
                break;
            case PadLayout.I_RS:
                mRsKx = 0;
                mRsKy = 0;
                onMove(elem, x, y);
                break;
            case PadLayout.I_DPAD:
                onMove(elem, x, y);
                break;
            case PadLayout.I_L2:
            case PadLayout.I_R2:
                // 带轨道的扳机：按下 = 最轻的一档（TRIG_MIN），往上滑逐步加深。
                // 记下按下点和当时的值，后面按位移增量算，
                // 这样按在轨道哪个位置都是"从最轻开始"，符合真实扳机的手感。
                mDown[elem] = 1;
                mTrigAnchorY[elem] = y;
                mTrigAnchorT[elem] = TRIG_MIN;
                mSink.onButton(toButtonIndex(elem), true);
                setTriggerValue(elem, TRIG_MIN);
                break;
            case PadLayout.I_L2B:
            case PadLayout.I_R2B:
                // 无轨道版：就是普通圆按钮，按下直接满值，松手归 0。
                // 和带轨道版是两个独立的按钮，位置/大小/隐藏状态各管各的。
                mDown[elem] = 1;
                mSink.onButton(toButtonIndex(elem), true);
                mSink.onTrigger(triggerIndexOf(elem), 1f);
                invalidate();
                break;
            default:
                mDown[elem] = 1;
                if (PadLayout.isBlankSlot(elem)) {
                    // 空白按钮：什么都不发，只留按下时的高亮
                } else if (PadLayout.isKeySlot(elem)) {
                    // 动态键盘按键：走键盘设备，不是手柄按钮
                    mSink.onKey(mLayout.keyCode[elem], true);
                } else {
                    mSink.onButton(toButtonIndex(elem), true);
                }
                invalidate();
                break;
        }
    }

    /**
     * 原型下标 -> 手柄按钮位号。
     *
     * toButtonIndex() 收的是**元素下标**，而空白按钮不映射任何键，
     * **原型下标**（创建时从 PAD_CANDIDATES 里挑的那个），两个不是一回事：
     * 直接把 proto 传进去，副本槽位判断会全错（proto 3 会被当成下标 3）。
     */
    private int buttonIndexOfProto(int proto) {
        switch (proto) {
            case PadLayout.I_A:
                return GamepadReport.BTN_A;
            case PadLayout.I_B:
                return GamepadReport.BTN_B;
            case PadLayout.I_X:
                return GamepadReport.BTN_X;
            case PadLayout.I_Y:
                return GamepadReport.BTN_Y;
            case PadLayout.I_L1:
                return GamepadReport.BTN_L1;
            case PadLayout.I_R1:
                return GamepadReport.BTN_R1;
            case PadLayout.I_L2:
            case PadLayout.I_L2B:
                return GamepadReport.BTN_L2;
            case PadLayout.I_R2:
            case PadLayout.I_R2B:
                return GamepadReport.BTN_R2;
            case PadLayout.I_SEL:
                return GamepadReport.BTN_SELECT;
            case PadLayout.I_STA:
                return GamepadReport.BTN_START;
            case PadLayout.I_L3:
                return GamepadReport.BTN_THUMBL;
            case PadLayout.I_R3:
                return GamepadReport.BTN_THUMBR;
            case PadLayout.I_HOME:
                return GamepadReport.BTN_MODE;
            case PadLayout.I_Z:
                return GamepadReport.BTN_Z;
            case PadLayout.I_C:
                return GamepadReport.BTN_C;
            default:
                return GamepadReport.BTN_START;
        }
    }

    /**
     * 设置扳机按压程度：发轴事件 + 重绘。
     *
     * L2/R2 同时驱动按钮和扳机轴 —— 有的游戏只认 BUTTON_L2/R2，
     * 有的只认 AXIS_LTRIGGER/RTRIGGER，两个都发才不会漏。
     * 这里只管轴，按钮在 onDown / onUp 里发一次（按下就有了，不看成程度）。
     */
    private void setTriggerValue(int elem, float t) {
        mTrigVal[elem] = t;
        mSink.onTrigger(triggerIndexOf(elem), t);
        invalidate();
    }

    /**
     * 这个按钮驱动哪根扳机轴：L2 / L2B 都是 0，R2 / R2B 都是 1。
     * 两个版本共用同一根轴 —— 它们是同一个物理扳机的两种操作方式。
     *
     * 【必须按 padType 转成 proto 再判，不能直接用下标】
     *   手动创建的键落在副本槽位（133..164），下标不是 I_L2(9) / I_L2B(17)。
     *   原来直接写 elem == I_L2 判断，副本槽位上一个都对不上，
     *   于是"手动建的 L2"走了 else 分支 → 返回 1 → **实际扣的是 R2 扳机**。
     *   表现：按 L2，游戏里 R2 在动。
     *
     *   对比：toButtonIndex() 一直是对的（它按 proto switch），
     *   所以按钮位号发的是 L2、扳机轴却发到 R2 —— 两个信号互相矛盾，
     *   只认轴的游戏看到 R2，只认按钮的看到 L2。
     */
    private int triggerIndexOf(int elem) {
        int proto = (mLayout != null && mLayout.padType[elem] != 0)
                ? PadLayout.protoOfType(mLayout.padType[elem]) : elem;
        return (proto == PadLayout.I_L2 || proto == PadLayout.I_L2B) ? 0 : 1;
    }

    /**
     * 把手指位置换算成摇杆的 (-1..1, -1..1)，**限制在单位圆内**。
     *
     * 原来用 clamp() 分别夹 dx / dy，那是方框：四个角能到 (±1, ±1)，
     * 合成 1.41 —— 斜着推比正着推"更快"，游戏里表现为斜跑超速、
     * 或者斜向灵敏度比正交方向高。真实手柄的摇杆是圆的，到边就是满了。
     *
     * 所以超出圆时按原方向等比缩到圆上（模长归一），方向不变、长度封顶。
     *
     * 返回长度 2 的数组而不是自定义类：省一个类，也避免多余的对象分配。
     */
    private float[] roundStick(float x, float y, int i, float r) {
        float dx = (x - mPx[i]) / r;
        float dy = (y - mPy[i]) / r;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len > 1f) {
            dx /= len;
            dy /= len;
        }
        // 复用同一个数组返回：onMove 每帧都可能被调用好几次，
        // 每次 new 一个 float[2] 会持续制造垃圾、触发 GC 卡顿。
        // 调用方立刻就取值用完，不存在持有引用的问题。
        mStickTmp[0] = dx;
        mStickTmp[1] = dy;
        return mStickTmp;
    }

    /**
     * 十字架组合键：手指**滑动**换方向。
     *
     * 【为什么必须单独写】
     *   onMove 是按 proto 分派的，而组合键 padType 恒 0，proto 会等于
     *   槽位下标（182+），switch 里没有任何 case 命中 —— 于是手指在十字架上
     *   滑动完全没有反应，只能抬手重按。
     *
     *   真·方向键能滑（onMove 的 I_DPAD 分支每帧重算 hat），
     *   十字架长得一样却不能滑，手感对不上。
     */
    private void comboCrossMove(int elem, float x, float y) {
        float rr = radiusOf(elem);
        float u = (x - mPx[elem]) / Math.max(1f, rr);
        float vv = (y - mPy[elem]) / Math.max(1f, rr);
        float th = 0.35f;
        boolean up = vv < -th;
        boolean dn = vv > th;
        boolean lf = u < -th;
        boolean rt = u > th;
        int d1 = -1;
        int d2 = -1;
        if (up && !dn) d1 = PadLayout.DIR_UP;
        else if (dn && !up) d1 = PadLayout.DIR_DOWN;
        if (lf && !rt) {
            if (d1 < 0) d1 = PadLayout.DIR_LEFT; else d2 = PadLayout.DIR_LEFT;
        } else if (rt && !lf) {
            if (d1 < 0) d1 = PadLayout.DIR_RIGHT; else d2 = PadLayout.DIR_RIGHT;
        }
        boolean diag = mLayout.comboDiag[elem];
        if (d1 < 0 || (d2 >= 0 && !diag)) {
            // 滑进死区 / 斜角关着时滑到斜角：松手，不触发任何方向
            if (mCrossArms[elem] != 0) {
                comboCancelAll();
                mCrossArms[elem] = 0;
                mDown[elem] = 0;
                invalidate();
            }
            return;
        }
        int want = crossArmBit(d1) | (d2 >= 0 ? crossArmBit(d2) : 0);
        if (want == mCrossArms[elem]) {
            return;                     // 还在同一个方向，什么都不做
        }
        // 换方向：先把上一条序列收掉（含延迟中没发出来的），再发新的。
        // 不收的话旧序列的延迟动作会在切完方向之后冒出来。
        comboCancelAll();
        mCrossArms[elem] = want;
        mDown[elem] = 1;
        fireCombo(elem, d1, 0);
        if (d2 >= 0) {
            fireCombo(elem, d2, 1);
        }
        invalidate();
    }

    private void onMove(int elem, float x, float y) {
        /*
          触摸板：按"上一次位置 -> 这次位置"的增量发，不是绝对坐标。
          【为什么每次发完就把 last 挪到当前点】
            这是增量模型，last 必须跟着实际发出去的量走，
            否则手指停一下再动会一次性补发攒下的位移，光标"抽一下"。
        */
        if (PadLayout.isMouseSlot(elem) && mLayout != null
                && mLayout.isMouseUsed(elem)) {
            if (elem == PadLayout.I_MOUSE_PAD) {
                int dx = Math.round((x - mMouseLastX) * MOUSE_SENS);
                int dy = Math.round((y - mMouseLastY) * MOUSE_SENS);
                if (dx != 0 || dy != 0) {
                    mMouseMoved = true;
                    if (sMouseDefer) {
                        // 松手才发：这里一个字节都不往外发，只记账。
                        // 滑动全程没有鼠标事件 = 没有"输入源变了" = 收不到 CANCEL。
                        mMouseAccX += dx;
                        mMouseAccY += dy;
                    } else {
                        mSink.onMouseMove(clampDelta(dx), clampDelta(dy));
                    }
                    mMouseLastX = x;
                    mMouseLastY = y;
                }
            }
            return;
        }
        // 十字架组合键：手指滑动换方向（普通组合键不参与，它只有一个方向）
        if (PadLayout.isComboSlot(elem) && mLayout != null
                && mLayout.isComboUsed(elem) && mLayout.comboCross[elem]) {
            comboCrossMove(elem, x, y);
            return;
        }
        // 按 proto 分派：副本槽位上的摇杆 / 十字键走同一套逻辑
        int proto = (mLayout != null && mLayout.padType[elem] != 0)
                ? PadLayout.protoOfType(mLayout.padType[elem]) : elem;
        switch (proto) {
            case PadLayout.I_LS: {
                float r = radiusOf(elem);
                // 摇杆是圆的，模拟范围也得是圆：拖到圆外时按方向归一化到圆边，
                // 而不是分别夹 dx / dy —— 那样四个角能到 (±1, ±1)，
                // 合成长度 1.41，比正上下左右推满还大，斜向会"超速"。
                float[] d = roundStick(x, y, elem, r);
                float dx = d[0];
                float dy = d[1];
                mLsKx = dx * r * 0.55f;
                mLsKy = dy * r * 0.55f;
                mSink.onAxis(0, applyDead(dx));
                mSink.onAxis(1, applyDead(dy));
                invalidate();
                break;
            }
            case PadLayout.I_RS: {
                float r = radiusOf(elem);
                float[] d = roundStick(x, y, elem, r);
                float dx = d[0];
                float dy = d[1];
                mRsKx = dx * r * 0.55f;
                mRsKy = dy * r * 0.55f;
                mSink.onAxis(2, applyDead(dx));
                mSink.onAxis(3, applyDead(dy));
                invalidate();
                break;
            }
            case PadLayout.I_DPAD: {
                float dx = x - mPx[elem];
                float dy = y - mPy[elem];
                if (dist(x, y, mPx[elem], mPy[elem])
                        < radiusOf(elem) * DEAD) {
                    setHat(8);
                    break;
                }
                setHat(hatFromDelta(dx, dy));
                break;
            }
            case PadLayout.I_L2:
            case PadLayout.I_R2: {
                // 往上滑（y 变小）= 扣得越深。
                // 位移除以整个行程，滑满一整个轨道正好从 TRIG_MIN 到 1。
                float t = mTrigAnchorT[elem]
                        + (mTrigAnchorY[elem] - y) / trackTravel(elem);
                if (t < TRIG_MIN) t = TRIG_MIN;
                if (t > 1f) t = 1f;
                setTriggerValue(elem, t);
                break;
            }
        }
    }

    /** 把瞬移过的浮动摇杆放回原位。没瞬移过就什么都不做。 */
    private void restoreFloatingStick(int elem) {
        if (elem < 0 || elem >= PadLayout.N || !mFloatMoved[elem]) {
            return;
        }
        mPx[elem] = mFloatHomeX[elem];
        mPy[elem] = mFloatHomeY[elem];
        mFloatMoved[elem] = false;
    }

    private void onUp(int elem) {
        if (PadLayout.isMouseSlot(elem) && mLayout != null
                && mLayout.isMouseUsed(elem)) {
            mDown[elem] = 0;
            if (elem == PadLayout.I_MOUSE_PAD) {
                // 双击触摸板 = 切换发送时机（测试用，好来回对比）
                long now = System.currentTimeMillis();
                if (!mMouseMoved && now - mMouseTapMs < 400) {
                    sMouseDefer = !sMouseDefer;
                    mMouseTapMs = 0;
                    toastLocal(sMouseDefer
                            ? "鼠标：松手才发（滑动中不发事件）"
                            : "鼠标：边滑边发（实时）");
                } else if (!mMouseMoved) {
                    mMouseTapMs = now;
                }
                if (sMouseDefer) {
                    // 一次性把整段位移吐出去。int8 一帧最多带 127，
                    // 所以拆成多帧发，每帧大小和正常拖动时一样。
                    int ax = Math.round(mMouseAccX);
                    int ay = Math.round(mMouseAccY);
                    while (ax != 0 || ay != 0) {
                        int sx = clampDelta(ax);
                        int sy = clampDelta(ay);
                        mSink.onMouseMove(sx, sy);
                        ax -= sx;
                        ay -= sy;
                    }
                    mMouseAccX = 0;
                    mMouseAccY = 0;
                }
            } else {
                int b = mouseButtonOf(elem);
                // 滚轮不发抬起：它本来就没按下过
                if (b >= 0) {
                    mSink.onMouseButton(b, false);
                }
            }
            invalidate();
            return;
        }
        // 【浮动摇杆：抬手归位】
        //   瞬移是临时的，松手必须回到原位，否则摇杆会一路跟着手指
        //   漂走，越漂越远 —— 那不是"浮动"，那是"被拖走了"。
        restoreFloatingStick(elem);
        if (PadLayout.isComboSlot(elem) && mLayout.isComboUsed(elem)) {
            releaseCombo(elem);
            return;
        }
        int proto = (mLayout != null && mLayout.padType[elem] != 0)
                ? PadLayout.protoOfType(mLayout.padType[elem]) : elem;
        switch (proto) {
            case PadLayout.I_LS:
                mLsKx = 0;
                mLsKy = 0;
                mSink.onAxis(0, 0f);
                mSink.onAxis(1, 0f);
                invalidate();
                break;
            case PadLayout.I_RS:
                mRsKx = 0;
                mRsKy = 0;
                mSink.onAxis(2, 0f);
                mSink.onAxis(3, 0f);
                invalidate();
                break;
            case PadLayout.I_DPAD:
                setHat(8);
                break;
            case PadLayout.I_COLLAPSE:
            case PadLayout.I_EDIT:
            case PadLayout.I_LAYOUT:
            case PadLayout.I_PASS:
            case PadLayout.I_FLOAT:
                mDown[elem] = 0;
                invalidate();
                break;
            // 以下 case 用的是 proto（副本槽位上的 L2 也要能松）
            case PadLayout.I_L2:
            case PadLayout.I_R2:
                mDown[elem] = 0;
                mSink.onButton(toButtonIndex(elem), false);
                setTriggerValue(elem, 0f);
                break;
            case PadLayout.I_L2B:
            case PadLayout.I_R2B:
                mDown[elem] = 0;
                mSink.onButton(toButtonIndex(elem), false);
                mSink.onTrigger(triggerIndexOf(elem), 0f);
                invalidate();
                break;
            default:
                mDown[elem] = 0;
                if (PadLayout.isBlankSlot(elem)) {
                    // 空白按钮：什么都不发
                } else if (PadLayout.isKeySlot(elem)) {
                    mSink.onKey(mLayout.keyCode[elem], false);
                } else {
                    mSink.onButton(toButtonIndex(elem), false);
                }
                invalidate();
                break;
        }
    }

    private void setHat(int hat) {
        if (mHat == hat) return;
        mHat = hat;
        mSink.onHat(hat);
        invalidate();
    }

    void releaseAll() {
        releaseAll(true);
    }

    /**
     * @param hardCombo true = 连组合键的延迟序列一起硬取消。
     *
     * 【为什么 ACTION_CANCEL 要传 false】
     *   组合键带延迟时，"松手后序列继续播完"是设计行为。
     *   而 ACTION_CANCEL 会在手指**还按着**的时候送来 —— 典型来源是
     *   窗口 relayout（FloatingService 的 mRegionCommit 会连续调
     *   updateViewLayout），它会把当前手势判为取消。
     *   这里如果硬取消，等于每按下一次就把自己的序列掐掉，
     *   表现为"延迟后面的键永远不触发"。交给 releaseCombo 软处理即可。
     */
    private void releaseAll(boolean hardCombo) {
        mSlDrag = NONE;
        mSlDragPointer = NONE;
        // 兜底：组合键的延迟序列可能还在排队（切布局、重置），
        // 这里统一收掉，否则键会卡在按下状态。
        // 【序列正在播时不能硬取消】见上面 hardCombo 的说明。
        if (hardCombo || !comboPending()) {
            comboCancelAll();
            // 硬取消（切布局 / 退出编辑 / 状态变更）：清掉十字架点亮的臂，
            // 否则切完布局那个十字架还亮着，看着像卡在按下状态。
            for (int i = 0; i < PadLayout.N; i++) {
                mCrossArms[i] = 0;
            }
        } else {
            Log.w("VGpad", "releaseAll: 组合键序列还在播，交给收尾处理");
        }
        if (mLayout == null) {
            mPointers.clear();
            return;
        }
        for (int i = 0; i < mPointers.size(); i++) {
            onUp(mPointers.valueAt(i));
        }
        mPointers.clear();
        // 扳机有可能停在按下状态（比如多指乱序抬起），兜底清一次，
        // 免得界面上滑块卡在半路、游戏里扳机一直扣着。
        // 兜底：万一某个摇杆瞬移后没走 onUp（比如手势被系统打断），
        // 这里统一放回去，别让它停在半路。
        for (int i = 0; i < PadLayout.N; i++) {
            restoreFloatingStick(i);
        }
        for (int i = 0; i < PadLayout.N; i++) {
            if (isTrigger(i) && mTrigVal[i] != 0f) {
                mTrigVal[i] = 0f;
                // 同样要按 proto 判（副本槽位下标对不上 I_L2），
                // 这里原来直接比下标，副本上的 L2 会被当成 R2 清错轴。
                mSink.onTrigger(triggerIndexOf(i), 0f);
            }
        }
        invalidate();
    }

    /**
     * 槽位 -> HID 按钮位号。
     *
     * 【按 padType 而不是按下标】
     *   副本槽位（133+）上的 A 键，下标不是 I_A，
     *   但发给游戏的必须还是 BTN_A —— 否则建的副本按下去没反应。
     *
     *   同一类型的多份共用同一个位号：
     *   两份 A 键任意一份按下都发 BTN_A，这是"多份"应有的语义
     *   （HID 报告的按钮位图里 A 只有一位，没法区分是哪一份）。
     */
    private int toButtonIndex(int elem) {
        int proto = (mLayout != null && mLayout.padType[elem] != 0)
                ? PadLayout.protoOfType(mLayout.padType[elem]) : elem;
        switch (proto) {
            case PadLayout.I_A:
                return GamepadReport.BTN_A;
            case PadLayout.I_B:
                return GamepadReport.BTN_B;
            case PadLayout.I_X:
                return GamepadReport.BTN_X;
            case PadLayout.I_Y:
                return GamepadReport.BTN_Y;
            case PadLayout.I_L1:
                return GamepadReport.BTN_L1;
            case PadLayout.I_R1:
                return GamepadReport.BTN_R1;
            case PadLayout.I_L2:
            case PadLayout.I_L2B:
                return GamepadReport.BTN_L2;
            case PadLayout.I_R2:
            case PadLayout.I_R2B:
                return GamepadReport.BTN_R2;
            case PadLayout.I_SEL:
                return GamepadReport.BTN_SELECT;
            case PadLayout.I_STA:
                return GamepadReport.BTN_START;
            case PadLayout.I_L3:
                return GamepadReport.BTN_THUMBL;
            case PadLayout.I_R3:
                return GamepadReport.BTN_THUMBR;
            case PadLayout.I_HOME:
                // 手柄上的 HOME / 导航 / PS 键，标准位号是 HID button 13
                return GamepadReport.BTN_MODE;
            case PadLayout.I_Z:
                return GamepadReport.BTN_Z;
            case PadLayout.I_C:
                return GamepadReport.BTN_C;
            default:
                return GamepadReport.BTN_START;
        }
    }

    /**
     * 清空选中集合。
     *
     * 选中是**会话级**的（不存盘）—— 切布局、重置、退出编辑都会清掉，
     * 免得下次进来还挂着上一轮那一批。
     */

    /** 只选中这一个（取消其它所有选中）。 */

    /** 当前选了几个。 */

    /**
     * 是不是多选。
     *
     * 【为什么不再用 isMulti() 之类的开关】
     *   关联挪动 / 多选调节 / 多选形状三件事，本质上都是"对一批按钮做批量操作"，
     *   所以统一成一种状态：**选中集合里有几个**。
     *   单选就是一个，多选就是一批，面板按这个数决定显示单键还是批量。
     *   三个开关各记各的必然会对不上（改了这处忘了那处）。
     */
    boolean isMulti() {
        return selCount() > 1;
    }

    /** 形状子模式下、且是多选 —— 此时「圆形 / 四边形」刷给整批。 */
    private boolean isMultiShape() {
        return mShapeMode && isMulti();
    }

    /** 选中集合里的第一个（当"主选中"用：面板显示名字、摇杆判断等）。 */

    /**
     * 把列表里勾好的那批变成当前选中集合。
     *
     * 关联挪动 / 多选调节 / 多选形状三条入口都走这里 ——
     * 它们只是"进去之后停在哪个子界面"不同，选中集合是同一份。
     */

    private static float clamp(float v) {
        if (v > 1f) return 1f;
        if (v < -1f) return -1f;
        return v;
    }

    private static float applyDead(float v) {
        float a = Math.abs(v);
        if (a < DEAD) return 0f;
        float sign = v < 0 ? -1f : 1f;
        return sign * (a - DEAD) / (1f - DEAD);
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (mLayout == null) {
            return;
        }

        // 从 app 自己的界面里打开编辑模式时，底下是亮的设置页，
        // 铺一层不透明黑底，按键才看得清；从游戏里打开时保持透明。
        if (mEditMode && mFromApp) {
            c.drawColor(0xFF000000);
        }

        // 网格画在按键**下面**，当参考线用；只在编辑期出现。
        if (mEditMode) {
            drawGrid(c);
        }

        /*
          所有元素按优先级从低到高画一遍：画在后面的盖在上面。

          以前是"空白 → 轴类 → 圆按钮 → 鼠标 → 键盘 → 组合键"六趟写死的循环，
          触摸板（鼠标那趟）永远画在按键之后 = 压在按键上面。
          现在顺序由 buildOrder() 算，优先级低的元素先画 ——
          触摸板默认垫底，按键照常压在它上面。
        */
        buildOrder(false);
        for (int k = 0; k < mOrdN; k++) {
            int i = mDrawOrd[k];
            if (mLayout.isBlankUsed(i)) {
                drawBlankBtn(c, i);
            } else if (mLayout.isComboUsed(i)) {
                drawComboBtn(c, i);
            } else if (mLayout.isMouseUsed(i)) {
                drawElem(c, i);
            } else if (mLayout.isKeySlotUsed(i)) {
                drawBtn(c, i, PadLayout.keyName(mLayout.keyCode[i]));
            } else {
                drawElem(c, i);
            }
        }
        // 编辑模式下给隐藏的按钮打叉：还能看见、还能选中、还能拖，
        // 但一眼知道它在游戏里是不出现的。
        if (mEditMode) {
            drawHiddenMarks(c);
        }

        if (mEditMode) {
            drawLinked(c);
            drawSelection(c);
            drawStickRange(c);
            // 对齐射线画在选中框之后、面板之前：比按键和选中框显眼，
            // 但不盖住下面的编辑面板（面板要能点）
            drawAlignRays(c);
            // 界面按钮：图层高于游戏按键，但**低于编辑面板和列表**。
            //
            // 【必须在 drawEditPanel 之前画】
            //   画在后面的盖在上面。按钮要是画在面板之后，就会盖住面板 ——
            //   画面上看着按钮在上面，点击却排在面板之后（hitSlider / hitTool 先判），
            //   于是"看得见却点不到"，而且按钮压住滑条时滑条也点不到了。
            //   绘制和点击必须一致：面板始终最上层、始终可点。
            drawUiButtons(c);
            drawEditPanel(c);
        } else {
            drawStatus(c);
            drawUiButtons(c);
        }
        // 列表永远画在最后一层：盖住编辑面板、四个界面按钮和所有游戏按键。
        // 不放在 mEditMode 分支里 —— 「布」按钮在非编辑模式下也会开列表。
        if (mListMode != LIST_NONE) {
            drawPickList(c);
        }
        // 对话框盖在列表之上（它是从列表里点出来的）
        if (mDlgMode != DLG_NONE) {
            drawDlg(c);
        }
        // 提示条盖在所有东西之上：它是"你刚才那下操作的结果"，
        // 被列表或对话框挡住就等于没提示。
        drawBanner(c);
    }

    /**
     * 「编」「键」「布」「收」四个界面按钮。
     *
     * 原来画在 onDraw 最末尾，结果**盖在列表上面** ——
     * 打开「常用工具 / 布局切换」时，四个角上的圆按钮浮在列表面板之上。
     * 它们只需要高于游戏按键，不需要高于列表，所以移到列表之前画。
     */
    private void drawUiButtons(Canvas c) {
        // 【逆序遍历 UI_Z】下标大的先画，被后画的盖住 → 在下层。
        //   和 hitUiButton() 的正序遍历恰好互为镜像，
        //   所以"画面上谁在最上"永远等于"点到的是谁"。
        for (int k = UI_Z.length - 1; k >= 0; k--) {
            int i = UI_Z[k];
            if (i == PadLayout.I_COLLAPSE) {
                // 【悬浮窗布局里「收」完全不画】
                //   它和 G 同一个位置，画出来就是两个圆叠着，
                //   编辑模式下还会各带一个叉 —— 看着像有个多余的按钮。
                //   G 点了就是收起，功能没丢，所以这个布局不需要它。
                if (isFloatLayout()) {
                    continue;
                }
                drawRound(c, i, uiLabel(i, "收"), mCollapsePaint);
            } else if (i == PadLayout.I_FLOAT) {
                drawFloatBall(c, i);
            } else if (i == PadLayout.I_PASS) {
                // 穿透开关：绿√ / 红×，颜色每次读当前开关状态。
                // 走 drawRound 就自动有了居中、缩放、隐藏、拖动这些能力，
                // 不用再自己算基线 —— 之前在状态行上手写 centerY + dp(5) 是不居中的。
                boolean on = FloatingService.keyWindowsEnabled(getContext());
                // 没设过就退回内置的绿 / 红（0 = 未设置）
                int pc = on ? mLayout.passOnColor : mLayout.passOffColor;
                mPassPaint.setColor(pc != 0 ? pc : (on ? 0xFF81C784 : 0xFFE57373));
                drawRound(c, i, uiLabel(i, on ? "透√" : "透×"), mPassPaint);
            } else {
                drawRound(c, i,
                        uiLabel(i, i == PadLayout.I_EDIT ? "编" : "布"), mEditPaint);
            }
        }
        // 叉补在最后：不然界面按钮会把自己的隐藏标记盖住
        if (mEditMode) {
            drawHiddenMarksForUi(c);
        }
    }

    /**
     * 元素实际的不透明度 —— 就是面板上那条「透明」滑条的值。
     *
     * 【为什么不再乘颜色里的 alpha】
     *   早先颜色也能带 alpha（#AARRGGBB），于是同一个按钮有两个透明度，
     *   画的时候再相乘：滑条写着 80%、看着却像 64%，
     *   拖到 70% 再拖回 80% 也对不上原来的样子。
     *   现在 alpha 只有滑条这一个来源（挑色时颜色里的 alpha 会搬进滑条，
     *   见 applyPickedColor），所以这里不需要再算一遍。
     */
    private float effAlpha(int i) {
        float a = mLayout.alpha[i];
        // 别真的归零：全透明等于这个键凭空消失，找都找不回来
        return a < 0.02f ? 0.02f : (a > 1f ? 1f : a);
    }

    /**
     * 界面按钮的标签：关掉「按键名」就什么都不画。
     *
     * 【为什么这里要单独判一次】
     *   drawRound 的 label 是调用方传进来的，而界面按钮这几个字
     *   （收 / 编 / 布 / 透）是写死的 —— 走 drawRound 的那套 labelOn
     *   判断压根轮不到它们。于是"外观 -> 按键名：隐藏"对功能键没反应，
     *   只有普通按键有效。
     */
    private String uiLabel(int i, String def) {
        return mLayout.labelOn(i) ? def : "";
    }

    /**
     * 悬浮窗布局里的 G。
     *
     * 【必须照着 BubbleView 画，不能走 drawRound】
     *   drawRound 是"游戏按键"的画法：灰描边 + 深色字 + 浅色描边，
     *   和收起后那个悬浮球（白描边 + 白字 + 纯色填充）根本不像。
     *   用户在这个布局里调 G，就是为了调"收起后看到的那颗球"，
     *   两边画法不一致的话等于没法预览。
     *
     *   所以这里把 BubbleView.onDraw 的画法搬过来：
     *   填充色（含 alpha）、白描边 3px、白字居中，按下 = 常态色提亮。
     */
    private void drawFloatBall(Canvas c, int i) {
        if (!shouldDraw(i)) return;
        // 【直接调 BubbleView.drawBall，不在这里重画一遍】
        //   自己画一遍就会抄漏细节（描边是 STROKE 还是 FILL、字对齐），
        //   抄漏一处就和真球不一样。共用一份代码才叫"预览"。
        BubbleView.drawBall(c, mPx[i], mPy[i], radiusOf(i),
                mBallPaint, mBallRingPaint, mBallTextPaint,
                mLayout.btnColor[i], mLayout.shape[i], mLayout.nameOf(i),
                mLayout.labelOn(i), mLayout.textScale[i], effAlpha(i),
                mDown[i] == 1, getResources().getDisplayMetrics().density);
    }

    private void drawStick(Canvas c, int i, float kx, float ky, String label) {
        if (!shouldDraw(i)) return;
        float a = effAlpha(i);
        float r = radiusOf(i);
        // 【摇杆也吃自定义底色】
        //   只有圆按钮（drawBtn）接了 btnColor，摇杆的环和帽都是写死色，
        //   于是给它设颜色毫无反应。这里把色套到"底环 + 帽"两支上：
        //   帽用提亮版，和底同色的话两层糊成一整块，看不出帽子在哪。
        int cc = mLayout.btnColor[i];
        int oldRing = mRingPaint.getColor();
        int oldStick = mStickPaint.getColor();
        if (cc != 0) {
            mRingPaint.setColor(cc);
            mStickPaint.setColor(0xFF000000 | (lighten(cc) & 0x00FFFFFF));
        }
        setA(mRingPaint, a);
        setA(mStickPaint, a);
        setA(mOutlinePaint, a);
        c.drawCircle(mPx[i], mPy[i], r, mRingPaint);
        // 摇杆帽是纯白的，压在浅色画面上会看不见 —— 和按键环一样加灰边。
        // 帽比环小，描边也跟着细一点，免得吃掉中间的字母。
        mOutlinePaint.setStrokeWidth(Math.max(2f, r * 0.05f));
        c.drawCircle(mPx[i] + kx, mPy[i] + ky, r * 0.45f, mStickPaint);
        c.drawCircle(mPx[i] + kx, mPy[i] + ky, r * 0.45f, mOutlinePaint);
        // 接 textScale：原来用的是 drawLabel（倍率写死 1f），
        // 滑条拖了没反应，用户会以为摇杆的字体不能调。
        resetA(mRingPaint);
        resetA(mStickPaint);
        resetA(mOutlinePaint);
        // 摇杆帽上写明 L / R，不用猜哪个是左哪个是右
        float oldSize = mTextPaint.getTextSize();
        float oldHalo = mTextHaloPaint.getStrokeWidth();
        mTextPaint.setTextSize(r * 0.42f);
        mTextHaloPaint.setTextSize(r * 0.42f);
        mTextHaloPaint.setStrokeWidth(Math.max(1.5f, r * 0.06f));
        drawLabelScaled(c, label, mPx[i] + kx, mPy[i] + ky, a,
                mLayout != null ? mLayout.textScale[i] : 1f);
        mTextPaint.setTextSize(oldSize);
        mTextHaloPaint.setTextSize(oldSize);
        mTextHaloPaint.setStrokeWidth(oldHalo);
        mRingPaint.setColor(oldRing);
        mStickPaint.setColor(oldStick);
    }

    /**
     * @param i 槽位下标。以前写死 I_DPAD，现在十字键也能建多份，
     *          所以要按传入的下标取位置和透明度。
     */
    /**
     * 是不是"轴类"元素（摇杆 / 十字键 / 扳机）。
     *
     * 分两趟绘制时用来决定层次：轴类在底层，圆按钮在上层。
     */
    private static boolean isAxisType(int type) {
        int proto = PadLayout.protoOfType(type);
        return proto == PadLayout.I_LS || proto == PadLayout.I_RS
                || proto == PadLayout.I_DPAD
                || proto == PadLayout.I_L2 || proto == PadLayout.I_R2;
    }

    /** 手柄元素在画布上的标签（列表里用 nameOf，那里要区分「L2(轨)」「L2(键)」）。 */
    private static String padLabel(int proto) {
        switch (proto) {
            case PadLayout.I_LS:
                return "L";
            case PadLayout.I_RS:
                return "R";
            case PadLayout.I_A:
                return "A";
            case PadLayout.I_B:
                return "B";
            case PadLayout.I_Y:
                return "Y";
            case PadLayout.I_X:
                return "X";
            case PadLayout.I_L1:
                return "L1";
            case PadLayout.I_R1:
                return "R1";
            case PadLayout.I_L2:
            case PadLayout.I_L2B:
                return "L2";
            case PadLayout.I_R2:
            case PadLayout.I_R2B:
                return "R2";
            case PadLayout.I_L3:
                return "L3";
            case PadLayout.I_R3:
                return "R3";
            case PadLayout.I_SEL:
                return "SEL";
            case PadLayout.I_STA:
                return "STA";
            case PadLayout.I_HOME:
                // 折成两行：横排的 "HOME" 有 4 个字母，比按钮直径还宽
                return "HO\nME";
            case PadLayout.I_Z:
                return "Z";
            case PadLayout.I_C:
                return "C";
            default:
                return "";
        }
    }

    /** 按 padType 把一个槽位画成对应的元素。 */
    /**
     * 触摸板：就是一块矩形底板，外加一个中心十字表明"这是拖动区"。
     *
     * 【为什么不画准星】
     *   相对鼠标没有"当前位置"——指针画在哪是 system_server 的事，
     *   App 读不到、也不需要读。画个假准星只会和真箭头打架。
     */
    /**
     * 触摸板就是一块矩形，除了边框和名字不画别的。
     *
     * 【原来那个十字架去掉了】
     *   它本来是想标出"中心点"，但触摸板是拖动区、不是摇杆 ——
     *   没有"回中"这回事，中心点不代表任何状态，
     *   画上去反而像十字键或者瞄准准星，误导人以为要按住中心。
     *
     * 【松手模式下的位移预览】
     *   松手才发时全程光标不动、抬手才跳一大段，
     *   滑的时候根本不知道会跳多远、往哪跳，没法控制力度。
     *   所以在触摸板中间画一支箭头：
     *     方向 = 松手后光标会走的方向
     *     长度 = 会走多远（按手指位移 1:1 画，超出触摸板就顶到边）
     *
     *   它预览的是**位移向量**，不是"光标会落在屏幕哪个点"。
     *   落点画不出来 —— 系统光标位置归系统自己维护，App 读不到，
     *   自己积分去猜的话，越过屏幕边界、被别的输入源动过之后就越偏越远。
     *   而"往哪走、走多远"既是准确的，也正是松手模式真正缺的那条信息。
     */
    /**
     * 触摸板就是一块矩形，除了边框和名字不画别的。
     *
     * 【原来那个十字架去掉了】
     *   它本来是想标出"中心点"，但触摸板是拖动区、不是摇杆 ——
     *   没有"回中"这回事，中心点不代表任何状态，
     *   画上去反而像十字键或者瞄准准星，误导人以为要按住中心。
     *
     * 【松手模式下的落点预览】
     *   松手才发时全程光标不动、抬手才跳一大段，
     *   滑的时候根本不知道会跳多远、往哪跳，没法控制力度。
     *
     *   所以以**手指按下的位置**为原点，把累计偏移**原样**画成一个点，
     *   再从原点连一条线过去：
     *     接到 X+8、Y+9   -> 点在起点右 8px、下 9px
     *     再接 X+10       -> 点移到起点右 18px、下 9px
     *
     * 【为什么原点用手指起点而不是触摸板中心】
     *   手指在板子最左边按下，线就从最左边长出去 ——
     *   画出来的那一段和手指真正划过的那一段是重合的，
     *   目光不用在"手指"和"板中心"之间来回跳，拖起来更跟手。
     *
     * 【为什么不夹在触摸板里】
     *   夹取之后点会顶在边界不动，手指继续划就看不出变化了 ——
     *   恰恰是最需要反馈的长距离滑动没了反馈。
     *   所以不 clamp：手指划出多远，点就跑多远，
     *   哪怕画到别的按键上面。看到的就是手指的真实行程。
     *   点跑出去了也说明"该松手了"，本身就是个提示。
     *
     * 【预览的是位移，不是光标落点】
     *   画不出"光标会停在屏幕哪个点" —— 系统光标的位置归系统自己维护，
     *   App 读不到；自己积分去猜的话，光标顶到屏幕边界、
     *   或被别的输入源动过之后就越偏越远。
     *   而"手指走了多远"既是准确的，也正是松手模式真正缺的那条信息。
     */
    private void drawMousePad(Canvas c, int i) {
        // 原名字（用户改过名就用改过的）+ 提示行。
        // forceLabel=true 是必须的：不强制的话 customName 会把整串顶掉。
        drawBtn(c, i, mLayout.displayName(i, "触摸板") + "\n双击切模式", true);
        if (!sMouseDefer || !shouldDraw(i) || mDown[i] != 1) return;
        // 预览开关（设置里那个，默认关）。关了就不画，鼠标照常能拖。
        if (!mousePreviewOn(getContext())) return;
        float ax = mMouseAccX;
        float ay = mMouseAccY;
        if (ax == 0f && ay == 0f) return;
        // acc 是乘过灵敏度的光标位移，除回去就是手指实际划过的距离。
        float fx = ax / MOUSE_SENS;
        float fy = ay / MOUSE_SENS;
        float len = (float) Math.hypot(fx, fy);
        if (len < 1f) return;

        // 原点 = 手指按下的位置（不是触摸板中心）
        float cx = mMouseOriginX;
        float cy = mMouseOriginY;
        // 【不夹取】点画在 起点 + 手指偏移，超出触摸板范围照画。
        float ex = cx + fx;
        float ey = cy + fy;
        // 点要压在半透明的触摸板上还能看清，所以比元素本身实一档
        float pa = Math.min(1f, effAlpha(i) + 0.3f);

        int oldColor = mRingPaint.getColor();
        float oldW = mRingPaint.getStrokeWidth();
        try {
            setA(mRingPaint, pa);
            // 中心 -> 点 的连线：点跑远了也能看出是从哪来的
            mRingPaint.setStrokeWidth(Math.max(2.0f, oldW * 1.2f));
            c.drawLine(cx, cy, ex, ey, mRingPaint);
            // 点本身：实心圆，比线粗，好认
            float dotR = Math.max(4f, oldW * 2.2f);
            c.drawCircle(ex, ey, dotR, mRingPaint);
            resetA(mRingPaint);
        } finally {
            mRingPaint.setStrokeWidth(oldW);
            mRingPaint.setColor(oldColor);
        }

        // Δ 数值：跟着点走，给确切会发出的光标像素位移
        float oldSize = mTextPaint.getTextSize();
        Paint.Align oldAlign = mTextPaint.getTextAlign();
        try {
            mTextPaint.setTextSize(oldSize * 0.66f);
            mTextPaint.setTextAlign(Paint.Align.CENTER);
            setA(mTextPaint, pa);
            // 沿位移方向再让开一点，免得压在点上
            c.drawText("Δ" + Math.round(ax) + "," + Math.round(ay),
                    ex + (fx / len) * 16f,
                    ey + (fy / len) * 16f, mTextPaint);
            resetA(mTextPaint);
        } finally {
            mTextPaint.setTextSize(oldSize);
            mTextPaint.setTextAlign(oldAlign);
        }
    }

    private void drawElem(Canvas c, int i) {
        int proto = PadLayout.protoOfType(mLayout.padType[i]);
        switch (proto) {
            case PadLayout.I_LS:
                // 多份左摇杆**共用同一组轴**，帽子也共用同一份偏移：
                // HID 报告里左摇杆只有一组 X/Y，建两份不会多出轴来，
                // 所以显示成"一起动"才是诚实的（否则看着在动其实没输出）。
                drawStick(c, i, mLsKx, mLsKy, "L");
                break;
            case PadLayout.I_RS:
                drawStick(c, i, mRsKx, mRsKy, "R");
                break;
            case PadLayout.I_DPAD:
                drawDpad(c, i);
                break;
            case PadLayout.I_L2:
                drawTrigger(c, i, "L2");
                break;
            case PadLayout.I_R2:
                drawTrigger(c, i, "R2");
                break;
            case PadLayout.I_MOUSE_PAD:
                drawMousePad(c, i);
                break;
            case PadLayout.I_MOUSE_L:
                drawBtn(c, i, "左键");
                break;
            case PadLayout.I_MOUSE_R:
                drawBtn(c, i, "右键");
                break;
            case PadLayout.I_MOUSE_M:
                drawBtn(c, i, "中键");
                break;
            case PadLayout.I_MOUSE_WU:
                drawBtn(c, i, "滚轮上");
                break;
            case PadLayout.I_MOUSE_WD:
                drawBtn(c, i, "滚轮下");
                break;
            default:
                drawBtn(c, i, padLabel(proto));
                break;
        }
    }

    private void drawDpad(Canvas c, int i) {
        drawDpad(c, i, -1, true);
    }

    /**
     * @param armsMask  >=0 = 按位点亮指定的臂（bit0上 bit1右 bit2下 bit3左）；
     *                  -1  = 读全局 mHat（真·方向键走这条）
     * @param showLabel 是否画环外那个「方向」标签。
     *                  十字架组合键要画自己的名字，再画「方向」就叠成一团了。
     */
    private void drawDpad(Canvas c, int i, int armsMask, boolean showLabel) {
        if (!shouldDraw(i)) return;
        float a = effAlpha(i);
        float r = radiusOf(i);
        float cx = mPx[i];
        float cy = mPy[i];
        // 半透明时改用**离屏图层**。
        //
        // 十字键是「1 个圆 + 4 条臂」拼的。若每块各自乘 alpha：
        //   - 臂与臂之间的缝隙、以及重叠处会各自变淡再叠加，
        //     能看出内部结构，不像一个整体在变淡
        //   - 中心还留着洞（臂内端只到 inner），半透明时像四根分离的小条
        // 改成：先把整块按**不透明**画进图层，再把图层一次性按 alpha 合成。
        // 这样"整体变淡"，观感和别的一体式按键一致。
        //
        // alpha 本来就是 1 时不建图层，省一次离屏缓冲（60fps 下没必要白花）。
        boolean layered = a < 0.999f;
        int save = 0;
        if (layered) {
            int ai = Math.round(255f * a);
            if (ai < 1) ai = 1;
            save = c.saveLayerAlpha(cx - r, cy - r, cx + r, cy + r,
                    ai, Canvas.ALL_SAVE_FLAG);
        }
        float bodyA = layered ? 1f : a;

        // 算这一帧要点亮哪几条臂：十字架用自己的位掩码，方向键用全局 hat
        int arms;
        if (armsMask >= 0) {
            arms = armsMask;
        } else if (mHat != 8) {
            arms = 0;
            for (int d = 0; d < 4; d++) {
                int armv = d * 2;                  // 0=上 2=右 4=下 6=左
                if (mHat == armv || mHat == (armv + 1) % 8 || mHat == (armv + 7) % 8) {
                    arms |= (1 << d);
                }
            }
        } else {
            arms = 0;
        }
        // 【圆底永远用常态色】
        //   之前按下时把整个圆换成按下色，结果"背景"整块亮起来 ——
        //   十字键的手感是"哪条臂被按了就亮哪条"，整块底变亮反而像
        //   点在了背景板上，看不出按的是哪边。
        //   死区（正中间）的反馈交给下面那个中心轴变亮，不去动整块底。
        // 【十字键也吃自定义底色】和摇杆同理：
        //   整块底用本色、臂用提亮版，底和臂才有层次；
        //   点亮的臂（mArmOnPaint）不动，那是"按下了"的反馈。
        int dcc = mLayout.btnColor[i];
        int oldDRing = mRingPaint.getColor();
        int oldArm = mArmPaint.getColor();
        if (dcc != 0) {
            mRingPaint.setColor(dcc);
            mArmPaint.setColor(0xFF000000 | (lighten(dcc) & 0x00FFFFFF));
        }
        setA(mRingPaint, bodyA);
        c.drawCircle(cx, cy, r, mRingPaint);

        // 画成真正的十字：四条臂，点亮的臂就是实际触发的方向（斜向时点亮相邻两条）
        float half = r * 0.30f;
        float inner = r * 0.10f;
        float outer = r * 0.86f;

        // 整个十字用**一条 Path** 一次画完（12 个顶点的"+"形）。
        //
        // 原来是 4 个 roundRect 各自填充 + 各自描边，问题有三个：
        //   1) 臂内端只到 inner，中心是个洞 —— 半透明时像四根分离的条
        //   2) 相邻臂的描边在拐角处互相压，能看出拼缝
        //   3) 每块各乘一次 alpha，重叠处会叠加变深
        // 改成单 Path 后：填充一次、描边一次，边界干净、没有内部接缝。
        if (mDpadPath == null) {
            mDpadPath = new Path();
        }
        mDpadPath.rewind();
        // 从"上臂左上角"起，顺时针绕一圈
        mDpadPath.moveTo(cx - half, cy - outer);   // 上臂 左上
        mDpadPath.lineTo(cx + half, cy - outer);   // 上臂 右上
        mDpadPath.lineTo(cx + half, cy - half);    // 进 右上凹角
        mDpadPath.lineTo(cx + outer, cy - half);   // 右臂 右上
        mDpadPath.lineTo(cx + outer, cy + half);   // 右臂 右下
        mDpadPath.lineTo(cx + half, cy + half);    // 进 右下凹角
        mDpadPath.lineTo(cx + half, cy + outer);   // 下臂 右下
        mDpadPath.lineTo(cx - half, cy + outer);   // 下臂 左下
        mDpadPath.lineTo(cx - half, cy + half);    // 进 左下凹角
        mDpadPath.lineTo(cx - outer, cy + half);   // 左臂 左下
        mDpadPath.lineTo(cx - outer, cy - half);   // 左臂 左上
        mDpadPath.lineTo(cx - half, cy - half);    // 进 左上凹角
        mDpadPath.close();

        setA(mArmPaint, bodyA);
        c.drawPath(mDpadPath, mArmPaint);
        resetA(mArmPaint);

        // 点亮的臂：只在十字形状内部画，所以先 clip 到 Path。
        // 不 clip 的话高亮块会溢出到凹角外面，那块就变成"多出来的白色方块"。
        if (arms != 0) {
            int sc2 = c.save();
            c.clipPath(mDpadPath);
            for (int d = 0; d < 4; d++) {
                if ((arms & (1 << d)) == 0) {
                    continue;
                }
                // 用 d 直接决定横竖，不要用方向向量的浮点值判断：
                // sin(180°) 在浮点下是 1.22e-16 而不是 0，"下"会被误判成横向画到右边去。
                switch (d) {
                    case 0:                        // 上
                        mTmpRect.set(cx - half, cy - outer, cx + half, cy);
                        break;
                    case 1:                        // 右
                        mTmpRect.set(cx, cy - half, cx + outer, cy + half);
                        break;
                    case 2:                        // 下
                        mTmpRect.set(cx - half, cy, cx + half, cy + outer);
                        break;
                    default:                       // 左
                        mTmpRect.set(cx - outer, cy - half, cx, cy + half);
                        break;
                }
                setA(mArmOnPaint, bodyA);
                c.drawRect(mTmpRect, mArmOnPaint);
                resetA(mArmOnPaint);
            }
            c.restoreToCount(sc2);
        }

        // 描边：臂比整个键小得多（half≈0.3r），
        // 用环那套 5f 会把细臂整个盖住，按臂宽来算。
        mOutlinePaint.setStrokeWidth(Math.max(1.5f, half * 0.18f));
        setA(mOutlinePaint, bodyA);
        c.drawPath(mDpadPath, mOutlinePaint);
        resetA(mOutlinePaint);

        // 中心轴：常态色，永远不亮。
        //
        //   之前按十字架时它跟着变色 —— 十字架按下会置 mDown=1，
        //   而真·方向键不置（它只改 mHat），于是"手柄方向键正常、
        //   组合键十字架中心多亮一个圈"。同一个 drawDpad 被两类元素共用，
        //   判据却只在一类上成立，就是这个差异。
        //
        //   反馈只保留"点亮被按的那条臂"：中心亮反而像是按在了背景板上。
        setA(mStickPaint, a);
        c.drawCircle(cx, cy, r * 0.20f, mStickPaint);
        resetA(mStickPaint);
        resetA(mRingPaint);

        if (layered) {
            c.restoreToCount(save);
        }
        mRingPaint.setColor(oldDRing);
        mArmPaint.setColor(oldArm);

        // 环外写个「方向」，免得把它当成第三个摇杆。
        // 十字架组合键不写 —— 那个位置要留给自己的名字。
        if (!showLabel) {
            return;
        }
        float oldSize = mTextPaint.getTextSize();
        float oldHalo = mTextHaloPaint.getStrokeWidth();
        // 基础字号已经比别的小（0.30r），再乘 textScale 才能被滑条调到
        float ts = r * 0.30f * (mLayout != null ? mLayout.textScale[i] : 1f);
        mTextPaint.setTextSize(ts);
        mTextHaloPaint.setTextSize(ts);
        mTextHaloPaint.setStrokeWidth(Math.max(1.5f, ts * 0.16f));
        drawLabel(c, "方向", cx, cy + r + r * 0.34f, a);
        mTextPaint.setTextSize(oldSize);
        mTextHaloPaint.setTextSize(oldSize);
        mTextHaloPaint.setStrokeWidth(oldHalo);
    }

    /**
     * 画空白按钮。
     *
     * 和其他按键同一个底（drawButtonBody / drawButtonOutline），
     * 区别只有一个：**不画标签**。它不映射任何手柄键 / 键盘键，
     * 摆个名字在上面反而像是漏了什么。
     */
    /**
     * 画组合键。
     *
     * 底和描边走通用的 drawButtonBody / drawButtonOutline，
     * 标签是它自己的名字（可能是自动拼的 "A+B"）。
     */
    private void drawComboBtn(Canvas c, int i) {
        if (!shouldDraw(i)) return;
        //
        // 【十字架组合键：样子就是十字键，名字挪到下方】
        //   和方向键一致的画法：drawDpad 画十字，
        //   名字画在圆下方（drawDpad 内部给方向键也是这个位置）。
        if (mLayout.comboCross[i]) {
            drawDpad(c, i, mCrossArms[i], false);
            //
            // 【名字的字号 / 间距要和方向键那两字一致】
            //   方向键的「方向」是 r * 0.30（相对半径现算），
            //   而这里走 drawLabelScaled 用的是全局基础字号 ——
            //   十字本体更小、字号却更大，还贴得更近。
            //   改成和 drawDpad 内部完全同一套算法。
            float r = radiusOf(i);
            float a = mLayout.alpha[i];
            float oldSize = mTextPaint.getTextSize();
            float oldHalo = mTextHaloPaint.getStrokeWidth();
            float ts = r * 0.30f * mLayout.textScale[i];
            mTextPaint.setTextSize(ts);
            mTextHaloPaint.setTextSize(ts);
            mTextHaloPaint.setStrokeWidth(Math.max(1.5f, ts * 0.16f));
            if (mLayout.labelOn(i)) {
                drawLabel(c, mLayout.displayName(i, mLayout.comboName[i]),
                        mPx[i], mPy[i] + r + r * 0.34f, a);
            }
            mTextPaint.setTextSize(oldSize);
            mTextHaloPaint.setTextSize(oldSize);
            mTextHaloPaint.setStrokeWidth(oldHalo);
            return;
        }
        float a = mLayout.alpha[i];
        setA(mBtnPaint, a);
        setA(mBtnOnPaint, a);
        setA(mRingPaint, a);
        drawButtonBody(c, i, mDown[i] == 1 ? mBtnOnPaint : mBtnPaint);
        drawButtonOutline(c, i, mRingPaint);
        if (mLayout.labelOn(i)) {
            drawLabelScaled(c, mLayout.displayName(i, mLayout.comboName[i]),
                    mPx[i], mPy[i], a, mLayout.textScale[i]);
        }
        resetA(mBtnPaint);
        resetA(mBtnOnPaint);
        resetA(mRingPaint);
    }

    private void drawBlankBtn(Canvas c, int i) {
        if (!shouldDraw(i)) return;
        float a = mLayout.alpha[i];
        setA(mBtnPaint, a);
        setA(mBtnOnPaint, a);
        setA(mRingPaint, a);
        drawButtonBody(c, i, mDown[i] == 1 ? mBtnOnPaint : mBtnPaint);
        drawButtonOutline(c, i, mRingPaint);
        resetA(mBtnPaint);
        resetA(mBtnOnPaint);
        resetA(mRingPaint);
        // 空白按钮默认不画字（它就是个占位块），但**改了名就要画** ——
        // 悬浮窗布局那块提示牌就靠这个把说明文字显示出来。
        String nm = (mLayout.customName[i] == null) ? "" : mLayout.customName[i].trim();
        if (nm.length() > 0 && mLayout.labelOn(i)) {
            float avail = halfW(i) * 2f - dp(10f);
            float tw = mTextPaint.measureText(nm);
            // 字太长就缩到正好装得下：提示牌是正方形，14 个字按默认字号
            // 会顶出框外一大截。
            float mul = (tw > avail && tw > 0f) ? (avail / tw) : 1f;
            drawLabelScaled(c, nm, mPx[i], mPy[i], a, mul * mLayout.textScale[i]);
        }
    }

    private void drawBtn(Canvas c, int i, String label) {
        drawBtn(c, i, label, false);
    }

    /**
     * @param forceLabel true = 直接用 label，不再让 customName 顶掉。
     *
     * 【为什么需要它】
     *   displayName(i, label) 的规则是"customName 非空就返回 customName"，
     *   整串 label 被扔掉。而 resetMouse() 给触摸板设了
     *   customName = "触摸板" —— 于是 drawMousePad 拼的
     *   "触摸板\n双击切模式" **从来没显示过**，只显示"触摸板"。
     *   要在原名后面接提示行，就必须绕过那条覆盖规则。
     */
    private void drawBtn(Canvas c, int i, String label, boolean forceLabel) {
        if (!shouldDraw(i)) return;
        float a = effAlpha(i);
        float r = radiusOf(i);
        int oldBtn = mBtnPaint.getColor();
        int oldOn = mBtnOnPaint.getColor();
        applyBtnColor(i);
        setA(mBtnPaint, a);
        setA(mBtnOnPaint, a);
        setA(mRingPaint, a);
        drawButtonBody(c, i, mDown[i] == 1 ? mBtnOnPaint : mBtnPaint);
        drawButtonOutline(c, i, mRingPaint);
        if (mLayout.labelOn(i)) {
            drawLabelScaled(c, forceLabel ? label : mLayout.displayName(i, label),
                    mPx[i], mPy[i], a, mLayout.textScale[i]);
        }
        resetA(mBtnPaint);
        resetA(mBtnOnPaint);
        resetA(mRingPaint);
        mBtnPaint.setColor(oldBtn);
        mBtnOnPaint.setColor(oldOn);
    }

    /**
     * 把自定义底色套到常态 / 按下两支画笔上。
     *
     * 【按下色不能直接沿用常态色】
     *   常态色是半透明的（0x66FFFFFF），按下去若还是它，
     *   手指压着和没压几乎没差别 —— 按下的反馈就没了。
     *   所以在自定义色基础上提亮再压成不透明，当作按下色。
     *
     * @param i 元素下标。btnColor[i] == 0 表示没设过，不动画笔。
     */
    private void applyBtnColor(int i) {
        int cc = mLayout.btnColor[i];
        if (cc == 0) {
            return;
        }
        mBtnPaint.setColor(cc);
        mBtnOnPaint.setColor(0xFF000000 | (lighten(cc) & 0x00FFFFFF));
    }

    /** 把颜色提亮一点（按下态用）。 */
    private static int lighten(int color) {
        int rr = (color >> 16) & 0xFF;
        int gg = (color >> 8) & 0xFF;
        int bb = color & 0xFF;
        rr = Math.min(255, rr + (255 - rr) / 2);
        gg = Math.min(255, gg + (255 - gg) / 2);
        bb = Math.min(255, bb + (255 - bb) / 2);
        return (rr << 16) | (gg << 8) | bb;
    }

    /**
     * 这个元素现在该不该画出来。
     *
     * 隐藏的按钮只在非编辑模式下消失；编辑模式下照画（只是打叉），
     * 否则你连它都找不到，更别说恢复显示了。
     */
    private boolean shouldDraw(int i) {
        /*
          【G 和「收」是互斥的，各自只在自己那个布局里存在】

          G 的位置恒等于「收」（见 computeGeometry）。若两边都画：
            - 别的布局里：G 叠在「收」上，编辑模式下还各带一个叉，
              看着像凭空多出一个按钮
            - 悬浮窗布局里：反过来是「收」叠在 G 上
          所以各自按 layoutId 判掉，连编辑模式也不画 ——
          编辑模式放行隐藏元素是为了"能把它放出来"，
          而这两个压根不属于当前布局，放出来也没有意义。
        */
        if (i == PadLayout.I_FLOAT && !isFloatLayout()) {
            return false;
        }
        if (i == PadLayout.I_COLLAPSE && isFloatLayout()) {
            return false;
        }
        // 没创建的键盘槽位：连编辑模式也不画。
        // 它不是"隐藏"（隐藏的还要打叉让你能找回来），而是根本不存在。
        if (PadLayout.isKeySlot(i) && !mLayout.isKeySlotUsed(i)) {
            return false;
        }
        // 同上：删掉的手柄槽位不画（它不是隐藏，是压根没有）
        //
        // 【必须排除界面按钮】编 / 收 / 布 的下标（14/13/132）里，
        // 前两个也在 i < N_FIXED 范围内，isPadSlot 判定为 true；
        // 而它们的 padType 恒为 0（不是手柄元素），于是被当成"空槽位"
        // 直接 return false —— 屏幕上就再也看不到「编」和「收」，
        // 既进不了编辑模式也收不起悬浮窗，等于把 app 锁死。
        if (PadLayout.isPadSlot(i) && !PadLayout.isUiButton(i)
                && !mLayout.isPadUsed(i)) {
            return false;
        }
        // 同上：没建的空白按钮槽位不画。
        // 【建了就在】空白按钮本来就不装任何键——它是先建出来、
        // 之后自己加键的东西。只建了壳就挡掉的话，画布上只剩选中框，
        // 看着就是一个"空气被框住"。
        if (PadLayout.isBlankSlot(i) && !mLayout.isBlankUsed(i)) {
            return false;
        }
        // 同上：没建的组合键槽位不画
        if (PadLayout.isComboSlot(i) && !mLayout.isComboUsed(i)) {
            return false;
        }
        // 鼠标三件套只在鼠标布局里存在（padType 由 resetMouse 设上）
        if (PadLayout.isMouseSlot(i) && !mLayout.isMouseUsed(i)) {
            return false;
        }
        return mEditMode || !mLayout.hidden[i];
    }

    /** 编辑模式下给隐藏的按钮打红叉。 */
    private void drawHiddenMarks(Canvas c) {
        for (int i = 0; i < PadLayout.N; i++) {
            if (!mLayout.hidden[i]) {
                continue;
            }
            // 【界面按钮一律交给 drawHiddenMarksForUi 处理】
            //   这里只管游戏按键。
            //   不拦住的话：悬浮窗布局里「收」是隐藏的（被 G 顶掉），
            //   而 G 就在「收」的位置 —— 于是「收」的叉画在了 G 上，
            //   变成"G 明明显示着，屁股后面却挂个红叉"。
            if (PadLayout.isUiButton(i)) {
                continue;
            }
            drawHiddenCross(c, i);
        }
        mHiddenPaint.setAlpha(255);
    }

    /**
     * 界面按钮（编 / 布 / 收）的叉要**后画**。
     *
     * 它们画在主循环之后（图层高于游戏按键），
     * 而 drawHiddenMarks 在主循环里 —— 于是按钮把叉整个盖住了，
     * 编辑模式下看不出这三个被隐藏。
     *
     * 单独抽出来，在 drawUiButtons() 之后再补一遍。
     */
    private void drawHiddenMarksForUi(Canvas c) {
        int[] ui = {PadLayout.I_EDIT, PadLayout.I_LAYOUT, PadLayout.I_COLLAPSE,
                PadLayout.I_PASS, PadLayout.I_FLOAT};
        for (int k = 0; k < ui.length; k++) {
            int i = ui[k];
            // 只对"这个布局里存在的界面按钮"打叉：
            //   - G 只在悬浮窗布局存在，别的布局里它是恒隐藏的
            //   - 「收」反过来在悬浮窗布局里被 G 顶掉
            // 不判的话编辑模式下「收」上面会莫名多一个叉。
            if (i == PadLayout.I_FLOAT && !isFloatLayout()) {
                continue;
            }
            if (i == PadLayout.I_COLLAPSE && isFloatLayout()) {
                continue;
            }
            if (mLayout.hidden[i]) {
                drawHiddenCross(c, i);
            }
        }
        mHiddenPaint.setAlpha(255);
    }

    private void drawHiddenCross(Canvas c, int i) {
        float r = radiusOf(i);
        float hy = isTrigger(i) ? trackHalfH(i) : r;
        float l = mPx[i] - r * 0.72f;
        float t = mPy[i] - hy * 0.72f;
        float rt = mPx[i] + r * 0.72f;
        float b = mPy[i] + hy * 0.72f;
        mHiddenPaint.setAlpha((int) (mLayout.alpha[i] * 255));
        c.drawLine(l, t, rt, b, mHiddenPaint);
        c.drawLine(rt, t, l, b, mHiddenPaint);
    }

    /**
     * 扳机（L2 / R2）：竖直胶囊形滑轨 + 里面的圆形滑块。
     *
     *   滑块停在底部 = 完全松开；滑到顶部 = 扣到底。
     *   轨道下半段会用蓝色填充，直观表示当前扣下去多少。
     *
     * 标签画在滑块上跟着走 —— 滑块才是真正要点的东西。
     */
    private void drawTrigger(Canvas c, int i, String label) {
        if (!shouldDraw(i)) return;
        float a = effAlpha(i);
        float r = radiusOf(i);
        float half = trackHalfH(i);
        float cx = mPx[i];
        float top = mPy[i] - half;
        float bottom = mPy[i] + half;

        // 轨道：圆角 = 半径，画出来是个胶囊
        setA(mSlTrackPaint, a);
        mTmpRect.set(cx - r, top, cx + r, bottom);
        c.drawRoundRect(mTmpRect, r, r, mSlTrackPaint);
        resetA(mSlTrackPaint);

        float t = mTrigVal[i];
        float ky = knobY(i, t);

        // 已扣下去的那一段：从轨道底填到滑块中心
        if (t > 0f) {
            setA(mSlFillPaint, a);
            mTmpRect.set(cx - r, ky, cx + r, bottom);
            c.drawRoundRect(mTmpRect, r, r, mSlFillPaint);
            resetA(mSlFillPaint);
        }

        // 滑块：扣下去时用高亮色，松手回到底部
        setA(mBtnPaint, a);
        setA(mBtnOnPaint, a);
        setA(mRingPaint, a);
        c.drawCircle(cx, ky, r, t > 0f ? mBtnOnPaint : mBtnPaint);
        c.drawCircle(cx, ky, r, mRingPaint);
        // 同摇杆：接上 textScale，L2/R2 的字号才能调
        drawLabelScaled(c, label, cx, ky, a,
                mLayout != null ? mLayout.textScale[i] : 1f);
        resetA(mBtnPaint);
        resetA(mBtnOnPaint);
        resetA(mRingPaint);
    }

    private void drawRound(Canvas c, int i, String label, Paint base) {
        if (!shouldDraw(i)) return;
        float a = effAlpha(i);
        float r = radiusOf(i);
        // 【界面按钮也能改底色】
        //   透（I_PASS）例外：它有两支状态色，走 passOn/OffColor，
        //   在这里被 btnColor 覆盖就永远只剩一种颜色了。
        int rc = (i == PadLayout.I_PASS) ? 0 : mLayout.btnColor[i];
        int oldBase = base.getColor();
        if (rc != 0) {
            base.setColor(rc);
        }
        setA(base, a);
        setA(mRingPaint, a);
        if (mDown[i] == 1) {
            setA(mBtnOnPaint, a);
            drawButtonBody(c, i, mBtnOnPaint);
            resetA(mBtnOnPaint);
        } else {
            drawButtonBody(c, i, base);
        }
        // 描边也得跟着形状走，否则方形上套个圆圈
        drawButtonOutline(c, i, mRingPaint);
        // 【G 用白字】
        //   它底子是自定义蓝，深色字（0xFF212121）在蓝底上对比不够。
        //   白字 + 描边在深浅底上都清楚。
        int oldTxt = mTextPaint.getColor();
        if (mLayout.isFloatBall(i)) {
            mTextPaint.setColor(0xFFFFFFFF);
        }
        drawLabelScaled(c, label, mPx[i], mPy[i], a, mLayout.textScale[i]);
        mTextPaint.setColor(oldTxt);
        resetA(base);
        resetA(mRingPaint);
        base.setColor(oldBase);
    }

    /** 深色字 + 一圈浅色描边，压在任何游戏画面上都读得清。 */
    private void drawLabel(Canvas c, String text, float x, float y, float alpha) {
        drawLabelScaled(c, text, x, y, alpha, 1f);
    }

    /**
     * 带字号倍率的那一份。
     *
     * 原来标签字号固定是 mBtnR * 0.85f，跟按钮的 scale 无关。
     * 键盘模式下整片键缩到 0.3 倍，字却还是原尺寸 —— 直接糊成一团。
     * 所以按元素的 textScale 再乘一次。
     */
    private void drawLabelScaled(Canvas c, String text, float x, float y,
                                 float alpha, float mul) {
        float oldSize = mTextPaint.getTextSize();
        float oldHalo = mTextHaloPaint.getStrokeWidth();
        if (mul != 1f) {
            float ns = oldSize * mul;
            mTextPaint.setTextSize(ns);
            mTextHaloPaint.setTextSize(ns);
            mTextHaloPaint.setStrokeWidth(Math.max(1.0f, ns * 0.16f));
        }
        try {
            if (text.indexOf('\n') < 0) {
                setA(mTextPaint, alpha);
                setA(mTextHaloPaint, alpha);
                drawTextWithHalo(c, text, x, y, mTextPaint, mTextHaloPaint);
                resetA(mTextPaint);
                resetA(mTextHaloPaint);
                return;
            }
            drawLabelLines(c, text, x, y, alpha);
        } finally {
            // 【无条件还原】
            // 之前这里写成 if (mul != 1f) 才还原，而 drawLabelLines()
            // （多行标签，比如 HOME 的 "HO\nME"）会**再**把字号乘一次 0.725
            // 却不再自己还原 —— mul 恰好是 1f 时这 0.725 就永久留下了。
            // mTextPaint 是所有按钮共用的一支，于是每画一帧全局字号 ×0.725，
            // 表现就是"所有按键的字一直在缩小"。
            mTextPaint.setTextSize(oldSize);
            mTextHaloPaint.setTextSize(oldSize);
            mTextHaloPaint.setStrokeWidth(oldHalo);
        }
    }

    private void drawLabelLines(Canvas c, String text, float x, float y, float alpha) {
        // 多行标签（目前只有 HOME 用）：整体缩到按钮里。
        //
        // 4 个字母的 "HOME" 横排要占约 2.04r，而按钮直径只有 2r —— 必然出界。
        // 折成两行后，瓶颈变成高度：每行实际高约 1.17 * 字号，
        // 两行就是 2.34 * 字号，所以字号得压到 0.85r 的七成左右才装得下。
        //
        // 1.45f / 行数 这个系数是让"总高 ≈ 1.44r"对任意行数都成立：
        // 两行时每行 0.62r，三行时每行 0.41r，都占直径的七成出头，
        // 既不出界也不至于小得看不清。
        String[] lines = text.split("\n", -1);
        float oldSize = mTextPaint.getTextSize();
        float oldHalo = mTextHaloPaint.getStrokeWidth();
        float size = oldSize * (1.45f / lines.length);
        mTextPaint.setTextSize(size);
        mTextHaloPaint.setTextSize(size);
        mTextHaloPaint.setStrokeWidth(Math.max(1.5f, size * 0.18f));

        Paint.FontMetrics fm = mTextPaint.getFontMetrics();
        float lineH = (fm.descent - fm.ascent) * 1.06f;   // 行间留一点缝，别粘住
        float top = y - lineH * lines.length / 2f;        // 整体垂直居中在 y
        setA(mTextPaint, alpha);
        setA(mTextHaloPaint, alpha);
        for (int i = 0; i < lines.length; i++) {
            drawTextWithHalo(c, lines[i], x, top + lineH * (i + 0.5f),
                    mTextPaint, mTextHaloPaint);
        }
        resetA(mTextPaint);
        resetA(mTextHaloPaint);
        // 自己也还原一份（双重保险）：这个函数可能被 drawLabel 直接调用，
        // 那条路径上 mul 恒为 1f，光靠外层还原不够。
        mTextPaint.setTextSize(oldSize);
        mTextHaloPaint.setTextSize(oldSize);
        mTextHaloPaint.setStrokeWidth(oldHalo);
    }

    private static void drawTextWithHalo(Canvas c, String text, float x, float y,
                                         Paint fill, Paint halo) {
        Paint.FontMetrics fm = fill.getFontMetrics();
        float base = y - (fm.ascent + fm.descent) / 2f;
        c.drawText(text, x, base, halo);
        c.drawText(text, x, base, fill);
    }

    /**
     * 摇杆设置里拖「范围」时的预览圈。
     *
     * 半径 = 摇杆半径 × 范围倍率，和 onDown 里的命中判定用的是同一个值 ——
     * 看到的圈就是真正的热区，不会出现"看着这么大、按下去没反应"。
     *
     * 只在**关掉固定摇杆**时画：固定模式下这个范围根本不参与判定，
     * 画出来会让人以为它有用。
     */
    private void drawStickRange(Canvas c) {
        if (!mStickCfg || mLayout == null || !selHasStick()) {
            return;
        }
        // 集合里每个"关掉固定"的摇杆都画 —— 多选时能一眼看出
        // 这批摇杆各自的范围，而不是只看到当前那一个。
        for (int i = 0; i < PadLayout.N; i++) {
            boolean in = isMulti() ? mSelSet[i] : (i == mSel);
            if (!in || !isFloatStick(i)) {
                continue;
            }
            float r = radiusOf(i) * mLayout.stickRange[i];
            mRangePaint.setStrokeWidth(Math.max(dp(2f), r * 0.03f));
            c.drawCircle(mPx[i], mPy[i], r, mRangePaint);
        }
    }


    // ------------------------------------------------------------------
    // 编辑面板
    // ------------------------------------------------------------------

    private void drawEditPanel(Canvas c) {
        // 【守卫】mSel 指向的元素可能已经不存在（刚删掉之后
        //   mSel 还指着那个槽位）。面板会去读 mLayout.xxx[mSel] 画滑条，
        //   画出来的就是一个"没有按钮却开着面板"的悬空状态。
        if (mSel != NONE && !pickable(mSel)) {
            clearSelection();
        }
        // 和触摸端一样按当前模式重排一次，保证"画的位置" == "点的位置"。
        layoutTools();
        if (mPanelState == PANEL_MIN) {
            // 最小档：不画面板背景、不画文字，只在右下角留一个加号，
            // 样式和右上角那个「+」一模一样（同样的圆角矩形、同样的字号）。
            // 屏幕下方整块让给游戏按键，看起来跟没有面板一样。
            c.drawRoundRect(mPanelFabRect, dp(6f), dp(6f), mToolBtnPaint);
            mBarTextPaint.setTextAlign(Paint.Align.CENTER);
            mBarTextPaint.setTextSize(mPanelFabRect.height() * 0.66f);
            mBarTextPaint.setColor(0xFF212121);
            Paint.FontMetrics ff = mBarTextPaint.getFontMetrics();
            c.drawText("+", mPanelFabRect.centerX(),
                    mPanelFabRect.centerY() - (ff.ascent + ff.descent) / 2f, mBarTextPaint);
            return;
        }

        // 画到 mPanelBottom 就停，底部留出的那段不再盖住，
        // 这样能明显看出整块面板是抬起来的，不是贴着屏幕底边。
        float side = dp(8f);
        float rad = dp(14f);
        mTmpRect.set(-side, mPanelTop - rad, (float) mW + side, mPanelBottom);
        c.drawRoundRect(mTmpRect, rad, rad, mPanelPaint);

        // 右上角：展开时是「−」（收成窄边），收起时是「+」（恢复最大）
        c.drawRoundRect(mPanelToggleRect, dp(6f), dp(6f), mToolBtnPaint);
        mBarTextPaint.setTextAlign(Paint.Align.CENTER);
        mBarTextPaint.setTextSize(mPanelToggleRect.height() * 0.66f);
        mBarTextPaint.setColor(0xFF212121);
        Paint.FontMetrics tf = mBarTextPaint.getFontMetrics();
        c.drawText(mPanelState == PANEL_FULL ? "−" : "+", mPanelToggleRect.centerX(),
                mPanelToggleRect.centerY() - (tf.ascent + tf.descent) / 2f,
                mBarTextPaint);

        if (mPanelState == PANEL_BAR) {
            // 收起态：一条窄边，底部按键全部露出来。
            // 「v」再收小一档（只剩右下角加号），「+」恢复最大。
            c.drawRoundRect(mPanelMinRect, dp(6f), dp(6f), mToolBtnPaint);
            Paint.FontMetrics vf = mBarTextPaint.getFontMetrics();
            c.drawText("v", mPanelMinRect.centerX(),
                    mPanelMinRect.centerY() - (vf.ascent + vf.descent) / 2f, mBarTextPaint);

            mBarTextPaint.setTextAlign(Paint.Align.LEFT);
            mBarTextPaint.setTextSize(mPanelH * 0.24f);
            mBarTextPaint.setColor(0xFFFFFFFF);
            Paint.FontMetrics cf = mBarTextPaint.getFontMetrics();
            c.drawText("点 + 展开调节，点 v 只留加号", dp(14f),
                    mPanelToggleRect.centerY() - (cf.ascent + cf.descent) / 2f,
                    mBarTextPaint);
            mBarTextPaint.setColor(0xFF212121);
            return;
        }

        mBarTextPaint.setTextSize(mPanelH * 0.085f);
        mBarTextPaint.setColor(0xFFFFFFFF);
        String tip = panelTip();
        Paint.FontMetrics fm = mBarTextPaint.getFontMetrics();
        float tipY = mPanelTop + mPanelH * 0.09f - (fm.ascent + fm.descent) / 2f;
        // 多选时提示行左端排了「更多选项 / 返回 / 布局调节 / 中心」，
        // 再居中就会和按钮叠在一起 —— 改成从按钮右边开始左对齐。
        if (isMulti() && !mGridCfg && !mShapeMode && !mStickCfg && !mPrioMode) {
            float start = mAdjLayoutMode ? mMultiCenBtnRect.right : mMultiLayBtnRect.right;
            mBarTextPaint.setTextAlign(Paint.Align.LEFT);
            // 余下的宽度可能不够，按可用宽度缩字号，别顶出屏幕
            float availTip = (float) mW - start - dp(10f);
            while (mBarTextPaint.getTextSize() > dp(8f)
                    && mBarTextPaint.measureText(tip) > availTip) {
                mBarTextPaint.setTextSize(mBarTextPaint.getTextSize() - dp(1f));
            }
            c.drawText(tip, start + dp(8f), tipY, mBarTextPaint);
        } else {
            mBarTextPaint.setTextAlign(Paint.Align.CENTER);
            c.drawText(tip, mW / 2f, tipY, mBarTextPaint);
        }

        // 左上角「更多选项」入口。停在形状模式时高亮，
        // 和「常用工具」一个逻辑 —— 提示"你现在在哪个子模式里"。
        // 多选面板下这个位置改画「布局调节」+「中心」，两者互斥。
        boolean shapeOn = (mShapeMode && mSel != NONE) || mPrioMode;
        // 多选时「更多选项」也高亮一下，提示"这批按钮还有批量设置可进"，
        // 但只在常规面板下 —— 子模式里本来就有「返回」在提示了。
        if (!mGridCfg) {
            c.drawRoundRect(mShapeBtnRect, dp(6f), dp(6f),
                    (shapeOn || isMulti()) ? mSlFillPaint : mToolBtnPaint);
            mBarTextPaint.setTextAlign(Paint.Align.CENTER);
            // 「更多选项」四个字比原来的「形状」长一倍，
            // 按按钮宽度自动缩字号，别顶出边框。
            String moreTxt = "更多选项";
            mBarTextPaint.setTextSize(mShapeBtnRect.height() * 0.42f);
            float avail = mShapeBtnRect.width() - dp(6f);
            while (mBarTextPaint.getTextSize() > dp(8f)
                    && mBarTextPaint.measureText(moreTxt) > avail) {
                mBarTextPaint.setTextSize(mBarTextPaint.getTextSize() - dp(1f));
            }
            mBarTextPaint.setColor(0xFF212121);
            Paint.FontMetrics sf = mBarTextPaint.getFontMetrics();
            c.drawText(moreTxt, mShapeBtnRect.centerX(),
                    mShapeBtnRect.centerY() - (sf.ascent + sf.descent) / 2f, mBarTextPaint);
            // 子模式里给个「返回」：不然只能再点一次「更多选项」把它切回去，
            // 那不算返回 —— 见 mSubBackRect 的说明。
            if (inSubMode()) {
                c.drawRoundRect(mSubBackRect, dp(6f), dp(6f), mToolBtnPaint);
                mBarTextPaint.setTextAlign(Paint.Align.CENTER);
                mBarTextPaint.setTextSize(mSubBackRect.height() * 0.42f);
                mBarTextPaint.setColor(0xFF212121);
                Paint.FontMetrics bf = mBarTextPaint.getFontMetrics();
                c.drawText("返回", mSubBackRect.centerX(),
                        mSubBackRect.centerY() - (bf.ascent + bf.descent) / 2f,
                        mBarTextPaint);
            }
        }
        // 【多选时「更多选项」照样显示】
        //   以前多选面板把这个位置换成了「布局调节」+「中心」，
        //   于是多选状态下"形状"等入口全没了 —— 只能先退出多选才调得了。
        //   现在更多 / 返回照常画，布局调节和中心跟在它们右边，
        //   只画"布局调节"两个字的宽度，中心按钮只在布局调节打开时才出现。
        if (isMulti() && !mGridCfg && !mShapeMode && !mStickCfg && !mPrioMode) {
            // 「布局调节」开关：开着时高亮，一眼能看出当前是哪种缩放语义
            c.drawRoundRect(mMultiLayBtnRect, dp(6f), dp(6f),
                    mAdjLayoutMode ? mSlFillPaint : mToolBtnPaint);
            mBarTextPaint.setTextAlign(Paint.Align.CENTER);
            mBarTextPaint.setTextSize(mMultiLayBtnRect.height() * 0.42f);
            mBarTextPaint.setColor(mAdjLayoutMode ? 0xFFFFFFFF : 0xFF212121);
            Paint.FontMetrics lf = mBarTextPaint.getFontMetrics();
            c.drawText("布局调节", mMultiLayBtnRect.centerX(),
                    mMultiLayBtnRect.centerY() - (lf.ascent + lf.descent) / 2f,
                    mBarTextPaint);

            // 「中心」：只在布局调节打开时给 —— 中心只对整体缩放有意义，
            // 平时画出来白白占掉提示行一大截。
            if (mAdjLayoutMode) {
            c.drawRoundRect(mMultiCenBtnRect, dp(6f), dp(6f), mToolBtnPaint);
            mBarTextPaint.setTextSize(mMultiCenBtnRect.height() * 0.40f);
            Paint.FontMetrics af = mBarTextPaint.getFontMetrics();
            String an = mAdjAnchor != NONE && mLayout != null
                    ? mLayout.nameOf(mAdjAnchor) : "未选";
            if (an.length() > 5) {
                an = an.substring(0, 5);
            }
            c.drawText("中心:" + an, mMultiCenBtnRect.centerX(),
                    mMultiCenBtnRect.centerY() - (af.ascent + af.descent) / 2f,
                    mBarTextPaint);
            }
        }

        // 网格配置面板：左上角改画「显示网格」+「自动吸附」两个开关。
        // 占的是「布局调节」+「中心」那两个位置（两种面板互斥，不冲突）。
        if (mGridCfg) {
            c.drawRoundRect(mAdjLayoutBtnRect, dp(6f), dp(6f),
                    mGridShow ? mSlFillPaint : mToolBtnPaint);
            mBarTextPaint.setTextAlign(Paint.Align.CENTER);
            mBarTextPaint.setTextSize(mAdjLayoutBtnRect.height() * 0.38f);
            mBarTextPaint.setColor(mGridShow ? 0xFFFFFFFF : 0xFF212121);
            Paint.FontMetrics gf = mBarTextPaint.getFontMetrics();
            c.drawText("显示网格", mAdjLayoutBtnRect.centerX(),
                    mAdjLayoutBtnRect.centerY() - (gf.ascent + gf.descent) / 2f,
                    mBarTextPaint);

            c.drawRoundRect(mAdjAnchorBtnRect, dp(6f), dp(6f),
                    mGridSnap ? mSlFillPaint : mToolBtnPaint);
            mBarTextPaint.setTextSize(mAdjAnchorBtnRect.height() * 0.38f);
            mBarTextPaint.setColor(mGridSnap ? 0xFFFFFFFF : 0xFF212121);
            Paint.FontMetrics sf2 = mBarTextPaint.getFontMetrics();
            c.drawText("自动吸附", mAdjAnchorBtnRect.centerX(),
                    mAdjAnchorBtnRect.centerY() - (sf2.ascent + sf2.descent) / 2f,
                    mBarTextPaint);
        }

        boolean sm = mShapeMode && mSel != NONE;
        // 多选形状面板：前两条滑条不参与（只选形状），
        // 空着画两条点不动的轨道反而像坏了，直接不画。
        // 网格配置面板：滑条换成「列数 / 行数」的 − + 步进器。
        if (mGridCfg) {
            drawGridStepRow(c, mSlHit[0], "列数", mGridCols,
                    mGridColMinusRect, mGridColPlusRect);
            drawGridStepRow(c, mSlHit[1], "行数", mGridRows,
                    mGridRowMinusRect, mGridRowPlusRect);
        } else if (mStickCfg && selHasStick()) {
            // 摇杆设置：只画范围那一条。
            //
            // 固定摇杆开着时整条变暗（轨道 / 名字 / 百分比 / 滑块一起暗）——
            // 它还在但拖不动，比"整个消失"更能说明"这里有个设置，只是现在用不上"。
            int refS = firstStickInSel();
            drawSlider(c, 0, refS != NONE && mLayout.stickFixed[refS]);
            // 第一行：固定摇杆开关
            drawStickFixedRow(c);
        } else if (mPrioMode) {
            // 优先级子面板：整块面板只有这一条，和第三行的形状选项互斥
            drawSlider(c, 0);
        } else if (!isMultiShape()) {
            for (int i = 0; i < (sm ? 2 : SL_COUNT); i++) {
                drawSlider(c, i);
            }
        }

        // 形状模式：第三行画「圆形」「四边形」
        if (sm) {
            boolean isCircle = mLayout.shape[mSel] == PadLayout.SHAPE_CIRCLE;
            drawShapeChoice(c, mShapeCircleRect, "圆形", isCircle);
            drawShapeChoice(c, mShapeRectRect, "四边形", !isCircle);
        }
        // 多选形状：同一块区域，语义变成"刷给整批"
        if (isMultiShape()) {
            drawShapeChoice(c, mShapeCircleRect, "圆形", false);
            drawShapeChoice(c, mShapeRectRect, "四边形", false);
        }

        mBarTextPaint.setTextAlign(Paint.Align.CENTER);
        mBarTextPaint.setTextSize(mPanelH * 0.075f);
        mBarTextPaint.setColor(0xFF212121);
        for (int i = 0; i < T_COUNT; i++) {
            // 收进「常用工具」里的那些在面板上不再画（TOOL_TEXT 为空串），
            // 下标仍然保留，是为了让 T_* 常量和 TOOL_TEXT 对得上。
            if (TOOL_TEXT[i].isEmpty()) {
                continue;
            }
            RectF r = mToolRects[i];
            // 「常用工具」在它的列表打开时高亮成蓝色
            boolean active = (i == T_TOOLS) && mListMode == LIST_TOOL;
            c.drawRoundRect(r, dp(8f), dp(8f), active ? mSlFillPaint : mToolBtnPaint);
            Paint.FontMetrics f = mBarTextPaint.getFontMetrics();
            c.drawText(active ? TOOL_TEXT[i] + "…" : TOOL_TEXT[i], r.centerX(),
                    r.centerY() - (f.ascent + f.descent) / 2f, mBarTextPaint);
        }
        mBarTextPaint.setColor(0xFF212121);
    }

    /**
     * 摇杆设置里的「固定摇杆」开关行。
     *
     * 开（默认）：摇杆待在摆好的位置，手指在任何地方按下都推它。
     * 关：手指落在范围圈内按下时摇杆瞬移过去，抬手就留在那儿。
     */
    private void drawStickFixedRow(Canvas c) {
        if (!selHasStick()) {
            return;
        }
        // 多选时大家状态可能不一致（比如只改过其中一个），
        // 显示"混合"比谎报"开"或"关"诚实 —— 点一下会把整批掰成一致。
        int ref = firstStickInSel();
        boolean on = ref != NONE && mLayout.stickFixed[ref];
        boolean mixed = false;
        if (isMulti()) {
            for (int i = 0; i < PadLayout.N; i++) {
                if (mSelSet[i] && isStickElem(i)
                        && mLayout.stickFixed[i] != on) {
                    mixed = true;
                    break;
                }
            }
        }
        int cnt = 0;
        for (int i = 0; i < PadLayout.N; i++) {
            if ((isMulti() ? mSelSet[i] : i == mSel) && isStickElem(i)) {
                cnt++;
            }
        }
        RectF r = mStickFixRect;
        c.drawRoundRect(r, dp(8f), dp(8f), on ? mSlFillPaint : mToolBtnPaint);
        mBarTextPaint.setTextAlign(Paint.Align.LEFT);
        mBarTextPaint.setTextSize(r.height() * 0.38f);
        mBarTextPaint.setColor(on ? 0xFFFFFFFF : 0xFFDDDDDD);
        Paint.FontMetrics f = mBarTextPaint.getFontMetrics();
        String txt = "固定摇杆  " + (mixed ? "混合" : (on ? "开" : "关"));
        if (cnt > 1) {
            txt += "  (" + cnt + " 个摇杆)";
        }
        c.drawText(txt, r.left + dp(10f),
                r.centerY() - (f.ascent + f.descent) / 2f, mBarTextPaint);
        mBarTextPaint.setTextAlign(Paint.Align.CENTER);
    }

    /** 形状模式里的一个选项按钮，选中时蓝色底 + 白字。 */
    private void drawShapeChoice(Canvas c, RectF r, String text, boolean on) {
        c.drawRoundRect(r, dp(8f), dp(8f), on ? mSlFillPaint : mToolBtnPaint);
        mBarTextPaint.setTextAlign(Paint.Align.CENTER);
        mBarTextPaint.setTextSize(r.height() * 0.42f);
        mBarTextPaint.setColor(on ? 0xFFFFFFFF : 0xFF212121);
        Paint.FontMetrics f = mBarTextPaint.getFontMetrics();
        c.drawText(text, r.centerX(),
                r.centerY() - (f.ascent + f.descent) / 2f, mBarTextPaint);
    }

    /** 面板顶部那行提示：点选模式下要告诉用户下一步该干什么。 */
    private String panelTip() {
        if (mGridCfg) {
            return gridTip();
        }
        if (mPrioMode) {
            return "优先级：数大的压在上面（触摸板默认 1，界面按钮恒 100）"
                    + (isMulti() ? " — 拖这一条，选中的 " + selCount() + " 个一起改" : "");
        }
        if (isMultiShape()) {
            return "多选形状：点「圆形」或「四边形」，把选中的 "
                    + selCount() + " 个按钮一次性刷成它";
        }
        if (isMulti()) {
            return "已选 " + selCount() + " 个：滑条作用于整批，拖任一个整批一起走"
                    + (mAdjLayoutMode
                    ? " — 大小 = 整体缩放（连间距一起变）"
                    : " — 大小 = 各自原地调，间距不变");
        }
        if (mStickCfg && selHasStick()) {
            int ref = firstStickInSel();
            String tail = isMulti() ? "（作用于选中的全部摇杆）" : "";
            return (ref != NONE && mLayout.stickFixed[ref])
                    ? "摇杆设置" + tail + "：固定摇杆开 —— 摇杆待在原地，手指在哪按都推它"
                    : "摇杆设置" + tail + "：固定摇杆关 —— 在范围圈内按下，摇杆瞬移过来跟着手指走，松手回到原位";
        }
        if (mShapeMode && mSel != NONE) {
            return "形状模式：选圆形 / 四边形，拖「长」「宽」调整；"
                    + "两者不等就是椭圆 / 长方形";
        }
        if (mSel == NONE) {
            return "拖动按键 = 移动，点一下选中后可拖滑条调大小 / 透明度";
        }
        return "已选中：" + mLayout.nameOf(mSel) + " — 拖它移动，滑条单独调它";
    }

    /** 选中的按钮都画一圈青色（多选时一眼看出选中了哪几个）。 */
    private void drawLinked(Canvas c) {
        for (int i = 0; i < PadLayout.N; i++) {
            if (!mSelSet[i]) {
                continue;
            }
            if (isTrigger(i)) {
                float r = radiusOf(i) + dp(7f);
                float hy = trackHalfH(i) + dp(7f);
                mTmpRect.set(mPx[i] - r, mPy[i] - hy, mPx[i] + r, mPy[i] + hy);
                c.drawRoundRect(mTmpRect, r, r, mLinkPaint);
            } else {
                c.drawCircle(mPx[i], mPy[i], radiusOf(i) + dp(7f), mLinkPaint);
            }
        }
    }

    /**
     * 画一条滑条。
     *
     * @param dim true = 当前用不上（比如固定摇杆开着时的"范围"）：
     *            轨道、名字、数值、滑块**全部**变暗，并且不画已填充段。
     *
     * 【为什么不能只在轨道上盖一层】
     *   盖层是后画的，确实能压暗底下的轨道，但白字和白滑块是在盖层之前画的
     *   —— 半透明黑盖上去，白字仍然是白的，看着还是"能用"的样子。
     *   所以必须从绘制源头就把颜色调暗，而不是事后盖一层。
     */
    private void drawSlider(Canvas c, int i, boolean dim) {
        RectF t = mSlTrack[i];
        // 多选面板下没有"当前选中键"，但滑条是有效的（作用于整批），
        // 所以按可用处理，否则会画成一条点不动的空轨道。
        boolean on = (isMulti() || mSel != NONE) && !dim;
        float v = sliderValue(i);
        if (v < 0f) v = 0f;
        if (v > 1f) v = 1f;

        mBarTextPaint.setTextSize(mPanelH * 0.115f);
        mBarTextPaint.setColor(dim ? 0x55FFFFFF : (on ? 0xFFFFFFFF : 0x66FFFFFF));
        Paint.FontMetrics fm = mBarTextPaint.getFontMetrics();
        float base = t.centerY() - (fm.ascent + fm.descent) / 2f;

        mBarTextPaint.setTextAlign(Paint.Align.LEFT);
        // 摇杆设置下第一条是"范围"。以前没有这个分支，显示的是 SL_TEXT[0]
        // 也就是"大小" —— SL_TEXT_STICK 定义了却一直没用上，
        // 于是那条拖的是范围、写的却是"大小"，直接把人带偏。
        String slName = mPrioMode ? SL_TEXT_PRIO[i]
                : ((mStickCfg && selHasStick())
                ? SL_TEXT_STICK[i] : (mShapeMode ? SL_TEXT_SHAPE[i] : SL_TEXT[i]));
        c.drawText(slName, dp(10f), base, mBarTextPaint);

        mBarTextPaint.setTextAlign(Paint.Align.RIGHT);
        // 形状模式下百分比没意义（区间是 0.3~8 倍），直接显示倍率，
        // 比如 1.19x，方便照着键盘键的默认比例调。
        String valTxt;
        if (mPrioMode) {
            // 优先级是 0~100 的整数，印"80%"反而像透明度的那条，直接给数字
            valTxt = String.valueOf(PadLayout.clampPrio(curPrio()));
        } else if (isMulti() && mAdjLayoutMode && i == SL_SIZE) {
            // 布局调节下百分比没有意义，直接显示整体倍率
            valTxt = String.format("%.2fx", mAdjK);
        } else if (mShapeMode && mSel != NONE) {
            float mul = i == SL_W ? mLayout.widthMul[mSel] : mLayout.heightMul[mSel];
            valTxt = String.format("%.2fx", mul);
        } else {
            /*
              【透明度那条要显示真实的不透明度，不是滑条的位置】
                滑条的行程是 15%~100%（MIN_ALPHA 以下留不住，太透会看不见），
                所以滑条停在 80% 处时实际是 15% + 80%×85% = 83%。
                直接把滑条位置当百分比印出来，就和屏幕上看到的不一致 ——
                "写着 80%，拖到 70% 再拖回 80%，看着却不一样"。
                现在按真实值显示，来回拖到同一个数就是同一个样子。
            */
            if (i == SL_ALPHA && !mStickCfg && !mShapeMode) {
                float realA = PadLayout.MIN_ALPHA + v * (1f - PadLayout.MIN_ALPHA);
                // 点这个数字可以切百分 / 256 制 —— 只换写法，值不变
                valTxt = mAlphaRawUnit ? String.valueOf(alpha256Of(realA))
                        : (Math.round(realA * 100f) + "%");
            } else {
                valTxt = Math.round(v * 100f) + "%";
            }
        }
        float vr = (float) mW - dp(10f);
        c.drawText(valTxt, vr, base, mBarTextPaint);
        /*
          【给这个数字留一块点击热区】
            只有透明度那条可点，所以只给它记区域；
            别的滑条置空，contains() 恒为 false，点它们照旧是拖滑条。
            热区比文字胖一圈 —— 数字就两三个字符，按原大小很难点中。
        */
        if (i == SL_ALPHA && !mStickCfg && !mShapeMode) {
            float tw = mBarTextPaint.measureText(valTxt);
            mSlValRect[i].set(vr - tw - dp(6f), t.top - dp(4f),
                    vr + dp(6f), t.bottom + dp(4f));
            // 画一道细线，提示"这里能点"
            c.drawLine(vr - tw - dp(2f), base + dp(4f), vr + dp(2f),
                    base + dp(4f), mBarTextPaint);
        } else {
            mSlValRect[i].setEmpty();
        }

        // 轨道
        c.drawRoundRect(t, t.height() / 2f, t.height() / 2f,
                dim ? mSlTrackDimPaint : mSlTrackPaint);
        // 已填充的一段
        if (on) {
            float fillR = t.left + t.width() * v;
            if (fillR > t.left + 1f) {
                mTmpRect.set(t.left, t.top, fillR, t.bottom);
                c.drawRoundRect(mTmpRect, t.height() / 2f, t.height() / 2f, mSlFillPaint);
            }
            // 滑块
            c.drawCircle(t.left + t.width() * v, t.centerY(),
                    t.height() * 0.80f, mSlThumbPaint);
        } else if (dim) {
            // 禁用态也要画滑块 —— 不画的话那条看着像"少了点什么"，
            // 而不是"这一条现在用不上"。画成灰色，和白色可用态区分。
            c.drawCircle(t.left + t.width() * v, t.centerY(),
                    t.height() * 0.80f, mSlThumbDimPaint);
        }
    }

    /** 画一条滑条（可用态）。 */
    private void drawSlider(Canvas c, int i) {
        drawSlider(c, i, false);
    }

    // ------------------------------------------------------------------

    /**
     * 切「按键点按穿透」的开关，并在状态行下面留一行小字。
     *
     * 开关写在 SharedPreferences 里，和主界面那个勾选框读的是同一份 ——
     * 所以两边永远一致，不存在"这边开了那边还显示关"。
     * 改完调 notifyKeyWindowPrefChanged() 让 Service 立刻重算穿透窗口，
     * 不用等下一秒的状态轮询。
     */
    private void togglePassThrough() {
        boolean now = !FloatingService.keyWindowsEnabled(getContext());
        FloatingService.setKeyWindowsEnabled(getContext(), now);
        mPassHint = "已切换穿透状态为" + (now ? "开" : "关");
        mPassHintUntil = System.currentTimeMillis() + PASS_HINT_MS;
        invalidate();
        // 3 秒后自动把小字抹掉（期间不重复 post，避免堆积）
        postDelayed(new Runnable() {
            @Override
            public void run() {
                if (mPassHint != null
                        && System.currentTimeMillis() >= mPassHintUntil) {
                    mPassHint = null;
                    invalidate();
                }
            }
        }, PASS_HINT_MS + 50L);
    }

    private void drawStatus(Canvas c) {
        float x = 12f;
        float y = 20f;
        // 绿 = 版本一致；红 = 未连接；橙 = 本 app 是新版，但连上的还是旧进程。
        // 橙点唯一能说明的是"旧进程没杀干净"；它无法说明 APK 有没有更新
        // （APK 没更新的话，这段绘制代码本身就是旧的，画不出这句提示）。
        mStatusPaint.setColor(mStale ? 0xFFFF9800 : (mConnected ? 0xFF4CAF50 : 0xFFF44336));
        c.drawCircle(x + 8f, y + 8f, 8f, mStatusPaint);

        mStatusTextPaint.setTextSize(28f);
        mStatusHaloPaint.setTextSize(28f);
        mStatusHaloPaint.setStrokeWidth(4f);
        String msg;
        if (mStale) {
            // 注意这句话只在"本 app 已是 v3、但对端还是旧进程"时才可能出现。
            // 如果 APK 压根没重新编译安装，这段代码本身就不存在，
            // 它不可能提示"APK 没更新" —— 那种情况只能看守护进程的终端横幅。
            // mDaemonVer < 0 = 连上了但问不到版本号（旧进程不认识 "?" 查询），
            // 这时别显示 "v-1"，看的人会以为是版本号负数。
            // mDaemonVer > 当前 = 反过来的情况：守护进程比 app 新（app 没重新装）。
            if (mDaemonVer < 0) {
                msg = "连上的是旧进程（不认识版本查询）— 旧的没杀干净，pkill 后重跑启动命令";
            } else if (mDaemonVer > GpadDaemon.PROTOCOL_VERSION) {
                msg = "守护进程比本 app 新(v" + mDaemonVer + ">" + GpadDaemon.PROTOCOL_VERSION
                        + ") — 重新编译安装本 app";
            } else {
                msg = "连上的是旧进程(v" + mDaemonVer + "≠" + GpadDaemon.PROTOCOL_VERSION
                        + ") — 旧的没杀干净，pkill 后重跑启动命令";
            }
        } else {
            msg = mConnected
                    ? "守护进程已连接 v" + mDaemonVer + "  已发送 " + mSent
                    : "守护进程未连接 — 请先执行启动命令";
        }
        drawTextWithHalo(c, msg, x + 26f, y + 18f, mStatusTextPaint, mStatusHaloPaint);

        // 切换后的小字提示：几秒后自己消失
        if (mPassHint != null) {
            if (System.currentTimeMillis() >= mPassHintUntil) {
                mPassHint = null;
            } else {
                mStatusTextPaint.setTextSize(dp(13f));
                mStatusHaloPaint.setTextSize(dp(13f));
                mStatusHaloPaint.setStrokeWidth(dp(3f));
                drawTextWithHalo(c, mPassHint, x + 26f, y + 18f + dp(24f),
                        mStatusTextPaint, mStatusHaloPaint);
            }
        }

        if (!mConnected && mErr != null && mErr.length() > 0) {
            mStatusTextPaint.setTextSize(22f);
            mStatusHaloPaint.setTextSize(22f);
            mStatusHaloPaint.setStrokeWidth(3f);
            drawTextWithHalo(c, mErr, x + 26f, y + 48f, mStatusTextPaint, mStatusHaloPaint);
        }
    }



}
