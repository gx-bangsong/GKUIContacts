# 分组 Chips + 中文姓氏快速索引 —— 整体方案设计

> 目标仓库：`gx-bangsong/GKUIContacts`（LineageOS 23.2 / AOSP Contacts fork）
> 分支：`arena/019f9ed7-gkuicontacts`
> 本文只含设计，不含最终实现代码；确认后再分功能落地。

---

## 0. 现状勘察结论（决定了后续所有取舍）

我先把仓库的真实情况核对了一遍，几个关键事实和你模板里的"待填写"项对上了：

| 项目 | 实际情况（已核实） |
|---|---|
| 基线 | AOSP Contacts，LineageOS 23.2 分支 |
| 语言 | **Java**（`src/**/*.java` 346 个文件，`Android.bp` 里 `*.kt` 已配但当前无 Kotlin 源码） |
| SDK | `AndroidManifest.xml`：`minSdkVersion 36` / `targetSdkVersion 36`（Android 16）；`Android.bp` 写的是 `sdk_version: "system_current"`, `min_sdk_version: "21"` |
| UI 框架 | **View + Material Components**（`com.google.android.material_material` 已在 `static_libs`），已在用 `Snackbar` / `TextInputLayout`。**没有 Compose** |
| 主题 | `PeopleActivityTheme` → `PeopleThemeAppCompat` → `Theme.AppCompat.Light.DarkActionBar`。**注意：目前不是 MD3 主题**，深色适配靠 `res/values-night/colors.xml` + `android:forceDarkAllowed=true` + `@*android:color/system_neutral1_*` 动态取色 |
| 主列表 | `DefaultContactBrowseListFragment` → `contact_list_content.xml` → `PinnedHeaderListView`（**ListView，非 RecyclerView**），`android:fastScrollEnabled="true"` 用的是 **framework 原生 FastScroller** |
| 索引来源 | `ContactsProvider` 通过 `Contacts.EXTRA_ADDRESS_BOOK_INDEX` 返回 `EXTRA_ADDRESS_BOOK_INDEX_TITLES/COUNTS`，`ContactEntryListAdapter.updateIndexer()` 直接喂给 `ContactsSectionIndexer` |
| 搜索 | `ActionBarAdapter` 里的 `EditText`（`search_bar_expanded.xml`）→ `setQueryString` → `DefaultContactListAdapter.configureLoader()` 走 `Contacts.CONTENT_FILTER_URI` |
| 分组 | `GroupListLoader`（`Groups.CONTENT_SUMMARY_URI`）已存在，目前只被抽屉 `DrawerFragment` 使用；点击走 `onGroupViewSelected(GroupListItem)` → `PeopleActivity.onGroupViewSelected` 打开 **独立的 `GroupMembersFragment`** |
| 分组过滤 | `ContactListFilter.FILTER_TYPE_GROUP_MEMBERS` 已存在但**只带 account 信息、不带 groupId**（groupId 走的是另一条 Fragment 路径） |
| 单测 | `tests/` 是 `android_test` instrumentation（`ContactsTests`），**没有 Robolectric/JVM 单测配置** |

### 这带来两个必须先说清楚的架构决策

**决策 A：索引不能只靠 Provider 的 `EXTRA_ADDRESS_BOOK_INDEX`。**
Provider 端的分节字母是 `ContactsProvider2` 用 ICU `AlphabeticIndex` 算的，它对多音姓（单/解/仇…）一律按常用读音走 —— 这正是你要修的问题，而 Provider 在系统 APK 里，我们不能改。
→ **方案：在 App 侧重算索引**。加载完成后用 `SurnameIndexBuilder` 遍历 cursor，用 `SurnamePinyinResolver` 生成每行的 indexKey，重新排序 + 重建 `ContactsSectionIndexer`，替换掉 Provider 给的那份。排序也必须在 App 侧做（否则"单 Shàn"排在 D 段但索引说 S，位置对不上）。

**决策 B：主列表是 ListView，原生 FastScroller 的气泡样式不可控（无法做 MD3 放大气泡、无法显示汉字）。**
→ 关掉 `fastScrollEnabled`，自绘一个 `SurnameIndexBarView`（自定义 View，覆盖在列表右侧的 FrameLayout 里），自己处理触摸 → `ListView.setSelectionFromTop()`，气泡用同一个 View 的 overlay 画。这样 A/B 两种展示形态都能支持，且 MD3 配色、深色、横竖屏全可控。

---

## 1. 功能 1：分组（Label）Chips

### 1.1 挂载位置

`contact_list_content.xml` 的结构是：

```
LinearLayout(vertical)
 ├─ alert_container            (同步提示条)
 ├─ include account_filter_header
 └─ FrameLayout #contact_list  (weight=1)
     ├─ include contact_list_card
     ├─ SwipeRefreshLayout → PinnedHeaderListView
     └─ ProgressBar
```

搜索框在 Toolbar 里（`ActionBarAdapter` 把 `search_bar_expanded` 塞进 `Toolbar`），所以"搜索框与联系人列表之间"= **`account_filter_header` 之后、`FrameLayout#contact_list` 之前**。

```xml
<!-- contact_list_content.xml 新增 -->
<include layout="@layout/account_filter_header" />
<include layout="@layout/group_chips_bar" />   <!-- ← 新增，默认 visibility=gone -->
<FrameLayout android:id="@+id/contact_list" ...>
```

