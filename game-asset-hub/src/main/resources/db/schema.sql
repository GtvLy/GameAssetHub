-- ============================================================
--  游戏项目资产与协作管理系统 —— 建库建表脚本
--  模块1：项目与成员管理
--  模块2：美术资产管理
--  模块3：任务看板与协作
--  模块4：统计与进度可视化
--
--  学号：85230645    姓名：张忠驰
--  文档版本：V1.0    日期：2026年9月
--
--  说明：
--   1. 本脚本严格照《项目实现文档》第 3.2 节 DDL 实现，
--      字段类型、注释、唯一约束与索引一个不漏。
--   2. 模块1 建 sys_user / project / project_member 三张表；
--      模块2 追加 asset_category / asset / asset_version / asset_status_log
--      四张表；模块3 追加 task / task_comment / task_status_log / notification
--      四张表；模块4 追加 project_report（报表快照）与 calendar_dates
--      （日期维表）两张表。
--   3. 字符集统一 utf8mb4：游戏项目名称、成员昵称常含 emoji 与
--      多语言字符，utf8（3 字节）存不下，会直接报 Incorrect string value。
--   4. 本脚本可重复执行：每次建表前先 DROP，用于开发环境重建。
--      <b>生产环境请勿直接执行</b>，那会清空全部数据。
--
--  用法：
--    mysql -uroot -p < src/main/resources/db/schema.sql
-- ============================================================

CREATE DATABASE IF NOT EXISTS game_asset_hub
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_general_ci;

USE game_asset_hub;

