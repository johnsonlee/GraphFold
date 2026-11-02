# Kotlin generated-member source locations

Seven generated-code categories each have an uncontrolled and a controlled counterpart (14 cases).
These checked-in base/head revisions support `KotlinGeneratedSourceLocationTest`.
Every change replaces one numeric literal. The test independently hard-codes its
one-based, UTF-16, end-exclusive source coordinates and checks the complete set of
reported BASE and HEAD locations. Repositories are created and committed through
JGit, without invoking the Git executable.

| Case | Compiled structure verified | Expected result |
|---|---|---|
| noncapturing_lambda | LambdaMetafactory site, zero captured arguments, verified static implementation target | FAIL, line 5, columns 27–28 |
| capturing_lambda | LambdaMetafactory site, one captured argument, verified static implementation target | FAIL, line 5, columns 35–36 |
| default_parameter | `calculate$default`, synthetic flag and delegation | FAIL, line 4, columns 32–33 |
| property_accessor | `getValue()I`, explicitly not flagged synthetic | FAIL, line 4, columns 28–29 |
| generic_bridge | erased `map(Object): Object`, bridge and synthetic flags | FAIL, line 5, columns 58–59 |
| suspend_state_machine | Continuation signature, ContinuationImpl, label, switch and invokeSuspend | FAIL, line 9, columns 24–25 |
| inline_body | arithmetic copied into `run`, no call to `calculate` | FAIL, line 4, columns 53–54 |
| controlled_lambda | lambda structure survives; changed branch folds away | PASS; reason anchors line 4, columns 54–55 |
| controlled_default | default stub exists; changed default branch folds away | PASS; reason anchors line 3, columns 59–60 |
| controlled_accessor | accessor exists; changed branch folds away | PASS; reason anchors line 3, columns 55–56 |
| controlled_captured_lambda | one captured argument; only changed arithmetic operand folds away | PASS; reason anchors line 4, columns 62–63 |
| controlled_bridge | erased bridge remains; typed implementation's changed branch folds away | PASS; reason anchors line 4, columns 85–86 |
| controlled_suspend | real suspension/resume remains; changed resumed arithmetic operand folds away | PASS; reason anchors line 8, columns 51–52 |
| controlled_inline | inline arithmetic remains; changed inline branch folds away | PASS; reason anchors line 3, columns 80–81 |

The suspension fixture also executes the head class: calling `run` returns
`COROUTINE_SUSPENDED`; its completion has not run; explicitly resuming the stored
continuation with 10 completes with 12. This verifies actual suspension and resume,
not merely a method whose descriptor contains Continuation.

Generated code is not synonymous with `ACC_SYNTHETIC`. The assertions distinguish
default stub/bridge flags from ordinary property accessors and coroutine classes.
Kotlin 2.1.20 indy lambda implementation methods are private static final and do not
carry `ACC_SYNTHETIC`; their bootstrap handle owner, invocation kind, method descriptor
and captured arguments are verified instead. Source ownership may conservatively remain a CLASS
obligation; that never permits findings outside the actual source diff.

These are source-location regression fixtures, not a statistical accuracy dataset.
