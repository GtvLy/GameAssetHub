-- ============================================================
--  游戏项目资产与协作管理系统 —— 演示数据
--  模块1：项目与成员管理
--  模块2：美术资产管理
--  模块3：任务看板与协作
--  模块4：统计与进度可视化
--
--
--  前置：先执行 schema.sql 建库建表
--  用法：mysql -uroot -p < src/main/resources/db/data.sql
--
--  说明：模块2 的演示数据追加在文件中段，包含分类树、若干资产
--        与多版本记录，用于演示版本时间线、状态流转与秒传；
--        模块3 的演示数据追加在文件中段偏后，包含覆盖四列的看板任务、
--        任务评论（含 @提及）、状态变更日志与通知；
--        模块4 的演示数据在文件末尾：日期维表（相对 CURDATE() 生成）
--        与若干用相对日期创建的补充任务，让燃尽图 / 趋势图 / 工作量图
--        任何时候打开都有内容。
-- ============================================================

USE game_asset_hub;

-- ------------------------------------------------------------
-- 清空（按依赖顺序；本项目未建物理外键，直接清即可）
--
-- 模块2 / 模块3 的表必须先于 project / sys_user 清空：
-- 虽然库里没有物理外键，但 asset / task 行的 project_id / owner_id
-- 指向它们，先清 project 会让这些数据变成孤儿，重建演示环境时容易看花眼。
-- ------------------------------------------------------------
DELETE FROM project_report;
DELETE FROM notification;
DELETE FROM task_status_log;
DELETE FROM task_comment;
DELETE FROM task;
DELETE FROM asset_status_log;
DELETE FROM asset_version;
DELETE FROM asset;
DELETE FROM asset_category;
DELETE FROM project_member;
DELETE FROM project;
DELETE FROM sys_user;
ALTER TABLE sys_user AUTO_INCREMENT = 1;
ALTER TABLE project AUTO_INCREMENT = 1;
ALTER TABLE project_member AUTO_INCREMENT = 1;
ALTER TABLE asset_category AUTO_INCREMENT = 1;
ALTER TABLE asset AUTO_INCREMENT = 1;
ALTER TABLE asset_version AUTO_INCREMENT = 1;
ALTER TABLE asset_status_log AUTO_INCREMENT = 1;
ALTER TABLE task AUTO_INCREMENT = 1;
ALTER TABLE task_comment AUTO_INCREMENT = 1;
ALTER TABLE task_status_log AUTO_INCREMENT = 1;
ALTER TABLE notification AUTO_INCREMENT = 1;
ALTER TABLE project_report AUTO_INCREMENT = 1;

-- ------------------------------------------------------------
-- 演示账号
--
-- 两个账号密码都是 123456，但密文不同——这是 BCrypt 每次加盐的正常表现，
-- 也正好说明库里存的是哈希而不是加密后的原文。
--
-- 密文由 BCryptPasswordEncoder().encode("123456") 真实生成（60 字符），
-- 由单元测试 DemoAccountPasswordTest 读本文件校验，防止手改到登不进去。
--
--   demo  / 123456   项目负责人，项目内角色 OWNER
--   liyang    / 123456   美术，项目内角色 ARTIST
-- ------------------------------------------------------------
INSERT INTO sys_user (id, username, password, nickname, email, role, status) VALUES
(1, 'demo',
 '$2a$10$oOD7sXPh2vQL74FUdgfQsOO.HhFZARjk8LdVVPPawvTj5ZjlKa2DK',
 '管理员', 'demo@gameasset.dev', 'MEMBER', 1),
(2, 'liyang',
 '$2a$10$xLXa2ADtW3R56KWiMZy6puLeRFlyJSPimBXAo7cWGArpi.cBy1yWO',
 '李阳', 'liyang@gameasset.dev', 'MEMBER', 1);

-- ------------------------------------------------------------
-- 演示项目
--
-- 场景取自真实的 UE5 独立游戏开发流程：一个主线项目 + 一个已暂停的试验项目，
-- 便于验证「我的项目列表」「项目切换器」「状态标签」在不同状态下的表现。
-- owner_id = 1（管理员），两个项目都是他创建的。
-- ------------------------------------------------------------
INSERT INTO project (id, name, code, description, engine, owner_id,
                     start_date, plan_end_date, status, member_count) VALUES
(1, '星港计划', 'XG',
 '科幻题材第三人称探索游戏。序章发生在废弃的轨道空间站，需要大量模块化场景组件与硬表面道具。',
 'UE5', 1, '2026-09-01', '2026-12-31', 'ACTIVE', 2),
(2, '像素农场', 'PXF',
 '2D 像素风经营模拟，用于验证轻量玩法原型。当前暂停，资源已冻结。',
 'Godot', 1, '2026-06-01', '2026-08-31', 'PAUSED', 1);

