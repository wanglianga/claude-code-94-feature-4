#!/usr/bin/env python3
"""
feature-4-b 端到端冒烟：冷链事件时间线 / 幂等回调 / 区间复盘 / 并行补证 /
冲突升级审批 / 赔付候选版本化 / 审批后生效 / 并发不重复赔付。
仅依赖标准库。用法：CC_BASE_URL=http://localhost:8088 python3 scripts/smoke_feature4b.py
"""
import base64
import json
import os
import sys
import threading
import time
import urllib.request
import urllib.error
from datetime import datetime, timedelta

PORT = os.environ.get("CC_PUBLISH_PORT", "8088")
BASE = os.environ.get("CC_BASE_URL", f"http://localhost:{PORT}").rstrip("/")

USERS = {
    "carrier1": "carrier123", "carrier2": "carrier123", "dispatch": "dispatch123",
    "qc": "qc123", "cs": "cs123", "settle": "settle123", "supervisor": "super123",
}
PASS = FAIL = 0
_LOCK = threading.Lock()


def req(method, path, user=None, body=None, expect=200):
    url = BASE + path
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        r.add_header("Content-Type", "application/json")
    if user:
        r.add_header("Authorization", "Basic " + base64.b64encode(
            f"{user}:{USERS[user]}".encode()).decode())
    try:
        with urllib.request.urlopen(r, timeout=30) as resp:
            status, raw = resp.status, resp.read().decode()
    except urllib.error.HTTPError as e:
        status, raw = e.code, e.read().decode()
    try:
        parsed = json.loads(raw) if raw else {}
    except json.JSONDecodeError:
        parsed = {"_raw": raw}
    if expect is not None and status != expect:
        die(f"{method} {path} 期望 HTTP {expect}，实际 {status}：{raw[:500]}")
    return status, parsed


def check(cond, msg):
    global PASS, FAIL
    with _LOCK:
        if cond:
            PASS += 1
            print(f"  ✅ {msg}")
        else:
            FAIL += 1
            print(f"  ❌ {msg}")


def die(msg):
    print(f"\n💥 冒烟失败：{msg}")
    sys.exit(1)


def wait_health():
    for _ in range(60):
        try:
            with urllib.request.urlopen(BASE + "/api/health", timeout=5) as r:
                if r.status == 200:
                    return json.loads(r.read().decode())
        except Exception:
            pass
        time.sleep(3)
    die("健康检查未通过")


def iso(park, off):
    return (park + timedelta(minutes=off)).replace(second=0, microsecond=0).isoformat()


def book(user, payload):
    _, t = req("POST", "/api/appointments", user, payload)
    return t


def admit_and_event(code, plate, seal, park):
    req("POST", "/api/gate/checkin", "dispatch",
        {"code": code, "vehiclePlate": plate, "eSealNo": seal})
    _, ev = req("GET", f"/api/cold-events/trips/{code}", "dispatch")
    return ev


def ticket_nos(ev, category):
    return [t["ticket_no"] for t in ev["tickets"] if t["category"] == category]


def intervals(ev, kind):
    return [i for i in ev["temperatureIntervals"] if i["kind"] == kind
            and i["status"] != "CLOSED"]