`group_chips_bar.xml` = `HorizontalScrollView` + `com.google.android.material.chip.ChipGroup`（`app:singleLine="true"`, `app:selectionRequired="true"`, `app:singleSelection="true"`）。

> 只改 `contact_list_content.xml`，**不改** `contact_picker_content.xml` / `join_contact_picker_list_content.xml`，所以选择器/合并联系人界面不受影响。

### 1.2 数据流

```
GroupListLoader (已存在, Groups.CONTENT_SUMMARY_URI)
      │  复用，不新造数据模型
      ▼
GroupChipsController  ← 新增
      │  · 过滤 deleted / 空 FFC 组（复用 GroupUtil.isEmptyFFCGroup）
      │  · 构造 chips：[全部] + 每个 GroupListItem
      │  · 单选，选中态 chipBackgroundColor = secondaryContainer
      ▼
onGroupChipSelected(groupId)
      ▼
DefaultContactBrowseListFragment.setGroupChipFilter(groupId)
      ▼
DefaultContactListAdapter.configureLoader()   ← 注入 groupId 约束
```

### 1.3 与搜索共存的关键实现

这是本功能里唯一需要动 `configureLoader()` 的地方。现有 `configureLoader` 有三条分支：

- **非搜索 + 无分组**：现状不变。
- **非搜索 + 有分组**：`loader.setUri(Data.CONTENT_URI)` + `MIMETYPE=GroupMembership AND GROUP_ROW_ID=?`，projection 用 `getDataProjectionForContacts()`（该方法已存在，DEVICE_CONTACTS 分支在用）。等价于 `GroupMembersAdapter.configureLoader()` 的做法。
- **搜索 + 有分组**（组内搜）：同上 URI/selection，再 `AND (DISPLAY_NAME_PRIMARY LIKE ?1 OR DISPLAY_NAME_ALTERNATIVE LIKE ?1)`。这正是现有 `isGroupMembersFilter()` 分支的写法，直接沿用其模式即可，不引入第二套搜索。

> 注意：组内搜索走的是 `LIKE` 而不是 `CONTENT_FILTER_URI`，会丢掉 Provider 的拼音/T9 匹配能力。**这是现有 `isGroupMembersFilter()` 分支既有的行为**，我保持一致（不在本次改动里扩大范围）；如果你希望组内也能拼音搜，我可以在功能 1 落地时额外加一层"用 `CONTENT_FILTER_URI` 取 contactId 集合，再与组成员 id 求交集"的实现 —— 需要你点头，因为会多一次查询。

### 1.4 状态与边界

| 场景 | 行为 |
|---|---|
| 分组列表为空 | 整个 chips 栏 `View.GONE`（`GroupListLoader` 返回 0 行时） |
| 只有"全部" | 也隐藏（没有可选项没意义） |
| 进入搜索模式 | chips 栏**保留可见**，选中的分组继续生效（= 组内搜）。这与 Google Contacts 一致 |
| 抽屉里选了某分组 | 走原有 `GroupMembersFragment` 路径，chips 栏不参与（两条路径不打架）。可选：让 chips 与抽屉选中态同步，作为 P2 |
| 旋转/进程重建 | `groupId` 存进 `onSaveInstanceState`（key `groupChipFilterId`），随 Fragment 恢复 |
| 分组增删改 | `GroupListLoader` 是 `CursorLoader`，自动 observe `Groups` URI，chips 自动刷新 |

### 1.5 MD3 配色的现实处理

仓库主题是 AppCompat，不是 `Theme.Material3.*`。直接写 `?attr/colorSecondaryContainer` 会编译不过（属性未定义在当前主题链）。三个选项，**我推荐 (2)**：

1. 把 `PeopleThemeAppCompat` 的 parent 换成 `Theme.Material3.DayNight.*` —— 干净但**风险大**，会连带改变全 App 的 ActionBar/Dialog/列表样式，超出本次范围。
2. **给 chips 单独套 ThemeOverlay**：`ThemeOverlay.Material3.*` 应用到 `group_chips_bar` 这一层（`android:theme="@style/ThemeOverlay.Contacts.GroupChips"`），只在 chips 局部启用 MD3 配色。颜色值挂到 `@color/group_chip_*`，`values/` 用 `@*android:color/system_accent1_100` 系动态取色、`values-night/` 给深色值 —— 与仓库现有做法（`lineage_colors.xml`、`values-night/colors.xml`）一致。
3. 纯手写 `ColorStateList` —— 最简单但不算 MD3。

选 (2) 的话，`secondaryContainer` 语义色映射：
- 亮色：`@*android:color/system_accent1_100`（容器）/ `system_neutral1_900`（文字）
- 深色：`@*android:color/system_accent2_700` / `system_accent1_100`

---

## 2. 功能 2：中文姓氏快速索引

### 2.1 核心模块 `SurnamePinyinResolver`

纯 Java、**零 Android 依赖**（构造函数吃 `InputStream`/`Reader`，不吃 `Context`），便于单测。

```java
package com.android.contacts.pinyin;

public final class SurnamePinyinResolver {

    /** 一个名字的索引结果 */
    public static final class IndexKey {
        public final String bucketLabel;   // 索引条上显示的东西: "S" 或 "单 Shàn"（取决于 UI 方案）
        public final char   latinInitial;  // 'S'，恒为 A-Z 或 '#'
        public final String sortKey;       // "SHAN|单某某" 用于排序，稳定且可比较
        public final Source source;        // COMPOUND / POLYPHONIC / PINYIN / NON_CJK / UNKNOWN
        public final String surname;       // 识别出的姓氏原文 "单" / "欧阳"，非中文为 null
        public final String pronunciation; // "shan" / "ouyang"
    }

    public enum Source { COMPOUND, POLYPHONIC, PINYIN, NON_CJK, UNKNOWN }

    public IndexKey resolve(String displayName);
}
```

