package com.example.vgamepad;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import java.util.Locale;
import android.provider.MediaStore;
import android.os.Environment;
import android.os.Build;
import java.io.FileOutputStream;
import android.net.Uri;
import java.text.SimpleDateFormat;
import java.io.OutputStream;
import java.io.File;
import java.util.Date;
import android.content.ContentValues;

/**
 * 列表 / 弹窗 / 分类子系统（从 GamepadView 拆出的第一块）。
 *
 * 【为什么用继承链而不是持有引用】这些方法咬着一百多个字段；
 *   改成持有引用的话每处字段访问都要加前缀，几千行机械改动容易出错。
 * 【为什么本类 extends View】Java 单继承：GamepadView 必须是 View
 *   （要挂到 WindowManager），链是 View -> PickList -> GamepadView。
 * 【纯搬迁，零逻辑改动】方法体原样从 GamepadView.java 移来。
 */
abstract class PickList extends View {

    PickList(Context context) {
        super(context);
    }

    // ===== 内部接口 =====
    public interface Sink {
        void onButton(int index, boolean pressed);

        /**
         * 请求一个"能打字"的界面来给布局改名。
         *
         * 【为什么不能在悬浮窗里直接输入】
         *   1) 悬浮窗带 FLAG_NOT_FOCUSABLE（必须带，否则会抢走游戏焦点），
         *      带了这个 flag 软键盘根本弹不出来；
         *   2) 就算弹出来，输入法是系统级窗口，悬浮窗挡在它上面，
         *      手指也够不到键盘。
         *
         *   所以只能把输入这件事交给 app 自己的 Activity ——
         *   那里有正常的 EditText 和系统输入法，层级也对。
         */
        void requestRenameLayout(int layoutId, String currentName);

        /** 请求给组合键改名（槽位下标）。同样是悬浮窗里弹不出输入法。 */
        void requestRenameCombo(int slot, String currentName);
        /** 单个按键改名：跳到 app 界面输入，element = 元素下标。 */
        void requestRenameKey(int element, String currentName);
        /**
         * 自定义底色：跳到 app 界面取色，currentHex 是初值。
         *
         * @param useWeb true = 用 WebView 里的 &lt;input type="color"&gt; 取色器；
         *               false = 手输 #AARRGGBB。
         */
        void requestColorHex(String currentHex, boolean useWeb, int count);

        /**
         * 请求一个文件选择器来导入布局存档。
         *
         * 和改名同理：悬浮窗里没法弹系统文件选择器，只能交给 Activity。
         */
        void requestImportLayout();

        /** 跳到 app 界面输入列表搜索词（同样会离开游戏）。 */
        void requestSearchList();

        void onAxis(int axis, float value);

        void onHat(int hat);

        void onTrigger(int trigger, float value);

        /**
         * 动态创建的键盘按键。
         *
         * @param usage HID keyboard usage（不是 Android keycode）——
         *              守护进程要把它填进键盘报告的 bitmap（NKRO，无 6 键上限），
         *              传 usage 省掉一层反查。
         */
        void onKey(int usage, boolean pressed);

        /**
         * 鼠标相对移动（触摸板拖动）。
         *
         * 和 onAxis 的区别：onAxis 是"当前推到 -1..1 的哪个位置"（状态，
         * 丢一帧下一帧补回来）；这里是"这一下往右走了几格"（增量，
         * 丢了就永久少走一段）。
         */
        void onMouseMove(int dx, int dy);

        /** 鼠标按键：0=左 1=右 2=中。 */
        void onMouseButton(int btn, boolean pressed);

        /** 鼠标滚轮：正 = 向上滚，负 = 向下。一次一格。 */
        void onMouseWheel(int notches);

        /** 用户点了「收」 */
        void onCollapse();

        /**
         * 悬浮窗实际尺寸变了（首次确定 / 旋转 / 导航栏变动）。
         *
         * 【为什么必须上报】
         *   悬浮球（收起态那个 G）和「收」按钮共用一份位置存档，
         *   存档存的是**相对悬浮窗尺寸的比例**（rx/ry）。
         *   悬浮球是独立窗口，定位时要换算回像素 ——
         *   换算用谁的尺寸，就必须和存档时用的谁一致。
         *
         *   之前悬浮球乘的是 screenSize()，而 rx/ry 是相对 GamepadView 的
         *   mW/mH；横屏或有导航栏时两者不相等，于是"点开在 A、收起跑到 B"。
         */
        void onPadSize(int w, int h);
    }
    interface StickOp {
        void run(int i);
    }

    // ===== 字段 =====
    java.util.ArrayList<PadLayout.LayoutMeta> mLayoutMetas
            = new java.util.ArrayList<PadLayout.LayoutMeta>();
    java.util.ArrayList<PadLayout.LayoutMeta> mLayoutFiltered
            = new java.util.ArrayList<PadLayout.LayoutMeta>();
    final RectF[] mListItemRects =
            new RectF[Math.max(PadLayout.N, PadLayout.KEY_NAMES.length)];
    final java.util.ArrayList<Integer> mListBackStack =
            new java.util.ArrayList<Integer>();
    boolean mSyncSrcPortrait = false;
    PadLayout mSyncSrc = null;
    int mSyncLayoutId = PadLayout.LAYOUT_DEFAULT_PAD;
    int mAppearSlot = NONE;
    /** 进「优先级」子列表时选中的槽位：拖条可能切走 mSel，不先存就改错对象。 */
    int mStickX = 0;
    int mStickY = 0;
    int mTrigPct = 100;
    int mComboEditDir = 0;
    boolean mComboInSub = false;
    int mComboDirSlot = NONE;
    int mComboDirProto = NONE;
    boolean mShapeMode;
    boolean mStickCfg;
    /**
     * 是否停在"优先级"子模式（面板上只剩一条优先级滑条）。
     *
     * 和形状 / 摇杆设置同一套子模式机制：点「更多选项 → 优先级」进来，
     * 面板原地换成优先级那条，左上角给「返回」回常规面板。
     */
    boolean mPrioMode;
    final RectF mShapeBtnRect = new RectF();
    final RectF mSubBackRect = new RectF();
    final RectF mShapeCircleRect = new RectF();
    final RectF mShapeRectRect = new RectF();
    boolean mGridCfg;
    final RectF mGridColMinusRect = new RectF();
    final RectF mGridColPlusRect = new RectF();
    final RectF mGridRowMinusRect = new RectF();
    final RectF mGridRowPlusRect = new RectF();
    int mFixTpl = PadLayout.TPL_PAD;
    int[] mFixCode = new int[0];
    String[] mFixName = new String[0];
    boolean mFixPicking = false;
    int mComboEditIdx = NONE;
    String mComboAutoPrev = null;
    int mComboPickIdx = COMBO_PICK_NONE;
    boolean mFixUiMode = false;
    /** 功能键页的胶囊矩形：行数 × 模板数（模板数 = FX_TPL_SHORT.length）。 */
    final RectF[][] mFixTplRects = new RectF[8][4];
    String mListQuery = "";
    final int[] mListFilter = new int[PadLayout.N + PadLayout.KEY_NAMES.length + 8];
    int mListFilterCount = 0;
    final RectF mListSearchRect = new RectF();
    final Paint mFixCapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final RectF[] mGridMinusRects = new RectF[GI_COUNT];
    final RectF[] mGridPlusRects = new RectF[GI_COUNT];
    int mListAdjust = ADJ_NONE;
    int mListAdjustPointer = NONE;
    final RectF mTrigBarRect = new RectF();
    final RectF mStickPadRect = new RectF();
    float mStickPadR = 0f;
    int mRayElem = NONE;
    final boolean[] mSelSet = new boolean[PadLayout.N];
    final boolean[] mColorSel = new boolean[PadLayout.N];
    int mColorPassState = 0;
    int mColorSelCount = 1;
    boolean mAdjLayoutMode;
    int mAdjAnchor = NONE;
    float mAdjK = 1f;
    float mAdjSizeVal = 0.5f;
    float mAdjAlphaVal = 1f;
    float mAdjTextVal = 0.5f;
    final RectF mAdjLayoutBtnRect = new RectF();
    final RectF mAdjAnchorBtnRect = new RectF();
    Sink mSink;
    final Paint mRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint mSelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint mPanelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint mToolBtnPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint mBarTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint mScrollBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint mSlTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint mSlFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    PadLayout mLayout;
    final float[] mPx = new float[PadLayout.N];
    final float[] mPy = new float[PadLayout.N];
    int mW;
    int mH;
    boolean mPortrait;
    boolean mEditMode;
    boolean mFromApp;
    int mSel = NONE;
    final RectF[] mToolRects = new RectF[T_COUNT];
    final RectF[] mSlTrack = new RectF[SL_COUNT];
    final RectF mStickFixRect = new RectF();
    final RectF mMultiLayBtnRect = new RectF();
    final RectF mMultiCenBtnRect = new RectF();
    final RectF[] mSlHit = new RectF[SL_COUNT];
    final RectF mTmpRect = new RectF();
    float mBarH;
    float mPanelH;
    float mPanelTop;
    float mPanelBottom;
    final RectF mPanelToggleRect = new RectF();
    final RectF mPanelMinRect = new RectF();
    int mPanelState = PANEL_FULL;
    float mTopGuard;
    int mListMode = LIST_NONE;
    final RectF[] mLayoutRenameRects = new RectF[PadLayout.LAYOUT_MAX];
    final RectF[] mLayoutSyncRects = new RectF[PadLayout.LAYOUT_MAX];
    final boolean[] mSyncSel = new boolean[PadLayout.N];
    final boolean[] mSyncDims = new boolean[SD_COUNT];
    final RectF[] mLayoutDelRects = new RectF[PadLayout.LAYOUT_MAX];
    final RectF[] mLayoutShareRects = new RectF[PadLayout.LAYOUT_MAX];
    final RectF[] mComboDelRects = new RectF[PadLayout.MAX_COMBO_ACTS + 2];
    final RectF[] mComboEndRects = new RectF[PadLayout.MAX_COMBO_ACTS + 2];
    final RectF mListImportRect = new RectF();
    int mListCat = CAT_ALL;
    boolean mListCatOpen;
    final RectF mListCatRect = new RectF();
    final RectF[] mListCatItemRects = new RectF[Math.max(TCAT_MAX, CAT_MAX)];
    final RectF mListSelAllRect = new RectF();
    final RectF mListCreateRect = new RectF();
    int mPendingTpl = PadLayout.TPL_PAD;
    String mBannerText = null;
    long mBannerUntil = 0L;
    int mDlgMode = DLG_NONE;
    String mDlgTitle = "";
    int mDlgTargetId = -1;
    final RectF mDlgPanelRect = new RectF();
    final RectF mDlgOkRect = new RectF();
    final RectF mDlgCancelRect = new RectF();
    boolean mKeyKeepOpen;
    final RectF mKeepOpenRect = new RectF();
    float mListPad;
    final int[] mListItems = new int[PadLayout.N];
    int mListCount;
    float mListScroll;
    float mListScrollMax;
    final RectF mListViewRect = new RectF();
    float mListContentH;
    boolean mListDragging;
    final boolean[] mResetDims = new boolean[RD_COUNT];
    final boolean[] mListSel = new boolean[PadLayout.N];
    final RectF mListOkRect = new RectF();
    final RectF mListCancelRect = new RectF();
    int mListBack = LIST_NONE;
    final RectF mListPanelRect = new RectF();
    final RectF[] mToolListRects = new RectF[TL_COUNT];
    final RectF[] mToolGroupRects = new RectF[TL_COUNT];
    final String[] mToolGroupTexts = new String[TL_COUNT];
    final int[] mSwapPick = new int[SWAP_MAX];

    // ===== 常量 =====
    static final int FXC_HIDE = 0xFF1565C0;   // 蓝
    static final int FXC_DEL = 0xFFC62828;    // 红
    static final int ST_DEF = 3;
    static final int ST_DEL = 2;
    static final int ST_HIDE = 1;
    static final int ST_SHOW = 0;
    static final int TCAT_OTHER = 4;
    static final int TCAT_BTN = 3;
    static final int TCAT_POS = 2;
    static final int TCAT_RESET = 1;
    static final int[] BTN_COLOR_VALUES = {
            0, 0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF43A047,
            0xFF00ACC1, 0xFF1E88E5, 0xFF8E24AA, 0xFFEC407A, 0xFF795548,
            0xFF757575, 0xFF212121,
            -1, -2
    };
    static final String[] RD_NAMES = {"位置重置", "大小重置", "透明重置",
            "字体重置", "其他信息"};
    static final int FXC_SHOW = 0xFF2E7D32;   // 绿
    static final int FXC_DEF = 0xFF616161;    // 灰
    static final String[] BTN_COLOR_NAMES = {
            "默认", "红", "橙", "黄", "绿", "青", "蓝", "紫", "粉", "棕", "灰", "黑",
            "手动输入", "网页输入"
    };
    static final String[] LEGEND_TEXT = {
            "强制显示", "强制隐藏", "删除", "跟随默认"
    };
    static final int[] LEGEND_COLOR = {
            FXC_SHOW, FXC_HIDE, FXC_DEL, FXC_DEF
    };
    /*
      【下标 0..2 必须等于 TPL_BLANK / TPL_PAD / TPL_KEYBOARD】
        buildFixRows(item) 直接把下标当模板 id 用，错一位就全错。
        下标 3 = TPL_MOUSE（鼠标模板），
        下标 4 = 功能键入口，它的编号是 GamepadView 的 FX_UI_ENTRY = 4
        —— 必须避开真实模板 id，不然和鼠标模板撞号。
    */
    static final String[] FX_TPL_NAMES = {
            "空白模板", "手柄模板", "键盘模板", "鼠标模板", "功能键"
    };
    static final String[] CAT_NAMES =
            {"全部", "手柄", "键盘", "空白", "组合", "功能"};
    static final String[] APPEAR_NAMES =
            {"形状", "改名", "改背景颜色", "按键名"};
    static final int NONE = -1;
    static final int T_RESET = 0;
    static final int T_RESET_ONE = 1;
    static final int T_SWAP = 2;
    static final int T_LINK = 3;
    static final int T_TOOLS = 4;
    static final int T_DONE = 5;
    static final int T_COUNT = 6;
    static final int[] TOOL_ROW_0 = {T_TOOLS};
    static final int[] TOOL_ROW_1 = {T_DONE};
    static final int TL_RESET = 0;
    static final int TL_RESET_ONE = 1;
    static final int TL_SWAP = 2;
    static final int TL_LINK = 3;
    static final int TL_HIDE = 4;
    static final int TL_REVERT = 5;
    static final int TL_KEY = 8;
    static final int TL_DELETE = 6;
    static final int TL_BATCH_DEL = 7;
    static final int TL_LAYOUT = 10;
    static final int TL_SELECT = 11;
    static final int TL_PAD = 9;
    static final int TL_CREATE = 16;
    static final int TL_MULTI_ADJ = 12;
    static final int TL_MULTI_SHAPE = 13;
    static final int TL_GRID = 14;
    static final int TCAT_ALL = 0;
    static final int TCAT_MAX = 5;
    static final int TL_FIXED = 15;
    static final int TL_COUNT = 17;
    static final String[] TCAT_NAMES = {"全部", "重置", "位置", "按钮", "其他"};
    static final int LIST_NONE = 0;
    static final int LIST_LINK = 1;
    static final int LIST_SWAP = 2;
    static final int LIST_RESET_ONE = 3;
    static final int LIST_HIDE = 4;
    static final int LIST_TOOL = 5;
    static final int LIST_KEY = 6;
    static final int LIST_DEL_KEY = 7;
    static final int LIST_LAYOUT = 8;
    static final int LIST_LAYOUT_PICK = 9;
    static final int LIST_TPL = 10;
    static final int LIST_SELECT = 11;
    static final int LIST_PAD = 12;
    static final int LIST_MULTI_ADJ = 13;
    static final int LIST_MULTI_SHAPE = 14;
    static final int LIST_ADJ_ANCHOR = 15;
    static final int LIST_GRID = 16;
    static final int LIST_FIX_TPL = 17;
    static final int LIST_FIX = 18;
    static final int LIST_RESET_DIM = 19;
    static final int LIST_CREATE = 20;
    static final int LIST_MOUSE = 22;
    /** 鼠标元素候选表：触摸板 / 左键 / 右键 / 中键 / 滚轮上 / 滚轮下 */
    static final int[] MOUSE_CANDIDATES = {
            PadLayout.I_MOUSE_PAD, PadLayout.I_MOUSE_L, PadLayout.I_MOUSE_R,
            PadLayout.I_MOUSE_M, PadLayout.I_MOUSE_WU, PadLayout.I_MOUSE_WD,
    };
    static final String[] CREATE_NAMES = {"手柄按键", "键盘按键", "空白", "组合键", "鼠标键"};
    static final int LIST_SYNC_PICK = 27;
    static final int LIST_SYNC_DIM = 28;
    static final int LIST_SYNC_DIR = 29;
    static final int SDIR_FROM_PORT = 1;
    static final String[] SDIR_NAMES = {"从横屏复制到竖屏", "从竖屏复制到横屏"};
    static final int SD_COUNT = 4;
    static final String[] SD_NAMES = {"大小复制", "透明复制", "字体复制", "其他信息"};
    static final int CT_PAD = 0;
    static final int CT_BLANK = 2;
    static final int CT_COMBO = 3;
    static final int CT_MOUSE = 4;
    static final int LIST_MORE = 21;
    static final int LIST_APPEAR = 39;
    /** 更多选项 → 优先级：一条可拖的条，0 最低 / 100 最高。 */
    static final int LIST_COMBO_EDIT = 24;
    static final int LIST_COMBO_TYPE = 25;
    static final int COMBO_ACT_PAD = 1;
    static final int COMBO_ACT_DELAY = 3;
    static final int COMBO_ACT_STICK_DIR = 7;
    static final int COMBO_ACT_HAT_DIR = 8;
    static final int COMBO_ACT_STICK_XY = 11;
    static final int LIST_COMBO_STICK_MODE = 31;
    static final int LIST_COMBO_STICK_XY = 32;
    static final int LIST_COMBO_TRIG = 33;
    static final int LIST_COMBO_KIND = 34;
    static final int LIST_COMBO_CROSS = 35;
    static final String[] COMBO_KIND_NAMES = {"按钮", "十字架"};
    static final int LIST_BTN_COLOR = 36;
    static final int LIST_PASS_COLOR = 38;
    static final String[] PASS_COLOR_NAMES = {"穿透开时的颜色", "穿透关时的颜色"};
    static final String[] STICK_MODE_NAMES = {"预设（四面八方）", "高级（自定义 X / Y）"};
    static final String[] DIR_NAMES = {"上", "右上", "右", "右下", "下", "左下", "左", "左上"};
    static final int DIR_COUNT = 8;
    static final int LIST_COMBO_DIR = 30;
    static final int LIST_COMBO_DELAY = 26;
    static final int COMBO_PICK_NONE = -1;
    static final int MORE_COMBO = 2;
    static final int SWAP_MAX = 2;
    static final int GI_COUNT = 7;
    static final int RD_COUNT = 5;
    static final int PANEL_FULL = 0;
    static final int ADJ_NONE = 0;
    static final int ADJ_TRIG = 1;
    /** 优先级条：和力度条同一套拖法，只是改的是图层优先级。 */
    static final int ADJ_STICK = 2;
    static final int SL_COUNT = 3;
    /**
     * 滑条的名字 / 数值文案。
     *
     * 【为什么放在基类】
     *   排布滑条要按文字的**实际宽度**留白（见 maxSliderLabelWidth），
     *   而排布代码在基类 PickList 里 —— 放子类的话基类读不到。
     */
    static final String[] SL_TEXT = {"大小", "透明", "字体"};
    /** 形状模式下的两条：长 = 宽倍率，宽 = 高倍率（第三格用不到，留空） */
    static final String[] SL_TEXT_SHAPE = {"长", "宽", ""};
    /** 摇杆设置下的第一条：范围。第二条以后留着扩展。 */
    static final String[] SL_TEXT_STICK = {"范围", "", ""};
    /** 优先级子面板下的第一条：图层优先级。第二条以后留着扩展。 */
    static final String[] SL_TEXT_PRIO = {"优先级", "", ""};
    static final float NO_BOTTOM_LIMIT = 0f;
    static final int CAT_ALL = 0;
    static final int CAT_PAD = 1;
    static final int CAT_KB = 2;
    static final int CAT_BLANK = 3;
    static final int CAT_COMBO = 4;
    static final int CAT_UI = 5;
    static final int CAT_MAX = 6;
    static final int CAT_MAX_LAYOUT = 4;
    static final String[] CAT_NAMES_LAYOUT = {"全部", "手柄", "键盘", "空白"};
    static final int DLG_NONE = 0;
    static final int DLG_CONFIRM_DEL = 1;
    static final int DLG_CONFIRM_RESET_ALL = 2;
    static final int DLG_CONFIRM_RESET_ONE = 3;
    static final int DLG_CONFIRM_DEL_KEY = 4;
    static final int DLG_CONFIRM_BATCH_DEL = 5;
    static final int DLG_CONFIRM_RENAME = 6;
    static final int DLG_CONFIRM_IMPORT = 7;
    static final int DLG_CONFIRM_SEARCH = 8;
    static final int DLG_CONFIRM_COMBO_RENAME = 9;
    static final int DLG_CONFIRM_KEY_RENAME = 10;
    static final int DLG_CONFIRM_KEY_COLOR = 11;
    /**
     * 固定显示列表最后那个「＋ 添加…」的代号。
     *
     * 【只留一个入口，不按种类拆成五行】
     *   手柄 / 键盘 / 鼠标 / 空白 / 组合键五种，拆开就是五行，
     *   底部被撑得很长。而「按键创建」那个界面（LIST_CREATE）本来就把
     *   这五种全列着，直接复用它 —— 点这一个入口进去选就行。
     *   所以这里只需要一个"这是入口行"的标记，具体选哪种交给那一层。
     */
    static final int FX_ACT_ADD = -1;

    /** 「＋ 添加…」是入口不是状态行：不上状态底色、行名也不带后缀。 */
    static boolean isFixAct(int code) {
        return code == FX_ACT_ADD;
    }
    /**
     * 功能键那页每行右侧的胶囊。下标 = 模板序号，
     * 和 GamepadView.FX_TPLS 一一对应（空 / 手 / 键 / 鼠）。
     *
     * 【加模板时要两边一起加】
     *   胶囊按这个数组的长度画、按这个长度排布，
     *   而点第 k 个胶囊时 fixUiState(pos, k) 拿 k 去索引 FX_TPLS。
     *   只加 FX_TPLS 不改这里，第四个胶囊永远画不出来。
     */
    static final String[] FX_TPL_SHORT = {"空", "手", "键", "鼠"};
    // 【下标必须和 PadLayout 的 TPL_* 常量对上】
    //   点某一行是 mPendingTpl = pos，直接拿去当模板 id 用：
    //   TPL_BLANK=0 / TPL_PAD=1 / TPL_KEYBOARD=2 / TPL_MOUSE=3。
    //   所以「鼠标模板」只能排在第 4 位，插到中间会让后面全错位
    //   —— 选"键盘模板"却建出鼠标布局这种事最难查。
    static final String[] TPL_NAMES = {"空白模板", "手柄模板", "键盘模板", "鼠标模板"};

    static final int[] TL_ORDER = {
            // —— 重置 ——
            TL_RESET, TL_RESET_ONE,
            // —— 位置 ——
            TL_SWAP, TL_LINK, TL_REVERT, TL_SELECT, TL_GRID,
            // —— 按钮 ——
            TL_HIDE, TL_DELETE, TL_BATCH_DEL, TL_CREATE,
            TL_FIXED,
            // —— 其他 ——
            TL_LAYOUT,
    };
    static final int[] TL_CAT = {
            TCAT_RESET,     // 0 重置全部
            TCAT_RESET,     // 1 重置单个…
            TCAT_POS,       // 2 互换位置…
            TCAT_POS,       // 3 多选…
            TCAT_BTN,       // 4 隐藏按钮…
            TCAT_POS,       // 5 回退旧版位置
            TCAT_BTN,       // 6 删除此键
            TCAT_BTN,       // 7 批量删除…
            TCAT_BTN,       // 8 键盘按键…
            TCAT_BTN,       // 9 手柄按键…
            TCAT_OTHER,     // 10 布局切换…
            TCAT_POS,       // 11 选中按钮…
            TCAT_BTN,       // 12 多选调节…
            TCAT_BTN,       // 13 多选形状…
            TCAT_POS,       // 14 网格/吸附…
            TCAT_BTN,       // 15 固定显示…
            TCAT_BTN,       // 16 按键创建…
    };
    static final String[] TL_TEXT = {
            /* 0 */ "重置全部",
            /* 1 */ "重置单个…",
            /* 2 */ "互换位置…",
            /* 3 */ "多选…",
            /* 4 */ "隐藏按钮…",
            /* 5 */ "回退旧版位置",
            /* 6 */ "删除此键",
            /* 7 */ "批量删除…",
            /* 8 */ "键盘按键…",      // 不在工具列表里了，占位
            /* 9 */ "手柄按键…",      // 同上
            /* 10 */ "布局切换…",
            /* 11 */ "选中按钮…",
            /* 12 */ "多选调节…",
            /* 13 */ "多选形状…",
            /* 14 */ "网格/吸附…",
            /* 15 */ "固定显示…",
            /* 16 */ "按键创建…"
    };
    static final int[] FLOAT_TOOLS = {
            TL_RESET,
            TL_RESET_ONE,
            TL_SELECT,
            TL_GRID,
            // 【布局切换必须留】
            //   「布」按钮在这个布局里是隐藏的（applyFloatLayout），
            //   再把这一项也藏了，就没有任何出口能切回别的布局 ——
            //   等于把自己锁在里面。
            TL_LAYOUT,
    };
    // ===== 列表 / 弹窗 / 分类 =====
    abstract void createCombo(boolean cross);
    abstract void setEditMode(boolean on, boolean save);
    abstract void resetOne(int i, boolean[] dims);
    static int fxColor(int st) {
        switch (st) {
            case ST_SHOW: return FXC_SHOW;
            case ST_HIDE: return FXC_HIDE;
            case ST_DEL:  return FXC_DEL;
            default:      return FXC_DEF;
        }
    }
    abstract void openCrossDir(int dir);
    abstract void openComboEditBack();
    abstract void createCombo();
    abstract void createBlank();
    abstract void comboEditClick(int pos);
    abstract void askCustomColor(boolean web);
    abstract float trackHalfH(int i);
    abstract void stickStep(int idx, int delta);
    abstract int sameNameIndex(int pos, String base);
    abstract float radiusOf(int i);
    abstract void openGridCfg();
    abstract void openFixedCfg();
    /**
     * 打开「优先级」子面板（面板原地换成优先级那条滑条）。
     *
     * 【为什么必须声明在这里】
     *   PickList 是父类，GamepadView 是子类。父类里点「优先级」要切面板子模式，
     *   调的就是子类实现的那个方法 —— 父类看不见子类的成员，
     *   不在这里留一条 abstract，编译器就报 Unknown method。
     *   和 openGridCfg / openFixedCfg 是同一套路。
     */
    abstract void openPrioPanel();
    abstract boolean isStickElem(int i);
    abstract float halfW(int i);
    abstract float halfH(int i);
    abstract void gridToggle(int pos);
    abstract boolean gridRowIsToggle(int pos);
    abstract Paint fxPaintOf(int color);
    abstract int fixUiState(int uiIdx, int tplIdx);
    abstract void fixUiCycle(int uiIdx, int tplIdx);
    abstract String fixRowName(int pos);
    abstract void fixCycle(int tpl, int code);
    abstract void exitEditMode();
    abstract void createPad(int pos);
    abstract void createMouse(int pos);
    abstract void comboToggleEndAfter(int pos);
    abstract boolean comboRowNoAutoIndex(int pos);
    abstract String comboRowName(int pos);
    abstract boolean comboRowIsKeyAct(int pos);
    abstract boolean comboRowIsAct(int pos);
    abstract int comboEditRowCount();
    abstract void comboDelAct(int pos);
    abstract String comboCrossRowName(int pos);
    abstract void toastLocal(String msg);
    abstract void swapElements(int a, int b);
    abstract void snapshotAdj();
    abstract boolean showRevertItem();
    abstract void setPanelState(int state);
    abstract public void setEditMode(boolean on);
    abstract void revertToLegacyPos();
    abstract void resetOne(int i);
    abstract void resetAllNow();
    abstract void releaseAll();
    abstract int rawPos(int pos);
    abstract void openMultiShape();
    abstract void openMultiAdj();
    abstract String[] moreRowNames();
    abstract boolean isTrigger(int i);
    abstract boolean isMulti();
    abstract boolean isDeletable(int i);
    abstract boolean inSubMode();
    abstract boolean hasQuery();
    abstract void gridStep(int pos, int delta);
    abstract String gridRowName(int pos);
    abstract int fixState(int tpl, int code);
    abstract void drawTrigRow(Canvas c, int pos, RectF r);
    /** 优先级条拖出来的值刷给当前选中的元素（在子类里改 PadLayout）。 */
    abstract void applyPrio(int v);
    abstract void drawStickXyRow(Canvas c, int pos, RectF r);
    abstract void drawGridRow(Canvas c, int pos, RectF r);
    abstract void drawComboRowButtons(Canvas c, int pos);
    abstract void doCustomColor();
    abstract int deletableCount();
    abstract String defaultLabelOf(int i);
    abstract void createKey(int keyPos);
    abstract void computeGeometry();
    abstract int comboFirstFreeSlot();
    abstract void closeGridCfg();
    abstract void buildFixRows(int tpl);
    /** 固定显示里往"额外创建"名单加一个（空白 / 组合键这类没种类可选的）。 */
    abstract void fixAddOne(String kind, boolean cross);
    abstract void applyAdjScale(float k);
    void afterComboPick() {
        if (mKeyKeepOpen) {
            int next = comboFirstFreeSlot();
            if (next >= 0) {
                mComboPickIdx = next;
                mLayout.save(getContext());
                // 标题上显示进度，否则连着加好几个不知道加到第几个了
                invalidate();
                return;
            }
            toastLocal("已经放满 " + PadLayout.MAX_COMBO_ACTS + " 个动作了");
        }
        mComboPickIdx = COMBO_PICK_NONE;
        mLayout.save(getContext());
        // 上级是「更多选项」，不是「常用工具」—— 和 openComboEditList 一致，
        // 否则返回会跳到一个跟组合键毫不相干的列表里。
        openListBack(LIST_COMBO_EDIT, LIST_MORE);
    }

