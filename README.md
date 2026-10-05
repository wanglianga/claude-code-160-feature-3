# 区域血透中心患者排班透析机消毒耗材与异常上报平台

面向血透中心高频、固定又容易被突发情况打断的工作节奏，把**患者、护士、医生、工程、收费窗口**的状态串成一条完整的治疗记录：

- 固定每周透析的患者排班，受机位数量、感染分区、透析时长、交通接送、耗材库存、设备消毒时间约束
- 护士透前评估（体重/血压/通路/上次不适/抗凝/脱水量调整）→ 上机 → 透中定时记录（血压/心率/报警/补液/抽血/不适/医嘱变更）→ 下机记录（下机体重/穿刺点止血/离开方式/下次排班）
- 工程人员维护透析机消毒、滤芯更换、水处理记录
- 异常上报贯穿全程：迟到、请假、感染标记变化、机器故障、耗材不足、透析中低血压、医嘱调整
- 收费窗口核对医保、自费耗材、补收费用；管理端查看机位利用率、异常率、耗材损耗、消毒合规
- 护士长调班时可预览影响：波及哪些患者、哪些机器消毒时间被压缩、哪些耗材已预领
- **耗材批次追溯与召回**：耗材按厂家批号分批入库，出库按最早失效期（FEFO）自动分配批次；厂家召回某批次后——
  - 平台根据出库与患者使用记录列出受影响治疗记录，自动生成患者回访任务
  - 库管冻结未使用库存，召回处理期间库房不能继续发放该批次耗材
  - 已排好的治疗自动提示更换批次，可单条或一键退回召回批次并重新预领正常批次
  - 护士回访登记患者是否不适（不适自动上报异常），医生评估是否需要复查
  - 回访发现不适时，医生可从治疗记录直接查看当日机器、护士、耗材批次与透析参数
  - 关闭召回（退回厂家/解除冻结）的处理结果进入耗材质量与供应商考核

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

### 2. 耗材批次召回全流程（`./verify_recall.sh`，已在全新数据库上实际执行通过）

仓库根目录的 `verify_recall.sh` 按顺序验证本任务全部验收点（需后端已启动）：

```bash
./verify_recall.sh
```

实际执行输出要点：

1. **发起召回**：`POST /api/recalls`（透析器批次 FX60-2508A）→ 批次转「已冻结」，冻结库存 44；根据出库/使用记录列出受影响治疗记录，生成 2 条患者回访任务、23 条已排班治疗待更换批次
2. **发放阻断**：对冻结批次出库 → `400 批次「FX60-2508A」已冻结，召回处理期间禁止发放`；不指定批次出库 → 自动分配正常批次 FX60-2509B（FEFO 跳过冻结批次）
3. **排班提示**：`GET /api/sessions/board` 返回 `recallAlerts`，AM 班 7 条治疗单提示预领了召回批次
4. **更换批次**：`POST /api/recalls/{id}/replace-all` → 23 条治疗单全部退回召回批次并重新预领正常批次，剩余待更换 0
5. **护士回访**：1 人无不适；1 人「透析后皮肤瘙痒、红疹」→ 自动上报「召回回访不适」异常
6. **医生复查**：登记「需要复查」及评估意见
7. **治疗记录追溯**：`GET /api/sessions/{id}/detail` 一处聚合当日机器（M02/Fresenius 4008S）、责任护士、耗材批号（FX60-2508A）、透析参数（透中血压/心率记录）与召回回访状态
8. **关闭召回**：确认召回 → 67 支冻结库存退回厂家（`SUPPLIER_RETURN` 流水），批次转「已召回」，透析器库存 93 → 26
9. **供应商考核**：`GET /api/recalls/supplier-stats` → 德医医疗质量分 75（B，确认召回 1 次 + 回访不适 1 例），康诚生物 80（B，历史召回演示数据），其余供应商 100（A）
10. **回归**：未携带 Token 访问受保护接口返回 403；透中收缩压 < 90 自动上报低血压；请假归还预领耗材按原批次归还（更换过批次的治疗单不重复归还）

### 3. 基础 API 冒烟（curl 实际执行通过）

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
- 患者请假后其预领耗材自动归还入库（按原批次）
- 感染标记变更自动上报异常并提示未来排班的分区不匹配
- 机器标记故障自动上报异常并列出当日受影响患者
- 耗材出库低于安全库存自动上报「耗材不足」异常
- 冻结/召回批次禁止发放；出库自动按 FEFO 分配正常批次

### 4. 前端构建与联调

```bash
cd frontend && npm install && npm run build   # 构建通过，产出 dist/
npm run dev                                    # 开发服务器 5173，/api 代理验证通过（含 /api/recalls）
```

## 主要功能页面

