#!/bin/bash
# 耗材批次追溯与召回 — 全流程验证
set -e
BASE=http://localhost:8080/api
J() { python3 -c "import sys,json;print(json.load(sys.stdin)$1)"; }
TOKEN=$(curl -s -X POST $BASE/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}' | J "['token']")
NURSE=$(curl -s -X POST $BASE/auth/login -H "Content-Type: application/json" -d '{"username":"nurse1","password":"123456"}' | J "['token']")
DOCTOR=$(curl -s -X POST $BASE/auth/login -H "Content-Type: application/json" -d '{"username":"doctor1","password":"123456"}' | J "['token']")
AUTH="Authorization: Bearer $TOKEN"
echo "== 0. 未携带 Token 访问受保护接口 =="
curl -s -o /dev/null -w "  HTTP %{http_code}（预期 403）\n" $BASE/stats/dashboard

CID=$(curl -s "$BASE/consumables" -H "$AUTH" | python3 -c "
import sys,json
for c in json.load(sys.stdin):
    if c['code']=='DIALYZER': print(c['id']); break")
BATCH_ID=$(curl -s "$BASE/consumables/batches" -H "$AUTH" | python3 -c "
import sys,json
for b in json.load(sys.stdin):
    if b['batchNo']=='FX60-2508A': print(b['id']); break")
echo "== 1. 发起召回（透析器批次 FX60-2508A）=="
curl -s -X POST $BASE/recalls -H "$AUTH" -H "Content-Type: application/json" \
  -d "{\"batchId\":$BATCH_ID,\"reason\":\"厂家通报批次 FX60-2508A 透析器膜材存在渗漏风险，主动召回\"}" > /tmp/r1.json
python3 - << 'PY'
import json
d = json.load(open('/tmp/r1.json'))
print(f"  召回单 {d['recallNo']} 状态={d['status']} 批次状态={d['batchStatus']} 冻结库存={d['frozenRemaining']}")
print(f"  受影响治疗记录（回访任务）={d['stats']['followupTotal']} 条，待更换批次={d['stats']['pendingReplacementCount']} 条")
PY
RECALL_ID=$(J "['id']" < /tmp/r1.json)

echo "== 2. 冻结批次发放阻断 =="
curl -s -w "  HTTP %{http_code} " -X POST $BASE/consumables/$CID/txn -H "$AUTH" -H "Content-Type: application/json" \
  -d "{\"type\":\"USE\",\"qty\":1,\"batchId\":$BATCH_ID}" | python3 -c "
import sys
raw = sys.stdin.read()
print(raw[:raw.rfind('HTTP')].strip()[:80], '| HTTP' + raw.rsplit('HTTP',1)[1].strip())"

echo "== 3. 出库自动分配正常批次（FEFO 跳过冻结批次）=="
curl -s -X POST $BASE/consumables/$CID/txn -H "$AUTH" -H "Content-Type: application/json" -d '{"type":"USE","qty":1,"note":"急诊临时使用"}' \
  | python3 -c "import sys,json; [print('  实际出库批次:', t.get('batchNo')) for t in json.load(sys.stdin)]"

echo "== 4. 排班看板自动提示更换批次 =="
curl -s "$BASE/sessions/board?date=$(date +%F)&shift=AM" -H "$AUTH" | python3 -c "
import sys,json
d = json.load(sys.stdin)
print(f'  AM 班 recallAlerts={len(d[\"recallAlerts\"])} 条:', '、'.join(a['patientName']+'('+a['batchNo']+')' for a in d['recallAlerts'][:3]), '...')"

echo "== 5. 一键更换批次 =="
curl -s -X POST $BASE/recalls/$RECALL_ID/replace-all -H "$AUTH" | python3 -c "
import sys,json
d = json.load(sys.stdin)
print(f\"  更换成功 {d['replacedCount']} 条，失败 {len(d['replaceFailed'])}，剩余待更换 {d['stats']['pendingReplacementCount']}\")"

echo "== 6. 护士回访（1 人无不适，1 人不适自动上报异常）=="
FID1=$(python3 -c "import json;print(json.load(open('/tmp/r1.json'))['followups'][0]['id'])")
FID2=$(python3 -c "import json;print(json.load(open('/tmp/r1.json'))['followups'][1]['id'])")
curl -s -X PUT $BASE/recalls/followups/$FID1 -H "Authorization: Bearer $NURSE" -H "Content-Type: application/json" \
  -d '{"status":"DONE","discomfort":false,"note":"电话回访，自述无不适"}' | python3 -c "import sys,json;f=json.load(sys.stdin);print('  ',f['patientName'],'回访完成，无不适')"
curl -s -X PUT $BASE/recalls/followups/$FID2 -H "Authorization: Bearer $NURSE" -H "Content-Type: application/json" \
  -d '{"status":"DONE","discomfort":true,"symptoms":"透析后皮肤瘙痒、红疹","note":"建议医生评估"}' | python3 -c "import sys,json;f=json.load(sys.stdin);print('  ',f['patientName'],'回访完成，不适:',f['symptoms'])"
curl -s "$BASE/exceptions?status=OPEN" -H "Authorization: Bearer $NURSE" | python3 -c "
import sys,json
for e in json.load(sys.stdin):
    if e['type']=='RECALL_DISCOMFORT': print('  自动上报异常:', e['description'][:60])"

echo "== 7. 医生复查评估 =="
curl -s -X PUT $BASE/recalls/followups/$FID2/review -H "Authorization: Bearer $DOCTOR" -H "Content-Type: application/json" \
  -d '{"reviewNeeded":true,"reviewNote":"安排下次透析前复查皮肤过敏原"}' | python3 -c "import sys,json;f=json.load(sys.stdin);print('  ',f['patientName'],'需复查:',f['reviewNeeded'],'|',f['reviewNote'][:30])"

echo "== 8. 治疗记录追溯（当日机器/护士/耗材批次/透析参数）=="
SID=$(python3 -c "import json;print(json.load(open('/tmp/r1.json'))['followups'][1]['sessionId'])")
curl -s $BASE/sessions/$SID/detail -H "Authorization: Bearer $DOCTOR" | python3 -c "
import sys,json
d = json.load(sys.stdin)
s = d['session']
b = [t.get('batchNo') for t in d['consumables'] if '透析器' in t['consumableName']]
print(f\"  机器={s['machine']['code']}({s['machine']['model']}) 护士={s['nurseName']} 透析器批次={b} 透中记录={len(d['intra'])}条 召回回访={[(f['status'],f['discomfort']) for f in d['recallFollowups']]}\")"

echo "== 9. 关闭召回（确认召回，剩余库存退回厂家）=="
curl -s -X POST $BASE/recalls/$RECALL_ID/close -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"outcome":"CONFIRMED","conclusion":"库存已退回厂家；1 名患者不适已安排复查；已排班治疗全部更换批次。"}' | python3 -c "
import sys,json
d = json.load(sys.stdin)
print(f\"  状态={d['status']} 结论={d['outcome']} 退回厂家={d['returnedToSupplier']} 批次状态={d['batchStatus']}\")"

echo "== 10. 供应商考核（处理结果已计入）=="
curl -s $BASE/recalls/supplier-stats -H "$AUTH" | python3 -c "
import sys,json
for s in json.load(sys.stdin):
    if s['recallTotal'] > 0:
        print(f\"  {s['supplier']}: 质量分={s['qualityScore']}({s['grade']}) 确认召回={s['confirmedRecallCount']} 回访不适={s['discomfortCount']} 结论={[c['conclusion'][:24] for c in s['closedRecalls']]}\")"

echo "== 11. 回归：透中低血压自动上报 + 请假归还带批次 =="
curl -s $BASE/sessions/board?date=$(date +%F)\&shift=AM -H "$AUTH" > /tmp/board.json
IDS=$(python3 -c "
import json
ids=[s['id'] for s in json.load(open('/tmp/board.json'))['sessions'] if s['status']=='SCHEDULED']
print(' '.join(str(i) for i in ids[:2]))")
SID2=$(echo $IDS | cut -d' ' -f1); SID3=$(echo $IDS | cut -d' ' -f2)
curl -s -X POST $BASE/sessions/$SID2/start -H "$AUTH" > /dev/null
curl -s -X POST $BASE/sessions/$SID2/intra -H "$AUTH" -H "Content-Type: application/json" -d '{"bpSys":85,"bpDia":50,"heartRate":95}' > /dev/null
curl -s "$BASE/exceptions?status=OPEN" -H "$AUTH" | python3 -c "
import sys,json
hits=[e for e in json.load(sys.stdin) if e['type']=='HYPOTENSION']
print('  低血压异常:', hits[0]['description'][:40] if hits else '未触发!')"
curl -s -X POST $BASE/sessions/$SID3/leave -H "$AUTH" -H "Content-Type: application/json" -d '{"reason":"家中有事"}' | python3 -c "import sys,json;d=json.load(sys.stdin);print('  请假归还耗材:',d['returned'],'条流水（更换过批次的治疗单不重复归还）')"
curl -s "$BASE/consumables/txns?sessionId=$SID3" -H "$AUTH" | python3 -c "
import sys,json
txns=json.load(sys.stdin)
r=[t for t in txns if t['type']=='RETURN']
print('  归还流水批次:', sorted(set(t.get('batchNo') for t in r if t.get('batchNo'))))"
echo "ALL_CHECKS_DONE"
