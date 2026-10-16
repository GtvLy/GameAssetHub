# 游戏项目资产与协作管理系统 —— 后端

姓名：张忠驰
课程：大学四年级 — Java企业级应用开发实训

美术资产版本管理 · 任务看板 · 团队协作可视化

**本仓库当前已完成「模块1～模块4」**：模块1 = 项目与成员管理，模块2 = 美术资产与版本管理，
模块3 = 任务看板与协作，模块4 = 统计与进度可视化（资产完成率 / 燃尽图 / 成员工作量 / 周报）。
前端在 `../game-asset-hub-web/`。

> 项目背景：结合本人在 Unreal Engine 5 与 Blender 方向的独立游戏开发经验自选。

---

## 一、技术栈

| 项 | 版本 |
|---|---|
| Java | 17 |
| Spring Boot | 3.5.16 |
| MyBatis-Plus | 3.5.17（`mybatis-plus-spring-boot3-starter` + `mybatis-plus-jsqlparser`） |
| MySQL | 8.0 |
| Redis | 5.0.14 |
| JWT | jjwt 0.12.6 |
| 端口 | 8082 |
| 数据库 | `game_asset_hub` |

---

## 二、启动

```powershell
# 0. 前置：Redis 已启动、数据库已初始化（见 D:\java\README.md）

cd D:\java\game-asset-hub
.\mvnw.cmd clean package -DskipTests
java -Dfile.encoding=UTF-8 -jar target\game-asset-hub-1.0.0.jar
```

### 演示账号

| 用户名 | 密码 | 昵称 | 在演示项目中的角色 |
|---|---|---|---|
| `zhangchi` | `123456` | 张忠驰 | `OWNER`（星港计划、像素农场） |
| `liyang` | `123456` | 李阳 | `ARTIST`（星港计划） |
| `wangong` | `123456` | 王工 | `PROGRAMMER`，初始为 `INVITED` 未激活 |
| `chenjing` | `123456` | 陈静 | `VIEWER`（星港计划，外部协作者只读） |

演示数据含 2 个项目、5 条成员关系（星港计划 4 名、像素农场 1 名），其中王工带一个一次性邀请码
`DEMO2026INVITECODE00000000000000`，可直接演示凭码加入流程。
另含分类树与多版本资产、任务看板与评论、站内通知等模块2、模块3 的演示数据，
以及模块4 的日期维表与让统计图有内容的补充任务（见「模块4」一节）。

---

## 三、模块1 已完成的能力

### 数据库表（3 张）

`sys_user`、`project`、`project_member`

建表脚本：`src/main/resources/db/schema.sql`（现含三个模块共 11 张表）　演示数据：`db/data.sql`

### 接口