#### 算法（严格按你给的优先级）

```
resolve(name):
  0. normalize: trim、全角→半角、去掉 "·"/"•"/空白 分隔符前的处理保留
  1. 空 / 全非字母数字            → '#', NON_CJK
  2. 首字符非 CJK 统一表意文字     → 走既有规则:
        A-Za-z → 大写首字母, NON_CJK
        其他   → '#',        NON_CJK
  3. 取前 2 字 → 查【复姓表 compound_surnames】
        命中 → pronunciation = 表中读音, source=COMPOUND, 结束
        （复姓表按"最长匹配优先"，为将来 3 字复姓预留）
  4. 取前 1 字 → 查【多音姓氏表 polyphonic_surnames】
        命中 → pronunciation = 表中"姓氏读音", source=POLYPHONIC, 结束
  5. 取前 1 字 → 查【基础拼音表 base_pinyin】(常用汉字→拼音首字母)
        命中 → source=PINYIN, 结束
  6. 兜底：android.icu.text.Transliterator "Han-Latin/Names" (API 29+, 我们 min36 安全)
        成功 → source=PINYIN；失败 → '#', UNKNOWN
```

第 5/6 步的"底层拼音能力"就是**约束 3 要求的复用点**，见 §2.5。

#### 优先级顺序的一个坑（已处理）

"欧阳" vs 多音姓"区(Ōu)"：因为复姓先查，`欧阳修` → COMPOUND `ouyang` → O，正确。
"单于"（复姓 Chányú）vs "单"（Shàn）：**复姓表里必须收 `单于 → chanyu`**，否则 `单于某` 会被第 4 步吃成 Shàn。同理 `尉迟(Yùchí)` vs `尉(Yù)`、`乐正(Yuèzhèng)` vs `乐(Yuè)`。复姓表优先解决了这类，但要求复姓表**收全**，这是数据质量问题，已在初始数据里覆盖。

#### 一个容易被忽略的歧义源：DISPLAY_NAME_ALTERNATIVE

用户可能把排序设成"姓在后"（`SORT_ORDER_ALTERNATIVE` / `DISPLAY_ORDER_ALTERNATIVE`，`ContactsPreferences` 里有）。
→ `SurnameIndexBuilder` 必须读**当前生效的那一列**（`Contacts.SORT_KEY_PRIMARY` 对应 `DISPLAY_NAME_PRIMARY`），而中文联系人 Provider 存的 `DISPLAY_NAME_PRIMARY` 就是"姓在前"的原串，取首字即姓。对 `ALTERNATIVE`（"某某, 单"）需要先按 `", "` 切出姓。这个分支我会在 `resolve()` 外层的 `SurnameIndexBuilder` 里处理，`resolve()` 本身只认"姓在前"。

### 2.2 数据表：外置、可维护

放 **`res/raw/`**（不放 assets：`res/raw` 能被 `values-zh-rCN` 等限定符覆盖，也能被 ROM overlay 覆盖，运维更友好；且 `tests/` 能直接 `openRawResource` 读同一份）。

```
res/raw/surnames_compound.csv      复姓表
res/raw/surnames_polyphonic.csv    多音姓氏表
res/raw/pinyin_initials.txt        基础汉字→拼音首字母（兜底表，可选，见 §2.5）
```

**格式：CSV，`#` 开头为注释，UTF-8。** 选 CSV 不选 XML 的理由：一行一条、diff 友好、外部脚本/表格可直接编辑，符合"便于后续补充"。

#### 复姓表 `surnames_compound.csv`

```csv
# surname,pinyin,initial,note
# pinyin 用无声调小写全拼；initial 冗余存一份便于人工校对（解析时以 pinyin 首字母为准）
欧阳,ouyang,O,
司马,sima,S,
诸葛,zhuge,Z,
上官,shangguan,S,
皇甫,huangfu,H,
令狐,linghu,L,      # 令 此处读 Líng 不读 Lìng
尉迟,yuchi,Y,       # 尉 此处读 Yù 不读 Wèi
万俟,moqi,M,        # 关键：不读 Wànsì
澹台,tantai,T,      # 关键：不读 Dàntái
慕容,murong,M,
独孤,dugu,D,
公孙,gongsun,G,
长孙,zhangsun,Z,    # 长 此处读 Zhǎng
单于,chanyu,C,      # 关键：与单姓 Shàn 区分
乐正,yuezheng,Y,    # 与乐姓 Yuè 一致但需独立收录以免被单字表截断
宇文,yuwen,Y,
夏侯,xiahou,X,
东方,dongfang,D,
西门,ximen,X,
南宫,nangong,N,
北堂,beitang,B,
司徒,situ,S,
司空,sikong,S,
呼延,huyan,H,
太史,taishi,T,
端木,duanmu,D,
拓跋,tuoba,T,       # 拓 此处读 Tuò
赫连,helian,H,
completed…（落地时给全量 ~80 条）
```

#### 多音姓氏表 `surnames_polyphonic.csv`

