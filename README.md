# 游戏项目资产与协作管理系统（GameAsset Hub）

> 面向游戏项目的**资产管理与团队协作平台**，Java 企业级应用开发课程作业。前后端分离，覆盖项目与成员管理、美术资产与版本管理、任务看板与协作、统计与进度可视化四大模块。

## 项目简介

结合作者在 Unreal Engine 5 与 Blender 方向的独立游戏开发经验，针对「美术资产数量庞大、版本迭代频繁、跨角色协作」的痛点，构建一个统一的资产管理与团队协作入口：

- **项目与成员管理**：多项目管理、邀请制加入、角色权限（RBAC）。
- **美术资产与版本管理**：资产分类、版本历史、状态审核流转、大文件分片上传与秒传。
- **任务看板与协作**：四列看板拖拽、任务评论与 @提及、站内通知。
- **统计与进度可视化**：资产完成率、燃尽图、成员工作量、周报 / 月报、Excel 导出。

## 技术栈

| 层次 | 技术 |
| --- | --- |
| 后端 | Java 17 · Spring Boot 3.5 · MyBatis-Plus 3.5 · MySQL 8.0 · Redis 5.0 · JWT（jjwt）· Apache POI |
| 前端 | Vue 3 · TypeScript · Vite 8 · Element Plus · Pinia · Vue Router · ECharts · vuedraggable |
| 存储 | 本地文件（默认）/ MinIO（预留抽象接口） |

## 系统架构

前后端分离的分层架构：

```
浏览器（Vue 3 单页应用）
   │  HTTP / JSON（Bearer Token + X-Project-Id）
后端（Spring Boot 3.5，端口 8082）
   ├── Controller 层（REST 接口、参数校验、统一返回 Result<T>）
   ├── 横切关注点（JwtInterceptor 登录态 / @ProjectPermission AOP 项目级 RBAC / 全局异常）
   ├── Service 层（业务编排 + support 支撑组件）
   └── 持久层（MyBatis-Plus Mapper / StorageService 抽象）
   │
   ├── MySQL 8.0（game_asset_hub，13 张表）
   ├── Redis 5.0（缓存 / 黑名单 / 锁 / 编号计数）
   └── 本地文件 / MinIO（资产文件存储）
```

## 功能截图

| 界面 | 截图 |
| --- | --- |
| 登录页 | ![登录](Output/01.png) |
| 项目列表 | ![项目列表](Output/02.png) |
| 项目概览 | ![概览](Output/03.png) |
| 资产列表 | ![资产列表](Output/04.png) |
| 看板 | ![看板](Output/05.png) |
| 燃尽图 | ![燃尽图](Output/06.png) |
| 成员工作量 | ![工作量](Output/07.png) |
| 周报 / 月报 | ![周报](Output/08.png) |
| 成员管理 | ![成员](Output/09.png) |
| 资产详情 | ![资产详情](Output/10.png) |
| 通知 | ![通知](Output/11.png) |

## 权限模型（RBAC）

| 角色 | 定位 | 权限 |
| --- | --- | --- |
| OWNER（负责人） | 项目创建者 | 项目内全权限 |
| ARTIST（美术） | 资产生产者 | 资产读写、删除草稿、看板读写、可看统计（无审核权） |
| PROGRAMMER（程序） | 程序开发 | 资产只读、看板读写、可看统计 |
| DESIGNER（策划） | 策划 | 资产只读、看板读写、可看统计 |
| VIEWER（外部协作者） | 外部协作 | 项目 / 资产 / 看板只读，无统计、无写权限 |

## 目录结构

```
GameAssetHub/
├── game-asset-hub/          # 后端（Spring Boot）
│   ├── src/main/java/com/zhangchi/gameasset/   # 后端源码
│   └── src/main/resources/db/                  # schema.sql + data.sql
├── game-asset-hub-web/      # 前端（Vue 3 + Vite）
│   └── src/                 # 前端源码
├── .tools/                  # 便携工具链（JDK / Node / Maven / MySQL / Redis，随包携带）
├── start-all.cmd / start-all.ps1   # 一键启动
├── stop-all.cmd / stop-all.ps1     # 一键停止
├── Output/                  # 功能截图
└── README.md
```

## 快速开始

项目自带完整工具链（`.tools`），**无需预装任何环境**：

```powershell
# 双击或执行（幂等，可重复执行）
start-all.cmd
# 或
powershell -ExecutionPolicy Bypass -File start-all.ps1
```

脚本会装载随包的 JDK 17 / Node 24 / Maven / MySQL 8.0 / Redis 5.0，启动便携 Redis（6379）与便携 MySQL（**3307**），首次运行自动建库并导入演示数据，然后启动后端（8082）与前端（5175）。

停止：`stop-all.cmd` 或 `stop-all.ps1`。

### 演示账号

| 用户名 | 密码 | 项目角色 |
| --- | --- | --- |
| `zhangchi` | `123456` | OWNER（星港计划、像素农场） |
| `liyang` | `123456` | ARTIST（星港计划） |
| `wangong` | `123456` | PROGRAMMER（初始 INVITED） |
| `chenjing` | `123456` | VIEWER（星港计划） |

邀请码演示：`DEMO2026INVITECODE00000000000000`。

## 测试

- 单元测试 **500 项全绿**（Mockito mock mapper，不连 MySQL/Redis）。
- 端到端验证脚本：`.tools/verify-p03-m4.ps1`（后端接口 204 项）+ `.tools/verify-p03-m4-ui.ps1`（前端渲染冒烟 63 项）。

## 更多文档

- **后端详细说明**：[game-asset-hub/README.md](game-asset-hub/README.md)（技术栈、接口清单、模块实现、与实现文档的偏离等）
- **设计文档 / 技术文档**：见 `docs/` 目录（或参考项目简要说明）。
