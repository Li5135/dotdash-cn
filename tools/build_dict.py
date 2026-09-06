# -*- coding: utf-8 -*-
"""
构建"点点划键盘"拼音词典（一次性工具，运行于 /tmp/dictbuild）。

数据来源：
  - 汉字拼音表 : mozillazg/pinyin-data  kXHC1983.txt   (MIT)
  - 词语拼音表 : mozillazg/phrase-pinyin-data  pinyin.txt (MIT)
  - 词频参考   : fxsjy/jieba  dict.txt                   (MIT)

输出：
  syllables.txt  每行一个合法全拼音节（去声调、ü->v）
  pinyin.txt     行格式 拼音<TAB>词<TAB>词频（UTF-8）
"""
import re
import os
import sys

# 用法: python tools/build_dict.py <数据目录> <输出目录>
#   <数据目录> 需包含 kxhc.txt / pinyin.txt / large_pinyin.txt / jieba.txt
#   （从各上游仓库下载，见 docs/dict-build.md）
BASE = sys.argv[1] if len(sys.argv) > 1 else "."
OUT = sys.argv[2] if len(sys.argv) > 2 else os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "assets", "dict")
os.makedirs(OUT, exist_ok=True)

# 去声调映射（含 ü -> v）
_TONE_MAP = {
    ord(c): ord(base) for base, tonal in [
        ("a", "āáǎà"), ("o", "ōóǒò"), ("e", "ēéěè"),
        ("i", "īíǐì"), ("u", "ūúǔù"), ("v", "ǖǘǚǜ"),
    ] for c in tonal
}
_TONE_MAP[ord("ê")] = ord("e")
_TONE_MAP[ord("ế")] = ord("e")
_TONE_MAP[ord("ü")] = ord("v")
_TONE_MAP[ord("Ü")] = ord("v")


def clean_pinyin(s):
    """去声调、转小写、ü->v"""
    return s.translate(_TONE_MAP).lower().replace(" ", "")


def is_hanzi_char(c):
    o = ord(c)
    return (0x3400 <= o <= 0x9FFF) or (0xF900 <= o <= 0xFAFF) or (0x20000 <= o <= 0x2FFFF)


def load_jieba_freq(path):
    """jieba dict.txt -> {词: 频} 以及单字累计频次 {字: 频}"""
    word_freq = {}
    char_freq = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split(" ")
            if len(parts) < 2:
                continue
            w, freq = parts[0], float(parts[1])
            if not all(is_hanzi_char(c) for c in w):
                continue
            word_freq[w] = freq
            if len(w) == 1:
                char_freq[w] = max(char_freq.get(w, 0.0), freq)
    return word_freq, char_freq


def load_kxhc(path):
    """kXHC1983.txt -> {汉字: set(全拼音节)}"""
    char_syl = {}
    pat = re.compile(r"^U\+([0-9A-Fa-f]+):\s*(\S+)")
    with open(path, encoding="utf-8") as f:
        for line in f:
            m = pat.match(line.strip())
            if not m:
                continue
            cp = int(m.group(1), 16)
            if not is_hanzi_char(chr(cp)):
                continue
            for part in m.group(2).split(","):
                p = clean_pinyin(part)
                if p:
                    char_syl.setdefault(chr(cp), set()).add(p)
    return char_syl


def load_phrases(path, word_freq=None, min_freq=None):
    """phrase pinyin 数据 -> [(词, 全拼串, [音节...])]
    min_freq 非空时仅保留 jieba 词频不低于该值的词（用于大词库裁剪）。"""
    out = []
    pat = re.compile(r"^(.*?):\s*(.*?)\s*(?:#.*)?$")
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            # 去行内注释
            line = re.split(r"\s+#", line)[0]
            if ":" not in line:
                continue
            word, pys = line.split(":", 1)
            word = word.strip()
            pys = pys.strip().split()
            if not word or not all(is_hanzi_char(c) for c in word):
                continue
            if len(pys) != len(word):
                continue
            syl = [clean_pinyin(x) for x in pys]
            if any(not s for s in syl):
                continue
            joined = "".join(syl)
            if len(joined) > 18:   # 超过拼音缓冲上限的词条不收录
                continue
            if min_freq is not None:
                if word_freq is None or word_freq.get(word, 0.0) < min_freq:
                    continue
            out.append((word, joined, syl))
    # 去重（同词同音只留一次）
    seen = set()
    uniq = []
    for t in out:
        k = (t[1], t[0])
        if k in seen:
            continue
        seen.add(k)
        uniq.append(t)
    return uniq


def main():
    word_freq, char_freq = load_jieba_freq(os.path.join(BASE, "jieba.txt"))
    char_syl = load_kxhc(os.path.join(BASE, "kxhc.txt"))
    # 1) 多音字相关词语（全覆盖）；2) 常用词（jieba 词频 >= 800，补足不含多音字的常用词）
    phrases = load_phrases(os.path.join(BASE, "pinyin.txt"))
    phrases += load_phrases(os.path.join(BASE, "large_pinyin.txt"),
                            word_freq=word_freq, min_freq=150)
    print("jieba words:", len(word_freq), "kxhc chars:", len(char_syl), "phrases:", len(phrases))

    syllables = set()
    for s in char_syl.values():
        syllables.update(s)
    for _, _, syl in phrases:
        syllables.update(syl)

    lines = []
    # 1) 单字（含全部读音；字频来自 jieba 单字条目或词内累计）
    for ch, syls in sorted(char_syl.items()):
        base_freq = char_freq.get(ch, 0.0)
        for p in sorted(syls):
            lines.append((p, ch, base_freq))
    # 2) 词语
    for word, joined, _syl in phrases:
        freq = word_freq.get(word, 0.0)
        lines.append((joined, word, freq))

    # 词频为 0 的给 1（保留词条但排最后）
    out_rows = []
    for p, w, f in lines:
        if f <= 0:
            f = 1
        out_rows.append("%s\t%s\t%d" % (p, w, f))

    with open(os.path.join(OUT, "pinyin.txt"), "w", encoding="utf-8") as f:
        f.write("# pinyin<TAB>word<TAB>freq  (generated by tools/build_dict.py)\n")
        f.write("\n".join(out_rows))
        f.write("\n")
    with open(os.path.join(OUT, "syllables.txt"), "w", encoding="utf-8") as f:
        f.write("# 全拼音节（去声调、ü->v）\n")
        f.write("\n".join(sorted(syllables)))
        f.write("\n")

    print("syllables:", len(syllables))
    print("dict rows:", len(out_rows))
    print("pinyin.txt bytes:", os.path.getsize(os.path.join(OUT, "pinyin.txt")))


if __name__ == "__main__":
    main()
