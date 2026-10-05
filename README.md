# 区域血透中心患者排班透析机消毒耗材与异常上报平台

面向血透中心高频、固定又容易被突发情况打断的工作节奏，把**患者、护士、医生、工程、收费窗口**的状态串成一条完整的治疗记录：

- 固定每周透析的患者排班，受机位数量、感染分区、透析时长、交通接送、耗材库存、设备消毒时间约束
- 护士透前评估（体重/血压/通路/上次不适/抗凝/脱水量调整）→ 上机 → 透中定时记录（血压/心率/报警/补液/抽血/不适/医嘱变更）→ 下机记录（下机体重/穿刺点止血/离开方式/下次排班）
- 工程人员维护透析机消毒、滤芯更换、水处理记录
- 异常上报贯穿全程：迟到、请假、感染标记变化、机器故障、耗材不足、透析中低血压、医嘱调整
- 收费窗口核对医保、自费耗材、补收费用；管理端查看机位利用率、异常率、耗材损耗、消毒合规
- 护士长调班时可预览影响：波及哪些患者、哪些机器消毒时间被压缩、哪些耗材已预领
- **耗材批次追溯与厂家召回**：耗材按厂家批号（含供应商、生产/失效日期）管理，出库按近效期优先（FEFO）；某批次被厂家召回时，平台依据出库与患者使用记录自动列出受影响治疗记录——护士逐例电话回访是否不适、库管冻结未使用库存（召回期间库房停发该批次）、医生从治疗记录查看当日机器/护士/耗材批号/透析参数并给出复查结论；已排治疗一键更换批次；办结做质量定性并计入供应商考核

## 技术栈

| 层 | 技术 |
|---|---|
| 前端 | Vue 3 + Vite + Element Plus + Pinia + ECharts（Nginx 托管） |
| 后端 | Spring Boot 3 + Spring Security(JWT) + Spring Data JPA |
| 数据库 | PostgreSQL 16（本地开发可用 15） |

## 一键启动（Docker Compose）

```bash
docker compose up -d --build
```

启动后访问：

- 前端：http://localhost （80 端口）
- 后端 API：http://localhost:8080
- Swagger UI：http://localhost:8080/swagger-ui.html

首次启动后端会自动建表并写入演示数据（账号、12 台机位、18 名患者、耗材库存、今日排班与一条完整治疗流水）。

## 本地开发

### 后端

```bash
# 1. 准备数据库（PostgreSQL）
createdb hd_center   # 或 psql -c "CREATE DATABASE hd_center;"

# 2. 启动后端（默认连 localhost:5432，可用环境变量覆盖）
cd backend
mvn spring-boot:run
# 或指定数据库：
# DB_URL=jdbc:postgresql://localhost:5432/hd_center DB_USER=postgres DB_PASS=postgres mvn spring-boot:run
```

### 前端

```bash
cd frontend
npm install
npm run dev        # http://localhost:5173 ，已配置 /api 代理到 8080
```

### 环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/hd_center` | 数据库连接 |
| `DB_USER` / `DB_PASS` | `postgres` / `postgres` | 数据库账号 |
| `SERVER_PORT` | `8080` | 后端端口 |
| `JWT_SECRET` | 内置开发密钥 | 生产环境务必覆盖 |
| `JWT_EXPIRE_MINUTES` | `720` | 登录态有效期 |

## 测试账号

| 账号 | 密码 | 角色 | 主要菜单 |
|---|---|---|---|
| `admin` | `admin123` | 护士长/管理 | 全部功能 |
| `nurse1` / `nurse2` | `123456` | 护士 | 工作台、排班、患者、透析治疗、异常 |
| `doctor1` | `123456` | 医生 | 工作台、排班、患者、透析治疗、异常 |
| `eng1` | `123456` | 工程 | 机器与消毒、耗材库存、异常 |
| `fin1` | `123456` | 收费 | 收费核对、统计分析 |