    void applyLayoutFilter() {
        mLayoutFiltered.clear();
        // 布局列表的搜索在这里直接过滤 mLayoutFiltered，不走通用筛选表
        // （两套下标会打架）。空串 = 不过滤。
        String q = hasQuery()
                ? mListQuery.toLowerCase(java.util.Locale.getDefault()) : "";
        for (int i = 0; i < mLayoutMetas.size(); i++) {
            PadLayout.LayoutMeta m = mLayoutMetas.get(i);
            if (!metaMatchesCat(m)) {
                continue;
            }
            // 【「布」快速切换里不列悬浮窗布局】
            //   那个布局是"进去调悬浮球"的编辑场所，没有游戏键，
            //   打游戏时误切过去会整个手柄空掉。
            //   要进它走「常用工具 -> 布局切换」（LIST_LAYOUT），那边照常列出。
            if (mListMode == LIST_LAYOUT_PICK && m.id == PadLayout.LAYOUT_FLOAT) {
                continue;
            }
            if (q.length() > 0) {
                String nm = m.name == null ? "" : m.name.toLowerCase(
                        java.util.Locale.getDefault());
                // 时间也参与匹配：搜"2026"或"导入"都能命中
                String tm = PadLayout.metaTimeText(m).toLowerCase(
                        java.util.Locale.getDefault());
                if (!nm.contains(q) && !tm.contains(q)) {
                    continue;
                }
            }
            mLayoutFiltered.add(m);
        }
    }

    void applyListAdjust(int which, float x, float y) {
        float cy = y - mListViewRect.top + mListScroll;
        if (which == ADJ_TRIG) {
            float w = mTrigBarRect.width();
            if (w <= 0f) {
                return;
            }
            float t = (x - mTrigBarRect.left) / w;
            t = Math.max(0f, Math.min(1f, t));
            mTrigPct = Math.round(t * 100f);
            invalidate();
            return;
        }
        if (which == ADJ_STICK) {
            if (mStickPadR <= 0f) {
                return;
            }
            float tx = (x - mStickPadRect.centerX()) / mStickPadR * 100f;
            float ty = (cy - mStickPadRect.centerY()) / mStickPadR * 100f;
            mStickX = Math.max(-100, Math.min(100, Math.round(tx)));
            mStickY = Math.max(-100, Math.min(100, Math.round(ty)));
            invalidate();
        }
    }

    void applyPickedColor(int col) {
        /*
          【透明度只有一个来源：面板上那条「透明」滑条】

          以前颜色自己也带 alpha（#AARRGGBB），绘制时再乘上滑条的 alpha ——
          于是同一个按钮有两个透明度，显示的数字是滑条那个，
          实际看到的却是两者相乘的结果：
            滑条写着 80%，看着却像 64%；
            拖到 70% 再拖回 80%，看着还是不对得上。
          所以现在颜色一律存成不透明，颜色里带的 alpha **搬进** alpha[i]：
            挑色时改透明度 = 改滑条，两个地方永远是同一个数。

          挑的是不透明色（alpha = 255）时不动滑条 ——
          否则点个预设色就把辛辛苦苦调好的透明度冲成 100%。
        */
        /*
          【col == 0 是「默认」那一档，不是"黑色 + 15% 透明"】
            btnColor == 0 在绘制时的含义是"没设过色，用内置画笔"，
            所以点「默认」要做的是**清掉**自定义色并让透明度回到默认值。
            直接把 0 当颜色算的话：rgb 是 000000（黑）、alpha 是 0
            → 被夹到 MIN_ALPHA(15%) → 按钮变成"15% 的灰黑色块"，
              正好是"点了默认反而毁了外观"。
        */
        if (col == 0) {
            if (mColorPassState == 1) {
                mLayout.passOnColor = 0;
            } else if (mColorPassState == 2) {
                mLayout.passOffColor = 0;
            }
            for (int i = 0; i < PadLayout.N; i++) {
                if (!mColorSel[i]) {
                    continue;
                }
                if (i == PadLayout.I_PASS && mColorPassState != 0) {
                    mLayout.alpha[i] = PadLayout.defaultAlphaOf(i);
                    continue;
                }
                mLayout.btnColor[i] = 0;
                mLayout.alpha[i] = PadLayout.defaultAlphaOf(i);
            }
            if (mColorPassState != 0) {
                mLayout.alpha[PadLayout.I_PASS] =
                        PadLayout.defaultAlphaOf(PadLayout.I_PASS);
            }
            mLayout.save(getContext());
            return;
        }
        int ca = (col >>> 24) & 0xFF;
        int opaque = 0xFF000000 | (col & 0x00FFFFFF);
        // 分母用 256，和写出去时（GamepadView 里 a*256）保持一致；
        // ca == 255 表示"不透明"，不动滑条。
        float a = (ca < 255) ? Math.max(PadLayout.MIN_ALPHA, ca / 256f) : -1f;
        if (mColorPassState == 1) {
            mLayout.passOnColor = opaque;
        } else if (mColorPassState == 2) {
            mLayout.passOffColor = opaque;
        }
        // 多选里除了「透」还有别的键时，那支颜色照样给它们 ——
        // 否则整批改色只改了透，别的键看着像没反应。
        for (int i = 0; i < PadLayout.N; i++) {
            if (!mColorSel[i]) {
                continue;
            }
            if (i == PadLayout.I_PASS && mColorPassState != 0) {
                continue;
            }
            mLayout.btnColor[i] = opaque;
        }
        if (a > 0f) {
            // 穿透两支共用同一个 alpha（按钮只有一个，透明度也只能有一个）
            if (mColorPassState != 0) {
                mLayout.alpha[PadLayout.I_PASS] = a;
            }
            for (int i = 0; i < PadLayout.N; i++) {
                if (!mColorSel[i]) {
                    continue;
                }
                if (i == PadLayout.I_PASS && mColorPassState != 0) {
                    continue;
                }
                mLayout.alpha[i] = a;
            }
        }
        mLayout.save(getContext());
        /*
          【改的是当前看不到的那一支时，说一句】
            不然改完屏幕上纹丝不动，只当是功能坏了。
        */
        if (mColorPassState != 0) {
            boolean on = FloatingService.keyWindowsEnabled(getContext());
            if ((mColorPassState == 1) != on) {
                toastLocal("已设成穿透" + (mColorPassState == 1 ? "开" : "关")
                        + "时的颜色，切一下穿透就能看到");
            }
        }
    }

    public void applySearchQuery(String q) {
        mListQuery = (q == null) ? "" : q.trim();
        if (mListMode == LIST_NONE) {
            return;                    // 列表已经关了，回来时不用再筛
        }
        if (mListMode == LIST_LAYOUT || mListMode == LIST_LAYOUT_PICK) {
            applyLayoutFilter();       // 布局列表走自己的过滤
        } else {
            buildListFilter();
        }
        mListScroll = 0f;
        layoutList();
        invalidate();
        if (hasQuery() && listItemCount() == 0) {
            toastLocal("没有匹配「" + mListQuery + "」的项");
        }
    }

    void applySelectionFromList() {
        boolean any = false;
        for (int i = 0; i < PadLayout.N; i++) {
            mSelSet[i] = mListSel[i];
            if (mListSel[i]) {
                any = true;
            }
        }
        mSel = any ? selFirst() : NONE;
        mAdjAnchor = mSel;
        mAdjLayoutMode = false;
        mAdjK = 1f;
        if (mSel != NONE) {
            mAdjSizeVal = PadLayout.scaleToSlider(mLayout.scale[mSel]);
            mAdjAlphaVal = (mLayout.alpha[mSel] - PadLayout.MIN_ALPHA)
                    / (1f - PadLayout.MIN_ALPHA);
            mAdjTextVal = (mLayout.textScale[mSel] - PadLayout.MIN_TEXT_SCALE)
                    / (PadLayout.MAX_TEXT_SCALE - PadLayout.MIN_TEXT_SCALE);
        }
        snapshotAdj();
    }

    void askImportLayout() {
        mDlgMode = DLG_CONFIRM_IMPORT;
        // 【不能用 \n 手动换行】
        //   drawDlg 是按"逐字测量宽度"自动折行的，\n 只是个宽度极小的普通字符，
        //   既不会触发折行，画出来还是个方块。长文案交给自动折行就行。
        mDlgTitle = "选择要导入的文件，完成后点悬浮球重新打开手柄。（会跳到本界面，游戏切后台可能被杀）";
        mDlgTargetId = NONE;
        openDlg();
    }

    void askSearchList() {
        mDlgMode = DLG_CONFIRM_SEARCH;
        mDlgTitle = "搜索要跳到本应用界面输入，游戏会切到后台（可能被系统杀掉），继续？";
        mDlgTargetId = NONE;
        openDlg();
    }

    void batchDeleteNow() {
        for (int i = PadLayout.N - 1; i >= 0; i--) {
            if (!mListSel[i] || !isDeletable(i)) {
                continue;
            }
            if (PadLayout.isKeySlot(i)) {
                mLayout.removeKey(i);
            } else if (PadLayout.isBlankSlot(i)) {
                mLayout.removeBlank(i);
            } else {
                mLayout.removePad(i);
            }
        }
        clearSelection();
        mRayElem = NONE;
        computeGeometry();
        mLayout.save(getContext());
        closeList();
        invalidate();
    }

    void buildListFilter() {
        mListFilterCount = 0;
        if (!hasQuery() || !listUsesGenericFilter()) {
            return;
        }
        String q = mListQuery.toLowerCase(java.util.Locale.getDefault());
        int raw = listItemCountRaw();
        for (int i = 0; i < raw && mListFilterCount < mListFilter.length; i++) {
            String nm = listItemNameRaw(i);
            if (nm == null) {
                continue;
            }
            if (nm.toLowerCase(java.util.Locale.getDefault()).contains(q)) {
                mListFilter[mListFilterCount++] = i;
            }
        }
    }

    void buildListItems() {
        mListCount = 0;
        // 属性列表：让 pos 直接当 mListSel 的下标用。
        // 通用绘制 / 勾选逻辑走的是 mListSel[mListItems[pos]]，
        // 不这么填的话勾"位置"会去翻 mListSel[第 pos 个元素] —— 全串位。
        if (mListMode == LIST_RESET_DIM) {
            for (int k = 0; k < RD_COUNT; k++) {
                mListItems[k] = k;
            }
            mListCount = RD_COUNT;
            return;
        }
        // 同步方向：2 项，下标即方向号
        if (mListMode == LIST_SYNC_DIR) {
            for (int k = 0; k < 2; k++) {
                mListItems[k] = k;
            }
            mListCount = 2;
            return;
        }
        // 同步的属性列表：同样让 pos 直接当 mListSel 的下标
        if (mListMode == LIST_SYNC_DIM) {
            for (int k = 0; k < SD_COUNT; k++) {
                mListItems[k] = k;
            }
            mListCount = SD_COUNT;
            return;
        }
        // 【下标即语义的列表：mListItems 必须填成 0,1,2…】
        //
        //   这类列表的 pos 本身就是"第几个模板 / 第几种颜色"，
        //   不是按钮槽位。通用绘制读的是 mListSel[mListItems[pos]]，
        //   而点击写的是 mListSel[pos] —— 两者对不上就没有选中态：
        //   点下去看着毫无反应，值其实已经选上了（点「确定」照样生效）。
        //
        //   不填的话 mListItems 装的是通用槽位遍历的结果，
        //   前面会被"未创建的键盘槽位 / 空槽位"跳过，
        //   于是 mListItems[0] 未必是 0 —— 布局不同结果就不同，
        //   表现为"有时候有反馈、有时候没有"。
        if (mListMode == LIST_TPL) {
            for (int k = 0; k < TPL_NAMES.length; k++) {
                mListItems[k] = k;
            }
            mListCount = TPL_NAMES.length;
            return;
        }
        if (mListMode == LIST_FIX_TPL) {
            for (int k = 0; k < FX_TPL_NAMES.length; k++) {
                mListItems[k] = k;
            }
            mListCount = FX_TPL_NAMES.length;
            return;
        }
        if (mListMode == LIST_PASS_COLOR) {
            for (int k = 0; k < PASS_COLOR_NAMES.length; k++) {
                mListItems[k] = k;
            }
            mListCount = PASS_COLOR_NAMES.length;
            return;
        }
        if (mListMode == LIST_COMBO_KIND) {
            for (int k = 0; k < 2; k++) {
                mListItems[k] = k;
            }
            mListCount = 2;
            return;
        }
        if (mListMode == LIST_COMBO_STICK_MODE) {
            for (int k = 0; k < 2; k++) {
                mListItems[k] = k;
            }
            mListCount = 2;
            return;
        }
        if (mListMode == LIST_COMBO_CROSS) {
            for (int k = 0; k < 6; k++) {
                mListItems[k] = k;
            }
            mListCount = 6;
            return;
        }
        if (mListMode == LIST_APPEAR) {
            for (int k = 0; k < APPEAR_NAMES.length; k++) {
                mListItems[k] = k;
            }
            mListCount = APPEAR_NAMES.length;
            return;
        }
        // 批量删除：只列**已创建的键盘按键**。
        // 固定手柄键（A/B/X/Y、摇杆…）不能删，混在列表里会让人以为可以选。
        // 批量删除：只列**可删除**的（键盘按键 + 手柄元素，界面按钮除外）
        boolean delOnly = mListMode == LIST_DEL_KEY;
        // 挑中心按钮：只列参与调节的那批，单选。
        // 中心必须是被缩放的成员之一，否则"朝它聚拢"没有意义。
        boolean anchorOnly = mListMode == LIST_ADJ_ANCHOR;
        // 同步挑按钮：按**源方向**判断"这个槽位上有没有按钮"
        PadLayout base = (mListMode == LIST_SYNC_PICK && mSyncSrc != null)
                ? mSyncSrc : mLayout;
        for (int i = 0; i < PadLayout.N; i++) {
            if (anchorOnly && !mSelSet[i]) {
                continue;
            }
            boolean isKey = PadLayout.isKeySlot(i);
            if (delOnly) {
                if (!isDeletable(i)) {
                    continue;
                }
            } else if (isKey && !base.isKeySlotUsed(i)) {
                continue;
            }
            // 【手柄槽位同理：没放元素的槽位不进列表】
            //
            //   副本槽位（133..164）初始 padType 全是 0，
            //   它们的下标超出了 NAMES 的长度（只有 N_FIXED=22 项），
            //   nameOf 走兜底分支返回 "" —— 于是「隐藏按钮」这类列表里
            //   凭空多出 32 行**没有名字**的项，还能点、还能勾选。
            //
            //   删掉的手柄元素（padType 归 0）也一样要排除。
            //
            //   界面按钮（编 / 收 / 布）的 padType 恒为 0，
            //   但它们**必须留在列表里** —— 「隐藏按钮」要靠这个列表
            //   把它们放出来/收回去。所以单独放行。
            if (!delOnly && !isKey && PadLayout.isPadSlot(i)
                    && !PadLayout.isUiButton(i)
                    && !base.isPadUsed(i)) {
                continue;
            }
            // 组合键槽位：没建的也不进列表（合成 16 行没名字的空行）
            if (!delOnly && PadLayout.isComboSlot(i) && !base.isComboUsed(i)) {
                continue;
            }
            // 空白按钮槽位同理：没建的不进列表（它本来就没名字，
            // 列出来是一排完全看不出区别的空白行）。
            if (!delOnly && PadLayout.isBlankSlot(i) && !base.isBlankUsed(i)) {
                continue;
            }
            // 【鼠标六个槽位同理】
            //   它们是固定槽位，"默认鼠标"布局之外 padType 全是 0、
            //   名字也是空的 —— 不过滤的话，任何按钮列表里都会凭空
            //   多出 6 行没有名字的空项，还能勾选、还能点。
            //   和键盘 / 空白 / 组合键槽位用同一套判据。
            if (!delOnly && PadLayout.isMouseSlot(i) && !base.isMouseUsed(i)) {
                continue;
            }
            // 【悬浮窗布局里「收」不进任何按钮列表】
            //   这个布局里它是被 G 顶掉的：不画、也点不中（见 pickable）。
            //   列表里还留着一行就等于有个"选了也没用"的项 ——
            //   「选中按钮」点它选不中，「重置单个」重置完它还是隐形的。
            //
            //   【不要顺手把 G 也滤掉】
            //     在悬浮窗布局里 G 是主角，正要靠列表选中它来改外观。
            //     反过来在**别的**布局里它不画也不点，留着就是死行。
            //
            //   【也不要改成"跳过 hidden 的"】
            //     「隐藏按钮」列表本身就是操作 hidden 的，见上面那条注释。
            if (i == PadLayout.I_COLLAPSE && isFloatLayout()) {
                continue;
            }
            if (i == PadLayout.I_FLOAT && !isFloatLayout()) {
                continue;
            }
            // 【这里不要加 hidden 过滤】
            //   「隐藏按钮」列表本身就是要操作 hidden 的：
            //   加一句"跳过隐藏的"，已隐藏的按钮就不会出现在那个列表里，
            //   于是再也取消不掉隐藏 —— 等于把自己锁死。
            //   LIST_SELECT 也因此需要隐藏按钮可见，正好一致。
            // 分类筛选：只留当前分类的按钮。
            // 键盘列表（LIST_KEY）不是按钮列表，不受影响。
            if (listHasCategory() && !elemMatchesCat(i)) {
                continue;
            }
            mListItems[mListCount] = i;
            mListCount++;
        }
    }

    int catCount() {
        if (mListMode == LIST_TOOL) {
            return TCAT_MAX;
        }
        return isLayoutCatList() ? CAT_MAX_LAYOUT : CAT_MAX;
    }

    String catName(int i) {
        if (mListMode == LIST_TOOL) {
            return TCAT_NAMES[i];
        }
        return isLayoutCatList() ? CAT_NAMES_LAYOUT[i] : CAT_NAMES[i];
    }

    void clearSelection() {
        for (int i = 0; i < PadLayout.N; i++) {
            mSelSet[i] = false;
        }
        // 【子模式必须跟着退】
        //   形状 / 摇杆设置 / 优先级都是"针对选中项"的子模式，没选中了就该退出。
        //   留着 mShapeMode=true 而 mSel=NONE 的话，滑条绘制会去读
        //   mLayout.widthMul[-1] —— 下标 -1，直接数组越界崩溃。
        //   （实测：形状模式下点画布空白处就崩，就是这里。）
        mShapeMode = false;
        mStickCfg = false;
        mPrioMode = false;
        mSel = NONE;
        mAdjLayoutMode = false;
        mAdjAnchor = NONE;
        mAdjK = 1f;
    }

    void closeDlg() {
        mDlgMode = DLG_NONE;
        mDlgTargetId = -1;
        invalidate();
    }

    void closeList() {
        mListBackStack.clear();
        // 列表没了，拖控件的状态也得跟着没 ——
        // 留着的话下次开别的列表，MOVE 会被它截走、滑不动。
        mListAdjust = ADJ_NONE;
        mListAdjustPointer = NONE;
        mListMode = LIST_NONE;
        // 「固定显示」的挑选态：退出就清掉。
        // 不清的话下次正常「手柄按键…」创建时会被误判成挑选，
        // 键没建出来，还跳回一个已经关掉的列表。
        mFixPicking = false;
        // 「新建」是布局列表专属，退出就清掉
        mListCreateRect.setEmpty();
        // 退出就恢复成单次创建：不重置的话下次打开是上一次的状态，
        // 而它是个"临时开关"，不是持久设置。
        mKeyKeepOpen = false;
        // 分类也一并重置回「全部」。
        //
        // 之前特意保留筛选条件，但实测是反直觉的：
        // 在「隐藏按钮」里选了「键盘」、点取消退出，
        // 再进「重置单个」时列表默认只显示键盘类 —— 而界面上
        // 没有任何提示说"你正在某个筛选里"，很容易以为按钮丢了。
        // 分类是临时视角，不是持久设置，退出就该回到全部。
        mListCat = CAT_ALL;
        mListCatOpen = false;
        for (int i = 0; i < PadLayout.N; i++) {
            mListSel[i] = false;
        }
        for (int k = 0; k < SWAP_MAX; k++) {
            mSwapPick[k] = NONE;
        }
    }

    void confirmDlg() {
        switch (mDlgMode) {
            case DLG_CONFIRM_DEL:
                if (mDlgTargetId >= PadLayout.LAYOUT_USER_START) {
                    PadLayout.deleteLayout(getContext(), mDlgTargetId);
                    reloadLayoutMetas();
                    layoutList();
                }
                break;
            case DLG_CONFIRM_RESET_ALL:
                resetAllNow();
                break;
            case DLG_CONFIRM_RESET_ONE:
                resetSelectedNow();
                break;
            case DLG_CONFIRM_DEL_KEY:
                if (mDlgTargetId >= 0) {
                    if (PadLayout.isKeySlot(mDlgTargetId)) {
                        mLayout.removeKey(mDlgTargetId);
                    } else if (PadLayout.isBlankSlot(mDlgTargetId)) {
                        mLayout.removeBlank(mDlgTargetId);
                    } else if (PadLayout.isComboSlot(mDlgTargetId)) {
                        mLayout.removeCombo(mDlgTargetId);
                    } else if (PadLayout.isMouseSlot(mDlgTargetId)) {
                        mLayout.removeMouse(mDlgTargetId);
                    } else {
                        mLayout.removePad(mDlgTargetId);
                    }
                    clearSelection();
                    mRayElem = NONE;
                    computeGeometry();
                    mLayout.save(getContext());
                }
                break;
            case DLG_CONFIRM_BATCH_DEL:
                batchDeleteNow();
                break;
            case DLG_CONFIRM_RENAME:
                renameLayoutNow(mDlgTargetId);
                break;
            case DLG_CONFIRM_COMBO_RENAME:
                if (mDlgTargetId >= 0 && PadLayout.isComboSlot(mDlgTargetId)
                        && mLayout.isComboUsed(mDlgTargetId)) {
                    mSink.requestRenameCombo(mDlgTargetId,
                            mLayout.comboName[mDlgTargetId]);
                }
                break;
            case DLG_CONFIRM_KEY_RENAME:
                if (mDlgTargetId >= 0 && mSink != null) {
                    mSink.requestRenameKey(mDlgTargetId,
                            mLayout.displayName(mDlgTargetId,
                                    defaultLabelOf(mDlgTargetId)));
                }
                break;
            case DLG_CONFIRM_IMPORT:
                doImportLayout();
                break;
            case DLG_CONFIRM_SEARCH:
                doSearchList();
                break;
            case DLG_CONFIRM_KEY_COLOR:
                doCustomColor();
                break;
            default:
                break;
        }
        closeDlg();
    }

