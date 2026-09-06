#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
生成"点点划键盘"的多页符号面板 Keyboard XML（res/xml/symbols_page*.xml）。

键码约定（与 DotDashIMEService.java 保持同步）：
    普通字符键   : code = ord(char)
    返回主键盘   : -203
    上一页       : -204
    下一页       : -205
    页码占位(无操作): -206

修改下方 PAGES 数据后重新运行：
    python tools/gen_symbol_pages.py
"""
import os

# (页面名, [ [行1字符...], [行2...], ... ])，每行最多 8 个字符
PAGES = [
    ("page0_common", [
        ["，", "。", "、", "？", "！", "：", "；", "～"],
        ["“", "”", "‘", "’", "（", "）", "【", "】"],
        ["《", "》", "—", "…", "·", "￥", "℃"],
        ["①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧"],
    ]),
    ("page1_digits", [
        ["1", "2", "3", "4", "5", "6", "7", "8"],
        ["9", "0", "-", "+", "=", ".", ",", "?"],
        ["@", "#", "$", "%", "^", "&", "*", "!"],
        ["(", ")", "[", "]", "{", "}", "<", ">"],
    ]),
    ("page2_ext", [
        ["⑨", "⑩", "½", "¼", "¾", "×", "÷", "±"],
        ["≈", "≠", "≤", "≥", "∞", "∴", "∵", "°"],
        ["§", "¶", "†", "‡", "¤", "¢", "£", "€"],
        ["Ⅰ", "Ⅱ", "Ⅲ", "Ⅳ", "Ⅴ", "Ⅵ", "Ⅶ", "Ⅷ"],
    ]),
]

CODE_BACK = -203
CODE_PREV = -204
CODE_NEXT = -205
CODE_PAGE = -206

OUT_DIR = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "xml")

KEY_ATTR = (
    '            android:keyLabel="%s"\n'
    '            android:keyWidth="%.3f%%"\n'
    '            android:codes="%d"%s\n'
)


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")


def gen(name, rows, total_pages, page_no):
    lines = []
    lines.append('<?xml version="1.0" encoding="utf-8"?>')
    lines.append("<!-- 本文件由 tools/gen_symbol_pages.py 自动生成，请勿手改。 -->")
    lines.append("<Keyboard xmlns:android=\"http://schemas.android.com/apk/res/android\"")
    lines.append('    android:horizontalGap="0px"')
    lines.append('    android:verticalGap="0px">')
    lines.append("")
    first = True
    for row in rows:
        assert len(row) <= 8, "每行最多 8 个符号: %s" % (row,)
        edge = '    <Row android:keyHeight="54dp"'
        if first:
            edge += ' android:rowEdgeFlags="top"'
        lines.append(edge + ">")
        first = False
        n = len(row)
        width = 100.0 / n
        for i, ch in enumerate(row):
            c = ord(ch)
            right = ""
            if i == n - 1:
                right = ' android:keyEdgeFlags="right"'
            left = ""
            if i == 0:
                left = ' android:keyEdgeFlags="left"'
            lines.append(
                '        <Key android:keyLabel="%s" android:keyWidth="%.3f%%"'
                ' android:codes="%d"%s%s />'
                % (esc(ch), width, c, left, right)
            )
        lines.append("    </Row>")
        lines.append("")
    # 功能行
    lines.append('    <Row android:keyHeight="50dp" android:rowEdgeFlags="bottom">')
    funcs = [
        ("返回", CODE_BACK, "left"),
        ("‹", CODE_PREV, None),
        ("%d/%d" % (page_no + 1, total_pages), CODE_PAGE, None),
        ("›", CODE_NEXT, "right"),
    ]
    for i, (label, code, edge) in enumerate(funcs):
        attr = ' android:keyEdgeFlags="%s"' % edge if edge else ""
        lines.append(
            '        <Key android:keyLabel="%s" android:keyWidth="25%%"'
            ' android:codes="%d"%s />' % (esc(label), code, attr)
        )
    lines.append("    </Row>")
    lines.append("")
    lines.append("</Keyboard>")
    lines.append("")
    return "\n".join(lines)


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    total = len(PAGES)
    for i, (page_name, rows) in enumerate(PAGES):
        # 兼容旧命名：symbols_page0 / page1 / page2
        page_id = "symbols_page%d" % i
        content = gen(page_id, rows, total, i)
        path = os.path.join(OUT_DIR, page_id + ".xml")
        with open(path, "w", encoding="utf-8") as f:
            f.write(content)
        print("generated", path)
    print("done: %d pages" % total)


if __name__ == "__main__":
    main()
