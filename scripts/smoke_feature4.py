#!/usr/bin/env python3
"""feature-4 端到端冒烟：冷链事件时间线 / 自动冻结 / 多方补证复盘 / 赔付审批 / 幂等并发。

覆盖：
A. 主流程：超温合并区间+断点+铅封异常+扫描不一致 → 自动冻结+工单 → 四角色补证（版本链）→
   客户部分拒收（与质检冲突升级）→ 结案解冻 → 赔付候选 → 证据替换重算 → 审批驳回重算 →
   审批链走完生效 → 结算写入 → 一页审计时间线；
B. 传感器离线：断点+设备日志在阈内 → SENSOR_OFFLINE → 合同阈值内赔付豁免（0 元）；
C. 幂等与并发：重复回调/重试不产生重复冻结/工单/赔付，并发补证版本唯一，并发重算安全。
"""
import base64
import json
import os
import sys
import time
import urllib.request
import urllib.error
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta

PORT = os.environ.get("CC_PUBLISH_PORT", "3194")
BASE = os.environ.get("CC_BASE_URL", f"http://host.docker.internal:{PORT}").rstrip("/")

USERS = {
    "carrier1": "carrier123", "carrier2": "carrier123",
    "dispatch": "dispatch123", "qc": "qc123", "cs": "cs123", "settle": "settle123",
    "customer1": "customer123", "customer2": "customer123", "customer3": "customer123",
}

PASS, FAIL = 0, 0


def req(method, path, user=None, body=None, expect=200):
    url = BASE + path
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        r.add_header("Content-Type", "application/json")
    if user:
        token = base64.b64encode(f"{user}:{USERS[user]}".encode()).decode()
        r.add_header("Authorization", "Basic " + token)
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
        time.sleep(5)
    die("服务健康检查在 300 秒内未通过")


def freezes_of(audit):
    """审计视图中的全部冻结记录（按批次+原因）。"""
    out = {}
    for a in audit["anomalies"]:
        for f in a["freezes"]:
            out[(f["batch_code"], f["reason"])] = f["status"]
    return out