```csv
# char,surname_pinyin,initial,common_pinyin,confidence,note
# surname_pinyin = 作姓氏时的读音（我们用这个）
# common_pinyin  = 该字最常用读音（仅注释用途，不参与计算）
# confidence     = certain | ambiguous   ← ambiguous 的走 §2.4 默认策略
单,shan,S,dan,certain,单雄信
解,xie,X,jie,certain,解缙
曾,zeng,Z,ceng,certain,曾国藩
仇,qiu,Q,chou,certain,仇英
查,zha,Z,cha,certain,查良镛
区,ou,O,qu,certain,区家麟
华,hua,H,hua,certain,华罗庚（去声，首字母同）
朴,piao,P,pu,certain,朴槿惠
乐,yue,Y,le,certain,乐毅
折,she,S,zhe,certain,折可适
翟,zhai,Z,di,certain,翟姓多读 Zhái
盖,ge,G,gai,certain,盖姓读 Gě
繁,po,P,fan,certain,繁姓读 Pó
过,guo,G,guo,certain,
应,ying,Y,ying,certain,
任,ren,R,ren,certain,任姓读 Rén（阳平）
纪,ji,J,ji,certain,纪姓读 Jǐ
种,chong,C,zhong,certain,种师道
牟,mu,M,mou,ambiguous,牟 Mù/Móu 均有
覃,qin,Q,tan,ambiguous,★Qín(广西壮族多)/Tán(湖南等) — 见 §2.4
member…（落地时给全量 ~60 条）
```

#### 解析与缓存

`SurnameTableLoader`（Android 侧，吃 `Context`）→ 读 `res/raw` → 解析成两个 `HashMap<String,String>` → 交给纯 Java 的 `SurnamePinyinResolver`。
`SurnamePinyinResolver` 通过 **静态单例 + 懒加载** 提供给 App（`SurnamePinyinResolver.getInstance(context)`），单测里走 `new SurnamePinyinResolver(reader, reader)` 直接注入，不碰 Android。

### 2.3 索引 key 的异步计算与缓存（约束 2）

三级缓存，避免滑动时解析：

```
① 进程内 LRU（热路径）
   ConcurrentHashMap<String /*surname 片段*/, IndexKey>
   key 是"姓"不是"全名"，命中率极高（几千联系人通常只有几百个不同姓）
   → 单次 resolve 本身就是两次 HashMap 查找，加这层后基本零成本

② 每次 load 完成后一次性批算（唯一的批量工作）
   SurnameIndexBuilder.build(cursor) 在 后台线程 跑：
     - 遍历 cursor，取 displayName → resolve() → (latinInitial, sortKey)
     - 用 sortKey 排序，产出 int[] rowMap（原 cursor position 的重排映射）
     - 产出 String[] sections + int[] counts
   → 输出一个不可变的 SurnameIndex 对象

③ 落库缓存（可选，P2）
   若联系人 >2000 条，把 contactId→indexKey 写进 App 私有 SQLite/SharedPref，
   下次冷启动直接读，仅对 CONTACT_LAST_UPDATED_TIMESTAMP 变化的行重算。
   ★ 初版不做，先测 ② 的实际耗时（预估 5000 联系人 < 30ms），够快就不引入。
```

**线程模型**：不用新建线程池。仓库已有 `com.android.contacts.util.concurrent`（`ContactsExecutors`），而且更简单的做法是把批算塞进 `FavoritesAndContactsLoader.loadInBackground()` —— 它本来就在 Loader 后台线程上，算完把结果塞进返回 Cursor 的 `getExtras()`（复用 `EXTRA_ADDRESS_BOOK_INDEX_TITLES/COUNTS` 这两个 key），UI 线程侧 `ContactEntryListAdapter.updateIndexer()` **一行都不用改**就能吃到新索引。

这是整个方案里最优雅的一处：**重排 + 重算索引全部发生在 Loader 后台线程，UI 层无感知。**

```java
// FavoritesAndContactsLoader.loadInBackground() 末尾
Cursor contacts = loadContacts();
if (SurnameIndexFeature.isEnabled(getContext())) {
    contacts = SurnameIndexBuilder.reindex(getContext(), contacts);
    // 返回一个 SortedCursorWrapper：position 重映射 + getExtras() 替换为新的 titles/counts
}
```

`SortedCursorWrapper extends CursorWrapper` 的写法在本仓库有现成先例 —— `GroupMembersFragment.FilterCursorWrapper` 就是干这个的（position 重映射 + `GroupUtil.updateBundle` 改 extras），照抄结构即可，风格一致。

### 2.4 读音歧义的默认策略（覃 Qín/Tán 类）

三层，从粗到细：

**第 1 层 —— 数据表内的 `confidence` 列**
`ambiguous` 的姓，`surname_pinyin` 填的是**方案默认值**，并在 `note` 里注明另一读音。当前默认取该姓在中国大陆人口占比更高的读音（覃 → **Qín**，因广西覃姓人口远多于其他）。

**第 2 层 —— 可配置覆盖（给维护者/ROM 厂商）**
新增 `res/raw/surnames_override.csv`，格式同多音表，**解析优先级最高**（在复姓/多音表之上）。ROM 可以用 RRO overlay 或直接改这个文件，不用碰代码：

```csv
# surnames_override.csv —— 部署方定制，优先级最高
覃,tan,T,,certain,湘鄂地区定制包
```