    void confirmList() {
        switch (mListMode) {
            case LIST_COMBO_STICK_XY: {
                if (mComboDirSlot == NONE || mComboDirProto == NONE) {
                    return;
                }
                mLayout.setComboAct(mComboEditIdx, mComboEditDir, mComboDirSlot,
                        COMBO_ACT_STICK_XY, mComboDirProto,
                        PadLayout.packStick(mStickX, mStickY));
                mComboDirSlot = NONE;
                mComboDirProto = NONE;
                syncComboAutoName();
                mLayout.save(getContext());
                if (mKeyKeepOpen) {
                    int next = comboFirstFreeSlot();
                    if (next >= 0) {
                        mComboPickIdx = next;
                        openList(LIST_PAD);
                        return;
                    }
                }
                openListBack(LIST_COMBO_EDIT, LIST_MORE);
                return;
            }
            case LIST_COMBO_TRIG: {
                if (mComboDirSlot == NONE || mComboDirProto == NONE) {
                    return;
                }
                mLayout.setComboAct(mComboEditIdx, mComboEditDir, mComboDirSlot, COMBO_ACT_PAD,
                        PadLayout.typeOfElem(mComboDirProto), mTrigPct);
                mComboDirSlot = NONE;
                mComboDirProto = NONE;
                syncComboAutoName();
                mLayout.save(getContext());
                if (mKeyKeepOpen) {
                    int next = comboFirstFreeSlot();
                    if (next >= 0) {
                        mComboPickIdx = next;
                        openList(LIST_PAD);
                        return;
                    }
                }
                openListBack(LIST_COMBO_EDIT, LIST_MORE);
                return;
            }
            case LIST_LINK:
                // 【关联挪动 = 多选】
                //   原来它单独维护一份 mLinked 联动组，拖动时跟着走。
                //   现在统一成"选中集合"：勾了哪几个就选中哪几个，
                //   拖其中一个 -> 整批一起走（见 ACTION_MOVE 里的分支）。
                //   三个入口（这个 / 多选调节 / 多选形状）写的是同一份集合。
                applySelectionFromList();
                break;
            case LIST_RESET_DIM: {
                // 记下勾选的属性，再去选按钮。
                // openList() 会清 mListSel，必须先搬到 mResetDims。
                for (int k = 0; k < RD_COUNT; k++) {
                    mResetDims[k] = mListSel[k];
                }
                openList(LIST_RESET_ONE);
                return;
            }
            case LIST_SYNC_DIR: {
                // 第零步：定方向，再去挑按钮
                mSyncSrcPortrait = false;
                for (int k = 0; k < 2; k++) {
                    if (mListSel[k]) {
                        mSyncSrcPortrait = (k == SDIR_FROM_PORT);
                        break;
                    }
                }
                mSyncSrc = PadLayout.load(getContext(), mW, mH, mSyncSrcPortrait,
                        mSyncLayoutId, NO_BOTTOM_LIMIT, mTopGuard);
                openList(LIST_SYNC_PICK);
                return;
            }
            case LIST_SYNC_PICK: {
                // 第一步：记下勾了哪些按钮，再去挑要复制哪些信息
                for (int i = 0; i < PadLayout.N; i++) {
                    mSyncSel[i] = mListSel[i];
                }
                openList(LIST_SYNC_DIM);
                return;
            }
            case LIST_SYNC_DIM: {
                // 第二步：记下属性，然后立刻执行一次同步（不是持续生效）
                for (int k = 0; k < SD_COUNT; k++) {
                    mSyncDims[k] = mListSel[k];
                }
                doOrientSync();
                return;
            }
            case LIST_RESET_ONE: {
                // 重置会把手动摆的位置清掉，先确认。
                // 不能在这里 closeList —— 对话框要盖在列表上，
                // 而 mListSel 里的勾选状态 confirmDlg 还要用。
                int cnt = 0;
                for (int i = 0; i < PadLayout.N; i++) {
                    if (mListSel[i]) {
                        cnt++;
                    }
                }
                mDlgMode = DLG_CONFIRM_RESET_ONE;
                mDlgTitle = "重置这 " + cnt + " 个按钮？它们会回到默认位置，手动摆放的会丢失。";
                openDlg();
                return;
            }
            case LIST_SWAP:
                if (mSwapPick[0] != NONE && mSwapPick[1] != NONE) {
                    swapElements(mSwapPick[0], mSwapPick[1]);
                }
                break;
            case LIST_HIDE:
                // 隐藏是按"最终勾选状态"整体覆盖，不是只加不减 ——
                // 取消勾选再确定 = 恢复显示，这样才有办法把按钮找回来。
                for (int i = 0; i < PadLayout.N; i++) {
                    mLayout.hidden[i] = mListSel[i];
                }
                mLayout.save(getContext());
                break;
            case LIST_MULTI_ADJ:
                // 勾好的那批交给调节面板，面板内部会 closeList
                openMultiAdj();
                return;
            case LIST_MULTI_SHAPE:
                openMultiShape();
                return;
            case LIST_ADJ_ANCHOR: {
                // 选定中心按钮：回到调节面板，并按当前倍率重算一次，
                // 让"换个中心"立刻看到聚拢方向的变化。
                int pick = mSwapPick[0];
                closeList();
                if (pick != NONE) {
                    mAdjAnchor = pick;
                }
                snapshotAdj();
                applyAdjScale(mAdjK);
                mLayout.save(getContext());
                setPanelState(PANEL_FULL);
                invalidate();
                return;
            }
            case LIST_TPL:
                // 模板选定 -> 建布局并切过去。switchLayout 内部会 closeList，
                // 所以这里不能再调一次 closeList（会把 mLayoutMetas 清掉）。
                createLayoutWithTpl(mPendingTpl);
                return;
            case LIST_DEL_KEY: {
                // 至少选一个由 listConfirmEnabled() 把关（默认分支就是 >0），
                // 走到这里 mListSel 里至少有一个。
                int cnt = 0;
                for (int i = 0; i < PadLayout.N; i++) {
                    if (mListSel[i] && isDeletable(i)) {
                        cnt++;
                    }
                }
                mDlgMode = DLG_CONFIRM_BATCH_DEL;
                mDlgTitle = "删除这 " + cnt + " 个按钮？删掉就找不回来了。";
                openDlg();
                return;
            }
            default:
                break;
        }
        closeList();
        invalidate();
    }

