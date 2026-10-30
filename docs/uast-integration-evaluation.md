# Headless UAST 与 GraphFold 引擎整合评估

验证状态：隔离整合评估完成。UAST 已通过受测的完整检测链路、定位及安装分发验证，但候选分支覆盖率尚未达到 95%，不能直接替换主工程。主工程仍使用 JavaParser/headless Kotlin PSI。

## 范围与验收方式

目标是在现有 Git diff → 编译 → 源码义务 → Graphite fold → IR 比较 → verdict 链路中，使用 UAST 同时处理 Java/Kotlin，并保留源码文件、行和列的报告契约。只成功创建 UFile 不构成完整整合验收。

主工程保留现有 JavaParser/headless Kotlin PSI 实现，候选工程位于 `/tmp/graphfold-uast-integration`。候选沿用相同检测 fixture 和断言；源码定位与分类的断言不得因更换前端而放宽。分别记录源码测试、完整检测 corpus、CLI、Gradle plugin、编译器分发、生命周期和质量门禁结果。候选源码以 [可应用补丁](experiments/uast-integration.patch) 保存，另附 [复现步骤](experiments/README.md) 和 [文件哈希清单](experiments/uast-integration.manifest.json)，避免实验仅存在临时目录中。

## 已确认的运行时约束

- 主工程构建编译器固定为 Kotlin 2.1.20，运行在 JDK 17。
- 候选 UAST 使用 Android Lint 31.11.0。其内部编译器版本实际为 `2.2.0-Beta2-for-lint`，配置语言/API 等级 2.1 不等于使用 Kotlin 2.1.20 编译器。
- 原 `kotlin-compiler-embeddable` 使用 `org.jetbrains.kotlin.com.intellij.*`，Lint 使用 `com.intellij.*`；两者的 PSI 不能直接互传，编译器类也不能任意混放。
- 实际探测中，Lint 虽提供 `K2JVMCompiler` 类，调用后却因缺少 `org/jetbrains/kotlin/backend/common/phaser/PhaseBuildersKt` 失败。该分析用 artifact 不能直接承担 JVM 代码生成。

候选因此使用一套 Lint/UAST 分析运行时，并将原 Kotlin 2.1.20 编译器及依赖打包为嵌套资源，在独立 JVM 中执行编译。这样保留生成字节码的编译器版本，同时隔离同名 Kotlin compiler 类；代价包括分发体积、编译进程及资源提取开销。尚未进行符合项目规范的多图性能验收，不给出性能结论。

本次候选构建的 engine JAR 约 57 MiB，内含 8 个编译器运行时 JAR 及 classpath 索引；嵌套资源未压缩大小共 64,199,808 字节。这是当前分发/磁盘占用记录，不是吞吐量或性能验收证据。分析运行时版本与用于生成被分析字节码的版本分别固定，升级时必须同时重验解析、代码生成和分发。

## 候选接入方式

`UastSession` 基于公开 Lint `Project` API 配置源码根、JDK 和 classpath，不依赖原型中的内部 `ManualProject`。Java/Kotlin 共享 UAST 的类、方法、字段、调用及条件遍历。物理源码范围取自 `sourcePsi.textRange`，不以合成 `javaPsi` 的文本位置代替原文件范围。

源码定位仍须结合 JVM 符号：Java 采用解析后的 JVM 描述符；Kotlin 保留 ASM 的 SourceFile、方法描述符和行表关联。UAST 不提供所有 lowering 的完整反向映射。Kotlin 生成声明、`@JvmName`、无行表声明或同一行重载不能唯一关联时，仍采用显式的类级验证义务。

候选串行化 UAST session 的使用，关闭 session 时释放项目，保留共享的 IntelliJ application，避免一个分析销毁其他会话的全局环境。当前按文件建立 session，尚未实现按真实 Gradle 模块复用，因此不能把该候选描述为大型仓库的最终部署方案。

## 当前实测记录