def main():
    print(f"目标服务：{BASE}")
    health = wait_health()
    park = datetime.fromisoformat(health["parkTime"])
    def I(off): return iso(park, off)

    # ======================================================================
    # 场景1：断点多区间合并 + 离线/超温区分 + 一致拒收 + L2 + 并发幂等入账
    # ======================================================================
    print("\n【场景1】区间合并·离线vs超温·一致拒收·L2审批·并发不重复赔付")
    t1 = book("carrier1", {
        "carrierId": 1, "driverId": 1, "vehiclePlate": "沪AA1001",
        "tempDeviceNo": "DEV-F4-1", "eSealNo": "SEAL-F4-1", "requestedAt": I(60),
        "cargoLines": [{"ownerId": 1, "cargoType": "冷冻肥牛", "tempZone": "FROZEN",
                        "maxTempCelsius": -18, "pieces": 100, "targetWarehouse": "W-F1"}]})
    c1, b1 = t1["code"], t1["code"] + "-B1"
    ev1 = admit_and_event(c1, "沪AA1001", "SEAL-F4-1", park)
    en1 = ev1["event_no"]
    check([b["batch_no"] for b in ev1["inboundBatches"]] == [b1], "入园自动聚合冷链事件并播种预约批次")
    check(ev1["chiller_device_no"] == "DEV-F4-1" and ev1["e_seal_no"] == "SEAL-F4-1",
          "冷链事件关联预约单/车辆司机/冷机/电子铅封")

    readings = [
        {"eventId": "E1", "recordedAt": I(-200), "status": "RUNNING", "celsius": -20.0},
        {"eventId": "E2", "recordedAt": I(-140), "status": "RUNNING", "celsius": -19.0},  # 间隔60min
        {"eventId": "E3", "recordedAt": I(-130), "status": "RUNNING", "celsius": -20.0},
        {"eventId": "E4", "recordedAt": I(-80),  "status": "RUNNING", "celsius": -20.0},  # 间隔50min
        {"eventId": "E5", "recordedAt": I(-40),  "status": "RUNNING", "celsius": -7.0},
        {"eventId": "E6", "recordedAt": I(-30),  "status": "RUNNING", "celsius": -6.0},
        {"eventId": "E7", "recordedAt": I(-20),  "status": "RUNNING", "celsius": -7.0}]

    # 并发重复回调（同一批 eventId 同时推送两次）
    results = []
    def push(): results.append(req("POST", f"/api/callback/{en1}/chiller", "carrier1", {"events": readings}))
    th = [threading.Thread(target=push), threading.Thread(target=push)]
    for x in th: x.start()
    for x in th: x.join()
    _, ev1 = req("GET", f"/api/cold-events/trips/{c1}", "dispatch")
    check(ev1["chillerEvents"].__len__() == 7, "重复/并发回调按 eventId 去重：冷机事件仅 7 条")
    check(len(ev1["temperatureReadings"]) == 7, "温度曲线原始点 7 个（不重复）")

    # 再整批重放一次：0 新增
    _, replay = req("POST", f"/api/callback/{en1}/chiller", "carrier1", {"events": readings})
    check(replay["callback"]["newInserted"] == 0 and replay["callback"]["duplicated"] == 7,
          "重复回调整批重放：newInserted=0，不产生重复数据")

    off = intervals(ev1, "OFFLINE")
    exc = intervals(ev1, "EXCURSION")
    check(len(off) == 1 and off[0]["merged_count"] == 2,
          "两个相邻数据断点自动合并为 1 个离线区间（merged_count=2）")
    check(len(exc) == 1 and float(exc[0]["max_celsius"]) == -6.0,
          "真实超温区间独立识别（与离线区间相距>合并窗口，不合并）")
    cats = {t["category"] for t in ev1["tickets"]}
    check({"DATA_GAP", "TEMP_EXCURSION"}.issubset(cats), "断点/超温各自动创建异常工单")
    check(ev1["frozen"] is True, "异常触发冷链事件结算冻结")
    check(all(f["status"] == "FROZEN" for f in ev1["freezes"]), "相关批次正常入库结算已冻结")
    freeze_n1 = len(ev1["freezes"])
    _, ev1b = req("GET", f"/api/cold-events/trips/{c1}", "dispatch")
    check(len(ev1b["freezes"]) == freeze_n1, "重复分析不产生重复冻结")

    gap_tk, temp_tk = ticket_nos(ev1, "DATA_GAP")[0], ticket_nos(ev1, "TEMP_EXCURSION")[0]

    # 四方补证（角色限定 + 版本保留，原始数据不可覆盖）
    req("POST", f"/api/tickets/{gap_tk}/evidence", "carrier1", {
        "evidenceType": "DEVICE_RAW_LOG",
        "content": "冷机原始日志导出：-200~-80 期间设备因堆场等待断电，记录器离线，厢内始终低于-18℃",
        "refUri": "s3://cold-raw/DEV-F4-1/offline.log"})
    req("POST", f"/api/tickets/{gap_tk}/evidence", "carrier1", {
        "evidenceType": "DWELLING_NOTE", "content": "停靠说明：在园区外堆场等待 2 小时，车载电池关闭"})
    s, bad = req("POST", f"/api/tickets/{gap_tk}/evidence", "carrier1", {
        "evidenceType": "GATE_RECORD", "content": "尝试冒充园区提交闸口记录"}, expect=400)
    check(s == 400, "承运商不能提交园区角色证据（GATE_RECORD 被拒）")
    req("POST", f"/api/tickets/{temp_tk}/evidence", "dispatch", {
        "evidenceType": "UNLOAD_RECORD", "content": "月台卸货记录：开门前厢温 -6℃，全程 20 分钟"})

    # 质检复检：建议拒收
    req("POST", f"/api/tickets/{temp_tk}/evidence", "qc", {
        "evidenceType": "QC_RECHECK", "content": "抽样 10 件全部化冻，不可销售",
        "conclusions": [{"batchNo": b1, "conclusion": "REJECT"}]})

    # 复盘：离线区间判定“传感器离线、无责”；超温区间判定真实超温、承运商责任
    req("POST", f"/api/review/tickets/{gap_tk}/conclusion", "qc", {
        "conclusion": "SENSOR_OFFLINE", "liabilityParty": "NONE",
        "note": "结合冷机日志与停靠说明，断点为设备离线非真实超温"})
    req("POST", f"/api/review/tickets/{temp_tk}/conclusion", "qc", {
        "conclusion": "REAL_EXCURSION", "liabilityParty": "CARRIER",
        "note": "开门前 -6℃ 且抽样全化冻，真实超温，承运商责任"})

    # 客户在时限内拒收（与质检一致，不升级）
    _, tv = req("POST", f"/api/tickets/{temp_tk}/customer-decision", "cs", {
        "decisions": [{"batchNo": b1, "decision": "REJECT", "acceptedPieces": 0,
                       "note": "整批拒收"}]})
    check(tv["status"] == "REVIEWING", "客户拒收与质检结论一致 → 进入责任复核（不升级）")

    req("POST", f"/api/review/tickets/{gap_tk}/close", "qc", {"note": "设备离线免责"})
    req("POST", f"/api/review/tickets/{temp_tk}/close", "qc", {"note": "承运商全责"})
    _, ev1c = req("GET", f"/api/cold-events/trips/{c1}", "dispatch")
    check(all(f["status"] == "RELEASED" for f in ev1c["freezes"]), "工单闭环后释放批次结算冻结")

    # 证据版本可审计
    _, tkview = req("GET", f"/api/tickets/{gap_tk}", "settle")
    evs = tkview["evidence"]
    check(any(e["role"] == "CARRIER" and e["version"] == 1 and e["superseded"] for e in evs)
          and any(e["role"] == "CARRIER" and e["version"] == 2 and not e["superseded"] for e in evs),
          "同一角色再次补证生成新版本，旧版本保留且标记 superseded（原始数据不被覆盖）")

    # 赔付候选：拒收100件 × 80 = 8000，承运全责；L2 阈值 5000
    _, calc = req("GET", f"/api/proposals/cold-events/{en1}/calculate", "settle")
    check(calc["liabilityParty"] == "CARRIER" and abs(float(calc["lossAmount"]) - 8000) < 0.01
          and abs(float(calc["compensationAmount"]) - 8000) < 0.01,
          "赔付候选=拒收100×80=8000（责任/可收数量/合同阈值/客户选择联合计算）")
    req("POST", f"/api/proposals/cold-events/{en1}/generate", "settle",
        {"reason": "首次编制"})
    props_before = req("GET", f"/api/proposals/cold-events/{en1}", "settle")[1]
    pid = [p for p in props_before if p["status"] == "DRAFT"][0]["id"]
    s, blocked = req("POST", f"/api/proposals/{pid}/post", "settle", expect=422)
    check(s == 422 and blocked.get("blockers") is None and bool(blocked.get("error")),
          "生效门控未满足（工单未闭环/冻结未释放/审批未完成）时禁止写入结算")
    req("POST", f"/api/proposals/{pid}/submit", "settle")
    _, escs = req("GET", f"/api/cold-events/{en1}/escalations", "supervisor")
    l2 = [e for e in escs if e["level"] == 2 and e["status"] == "PENDING"]
    check(len(l2) == 1, "赔付额≥合同 L2 阈值，提交后自动生成 L2 升级审批")
    req("POST", f"/api/escalations/{l2[0]['id']}/decision", "supervisor",
        {"decision": "APPROVE", "note": "证据充分，同意赔付"})

    # 并发两次生效请求：只能有一条正式赔付
    posts = []
    def do_post():
        try:
            posts.append(req("POST", f"/api/proposals/{pid}/post", "settle"))
        except Exception as e:
            posts.append(("ERR", str(e)))
    ths = [threading.Thread(target=do_post), threading.Thread(target=do_post)]
    for x in ths: x.start()
    for x in ths: x.join()
    _, props_after = req("GET", f"/api/proposals/cold-events/{en1}", "settle")
    eff = [p for p in props_after if p["status"] == "EFFECTIVE"]
    check(len(eff) == 1, "并发/重试 post 只产生一个 EFFECTIVE 方案（无重复赔付）")
    _, audit1 = req("GET", f"/api/audit/trips/{c1}", "settle")
    check(audit1["summary"]["settlementPosted"] is True
          and float(audit1["ledger"]["compensation_amount"]) == 8000.0
          and audit1["carrierCreditScoreNow"] < 100,
          "正式赔付唯一入账 8000，承运商信用分下调（影响后续预约）")
    check(len(audit1["auditFingerprint"]) == 32 and len(audit1["breakpoints"]) >= 2,
          "一页审计时间线给出断点/证据/处置/责任/赔付与审计指纹")

    # ======================================================================
    # 场景2：客户与质检冲突 → L1 驳回 → 证据替换撤销草稿 → 重做一致 → L1→L2
    # ======================================================================
    print("\n【场景2】客户决定与质检冲突·L1驳回·证据替换撤销草稿·重做后审批生效")
    t2 = book("carrier2", {
        "carrierId": 2, "driverId": 3, "vehiclePlate": "京BB2002",
        "tempDeviceNo": "DEV-F4-2", "eSealNo": "SEAL-F4-2", "requestedAt": I(200),
        "cargoLines": [{"ownerId": 2, "cargoType": "低温酸奶", "tempZone": "CHILLED",
                        "maxTempCelsius": 4, "pieces": 100, "targetWarehouse": "W-C1"}]})
    c2, b2 = t2["code"], t2["code"] + "-B1"
    ev2 = admit_and_event(c2, "京BB2002", "SEAL-F4-2", park)
    en2 = ev2["event_no"]
    req("POST", f"/api/callback/{en2}/chiller", "carrier2", {"events": [
        {"eventId": "X1", "recordedAt": I(-40), "status": "RUNNING", "celsius": 8.0},
        {"eventId": "X2", "recordedAt": I(-30), "status": "RUNNING", "celsius": 9.0},
        {"eventId": "X3", "recordedAt": I(-20), "status": "RUNNING", "celsius": 8.0}]})
    _, ev2 = req("GET", f"/api/cold-events/trips/{c2}", "dispatch")
    tk2 = ticket_nos(ev2, "TEMP_EXCURSION")[0]

    # 质检判可收，客户却拒收 → 冲突 L1
    req("POST", f"/api/tickets/{tk2}/evidence", "qc", {
        "evidenceType": "QC_RECHECK", "content": "复检中心温度仍在可接受范围，建议正常收货",
        "conclusions": [{"batchNo": b2, "conclusion": "ACCEPT"}]})
    _, tv2 = req("POST", f"/api/tickets/{tk2}/customer-decision", "cs", {
        "decisions": [{"batchNo": b2, "decision": "REJECT", "acceptedPieces": 0}]})
    check(tv2["status"] == "ESCALATED", "客户拒收与质检可收冲突 → 工单升级")
    esc2 = tv2["escalations"]
    l1 = [e for e in esc2 if e["level"] == 1 and e["status"] == "PENDING"]
    check(len(l1) == 1, "自动创建 L1 冲突审批")

    # 先编一个赔付草稿
    req("POST", f"/api/proposals/cold-events/{en2}/generate", "settle", {"reason": "冲突期间预编"})
    # 主管驳回 L1
    req("POST", f"/api/escalations/{l1[0]['id']}/decision", "supervisor",
        {"decision": "REJECT", "note": "证据不足，要求质检重新复检、承运商补充设备日志"})
    _, props_r = req("GET", f"/api/proposals/cold-events/{en2}", "settle")
    check(all(p["status"] == "SUPERSEDED" for p in props_r),
          "审批驳回 → 未生效赔付草稿撤销（SUPERSEDED）")

    # 补证：承运商日志 + 质检改判拒收（证据替换，新版本）
    req("POST", f"/api/tickets/{tk2}/evidence", "carrier2", {
        "evidenceType": "DEVICE_RAW_LOG", "content": "冷机日志：-40~-20 持续 8℃以上约 20 分钟"})
    req("POST", f"/api/tickets/{tk2}/evidence", "qc", {
        "evidenceType": "QC_RECHECK", "content": "复检确认乳酸菌失活，改判拒收",
        "conclusions": [{"batchNo": b2, "conclusion": "REJECT"}]})
    req("POST", f"/api/review/tickets/{tk2}/conclusion", "qc", {
        "conclusion": "REAL_EXCURSION", "liabilityParty": "CARRIER"})
    _, tv2b = req("POST", f"/api/tickets/{tk2}/customer-decision", "cs", {
        "decisions": [{"batchNo": b2, "decision": "REJECT", "acceptedPieces": 0}]})
    check(tv2b["status"] == "REVIEWING", "补证重做后双方一致拒收，工单回到复核（升级重新待主管关闭）")
    l1_rows = [e for e in tv2b["escalations"] if e["level"] == 1]
    check(l1_rows[0]["status"] == "PENDING", "被驳回的 L1 在补证重做后重新打开")

    # 重新计算 v2
    req("POST", f"/api/proposals/cold-events/{en2}/generate", "settle", {"reason": "证据替换后重算"})
    props_v2 = req("GET", f"/api/proposals/cold-events/{en2}", "settle")[1]
    v2 = [p for p in props_v2 if p["status"] == "DRAFT"][0]
    check(len([p for p in props_v2 if p["status"] == "SUPERSEDED"]) == 1 and v2["version"] == 2,
          "证据替换 → 旧草稿作废，新版本 v2 重新计算")
    req("POST", f"/api/proposals/{v2['id']}/submit", "settle")
    # L1 通过，赔付=100*60*0.9=5400 ≥ 蒙牛 L2 阈值 5000 → 自动 L2
    req("POST", f"/api/escalations/{l1_rows[0]['id']}/decision", "supervisor",
        {"decision": "APPROVE", "note": "改判合理"})
    _, escs2 = req("GET", f"/api/cold-events/{en2}/escalations", "supervisor")
    l2b = [e for e in escs2 if e["level"] == 2 and e["status"] == "PENDING"]
    check(len(l2b) == 1, "L1 通过且金额超阈值 → 自动升级 L2")
    req("POST", f"/api/escalations/{l2b[0]['id']}/decision", "supervisor",
        {"decision": "APPROVE", "note": "终审同意"})
    req("POST", f"/api/review/tickets/{tk2}/close", "qc", {"note": "闭环"})
    _, posted2 = req("POST", f"/api/proposals/{v2['id']}/post", "settle")
    check(posted2["status"] == "EFFECTIVE"
          and abs(float(posted2["compensation_amount"]) - 5400.0) < 0.01,
          "蒙牛按合同赔付率90%赔付 5400，L1→L2 链完整后才生效")

    # ======================================================================
    # 场景3：同一批次部分可收 / 部分拒收
    # ======================================================================
    print("\n【场景3】同一批次部分可收/部分拒收")
    t3 = book("carrier1", {
        "carrierId": 1, "driverId": 1, "vehiclePlate": "沪CC3003",
        "tempDeviceNo": "DEV-F4-3", "eSealNo": "SEAL-F4-3", "requestedAt": I(240),
        "cargoLines": [{"ownerId": 1, "cargoType": "冷冻牛排", "tempZone": "FROZEN",
                        "maxTempCelsius": -18, "pieces": 100, "targetWarehouse": "W-F3"}]})
    c3, b3 = t3["code"], t3["code"] + "-B1"
    ev3 = admit_and_event(c3, "沪CC3003", "SEAL-F4-3", park)
    en3 = ev3["event_no"]
    req("POST", f"/api/callback/{en3}/chiller", "carrier1", {"events": [
        {"eventId": "Y1", "recordedAt": I(-30), "status": "RUNNING", "celsius": -7.0},
        {"eventId": "Y2", "recordedAt": I(-15), "status": "RUNNING", "celsius": -7.5}]})
    _, ev3 = req("GET", f"/api/cold-events/trips/{c3}", "dispatch")
    tk3 = ticket_nos(ev3, "TEMP_EXCURSION")[0]
    req("POST", f"/api/tickets/{tk3}/evidence", "qc", {
        "evidenceType": "QC_RECHECK", "content": "表层 30 件化冻，内层完好",
        "conclusions": [{"batchNo": b3, "conclusion": "ACCEPT"}]})
    req("POST", f"/api/review/tickets/{tk3}/conclusion", "qc", {
        "conclusion": "REAL_EXCURSION", "liabilityParty": "CARRIER"})
    req("POST", f"/api/tickets/{tk3}/customer-decision", "cs", {
        "decisions": [{"batchNo": b3, "decision": "ACCEPT", "acceptedPieces": 70,
                       "note": "70 件完好可收，30 件拒收"}]})
    req("POST", f"/api/review/tickets/{tk3}/close", "qc", {"note": "部分收"})
    _, calc3 = req("GET", f"/api/proposals/cold-events/{en3}/calculate", "settle")
    check(calc3["acceptablePieces"] == 70 and calc3["rejectedPieces"] == 30
          and abs(float(calc3["lossAmount"]) - 2400) < 0.01
          and abs(float(calc3["compensationAmount"]) - 2400) < 0.01,
          "同批次部分可收70/拒收30：货损与赔付仅按拒收30件=2400")
    req("POST", f"/api/proposals/cold-events/{en3}/generate", "settle", {"reason": "部分收"} )
    p3 = req("GET", f"/api/proposals/cold-events/{en3}", "settle")[1][-1]
    req("POST", f"/api/proposals/{p3['id']}/submit", "settle")
    _, post3 = req("POST", f"/api/proposals/{p3['id']}/post", "settle")
    check(post3["status"] == "EFFECTIVE", "未达 L2 阈值且审批链完整 → 直接生效")

    # ======================================================================
    # 场景4：卸货批次不符（只冻结该批）+ 铅封告警幂等 + 并行补证 + 时限延期
    # ======================================================================
    print("\n【场景4】扫描批次不一致只冻该批·铅封告警幂等·四方并行补证·时限")
    t4 = book("carrier1", {
        "carrierId": 1, "driverId": 1, "vehiclePlate": "沪DD4004",
        "tempDeviceNo": "DEV-F4-4", "eSealNo": "SEAL-F4-4", "requestedAt": I(270),
        "cargoLines": [
            {"ownerId": 1, "cargoType": "冷冻虾仁", "tempZone": "FROZEN",
             "maxTempCelsius": -18, "pieces": 50, "targetWarehouse": "W-F4"},
            {"ownerId": 2, "cargoType": "冻奶油", "tempZone": "FROZEN",
             "maxTempCelsius": -15, "pieces": 30, "targetWarehouse": "W-C4"}]})
    c4 = t4["code"]
    b41, b42 = c4 + "-B1", c4 + "-B2"
    ev4 = admit_and_event(c4, "沪DD4004", "SEAL-F4-4", park)
    en4 = ev4["event_no"]

    _, scan = req("POST", f"/api/events/trips/{c4}/unload-scan", "dispatch", {"batches": [
        {"batchNo": b41, "scannedPieces": 50, "scannedBy": "月台-小赵"},
        {"batchNo": b42, "scannedPieces": 27, "scannedBy": "月台-小赵"}]})
    r41 = [r for r in scan["scanResults"] if r["batchNo"] == b41][0]
    r42 = [r for r in scan["scanResults"] if r["batchNo"] == b42][0]
    check(r41["matched"] is True and r42["matched"] is False,
          "卸货扫描与预约批次核对：B1 一致、B2 件数不符")
    _, ev4 = req("GET", f"/api/cold-events/trips/{c4}", "dispatch")
    mm = ticket_nos(ev4, "BATCH_MISMATCH")
    check(len(mm) == 1 and ev4["tickets"][-1]["batch_no"] == b42, "仅为不符批次 B2 创建工单")
    frozen_batches = {f["batch_no"] for f in ev4["freezes"] if f["status"] == "FROZEN"}
    check(frozen_batches == {b42}, "只冻结不符批次 B2 的正常入库结算，B1 不受影响")

    # 重复扫描同样数据：不新增工单/冻结
    req("POST", f"/api/events/trips/{c4}/unload-scan", "dispatch", {"batches": [
        {"batchNo": b42, "scannedPieces": 27, "scannedBy": "月台-小赵"}]})
    _, ev4b = req("GET", f"/api/cold-events/trips/{c4}", "dispatch")
    check(len(ticket_nos(ev4b, "BATCH_MISMATCH")) == 1
          and len([f for f in ev4b["freezes"] if f["status"] == "FROZEN"]) == 1,
          "重复扫描重试不生成重复工单/冻结")

    # 铅封告警重复/并发回调：仅 1 张铅封工单
    seal_payload = {"events": [{"eventId": "S1", "sealNo": "SEAL-F4-4",
                                "recordedAt": I(-10), "sealStatus": "ALARM"}]}
    outs = []
    def seal_push():
        try: outs.append(req("POST", f"/api/callback/{en4}/seal", "carrier1", seal_payload))
        except Exception as e: outs.append(("ERR", str(e)))
    ts = [threading.Thread(target=seal_push), threading.Thread(target=seal_push)]
    for x in ts: x.start()
    for x in ts: x.join()
    # 再重放一次
    req("POST", f"/api/callback/{en4}/seal", "carrier1", seal_payload)
    _, ev4c = req("GET", f"/api/cold-events/trips/{c4}", "dispatch")
    seal_tks = ticket_nos(ev4c, "SEAL_ABNORMAL")
    check(len(seal_tks) == 1, "重复/并发铅封告警只创建 1 张工单（幂等）")
    seal_frozen = {f["batch_no"] for f in ev4c["freezes"]
                   if f["status"] == "FROZEN" and f["ticket_id"] ==
                   ev4c["tickets"][[t["ticket_no"] for t in ev4c["tickets"]].index(seal_tks[0])]["id"]}
    check(seal_frozen == {b41, b42}, "铅封异常冻结全部批次（B1/B2）")

    # 四方并行补证（同一工单同时提交不同角色证据，各自版本正确）
    seal_tk = seal_tks[0]
    errs = []
    def ev_post(user, payload):
        try: req("POST", f"/api/tickets/{seal_tk}/evidence", user, payload)
        except Exception as e: errs.append(str(e))
    jobs = [
        ("carrier1", {"evidenceType": "DWELLING_NOTE", "content": "运输途中未异常停靠"}),
        ("dispatch", {"evidenceType": "GATE_RECORD", "content": "闸口复检封签卡扣松动误报，封条编号一致"}),
        ("qc", {"evidenceType": "QC_RECHECK", "content": "开封抽检无异常",
                "conclusions": [{"batchNo": b41, "conclusion": "ACCEPT"},
                                {"batchNo": b42, "conclusion": "DOWNGRADE"}]}),
    ]
    threads = [threading.Thread(target=ev_post, args=j) for j in jobs]
    for x in threads: x.start()
    for x in threads: x.join()
    check(not errs, "四方并行补证无并发错误：" + ";".join(errs))
    _, sealview = req("GET", f"/api/tickets/{seal_tk}", "settle")
    roles = {(e["role"], e["version"]) for e in sealview["evidence"]}
    check(("CARRIER", 1) in roles and ("PARK", 1) in roles and ("QC", 1) in roles,
          "并行补证各角色证据版本独立正确（v1 并存）")

    # 园区补证可再交一版（版本2，旧版保留）
    req("POST", f"/api/tickets/{seal_tk}/evidence", "dispatch", {
        "evidenceType": "UNLOAD_RECORD", "content": "卸货监控显示封签在月台由工作人员开启"})
    _, sealview2 = req("GET", f"/api/tickets/{seal_tk}", "settle")
    park_evs = [e for e in sealview2["evidence"] if e["role"] == "PARK"]
    check(len(park_evs) == 2 and park_evs[0]["superseded"] and not park_evs[1]["superseded"],
          "园区两版证据均保留（闸口/卸货记录），版本链可审计")

    # 客户决定（含 B2 降级）+ 铅封免责复盘后闭环
    req("POST", f"/api/tickets/{seal_tk}/customer-decision", "cs", {"decisions": [
        {"batchNo": b41, "decision": "ACCEPT", "acceptedPieces": 50},
        {"batchNo": b42, "decision": "DOWNGRADE", "acceptedPieces": 27}]})
    req("POST", f"/api/review/tickets/{seal_tk}/conclusion", "dispatch", {
        "liabilityParty": "PARK", "note": "月台误操作致告警，园区责任但无货损"})
    req("POST", f"/api/review/tickets/{seal_tk}/close", "dispatch", {"note": "园区月台责任，无货损"})
    mm_tk = mm[0]
    req("POST", f"/api/tickets/{mm_tk}/evidence", "dispatch", {
        "evidenceType": "UNLOAD_RECORD", "content": "复核 B2 实际 27 件入库，3 件破损，修正预约差异"})
    req("POST", f"/api/tickets/{mm_tk}/customer-decision", "cs", {"decisions": [
        {"batchNo": b42, "decision": "DOWNGRADE", "acceptedPieces": 27}]})
    req("POST", f"/api/review/tickets/{mm_tk}/conclusion", "dispatch", {
        "liabilityParty": "CARRIER", "note": "短装 3 件由承运商承担"})
    # 非主管不能延期（QC 应被拒）
    s, _ = req("POST", f"/api/review/tickets/{mm_tk}/extend-due", "qc", {"hours": 12}, expect=403)
    check(s == 403, "时限延期仅限审批主管")
    req("POST", f"/api/review/tickets/{mm_tk}/extend-due", "supervisor", {"hours": 12})
    req("POST", f"/api/review/tickets/{mm_tk}/close", "dispatch", {"note": "短装闭环"})
    _, ev4d = req("GET", f"/api/cold-events/trips/{c4}", "dispatch")
    check(all(f["status"] == "RELEASED" for f in ev4d["freezes"]), "全部工单闭环后批次冻结均解除")
    _, audit4 = req("GET", f"/api/audit/trips/{c4}", "dispatch")
    bpk = {b["breakpointKind"] for b in audit4["breakpoints"]}
    check({"BATCH", "SEAL"}.issubset(bpk),
          "审计页逐断点列出 BATCH/SEAL 的证据、处置、责任结论与赔付状态")
    check(any(t["ticket_no"] == mm_tk and len(t["evidenceVersions"]) >= 1 for t in audit4["tickets"]),
          "审计页工单含逐版本证据（提交人/时间/是否被替换）")

    print(f"\n================ feature-4-b 冒烟：通过 {PASS} 项，失败 {FAIL} 项 ================")
    sys.exit(1 if FAIL else 0)


if __name__ == "__main__":
    main()