-- ------------------------------------------------------------
-- 演示成员关系
--
--   项目1 星港计划：管理员 OWNER（已加入）+ 李阳 ARTIST（已加入）
--   项目2 像素农场：管理员 OWNER（已加入），单人项目
--
-- 另外插入一条 INVITED 记录演示邀请流程：把「王工」邀请进星港计划当程序员，
-- 邀请码固定为 DEMO2026INVITECODE00000000000000，可直接用于端到端验证：
--
--   方式一（被邀请人本人）：用 wangong / 123456 登录，
--          POST /api/projects/join  {"inviteCode":"DEMO2026INVITECODE00000000000000"}
--   方式二（转发给他人）：用任意其他账号登录后提交同一个邀请码，
--          邀请码是一次性的，被使用后立即作废。
--
-- 注意：joinByInviteCode 只校验「邀请码有效且未使用」并加入当前登录用户，
-- 不强制校验登录账号与被邀请账号一致——这与文档第四节对该接口的描述一致。
-- ------------------------------------------------------------
INSERT INTO sys_user (id, username, password, nickname, email, role, status) VALUES
(3, 'wangong',
 '$2a$10$a8zKeTGV0lJglEcUhyVcbOa8fVzb0M1pyjlK6O.oZm.pYqQ5nImZ.',
 '王工', 'wangong@gameasset.dev', 'MEMBER', 1);

INSERT INTO project_member (id, project_id, user_id, role, status, invite_code, join_time) VALUES
(1, 1, 1, 'OWNER',      'ACTIVE',  NULL,                          '2026-09-01 10:00:00'),
(2, 1, 2, 'ARTIST',     'ACTIVE',  NULL,                          '2026-09-03 14:30:00'),
(3, 2, 1, 'OWNER',      'ACTIVE',  NULL,                          '2026-06-01 09:00:00'),
(4, 1, 3, 'PROGRAMMER', 'INVITED', 'DEMO2026INVITECODE00000000000000', NULL);

-- 星港计划实际有 3 条未移除成员记录（含 1 条待加入），同步冗余字段
UPDATE project SET member_count = 3 WHERE id = 1;
UPDATE project SET member_count = 1 WHERE id = 2;

-- ============================================================
--  模块2 演示数据：分类 / 资产 / 版本 / 状态流转
--
--  场景承接模块1 的「星港计划」：一套 UE5 科幻场景资产，
--  覆盖全部五种状态（制作中 / 待审核 / 已通过 / 已打回 / 已废弃），
--  并特意让部分资产有 2~3 个版本，方便演示版本时间线与「设为此版本」。
-- ============================================================

-- ------------------------------------------------------------
-- 资产分类：两个一级分类各带二级分类，覆盖「分类树 + 删除校验」的演示
--
-- 编码即资产编号前缀：PROP_0001 = 场景道具类的第 1 号资产。
-- ------------------------------------------------------------
INSERT INTO asset_category (id, project_id, parent_id, name, code, icon, color, sort_order) VALUES
(1, 1, 0, '模型',     'MODEL',  'Box',        '#5b8cff', 1),
(2, 1, 1, '角色模型', 'CHR',    'Avatar',     '#5b8cff', 1),
(3, 1, 1, '场景道具', 'PROP',   'Box',        '#67c23a', 2),
(4, 1, 0, '贴图材质', 'TEX',    'Picture',    '#e6a23c', 2),
(5, 1, 4, '环境贴图', 'TEX_ENV','Picture',    '#e6a23c', 1),
(6, 1, 0, '音效',     'SFX',    'Headset',    '#f56c6c', 3),
(7, 1, 0, '蓝图',     'BP',     'SetUp',      '#909399', 4),
(8, 1, 0, '动画',     'ANIM',   'VideoPlay',  '#409eff', 5),
-- 像素农场（项目2）：用于验证分类与资产完全按项目隔离，
-- 它的「音效」与星港计划的「音效」同名但独立，互不可见
(9,  2, 0, '像素图', 'PIX', 'Picture', '#409eff', 1),
(10, 2, 0, '音效',   'SND', 'Headset', '#f56c6c', 2);

-- ------------------------------------------------------------
-- 资产
--
-- current_version_id 与 asset_version 中 is_current = 1 的那一条严格对应，
-- version_count 等于最大版本号。演示数据必须内部自洽，
-- 否则详情页会出现「当前版本为空」这类看起来像 bug 的现象。
-- ------------------------------------------------------------
INSERT INTO asset (id, project_id, category_id, asset_no, name, description, current_version_id,
                   version_count, status, priority, tags, owner_id, reviewer_id, review_time, review_remark) VALUES
(1, 1, 3, 'PROP_0001', '木箱 A 型',
 '序章场景用木质集装箱，需带磨损贴图。已通过审核，可作为正式资源引用。',
 103, 3, 'APPROVED', 'P1', '场景,道具', 2, 1, '2026-09-22 10:30:00',
 '模型结构合理，UV 需在下一版本优化'),
(2, 1, 2, 'CHR_0001', '主角宇航服',
 '主角 EVA 宇航服高模，含背包与头盔分件，等待面数确认。',
 202, 2, 'PENDING', 'P0', '角色,主角', 1, NULL, NULL, NULL),
(3, 1, 3, 'PROP_0002', '空间站地板模块',
 '模块化地板，3m×3m 网格，需与墙面模块共用接缝。',
 301, 1, 'IN_PROGRESS', 'P2', '场景,模块化', 2, NULL, NULL, NULL),
(4, 1, 5, 'TEX_ENV_0001', '磨损金属贴图',
 '4K PBR 金属磨损贴图（Albedo / Normal / Roughness 三张）。',
 402, 2, 'APPROVED', 'P1', '贴图,PBR', 1, 1, '2026-09-20 16:20:00',
 '接缝处理自然，Roughness 通道可直接使用'),
