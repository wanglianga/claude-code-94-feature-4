# 物流园冷链车辆预约入场与温控异常协同服务

基于 **Java 17 + Spring Boot 3 + PostgreSQL** 的 0-1 工程，覆盖冷链车辆「预约 → 窗口生成 → 闸口核验 → 月台接车 → 温度/卸货/质检 → 异常多方协同 → 处置定责结算 → 客户签收追溯」全流程。

**feature-4（本次新增）**：车辆入园后，平台把预约单、车辆/司机、冷机设备、舱门传感器、电子铅封、温度曲线与卸货批次关联为**一条冷链事件时间线**；温度超阈、数据断点、铅封异常、卸货扫描与预约批次不一致时**自动冻结该批次入库结算**并创建可并行补证的**异常工单**；园区/承运商/质检/客户**分角色补证**（版本留痕、不覆盖原始数据）；复盘区分**传感器离线/真实超温**、**合并异常区间**、支持**部分可收/部分拒收**与**客户-质检冲突升级审批**；系统按**责任归属/可收数量/合同阈值/客户选择**计算赔付候选，**审批链完整后才写入结算**，驳回或证据替换自动撤销重算；最终输出**一页可审计异常时间线**，并保证重复回调、并发补证、重试**不产生重复赔付或重复冻结**。

---

## 原始需求

> 开发物流园冷链车辆预约入场与温控异常服务，可使用 Java、Spring Boot 和 PostgreSQL。承运商提交车牌、司机、货主、货品类型、温区要求、预约时间、预计件数、车厢温度设备和电子封签后，服务根据月台容量、质检人员班次和客户优先级生成入场窗口。车辆到园时，闸口核验预约、车牌和封签，月台接车后上传开门前温度、卸货照片、破损件数和质检抽样结果。若车辆晚到、温度曲线断点、封签异常、货主临时改仓、月台拥堵或质检发现化冻，服务需要让承运商、园区调度、质检、客户客服和结算人员在同一车次中处理。货物放行、隔离、降级入库或拒收后，责任判断会影响承运商扣罚、客户赔付和后续预约优先级。服务还要保留温控设备原始数据、卸货时长和签收结果，便于客户追溯某批冷链商品的运输质量。服务还要兼顾多货主混装、夜间排队、司机证件过期、客户临时加急和园区限电。调度人员需要看到每辆车卡在闸口、月台、质检、隔离还是结算环节，客户客服则需要把温控异常解释成可理解的责任、货损和预计放行时间。

---

## 技术栈与运行形态

- Spring Boot 3.3（Web / JDBC / Security / Validation），JdbcTemplate 直写 SQL，无额外重型依赖。
- PostgreSQL 16（仅 compose 内网，**不发布到宿主**）。
- 多阶段 Dockerfile：Maven 构建 → JRE 运行；**非 root 用户** + **HEALTHCHECK**。
- 鉴权：HTTP Basic + 六类角色 `CARRIER / DISPATCH / QC / CS / SETTLE / CUSTOMER`，无状态。
- 库表由 `schema.sql` 自动建表、`data.sql` 自动播种（幂等，可重复启动）。
- **OpenAPI 3.x + Swagger UI**：`/swagger-ui.html`（免登录可浏览文档，调接口需 Basic 认证）。
- **一页可审计异常时间线**：`/audit.html`（输入账号口令与车次号即可查看审计视图）。

## 快速启动（宿主 docker compose 一键部署）

```bash
cp .env.example .env        # CC_PUBLISH_PORT 已由验证环境注入，也可自行修改
docker compose up -d --build
# 健康检查（start_period 约 90 秒，首次需 Maven 拉依赖+构建镜像）
curl -s http://localhost:${CC_PUBLISH_PORT}/api/health
```

只发布应用端口：`ports: ["${CC_PUBLISH_PORT}:8080"]`；数据库仅在 compose 网络内通过服务名 `db` 访问。

验证实际映射端口（并行任务端口不冲突）：

```bash
docker compose port app 8080
# 浏览器/接口访问： http://host.docker.internal:<映射端口>/api/health
```

停止并释放资源：

```bash
docker compose down
```

> 演示“晚到 / 夜间排队”等时间场景：在 `.env` 设置 `PARK_CLOCK=2026-09-18T13:00:00`（园区基准时钟，按真实流逝推进）；留空则使用容器当前时间。