## 验证方式（本任务实际执行过的验证）

### 1. 后端编译与启动

```bash
cd backend && mvn -s settings.xml -DskipTests package   # 构建通过，产出 target/hd-center-backend-1.0.0.jar
DB_URL=jdbc:postgresql://localhost:5433/hd_center java -jar target/hd-center-backend-1.0.0.jar
# 日志出现 "Started HdCenterApplication" 与 "演示数据初始化完成"
```

### 2. API 冒烟（curl 实际执行通过）

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['token'])")

# 今日工作台：18 条治疗单、机位状态分布、待处理异常、耗材预警、进行中召回
curl -s http://localhost:8080/api/stats/dashboard -H "Authorization: Bearer $TOKEN"

# 排班看板：感染标记自动匹配分区（HBV 患者排到乙肝区机位）
curl -s "http://localhost:8080/api/sessions/board?date=$(date +%F)&shift=AM" -H "Authorization: Bearer $TOKEN"

# 调班影响分析：受影响患者 + 消毒间隔压缩 + 已预领耗材
curl -s "http://localhost:8080/api/sessions/impact?sessionId=3&machineId=4&date=$(date +%F)&shift=AM" \
  -H "Authorization: Bearer $TOKEN"

# 治疗全流程：透前评估 -> 上机 -> 透中记录(低血压自动上报) -> 下机 -> 收费核对 -> 治疗单完成
curl -s -X POST http://localhost:8080/api/sessions/6/pre  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"weight":68.5,"bpSys":140,"bpDia":85,"heartRate":78,"accessCondition":"NORMAL","anticoagPlan":"低分子肝素 3500IU"}'
curl -s -X POST http://localhost:8080/api/sessions/6/start -H "Authorization: Bearer $TOKEN"
curl -s -X POST http://localhost:8080/api/sessions/6/intra -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"bpSys":85,"bpDia":50,"heartRate":95,"discomfort":"出冷汗"}'
curl -s -X POST http://localhost:8080/api/sessions/6/post -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"offWeight":66.8,"hemostasisOk":true,"hemostasisMin":10,"departureMode":"SELF"}'
curl -s -X POST http://localhost:8080/api/billing/1/confirm -H "Authorization: Bearer $TOKEN"

# 管理端统计：机位利用率 / 异常率 / 耗材损耗率 / 消毒合规率
curl -s http://localhost:8080/api/stats/overview -H "Authorization: Bearer $TOKEN"
```

已验证的关键行为：

- 未携带 Token 访问受保护接口返回 403
- 透中记录收缩压 < 90 自动产生「透析中低血压」异常
- 透中记录填写医嘱变更自动产生「医嘱调整」异常
- 下机后机器自动转「消毒中」，登记合格消毒记录后自动回「空闲」
- 下机自动生成待核对账单；收费确认后治疗单转「已完成」
- 患者请假后其预领耗材自动归还入库
- 感染标记变更自动上报异常并提示未来排班的分区不匹配
- 机器标记故障自动上报异常并列出当日受影响患者
- 耗材出库低于安全库存自动上报「耗材不足」异常
- 耗材按厂家批号入库；出库近效期优先（FEFO），自动跳过召回冻结批次；请假/换批按原批次归还

### 3. 批次召回全流程（curl 实际执行通过）

演示数据内置：透析器批号 `DLZ-260401` 一个处理中的召回（受影响 25 条治疗/17 名患者，含 1 例回访不适已安排复查、1 例已一键换批），以及一批柠檬酸消毒液的已办结召回（确认质量缺陷、扣分 20，供应商考核可查）。

```bash
BASE=http://localhost:8080   # $TOKEN 见上文登录步骤

# 批次台账与批次入库（批号/供应商/效期）
curl -s "$BASE/api/consumables/batches?consumableId=1" -H "Authorization: Bearer $TOKEN"
curl -s -X POST "$BASE/api/consumables/1/batches" -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"batchNo":"DLZ-261005","supplier":"费森尤斯医疗","expiryDate":"2028-09-01","qty":5}'

