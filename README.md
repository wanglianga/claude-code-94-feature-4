# 物流园冷链车辆预约入场与温控异常协同服务

基于 **Java 17 + Spring Boot 3 + PostgreSQL** 的 0-1 工程，覆盖冷链车辆「预约 → 窗口生成 → 闸口核验 → 月台接车 → 温度/卸货/质检 → 异常多方协同 → 处置定责结算 → 客户签收追溯」全流程，并在 feature-4-b 中提供「冷链事件时间线聚合 → 温度区间复盘（传感器离线 vs 真实超温/多区间合并）→ 异常工单并行补证（四方角色证据版本化）→ 客户逐批次收货决定与冲突升级审批 → 赔付候选方案版本化与审批后生效 → 一页可审计时间线」的异常闭环。

---

## 原始需求

> 开发物流园冷链车辆预约入场与温控异常服务，可使用 Java、Spring Boot 和 PostgreSQL。承运商提交车牌、司机、货主、货品类型、温区要求、预约时间、预计件数、车厢温度设备和电子封签后，服务根据月台容量、质检人员班次和客户优先级生成入场窗口。车辆到园时，闸口核验预约、车牌和封签，月台接车后上传开门前温度、卸货照片、破损件数和质检抽样结果。若车辆晚到、温度曲线断点、封签异常、货主临时改仓、月台拥堵或质检发现化冻，服务需要让承运商、园区调度、质检、客户客服和结算人员在同一车次中处理。货物放行、隔离、降级入库或拒收后，责任判断会影响承运商扣罚、客户赔付和后续预约优先级。服务还要保留温控设备原始数据、卸货时长和签收结果，便于客户追溯某批冷链商品的运输质量。服务还要兼顾多货主混装、夜间排队、司机证件过期、客户临时加急和园区限电。调度人员需要看到每辆车卡在闸口、月台、质检、隔离还是结算环节，客户客服则需要把温控异常解释成可理解的责任、货损和预计放行时间。

---

## 技术栈与运行形态

- Spring Boot 3.3（Web / JDBC / Security / Validation），JdbcTemplate 直写 SQL，无额外重型依赖。
- PostgreSQL 16（仅 compose 内网，**不发布到宿主**）。
- springdoc-openapi：OpenAPI 3.x 文档 `/v3/api-docs`，Swagger UI `/swagger-ui.html`（免认证可打开，调接口时在 Authorize 填 Basic 账号）。
- 多阶段 Dockerfile：Maven 构建 → JRE 运行；**非 root 用户** + **HEALTHCHECK**。
- 鉴权：HTTP Basic + 六类角色 `CARRIER / DISPATCH / QC / CS / SETTLE / SUPERVISOR`，无状态。
- 库表由 `schema.sql` 自动建表、`data.sql` 自动播种（幂等，可重复启动）。

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
| `carrier1` | `carrier123` | 承运商 CARRIER（顺丰冷链） | 提交/查看本公司预约、上传温控设备原始数据、查看车次时间线与追溯 |
| `carrier2` | `carrier123` | 承运商 CARRIER（京东冷链） | 同上 |
| `dispatch` | `dispatch123` | 园区调度 DISPATCH | 闸口核验放行、改约、货主改仓、限电开关、异常闭环定责、排队放行、调度看板 |
| `qc` | `qc123` | 质检员 QC | 月台卸货登记（开门前温度/照片/破损）、质检抽样、放行/隔离/降级/拒收处置决定 |
| `cs` | `cs123` | 客户客服 CS | 查看客户可理解的异常解释（责任/货损/预计放行时间）、客户签收、运输质量追溯 |
| `settle` | `settle123` | 结算员 SETTLE | 责任金额测算预览、赔付候选方案编制/提交/审批后入账 |
| `supervisor` | `super123` | 升级审批主管 SUPERVISOR（运营主管/质量与商务经理） | L1/L2 冲突审批与绑定裁定、大额赔付终审、驳回方案、客户决定时限延期 |

## 演示主数据