**第 3 层 —— 给终端用户（可选 P2）**
在"显示选项"（`res/xml/preference_display_options.xml`，仓库已有）加一个入口"中文姓氏读音"，列出所有 `confidence=ambiguous` 的姓，让用户逐个选读音，存 `SharedPreferences`，键 `surname_pron_覃=tan`。Resolver 查询顺序变为：
`用户偏好 → override.csv → 复姓表 → 多音表 → 基础拼音 → ICU`。

> **我建议第 1+2 层随功能 2 一起落地，第 3 层等你确认要不要做 UI。** 第 3 层要新增 Preference 类 + strings，工作量约等于半个功能 1。

### 2.5 复用既有拼音能力（约束 3）

先说结论：**这个仓库的 App 侧目前没有任何拼音/T9 实现**（我 grep 了 `pinyin`/`HanziToPinyin`/`T9`，`src/` 和 `tests/` 零命中）。现有拼音能力全在 **`ContactsProvider2` 侧**（`HanziToPinyin` 在 `packages/providers/ContactsProvider`，App 只是通过 `CONTENT_FILTER_URI` 间接享用）。

所以"避免两套重复实现"落到本仓库，具体是这两条：

1. **搜索继续 100% 走 Provider**。`SurnamePinyinResolver` **只用于索引/排序**，绝不接管搜索。搜索照旧 `Contacts.CONTENT_FILTER_URI`，Provider 的拼音/T9 匹配完整保留。→ 不存在"两套搜索"。
2. **兜底拼音优先用平台 ICU，不自带汉字库**。`android.icu.text.Transliterator.getInstance("Han-Latin/Names")` 从 API 29 起可用，本项目 `minSdk 36`，安全。这样我们自己只维护"姓氏例外表"（复姓 + 多音，共约 140 条），**不维护 2 万字的拼音大表** —— 这才是真正的"不重复实现"。
   `res/raw/pinyin_initials.txt` 因此降级为**可选的 200 字常见姓氏加速表**（避免每次调 ICU），不是必需品；若你想彻底轻量，可以不要这个文件。

   ⚠️ 一个已知风险：官方文档明确说 *"The set of available transliteration IDs is unstable across Android releases and devices"*，要求先 `Transliterator.getAvailableIDs()` 检查。→ 代码里会做可用性探测，不可用时退化到 `pinyin_initials.txt`，再不行归 `#`。

### 2.6 索引条 UI：两方案对比 ★需要你决策★

#### 方案 A —— 显示 A–Z 拼音字母，归类按姓氏正确读音

```
列表：  单 → S 段    解 → X 段    欧阳 → O 段
索引条：# A B C D … S … X … Z     （27 项，纯字母）
分节头：S / X / O
气泡：  放大的 "S"
```

| | |
|---|---|
| ✅ | 索引条项数固定 27，竖屏一屏放得下，横屏更没问题 |
| ✅ | 命中区域大（~28dp/项），拖动精准，无障碍友好 |
| ✅ | 与 Provider 原生索引、与英文联系人天然统一，混合通讯录无缝 |
| ✅ | 改动最小 —— `ContactsSectionIndexer` 完全不用改，只换喂给它的数据 |
| ❌ | 用户看到 "S" 不会意识到"这里的单读 Shàn 已修正"，**功能价值不可见** |
| ❌ | 不符合你"尽量贴近按中文姓氏呈现"的倾向 |

#### 方案 B —— 索引条直接显示中文姓氏字符

```
列表：  单某某 / 解某某 / 欧阳某
索引条：# A…Z(非中文)  单 解 欧阳 …   ← 项数 = 通讯录里不同姓的数量
分节头：单 / 解 / 欧阳
气泡：  放大的 "单"（可副标 Shàn）
```

| | |
|---|---|
| ✅ | 直观，"按中文姓氏"一眼可见，功能价值外显 |
| ✅ | 大通讯录里找"张"比找"Z 段再翻"快 |
| ❌ | **项数不可控**：500 个中文联系人可能有 200+ 个不同姓，竖屏最多容纳约 28 项 → 必须做**动态抽样折叠**（隐藏部分姓、用 `·` 占位，拖动时才展开），实现复杂度是 A 的 3–4 倍 |
| ❌ | 横屏可用高度更小，折叠更激进，体验退化明显 |
| ❌ | 汉字在 12sp 下识别度低于字母；触摸目标小 |
| ❌ | 中英混合通讯录里，索引条会变成 "A B C 陈 邓 …" 的混排，视觉杂乱 |

#### 方案 C（我的推荐）—— A 的骨架 + B 的呈现，二段式

```
索引条：# A B C … Z          ← 27 项，稳定，同方案 A
分节头：S  单 · 沈 · 石       ← 字母 + 该段包含的姓氏（这是 B 的价值）
气泡：   ┌──────┐
        │  S   │            ← 大字母
        │ 单沈石 │            ← 小字：该段的姓氏列表（最多 6 个 + …）
        └──────┘
```

**取舍理由**：索引条本身是"粗定位"控件，27 项是它的物理上限，硬塞汉字只会牺牲可用性；而"按中文姓氏"的信息价值真正需要露出的地方是**气泡和分节头**——用户拖到 S 段时，气泡告诉他"这里是 单/沈/石"，他立刻明白单被正确归到 S 了。既拿到了 B 的全部认知收益，又保留 A 的全部可用性与实现简洁度。

> **我建议选 C。** 如果你坚持"索引条上必须是汉字"，我就实现 B，但会带一个开关 `config_surname_index_style`（`letter` / `hanzi`），默认 letter，让部署方能切 —— 因为 B 在大通讯录上的折叠体验我没法保证一定好。