**认证 `/api/auth`**

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/auth/register` | 注册 |
| POST | `/api/auth/login` | 登录 |
| POST | `/api/auth/logout` | 登出（jti 写 Redis 黑名单） |
| GET | `/api/auth/me` | 当前用户信息 + 我参与的项目列表 |

**项目 `/api/projects`**

| 方法 | 路径 | 所需权限 | 说明 |
|---|---|---|---|
| GET | `/api/projects` | 登录 | 我参与的项目（仅 `ACTIVE` 成员关系） |
| POST | `/api/projects` | 登录 | 创建项目，**创建者自动成为 OWNER** |
| POST | `/api/projects/join` | 登录 | 凭邀请码加入项目 |
| GET | `/api/projects/{id}` | `PROJECT_READ` | 项目详情 |
| PUT | `/api/projects/{id}` | `PROJECT_WRITE` | 修改项目 |
| PATCH | `/api/projects/{id}/status` | `PROJECT_WRITE` | 状态变更（ACTIVE/PAUSED/ARCHIVED） |
| DELETE | `/api/projects/{id}` | `PROJECT_WRITE` | 归档 |
| GET | `/api/projects/{id}/overview` | `PROJECT_READ` | 概览（Redis 缓存） |
| GET | `/api/projects/{id}/members` | `PROJECT_READ` | 成员列表 |
| POST | `/api/projects/{id}/members` | `MEMBER_MANAGE` | 邀请成员，返回邀请码 |
| PUT | `/api/projects/{id}/members/{uid}` | `MEMBER_MANAGE` | 修改成员角色 |
| DELETE | `/api/projects/{id}/members/{uid}` | `MEMBER_MANAGE` | 移除成员 |

### RBAC 权限模型

角色存在 `project_member.role`，权限矩阵集中在 `RolePermissionMap` 中定义（不散落在各处 if-else）：

| 角色 | 项目读写 | 成员管理 | 资产读 | 资产写 | 资产审核 | 任务读写 | 统计 |
|---|---|---|---|---|---|---|---|
| `OWNER` 负责人 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `ARTIST` 美术 | 只读 | ❌ | ✅ | ✅ | ❌ | ✅ | ✅ |
| `PROGRAMMER` 程序 | 只读 | ❌ | ✅ | ❌ | ❌ | ✅ | ✅ |
| `DESIGNER` 策划 | 只读 | ❌ | ✅ | ❌ | ❌ | ✅ | ✅ |
| `VIEWER` 外部协作者 | 只读 | ❌ | ✅ | ❌ | ❌ | 只读 | ❌ |

### 关键实现

- **`@ProjectPermission` 注解 + AOP 切面**：从路径变量解析 projectId，校验「是该项目的
  `ACTIVE` 成员」+「角色具备所需权限」，不通过抛 `BizException(403, ...)`。
  权限集中在 `RolePermissionMap` 一处定义，调整角色或权限只改一个地方。
- **第二层越权防护**：所有单资源查询的 SQL 条件都带 `project_id`，
  不依赖「按主键查出来再在 Java 里判断归属」。单元测试断言 SQL 片段含
  `project_id` / `role` / `status` 且参数真值被绑定。
- **概览缓存**：整份 `ProjectOverviewVO` 按项目维度缓存到 Redis（TTL 10 分钟），
  项目信息或成员发生任何写操作时**主动失效**该 key。
- **唯一 OWNER 保护**：项目至少保留一名 OWNER，移除或降级唯一的 OWNER 会被拒绝。
- **邀请码一次性**：凭码加入后码立即作废，重复使用返回「邀请码无效或已被使用」。
  被移除成员时同时清空其 `invite_code`，防止用旧码加回。

### 测试

90 个单元测试，**全部是纯逻辑测试**（Mockito mock mapper，不连 MySQL/Redis）：

| 测试类 | 用例数 | 覆盖 |
|---|---|---|
| `ProjectMemberServiceImpl` | 22 | 邀请、改角色、移除、唯一 OWNER 保护 |
| `ProjectServiceImpl` | 20 | 项目管理、状态变更、概览缓存命中/失效 |
| `RolePermissionMap` 权限矩阵 | 18 | 5 种角色 × 10 项权限逐格断言 |
| `ProjectAccessGuard` | 14 | 成员身份校验、唯一负责人校验 |
| `ProjectIdResolver` | 12 | 从路径解析 projectId（含 `/api/assets/{id}` 不得误判） |
| 演示数据密码密文自检 | 4 | 读 `data.sql` 反校验 BCrypt 密文可用 |

```powershell
.\mvnw.cmd clean test
```

---

## 四、模块2 已完成的能力

### 数据库表（4 张）

`asset_category`、`asset`、`asset_version`、`asset_status_log`

建表脚本同上：`src/main/resources/db/schema.sql`　演示数据：`db/data.sql`「模块2」段

### 接口

**资产分类 `/api/projects/{pid}/categories`**

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/projects/{pid}/categories` | 分类树（两级） |
| POST | `/api/projects/{pid}/categories` | 新增分类 |
| PUT | `/api/projects/{pid}/categories/{id}` | 修改分类 |
| DELETE | `/api/projects/{pid}/categories/{id}` | 删除分类（分类下有资产时拒绝） |

**资产与版本 `/api/projects/{pid}/assets`、`/api/assets`**

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/projects/{pid}/assets` | 资产列表（分页 + 分类/状态/负责人/优先级/关键字） |
| POST | `/api/projects/{pid}/assets` | 新建资产（含首个版本上传） |
| GET | `/api/assets/{id}` | 资产详情（含元数据与当前版本） |
| PUT | `/api/assets/{id}` | 修改资产信息 |
| DELETE | `/api/assets/{id}` | 删除资产（逻辑删除） |
| GET | `/api/assets/{id}/versions` | 版本历史 |
| POST | `/api/assets/{id}/versions` | 上传新版本 |
| GET | `/api/assets/{id}/versions/{vid}/download` | 下载指定版本 |
| PATCH | `/api/assets/{id}/versions/{vid}/current` | 把指定版本设为当前（回滚） |
| GET | `/api/assets/{id}/versions/compare` | 两个版本的元数据对比 |
| PATCH | `/api/assets/{id}/status` | 资产状态流转（含审核） |
| GET | `/api/assets/{id}/status-logs` | 状态流转历史 |

> 资产维度的路径里没有项目段，项目上下文由请求头 `X-Project-Id` 提供（与看板、评论、通知一致）。

**大文件上传 `/api/assets/upload`**

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/assets/upload/check-md5` | 秒传检查（整包 MD5 命中则直接复用） |
| POST | `/api/assets/upload/init` | 分片上传初始化（分配 uploadId） |
| POST | `/api/assets/upload/chunk` | 上传单个分片（可乱序） |
| POST | `/api/assets/upload/complete` | 合并分片并落库 |

### 关键实现

- **状态流转白名单**：`IN_PROGRESS → PENDING → APPROVED → DEPRECATED`，打回走 `REJECTED`
  （可重新提交或改回 `IN_PROGRESS`）；仅 `OWNER` / `ARTIST` 可提交审核，仅 `OWNER` 可通过、
  打回、废弃——越权 403，打回不填意见 400。