## 测试账号（逐角色）

| 用户名 | 密码 | 角色 | 权限/可执行操作 |
|---|---|---|---|
| `carrier1` | `carrier123` | 承运商 CARRIER（顺丰冷链） | 提交/查看本公司预约、上传温控设备原始数据、设备/铅封/舱门回调、补传设备原始日志与停靠说明、查看车次时间线与追溯 |
| `carrier2` | `carrier123` | 承运商 CARRIER（京东冷链） | 同上 |
| `dispatch` | `dispatch123` | 园区调度 DISPATCH | 闸口核验放行、改约、货主改仓、限电开关、异常闭环定责、排队放行、调度看板、补传闸口/卸货记录、工单结案解冻 |
| `qc` | `qc123` | 质检员 QC | 月台卸货登记、质检抽样、处置决定、卸货扫描回调、复检结论补证、人工复盘分类 |
| `cs` | `cs123` | 客户客服 CS | 查看客户可理解的异常解释、客户签收、运输质量追溯 |
| `settle` | `settle123` | 结算员 SETTLE | 责任金额测算预览、赔付候选提交/审批、确认扣罚/赔付、落地承运商信用分调整 |
| `customer1` | `customer123` | 客户 CUSTOMER（盒马鲜生） | 时限内对本货主批次选择继续收货/降级收货/拒收（支持部分可收部分拒收） |
| `customer2` | `customer123` | 客户 CUSTOMER（蒙牛乳业） | 同上 |
| `customer3` | `customer123` | 客户 CUSTOMER（永辉超市） | 同上 |

## 演示主数据

- 货主与客户优先级：`盒马鲜生 P0`、`蒙牛乳业 P1`、`永辉超市 P2`。
- 承运商：顺丰冷链（信用分 100）、京东冷链（85）。
- 司机：张志强（证件有效）、**李大山（证件 2026-01-15 已过期，用于演示证件拦截）**、王海涛。
- 月台：`D01` 冷冻/夜间/自备发电、`D02` 冷藏/无备电、`D03` 常温/夜间、`D04` 冷藏/夜间/自备发电、`D05` 冷冻/无备电。
- 质检班次：工作日 08:00-18:00（4 人）、周末 09:00-18:00（3 人）、每日 20:00-24:00 夜班。
- 园区参数：夜间 22:00-06:00、晚到容忍 30 分钟、窗口 30 分钟、温度断点阈值 45 分钟、限电开关默认关。
- feature-4 参数：异常区间合并窗口 15 分钟（`MERGE_WINDOW_MINUTES`）、客户收货决定时限 48 小时（`CUSTOMER_DECISION_HOURS`）。
- 货主合同阈值（赔付计算依据）：盒马 30 分钟/容差 1.0℃/降级折价 50%，蒙牛 45 分钟/1.5℃/40%，永辉 60 分钟/2.0℃/30%。
- 冷机设备台账：`RF-SF-001`（顺丰）、`RF-JD-002`（京东），心跳数据用于区分传感器离线/真实超温。

## 车次状态机（环节）

```
BOOKED ──闸口核验──> AT_GATE ──月台接车──> AT_DOCK ──卸货完成──> IN_QC
  │                     │                     │
  │(封签/证件/晚到/     │              质检无化冻│有化冻
  │ 拥堵/限电)          ▼                     ▼
  └──────────────> QUEUED_AT_GATE      AWAIT_DISPOSITION / QUARANTINED
                     (调度处置后放行)            │ 处置决定(放行/隔离/降级/拒收)
                                                 ▼
                                            SETTLING ──结算确认──> DONE ──客服签收
```

调度看板 `/api/board/overview` 直接给出每辆车的 `currentStage`（闸口/月台/质检/隔离/结算）与卡点原因。

## 需求点 → 实现映射