### 2.7 索引条 UI 实现

```java
// 新增自定义 View，替换原生 FastScroller
public class SurnameIndexBarView extends View {
    // - onDraw: 绘制 27 个 section label，选中项 tint = ?attr/colorPrimary
    // - onTouchEvent: DOWN/MOVE → 命中 index → callback，同时 show bubble
    //                 UP/CANCEL → hide bubble (250ms 淡出)
    // - bubble 用 PopupWindow（避免被列表 clip）或同 FrameLayout 内的兄弟 View
    // - 触觉反馈: performHapticFeedback(CLOCK_TICK) 跨节时
    // - 无障碍: ExploreByTouchHelper 提供 virtual view
}
```

**接线**：
- `contact_list_content.xml` 里 `PinnedHeaderListView` 改 `android:fastScrollEnabled="false"`，在同一 `FrameLayout#contact_list` 内加 `SurnameIndexBarView`（`layout_gravity="end|center_vertical"`）。
- `ContactEntryListFragment.configureVerticalScrollbar()` 里 `setFastScrollEnabled(hasScrollbar)` 改为同时控制新 View 的可见性（保持搜索模式下隐藏的既有行为，`line 803: setFastScrollEnabled(!isSearchMode())`）。
- 数据源 = `adapter.getSections()`（已实现 `SectionIndexer`），滚动定位 = `listView.setSelectionFromTop(adapter.getPositionForSection(i) + headerCount, 0)`。
- **分节头**：`ContactListItemView.setSectionHeader(String)` 与 `ContactListPinnedHeaderView.setSectionHeaderTitle(String)` 已存在，方案 C 只需把传入的 title 从 `"S"` 换成 `"S"` + 副标；副标需要给 `ContactListPinnedHeaderView` 加一个小 TextView（它目前是裸 `TextView`，要改成 `LinearLayout`，注意 `contact_list_section_header_width=56dp` 要放宽）。

**MD3 / 深色 / 横竖屏**：
- 尺寸走 `res/values/dimens.xml` + `res/values-land/`（仓库已有 `values-land`），横屏时项高压缩并允许隐藏偶数项。
- 配色走 `res/values/colors.xml` + `res/values-night/colors.xml`（仓库已有），用 `@*android:color/system_*` 动态取色，与 `lineage_colors.xml` 风格一致。
- 气泡：`MaterialShapeDrawable` + `cornerSize` 走 MD3 shape（`Widget.Material3.*` 的 shape appearance 可以单独引用，不需要换全局主题）。

---

## 3. 单元测试策略（约束 5）

### 3.1 问题：仓库没有 JVM 单测

`tests/` 是 `android_test`（instrumentation，要跑设备/模拟器）。你要求"重点写单元测试"，跑在设备上迭代太慢。

**方案**：新增一个 `android_robolectric_test` 或纯 `java_test_host` 模块。因为 `SurnamePinyinResolver` 被设计成**零 Android 依赖**，最干净的是：

```
tests-unit/                        ← 新增目录
├── Android.bp                     ← java_test_host { name: "ContactsSurnameUnitTests" }
└── src/com/android/contacts/pinyin/
    ├── SurnamePinyinResolverTest.java
    ├── SurnameIndexBuilderTest.java
    └── SurnameTableParserTest.java
```

`java_test_host` 在 AOSP 里跑在 host JVM 上，`atest ContactsSurnameUnitTests` 秒级反馈。数据表用 `test resources` 里的同一份 CSV（构建时 copy 或用 `java_resources`）。

⚠️ 唯一障碍：`resolve()` 的 ICU 兜底分支用 `android.icu`，host 上没有。→ 用**策略接口** `PinyinFallback` 注入，生产实现是 `IcuPinyinFallback`（Android），测试实现是 `NoopPinyinFallback`。核心表匹配逻辑 100% 可 host 测试。

### 3.2 测试用例清单

| 类别 | 用例 | 期望 |
|---|---|---|
| 多音姓 | 单雄信 / 解缙 / 曾国藩 / 仇英 / 查良镛 / 区家麟 / 华罗庚 / 朴槿惠 / 乐毅 / 折可适 | S / X / Z / Q / Z / O / H / P / Y / S，且 `source=POLYPHONIC` |
| 复姓 | 欧阳修 / 司马光 / 万俟卨 / 澹台灭明 / 尉迟恭 / 诸葛亮 / 上官婉儿 / 皇甫嵩 / 令狐冲 / 慕容复 / 独孤求败 / 公孙策 | O/S/**M**/**T**/Y/Z/S/H/L/M/D/G，`source=COMPOUND` |
| 复姓 vs 单字歧义 | 单于某 vs 单某 | C vs S |
| | 乐正某 vs 乐某 | Y vs Y（读音都对，但 `surname` 分别为"乐正"/"乐"） |
| | 长孙无忌 vs 长某 | Z(zhangsun) vs C(chang) |
| 普通姓 | 张三 / 王五 / 李四 / 陈六 | Z / W / L / C，`source=PINYIN` |
| 单字名 | 单 | S |
| 非中文 | Alice / alice / 123 / +8613800138000 / (空) / null / "  " / 😀 / Владимир | A / A / # / # / # / # / # / # / #（西里尔归 # 或按 ICU，需与现有行为对齐 —— 会加断言锁定） |
| 混合 | 张Alice / Tom张 | Z / T |
| 中文标点 | ·单雄信 / 单·雄信 | S / S |
| 歧义策略 | 覃某（默认） | Q |
| | 覃某（override.csv 设 tan 后） | T |
| 排序 | ["单某","陈某","张某"] 排序 | 陈(C) < 单(S) < 张(Z) |
| 表解析 | 注释行 / 空行 / 列数不足 / BOM / CRLF | 跳过且不抛异常 |
| 缓存 | 同一姓调 1000 次 | 只解析 1 次（用 spy 计数） |