- 货主与客户优先级：`盒马鲜生 P0`、`蒙牛乳业 P1`、`永辉超市 P2`。
- 承运商：顺丰冷链（信用分 100）、京东冷链（85）。
- 司机：张志强（证件有效）、**李大山（证件 2026-01-15 已过期，用于演示证件拦截）**、王海涛。
- 月台：`D01` 冷冻/夜间/自备发电、`D02` 冷藏/无备电、`D03` 常温/夜间、`D04` 冷藏/夜间/自备发电、`D05` 冷冻/无备电。
- 质检班次：工作日 08:00-18:00（4 人）、周末 09:00-18:00（3 人）、每日 20:00-24:00 夜班。
- 园区参数：夜间 22:00-06:00、晚到容忍 30 分钟、窗口 30 分钟、温度断点阈值 45 分钟、限电开关默认关。

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

## feature-4-b：冷链事件时间线 · 并行补证 · 复盘升级 · 赔付审批

### 核心对象与自动联动

车辆入园（闸口放行）即自动生成一条 `cold_event`，把**预约单、车辆/司机、冷机设备、舱门传感器、电子铅封、温度曲线、卸货批次**聚合为一条冷链事件时间线。随后：

- 冷机/舱门/铅封回调按 `event_id` **幂等入库**（重复回调、并发推送、重试均去重，不产生重复数据）；冷机温度点同步进统一温度曲线。
- 系统实时做温度复盘：区分 **OFFLINE 传感器离线（数据断点）** 与 **EXCURSION 真实超温**，相邻异常区间按 `INTERVAL_MERGE_MINUTES`（默认 30 分钟）**自动合并**（异类合并为 MIXED，记录 `merged_count`）。
- 命中 温度超阈 / 数据断点 / 铅封告警 / 卸货扫描与预约批次不一致 时：
  - 幂等创建异常工单 `anomaly_ticket`（`business_key` 唯一）；
  - **冻结相关批次的正常入库结算** `settlement_freeze`（温度/铅封冻全部批次，批次不符只冻该批次；按“区间+批次”去重，重复分析不重复冻结）。

### 四方并行补证（角色强约束 + 证据只追加版本化）

| 角色 | 账号 | 只能提交的证据类型 |
|---|---|---|
| 承运商 CARRIER | carrier1/2 | `DEVICE_RAW_LOG` 设备原始日志、`DWELLING_NOTE` 停靠说明 |
| 园区 PARK（DISPATCH） | dispatch | `GATE_RECORD` 闸口记录、`UNLOAD_RECORD` 卸货记录 |
| 质检 QC | qc | `QC_RECHECK` 复检结论（可带逐批次结论） |
| 客户 CUSTOMER（CS 代提交） | cs | `CUSTOMER_DECISION` 继续收/降级/拒收 |

- 任何一方补证**都不能覆盖原始数据**：每次提交生成新版本（`version`），旧版本置 `superseded=true` 但永久保留，含提交人与时间。
- 四方可**并行**补证（各角色版本链独立）。
- 客户在时限（合同 `customer_decision_hours`，默认 24h）内逐批次选择 `ACCEPT / DOWNGRADE / REJECT`，支持**同一批次部分可收/部分拒收**（按 `acceptedPieces`）。超期需主管延期。

### 复盘规则

- **传感器离线 vs 真实超温**：结合承运商设备日志/停靠说明、舱门/铅封事件与质检复检，对区间给出 `SENSOR_OFFLINE / REAL_EXCURSION / INCONCLUSIVE` 结论与责任方（CARRIER/PARK/OWNER/SHARED/NONE）。
- **多个异常区间合并**：相邻（≤30 分钟或重叠）区间自动合并，混合区间单独定责；被更大区间取代的旧区间保留为 `CLOSED/SUPERSEDED` 可审计，其工单迁移到新区间。
- **同一批次部分可收/部分拒收**：赔付仅按拒收/降级损失件计算。
- **客户决定与质检结论冲突**：自动创建 **L1** 升级审批，工单转 `ESCALATED`；主管可在审批中给出**绑定裁定**（逐批次权威结论，落为最终收货决定）。L1 审批人可显式升级 L2。

### 赔付候选方案与审批后生效