| 需求 | 实现 |
|---|---|
| 月台容量约束生成窗口 | `SchedulingService` 按 30 分钟窗口检查同月台时间重叠 |
| 质检人员班次 | 窗口必须被 `qc_shift` 覆盖，同窗在班车次 ≤ 当班人数 |
| 客户优先级 | P0/P1/P2 + 承运商信用分 + 加急 50 分合成 `priority_score`，加急插队、排队按优先级 |
| 多货主混装 | 一次预约多条 `cargoLines`（不同货主/温区），主温区取最严格，逐货主质检/处置/结算 |
| 夜间排队 | 22:00-06:00 仅排 `night_open` 月台，登记排队事件 |
| 司机证件过期 | 预约即登记 `DOCS_EXPIRED` 高优异常，闸口拦截排队 |
| 客户临时加急 | `urgent=true` 提分插队，登记 `URGENT_INSERT` |
| 园区限电 | 限电开关后仅排 `backup_power` 月台，已约车辆批量生成 `POWER_LIMIT` 异常需改约 |
| 闸口核验预约/车牌/封签 | `/api/gate/checkin`，车牌不符直接拦截，封签异常排队待调度核验 |
| 晚到 | 超窗口结束+30 分钟判定，登记 `LATE_ARRIVAL`，调度改约闭环 |
| 月台拥堵 | 月台在园占用时新到车进入闸口排队，入园自动闭环拥堵异常 |
| 温度曲线断点 | 上传原始温度点，相邻间隔 >45 分钟标记 `gap_after` 并生成 `TEMP_GAP` |
| 开门前温度/卸货照片/破损件数 | `/api/dock/trips/{code}/unload`，温度落到每条货主明细 |
| 质检抽样化冻 | `/api/qc/trips/{code}/sample`，化冻自动转 `QUARANTINED` 隔离 |
| 同一车次多方协同 | `incident` + `trip_event` 时间线，五角色共享，`/api/incidents/trips/{code}` |
| 货主临时改仓 | `/api/dispatch/trips/{code}/warehouse-change` 登记 `WAREHOUSE_CHANGE` |
| 放行/隔离/降级/拒收 | `/api/disposition/trips/{code}/decide`（可按货主明细分别处置） |
| 责任→扣罚/赔付/优先级 | 结算按异常责任归集（承运/园区/货主/多方），扣罚+客户赔付+承运商信用分下调（直接影响后续窗口优先级） |
| 温控原始数据/卸货时长/签收追溯 | `/api/trace/trips/{code}` 全量原始温度点、断点、卸货时长、质检、处置、结算、签收 |
| 调度环节看板 | `/api/board/overview`、`/api/board/incidents` |
| 客服可理解解释 | `/api/cs/trips/{code}/explanation` 输出客户语言的责任/货损/预计放行时间 |

## feature-4：冷链事件时间线与异常协同结算

### 关联模型（一条时间线）

预约单（`trip`+`trip_batch` 预约批次）关联：车辆/司机（`trip.vehicle_plate`/`driver`）、冷机设备（`reefer_device`+`device_heartbeat`）、舱门传感器（`door_event`）、电子铅封（`seal_event`）、温度曲线（`temp_reading`）、卸货批次扫描（`unload_scan`）。`GET /api/audit/trips/{code}` 将全部来源合并为按时间排序的统一事件流。

### 自动冻结与异常工单

| 触发 | 检测 | 冻结范围 |
|---|---|---|
| 温度超阈 | 连续超阈点构成区间，相邻区间间隔 ≤15 分钟自动**合并为一个异常区间** | 全车批次 |
| 数据断点 | 相邻温度点间隔 >45 分钟 | 全车批次 |
| 铅封异常 | 铅封回调 TAMPERED/BROKEN | 全车批次 |
| 扫描不一致 | 扫描批次号预约外（EXTRA）或数量不符（MISMATCH） | 预约外→全车批次；数量不符→该批次 |

冻结即 `settlement_freeze(batch_id, reason)`（**唯一约束**：重复回调/重试不重复冻结，解冻后再异常可重新冻结）；同时创建 `work_order`（`dedup_key` 唯一：同车次+类型+区间只建一单）。批次存在 FROZEN 冻结时，`/api/settle/trips/{code}/confirm` 一律 409 阻断。

### 分角色补证（版本留痕，不覆盖原始数据）

| 角色 | 可提交证据类型 |
|---|---|
| 承运商 CARRIER | `DEVICE_LOG` 设备原始日志、`STOP_NOTE` 停靠说明 |
| 园区 DISPATCH | `GATE_RECORD` 闸口记录、`UNLOAD_RECORD` 卸货记录 |
| 质检 QC | `QC_RECHECK` 复检结论 |
| 客户 CUSTOMER | `CUSTOMER_DECISION` 收货决定（专用端点，时限 48h 内，仅本货主批次） |