(5, 1, 6, 'SFX_0001', '推进器点火音效',
 '飞船推进器点火与持续燃烧音效，需要无缝循环。',
 502, 2, 'REJECTED', 'P0', '音效,飞船', 2, 1, '2026-09-23 09:15:00',
 '尾段 1.2s 处有明显爆音，循环点也不平滑，请重新混音后再提交'),
(6, 1, 7, 'BP_0001', '气闸舱门蓝图',
 '气闸舱门开关逻辑，含双门互锁，暂未接入交互提示。',
 601, 1, 'IN_PROGRESS', 'P1', '蓝图,交互', 1, NULL, NULL, NULL),
(7, 1, 8, 'ANIM_0001', '角色待机动画',
 '主角零重力待机循环动画，60 帧。',
 701, 1, 'PENDING', 'P2', '动画,角色', 2, NULL, NULL, NULL),
(8, 1, 3, 'PROP_0003', '旧版补给箱',
 '第一版补给箱，已被 PROP_0001 替代，仅保留历史版本供追溯。',
 801, 1, 'DEPRECATED', 'P2', '场景,废弃', 2, 1, '2026-09-10 11:00:00',
 '已由新版补给箱（PROP_0001）替代，标记为废弃'),
-- 像素农场（项目2）的资产：验证跨项目隔离（项目2 的成员看不到上面 8 条）
(9, 2, 9, 'PIX_0001', '小麦生长帧序列',
 '像素风小麦从播种到成熟的 6 帧序列图。',
 901, 1, 'IN_PROGRESS', 'P2', '像素,农作物', 1, NULL, NULL, NULL);

-- ------------------------------------------------------------
-- 资产版本
--
-- 版本号在「同一资产内」从 1 递增（uk_asset_version 保证不重复），
-- 每个资产下有且仅有一条 is_current = 1。
--
-- file_key 形如 {projectId}/{assetId}/{uuid}.{ext}，磁盘上是 UUID 名，
-- 原始文件名只存在 file_name 列。
--
-- 秒传演示：asset_version 301 与 101 的 file_md5 与 file_key 完全相同，
-- 表示「同一份物理文件」——对着 PROP_0002 调用 check-md5 接口会命中秒传。
--
-- 注：下面的 file_size 与 file_md5 是「看起来合理」的初值，导入后由
-- .tools/seed-game-asset-hub.ps1 生成真实的占位文件，并回写成该文件的
-- 真实体积与 MD5（init-db.ps1 导入 data.sql 后会自动调用它）。
-- 少了这一步，库里只有记录、磁盘上没有文件，界面上点「下载此版本」必然报错。
-- 共用同一个 file_key 的记录会被回写成同一组值——它们本来就是同一份文件。
-- ------------------------------------------------------------
INSERT INTO asset_version (id, asset_id, project_id, version_no, version_name, file_name, file_key,
                           file_size, file_ext, file_md5, thumbnail_url, poly_count, texture_size,
                           change_log, uploader_id, is_current, create_time) VALUES
-- PROP_0001 木箱 A 型：三个版本，演示版本时间线与「设为此版本」
(101, 1, 1, 1, '初版高模', 'SM_Crate_A.fbx',
 '1/1/a1b2c3d4e5f60718293a4b5c6d7e8f90.fbx', 189743104, 'fbx',
 '3f2a1b0c9d8e7f6a5b4c3d2e1f0a9b8c', NULL, 48210, NULL,
 '初版高模，未做 UV 展平', 2, 0, '2026-09-15 14:20:00'),
(102, 1, 1, 2, '修复法线朝向', 'SM_Crate_A.fbx',
 '1/1/b2c3d4e5f60718293a4b5c6d7e8f90a1.fbx', 155238400, 'fbx',
 '4a3b2c1d0e9f8a7b6c5d4e3f2a1b0c9d', NULL, 39680, '2048x2048',
 '修复法线朝向错误，UV 重新展平，面数从 48210 减到 39680', 2, 0, '2026-09-21 09:40:00'),
(103, 1, 1, 3, '优化 UV 与磨损贴图', 'SM_Crate_A_v3.fbx',
 '1/1/c3d4e5f60718293a4b5c6d7e8f90a1b2.fbx', 163577856, 'fbx',
 '5b4c3d2e1f0a9b8c7d6e5f4a3b2c1d0e', NULL, 41250, '4096x4096',
 '优化 UV 排布，补充磨损细节贴图，已通过审核', 2, 1, '2026-09-22 10:30:00'),
-- CHR_0001 主角宇航服：两个版本
(201, 2, 1, 1, '初版中模', 'CHR_EVA_Suit.blend',
 '1/2/d4e5f60718293a4b5c6d7e8f90a1b2c3.blend', 524288000, 'blend',
 '6c5d4e3f2a1b0c9d8e7f6a5b4c3d2e1f', NULL, 128400, NULL,
 '初版中模，仅主体结构', 1, 0, '2026-09-18 20:10:00'),
(202, 2, 1, 2, '增加背包与头盔分件', 'CHR_EVA_Suit.blend',
 '1/2/e5f60718293a4b5c6d7e8f90a1b2c3d4.blend', 671088640, 'blend',
 '7d6e5f4a3b2c1d0e9f8a7b6c5d4e3f2a', NULL, 142860, '4096x4096',
 '增加背包与头盔分件，拆分材质槽，面数待确认后提交审核', 1, 1, '2026-09-23 11:05:00'),