# 发起厂家召回：冻结未使用库存、列出受影响治疗记录、上报异常
curl -s -X POST "$BASE/api/recalls" -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"consumableId":1,"batchNo":"DLZ-260515","reason":"厂家来函：外壳标识不清","manufacturerDocNo":"FME-RC-X1"}'

# 召回详情（followUps 受影响治疗 / replacementBatches 可换批次 / batchTxns 追溯流水）
curl -s "$BASE/api/recalls/1" -H "Authorization: Bearer $TOKEN"

# 冻结批次出库被拒绝（400：已因厂家召回冻结，停止发放）
curl -s -X POST "$BASE/api/consumables/1/txn" -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"type":"USE","qty":1,"batchId":1}'

# 护士回访：报告不适自动上报「召回回访不适」异常，转医生复查
curl -s -X POST "$BASE/api/recalls/1/follow-ups/{fid}" -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"status":"DISCOMFORT","symptoms":"低热、畏寒","note":"透析当晚出现"}'
# 医生从治疗记录核对当日机器/护士/耗材批号/透中参数后给复查结论
curl -s -X POST "$BASE/api/recalls/1/follow-ups/{fid}/review" -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"result":"RECHECK_SCHEDULED","recheckPlan":"复查血常规、CRP","reviewNote":"已核对治疗全要素"}'
# 未开始的治疗一键换批（召回批号退回冻结批次，新批次重新预领）
curl -s -X POST "$BASE/api/recalls/1/follow-ups/{fid}/replace" -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"replacementBatchId":3}'
# 办结：质量定性 + 供应商扣分 + 未使用库存退厂（全部回访且不适病例复查完才可办结）
curl -s -X POST "$BASE/api/recalls/1/close" -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"verdict":"CONFIRMED_DEFECT","deduction":20,"returnUnused":true,"closeNote":"冻结库存退厂，供应商考核扣分"}'
# 耗材质量与供应商考核（按已办结召回汇总，基础分 100）
curl -s "$BASE/api/recalls/supplier-assessment" -H "Authorization: Bearer $TOKEN"
# 排班看板召回预警：某日待治疗且预领了召回批次、尚未换批的治疗单
curl -s "$BASE/api/sessions/board?date=$(date +%F)&shift=AM" -H "Authorization: Bearer $TOKEN"
```

已验证的关键行为：

- 发起召回立即冻结批次，冻结量随「请假未治疗患者预领归还」自动修正
- 召回期间冻结批次任何出库（预领/使用/损耗）返回 400；FEFO 自动挑批跳过冻结批次
- 看板只对「未开始且未换批」的治疗提示更换批次；换批/办结后预警自动消失
- 回访「报告不适」自动产生异常，医生复查前召回不可办结；治疗单详情聚合机器、护士、耗材批号与透中参数
- 办结时未使用库存生成「召回出库」退厂，批次转「已退厂家」；质量定性与扣分进入供应商考核
- 已请假（ABSENT）治疗不产生回访单，其预领的召回批次归还冻结库存随召回退厂

### 4. 前端构建与联调

```bash
cd frontend && npm install && npm run build   # 构建通过，产出 dist/
npm run dev                                    # 开发服务器 5173，/api 代理验证通过
```

## 主要功能页面

| 页面 | 说明 |
|---|---|
| 工作台 | 今日治疗单/透析中/待处理异常/耗材预警总览，机位状态色块 |
| 排班看板 | 按日期+班次查看机位-患者安排；自动排班；调班（含影响分析预览）；迟到/请假登记 |
| 患者管理 | 档案、感染标记、风险等级、固定透析日、接送时间窗、熟悉护士 |
| 透析治疗 | 治疗单总览（异常时间线+本机消毒记录）、透前评估、透中记录、透后记录、耗材与费用 |
| 机器与消毒 | 机位状态变更（故障自动上报）、消毒/滤芯/水处理记录登记 |
| 耗材库存 | 库存与安全库存预警、按厂家批号的批次台账（供应商/生产/失效日期/在库量）、批次入库补货、按批/FEFO 出入库、批次追溯流水、发起召回 |
| 批次召回 | 召回单全流程：冻结停发、受影响治疗记录、护士回访（不适自动上报）、医生复查、一键换批、办结质量定性、耗材质量与供应商考核 |
| 异常上报 | 全类型异常（含耗材召回、召回回访不适）的查询、上报、处理闭环 |
| 收费核对 | 医保/自费耗材/补收费用核对，确认后治疗单完成 |
| 统计分析 | 机位利用率、异常类型分布、耗材损耗、消毒合规图表 |

## 项目结构

```
├── docker-compose.yml          # 一键启动：db + backend + frontend
├── backend/                    # Spring Boot 后端
│   ├── Dockerfile
│   ├── settings.xml            # Maven 阿里云镜像（加速依赖下载）
│   └── src/main/java/com/hd/center/
│       ├── config/             # 安全(JWT)、全局异常、数据种子
│       ├── entity/             # 实体（含 ConsumableBatch/RecallOrder/RecallFollowUp）+ 枚举
│       ├── repository/         # Spring Data JPA
│       ├── service/            # 排班算法/耗材批次/召回/计费/统计/异常
│       └── controller/         # REST API（含 RecallController）
└── frontend/                   # Vue 3 前端
    ├── Dockerfile
    ├── nginx.conf              # SPA 路由回退 + /api 反代
    └── src/
        ├── api/                # axios 封装
        ├── layout/             # 主布局（按角色过滤菜单）
        ├── views/              # 10 个业务页面（含批次召回 RecallView）
        └── utils/labels.js     # 枚举中文标签
