-- ============================================================
-- 冷链车辆预约入场与温控异常协同服务 - 数据库结构
-- 含 feature-4-b：冷链事件时间线、异常工单并行补证、复盘升级、赔付审批
-- ============================================================

CREATE TABLE IF NOT EXISTS app_user (
    id           BIGSERIAL PRIMARY KEY,
    username     VARCHAR(64) NOT NULL UNIQUE,
    password     VARCHAR(128) NOT NULL,           -- {noop} 前缀演示口令
    display_name VARCHAR(64) NOT NULL,
    role         VARCHAR(16) NOT NULL CHECK (role IN ('CARRIER','DISPATCH','QC','CS','SETTLE','SUPERVISOR')),
    carrier_id   BIGINT,
    owner_id     BIGINT,
    enabled      BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS cargo_owner (
    id             BIGSERIAL PRIMARY KEY,
    code           VARCHAR(32) NOT NULL UNIQUE,
    name           VARCHAR(128) NOT NULL,
    priority_level VARCHAR(8) NOT NULL DEFAULT 'P2' CHECK (priority_level IN ('P0','P1','P2')),
    default_value  NUMERIC(12,2) NOT NULL DEFAULT 50.00,  -- 每件货值（用于货损估算）
    contact        VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS carrier (
    id           BIGSERIAL PRIMARY KEY,
    code         VARCHAR(32) NOT NULL UNIQUE,
    name         VARCHAR(128) NOT NULL,
    credit_score INT NOT NULL DEFAULT 100,        -- 信用分，影响后续预约优先级
    contact      VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS driver (
    id                  BIGSERIAL PRIMARY KEY,
    carrier_id          BIGINT NOT NULL REFERENCES carrier(id),
    name                VARCHAR(64) NOT NULL,
    license_no          VARCHAR(64) NOT NULL UNIQUE,
    license_expire_date DATE NOT NULL,
    phone               VARCHAR(32)
);

CREATE TABLE IF NOT EXISTS dock (
    id           BIGSERIAL PRIMARY KEY,
    code         VARCHAR(16) NOT NULL UNIQUE,
    temp_zone    VARCHAR(16) NOT NULL CHECK (temp_zone IN ('FROZEN','CHILLED','AMBIENT')),
    night_open   BOOLEAN NOT NULL DEFAULT FALSE,  -- 夜间可作业（夜间排队容量受此约束）
    backup_power BOOLEAN NOT NULL DEFAULT FALSE,  -- 自备发电（限电时仍可作业）
    active       BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS qc_shift (
    id           BIGSERIAL PRIMARY KEY,
    day_of_week  INT NOT NULL CHECK (day_of_week BETWEEN 1 AND 7), -- 1=周一 ... 7=周日
    start_minute INT NOT NULL CHECK (start_minute BETWEEN 0 AND 1439),
    end_minute   INT NOT NULL CHECK (end_minute BETWEEN 1 AND 1800), -- 可跨夜（>1440 表示次日）
    headcount    INT NOT NULL
);

CREATE TABLE IF NOT EXISTS park_setting (
    key   VARCHAR(64) PRIMARY KEY,
    value VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS trip (
    id                    BIGSERIAL PRIMARY KEY,
    code                  VARCHAR(32) NOT NULL UNIQUE,
    carrier_id            BIGINT NOT NULL REFERENCES carrier(id),
    driver_id             BIGINT NOT NULL REFERENCES driver(id),
    vehicle_plate         VARCHAR(16) NOT NULL,
    temp_device_no        VARCHAR(64) NOT NULL,    -- 车厢温度设备（冷机）号
    e_seal_no             VARCHAR(64) NOT NULL,    -- 电子封签号
    requested_at          TIMESTAMP NOT NULL,      -- 承运商预约时间
    window_start          TIMESTAMP NOT NULL,      -- 系统生成入场窗口
    window_end            TIMESTAMP NOT NULL,
    dock_id               BIGINT REFERENCES dock(id),
    temp_zone             VARCHAR(16) NOT NULL,
    estimated_pieces      INT NOT NULL,
    urgent                BOOLEAN NOT NULL DEFAULT FALSE, -- 客户临时加急
    mixed_loading         BOOLEAN NOT NULL DEFAULT FALSE, -- 多货主混装
    priority_score        INT NOT NULL DEFAULT 0,
    status                VARCHAR(24) NOT NULL DEFAULT 'BOOKED'
        CHECK (status IN ('BOOKED','AT_GATE','QUEUED_AT_GATE','AT_DOCK','IN_QC',
                          'QUARANTINED','AWAIT_DISPOSITION','SETTLING','DONE','CANCELLED')),
    current_stage         VARCHAR(16),             -- GATE/DOCK/QC/QUARANTINE/SETTLE/DONE
    stage_entered_at      TIMESTAMP,
    arrived_at            TIMESTAMP,
    gate_plate_ok         BOOLEAN,
    gate_seal_ok          BOOLEAN,
    queued                BOOLEAN NOT NULL DEFAULT FALSE,
    late                  BOOLEAN NOT NULL DEFAULT FALSE,
    unload_photos         TEXT,                    -- 卸货照片（URL/说明，逗号分隔）
    unloading_started_at  TIMESTAMP,
    unloading_finished_at TIMESTAMP,
    disposition           VARCHAR(16) CHECK (disposition IN ('RELEASED','QUARANTINED','DOWNGRADED','REJECTED')),
    disposition_note      TEXT,
    liability_party       VARCHAR(16) CHECK (liability_party IN ('CARRIER','PARK','OWNER','SHARED')),
    penalty_amount        NUMERIC(12,2) NOT NULL DEFAULT 0,
    compensation_amount   NUMERIC(12,2) NOT NULL DEFAULT 0,
    liability_note        TEXT,
    settled_at            TIMESTAMP,
    signoff_result        VARCHAR(16) CHECK (signoff_result IN ('SIGNED','PARTIAL','REJECTED')),
    signed_pieces         INT,
    signed_by             VARCHAR(64),
    signoff_note          TEXT,
    signed_at             TIMESTAMP,
    created_by            VARCHAR(64),
    created_at            TIMESTAMP NOT NULL,
    updated_at            TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_trip_window ON trip(window_start, temp_zone);
CREATE INDEX IF NOT EXISTS idx_trip_stage ON trip(current_stage, status);

CREATE TABLE IF NOT EXISTS trip_cargo (
    id                 BIGSERIAL PRIMARY KEY,
    trip_id            BIGINT NOT NULL REFERENCES trip(id) ON DELETE CASCADE,
    owner_id           BIGINT NOT NULL REFERENCES cargo_owner(id),
    cargo_type         VARCHAR(64) NOT NULL,
    temp_zone          VARCHAR(16) NOT NULL,
    max_temp_celsius   NUMERIC(5,2) NOT NULL,      -- 允许的最高厢温
    pieces             INT NOT NULL,
    value_per_piece    NUMERIC(12,2) NOT NULL DEFAULT 50.00,
    target_warehouse   VARCHAR(64),                -- 目标仓（货主临时改仓会更新）
    door_open_temp     NUMERIC(5,2),               -- 开门前温度
    damaged_pieces     INT NOT NULL DEFAULT 0,     -- 破损件数
    sample_total       INT,                        -- 质检抽样件数
    sample_thawed      INT NOT NULL DEFAULT 0,     -- 化冻件数
    qc_result          VARCHAR(16) CHECK (qc_result IN ('PASS','THAWED','DAMAGE_ONLY')),
    disposition        VARCHAR(16) CHECK (disposition IN ('RELEASED','QUARANTINED','DOWNGRADED','REJECTED')),
    disposition_note   TEXT
);
CREATE INDEX IF NOT EXISTS idx_cargo_trip ON trip_cargo(trip_id);

-- 温控设备原始数据（完整保留，供客户追溯）
CREATE TABLE IF NOT EXISTS temp_reading (
    id          BIGSERIAL PRIMARY KEY,
    trip_id     BIGINT NOT NULL REFERENCES trip(id) ON DELETE CASCADE,
    device_no   VARCHAR(64) NOT NULL,
    recorded_at TIMESTAMP NOT NULL,
    celsius     NUMERIC(5,2) NOT NULL,
    gap_after   BOOLEAN NOT NULL DEFAULT FALSE     -- 该点之后存在曲线断点
);
CREATE INDEX IF NOT EXISTS idx_reading_trip_time ON temp_reading(trip_id, recorded_at);
-- 重复回调幂等：同一车次同一设备同一时刻只保留一个原始点
CREATE UNIQUE INDEX IF NOT EXISTS uq_reading_trip_time ON temp_reading(trip_id, recorded_at);

CREATE TABLE IF NOT EXISTS incident (
    id             BIGSERIAL PRIMARY KEY,
    trip_id        BIGINT NOT NULL REFERENCES trip(id) ON DELETE CASCADE,
    type           VARCHAR(24) NOT NULL CHECK (type IN (
                       'LATE_ARRIVAL','TEMP_GAP','SEAL_ABNORMAL','WAREHOUSE_CHANGE',
                       'DOCK_CONGESTION','THAW_FOUND','DOCS_EXPIRED','POWER_LIMIT',
                       'URGENT_INSERT','TEMP_EXCURSION')),
    severity       VARCHAR(8) NOT NULL DEFAULT 'MED' CHECK (severity IN ('LOW','MED','HIGH')),
    description    TEXT NOT NULL,
    status         VARCHAR(12) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','RESOLVED')),
    responsibility VARCHAR(16) CHECK (responsibility IN ('CARRIER','PARK','OWNER','PENDING')),
    raised_by_role VARCHAR(16),
    resolution     TEXT,
    resolved_by    VARCHAR(64),
    created_at     TIMESTAMP NOT NULL,
    resolved_at    TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_incident_trip ON incident(trip_id, status);

-- 同一车次的协同时间线：承运商/调度/质检/客服/结算共享
CREATE TABLE IF NOT EXISTS trip_event (
    id         BIGSERIAL PRIMARY KEY,
    trip_id    BIGINT NOT NULL REFERENCES trip(id) ON DELETE CASCADE,
    event_type VARCHAR(40) NOT NULL,
    actor_role VARCHAR(16),
    actor_name VARCHAR(64),
    detail     TEXT,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_event_trip ON trip_event(trip_id, created_at);

-- ============================================================
-- feature-4-b：冷链事件时间线 / 卸货批次 / 异常区间 / 工单补证 /
--              升级审批 / 结算冻结 / 赔付候选与审批 / 合同阈值
-- ============================================================

-- 冷链主事件：一次预约入园聚合为一条可审计事件时间线
CREATE TABLE IF NOT EXISTS cold_event (
    id                 BIGSERIAL PRIMARY KEY,
    trip_id            BIGINT NOT NULL UNIQUE REFERENCES trip(id) ON DELETE CASCADE,
    event_no           VARCHAR(32) NOT NULL UNIQUE,
    appointment_code   VARCHAR(32) NOT NULL,      -- 预约单号
    carrier_id         BIGINT NOT NULL REFERENCES carrier(id),
    driver_id          BIGINT NOT NULL REFERENCES driver(id),
    chiller_device_no  VARCHAR(64) NOT NULL,      -- 冷机设备
    door_sensor_no     VARCHAR(64),               -- 舱门传感器
    e_seal_no          VARCHAR(64) NOT NULL,      -- 电子铅封
    frozen             BOOLEAN NOT NULL DEFAULT FALSE, -- 正常入库结算是否被冻结
    frozen_at          TIMESTAMP,
    frozen_reason      TEXT,
    released_at        TIMESTAMP,                 -- 解冻时间
    timeline_hash      VARCHAR(64),               -- 审计页指纹
    created_at         TIMESTAMP NOT NULL,
    updated_at         TIMESTAMP NOT NULL
);

-- 预约批次：预约单声明的批次/件数，卸货扫描与之核对
CREATE TABLE IF NOT EXISTS inbound_event (
    id             BIGSERIAL PRIMARY KEY,
    cold_event_id  BIGINT NOT NULL REFERENCES cold_event(id) ON DELETE CASCADE,
    batch_no       VARCHAR(40) NOT NULL,          -- 预约批次号
    owner_id       BIGINT REFERENCES cargo_owner(id),
    cargo_type     VARCHAR(64),
    expected_pieces INT NOT NULL,
    created_at     TIMESTAMP NOT NULL,
    UNIQUE (cold_event_id, batch_no)
);

-- 卸货扫描批次（月台扫码实绩）
CREATE TABLE IF NOT EXISTS unload_batch (
    id             BIGSERIAL PRIMARY KEY,
    cold_event_id  BIGINT NOT NULL REFERENCES cold_event(id) ON DELETE CASCADE,
    batch_no       VARCHAR(40) NOT NULL,          -- 扫描到的批次号
    scanned_pieces INT NOT NULL,
    scanned_by     VARCHAR(64),
    scanned_at     TIMESTAMP NOT NULL,
    matched        BOOLEAN NOT NULL DEFAULT FALSE,
    mismatch_note  TEXT,
    UNIQUE (cold_event_id, batch_no)
);

-- 冷机设备原始入库事件（回调幂等：event_id 唯一）
CREATE TABLE IF NOT EXISTS chiller_event (
    id             BIGSERIAL PRIMARY KEY,
    cold_event_id  BIGINT NOT NULL REFERENCES cold_event(id) ON DELETE CASCADE,
    event_id       VARCHAR(80) NOT NULL,          -- 上游回调事件ID（去重用）
    device_no      VARCHAR(64) NOT NULL,
    recorded_at    TIMESTAMP NOT NULL,
    payload_status VARCHAR(16),                   -- RUNNING/DEFROST/OFFLINE/ALARM...
    celsius        NUMERIC(5,2),
    raw_payload    TEXT,
    received_at    TIMESTAMP NOT NULL,
    UNIQUE (cold_event_id, event_id)
);

-- 舱门传感器事件（开门/关门；与铅封、超温时间对齐用于复盘）
CREATE TABLE IF NOT EXISTS door_event (
    id             BIGSERIAL PRIMARY KEY,
    cold_event_id  BIGINT NOT NULL REFERENCES cold_event(id) ON DELETE CASCADE,
    event_id       VARCHAR(80) NOT NULL,
    sensor_no      VARCHAR(64) NOT NULL,
    recorded_at    TIMESTAMP NOT NULL,
    action         VARCHAR(8) NOT NULL CHECK (action IN ('OPEN','CLOSE')),
    raw_payload    TEXT,
    received_at    TIMESTAMP NOT NULL,
    UNIQUE (cold_event_id, event_id)
);

-- 电子铅封事件（封/解/报警）
CREATE TABLE IF NOT EXISTS seal_event (
    id             BIGSERIAL PRIMARY KEY,
    cold_event_id  BIGINT NOT NULL REFERENCES cold_event(id) ON DELETE CASCADE,
    event_id       VARCHAR(80) NOT NULL,
    seal_no        VARCHAR(64) NOT NULL,
    recorded_at    TIMESTAMP NOT NULL,
    seal_status    VARCHAR(16) NOT NULL CHECK (seal_status IN ('SEALED','UNSEALED','ALARM')),
    raw_payload    TEXT,
    received_at    TIMESTAMP NOT NULL,
    UNIQUE (cold_event_id, event_id)
);

-- 温度异常区间：复盘核心。kind 区分传感器离线/真实超温/混合；相邻区间按规则合并
CREATE TABLE IF NOT EXISTS temp_anomaly_interval (
    id               BIGSERIAL PRIMARY KEY,
    cold_event_id    BIGINT NOT NULL REFERENCES cold_event(id) ON DELETE CASCADE,
    start_at         TIMESTAMP NOT NULL,
    end_at           TIMESTAMP NOT NULL,
    kind             VARCHAR(16) NOT NULL CHECK (kind IN ('OFFLINE','EXCURSION','MIXED')),
    max_celsius      NUMERIC(5,2),
    limit_celsius    NUMERIC(5,2),
    gap_minutes      INT NOT NULL DEFAULT 0,      -- 离线/断点分钟数
    merged_count     INT NOT NULL DEFAULT 1,      -- 合并了几个原始区间
    evidence_summary TEXT,
    conclusion       VARCHAR(24),                 -- 复盘结论：SENSOR_OFFLINE/REAL_EXCURSION/INCONCLUSIVE
    liability_party  VARCHAR(16) CHECK (liability_party IN ('CARRIER','PARK','OWNER','SHARED','NONE')),
    status           VARCHAR(16) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','REVIEWING','CLOSED')),
    created_at       TIMESTAMP NOT NULL,
    updated_at       TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_interval_event ON temp_anomaly_interval(cold_event_id, start_at);
-- 并发重算/重复回调时区间去重
CREATE UNIQUE INDEX IF NOT EXISTS uq_interval_range ON temp_anomaly_interval(cold_event_id, start_at, end_at, kind);

-- 异常工单：可并行补证；同批次同类型用 business_key 幂等，杜绝重复冻结/重复工单
CREATE TABLE IF NOT EXISTS anomaly_ticket (
    id              BIGSERIAL PRIMARY KEY,
    cold_event_id   BIGINT NOT NULL REFERENCES cold_event(id) ON DELETE CASCADE,
    interval_id     BIGINT REFERENCES temp_anomaly_interval(id),
    batch_no        VARCHAR(40),
    ticket_no       VARCHAR(40) NOT NULL UNIQUE,
    category        VARCHAR(24) NOT NULL CHECK (category IN (
                        'TEMP_EXCURSION','DATA_GAP','SEAL_ABNORMAL','BATCH_MISMATCH')),
    title           TEXT NOT NULL,
    severity        VARCHAR(8) NOT NULL DEFAULT 'MED',
    status          VARCHAR(16) NOT NULL DEFAULT 'OPEN'
                       CHECK (status IN ('OPEN','AWAIT_CUSTOMER','REVIEWING','ESCALATED','CLOSED','REJECTED')),
    business_key    VARCHAR(120) NOT NULL,        -- 幂等键：event|category|区间/批次
    responsibility  VARCHAR(16) DEFAULT 'PENDING'
                       CHECK (responsibility IN ('CARRIER','PARK','OWNER','SHARED','PENDING','NONE')),
    due_at          TIMESTAMP,                    -- 客户补证/决定时限
    closed_at       TIMESTAMP,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP NOT NULL,
    UNIQUE (cold_event_id, business_key)
);
CREATE INDEX IF NOT EXISTS idx_ticket_event ON anomaly_ticket(cold_event_id, status);

-- 工单证据：只追加不改写原始数据；角色限定证据类型；每次提交生成新版本
CREATE TABLE IF NOT EXISTS ticket_evidence (
    id            BIGSERIAL PRIMARY KEY,
    ticket_id     BIGINT NOT NULL REFERENCES anomaly_ticket(id) ON DELETE CASCADE,
    version       INT NOT NULL,
    role          VARCHAR(16) NOT NULL CHECK (role IN ('CARRIER','PARK','QC','CUSTOMER')),
    evidence_type VARCHAR(24) NOT NULL CHECK (evidence_type IN (
                      'DEVICE_RAW_LOG','DWELLING_NOTE',      -- 承运商：设备原始日志/停靠说明
                      'GATE_RECORD','UNLOAD_RECORD',         -- 园区：闸口/卸货记录
                      'QC_RECHECK',                          -- 质检：复检结论
                      'CUSTOMER_DECISION')),                 -- 客户：继续收/降级/拒收
    content       TEXT NOT NULL,
    ref_uri       TEXT,                                     -- 附件/原始数据定位
    submitted_by  VARCHAR(64) NOT NULL,
    submitted_at  TIMESTAMP NOT NULL,
    superseded    BOOLEAN NOT NULL DEFAULT FALSE,           -- 被同角色新版本替换（旧版仍保留）
    UNIQUE (ticket_id, role, version)
);
CREATE INDEX IF NOT EXISTS idx_evidence_ticket ON ticket_evidence(ticket_id, version);

-- 客户在同一工单/批次上的收货决定（可按批次部分可收/部分拒收）
CREATE TABLE IF NOT EXISTS customer_decision (
    id             BIGSERIAL PRIMARY KEY,
    ticket_id      BIGINT NOT NULL REFERENCES anomaly_ticket(id) ON DELETE CASCADE,
    batch_no       VARCHAR(40) NOT NULL,
    decision       VARCHAR(16) NOT NULL CHECK (decision IN ('ACCEPT','DOWNGRADE','REJECT')),
    accepted_pieces INT NOT NULL DEFAULT 0,
    note           TEXT,
    decided_by     VARCHAR(64) NOT NULL,
    decided_at     TIMESTAMP NOT NULL,
    UNIQUE (ticket_id, batch_no)
);

-- 升级审批：客户决定与质检结论冲突（或重大赔付）时生成，L1→L2 链
CREATE TABLE IF NOT EXISTS escalation (
    id               BIGSERIAL PRIMARY KEY,
    cold_event_id    BIGINT NOT NULL REFERENCES cold_event(id) ON DELETE CASCADE,
    ticket_id        BIGINT REFERENCES anomaly_ticket(id),
    level            INT NOT NULL DEFAULT 1,        -- 1=运营主管 2=质量与商务经理
    reason           TEXT NOT NULL,
    status           VARCHAR(16) NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING','APPROVED','REJECTED')),
    decision_note    TEXT,
    binding_choice_json TEXT,                     -- 冲突裁定时主管绑定的逐批次收货结论
    decided_by       VARCHAR(64),
    decided_at       TIMESTAMP,
    created_at       TIMESTAMP NOT NULL,
    UNIQUE (cold_event_id, ticket_id, level)
);

-- 结算冻结：异常触发时冻结“该批次正常入库结算”，按 business_key 幂等
CREATE TABLE IF NOT EXISTS settlement_freeze (
    id              BIGSERIAL PRIMARY KEY,
    cold_event_id   BIGINT NOT NULL REFERENCES cold_event(id) ON DELETE CASCADE,
    batch_no        VARCHAR(40) NOT NULL,
    ticket_id       BIGINT REFERENCES anomaly_ticket(id),
    status          VARCHAR(16) NOT NULL DEFAULT 'FROZEN'
                       CHECK (status IN ('FROZEN','RELEASED')),
    reason          TEXT NOT NULL,
    business_key    VARCHAR(120) NOT NULL,
    frozen_by       VARCHAR(64) NOT NULL,
    frozen_at       TIMESTAMP NOT NULL,
    released_by     VARCHAR(64),
    released_at     TIMESTAMP,
    UNIQUE (cold_event_id, business_key)
);
CREATE INDEX IF NOT EXISTS idx_freeze_event ON settlement_freeze(cold_event_id, status);

-- 赔付候选方案（草稿，可多版本）；仅审批链完整后写入正式结算
CREATE TABLE IF NOT EXISTS settlement_proposal (
    id                 BIGSERIAL PRIMARY KEY,
    cold_event_id      BIGINT NOT NULL REFERENCES cold_event(id) ON DELETE CASCADE,
    version            INT NOT NULL,
    status             VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
                          CHECK (status IN ('DRAFT','APPROVING','APPROVED','REJECTED','SUPERSEDED','EFFECTIVE')),
    liability_party    VARCHAR(16),
    acceptable_pieces  INT NOT NULL DEFAULT 0,     -- 可收数量
    rejected_pieces    INT NOT NULL DEFAULT 0,
    loss_amount        NUMERIC(12,2) NOT NULL DEFAULT 0,
    penalty_amount     NUMERIC(12,2) NOT NULL DEFAULT 0,
    compensation_amount NUMERIC(12,2) NOT NULL DEFAULT 0,
    calc_detail        TEXT,
    created_reason     TEXT,                       -- 重算原因（证据替换/驳回）
    created_by         VARCHAR(64) NOT NULL,
    created_at         TIMESTAMP NOT NULL,
    decided_by         VARCHAR(64),
    decided_at         TIMESTAMP,
    UNIQUE (cold_event_id, version)
);
CREATE INDEX IF NOT EXISTS idx_proposal_event ON settlement_proposal(cold_event_id, version);

-- 赔付候选逐批次明细：支撑同一批次部分可收/部分拒收
CREATE TABLE IF NOT EXISTS settlement_entry (
    id             BIGSERIAL PRIMARY KEY,
    proposal_id    BIGINT NOT NULL REFERENCES settlement_proposal(id) ON DELETE CASCADE,
    batch_no       VARCHAR(40) NOT NULL,
    owner_id       BIGINT REFERENCES cargo_owner(id),
    expected_pieces INT NOT NULL,
    accepted_pieces INT NOT NULL,
    rejected_pieces INT NOT NULL,
    unit_value     NUMERIC(12,2) NOT NULL,
    customer_choice VARCHAR(16),
    qc_conclusion  VARCHAR(24),
    liability_party VARCHAR(16),
    loss_amount    NUMERIC(12,2) NOT NULL DEFAULT 0,
    compensation   NUMERIC(12,2) NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_entry_proposal ON settlement_entry(proposal_id);

-- 合同阈值：赔付率上限/降级折扣/起赔/客户时限等，按货主配置
CREATE TABLE IF NOT EXISTS owner_contract (
    owner_id              BIGINT PRIMARY KEY REFERENCES cargo_owner(id),
    compensation_rate_max NUMERIC(5,2) NOT NULL DEFAULT 1.00, -- 赔付率上限（相对货损）
    downgrade_rate        NUMERIC(5,2) NOT NULL DEFAULT 0.50, -- 降级收货赔付比例
    min_claim_amount      NUMERIC(12,2) NOT NULL DEFAULT 0,   -- 起赔额
    customer_decision_hours INT NOT NULL DEFAULT 24,          -- 客户决定时限（小时）
    require_l2_threshold  NUMERIC(12,2) NOT NULL DEFAULT 5000 -- 赔付超此值需 L2 审批
);

-- 正式赔付写入幂等记录：一次冷链事件只允许一条生效结算，杜绝重复赔付
CREATE TABLE IF NOT EXISTS settlement_ledger (
    id               BIGSERIAL PRIMARY KEY,
    cold_event_id    BIGINT NOT NULL UNIQUE REFERENCES cold_event(id),
    proposal_id      BIGINT NOT NULL REFERENCES settlement_proposal(id),
    penalty_amount   NUMERIC(12,2) NOT NULL,
    compensation_amount NUMERIC(12,2) NOT NULL,
    posted_by        VARCHAR(64) NOT NULL,
    posted_at        TIMESTAMP NOT NULL
);

-- 业务单号序列（高并发下保证唯一）
CREATE SEQUENCE IF NOT EXISTS cold_event_no_seq;
CREATE SEQUENCE IF NOT EXISTS anomaly_ticket_no_seq;