证据表只增不改：同（工单,角色,类型）每次提交版本 +1，旧版本置 `active=false` 并挂 `supersedes` 链，**版本号、提交人、时间全留痕**；结案后仍可补充证据（触发重算）。**证据被替换 → 自动撤销未生效结算草稿（DRAFT/IN_APPROVAL→REVOKED）并按新版本重算**。

### 复盘规则

- **传感器离线 vs 真实超温**：承运商补传 `DEVICE_LOG`（JSON `{"readings":[{"at","celsius"}]}`）后系统自动判定——区间内日志温度均不超阈 → `SENSOR_OFFLINE`；存在超阈 → `REAL_EXCURSION`；无日志覆盖但设备心跳离线 → `SENSOR_OFFLINE`。质检/调度亦可人工 `/classify` 改判。
- **多区间合并**：检测时按合并窗口（15 分钟）自动合并，`merged_count` 记录合并的原始区间数。
- **部分可收/部分拒收**：客户决定按批次给出 `acceptQty`+`rejectQty`（必须等于预约数量），`ACCEPT` 不允许有拒收件，`DOWNGRADE/REJECT` 必须有拒收件。
- **冲突升级**：客户拒收但质检合格（PASS）、或客户照收但质检化冻（THAWED）→ 工单自动 `ESCALATED`，赔付审批链追加质检复核环节（QC→DISPATCH→SETTLE 三级；无冲突为 DISPATCH→SETTLE 两级）。

### 赔付候选与审批链

计算四要素：**责任归属**（结案工单归集，多方 SHARED 减半）、**可收数量**（客户决定）、**合同阈值**（货主合同允许累计超温分钟；阈值内且非真实超温 → 温度因素赔付豁免）、**客户选择**（继续收货 0 / 降级折价 / 拒收全值）。
纪律：`payout(batch_id, calc_version)` 唯一；**审批链完整（全部环节 APPROVED）才 EFFECTIVE 并允许写入结算**；审批驳回 → 该单 REJECTED 并自动撤销重算新版本；证据替换 → 未生效草稿一律 REVOKED 重算；已 EFFECTIVE 的赔付永不重算（不重复赔付）。结算确认时客户赔付取生效赔付单合计。

### 一页可审计异常时间线

`GET /api/audit/trips/{code}`（或浏览器打开 `/audit.html`）：预约/车辆/司机/设备关联、批次与扫描比对、温度曲线统计与断点位置、每个异常断点的**证据版本链/客户决定/冻结状态/赔付状态/责任结论**、结算结果、**后续预约影响**（承运商信用分→预约优先级），以及全来源统一时间线。

### 幂等与并发设计

- 回调幂等：温度点 `(trip_id, recorded_at)` 唯一；舱门/铅封 `event_uid` 唯一；扫描 `scan_uid` 唯一；通用 `idempotency_key`；重复回调返回 `duplicated=true` 且无副作用。
- 冻结/工单幂等：`settlement_freeze(batch_id, reason)` 与 `work_order.dedup_key` 唯一约束 + `ON CONFLICT`。
- 并发补证：PostgreSQL 事务级咨询锁按（工单,角色,类型）串行化版本分配，版本唯一连续；唯一约束兜底。
- 并发重算：按批次咨询锁串行化「撤销-重插」，任意并发下每批次至多 1 条未生效草稿；赔付生效用状态守卫（`IN_APPROVAL→EFFECTIVE` 单语句）保证只生效一次。

## 主要接口（均需 Basic 认证，除 `/api/health`）