| 检查 | 已观察到的结果 | 意义 |
| --- | --- | --- |
| 独立 headless 原型 | Java/Kotlin UFile、方法、条件、调用和 sourcePsi 范围通过；两语言 Math.abs 可 resolve | 验证命令行可用，不代表完整引擎整合 |
| 候选编译 | 修正 UField.javaPsi 的具体类型处理后可编译 | UAST 的 javaPsi 不统一保证 PsiField 静态类型 |
| 第一轮前端测试 | 62 项，60 通过、2 失败 | Kotlin object init 的分类不符合现有契约 |
| Kotlin object init 修复及集成复验 | engine 90、CLI 9、Gradle plugin 11 项均通过，无失败/跳过；detekt 与 detektTest 通过 | UAST 构造器体需要关联到物理 KtAnonymousInitializer，不能直接套用 Java 静态块模型 |
| 候选 Kotlin 完整 corpus | 186/186 符合预期，所有案例无 diagnostic | 使用真实 Git、编译、UAST、Graphite fold、IR 比较链路；仅代表本回归集 |
| 候选 Java 首轮 corpus | 第 21 项 anonymous callback 因新增源码诊断而失败 | 无名匿名类不可当作无法解析限定名的普通命名类；须保留外层义务和实际生成类行为 |
| 修复后 Java 完整 corpus | 130/130 符合预期，所有案例无 diagnostic | 仅跳过外层义务已覆盖的匿名 UAST 声明，不跳过生成类行为；命名局部类无法绑定时仍诊断 |
| 生成代码精确定位 | Java 8、Kotlin 14，共 22 项通过；每类均有正反案例 | 对实际编译结构及 base/head 文件、行列断言；包含真实 suspend/resume |
| 修复后定向复验 | engine 50、CLI 9、plugin 13 项通过，无失败/跳过；detekt、detektTest、plugin 覆盖率门禁通过 | engine 50 项包含 Java corpus、22 定位、9 Java 索引、5 session 和 13 escape 测试 |
| 安装后的 CLI | Java/Kotlin 各正反一例，共 4 次分析通过 | 从 installDist 启动，验证退出码、verdict、零诊断及精确行列；Kotlin 编译器嵌套资源实际可用 |

首轮完整 `check --continue` 中，engine 共 279 项、6 项失败：Java corpus、两项匿名 callback 定位，以及三项使用错误 SYNTHETIC 标志假设的 Kotlin lambda 测试。CLI 9 项和 plugin 11 项通过，但 plugin 分支覆盖率 50% 未达到门禁。修复共享 plugin 测试环境后，plugin 13 项及其门禁已通过。首轮其余 engine 测试通过；后续只重跑受改动影响的集合，没有重复执行未受 Java 适配修正影响的 Kotlin corpus。不能把这些分轮证据表述为候选已经取得一次完整 `check` 全绿。

安装分发复验的 [脚本](experiments/uast-installed-smoke.py) 和 [四例结果](experiments/uast-installed-smoke-results.json) 随文保存。脚本中的 Git 命令只用于构造一次性开发 fixture；被验证的 CLI 运行时使用 JGit。

### 覆盖率门禁与分发修正

将首轮完整测试与修复后定向测试的 JaCoCo 执行数据合并，并针对候选当前编译 class 生成诊断报告：分支 **1599/1709 = 93.5635%**，行 **1880/1898 = 99.0516%**。以该数据执行 95% 分支门禁实际失败。这是合并的诊断数据，不是一次标准全量 `check` 通过的证明；候选仍有明确的采纳阻碍。记录见 [验证摘要](experiments/uast-validation.json)。主工程的独立门禁结果见 [主工程验证记录](validation.md)。

生成该报告还发现，默认 JaCoCo 输出目录包含资源，导致内嵌第三方 compiler JAR 被扫描，并因多版本 kotlin-reflect 的同名 class 冲突而失败。候选把 Report 与 Verification 的 classDirectories 明确设为 `sourceSets.main.output.classesDirs`。该设置仍覆盖全部 engine 生产 class，不排除任何生产类或分支；只把资源中打包的第三方编译器与被测生产代码区分开。

## 源码映射的评估要求

必须分别检验未解析名字、lambda、桥接方法、默认参数、访问器及 suspend 等编译产物。测试不仅检查 finding 存在，还应断言 base/head 的文件与起止行列，并检查编译产物确实具有案例声称的结构。生成的匿名类不一定带 ACC_SYNTHETIC；简单 suspend 方法也不一定生成状态机。

