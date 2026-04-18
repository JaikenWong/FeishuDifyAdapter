# Feishu Robot Adapter for Dify

飞书机器人与 Dify 应用对接的适配服务：通过**飞书 SDK 长连接**接收消息，调用 Dify 流式对话 API，并将回复实时更新到飞书**交互式卡片**。提供 **Web 配置台**管理多机器人、导出对话记录。

---

## 目录

- [功能概览](#功能概览)
- [技术栈](#技术栈)
- [飞书侧：创建应用与权限](#飞书侧创建应用与权限)
- [本服务中的接入方式（长连接）](#本服务中的接入方式长连接)
- [Dify 侧配置](#dify-侧配置)
- [Web 配置台使用说明](#web-配置台使用说明)
- [配置项说明（application.yml）](#配置项说明applicationyml)
- [数据存储与重启说明](#数据存储与重启说明)
- [本地运行与打包](#本地运行与打包)
- [安全建议](#安全建议)
- [常见问题](#常见问题)
- [License](#license)

---

## 功能概览

| 能力 | 说明 |
|------|------|
| 长连接收消息 | 使用飞书 `com.lark.oapi.ws.Client`，无需公网 HTTP 事件回调地址 |
| Dify 流式对话 | `POST /chat-messages`，SSE 按行解析，卡片增量更新 |
| 多轮会话 | 按「机器人配置 + Dify 用户标识 + 飞书 chat」复用 `conversation_id`（见下文） |
| 用户上下文 | 可选：飞书通讯录姓名/工号/邮箱等传入 Dify `inputs`；Dify `user` 优先用工号 |
| 多模态 | 文本 / 图片 / 文件 / 富文本（post）入站；出站解析 Dify 附件并渲染卡片 |
| 管理后台 | 登录后维护多机器人、开关长连接、导出 CSV 记录 |

---

## 技术栈

- Java 21、Spring Boot 3.3
- Spring Data JPA + **H2 文件库**（默认 `./data/feishu-robot-adapter`）
- 飞书 OpenAPI SDK（`oapi-sdk`）
- 前端：静态页 `login.html` / `index.html` + `styles.css` / `app.js`

---

## 飞书侧：创建应用与权限

在 [飞书开放平台](https://open.feishu.cn/) 创建**企业自建应用**（机器人能力按你实际场景开通）。

### 1. 基础凭证

- **App ID**、**App Secret**：应用详情页「凭证与基础信息」中复制，填入本服务 Web 表单。

### 2. 事件与加密（长连接场景）

本服务使用 **SDK 长连接**接收事件，**不依赖**「请求网址 URL」类 HTTP 回调；但仍建议在开放平台中：

- 按需配置 **事件订阅**中的 **Verification Token**、**Encrypt Key**（若启用加密），并与本服务表单中填写一致，供 SDK 内 `EventDispatcher` 校验/解密事件。

### 3. 建议开通的权限（scope）

以下按**功能**归类，在开放平台「权限管理」中搜索并勾选；**发布新版本**并由**管理员审核**后生效。

#### 消息与机器人（必选）

| 权限能力（名称以控制台为准） | 用途 |
|------------------------------|------|
| 获取与发送单聊、群组消息（如 `im:message`、`im:message:send_as_bot` 等，以控制台列表为准） | 收消息、以机器人身份回复/更新卡片 |
| 读取用户发给机器人的单聊消息 / 群组消息 | 接收用户消息事件 |

> 具体 API 名称以飞书当前版本文档为准，请搜索 **「接收消息」「发送消息」** 相关权限。

#### 卡片与资源（用于交互式卡片、图片等）

| 权限能力 | 用途 |
|----------|------|
| 卡片相关（如 `cardkit` 创建/更新卡片类权限，以控制台为准） | 创建与流式更新交互式卡片 |
| 下载消息内资源 / 读取图片与文件 | 下载用户发送的图片、文件再上传 Dify |

#### 通讯录（可选，用于用户名、工号、邮箱进入 Dify）

若需要在 Dify 中区分用户身份或带入**姓名 / 工号 / 邮箱**，需能调用「获取用户」类通讯录接口，例如：

| 权限能力 | 用途 |
|----------|------|
| 获取通讯录基本信息，或以应用身份读通讯录（如 `contact:contact.base:readonly`、`contact:contact:access_as_app` 等，**任选其一组合**，以官方文档为准） | 调用 `contact/v3/users/:user_id` |
| 获取用户基本信息 | 返回姓名、英文名等 |
| 查看成员工号 | `employee_no` 字段 |
| 获取用户邮箱信息 | 邮箱字段 |

同时需在**管理后台**将应用授权到**可见的部门/人员范围**，否则会出现「通讯录拉取失败、仅有群聊 @ 展示名」等情况。

### 4. 将机器人用于群聊 / 单聊

- 在飞书客户端将应用机器人**拉入群**或**单聊**，用户向机器人发消息或 @ 机器人，事件才会到达本服务。

---

## 本服务中的接入方式（长连接）

- 在 Web 配置台为某条机器人配置开启 **「长连接」** 后，进程内会创建 `com.lark.oapi.ws.Client` 并 `start()`。
- 应用**重启**后，若数据库中该配置 `longConnectionEnabled = true`，启动逻辑会尝试**自动恢复**长连接（具体见 `LongConnectionStartupInitializer`）。
- 关闭长连接会断开 WebSocket（实现上通过反射调用 SDK 内 `disconnect` 等，见 `InMemoryFeishuLongConnectionManager`）。

---

## Dify 侧配置

### Base URL 与 API Key

- **Dify Base URL**：可填根地址或带 `/v1` 的地址，服务内会规范化后请求 `/chat-messages`、`/files/upload`。
- **API Key**：在 Dify 应用内创建，填入 Web 表单。

### 多轮对话（conversation）

- 本服务会把上一次成功返回的 **`conversation_id`** 与 **`user`** 一并持久化，下次同用户、同群继续传参给 Dify。
- **`user` 标识规则**：若飞书通讯录能取到 **工号**，则优先用工号作为 Dify 的 `user`；否则使用 `open_id`，再否则 `union_id`。上传文件接口使用同一 `user`，以与对话一致。

### 传入 Dify 的 `inputs` 变量（可选）

在 Dify 应用里添加**同名输入变量**后，可在提示词或工作流中引用（语法以你使用的 Dify 版本为准）：

| 变量名 | 含义 |
|--------|------|
| `feishu_sender_name` | 展示名 |
| `feishu_full_name` | 通讯录姓名 |
| `feishu_employee_no` | 工号 |
| `feishu_email` | 邮箱 |
| `feishu_en_name` | 英文名 |
| `feishu_union_id` | 飞书 union_id |

未开通通讯录权限时，部分字段可能为空。

---

## Web 配置台使用说明

### 1. 访问与登录

1. 浏览器访问：`http://<服务器>:8080`（默认端口 `8080`）。
2. 未登录会进入登录页；默认账号见下文 `application.yml`（**生产环境务必修改**）。

### 2. 添加机器人配置

登录后点击 **「添加配置」**，在弹窗中填写：

| 区块 | 字段 | 说明 |
|------|------|------|
| 基础信息 | 机器人名称 | 仅用于本后台展示 |
| 飞书接入 | App ID / App Secret | 开放平台应用凭证 |
| 飞书接入 | Verification Token / Encrypt Key | 与事件订阅配置一致；不用加密可留空（视开放平台设置） |
| Dify | Base URL | 你的 Dify 服务地址 |
| Dify | API Key | 应用 API Key |

提交成功后，列表中会出现对应卡片。

### 3. 开启长连接

在卡片上操作 **开启长连接**（具体按钮文案以页面为准）。开启成功后，飞书消息才会进入本服务并转发 Dify。

### 4. 对话记录与导出

- 后台会保存问答记录（含 OpenId、Dify 用户标识、chat、Dify 会话 id 等）。
- 使用 **导出记录**（若已提供）可下载 CSV，便于审计。

---

## 配置项说明（`application.yml`）

| 配置 | 说明 |
|------|------|
| `server.port` | HTTP 端口，默认 `8080` |
| `spring.datasource.url` | H2 文件库路径；**相对路径相对进程工作目录**，换目录启动会换库，生产建议改为**固定绝对路径** |
| `app.auth.default-admin-username` / `default-admin-password` | 默认登录账号，**部署前请修改** |

可通过 `application-local.yml`（已加入 `.gitignore`）覆盖本地配置。

---

## 数据存储与重启说明

- 对话与机器人配置保存在 **H2 文件库**（默认 `./data/` 下，且已在 `.gitignore` 中忽略，**勿将业务库提交 Git**）。
- 进程重启后，只要**库文件路径不变**，Dify 多轮会话与配置仍在；启动时会尝试回填历史行的 `dify_user_key`，避免升级后续聊丢失（见 `ConversationRecordBackfillRunner`）。

---

## 本地运行与打包

```bash
mvn clean package -DskipTests
java -jar target/feishu-robot-adapter-*.jar
```

开发时：

```bash
mvn spring-boot:run
```

健康检查（若已暴露）：`GET /api/health`（以实际 `HealthController` 为准）。

---

## 安全建议

1. **修改默认管理员密码**，并限制管理后台访问 IP 或前置网关鉴权。
2. **勿**将真实 `App Secret`、Dify Key 提交到公开仓库；生产用环境变量或密钥管理。
3. H2 默认无密码，**不要**把数据库文件暴露到公网目录。
4. 飞书、Dify 的权限与令牌遵循各自平台最小权限原则。

---

## 常见问题

**Q：收不到飞书消息？**  
检查：机器人是否入群/单聊、长连接是否已开启、应用权限与版本是否已发布并通过审核。

**Q：Dify 多轮对话断了？**  
检查：是否更换了进程工作目录导致 H2 路径变化；`user` 是否从 open_id 切换为工号（切换后 Dify 侧为新用户会话）。

**Q：拿不到用户姓名/工号？**  
检查通讯录权限与可见范围；群聊仅 @ 机器人时可能没有发送者 mentions，需依赖通讯录接口。

**Q：`Missing artifact fastjson`？**  
本仓库**不依赖** Fastjson，JSON 使用 Jackson；若本地 `pom.xml` 误加错误坐标的依赖，请删除或改为 `com.alibaba:fastjson` 正确 GAV。

---

## 工作流程（简图）

```
飞书消息 → SDK 长连接 → 解析消息/附件 → Dify 流式 API
         → 更新飞书卡片 ← 累积文本与附件展示
```

---

## License

MIT