| 方法 & 路径 | 角色 | 说明 |
|---|---|---|
| POST `/api/appointments` | CARRIER | 提交预约，返回含系统生成窗口的车次 |
| GET `/api/appointments/{code}` | 全部 | 车次详情（货物/异常/时间线/温度原始数据） |
| GET `/api/my/trips` | 全部 | 承运商看本公司，其它角色看全部 |
| POST `/api/gate/checkin` | DISPATCH | 闸口核验（车牌/封签，晚到/拥堵/限电判定） |
| GET `/api/gate/queue` | DISPATCH | 闸口排队队列（按加急+优先级） |
| POST `/api/dock/trips/{code}/receive` | DISPATCH | 月台接车，卸货计时开始 |
| POST `/api/dock/trips/{code}/temperature` | CARRIER | 上传温控设备原始数据（断点/超限自动识别） |
| POST `/api/dock/trips/{code}/unload` | QC | 开门前温度、卸货照片、破损件数 |
| POST `/api/qc/trips/{code}/sample` | QC | 质检抽样结果（化冻自动隔离） |
| POST `/api/dispatch/trips/{code}/incidents/{id}/resolve` | DISPATCH | 异常闭环并定责 |
| POST `/api/dispatch/trips/{code}/warehouse-change` | DISPATCH | 货主临时改仓 |
| POST `/api/dispatch/trips/{code}/rebook` | DISPATCH | 重新生成入场窗口（晚到/限电/拥堵处置） |
| POST `/api/dispatch/trips/{code}/admit-queue` | DISPATCH | 排队车辆处置后放行入园 |
| POST `/api/dispatch/power-limit` | DISPATCH | 园区限电开关 |
| GET `/api/dispatch/queue` | DISPATCH | 排队队列 |
| GET `/api/board/overview` | DISPATCH/QC/SETTLE | 环节看板（每辆车卡在哪） |
| GET `/api/board/incidents` | DISPATCH/QC/SETTLE | 全量未闭环异常台 |
| POST `/api/disposition/trips/{code}/decide` | QC/DISPATCH | 放行/隔离/降级/拒收 |
| GET `/api/settle/trips/{code}/preview` | SETTLE | 责任与扣罚/赔付测算 |
| POST `/api/settle/trips/{code}/confirm` | SETTLE | 结算确认，调整信用分，车次 DONE |
| GET `/api/cs/trips/{code}/explanation` | CS | 客户语言的责任/货损/预计放行时间 |
| POST `/api/signoff/trips/{code}` | CS | 客户签收（SIGNED/PARTIAL/REJECTED） |
| GET `/api/trace/trips/{code}` | CS/CARRIER/DISPATCH/SETTLE | 运输质量全量追溯档案 |
| GET `/api/trace/owners/{ownerId}/trips` | 同上 | 按货主查批次 |
| POST `/api/callbacks/temperature` | CARRIER/DISPATCH | 冷机设备直推温度（幂等键去重，自动检测超温合并区间/断点→冻结+工单） |
| POST `/api/callbacks/heartbeat` | CARRIER/DISPATCH | 设备心跳（区分传感器离线/真实超温的依据） |
| POST `/api/callbacks/door` | CARRIER/DISPATCH | 舱门传感器事件（eventUid 幂等） |
| POST `/api/callbacks/seal` | CARRIER/DISPATCH | 电子铅封事件（TAMPERED/BROKEN 自动冻结全车批次） |
| POST `/api/callbacks/unload-scan` | QC/DISPATCH | 卸货扫描（与预约批次比对，不一致自动冻结） |
| GET `/api/work-orders` | 全部角色 | 异常工单列表（tripCode/status 过滤） |
| GET `/api/work-orders/{id}` | 全部角色 | 工单详情（证据版本链/客户决定/冻结） |
| POST `/api/work-orders/{id}/evidence` | CARRIER/DISPATCH/QC | 分角色补证（版本化，不覆盖历史） |
| POST `/api/work-orders/{id}/classify` | QC/DISPATCH | 人工复盘分类（传感器离线/真实超温） |
| POST `/api/work-orders/{id}/customer-decision` | CUSTOMER | 客户时限内收货决定（部分可收/部分拒收，冲突自动升级） |
| POST `/api/work-orders/{id}/resolve` | DISPATCH | 工单结案定责、解冻批次、触发赔付重算 |
| GET `/api/payouts?tripCode=` | 全部角色 | 车次赔付单（含审批链） |
| POST `/api/payouts/trips/{code}/compute` | SETTLE/DISPATCH | 重算赔付候选（撤销未生效草稿） |
| POST `/api/payouts/{id}/submit` | SETTLE | 提交审批（生成审批链，升级单追加质检复核） |
| POST `/api/payouts/{id}/approve` | 当前环节角色 | 审批通过（链完整自动生效） |
| POST `/api/payouts/{id}/reject` | 当前环节角色 | 审批驳回（自动撤销重算） |
| GET `/api/audit/trips/{code}` | 全部角色 | 一页可审计异常时间线（证据/处置/责任/赔付/后续预约影响） |
| GET `/audit.html` | 免登录页面 | 审计时间线可视化页（页面内输入账号口令） |
| GET `/swagger-ui.html` | 免登录 | OpenAPI 3.x 文档与在线调试 |

