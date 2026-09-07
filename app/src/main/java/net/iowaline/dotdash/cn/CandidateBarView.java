package net.iowaline.dotdash.cn;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 候选词栏（自绘）。
 *
 * 直接以 Canvas 绘制候选词条并自行处理点击/横向滚动，
 * 不依赖系统候选区的 TextView 布局刷新，确保每次数据变化后
 * invalidate 即强制重绘（解决"候选区空白不刷新"问题）。
 *
 * 视觉（深空电台主题）：
 *  - 容器底色 #0F131B + 边框 #1D2430；
 *  - 拼音/提示文字 次要色 #8B93A3；
 *  - 首个候选：琥珀渐变 #FFB020→#F57C00 + 深色文字，高亮突出；
 *  - 其余候选：半透明深色底 + 细边框 + 主色文字 #E9EDF2。
 *
 * 本类只负责展示与命中，不含任何输入业务逻辑。
 */
public class CandidateBarView extends View {

    /** 候选被点击（index 对应 setCandidates 传入的候选下标，0 起） */
    public interface OnCandidateClickListener {
        void onCandidateClick(int index);
    }

    private static final int ITEM_HEIGHT_DP = 34;
    private static final int ITEM_GAP_DP = 6;
    private static final int ITEM_PAD_H_DP = 16;
    private static final int CONTENT_PAD_DP = 6;
    private static final int BAR_HEIGHT_DP = 48;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float density;
    private final int barHeight;
    private final int itemHeight;
    private final int itemGap;
    private final int itemPadH;
    private final int contentPad;

    private OnCandidateClickListener listener;

    // ---- 当前展示数据 ----
    private String hintText;            // 无候选引导文案（如：点划输入，按；提交）
    private String displayText;         // 拼音提示（灰）
    private List<String> candidates = Collections.emptyList();
    private String emptyNote;           // 缓冲非空但无匹配时的附注（灰）

    // ---- 滚动状态 ----
    private float scrollX = 0f;         // 当前横向偏移（<=0）
    private float maxScrollX = 0f;      // 内容超出宽度时的最大负偏移
    private float downX = 0f;
    private float downScrollX = 0f;
    private float touchSlop;
    private boolean trackingScroll = false;

    @SuppressWarnings("deprecation")
    private int colorRes(int resId) {
        return getResources().getColor(resId);
    }

    public CandidateBarView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        barHeight = Math.round(BAR_HEIGHT_DP * density);
        itemHeight = Math.round(ITEM_HEIGHT_DP * density);
        itemGap = Math.round(ITEM_GAP_DP * density);
        itemPadH = Math.round(ITEM_PAD_H_DP * density);
        contentPad = Math.round(CONTENT_PAD_DP * density);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        textPaint.setTextAlign(Paint.Align.CENTER);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(Math.max(1f * density, 1f));
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    public void setOnCandidateClickListener(OnCandidateClickListener l) {
        this.listener = l;
    }

    // ------------------------------------------------------------------
    // 数据入口：任何输入缓冲变化后调用，立即重绘
    // ------------------------------------------------------------------

    /** 无输入/无候选：显示引导文案 */
    public void showHint(String text) {
        this.hintText = text;
        this.displayText = null;
        this.candidates = Collections.emptyList();
        this.emptyNote = null;
        resetScroll();
        invalidate();
    }

    /** 有拼音缓冲但无候选词条 */
    public void showNoCandidate(String display, String note) {
        this.hintText = null;
        this.displayText = display;
        this.candidates = Collections.emptyList();
        this.emptyNote = note;
        resetScroll();
        invalidate();
    }

    /** 显示拼音 + 候选词条（首候选高亮） */
    public void showCandidates(String display, List<String> cands) {
        this.hintText = null;
        this.displayText = display;
        this.candidates = cands == null ? Collections.<String>emptyList() : cands;
        this.emptyNote = null;
        resetScroll();
        invalidate();
    }

    private void resetScroll() {
        scrollX = 0f;
        maxScrollX = 0f;
    }