-- ============================================================
-- 用户表
-- ============================================================
DROP TABLE IF EXISTS sys_user;
CREATE TABLE sys_user (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  username    VARCHAR(50)  NOT NULL COMMENT '登录名',
  password    VARCHAR(100) NOT NULL COMMENT 'BCrypt 加密密码',
  nickname    VARCHAR(50)           COMMENT '昵称/显示名',
  email       VARCHAR(100),
  avatar      VARCHAR(255),
  role        VARCHAR(20)  NOT NULL DEFAULT 'MEMBER'
              COMMENT 'ADMIN系统管理员 / MEMBER普通用户',
  status      TINYINT      NOT NULL DEFAULT 1 COMMENT '0禁用 1正常',
  create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted     TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户表';

-- ============================================================
-- 项目表（模块1）
-- ============================================================
DROP TABLE IF EXISTS project;
CREATE TABLE project (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  name          VARCHAR(100) NOT NULL COMMENT '项目名称',
  code          VARCHAR(50)           COMMENT '项目代号，用于资产编号前缀',
  description   TEXT                  COMMENT '项目简介',
  engine        VARCHAR(30)           COMMENT '引擎：UE5/Unity/Godot/Other',
  cover_url     VARCHAR(500)          COMMENT '封面图',
  owner_id      BIGINT       NOT NULL COMMENT '创建者/项目负责人',
  start_date    DATE                  COMMENT '项目开始日期',
  plan_end_date DATE                  COMMENT '计划结束日期',
  status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                COMMENT 'ACTIVE进行中/PAUSED暂停/ARCHIVED已归档',
  member_count  INT          NOT NULL DEFAULT 1 COMMENT '成员数（冗余）',
  create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted       TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_owner (owner_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目表';

-- ============================================================
-- 项目成员与角色（RBAC 的核心载体）
--
-- 注意：本表刻意没有 deleted 列。成员移除是状态位软删（status = 'REMOVED'），
--       因为 uk_project_user 唯一约束会让「逻辑删除后重新邀请」撞唯一键。
-- ============================================================
DROP TABLE IF EXISTS project_member;
CREATE TABLE project_member (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  project_id  BIGINT      NOT NULL,
  user_id     BIGINT      NOT NULL,
  role        VARCHAR(20) NOT NULL DEFAULT 'ARTIST'
              COMMENT 'OWNER负责人/ARTIST美术/PROGRAMMER程序/DESIGNER策划/VIEWER外部协作者',
  status      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
              COMMENT 'INVITED已邀请/ACTIVE已加入/REMOVED已移除',
  invite_code VARCHAR(64)          COMMENT '邀请码（邀请制时使用）',
  join_time   DATETIME             COMMENT '加入时间',
  create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_project_user (project_id, user_id),
  KEY idx_user (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目成员表';

-- ============================================================
--  模块2：美术资产管理
-- ============================================================

-- ------------------------------------------------------------
-- 资产分类
--
-- 支持二级：parent_id = 0 为一级分类，非 0 表示挂在某个一级分类下。
-- uk_project_code 保证同一项目内编码不重复——分类编码是资产编号的前缀
-- （CHR_SM → CHR_SM_0001），重复编码会让两类资产共用一个编号空间。
-- 本表没有 deleted 列：删除前会校验「分类下无资产且无子分类」，
-- 因此不存在「删了分类留下孤儿资产」的中间态，不需要软删标志位。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS asset_category;
CREATE TABLE asset_category (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  project_id  BIGINT       NOT NULL,
  parent_id   BIGINT       NOT NULL DEFAULT 0 COMMENT '父分类，支持二级',
  name        VARCHAR(50)  NOT NULL COMMENT '分类名：模型/贴图/蓝图/音效/动画/UI',
  code        VARCHAR(30)  NOT NULL COMMENT '分类编码，用于自动生成资产编号',
  icon        VARCHAR(50),
  color       VARCHAR(20)  DEFAULT '#409EFF',
  sort_order  INT          NOT NULL DEFAULT 0,
  create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_project_code (project_id, code),
  KEY idx_project (project_id, parent_id, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资产分类表';

-- ------------------------------------------------------------
-- 资产主表
--
-- current_version_id 指向 asset_version.id，与「asset_version 中
-- is_current = 1 的那一条」始终一致，两者由资产服务在同一事务内维护。
-- 留有 deleted 列（逻辑删除）：删资产只置 1，版本与状态日志全部保留，
-- 因为「历史版本可完整追溯」是硬要求，删资产不等于抹掉它的历史。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS asset;
CREATE TABLE asset (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  project_id    BIGINT       NOT NULL,
  category_id   BIGINT       NOT NULL COMMENT '所属分类',
  asset_no      VARCHAR(60)  NOT NULL COMMENT '资产编号，如 CHR_SM_0001',
  name          VARCHAR(200) NOT NULL COMMENT '资产名称',
  description   TEXT                  COMMENT '资产说明',
  current_version_id BIGINT           COMMENT '当前版本ID（指向 asset_version）',
  version_count INT          NOT NULL DEFAULT 0 COMMENT '版本总数',
  status        VARCHAR(20)  NOT NULL DEFAULT 'IN_PROGRESS'
                COMMENT 'IN_PROGRESS制作中/PENDING待审核/APPROVED已通过/REJECTED已打回/DEPRECATED已废弃',
  priority      VARCHAR(10)  NOT NULL DEFAULT 'P2' COMMENT 'P0/P1/P2 资产优先级',
  tags          VARCHAR(300)          COMMENT '标签，逗号分隔',
  owner_id      BIGINT       NOT NULL COMMENT '主负责人（上传者/制作者）',
  reviewer_id   BIGINT                COMMENT '审核人',
  review_time   DATETIME              COMMENT '审核时间',
  review_remark VARCHAR(500)          COMMENT '审核意见',
  create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted       TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_project_asset_no (project_id, asset_no),
  KEY idx_project_category (project_id, category_id, status),
  KEY idx_project_owner (project_id, owner_id),
  KEY idx_project_priority (project_id, priority, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资产主表';

-- ------------------------------------------------------------
-- 资产版本表
--
-- 版本一经上传永不覆盖：切换当前版本、回滚历史版本都只改 is_current
-- 标记位并更新 asset.current_version_id，不删除任何历史文件。
-- uk_asset_version(asset_id, version_no) 是版本号自增的最后一道防线：
-- 分布式锁失效（Redis 抖动）时，并发插入只会失败一个，不会写出两个 V3。
-- 冗余 project_id 是刻意的：所有单资源查询都要带项目条件，
-- 冗余一列就不必为了拿 project_id 先回查 asset 表。
-- 本表无 deleted 列，也不允许删除。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS asset_version;
CREATE TABLE asset_version (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  asset_id      BIGINT       NOT NULL,
  project_id    BIGINT       NOT NULL,
  version_no    INT          NOT NULL COMMENT '版本号，从1递增',
  version_name  VARCHAR(100)          COMMENT '版本备注名，如 初版高模',
  file_name     VARCHAR(255) NOT NULL COMMENT '原始文件名',
  file_key      VARCHAR(500) NOT NULL COMMENT '存储路径/对象Key',
  file_size     BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  file_ext      VARCHAR(20)           COMMENT '扩展名：fbx/blend/png/uasset/wav',
  file_md5      VARCHAR(64)           COMMENT '文件MD5，用于秒传与去重',
  thumbnail_url VARCHAR(500)          COMMENT '缩略图（图片类或渲染截图）',
  poly_count    INT                   COMMENT '面数（模型类资产）',
  texture_size  VARCHAR(50)           COMMENT '贴图尺寸，如 2048x2048',
  change_log    VARCHAR(500)          COMMENT '变更说明',
  uploader_id   BIGINT       NOT NULL COMMENT '上传人',
  is_current    TINYINT      NOT NULL DEFAULT 1 COMMENT '是否当前版本（同资产仅一条为1）',
  create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_asset_version (asset_id, version_no),
  KEY idx_asset_current (asset_id, is_current),
  KEY idx_md5 (file_md5),
  KEY idx_project_time (project_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资产版本表';

-- ------------------------------------------------------------
-- 资产状态流转记录
--
-- 每次状态变更都必须落一条：审核过程是协作中的争议点
-- （「这个打回是谁批的、理由是什么」），只留最终状态等于把过程丢掉。
-- 本表只追加，不修改不删除。
--
-- 注意：按文档 DDL，本表<b>没有 project_id 列</b>。因此它的查询无法
-- 直接带项目条件，只能先用带 project_id 的 asset 查询确认归属，
-- 再按 asset_id 查日志。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS asset_status_log;
CREATE TABLE asset_status_log (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  asset_id    BIGINT      NOT NULL,
  from_status VARCHAR(20)          COMMENT '变更前状态',
  to_status   VARCHAR(20) NOT NULL COMMENT '变更后状态',
  operator_id BIGINT      NOT NULL,
  remark      VARCHAR(500)         COMMENT '变更说明/审核意见',
  create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_asset (asset_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资产状态流转记录表';

-- ============================================================
-- 模块3：任务看板与协作
--
-- 四张表：task / task_comment / task_status_log / notification。
-- 表结构严格照文档 3.2 的 DDL，仅有一处补充，见 task.version 的注释。
-- ============================================================

-- ------------------------------------------------------------
-- 任务表
--
-- sort_order 是「列内排序」的唯一依据，采用大间隔整数（每次 +1000）：
-- 拖拽落位时取前后卡片的中间值，一条 UPDATE 即可完成，不必重排整列。
-- 相邻间距小于 10 时（长时间拖拽后）才对该列做一次整体重排。
--
-- 查询一律 order by sort_order asc, id asc：id 是并列时的稳定兜底，
-- 少了它，两条 sort_order 相同的任务在两次请求里的先后顺序可能不一样，
-- 表现为「刷新一下顺序就变了」。
--
-- deleted 为逻辑删除：删任务只置 1，评论与状态日志全部保留——
-- 「这个任务当时是谁改的、改成了什么」是协作复盘的材料，不能随删除一起消失。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS task;
CREATE TABLE task (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  project_id    BIGINT       NOT NULL,
  asset_id      BIGINT                COMMENT '关联资产（可空）',
  title         VARCHAR(200) NOT NULL COMMENT '任务标题',
  description   TEXT                  COMMENT '任务描述',
  column_status VARCHAR(20)  NOT NULL DEFAULT 'TODO'
                COMMENT 'TODO待办/DOING进行中/REVIEW待审核/DONE已完成',
  priority      VARCHAR(10)  NOT NULL DEFAULT 'P2' COMMENT 'P0/P1/P2',
  task_type     VARCHAR(20)  NOT NULL DEFAULT 'ART'
                COMMENT 'ART美术/PROGRAM程序/DESIGN策划/BUG缺陷/OTHER',
  assignee_id   BIGINT                COMMENT '指派给谁',
  creator_id    BIGINT       NOT NULL COMMENT '创建人',
  due_date      DATE                  COMMENT '截止日期',
  estimate_hours DECIMAL(5,1)         COMMENT '预估工时',
  actual_hours  DECIMAL(5,1)          COMMENT '实际工时',
  story_point   INT                   COMMENT '故事点（燃尽图用）',
  sort_order    INT          NOT NULL DEFAULT 0 COMMENT '列内排序（拖拽用，大间隔 +1000）',
  finished_at   DATETIME              COMMENT '完成时间（拖入 DONE 时写入，拖出时清空）',
  -- 乐观锁版本号。文档 3.2 的 DDL 未列此列，但文档 5.4 明确要求
  -- 「task 表加 @Version 乐观锁字段，move 接口携带 version，冲突时返回 409」，
  -- 因此这里按 5.4 的并发一致性要求补上。version 由服务端维护，
  -- 客户端只回传它拿到的值；每次写操作 +1。
  version       INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号（文档 5.4）',
  create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted       TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_project_column (project_id, column_status, sort_order),
  KEY idx_assignee (assignee_id, column_status),
  KEY idx_due (project_id, due_date),
  KEY idx_project_asset (project_id, asset_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务表';

-- ------------------------------------------------------------
-- 任务评论表
--
-- mention_ids 存被 @ 的用户ID（逗号分隔）。刻意冗余 project_id：
-- 与 asset_version 同理，单资源查询要带项目条件时不必回查 task 表。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS task_comment;
CREATE TABLE task_comment (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  task_id     BIGINT      NOT NULL,
  project_id  BIGINT      NOT NULL,
  user_id     BIGINT      NOT NULL COMMENT '评论人',
  content     TEXT        NOT NULL COMMENT '评论内容（支持 @提及）',
  mention_ids VARCHAR(300)         COMMENT '被 @ 的用户ID，逗号分隔',
  create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted     TINYINT     NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_task (task_id, create_time),
  KEY idx_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务评论表';

-- ------------------------------------------------------------
-- 任务状态变更日志
--
-- 只追加不修改不删除。模块4 的燃尽图要「用状态变更日志回放每日快照」，
-- 而不是看任务的当前状态——否则历史曲线会被后续操作改写。
-- 因此每次 column_status 变化都必须在这里落一条，无例外。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS task_status_log;
CREATE TABLE task_status_log (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  task_id     BIGINT      NOT NULL,
  project_id  BIGINT      NOT NULL,
  from_status VARCHAR(20),
  to_status   VARCHAR(20) NOT NULL,
  operator_id BIGINT      NOT NULL,
  create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_task (task_id, create_time),
  KEY idx_project_time (project_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务状态变更日志表';

-- ------------------------------------------------------------
-- 站内通知表
--
-- 无 deleted 列：通知是「发生过的事」，只标记已读，不删除。
-- idx_user_read(user_id, is_read, create_time) 同时覆盖
-- 「未读列表」与「未读数」两个查询，铃铛每 30 秒轮询就走它。
--
-- project_id 可空（文档 DDL 如此），但本项目的通知都挂项目：
-- 前端按当前项目上下文（X-Project-Id）过滤，避免把 A 项目的
-- 待办提示混进 B 项目的铃铛里。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS notification;
CREATE TABLE notification (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  project_id  BIGINT                COMMENT '所属项目',
  user_id     BIGINT       NOT NULL COMMENT '接收人',
  type        VARCHAR(30)  NOT NULL
              COMMENT 'TASK_ASSIGNED任务指派/TASK_STATUS任务状态/COMMENT评论/ASSET_REVIEW资产审核/MENTION提及/TASK_DUE_SOON即将到期',
  title       VARCHAR(200) NOT NULL,
  content     VARCHAR(500),
  biz_type    VARCHAR(30)           COMMENT '业务类型：TASK/ASSET/PROJECT',
  biz_id      BIGINT                COMMENT '业务ID，前端跳转用',
  sender_id   BIGINT                COMMENT '触发人',
  is_read     TINYINT      NOT NULL DEFAULT 0 COMMENT '0未读 1已读',
  create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_user_read (user_id, is_read, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='站内通知表';

-- ============================================================
-- 模块4：统计与进度可视化
--
-- 两张表：project_report（周报/月报快照）与 calendar_dates（日期维表）。
-- ============================================================

-- ------------------------------------------------------------
-- 项目报表快照（文档 3.2 DDL + 5.10）
--
-- 表结构严格照文档 3.2 的 DDL，一个字段不多不少。
--
-- uk_project_period(project_id, report_type, period_start) 是本表的核心：
-- 它让「同一项目 + 同一类型 + 同一周期」只可能有一行，
-- 生成周报因此可以用 INSERT ... ON DUPLICATE KEY UPDATE 做成幂等 upsert。
--
-- 为什么必须靠唯一约束而不是「先 SELECT 再 INSERT/UPDATE」：
-- 后者在并发下两个请求会同时查不到、同时插入，一个必然撞唯一键失败；
-- 若再用「catch (DuplicateKeyException) 后在同一事务里重查」来兜底，
-- REPEATABLE READ 的快照读看不到并发事务刚提交的那一行，重查仍是空，
-- 异常最终会冒到用户面前（本轮在项目02 上真实踩过这个坑）。
-- 交给 ON DUPLICATE KEY UPDATE 一条语句解决，既不重查也不靠异常。
--
-- snapshot_json 用 JSON 类型：快照是「当时算出来的整份报表」，
-- 结构随报表字段演进，用 JSON 存可以让历史快照保持原样可查，
-- 不必为每次字段调整写一次 ALTER TABLE。
-- 本表刻意没有 update_time：是否被更新过由 snapshot_json 的内容体现，
-- 文档 DDL 也没有这一列，不擅自加。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS project_report;
CREATE TABLE project_report (
  id             BIGINT      NOT NULL AUTO_INCREMENT,
  project_id     BIGINT      NOT NULL,
  report_type    VARCHAR(10) NOT NULL DEFAULT 'WEEK' COMMENT 'WEEK周报/MONTH月报',
  period_start   DATE        NOT NULL,
  period_end     DATE        NOT NULL,
  snapshot_json  JSON                 COMMENT '报表数据快照',
  create_time    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_project_period (project_id, report_type, period_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目报表快照表';

-- ------------------------------------------------------------
-- 日期维表（文档 5.6 的 calendar_dates）
--
-- 燃尽图的实际线是「按日回放剩余故事点」，没有任务的日期也必须有一个点，
-- 否则折线会在没有活动的日子断开——看上去像数据丢了。
-- 因此按日 LEFT JOIN 这张维表，而不是直接 GROUP BY task 表。
--
-- 数据由 data.sql 生成，覆盖 [CURDATE() - 200 天, CURDATE() + 200 天]（约 400 行）。
-- 刻意<b>不用固定年月</b>：写死 2026 年的日历，等演示日期一过，
-- 燃尽图就会画出空曲线，而代码没有任何问题——这类「过一段时间自己坏掉」
-- 的种子数据比不写还糟。
--
-- 只有 stat_date 一个业务列 + is_workday 辅助列：工作日不参与当前口径
-- （理想线含非工作日，见 README 的口径说明），但先存下来，
-- 将来要改成「只算工作日」时不必重建维表。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS calendar_dates;
CREATE TABLE calendar_dates (
  stat_date  DATE    NOT NULL COMMENT '日期',
  is_workday TINYINT NOT NULL DEFAULT 1 COMMENT '1工作日 0周末',
  PRIMARY KEY (stat_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='日期维表（模块4 燃尽图）';