本轮明确区分两件事：finding 的 diff 片段位置精确，并不自动证明源码声明到每个 synthetic 方法的关联也是精确的一对一映射。类级 fallback 的位置可以精确，但其证明范围仍然较宽。独立 Kotlin 2.1.20 编译还确认 indy lambda 实现可带 PRIVATE/STATIC/FINAL 而不带 SYNTHETIC；fixture 因此验证 bootstrap implementation handle、真实方法 descriptor 与捕获参数，不依赖错误的标志假设。

## 可复现的验证步骤

先按 [补丁说明](experiments/README.md) 在隔离目录应用与当前主工程快照匹配的补丁。首次完整检查使用：

```sh
./gradlew check :engine:detektTest :cli:installDist --continue --console=plain
```

修复后的定向复验选择 Java corpus 方法及以下测试类，另执行 CLI/plugin 测试、engine lint、plugin 覆盖率门禁和安装任务：

```sh
./gradlew :engine:test \
  --tests '*DetectionCorpusTest.real git Java Cypher and folded IR achieve the detection threshold' \
  --tests '*JavaGeneratedSourceLocationTest' --tests '*KotlinGeneratedSourceLocationTest' \
  --tests '*JavaSourceIndexTest' --tests '*UastSessionTest' --tests '*ExternalObjectEscapeTest' \
  :cli:test :gradle-plugin:test :engine:detekt :engine:detektTest \
  :gradle-plugin:jacocoTestCoverageVerification :cli:installDist --console=plain
```

全套 corpus 共 316 个场景，位于两个 JUnit 测试内，不与 JUnit 项数混加。结果只适用于受测的 JDK 17、固定版本和当前 fixture；不声称真实业务仓库的一般精确率或性能达标。

## 精度边界与取舍

| 问题 | UAST 带来的帮助 | 仍然存在的 gap / trade-off |
| --- | --- | --- |
| Java/Kotlin 分类一致性 | 共享类、方法、字段、调用、条件的遍历入口 | object init、默认值、属性等仍需要语言特有 PSI 语义；UAST 形状不能直接套用 Java 假设 |
| 源码精确位置 | 物理 sourcePsi 的 UTF-16 范围可与 diff 相交 | 同一方法多个改动的行为归因仍需要来源证据；单变更坐标测试不证明一般因果归属 |
| 名字无法解析 | 在完整源码/classpath 下可解析引用与声明 | 缺模块模型、生成源或依赖时不能猜签名；无法建立完整验证义务仍必须 FAIL |
| lambda / synthetic | 能找到原始表达式/声明，有利于组织生成来源 | 仍须结合 invokedynamic handle、实际 JVM descriptor、嵌套类及编译元数据；没有完整 lowering 反向映射 |
| 跨版本生成名称变化 | 可提供原始源码声明作为配对线索 | lambda/匿名类序号不是稳定身份；当前保守比较可能误拦编号漂移 |
| inline / suspend | 提供原始函数与调用的物理位置 | 调用点展开、状态机需要多对多来源；SMAP/行表不等于列级来源 |
| 模块隔离 | Lint 支持配置项目与模块 | 候选仍合并源码根与依赖，未复现任意多模块 source set、friend path、编译插件及生成源配置 |
| 生命周期与分发 | headless 可在 CLI、引擎及 plugin 运行 | 全局 IntelliJ 状态、串行 session、嵌套编译器资源需维护；当前验证不构成大型仓库资源预算承诺 |

因此，是否采用 UAST 应分别判断“前端统一是否通过回归”和“行为来源关联是否充分”。替换解析前端不会自动消除现有 IR 结构比较、入口/动态行为模型和生成来源的限制。主工程保持现有实现，直到候选完成同等检测、定位、分发与质量门禁验证；不得以独立 UFile 探测成功代替这些验收。

## 采纳建议

技术上可以在 headless 环境中将 UAST 接入现有引擎，保留 Graphite 建图/折叠和 Jimple 比较；本轮已经通过两语言的全部受测 corpus、精确定位和安装版 CLI。当前不建议直接切换主工程：先补齐候选生产分支测试并获得一次标准全量 `check` 通过，再把实际多模块构建模型与 session 生命周期纳入验收。

若目标是进一步提高检测精度，优先投入源码到生成成员/IR 的多对多来源、跨 revision 的生成符号对齐，以及多个 diff 片段的因果归属。UAST 能统一源码访问，但不能替代这些工作。大型仓库的资源与并发取舍还需要符合项目规范的代表性多图负载验证，本轮没有提供性能验收结论。