-- PROP_0002 空间站地板模块：单版本，且文件与 PROP_0001 的 V1 相同（秒传演示）
(301, 3, 1, 1, '初版', 'SM_Floor_Module.fbx',
 '1/1/a1b2c3d4e5f60718293a4b5c6d7e8f90.fbx', 189743104, 'fbx',
 '3f2a1b0c9d8e7f6a5b4c3d2e1f0a9b8c', NULL, 48210, NULL,
 '初版，与木箱共用同一份物理文件（用于演示 MD5 秒传）', 2, 1, '2026-09-24 08:30:00'),
-- TEX_ENV_0001 磨损金属贴图：两个版本
(401, 4, 1, 1, '1K 预览版', 'T_Metal_Worn_1K.png',
 '1/4/0718293a4b5c6d7e8f90a1b2c3d4e5f6.png', 4194304, 'png',
 '9f8a7b6c5d4e3f2a1b0c9d8e7f6a5b4c', NULL, NULL, '1024x1024',
 '1K 预览版，用于快速验证材质效果', 1, 0, '2026-09-16 15:00:00'),
(402, 4, 1, 2, '4K 正式版', 'T_Metal_Worn_4K.png',
 '1/4/18293a4b5c6d7e8f90a1b2c3d4e5f607.png', 67108864, 'png',
 'a09b8c7d6e5f4a3b2c1d0e9f8a7b6c5d', NULL, NULL, '4096x4096',
 '升级到 4K，补齐 Roughness 通道，已通过审核', 1, 1, '2026-09-20 16:20:00'),
-- SFX_0001 推进器点火音效：两个版本，第二版被打回
(501, 5, 1, 1, '初版', 'SFX_Thruster_Ignite.wav',
 '1/5/293a4b5c6d7e8f90a1b2c3d4e5f60718.wav', 10485760, 'wav',
 'b1a0c9d8e7f6a5b4c3d2e1f0a9b8c7d6', NULL, NULL, NULL,
 '初版，48kHz 单声道', 2, 0, '2026-09-19 17:45:00'),
(502, 5, 1, 2, '重新混音版', 'SFX_Thruster_Ignite_v2.wav',
 '1/5/3a4b5c6d7e8f90a1b2c3d4e5f6071829.wav', 12582912, 'wav',
 'c2b1d0e9f8a7b6c5d4e3f2a1b0c9d8e7', NULL, NULL, NULL,
 '重新混音，增加低频厚度（审核未通过，待再次修改）', 2, 1, '2026-09-23 09:15:00'),
-- BP_0001 气闸舱门蓝图
(601, 6, 1, 1, '初版', 'BP_AirlockDoor.uasset',
 '1/6/4b5c6d7e8f90a1b2c3d4e5f60718293a.uasset', 2097152, 'uasset',
 'd3c2e1f0a9b8c7d6e5f4a3b2c1d0e9f8', NULL, NULL, NULL,
 '初版双门互锁逻辑，尚未接入交互提示', 1, 1, '2026-09-24 09:50:00'),
-- ANIM_0001 角色待机动画
(701, 7, 1, 1, '初版', 'ANIM_Idle_ZeroG.uasset',
 '1/7/5c6d7e8f90a1b2c3d4e5f60718293a4b.uasset', 3145728, 'uasset',
 'e4d3f2a1b0c9d8e7f6a5b4c3d2e1f0a9', NULL, NULL, NULL,
 '初版 60 帧循环，等待动作确认', 2, 1, '2026-09-24 10:20:00'),
-- PROP_0003 旧版补给箱（已废弃）
(801, 8, 1, 1, '初版', 'SM_SupplyCrate_Old.fbx',
 '1/8/6d7e8f90a1b2c3d4e5f60718293a4b5c.fbx', 96468992, 'fbx',
 'f5e4a3b2c1d0e9f8a7b6c5d4e3f2a1b0', NULL, 24300, '1024x1024',
 '初版补给箱，已被 PROP_0001 替代', 2, 1, '2026-09-10 11:00:00'),
-- 像素农场（项目2）的资产版本
(901, 9, 2, 1, '初版', 'wheat_growth.png',
 '2/9/7e8f90a1b2c3d4e5f60718293a4b5c6d.png', 524288, 'png',
 '16f5b4c3d2e1f0a9b8c7d6e5f4a3b2c1', NULL, NULL, '512x512',
 '初版 6 帧序列图', 1, 1, '2026-08-20 10:00:00');