def main():
    print(f"目标服务：{BASE}")
    health = wait_health()
    park = datetime.fromisoformat(health["parkTime"])

    def iso(offset_min):
        return (park + timedelta(minutes=offset_min)).replace(second=0, microsecond=0).isoformat()

    # ==================================================================
    print("\n【场景A】超温合并区间+断点+铅封+扫描不一致 → 冻结/补证/复盘/赔付审批/结算/审计")
    # ---------- 预约（声明卸货批次） ----------
    _, t = req("POST", "/api/appointments", "carrier1", {
        "carrierId": 1, "driverId": 1, "vehiclePlate": "沪F40001",
        "tempDeviceNo": "RF-SF-001", "eSealNo": "SEAL-F4A", "requestedAt": iso(60),
        "cargoLines": [
            {"ownerId": 1, "cargoType": "冷冻牛肉", "tempZone": "FROZEN", "maxTempCelsius": -18,
             "pieces": 100, "batches": [
                 {"batchCode": "HT-A001", "expectedQty": 60},
                 {"batchCode": "HT-A002", "expectedQty": 40}]},
            {"ownerId": 2, "cargoType": "冷冻虾仁", "tempZone": "FROZEN", "maxTempCelsius": -18,
             "pieces": 80, "batches": [
                 {"batchCode": "MN-B001", "expectedQty": 80}]}]})
    cA = t["code"]
    lines = {x["owner_id"]: x["id"] for x in t["cargoLines"]}
    check(len(t["batches"]) == 3, f"预约单声明 3 个卸货批次（{cA}）")

    req("POST", "/api/gate/checkin", "dispatch",
        {"code": cA, "vehiclePlate": "沪F40001", "eSealNo": "SEAL-F4A"})
    req("POST", f"/api/dock/trips/{cA}/receive", "dispatch")

    # ---------- 设备心跳（断点窗口内离线） ----------
    req("POST", "/api/callbacks/heartbeat", "carrier1", {"deviceNo": "RF-SF-001", "beats": [
        {"beatAt": iso(-300), "online": True}, {"beatAt": iso(-220), "online": False},
        {"beatAt": iso(-200), "online": False}, {"beatAt": iso(-150), "online": True}]})

    # ---------- 温度回调：两段超温（间隔14分钟应合并）+ 80分钟断点 ----------
    temp_body = {"tripCode": cA, "idempotencyKey": f"TEMP-{cA}-1", "readings": [
        {"recordedAt": iso(-300), "celsius": -19.0},
        {"recordedAt": iso(-290), "celsius": -19.0},
        {"recordedAt": iso(-280), "celsius": -10.0},
        {"recordedAt": iso(-270), "celsius": -8.0},
        {"recordedAt": iso(-262), "celsius": -18.5},
        {"recordedAt": iso(-256), "celsius": -11.0},
        {"recordedAt": iso(-240), "celsius": -18.0},
        {"recordedAt": iso(-160), "celsius": -18.0},
        {"recordedAt": iso(-150), "celsius": -18.0}]}
    _, tc = req("POST", "/api/callbacks/temperature", "carrier1", temp_body)
    kinds = sorted(a["type"] for a in tc["anomalies"])
    check(kinds == ["DATA_GAP", "TEMP_EXCURSION"], f"检出 1 个超温合并区间 + 1 个断点：{kinds}")
    exc = next(a for a in tc["anomalies"] if a["type"] == "TEMP_EXCURSION")
    check(exc["mergedCount"] == 2, "两段相邻超温（间隔≤15分钟）合并为一个异常区间")

    # ---------- 舱门 / 铅封回调 ----------
    req("POST", "/api/callbacks/door", "carrier1",
        {"tripCode": cA, "deviceNo": "DOOR-S-1", "event": "OPEN",
         "occurredAt": iso(-100), "eventUid": f"D-{cA}-1"})
    req("POST", "/api/callbacks/door", "carrier1",
        {"tripCode": cA, "deviceNo": "DOOR-S-1", "event": "CLOSE",
         "occurredAt": iso(-90), "eventUid": f"D-{cA}-2"})
    _, seal = req("POST", "/api/callbacks/seal", "carrier1",
                  {"tripCode": cA, "sealNo": "SEAL-F4A", "event": "TAMPERED",
                   "occurredAt": iso(-200), "eventUid": f"S-{cA}-1"})
    check(seal.get("frozen") is True, "铅封 TAMPERED 自动冻结全车批次并建工单")

    # ---------- 冻结与工单核验 ----------
    _, audit = req("GET", f"/api/audit/trips/{cA}", "dispatch")
    fz = freezes_of(audit)
    check(len([v for v in fz.values() if v == "FROZEN"]) == 9,
          f"3 批次 × 3 原因（超温/断点/铅封）= 9 条冻结，实际 {len(fz)} 条 {fz}")
    _, wos = req("GET", f"/api/work-orders?tripCode={cA}", "dispatch")
    check(len(wos) == 3, f"自动创建 3 个异常工单，实际 {len(wos)}")

    # ---------- 幂等：重复回调/重试 ----------
    _, dup1 = req("POST", "/api/callbacks/temperature", "carrier1", temp_body)
    check(dup1.get("duplicated") is True, "同幂等键温度回调 → duplicated=true")
    temp_body2 = dict(temp_body)
    temp_body2.pop("idempotencyKey")
    req("POST", "/api/callbacks/temperature", "carrier1", temp_body2)
    _, dup2 = req("POST", "/api/callbacks/seal", "carrier1",
                  {"tripCode": cA, "sealNo": "SEAL-F4A", "event": "TAMPERED",
                   "occurredAt": iso(-200), "eventUid": f"S-{cA}-1"})
    check(dup2.get("duplicated") is True, "同 eventUid 铅封回调 → duplicated=true")
    _, audit2 = req("GET", f"/api/audit/trips/{cA}", "dispatch")
    _, wos2 = req("GET", f"/api/work-orders?tripCode={cA}", "dispatch")
    check(len(freezes_of(audit2)) == 9 and len(wos2) == 3,
          "重复回调/重试后冻结与工单数量不变（无重复冻结/重复工单）")

    # ---------- 卸货扫描比对 ----------
    _, s1 = req("POST", "/api/callbacks/unload-scan", "qc",
                {"tripCode": cA, "batchCode": "HT-A001", "qty": 60,
                 "scannedAt": iso(-95), "scanUid": f"SC-{cA}-1"})
    check(s1.get("scanResult") == "MATCHED", "HT-A001 扫描 60=预约 60 → MATCHED")
    _, s2 = req("POST", "/api/callbacks/unload-scan", "qc",
                {"tripCode": cA, "batchCode": "HT-A002", "qty": 35,
                 "scannedAt": iso(-94), "scanUid": f"SC-{cA}-2"})
    check(s2.get("scanResult") == "MISMATCH" and s2.get("frozen") is True,
          "HT-A002 扫描 35≠预约 40 → MISMATCH 并冻结该批次")
    _, s3 = req("POST", "/api/callbacks/unload-scan", "qc",
                {"tripCode": cA, "batchCode": "XX-999", "qty": 5,
                 "scannedAt": iso(-93), "scanUid": f"SC-{cA}-3"})
    check(s3.get("scanResult") == "EXTRA", "预约外批次 XX-999 → EXTRA，全车冻结")
    _, dup3 = req("POST", "/api/callbacks/unload-scan", "qc",
                  {"tripCode": cA, "batchCode": "HT-A002", "qty": 35,
                   "scannedAt": iso(-94), "scanUid": f"SC-{cA}-2"})
    check(dup3.get("duplicated") is True, "重复扫描回调去重（不重复冻结）")
    _, wos3 = req("GET", f"/api/work-orders?tripCode={cA}", "dispatch")
    check(len(wos3) == 5, f"扫描不一致新增 2 个工单（共 5 个），实际 {len(wos3)}")

    wo_exc = next(w for w in wos3 if w["type"] == "TEMP_EXCURSION")
    wo_gap = next(w for w in wos3 if w["type"] == "DATA_GAP")
    wo_seal = next(w for w in wos3 if w["type"] == "SEAL_ABNORMAL")
    wo_scan = [w for w in wos3 if w["type"] == "SCAN_MISMATCH"]

    # ---------- 角色补证限定 ----------
    s, _ = req("POST", f"/api/work-orders/{wo_exc['id']}/evidence", "carrier1",
               {"evidenceType": "GATE_RECORD", "content": "越权"}, expect=400)
    check(s == 400, "承运商提交园区证据类型被拒绝（角色证据限定）")
    s, _ = req("POST", f"/api/work-orders/{wo_exc['id']}/evidence", "customer1",
               {"evidenceType": "DEVICE_LOG", "content": "越权"}, expect=403)
    check(s == 403, "客户账号不能提交证据（仅可走收货决定）")

    # ---------- 并发补证：版本唯一 ----------
    def submit_stop_note(i):
        return req("POST", f"/api/work-orders/{wo_exc['id']}/evidence", "carrier1",
                   {"evidenceType": "STOP_NOTE", "content": f"停靠说明 v{i}：服务区排队等待"},
                   expect=None)[0]
    with ThreadPoolExecutor(max_workers=6) as pool:
        codes = list(pool.map(submit_stop_note, range(6)))
    check(all(c == 200 for c in codes), f"6 路并发补证全部成功（{codes}）")
    _, det = req("GET", f"/api/work-orders/{wo_exc['id']}", "dispatch")
    notes = [e for e in det["evidence"] if e["evidence_type"] == "STOP_NOTE"]
    versions = sorted(e["version"] for e in notes)
    active = [e for e in notes if e["active"]]
    check(versions == [1, 2, 3, 4, 5, 6] and len(active) == 1 and active[0]["version"] == 6,
          f"并发补证版本链完整 1..6，仅 v6 有效（历史版本保留不覆盖）：{versions}")

    # ---------- 设备原始日志 → 自动区分传感器离线/真实超温 ----------
    device_log = {"readings": [
        {"at": iso(-275), "celsius": -9.0}, {"at": iso(-258), "celsius": -8.5},
        {"at": iso(-230), "celsius": -18.5}, {"at": iso(-180), "celsius": -18.2}]}
    req("POST", f"/api/work-orders/{wo_exc['id']}/evidence", "carrier1",
        {"evidenceType": "DEVICE_LOG", "content": json.dumps(device_log)})
    _, det = req("GET", f"/api/work-orders/{wo_exc['id']}", "dispatch")
    _, det_gap = req("GET", f"/api/work-orders/{wo_gap['id']}", "dispatch")
    check(det["classification"] == "REAL_EXCURSION",
          f"超温区间设备日志峰值超阈 → 真实超温（{det['classification']}）")
    check(det_gap["classification"] == "SENSOR_OFFLINE",
          f"断点区间日志温度在阈内 → 传感器离线（{det_gap['classification']}）")

    # ---------- 园区/质检补证 ----------
    req("POST", f"/api/work-orders/{wo_exc['id']}/evidence", "dispatch",
        {"evidenceType": "GATE_RECORD", "content": "闸口记录：10:02 入园，封签外观完好"})
    req("POST", f"/api/work-orders/{wo_exc['id']}/evidence", "qc",
        {"evidenceType": "QC_RECHECK", "content": json.dumps({"result": "PASS", "note": "复检未发现化冻"})})

    # ---------- 客户收货决定（部分可收/部分拒收 + 冲突升级） ----------
    _, batches_view = req("GET", f"/api/appointments/{cA}", "dispatch")
    bid = {b["batch_code"]: b["id"] for b in batches_view["batches"]}
    req("POST", f"/api/work-orders/{wo_exc['id']}/customer-decision", "customer1",
        {"batchId": bid["HT-A001"], "decision": "ACCEPT", "acceptQty": 60, "rejectQty": 0})
    _, dec2 = req("POST", f"/api/work-orders/{wo_exc['id']}/customer-decision", "customer1",
                  {"batchId": bid["HT-A002"], "decision": "REJECT", "acceptQty": 30, "rejectQty": 10})
    check(dec2.get("escalated") is True, "客户拒收与质检 PASS 冲突 → 工单自动升级")
    req("POST", f"/api/work-orders/{wo_exc['id']}/customer-decision", "customer2",
        {"batchId": bid["MN-B001"], "decision": "DOWNGRADE", "acceptQty": 60, "rejectQty": 20})
    s, _ = req("POST", f"/api/work-orders/{wo_exc['id']}/customer-decision", "customer1",
               {"batchId": bid["MN-B001"], "decision": "REJECT", "acceptQty": 0, "rejectQty": 80},
               expect=409)
    check(s == 409, "客户不能决定其他货主的批次")
    s, _ = req("POST", f"/api/work-orders/{wo_exc['id']}/customer-decision", "customer2",
               {"batchId": bid["MN-B001"], "decision": "REJECT", "acceptQty": 10, "rejectQty": 10},
               expect=400)
    check(s == 400, "可收+拒收必须等于批次预约数量（部分可收/部分拒收校验）")

    # ---------- 走完质检/处置流程进入结算 ----------
    req("POST", f"/api/dock/trips/{cA}/unload", "qc", {
        "doorOpenTempCelsius": -17.5, "unloadPhotos": "http://img/f4a.jpg",
        "cargoDamages": [{"cargoId": lines[1], "damagedPieces": 0},
                         {"cargoId": lines[2], "damagedPieces": 0}]})
    req("POST", f"/api/qc/trips/{cA}/sample", "qc", {"samples": [
        {"cargoId": lines[1], "sampleTotal": 10, "sampleThawed": 0},
        {"cargoId": lines[2], "sampleTotal": 10, "sampleThawed": 0}]})
    req("POST", f"/api/disposition/trips/{cA}/decide", "qc", {
        "decisions": [{"cargoId": lines[1], "disposition": "RELEASED"},
                      {"cargoId": lines[2], "disposition": "RELEASED"}],
        "note": "复检合格放行，异常批次走赔付流程"})

    # ---------- 冻结中禁止结算 ----------
    s, _ = req("POST", f"/api/settle/trips/{cA}/confirm", "settle", {}, expect=409)
    check(s == 409, "批次冻结中 → 入库结算被阻断")

    # ---------- 结案解冻 ----------
    for w in [wo_exc, wo_gap, wo_seal] + wo_scan:
        req("POST", f"/api/work-orders/{w['id']}/resolve", "dispatch",
            {"responsibility": "CARRIER", "note": "复盘定责：承运商冷机故障主责"})
    _, audit3 = req("GET", f"/api/audit/trips/{cA}", "dispatch")
    check(all(v == "RELEASED" for v in freezes_of(audit3).values()),
          "全部工单结案后冻结解除")

    # ---------- 赔付候选：责任/可收数量/合同阈值/客户选择 ----------
    _, payouts = req("GET", f"/api/payouts?tripCode={cA}", "settle")
    latest = {}
    for p in payouts:
        if p["status"] in ("DRAFT", "IN_APPROVAL"):
            latest[p["batch_id"]] = p
    amounts = {b["batch_code"]: float(latest[b["id"]]["amount"]) for b in batches_view["batches"]
               if b["id"] in latest}
    check(amounts.get("HT-A001") == 0.0, f"HT-A001 客户继续收货 → 赔付 0（{amounts}）")
    check(amounts.get("HT-A002") == 800.0, f"HT-A002 拒收 10×80 → 赔付 800（{amounts}）")
    check(amounts.get("MN-B001") == 480.0, f"MN-B001 降级 20×60×0.4 → 赔付 480（{amounts}）")

    # ---------- 并发重算安全 ----------
    with ThreadPoolExecutor(max_workers=4) as pool:
        codes = list(pool.map(
            lambda _: req("POST", f"/api/payouts/trips/{cA}/compute", "settle", expect=None)[0],
            range(4)))
    check(all(c == 200 for c in codes), f"4 路并发重算无 5xx（{codes}）")
    _, payouts = req("GET", f"/api/payouts?tripCode={cA}", "settle")
    drafts = [p for p in payouts if p["status"] == "DRAFT"]
    check(len(drafts) == 3, f"并发重算后每批次恰有 1 条 DRAFT（共 {len(drafts)} 条）")

    # ---------- 证据替换 → 撤销未生效草稿并重算 ----------
    before = {p["batch_id"]: p["calc_version"] for p in drafts}
    device_log2 = {"readings": [
        {"at": iso(-275), "celsius": -9.5}, {"at": iso(-258), "celsius": -8.5},
        {"at": iso(-230), "celsius": -18.5}, {"at": iso(-180), "celsius": -18.2}]}
    req("POST", f"/api/work-orders/{wo_exc['id']}/evidence", "carrier1",
        {"evidenceType": "DEVICE_LOG", "content": json.dumps(device_log2)})
    _, payouts = req("GET", f"/api/payouts?tripCode={cA}", "settle")
    revoked = [p for p in payouts if p["status"] == "REVOKED"]
    drafts2 = [p for p in payouts if p["status"] == "DRAFT"]
    check(len(revoked) >= 3 and len(drafts2) == 3
          and all(drafts2_by["calc_version"] > before[drafts2_by["batch_id"]]
                  for drafts2_by in drafts2),
          "证据替换 → 未生效草稿撤销并以新版本重算")

    # ---------- 审批链（升级单：QC→DISPATCH→SETTLE） ----------
    p_ht2 = next(p for p in drafts2 if p["batch_id"] == bid["HT-A002"])
    _, sub = req("POST", f"/api/payouts/{p_ht2['id']}/submit", "settle")
    roles = [s["approver_role"] for s in sub["approvalSteps"]]
    check(roles == ["QC", "DISPATCH", "SETTLE"], f"冲突升级批次审批链追加质检复核：{roles}")
    s, _ = req("POST", f"/api/payouts/{p_ht2['id']}/approve", "dispatch", {}, expect=409)
    check(s == 409, "审批顺序管控：未到园区环节不能越级审批")

    # ---------- 审批驳回 → 撤销重算 ----------
    req("POST", f"/api/payouts/{p_ht2['id']}/approve", "qc", {"comment": "质检复核通过"})
    _, rej = req("POST", f"/api/payouts/{p_ht2['id']}/reject", "dispatch",
                 {"comment": "金额需复核"})
    check(rej["status"] == "REJECTED", "园区驳回赔付单")
    _, payouts = req("GET", f"/api/payouts?tripCode={cA}", "settle")
    again = [p for p in payouts if p["batch_id"] == bid["HT-A002"] and p["status"] == "DRAFT"]
    check(len(again) == 1 and again[0]["calc_version"] > p_ht2["calc_version"],
          "驳回后自动撤销未生效草稿并重算新版本")

    # ---------- 全部赔付走完审批链生效 ----------
    for p in [p for p in payouts if p["status"] == "DRAFT"]:
        _, sub = req("POST", f"/api/payouts/{p['id']}/submit", "settle")
        for role_user in ["qc", "dispatch", "settle"]:
            req("POST", f"/api/payouts/{p['id']}/approve", role_user, {"comment": "同意"})
    _, payouts = req("GET", f"/api/payouts?tripCode={cA}", "settle")
    eff = [p for p in payouts if p["status"] == "EFFECTIVE"]
    check(len(eff) == 3 and abs(sum(float(p["amount"]) for p in eff) - 1280.0) < 0.01,
          f"3 条赔付全部生效，合计 1280（实际 {sum(float(p['amount']) for p in eff)}）")
    per_batch = {}
    for p in eff:
        per_batch[p["batch_id"]] = per_batch.get(p["batch_id"], 0) + 1
    check(all(v == 1 for v in per_batch.values()), "每批次恰 1 条生效赔付（无重复赔付）")

    # ---------- 结算写入 ----------
    _, st = req("POST", f"/api/settle/trips/{cA}/confirm", "settle",
                {"liabilityPartyOverride": "CARRIER"})
    check(st["status"] == "DONE" and float(st["compensation_amount"]) == 1280.0,
          f"审批链完整后结算写入：客户赔付 1280（实际 {st['compensation_amount']}）")

    # ---------- 一页可审计时间线 ----------
    _, audit = req("GET", f"/api/audit/trips/{cA}", "cs")
    check(len(audit["anomalies"]) == 5 and len(audit["timeline"]) > 15,
          "审计页：5 个异常断点 + 统一事件时间线")
    a_exc = next(a for a in audit["anomalies"] if a["type"] == "TEMP_EXCURSION")
    ev_types = {e["evidence_type"] for e in a_exc["evidence"]}
    check({"DEVICE_LOG", "STOP_NOTE", "GATE_RECORD", "QC_RECHECK", "CUSTOMER_DECISION"} <= ev_types,
          f"断点证据链完整（四角色留痕）：{sorted(ev_types)}")
    replaced = [e for e in a_exc["evidence"] if not e["active"]]
    check(len(replaced) >= 1, "被替换的旧版本证据保留可查（不覆盖原始数据）")
    check(a_exc["responsibility"] == "CARRIER" and a_exc["classification"] == "REAL_EXCURSION",
          "断点责任结论与分类落档")
    check(audit["settlement"]["frozenBatches"] == 0
          and len(audit["settlement"]["effectivePayouts"]) == 3,
          "审计页结算区：无冻结残留，3 条生效赔付")
    check("credit" in json.dumps(audit["followUpImpact"]).lower()
          or "信用分" in audit["followUpImpact"]["impactNote"],
          "审计页含后续预约影响（信用分/优先级）")
    times = [e["at"] for e in audit["timeline"]]
    check(times == sorted(times), "时间线严格按时间排序")

    # ==================================================================
    print("\n【场景B】传感器离线（非真实超温）→ 合同阈值内赔付豁免")
    _, tB = req("POST", "/api/appointments", "carrier2", {
        "carrierId": 2, "driverId": 3, "vehiclePlate": "京F40002",
        "tempDeviceNo": "RF-JD-002", "eSealNo": "SEAL-F4B", "requestedAt": iso(60),
        "cargoLines": [
            {"ownerId": 3, "cargoType": "冷藏果蔬", "tempZone": "CHILLED",
             "maxTempCelsius": 4, "pieces": 50}]})
    cB = tB["code"]
    lineB = tB["cargoLines"][0]["id"]
    req("POST", "/api/gate/checkin", "dispatch",
        {"code": cB, "vehiclePlate": "京F40002", "eSealNo": "SEAL-F4B"})
    req("POST", f"/api/dock/trips/{cB}/receive", "dispatch")
    req("POST", "/api/callbacks/heartbeat", "carrier2", {"deviceNo": "RF-JD-002", "beats": [
        {"beatAt": iso(-170), "online": False}, {"beatAt": iso(-140), "online": False}]})
    req("POST", "/api/callbacks/temperature", "carrier2", {"tripCode": cB, "readings": [
        {"recordedAt": iso(-200), "celsius": 3.0},
        {"recordedAt": iso(-190), "celsius": 3.5},
        {"recordedAt": iso(-100), "celsius": 3.0},
        {"recordedAt": iso(-90), "celsius": 3.0}]})
    _, wosB = req("GET", f"/api/work-orders?tripCode={cB}", "dispatch")
    check(len(wosB) == 1 and wosB[0]["type"] == "DATA_GAP", "断点 90 分钟 → 1 个断点工单")
    woB = wosB[0]
    logB = {"readings": [
        {"at": iso(-160), "celsius": 3.2}, {"at": iso(-130), "celsius": 3.8}]}
    req("POST", f"/api/work-orders/{woB['id']}/evidence", "carrier2",
        {"evidenceType": "DEVICE_LOG", "content": json.dumps(logB)})
    _, detB = req("GET", f"/api/work-orders/{woB['id']}", "dispatch")
    check(detB["classification"] == "SENSOR_OFFLINE",
          "断点窗口设备日志温度在阈内 → 判定传感器离线（非真实超温）")

    _, batchesB = req("GET", f"/api/appointments/{cB}", "dispatch")
    bidB = batchesB["batches"][0]["id"]
    req("POST", f"/api/work-orders/{woB['id']}/customer-decision", "customer3",
        {"batchId": bidB, "decision": "DOWNGRADE", "acceptQty": 40, "rejectQty": 10})
    req("POST", f"/api/dock/trips/{cB}/unload", "qc", {
        "doorOpenTempCelsius": 3.2, "cargoDamages": [{"cargoId": lineB, "damagedPieces": 0}]})
    req("POST", f"/api/qc/trips/{cB}/sample", "qc", {"samples": [
        {"cargoId": lineB, "sampleTotal": 10, "sampleThawed": 0}]})
    req("POST", f"/api/disposition/trips/{cB}/decide", "qc", {
        "decisions": [{"cargoId": lineB, "disposition": "RELEASED"}], "note": "放行"})
    req("POST", f"/api/work-orders/{woB['id']}/resolve", "dispatch",
        {"responsibility": "CARRIER", "note": "传感器传输故障，非真实超温"})
    _, payoutsB = req("GET", f"/api/payouts?tripCode={cB}", "settle")
    draftB = [p for p in payoutsB if p["status"] == "DRAFT"]
    check(len(draftB) == 1 and float(draftB[0]["amount"]) == 0.0
          and "豁免" in draftB[0]["basis"],
          f"传感器离线+合同阈值内 → 赔付豁免（0 元）：{draftB[0]['basis'][:80] if draftB else '无'}")
    _, subB = req("POST", f"/api/payouts/{draftB[0]['id']}/submit", "settle")
    rolesB = [s["approver_role"] for s in subB["approvalSteps"]]
    check(rolesB == ["DISPATCH", "SETTLE"], f"无冲突升级 → 标准两级审批链：{rolesB}")
    req("POST", f"/api/payouts/{draftB[0]['id']}/approve", "dispatch", {})
    req("POST", f"/api/payouts/{draftB[0]['id']}/approve", "settle", {})
    _, stB = req("POST", f"/api/settle/trips/{cB}/confirm", "settle", {})
    check(stB["status"] == "DONE" and float(stB["compensation_amount"]) == 0.0,
          "传感器离线场景结算完成，赔付 0")

    # ==================================================================
    print("\n【场景C】并发回调幂等（多路重复设备回调不产生重复数据）")
    dup_readings = {"tripCode": cB, "readings": [
        {"recordedAt": iso(-80), "celsius": 3.1},
        {"recordedAt": iso(-70), "celsius": 3.2}]}
    with ThreadPoolExecutor(max_workers=8) as pool:
        results = list(pool.map(
            lambda _: req("POST", "/api/callbacks/temperature", "carrier2", dup_readings,
                          expect=None),
            range(8)))
    check(all(c == 200 for c, _ in results), "8 路并发相同温度回调全部受理")
    inserted = sum(r.get("inserted", 0) for _, r in results)
    check(inserted == 2, f"相同温度点全局只落 2 条（实际 {inserted}）")
    _, wosB2 = req("GET", f"/api/work-orders?tripCode={cB}", "dispatch")
    check(len(wosB2) == 1, "并发回调不产生重复工单")

    # ==================================================================
    print(f"\n结果：通过 {PASS} 项，失败 {FAIL} 项")
    if FAIL:
        sys.exit(1)
    print("🎉 feature-4 冒烟全部通过")


if __name__ == "__main__":
    main()
