# 保留执行结构的 negative fixtures

这 12 个案例分别提供 Java 与 Kotlin base/head 源码，统一使用 `fold.yml` 将 `Ab.enabled("feature")` 折叠为 false，预期均为 PASS、无 violation。`DetectionCorpusTest` 自动读取共享 manifest，执行真实 Git diff、构建、Cypher 折叠与判定；阻断诊断不算通过。

变化位于仍会执行的方法、Stream、callback 或循环内部；不能依靠删除整个外层结构来证明等价。每对源码都存在真实代码差异。此组仅证明固定 PR_K=false 下的行为保持，不声称 treatment 行为等价，也不覆盖跨线程调度或未知外部 callback。

| ID | 不变量与覆盖边界 |
| --- | --- |
| `negative_local_stream_filter` | Stream filter 继续执行；仅 treatment predicate 阈值变化。 |
| `negative_local_stream_map` | Stream map 和 control 映射保留；仅 treatment 映射变化。 |
| `negative_local_stream_condition_added` | 保留 filter，仅 treatment 分支增加 conjunction 条件。 |
| `negative_local_stream_condition_removed` | 保留 filter，仅 treatment 分支删除 conjunction 条件。 |
| `negative_local_direct_callback` | callback 本身仍创建并调用；仅其内部 treatment 分支变化。 |
| `negative_local_captured_callback` | 捕获的 offset 在 control 路径仍被使用；只改变内部受控加数。 |
| `negative_local_optional_map` | Optional map 在非空 control 输入仍调用；空值 fallback 保留。 |
| `negative_local_shared_helper` | helper 同时用于普通与 treatment 调用点；helper 内部自身的 gate 保护变更。 |
| `negative_local_sideeffect_order` | gate 前后的共同可观察副作用继续执行且顺序不变。 |
| `negative_local_finally` | try 与 finally 保留；control 返回值和 finally 副作用不变。 |
| `negative_local_loop_mutation` | 循环、迭代次数及普通累加保留；仅循环内部受控 mutation 变化。 |
| `negative_local_stream_reduce` | 终结操作 reduce 保留；二参数 callback 的 control 累加保持一致。 |

## 独立检查

48 份 revision 源码均通过 Java 17 或 Kotlin 2.1.20 编译。另以测试替身将 `Ab.enabled` 固定为 false，针对每个语言/案例执行 base/head：集合输入 `[-2, 0, 3, 12]`，标量输入 5，捕获偏移 3；比较返回值和 `StringBuilder` 副作用，24 对结果均一致。这只是独立样本核验；完整检测是否通过由 `DetectionCorpusTest` 的执行结果决定。
