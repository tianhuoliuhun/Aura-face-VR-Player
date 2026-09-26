# Aurelia Migration Status

> 本文件是 AI 每次启动的必读文件之一（见 `AI_MIGRATION_PLAN.md` §28）。
> 每完成一个 Task 必须更新，不得留空。

## Current Task

P0-01 Repository Audit

## Phase

Phase 0 — 项目审计

## Status

DONE（2026-09-16）

## Completed

| Task | 名称 | 结果 |
| --- | --- | --- |
| P0-01 | Repository Audit | ✅ 完成，产出 `MIGRATION_AUDIT.md` + `DEPENDENCY_MAP.md` |

## Changed Files

**Phase 0 不修改任何业务代码 —— `app/` 下零改动。**

| 文件 | 类型 | 说明 |
| --- | --- | --- |
| `AI_MIGRATION_PLAN.md` | 新增 | 迁移计划落盘（40 节全文 + §41 本仓库落地说明） |
| `MIGRATION_STATUS.md` | 新增 | 本文件 |
| `MIGRATION_AUDIT.md` | 新增 | Phase 0 交付物 1：规模 / 功能盘点 / 耦合量化 / Core 可抽取性 / 偏差 / 风险 |
| `DEPENDENCY_MAP.md` | 新增 | Phase 0 交付物 2：依赖拓扑 / 逐能力映射 / 平台 API 对照 / 可复用资产 |
| `.workbuddy/tmp/audit_scan.py` | 新增（临时） | 规模统计脚本，可复现 |
| `.workbuddy/tmp/audit_deps.py` | 新增（临时） | 依赖面扫描脚本，可复现 |

## Tests

N/A — Phase 0 为只读审计，无代码变更，不需构建验证。
审计起止各执行一次 `git status --short`，均为空（确认未污染工作区）。

## Performance

Not Started — 未做性能测量。已在 `MIGRATION_AUDIT.md` §8/R-08 登记「无性能基线」缺口，
建议 Phase 3 起建立（计划 §32 设备矩阵）。

## Risks

| ID | 风险 | 等级 | 状态 |
| --- | --- | --- | --- |
| R-01 | 本机 Harmony 工具链缺失（DevEco Studio 已卸载，仅剩 `hdc.exe`） | **BLOCKER**（针对 Phase 2+，不影响 Phase 1） | **待用户决策** |
| R-02 | sherpa-onnx 仅 Android AAR，Harmony 需自编译 C/C++ | HIGH | 待 POC（Phase 7 前） |
| R-03 | Harmony 人脸 Provider（Vision / HiAI）可用性未验证 | HIGH | 待 POC（Phase 5 前） |
| R-04 | Android 侧无 C++ 层，Harmony native 渲染管线需新写 | MEDIUM | 已登记 |
| R-05 | 计划 §10 的 GPUPixel 路线与仓库现状（v102 已移除）冲突 | MEDIUM | 已登记，待决策 |
| R-06 | 字体 40.76MB + ASR 模型 228MB 包体压力 | MEDIUM | 已登记 |
| R-07 | `VRPlayerScreen.kt` 5,321 行单文件、~110 个 state，回归风险高 | MEDIUM | 已登记 |
| R-08 | 无性能基线数据 | LOW | 已登记 |
| R-09 | Compose BOM 2024.09 偏旧 | LOW | 已登记 |
| R-10 | 67 个 prefs 键无 schema 版本 | LOW | 已登记 |

完整证据与处置建议见 `MIGRATION_AUDIT.md` §8。

## Decisions

| ID | 决策 | 依据 | 状态 |
| --- | --- | --- | --- |
| D-01 | Phase 0 只产出文档，`app/` 零改动 | 计划 §15 禁止条款 | 已执行 |
| D-02 | 仓库名 / APK 产物名 / 包名 / namespace 本轮不改名 | 改名会打断发布链路与存量用户升级（计划 §41.2） | 已记录 |
| D-03 | 不引入 Room；Repository 抽象从现有 67 个 SharedPreferences 键迁移 | 实测代码中 Room 零使用（`MIGRATION_AUDIT.md` §6.2） | **待用户确认** |
| D-04 | Core `FaceLandmarks` 建议采用「语义关键点集 + 可选全点位」而非仅 `points[]` | 现有实现只产出 12 个语义点（§6.3） | **待用户确认** |
| D-05 | 美颜路线建议以现有 GLSL 移植为主，GPUPixel 降级为备选 | v102 已移除 GPUPixel（§6.1） | **待用户确认** |
| D-06 | Core `DecoderEngine` 建议只保留 EXO，移除 MPV 幽灵项 | MPV 零实现（§6.4） | **待用户确认** |
| D-07 | 字体合规需补齐「官方许可原文入库」 | 当前仅有应用内声明条目（§7） | **待用户确认** |

## Next Task

**建议顺序**（依 `MIGRATION_AUDIT.md` §9.2，按依赖深度从浅到深）：

```
P1-06/P1-07  VRProjection / StereoMode     ← 无依赖，可立即开始（🟢）
P1-08        Subtitle Model                 ← 无依赖，纯 Kotlin 可直接搬（🟢）
P1-03        FaceLandmarks                  ← 需先决策 D-04
P1-04        FaceDetector Interface
P1-05        BeautyEngine Interface         ← 需先决策 D-05
P1-10        Repository Interface           ← 需先决策 D-03
P1-01/P1-02  Player Interface + State       ← 依赖最多，放最后
P1-09        ASR Interface                  ← 需先明确 R-02 处置
```

**当前状态：STOP（计划 §38 要求）** —— 等待用户确认 D-03 ~ D-07 与 R-01 处置方式后再启动 Phase 1。