    public int countPickable() {
        if (mLayout == null) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < PadLayout.N; i++) {
            if (pickable(i)) {
                n++;
            }
        }
        return n;
    }

    void createLayoutWithTpl(int tpl) {
        int n = PadLayout.loadMetas(getContext()).size();
        String name = "布局" + (n + 1);
        int id = PadLayout.createLayout(getContext(), name, tpl);
        if (id < 0) {
            toastLocal("布局数量已达上限");
            closeList();
            invalidate();
            return;
        }
        switchLayout(id);
    }

    public int currentLayoutId() {
        return mLayout != null ? mLayout.layoutId : PadLayout.LAYOUT_DEFAULT_PAD;
    }

    void deleteLayoutAt(int pos) {
        if (pos < 0 || pos >= mLayoutFiltered.size()) {
            return;
        }
        PadLayout.LayoutMeta m = mLayoutFiltered.get(pos);
        if (m.builtin) {
            toastLocal("内置布局不能删除");
            return;
        }
        if (mLayout != null && mLayout.layoutId == m.id) {
            toastLocal("正在用这个布局，先切到别的再删");
            return;
        }
        mDlgMode = DLG_CONFIRM_DEL;
        mDlgTitle = "确定删除「" + m.name + "」？竖屏和横屏的摆放都会一起删掉。";
        mDlgTargetId = m.id;
        openDlg();
    }

    void deleteSelectedKey() {
        if (!showDeleteItem()) {
            return;
        }
        if (PadLayout.isKeySlot(mSel)) {
            mLayout.removeKey(mSel);
        } else if (PadLayout.isBlankSlot(mSel)) {
            mLayout.removeBlank(mSel);
        } else if (PadLayout.isComboSlot(mSel)) {
            mLayout.removeCombo(mSel);
        } else {
            mLayout.removePad(mSel);
        }
        clearSelection();
        mRayElem = NONE;
        mLayout.save(getContext());
        invalidate();
        toastLocal("已删除");
    }

    String dlgOkText() {
        switch (mDlgMode) {
            case DLG_CONFIRM_RESET_ALL:
            case DLG_CONFIRM_RESET_ONE:
                return "重置";
            case DLG_CONFIRM_RENAME:
            case DLG_CONFIRM_COMBO_RENAME:
            case DLG_CONFIRM_KEY_RENAME:
            case DLG_CONFIRM_KEY_COLOR:
            case DLG_CONFIRM_IMPORT:
            case DLG_CONFIRM_SEARCH:
                // 这几个是"要离开游戏"的确认，写「删除」会吓一跳。
                //
                // 【KEY_RENAME 之前漏在这里】
                //   它掉进 default 返回了"删除" —— 而 default 本来是给
                //   DEL / DEL_KEY / BATCH_DEL 那几个真删除用的。
                //   于是改名弹窗的确认键写着"删除"，点了却是改名。
                //   现在凡是"跳出去输东西"的一律写「继续」。
                return "继续";
            default:
                return "删除";
        }
    }

    void doImportLayout() {
        if (mSink == null) {
            return;
        }
        mSink.requestImportLayout();
    }

    void doOrientSync() {
        closeList();
        int n = PadLayout.syncFromOtherOrient(getContext(), mSyncLayoutId,
                mLayout != null && mLayout.keyboardMode, mSyncSrcPortrait,
                mSyncSel, mSyncDims);
        if (n <= 0) {
            toastLocal((mSyncSrcPortrait ? "竖屏" : "横屏") + "还没有这份按钮的记录，没得同步");
            invalidate();
            return;
        }
        // 目标 = 源的另一个方向。只有它正好是当前方向才需要重载，
        // 否则改的是另一份存档，转屏才看得到 —— 这时也说清楚往哪边写了。
        boolean dstPortrait = !mSyncSrcPortrait;
        if (dstPortrait == mPortrait) {
            mLayout = PadLayout.load(getContext(), mW, mH, mPortrait,
                    mSyncLayoutId, NO_BOTTOM_LIMIT, mTopGuard);
            computeGeometry();
        }
        toastLocal("已把" + (mSyncSrcPortrait ? "竖屏" : "横屏") + "的 " + n
                + " 个按钮复制到" + (dstPortrait ? "竖屏" : "横屏"));
        invalidate();
    }

    void doSearchList() {
        if (mSink == null) {
            return;
        }
        mSink.requestSearchList();
    }

    void drawBanner(Canvas c) {
        if (mBannerText == null) {
            return;
        }
        if (System.currentTimeMillis() >= mBannerUntil) {
            mBannerText = null;
            return;
        }
        if (mW <= 0 || mH <= 0) {
            return;
        }
        float maxW = mW * 0.76f;
        float ts = Math.max(dp(13f), Math.min(dp(17f), mW * 0.034f));
        mBarTextPaint.setTextSize(ts);
        mBarTextPaint.setTextAlign(Paint.Align.LEFT);
        mBarTextPaint.setColor(0xFFFFFFFF);

        // 中文没有空格，只能逐字累加、超宽就断行
        java.util.ArrayList<String> lines = new java.util.ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < mBannerText.length(); i++) {
            char ch = mBannerText.charAt(i);
            if (ch == '\n') {
                lines.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            cur.append(ch);
            if (mBarTextPaint.measureText(cur.toString()) > maxW
                    && cur.length() > 1) {
                lines.add(cur.substring(0, cur.length() - 1));
                cur = new StringBuilder(String.valueOf(ch));
            }
        }
        if (cur.length() > 0) {
            lines.add(cur.toString());
        }

        float lineH = ts * 1.4f;
        float pad = dp(12f);
        float boxW = 0f;
        for (String s : lines) {
            boxW = Math.max(boxW, mBarTextPaint.measureText(s));
        }
        boxW += pad * 2f;
        float boxH = lines.size() * lineH + pad * 2f;
        // 放中上部：底部是编辑面板和界面按钮，顶部只有状态栏区域
        float left = (mW - boxW) / 2f;
        float top = mH * 0.18f;

        int save = c.save();
        mPanelPaint.setColor(0xE6000000);
        c.drawRoundRect(left, top, left + boxW, top + boxH,
                dp(10f), dp(10f), mPanelPaint);
        // 还原成面板默认色，别把后面的绘制带歪
        mPanelPaint.setColor(0xE8000000);

        mBarTextPaint.setTextAlign(Paint.Align.LEFT);
        mBarTextPaint.setColor(0xFFFFFFFF);
        Paint.FontMetrics fm = mBarTextPaint.getFontMetrics();
        float y = top + pad + lineH / 2f - (fm.ascent + fm.descent) / 2f;
        for (String s : lines) {
            c.drawText(s, left + pad, y, mBarTextPaint);
            y += lineH;
        }
        c.restoreToCount(save);
    }

    void drawDlg(Canvas c) {
        RectF p = mDlgPanelRect;
        c.drawRoundRect(p, dp(14f), dp(14f), mPanelPaint);

        mBarTextPaint.setTextAlign(Paint.Align.CENTER);
        mBarTextPaint.setTextSize(dp(16f));
        mBarTextPaint.setColor(0xFFFFFFFF);

        // 现在只用于"删除确认"（改名已交给 app 界面），
        // 提示语可能很长，按可用宽度自动换行。
        float maxW = p.width() - dp(28f);
        java.util.ArrayList<String> lines = new java.util.ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < mDlgTitle.length(); i++) {
            char ch = mDlgTitle.charAt(i);
            cur.append(ch);
            if (mBarTextPaint.measureText(cur.toString()) > maxW) {
                lines.add(cur.substring(0, cur.length() - 1));
                cur = new StringBuilder(String.valueOf(ch));
            }
        }
        if (cur.length() > 0) {
            lines.add(cur.toString());
        }
        Paint.FontMetrics f = mBarTextPaint.getFontMetrics();
        float lh = f.descent - f.ascent;
        float y0 = p.top + dp(26f);
        for (int i = 0; i < lines.size(); i++) {
            c.drawText(lines.get(i), p.centerX(), y0 + i * lh, mBarTextPaint);
        }

        drawListButton(c, mDlgCancelRect, "取消", true);
        drawListButton(c, mDlgOkRect, dlgOkText(), true);
    }

    void drawListButton(Canvas c, RectF r, String text, boolean enabled) {
        c.drawRoundRect(r, dp(8f), dp(8f), enabled ? mToolBtnPaint : mSlTrackPaint);
        mBarTextPaint.setTextAlign(Paint.Align.CENTER);
        mBarTextPaint.setTextSize(dp(16f));
        mBarTextPaint.setColor(enabled ? 0xFF212121 : 0x88FFFFFF);
        Paint.FontMetrics f = mBarTextPaint.getFontMetrics();
        c.drawText(text, r.centerX(), r.centerY() - (f.ascent + f.descent) / 2f,
                mBarTextPaint);
    }

    void drawListCategory(Canvas c) {
        RectF r = mListCatRect;
        if (r.isEmpty()) {
            return;
        }
        String label = catName(mListCat) + (mListCatOpen ? " ^" : " v");

        c.drawRoundRect(r, dp(8f), dp(8f),
                mListCatOpen ? mSlFillPaint : mSlTrackPaint);
        mBarTextPaint.setTextAlign(Paint.Align.CENTER);
        mBarTextPaint.setTextSize(dp(14f));
        mBarTextPaint.setColor(0xFFFFFFFF);
        Paint.FontMetrics f = mBarTextPaint.getFontMetrics();
        c.drawText(label, r.centerX(),
                r.centerY() - (f.ascent + f.descent) / 2f, mBarTextPaint);

        if (!mListCatOpen) {
            return;
        }
        // 浮层盖在列表内容之上，所以每项都得有自己的背景 ——
        // 不画背景的话底下的行会透上来，字叠字一片糊。
        int n = catCount();
        for (int i = 0; i < n; i++) {
            RectF it = mListCatItemRects[i];
            boolean on = i == mListCat;
            c.drawRoundRect(it, dp(8f), dp(8f), on ? mSlFillPaint : mPanelPaint);
            mBarTextPaint.setColor(on ? 0xFFFFFFFF : 0xFF9E9E9E);
            Paint.FontMetrics fi = mBarTextPaint.getFontMetrics();
            c.drawText(catName(i), it.centerX(),
                    it.centerY() - (fi.ascent + fi.descent) / 2f, mBarTextPaint);
        }
        mBarTextPaint.setColor(0xFFFFFFFF);
    }

    void drawListScrollBar(Canvas c) {
        if (mListScrollMax <= 0f) {
            return;
        }
        float viewH = mListViewRect.height();
        if (viewH <= 0f) {
            return;
        }
        float thumbH = Math.max(dp(28f), viewH * (viewH / mListContentH));
        float t = mListScroll / mListScrollMax;
        float thumbTop = mListViewRect.top + (viewH - thumbH) * t;
        float right = mListViewRect.right - dp(2f);
        mTmpRect.set(right - dp(4f), thumbTop, right, thumbTop + thumbH);
        c.drawRoundRect(mTmpRect, dp(2f), dp(2f), mScrollBarPaint);
    }

    void drawListSelAll(Canvas c) {
        RectF r = mListSelAllRect;
        if (r.isEmpty()) {
            return;
        }
        boolean allOn = isAllSelectedInCat();
        c.drawRoundRect(r, dp(8f), dp(8f), allOn ? mSlFillPaint : mSlTrackPaint);
        mBarTextPaint.setTextAlign(Paint.Align.CENTER);
        mBarTextPaint.setTextSize(dp(13f));
        mBarTextPaint.setColor(0xFFFFFFFF);
        Paint.FontMetrics f = mBarTextPaint.getFontMetrics();
        c.drawText(allOn ? "取消" : "全选", r.centerX(),
                r.centerY() - (f.ascent + f.descent) / 2f, mBarTextPaint);
    }

    void drawPickList(Canvas c) {
        RectF p = mListPanelRect;
        c.drawRoundRect(p, dp(14f), dp(14f), mPanelPaint);

        String title;
        switch (mListMode) {
            case LIST_LINK:
                title = "关联挪动：勾选要一起移动的";
                break;
            case LIST_SWAP:
                title = "互换位置：选两个";
                break;
            case LIST_HIDE:
                title = "隐藏按钮：勾选要隐藏的（可多选）";
                break;
            case LIST_TOOL:
                title = "常用工具";
                break;
            case LIST_KEY:
                title = "键盘按键：点一个就创建";
                break;
            case LIST_DEL_KEY:
                title = "批量删除：勾选要删的按钮";
                break;
            case LIST_LAYOUT:
                title = "布局切换：" + (mPortrait ? "竖屏" : "横屏");
                break;
            case LIST_LAYOUT_PICK:
                title = "切换到（" + (mPortrait ? "竖屏" : "横屏") + "）";
                break;
            case LIST_TPL:
                title = "新建布局：选一个模板";
                break;
            default:
                title = "重置单个：勾选要恢复的（可多选）";
                break;
            case LIST_SELECT:
                title = "选中按钮：点一个就选中它";
                break;
            case LIST_MULTI_ADJ:
                title = "多选调节：勾至少两个（已选 " + listSelCount() + "）";
                break;
            case LIST_MULTI_SHAPE:
                title = "多选形状：勾至少两个（已选 " + listSelCount() + "）";
                break;
            case LIST_ADJ_ANCHOR:
                title = "选中心按钮：缩的时候其他键朝它聚";
                break;
            case LIST_PAD:
                title = "手柄按键：点一个就创建";
                break;
            case LIST_COMBO_DELAY:
                title = "延迟多久";
                break;
            case LIST_COMBO_DIR:
                title = "推哪个方向";
                break;
            case LIST_COMBO_STICK_MODE:
                title = "怎么设置摇杆";
                break;
            case LIST_COMBO_STICK_XY:
                title = "自定义 X / Y";
                break;
            case LIST_COMBO_TRIG:
                title = "扳机力度";
                break;
            case LIST_COMBO_KIND:
                title = "做成哪种";
                break;
            case LIST_COMBO_CROSS:
                title = "十字架（点方向编辑）";
                break;
            case LIST_PASS_COLOR:
                title = "穿透按钮：设哪一支颜色";
                break;
            case LIST_BTN_COLOR:
                // 【之前缺这一支，落进 default 显示成"重置单个"】
                //   色板是后加的列表模式，标题 switch 没跟着补，
                //   default 那句本来是给重置单个兜底的 —— 于是改背景颜色
                //   的界面顶着"重置单个"四个字。
                //
                // 【这里不能用 listSelCount()】
                //   那个数的是 mListSel —— 色板是"点一项就生效"，
                //   压根不往 mListSel 里勾，所以它恒为 0：
                //   明明选了按钮，标题却写"应用到 0 个"。
                //   色板看的是 mSelSet（画布上的选中），用 selCount()。
                title = (mColorPassState == 0)
                        ? "改背景颜色：选一个（应用到 " + selCount() + " 个）"
                        : "改" + PASS_COLOR_NAMES[mColorPassState - 1];
                break;
            case LIST_CREATE:
                title = "按键创建：选类型";
                break;
            case LIST_MOUSE:
                title = "鼠标键：选一个";
                break;
            case LIST_MORE:
                title = "更多选项";
                break;
            case LIST_APPEAR:
                title = "外观";
                break;
            case LIST_COMBO_EDIT:
                title = (mComboEditIdx != NONE && mLayout.isComboUsed(mComboEditIdx))
                        ? ("组合键：" + mLayout.comboName[mComboEditIdx]) : "组合键";
                break;
            case LIST_COMBO_TYPE:
                title = "这个键是手柄键还是键盘键";
                break;
            case LIST_GRID:
                title = "网格 / 吸附";
                break;
            case LIST_RESET_DIM:
                title = "重置哪些属性（至少选一个）";
                break;
            case LIST_SYNC_DIR:
                title = "同步方向：把哪边的复制过来";
                break;
            case LIST_SYNC_PICK:
                title = "勾要同步的按钮（" + (mSyncSrcPortrait ? "复制到横屏" : "复制到竖屏")
                        + "，可多选）";
                break;
            case LIST_SYNC_DIM:
                title = "同步哪些信息（至少选一个，位置不同步）";
                break;
            case LIST_FIX_TPL:
                title = "固定显示：选模板";
                break;
            case LIST_FIX:
                // mFixTpl 只可能是 0..3（走 FX_UI_ENTRY 那条路时
                // buildFixRows 会提前 return，不会把 4 写进 mFixTpl），
                // 但 TPL_NAMES 里没有"功能键"这一项，越界保护留着，
                // 免得以后加模板忘了同步这里就显示成空标题。
                title = mFixUiMode ? "功能键：点「空/手/键/鼠」切换"
                        : "固定显示：" + ((mFixTpl >= 0 && mFixTpl < TPL_NAMES.length)
                        ? TPL_NAMES[mFixTpl] : "模板") + "（点行切换）";
                break;
        }
        mBarTextPaint.setTextAlign(Paint.Align.CENTER);
        mBarTextPaint.setTextSize(dp(17f));
        mBarTextPaint.setColor(0xFFFFFFFF);
        // 键盘列表右上角有「连续创建」，标题要给它让位。
        //
        // 小屏（480px 宽）上面板只有 136dp，标题按 17sp 排不下会被压住。
        // 所以先量文字宽度，放不下就逐档缩小（最低 11sp），
        // 并且在"左内边距 ~ 勾选框左端"这段剩余空间里居中，
        // 而不是整个面板居中 —— 否则标题照样往右顶到勾选框。
        float titleCx = p.centerX();
        if (listHasCategory()) {
            // 两端都可能被按钮占（左「全选」、右「分类」），
            // 标题按剩余宽度缩字号，并在剩余空间里居中。
            float leftLimit = mListSelAllRect.isEmpty()
                    ? p.left + mListPad : mListSelAllRect.right + dp(6f);
            float rightLimit = mListCatRect.isEmpty()
                    ? p.right - mListPad : mListCatRect.left - dp(6f);
            float availW = rightLimit - leftLimit;
            float ts = dp(17f);
            while (ts > dp(11f) && mBarTextPaint.measureText(title) > availW) {
                ts -= dp(1f);
                mBarTextPaint.setTextSize(ts);
            }
            titleCx = (leftLimit + rightLimit) / 2f;
        } else if (mListMode == LIST_KEY || mListMode == LIST_PAD) {
            float rightLimit = mKeepOpenRect.left - dp(6f);
            float availW = rightLimit - (p.left + mListPad);
            float ts = dp(17f);
            while (ts > dp(11f) && mBarTextPaint.measureText(title) > availW) {
                ts -= dp(1f);
                mBarTextPaint.setTextSize(ts);
            }
            titleCx = (p.left + mListPad + rightLimit) / 2f;
        }
        Paint.FontMetrics tf = mBarTextPaint.getFontMetrics();
        c.drawText(title, titleCx, p.top + dp(30f)
                - (tf.ascent + tf.descent) / 2f, mBarTextPaint);

        // 图例：光看颜色容易把"红"误读成"隐藏"，其实红=删除、
        // 蓝才是隐藏。两页都画 —— 模板页现在整行也上色了，没说明一样看不懂。
        // 功能键页没有"删除"那一档，图例里那格就跳过。
        if (mListMode == LIST_FIX) {
            float ly = p.top + dp(52f);
            float lx = p.left + mListPad + dp(10f);
            float legendAvail = p.right - mListPad - dp(10f) - lx;
            // 小屏上面板只有 300dp 左右，12sp 排三条会顶出去，
            // 先量总宽，放不下就逐档缩字号（最低 8sp）。
            float lts = dp(12f);
            while (lts > dp(8f)) {
                mBarTextPaint.setTextSize(lts);
                float need = 0f;
                for (String t : LEGEND_TEXT) {
                    need += dp(11f) + dp(5f) + mBarTextPaint.measureText(t) + dp(14f);
                }
                if (need <= legendAvail) {
                    break;
                }
                lts -= dp(1f);
            }
            mBarTextPaint.setTextSize(lts);
            mBarTextPaint.setTextAlign(Paint.Align.LEFT);
            Paint.FontMetrics lf = mBarTextPaint.getFontMetrics();
            for (int i = 0; i < LEGEND_TEXT.length; i++) {
                // 功能键页不能删除功能键（删了「编」就再也进不了编辑模式），
                // 所以"删除"那格不画，免得以为能删。
                if (mFixUiMode && LEGEND_COLOR[i] == FXC_DEL) {
                    continue;
                }
                float sw = dp(11f);
                mFixCapPaint.setColor(LEGEND_COLOR[i]);
                mTmpRect.set(lx, ly - sw / 2f, lx + sw, ly + sw / 2f);
                c.drawRoundRect(mTmpRect, dp(3f), dp(3f), mFixCapPaint);
                mBarTextPaint.setColor(0xFFDDDDDD);
                c.drawText(LEGEND_TEXT[i], lx + sw + dp(5f),
                        ly - (lf.ascent + lf.descent) / 2f, mBarTextPaint);
                lx += sw + dp(5f) + mBarTextPaint.measureText(LEGEND_TEXT[i]) + dp(14f);
            }
            mBarTextPaint.setTextAlign(Paint.Align.CENTER);
            mBarTextPaint.setTextSize(dp(17f));
            mBarTextPaint.setColor(0xFFFFFFFF);
        }

        // 「连续创建」勾选框（键盘列表 + 手柄列表，两者都是"点一个就创建"）
        if (mListMode == LIST_KEY || mListMode == LIST_PAD) {
            RectF k = mKeepOpenRect;
            c.drawRoundRect(k, dp(6f), dp(6f),
                    mKeyKeepOpen ? mSlFillPaint : mSlTrackPaint);
            // 小方框：勾上就填白，没勾就只画边框
            float bs = k.height() * 0.42f;
            mTmpRect.set(k.left + dp(6f), k.centerY() - bs / 2f,
                    k.left + dp(6f) + bs, k.centerY() + bs / 2f);
            setA(mRingPaint, 1f);
            c.drawRect(mTmpRect, mKeyKeepOpen ? mBarTextPaint : mRingPaint);
            resetA(mRingPaint);
            mBarTextPaint.setTextAlign(Paint.Align.LEFT);
            mBarTextPaint.setTextSize(dp(12f));
            mBarTextPaint.setColor(0xFFFFFFFF);
            Paint.FontMetrics kf = mBarTextPaint.getFontMetrics();
            // 组合键挑键时这个开关的语义是"往同一个组合键里连着加"，
            // 不是"连着建新按钮"，文案要跟着变，否则不知道勾了会发生什么。
            c.drawText(mComboPickIdx != COMBO_PICK_NONE ? "连续添加" : "连续创建",
                    mTmpRect.right + dp(6f),
                    k.centerY() - (kf.ascent + kf.descent) / 2f, mBarTextPaint);
            // 后面列表项用的是居中的字，这里改了要改回来
            mBarTextPaint.setTextAlign(Paint.Align.CENTER);
        }

        mBarTextPaint.setTextSize(dp(15f));
        if (mListMode == LIST_TOOL) {
            // 内容坐标 -> 屏幕坐标（滚动只影响纵向）
            int saveT = c.save();
            c.clipRect(mListViewRect);
            c.translate(0f, mListViewRect.top - mListScroll);
            for (int k = 0; k < TL_ORDER.length; k++) {
                int i = TL_ORDER[k];
                RectF g = mToolGroupRects[i];
                if (!g.isEmpty() && mToolGroupTexts[i] != null) {
                    // 分组小标题：左对齐、灰字、不带背景，和菜单项明显区分，
                    // 让人一眼看出"它不是能点的"。
                    mBarTextPaint.setTextAlign(Paint.Align.LEFT);
                    mBarTextPaint.setTextSize(dp(12f));
                    mBarTextPaint.setColor(0xFF9E9E9E);
                    Paint.FontMetrics gf = mBarTextPaint.getFontMetrics();
                    c.drawText(mToolGroupTexts[i], g.left + dp(4f),
                            g.centerY() - (gf.ascent + gf.descent) / 2f, mBarTextPaint);
                    mBarTextPaint.setTextAlign(Paint.Align.CENTER);
                }
                RectF r = mToolListRects[i];
                if (r.isEmpty()) {
                    continue;
                }
                c.drawRoundRect(r, dp(8f), dp(8f), mSlTrackPaint);
                mBarTextPaint.setTextSize(dp(15f));
                mBarTextPaint.setColor(0xFFFFFFFF);
                Paint.FontMetrics f = mBarTextPaint.getFontMetrics();
                c.drawText(TL_TEXT[i], r.centerX(),
                        r.centerY() - (f.ascent + f.descent) / 2f, mBarTextPaint);
            }
            c.restoreToCount(saveT);
            if (mListScrollMax > 0f) {
                drawListScrollBar(c);
            }
        } else {
            // 【组合键编辑列表绘制前必须重排】
            //   它的行数随加/删动作变化，而 layoutList 只在开列表和改动作时调用。
            //   中间任何一次"矩形还没跟上"都会让「删」按钮留着旧坐标、
            //   或者干脆是空的 —— 画不出来也点不到，表现为"删不掉"。
            if (mListMode == LIST_COMBO_EDIT) {
                layoutList();
            }
            int n = listItemCount();
            boolean scrolling = mListScrollMax > 0f;
            // 【必须无条件 save】
            // 之前写成 scrolling ? c.save() : 0，非滚动时平移了却不 restore，
            // 结果下面画的「确定 / 取消」被整体下移了 mListViewRect.top（几百 px），
            // 表现为按钮看不见或发黑；而它们的矩形坐标是对的，
            // 所以点原来的位置照样能触发 —— 正是"看不见但能点"。
            //
            // 键盘列表因为有 103 项必然走 scrolling 分支（有 save/restore）所以正常，
            // 项数少的列表走非滚动分支才暴露出来。
            int save = c.save();
            c.clipRect(mListViewRect);
            c.translate(0f, mListViewRect.top - mListScroll);
            for (int pos = 0; pos < n; pos++) {
                RectF r = mListItemRects[pos];
                if (mListMode == LIST_GRID) {
                    drawGridRow(c, pos, r);
                    continue;
                }
                if (mListMode == LIST_COMBO_STICK_XY) {
                    drawStickXyRow(c, pos, r);
                    continue;
                }
                if (mListMode == LIST_COMBO_TRIG) {
                    drawTrigRow(c, pos, r);
                    continue;
                }
                // LIST_KEY 是"点一下就创建"，没有勾选态
                boolean on = mListMode != LIST_KEY
                        && mListMode != LIST_FIX && mListMode != LIST_FIX_TPL
                        && mListMode != LIST_COMBO_CROSS && mListMode != LIST_COMBO_KIND
                        && mListSel[mListItems[rawPos(pos)]];
                // 功能键页：右边被三个胶囊占掉，名字居中的话会压上去
                if (mListMode == LIST_FIX && mFixUiMode) {
                    mBarTextPaint.setTextAlign(Paint.Align.LEFT);
                    mBarTextPaint.setColor(0xFFDDDDDD);
                    Paint.FontMetrics fu = mBarTextPaint.getFontMetrics();
                    c.drawText(listItemName(pos), r.left + dp(12f),
                            r.centerY() - (fu.ascent + fu.descent) / 2f,
                            mBarTextPaint);
                    mBarTextPaint.setTextAlign(Paint.Align.CENTER);
                    continue;
                }
                // 模板页：**整行**按状态上色，不用勾选蓝。
                // 这一行不是"勾选框"，点它是切换三态，
                // 底色的语义就是"它现在是什么状态"，和胶囊那套配色一致。
                if (mListMode == LIST_FIX) {
                    int rp = rawPos(pos);
                    int code = (rp >= 0 && rp < mFixCode.length)
                            ? mFixCode[rp] : FX_ACT_ADD;
                    if (isFixAct(code)) {
                        // 「＋ 添加…」是入口不是状态行，保持中性底色
                        c.drawRoundRect(r, dp(8f), dp(8f), mSlTrackPaint);
                        mBarTextPaint.setColor(0xFFDDDDDD);
                    } else {
                        int st = fixState(mFixTpl, code);
                        c.drawRoundRect(r, dp(8f), dp(8f), fxPaintOf(fxColor(st)));
                        mBarTextPaint.setColor(0xFFFFFFFF);
                    }
                    Paint.FontMetrics ff2 = mBarTextPaint.getFontMetrics();
                    c.drawText(listItemName(pos), r.centerX(),
                            r.centerY() - (ff2.ascent + ff2.descent) / 2f,
                            mBarTextPaint);
                    continue;
                }
                c.drawRoundRect(r, dp(8f), dp(8f), on ? mSlFillPaint : mSlTrackPaint);
                mBarTextPaint.setColor(on ? 0xFFFFFFFF : 0xDDFFFFFF);
                //
                // 【组合键的「结束」「删」必须画在这里，紧跟自己的行】
                //   以前是行循环结束后另起一个 for 统一画，
                //   结果和行的可见性 / 裁剪状态脱节，行画出来了按钮却没有。
                //   跟着行一起画，行在什么图层、有没有被裁，按钮就跟着一样。
                if (mListMode == LIST_COMBO_EDIT) {
                    drawComboRowButtons(c, pos);
                }
                Paint.FontMetrics f = mBarTextPaint.getFontMetrics();
                String t = (on ? "✓ " : "") + listItemName(pos);
                // 布局列表每行右侧还有「改名 / 删」，名字区被挤窄了，
                // 再居中会顶到按钮上。左对齐 + 留内边距。
                /*
                  【左对齐】和【画时间】是两件事，但以前写在了同一个 if 里。

                  组合键编辑（LIST_COMBO_EDIT）需要左对齐 —— 它的行里也有
                  右侧按钮，居中会顶上去。可是"时间"只有布局列表才有：

                    mLayoutFiltered 只在 applyLayoutFilter() 里刷新，
                    也就是**打开布局列表时**才更新。切到别的列表之后它
                    还是上一次的残留，不清空。

                    而组合键编辑里的 pos 是"第几个动作"的下标，
                    拿它去索引残留的 mLayoutFiltered，就会取到任意一个
                    布局的时间 —— 表现为：第 3 个动作下面多了一行小字，
                    内容正是用户某个自建布局的时间。

                  所以拆开：左对齐三个列表都要，时间只给布局列表。
                */
                boolean leftAlign = (mListMode == LIST_LAYOUT
                        || mListMode == LIST_LAYOUT_PICK
                        || mListMode == LIST_COMBO_EDIT);
                boolean isLayoutList = (mListMode == LIST_LAYOUT
                        || mListMode == LIST_LAYOUT_PICK);
                if (leftAlign) {
                    mBarTextPaint.setTextAlign(Paint.Align.LEFT);
                    // 有时间的布局：名字抬上去一点，下面留一行小字放时间。
                    // 内置（默认手柄 / 默认键盘）没有时间，名字照旧垂直居中。
                    String tm = (isLayoutList && pos >= 0
                            && pos < mLayoutFiltered.size())
                            ? PadLayout.metaTimeText(mLayoutFiltered.get(pos)) : "";
                    if (tm.length() > 0) {
                        c.drawText(t, r.left + dp(12f),
                                r.centerY() - dp(9f) - (f.ascent + f.descent) / 2f,
                                mBarTextPaint);
                        float oldSize = mBarTextPaint.getTextSize();
                        int oldColor = mBarTextPaint.getColor();
                        mBarTextPaint.setTextSize(dp(11f));
                        // 时间用灰一点的颜色，和名字拉开主次
                        mBarTextPaint.setColor(0xAAFFFFFF);
                        // 变量名不能再用 tf —— 同一方法里上面已经有一个了，
                        // 重复声明会报 "There already is another variable named 'tf'"
                        Paint.FontMetrics tfTime = mBarTextPaint.getFontMetrics();
                        c.drawText(tm, r.left + dp(12f),
                                r.centerY() + dp(11f)
                                        - (tfTime.ascent + tfTime.descent) / 2f,
                                mBarTextPaint);
                        mBarTextPaint.setTextSize(oldSize);
                        mBarTextPaint.setColor(oldColor);
                    } else {
                        c.drawText(t, r.left + dp(12f),
                                r.centerY() - (f.ascent + f.descent) / 2f, mBarTextPaint);
                    }
                    mBarTextPaint.setTextAlign(Paint.Align.CENTER);
                } else {
                    c.drawText(t, r.centerX(),
                            r.centerY() - (f.ascent + f.descent) / 2f, mBarTextPaint);
                }
            }
            // 布局列表的「改名 / 删」：内置两个（默认手柄 / 默认键盘）不给删，
            // 也不给改名 —— 它们是"更新覆盖"的锚点，改名后用户就认不出了。
            // 功能键那页：每行右侧一排胶囊（空 / 手 / 键 / 鼠），
            // 底色表示它在那个模板下是强制显示 / 强制隐藏 / 跟随默认。
            if (mListMode == LIST_FIX && mFixUiMode) {
                mBarTextPaint.setTextSize(dp(12f));
                for (int pos = 0; pos < n && pos < mFixTplRects.length; pos++) {
                    for (int k = 0; k < FX_TPL_SHORT.length; k++) {
                        RectF rc = mFixTplRects[pos][k];
                        if (rc.isEmpty()) {
                            continue;
                        }
                        mFixCapPaint.setColor(fxColor(fixUiState(pos, k)));
                        c.drawRoundRect(rc, dp(5f), dp(5f), mFixCapPaint);
                        mBarTextPaint.setColor(0xFFFFFFFF);
                        Paint.FontMetrics ff = mBarTextPaint.getFontMetrics();
                        c.drawText(FX_TPL_SHORT[k], rc.centerX(),
                                rc.centerY() - (ff.ascent + ff.descent) / 2f,
                                mBarTextPaint);
                    }
                }
                mBarTextPaint.setTextSize(dp(15f));
                mBarTextPaint.setColor(0xFFFFFFFF);
            }
            if (mListMode == LIST_LAYOUT) {
                mBarTextPaint.setTextSize(dp(13f));
                for (int pos = 0; pos < n; pos++) {
                    PadLayout.LayoutMeta m = pos < mLayoutFiltered.size()
                            ? mLayoutFiltered.get(pos) : null;
                    if (m == null) {
                        continue;
                    }
                    Paint.FontMetrics f = mBarTextPaint.getFontMetrics();
                    // 分享：内置布局也给导出 —— 想把自己改好的"默认键盘"
                    // 发给别人是常见需求，没必要拦。
                    RectF rs = mLayoutShareRects[pos];
                    c.drawRoundRect(rs, dp(6f), dp(6f), mToolBtnPaint);
                    mBarTextPaint.setColor(0xFF1565C0);
                    c.drawText("分享", rs.centerX(),
                            rs.centerY() - (f.ascent + f.descent) / 2f, mBarTextPaint);
                    // 同步对内置也画（见 layoutLayoutList 里 full 的注释）
                    RectF rsy = mLayoutSyncRects[pos];
                    c.drawRoundRect(rsy, dp(6f), dp(6f), mToolBtnPaint);
                    mBarTextPaint.setColor(0xFF00695C);
                    c.drawText("同步", rsy.centerX(),
                            rsy.centerY() - (f.ascent + f.descent) / 2f, mBarTextPaint);
                    if (m.builtin) {
                        continue;      // 改名 / 删：内置不给
                    }
                    RectF rn = mLayoutRenameRects[pos];
                    c.drawRoundRect(rn, dp(6f), dp(6f), mToolBtnPaint);
                    mBarTextPaint.setColor(0xFF212121);
                    c.drawText("改名", rn.centerX(),
                            rn.centerY() - (f.ascent + f.descent) / 2f, mBarTextPaint);
                    RectF rd = mLayoutDelRects[pos];
                    c.drawRoundRect(rd, dp(6f), dp(6f), mToolBtnPaint);
                    mBarTextPaint.setColor(0xFFD32F2F);
                    c.drawText("删", rd.centerX(),
                            rd.centerY() - (f.ascent + f.descent) / 2f, mBarTextPaint);
                }
                mBarTextPaint.setTextSize(dp(15f));
                mBarTextPaint.setColor(0xFFFFFFFF);
            }
            c.restoreToCount(save);
            if (scrolling) {
                drawListScrollBar(c);
            }
        }
        // 「导入」在标题栏上，不在滚动区里，所以要在 clip 之外画
        if (mListMode == LIST_LAYOUT && !mListImportRect.isEmpty()) {
            c.drawRoundRect(mListImportRect, dp(6f), dp(6f), mToolBtnPaint);
            mBarTextPaint.setTextAlign(Paint.Align.CENTER);
            mBarTextPaint.setTextSize(mListImportRect.height() * 0.46f);
            mBarTextPaint.setColor(0xFF1565C0);
            Paint.FontMetrics inf = mBarTextPaint.getFontMetrics();
            c.drawText("导入", mListImportRect.centerX(),
                    mListImportRect.centerY()
                            - (inf.ascent + inf.descent) / 2f, mBarTextPaint);
            mBarTextPaint.setColor(0xFFFFFFFF);
        }

        // 「搜索」在标题栏上，同样要在 clip 之外画。
        // 有搜索词时整枚按钮点亮成蓝色，提醒"列表现在是筛过的"，
        // 免得看到条目变少以为功能坏了。
        if (!mListSearchRect.isEmpty()) {
            c.drawRoundRect(mListSearchRect, dp(6f), dp(6f),
                    hasQuery() ? fxPaintOf(FXC_HIDE) : mToolBtnPaint);
            mBarTextPaint.setTextAlign(Paint.Align.CENTER);
            mBarTextPaint.setTextSize(mListSearchRect.height() * 0.44f);
            mBarTextPaint.setColor(0xFFFFFFFF);
            Paint.FontMetrics sf = mBarTextPaint.getFontMetrics();
            c.drawText(hasQuery() ? ("搜索:" + mListQuery) : "搜索",
                    mListSearchRect.centerX(),
                    mListSearchRect.centerY() - (sf.ascent + sf.descent) / 2f,
                    mBarTextPaint);
        }

        // 【退出统一成两个词】
        //   以前顶层列表左键是「取消」、常用工具是「关闭」，
        //   两者行为完全一样（都是 closeList），却用两个词 —— 纯冗余。
        //   现在一律：有上级 = 「返回」（退一层），没上级 = 「关闭」（关掉整个）。
        drawListButton(c, mListCancelRect,
                mListBack != LIST_NONE ? "返回" : "关闭", true);
        // 加 mListMode 判定做双保险：即便哪个分支忘了清空矩形，
        // 也不会在别的列表里画出「新建」
        if (!mListCreateRect.isEmpty() && mListMode == LIST_LAYOUT) {
            drawListButton(c, mListCreateRect, "新建", true);
        }
        if (!mListOkRect.isEmpty()) {
            // 【有上级时右边这颗是「完成 / 关闭」】
            //   这些列表没有勾选语义，走通用「确定」会一直是灰的。
            // 【自定义坐标必须能点「确定」】
            //   它是 openListBack 进来的，mListBack != NONE，
            //   于是右键一律画成"关闭" —— 调完 X/Y 没处确认，
            //   只能返回（等于丢弃）。这里单独放行。
            if (listOkIsClose() || listNoOkButton()) {
                // 见 listOkIsClose 的说明：退出交给左边那颗，这里不画。
            } else {
            // 隐藏 / 关联允许一个都不选，这时按钮写"清空"，
            // 免得你以为"确定"是灰的、没法把隐藏的按钮放回来
            boolean clear = (mListMode == LIST_HIDE || mListMode == LIST_LINK)
                    && listSelCount() == 0;
            boolean okEnabled = listConfirmEnabled();
            String okText = okEnabled
                    ? (clear ? "清空" : "确定")
                    : (mListMode == LIST_SWAP ? "确定(选2个)"
                    : (mListMode == LIST_MULTI_ADJ || mListMode == LIST_MULTI_SHAPE)
                            ? "确定(至少2个)" : "确定(先选)");
            drawListButton(c, mListOkRect, okText, okEnabled);
            }
        }
        // 【分类浮层必须画在所有按钮之后 —— 包括确定】
        //   之前它排在「确定」前面，于是展开时「确定 / 清空」会盖在分类项上，
        //   看着点在分类项、实际那一块被按钮占着。
        //   判定顺序里浮层本来就优先（见 performListTap），
        //   绘制顺序必须和它一致，否则就是"看着是 A、画的是 B"。
        //   高度已在 layoutList 里按面板剩余空间夹过，
        //   所以即便它盖住一点列表内容，也不会盖到按钮上。
        drawListCategory(c);
        // 「全选」在标题行左端（不在浮层里，和其他按钮同层）
        drawListSelAll(c);
    }

    void drawSelection(Canvas c) {
        if (mSel == NONE) return;
        // 【守卫】mSel 可能指向一个已经不存在的元素（比如刚被删掉的空白按钮），
        //   那就在屏幕正中画一圈悬空的青框 = "框了个空气"。
        if (!pickable(mSel)) return;
        if (isTrigger(mSel)) {
            // 扳机用轨道形状的高亮框，圆圈套在滑轨上会只框住中间一小块
            float r = radiusOf(mSel) + dp(6f);
            float hy = trackHalfH(mSel) + dp(6f);
            mTmpRect.set(mPx[mSel] - r, mPy[mSel] - hy, mPx[mSel] + r, mPy[mSel] + hy);
            c.drawRoundRect(mTmpRect, r, r, mSelPaint);
            return;
        }
        float r = radiusOf(mSel) + dp(6f);
        if (mLayout.shape[mSel] == PadLayout.SHAPE_RECT) {
            // 选中框跟着形状：方块键套个圆圈看着别扭，也不好判断选中了谁
            float hw = halfW(mSel) + dp(5f);
            float hh = halfH(mSel) + dp(5f);
            mTmpRect.set(mPx[mSel] - hw, mPy[mSel] - hh, mPx[mSel] + hw, mPy[mSel] + hh);
            c.drawRoundRect(mTmpRect, Math.min(hw, hh) * 0.30f,
                    Math.min(hw, hh) * 0.30f, mSelPaint);
        } else {
            c.drawCircle(mPx[mSel], mPy[mSel], r, mSelPaint);
        }
    }

    String elemListName(int i) {
        if (mLayout == null) {
            return "";
        }
        // 同步挑按钮时按钮可能只在源方向存在，名字要按源那份取，
        // 否则 target 上是空槽位，nameOf 返回空串 —— 列表里一排空白行
        PadLayout src = (mListMode == LIST_SYNC_PICK && mSyncSrc != null)
                ? mSyncSrc : mLayout;
        String nm = src.nameOf(i);
        if (src.hidden[i]) {
            nm += "{隐藏}";
        }
        return nm;
    }

    boolean elemMatchesCat(int elem) {
        if (mListCat == CAT_ALL) {
            return true;
        }
        // 「功能」= 界面按钮：编 / 收 / 布 / 透 / G
        if (mListCat == CAT_UI) {
            return elem == PadLayout.I_EDIT || elem == PadLayout.I_COLLAPSE
                    || elem == PadLayout.I_LAYOUT || elem == PadLayout.I_PASS
                    || elem == PadLayout.I_FLOAT;
        }
        // 「空白」= 用户建的空白按钮。必须是**已占用**的槽位 ——
        // 空白有 16 个槽位，不过滤的话空槽也会列进来，一片没名字的空行。
        if (mListCat == CAT_BLANK) {
            return PadLayout.isBlankSlot(elem) && mLayout.isBlankUsed(elem);
        }
        // 「组合」= 组合键（含十字架），同样只列已占用的
        if (mListCat == CAT_COMBO) {
            return PadLayout.isComboSlot(elem) && mLayout.isComboUsed(elem);
        }
        // 键盘 = 键盘槽位。
        // 固定手柄元素（摇杆 / ABXY / 肩键…）算「手柄」；
        // 界面按钮单独归到「功能」，不混进手柄类 ——
        // 它们是"操作界面用的"，和"打游戏用的"不是一回事。
        boolean isKb = PadLayout.isKeySlot(elem);
        if (mListCat == CAT_KB) {
            return isKb;
        }
        // CAT_PAD：手柄元素。界面按钮不算；
        // 空白和组合键现在已经各自有分类，不能再混进「手柄」里 ——
        // 否则挑按钮时它们的名字会夹在一堆 ABXY 中间，很难找。
        if (PadLayout.isBlankSlot(elem) || PadLayout.isComboSlot(elem)) {
            return false;
        }
        return !isKb && elem != PadLayout.I_EDIT
                && elem != PadLayout.I_COLLAPSE && elem != PadLayout.I_LAYOUT
                && elem != PadLayout.I_PASS && elem != PadLayout.I_FLOAT;
    }

    int fillColorSel() {
        int n = 0;
        for (int i = 0; i < PadLayout.N; i++) {
            mColorSel[i] = mSelSet[i];
            if (mSelSet[i]) n++;
        }
        // 顺手数一下会应用到几个键，带到网页上显示 ——
        // 选了五个却以为只改一个，改完才发现就晚了。
        mColorSelCount = Math.max(1, n);
        return n;
    }

    int firstStickInSel() {
        if (mLayout == null) {
            return NONE;
        }
        if (isMulti()) {
            for (int i = 0; i < PadLayout.N; i++) {
                if (mSelSet[i] && isStickElem(i)) {
                    return i;
                }
            }
            return NONE;
        }
        return (mSel != NONE && isStickElem(mSel)) ? mSel : NONE;
    }

    int forEachSelStick(StickOp op) {
        if (mLayout == null) {
            return 0;
        }
        int n = 0;
        if (isMulti()) {
            for (int i = 0; i < PadLayout.N; i++) {
                if (mSelSet[i] && isStickElem(i)) {
                    op.run(i);
                    n++;
                }
            }
        } else if (mSel != NONE && isStickElem(mSel)) {
            op.run(mSel);
            n = 1;
        }
        return n;
    }

    int hitListAdjust(float x, float y) {
        if (!mListViewRect.contains(x, y)) {
            return ADJ_NONE;
        }
        float cy = y - mListViewRect.top + mListScroll;
        if (mListMode == LIST_COMBO_TRIG) {
            // 竖向外扩一点：条本身只有 26dp 高，手指粗容易点空
            if (x >= mTrigBarRect.left && x <= mTrigBarRect.right
                    && cy >= mTrigBarRect.top - dp(10f)
                    && cy <= mTrigBarRect.bottom + dp(10f)) {
                return ADJ_TRIG;
            }
            return ADJ_NONE;
        }
        if (mListMode == LIST_COMBO_STICK_XY) {
            float dx = x - mStickPadRect.centerX();
            float dy = cy - mStickPadRect.centerY();
            // 圆外留一圈余量：拖到边上还能继续往角上调，不会突然卡住
            if (Math.sqrt(dx * dx + dy * dy) <= mStickPadR + dp(14f)) {
                return ADJ_STICK;
            }
            return ADJ_NONE;
        }
        return ADJ_NONE;
    }

    int hitListItem(float x, float y) {
        if (!mListViewRect.contains(x, y)) {
            return NONE;
        }
        float cy = y - mListViewRect.top + mListScroll;
        int n = listItemCount();
        for (int pos = 0; pos < n; pos++) {
            if (mListItemRects[pos].contains(x, cy)) {
                // 【返回原始位置，不是显示位置】
                //   下面所有处理（mListItems[item]、mLayoutFiltered.get(item)、
                //   mFixCode[item]…)用的都是原始下标。返回显示位置的话，
                //   搜索一开，点第 1 行会去改原始第 1 项 —— 全串位。
                return rawPos(pos);
            }
        }
        return NONE;
    }

    boolean hitListSub(RectF r, float x, float y) {
        if (r.isEmpty() || !mListViewRect.contains(x, y)) {
            return false;
        }
        // 屏幕坐标 -> 内容坐标，和 hitListItem 同一套：
        // 内容被 translate(0, viewRect.top - scroll) 平移过，反着减回来。
        float cy = y - mListViewRect.top + mListScroll;
        return r.contains(x, cy);
    }

    int hitTool(float x, float y) {
        if (!mEditMode || mPanelState != PANEL_FULL) {
            return NONE;
        }
        for (int i = 0; i < T_COUNT; i++) {
            if (mToolRects[i].contains(x, y)) {
                return i;
            }
        }
        return NONE;
    }

    int hitToolListItem(float x, float y) {
        // 项的矩形是**内容坐标**，所以先卡可视区再换算，
        // 和 hitListItem 同一套逻辑：不先卡可视区的话，
        // 滚出去的项会"穿透"到屏幕顶部被误命中。
        if (!mListViewRect.contains(x, y)) {
            return NONE;
        }
        float cy = y - mListViewRect.top + mListScroll;
        for (int i = 0; i < TL_COUNT; i++) {
            if (mToolListRects[i].contains(x, cy)) {
                return i;
            }
        }
        return NONE;
    }

    boolean isAllSelectedInCat() {
        if (mListCount <= 0) {
            return false;
        }
        for (int i = 0; i < mListCount; i++) {
            if (!mListSel[mListItems[i]]) {
                return false;
            }
        }
        return true;
    }

    boolean isFloatLayout() {
        return mLayout != null && mLayout.layoutId == PadLayout.LAYOUT_FLOAT;
    }

    static boolean isFloatTool(int item) {
        for (int k = 0; k < FLOAT_TOOLS.length; k++) {
            if (FLOAT_TOOLS[k] == item) {
                return true;
            }
        }
        return false;
    }

    boolean isLayoutCatList() {
        return mListMode == LIST_LAYOUT || mListMode == LIST_LAYOUT_PICK;
    }

    void layoutDlg() {
        float pw = Math.min((float) mW - dp(32f), dp(340f));
        float pad = dp(14f);
        float btnH = dp(46f);
        float bodyH = dp(56f);
        float titleH = dp(52f);
        float ph = titleH + bodyH + btnH + pad * 3f;
        float left = ((float) mW - pw) / 2f;
        float top = ((float) mH - ph) / 2f;
        mDlgPanelRect.set(left, top, left + pw, top + ph);

        float by = top + ph - pad - btnH;
        float bw = (pw - pad * 2f - dp(10f)) / 2f;
        mDlgCancelRect.set(left + pad, by, left + pad + bw, by + btnH);
        mDlgOkRect.set(left + pad + bw + dp(10f), by, left + pw - pad, by + btnH);
    }

    void layoutGridList() {
        float pad = dp(12f);
        float titleH = dp(42f);
        float btnH = dp(44f);
        float rowH = dp(52f);
        int n = GI_COUNT;

        float contentH = n * rowH;
        float fixed = titleH + btnH + pad * 3f;
        float availH = (float) mH * 0.94f - fixed;
        float viewH = Math.min(contentH, availH);
        mListContentH = contentH;
        mListScrollMax = Math.max(0f, contentH - viewH);
        if (mListScroll > mListScrollMax) {
            mListScroll = mListScrollMax;
        }
        if (mListScroll < 0f) {
            mListScroll = 0f;
        }

        float ph = titleH + viewH + btnH + pad * 3f;
        float pw = Math.min((float) mW - dp(40f), dp(340f));
        float left = ((float) mW - pw) / 2f;
        float top = ((float) mH - ph) / 2f;
        mListPanelRect.set(left, top, left + pw, top + ph);

        float vy = top + pad + titleH;
        mListViewRect.set(left + pad, vy, left + pw - pad, vy + viewH);

        float x0 = left + pad;
        float x1 = left + pw - pad;
        float stepW = dp(34f);
        float gap = dp(6f);
        // 【− 和 + 之间必须留出一段专门放数值】
        //   原来两者只隔 6dp，数值却画在它们正中间 —— 2 个字的宽度
        //   远超 6dp，于是文字压在按钮上，看起来就是"重叠到加减号之间"。
        //   这里显式留出一格 valueW 给数值，位置从 (minus.right + plus.left)/2
        //   推导即可，绘制处不用改。
        float valueW = dp(40f);
        for (int i = 0; i < n; i++) {
            float iy = i * rowH;
            mListItemRects[i].setEmpty();
            mGridMinusRects[i].setEmpty();
            mGridPlusRects[i].setEmpty();
            if (gridRowIsToggle(i)) {
                // 开关行：整行可点，右侧留一段给状态文字（纯展示，不响应）
                mListItemRects[i].set(x0, iy, x1, iy + rowH - dp(6f));
            } else {
                // 从右往左排：+ | 数值区 | − | 名字
                float plusL = x1 - stepW;
                float valueR = plusL - gap;
                float valueL = valueR - valueW;
                float minusR = valueL - gap;
                float minusL = minusR - stepW;
                mGridMinusRects[i].set(minusL, iy + dp(8f),
                        minusR, iy + rowH - dp(12f));
                mGridPlusRects[i].set(plusL, iy + dp(8f),
                        plusL + stepW, iy + rowH - dp(12f));
                mListItemRects[i].set(x0, iy, minusL - gap, iy + rowH - dp(6f));
            }
        }

        // 底栏：只有「关闭」，没有确定 / 新建 —— 改一项生效一项，
        // 放个确定反而让人以为要点它才保存。
        float by = top + ph - pad - btnH;
        mListCancelRect.set(left + pad, by, left + pw - pad, by + btnH);
        mListOkRect.setEmpty();
    }

    void layoutLayoutList() {
        float pad = dp(12f);
        float titleH = dp(42f);
        float btnH = dp(44f);
        float rowH = dp(52f);
        int n = listItemCount();
        boolean editable = mListMode == LIST_LAYOUT;

        float contentH = n * rowH;
        float fixed = titleH + btnH + pad * 3f;
        float availH = (float) mH * 0.94f - fixed;
        float viewH = Math.min(contentH, availH);
        mListContentH = contentH;
        mListScrollMax = Math.max(0f, contentH - viewH);
        if (mListScroll > mListScrollMax) mListScroll = mListScrollMax;
        if (mListScroll < 0f) mListScroll = 0f;

        float pw = Math.min((float) mW - dp(32f), dp(400f));
        float ph = titleH + viewH + btnH + pad * 3f;
        float left = ((float) mW - pw) / 2f;
        float top = ((float) mH - ph) / 2f;
        mListPanelRect.set(left, top, left + pw, top + ph);
        float viewTop = top + pad + titleH;
        mListViewRect.set(left + pad, viewTop, left + pw - pad, viewTop + viewH);

        float actW = editable ? dp(56f) : 0f;      // 改名按钮宽
        float delW = editable ? dp(40f) : 0f;      // 删除按钮宽
        float gapA = editable ? dp(6f) : 0f;
        float syncW = editable ? dp(52f) : 0f;     // 横竖屏同步按钮宽
        // 分享对内置布局也给（导出默认键盘这类需求是有的），
        // 所以不跟 editable 走，每行都占最右边一格。
        float shareW = dp(44f);

        // 【两个数组长度不一样，不能共用一个循环上限】
        // mListItemRects 长 134，mLayoutRenameRects / mLayoutDelRects 只有
        // LAYOUT_MAX = 32。之前共用一个循环，i 走到 32 就越界崩溃 ——
        // 表现为"一打开布局切换就崩"。
        for (int i = 0; i < mListItemRects.length; i++) {
            if (i >= n) {
                mListItemRects[i].setEmpty();
                continue;
            }
            float iy = i * rowH;
            float x0 = left + pad;
            float x1 = left + pw - pad;
            // 【从右往左排：分享 | 同步 | 删 | 改名 | 名字】
            //   于是左到右读是「改名 删 同步 分享」—— 用户要的顺序。
            //   上一版排成了「分享 | 删 | 改名 | 同步」，左读变成
            //   「同步 改名 删 分享」，跟要求对不上。
            mLayoutShareRects[i].set(x1 - shareW, iy + dp(6f),
                    x1, iy + rowH - dp(10f));
            float nameRight = x1 - shareW - gapA;
            // 【内置布局只给 分享 + 同步】
            //   删 / 改名对它没意义（点了只会弹"内置布局不能…"），
            //   摆出来就是两颗按了没反应的按钮。
            //   同步不一样：横竖屏各摆一次是刚需，内置布局也该能同步。
            PadLayout.LayoutMeta lm = (i < mLayoutFiltered.size())
                    ? mLayoutFiltered.get(i) : null;
            boolean full = editable && lm != null && !lm.builtin;
            if (editable) {
                if (full) {
                    // 同步紧贴分享左边
                    mLayoutSyncRects[i].set(nameRight - syncW, iy + dp(6f),
                            nameRight, iy + rowH - dp(10f));
                    float delRight = nameRight - syncW - gapA;
                    mLayoutDelRects[i].set(delRight - delW, iy + dp(6f),
                            delRight, iy + rowH - dp(10f));
                    float renRight = delRight - delW - gapA;
                    mLayoutRenameRects[i].set(renRight - actW, iy + dp(6f),
                            renRight, iy + rowH - dp(10f));
                    mListItemRects[i].set(x0, iy,
                            renRight - actW - gapA,
                            iy + rowH - dp(4f));
                } else {
                    // 内置：删 / 改名 不占位，矩形置空（不然点空白也触发）
                    mLayoutDelRects[i].setEmpty();
                    mLayoutRenameRects[i].setEmpty();
                    float syncRight = nameRight - gapA;
                    mLayoutSyncRects[i].set(syncRight - syncW, iy + dp(6f),
                            syncRight, iy + rowH - dp(10f));
                    mListItemRects[i].set(x0, iy,
                            syncRight - syncW - gapA,
                            iy + rowH - dp(4f));
                }
            } else {
                mLayoutRenameRects[i].setEmpty();
                mLayoutSyncRects[i].setEmpty();
                mLayoutDelRects[i].setEmpty();
                mListItemRects[i].set(x0, iy, nameRight, iy + rowH - dp(4f));
            }
        }

        // 改名 / 删 / 分享 三个数组按自己的长度清空。
        // 不清的话上一次的矩形还在，手指点到那个位置照样触发。
        for (int i = 0; i < mLayoutRenameRects.length; i++) {
            if (i >= n) {
                mLayoutRenameRects[i].setEmpty();
                mLayoutDelRects[i].setEmpty();
                mLayoutShareRects[i].setEmpty();
            }
        }

        // 底栏：取消占一半，右边是「新建」。
        // 只有编辑模式的那个列表（LIST_LAYOUT）才给「新建」——
        // 「布」按钮打开的那份只管切换，不给建。
        float by = top + ph - pad - btnH;
        float fullW = pw - pad * 2f;
        if (editable) {
            float gapB = dp(8f);
            float cancelW = (fullW - gapB) / 2f;      // 取消 = 原来的一半
            mListCancelRect.set(left + pad, by, left + pad + cancelW, by + btnH);
            mListCreateRect.set(left + pad + cancelW + gapB, by,
                    left + pw - pad, by + btnH);
        } else {
            mListCancelRect.set(left + pad, by, left + pw - pad, by + btnH);
            mListCreateRect.setEmpty();
        }
        // 布局列表不走通用「确定」逻辑（点行即切换），所以确定键始终为空
        mListOkRect.setEmpty();

        // 右上角分类按钮：布局列表按**模板**分，所以有四项（含「空白」）
        float catW = Math.min(dp(78f), pw * 0.34f);
        float catH = dp(30f);
        mListCatRect.set(left + pw - pad - catW, top + pad + (titleH - catH) / 2f,
                left + pw - pad, top + pad + (titleH - catH) / 2f + catH);
        float dropTop2 = mListCatRect.bottom;
        // 同上：下界取屏幕底，别被矮面板挤扁
        float avail2 = (float) mH * 0.97f - dropTop2;
        float ciH2 = Math.max(dp(22f), Math.min(dp(38f), avail2 / CAT_MAX_LAYOUT));
        for (int i = 0; i < CAT_MAX_LAYOUT; i++) {
            mListCatItemRects[i].set(mListCatRect.left, dropTop2 + i * ciH2,
                    mListCatRect.right, dropTop2 + (i + 1) * ciH2);
        }
        // 布局列表没有"全选"（点行是切换，不是勾选）
        mListSelAllRect.setEmpty();
        // 「导入」放在标题栏左端（别的列表里「全选」的位置）。
        // 只给 LIST_LAYOUT：「布」按钮开的那个（LIST_LAYOUT_PICK）是快速切换，
        // 摆在那儿的导入会让"顺手切个布局"变成误触风险。
        mListImportRect.setEmpty();
        if (mListMode == LIST_LAYOUT) {
            float impW = Math.min(dp(62f), pw * 0.28f);
            float impH = dp(30f);
            mListImportRect.set(left + pad, top + pad + (titleH - impH) / 2f,
                    left + pad + impW, top + pad + (titleH - impH) / 2f + impH);
        }
        // 布局列表：搜索放「分类」左边，和通用分支同一套摆法
        mListSearchRect.setEmpty();
        if (listCanSearch()) {
            float seW = Math.min(dp(58f), pw * 0.26f);
            float seRight = mListCatRect.isEmpty()
                    ? (left + pw - pad) : (mListCatRect.left - dp(6f));
            mListSearchRect.set(seRight - seW, top + pad + (titleH - dp(30f)) / 2f,
                    seRight, top + pad + (titleH - dp(30f)) / 2f + dp(30f));
        }
    }

    void layoutList() {
        float pad = dp(14f);
        // 功能键页标题下面还要画一行图例（说明红/绿/灰是什么意思），
        // 标题区要加高，否则图例会压到第一行上。
        float titleH = (mListMode == LIST_FIX) ? dp(68f) : dp(44f);
        float btnH = dp(46f);

        // 【先全部清空，再由具体分支按需设置】
        //   工具列表（LIST_TOOL）走自己的分支，不会设置这几个矩形 ——
        //   不清空的话，上一次列表留下的坐标还在，于是常用工具里
        //   凭空冒出「全选」「分类」，以及布局列表的「新建」。
        //   矩形还在就画得出来（也点得到），光在绘制处判 mListMode 没用：
        //   「新建」只在 layoutLayoutList() 里赋值，别的列表模式
        //   从来不碰它，坐标就一直留着 —— 点取消退出后再开别的列表，
        //   那个「新建」还挂在底部。
        mListCatRect.setEmpty();
        mListSelAllRect.setEmpty();
        mListCreateRect.setEmpty();
        // 「导入」只在布局列表里赋值，别的列表留着上次坐标会变成幽灵按钮
        mListImportRect.setEmpty();
        // 「搜索」同理：不走上面两个分支的列表必须清空，
        // 否则留着上次坐标又会变成"看不见但点得到"的幽灵按钮。
        mListSearchRect.setEmpty();
        // 用数组长度而不是 CAT_MAX：
        // 工具列表有 5 个分类，只清 4 个的话第 5 项会留着上一次的坐标，
        // 切成别的列表后浮层展开会多出一个"幽灵项"，点它还可能越界。
        for (int i = 0; i < mListCatItemRects.length; i++) {
            mListCatItemRects[i].setEmpty();
        }

        // 布局列表：每行要放「名字 + 改名 + 删」三段，
        // 通用分支是 2/4 列网格，挤不下三段，所以单独排。
        if (mListMode == LIST_LAYOUT || mListMode == LIST_LAYOUT_PICK) {
            layoutLayoutList();
            return;
        }
        if (mListMode == LIST_GRID) {
            layoutGridList();
            return;
        }
        if (mListMode == LIST_COMBO_STICK_XY) {
            layoutStickXyList();
            return;
        }
        if (mListMode == LIST_COMBO_TRIG) {
            layoutTrigList();
            return;
        }

        if (mListMode == LIST_TOOL) {
            // 「常用工具」就 5 项，单列竖排，做成窄一点的小菜单。
            // 行高按 94% 屏高反推：横屏 1080 高时按固定 52dp 算会顶出屏幕
            // （5×52 + 标题 + 按钮 + 间距 = 392dp，超过 1080/3=360dp）。
            // 工具菜单用自己的一套紧凑尺寸：矮横屏（720 高 = 240dp）上
            // 用按键列表那套 44dp 标题 + 46dp 按钮会把 5 行挤到屏幕外面。
            float padT = dp(10f);
            float titleT = dp(36f);
            float btnT = dp(38f);
            float fixedT = titleT + btnT + padT * 3f;
            // 「回退旧版位置」「删除此键」都是条件显示的，行数按实际条目数算。
            // 隐藏的那项要 setEmpty()：不然它留着上一次的坐标，
            // 手指点到那个位置还是会触发（矩形还在，只是没画出来）。
            int rows = visibleToolCount();
            // 分组标题占的额外高度。行高要按"减去这些标题"后的净空间算，
            // 不然 14 项 + 4 条标题会顶出屏幕，底部被裁掉。
            float gh = dp(22f);
            int groups = visibleToolGroupCount();
            // 兜底：万一某个分类下一项都没有，rows=0 会让 ih 变 Infinity，
            // 后面 ph 也跟着成 NaN，整个列表画不出来。
            if (rows < 1) {
                rows = 1;
            }
            float ih = ((float) mH * 0.94f - fixedT - groups * gh) / rows;
            if (ih > dp(52f)) ih = dp(52f);
            if (ih < dp(24f)) ih = dp(24f);
            // 工具列表也从"固定总高"改成"可视区 + 滚动"。
            //
            // 加了「批量删除」后最多 9 行。屏矮时（854 / 720 高）
            // 9×24dp + 标题 + 按钮 会超出屏幕 —— 行高已经夹到下限不能再压，
            // 只能让内容滚。内容不足一屏时 viewT == contentT、scrollMax = 0，
            // 行为和以前完全一样。
            float contentT = rows * ih + groups * gh;
            float availT = (float) mH * 0.94f - fixedT;
            float viewT = Math.min(contentT, availT);
            mListContentH = contentT;
            mListScrollMax = Math.max(0f, contentT - viewT);
            if (mListScroll > mListScrollMax) {
                mListScroll = mListScrollMax;
            }
            if (mListScroll < 0f) {
                mListScroll = 0f;
            }

            float ph = titleT + viewT + btnT + padT * 3f;
            float pw = Math.min((float) mW - dp(40f), dp(320f));
            float left = ((float) mW - pw) / 2f;
            float top = ((float) mH - ph) / 2f;
            mListPanelRect.set(left, top, left + pw, top + ph);

            // 可视区（屏幕坐标）。项的矩形改存**内容坐标**（从 0 起），
            // 绘制/命中时再平移 —— 和按键列表那套保持一致。
            float tyStart = top + padT + titleT;
            mListViewRect.set(left + padT, tyStart,
                    left + pw - padT, tyStart + viewT);

            float ty = 0f;
            // gh 在上面算行高时已经定义好了（同一方法内），
            // 这里不能再声明一个 —— 同名局部变量会报
            // "already is another variable named 'gh'"。
            int lastCat = -1;
            for (int k = 0; k < TL_ORDER.length; k++) {
                int i = TL_ORDER[k];
                if (!toolItemVisible(i)) {
                    mToolListRects[i].setEmpty();
                    mToolGroupRects[i].setEmpty();
                    continue;
                }
                int cat = toolItemCat(i);
                if (cat != lastCat) {
                    mToolGroupRects[i].set(left + padT, ty, left + pw - padT, ty + gh);
                    mToolGroupTexts[i] = TCAT_NAMES[cat];
                    ty += gh;
                    lastCat = cat;
                } else {
                    mToolGroupRects[i].setEmpty();
                }
                // 用 padT（和容器一致），之前误用了外层列表的 pad(14dp)，
                // 结果菜单项左右各多缩了 4dp，和标题对不齐
                mToolListRects[i].set(left + padT, ty, left + pw - padT, ty + ih - dp(6f));
                ty += ih;
            }
            float by = top + ph - padT - btnT;
            // 工具列表点一项就走，不需要「确定」，只留一个关闭按钮（占满整行）
            mListCancelRect.set(left + padT, by, left + pw - padT, by + btnT);
            mListOkRect.setEmpty();

            // 【分类按钮必须在这里单独布局】
            //
            // 这个分支是 return 提前返回的，走不到下面那套通用的
            // 「左全选 / 右分类」布局代码（在 mListOkRect 之后）。
            // 不补这段的话 mListCatRect 始终是空的 ——
            // 分类按钮画不出来，也点不到（连展开都做不到）。
            float smH = dp(30f);
            float smTop = top + padT + (titleT - smH) / 2f;
            float catW = Math.min(dp(78f), pw * 0.34f);
            mListCatRect.set(left + pw - padT - catW, smTop,
                    left + pw - padT, smTop + smH);
            int catNT = catCount();
            float dropTopT = mListCatRect.bottom;
            // 下界同样取"屏幕底"：切到某个空分类时行数为 0、面板很矮，
            // 取按钮顶会把 5 个分类项压到每项几 dp，根本点不准。
            float availT2 = (float) mH * 0.97f - dropTopT;
            float ciHT = Math.max(dp(22f), Math.min(dp(38f), availT2 / catNT));
            for (int i = 0; i < catNT; i++) {
                mListCatItemRects[i].set(mListCatRect.left, dropTopT + i * ciHT,
                        mListCatRect.right, dropTopT + (i + 1) * ciHT);
            }
            // 工具列表没有「全选」（它不是勾选型列表）
            mListSelAllRect.setEmpty();
            return;
        }
        // 列数自适应：从 2 列往上试，选第一个行高够点的方案。
        // 横屏只有 1080 高，两列要 9 行、行高被压到 22dp（点不准），
        // 三列 6 行就有 33dp；再矮的屏（720 高）就上四列。
        float fixed = titleH + btnH + pad * 3f;
        // 存一份给绘制用：标题要按它算"去掉内边距后还剩多宽"。
        mListPad = pad;
        // 项数按"当前实际能列出的"算，不是 PadLayout.N：
        // 空着的键盘槽位不占格子，否则列表会拖出一串空白项。
        int n = listItemCount();
        int cols = mW > mH ? 4 : 2;
        // 「固定显示」的行名带状态后缀（"A  隐藏"），两列挤不下，单列排。
        // 组合键编辑列表同理：每行右边还要放一枚「删」，两列根本放不下。
        if (mListMode == LIST_FIX || mListMode == LIST_COMBO_EDIT) {
            cols = 1;
        }
        int rows = (n + cols - 1) / cols;
        float maxW = Math.min((float) mW - dp(24f), dp(430f));
        float pw = maxW;
        float colGap = dp(8f);
        float iw = (pw - pad * 2f - colGap * (cols - 1)) / cols;

        // 高度：先按"最多占 94% 屏高"反推行高，再夹上下限。
        // 顺序不能反 —— 先夹下限再算总高的话，矮横屏上会顶出屏幕外面。
        // 行高上限 52dp：60dp 时 9 行会占掉 84% 屏高，几乎全屏遮住编辑面板；
        // 52dp 降到 75%，且仍高于 Android 推荐的 48dp 最小触摸目标。
        float ih = dp(48f);
        float contentH = rows * ih;
        float availH = (float) mH * 0.94f - fixed;
        float viewH = Math.min(contentH, availH);

        mListContentH = contentH;
        mListScrollMax = Math.max(0f, contentH - viewH);
        if (mListScroll > mListScrollMax) {
            mListScroll = mListScrollMax;
        }
        if (mListScroll < 0f) {
            mListScroll = 0f;
        }

        float ph = titleH + viewH + btnH + pad * 3f;
        float left = ((float) mW - pw) / 2f;
        float top = ((float) mH - ph) / 2f;
        mListPanelRect.set(left, top, left + pw, top + ph);

        float viewTop = top + pad + titleH;
        mListViewRect.set(left + pad, viewTop, left + pw - pad, viewTop + viewH);

        boolean fixUi = mListMode == LIST_FIX && mFixUiMode;
        float capW = dp(30f);
        float capGap = dp(5f);
        for (int i = 0; i < n; i++) {
            int col = i % cols;
            int row = i / cols;
            float x = left + pad + col * (iw + colGap);
            float iy = row * ih;
            if (fixUi) {
                // 名字区让位给右侧那一排胶囊，不然文字会压到胶囊上。
                // 胶囊数按 FX_TPL_SHORT.length 走，加模板时这里自动跟着变。
                int nCap = FX_TPL_SHORT.length;
                float nameW = iw - ((capW + capGap) * nCap);
                mListItemRects[i].set(x, iy, x + nameW, iy + ih - dp(4f));
                for (int k = 0; k < nCap; k++) {
                    float cx = x + nameW + capGap + k * (capW + capGap);
                    mFixTplRects[i][k].set(cx, iy + dp(8f), cx + capW,
                            iy + ih - dp(12f));
                }
            } else if (mListMode == LIST_COMBO_EDIT && comboRowIsAct(i)) {
                //
                // 键行右侧：[结束][删]；延迟行只有 [删]。
                //
                // 【延迟行为什么不给「结束」】
                //   「结束」= 松开前面还按着的键。延迟行本身不按键，
                //   在它后面再加个"结束"没有意义 —— 能停的是键，不是等待。
                //
                // 「删」52dp 是加宽过的：40dp 时手指容易点到旁边的名字区
                // （那是"重选这个键"，不是删除）。
                float dw = dp(52f);
                float ew = dp(56f);
                float gap = dp(4f);
                float delRight = x + iw;
                float delLeft = delRight - dw;
                mComboDelRects[i].set(delLeft, iy + dp(6f),
                        delRight, iy + ih - dp(10f));
                float nameRight = delLeft - dp(6f);
                if (comboRowIsKeyAct(i)) {
                    float endRight = delLeft - gap;
                    float endLeft = endRight - ew;
                    mComboEndRects[i].set(endLeft, iy + dp(6f),
                            endRight, iy + ih - dp(10f));
                    nameRight = endLeft - dp(6f);
                } else {
                    mComboEndRects[i].setEmpty();
                }
                // 名字区让位，否则文字压到按钮上
                mListItemRects[i].set(x, iy, nameRight, iy + ih - dp(4f));
            } else {
                mListItemRects[i].set(x, iy, x + iw, iy + ih - dp(4f));
            }
        }
        // 非键行（名字行、添加行）和多余位置一律清空：
        // 留着上次坐标的话，点到空白处也会命中上一轮那个「删」。
        if (mListMode != LIST_COMBO_EDIT) {
            for (int i = 0; i < mComboDelRects.length; i++) {
                mComboDelRects[i].setEmpty();
            }
            for (int i = 0; i < mComboEndRects.length; i++) {
                mComboEndRects[i].setEmpty();
            }
        } else {
            for (int i = 0; i < mComboDelRects.length; i++) {
                if (i >= n || !comboRowIsAct(i)) {
                    mComboDelRects[i].setEmpty();
                    mComboEndRects[i].setEmpty();
                }
            }
        }
        // 非功能键页要把胶囊清掉：留着上次坐标的话，切到别的列表后
        // 手指点到那个位置还是会命中（矩形还在，只是没画出来）。
        if (!fixUi) {
            for (int i = 0; i < mFixTplRects.length; i++) {
                for (int k = 0; k < mFixTplRects[i].length; k++) {
                    mFixTplRects[i][k].setEmpty();
                }
            }
        }
        // 多余的位置清掉：留着旧坐标的话，点到空白处也会命中上一轮的项
        for (int i = n; i < mListItemRects.length; i++) {
            mListItemRects[i].setEmpty();
        }

        float bw = (pw - pad * 2f - dp(10f)) / 2f;
        float by = top + ph - pad - btnH;
        // LIST_SELECT / LIST_PAD 点了就生效，不需要「确定」，取消占满整行
        if (mListMode == LIST_SELECT || mListMode == LIST_FIX_TPL
                || mListMode == LIST_FIX || mListMode == LIST_CREATE
                || mListMode == LIST_MORE || mListMode == LIST_COMBO_DELAY
                ) {
            // 「固定显示」改一项生效一项，不需要「确定」
            if (mListBack != LIST_NONE && !listOkIsClose()) {
                // 有上级：左边「返回」、右边「确定」，各占一半
                mListCancelRect.set(left + pad, by, left + pad + bw, by + btnH);
                mListOkRect.set(left + pad + bw + dp(10f), by, left + pw - pad, by + btnH);
            } else {
                mListCancelRect.set(left + pad, by, left + pw - pad, by + btnH);
                mListOkRect.setEmpty();
            }
            if (mListMode != LIST_SELECT) {
                // 从 LIST_PAD / LIST_KEY 回来时它带着上次坐标，
                // 不清会在「固定显示」/「按键创建」里冒出「连续创建」勾选框
                mKeepOpenRect.setEmpty();
                // 搜索按钮同理：LIST_KEY 摆过一次，坐标留着会变成幽灵按钮
                mListSearchRect.setEmpty();
                return;
            }
            // 【同样要单独布局分类按钮】
            // 这个分支也是提前 return 的，走不到下面那套通用布局，
            // 光把 LIST_SELECT 加进 listHasCategory() 没用 ——
            // mListCatRect 一直是空的，画不出来也点不到（和常用工具那次一样）。
            float smH2 = dp(30f);
            float smTop2 = top + pad + (titleH - smH2) / 2f;
            float catW2 = Math.min(dp(78f), pw * 0.34f);
            mListCatRect.set(left + pw - pad - catW2, smTop2,
                    left + pw - pad, smTop2 + smH2);
            int catNS = catCount();
            float dropTopS = mListCatRect.bottom;
            float availS = (float) mH * 0.97f - dropTopS;
            float ciHS = Math.max(dp(22f), Math.min(dp(38f), availS / catNS));
            for (int i = 0; i < catNS; i++) {
                mListCatItemRects[i].set(mListCatRect.left, dropTopS + i * ciHS,
                        mListCatRect.right, dropTopS + (i + 1) * ciHS);
            }
            // 选中按钮是"点一项就走"，不是勾选型，没有全选
            mListSelAllRect.setEmpty();
            return;
        } else if (mListMode == LIST_PAD) {
            // 同 LIST_KEY：点一项就创建，不要「确定」
            if (mListBack != LIST_NONE && !listOkIsClose()) {
                mListCancelRect.set(left + pad, by, left + pad + bw, by + btnH);
                mListOkRect.set(left + pad + bw + dp(10f), by, left + pw - pad, by + btnH);
            } else {
                mListCancelRect.set(left + pad, by, left + pw - pad, by + btnH);
                mListOkRect.setEmpty();
            }
            // 「连续创建」勾选框：放在标题行右端，和键盘列表一致。
            // 不设的话 mKeepOpenRect 是空的 —— 画不出来也点不到。
            float kw = dp(78f);
            float kh = dp(24f);
            mKeepOpenRect.set(left + pw - pad - kw, top + titleH / 2f - kh / 2f,
                    left + pw - pad, top + titleH / 2f + kh / 2f);
            return;
        } else if (mListMode == LIST_KEY) {
            // 键盘列表点一项就创建，没有"勾选再确定"这回事，
            // 所以不要「确定」，关闭按钮占满整行。
            if (mListBack != LIST_NONE && !listOkIsClose()) {
                mListCancelRect.set(left + pad, by, left + pad + bw, by + btnH);
                mListOkRect.set(left + pad + bw + dp(10f), by, left + pw - pad, by + btnH);
            } else {
                mListCancelRect.set(left + pad, by, left + pw - pad, by + btnH);
                mListOkRect.setEmpty();
            }
            // 「连续创建」勾选框：放在标题行右端。
            //
            // 【这里不能用 p】layoutList() 里没有 p 这个变量 ——
            // p 是 drawPickList() 里 `RectF p = mListPanelRect` 的局部变量，
            // 两个方法各是各的。这里要用当前算出来的 left / top / pw。
            // 4 个 p.xxx 就是那 4 个 "Unknown entity 'p'"。
            //
            // 宽 96dp 够放"连续创建"四个字 + 一个方框；
            // 面板窄时（小屏 136dp）会和标题冲突，
            // 标题那边会自动缩字号并左移让位（见 drawPickList）。
            float kw = Math.min(dp(96f), pw - pad * 2f);
            mKeepOpenRect.set(left + pw - pad - kw, top + dp(10f),
                    left + pw - pad, top + dp(38f));
            // 键盘候选键有 104 个，是最需要搜索的列表 ——
            // 这里提前 return，搜索按钮得单独摆一次，别漏了。
            // 放在「连续创建」下面一行，和它右对齐。
            mListSearchRect.setEmpty();
            if (listCanSearch()) {
                float seW = Math.min(dp(96f), pw - pad * 2f);
                float seTop = mKeepOpenRect.bottom + dp(6f);
                mListSearchRect.set(left + pw - pad - seW, seTop,
                        left + pw - pad, seTop + dp(30f));
            }
            return;
        }
        // 上面 LIST_KEY / LIST_SELECT 已经 return 掉了，这里只处理需要「确定」的
        if (listOkIsClose() || listNoOkButton()) {
            // 子列表里"点一项就生效"的那些（选方向 / 选种类 / 动作序列编辑…）：
            // 退出统一走左边那颗「返回」，右边不再摆第二颗退出按钮。
            mListCancelRect.set(left + pad, by, left + pw - pad, by + btnH);
            mListOkRect.setEmpty();
        } else {
            mListCancelRect.set(left + pad, by, left + pad + bw, by + btnH);
            mListOkRect.set(left + pad + bw + dp(10f), by, left + pw - pad, by + btnH);
        }

        // 标题行两端的小按钮：左「全选」、右「分类」。
        // 两个都要和标题抢地方，所以宽高统一 30dp 高，
        // 宽度按面板比例夹一下，小屏上不至于把标题挤没。
        float smH = dp(30f);
        float smTop = top + pad + (titleH - smH) / 2f;
        if (listHasCategory()) {
            float catW = Math.min(dp(78f), pw * 0.34f);
            mListCatRect.set(left + pw - pad - catW, smTop,
                    left + pw - pad, smTop + smH);
            // 浮层高度按"面板剩余空间"自适应：
            // 固定 38dp × 4 项 = 152dp，面板一矮（列表项少时）就会顶出面板、
            // 伸到底部按钮那一行，被后画的确定按钮盖住。
            // 【不能叫 n】外层已经有一个 int n = listItemCount()。
            //   Java 不允许局部变量遮蔽外层变量（C++ 可以），
            //   内层块再声明同名 n 就是 "already is another variable named 'n'"。
            int catN = catCount();
            float dropTop = mListCatRect.bottom;
            // 下界取"屏幕底"而不是"按钮顶"。
            //
            // 取按钮顶的话，某个分类下没有按钮时列表为空、面板被压得很矮，
            // by - dropTop 只剩二十几 dp，4 个分类项挤在里面根本点不准
            // （每项被压到 5dp，比手指小一个数量级）。
            //
            // 浮层本来就是覆盖层，允许它伸到面板外面，只要不出屏。
            // 盖住按钮没关系 —— 判定顺序里浮层优先，见 performListTap。
            float avail = (float) mH * 0.97f - dropTop;
            float ciH = Math.max(dp(22f), Math.min(dp(38f), avail / catN));
            for (int i = 0; i < catN; i++) {
                mListCatItemRects[i].set(mListCatRect.left, dropTop + i * ciH,
                        mListCatRect.right, dropTop + (i + 1) * ciH);
            }
        }
        if (listHasSelAll()) {
            float saW = Math.min(dp(60f), pw * 0.26f);
            mListSelAllRect.set(left + pad, smTop, left + pad + saW, smTop + smH);
        } else {
            mListSelAllRect.setEmpty();
        }
        // 「搜索」放标题行最右端。有「分类」按钮时给它让位（往左挪），
        // 两者挤同一角会互相盖住。
        if (listCanSearch()) {
            float seW = Math.min(dp(58f), pw * 0.26f);
            float seRight = mListCatRect.isEmpty()
                    ? (left + pw - pad) : (mListCatRect.left - dp(6f));
            mListSearchRect.set(seRight - seW, smTop, seRight, smTop + smH);
        } else {
            mListSearchRect.setEmpty();
        }
    }

    void layoutStickXyList() {
        float pad = dp(12f);
        float titleH = dp(44f);
        float btnH = dp(46f);
        float rowH = dp(52f);
        float prevH = dp(130f);
        float contentH = rowH * 2 + prevH;
        float fixed = titleH + btnH + pad * 3f;
        float availH = (float) mH * 0.94f - fixed;
        float viewH = Math.min(contentH, availH);
        mListContentH = contentH;
        mListScrollMax = Math.max(0f, contentH - viewH);
        if (mListScroll > mListScrollMax) mListScroll = mListScrollMax;
        if (mListScroll < 0f) mListScroll = 0f;

        float ph = titleH + viewH + btnH + pad * 3f;
        float pw = Math.min((float) mW - dp(40f), dp(340f));
        float left = ((float) mW - pw) / 2f;
        float top = ((float) mH - ph) / 2f;
        mListPanelRect.set(left, top, left + pw, top + ph);

        float vy = top + pad + titleH;
        mListViewRect.set(left + pad, vy, left + pw - pad, vy + viewH);

        float x0 = left + pad;
        float x1 = left + pw - pad;
        float stepW = dp(34f);
        float gap = dp(6f);
        float valueW = dp(44f);
        for (int i = 0; i < 3; i++) {
            mListItemRects[i].setEmpty();
            mGridMinusRects[i].setEmpty();
            mGridPlusRects[i].setEmpty();
        }
        for (int i = 0; i < 2; i++) {
            float iy = i * rowH;
            float plusL = x1 - stepW;
            float valueR = plusL - gap;
            float valueL = valueR - valueW;
            float minusR = valueL - gap;
            float minusL = minusR - stepW;
            mGridMinusRects[i].set(minusL, iy + dp(8f), minusR, iy + rowH - dp(12f));
            mGridPlusRects[i].set(plusL, iy + dp(8f), plusL + stepW, iy + rowH - dp(12f));
            mListItemRects[i].set(x0, iy, minusL - gap, iy + rowH - dp(6f));
        }
        // 预览占剩下的一整块
        float py = 2 * rowH;
        mListItemRects[2].set(x0, py, x1, py + prevH - dp(6f));
        //
        // 【预览盘的几何要单独记一份】
        //   绘制时是从 mListItemRects[2] 现算圆心半径的，
        //   命中判定要同一个圆，两处各算一遍容易对不上（改了一边就错位）。
        //   统一在这里算好存起来，绘制和命中都读它。
        RectF pr = mListItemRects[2];
        float ptextH = dp(20f);          // 底部那行 "X ..% Y ..%" 占的高度
        float prr = Math.min(pr.width(), pr.height() - ptextH) / 2f - dp(10f);
        prr = Math.max(prr, dp(24f));
        mStickPadR = prr;
        float pcx = pr.centerX();
        float pcy = pr.top + (pr.height() - ptextH) / 2f;
        mStickPadRect.set(pcx - prr, pcy - prr, pcx + prr, pcy + prr);

        float by = top + ph - pad - btnH;
        float bw = (pw - pad * 2f - dp(10f)) / 2f;
        mListCancelRect.set(left + pad, by, left + pad + bw, by + btnH);
        mListOkRect.set(left + pad + bw + dp(10f), by, left + pw - pad, by + btnH);
        mListCreateRect.setEmpty();
        mListSelAllRect.setEmpty();
        mListCatRect.setEmpty();
        mListSearchRect.setEmpty();
        mListImportRect.setEmpty();
    }

    /**
     * 滑条左边名字那一栏要留多宽 —— 取所有候选名字里最宽的一个。
     *
     * 名字只有两三个字，但因为字体是 mPanelH * 0.115，
     * 屏幕小、面板显得大的时候这两个字能占掉小半行，写死宽度不够用。
     */
    private float maxSliderLabelWidth() {
        mBarTextPaint.setTextSize(mPanelH * 0.115f);
        float w = 0f;
        String[][] all = {SL_TEXT, SL_TEXT_SHAPE, SL_TEXT_STICK, SL_TEXT_PRIO};
        for (int g = 0; g < all.length; g++) {
            for (int i = 0; i < all[g].length; i++) {
                // 名字是左对齐画在 dp(10) 处，所以直接量它占多宽
                float tw = mBarTextPaint.measureText(all[g][i]) + dp(10f);
                if (tw > w) {
                    w = tw;
                }
            }
        }
        return w;
    }

    /**
     * 滑条右边数值那一栏要留多宽 —— 取所有候选数值里最宽的一个。
     *
     * 最宽的不是 "100%"，是布局调节下的倍率（"1.19x"）和形状模式的
     * "-1.19x"，五个字符。按 "100%" 量会不够。
     */
    private float maxSliderValueWidth() {
        mBarTextPaint.setTextSize(mPanelH * 0.115f);
        float w = 0f;
        String[] cands = {"100%", "255", "-1.19x", "1.19x", "0%"};
        for (int i = 0; i < cands.length; i++) {
            // 数值是右对齐画在 mW - dp(10) 处
            float tw = mBarTextPaint.measureText(cands[i]) + dp(10f);
            if (tw > w) {
                w = tw;
            }
        }
        return w;
    }

    void layoutTools() {
        float pad = dp(10f);
        // 面板的位置和尺寸由 computePanelMetrics() 算好，这里只排内容。
        float tipH = mPanelH * 0.15f;
        float btnH = mPanelH * 0.14f;
        // 滑条从 2 条变 3 条（加了「字体」），
        // 每条还按 0.20 的话三行滑条 + 两行按钮会**超出面板底部**
        // （0.16 + 3×0.20 + 2×0.14 = 1.04 > 1.0）。
        // 改成：把"提示行以下、按钮行以上"的空间平分给 SL_COUNT 条。
        float slArea = mPanelH - tipH - btnH * 2f - dp(8f);
        float slH = slArea / SL_COUNT;
        // 保底只防"算出来是负数"，不再往上夹 ——
        // 一夹就会把两行工具按钮顶出面板底部（见上面面板下限的注释）。
        if (slH < dp(16f)) slH = dp(16f);

        // 右上角两个按钮：「+」展开到最大，「v」再收小一档（只留右下角加号）。
        // 两个都在同一个角落，v 在 + 的左边。
        float ts = mBarH * 0.5f;
        float top = mPanelTop + dp(5f);
        mPanelToggleRect.set((float) mW - dp(8f) - ts, top,
                (float) mW - dp(8f), top + ts);
        mPanelMinRect.set((float) mW - dp(8f) - ts * 2f - dp(6f), top,
                (float) mW - dp(8f) - ts - dp(6f), top + ts);

        // 左上角「更多选项」入口：放在提示行左端，和右上角的「−」「v」不冲突。
        //
        // 比原来的「形状」按钮宽 —— 四个字放不进原先那个 0.95*barH 的窄条。
        // 右端到不了屏幕中线，不会压到居中的提示文字。
        float sbW = mBarH * 1.65f;
        float sbH = tipH * 0.72f;
        mShapeBtnRect.set(dp(10f), mPanelTop + (tipH - sbH) / 2f,
                dp(10f) + sbW, mPanelTop + (tipH - sbH) / 2f + sbH);
        // 「返回」：紧跟在「更多选项」右边，只在子模式里画。
        // 常规模式下置空 —— 留着坐标的话，那个位置会变成点得到的幽灵按钮。
        float bkW = mBarH * 0.95f;
        mSubBackRect.set(dp(10f) + sbW + dp(6f), mPanelTop + (tipH - sbH) / 2f,
                dp(10f) + sbW + dp(6f) + bkW, mPanelTop + (tipH - sbH) / 2f + sbH);
        // 多选时的「布局调节」/「中心」：紧贴左边最后一个按钮。
        //
        // 【起点按「返回」当前显不显来定，别写死】
        //   以前无条件从"更多 + 返回"之后起算，于是常规面板下（返回不画）
        //   「更多选项」和「布局调节」之间永远空着一格 —— 看着像多了个按钮。
        //   而形状 / 摇杆设置里布局调节压根不显示，那格空得更没道理。
        float mLay = (inSubMode() ? mSubBackRect.right : mShapeBtnRect.right) + dp(6f);
        float mLayW = mBarH * 1.35f;
        float mCenW = mBarH * 1.70f;
        //
        // 【不适用时必须置空，不能留着坐标】
        //   这两个矩形以前无条件 set()，而它们只在
        //   "多选 + 常规面板"下才画。子模式（形状 / 摇杆设置）里不画，
        //   坐标却还留在那儿 —— 命中判定用的是同一批矩形，
        //   于是那个看不见的位置照样点得到（幽灵按钮）。
        //
        //   更糟的是 layoutTools() 只在尺寸变化时被调用，进子模式不重排，
        //   mLay 还是按"没有返回按钮"算出来的旧值 ——
        //   「布局调节」的矩形正好压在「返回」上，点返回反而切了布局调节。
        //   （面板重排见 panelLayoutDirty()。）
        if (!isMulti() || mGridCfg || inSubMode()) {
            mMultiLayBtnRect.setEmpty();
            mMultiCenBtnRect.setEmpty();
        } else {
            mMultiLayBtnRect.set(mLay, mPanelTop + (tipH - sbH) / 2f,
                    mLay + mLayW, mPanelTop + (tipH - sbH) / 2f + sbH);
            // 「中心」只在布局调节打开时才存在：关着的时候留矩形 = 幽灵按钮
            if (mAdjLayoutMode) {
                mMultiCenBtnRect.set(mLay + mLayW + dp(6f),
                        mPanelTop + (tipH - sbH) / 2f,
                        mLay + mLayW + dp(6f) + mCenW,
                        mPanelTop + (tipH - sbH) / 2f + sbH);
            } else {
                mMultiCenBtnRect.setEmpty();
            }
        }

        // 多选调节面板：提示行左端改成「布局调节」开关 + 「中心」按钮，
        // 占的是「形状」入口的位置（这两种面板不会同时出现，不冲突）。
        float layW = mBarH * 1.55f;
        float cenW = mBarH * 2.35f;
        mAdjLayoutBtnRect.set(dp(10f), mPanelTop + (tipH - sbH) / 2f,
                dp(10f) + layW, mPanelTop + (tipH - sbH) / 2f + sbH);
        mAdjAnchorBtnRect.set(dp(10f) + layW + dp(6f),
                mPanelTop + (tipH - sbH) / 2f,
                dp(10f) + layW + dp(6f) + cenW,
                mPanelTop + (tipH - sbH) / 2f + sbH);

        // 两条滑条：一整行都能拖，不一定要精确按在那根细线上。
        // 排在面板上半部，比之前明显靠上。
        float y = mPanelTop + tipH;
        /*
          【滑条两端的留白要按"实测文字宽度 + 滑块半径"算，不能拍脑袋】

          以前是写死的 labelW/valW（按 mBarH 的比例）。
          而数值文字的字体是 mPanelH * 0.115 —— 和 mBarH 没有比例关系，
          屏幕上小、面板大的时候文字反而更宽，写死的留白根本不够。
          于是滑块拖到两端时会压在文字上：拖到最大盖住数值，
          拖到最小盖住名字（滑块画在文字**之后**，会盖上去）。

          现在按当前字体量一遍最宽的候选文字，再各加一个滑块半径，
          保证滑块边缘永远碰不到文字。
        */
        float trackH = slH * 0.40f;
        float thumbR = trackH * 0.80f;
        float slGap = dp(4f);
        float labelW = maxSliderLabelWidth() + slGap;
        float valW = maxSliderValueWidth() + slGap;
        // 极端窄屏下两头留白把轨道挤没了 —— 保底给它留点长度
        float minTrack = dp(40f);
        float left = pad + labelW + thumbR;
        float right = (float) mW - pad - valW - thumbR;
        if (right - left < minTrack) {
            float mid = (left + right) / 2f;
            left = mid - minTrack / 2f;
            right = mid + minTrack / 2f;
        }
        for (int i = 0; i < SL_COUNT; i++) {
            mSlHit[i].set(0f, y, (float) mW, y + slH);
            mSlTrack[i].set(left, y + slH * 0.30f, right, y + slH * 0.70f);
            y += slH;
        }

        // 网格配置面板：滑条那两行改成「列数 / 行数」的 − + 步进器。
        // 网格是离散的整数档位，用滑条拖没有意义，点 − / + 更准。
        float bwG = dp(58f);
        float gapG = dp(6f);
        RectF gRow0 = mSlHit[0];
        RectF gRow1 = mSlHit[1];
        mGridColPlusRect.set((float) mW - dp(10f) - bwG, gRow0.top + dp(3f),
                (float) mW - dp(10f), gRow0.bottom - dp(3f));
        mGridColMinusRect.set((float) mW - dp(10f) - bwG * 2f - gapG,
                gRow0.top + dp(3f),
                (float) mW - dp(10f) - bwG - gapG, gRow0.bottom - dp(3f));
        mGridRowPlusRect.set((float) mW - dp(10f) - bwG, gRow1.top + dp(3f),
                (float) mW - dp(10f), gRow1.bottom - dp(3f));
        mGridRowMinusRect.set((float) mW - dp(10f) - bwG * 2f - gapG,
                gRow1.top + dp(3f),
                (float) mW - dp(10f) - bwG - gapG, gRow1.bottom - dp(3f));

        // 摇杆设置：开关行放在第三行那块（和形状的圆形/四边形同一位置）。
        // 范围滑条仍占第一行 —— 开关和滑条分开两行，不会互相挤。
        RectF thirdS = mSlHit[2];
        mStickFixRect.set(dp(10f), thirdS.top + dp(3f),
                (float) mW - dp(10f), thirdS.bottom - dp(3f));

        // 形状模式：第三行不放滑条，改放「圆形」「四边形」两个按钮。
        // 放在 mSlHit[2] 那块区域内，左右各一半。
        RectF third = mSlHit[2];
        float gapS = dp(8f);
        float bwS = (third.width() - dp(20f) - gapS) / 2f;
        mShapeCircleRect.set(dp(10f), third.top + dp(3f),
                dp(10f) + bwS, third.bottom - dp(3f));
        mShapeRectRect.set(dp(10f) + bwS + gapS, third.top + dp(3f),
                dp(10f) + bwS + gapS + bwS, third.bottom - dp(3f));

        // 工具按钮：两行，第一行 3 个、第二行 2 个，整行居中。
        // 第二行（含「完成」）贴着面板底边，面板变高时不会跟着往上跑。
        float gap = dp(10f);
        float side = dp(10f);
        float avail = (float) mW - side * 2f;
        float rowGap = dp(6f);
        float by1 = mPanelBottom - btnH - dp(6f);
        float by0 = by1 - btnH - rowGap;

        int[][] rows = {TOOL_ROW_0, TOOL_ROW_1};
        float[] rowY = {by0, by1};
        for (int r = 0; r < rows.length; r++) {
            int[] row = rows[r];
            float bw = (avail - gap * (row.length - 1)) / row.length;
            float x = side;
            for (int t : row) {
                mToolRects[t].set(x, rowY[r], x + bw, rowY[r] + btnH);
                x += bw + gap;
            }
        }
    }

    void layoutTrigList() {
        float pad = dp(12f);
        float titleH = dp(44f);
        float btnH = dp(46f);
        float prevH = dp(120f);
        float contentH = prevH;
        float fixed = titleH + btnH + pad * 3f;
        float availH = (float) mH * 0.94f - fixed;
        float viewH = Math.min(contentH, availH);
        mListContentH = contentH;
        mListScrollMax = Math.max(0f, contentH - viewH);
        if (mListScroll > mListScrollMax) mListScroll = mListScrollMax;
        if (mListScroll < 0f) mListScroll = 0f;

        float ph = titleH + viewH + btnH + pad * 3f;
        float pw = Math.min((float) mW - dp(40f), dp(340f));
        float left = ((float) mW - pw) / 2f;
        float top = ((float) mH - ph) / 2f;
        mListPanelRect.set(left, top, left + pw, top + ph);

        float vy = top + pad + titleH;
        mListViewRect.set(left + pad, vy, left + pw - pad, vy + viewH);

        float x0 = left + pad;
        float x1 = left + pw - pad;
        // 只有一项，−/+ 矩形清空 —— 留着旧坐标会变成幽灵按钮
        for (int i = 0; i < 3; i++) {
            mListItemRects[i].setEmpty();
            mGridMinusRects[i].setEmpty();
            mGridPlusRects[i].setEmpty();
        }
        mListItemRects[0].set(x0, 0f, x1, prevH - dp(6f));
        //
        // 【力度条的几何单独记一份】
        //   绘制和命中必须用同一个矩形，两处各算一遍的话改了一边就错位，
        //   表现为"看得见点不到"。
        //   竖向外扩 dp(10)，手指粗也能点中。
        RectF tr = mListItemRects[0];
        float tinset = dp(14f);
        float tbh = dp(30f);
        float tcy = tr.top + (tr.height() - dp(24f)) / 2f;
        // 力度条和优先级条共用这一份排版，另一条清空 ——
        // 留着旧坐标会变成看不见但点得到的幽灵控件。
        // 力度条不再和优先级条共用排版 —— 优先级改成了面板子模式，
        // 列表里只剩下扳机力度这一条。
        mTrigBarRect.set(tr.left + tinset, tcy - tbh / 2f,
                tr.right - tinset, tcy + tbh / 2f);

        float by = top + ph - pad - btnH;
        float bw = (pw - pad * 2f - dp(10f)) / 2f;
        mListCancelRect.set(left + pad, by, left + pad + bw, by + btnH);
        mListOkRect.set(left + pad + bw + dp(10f), by, left + pw - pad, by + btnH);
        mListCreateRect.setEmpty();
        mListSelAllRect.setEmpty();
        mListCatRect.setEmpty();
        mListSearchRect.setEmpty();
        mListImportRect.setEmpty();
    }

    boolean listCanSearch() {
        switch (mListMode) {
            case LIST_GRID:        // 网格/吸附：7 行固定配置
            case LIST_RESET_DIM:   // 重置属性：4 项
            case LIST_SYNC_DIM:    // 同步属性：4 项
            case LIST_SYNC_DIR:    // 同步方向：2 项
            case LIST_TPL:         // 选模板：4 项（加鼠标模板后）
            case LIST_FIX_TPL:     // 固定显示：5 项（加鼠标模板后）
            case LIST_CREATE:      // 按键创建类型：就 3 项，搜什么
            case LIST_MORE:        // 更多选项：就那么几项
            case LIST_COMBO_DELAY: // 延迟档位：12 档固定，搜什么
            case LIST_COMBO_DIR:   // 方向：8 项固定
            case LIST_COMBO_STICK_MODE:
            case LIST_COMBO_STICK_XY:
            case LIST_COMBO_TRIG:
            case LIST_COMBO_KIND:
            case LIST_COMBO_CROSS:
            case LIST_BTN_COLOR:   // 十几档固定色，搜什么
            case LIST_COMBO_EDIT:  // 组合键编辑：行数会变，搜索映射对不上
                return false;
            default:
                return true;
        }
    }

    boolean listConfirmEnabled() {
        switch (mListMode) {
            case LIST_HIDE:
            case LIST_LINK:
                return true;
            case LIST_COMBO_STICK_XY:
                // 坐标一开始就有值（默认 0,0），不用先选什么
                return true;
            case LIST_COMBO_TRIG:
                // 力度默认 100%，直接可确定
                return true;
            case LIST_BTN_COLOR:
                // 档位固定，进来就能确定
                return true;
            case LIST_SWAP:
                return mSwapPick[0] != NONE && mSwapPick[1] != NONE;
            case LIST_MULTI_ADJ:
            case LIST_MULTI_SHAPE:
                // 选中集合本来就允许一个 —— 勾一个就是单选，
                // 勾多个才是批量。强行要求 >= 2 的话，
                // "先选中一个再慢慢加"这种用法就没了。
                return listSelCount() > 0;
            case LIST_ADJ_ANCHOR:
                // 中心是单选语义，选了就能确定
                return mSwapPick[0] != NONE;
            case LIST_TPL:
                // 模板一进来就有默认选中，直接可用
                return true;
            case LIST_SYNC_DIR:
                // 单选语义：点了就定方向，去挑按钮
                for (int k = 0; k < 2; k++) {
                    if (mListSel[k]) {
                        return true;
                    }
                }
                return false;
            case LIST_SYNC_PICK:
                //
                // 【挑按钮这步不能用 SD_COUNT 当上限】
                //   这一步 mListSel 的下标是**元素下标**（0..N-1，N=182），
                //   不是属性号（0..3）。原先照抄属性列表那段只查前 4 个下标，
                //   于是勾了 A 键（下标 3）以外任何按钮都算"没选" ——
                //   全选完了确定键还是锁着。
                //
                //   （属性列表那一步下标才是属性号，所以分开写。）
                return listSelCount() > 0;
            case LIST_SYNC_DIM:
                // 和重置属性同理：读勾选中的 mListSel，不能读搬过去的那份
                for (int k = 0; k < SD_COUNT; k++) {
                    if (mListSel[k]) {
                        return true;
                    }
                }
                return false;
            case LIST_RESET_DIM:
                // 【必须读 mListSel，不能读 mResetDims】
                //   mResetDims 是点确定的那一刻才从 mListSel 搬过去的"载体"，
                //   勾选阶段它还是全 false —— 拿它判断的话确定键永远锁着：
                //   钥匙锁在保险箱里，而保险箱要这把钥匙才打得开。
                for (int k = 0; k < RD_COUNT; k++) {
                    if (mListSel[k]) {
                        return true;
                    }
                }
                return false;
            default:
                return listSelCount() > 0;
        }
    }

    void listGoBack() {
        int parent = mListBack;
        if (parent == LIST_NONE) {
            closeList();
            return;
        }
        // 挑选态是"某一层的临时状态"，退回上一层就该清掉。
        // 不清的话下次正常「手柄按键…」创建会被误判成挑选。
        mFixPicking = false;
        if (parent == LIST_FIX) {
            // 固定显示：退回前要按当前模板重建行，否则列表是旧的
            buildFixRows(mFixTpl);
        }
        // 【先弹再 openList】openList 会清栈，弹晚了就拿不到东西。
        int restore = mListBackStack.isEmpty() ? LIST_NONE
                : mListBackStack.remove(mListBackStack.size() - 1);
        // 【弹完之后必须先快照剩下的栈】
        //   openList() 里有 mListBackStack.clear()（那是给"顶层入口"用的），
        //   返回路径走同一个函数就顺带把整条链清了。
        //   于是只有倒数第一次返回是对的：再往上退，栈已经空了，
        //   restore 拿到 NONE —— 这一层被当成顶层列表，
        //   底栏按顶层画，右下角冒出「确定」（而它点了什么也不会发生）。
        //   三层以内不容易发现，所以"点上再返回"看着是好的，
        //   一路退到「按键创建 -> 手柄按键 -> 返回」才暴露。
        java.util.ArrayList<Integer> saved =
                new java.util.ArrayList<Integer>(mListBackStack);
        openList(parent);
        mListBackStack.clear();
        mListBackStack.addAll(saved);
        mListBack = restore;
        // 上级刚变过，底栏要按新的上级重排，否则又是"只有返回没有完成"
        layoutList();
    }

    boolean listHasCategory() {
        return mListMode == LIST_TOOL
                || mListMode == LIST_RESET_ONE || mListMode == LIST_LINK
                // 互换位置：82 个键的键盘布局下不筛根本找不到
                || mListMode == LIST_SWAP
                || mListMode == LIST_HIDE || mListMode == LIST_DEL_KEY
                || mListMode == LIST_SELECT
                || mListMode == LIST_MULTI_ADJ || mListMode == LIST_MULTI_SHAPE
                || mListMode == LIST_LAYOUT || mListMode == LIST_LAYOUT_PICK;
    }

    boolean listHasSelAll() {
        return mListMode == LIST_RESET_ONE || mListMode == LIST_LINK
                || mListMode == LIST_HIDE || mListMode == LIST_DEL_KEY
                // 多选调节：键盘布局 80+ 个键，一个个勾太累
                || mListMode == LIST_MULTI_ADJ || mListMode == LIST_MULTI_SHAPE
                // 横竖屏同步第一步：一份布局几十个键，一个个勾太累
                || mListMode == LIST_SYNC_PICK
                // 同步/重置的属性列表只有 4 项，但也支持一次勾全
                // （方向列表只有 2 项且是单选语义，不给全选）
                || mListMode == LIST_SYNC_DIM || mListMode == LIST_RESET_DIM;
    }

    int listItemCount() {
        if (hasQuery() && listUsesGenericFilter()) {
            return mListFilterCount;
        }
        return listItemCountRaw();
    }

    int listItemCountRaw() {
        if (mListMode == LIST_GRID) {
            return GI_COUNT;
        }
        if (mListMode == LIST_RESET_DIM) {
            return RD_COUNT;
        }
        if (mListMode == LIST_SYNC_DIM) {
            return SD_COUNT;
        }
        if (mListMode == LIST_SYNC_DIR) {
            return 2;
        }
        if (mListMode == LIST_KEY) {
            return PadLayout.KEY_NAMES.length;
        }
        if (mListMode == LIST_PAD) {
            return PadLayout.PAD_CANDIDATES.length;
        }
        if (mListMode == LIST_CREATE) {
            return CREATE_NAMES.length;
        }
        if (mListMode == LIST_MOUSE) {
            return MOUSE_CANDIDATES.length;
        }
        if (mListMode == LIST_MORE) {
            return moreRowNames().length;
        }
        if (mListMode == LIST_APPEAR) {
            return APPEAR_NAMES.length;
        }
        if (mListMode == LIST_PASS_COLOR) {
            return PASS_COLOR_NAMES.length;
        }
        if (mListMode == LIST_BTN_COLOR) {
            return BTN_COLOR_NAMES.length;
        }
        if (mListMode == LIST_COMBO_EDIT) {
            return comboEditRowCount();
        }
        if (mListMode == LIST_COMBO_TYPE) {
            return 2;
        }
        if (mListMode == LIST_COMBO_DELAY) {
            return PadLayout.DELAY_PRESETS.length;
        }
        if (mListMode == LIST_COMBO_DIR) {
            return DIR_COUNT;
        }
        if (mListMode == LIST_COMBO_STICK_MODE) {
            return 2;
        }
        if (mListMode == LIST_COMBO_STICK_XY) {
            return 3;   // X / Y / 预览
        }
        if (mListMode == LIST_COMBO_TRIG) {
            return 1;   // 就一块：力度条（−/+ 已去掉，直接拖条）
        }
        if (mListMode == LIST_COMBO_KIND) {
            return 2;
        }
        if (mListMode == LIST_COMBO_CROSS) {
            return 6;   // 名字 / 上 / 下 / 左 / 右 / 斜角
        }
        if (mListMode == LIST_LAYOUT || mListMode == LIST_LAYOUT_PICK) {
            return mLayoutFiltered.size();
        }
        if (mListMode == LIST_TPL) {
            return TPL_NAMES.length;
        }
        if (mListMode == LIST_FIX_TPL) {
            return FX_TPL_NAMES.length;
        }
        if (mListMode == LIST_FIX) {
            return mFixName.length;
        }
        return mListCount;
    }

    String listItemName(int pos) {
        String nm = listItemNameRaw(rawPos(pos));
        if (nm == null || nm.isEmpty()) {
            return nm;
        }
        // 组合键的延迟行 / 结束行：编号由自己管（或压根不编号），
        // 别再套一遍通用编号
        if (comboRowNoAutoIndex(pos)) {
            return nm;
        }
        //
        // 【同名项后面加 [1] [2] …】
        //   按钮列表里重名很常见（两个手柄 A、两个键盘 W），
        //   光看名字分不清是哪一行，只能靠位置猜。
        //   所有列表统一加：只有一个就不加，避免平白多一串后缀。
        //
        //   装饰后缀 {隐藏} 不参与比较，但要留在编号后面 ——
        //   否则"隐藏的那个 A"和"没隐藏的 A"会因为后缀不同而漏掉编号。
        String suffix = "";
        String base = nm;
        if (base.endsWith("{隐藏}")) {
            suffix = "{隐藏}";
            base = base.substring(0, base.length() - suffix.length());
        }
        int nth = sameNameIndex(pos, base);
        if (nth <= 0) {
            return nm;
        }
        return base + "[" + nth + "]" + suffix;
    }

    String listItemNameRaw(int pos) {
        if (mListMode == LIST_GRID) {
            return gridRowName(pos);
        }
        if (mListMode == LIST_RESET_DIM) {
            return (pos >= 0 && pos < RD_COUNT) ? RD_NAMES[pos] : "";
        }
        if (mListMode == LIST_SYNC_DIM) {
            return (pos >= 0 && pos < SD_COUNT) ? SD_NAMES[pos] : "";
        }
        if (mListMode == LIST_SYNC_DIR) {
            return (pos >= 0 && pos < 2) ? SDIR_NAMES[pos] : "";
        }
        if (mListMode == LIST_KEY) {
            return PadLayout.KEY_NAMES[pos];
        }
        if (mListMode == LIST_PAD) {
            if (pos < 0 || pos >= PadLayout.PAD_CANDIDATES.length) {
                return "";
            }
            return PadLayout.NAMES[PadLayout.PAD_CANDIDATES[pos]];
        }
        if (mListMode == LIST_CREATE) {
            return (pos >= 0 && pos < CREATE_NAMES.length) ? CREATE_NAMES[pos] : "";
        }
        if (mListMode == LIST_MOUSE) {
            return (pos >= 0 && pos < MOUSE_CANDIDATES.length)
                    ? PadLayout.mouseNameOf(MOUSE_CANDIDATES[pos]) : "";
        }
        if (mListMode == LIST_MORE) {
            String[] mn = moreRowNames();
            return (pos >= 0 && pos < mn.length) ? mn[pos] : "";
        }
        if (mListMode == LIST_APPEAR) {
            // 按键名那行显示当前状态，点一下才知道会切到哪边
            String an = (pos >= 0 && pos < APPEAR_NAMES.length)
                    ? APPEAR_NAMES[pos] : "";
            if ("按键名".equals(an)) {
                int sl = (mAppearSlot != NONE) ? mAppearSlot : mSel;
                return "按键名："
                        + ((sl != NONE && mLayout.labelOn(sl)) ? "显示" : "隐藏");
            }
            return an;
        }
        if (mListMode == LIST_PASS_COLOR) {
            if (pos < 0 || pos >= PASS_COLOR_NAMES.length) {
                return "";
            }
            /*
              【标出当前是开还是关】
                「透」只显示一种状态色 —— 改的是"关"那支、屏幕上却是"开"，
                改完什么都没变，看着就像颜色没生效。
                所以在行尾写明现在是哪一支，改之前就知道这一支看不看得见。
            */
            boolean on = FloatingService.keyWindowsEnabled(getContext());
            boolean thisIsOn = (pos == 0);
            return PASS_COLOR_NAMES[pos] + (thisIsOn == on ? "（当前）" : "（当前看不到）");
        }
        if (mListMode == LIST_BTN_COLOR) {
            return (pos >= 0 && pos < BTN_COLOR_NAMES.length)
                    ? BTN_COLOR_NAMES[pos] : "";
        }
        if (mListMode == LIST_COMBO_EDIT) {
            return comboRowName(pos);
        }
        if (mListMode == LIST_COMBO_TYPE) {
            return pos == 0 ? "手柄按键" : "键盘按键";
        }
        if (mListMode == LIST_COMBO_DIR) {
            return (pos >= 0 && pos < DIR_COUNT) ? DIR_NAMES[pos] : "";
        }
        if (mListMode == LIST_COMBO_STICK_MODE) {
            return (pos >= 0 && pos < 2) ? STICK_MODE_NAMES[pos] : "";
        }
        if (mListMode == LIST_COMBO_STICK_XY) {
            if (pos == 0) return "X 轴（左负 右正）";
            if (pos == 1) return "Y 轴（上负 下正）";
            return "";
        }
        if (mListMode == LIST_COMBO_TRIG) {
            return pos == 0 ? "按下力度（0 = 不扣，100 = 扣到底）" : "";
        }
        if (mListMode == LIST_COMBO_KIND) {
            return (pos >= 0 && pos < 2) ? COMBO_KIND_NAMES[pos] : "";
        }
        if (mListMode == LIST_COMBO_CROSS) {
            return comboCrossRowName(pos);
        }
        if (mListMode == LIST_COMBO_DELAY) {
            if (pos < 0 || pos >= PadLayout.DELAY_PRESETS.length) {
                return "";
            }
            int ms = PadLayout.DELAY_PRESETS[pos];
            // 当前已选的那一档标一下，进去就知道现在是多少
            boolean cur = mComboPickIdx != COMBO_PICK_NONE
                    && mLayout.comboActType(mComboEditIdx, mComboEditDir, mComboPickIdx)
                    == COMBO_ACT_DELAY
                    && mLayout.comboActCode(mComboEditIdx, mComboEditDir, mComboPickIdx) == ms;
            return ms + " 毫秒" + (cur ? "  ✓" : "");
        }
        if (mListMode == LIST_TPL) {
            return TPL_NAMES[pos];
        }
        if (mListMode == LIST_FIX_TPL) {
            return (pos >= 0 && pos < FX_TPL_NAMES.length) ? FX_TPL_NAMES[pos] : "";
        }
        if (mListMode == LIST_FIX) {
            return fixRowName(pos);
        }
        if (mListMode == LIST_LAYOUT || mListMode == LIST_LAYOUT_PICK) {
            if (pos < 0 || pos >= mLayoutFiltered.size()) {
                return "";
            }
            PadLayout.LayoutMeta m = mLayoutFiltered.get(pos);
            // 当前正在用的那个标注一下，不然列表一多分不清在哪
            String cur = m.id == mLayout.layoutId ? "（当前）" : "";
            return m.name + cur;
        }
        // 其余都是"按钮列表"：重置单个 / 互换位置 / 关联挪动 / 隐藏 /
        // 批量删除 / 选中按钮
        if (pos < 0 || pos >= mListCount) {
            return "";
        }
        int i = mListItems[pos];
        return elemListName(i);
    }

    boolean listNoOkButton() {
        // 穿透选支：只有两项，点一下就跳
        // 色板：点一档立刻生效（自定义那两项是点完就跳出去），
        //   摆个「确定」只会让人以为还要再确认一次 —— 而点它什么也不会发生。
        return mListMode == LIST_PASS_COLOR || mListMode == LIST_BTN_COLOR;
    }

    boolean listOkIsClose() {
        return mListBack != LIST_NONE
                && mListMode != LIST_COMBO_STICK_XY
                && mListMode != LIST_COMBO_TRIG
                && mListMode != LIST_BTN_COLOR;
    }

    int listSelCount() {
        int n = 0;
        for (int i = 0; i < PadLayout.N; i++) {
            if (mListSel[i]) {
                n++;
            }
        }
        return n;
    }

    boolean listUsesGenericFilter() {
        switch (mListMode) {
            case LIST_GRID:
            case LIST_RESET_DIM:
            case LIST_SYNC_DIM:
            case LIST_SYNC_DIR:
            case LIST_TPL:
            case LIST_FIX_TPL:
            case LIST_LAYOUT:
            case LIST_LAYOUT_PICK:
                return false;
            default:
                return true;
        }
    }

    boolean metaMatchesCat(PadLayout.LayoutMeta m) {
        switch (mListCat) {
            case CAT_BLANK:
                return m.tpl == PadLayout.TPL_BLANK;
            case CAT_PAD:
                return m.tpl == PadLayout.TPL_PAD;
            case CAT_KB:
                return m.tpl == PadLayout.TPL_KEYBOARD;
            default:
                return true;
        }
    }

    String[] moreNames() {
        // 【多选也支持摇杆设置】
        //   只要这批里混着摇杆就给这一行 —— 进去之后「固定 / 范围」
        //   一律刷给集合里**所有**摇杆，别的键（ABXY 之类）跳过不动。
        //   比"多选时不显示"实用：左右摇杆本来就该同步设成一样。
        //
        //   集合里一个摇杆都没有时才不显示（否则点进去是个空面板）。
        // 【顺序必须和 MORE_* 常量一致】
        //   pos 就是数组下标，加项时两处一起改，别按下标硬编码判断。
        //
        return selHasStick()
                ? new String[]{"形状", "摇杆设置"} : new String[]{"形状"};
    }

    void onCategoryChanged() {
        mListScroll = 0f;
        if (mListMode == LIST_LAYOUT || mListMode == LIST_LAYOUT_PICK) {
            applyLayoutFilter();
        } else {
            // 【不清空已勾选】
            //
            // 原来这里会把 mListSel 全清 —— 理由是"留着会作用到看不见的按钮上"。
            // 但那正是用户想要的：在「手柄」里勾几个，切到「键盘」再勾几个，
            // 点确定时**两批一起生效**。分类只是筛"现在看哪些"，
            // 不该顺手把之前的选择扔掉。
            //
            // 相应地，确定时也要扫全部已勾选项（不能只扫当前分类可见的），
            // 见 listSelCount() / 各 case 的执行逻辑：它们遍历的是
            // PadLayout.N 而不是 mListItems。
            buildListItems();
        }
        layoutList();
        invalidate();
    }

    void onTool(int tool) {
        // 网格配置面板开着时点「完成」：只收配置面板，不要直接退出编辑。
        // 否则调完网格一按完成就整个退出了，还得重新进编辑。
        if (mGridCfg && tool == T_DONE) {
            closeGridCfg();
            return;
        }
        switch (tool) {
            case T_DONE:
                mLayout.save(getContext());
                if (mFromApp) {
                    // 从 app 里进来编辑的，改完直接收起浮层回到设置页
                    mSink.onCollapse();
                } else {
                    // 走统一收尾，别再手工逐项清 ——
                    // 手工清漏过 mDlgMode，导致退出后穿透起不来。
                    exitEditMode();
                }
                break;
            case T_RESET:
                // 重置全部会把所有手动摆的位置清掉 —— 不可逆，先确认。
                mDlgMode = DLG_CONFIRM_RESET_ALL;
                mDlgTitle = "重置全部？所有按钮都会回到默认位置，手动摆放的会全部丢失。";
                openDlg();
                break;
            case T_RESET_ONE:
                openList(LIST_RESET_DIM);
                break;
            case T_SWAP:
                openList(LIST_SWAP);
                break;
            case T_LINK:
                openList(LIST_LINK);
                break;
            case T_TOOLS:
                openList(LIST_TOOL);
                break;
            default:
                break;
        }
    }

    void onToolListItem(int item) {
        switch (item) {
            case TL_RESET:
                // 同上：先确认再重置
                mDlgMode = DLG_CONFIRM_RESET_ALL;
                mDlgTitle = "重置全部？所有按钮都会回到默认位置，手动摆放的会全部丢失。";
                openDlg();
                break;
            case TL_RESET_ONE:
                openList(LIST_RESET_DIM);
                break;
            case TL_SWAP:
                openList(LIST_SWAP);
                break;
            case TL_LINK:
                openList(LIST_LINK);
                break;
            case TL_HIDE:
                openList(LIST_HIDE);
                break;
            case TL_REVERT:
                revertToLegacyPos();
                closeList();
                break;
            case TL_KEY:
                // 再弹一层：从键盘表里挑一个键名
                openList(LIST_KEY);
                break;
            case TL_CREATE:
                // 先选类型（手柄 / 键盘），再进对应的候选表
                // 从常用工具进来的，返回就回常用工具
                openListBack(LIST_CREATE, LIST_TOOL);
                break;
            case TL_DELETE:
                // 删掉的键没法撤销，先确认
                if (mSel >= 0 && mSel < PadLayout.N) {
                    mDlgMode = DLG_CONFIRM_DEL_KEY;
                    mDlgTitle = "删除「" + mLayout.nameOf(mSel) + "」？删掉就找不回来了。";
                    mDlgTargetId = mSel;
                    openDlg();
                }
                break;
            case TL_BATCH_DEL:
                openBatchDelete();
                break;
            case TL_LAYOUT:
                openList(LIST_LAYOUT);
                break;
            case TL_SELECT:
                openList(LIST_SELECT);
                break;
            case TL_PAD:
                openList(LIST_PAD);
                break;
            case TL_MULTI_ADJ:
                openList(LIST_MULTI_ADJ);
                break;
            case TL_MULTI_SHAPE:
                openList(LIST_MULTI_SHAPE);
                break;
            case TL_GRID:
                openGridCfg();
                break;
            case TL_FIXED:
                openFixedCfg();
                break;
            default:
                break;
        }
    }

    void openBatchDelete() {
        // 【不能只数键盘按键】
        //   手柄元素现在也能删，只建过手柄按钮的话
        //   keySlotCount() 是 0，会误报"尚未创建按钮"、列表根本打不开。
        if (deletableCount() == 0) {
            toastLocal("当前没有可删除的按钮");
            closeList();
            invalidate();
            return;
        }
        openList(LIST_DEL_KEY);
    }

    void openComboEditList(int idx) {
        mComboEditIdx = idx;
        mComboPickIdx = COMBO_PICK_NONE;
        // 进列表时把基准设成"现在的自动名"：
        // 当前名字正好等于它 -> 说明还没手动改过，之后加键可以继续自动更新；
        // 不等于（用户改过名）-> 之后加键就不会覆盖。
        mComboAutoPrev = mLayout.isComboUsed(idx) ? mLayout.comboAutoName(idx, mComboEditDir) : null;
        // 【返回目标是「更多选项」，不是「常用工具」】
        //   进来这条路是 编辑面板 → 更多选项 → 编辑组合键，
        //   写 LIST_TOOL 的话返回会跳到"常用工具"列表 ——
        //   那儿压根没有这一项，看着像回到了不相干的界面。
        //   LIST_MORE 自己的上级是 LIST_NONE，所以再点一次返回就关列表回面板，
        //   整条链是通的：组合键编辑 → 更多选项 → 面板。
        mComboInSub = false;
        mComboEditDir = 0;
        if (mLayout.comboCross[idx]) {
            // 十字架：先到"名字 / 四个方向 / 斜角"那一层
            openListBack(LIST_COMBO_CROSS, LIST_MORE);
        } else {
            openListBack(LIST_COMBO_EDIT, LIST_MORE);
        }
    }

    void openDlg() {
        layoutDlg();
        invalidate();
    }

    void openList(int mode) {
        mListMode = mode;
        // 顶层入口：链从头开始，旧栈留着会让返回跳到不相干的列表
        mListBackStack.clear();
        // 【默认没有上级】有上级的由调用方在 openList 之后单独设 mListBack。
        //   放在这里重置，是为了让"从工具列表 / 面板直接进入"的列表
        //   一律是顶层，不会带着上一次的返回关系进来。
        mListBack = LIST_NONE;
        for (int i = 0; i < PadLayout.N; i++) {
            if (mode == LIST_LINK) {
                mListSel[i] = mSelSet[i];
            } else if (mode == LIST_HIDE) {
                mListSel[i] = mLayout.hidden[i];
            } else {
                mListSel[i] = false;
            }
        }
        for (int k = 0; k < SWAP_MAX; k++) {
            mSwapPick[k] = NONE;
        }
        if (mode == LIST_ADJ_ANCHOR && mAdjAnchor != NONE) {
            // 预选当前中心，进来就能看到"现在是谁"，也方便改回它
            mListSel[mAdjAnchor] = true;
            mSwapPick[0] = mAdjAnchor;
        }
        mListScroll = 0f;
        mListDragging = false;
        // 分类每次开列表都回到「全部」。
        // 留着上一次的话，进别的列表时筛选条件还在，
        // 界面上却没有"你正在筛选"的提示，很容易以为按钮丢了。
        mListCat = CAT_ALL;
        mListCatOpen = false;
        if (mode == LIST_TPL) {
            for (int i = 0; i < PadLayout.N; i++) {
                mListSel[i] = false;
            }
            mListSel[mPendingTpl] = true;
        }
        if (mode == LIST_LAYOUT || mode == LIST_LAYOUT_PICK) {
            // 开列表时抓一次快照：改名 / 删除先在快照上改，
            // 点「确定」才落盘。中途取消就不会留下半截状态。
            reloadLayoutMetas();
        }
        // 【开列表就清掉上一次的搜索词】
        //   留着的话：在键盘列表搜了"F1"，退出再开手柄列表，
        //   列表是空的、还亮着一个"搜索:F1"的蓝按钮，看着像坏了。
        mListQuery = "";
        mListFilterCount = 0;
        buildListItems();
        buildListFilter();
        layoutList();
        invalidate();
    }

    void openListBack(int mode, int back) {
        // 进子列表前把**当前层自己的上级**压栈，
        // 退回来时还给它（见 mListBackStack 的注释）。
        //
        // 【必须先把栈快照下来】
        //   openList() 里有 mListBackStack.clear()（给"顶层入口"用的），
        //   而 openListBack / listGoBack 走的也是它 ——
        //   于是每深入一层，前面压的就被清掉了，栈里永远只剩 1 条。
        //
        //   表现：只有倒数第一次返回是对的，再往上退一层就拿到 NONE，
        //   那一层被当成顶层列表 —— LIST_COMBO_EDIT（比如十字架的"上"）
        //   不在"不画确定"那组里，于是底栏冒出「确定(先选)」，
        //   而它点了什么也不会发生（这个列表没有勾选语义）。
        int cur = mListBack;
        java.util.ArrayList<Integer> saved =
                new java.util.ArrayList<Integer>(mListBackStack);
        openList(mode);
        mListBackStack.clear();
        mListBackStack.addAll(saved);
        mListBackStack.add(cur);      // 追加（不是覆盖），链才连得起来
        mListBack = back;
        layoutList();
    }

    void openSyncPick(int row) {
        if (row < 0 || row >= mLayoutFiltered.size()) {
            return;
        }
        // 【内置布局也允许同步】
        //   横竖屏各摆一次是刚需，内置布局（默认手柄 / 默认键盘 / 悬浮窗）
        //   也一样。之前这里拦了 builtin，但删 / 改名有 toast 提示、
        //   界面上也能看出区别，同步拦了就只剩"点上没反应"。
        //   悬浮窗布局里现在有 G 和提示牌，不再是空的，同步过去也说得通。
        mSyncLayoutId = mLayoutFiltered.get(row).id;
        for (int i = 0; i < PadLayout.N; i++) {
            mSyncSel[i] = false;
        }
        openList(LIST_SYNC_DIR);
    }

    void performListTap(float x, float y) {
        // 「连续创建」放在最前面判定：它在标题行右端，
        // 和列表项、取消按钮都不重叠，先判谁都一样；
        // 但放在这里能保证以后万一调整布局也不会被别的分支吃掉。
        if ((mListMode == LIST_KEY || mListMode == LIST_PAD)
                && mKeepOpenRect.contains(x, y)) {
            mKeyKeepOpen = !mKeyKeepOpen;
            invalidate();
            return;
        }
        // 【分类浮层的判定必须排在确定 / 取消之前】
        //
        // 之前放在它们后面，于是：某个分类下没有按钮时列表为空、面板被压得很矮，
        // 浮层展开就盖到了确定 / 取消上 —— 看着点在分类项上，
        // 实际先命中了底下的按钮（按钮判定在前），于是"点分类变成点了确定"。
        //
        // 视觉上浮层是最上层，判定顺序就得和视觉层级一致，否则就是点了 A 生效 B。
        if (listHasCategory()) {
            if (mListCatOpen) {
                int n = catCount();
                for (int i = 0; i < n; i++) {
                    if (mListCatItemRects[i].contains(x, y)) {
                        mListCat = i;
                        mListCatOpen = false;
                        onCategoryChanged();
                        return;
                    }
                }
                // 展开状态下点按钮本身（显示 ^）= 只收起，不改分类
                if (mListCatRect.contains(x, y)) {
                    mListCatOpen = false;
                    invalidate();
                    return;
                }
                // 点浮层外：先收起。
                mListCatOpen = false;
                invalidate();
                // 底部按钮例外 —— 浮层空间不足时会压到它们上面，
                // 这时用户明显是想点按钮，收起的同时让按钮生效一次。
                // 其余区域（列表项）只收起，不穿透，避免误选。
                if (!mListOkRect.contains(x, y) && !mListCancelRect.contains(x, y)) {
                    return;
                }
            } else if (mListCatRect.contains(x, y)) {
                mListCatOpen = true;
                invalidate();
                return;
            }
        }
        if (mListOkRect.contains(x, y)) {
            // 【有上级时右边那颗是「完成 / 关闭」，不是「确定」】
            //   这一排列表（按键创建 / 候选表）没有勾选语义，
            //   listConfirmEnabled() 对它们恒为 false，
            //   走通用逻辑的话按钮会一直是灰的、点了没反应。
            if (listOkIsClose()) {
                // 矩形已经置空，正常点不到。留着是防御：万一哪条路径
                // 没重排、矩形还带着旧坐标，行为也得和以前一致（整个关掉）。
                closeList();
                invalidate();
                return;
            }
            if (listConfirmEnabled()) {
                confirmList();
            }
            return;
        }
        if (mListCancelRect.contains(x, y)) {
            // 【有上级就退回上级，没有才关掉整个列表】
            //   多层列表（常用工具 → 按键创建 → 候选表）
            //   以前一律 closeList()，直接掉回画布，前面走的路全白费。
            if (mListBack != LIST_NONE) {
                listGoBack();
                invalidate();
                return;
            }
            closeList();
            invalidate();
            return;
        }
        if (mListMode == LIST_TOOL) {
            int tl = hitToolListItem(x, y);
            if (tl != NONE) {
                onToolListItem(tl);
            }
            return;
        }
        if (mListMode == LIST_GRID) {
            // 先判 − / +：它们压在行矩形右侧，整行判定会抢先吃掉
            for (int i = 0; i < GI_COUNT; i++) {
                if (hitListSub(mGridMinusRects[i], x, y)) {
                    gridStep(i, -1);
                    return;
                }
                if (hitListSub(mGridPlusRects[i], x, y)) {
                    gridStep(i, +1);
                    return;
                }
            }
            int gi = hitListItem(x, y);
            if (gi != NONE) {
                gridToggle(gi);
            }
            return;
        }
        if (mListMode == LIST_COMBO_STICK_XY) {
            // X / Y 行右侧的 − / +：先判小按钮，整行判定会抢先吃掉
            for (int i = 0; i < 2; i++) {
                if (hitListSub(mGridMinusRects[i], x, y)) {
                    stickStep(i, -5);
                    return;
                }
                if (hitListSub(mGridPlusRects[i], x, y)) {
                    stickStep(i, +5);
                    return;
                }
            }
            return;
        }
        if (mListMode == LIST_COMBO_TRIG) {
            // 整块都是力度条，−/+ 已去掉：这里不处理，交给 hitListAdjust
            return;
        }
        if (listHasSelAll() && mListSelAllRect.contains(x, y)) {
            toggleSelectAllInCat();
            return;
        }
        if (mListMode == LIST_LAYOUT && mListCreateRect.contains(x, y)) {
            // 新建：先选模板，选完直接建好并切过去
            mPendingTpl = PadLayout.TPL_PAD;
            openList(LIST_TPL);
            return;
        }
        if (mListMode == LIST_LAYOUT) {
            if (!mListImportRect.isEmpty() && mListImportRect.contains(x, y)) {
                askImportLayout();
                return;
            }
        }
        if (mListMode == LIST_LAYOUT) {
            // 先判右侧几个小按钮：它们压在行矩形之上
            for (int i = 0; i < mLayoutFiltered.size(); i++) {
                if (hitListSub(mLayoutShareRects[i], x, y)) {
                    shareLayoutAt(i);
                    return;
                }
                if (hitListSub(mLayoutDelRects[i], x, y)) {
                    deleteLayoutAt(i);
                    return;
                }
                if (hitListSub(mLayoutRenameRects[i], x, y)) {
                    renameLayoutAt(i);
                    return;
                }
                if (hitListSub(mLayoutSyncRects[i], x, y)) {
                    openSyncPick(i);
                    return;
                }
            }
        }
        // 组合键编辑列表：键行右侧的「删」压在行矩形之上，
        // 必须先判，否则整行判定会把它吃掉（和布局列表「改名/删」同理）。
        if (mListMode == LIST_COMBO_EDIT) {
            // 「结束」在「删」左边，两个都压在整行矩形之上，必须先判
            for (int i = 0; i < mComboEndRects.length; i++) {
                if (hitListSub(mComboEndRects[i], x, y)) {
                    comboToggleEndAfter(i);
                    return;
                }
            }
            for (int i = 0; i < mComboDelRects.length; i++) {
                if (hitListSub(mComboDelRects[i], x, y)) {
                    comboDelAct(i);
                    return;
                }
            }
        }
        // 「搜索」在标题栏上，压不到行矩形，但要在 hitListItem 之前判
        if (!mListSearchRect.isEmpty() && mListSearchRect.contains(x, y)) {
            askSearchList();
            return;
        }
        // 功能键页：先判那一排胶囊。它们压在行矩形右边，
        // 整行判定会抢先把它们吃掉（和布局列表「改名/删」同理）。
        if (mListMode == LIST_FIX && mFixUiMode) {
            int n = listItemCount();
            for (int i = 0; i < n && i < mFixTplRects.length; i++) {
                for (int k = 0; k < FX_TPL_SHORT.length; k++) {
                    if (hitListSub(mFixTplRects[i][k], x, y)) {
                        fixUiCycle(i, k);
                        return;
                    }
                }
            }
        }
        int item = hitListItem(x, y);
        if (item != NONE) {
            if (mListMode == LIST_LAYOUT || mListMode == LIST_LAYOUT_PICK) {
                // 点名字区 = 切过去
                PadLayout.LayoutMeta m = item < mLayoutFiltered.size()
                        ? mLayoutFiltered.get(item) : null;
                if (m != null) {
                    switchLayout(m.id);
                }
                return;
            }
            if (mListMode == LIST_TPL) {
                mPendingTpl = item;
                toggleListItem(item);   // 给个选中态，让「确定」可用
                return;
            }
            if (mListMode == LIST_FIX_TPL) {
                buildFixRows(item);
                openListBack(LIST_FIX, LIST_FIX_TPL);
                return;
            }
            if (mListMode == LIST_FIX && mFixUiMode) {
                return;   // 名字区不可点，要改状态点右边的胶囊
            }
            if (mListMode == LIST_FIX) {
                int code = (item >= 0 && item < mFixCode.length)
                        ? mFixCode[item] : FX_ACT_ADD;
                if (code == FX_ACT_ADD) {
                    // 直接复用「按键创建」那个界面：五种都能建，
                    // 在这儿拆成五行会把列表底部撑得老长。
                    // mFixPicking 让后面的 createXxx 只记名单、不真建。
                    mFixPicking = true;
                    openListBack(LIST_CREATE, LIST_FIX);
                    return;
                }
                fixCycle(mFixTpl, code);
                // 加/删行会改变行数，重建一次
                buildFixRows(mFixTpl);
                layoutList();
                invalidate();
                return;
            }
            toggleListItem(item);
        }
    }

    boolean pickable(int i) {
        if (mLayout == null) return false;
        // 没创建的键盘槽位等于不存在 —— 编辑模式也不能选中它，
        // 否则会选中一个看不见、画不出、也删不掉的东西。
        if (PadLayout.isKeySlot(i) && !mLayout.isKeySlotUsed(i)) return false;
        // 被删掉的手柄元素槽位 = 不存在，和空键盘槽位同理
        // 同样要排除界面按钮，理由见 shouldDraw()
        if (PadLayout.isPadSlot(i) && !PadLayout.isUiButton(i)
                && !mLayout.isPadUsed(i)) return false;
        // 没建的空白按钮槽位 = 不存在，同上
        if (PadLayout.isBlankSlot(i) && !mLayout.isBlankUsed(i)) return false;
        // 没建的组合键槽位 = 不存在，同上
        if (PadLayout.isComboSlot(i) && !mLayout.isComboUsed(i)) return false;
        // 鼠标三件套：只在鼠标布局里启用（padType 由 resetMouse 设上）
        if (PadLayout.isMouseSlot(i) && !mLayout.isMouseUsed(i)) return false;
        // 【G 和「收」各自只在自己那个布局里可点】
        //   和 shouldDraw() 同一套判据：不画就不能点，
        //   否则会选中一个看不见的东西，拖了也不知道在拖谁。
        if (i == PadLayout.I_FLOAT && !isFloatLayout()) return false;
        if (i == PadLayout.I_COLLAPSE && isFloatLayout()) return false;
        return mEditMode || !mLayout.hidden[i];
    }

    public void refreshLayoutList() {
        reloadLayoutMetas();
        if (mListMode == LIST_LAYOUT || mListMode == LIST_LAYOUT_PICK) {
            layoutList();
        }
        invalidate();
    }

    void reloadLayoutMetas() {
        mLayoutMetas = PadLayout.loadMetas(getContext());
        applyLayoutFilter();
    }

    void renameComboNow() {
        if (mComboEditIdx == NONE || !mLayout.isComboUsed(mComboEditIdx)) {
            return;
        }
        mDlgMode = DLG_CONFIRM_COMBO_RENAME;
        mDlgTitle = "改名要跳到本应用界面，游戏会切到后台（可能被系统杀掉），继续？";
        mDlgTargetId = mComboEditIdx;
        openDlg();
    }

    public void renameComboSlot(int slot, String name) {
        if (name == null || name.isEmpty()) {
            return;
        }
        if (!PadLayout.isComboSlot(slot) || !mLayout.isComboUsed(slot)) {
            return;
        }
        mLayout.comboName[slot] = name;
        // 用户手动改了名：断掉自动更新的链条，之后加键不再动这个名字
        mComboAutoPrev = null;
        mLayout.save(getContext());
        invalidate();
    }

    public void renameKeyName(int elem, String name) {
        if (mLayout == null || elem < 0 || elem >= PadLayout.N) {
            return;
        }
        mLayout.customName[elem] = (name == null) ? "" : name.trim();
        mLayout.save(getContext());
        invalidate();
    }

    void renameKeyNow() {
        if (mSel == NONE) {
            toastLocal("先点一个按键");
            return;
        }
        mDlgMode = DLG_CONFIRM_KEY_RENAME;
        mDlgTitle = "改名要跳到本应用界面，游戏会切到后台（可能被系统杀掉），继续？";
        mDlgTargetId = mSel;
        openDlg();
    }

    void renameLayoutAt(int pos) {
        if (pos < 0 || pos >= mLayoutFiltered.size()) {
            return;
        }
        PadLayout.LayoutMeta m = mLayoutFiltered.get(pos);
        if (m.builtin) {
            toastLocal("内置布局不能改名");
            return;
        }
        // 改名要跳到 app 界面，游戏会切到后台，先确认一次
        mDlgMode = DLG_CONFIRM_RENAME;
        mDlgTitle = "改名要跳到本应用界面，游戏会切到后台（可能被系统杀掉），继续？";
        mDlgTargetId = m.id;
        openDlg();
    }

    void renameLayoutNow(int id) {
        PadLayout.LayoutMeta m = PadLayout.findMeta(getContext(), id);
        if (m == null || mSink == null) {
            return;
        }
        mSink.requestRenameLayout(m.id, m.name);
    }

    void resetSelectedNow() {
        for (int i = 0; i < PadLayout.N; i++) {
            if (mListSel[i]) {
                resetOne(i, mResetDims);
            }
        }
        closeList();
        invalidate();
    }

    int selCount() {
        int n = 0;
        for (int i = 0; i < PadLayout.N; i++) {
            if (mSelSet[i]) {
                n++;
            }
        }
        return n;
    }

    int selFirst() {
        for (int i = 0; i < PadLayout.N; i++) {
            if (mSelSet[i]) {
                return i;
            }
        }
        return NONE;
    }

    boolean selHasStick() {
        if (mLayout == null) {
            return false;
        }
        if (isMulti()) {
            for (int i = 0; i < PadLayout.N; i++) {
                if (mSelSet[i] && isStickElem(i)) {
                    return true;
                }
            }
            return false;
        }
        return mSel != NONE && isStickElem(mSel);
    }

    void selectButtonFromList(int pos) {
        if (pos < 0 || pos >= mListCount) {
            return;
        }
        int i = mListItems[pos];
        if (mLayout != null && mLayout.hidden[i]) {
            mLayout.hidden[i] = false;
            mLayout.save(getContext());
        }
        closeList();
        mSel = i;
        if (!mEditMode) {
            setEditMode(true, false);
        }
        invalidate();
        toastLocal("已选中：" + (mLayout != null ? mLayout.nameOf(i) : ""));
    }

    void selectSingle(int i) {
        for (int k = 0; k < PadLayout.N; k++) {
            mSelSet[k] = (k == i);
        }
        mSel = i;
        mAdjAnchor = i;
        mAdjLayoutMode = false;
        mAdjK = 1f;
    }

    void shareLayoutAt(int pos) {
        if (pos < 0 || pos >= mLayoutFiltered.size()) {
            return;
        }
        PadLayout.LayoutMeta m = mLayoutFiltered.get(pos);
        String json = PadLayout.exportLayoutJson(getContext(), m.id);
        if (json == null) {
            toastLocal("这个布局还没有摆位存档，先打开摆一次再导出");
            return;
        }
        String stamp = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss",
                Locale.getDefault()).format(new Date());
        // 文件名里去掉路径分隔符，避免布局名带斜杠时写到奇怪的位置
        String safeName = m.name.replaceAll("[/\\\\:*?\"<>|]", "_");
        String fileName = safeName + "_" + stamp + ".json";
        String relPath = "com.example.vgamepad/" + fileName;

        try {
            Uri uri = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 走 MediaStore：不用申请存储权限就能写公共 Download
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                cv.put(MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/com.example.vgamepad");
                cv.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                uri = getContext().getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri != null) {
                    OutputStream os =
                            getContext().getContentResolver().openOutputStream(uri);
                    if (os != null) {
                        os.write(json.getBytes("UTF-8"));
                        os.close();
                    }
                }
            } else {
                // Android 9 及以下：直接写公共目录，需要 WRITE_EXTERNAL_STORAGE，
                // 没授权就退到 app 私有目录（至少文件还在，只是不好找）
                File dir = new File(
                        Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS),
                        "com.example.vgamepad");
                if (!dir.exists() && !dir.mkdirs()) {
                    dir = getContext().getExternalFilesDir(
                            Environment.DIRECTORY_DOWNLOADS);
                }
                if (dir != null) {
                    File f = new File(dir, fileName);
                    FileOutputStream fos = new FileOutputStream(f);
                    fos.write(json.getBytes("UTF-8"));
                    fos.close();
                }
            }
            toastLocal("已导出到 下载/" + relPath);
        } catch (Exception e) {
            toastLocal("导出失败：" + e.getMessage());
        }
    }

    boolean showDeleteItem() {
        if (mSel < 0 || mSel >= PadLayout.N || mLayout == null) {
            return false;
        }
        if (PadLayout.isKeySlot(mSel)) {
            return mLayout.isKeySlotUsed(mSel);
        }
        // 【空白 / 组合键同理】
        //   它们 padType 恒 0，走下面那句必然 false ——
        //   于是选中它们时「删除」这一项压根不显示，删不掉。
        if (PadLayout.isBlankSlot(mSel)) {
            return mLayout.isBlankUsed(mSel);
        }
        // 没建的组合键槽位 = 不存在，同上
        if (PadLayout.isComboSlot(mSel)) {
            return mLayout.isComboUsed(mSel);
        }
        // 鼠标元素：建出来之后就能删（触摸板 / 左键 / 中键 / 滚轮都算）
        if (PadLayout.isMouseSlot(mSel)) {
            return mLayout.isMouseUsed(mSel);
        }
        return mLayout.isPadUsed(mSel) && !PadLayout.isUiButton(mSel);
    }

    void switchLayout(int layoutId) {
        if (mLayout == null || layoutId == mLayout.layoutId) {
            closeList();
            invalidate();
            return;
        }
        // 先存：否则当前布局没保存的拖动全丢
        mLayout.save(getContext());
        closeList();
        mSel = NONE;
                    mRayElem = NONE;
        releaseAll();
        mLayout = PadLayout.load(getContext(), mW, mH, mPortrait, layoutId,
                NO_BOTTOM_LIMIT, mTopGuard);
        // 记住这次切到哪了：收起再打开还是它
        PadLayout.saveCurrentLayoutId(getContext(), mLayout.layoutId);
        // 和切换模式时同一套收尾：清选中 / 清联动 / 重算几何。
        // 少一步 computeGeometry 的话 mPx/mPy 还是上一个布局的坐标，
        // 新布局的键会画在旧位置上。
        clearSelection();
        computeGeometry();
        invalidate();
        PadLayout.LayoutMeta m = PadLayout.findMeta(getContext(), layoutId);
        toastLocal(m != null ? ("已切换到「" + m.name + "」") : "已切换布局");
    }

    void syncComboAutoName() {
        //
        // 【十字架不自动改名】
        //   四条序列各自有键，拼出来的名字会很长而且没意义；
        //   十字架的名字是整体名字（显示在下方），只由用户自己定。
        if (mComboEditIdx != NONE && mLayout != null
                && mLayout.comboCross[mComboEditIdx]) {
            return;
        }
        if (mComboEditIdx == NONE || !mLayout.isComboUsed(mComboEditIdx)) {
            return;
        }
        String cur = mLayout.comboName[mComboEditIdx];
        String auto = mLayout.comboAutoName(mComboEditIdx, mComboEditDir);
        //
        // 【判据是"当前名字 == 上一次我们写进去的自动名"，不是"== 现在算出的自动名"】
        //
        //   本函数是在**改完动作之后**调用的，所以此刻算出的 auto 已经包含
        //   刚加的那个键。拿它和 cur 比必然不相等：
        //     加 A      -> cur = "组合键"，写进去 "A"
        //     再加 B    -> cur = "A"，此刻 auto = "A+B"，两者不等 -> 判成"手动改过"
        //   —— 于是从第二个键开始名字就再也不更新了。
        //
        //   正确的基准是 mComboAutoPrev：上一次我们**自己写进去**的那个自动名。
        //   cur 还等于它，说明中间没人手动改过，可以放心覆盖。
        boolean untouched = cur == null || cur.isEmpty()
                || cur.equals("组合键")
                || (mComboAutoPrev != null && cur.equals(mComboAutoPrev));
        if (untouched) {
            mLayout.comboName[mComboEditIdx] = auto;
            mComboAutoPrev = auto;
        }
        mLayout.save(getContext());
        invalidate();
    }

    void syncFloatBallPx() {
        if (mLayout == null) return;
        mPx[PadLayout.I_FLOAT] = mPx[PadLayout.I_COLLAPSE];
        mPy[PadLayout.I_FLOAT] = mPy[PadLayout.I_COLLAPSE];
        mLayout.rx[PadLayout.I_FLOAT] = mLayout.rx[PadLayout.I_COLLAPSE];
        mLayout.ry[PadLayout.I_FLOAT] = mLayout.ry[PadLayout.I_COLLAPSE];
    }

    void toggleListItem(int pos) {
        if (mListMode == LIST_KEY) {
            createKey(pos);
            return;
        }
        if (mListMode == LIST_PAD) {
            createPad(pos);
            return;
        }
        if (mListMode == LIST_MOUSE) {
            createMouse(pos);
            return;
        }
        if (mListMode == LIST_CREATE) {
            // 只分流，不创建：选完进对应的候选表，在那儿才是"点一个就建"
            if (pos == CT_BLANK) {
                createBlank();
                return;
            }
            if (pos == CT_COMBO) {
                // 先问做成"按钮"还是"十字架"。
                // 固定显示也问 —— 形状借高位存得进名单（FX_COMBO_CROSS_BASE），
                // 建出来就是选的那个形状，不是假选项。
                openListBack(LIST_COMBO_KIND, LIST_CREATE);
                return;
            }
            if (pos == CT_MOUSE) {
                // 鼠标元素有自己的候选表：六个固定种类，别混进手柄按键表
                openListBack(LIST_MOUSE, LIST_CREATE);
                return;
            }
            // 候选表是从「按键创建」进来的，返回退回按键创建
            openListBack(pos == CT_PAD ? LIST_PAD : LIST_KEY, LIST_CREATE);
            return;
        }
        if (mListMode == LIST_COMBO_TYPE) {
            if (mComboPickIdx == COMBO_PICK_NONE) {
                return;
            }
            openListBack(pos == 0 ? LIST_PAD : LIST_KEY, LIST_COMBO_TYPE);
            return;
        }
        if (mListMode == LIST_COMBO_EDIT) {
            comboEditClick(pos);
            return;
        }
        if (mListMode == LIST_COMBO_CROSS) {
            if (mComboEditIdx == NONE || !mLayout.isComboUsed(mComboEditIdx)) {
                return;
            }
            if (pos == 0) {
                renameComboNow();
                return;
            }
            if (pos >= 1 && pos <= 4) {
                openCrossDir(pos - 1);
                return;
            }
            if (pos == 5) {
                // 斜角是开关，点了立刻生效并存盘
                mLayout.comboDiag[mComboEditIdx] = !mLayout.comboDiag[mComboEditIdx];
                mLayout.save(getContext());
                layoutList();
                invalidate();
            }
            return;
        }
        if (mListMode == LIST_COMBO_KIND) {
            if (pos >= 0 && pos <= 1) {
                createCombo(pos == 1);
            }
            return;
        }
        if (mListMode == LIST_COMBO_STICK_MODE) {
            if (mComboDirSlot == NONE || pos < 0 || pos > 1) {
                return;
            }
            if (pos == 0) {
                openListBack(LIST_COMBO_DIR, LIST_COMBO_EDIT);
            } else {
                openListBack(LIST_COMBO_STICK_XY, LIST_COMBO_EDIT);
            }
            return;
        }
        if (mListMode == LIST_COMBO_DIR) {
            // 单选语义：点了写入方向并退回编辑列表
            if (mComboDirSlot == NONE || mComboDirProto == NONE
                    || pos < 0 || pos >= DIR_COUNT) {
                return;
            }
            int t = (mComboDirProto == PadLayout.I_DPAD)
                    ? COMBO_ACT_HAT_DIR : COMBO_ACT_STICK_DIR;
            mLayout.setComboAct(mComboEditIdx, mComboEditDir, mComboDirSlot, t,
                    mComboDirProto, pos);
            mComboDirSlot = NONE;
            mComboDirProto = NONE;
            syncComboAutoName();
            mLayout.save(getContext());
            // 连续添加时继续留在候选表，和挑普通键一致
            if (mKeyKeepOpen) {
                int next = comboFirstFreeSlot();
                if (next >= 0) {
                    mComboPickIdx = next;
                    openList(LIST_PAD);
                    return;
                }
            }
            openComboEditBack();
            return;
        }
        if (mListMode == LIST_COMBO_DELAY) {
            // 单选语义：点一下写入并退回编辑列表
            if (mComboPickIdx == COMBO_PICK_NONE
                    || pos < 0 || pos >= PadLayout.DELAY_PRESETS.length) {
                return;
            }
            mLayout.setComboAct(mComboEditIdx, mComboEditDir, mComboPickIdx, COMBO_ACT_DELAY,
                    PadLayout.DELAY_PRESETS[pos]);
            mComboPickIdx = COMBO_PICK_NONE;
            syncComboAutoName();
            mLayout.save(getContext());
            openComboEditBack();
            return;
        }
        if (mListMode == LIST_MORE) {
            String[] mn = moreRowNames();
            if (pos < 0 || pos >= mn.length) {
                return;
            }
            //
            // 【按名字匹配，不用写死的下标】
            //   行表会随选中对象变（摇杆多一行、组合键多一行），
            //   下标是浮动的。用 MORE_COMBO(=2) 这种常量去比，
            //   只在"正好选中了摇杆"时才碰巧对得上。
            String name = mn[pos];
            if (mSel == NONE && !isMulti()) {
                toastLocal("先点一个按键");
                invalidate();
                return;
            }
            if ("摇杆设置".equals(name) && selHasStick()) {
                mShapeMode = false;
                mStickCfg = !mStickCfg;
                // 同上：摇杆设置的滑条也在面板上
                if (mStickCfg) {
                    closeList();
                }
            } else if ("编辑组合键".equals(name)) {
                mComboInSub = false;
                mComboEditDir = 0;
                openComboEditList(mSel);
            } else if (name != null && name.startsWith("优先级")) {
                // 【必须用 startsWith，不能用 equals】
                //   这行的名字是带当前值的 —— moreRowNames() 里拼成
                //   "优先级：50"，而这里写死 .equals("优先级")，
                //   选中了按键就永远匹配不上，if-else 链一路落到末尾，
                //   什么都不做、只是 invalidate —— 表现就是"点了没反应"。
                //   行尾带值是为了进去之前就能看到现在是多少，值得留着，
                //   所以改判定而不是改行名。其余三行（外观 / 摇杆设置 /
                //   编辑组合键）不带后缀，equals 没问题。
                //
                // 【走面板子模式，不再弹列表】
                //   形状 / 摇杆设置都是原地换面板内容，只有优先级弹一层列表
                //   还要再点「确定」，同一个软件两种调节方式看着像两套东西。
                //   面板模式下拖完即生效并存档，和别的滑条一致。
                openPrioPanel();
                return;
            } else if ("外观".equals(name)) {
                // 记下槽位：子列表里三项都要改"当前这个键"，
                // 而打开色板之类会切走 mSel，不先存就改错对象。
                mAppearSlot = mSel;
                openListBack(LIST_APPEAR, LIST_MORE);
                return;
            }
            invalidate();
            return;
        }
        if (mListMode == LIST_APPEAR) {
            if (pos < 0 || pos >= APPEAR_NAMES.length) {
                return;
            }
            // 用进子列表时记下的槽位，不用 mSel ——
            // 中间可能已经切走过。
            if (mAppearSlot != NONE) {
                mSel = mAppearSlot;
                if (!mSelSet[mSel]) {
                    for (int i = 0; i < PadLayout.N; i++) {
                        mSelSet[i] = false;
                    }
                    mSelSet[mSel] = true;
                }
            }
            if (mSel == NONE && !isMulti()) {
                toastLocal("先点一个按键");
                invalidate();
                return;
            }
            String an = APPEAR_NAMES[pos];
            if ("形状".equals(an)) {
                // 形状子模式：和摇杆设置互斥，进一个先退另一个
                mStickCfg = false;
                mShapeMode = !mShapeMode;
                // 【开了子模式必须关列表】
                //   形状 / 摇杆设置的滑条画在**面板**上（不是列表里），
                //   列表浮在上面就把滑条全盖住了 ——
                //   于是"点了形状什么也没发生"，其实是界面没让开。
                if (mShapeMode) {
                    closeList();
                }
                invalidate();
                return;
            }
            if ("改名".equals(an)) {
                closeList();
                renameKeyNow();
                return;
            }
            if ("改背景颜色".equals(an)) {
                closeList();
                // 【按"选中里有没有透"判，不按 mSel == I_PASS】
                //   多选时 mSel 是第一个选中的下标，未必是透 ——
                //   于是多选里带了个「透」，却走了普通取色，
                //   写进 btnColor[I_PASS]，而那支在绘制时是被忽略的
                //   （透有两支状态色）→ 表现就是"改了没用"。
                if (mSelSet[PadLayout.I_PASS]) {
                    openList(LIST_PASS_COLOR);
                } else {
                    mColorPassState = 0;
                    openList(LIST_BTN_COLOR);
                }
                invalidate();
                return;
            }
            // 按键名：整批一起切，切完退出面板让人看看效果
            for (int i = 0; i < PadLayout.N; i++) {
                if (mSelSet[i]) {
                    mLayout.showLabel[i] = !mLayout.showLabel[i];
                }
            }
            mLayout.save(getContext());
            closeList();
            invalidate();
            return;
        }
        if (mListMode == LIST_PASS_COLOR) {
            if (pos < 0 || pos >= PASS_COLOR_NAMES.length) {
                return;
            }
            mColorPassState = pos + 1;
            openListBack(LIST_BTN_COLOR, LIST_PASS_COLOR);
            invalidate();
            return;
        }
        if (mListMode == LIST_BTN_COLOR) {
            if (pos < 0 || pos >= BTN_COLOR_NAMES.length) {
                return;
            }
            int col = BTN_COLOR_VALUES[pos];
            if (col == -1 || col == -2) {
                // 两种方式都要离开游戏（悬浮窗里弹不出输入法 / 开不了 WebView），
                // 先确认一次再跳。web = true 走网页调色盘，false 走手输。
                askCustomColor(col == -2);
                return;
            }
            fillColorSel();          // 点预设色这条路径也要填，否则写不进去
            applyPickedColor(col);
            closeList();
            invalidate();
            return;
        }
        if (mListMode == LIST_SELECT) {
            // 单选语义：点了立刻选中，不是勾再确定
            selectButtonFromList(pos);
            return;
        }
        if (mListMode == LIST_TPL) {
            // 单选：模板只能选一个
            mPendingTpl = pos;
            for (int i = 0; i < PadLayout.N; i++) {
                mListSel[i] = false;
            }
            mListSel[pos] = true;
            invalidate();
            return;
        }
        if (mListMode == LIST_ADJ_ANCHOR) {
            // 单选：中心只能有一个。用 mSwapPick[0] 存，
            // 和「互换位置」共用同一套单选槽位，不多开一个字段。
            int i = mListItems[pos];
            for (int k = 0; k < PadLayout.N; k++) {
                mListSel[k] = false;
            }
            mSwapPick[0] = i;
            mListSel[i] = true;
            invalidate();
            return;
        }
        int i = mListItems[pos];
        if (mListMode == LIST_SWAP) {
            int at = -1;
            for (int k = 0; k < SWAP_MAX; k++) {
                if (mSwapPick[k] == i) {
                    at = k;
                    break;
                }
            }
            if (at >= 0) {
                mSwapPick[at] = NONE;
                mListSel[i] = false;
            } else {
                int free = -1;
                for (int k = 0; k < SWAP_MAX; k++) {
                    if (mSwapPick[k] == NONE) {
                        free = k;
                        break;
                    }
                }
                if (free < 0) {
                    // 已经选满了：挤掉第一个，腾位置给新的
                    mListSel[mSwapPick[0]] = false;
                    mSwapPick[0] = mSwapPick[1];
                    mSwapPick[1] = i;
                } else {
                    mSwapPick[free] = i;
                }
                mListSel[i] = true;
            }
        } else {
            mListSel[i] = !mListSel[i];
        }
        invalidate();
    }

    void toggleSelectAllInCat() {
        boolean allOn = mListCount > 0;
        for (int i = 0; i < mListCount; i++) {
            if (!mListSel[mListItems[i]]) {
                allOn = false;
                break;
            }
        }
        for (int i = 0; i < mListCount; i++) {
            mListSel[mListItems[i]] = !allOn;
        }
        invalidate();
    }

    int toolItemCat(int item) {
        if (mListMode == LIST_TOOL && mListCat != TCAT_ALL) {
            return mListCat;
        }
        return TL_CAT[item];
    }

    boolean toolItemVisible(int item) {
        if (item == TL_REVERT) return showRevertItem();
        if (item == TL_DELETE) return showDeleteItem();
        // 常用工具列表的分类筛选
        if (mListMode == LIST_TOOL && mListCat != TCAT_ALL
                && TL_CAT[item] != mListCat) {
            return false;
        }
        // 悬浮窗布局：只留对"一颗球"有意义的几项
        if (mListMode == LIST_TOOL && isFloatLayout() && !isFloatTool(item)) {
            return false;
        }
        return true;
    }

    int visibleToolCount() {
        // 【必须按 TL_ORDER 数，不能按 TL_COUNT】
        //   TL_COUNT 是 TL_* 常量的总数（17），里面包含 TL_KEY / TL_PAD
        //   这两个"只在 TL_TEXT 里占位、不出现在工具列表"的项。
        //   按 TL_COUNT 数会多算出 2 行，内容高度凭空多两行 ——
        //   列表下方拖出一大片空白。
        int n = 0;
        for (int k = 0; k < TL_ORDER.length; k++) {
            if (toolItemVisible(TL_ORDER[k])) {
                n++;
            }
        }
        return n;
    }

    int visibleToolGroupCount() {
        int n = 0;
        int last = -1;
        for (int k = 0; k < TL_ORDER.length; k++) {
            int i = TL_ORDER[k];
            if (!toolItemVisible(i)) {
                continue;
            }
            int cat = toolItemCat(i);
            if (cat != last) {
                n++;
                last = cat;
            }
        }
        return n;
    }

    // ===== 通用工具（两边都用）=====
    static void setA(Paint p, float a) {
        p.setAlpha(Math.round(255 * a));
    }
    static void resetA(Paint p) {
        p.setAlpha(255);
    }
    float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
