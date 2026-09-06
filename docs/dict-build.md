# 词典构建说明

`app/src/main/assets/dict/` 下的两个文件：

- `syllables.txt` — 全拼音节表（每行一个，去声调、`ü→v`，共 424 个）
- `pinyin.txt` — 词库，行格式 `拼音<TAB>词<TAB>词频`（UTF-8，约 8 万行，1.7 MB）

均由 `tools/build_dict.py` 从三个上游数据源生成。

## 上游数据

| 用途 | 来源 | 需要的文件 | 许可 |
|---|---|---|---|
| 汉字拼音 | [mozillazg/pinyin-data](https://github.com/mozillazg/pinyin-data) | `kXHC1983.txt` | MIT（副本：`docs/licenses/LICENSE-pinyin-data.txt`） |
| 词语拼音 | [mozillazg/phrase-pinyin-data](https://github.com/mozillazg/phrase-pinyin-data) | `pinyin.txt`、`large_pinyin.txt` | MIT（副本：`docs/licenses/LICENSE-phrase-pinyin-data.txt`） |
| 词频权重 | [fxsjy/jieba](https://github.com/fxsjy/jieba) | `dict.txt` | MIT（副本：`docs/licenses/LICENSE-jieba.txt`） |

下载命令示例：

```bash
mkdir -p data && cd data
curl -L -O https://raw.githubusercontent.com/mozillazg/pinyin-data/master/kXHC1983.txt
curl -L -O https://raw.githubusercontent.com/mozillazg/phrase-pinyin-data/master/pinyin.txt
curl -L -O https://raw.githubusercontent.com/mozillazg/phrase-pinyin-data/master/large_pinyin.txt
curl -L -O https://raw.githubusercontent.com/fxsjy/jieba/master/jieba/dict.txt
```

## 重新生成

```bash
python tools/build_dict.py <数据目录> <输出目录>
# 默认输出到 app/src/main/assets/dict
```

## 生成规则（与 PinyinEngine 的约定对应）

1. **去声调**：`āáǎà→a` 等；`ü/ǖǘǚǜ→v`（输入法用 `v` 表示 `ü`，如 `nü→nv`）。
2. **词条拼音拼接**：词语的逐字拼音按文件顺序拼接成整串（如 `你好→nihao`），
   引擎按整串精确匹配候选词，天然区分多音字在词中的实际读音。
3. **收录范围**：
   - 多音字相关词语（`pinyin.txt` 全覆盖）；
   - 常用词：`large_pinyin.txt` 中 jieba 词频 ≥ 150 的（补足不含多音字的常用词，如"学习/世界"）；
   - 单字：`kXHC1983.txt` 全部汉字及其全部读音；
   - 词条拼音长度 ≤ 18（与拼音缓冲上限一致）。
4. **词频**：词取自 jieba 词频；单字取 jieba 单字条目词频（缺失按 1）。
5. 引擎内另有一张"常用音节首字调节表"（`PinyinEngine.CHAR_RANK_OVERRIDES`），
   修正 jieba 单字词频把"向/见"等虚词排前的问题，可按个人习惯修改后重新编译。

## 复现性

当前入库词典可由 `tools/build_dict.py` + 上述四个上游文件精确复现
（已用 `diff` 验证），便于将来按需调整词库规模或阈值。