-- ------------------------------------------------------------
-- 状态流转记录
--
-- 与上面 asset.status 的最终值必须一致：asset 1/4 已通过（有 PENDING→APPROVED），
-- asset 5 已打回（有 PENDING→REJECTED 且带审核意见），
-- asset 8 已废弃（有 APPROVED→DEPRECATED）。
-- 打回记录的意见不为空，这也是服务层的强制校验。
-- ------------------------------------------------------------
INSERT INTO asset_status_log (asset_id, from_status, to_status, operator_id, remark, create_time) VALUES
(1, 'IN_PROGRESS', 'PENDING',  2, '首版完成，提交审核',                            '2026-09-21 10:00:00'),
(1, 'PENDING',     'APPROVED', 1, '模型结构合理，UV 需在下一版本优化',              '2026-09-22 10:30:00'),
(2, 'IN_PROGRESS', 'PENDING',  1, '第二版已完成，面数待确认，提交审核',              '2026-09-23 11:05:00'),
(3, NULL,          'IN_PROGRESS', 2, '创建资产',                                   '2026-09-24 08:30:00'),
(4, 'IN_PROGRESS', 'PENDING',  1, '4K 版本完成，提交审核',                        '2026-09-19 18:00:00'),
(4, 'PENDING',     'APPROVED', 1, '接缝处理自然，Roughness 通道可直接使用',         '2026-09-20 16:20:00'),
(5, 'IN_PROGRESS', 'PENDING',  2, '重新混音版完成，提交审核',                       '2026-09-23 09:00:00'),
(5, 'PENDING',     'REJECTED', 1, '尾段 1.2s 处有明显爆音，循环点也不平滑，请重新混音后再提交', '2026-09-23 09:15:00'),
(7, 'IN_PROGRESS', 'PENDING',  2, '待机循环完成，提交审核',                          '2026-09-24 10:20:00'),
(8, 'IN_PROGRESS', 'PENDING',  2, '初版完成，提交审核',                             '2026-09-09 15:00:00'),
(8, 'PENDING',     'APPROVED', 1, '可用，准予通过',                                 '2026-09-10 10:00:00'),
(8, 'APPROVED',    'DEPRECATED', 1, '已由新版补给箱（PROP_0001）替代，标记为废弃',   '2026-09-11 09:30:00');

-- ============================================================
--  模块3 演示数据：任务看板 / 评论 / 状态日志 / 通知
--
--  场景承接「星港计划」：四列都有任务，覆盖
--    · 三个逾期任务（截止日期早于今天）与一个临近截止的任务
--    · 关联资产的任务（卡片上显示资产分类标签与状态）
--    · 不同负责人（管理员 / 李阳）与不同类型的任务
--    · 评论含 @提及，通知含未读与已读
--  看一眼看板就能判断「逾期标红、四列分布、故事点合计」是否正常。
-- ============================================================

-- ------------------------------------------------------------
-- 演示成员：外部协作者（VIEWER）
--
-- 模块3 的权限矩阵里 VIEWER 是「任务看板只读」，需要有一个这样的账号
-- 才能验证「VIEWER 创建任务 → 403」这条。前面两个账号都是能写的角色，
-- 光靠它们测不出只读边界。
--
--   chenjing / 123456   外部协作者（外包），项目内角色 VIEWER
-- ------------------------------------------------------------
INSERT INTO sys_user (id, username, password, nickname, email, role, status) VALUES
(4, 'chenjing',
 '$2a$10$1IIFouT.JwfRBo4lL4t/BetVktUKAs6ud/TBGGiSLrgYXYbgM5VY2',
 '陈静', 'chenjing@gameasset.dev', 'MEMBER', 1);

INSERT INTO project_member (id, project_id, user_id, role, status, invite_code, join_time) VALUES
(5, 1, 4, 'VIEWER', 'ACTIVE', NULL, '2026-09-12 11:00:00');

-- 星港计划此时有 4 条未移除成员记录（含 1 条待加入），同步冗余字段
UPDATE project SET member_count = 4 WHERE id = 1;

-- ------------------------------------------------------------
-- 任务
--
-- sort_order 用大间隔整数（1000 / 2000 / 3000），与拖拽算法一致：
-- 中间值落位时两条相邻卡片之间还留得下 999 次插入。
--
-- version 全部为 0：它由服务端维护，客户端只回传。演示数据从 0 起，
-- 拖拽一次后前端拿到的就是 1，能直观看出乐观锁在动。
-- ------------------------------------------------------------
INSERT INTO task (id, project_id, asset_id, title, description, column_status, priority, task_type,
                  assignee_id, creator_id, due_date, estimate_hours, actual_hours, story_point,
                  sort_order, finished_at, version, create_time, update_time) VALUES
-- 待办列
(501, 1, 6, '气闸舱门接入交互提示',
 '双门互锁逻辑已通，还差交互提示的 UI 资源与按键绑定。',
 'TODO', 'P1', 'PROGRAM', 1, 1, '2026-10-05', 8.0, NULL, 5, 1000, NULL, 0,
 '2026-09-20 09:10:00', '2026-09-20 09:10:00'),
(502, 1, 5, '推进器音效重新混音',
 '上一版尾段有爆音被打回，需要重新混音并修好循环点。',
 'TODO', 'P0', 'ART', 2, 1, '2026-09-20', 4.0, NULL, 3, 2000, NULL, 0,
 '2026-09-16 14:00:00', '2026-09-16 14:00:00'),
(503, 1, 2, '主角宇航服面数优化',
 '面数超出预算，需要在保留分件的前提下压到 12 万以内。',
 'TODO', 'P0', 'ART', 2, 1, '2026-09-23', 12.0, NULL, 8, 3000, NULL, 0,
 '2026-09-19 16:20:00', '2026-09-19 16:20:00'),
-- 进行中列
(504, 1, 3, '空间站地板模块接缝对齐',
 '3m×3m 模块化地板，需与墙面模块共用接缝，当前存在可见错位。',
 'DOING', 'P1', 'ART', 2, 1, '2026-09-30', 6.0, NULL, 5, 1000, NULL, 0,
 '2026-09-18 09:00:00', '2026-09-22 15:40:00'),
