# 点点划键盘（DotDash CN）

基于开源项目 [agwells/dotdash-keyboard-android](https://github.com/agwells/dotdash-keyboard-android)
（GPL-3.0）二次开发的 Android 摩斯输入法，新增 **中文拼音智能输入**。

> 单手持机也能打字：主键只有 **点(·)** 与 **划(–)**，每敲完一个字母的点划组合按一下
> **分号(；)** 立即翻译成字母——彻底废除原版"等待超时自动提交"的旧逻辑。

## 功能

- **主键盘极简**：点(·)、划(–)、退格(⌫) 三大键；分号(；) 为"强制分割键"。
- **中/英切换**：保留切换键（左下角，label 显示当前模式"中"/"EN"）。
- **英文模式**：点划 → 分号 → 字母**直接上屏**。
- **中文模式**：点划 → 分号 → 字母追加到拼音缓冲 → **自动切分拼音**（Trie + DP，无需手敲空格）→ 顶部候选条弹词；**按空格上屏首个候选并清空缓冲**，也可直接点选其它候选。
- **符号系统完全脱离摩斯**：键盘左侧固定 4 个常用符号（，。？！）；底部"符"键展开**三页符号面板**（常用中文标点 / 数字·英文符号 / 扩展符号），点一下直接上屏。
- 点划输入时**空格键实时显示**正在敲的码（`.-`），所见即所得。
- 附带桌面引导页（一键跳转系统输入法设置）与简易设置页（默认中文/英文）。

### 键盘布局

```
┌──────┬───────────────────────┐
│ 候选条│ 拼音 ni hao   你好    │
├──────┼──────────┬────────────┤
│ ，   │    ·     │     –      │
├──────┼──────────┼────────────┤
│ 。   │    ；    │     ⌫      │
├──────┼──────────┴────────────┤
│ ？   │         空格          │
├──────┼──────────┬────────────┤
│ ！   │  中 ⇄ EN │     符     │
└──────┴──────────┴────────────┘
```

## 构建

### 方式一：GitHub Actions（推荐，免本地环境）

仓库已附带 `.github/workflows/build.yml`。推送到 GitHub 后：

1. Actions 自动执行 `./gradlew assembleDebug`；
2. 构建产物 **app-debug.apk** 在 Action 页面的 Artifacts 中下载。

```bash
git push origin main
```

### 方式二：本地 Android Studio / 命令行

要求：JDK 17、Android SDK（compileSdk 35）。

> 中国大陆网络下，若下载 Gradle 缓慢，本项目 wrapper 已默认指向腾讯云镜像
> （`gradle/wrapper/gradle-wrapper.properties`）；想换官方源改回
> `https\://services.gradle.org/distributions/gradle-8.14.3-bin.zip` 即可。

```bash
./gradlew assembleDebug          # Windows: gradlew.bat assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

## 使用

1. 安装 APK；
2. 打开桌面"点点划键盘"图标（或系统设置 → 语言与输入法）启用该输入法并设为默认；
3. 在任意输入框长按选择"点点划键盘"。

摩斯速查（英文 26 字母）：

| a | b | c | d | e | f | g | h | i | j |
|---|---|---|---|---|---|---|---|---|---|
| .- | -... | -.-. | -.. | . | ..-. | --. | .... | .. | .--- |

| k | l | m | n | o | p | q | r | s | t |
|---|---|---|---|---|---|---|---|---|---|
| -.- | .-.. | -- | -. | --- | .--. | --.- | .-. | ... | - |

| u | v | w | x | y | z |
|---|---|---|---|---|---|
| ..- | ...- | .-- | -..- | -.-- | --.. |

示例：输入"你好" → `-. .. .... .- ---`（即 n i h a o 逐字母），每敲完一个字母按分号(；)，
拼音条出现 `ni hao` 与候选"你好"，按空格上屏。

## 词典数据与许可

- 主程序：GPL-3.0（继承自 DotDash Keyboard；其中部分文件来自 AOSP / Hacker's Keyboard，Apache-2.0，见原 `NOTICE`）。
- 拼音词典（`app/src/main/assets/dict/`）构建自以下 MIT 许可数据，各许可证全文见 `docs/licenses/`：
  - [mozillazg/pinyin-data](https://github.com/mozillazg/pinyin-data)（汉字拼音，`kXHC1983.txt`）
  - [mozillazg/phrase-pinyin-data](https://github.com/mozillazg/phrase-pinyin-data)（词语拼音，`pinyin.txt` / `large_pinyin.txt`）
  - [fxsjy/jieba](https://github.com/fxsjy/jieba)（`dict.txt`，用作词频排序权重）
- 词库生成脚本：`tools/gen_symbol_pages.py`（符号面板）与仓库外的一次性脚本（详见 `docs/dict-build.md`）。

## 目录结构

```
app/src/main/
├── java/net/iowaline/dotdash/cn/
│   ├── DotDashIMEService.java   # 输入法服务：中英模式状态机、分号分割、候选交互
│   ├── PinyinEngine.java        # 拼音引擎：词典装载、DP 音节切分、候选排序（纯 Java）
│   ├── DotDashPrefs.java        # 设置页
│   └── UsageActivity.java       # 桌面引导页
├── res/xml/dotdash.xml          # 主键盘布局
├── res/xml/symbols_page*.xml    # 符号面板（tools/gen_symbol_pages.py 生成）
├── res/layout/candidates.xml    # 候选条
└── assets/dict/                 # 拼音词库 + 音节表
```

## 已知限制 / Roadmap

- 英文模式固定小写（原 caps lock 已移除，大写可用符号面板外的应用内 Shift 或后续版本补充）；
- 中文候选为"整串词 + 少量同音变体"，无 n-gram 语言模型，长句联想有限；
- 无按键音/振动（原版 iambic/超时逻辑已按设计废除）；
- Keyboard/KeyboardView 属系统 deprecated API（仍可用且稳定），后续可平滑迁移到自绘视图。