- 结算员基于**责任归属、可收数量、合同阈值（赔付率上限/降级折扣/起赔额/L2 金额线）、客户选择**计算候选方案（`GET /api/proposals/cold-events/{eventNo}/calculate`，含未满足项 `blockers`）。
- 方案**版本化**：`generate` 生成 DRAFT，旧的未生效方案置 `SUPERSEDED`；**证据被替换或审批被驳回时自动撤销未生效草稿并重新计算**。
- 生效门控（全部满足才能写入结算）：无 PENDING/REJECTED 升级审批；大额赔付（≥合同 L2 阈值）有通过的 L2 终审；工单全部闭环、责任已判定、无 FROZEN 批次、异常批次均有客户决定。
- 只有审批链完整后 `post` 才写入 `settlement_ledger`（冷链事件唯一）+ 更新车次结算与承运商信用分；**行锁 + 唯一约束保证重复回调、并发补证、重试不会生成重复赔付或重复冻结**，重复 post 幂等返回。

### 一页可审计时间线

`GET /api/audit/trips/{code}` 一页给出：冷链事件要素、每个断点（温度区间/批次/铅封）的**证据、处置、责任结论、赔付状态**，工单逐版本证据、客户决定、升级链、冻结/解冻、赔付方案全版本与正式入账、承运商信用分（后续预约影响），以及 SHA-256 审计指纹。

### feature-4-b 主要接口（均需 Basic，除 Swagger/health）

| 方法 & 路径 | 角色 | 说明 |
|---|---|---|
| GET/POST `/api/cold-events/trips/{code}` | 多角色 | 冷链事件时间线（入园自动建，POST 可补舱门传感器号） |
| GET `/api/cold-events/{eventNo}` | 多角色 | 事件聚合视图（区间/工单/冻结/回调/温度/时间线） |
| POST `/api/callback/{eventNo}/chiller` `/door` `/seal` | CARRIER | 冷机/舱门/铅封回调，`eventId` 幂等 |
| POST `/api/events/trips/{code}/unload-scan` | DISPATCH | 卸货扫描批次与预约核对，不符只冻该批 |
| POST `/api/tickets/{no}/evidence` | 四方 | 提交本角色证据（业务层强校验类型，版本化只追加） |
| POST `/api/tickets/{no}/customer-decision` | CS | 客户逐批次 继续收/降级/拒收（含部分拒收） |
| GET `/api/tickets/{no}` · GET `/api/tickets/cold-events/{eventNo}` | 多角色 | 工单详情（证据版本/决定/冻结/升级）/ 列表 |
| POST `/api/review/tickets/{no}/conclusion` | QC/DISPATCH | 区间复盘（离线/超温/存疑）+ 责任方 |
| POST `/api/review/tickets/{no}/close` | QC/DISPATCH | 闭环工单并释放冻结（审批链完整才允许） |
| POST `/api/review/tickets/{no}/extend-due` | SUPERVISOR | 延长客户决定时限 |
| POST `/api/escalations/{id}/decision` | SUPERVISOR | L1/L2 审批 APPROVE/REJECT，可带 `bindingChoices` 绑定裁定、`requireL2` |
| GET `/api/cold-events/{eventNo}/escalations` | 多角色 | 升级链 |
| GET `/api/proposals/cold-events/{eventNo}/calculate` | SETTLE | 候选方案实时测算（含 blockers） |
| POST `/api/proposals/cold-events/{eventNo}/generate` | SETTLE | 生成新版本 DRAFT（旧草稿作废） |
| GET `/api/proposals/cold-events/{eventNo}` · GET `/api/proposals/{id}` | SETTLE/主管 | 方案版本列表 / 详情 |
| POST `/api/proposals/{id}/submit` | SETTLE | 提交审批链（大额自动 L2） |
| POST `/api/proposals/{id}/post` | SETTLE | 审批链完整后幂等入账（重复/并发不重复赔付） |
| POST `/api/proposals/{id}/reject` | SUPERVISOR | 驳回方案，退回补证重算 |
| GET `/api/audit/trips/{code}` · `/api/audit/events/{eventNo}` | 多角色 | 一页可审计异常时间线 |

## 主要接口（基线：预约入场协同，均需 Basic 认证，除 `/api/health`）

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

## 一键端到端验证脚本

```bash
# 需宿主机有 curl/python3；CC_PUBLISH_PORT 为应用宿主端口
export CC_PUBLISH_PORT=$(docker compose port app 8080 | cut -d: -f2)
bash scripts/smoke.sh                 # 基线：预约入场协同（40 项断言）
CC_BASE_URL=http://host.docker.internal:${CC_PUBLISH_PORT} python3 scripts/smoke_feature4b.py
# feature-4-b：冷链事件/幂等回调/区间合并/并行补证/冲突升级/赔付审批（44 项断言，含并发重复回调、并发入账）
```