(505, 1, NULL, '存档系统联调',
 '存档读写与云同步联调，需覆盖中途退出与断电两种异常路径。',
 'DOING', 'P0', 'PROGRAM', 1, 1, '2026-09-28', 16.0, NULL, 8, 2000, NULL, 0,
 '2026-09-17 10:30:00', '2026-09-23 11:00:00'),
-- 待审核列
(506, 1, 4, '磨损金属贴图 4K 验收',
 '4K PBR 三张通道已上传，等待与场景光照一起验收。',
 'REVIEW', 'P1', 'ART', 1, 2, '2026-09-25', 2.0, NULL, 2, 1000, NULL, 0,
 '2026-09-20 16:30:00', '2026-09-23 09:20:00'),
(507, 1, 7, '角色待机动画节奏确认',
 '零重力待机循环 60 帧，需要确认呼吸起伏的节奏是否符合角色设定。',
 'REVIEW', 'P2', 'DESIGN', 2, 1, '2026-10-02', 3.0, NULL, 3, 2000, NULL, 0,
 '2026-09-21 13:00:00', '2026-09-24 10:20:00'),
-- 已完成列
(508, 1, 1, '木箱 A 型 UV 优化',
 '优化 UV 排布并补充磨损细节贴图，已通过审核。',
 'DONE', 'P1', 'ART', 2, 1, '2026-09-22', 10.0, 9.5, 5, 1000, '2026-09-22 10:30:00', 0,
 '2026-09-14 09:00:00', '2026-09-22 10:30:00'),
(509, 1, NULL, '项目骨架与目录规范',
 '搭好工程骨架并约定资产目录规范，后续资产统一按规范入库。',
 'DONE', 'P2', 'OTHER', 1, 1, '2026-09-18', 6.0, 5.0, 3, 2000, '2026-09-18 18:00:00', 0,
 '2026-09-02 09:00:00', '2026-09-18 18:00:00'),
-- 像素农场（项目2）的任务：验证任务同样按项目隔离，
-- 项目1 的成员列表与看板里都不该出现它
(510, 2, 9, '小麦生长帧序列切图',
 '把 6 帧生长序列切成独立贴图并配好命名，供引擎侧按帧号引用。',
 'TODO', 'P2', 'ART', 1, 1, '2026-09-30', 2.0, NULL, 2, 1000, NULL, 0,
 '2026-08-21 10:00:00', '2026-08-21 10:00:00');

-- ------------------------------------------------------------
-- 任务评论（含 @提及）
--
-- mention_ids 存被 @ 的用户ID，逗号分隔。这里的两条提及都指向李阳（2），
-- 与通知表里那两条 MENTION / COMMENT 通知严格对应：
-- 「评论提到谁」与「谁收到了提及通知」在演示数据里必须一致，
-- 否则演示时点开通知会找不到对应的评论。
-- ------------------------------------------------------------
INSERT INTO task_comment (id, task_id, project_id, user_id, content, mention_ids, create_time) VALUES
(1001, 504, 1, 1, '@李阳 接缝这里和墙面模块对不齐，麻烦按 3m 网格重新对齐一下，我这边先按新网格摆场景。',
 '2', '2026-09-22 15:40:00'),
(1002, 504, 1, 2, '收到，今晚改完推到待审核，明早可以一起看。', NULL, '2026-09-23 09:05:00'),
(1003, 503, 1, 1, '@李阳 面数还是超了，头盔分件能不能合到主体上？如果不行我们再单开一个贴图交换的方案。',
 '2', '2026-09-21 10:12:00'),
(1004, 506, 1, 2, '4K 版已经上传，Roughness 通道补上了，验收时注意看一下接缝处的金属高光。', NULL,
 '2026-09-23 09:20:00'),
(1005, 501, 1, 1, '双门互锁逻辑已通，等交互提示的 UI 资源到位后一起联调。', NULL, '2026-09-23 17:30:00');

-- ------------------------------------------------------------
-- 任务状态变更日志
--
-- 每个任务都从「新建 → 待办」开始，非待办列的任务再补上后续流转。
-- 每条的最后一行 to_status 必须等于 task.column_status——
-- 不一致的话，任务详情页的「状态变更日志」会与卡片上的列对不上，
-- 看起来就像状态同步坏了（模块4 的燃尽图也要靠这张表回放）。
-- ------------------------------------------------------------
INSERT INTO task_status_log (task_id, project_id, from_status, to_status, operator_id, create_time) VALUES
(501, 1, NULL, 'TODO',   1, '2026-09-20 09:10:00'),
(502, 1, NULL, 'TODO',   1, '2026-09-16 14:00:00'),
(503, 1, NULL, 'TODO',   1, '2026-09-19 16:20:00'),
(504, 1, NULL, 'TODO',   1, '2026-09-18 09:00:00'),
(504, 1, 'TODO', 'DOING', 2, '2026-09-22 15:40:00'),
(505, 1, NULL, 'TODO',   1, '2026-09-17 10:30:00'),
(505, 1, 'TODO', 'DOING', 1, '2026-09-23 11:00:00'),
(506, 1, NULL, 'TODO',   2, '2026-09-20 16:30:00'),
(506, 1, 'TODO', 'DOING', 1, '2026-09-22 09:00:00'),
(506, 1, 'DOING', 'REVIEW', 1, '2026-09-23 09:20:00'),
(507, 1, NULL, 'TODO',   1, '2026-09-21 13:00:00'),
(507, 1, 'TODO', 'DOING', 2, '2026-09-23 14:00:00'),
(507, 1, 'DOING', 'REVIEW', 2, '2026-09-24 10:20:00'),
(508, 1, NULL, 'TODO',   1, '2026-09-14 09:00:00'),
(508, 1, 'TODO', 'DOING', 2, '2026-09-16 09:00:00'),
(508, 1, 'DOING', 'REVIEW', 2, '2026-09-21 18:00:00'),
(508, 1, 'REVIEW', 'DONE', 1, '2026-09-22 10:30:00'),
(509, 1, NULL, 'TODO',   1, '2026-09-02 09:00:00'),
(509, 1, 'TODO', 'DOING', 1, '2026-09-03 09:00:00'),
(509, 1, 'DOING', 'DONE', 1, '2026-09-18 18:00:00'),
(510, 2, NULL, 'TODO',   1, '2026-08-21 10:00:00');

