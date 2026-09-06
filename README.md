# 点点划键盘（DotDash CN）

基于开源项目 [agwells/dotdash-keyboard-android](https://github.com/agwells/dotdash-keyboard-android)
二次开发的 Android 摩斯码输入法，面向中文用户重做了整套交互。

> 应用包名 `net.iowaline.dotdash.cn`，应用名「点点划键盘」，与原版可共存安装。

## 核心交互

主键位只保留 **点（·）**、**划（–）**、**退格**，并新增 **分号（；）强制分割键**——
每敲完一个字母的点划组合按一下分号，立即翻译成字母，**没有超时自动提交逻辑**。

键盘版式：

```
， ·     –          ← 点、划两个大键
。 ；    ⌫          ← 分号强制分割 / 退格
？ 空格             ← 中文候选确认 / 英文词空格
！ 中⇄英  符        ← 模式切换 / 符号面板
```

左侧固定 4 个常用符号（，。？！）一键上屏；「符」键展开多页符号面板，点一下直接上屏，
**符号系统完全脱离摩斯码**。

### 英文模式（EN）
`· 按点 · - 分号` ⇒ 上屏字母，例如：`.- 分号` → `a`；`-... 分号` → `b`。

### 中文模式（中）
字母先进入拼音缓冲区，自动切分拼音并弹出候选词，**无需手动空格**：

```
-... 分号   → 缓冲 b?（示例：ni hao 需按 ni+分号、hao+分号）
. 分号      → n
.. 分号     → i    → 缓冲 "ni"
.... 分号   → h
.- 分号     → a
--- 分号    → o    → 缓冲 "nihao" → 候选条出现「你好」
空格        → 上屏「你好」并清空缓冲（也可直接点候选条里的候选词）
```

「中⇄英」随时切换；中文模式下切换到英文前如有未提交候选会自动上屏，避免丢字。

### 符号面板
「符」键进入，共 3 页：常用中文标点 / 数字与英文符号 / 扩展符号。底部：
`返回 | ‹ | 1/3 | ›`，翻页循环。点符号立即上屏并停留在面板，方便连续输入；点「返回」回主键盘。

### 拼音引擎
- 全拼 Trie 词典 + DP 音节切分（无手动切分/空格）
- 整串词优先，其次按切分路径贪心组词、逐字高频字补足
- 词典：约 5.8 万条（单字全音 + 常用词），排序参考词频
- 候选上限 9 个，横条可滚动，点按任选

## 构建

本地（需 JDK 17+ 与 Android SDK）：

```bash
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

> 中国大陆网络下 Gradle 发行版下载可能失败，本仓库 wrapper 已指向腾讯云镜像
> （`gradle/wrapper/gradle-wrapper.properties`），可自行换回官方地址。

GitHub Actions 云端构建：推送到 GitHub 后 `.github/workflows/build.yml` 自动产出
debug APK 并上传为 artifact（`workflow_dispatch` 可手动触发）。

## 使用

1. 安装后从桌面图标打开「点点划键盘」查看说明，点「启用输入法」；
2. 系统设置 → 语言与输入法 → 启用「点点划键盘」并设为当前输入法；
3. 在任何输入框呼出键盘即可使用。

## 目录结构

```
app/src/main/java/net/iowaline/dotdash/cn/
  DotDashIMEService.java  输入法服务（模式状态机、分号分割、候选/符号处理）
  PinyinEngine.java       纯 Java 拼音引擎（Trie + DP 切分 + 候选排序）
  DotDashPrefs.java       设置页（默认输入模式）
  UsageActivity.java      桌面引导页
app/src/main/res/xml/
  dotdash.xml             主键盘布局
  symbols_page*.xml       符号面板（由 tools/gen_symbol_pages.py 生成）
app/src/main/assets/dict/ 拼音词典（pinyin.txt / syllables.txt）
tools/gen_symbol_pages.py 符号面板字符集生成脚本
```

## 许可与数据来源

- 本项目派生自 [DotDash Keyboard](https://github.com/agwells/dotdash-keyboard-android)
  （含 AOSP / Hacker's Keyboard 代码），沿用 **GPL-3.0-or-later**，详见 [LICENSE](LICENSE) 与 [NOTICE](NOTICE)。
- 拼音词典数据来源（均 MIT）：[fxsjy/jieba](https://github.com/fxsjy/jieba)（词频）、
  [mozillazg/pinyin-data](https://github.com/mozillazg/pinyin-data)（汉字拼音）、
  [mozillazg/phrase-pinyin-data](https://github.com/mozillazg/phrase-pinyin-data)（词语拼音）。