```

## 核心设计说明

**自动排班**（`POST /api/sessions/generate`）：筛选当日透析规律匹配的患者 → 按「风险等级 → 班车接送 → 病历号」排序 → 按感染标记匹配机位分区（普通/乙肝/丙肝/隔离）→ 逐机位计算下一可用上机时刻（上一台结束 + 消毒间隔）→ 生成治疗单并按 FEFO 自动预领标准耗材套件。

**调班影响分析**（`GET /api/sessions/impact`）：执行调班前返回——目标机位当日受影响患者、插入后相邻治疗间隔小于消毒缓冲的压缩项、涉及治疗单已预领的耗材、分区不匹配等警告；确认后 `POST /api/sessions/{id}/move` 执行。

**治疗单详情**（`GET /api/sessions/{id}/detail`）：一个接口聚合患者档案、透前评估、透中记录、透后记录、异常事件、耗材流水（含批号）、账单、本机位当日消毒记录、关联召回回访，即"一条治疗记录"的全貌；医生可据此在召回回访发现不适时核对当日机器、责任护士、耗材批号与透析参数。

**批次召回**（`POST /api/recalls`）：耗材按「耗材+厂家批号」建台账（`ConsumableBatch`，FEFO 出库）；发起召回（`RecallOrder`）时立即冻结批次（`FROZEN`，`ConsumableService` 出库拦截）并按该批次流向治疗单的预领/使用流水（净数量扣除归还）逐治疗单生成回访记录（`RecallFollowUp`）；请假未治疗者预领自动归还冻结批次、不产生回访。护士回访「报告不适」自动上报异常并转医生复查；未开始治疗可一键换批（召回批号退回冻结、新批次重新预领）；全部回访且不适病例复查完成后才可办结——质量定性与供应商扣分回流到 `/api/recalls/supplier-assessment` 考核，未使用库存生成召回出库退厂。排班看板（`GET /api/sessions/board` 的 `recallWarnings`）对未开始且未换批的治疗自动提示更换批次。