-- ------------------------------------------------------------
-- 站内通知
--
-- 演示两种视角：
--   李阳（2）3 条未读 —— 演示铃铛角标与「可筛未读」
--   管理员（1）1 条未读 —— 演示「项目负责人收到提交审核提醒」
--
-- 所有通知都不自己发给自己（文档 5.8 的硬规则）：sender_id 与 user_id 必然不同。
-- ------------------------------------------------------------
INSERT INTO notification (id, project_id, user_id, type, title, content, biz_type, biz_id, sender_id, is_read, create_time) VALUES
(1001, 1, 2, 'TASK_ASSIGNED', '有新任务指派给你：推进器音效重新混音',
 '优先级 P0，截止 2026-09-20', 'TASK', 502, 1, 0, '2026-09-16 14:00:00'),
(1002, 1, 2, 'MENTION', '你在任务里被提及：空间站地板模块接缝对齐',
 '管理员：@李阳 接缝这里和墙面模块对不齐，麻烦按 3m 网格重新对齐一下。', 'TASK', 504, 1, 0,
 '2026-09-22 15:40:00'),
(1003, 1, 2, 'ASSET_REVIEW', '资产被打回：推进器点火音效',
 '尾段 1.2s 处有明显爆音，循环点也不平滑，请重新混音后再提交', 'ASSET', 5, 1, 0,
 '2026-09-23 09:15:00'),
(1004, 1, 2, 'TASK_STATUS', '任务状态变更：木箱 A 型 UV 优化',
 '待审核 → 已完成', 'TASK', 508, 1, 1, '2026-09-22 10:30:00'),
(1005, 1, 1, 'COMMENT', '任务有新评论：空间站地板模块接缝对齐',
 '李阳：收到，今晚改完推到待审核，明早可以一起看。', 'TASK', 504, 2, 1, '2026-09-23 09:05:00'),
(1006, 1, 1, 'ASSET_REVIEW', '资产提交审核：角色待机动画',
 '待机循环完成，提交审核', 'ASSET', 7, 2, 0, '2026-09-24 10:20:00');

-- ============================================================
--  模块4 演示数据：日期维表 + 让统计图有内容的补充任务
--
--  为什么这里的日期全部写成 CURDATE() 的相对表达式，而上面的模块3
--  演示数据是固定日期：模块4 的三张图（燃尽图 / 趋势 / 工作量）
--  横轴都带「今天」。固定日期过一段时间之后会整段滑出统计窗口，
--  表现成「图是空的」——而代码没有任何问题，排查方向会彻底跑偏。
--  相对日期让这套演示数据任何时候导入都有内容。
-- ============================================================

-- ------------------------------------------------------------
-- 日期维表（文档 5.6）
--
-- 覆盖 [CURDATE() - 200 天, CURDATE() + 200 天]，共 401 行。
-- 用递归 CTE 生成而不是写死某一年的 365 天：写死年月的话，
-- 演示日期一过，燃尽图就会画出空曲线。
--
-- 先 DELETE 再 INSERT，保证本文件可重复执行。
-- 递归深度的默认上限是 1000，401 轮不会触顶，不必改会话变量。
--
-- 注意：本表只覆盖导入时刻前后各 200 天。若在很久之后才打开页面，
-- 而这个窗口已经盖不住项目的计划区间，则燃尽图的实际线会少掉
-- 窗口之外的那一段——此时重新导入一次本文件（或 schema + data）即可。
-- ------------------------------------------------------------
DELETE FROM calendar_dates;
INSERT INTO calendar_dates (stat_date, is_workday)
WITH RECURSIVE seq (stat_date) AS (
    SELECT CURDATE() - INTERVAL 200 DAY
    UNION ALL
    SELECT stat_date + INTERVAL 1 DAY FROM seq
    WHERE stat_date < CURDATE() + INTERVAL 200 DAY
)
SELECT stat_date, CASE WHEN DAYOFWEEK(stat_date) IN (1, 7) THEN 0 ELSE 1 END
FROM seq;

