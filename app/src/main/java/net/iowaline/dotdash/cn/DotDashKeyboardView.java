package net.iowaline.dotdash.cn;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 深空电台主题的键盘绘制层。
 *
 * 由于 {@link KeyboardView#onDraw} 是 final 且不提供按键级样式钩子，
 * 这里采用"双层"结构（见 res/layout/input.xml）：
 *   - 底层：透明 KeyboardView —— 保留全部触摸/长按/回调等业务机制（原样不动）；
 *   - 本层：普通 View 叠在上方，负责自绘键帽（深空电台主题）并把触摸事件
 *     原样转发给底层 KeyboardView。
 *
 * 本类只做视觉与事件转发，不包含任何输入业务逻辑。
 */
public class DotDashKeyboardView extends View {

    // ---- 键码（与 DotDashIMEService / 布局一致）----
    private static final int KEYCODE_DOT = DotDashIMEService.KEYCODE_DOT;
    private static final int KEYCODE_DASH = DotDashIMEService.KEYCODE_DASH;
    private static final int CODE_SEMICOLON = DotDashIMEService.CODE_SEMICOLON;
    private static final int KEYCODE_DEL = DotDashIMEService.KEYCODE_DEL;
    /** 符号面板页码占位键（无操作） */
    private static final int CODE_SYM_PAGE = -206;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float cornerKey;   // 普通键圆角 16dp
    private float cornerBig;   // 点/划大键圆角 20dp
    private float glowRadius;  // 点/划按压微光半径

    private KeyboardView host;       // 底层 KeyboardView（事件转发 + 尺寸来源）
    private Keyboard keyboard;       // 当前要绘制的键盘
    private Keyboard.Key trackedKey; // 视觉按压跟踪（不触碰 host 状态）

    @SuppressWarnings("deprecation")
    private int colorRes(int resId) {
        return getResources().getColor(resId);
    }

    public DotDashKeyboardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setup();
    }

    public DotDashKeyboardView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        setup();
    }

    private void setup() {
        float density = getResources().getDisplayMetrics().density;
        cornerKey = 16 * density;
        cornerBig = 20 * density;
        glowRadius = 18 * density;
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(Math.max(1.5f * density, 1f));
        textPaint.setTextAlign(Paint.Align.CENTER);
        // 微光（shadow）依赖软件层渲染，保证各版本一致
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    /** 绑定底层 KeyboardView（转发触摸、对齐尺寸） */
    public void setHost(KeyboardView hostView) {
        this.host = hostView;
        requestLayout();
    }

    /** 绑定要绘制的键盘（与底层 KeyboardView 使用同一 Keyboard 实例） */
    public void setKeyboard(Keyboard kbd) {
        this.keyboard = kbd;
        trackedKey = null;
        invalidate();
    }

    // ------------------------------------------------------------------
    // 尺寸：跟随底层 KeyboardView（键盘高度由其行高决定）
    // ------------------------------------------------------------------
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (host != null && host.getMeasuredHeight() > 0) {
            int w = host.getMeasuredWidth() > 0
                    ? host.getMeasuredWidth()
                    : resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec);
            setMeasuredDimension(w, host.getMeasuredHeight());
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    // ------------------------------------------------------------------
    // 触摸：视觉跟踪 + 原样转发给底层 KeyboardView（业务不变）
    // ------------------------------------------------------------------
    @Override
    public boolean onTouchEvent(MotionEvent me) {
        int action = me.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                trackedKey = hitTest((int) me.getX(), (int) me.getY());
                invalidate();
                break;
            case MotionEvent.ACTION_MOVE:
                if (trackedKey != null
                        && !trackedKey.isInside((int) me.getX(), (int) me.getY())) {
                    // 滑出按键视为取消按压
                    trackedKey = null;
                    invalidate();
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_UP:
                if (trackedKey != null) {
                    trackedKey = null;
                    invalidate();
                }
                break;
            default:
                break;
        }
        // 业务触摸全部交由底层 KeyboardView 原逻辑处理
        return host != null ? host.dispatchTouchEvent(me) : false;
    }

    private Keyboard.Key hitTest(int x, int y) {
        if (keyboard == null) {
            return null;
        }
        for (Keyboard.Key k : keyboard.getKeys()) {
            if (k.isInside(x, y)) {
                return k;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 自绘键帽
    // ------------------------------------------------------------------
    @Override
    protected void onDraw(Canvas canvas) {
        if (keyboard == null) {
            return;
        }
        for (Keyboard.Key key : keyboard.getKeys()) {
            drawKey(canvas, key, key == trackedKey);
        }
    }

    private void drawKey(Canvas canvas, Keyboard.Key key, boolean pressed) {
        RectF r = new RectF(key.x, key.y, key.x + key.width, key.y + key.height);
        if (r.width() <= 0 || r.height() <= 0) {
            return;
        }
        int code = key.codes[0];

        if (code == KEYCODE_DOT || code == KEYCODE_DASH) {
            drawAmberKey(canvas, r, key, pressed);
        } else if (code == CODE_SEMICOLON) {
            drawSemicolonKey(canvas, r, key, pressed);
        } else if (code == CODE_SYM_PAGE) {
            drawNormalKey(canvas, r, key, pressed, colorRes(R.color.text_secondary));
        } else {
            int textColor = code == KEYCODE_DEL
                    ? colorRes(R.color.delete_red)
                    : colorRes(R.color.text_primary);
            drawNormalKey(canvas, r, key, pressed, textColor);
        }
    }

    /** 点/划大键：琥珀渐变 + 按压微光，文字深色 */
    private void drawAmberKey(Canvas canvas, RectF r, Keyboard.Key key, boolean pressed) {
        int start = colorRes(pressed ? R.color.amber_pressed_start : R.color.amber_start);
        int end = colorRes(pressed ? R.color.amber_pressed_end : R.color.amber_end);
        bgPaint.setShader(new LinearGradient(
                r.left, r.top, r.left, r.bottom, start, end, Shader.TileMode.CLAMP));
        bgPaint.setShadowLayer(pressed ? glowRadius : 0f, 0f, 0f,
                colorRes(R.color.amber_glow));
        canvas.drawRoundRect(r, cornerBig, cornerBig, bgPaint);
        bgPaint.setShader(null);
        bgPaint.setShadowLayer(0f, 0f, 0f, 0);

        drawLabel(canvas, r, key.label, colorRes(R.color.text_on_amber), 0.48f);
    }

    /** 分号提交键：深色底 + 青色描边 + 青色文字 */
    private void drawSemicolonKey(Canvas canvas, RectF r, Keyboard.Key key, boolean pressed) {
        bgPaint.setShader(null);
        bgPaint.setShadowLayer(0f, 0f, 0f, 0);
        bgPaint.setColor(colorRes(pressed ? R.color.semi_bg_pressed : R.color.semi_bg));
        canvas.drawRoundRect(r, cornerKey, cornerKey, bgPaint);

        strokePaint.setColor(colorRes(R.color.semi_accent));
        canvas.drawRoundRect(r, cornerKey, cornerKey, strokePaint);

        drawLabel(canvas, r, key.label, colorRes(R.color.semi_accent), 0.46f);
    }

    /** 普通键：深色底（按压提亮），文字颜色按 key 给定 */
    private void drawNormalKey(Canvas canvas, RectF r, Keyboard.Key key,
                               boolean pressed, int textColor) {
        bgPaint.setShader(null);
        bgPaint.setShadowLayer(0f, 0f, 0f, 0);
        bgPaint.setColor(colorRes(pressed ? R.color.key_pressed : R.color.key_normal));
        canvas.drawRoundRect(r, cornerKey, cornerKey, bgPaint);
        drawLabel(canvas, r, key.label, textColor, 0.42f);
    }

    private void drawLabel(Canvas canvas, RectF r, CharSequence label, int color, float sizeRatio) {
        if (label == null || label.length() == 0) {
            return;
        }
        textPaint.setColor(color);
        textPaint.setTextSize(r.height() * sizeRatio);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baseline = r.centerY() - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(label.toString(), r.centerX(), baseline, textPaint);
    }
}
