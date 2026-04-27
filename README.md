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
| Dify 流式对话 | `POST /chat-messages`，SSE 按行解析，**每 20 字符增量更新卡片**平衡流畅度与API调用次数 |
| 多轮会话 | 按「机器人配置 + Dify 用户标识 + 飞书 chat」复用 `conversation_id`（见下文） |
| 会话重置 | 用户发送 `/clear` 可清空当前会话上下文，下一条消息强制新会话 |
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

### 3. 权限（scope）清单

在开放平台「权限管理」中搜索并勾选；**创建版本并发布**，由**租户管理员审核**后生效。下列为与本项目**联调通过**的权限集合（可按实际产品裁剪：未使用 Aily、多维表/Base、Wiki、人事附件等能力时可去掉对应项）。

#### 3.1 完整 JSON（便于复制核对）

```json
{
  "scopes": {
    "tenant": [
      "aily:file:read",
      "aily:file:write",
      "application:application.app_message_stats.overview:readonly",
      "application:application:self_manage",
      "application:bot.menu:write",
      "base:record:retrieve",
      "bitable:app",
      "bitable:app:readonly",
      "cardkit:card:read",
      "cardkit:card:write",
      "contact:contact.base:readonly",
      "contact:user.basic_profile:readonly",
      "contact:user.email:readonly",
      "contact:user.employee_id:readonly",
      "contact:user.employee_number:read",
      "corehr:file:download",
      "event:ip_list",
      "im:chat.access_event.bot_p2p_chat:read",
      "im:chat.members:bot_access",
      "im:message",
      "im:message.group_at_msg:readonly",
      "im:message.p2p_msg:readonly",
      "im:message:readonly",
      "im:message:send_as_bot",
      "im:resource"
    ],
    "user": [
      "aily:file:read",
      "aily:file:write",
      "contact:contact.base:readonly",
      "im:chat.access_event.bot_p2p_chat:read",
      "im:message",
      "wiki:node:read",
      "wiki:wiki",
      "wiki:wiki:readonly"
    ]
  }
}
```

#### 3.2 与本服务相关的权限说明（tenant 为主）

| Scope | 说明 |
|-------|------|
| `im:message` / `im:message:send_as_bot` / `im:message:readonly` 等 | 收发消息、以机器人发消息、读会话消息 |
| `im:resource` | 下载消息内图片/文件（多模态转发 Dify） |
| `im:chat.members:bot_access` / `im:chat.access_event.bot_p2p_chat:read` | 群成员与机器人单聊相关事件 |
| `cardkit:card:read` / `cardkit:card:write` | 创建与更新交互式卡片 |
| `contact:contact.base:readonly` | 通讯录基础能力，配合获取用户 |
| `contact:user.basic_profile:readonly` | 用户基础资料 |
| `contact:user.email:readonly` | 用户邮箱（传入 Dify `inputs`） |
| `contact:user.employee_id:readonly` | 租户内 user_id 等标识 |
| `contact:user.employee_number:read` | **工号**（作 Dify `user` 与 `inputs` 时优先） |
| `application:application:self_manage` / `application:bot.menu:write` / `application:application.app_message_stats.overview:readonly` 等 | 应用自管、机器人菜单、应用消息统计只读等（按需在控制台配置） |
| `base:record:retrieve` / `bitable:app` / `bitable:app:readonly` | 云文档-多维表（Base / Bitable）数据读取；**本仓库核心链路未用时可从控制台裁掉** |
| `wiki:node:read` / `wiki:wiki` / `wiki:wiki:readonly` | 知识空间（Wiki）只读，**在 user 身份授权**场景下与 tenant 侧配合；未接 Wiki 可删 |
| `aily:file:*`、`corehr:file:download` | 若未使用 Aily / CoreHR 文件能力，可按需移除 |

**user** 侧 scope 为「用户身份」授权场景下使用；与 **tenant** 侧配合以控制台实际要求为准。

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
- 用户发送 **`/clear`** 时，会清空当前「机器人 + 用户 + chat」下已保存的 `conversation_id`，并回复提示“下一条消息将开启新会话”。

### `<think>` 兼容处理

- 对于未开启“推理分离”或会输出推理标签的模型，本服务会在流式阶段过滤 `<think>...</think>` 段，仅把可见回答发给飞书卡片。
- 过滤器支持标签跨 chunk 断裂场景（例如 `<thi` + `nk>`）。

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

配置台支持「Dify input 映射表」动态添加多行，每行可配置：
- Dify 变量名（如 `user_name`）
- 来源字段（展示名/工号/姓名/邮箱/英文名）

---

## Web 配置台使用说明

### 1. 访问与登录

1. 浏览器访问：`http://<服务器>:8081`（默认端口 `8081`）。
2. 未登录会进入登录页；默认管理员账号：
   - 用户名：`admin`
   - 密码：`admin123`
   
   **生产环境部署前务必修改默认密码**！

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

### 5. 会话重置指令

- 在飞书里发送：`/clear`
- 作用：清空当前会话上下文（仅当前机器人 + 当前用户 + 当前 chat）
- 结果：机器人会回复确认文案，下一条消息从新会话开始

---

## 配置项说明（`application.yml`）

| 配置 | 说明 |
|------|------|
| `server.port` | HTTP 端口，默认 `8081` |
| `spring.datasource.url` | H2 文件库路径；**相对路径相对进程工作目录**，换目录启动会换库，生产建议改为**固定绝对路径** |
| `app.auth.default-admin-username` | 默认管理员用户名，默认 `admin` |
| `app.auth.default-admin-password` | 默认管理员密码，默认 `admin123`，**部署前请修改** |

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

**Q：为什么日志里会看到大量卡片更新性能日志？**  
这是新增的排查日志（`[MessageRelay][Perf]` / `[Dify][Perf]`），用于定位慢点。若生产环境不需要，可将日志级别调高或按需去掉。

---

## 工作流程（简图）

```
飞书消息 → SDK 长连接 → 解析消息/附件 → Dify 流式 API
         → 更新飞书卡片 ← 累积文本与附件展示
```

---

## License

MIT
