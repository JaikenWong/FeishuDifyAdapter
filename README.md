# Feishu Robot Adapter for Dify

飞书机器人对接 Dify 的适配器，将飞书收到的消息转发给 Dify，并把 Dify 的流式回答实时更新到飞书卡片中。

## 功能特性

- ✅ 飞书长连接实时接收消息
- ✅ Dify 流式响应，飞书卡片动态增量更新
- ✅ 支持飞书事件订阅的 Verification Token / Encrypt Key 配置
- ✅ 友好的 Web 管理界面，可视化管理多机器人
- ✅ 自动保存对话记录，支持导出

## 技术栈

- Spring Boot 3 + Spring Data JPA
- H2 数据库（内嵌，无需额外安装）
- 飞书 OpenAPI SDK
- 流式响应使用 `BufferedReader.readLine()` 保证 SSE 按行正确解析

## 快速开始

### 1. 编译打包

```bash
mvn clean package
java -jar target/feishu-robot-adapter-*.jar
```

### 2. 配置飞书应用

1. 在 [飞书开放平台](https://open.feishu.cn/) 创建企业自建应用
2. 获取 `App ID` 和 `App Secret`
3. 开启 "事件订阅"，获取 `Verification Token`，如果开启加密还需要 `Encrypt Key`
4. 添加 "接收消息 v2" 事件权限
5. 在应用权限中添加 `im:message`、`im:message:send_as_bot` 等权限

### 3. 配置 Dify

1. 在 Dify 创建应用
2. 获取 Dify API Key
3. 填写 Dify Base URL（例如 `http://your-dify-instance` 或 `http://your-dify-instance/v1`）

### 4. 启动使用

1. 访问 `http://localhost:8080`
2. 默认登录：用户名 `admin`，密码 `admin123`（记得修改默认密码）
3. 添加机器人配置，填入上述参数
4. 开启长连接
5. 在飞书给机器人发消息，即可得到 Dify 的流式回答

## 工作流程

```
飞书消息 → 飞书长连接(websocket) → 接收事件 → 转发给 Dify API
Dify 流式响应 → 逐行解析 → 增量更新飞书交互式卡片 → 用户看到实时流式回答
```

## 界面预览

- 响应式卡片布局，支持移动端
- 模态框新增配置，表单分组清晰
- 敏感密钥默认隐藏，支持点击显示核对

## 修复说明

修复了常见问题：

1. **SSE 解析问题**：原 WebClient 按 buffer 分割可能切断行，改用 `BufferedReader.readLine()` 保证完整解析每一行
2. **事件验证问题**：原代码硬编码空 token，现在支持从配置读取 Verification Token 和 Encrypt Key
3. **异常处理问题**：原代码没有异常处理，出错后卡片一直显示思考中，现在错误会显示在卡片上

## License

MIT
