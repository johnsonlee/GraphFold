# Kotlin 局部受控 negative fixtures

以下 8 个案例由 `DetectionCorpusTest` 通过 `features.json` 自动运行，预期均为无 violation。每对源码只将受控表达式中的 `2` 改为 `3`；公共 fold 规则将 `sample.Ab.enabled("feature")` 替换为 `false`。外层调用和执行结构在控制场景下保留，不能通过删除整个外层结构来证明等价。

| Case ID（前缀 `kotlin_negative_`） | 保留的执行 | 等价依据 |
|---|---|---|
| `custom_delegate` | 自定义 delegate 的构造、属性读取和 `getValue` 调用 | getter 内部受控分支消失，返回值始终为 1；不改变 delegate 协议 |
| `lazy_initializer` | lazy 对象创建、首次求值、两次属性读取 | initializer 内部受控分支消失，两次读取同一缓存值 1 |
| `default_parameter` | 调用未传可选参数的方法、生成的默认参数桥接和方法体 | 改变的是默认参数声明中的表达式，控制场景下默认值仍为 1；签名和默认参数存在性不变 |
| `companion_initializer` | companion 和类初始化、静态字段读取 | 初始化表达式中的受控分支消失；初始化时序不变，字段值仍为 1 |
| `object_callback` | 匿名对象创建及 `IntSupplier` 调用 | callback 内部的增量仍为 1 |
| `sequence_predicate` | sequence 构造、惰性 filter/map 及终结 sum | predicate 内短路条件在控制场景下为真，存活的 `it > 0` 筛选和映射完全一致 |
| `suspend_lambda` | suspend lambda 创建、`startCoroutine` 和 continuation 回调 | lambda 内部的增量仍为 1；此例没有真正挂起，不声称覆盖调度、恢复或取消 |
| `extension_getter` | 扩展属性 getter 调用 | getter 内部的增量仍为 1；接收者和 JVM 签名不变 |

这些案例已通过定向引擎测试；完整回归结果以 [validation.md](../../docs/validation.md) 为准。自定义 delegate 的元数据 adapter 支持要求运行时与固定标准库一致；真正反射访问仍阻断，不扩大为通用 Kotlin 反射支持。

独立语义校验已执行：Kotlin 2.1.20 / JVM 17 编译全部 16 个 revision，并对每个 revision 以 gate=false/true 各启动独立 JVM，运行输入 `[-1, 0, 1, 2, 3, 4]`。8 对案例均满足 control 输出相同、treatment 至少一个输出不同。独立 JVM 避免 companion 初始化和 lazy 缓存跨场景污染；这项有限输入验证不替代引擎端到端测试或全域等价证明。