- 基线脚本依次跑通：① 多货主混装正常车全流程；② 温度断点+超限+化冻车（隔离→降级→承运商全责）；③ 封签异常闸口排队闭环；④ 证件过期拦截；⑤ 限电仅自备发电月台可排窗；⑥ 调度看板与异常台。
- feature-4-b 脚本依次跑通：① 两断点合并/离线vs超温区分/一致拒收/大额 L2/并发重复回调与并发入账幂等；② 客户与质检冲突→L1 驳回→证据替换撤销草稿→重做一致→L1→L2 生效；③ 同批次部分可收 70/部分拒收 30；④ 扫描批次不符只冻该批、铅封告警并发幂等、四方并行补证、版本保留、主管时限延期、审计页。

### 本地开发（无 Docker，直连 PostgreSQL）

```bash
# 1) 准备库
createdb -U <user> coldchain
# 2) 运行（schema.sql/data.sql 自动建表播种）
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/coldchain \
SPRING_DATASOURCE_USERNAME=<user> SPRING_DATASOURCE_PASSWORD=<pwd> \
PARK_CLOCK=2026-09-30T13:00:00 SERVER_PORT=8088 \
mvn spring-boot:run
# 3) 验证
CC_BASE_URL=http://localhost:8088 python3 scripts/smoke_feature4b.py
```

接口文档：浏览器打开 `http://localhost:8088/swagger-ui.html`（容器部署时把 8088 换成宿主映射端口），OpenAPI JSON：`/v3/api-docs`。

### 实际执行过的验证（本机）

交付前在本机使用 **Maven 3 + JDK17 + PostgreSQL 15（独立实例，库 `coldchain`）** 实际启动应用并跑通（非仅静态编译）：

- `mvn package` 构建成功，应用以 `schema.sql/data.sql` 自动建表播种启动，`/api/health` 正常。
- 基线冒烟 `scripts/smoke.py`：**40 项断言全部通过，0 失败**。
- feature-4-b 冒烟 `scripts/smoke_feature4b.py`：**44 项断言全部通过，0 失败**，其中实际并发执行了「重复/并发冷机回调」「重复/并发铅封告警回调」「并发赔付 post」，确认无重复数据/工单/冻结/赔付。
- OpenAPI `/v3/api-docs` 返回 200（53 个路径），`/swagger-ui.html` 可打开；并验证角色越权返回 403、跨承运商回调返回 409。

> 说明：本机无 Docker daemon，未执行 `docker compose build`；Dockerfile/compose 沿用已验证的多阶段结构，依赖在镜像构建期由 Maven 拉取（springdoc 已在本机 Maven 构建中验证可解析）。

## 目录结构

```
├── Dockerfile                 # 多阶段构建 + 非 root + HEALTHCHECK
├── docker-compose.yml         # app + postgres（仅发布 app 端口）
├── .env.example
├── pom.xml
├── scripts/smoke.sh              # 基线端到端冒烟（预约入场协同）
├── scripts/smoke_feature4b.sh    # feature-4-b 冒烟（异常工单/补证/升级/赔付，含并发幂等）
├── scripts/smoke_feature4b.py
└── src/main/
    ├── resources/
    │   ├── application.yml
    │   ├── schema.sql         # 表结构（基线 + feature-4-b 事件/区间/工单/证据/升级/冻结/赔付/合同）
    │   └── data.sql           # 角色账号（含主管）/主数据/合同阈值/复盘参数
    └── java/com/logpark/coldchain/
        ├── config/            # Spring Security 六角色 + OpenAPI 3
        ├── support/           # 时钟、异常、全局错误、角色映射、JSON
        ├── repo/Db.java
        ├── service/           # 基线：排程/预约/闸口/月台质检/调度/处置结算/客服/看板/追溯
        │                    # feature-4-b：ColdEvent(事件/回调/区间)/Ticket(补证)/Escalation(升级)/
        │                    #               SettlementProposal(赔付版本/审批生效)/Audit(审计时间线)
        └── web/               # REST 控制器（含 ColdEvent/Ticket/Escalation/Proposal/Audit）
```
