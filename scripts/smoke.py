#!/usr/bin/env python3
"""冷链预约入场服务端到端冒烟脚本（仅依赖标准库 + curl 外的 urllib）。"""
import base64
import json
import os
import sys
import time
import urllib.request
import urllib.error
from datetime import datetime, timedelta

PORT = os.environ.get("CC_PUBLISH_PORT", "3194")
BASE = os.environ.get("CC_BASE_URL", f"http://host.docker.internal:{PORT}").rstrip("/")

USERS = {
    "carrier1": "carrier123",
    "carrier2": "carrier123",
    "dispatch": "dispatch123",
    "qc": "qc123",
    "cs": "cs123",
    "settle": "settle123",
    "customer1": "customer123",
    "customer2": "customer123",
    "customer3": "customer123",
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
            status = resp.status
            raw = resp.read().decode()
    except urllib.error.HTTPError as e:
        status = e.code
        raw = e.read().decode()
    try:
        parsed = json.loads(raw) if raw else {}
    except json.JSONDecodeError:
        parsed = {"_raw": raw}
    if expect is not None and status != expect:
        die(f"{method} {path} 期望 HTTP {expect}，实际 {status}：{raw[:400]}")
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


def incident_id(trip, itype):
    for inc in trip["incidents"]:
        if inc["type"] == itype and inc["status"] == "OPEN":
            return inc["id"]
    return None


def main():
    print(f"目标服务：{BASE}")
    health = wait_health()
    park = datetime.fromisoformat(health["parkTime"])

    def iso(offset_min):
        return (park + timedelta(minutes=offset_min)).replace(second=0, microsecond=0).isoformat()

    # 鉴权：无密码应 401
    s, _ = req("GET", "/api/whoami", expect=401)
    check(s == 401, "未认证访问 /api/whoami 返回 401")
    s, who = req("GET", "/api/whoami", user="dispatch")
    check(who["role"] == "DISPATCH", "dispatch 账号角色正确")

    # ============ 场景一：多货主混装正常车，全流程放行 ============
    print("\n【场景1】多货主混装 · 正常放行 · 结算无责 · 客户签收 · 质量追溯")
    _, t1 = req("POST", "/api/appointments", "carrier1", {
        "carrierId": 1, "driverId": 1, "vehiclePlate": "沪A12345",
        "tempDeviceNo": "DEV-T1", "eSealNo": "SEAL-T1", "requestedAt": iso(60),
        "cargoLines": [
            {"ownerId": 1, "cargoType": "冷冻牛排", "tempZone": "FROZEN",
             "maxTempCelsius": -18, "pieces": 60, "targetWarehouse": "W-F1"},
            {"ownerId": 2, "cargoType": "低温酸奶", "tempZone": "CHILLED",
             "maxTempCelsius": 4, "pieces": 40, "targetWarehouse": "W-C1"}]})
    c1 = t1["code"]
    c1_lines = {x["owner_id"]: x["id"] for x in t1["cargoLines"]}
    check(t1["status"] == "BOOKED", f"预约成功 {c1}，状态 BOOKED")
    check(t1["mixed_loading"] is True and t1["temp_zone"] == "FROZEN",
          "识别为多货主混装，主温区按最严格取 FROZEN")
    check(t1["window_start"] >= iso(60), "系统按月台容量/质检班次/优先级生成入场窗口")

    _, g = req("POST", "/api/gate/checkin", "dispatch",
               {"code": c1, "vehiclePlate": "沪A12345", "eSealNo": "SEAL-T1"})
    check(g["admitted"] is True and g["status"] == "AT_GATE", "闸口核验预约/车牌/封签一致，放行入园")

    req("POST", f"/api/dock/trips/{c1}/receive", "dispatch")
    _, t1 = req("GET", f"/api/appointments/{c1}", "dispatch")
    check(t1["status"] == "AT_DOCK", "月台接车，卸货计时开始")

    req("POST", f"/api/dock/trips/{c1}/temperature", "carrier1", {"readings": [
        {"recordedAt": iso(-120), "celsius": -20.0},
        {"recordedAt": iso(-100), "celsius": -19.8},
        {"recordedAt": iso(-80), "celsius": -19.5}]})

    _, u = req("POST", f"/api/dock/trips/{c1}/unload", "qc", {
        "doorOpenTempCelsius": -19.2,
        "unloadPhotos": "http://img/t1-door.jpg;http://img/t1-unload.jpg",
        "cargoDamages": [
            {"cargoId": c1_lines[1], "damagedPieces": 0},
            {"cargoId": c1_lines[2], "damagedPieces": 0}]})
    check(u["status"] == "IN_QC", "上传开门前温度/卸货照片/破损件数，进入质检")

    _, q = req("POST", f"/api/qc/trips/{c1}/sample", "qc", {"samples": [
        {"cargoId": c1_lines[1], "sampleTotal": 10, "sampleThawed": 0},
        {"cargoId": c1_lines[2], "sampleTotal": 10, "sampleThawed": 0}]})
    check(q["status"] == "AWAIT_DISPOSITION", "质检抽样无化冻，待处置决定")

    _, d = req("POST", f"/api/disposition/trips/{c1}/decide", "qc", {
        "decisions": [
            {"cargoId": c1_lines[1], "disposition": "RELEASED"},
            {"cargoId": c1_lines[2], "disposition": "RELEASED"}],
        "note": "质检合格，两家货主货物均放行"})
    check(d["status"] == "SETTLING" and d["disposition"] == "RELEASED", "处置决定：正常放行，进入结算")

    _, pv = req("GET", f"/api/settle/trips/{c1}/preview", "settle")
    check(pv["liabilityParty"] == "" and float(pv["lossAmount"]) == 0,
          "无责任异常：货损 0、无责任方")
    _, st = req("POST", f"/api/settle/trips/{c1}/confirm", "settle", {})
    check(st["status"] == "DONE" and float(st["penalty_amount"]) == 0
          and float(st["compensation_amount"]) == 0, "结算确认：无扣罚无赔付，车次完成")

    req("POST", f"/api/signoff/trips/{c1}", "cs", {
        "result": "SIGNED", "signedPieces": 100, "signedBy": "盒马-仓库主管", "note": "全件签收"})
    _, tr = req("GET", f"/api/trace/trips/{c1}", "cs")
    check(tr["temperature"]["readingCount"] == 3 and tr["temperature"]["gapCount"] == 0,
          "追溯档案保留温控设备原始数据 3 点、0 断点")
    check(tr["trip"]["signoffResult"] == "SIGNED" and tr["trip"]["unloadMinutes"] is not None,
          "追溯档案含签收结果与卸货时长")
    _, ex = req("GET", f"/api/cs/trips/{c1}/explanation", "cs")
    check("【" in ex["customerSummary"], "客服解释为客户可读话术")

    # ============ 场景二：温度断点+超限+化冻 → 降级 → 承运商全责 ============
    print("\n【场景2】温度曲线断点+超限+化冻 · 隔离 · 降级入库 · 承运商扣罚赔付+信用分下调")
    _, t2 = req("POST", "/api/appointments", "carrier2", {
        "carrierId": 2, "driverId": 3, "vehiclePlate": "京B66666",
        "tempDeviceNo": "DEV-T2", "eSealNo": "SEAL-T2", "requestedAt": iso(95),
        "cargoLines": [
            {"ownerId": 1, "cargoType": "冷冻肥牛卷", "tempZone": "FROZEN",
             "maxTempCelsius": -18, "pieces": 100, "targetWarehouse": "W-F1"}]})
    c2 = t2["code"]
    c2_line = t2["cargoLines"][0]["id"]
    req("POST", "/api/gate/checkin", "dispatch",
        {"code": c2, "vehiclePlate": "京B66666", "eSealNo": "SEAL-T2"})
    req("POST", f"/api/dock/trips/{c2}/receive", "dispatch")
    req("POST", f"/api/dock/trips/{c2}/temperature", "carrier2", {"readings": [
        {"recordedAt": iso(-120), "celsius": -20.0},
        {"recordedAt": iso(-60), "celsius": -19.0},   # 与上点间隔 60 分钟 > 45 阈值
        {"recordedAt": iso(-30), "celsius": -7.5}]})  # 超过 -18℃ 上限
    _, t2v = req("GET", f"/api/appointments/{c2}", "dispatch")
    types2 = {i["type"] for i in t2v["incidents"]}
    check("TEMP_GAP" in types2 and "TEMP_EXCURSION" in types2,
          "系统自动识别温度曲线断点与温度超限并登记异常")

    req("POST", f"/api/dock/trips/{c2}/unload", "qc", {
        "doorOpenTempCelsius": -6.0, "unloadPhotos": "http://img/t2-door.jpg",
        "cargoDamages": [{"cargoId": c2_line, "damagedPieces": 5}]})
    _, q2 = req("POST", f"/api/qc/trips/{c2}/sample", "qc", {"samples": [
        {"cargoId": c2_line, "sampleTotal": 10, "sampleThawed": 4}]})
    check(q2["status"] == "QUARANTINED", "质检发现化冻，车辆/货物转入隔离区")

    _, board = req("GET", "/api/board/overview", "dispatch")
    here = [v for v in board["vehicles"] if v["code"] == c2][0]
    check(here["current_stage"] == "QUARANTINE", "调度看板显示本车卡在隔离环节")

    req("POST", f"/api/disposition/trips/{c2}/decide", "qc", {
        "decisions": [{"cargoId": c2_line, "disposition": "DOWNGRADED",
                       "note": "化冻批次折价50%降级入库"}], "note": "降级入库"})
    _, pv2 = req("GET", f"/api/settle/trips/{c2}/preview", "settle")
    check(pv2["liabilityParty"] == "CARRIER", "责任归集：承运商全责")
    check(abs(float(pv2["lossAmount"]) - 4000.0) < 0.01, "货损估算=100件×80×50%=4000")

    # feature-4：温度断点/超限已自动冻结批次结算，须先闭环异常工单（分类→客户决定→结案→赔付审批）
    _, wos2 = req("GET", f"/api/work-orders?tripCode={c2}", "dispatch")
    check(len(wos2) >= 2, "温度断点/超限自动生成异常工单并冻结批次")
    s, _ = req("POST", f"/api/settle/trips/{c2}/confirm", "settle", {}, expect=409)
    check(s == 409, "批次冻结中，结算确认被阻断")
    for w in wos2:
        if w["type"] in ("TEMP_EXCURSION", "DATA_GAP"):
            req("POST", f"/api/work-orders/{w['id']}/classify", "qc",
                {"classification": "REAL_EXCURSION", "note": "开门温度-6℃佐证真实超温"})
    _, t2b = req("GET", f"/api/appointments/{c2}", "dispatch")
    b2 = t2b["batches"][0]["id"]
    wo2exc = next(w for w in wos2 if w["type"] == "TEMP_EXCURSION")
    req("POST", f"/api/work-orders/{wo2exc['id']}/customer-decision", "customer1",
        {"batchId": b2, "decision": "DOWNGRADE", "acceptQty": 0, "rejectQty": 100})
    for w in wos2:
        req("POST", f"/api/work-orders/{w['id']}/resolve", "dispatch",
            {"responsibility": "CARRIER", "note": "承运商冷机故障全责"})
    _, pays2 = req("GET", f"/api/payouts?tripCode={c2}", "settle")
    draft2 = [p for p in pays2 if p["status"] == "DRAFT"]
    check(len(draft2) == 1 and abs(float(draft2[0]["amount"]) - 4000.0) < 0.01,
          "赔付候选=降级100件×80×50%=4000，审批链走完方可生效")
    req("POST", f"/api/payouts/{draft2[0]['id']}/submit", "settle")
    req("POST", f"/api/payouts/{draft2[0]['id']}/approve", "dispatch", {})
    req("POST", f"/api/payouts/{draft2[0]['id']}/approve", "settle", {})

    _, st2 = req("POST", f"/api/settle/trips/{c2}/confirm", "settle", {})
    check(abs(float(st2["penalty_amount"]) - 6400.0) < 0.01,
          "承运商扣罚=货损4000+温度类定额罚款2400=6400")
    check(abs(float(st2["compensation_amount"]) - 4000.0) < 0.01, "客户赔付 4000")
    check("85→40" in st2["liability_note"], "承运商信用分 85→40（后续预约优先级随之下调）")
    req("POST", f"/api/signoff/trips/{c2}", "cs", {
        "result": "PARTIAL", "signedPieces": 80, "signedBy": "盒马-收货员"})
    _, ex2 = req("GET", f"/api/cs/trips/{c2}/explanation", "cs")
    check("承运商" in ex2["liability"] and "化冻" in ex2["customerSummary"],
          "客服解释包含可理解的责任方与化冻货损说明")

    # ============ 场景三：封签异常 → 闸口排队 → 调度核验闭环 → 放行 ============
    print("\n【场景3】电子封签异常 · 闸口排队 · 调度同车处置闭环 · 后续放行")
    _, t3 = req("POST", "/api/appointments", "carrier1", {
        "carrierId": 1, "driverId": 1, "vehiclePlate": "沪C77777",
        "tempDeviceNo": "DEV-T3", "eSealNo": "SEAL-T3", "requestedAt": iso(125),
        "cargoLines": [
            {"ownerId": 2, "cargoType": "巴氏鲜奶", "tempZone": "CHILLED",
             "maxTempCelsius": 4, "pieces": 50, "targetWarehouse": "W-C1"}]})
    c3 = t3["code"]
    c3_line = t3["cargoLines"][0]["id"]
    _, g3 = req("POST", "/api/gate/checkin", "dispatch",
                {"code": c3, "vehiclePlate": "沪C77777", "eSealNo": "BROKEN-SEAL"})
    check(g3["admitted"] is False and g3["status"] == "QUEUED_AT_GATE",
          "封签不符，车辆拦截在闸口排队")
    check("封签异常待调度核验" in g3["reasons"], "排队原因明确为封签异常")
    _, qlist = req("GET", "/api/gate/queue", "dispatch")
    check(any(v["code"] == c3 for v in qlist), "闸口排队队列可见本车")
    _, t3v = req("GET", f"/api/appointments/{c3}", "dispatch")
    seal_inc = incident_id(t3v, "SEAL_ABNORMAL")
    check(seal_inc is not None, "封签异常已在同一车次登记")
    req("POST", f"/api/dispatch/trips/{c3}/incidents/{seal_inc}/resolve", "dispatch",
        {"responsibility": "CARRIER", "resolution": "现场核验为封签条码污损，重新扫码一致，准予放行"})
    _, a3 = req("POST", f"/api/dispatch/trips/{c3}/admit-queue", "dispatch")
    check(a3["status"] == "AT_GATE", "调度处置后排队车辆放行入园")
    req("POST", f"/api/dock/trips/{c3}/receive", "dispatch")
    req("POST", f"/api/dock/trips/{c3}/temperature", "carrier1", {"readings": [
        {"recordedAt": iso(-40), "celsius": 2.0},
        {"recordedAt": iso(-20), "celsius": 2.3}]})
    req("POST", f"/api/dock/trips/{c3}/unload", "qc", {
        "doorOpenTempCelsius": 2.5, "unloadPhotos": "http://img/t3.jpg",
        "cargoDamages": [{"cargoId": c3_line, "damagedPieces": 0}]})
    req("POST", f"/api/qc/trips/{c3}/sample", "qc", {"samples": [
        {"cargoId": c3_line, "sampleTotal": 10, "sampleThawed": 0}]})
    req("POST", f"/api/disposition/trips/{c3}/decide", "qc", {
        "decisions": [{"cargoId": c3_line, "disposition": "RELEASED"}]})
    _, st3 = req("POST", f"/api/settle/trips/{c3}/confirm", "settle", {})
    check(float(st3["penalty_amount"]) == 1000.0 and float(st3["compensation_amount"]) == 0
          and st3["liability_party"] == "CARRIER",
          "封签责任归承运商：扣罚 1000，无货损赔付，顺丰信用分 100→85")
    req("POST", f"/api/signoff/trips/{c3}", "cs", {
        "result": "SIGNED", "signedPieces": 50, "signedBy": "蒙牛-仓库主管"})

    # ============ 场景四：司机证件过期 → 闸口拦截 → 闭环后放行 ============
    print("\n【场景4】司机证件过期 · 预约即预警 · 闸口拦截 · 闭环后放行")
    _, t4 = req("POST", "/api/appointments", "carrier1", {
        "carrierId": 1, "driverId": 2, "vehiclePlate": "沪D88888",
        "tempDeviceNo": "DEV-T4", "eSealNo": "SEAL-T4", "requestedAt": iso(155),
        "cargoLines": [
            {"ownerId": 3, "cargoType": "常温粮油", "tempZone": "AMBIENT",
             "maxTempCelsius": 25, "pieces": 30, "targetWarehouse": "W-A1"}]})
    c4 = t4["code"]
    check(incident_id(t4, "DOCS_EXPIRED") is not None, "预约提交时即检出司机证件过期并登记高优异常")
    _, g4 = req("POST", "/api/gate/checkin", "dispatch",
                {"code": c4, "vehiclePlate": "沪D88888", "eSealNo": "SEAL-T4"})
    check(g4["admitted"] is False and "司机证件过期" in g4["reasons"],
          "证件过期车辆被拦在闸口排队")
    _, err = req("POST", f"/api/dispatch/trips/{c4}/admit-queue", "dispatch", expect=422)
    check("未闭环异常" in err["error"], "未闭环证件异常前不允许放行")
    docs_inc = incident_id(t4, "DOCS_EXPIRED")
    req("POST", f"/api/dispatch/trips/{c4}/incidents/{docs_inc}/resolve", "dispatch",
        {"responsibility": "CARRIER", "resolution": "司机现场补办电子从业资格证，核验有效"})
    _, a4 = req("POST", f"/api/dispatch/trips/{c4}/admit-queue", "dispatch")
    check(a4["status"] == "AT_GATE", "证件异常闭环后放行入园")

    # ============ 场景五：园区限电 → 仅自备发电月台可排窗 ============
    print("\n【场景5】园区限电 · 仅自备发电月台可排窗")
    req("POST", "/api/dispatch/power-limit", "dispatch", {"limited": True})
    _, t5a = req("POST", "/api/appointments", "carrier1", {
        "carrierId": 1, "driverId": 1, "vehiclePlate": "沪E55555",
        "tempDeviceNo": "DEV-T5A", "eSealNo": "SEAL-T5A", "requestedAt": iso(185),
        "cargoLines": [
            {"ownerId": 3, "cargoType": "冷藏盒饭", "tempZone": "CHILLED",
             "maxTempCelsius": 8, "pieces": 20, "targetWarehouse": "W-C2"}]})
    c5a = t5a["code"]
    _, t5b = req("POST", "/api/appointments", "carrier1", {
        "carrierId": 1, "driverId": 1, "vehiclePlate": "沪F55556",
        "tempDeviceNo": "DEV-T5B", "eSealNo": "SEAL-T5B", "requestedAt": iso(215),
        "cargoLines": [
            {"ownerId": 1, "cargoType": "冷冻虾仁", "tempZone": "FROZEN",
             "maxTempCelsius": -18, "pieces": 25, "targetWarehouse": "W-F2"}]})
    c5b = t5b["code"]
    _, board2 = req("GET", "/api/board/overview", "dispatch")
    docks = {v["code"]: v["dock_code"] for v in board2["vehicles"]}
    check(docks.get(c5a) == "D04", "限电时冷藏车只排到自备发电月台 D04（跳过无备电 D02）")
    check(docks.get(c5b) == "D01", "限电时冷冻车只排到自备发电月台 D01（跳过无备电 D05）")
    check(board2["powerLimit"] is True, "看板显示园区限电状态")
    req("POST", "/api/dispatch/power-limit", "dispatch", {"limited": False})

    # ============ 调度总览：环节卡点 + 未闭环异常台 + 同车时间线 ============
    print("\n【场景6】调度看板 / 异常台 / 同车次协同时间线")
    _, board3 = req("GET", "/api/board/overview", "dispatch")
    check(board3["countByStage"]["GATE"] >= 1, "看板按闸口/月台/质检/隔离/结算汇总在园车辆")
    stages = {v["code"]: v["current_stage"] for v in board3["vehicles"]}
    check(stages.get(c4) == "GATE", f"证件补办车 {c4} 当前卡在闸口之后待月台（GATE）")
    _, incs = req("GET", "/api/board/incidents", "dispatch")
    print(f"  ℹ️ 当前全园区未闭环异常 {len(incs)} 条")
    _, tl = req("GET", f"/api/incidents/trips/{c2}", "cs")
    types = [e["event_type"] for e in tl["timeline"]]
    check({"BOOKED", "GATE_ADMITTED", "DOCK_RECEIVED", "INCIDENT_TEMP_GAP",
           "QUARANTINED", "DISPOSITION_DECIDED", "SETTLED", "SIGNED_OFF"}.issubset(set(types)),
          "同一车次时间线串联承运/调度/质检/客服/结算五方动作")

    print(f"\n================ 冒烟结果：通过 {PASS} 项，失败 {FAIL} 项 ================")
    sys.exit(1 if FAIL else 0)


if __name__ == "__main__":
    main()