-- ------------------------------------------------------------
-- 补充任务：让燃尽图 / 趋势图 / 工作量图有内容
--
-- 四张新任务刻意覆盖四种统计场景：
--   511 完成过又被拖回 TODO —— 验证燃尽图「用日志回放」的口径：
--       它在 -11 天完成、-8 天被拖回，但历史点位不会因为这次回退而改变，
--       因此这条任务在曲线上仍然表现为「那天之后就烧掉了」。
--       若哪天有人把回放 SQL 改成「看当前状态」，这条任务就是第一个报警的。
--   512 还在进行中、填了实际工时 —— 工作量表的「工时」列要有非零值
--   513 两周内完成 —— 趋势图的「完成」线不能是平的
--   514 推到待审核 —— 让待审核列的资产/任务统计不为空
--
-- sort_order 沿用大间隔整数（1000 一档），接在既有卡片后面，
-- 不影响模块3 看板脚本对「列内顺序严格递增」的断言。
-- 主键从 511 起，不与模块3 的 501~510 冲突；e2e 脚本依赖的既有任务
-- 主键（501~510）一个都没动。
-- ------------------------------------------------------------
INSERT INTO task (id, project_id, asset_id, title, description, column_status, priority, task_type,
                  assignee_id, creator_id, due_date, estimate_hours, actual_hours, story_point,
                  sort_order, finished_at, version, create_time, update_time) VALUES
(511, 1, NULL, '序章灯光氛围调优',
 '把序章空间站的冷白光改成带暖色补光的方案，已完成过一版，评审后决定回到待办重做。',
 'TODO', 'P1', 'ART', 2, 1, CURDATE() + INTERVAL 6 DAY, 6.0, NULL, 5, 4000, NULL, 0,
 CURDATE() - INTERVAL 18 DAY + INTERVAL 9 HOUR, CURDATE() - INTERVAL 8 DAY + INTERVAL 9 HOUR),
(512, 1, NULL, '空间站外壳模块 UV 展平',
 '外壳六块模块统一展平到 0-1 空间，接缝处预留重叠像素。',
 'DOING', 'P2', 'PROGRAM', 2, 1, CURDATE() + INTERVAL 9 DAY, 8.0, 5.5, 8, 3000, NULL, 0,
 CURDATE() - INTERVAL 14 DAY + INTERVAL 11 HOUR, CURDATE() - INTERVAL 9 DAY + INTERVAL 10 HOUR),
(513, 1, 6, '重力系统原型验证',
 '在无重力与人工重力两套参数之间切换，验证角色移动手感是否可控。',
 'DONE', 'P1', 'PROGRAM', 1, 2, NULL, 6.0, 6.0, 5, 3000,
 CURDATE() - INTERVAL 3 DAY + INTERVAL 16 HOUR, 0,
 CURDATE() - INTERVAL 7 DAY + INTERVAL 9 HOUR, CURDATE() - INTERVAL 3 DAY + INTERVAL 16 HOUR),
(514, 1, 5, '音效总线与混音规则整理',
 '整理三档音效总线的响度规范，供后续所有音效资产按同一套规则制作。',
 'REVIEW', 'P2', 'ART', 1, 1, CURDATE() + INTERVAL 3 DAY, 3.0, NULL, 3, 3000, NULL, 0,
 CURDATE() - INTERVAL 5 DAY + INTERVAL 14 HOUR, CURDATE() - INTERVAL 2 DAY + INTERVAL 15 HOUR);

-- 上面四条任务的状态变更日志。每条的最后一行 to_status 必须等于
-- task.column_status，否则任务详情页的日志会与卡片上的列对不上。
-- 511 的最后一行是 DONE → TODO（就是「完成过又被拖回待办」那一次）。
INSERT INTO task_status_log (task_id, project_id, from_status, to_status, operator_id, create_time) VALUES
(511, 1, NULL,     'TODO',  1, TIMESTAMP(CURDATE() - INTERVAL 18 DAY, '09:30:00')),
(511, 1, 'TODO',   'DOING', 2, TIMESTAMP(CURDATE() - INTERVAL 15 DAY, '10:00:00')),
(511, 1, 'DOING',  'DONE',  1, TIMESTAMP(CURDATE() - INTERVAL 11 DAY, '17:20:00')),
(511, 1, 'DONE',   'TODO',  1, TIMESTAMP(CURDATE() - INTERVAL 8 DAY,  '09:15:00')),
(512, 1, NULL,     'TODO',  1, TIMESTAMP(CURDATE() - INTERVAL 14 DAY, '11:00:00')),
(512, 1, 'TODO',   'DOING', 2, TIMESTAMP(CURDATE() - INTERVAL 9 DAY,  '09:40:00')),
(513, 1, NULL,     'TODO',  2, TIMESTAMP(CURDATE() - INTERVAL 7 DAY,  '09:00:00')),
(513, 1, 'TODO',   'DOING', 1, TIMESTAMP(CURDATE() - INTERVAL 5 DAY,  '09:20:00')),
(513, 1, 'DOING',  'DONE',  1, TIMESTAMP(CURDATE() - INTERVAL 3 DAY,  '16:45:00')),
(514, 1, NULL,     'TODO',  1, TIMESTAMP(CURDATE() - INTERVAL 5 DAY,  '14:10:00')),
(514, 1, 'TODO',   'DOING', 1, TIMESTAMP(CURDATE() - INTERVAL 4 DAY,  '09:30:00')),
(514, 1, 'DOING',  'REVIEW', 1, TIMESTAMP(CURDATE() - INTERVAL 2 DAY, '15:00:00'));