| 页面 | 说明 |
|---|---|
| 工作台 | 今日治疗单/透析中/待处理异常/耗材预警总览，进行中召回提醒，机位状态色块 |
| 排班看板 | 按日期+班次查看机位-患者安排；自动排班；调班（含影响分析预览）；迟到/请假登记；预领召回批次耗材的治疗单自动提示更换 |
| 患者管理 | 档案、感染标记、风险等级、固定透析日、接送时间窗、熟悉护士 |
| 透析治疗 | 治疗单总览（异常时间线+本机消毒记录+召回提示）、透前评估、透中记录、透后记录、耗材批号与费用 |
| 机器与消毒 | 机位状态变更（故障自动上报）、消毒/滤芯/水处理记录登记 |
| 耗材库存 | 库存与冻结量、批次档案（供应商/效期/剩余）、出入库（FEFO 自动分配批次）、流水查询 |
| 批次召回 | 发起召回（冻结库存）、受影响治疗回访、医生复查评估、更换批次、关闭召回、供应商考核 |
| 异常上报 | 全类型异常的查询、上报、处理闭环 |
| 收费核对 | 医保/自费耗材/补收费用核对，确认后治疗单完成 |
| 统计分析 | 机位利用率、异常类型分布、耗材损耗、消毒合规图表 |

## 项目结构

```
├── docker-compose.yml          # 一键启动：db + backend + frontend
├── verify_recall.sh            # 批次召回全流程验证脚本（需后端已启动）
├── backend/                    # Spring Boot 后端
│   ├── Dockerfile
│   ├── settings.xml            # Maven 阿里云镜像（加速依赖下载）
│   └── src/main/java/com/hd/center/
│       ├── config/             # 安全(JWT)、全局异常、数据种子
│       ├── entity/             # 15 个实体（含批次/召回/回访）+ 枚举
│       ├── repository/         # Spring Data JPA
│       ├── service/            # 排班算法/耗材批次/召回/计费/统计/异常
│       └── controller/         # REST API
└── frontend/                   # Vue 3 前端
    ├── Dockerfile
    ├── nginx.conf              # SPA 路由回退 + /api 反代
    └── src/
        ├── api/                # axios 封装
        ├── layout/             # 主布局（按角色过滤菜单）
        ├── views/              # 10 个业务页面（含批次召回）
        └── utils/labels.js     # 枚举中文标签
```

## 核心设计说明

**自动排班**（`POST /api/sessions/generate`）：筛选当日透析规律匹配的患者 → 按「风险等级 → 班车接送 → 病历号」排序 → 按感染标记匹配机位分区（普通/乙肝/丙肝/隔离）→ 逐机位计算下一可用上机时刻（上一台结束 + 消毒间隔）→ 生成治疗单并自动预领标准耗材套件（按 FEFO 分配批次）。

**调班影响分析**（`GET /api/sessions/impact`）：执行调班前返回——目标机位当日受影响患者、插入后相邻治疗间隔小于消毒缓冲的压缩项、涉及治疗单已预领的耗材、分区不匹配等警告；确认后 `POST /api/sessions/{id}/move` 执行。

**治疗单详情**（`GET /api/sessions/{id}/detail`）：一个接口聚合患者档案、透前评估、透中记录、透后记录、异常事件、耗材流水（含批号）、账单、本机位当日消毒记录、召回回访状态，即"一条治疗记录"的全貌；召回回访发现患者不适时，医生在此直接查看当日机器、护士、耗材批次与透析参数。

**批次追溯**（`ConsumableBatch` + `ConsumableTxn.batchId`）：耗材按厂家批号分批入库（入库自动查找或创建批次），批次剩余量 = 该批次全部流水之和；出库（预领/使用/损耗）默认按最早失效期（FEFO）从正常批次自动分配，可跨批次拆分；耗材总库存不变，另按批次状态汇总「冻结量」。

**召回闭环**（`POST /api/recalls` → `PUT /api/recalls/followups/{id}` → `POST /api/recalls/{id}/replace-all` → `POST /api/recalls/{id}/close`）：
发起召回即冻结批次库存（冻结/召回批次发放一律 400 拒绝，自动排班预领跳过并上报异常）；按出库与使用记录生成受影响治疗的回访任务（透析中/已下机/已完成）与待更换批次清单（已排班/已评估）；更换批次 = 召回批次退回冻结库存 + 正常批次重新预领（同一事务）；关闭时确认召回则剩余库存退回厂家、解除冻结则批次恢复可用。

**供应商考核**（`GET /api/recalls/supplier-stats`）：按供应商汇总批次数、召回次数、回访不适例数与已关闭召回的处理结果；质量分 = 100 - 20×确认召回次数 - 5×回访不适例数，分 A/B/C/D 四级。