约 45 个 case。

---

## 4. 目录树（新增/修改）

```
GKUIContacts/
├── Android.bp                                          [改] 加 tests-unit 引用（如需）
├── docs/design/
│   └── group-chips-and-surname-index-design.md         [新] 本文档
├── res/
│   ├── layout/
│   │   ├── contact_list_content.xml                    [改] 挂 chips 栏 + 索引条, 关 fastScroll
│   │   ├── group_chips_bar.xml                         [新] HorizontalScrollView + ChipGroup
│   │   ├── group_chip_item.xml                         [新] <Chip> 单项模板
│   │   ├── surname_index_bubble.xml                    [新] 气泡（方案 C: 大字母+小姓氏）
│   │   └── contact_list_section_header.xml             [新] 分节头(字母+姓氏副标), 替换裸 TextView
│   ├── raw/
│   │   ├── surnames_compound.csv                       [新] 复姓表 ~80 条
│   │   ├── surnames_polyphonic.csv                     [新] 多音姓表 ~60 条
│   │   ├── surnames_override.csv                       [新] 部署方覆盖表（初始只有注释）
│   │   └── pinyin_initials.txt                         [新/可选] 常见姓加速表 ~200 字
│   ├── values/
│   │   ├── colors.xml                                  [改] group_chip_*, surname_index_*
│   │   ├── dimens.xml                                  [改] chips 栏高度/间距, 索引条宽度/项高
│   │   ├── strings.xml                                 [改] chip "全部", 索引条 a11y 描述
│   │   ├── styles.xml                                  [改] ThemeOverlay.Contacts.GroupChips
│   │   │                                                    Widget.Contacts.GroupChip
│   │   └── donottranslate_config.xml                   [改] config_surname_index_enabled
│   │                                                        config_surname_index_style
│   ├── values-night/colors.xml                         [改] 深色对应色
│   └── values-land/dimens.xml                          [改] 横屏索引条项高
├── src/com/android/contacts/
│   ├── pinyin/                                         [新包]
│   │   ├── SurnamePinyinResolver.java                  [新] 核心，零 Android 依赖
│   │   ├── SurnameTableParser.java                     [新] CSV 解析，零 Android 依赖
│   │   ├── SurnameTables.java                          [新] 不可变表容器
│   │   ├── PinyinFallback.java                         [新] 兜底接口（可注入）
│   │   ├── IcuPinyinFallback.java                      [新] android.icu 实现 + 可用性探测
│   │   ├── SurnameTableLoader.java                     [新] Context → res/raw → Resolver 单例
│   │   ├── SurnameIndexBuilder.java                    [新] Cursor → 重排+sections/counts
│   │   ├── SurnameSortedCursor.java                    [新] CursorWrapper, 仿 FilterCursorWrapper
│   │   └── SurnamePreferences.java                     [新/P2] 用户级读音覆盖
│   ├── list/
│   │   ├── FavoritesAndContactsLoader.java             [改] loadInBackground 末尾接 reindex
│   │   ├── ContactEntryListFragment.java               [改] 索引条可见性接管 fastScroll
│   │   ├── DefaultContactBrowseListFragment.java       [改] chips 挂载 + groupId 状态
│   │   ├── DefaultContactListAdapter.java              [改] configureLoader 加 group 约束
│   │   ├── ContactListPinnedHeaderView.java            [改] 支持姓氏副标(方案 C)
│   │   └── ContactListItemView.java                    [改] 同上
│   ├── group/
│   │   └── GroupChipsController.java                   [新] GroupListLoader → ChipGroup
│   └── widget/
│       └── SurnameIndexBarView.java                    [新] 自绘索引条 + 气泡
├── tests/src/com/android/contacts/
│   ├── pinyin/SurnameTableLoaderTest.java              [新] 需 Context 的部分（instrumentation）
│   └── list/GroupChipsControllerTest.java              [新] instrumentation
└── tests-unit/                                         [新] host JVM 单测
    ├── Android.bp
    └── src/com/android/contacts/pinyin/
        ├── SurnamePinyinResolverTest.java              [新] ★主力，~45 cases
        ├── SurnameIndexBuilderTest.java                [新]
        └── SurnameTableParserTest.java                 [新]
```

统计：**新增 ~24 个文件，修改 ~13 个文件**。功能 1 约占 25%，功能 2 约占 75%。

---

## 5. 落地顺序（确认后分批出代码）

| 批次 | 内容 | 可独立验证 |
|---|---|---|
| **P1** | `pinyin/` 包全部 + `res/raw/*.csv` + `tests-unit/` | ✅ `atest ContactsSurnameUnitTests`，不碰 UI，零回归风险 |
| **P2** | 功能 1 分组 chips（layout + Controller + configureLoader + 资源） | ✅ 装机看 chips 与过滤 |
| **P3** | 功能 2 UI（`SurnameIndexBarView` + Loader 接线 + 分节头改造） | ✅ 装机看索引与气泡 |
| **P4**（可选） | 用户级读音偏好 UI、落库缓存、组内拼音搜 | 按需 |

