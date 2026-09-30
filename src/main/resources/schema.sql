-- ============================================================
-- 冷链车辆预约入场与温控异常协同服务 - 数据库结构
-- ============================================================

CREATE TABLE IF NOT EXISTS app_user (
    id           BIGSERIAL PRIMARY KEY,
    username     VARCHAR(64) NOT NULL UNIQUE,
    password     VARCHAR(128) NOT NULL,           -- {noop} 前缀演示口令
    display_name VARCHAR(64) NOT NULL,
    role         VARCHAR(16) NOT NULL CHECK (role IN ('CARRIER','DISPATCH','QC','CS','SETTLE')),
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
    temp_device_no        VARCHAR(64) NOT NULL,    -- 车厢温度设备号
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
-- 幂等：同一车次同一时刻的温度点只保留一条（设备重推/回调重试去重）
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
-- feature-4：冷链事件时间线 / 自动冻结 / 异常工单 / 多方补证 /
--             复盘 / 赔付候选与审批链 / 幂等控制
-- ============================================================

-- 角色扩展：新增 CUSTOMER（客户，仅可提交本货主的收货决定）
ALTER TABLE app_user DROP CONSTRAINT IF EXISTS app_user_role_check;
ALTER TABLE app_user ADD CONSTRAINT app_user_role_check
    CHECK (role IN ('CARRIER','DISPATCH','QC','CS','SETTLE','CUSTOMER'));

-- 异常类型扩展：卸货扫描与预约批次不一致
ALTER TABLE incident DROP CONSTRAINT IF EXISTS incident_type_check;
ALTER TABLE incident ADD CONSTRAINT incident_type_check CHECK (type IN (
    'LATE_ARRIVAL','TEMP_GAP','SEAL_ABNORMAL','WAREHOUSE_CHANGE',
    'DOCK_CONGESTION','THAW_FOUND','DOCS_EXPIRED','POWER_LIMIT',
    'URGENT_INSERT','TEMP_EXCURSION','SCAN_MISMATCH'));

-- 货主合同阈值：用于赔付候选计算（合同允许的超温时长/温度容差/降级折价率）
ALTER TABLE cargo_owner ADD COLUMN IF NOT EXISTS contract_max_excursion_minutes INT NOT NULL DEFAULT 30;
ALTER TABLE cargo_owner ADD COLUMN IF NOT EXISTS contract_temp_tolerance_c NUMERIC(4,1) NOT NULL DEFAULT 1.0;
ALTER TABLE cargo_owner ADD COLUMN IF NOT EXISTS downgrade_rate NUMERIC(3,2) NOT NULL DEFAULT 0.50;

-- 冷机设备台账（车厢冷机，关联承运商）
CREATE TABLE IF NOT EXISTS reefer_device (
    id          BIGSERIAL PRIMARY KEY,
    device_no   VARCHAR(64) NOT NULL UNIQUE,
    carrier_id  BIGINT REFERENCES carrier(id),
    model       VARCHAR(64),
    registered_at TIMESTAMP NOT NULL
);

-- 设备心跳：区分“传感器离线”与“真实超温”的关键原始依据
CREATE TABLE IF NOT EXISTS device_heartbeat (
    id        BIGSERIAL PRIMARY KEY,
    device_no VARCHAR(64) NOT NULL,
    beat_at   TIMESTAMP NOT NULL,
    online    BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (device_no, beat_at)              -- 幂等：重复心跳回调去重
);
CREATE INDEX IF NOT EXISTS idx_heartbeat_device ON device_heartbeat(device_no, beat_at);

-- 舱门传感器事件（开门/关门）
CREATE TABLE IF NOT EXISTS door_event (
    id          BIGSERIAL PRIMARY KEY,
    trip_id     BIGINT NOT NULL REFERENCES trip(id) ON DELETE CASCADE,
    device_no   VARCHAR(64) NOT NULL,
    event       VARCHAR(8) NOT NULL CHECK (event IN ('OPEN','CLOSE')),
    occurred_at TIMESTAMP NOT NULL,
    event_uid   VARCHAR(80) UNIQUE           -- 幂等键：重复回调不产生重复事件
);
CREATE INDEX IF NOT EXISTS idx_door_trip ON door_event(trip_id, occurred_at);

-- 电子铅封事件（施封/异常撬动/破封/解封）
CREATE TABLE IF NOT EXISTS seal_event (
    id          BIGSERIAL PRIMARY KEY,
    trip_id     BIGINT NOT NULL REFERENCES trip(id) ON DELETE CASCADE,
    seal_no     VARCHAR(64) NOT NULL,
    event       VARCHAR(16) NOT NULL CHECK (event IN ('SEALED','TAMPERED','BROKEN','UNSEALED')),
    occurred_at TIMESTAMP NOT NULL,
    event_uid   VARCHAR(80) UNIQUE           -- 幂等键
);
CREATE INDEX IF NOT EXISTS idx_seal_trip ON seal_event(trip_id, occurred_at);

-- 预约批次：预约单声明的卸货批次（卸货扫描比对基准）
CREATE TABLE IF NOT EXISTS trip_batch (
    id           BIGSERIAL PRIMARY KEY,
    trip_id      BIGINT NOT NULL REFERENCES trip(id) ON DELETE CASCADE,
    cargo_id     BIGINT NOT NULL REFERENCES trip_cargo(id) ON DELETE CASCADE,
    batch_code   VARCHAR(64) NOT NULL,
    expected_qty INT NOT NULL,
    scanned_qty  INT NOT NULL DEFAULT 0,
    scan_status  VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (scan_status IN ('PENDING','MATCHED','MISMATCH','EXTRA')),
    accept_qty   INT,                        -- 复盘后客户可收数量
    reject_qty   INT,                        -- 复盘后客户拒收数量
    review_note  VARCHAR(255),
    UNIQUE (trip_id, batch_code)
);
CREATE INDEX IF NOT EXISTS idx_batch_trip ON trip_batch(trip_id);

-- 卸货扫描回调记录（幂等）
CREATE TABLE IF NOT EXISTS unload_scan (
    id         BIGSERIAL PRIMARY KEY,
    trip_id    BIGINT NOT NULL REFERENCES trip(id) ON DELETE CASCADE,
    batch_code VARCHAR(64) NOT NULL,
    qty        INT NOT NULL,
    scanned_at TIMESTAMP NOT NULL,
    scan_uid   VARCHAR(80) NOT NULL UNIQUE   -- 幂等键：重复扫描回调去重
);
CREATE INDEX IF NOT EXISTS idx_scan_trip ON unload_scan(trip_id, scanned_at);

-- 结算冻结：同一批次同一原因只冻结一次（唯一约束保证重试/重复回调不重复冻结）
CREATE TABLE IF NOT EXISTS settlement_freeze (
    id            BIGSERIAL PRIMARY KEY,
    trip_id       BIGINT NOT NULL REFERENCES trip(id) ON DELETE CASCADE,
    batch_id      BIGINT NOT NULL REFERENCES trip_batch(id) ON DELETE CASCADE,
    reason        VARCHAR(24) NOT NULL
        CHECK (reason IN ('TEMP_EXCURSION','DATA_GAP','SEAL_ABNORMAL','SCAN_MISMATCH')),
    work_order_id BIGINT,
    status        VARCHAR(12) NOT NULL DEFAULT 'FROZEN' CHECK (status IN ('FROZEN','RELEASED')),
    created_at    TIMESTAMP NOT NULL,
    released_at   TIMESTAMP,
    release_note  VARCHAR(255),
    UNIQUE (batch_id, reason)
);
CREATE INDEX IF NOT EXISTS idx_freeze_trip ON settlement_freeze(trip_id, status);

-- 异常工单：一次异常区间一单，支持多方并行补证
CREATE TABLE IF NOT EXISTS work_order (
    id              BIGSERIAL PRIMARY KEY,
    trip_id         BIGINT NOT NULL REFERENCES trip(id) ON DELETE CASCADE,
    batch_id        BIGINT REFERENCES trip_batch(id),
    type            VARCHAR(24) NOT NULL
        CHECK (type IN ('TEMP_EXCURSION','DATA_GAP','SEAL_ABNORMAL','SCAN_MISMATCH')),
    status          VARCHAR(16) NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN','IN_REVIEW','ESCALATED','RESOLVED')),
    anomaly_start   TIMESTAMP,
    anomaly_end     TIMESTAMP,
    merged_count    INT NOT NULL DEFAULT 1,  -- 复盘合并的原始异常区间数
    classification  VARCHAR(24) CHECK (classification IN ('SENSOR_OFFLINE','REAL_EXCURSION','CONFIRMED','CLEARED')),
    classify_note   VARCHAR(255),
    responsibility  VARCHAR(16) CHECK (responsibility IN ('CARRIER','PARK','OWNER','SHARED')),
    review_note     TEXT,
    dedup_key       VARCHAR(160) UNIQUE,     -- 幂等：同车次+类型+区间只建一单
    created_at      TIMESTAMP NOT NULL,
    resolved_at     TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_wo_trip ON work_order(trip_id, status);
-- 升级标记：客户决定与质检结论冲突的工单，结案后仍保留升级痕迹（审批链据此追加质检复核）
ALTER TABLE work_order ADD COLUMN IF NOT EXISTS escalated BOOLEAN NOT NULL DEFAULT FALSE;

-- 补证：只增不改的版本链；任何一方补证不覆盖原始数据与历史版本
CREATE TABLE IF NOT EXISTS evidence (
    id            BIGSERIAL PRIMARY KEY,
    work_order_id BIGINT NOT NULL REFERENCES work_order(id) ON DELETE CASCADE,
    role          VARCHAR(16) NOT NULL CHECK (role IN ('CARRIER','PARK','QC','CUSTOMER')),
    evidence_type VARCHAR(32) NOT NULL
        CHECK (evidence_type IN ('DEVICE_LOG','STOP_NOTE','GATE_RECORD','UNLOAD_RECORD',
                                 'QC_RECHECK','CUSTOMER_DECISION')),
    version       INT NOT NULL,
    content       TEXT NOT NULL,
    submitted_by  VARCHAR(64) NOT NULL,
    submitted_at  TIMESTAMP NOT NULL,
    supersedes    BIGINT,                    -- 被替换的旧版本 id（版本链）
    active        BOOLEAN NOT NULL DEFAULT TRUE,  -- 被新版本替换后置 FALSE（保留审计，不删除）
    UNIQUE (work_order_id, role, evidence_type, version)  -- 并发补证版本号唯一
);
CREATE INDEX IF NOT EXISTS idx_evidence_wo ON evidence(work_order_id, active);

-- 客户收货决定（时限内）：继续收货 / 降级收货 / 拒收，支持部分可收部分拒收
CREATE TABLE IF NOT EXISTS customer_decision (
    id           BIGSERIAL PRIMARY KEY,
    work_order_id BIGINT NOT NULL REFERENCES work_order(id) ON DELETE CASCADE,
    batch_id     BIGINT NOT NULL REFERENCES trip_batch(id),
    decision     VARCHAR(16) NOT NULL CHECK (decision IN ('ACCEPT','DOWNGRADE','REJECT')),
    accept_qty   INT NOT NULL,
    reject_qty   INT NOT NULL,
    decided_by   VARCHAR(64) NOT NULL,
    decided_at   TIMESTAMP NOT NULL,
    deadline_at  TIMESTAMP NOT NULL,
    version      INT NOT NULL,
    active       BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (work_order_id, version)
);

-- 赔付候选方案：重算产生新 calc_version，旧版本撤销，永不重复生效
CREATE TABLE IF NOT EXISTS payout (
    id             BIGSERIAL PRIMARY KEY,
    trip_id        BIGINT NOT NULL REFERENCES trip(id) ON DELETE CASCADE,
    batch_id       BIGINT NOT NULL REFERENCES trip_batch(id),
    work_order_id  BIGINT,
    kind           VARCHAR(16) NOT NULL DEFAULT 'COMPENSATION',
    calc_version   INT NOT NULL,
    amount         NUMERIC(12,2) NOT NULL,
    responsibility VARCHAR(16),
    basis          TEXT,                     -- 计算依据：责任/可收数量/合同阈值/客户选择
    status         VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT','IN_APPROVAL','APPROVED','EFFECTIVE','REVOKED','REJECTED')),
    created_at     TIMESTAMP NOT NULL,
    UNIQUE (batch_id, calc_version)          -- 幂等：同一批次同一计算版本只落一条
);
CREATE INDEX IF NOT EXISTS idx_payout_trip ON payout(trip_id, status);

-- 审批链：审批链完整后赔付才生效写入结算
CREATE TABLE IF NOT EXISTS approval_step (
    id            BIGSERIAL PRIMARY KEY,
    payout_id     BIGINT NOT NULL REFERENCES payout(id) ON DELETE CASCADE,
    seq           INT NOT NULL,
    approver_role VARCHAR(16) NOT NULL CHECK (approver_role IN ('QC','DISPATCH','SETTLE')),
    status        VARCHAR(12) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','APPROVED','REJECTED','SKIPPED')),
    decided_by    VARCHAR(64),
    decided_at    TIMESTAMP,
    comment       VARCHAR(255),
    UNIQUE (payout_id, seq)
);

-- 通用幂等键（客户端回调去重）
CREATE TABLE IF NOT EXISTS idempotency_key (
    key           VARCHAR(120) PRIMARY KEY,
    endpoint      VARCHAR(120) NOT NULL,
    response_json TEXT,
    created_at    TIMESTAMP NOT NULL
);
