package net.iowaline.dotdash.cn;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 全拼引擎：负责
 *  1) 装载"拼音-汉字词"词库与合法音节表；
 *  2) 把用户累积的拼音字母串用 DP/回溯自动切分成音节序列；
 *  3) 依据词典生成候选（整串词条优先，其次各切分路径上"最长词贪心 + 逐字补足"的句子候选）。
 *
 * 纯 Java 实现，不依赖任何 Android API，方便单元测试与复用。
 * 词库行格式（UTF-8，Tab 分隔）：<拼音>\t<词>\t<词频>
 * 音节表每行一个合法拼音音节（不带声调）。
 */
public final class PinyinEngine {

    /** 候选词最大数量 */
    public static final int MAX_CANDIDATES = 9;
    /** 单个拼音音节的最大字母长度（如 zhuang=6） */
    private static final int MAX_SYLLABLE_LEN = 6;
    /** 音节切分路径枚举上限，防止组合爆炸 */
    private static final int MAX_PATHS = 64;
    /** 拼音缓冲最大长度（超出后忽略新字母） */
    public static final int MAX_PINYIN_LEN = 18;

    /** 词条 */
    public static final class Word {
        public final String text;
        public final long freq;

        public Word(String text, long freq) {
            this.text = text;
            this.freq = freq;
        }

        @Override
        public String toString() {
            return text + "(" + freq + ")";
        }
    }

    /** 一次输入得到的候选结果 */
    public static final class Result {
        /** 供候选条显示的拼音切分，如 "ni hao"；切分失败时为原始字母串 */
        public final String display;
        /** 候选词（已按词频降序、去重、限长） */
        public final List<String> candidates;

        Result(String display, List<String> candidates) {
            this.display = display;
            this.candidates = candidates;
        }
    }

    private final Set<String> syllables = new HashSet<>();
    /** 整串拼音 -> 全部同音词（含单字），已按词频降序 */
    private final Map<String, List<Word>> wordMap = new HashMap<>();
    /** 单音节 -> 高频单字（已排序），用于逐字补足 */
    private final Map<String, List<Word>> oneCharBySyllable = new HashMap<>();

    private volatile boolean loaded = false;
    private Result lastResult = new Result("", Collections.<String>emptyList());

    public boolean isLoaded() {
        return loaded;
    }

    /**
     * 装载词典。两个流均可为空（空则跳过对应数据）。
     */
    public synchronized void load(InputStream syllablesIn, InputStream dictIn) throws IOException {
        syllables.clear();
        wordMap.clear();
        oneCharBySyllable.clear();

        if (syllablesIn != null) {
            BufferedReader br = new BufferedReader(new InputStreamReader(syllablesIn, StandardCharsets.UTF_8));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String s = line.split("\\s+")[0].toLowerCase();
                if (isLatinLetters(s)) {
                    syllables.add(s);
                }
            }
        }

