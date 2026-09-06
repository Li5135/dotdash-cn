#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
静态检查：app/src/main/java 中所有 R.<type>.<name> 引用都能在 res 下找到。
用于在 IDE 编译前快速自查资源引用笔误。

用法：python tools/check_resources.py
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
JAVA = os.path.join(ROOT, "app", "src", "main", "java")


def collect():
    found = {}
    for dirpath, _dirs, files in os.walk(RES):
        rtype = os.path.basename(dirpath).split("-")[0]
        for f in files:
            found.setdefault(rtype, set()).add(os.path.splitext(f)[0])
    # values 内 <string>/<string-array> 等具名资源
    for dirpath, _dirs, files in os.walk(RES):
        if os.path.basename(dirpath).split("-")[0] != "values":
            continue
        for f in files:
            if not f.endswith(".xml"):
                continue
            try:
                tree = ET.parse(os.path.join(dirpath, f))
            except Exception:
                continue
            for el in tree.getroot().iter():
                tag = el.tag.rsplit("}", 1)[-1]
                if tag in ("string", "string-array", "style", "array", "plurals"):
                    nm = el.get("name")
                    if nm:
                        found.setdefault("string", set()).add(nm) if tag == "string" \
                            else found.setdefault(tag, set()).add(nm)
    # 布局里的 @+id
    for dirpath, _dirs, files in os.walk(RES):
        if os.path.basename(dirpath).split("-")[0] != "layout":
            continue
        for f in files:
            with open(os.path.join(dirpath, f), encoding="utf-8") as fh:
                for m in re.finditer(r"@\+id/(\w+)", fh.read()):
                    found.setdefault("id", set()).add(m.group(1))
    return found


def main():
    found = collect()
    pat = re.compile(r"\bR\.(\w+)\.(\w+)")
    missing = []
    for dirpath, _dirs, files in os.walk(JAVA):
        for f in files:
            if not f.endswith(".java"):
                continue
            path = os.path.join(dirpath, f)
            with open(path, encoding="utf-8") as fh:
                for m in pat.finditer(fh.read()):
                    if m.group(2) not in found.get(m.group(1), set()):
                        missing.append("%s: R.%s.%s" % (
                            os.path.relpath(path, ROOT), m.group(1), m.group(2)))
    if missing:
        print("MISSING:")
        for x in sorted(set(missing)):
            print("  ", x)
        sys.exit(1)
    print("ALL R.* REFERENCES RESOLVE")


if __name__ == "__main__":
    main()
