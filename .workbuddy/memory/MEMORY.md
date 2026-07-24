# aps-lh-parent 项目长期记忆

## 项目概况
- 硫化排程（APS）系统，多模块 Maven 工程。
- 技术栈：Java 8 + Spring Boot + MyBatis-Plus + Hutool。
- 主模块 `aps-lh`，API 模块 `aps-lh-api`。
- 启动类入口位于 `aps-lh` 模块，端口默认 9669。

## 角色与规范
- 在本项目需同时承担：硫化排程业务专家、排程算法专家、资深 Java 后端。
- 完整编码规范、业务改造要求、SQL 规范见根目录 `AGENTS.md`（以该文件为权威依据，本笔记仅补充实践中确认的关键点）。

## 常用命令
- 启动：`mvn spring-boot:run -pl aps-lh -Dmaven.test.skip=true`
- 排程执行验证：`POST /lhScheduleResult/execute`，body `{"factoryCode":"116","scheduleDate":"..."}`
- 接口示例：`curl -X POST 'http://localhost:9669/lhScheduleResult/execute' -H 'Content-Type: application/json' -d '{"factoryCode":"116","scheduleDate":"排程日期"}'`

## 排程核心概念（速查）
月计划 / 日计划 / 机台 / 模具 / 胎胚 / SKU / 续作 / 换模 / 换活字块 / 滚动排程 / 满产排程 / 欠产 / 开停产。
修改排程算法须同步检查相关入口，禁止单点改动导致前后逻辑不一致。

---
（后续实践确认的内容追加在下方）