## 一键端到端验证脚本

```bash
# 需宿主机有 curl/python3；CC_PUBLISH_PORT 为应用宿主端口
export CC_PUBLISH_PORT=$(docker compose port app 8080 | cut -d: -f2)
bash scripts/smoke.sh            # 预约→闸口→月台→质检→处置→结算→签收→追溯 全流程
bash scripts/smoke_feature4.sh   # feature-4：冻结/补证/复盘/赔付审批/幂等并发
```

`smoke.sh` 依次跑通：① 多货主混装正常车全流程；② 温度断点+超限+化冻车（隔离→降级→异常工单闭环→赔付审批→承运商全责扣罚赔付→信用分下调→客服解释）；③ 封签异常闸口排队→调度核验闭环→放行；④ 证件过期车闸口拦截；⑤ 限电开关后仅自备发电月台可排窗；⑥ 调度看板与异常台。

`smoke_feature4.sh`（53 项断言）依次跑通：
- **场景A**：预约声明批次 → 温度回调（两段超温合并为一个区间+断点）→ 铅封 TAMPERED → 扫描数量不符/预约外批次 → 4 类原因自动冻结 3 批次并建 5 个工单；重复回调/重试后冻结与工单数量不变；6 路并发补证版本链 1..6 仅最新有效；设备日志自动判定真实超温/传感器离线；客户部分拒收与质检 PASS 冲突自动升级；冻结中结算被 409 阻断；结案解冻；赔付候选 0/800/480 精确命中；4 路并发重算每批次恰 1 条草稿；证据替换撤销重算；驳回自动重算；三级审批链走完生效；结算写入赔付合计 1280；审计页五段断点证据链完整。
- **场景B**：断点+设备日志在阈内 → 判定传感器离线 → 合同阈值内赔付豁免（0 元），标准两级审批链。
- **场景C**：8 路并发相同温度回调全局只落 2 条数据点、不产生重复工单。

## 本地开发验证（实际执行过的验证）

```bash
# 本地 PostgreSQL（任意可连实例）+ Maven 3.8+
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/coldchain
mvn clean package -DskipTests
PARK_CLOCK=2026-09-30T10:00:00 java -jar target/coldchain.jar
CC_BASE_URL=http://localhost:8080 python3 scripts/smoke_feature4.py   # 53 项全过
CC_BASE_URL=http://localhost:8080 python3 scripts/smoke.py            # 43 项全过（回归）
```

## 目录结构

```
├── Dockerfile                 # 多阶段构建 + 非 root + HEALTHCHECK
├── docker-compose.yml         # app + postgres（仅发布 app 端口）
├── .env.example
├── pom.xml
├── scripts/
│   ├── smoke.sh / smoke.py                # 基础业务流冒烟（43 项）
│   └── smoke_feature4.sh / smoke_feature4.py  # feature-4 冒烟（53 项，含幂等/并发）
└── src/main/
    ├── resources/
    │   ├── application.yml
    │   ├── schema.sql         # 表结构（含冻结/工单/证据/赔付/审批/幂等键）
    │   ├── data.sql           # 角色账号/主数据/合同阈值/设备台账
    │   └── static/audit.html  # 一页可审计异常时间线页面
    └── java/com/logpark/coldchain/
        ├── config/            # Spring Security 六角色 + OpenAPI
        ├── support/           # 时钟、异常、全局错误
        ├── repo/Db.java       # 通用数据访问 + 事务级咨询锁
        ├── service/           # 排程/预约/闸口/月台质检/调度/处置结算/客服/看板/追溯
        │                      # + Detection(异常检测冻结) / Callback(设备回调)
        │                      # + Evidence(补证复盘) / Payout(赔付审批) / Audit(审计时间线)
        └── web/               # REST 控制器
```
