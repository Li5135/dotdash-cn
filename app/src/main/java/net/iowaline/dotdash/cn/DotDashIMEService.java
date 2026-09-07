package net.iowaline.dotdash.cn;

import android.inputmethodservice.InputMethodService;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 点点划键盘（DotDash CN）核心输入法服务。
 *
 * 交互模型：
 *  - 主键位仅有点(·)、划(-)、退格；新增分号(；)键作为"强制分割键"：
 *    每输入完一个字母的点划序列后按分号，立即翻译成字母上屏（英文模式）
 *    或追加进拼音缓冲区（中文模式）。不存在任何"超时自动提交"逻辑。
 *  - 中/英切换键保留。
 *  - 中文模式：拼音缓冲 -> Trie/DP 自动切分 -> 顶部候选条出候选词；
 *    按空格上屏首个候选并清空缓冲；点按候选条可任选候选。
 *  - 符号系统完全脱离摩斯：符号一律通过"符"键展开的多页符号面板直接上屏。
 */
public class DotDashIMEService extends InputMethodService implements
        KeyboardView.OnKeyboardActionListener {

    // ------------------------------------------------------------------
    // 键码定义（与 res/xml/dotdash.xml、res/xml/symbols_page*.xml 保持一致）
    // ------------------------------------------------------------------
    /** 点键 */
    public static final int KEYCODE_DOT = 0;
    /** 划键 */
    public static final int KEYCODE_DASH = 1;
    /** 空格 */
    public static final int KEYCODE_SPACE = 32;
    /** 退格 */
    public static final int KEYCODE_DEL = 67;
    /** 分号：强制分割当前点划序列（显示为全角"；"） */
    public static final int CODE_SEMICOLON = -200;
    /** 中/英 模式切换 */
    public static final int CODE_LANG = -201;
    /** 打开符号面板 */
    public static final int CODE_SYMBOLS = -202;
    // 符号面板内部功能键（tools/gen_symbol_pages.py 同步生成）
    public static final int CODE_SYM_BACK = -203;   // 返回主键盘
    public static final int CODE_SYM_PREV = -204;   // 上一页
    public static final int CODE_SYM_NEXT = -205;   // 下一页

    // ------------------------------------------------------------------
    // 状态常量
    // ------------------------------------------------------------------
    public static final int MODE_PINYIN = 0;
    public static final int MODE_LATIN = 1;
    /** 单个点划序列最大长度 */
    private static final int MAX_MORSE_LEN = 8;

    private static final String[] SYMBOL_PAGE_XML = {
            "symbols_page0",
            "symbols_page1",
            "symbols_page2",
    };

    // ------------------------------------------------------------------
    // 界面
    // ------------------------------------------------------------------
    private KeyboardView inputView;
    private DotDashKeyboardView keyboardOverlay;
    private Keyboard dotDashKeyboard;
    private final List<Keyboard> symbolKeyboards = new ArrayList<>();
    private int symbolPage = 0;

    private Keyboard.Key spaceKey;
    private int spaceKeyIndex = -1;
    private Keyboard.Key langKey;
    private int langKeyIndex = -1;

    private CandidateBarView candidateView;
    /** 候选栏显示状态（避免每次按键反复触发布局切换） */
    private boolean candidatesShownState = false;

    // ------------------------------------------------------------------
    // 输入状态
    // ------------------------------------------------------------------
    private int mode = MODE_PINYIN;
    /** 当前正在敲的点划序列（如 ".-"） */
    private final StringBuilder charInProgress = new StringBuilder(MAX_MORSE_LEN);
    /** 中文模式的拼音缓冲 */
    private final StringBuilder pinyin = new StringBuilder();
    private final Map<String, String> morseMap = new HashMap<>();
    private final PinyinEngine engine = new PinyinEngine();

    @Override
    public void onCreate() {
        super.onCreate();

        // 读取"默认输入模式"偏好（中文/英文）
        android.content.SharedPreferences prefs =
                android.preference.PreferenceManager.getDefaultSharedPreferences(this);
        String defaultMode = prefs.getString(DotDashPrefs.DEFAULT_MODE, "0");
        mode = "1".equals(defaultMode) ? MODE_LATIN : MODE_PINYIN;

        buildMorseMap();

        // 后台装载词典（assets 内嵌文本，量小，很快）
        Thread loader = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    InputStream syl = getAssets().open("dict/syllables.txt");
                    InputStream dict = getAssets().open("dict/pinyin.txt");
                    engine.load(syl, dict);
                } catch (IOException e) {
                    // 词典缺失/损坏时引擎保持空，中文候选自然为空，不影响其它功能
                    try {
                        engine.load(new java.io.ByteArrayInputStream(new byte[0]),
                                new java.io.ByteArrayInputStream(new byte[0]));
                    } catch (IOException ignored) {
                        // 不可能发生（空流）
                    }
                }
            }
        }, "pinyin-dict-loader");
        loader.setDaemon(true);
        loader.start();
    }

    // ------------------------------------------------------------------
    // 键盘初始化
    // ------------------------------------------------------------------
    @Override
    public void onInitializeInterface() {
        dotDashKeyboard = new Keyboard(this, R.xml.dotdash);
        symbolKeyboards.clear();
        for (String xml : SYMBOL_PAGE_XML) {
            int resId = getResources().getIdentifier(xml, "xml", getPackageName());
            symbolKeyboards.add(new Keyboard(this, resId));
        }

        spaceKey = null;
        langKey = null;
        List<Keyboard.Key> keys = dotDashKeyboard.getKeys();
        for (int i = 0; i < keys.size(); i++) {
            int code = keys.get(i).codes[0];
            if (code == KEYCODE_SPACE) {
                spaceKey = keys.get(i);
                spaceKeyIndex = i;
            } else if (code == CODE_LANG) {
                langKey = keys.get(i);
                langKeyIndex = i;
            }
        }
    }

    @Override
    public View onCreateInputView() {
        View root = getLayoutInflater().inflate(R.layout.input, null);
        inputView = root.findViewById(R.id.keyboard);
        inputView.setOnKeyboardActionListener(this);
        // 自绘层（视觉）与底层 KeyboardView（触摸/业务）绑定
        keyboardOverlay = root.findViewById(R.id.keyboard_overlay);
        keyboardOverlay.setHost(inputView);
        bindKeyboard(dotDashKeyboard);
        refreshKeyLabels(true);
        return root;
    }

    /** 切换当前键盘：底层 KeyboardView 与自绘层同步（仅 UI 联动，业务不变） */
    private void bindKeyboard(Keyboard kbd) {
        inputView.setKeyboard(kbd);
        if (keyboardOverlay != null) {
            keyboardOverlay.setKeyboard(kbd);
        }
    }

    @Override
    public View onCreateCandidatesView() {
        // 直接构建自绘候选栏：内容渲染/点击/滚动均在 CandidateBarView 内部完成，
        // 每次数据更新都会 invalidate 强制重绘，避免系统候选区 TextView 布局不刷新的问题。
        candidateView = new CandidateBarView(this);
        candidateView.setOnCandidateClickListener(new CandidateBarView.OnCandidateClickListener() {
            @Override
            public void onCandidateClick(int index) {
                commitCandidate(index);
            }
        });
        // 视图就绪后立即按当前输入状态刷新一次
        updateCandidates();
        return candidateView;
    }

    // ------------------------------------------------------------------
    // 按键分发
    // ------------------------------------------------------------------
    @Override
    public void onKey(int primaryCode, int[] keyCodes) {
        if (isOnSymbolKeyboard()) {
            onSymbolKey(primaryCode);
            return;
        }
        switch (primaryCode) {
            case KEYCODE_DOT:
            case KEYCODE_DASH:
                onMorseSignal(primaryCode == KEYCODE_DASH);
                break;
            case CODE_SEMICOLON:
                onSemicolon();
                break;
            case KEYCODE_SPACE:
                onSpace();
                break;
            case KEYCODE_DEL:
                onDelete();
                break;
            case CODE_LANG:
                toggleMode();
                break;
            case CODE_SYMBOLS:
                openSymbolPanel();
                break;
        }
    }

    // ------------------------------------------------------------------
    // 主键盘逻辑
    // ------------------------------------------------------------------

    /** 点 / 划被按下：追加进当前序列（达到上限后忽略新信号） */
    private void onMorseSignal(boolean isDash) {
        if (charInProgress.length() < MAX_MORSE_LEN) {
            charInProgress.append(isDash ? '-' : '.');
            refreshSpaceLabel(true);
        }
    }

    /**
     * 分号键 = 强制分割键：
     *  1) 有正在敲的点划序列 -> 立即翻译成字母；
     *  2) 没有序列 -> 输出普通分号字符（中文模式全角；英文模式半角）。
     */
    private void onSemicolon() {
        if (charInProgress.length() > 0) {
            commitMorseLetter();
        } else if (mode == MODE_PINYIN) {
            commitText("\uFF1B"); // 全角 ；
        } else {
            commitText(";");
        }
    }

    /**
     * 把当前点划序列翻译成字母并提交到合适的位置：
     *  英文模式直接上屏；中文模式追加进拼音缓冲并刷新候选。
     */
    private void commitMorseLetter() {
        String code = charInProgress.toString();
        charInProgress.setLength(0);
        refreshSpaceLabel(true);

        String letter = morseMap.get(code);
        if (letter == null) {
            // 无效码组：直接丢弃（序列已清空），保持静默
            return;
        }
        if (mode == MODE_LATIN) {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                ic.commitText(letter, 1);
            }
            return;
        }
        // 中文模式：数字直接上屏（不进拼音缓冲）
        char c0 = letter.charAt(0);
        if (c0 >= '0' && c0 <= '9') {
            commitText(letter);
            return;
        }
        // 拼音缓冲已满：先自动上屏当前首选候选，为新字母腾出空间
        if (pinyin.length() >= PinyinEngine.MAX_PINYIN_LEN) {
            PinyinEngine.Result r = engine.getLastResult();
            if (!r.candidates.isEmpty()) {
                commitCandidate(0);
            }
        }
        if (pinyin.length() < PinyinEngine.MAX_PINYIN_LEN) {
            pinyin.append(letter);
        }
        updateCandidates();
    }

    /** 空格：中文模式且有缓冲时上屏首个候选并清空；否则输出空格 */
    private void onSpace() {
        if (mode == MODE_PINYIN && pinyin.length() > 0) {
            PinyinEngine.Result r = engine.getLastResult();
            if (r.candidates.isEmpty()) {
                // 缓冲无法切分/无候选：放弃当前缓冲（避免残留污染后续输入），输出空格
                clearPinyin();
                commitText(" ");
                return;
            }
            commitCandidate(0);
        } else {
            commitText(" ");
        }
    }

    /** 退格：优先退点划序列，其次退拼音缓冲，最后交给系统删除 */
    private void onDelete() {
        if (charInProgress.length() > 0) {
            charInProgress.deleteCharAt(charInProgress.length() - 1);
            refreshSpaceLabel(true);
        } else if (mode == MODE_PINYIN && pinyin.length() > 0) {
            pinyin.deleteCharAt(pinyin.length() - 1);
            updateCandidates();
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
        }
    }

    /** 中/英 切换 */
    private void toggleMode() {
        if (mode == MODE_PINYIN) {
            // 离开中文模式前：若还有可上屏的候选，先自动上屏，避免丢字
            PinyinEngine.Result r = engine.getLastResult();
            if (pinyin.length() > 0 && !r.candidates.isEmpty()) {
                commitCandidate(0);
            } else {
                clearPinyin();
            }
            mode = MODE_LATIN;
        } else {
            mode = MODE_PINYIN;
        }
        updateCandidates();
        refreshKeyLabels(true);
    }

    // ------------------------------------------------------------------
    // 符号面板
    // ------------------------------------------------------------------
    private boolean isOnSymbolKeyboard() {
        return symbolKeyboards.contains(inputView != null ? inputView.getKeyboard() : null);
    }

    private void openSymbolPanel() {
        symbolPage = 0;
        bindKeyboard(symbolKeyboards.get(0));
        updateCandidates();
    }

    private void onSymbolKey(int code) {
        if (code == CODE_SYM_BACK) {
            bindKeyboard(dotDashKeyboard);
            updateCandidates();
        } else if (code == CODE_SYM_PREV) {
            symbolPage = (symbolPage - 1 + symbolKeyboards.size()) % symbolKeyboards.size();
            bindKeyboard(symbolKeyboards.get(symbolPage));
        } else if (code == CODE_SYM_NEXT) {
            symbolPage = (symbolPage + 1) % symbolKeyboards.size();
            bindKeyboard(symbolKeyboards.get(symbolPage));
        } else if (code > 0) {
            // 普通字符/标点：直接上屏
            commitCodePoint(code);
        }
    }

    private void commitCodePoint(int codePoint) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            return;
        }
        String s;
        if (codePoint < 0x10000) {
            s = String.valueOf((char) codePoint);
        } else {
            s = new String(Character.toChars(codePoint));
        }
        ic.commitText(s, 1);
    }

    // ------------------------------------------------------------------
    // 候选条
    // ------------------------------------------------------------------
    private void updateCandidates() {
        // 让引擎与当前缓冲同步（保证 getLastResult 有效）
        PinyinEngine.Result r = engine.update(pinyin.toString());

        boolean pinyinMode = mode == MODE_PINYIN && !isOnSymbolKeyboard();
        setCandidatesShownState(pinyinMode);
        if (candidateView == null || !pinyinMode) {
            return;
        }

        // 缓冲为空：展示操作引导（候选栏不再空白）
        if (pinyin.length() == 0) {
            candidateView.showHint(getString(R.string.hint_morse_input));
            return;
        }
        // 缓冲有拼音：展示切分结果 + 候选词条（首候选高亮）
        if (r.candidates.isEmpty()) {
            candidateView.showNoCandidate(r.display, getString(R.string.no_candidate));
        } else {
            candidateView.showCandidates(r.display, r.candidates);
        }
    }

    /** 只在显示状态变化时才开关候选栏，避免每次按键都触发布局重排 */
    private void setCandidatesShownState(boolean shown) {
        if (candidatesShownState == shown) {
            return;
        }
        candidatesShownState = shown;
        setCandidatesViewShown(shown);
    }

    private void commitCandidate(int index) {
        PinyinEngine.Result r = engine.getLastResult();
        if (index < 0 || index >= r.candidates.size()) {
            return;
        }
        commitText(r.candidates.get(index));
        clearPinyin();
    }

    private void clearPinyin() {
        pinyin.setLength(0);
        charInProgress.setLength(0);
        refreshSpaceLabel(true);
        updateCandidates();
    }

    private void commitText(String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.commitText(text, 1);
        }
    }

    // ------------------------------------------------------------------
    // 键帽标签刷新
    // ------------------------------------------------------------------
    /** 刷新中英键与空格键标签 */
    private void refreshKeyLabels(boolean refreshScreen) {
        if (langKey != null) {
            langKey.label = mode == MODE_PINYIN ? getString(R.string.mode_label_pinyin)
                    : getString(R.string.mode_label_latin);
        }
        refreshSpaceLabel(refreshScreen);
        if (refreshScreen && inputView != null) {
            if (langKeyIndex >= 0) {
                safeInvalidate(langKeyIndex);
            }
        }
    }

    /** 空格键标签 = 正在输入的点划序列（空则显示"空格"） */
    private void refreshSpaceLabel(boolean refreshScreen) {
        if (spaceKey == null) {
            return;
        }
        String label;
        if (charInProgress.length() == 0) {
            label = getString(R.string.space_key);
        } else {
            String seq = charInProgress.toString();
            label = seq.length() == 1 ? " " + seq + " " : seq;
        }
        if (!label.equals(spaceKey.label)) {
            spaceKey.label = label;
            if (refreshScreen) {
                safeInvalidate(spaceKeyIndex);
            }
        }
    }

    private void safeInvalidate(int index) {
        try {
            inputView.invalidateKey(index);
        } catch (Exception ignored) {
            // 视图未就绪时忽略
        }
        if (keyboardOverlay != null) {
            // 键帽标签（空格/中英）变化后自绘层需重绘
            keyboardOverlay.invalidate();
        }
    }

    // ------------------------------------------------------------------
    // InputMethodService 生命周期
    // ------------------------------------------------------------------
    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        clearPinyin();
        if (inputView != null) {
            bindKeyboard(dotDashKeyboard);
        }
        refreshKeyLabels(true);
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        clearPinyin();
        if (inputView != null) {
            bindKeyboard(dotDashKeyboard);
        }
    }

    @Override
    public void onFinishInput() {
        super.onFinishInput();
        clearPinyin();
    }

    // 下面这些回调按接口要求实现，均为空实现（无滑动、无长按扩展）
    @Override
    public void onPress(int primaryCode) {
    }

    @Override
    public void onRelease(int primaryCode) {
    }

    @Override
    public void onText(CharSequence text) {
    }

    @Override
    public void swipeLeft() {
    }

    @Override
    public void swipeRight() {
    }

    @Override
    public void swipeDown() {
    }

    @Override
    public void swipeUp() {
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------
    private void buildMorseMap() {
        // 只保留字母与数字的标准摩斯码。标点与其它符号完全交给符号面板，
        // 与"符号系统完全脱离摩斯"的设计一致。
        morseMap.put(".-", "a");
        morseMap.put("-...", "b");
        morseMap.put("-.-.", "c");
        morseMap.put("-..", "d");
        morseMap.put(".", "e");
        morseMap.put("..-.", "f");
        morseMap.put("--.", "g");
        morseMap.put("....", "h");
        morseMap.put("..", "i");
        morseMap.put(".---", "j");
        morseMap.put("-.-", "k");
        morseMap.put(".-..", "l");
        morseMap.put("--", "m");
        morseMap.put("-.", "n");
        morseMap.put("---", "o");
        morseMap.put(".--.", "p");
        morseMap.put("--.-", "q");
        morseMap.put(".-.", "r");
        morseMap.put("...", "s");
        morseMap.put("-", "t");
        morseMap.put("..-", "u");
        morseMap.put("...-", "v");
        morseMap.put(".--", "w");
        morseMap.put("-..-", "x");
        morseMap.put("-.--", "y");
        morseMap.put("--..", "z");
        morseMap.put(".----", "1");
        morseMap.put("..---", "2");
        morseMap.put("...--", "3");
        morseMap.put("....-", "4");
        morseMap.put(".....", "5");
        morseMap.put("-....", "6");
        morseMap.put("--...", "7");
        morseMap.put("---..", "8");
        morseMap.put("----.", "9");
        morseMap.put("-----", "0");
    }
}
