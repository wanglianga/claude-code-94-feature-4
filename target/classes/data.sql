-- ============================================================
-- 种子数据：角色账号、货主、承运商、司机、月台、质检班次、园区参数
-- ============================================================

-- 货主（客户优先级 P0>P1>P2）
INSERT INTO cargo_owner (id, code, name, priority_level, default_value, contact) VALUES
    (1, 'HEMA',  '盒马鲜生',   'P0', 80.00, '客服-王敏'),
    (2, 'MENGNIU','蒙牛乳业',   'P1', 60.00, '客服-赵磊'),
    (3, 'YH',    '永辉超市',   'P2', 40.00, '客服-孙莉')
ON CONFLICT (code) DO NOTHING;

-- 承运商
INSERT INTO carrier (id, code, name, credit_score, contact) VALUES
    (1, 'SF', '顺丰冷链', 100, '调度-陈刚'),
    (2, 'JD', '京东冷链', 85,  '调度-刘洋')
ON CONFLICT (code) DO NOTHING;

-- 司机（含证件已过期场景）
INSERT INTO driver (id, carrier_id, name, license_no, license_expire_date, phone) VALUES
    (1, 1, '张志强', 'J-3101-0001', DATE '2028-06-30', '13800000001'),
    (2, 1, '李大山', 'J-3101-0002', DATE '2026-01-15', '13800000002'),
    (3, 2, '王海涛', 'J-1101-0003', DATE '2027-03-10', '13800000003')
ON CONFLICT (license_no) DO NOTHING;
-- 序列对齐显式 id，避免重启后插入冲突
SELECT setval(pg_get_serial_sequence('driver','id'), GREATEST((SELECT MAX(id) FROM driver), 1));

-- 月台：温区 / 夜间作业 / 自备发电（限电时可用）
INSERT INTO dock (id, code, temp_zone, night_open, backup_power, active) VALUES
    (1, 'D01', 'FROZEN',  TRUE,  TRUE,  TRUE),
    (2, 'D02', 'CHILLED', FALSE, FALSE, TRUE),
    (3, 'D03', 'AMBIENT', TRUE,  FALSE, TRUE),
    (4, 'D04', 'CHILLED', TRUE,  TRUE,  TRUE),
    (5, 'D05', 'FROZEN',  FALSE, FALSE, TRUE)
ON CONFLICT (code) DO NOTHING;

-- 质检班次（分钟，可跨夜）
INSERT INTO qc_shift (day_of_week, start_minute, end_minute, headcount)
SELECT * FROM (VALUES
    (1, 480, 1080, 4),   -- 周一~周五 白班 08:00-18:00
    (2, 480, 1080, 4),
    (3, 480, 1080, 4),
    (4, 480, 1080, 4),
    (5, 480, 1080, 4),
    (6, 540, 1080, 3),   -- 周六~周日 09:00-18:00
    (7, 540, 1080, 3),
    (1, 1200, 1440, 2),  -- 每日 20:00-24:00 夜班（小批量）
    (2, 1200, 1440, 2),
    (3, 1200, 1440, 2),
    (4, 1200, 1440, 2),
    (5, 1200, 1440, 2),
    (6, 1200, 1440, 1),
    (7, 1200, 1440, 1)
) AS v(day_of_week, start_minute, end_minute, headcount)
WHERE NOT EXISTS (SELECT 1 FROM qc_shift);

-- 园区参数
INSERT INTO park_setting (key, value) VALUES
    ('NIGHT_START_MINUTE',     '1320'),  -- 22:00
    ('NIGHT_END_MINUTE',       '360'),   -- 06:00
    ('POWER_LIMIT',            'false'), -- 园区限电开关
    ('LATE_TOLERANCE_MINUTES', '30'),    -- 晚到容忍分钟
    ('WINDOW_MINUTES',         '30'),    -- 单窗口时长
    ('TEMP_GAP_MINUTES',       '45')     -- 温度曲线相邻点最大间隔，超过判定断点
ON CONFLICT (key) DO NOTHING;

-- 逐角色账号（明文演示口令，生产应使用强哈希）
INSERT INTO app_user (username, password, display_name, role, carrier_id, owner_id) VALUES
    ('carrier1', '{noop}carrier123', '顺丰承运账号', 'CARRIER', 1, NULL),
    ('carrier2', '{noop}carrier123', '京东承运账号', 'CARRIER', 2, NULL),
    ('dispatch', '{noop}dispatch123','园区调度-周琳', 'DISPATCH', NULL, NULL),
    ('qc',       '{noop}qc123',      '质检员-吴凯',   'QC',       NULL, NULL),
    ('cs',       '{noop}cs123',      '客户客服-林悦', 'CS',       NULL, NULL),
    ('settle',   '{noop}settle123',  '结算员-郑华',   'SETTLE',   NULL, NULL)
ON CONFLICT (username) DO NOTHING;

-- ============================================================
-- feature-4 种子：客户账号 / 复盘与赔付参数 / 货主合同阈值 / 冷机设备
-- ============================================================

-- 客户账号（仅可对本货主批次提交收货决定）
INSERT INTO app_user (username, password, display_name, role, carrier_id, owner_id) VALUES
    ('customer1', '{noop}customer123', '盒马收货-沈青', 'CUSTOMER', NULL, 1),
    ('customer2', '{noop}customer123', '蒙牛收货-罗成', 'CUSTOMER', NULL, 2),
    ('customer3', '{noop}customer123', '永辉收货-许岚', 'CUSTOMER', NULL, 3)
ON CONFLICT (username) DO NOTHING;

-- feature-4 园区参数
INSERT INTO park_setting (key, value) VALUES
    ('MERGE_WINDOW_MINUTES',   '15'),  -- 复盘：相邻异常区间间隔不超过该值则合并为一个区间
    ('CUSTOMER_DECISION_HOURS','48')   -- 客户收货决定时限（自工单创建起）
ON CONFLICT (key) DO NOTHING;

-- 货主合同阈值（赔付候选计算依据）：合同允许累计超温分钟 / 温度容差℃ / 降级折价率
UPDATE cargo_owner SET contract_max_excursion_minutes = 30, contract_temp_tolerance_c = 1.0, downgrade_rate = 0.50 WHERE code = 'HEMA';
UPDATE cargo_owner SET contract_max_excursion_minutes = 45, contract_temp_tolerance_c = 1.5, downgrade_rate = 0.40 WHERE code = 'MENGNIU';
UPDATE cargo_owner SET contract_max_excursion_minutes = 60, contract_temp_tolerance_c = 2.0, downgrade_rate = 0.30 WHERE code = 'YH';

-- 冷机设备台账（演示：两车冷机）
INSERT INTO reefer_device (device_no, carrier_id, model, registered_at)
SELECT 'RF-SF-001', 1, '冷王T-800Pro', NOW()
WHERE NOT EXISTS (SELECT 1 FROM reefer_device WHERE device_no = 'RF-SF-001');
INSERT INTO reefer_device (device_no, carrier_id, model, registered_at)
SELECT 'RF-JD-002', 2, '开利S-550', NOW()
WHERE NOT EXISTS (SELECT 1 FROM reefer_device WHERE device_no = 'RF-JD-002');