    // ------------------------------------------------------------------
    // 测量：候选栏固定高度
    // ------------------------------------------------------------------
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec);
        setMeasuredDimension(w, barHeight);
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------
    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();

        // 容器：底色 + 边框
        bgPaint.setShader(null);
        bgPaint.setColor(colorRes(R.color.panel_bg));
        canvas.drawRect(0, 0, w, h, bgPaint);
        strokePaint.setColor(colorRes(R.color.cand_border));
        RectF frame = new RectF(0.5f, 0.5f, w - 0.5f, h - 0.5f);
        canvas.drawRect(frame, strokePaint);

        List<Item> items = buildItems();
        if (items.isEmpty()) {
            return;
        }
        // 内容总宽，用于横向滚动
        int totalW = 0;
        for (Item it : items) {
            totalW += it.width;
            totalW += itemGap;
        }
        totalW -= itemGap; // 去掉末尾 gap
        totalW += contentPad;

        if (totalW > w) {
            maxScrollX = w - totalW; // 负值
            if (scrollX < maxScrollX) {
                scrollX = maxScrollX;
            }
            if (scrollX > 0) {
                scrollX = 0;
            }
        } else {
            maxScrollX = 0f;
            scrollX = 0f;
        }

        canvas.save();
        canvas.clipRect(0, 0, w, h);
        canvas.translate(scrollX, 0);

        int x = contentPad;
        int y = (h - itemHeight) / 2;
        for (Item it : items) {
            it.x = x;
            it.y = y;
            drawItem(canvas, it);
            x += it.width + itemGap;
        }
        canvas.restore();
    }

    /** 一个展示单元：灰字提示 或 词条（首候选特殊） */
    private static class Item {
        static final int TYPE_TEXT = 0;      // 拼音/引导/无匹配：灰色文本
        static final int TYPE_CAND_FIRST = 1; // 首候选：琥珀渐变
        static final int TYPE_CAND = 2;      // 其余候选：半透明深底
        final int type;
        final String text;
        final int index;                     // 候选下标（TYPE_TEXT 为 -1）
        float width;
        float x;
        float y;

        Item(int type, String text, int index) {
            this.type = type;
            this.text = text;
            this.index = index;
        }
    }

    private List<Item> buildItems() {
        List<Item> items = new ArrayList<>();
        textPaint.setTextSize(20 * density);
        textPaint.setColor(colorRes(R.color.text_secondary));

        if (hintText != null) {
            Item it = new Item(Item.TYPE_TEXT, hintText, -1);
            it.width = textPaint.measureText(hintText) + contentPad * 2f;
            items.add(it);
        } else if (displayText != null) {
            if (!candidates.isEmpty()) {
                Item d = new Item(Item.TYPE_TEXT, displayText, -1);
                d.width = textPaint.measureText(displayText) + contentPad * 2f;
                items.add(d);
            } else {
                // 缓冲有内容但无候选：拼音 + 附注
                String note = displayText;
                if (emptyNote != null) {
                    note = displayText + "（" + emptyNote + "）";
                }
                Item d = new Item(Item.TYPE_TEXT, note, -1);
                d.width = textPaint.measureText(note) + contentPad * 2f;
                items.add(d);
            }
            for (int i = 0; i < candidates.size(); i++) {
                int type = i == 0 ? Item.TYPE_CAND_FIRST : Item.TYPE_CAND;
                Item it = new Item(type, candidates.get(i), i);
                // 词条宽 = 文本 + 左右留白
                it.width = textPaint.measureText(it.text) + itemPadH * 2f;
                items.add(it);
            }
        }
        return items;
    }

    private void drawItem(Canvas canvas, Item it) {
        if (it.type == Item.TYPE_TEXT) {
            textPaint.setColor(colorRes(R.color.text_secondary));
            drawCenteredText(canvas, it.text, it.x + it.width / 2f, it.y + itemHeight / 2f, textPaint);
            return;
        }
        RectF r = new RectF(it.x, it.y, it.x + it.width, it.y + itemHeight);
        float radius = 10 * density;
        if (it.type == Item.TYPE_CAND_FIRST) {
            // 琥珀渐变高亮
            bgPaint.setShader(new LinearGradient(
                    r.left, r.top, r.left, r.bottom,
                    colorRes(R.color.amber_start),
                    colorRes(R.color.amber_end),
                    Shader.TileMode.CLAMP));
            canvas.drawRoundRect(r, radius, radius, bgPaint);
            bgPaint.setShader(null);
            textPaint.setColor(colorRes(R.color.text_on_amber));
        } else {
            // 半透明深色底 + 细边框
            bgPaint.setColor(colorRes(R.color.key_normal));
            bgPaint.setAlpha(150);
            canvas.drawRoundRect(r, radius, radius, bgPaint);
            bgPaint.setAlpha(255);
            strokePaint.setColor(colorRes(R.color.cand_border));
            canvas.drawRoundRect(r, radius, radius, strokePaint);
            textPaint.setColor(colorRes(R.color.text_primary));
        }
        drawCenteredText(canvas, it.text, r.centerX(), r.centerY(), textPaint);
    }

    private void drawCenteredText(Canvas canvas, String text, float cx, float cy, Paint paint) {
        Paint.FontMetrics fm = paint.getFontMetrics();
        float baseline = cy - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(text, cx, baseline, paint);
    }

    // ------------------------------------------------------------------
    // 触摸：点击候选词条 / 横向滚动
    // ------------------------------------------------------------------
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downScrollX = scrollX;
                trackingScroll = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = ev.getX() - downX;
                if (!trackingScroll && Math.abs(dx) > touchSlop) {
                    trackingScroll = true;
                }
                if (trackingScroll) {
                    float nx = downScrollX + dx;
                    if (nx > 0) {
                        nx = 0;
                    } else if (nx < maxScrollX) {
                        nx = maxScrollX;
                    }
                    if (nx != scrollX) {
                        scrollX = nx;
                        invalidate();
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (!trackingScroll) {
                    handleTap(ev.getX(), ev.getY());
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                return true;
            default:
                return true;
        }
    }

    private void handleTap(float x, float y) {
        if (listener == null) {
            return;
        }
        List<Item> items = buildItems();
        float hitX = x - scrollX;
        int yTop = (getHeight() - itemHeight) / 2;
        if (y < yTop || y > yTop + itemHeight) {
            return;
        }
        int cur = contentPad;
        for (Item it : items) {
            if (hitX >= cur && hitX <= cur + it.width) {
                if (it.type != Item.TYPE_TEXT) {
                    listener.onCandidateClick(it.index);
                }
                return;
            }
            cur += it.width + itemGap;
        }
    }
}