- **分片上传**：分片乱序上传后由 `ChunkMerger` 按序合并，合并后校验整包 MD5；
  残留分片由 `ChunkCleanupTask`（每天凌晨 3 点）清理。上传新版本后资产状态回退到 `IN_PROGRESS`。
- **秒传**：`check-md5` 命中已存在的文件就直接建版本，不再传输。
- **资产编号**：`asset_no` 由 `AssetNoGenerator`（Redis INCR）生成，`uk_project_asset_no`
  保证唯一，编号不复用（删掉的资产编号也不会被重新分配）。
- **存储可切换**：`gameasset.storage.type=local | minio`，默认 `local`；MinIO 目前是占位实现
  （见「与实现文档的偏离」第 16 条）。
- **概览接入**：`GET /api/projects/{pid}/overview` 增补资产指标（总数 / 已通过 / 待审核 / 完成率）。

---

## 五、模块3 已完成的能力

### 数据库表（4 张）

`task`、`task_comment`、`task_status_log`、`notification`

`task` 比文档 DDL 多一列 `version` 做乐观锁（见「与实现文档的偏离」第 15 条）；
`task_status_log` 只追加、不修改，`notification` 不设逻辑删除。
建表脚本同上：`src/main/resources/db/schema.sql`　演示数据：`db/data.sql`「模块3」段

### 接口

**看板与任务 `/api/projects/{pid}/board`、`/api/projects/{pid}/tasks`、`/api/tasks`**

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/projects/{pid}/board` | 整个看板（按 TODO / DOING / REVIEW / DONE 四列分组） |
| GET | `/api/projects/{pid}/tasks` | 任务列表（分页 + 筛选） |
| POST | `/api/projects/{pid}/tasks` | 创建任务（可关联资产） |
| GET | `/api/tasks/{id}` | 任务详情（含评论与状态日志） |
| PUT | `/api/tasks/{id}` | 更新任务 |
| PATCH | `/api/tasks/{id}/move` | 拖拽移动（列 + 相邻卡片），并发冲突返回 409 |
| PATCH | `/api/tasks/{id}/assign` | 指派任务 |
| DELETE | `/api/tasks/{id}` | 删除任务 |

**评论 `/api/tasks`**

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/tasks/{id}/comments` | 评论列表 |
| POST | `/api/tasks/{id}/comments` | 发表评论（支持 @提及） |
| DELETE | `/api/tasks/comments/{cid}` | 删除自己的评论 |