        if (dictIn != null) {
            BufferedReader br = new BufferedReader(new InputStreamReader(dictIn, StandardCharsets.UTF_8));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\t");
                if (parts.length < 2) {
                    continue;
                }
                String pinyin = parts[0].trim().toLowerCase();
                String word = parts[1].trim();
                if (pinyin.isEmpty() || word.isEmpty() || !isLatinLetters(pinyin)) {
                    continue;
                }
                long freq = 1;
                if (parts.length >= 3) {
                    try {
                        freq = Long.parseLong(parts[2].trim());
                    } catch (NumberFormatException ignored) {
                        // 词频解析失败则用默认值 1
                    }
                }
                wordMap.computeIfAbsent(pinyin, k -> new ArrayList<>()).add(new Word(word, freq));
                if (word.codePointCount(0, word.length()) == 1) {
                    oneCharBySyllable.computeIfAbsent(pinyin, k -> new ArrayList<>()).add(new Word(word, freq));
                }
            }
        }

        // 词频降序排序
        Comparator<Word> byFreq = (a, b) -> Long.compare(b.freq, a.freq);
        for (List<Word> list : wordMap.values()) {
            list.sort(byFreq);
        }
        for (List<Word> list : oneCharBySyllable.values()) {
            list.sort(byFreq);
        }
        applyCharRankOverrides();
        loaded = true;
    }

    /**
     * 常用音节"首字"人工调节表。
     *
     * 背景：jieba 的单字词频会把"向/见/其/己"这类高频虚词/代词排前，
     * 但口语输入时这些音节更常指"想/现/机/己(自己)"等。此处把最常见
     * 期望首字提升到该音节候选首位（不改变其它字的相对顺序）。
     * 可在此按个人习惯增删。
     */
    private static final String[][] CHAR_RANK_OVERRIDES = {
            {"ni", "你"}, {"wo", "我"}, {"ta", "他"}, {"de", "的"},
            {"shi", "是"}, {"yi", "一"}, {"you", "有"}, {"ren", "人"},
            {"zai", "在"}, {"zhe", "这"}, {"shang", "上"}, {"xia", "下"},
            {"men", "们"}, {"ge", "个"}, {"jiu", "就"}, {"dou", "都"},
            {"da", "大"}, {"xiao", "小"}, {"zhong", "中"}, {"guo", "国"},
            {"tian", "天"}, {"nian", "年"}, {"di", "地"}, {"xiang", "想"},
            {"yao", "要"}, {"hai", "还"}, {"kan", "看"}, {"shuo", "说"},
            {"lai", "来"}, {"qu", "去"}, {"hui", "会"}, {"neng", "能"},
            {"dao", "到"}, {"gei", "给"}, {"jiao", "叫"}, {"wen", "问"},
            {"cai", "才"}, {"zuo", "做"}, {"yong", "用"}, {"hao", "好"},
            {"zi", "子"}, {"ji", "机"}, {"jian", "见"}, {"xian", "现"},
            {"qing", "请"}, {"ming", "明"}, {"wan", "晚"}, {"dian", "点"},
            {"xie", "写"}, {"ting", "听"}, {"du", "读"}, {"er", "二"},
            {"san", "三"}, {"si", "四"}, {"wu", "五"}, {"liu", "六"},
            {"qi", "起"},
    };

    private void applyCharRankOverrides() {
        for (String[] pair : CHAR_RANK_OVERRIDES) {
            // 整串词候选（单音节输入时直接作为候选列表）
            List<Word> list = wordMap.get(pair[0]);
            if (list != null) {
                promote(list, pair[1]);
            }
            // 句子回退候选中的单音节选字
            List<Word> chars = oneCharBySyllable.get(pair[0]);
            if (chars != null) {
                promote(chars, pair[1]);
            }
        }
    }

    /** 把 text 对应词条提到列表首位（若存在且不在首位） */
    private static void promote(List<Word> list, String text) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).text.equals(text) && i != 0) {
                Word w = list.remove(i);
                list.add(0, w);
                break;
            }
        }
    }

    /**
     * 根据当前拼音缓冲重新计算候选。线程安全由调用方保证（输入法主线程）。
     * 词典尚未装载完成时返回空结果。
     */
    public synchronized Result update(String letters) {
        if (!loaded) {
            lastResult = new Result("", Collections.<String>emptyList());
            return lastResult;
        }
        if (letters == null) {
            letters = "";
        }
        letters = letters.toLowerCase();
        if (letters.isEmpty()) {
            lastResult = new Result("", Collections.<String>emptyList());
            return lastResult;
        }

        // 1) 枚举全部合法音节切分
        List<List<String>> paths = new ArrayList<>();
        enumerate(letters, 0, new ArrayList<String>(), paths);

        // 2) 整串词条（若存在，直接用词条候选，不再叠加路径句子）
        LinkedHashSet<String> cands = new LinkedHashSet<>();
        List<Word> whole = wordMap.get(letters);
        if (whole != null && !whole.isEmpty()) {
            for (Word w : whole) {
                cands.add(w.text);
                if (cands.size() >= MAX_CANDIDATES) {
                    break;
                }
            }
        } else {
            // 3) 各切分路径 -> "最长词贪心 + 逐字补足"的句子候选（含同音字变体）
            for (List<String> path : paths) {
                addSentenceCandidates(path, cands);
                if (cands.size() >= MAX_CANDIDATES) {
                    break;
                }
            }
        }

        String display = paths.isEmpty()
                ? letters
                : joinWithSpaces(paths.get(0));

        List<String> finalCands = new ArrayList<>(cands);
        lastResult = new Result(display, finalCands);
        return lastResult;
    }

    public synchronized Result getLastResult() {
        return lastResult;
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /** 递归枚举把字母串切分为合法音节的所有方案 */
    private void enumerate(String s, int pos, List<String> cur, List<List<String>> out) {
        if (out.size() >= MAX_PATHS) {
            return;
        }
        if (pos == s.length()) {
            out.add(new ArrayList<>(cur));
            return;
        }
        int maxLen = Math.min(MAX_SYLLABLE_LEN, s.length() - pos);
        // 优先尝试较长的音节，让切分结果更直观（先出整体、再出拆分）
        for (int len = maxLen; len >= 1; len--) {
            String seg = s.substring(pos, pos + len);
            if (syllables.contains(seg)) {
                cur.add(seg);
                enumerate(s, pos + len, cur, out);
                cur.remove(cur.size() - 1);
                if (out.size() >= MAX_PATHS) {
                    return;
                }
            }
        }
    }

    /** 路径候选中的一段：已合并成词典词，或待选单字的音节 */
    private static final class Segment {
        /** 词典词（整段匹配）时的词文本；否则为 null */
        final String word;
        /** 待选单字的音节；词典词时为 null */
        final String syl;
        /** 该音节的高频单字（最多取前 3） */
        final List<Word> chars;

        Segment(String word, String syl, List<Word> chars) {
            this.word = word;
            this.syl = syl;
            this.chars = chars;
        }
    }

    /**
     * 把一个音节切分路径合成为句子候选（含少量同音字变体）：
     * 从左到右尝试用词典里"音节串恰好对齐"的多音节词覆盖（贪心最长词优先），
     * 没有词覆盖的单音节退回该音节的高频单字；
     * 无词典词覆盖的路径退化为逐字候选句，并额外给出把个别单字换成
     * 次高频同音字的变体（用于弥补整词缺失时的选字偏差）。
     */
    private void addSentenceCandidates(List<String> path, LinkedHashSet<String> out) {
        if (path.isEmpty()) {
            return;
        }
        List<Segment> segs = new ArrayList<>();
        int i = 0;
        while (i < path.size()) {
            int bestLen = 0;
            String bestText = null;
            // 最长词优先：从当前音节起尝试 1..剩余 个音节的整串匹配
            StringBuilder acc = new StringBuilder(path.get(i));
            for (int j = i + 1; j <= path.size(); j++) {
                List<Word> words = wordMap.get(acc.toString());
                if (words != null) {
                    bestLen = j - i;
                    bestText = words.get(0).text;   // 取该拼法最高频词
                }
                if (j < path.size()) {
                    acc.append(path.get(j));
                }
            }
            if (bestLen >= 2) {
                segs.add(new Segment(bestText, null, null));
                i += bestLen;
            } else {
                List<Word> chars = oneCharBySyllable.get(path.get(i));
                if (chars == null || chars.isEmpty()) {
                    // 该音节词典无字：放弃整条路径的句子候选
                    return;
                }
                List<Word> top3 = chars.subList(0, Math.min(3, chars.size()));
                segs.add(new Segment(null, path.get(i), top3));
                i += 1;
            }
        }

        // 基准句：各段取默认（词典词取词，单音节取最高频单字）
        addBase(segs, out);

        // 变体：从右往左，把"待选单字"段替换为第 2/3 高频同音字（最多 4 句）
        int variants = 0;
        for (int pos = segs.size() - 1; pos >= 0 && variants < 4; pos--) {
            Segment seg = segs.get(pos);
            if (seg.syl == null) {
                continue;
            }
            for (int rank = 1; rank < seg.chars.size() && variants < 4; rank++) {
                if (addVariantAt(segs, pos, rank, out)) {
                    variants++;
                }
            }
        }
    }

    /** 基准句：每段取默认文本（词典词取词，单音节取最高频单字） */
    private static boolean addBase(List<Segment> segs, LinkedHashSet<String> out) {
        StringBuilder sb = new StringBuilder();
        for (Segment seg : segs) {
            if (seg.word != null) {
                sb.append(seg.word);
            } else {
                sb.append(seg.chars.get(0).text);
            }
        }
        return out.add(sb.toString());
    }

    /** 把第 pos 个待选单字段替换成第 rank 高频字后生成一句 */
    private static boolean addVariantAt(List<Segment> segs, int pos, int rank, LinkedHashSet<String> out) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segs.size(); i++) {
            Segment seg = segs.get(i);
            if (seg.word != null) {
                sb.append(seg.word);
            } else if (i == pos && rank < seg.chars.size()) {
                sb.append(seg.chars.get(rank).text);
            } else {
                sb.append(seg.chars.get(0).text);
            }
        }
        return out.add(sb.toString());
    }

    private static String joinWithSpaces(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(p);
        }
        return sb.toString();
    }

    private static boolean isLatinLetters(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(c >= 'a' && c <= 'z')) {
                return false;
            }
        }
        return true;
    }
}