---

## 6. ★需要你手动处理 / 需要你拍板的事项★

### 6.1 需要你决策（阻塞开工）

| # | 事项 | 我的建议 |
|---|---|---|
| **D1** | **索引条 UI 选 A / B / C** | **C**（27 字母索引条 + 气泡与分节头显示汉字姓氏）。理由见 §2.6 |
| **D2** | MD3 配色如何落地 | **局部 ThemeOverlay**（§1.5 选项 2），不动全局主题 |
| **D3** | 覃 Qín/Tán 默认值 | 默认 **Qín**；`surnames_override.csv` 供部署方改；用户级 UI 列为 P4 |
| **D4** | 组内搜索是否要支持拼音 | 初版**不支持**（与现有 `isGroupMembersFilter` 行为一致）。要的话我加 id 求交集方案 |
| **D5** | 是否新建 `tests-unit` host 测试模块 | **建议建**。否则单测只能跑 instrumentation，迭代慢 |

### 6.2 需要你手动处理（我改不了 / 需要你的环境）

| # | 事项 | 说明 |
|---|---|---|
| **M1** | **依赖库** | 好消息：`com.google.android.material_material` **已在 `Android.bp` 的 `static_libs`**，Chip/ChipGroup 直接可用，**无需新增依赖**。ICU 走平台 `android.icu`，也无需依赖。→ **本方案零新增第三方依赖** |
| **M2** | **Manifest** | **无需改动**。不新增 Activity/Service/Permission。`GroupListLoader` 用的 `READ_CONTACTS` 已声明 |
| **M3** | **布局挂载点** | 我会改 `res/layout/contact_list_content.xml`。**请确认这个文件在你的 ROM 里没有被 device tree 的 overlay 覆盖** —— 如果有 RRO overlay，需要同步改 overlay 那份，否则改动不生效 |
| **M4** | **数据表位置** | `res/raw/surnames_*.csv`。若你的 ROM 想按机型定制，请用 RRO overlay 覆盖同名 raw 资源，或直接改 `surnames_override.csv`。**注意 `res/raw` 里的文件会被打进 APK，不走 proguard，但要确认 `proguard.flags` 不会 shrink 掉 `com.android.contacts.pinyin.*`**（我会补 `-keep`） |
| **M5** | **构建验证** | 我在这个沙箱里**没有 AOSP 构建环境**（无 `soong`/`m`/`atest`），代码我会保证语法正确、API 用法正确，但**最终 `m Contacts` 编译和 `atest` 需要你在本地 AOSP tree 里跑**。我会在每批交付时列出需要验证的命令 |
| **M6** | **Provider 索引开关** | `buildSectionIndexerUri()` 仍会让 Provider 算一份索引（浪费但无害）。若想省这次计算，需要在 `DefaultContactListAdapter.configureUri` 里去掉该参数 —— 但那样 `GroupMembersFragment` 等其他路径会失去索引。**建议保留，让 App 侧覆盖即可** |
| **M7** | **回归测试范围** | 改了 `FavoritesAndContactsLoader` 和 `contact_list_content.xml`，请重点回归：收藏区置顶、"我的名片"header、搜索进出、旋转、多账户过滤、抽屉分组跳转 |
| **M8** | **`SORT_ORDER_ALTERNATIVE`** | 请确认你的产品是否允许用户切"姓在后"。若允许，我会实现 §2.1 里的 ALTERNATIVE 分支；若产品锁死 PRIMARY，可以省掉这部分逻辑和测试 |

### 6.3 已知风险

| 风险 | 缓解 |
|---|---|
| `Transliterator("Han-Latin/Names")` ID 在某些设备不可用（官方明说不稳定） | `getAvailableIDs()` 探测 + 降级到本地表 + 最终降级到 `#` |
| 大通讯录（>5000）重排耗时 | 全在 Loader 后台线程；实测超 100ms 则启用 §2.3 ③ 落库缓存 |
| chips 栏占用垂直空间，小屏列表变短 | 高度 48dp，搜索模式下可选隐藏；提供 `config_show_group_chips` 开关 |
| 与抽屉分组选择的双入口可能让用户困惑 | P2 里做选中态同步，或按你的意见隐藏抽屉分组项 |
| `ContactListPinnedHeaderView` 从 `TextView` 改成容器 | 它被 `IndexerListAdapter.setPinnedSectionTitle` 直接调用，改动面小但要同步改 3 处调用点；方案 A 则完全不用改 |

---

## 7. 一句话总结

- 分组 chips：**纯增量**，复用 `GroupListLoader` + `ContactsContract.Groups`，只在 `contact_list_content.xml` 插一栏、在 `configureLoader` 加一个 group 约束分支。
- 姓氏索引：核心洞察是 **Provider 的索引改不了，所以在 App 侧的 Loader 后台线程重排 + 重建 `ContactsSectionIndexer`**，用现成的 `EXTRA_ADDRESS_BOOK_INDEX_*` 通道回灌，UI 层几乎无感；`SurnamePinyinResolver` 零 Android 依赖、表全外置在 `res/raw`、host JVM 可测。
- 搜索不动，Provider 拼音/T9 完整保留，**不产生第二套拼音实现**（我们只维护 ~140 条姓氏例外表，不维护汉字大表）。

**请回复 D1–D5，我就开始按 P1 → P2 → P3 出代码。**