**站内通知 `/api/notifications`**

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/notifications` | 我的通知（分页，可筛未读） |
| GET | `/api/notifications/unread-count` | 未读数（铃铛角标轮询，Redis 缓存 5 分钟） |
| PATCH | `/api/notifications/{id}/read` | 标记已读 |
| PATCH | `/api/notifications/read-all` | 全部标记已读 |

### 关键实现

- **拖拽落位以服务端为准**：按相邻卡片的 `sort_order` 算出新位置，**响应返回服务端计算的
  最终 `sortOrder`**；`task.version` 乐观锁挡住并发拖拽，冲突返回 409。
- **通知触发点**：任务指派、任务改列、评论与 @提及、资产审核（提交 / 通过 / 打回）、
  任务临近截止（`TaskDueReminderTask` 每天 9:05，按天幂等去重）。
  统一入口 `NotificationService.push()`，硬规则「不给自己发通知」。
- **概览的任务指标**：`taskTotal` / `taskDone` / `taskOverdue` 按项目维度进缓存；
  `myTasks` 是用户维度数据，每次请求实时查库（见「与实现文档的偏离」第 10 条）。
- **@提及**：`MentionParser` 解析评论里的被提及人；除被提及人外，任务负责人也会收到评论通知。

---

## 六、模块4 已完成的能力

### 数据库表（2 张）

| 表 | 说明 |
|---|---|
| `project_report` | 报表快照。**严格照文档 3.2 的 DDL**，一个字段不多不少（没有 `update_time`）；`uk_project_period(project_id, report_type, period_start)` 是幂等 upsert 的基础 |
| `calendar_dates` | 日期维表。`stat_date`（主键）+ `is_workday`。数据由 `db/data.sql` 用**递归 CTE** 生成，覆盖 `[CURDATE() - 200 天, CURDATE() + 200 天]`（401 行），**不是固定年月**——写死日历的话演示日期一过燃尽图就画出空曲线 |

`calendar_dates` 刻意没有实体类与 Mapper：它只被燃尽图那条手写 SQL `LEFT JOIN` 使用，
没有「单资源查询」的需求。加一个 Mapper 出来反而会破坏
「所有手写 SQL 都必须带 `project_id`」这条契约（它是全局维表，天然没有项目维度）。

### 接口（8 个，全部在 `/api/projects/{pid}/...` 下）

| 方法 | 路径 | 参数 | 说明 |
|---|---|---|---|
| GET | `/stat/asset-completion` | — | 资产完成率与状态分布（环形图） |
| GET | `/stat/asset-by-category` | — | 各分类资产数量与完成情况（柱状图） |
| GET | `/stat/burndown` | — | 燃尽图（理想线 + 按状态日志回放的实际线） |
| GET | `/stat/workload` | — | 成员工作量 + 负载均衡度 |
| GET | `/stat/task-trend` | `days`（7~180，缺省 30） | 每日新增 / 完成趋势，空日补 0 |
| GET | `/report/weekly` | `weekStart`（缺省本周一） | 项目周报，生成即落快照 |
| GET | `/report/monthly` | `month`（`yyyy-MM`，缺省当前月） | 项目月报，同上 |
| GET | `/stat/export` | — | 导出统计报表 xlsx（`Content-Disposition: attachment; filename*=utf-8''…`） |

八个接口**全部把项目ID 放在路径里**，不采用 `/api/stat/*` 这种「路径里没有项目段」的形态：
路径里的 `pid` 是越权重灾区，一旦项目上下文只能靠客户端可改写的 `X-Project-Id` 头，
「越权与否取决于一个客户端说了算的头」这件事本身就不该出现在只读统计接口上。

### 权限矩阵（`RolePermissionMap` 里 `STAT_READ` 这一格的落点）

| 角色 | 统计查看 / 导出 | 实测 |
|---|---|---|
| OWNER | ✅ | 200 |
| ARTIST | ✅ | 200 |
| PROGRAMMER | ✅ | 200 |
| DESIGNER | ✅ | 200 |
| VIEWER | ❌ | **403** |
| 非本项目成员（含拿别的项目 pid） | ❌ | **403** |
| 未登录 / 伪造 token | ❌ | **401** |

导出与查询**同权限**，不额外开特权：把导出单列成更高权限只会让 OWNER 之外的人先截图再手工贴进
Excel，既没防住泄露，又平白多了一道摩擦。

> **与任务要求的偏差（须协调者注意）**：任务书里写的是「VIEWER 只读也可看统计」，
> 而文档 3.3 的权限矩阵「统计查看」列对 VIEWER 明确是 ❌，文档 5.1 的 `ROLE_PERMS` 里
> `"VIEWER", EnumSet.of(PROJECT_READ, ASSET_READ, TASK_READ)` 同样没有 `STAT_READ`，
> 模块1 的 `RolePermissionMap`（含单测）与前端 `constants/index.ts` 的
> 「外部协作者：项目内全部只读，**不含统计**」都照此实现。
> **本模块沿用文档与既有实现，没有改 `RolePermissionMap`**——改它会让模块1 的权限矩阵单测
> 与文档 3.3 同时失效，也会让前端早就写在界面上的说明变成错的。
> 如果确实要放开 VIEWER，请明确「以任务书为准、接受与文档 3.3 不一致」，再统一改
> `RolePermissionMap` + 其单测 + 前端 `roleMeta` 描述 + 本文档。

### 燃尽图口径（含一处对文档 5.6 的修正）

```
理想线  从 start_date 到 plan_end_date 全区间逐日线性递减到 0（含非工作日）
实际线  从 start_date 到 min(plan_end_date, 今天) 逐日回放「截至该日仍未完成的故事点」
```

判定（文档 5.6 的三档，`BurndownCalculator#judge`）：

```
实际剩余 < 理想剩余 × 0.9  → AHEAD（进度超前）
实际剩余 > 理想剩余 × 1.1  → BEHIND（进度落后）
其余                       → ON_TRACK（符合计划）
```

**修正 1：文档 5.6 的实际线 SQL 漏了「在该日期之后才创建的任务」。**

文档原文的 `NOT EXISTS` 只排除了「已 DONE 的任务」，没有排除未来才创建的任务。
后果是一个 10 月才建的任务，在 9 月 1 日的点位上就被算成「剩余故事点」，历史曲线凭空多出故事点。
已补上 `t.create_time < d.stat_date + INTERVAL 1 DAY`，并在 `MapperSqlContractTest` 里钉住。

在演示数据上这条缺陷是**可见**的（临时库实测）：

| 日期 | 文档原版 SQL | 修正版 |
|---|---|---|
| 2026-09-01 | 63（= 全部故事点） | **0**（当天还没有任务） |
| 2026-09-02 | 63 | 3 |

顺带说明：原版把 `l.create_time <= d.stat_date + INTERVAL 1 DAY` 与 `create_time` 混用
（`DATETIME` 与 `DATE` 直接比会把当天创建的任务排除掉），修正版统一成
`< DATE_ADD(d.stat_date, INTERVAL 1 DAY)`。

**修正 2：三种「今天」都要能跑。** 项目表的 `start_date` / `plan_end_date` 是固定值，
而今天是会走的，所以 `BurndownCalculator.resolveWindow` 显式处理：

| 今天的位置 | 实际线 | idealRemain 的依据 | 判定 |
|---|---|---|---|
| 早于开始日 | 空数组（不查库） | 夹紧为 0 天 → 理想剩余 = 总故事点 | ON_TRACK |
| 区间内 | `[start, today]` | 真实已过天数 | AHEAD / ON_TRACK / BEHIND |
| 晚于计划结束日 | `[start, plan_end]` | 夹紧为总天数 → 理想剩余 = 0 | 剩余 > 0 则 BEHIND |

**「已消耗天数」夹紧到 `[0, totalDays]`**：不夹紧的话，过了计划结束日之后理想剩余会变成负数，
判据会随着拖延自动放宽——越拖反而越「正常」，这是最典型的统计造假。
（`BurndownCalculatorTest` 对三种情形与 clamp 逐格覆盖。）

### 实际线用 `task_status_log` 回放（关键做法）

判定「某个任务在某个日期点位上是否已完成」的依据是
**该日期（含）之前存在一条 `to_status='DONE'` 的日志**，与任务**现在**是什么状态无关。

所以「先完成、之后又被拖回 TODO」的任务，历史点位不会因为这次回退而改变。
临时库实测（把已完成的任务 508 拖回待办，逐日对比回放结果）：

```
changed_days_before_today = 0          # 今天之前的历史点位零变化
按日志回放 = 45，按当前状态算 = 57      # 两种口径差 12 点（7 = 临时任务，5 = 演示数据里回退过的那条）
```

`MapperSqlContractTest` 用一条**否定式**断言守住这条：燃尽图的 SQL 里
**不许出现** `column_status` 与 `finished_at`——一旦有人「简化」成看当前状态，
历史曲线会随每次拖拽被改写，而图上完全看不出来。

### 负载均衡度口径（文档 5.7）

```
平均值 μ = Σ任务数 / 人数
标准差 σ = √( Σ(任务数ᵢ − μ)² / n )        ← 总体标准差，分母是 n
均衡度   = 1 − σ / μ
```

三处细节（都影响数字，`WorkloadBalanceCalculatorTest` 逐条钉住）：

1. **总体标准差（÷n），不是样本标准差（÷n−1）**：这里的成员就是要看的全部对象，
   不是从更大总体里抽的样本；且 n = 1 时样本标准差分母为 0，单人项目会算出 NaN。
2. **μ = 0 时（所有人任务数都是 0，例如刚建好还没排任务的项目）均衡度取 1.0**，
   不做除法。取 0 或 NaN 会把「项目刚建好」显示成「工作量分配不均，建议调整」——纯粹的误报。
3. **均衡度可以为负，不截断**：σ/μ > 1 时（3 个人是 10/0/0）1 − σ/μ < 0。
   截断到 0 会把「稍微不均」与「极度不均」压成同一个值。要显示成 0% 是展示层的取舍。

`< 0.5` 时给出「工作量分配不均，建议调整」提示（文档 5.7）。统计对象只含 **ACTIVE** 成员：
被邀请但没加入的人不可能有产出，列进去会把人均任务量摊薄、掩盖真正的不均衡。

### 周报 / 月报结构（文档 5.10）

四段：**任务进展**（周期内新建 / 完成、当前逾期与任务总数）→ **资产进展**（周期内新增 / 通过、
待审核存量）→ **成员产出**（周期内每人新建 / 完成 / 名下未完成 / 上传版本 / 通过资产 / 故事点 / 工时）→
**自动生成的结论与风险提示**。

风险提示触发条件（`ReportRiskAdvisor`，每条都钉住阈值两侧）：

| 条件 | 文案 |
|---|---|
| 逾期任务 > 0 | 有 N 个任务已逾期，请及时跟进 |
| 待审核资产 > 5 | 待审核资产积压 N 个，建议尽快安排审核 |
| 燃尽图判定 = BEHIND | 项目进度落后于燃尽图理想线，建议评估范围或调整排期 |
| 周期净增任务 ≥ 5 且新增 > 完成 | 本周期新增 N 个、仅完成 M 个，待办在堆积… |

`summary` 是「自动生成的结论文本」：无风险时说「未发现明显风险」，有风险时**直接写出最该先处理的
那一条**，而不是只留一句「存在风险，详见下表」。

**快照 upsert 幂等**（`ProjectReportMapper#upsertSnapshot`）：

```sql
INSERT INTO project_report (...) VALUES (...)
ON DUPLICATE KEY UPDATE period_end = VALUES(period_end), snapshot_json = VALUES(snapshot_json)
```

一条语句解决，**既不重查也不依赖异常**。特别地，
**没有**写成「`catch (DuplicateKeyException)` 之后在同一事务里重新 SELECT 再更新」——
那个写法在 MySQL 默认的 REPEATABLE READ 下是坏的：事务的一致性快照在第一次查重（返回空）时
就已固定，重查看不到并发事务刚提交的行，异常最终会冒到用户面前。
（项目02 的点名幂等就栽在这里，见 `PROGRESS.md` 第四节 A。）
`MapperSqlContractTest` 断言这条 SQL **不含 `SELECT`**，防止有人把它改回「先查后插」。

快照 JSON 用**注入的 `ObjectMapper`**（`JacksonConfig#objectMapper()`，已注册 JavaTimeModule 并把日期
固定成 `yyyy-MM-dd`）序列化，而不是 `JSON.toJSONString`：Jackson 的默认行为会把 `LocalDate`
写成 `[2026,9,21]` 这种数组，快照里的日期就没人认得了。

### 缓存

| key 前缀 | 内容 | TTL |
|---|---|---|
| `gah:stat:{pid}:asset-completion` | 资产完成率与状态分布 | 10 分钟 |
| `gah:stat:{pid}:asset-category` | 分类分布 | 10 分钟 |
| `gah:stat:{pid}:workload` | 成员工作量 | 10 分钟 |

- **燃尽图与任务趋势刻意不缓存**：它们的横轴含「今天」，缓存一天就会让今天的曲线还是昨天画的
  那个形状——「数字看着对、就是不动」的 bug 极难排查。少一次聚合换来的是不出现这类问题。
- **失效时机**：任务增删改 / 移动、资产增删改 / 状态变更、成员变更。
  这些写路径本来就都会调用 `ProjectOverviewCache#evict()`，所以统计缓存的失效**挂在那条通道上**
  （`evict` 里连带 `ProjectStatCache#evict`），一次修改、一处失效，不给将来的新写路径留下
  「只失效了概览、统计图还是旧的」这种半失效的可能。
  唯一的例外是 `TaskServiceImpl#assign`（改指派人只影响工作量、不影响概览的公共指标），
  它在方法体里单独调了一次统计缓存失效。
- **不放任何用户维度字段**：三个 VO 的每个字段都是项目维度的（成员工作量的成员列表也是
  「项目里的所有成员」，与调用者无关）。项目03 在模块1 时踩过 `myTasks` 串号的坑，
  这类字段必须先拆到缓存之外，不能顺手挂进 VO。

### 前端

| 文件 | 说明 |
|---|---|
| `views/stat/BurndownView.vue` | 燃尽图：理想线（虚线）+ 实际线（面积）+ 今天的 markLine；四张指标卡 |
| `views/stat/WorkloadView.vue` | 横向柱状图（任务数 / 已完成）+ 角色分布环形图 + 明细表 + 导出按钮 |
| `views/stat/ReportView.vue` | 周报 / 月报：结论文本 + 风险提示 + 四段数据 + 快照落库状态 |
| `views/project/ProjectOverviewView.vue` | 资产完成率环形图 + 分类柱状图（放在概览页的统计区块） |
| `composables/useEcharts.ts` | ECharts 生命周期（**SVG 渲染器**、主题切换重绘、销毁） |
| `composables/useChartTheme.ts` | 从 `:root` 读 CSS 变量作为图表配色，深色主题自动跟随 |

- **资产的两张图放在概览页**（而不是 WorkloadView）：「资产完成率」在概览页本来就已经有一个
  数字卡片，把环形图与分类柱状图放在它下面，打开项目第一眼就能看到；看板、燃尽图、工作量
  各自有独立页面，而资产维度属于概览层面。
- **ECharts 用 SVG 渲染器**（`echarts.init(el, null, { renderer: 'svg' })`）：canvas 渲染出来的
  在 DOM 里只有一个空 `<canvas>`，无头浏览器 `--dump-dom` 看不到任何东西，
  渲染冒烟脚本就没法断言「图上真的有数据」。SVG 会把每个刻度、每个图例都变成真实节点。
- **导出按钮走 axios 的 `responseType: 'blob'`**，不用 `window.open`：导出接口要求
  `Authorization: Bearer` 头，浏览器原生导航带不上，必然 401，而且是在新标签页里静默失败。
- 路由：`/project/:id/burndown`、`/project/:id/workload`、`/project/:id/report`；
  旧的 `/project/:id/stats` 保留并重定向到燃尽图。侧栏导航同步换成三个入口。
- `vite.config.ts` 的代理目标支持用环境变量 `VITE_PROXY_TARGET` 覆盖（默认仍是 8082），
  这样不占用 8082 也能做前端渲染冒烟。

### 演示数据（模块4 新增）

- **`calendar_dates`**：`db/data.sql` 用递归 CTE 生成 `[今天-200, 今天+200]` 共 401 行。
- **四张补充任务**（主键 511~514，不改动既有 501~510 的任何业务字段），日期全部写成
  `CURDATE()` 的相对表达式——固定日期过一段时间会整段滑出统计窗口，表现成「图是空的」
  而代码没有问题：
  - `511` 序章灯光氛围调优：**完成过又被拖回 TODO**，是回放口径的活样本；
  - `512` 空间站外壳模块 UV 展平：进行中且填了实际工时，让工作量表的工时列非零；
  - `513` 重力系统原型验证：近两周完成，让趋势图的「完成」线不是平的；
  - `514` 音效总线与混音规则整理：推到待审核，让待审核统计非空。
- 它们的 `task_status_log` 一并补齐，**含跨日期的 `to_status='DONE'` 日志**——那是燃尽图实际线的
  唯一数据源，没有它实际线就是一条水平线。

### 测试

- 单元测试 **500 项全绿**（模块1/2/3 原有 433 项未破，模块4 新增 67 项）：
  `BurndownCalculatorTest`（12）、`WorkloadBalanceCalculatorTest`（7）、
  `ReportRiskAdvisorTest`（8）、`StatExcelWriterTest`（5）、
  `StatServiceImplTest`（16）、`ReportServiceImplTest`（13），
  以及 `MapperSqlContractTest` 新增的 4 条 SQL 契约断言；
  `ProjectOverviewCacheTest` 与 `TaskServiceImplTest` 各补 1~2 条断言。
- 端到端：`D:\java\.tools\verify-p03-m4.ps1`（204 项）+ `verify-p03-m4-ui.ps1`（63 项）。
  两个脚本都支持指向别的后端与库（`-Base/-Db`、`-Web/-Api`），便于在不占用 8082 / 5175
  的情况下做隔离验证。

### 如何验证

```powershell
# 后端（8082，需先 init-db 重建演示数据）
powershell -ExecutionPolicy Bypass -File D:\java\.tools\verify-p03-m4.ps1

# 前端渲染冒烟（需 5175 前端 + 8082 后端）
powershell -ExecutionPolicy Bypass -File D:\java\.tools\verify-p03-m4-ui.ps1

# 单元测试
cd D:\java\game-asset-hub; .\mvnw.cmd clean test
```

导出接口的内容校验不是「字节数大于 0」：脚本用 .NET 的
`System.IO.Compression.ZipFile` 打开返回的 xlsx，读 `xl/sharedStrings.xml`，
断言里面出现「星港计划」「李阳」「场景道具」「预估工时」「负载均衡度」等中文关键字——
只查字节数证明不了里面是**我们的数据**。

---

## 七、与实现文档的偏离

| # | 偏离 | 原因 |
|---|---|---|
| 1 | 依赖中额外加入 `com.baomidou:mybatis-plus-jsqlparser:3.5.17` | MyBatis-Plus 3.5.9 起 `PaginationInnerInterceptor` 已移出 `mybatis-plus-extension`，不加则编译期报「找不到符号」 |
| 2 | 用 `BeanUtils.copyProperties` 手写转换器，未用 MapStruct | 按交付约定禁用 MapStruct |
| 3 | 越权返回**真实 HTTP 403**（同时响应体 `code:403`）。**HTTP 状态码与业务码同值**：400/401/403/404/409/500 都写回同值状态码；而 `DuplicateKeyException` 的 1001 不是合法 HTTP 状态，仍返回 HTTP 200 + body 1001 | 让状态行也能直接反映错误类别，curl / Postman 调试无需解析响应体。**该约定与项目01（personal-focus）、项目02（teacher-assistant）一致** |
| 4 | 凭邀请码加入**不校验账号归属**，当前登录用户即可入伙 | 实现文档只要求「校验邀请码有效性与未使用」，未要求校验被邀请人身份 |
| 5 | 成员移除走 `status=REMOVED` 而非逻辑删除 | `project_member` 表无 `deleted` 列，实现文档也允许「软删或置 status=REMOVED」 |
| 6 | `member_count` 口径 = 未移除的成员数（**含 `INVITED`**） | 实现文档只写「成员数（冗余）」，这里明确口径，唯一维护入口 `refreshMemberCount` |
| 7 | `GET /api/projects` **只返回 `ACTIVE` 成员关系** | 切面要求 `ACTIVE` 才能访问项目，若把 `INVITED` 也列出来，用户点进去必然 403。代价是受邀人须先凭码加入才能看到项目 |
| 8 | 唯一 OWNER 保护会拦住「负责人移除自己」 | 该规则的必然结果——项目至少要有一名管理者。前端已加 tooltip 说明 |
| 9 | `daysToDeadline` 在未设计划结束日期时返回 `null` | 不编造数字；前端显示「未设置计划结束日期」 |
| 10 | 概览里的 `myTasks` **不进缓存**：缓存只存项目维度指标，`myTasks` 每次请求实时查库（`TaskOverviewSupport.withMyTasks`） | 模块1 留下的 TODO，模块3 已处理。`myTasks` 是用户维度数据，放进按项目共享的缓存会串号 |
| 11 | `ProjectServiceImpl.getExistingProject` 用 `selectById`（未带 project_id），是全项目**唯一**一处 | 调用它之前必先通过 `ProjectAccessGuard` 的成员/OWNER 校验并已带 project_id。模块2/3 的资产与任务必须照「第二层」写法，代码注释已写明 |
| 12 | 前端 `vite.config.ts` 的 `manualChunks` 写成函数形式 | Vite 8 底层是 Rolldown，传对象会直接 `TypeError: manualChunks is not a function` **中断构建**（不是警告） |
| 13 | 未配置 `spring.data.redis.lettuce.pool` | 连接池需要 `commons-pool2`，而 `spring-boot-starter-data-redis` 并不带它，留着是个隐性失败点。现有的缓存、未读计数与黑名单场景用共享原生连接足够 |
| 14 | 通知推送已交付（模块3）：`notification` 表 + 4 个接口 + `NotificationService.push()` 统一入口 | 模块1 时的待办，模块3 已实现，触发点见「模块3 已完成的能力」 |
| 15 | `task` 表增加 `version` 列做乐观锁（文档 §3.2 的 DDL 未列此列），拖拽冲突返回 409 | 文档 §5.4 要求「看板拖拽与并发一致性」，没有版本列就发现不了并发修改 |
| 16 | MinIO 存储是占位实现（`MinioStorageService` 方法体直接抛错），pom 未引入 minio SDK，实际只用 `local` | 演示用不到这条通道，不值得为它拉一串传递依赖；`StorageService` 接口已留好，要用时再补 |
| 17 | 燃尽图实际线的 SQL **比文档 5.6 多加了一个条件**：`t.create_time < DATE_ADD(d.stat_date, INTERVAL 1 DAY)` | 文档原文只排除了已 DONE 的任务，没排除「在该日期之后才创建的任务」——一个 10 月才建的任务会在 9 月 1 日的点位上被算成剩余故事点。演示数据上实测：原版首日 63、修正版 0 |
| 18 | 燃尽图的**理想线改成逐日**，未采用文档 5.6 示例里的 `stepDays(project)` 抽样 | 实际线是逐日的，两条线要共用同一个类目轴；一条按周一条按日，前端得自己插值对齐，画出来的交点全是假的。文档里的 `stepDays` 本身没给定义，逐日是最不含糊的取法 |
| 19 | `GET /stat/export` 返回**二进制流**而不是统一的 `Result<T>` 包装 | Excel 要能被浏览器/客户端直接存成文件，包一层 JSON 就打不开了。出错时仍走全局异常处理器返回 `Result`（HTTP 4xx），前端据状态码区分 |
| 20 | 统计接口的 `STAT_READ` 权限矩阵**沿用文档 3.3 与模块1 的实现**：VIEWER 无统计权限（403） | 任务书里写的是「VIEWER 只读也可看统计」，但文档 3.3 的权限矩阵与 5.1 的 `ROLE_PERMS` 都明确 VIEWER 没有 `STAT_READ`，`RolePermissionMap`（含 18 项单测）与前端 `roleMeta` 描述也照此实现。改它会让三处同时失效。详见「模块4」一节的说明 |
| 21 | 新增 `MethodArgumentTypeMismatchException → 400` 的全局映射 | 与第 5 项同一类问题：`?days=abc` / `?weekStart=2026-09-32` 这类参数转换失败原本会掉进兜底分支变成 **500「系统繁忙」**，而真正的原因在请求里 |
| 22 | `ProjectOverviewCache#evict` 里**连带**失效模块4 的统计缓存 | 统计图与概览指标的数据源是同一批写操作，而所有写路径本来就都调用了这个 `evict`。挂在同一条通道上是「一次修改、一处失效」；反过来让每个写路径显式调两次，漏掉任何一处都会产生「概览对了、统计图是旧的」的静默 bug |
| 23 | pom 新增 `org.apache.poi:poi-ooxml:5.4.0`（与项目02 同版本） | 导出统计报表 xlsx。产物已在本地仓库 `.tools/m2`，构建不需要联网 |
| 24 | `RedisCacheService` 新增 `get(String, TypeReference<T>)` 重载 | 只传 `Class` 无法表达 `List<AssetCategoryStatVO>`——泛型擦除后 Jackson 会反序列化成 `List<LinkedHashMap>`，取元素时 `ClassCastException`，且只在读了缓存之后才抛（「第一次请求正常、第二次 500」） |
| 25 | `vite.config.ts` 的代理目标支持 `VITE_PROXY_TARGET` 环境变量覆盖 | 为了能不占用 8082 / 5175 做前端渲染冒烟（用临时库 + 别的端口）。默认值不变，仍是 `http://localhost:8082` |

> **修正记录**：`application.yml` 原先写死 `password: root`，与其它两个项目的
> `root/123456` 不一致，会导致启动看似正常、一访问数据库就 `Access denied`。
> 已统一为 `${DB_PASSWORD:123456}` 的可覆盖写法。

---

## 八、未实现 / 留给后续的部分

- MinIO 存储实现（`MinioStorageService` 目前是占位，见「与实现文档的偏离」第 16 条）。
- 报表快照**只写不读**：`project_report` 里存下了每周 / 每月的快照，但还没有
  「历史报表列表」「对比两个周期」这类接口与页面。`ProjectReportMapper#selectSnapshot`
  已经备好（带 `project_id`），要补时不必重新设计。
- 通知的 EMAIL / WECHAT 渠道仍是扩展点（模块3 交付时的状态，模块4 未涉及）。
- `VIEWER` 是否应该能看统计：见「模块4」一节的偏差说明，需要明确以谁为准。

前端的「概览」「资产」「看板」「燃尽图」「工作量」「周报」「成员」七个入口都已接入真实页面，
模块1 那个 `/project/:projectId/stats` 占位页已拆成三个真实页面，旧地址保留并重定向到燃尽图。
