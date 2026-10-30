# Muse · 灵感制作所

一个独立运行的素材制作网站：发现模板 → 上传真实图片 → Java 受理任务 → 后台三阶段制作 → 查看个人历史与结果。默认匿名使用；也可以在账号设置注册邮箱账号，把当前匿名空间的作品归入账号。界面参考题目中的深色模板网格、左侧制作区和右侧历史区；不实现会员、支付、积分、真实 AI 或视频。

**结果是第一张上传图片的独立文件副本，不是 AI 特效或图片合成。** 两张素材的模板仍要求上传并验证两张图片，用于演示不同输入约束。

## 1. 技术与目录

| 部分 | 版本 / 方案 |
| --- | --- |
| 前端 | Next.js 16.4.3 App Router、React 19.3.1、TypeScript 5.9.x |
| 前端工程 | Node.js 24.x、npm lockfile、ESLint 9、Vitest 5.0.5、Testing Library |
| 后端 | Java 21、Spring Boot 3.5.6、MyBatis Starter 3.0.4、Spring Security Crypto（BCrypt）、Maven 3.9.9 |
| 数据库 | MySQL 8.4，UTC 时间，utf8mb4 |
| 异步执行 | Java 单工作线程 + 有界内存队列；每个阶段独立数据库事务 |
| 状态更新 | 每任务 1.5 秒一次轮询；终态停止；请求异常暂停该任务，手动恢复 |
| 身份 | 匿名优先 + 可选邮箱密码账号（BCrypt）+ HttpOnly Cookie + owner 隔离；注册认领匿名身份，登录合并匿名作品并轮换会话 |
| 文件 | Java 私有本地目录；Compose 命名卷持久化 |

具体 npm 依赖版本以 `frontend/package-lock.json` 为准；Java 传递依赖由 Spring Boot BOM 固定。

```text
frontend/
  src/app/                  App Router 首页、详情、历史、错误页面
  src/components/           页面、上传、结果预览、账号与匿名身份对话框
  src/hooks/use-tasks.ts    独立任务轮询与请求生命周期
  src/lib/                  API 客户端、类型及测试
  public/demo/              六张随源码交付的本地 JPEG 图片
  scripts/smoke.mjs         经 Next 代理访问 Java 的接口冒烟测试
  Dockerfile
backend/
  src/main/java/com/muse/api/
    ApiController.java      HTTP 边界
    SessionService.java     匿名/账号会话与 token 轮换
    AccountService.java     邮箱注册、登录合并、修改密码
    AssetService.java       上传与素材记录
    ImageStorage.java       实际文件校验与私有存储
    TaskService.java        校验、受理、历史、查询
    TaskScheduler.java      提交后调度、有界执行队列
    TaskRunner.java         三阶段执行
    TaskTransitions.java   独立事务与阶段事件持久化
    StoreMapper.java        MyBatis 参数化 SQL
  src/main/resources/      schema.sql、data.sql、application.properties
  src/test/                JUnit 集成测试；H2 仅 test scope
  Dockerfile
compose.yaml
.github/workflows/ci.yml
.env.example
```

## 2. 推荐启动方式：Docker Compose

需要 Docker Engine / Docker Desktop 和 Compose v2（支持 `--wait`）。在项目根目录执行：

```bash
docker compose up --build -d --wait --wait-timeout 240
```

浏览器打开 **http://localhost:3000**。首次构建会下载 Node、Java、MySQL 镜像和依赖，耗时取决于网络；慢速环境可增加等待时间。无需预先安装 Node、Java、Maven 或 MySQL，也无需外部 AI 凭据。

- 默认发布到宿主机 `0.0.0.0:${WEB_PORT:-3000}`，VPS 上仍需要放行防火墙/安全组；只允许回环访问时设置 `WEB_HOST=127.0.0.1`。
- 浏览器 `/api/*` → Next rewrites → Compose 内 `backend:8080` → `db:3306`。
- Next 不执行业务，也不直接连接数据库。
- `mysql-data` 保存 MySQL 数据；`material-data` 保存上传文件和成功结果。
- MySQL 健康后启动 Java；Java 健康后启动前端。
- 后端首次启动执行 `schema.sql`、`data.sql`；建表与种子导入可重复执行，不清空个人数据。

查看状态与日志：

```bash
docker compose ps
docker compose logs -f backend
docker compose logs -f frontend
```

停止服务但保留数据：

```bash
docker compose down
```

**不要在保留数据时加 `-v`。** 删除命名卷会清空数据库及素材，且数据库和文件必须成套备份、恢复。

### VPS 公网访问

默认绑定 `0.0.0.0`，VPS 可以从外部访问；如果 `.env` 里已经写了 `WEB_HOST=127.0.0.1`，需要改成 `0.0.0.0` 或删除该行。直接通过公网 IP + 端口演示时：

1. 在项目目录确认 `.env`（没有则从 `.env.example` 复制），并按需设置：
   ```dotenv
   WEB_HOST=0.0.0.0
   WEB_PORT=3000
   ```
2. 拉取最新代码后重建并启动：
   ```bash
   docker compose up --build -d --wait --wait-timeout 240
   docker compose port frontend 3000
   ```
   输出应为 `0.0.0.0:3000` 或 `:::3000`。
3. 在 VPS 防火墙和云厂商安全组放行 3000/TCP，例如 Ubuntu UFW：
   ```bash
   sudo ufw allow 3000/tcp
   ```
4. 在 VPS 上先验证：
   ```bash
   curl -I http://127.0.0.1:3000
   ```
   再从外部访问 `http://<VPS_IP>:3000`。

更推荐用 Nginx/Caddy 反向代理到 `127.0.0.1:3000`，只开放 80/443，并启用 HTTPS；此时设置 `WEB_HOST=127.0.0.1`，HTTPS 下设置 `COOKIE_SECURE=true`。当前演示没有登录限流，直接把 3000 长期暴露在公网有风险。

### 配置

默认值仅用于本地演示；`.env.example` 不包含真实凭据。需要覆盖时复制为 `.env` 并修改，不要提交实际凭据。

| 配置 | 默认 | 用途 |
| --- | --- | --- |
| `WEB_HOST` | `0.0.0.0` | Compose 宿主机绑定地址；只允许回环访问时设为 `127.0.0.1` |
| `WEB_PORT` | `3000` | Compose 宿主端口 |
| `MYSQL_DATABASE` | `muse` | 数据库 |
| `MYSQL_USER` | `muse` | 应用专用数据库用户 |
| `MYSQL_PASSWORD` | `demo-only-change-me` | 本地演示密码 |
| `MYSQL_ROOT_PASSWORD` | `demo-root-change-me` | 初始化管理员密码，应用不使用 root |
| `MYSQL_HOST` / `MYSQL_PORT` | `localhost` / `3306` | Java 直连；Compose 将 host 设置为 db |
| `MYSQL_SSL_MODE` | `DISABLED` | Compose 私有网络默认不启用 TLS；使用已有 MySQL 时建议配置 REQUIRED |
| `PORT` | `8080` | Java 端口 |
| `STORAGE_PATH` | `./storage` | Java 文件目录；Compose 为 `/app/storage` |
| `COOKIE_SECURE` | `false` | HTTPS 部署时在 backend environment 设置为 true |
| `SIMULATION_ENABLED` | `true` | 是否接受请求里的模拟配置 |
| `STEP_DELAY_MS` | `2500` | 请求不传 simulation 时的每阶段延迟，范围 0–30000 |
| `TASK_QUEUE_CAPACITY` | `128` | 等待队列容量，另有一个正在执行的任务 |
| `JAVA_API_URL` | `http://127.0.0.1:8080` | Next rewrite 的构建期后端地址；Compose build arg 为 `http://backend:8080` |

`COOKIE_SECURE`、`STEP_DELAY_MS` 等 Java 配置如需用于 Compose，请加入 `compose.yaml` 的 backend environment；仅写入 `.env` 不会自动注入未映射的变量。修改 `JAVA_API_URL` 后需要重建前端。

演示页面会显式传递所选耗时，优先于 `STEP_DELAY_MS`。关闭 `SIMULATION_ENABLED` 后，不应使用当前演示页面提交（页面固定发送模拟参数）；该开关用于限制 API 调用，非完整生产模式。

### 非容器开发命令

以下仅供开发者参考；评估建议使用上一节和下一节的容器命令，不在评估宿主机运行项目脚本。

准备 MySQL 8.4 数据库 `muse` 和相应用户，配置上述变量后：

```bash
# 终端一：Java 21 + Maven 3.9.9
mvn -f backend/pom.xml spring-boot:run

# 终端二：Node 24
npm --prefix frontend ci
npm --prefix frontend run dev
```

默认配置下 `schema.sql` 和 `data.sql` 会由 Java 自动执行，示例图片已随源码位于 `frontend/public/demo/`，不需要额外下载。

新增账号功能使用 `accounts` 表。已有本地数据库如果关闭了自动初始化（`local` profile），请补执行一次 `schema.sql`，其中的 `CREATE TABLE IF NOT EXISTS` 会新增表而不清空数据；再执行 `data.sql` 可补齐模板种子。

### 连接已有 MySQL（可选）

如果不想使用 Compose 的 MySQL，可让 Java 连接已有数据库：

- 复制 `.env.local.example` 为 `.env.local`，填写连接信息；该文件已被 Git 忽略，不要提交真实凭据。
- `local` Spring profile 会读取项目根目录的 `.env.local`，关闭启动时建表和种子导入；首次连接已有库需要执行一次 `schema.sql` 和 `data.sql`。
- 后端只监听 `127.0.0.1:8080`，前端仍通过同源 `/api` 代理访问。
- 图片本体保存在本地 `storage/`，数据库只保存素材元信息、任务、阶段和历史；数据库和素材目录需要成套备份。

在项目根目录运行：

```bash
# Java 21 + Maven Wrapper
./backend/mvnw -f backend/pom.xml verify
java -jar backend/target/muse-backend.jar --spring.profiles.active=local

# 另开终端
npm --prefix frontend run dev
```

如需在编辑器中调试，可使用 `.vscode/launch.json` 中的后端/前端配置；修改 Java 源码后重新构建并启动。

## 3. 验证与 CI

容器内检查前端 lint、类型和测试：

```bash
docker compose --profile test build frontend-checks
docker compose --profile test run --rm frontend-checks
```

Java 测试和构建（测试使用 test-scope H2 MySQL 模式，不替代真实 MySQL 冒烟）：

```bash
docker compose --profile test run --rm backend-checks
```

完整应用启动后，在前端容器运行 Node 冒烟脚本，实际经过 Next → Java → MySQL：

```bash
docker compose exec -T frontend node /app/scripts/smoke.mjs
```

该脚本创建独立匿名身份和少量测试任务，不清理已有用户数据。覆盖分类查询、非法图片、素材数量、重复素材、延迟校验、三个指定失败阶段、多任务受理、终态不回退、结果文件、个人记录隔离。

开发者检查命令：

```bash
npm --prefix frontend run lint
npm --prefix frontend run typecheck
npm --prefix frontend test
npm --prefix frontend run build
mvn -f backend/pom.xml clean verify
```

GitHub Actions 在 push / pull_request 时：

1. Node 容器中执行 `npm ci`、lint、TypeScript 检查、Vitest、Next 构建。
2. Maven / Java 容器中执行 `mvn verify`。
3. 前两项通过后构建并启动 Compose，在真实 MySQL 上执行同一冒烟脚本；失败保存服务日志，最后停止容器。
4. 完成后在 run 页面的 Artifacts 上传 `frontend-standalone`、`muse-backend-jar` 和 `backend-test-reports`；Docker 镜像不在 Artifacts 中，由评估者从源码构建。

前端关键测试验证多任务互不串扰、单任务请求不重叠、终态停止轮询、异常保留旧状态、手动恢复、刷新竞态、提交任务不被旧历史覆盖、身份切换 / 卸载取消旧请求、根级 StrictMode 清理，以及 SessionProvider 的注册、登录、退出、改密、失败不覆盖会话和过期初始化响应。后端验证事务提交后调度、回滚不调度、每阶段状态先持久化再等待、各阶段失败中断、结果文件、所有权、上传真实内容及尺寸、队列拒绝、启动中断处理，以及账号注册认领、登录合并匿名作品、会话轮换、跨账号拒绝和改密吊销会话。

## 4. 演示步骤

### 成功与图片操作

1. 首页切换“萌宠时刻 / 旅行灵感 / 人像写真”，数据来自 Java。
2. 进入“云朵小狗”，上传 `frontend/public/demo/pet.jpg` 或自己的 JPG / PNG（不超过 5 MB）。可以拖放、替换、移除；上传完成前提交按钮不可用。
3. 点击“开始制作”。受理后按钮恢复可用，右侧新增任务卡，而不是持续显示“提交中”。
4. 观察“素材检查 → 模拟生成 → 结果整理”，成功后点击“预览作品”。
5. “寄自海岸的明信片”与“把心动拍成电影”要求两张素材，数量未满足不能提交。

客户端校验 MIME、大小、数量；Java 再次检查实际图片签名、格式、可解码性、尺寸、文件存在性、所有者、重复素材和模板限制。素材最大 5 MiB；单边尺寸不超过 8192、总像素不超过 16777216。浏览器 object URL 仅用于即时预览，任务使用 Java 返回的素材 ID，不依赖临时地址。

### 指定阶段失败与耗时

展开制作页的“模拟调试”：

- 结果选择“素材检查失败 / 模拟生成失败 / 结果整理失败”。每次相同选择都能重复触发，不使用随机数。
- 每阶段耗时可选择 1、2.5、5、15 秒。默认成功总耗时约 7.5 秒，排队时间另计。
- 某步失败后不再执行后续步骤，卡片显示失败阶段及原因，不生成结果地址。

API 可传 `stepDelayMs: 0..30000`，`failStage: null | CHECK | GENERATE | FINALIZE`；不传 simulation 时用服务端默认值。

### 多任务、刷新与离开页面

1. 将每阶段耗时改为 5 秒，连续提交两个任务；素材已上传后可以复用提交。
2. 单工作线程下，一个处理中、另一个排队；状态按各自任务 ID 更新。
3. 刷新页面：从 Java 恢复历史并继续跟踪未完成任务。
4. 跳回首页或关闭页面：浏览器停止查询，但 Java 后台继续执行。再进入“我的作品”可查看结果。
5. 所有任务完成后不再自动查询。详情页仅展示当前模板记录；“我的作品”展示当前身份的全部记录。

### 查询异常不是任务失败

任务制作期间，用浏览器开发者工具的请求阻止功能阻止 `/api/tasks/*` 的状态查询。任务保留最后状态，出现“状态更新中断”，不会改为 FAILED；解除阻止并点击“恢复查询”或刷新记录按钮即可恢复。

这只模拟网络请求异常；不要靠停止 Java 演示该场景，因为停止 Java 会真正中断执行。首次历史请求失败也会显示重试入口，不伪装为空历史。

### 用户身份与个人记录

**匿名优先。** 第一次访问调用 `POST /api/session` 创建匿名 identity，服务器生成 32 字节安全随机 token，只在 Cookie 中下发；MySQL 只存 SHA-256 token hash。Cookie 为 `HttpOnly; SameSite=Lax; Path=/`，有效期 30 天。localhost HTTP 不设置 Secure；HTTPS 应开启 `COOKIE_SECURE`。不注册也可以完成上传、制作和查看历史。

**可选邮箱账号。** 右上角头像 → 账号设置：

- **注册**：邮箱 + 密码，密码用 BCrypt 存储。注册会把当前匿名 identity 直接升级为账号，任务和素材的 `owner_id` 不变，所以当前作品立即归入账号；旧匿名 token 被轮换。
- **登录**：邮箱 + 密码。如果当前仍是匿名空间，服务端在同一事务里把当前匿名空间的任务和素材迁移到账号，删除旧匿名会话和空 identity，再轮换到账号会话；因此登录也会把当前匿名作品带入账号。若浏览器已经登录另一个账号，需要先退出，不能直接切换账号。
- **退出登录**：删除当前会话并创建一个新的空匿名空间，账号作品保留，可再次登录查看。
- **修改密码**：登录后验证当前密码；修改成功后吊销该账号所有会话，当前浏览器换发新 token。没有“忘记密码 / 邮件重置”。
- 邮箱会去掉首尾空格并转为小写；密码至少 8 位，按 UTF-8 限制为 72 字节，使用 BCrypt 存储；重复邮箱返回 409。没有邮箱验证、账号注销、账单、订阅或第三方登录，也没有登录限流；这是本地笔试题的演示账号，不是完整生产身份系统。

所有任务、素材及结果访问均由服务器解析会话并按 `owner_id` 查询；客户端传递的任务 ID 不是授权凭据。不属于当前身份的记录返回 404；缺少有效会话返回 401；私有响应 `Cache-Control: private, no-store`。账号身份和匿名身份使用同一个 owner 隔离模型。

**演示方法：**

1. 匿名上传并提交一个任务，等它成功；确认右侧有记录。
2. 打开账号设置 → 注册；注册后头像旁显示邮箱，刷新后作品仍在。
3. 退出登录：当前空间变为新的匿名空间，历史为空；再用同一邮箱登录，原作品回来。
4. 新开隐身窗口，匿名登录同一账号，可见相同历史。
5. 在新的匿名空间上传并提交一个任务，然后登录已有账号；该任务会自动并入账号，旧匿名会话失效。
6. 注册两个账号，确认彼此看不到对方的任务、素材和结果。
7. 匿名状态下仍可创建新的匿名空间；这会删除当前会话并新建空匿名空间，旧匿名空间的 token 无法从本浏览器恢复。账号状态下请先退出登录，再创建新的匿名空间。

## 5. 接口摘要

所有写请求（包括创建匿名会话、账号请求和 multipart 上传）必须携带 `X-Muse-Request: 1`，前端与冒烟脚本已自动设置。该头配合禁用跨源 CORS，阻止外站 HTML 表单强制切换匿名身份或修改账号；不是认证凭据，实际授权仍使用 Cookie。

所有 JSON 错误统一为 `{ "message": "可理解的错误原因" }`。常见 HTTP 状态：400 参数错误，401 会话无效或邮箱密码错误，404 模板或个人记录不可访问，409 邮箱已注册 / 当前已登录其他账号，413 文件过大，503 服务暂不可用。

| 方法 / 路径 | 请求 | 返回 |
| --- | --- | --- |
| `GET /api/health` | 无 | `{status:"UP"}`，数据库不可用时非 200 |
| `POST /api/session` | 可选 `?fresh=true` | `{id, displayName, email}` 和会话 Cookie；`email` 匿名时为 `null` |
| `POST /api/account/register` | `{email, password}` + Cookie | Identity 和新会话 Cookie；认领当前匿名身份 |
| `POST /api/account/login` | `{email, password}` + Cookie | Identity 和新会话 Cookie；匿名作品会并入账号 |
| `POST /api/account/logout` | Cookie | 新的匿名 Identity 和会话 Cookie；账号数据保留 |
| `POST /api/account/password` | `{currentPassword, newPassword}` + Cookie | Identity 和新会话 Cookie；吊销该账号其他会话 |
| `GET /api/categories` | 无 | 分类字符串数组 |
| `GET /api/templates` | 可选 `?category=分类` | Template[] |
| `GET /api/templates/{id}` | 模板 ID | Template |
| `POST /api/assets` | multipart: `file`, `templateId` | Asset，200 |
| `GET /api/assets/{id}/content` | Cookie | 私有图片字节 |
| `POST /api/tasks` | CreateTask JSON + Cookie | Task，**202** |
| `GET /api/tasks` | Cookie | 当前身份全部 Task[]，创建时间倒序 |
| `GET /api/tasks/{id}` | Cookie | Task，包括阶段事件 |
| `GET /api/tasks/{id}/result` | Cookie | 成功结果图片字节 |

Identity：`id, displayName, email`。Template：`id, title, category, coverUrl, description, requiredImageCount, acceptedMimeTypes, maxFileSizeBytes, tag`。

Asset：`id, url, originalName, mimeType, sizeBytes`。

账号请求示例：

```json
{ "email": "demo@example.com", "password": "demo-password" }
```

修改密码请求示例：

```json
{ "currentPassword": "demo-password", "newPassword": "new-demo-password" }
```

CreateTask 示例：

```json
{
  "templateId": "cloud-pet",
  "assetIds": ["由上传接口返回的素材 ID"],
  "simulation": { "failStage": "GENERATE", "stepDelayMs": 2500 }
}
```

Task 示例结构（ID、时间仅为说明）：

```json
{
  "id": "任务 UUID",
  "templateId": "cloud-pet",
  "templateTitle": "云朵小狗",
  "templateCoverUrl": "/demo/pet.jpg",
  "status": "RUNNING",
  "currentStage": "GENERATE",
  "createdAt": "2026-10-28T10:00:00Z",
  "updatedAt": "2026-10-28T10:00:03Z",
  "resultUrl": null,
  "failureReason": null,
  "events": [
    { "stage": "CHECK", "status": "SUCCEEDED", "startedAt": "2026-10-28T10:00:00Z", "finishedAt": "2026-10-28T10:00:03Z" },
    { "stage": "GENERATE", "status": "RUNNING", "startedAt": "2026-10-28T10:00:03Z", "finishedAt": null }
  ]
}
```

状态转换：`QUEUED → RUNNING → SUCCEEDED / FAILED`；队列拒绝可直接 `QUEUED → FAILED`。阶段固定为 `CHECK → GENERATE → FINALIZE`。每阶段事件独立记录开始 / 结束时间与结果，不提供伪造百分比。

### 后台执行与一致性

- `TaskService.create` 在事务里完成参数验证、任务及素材关联插入，发布 TaskCreated 事件。
- AFTER_COMMIT 监听器把任务放入有界线程池，不在请求内等待模拟过程。回滚事务不调度；队列拒绝使用新事务记录可理解的失败。
- 每步开始先锁定任务行并提交 RUNNING 阶段事件，再在事务外执行模拟等待；结束用新事务写入结果。不会在延迟期间持有数据库事务。
- FINALIZE 成功时将第一张上传文件复制到独立私有结果文件，再原子提交成功状态与结果地址。数据库提交失败时清理结果副本。
- 查询只读取持久化状态，不驱动制作；数据库读事务避免单个响应的任务状态和阶段事件不一致。终态不被迟到操作改回处理中。
- 账号注册认领当前 identity；登录在事务中迁移匿名任务 / 素材的 `owner_id`，删除旧匿名会话和空 identity；注册、登录、改密、退出都会轮换会话。账号切换不改变任务执行状态，后台仍按 task id 推进。
- 浏览器只同步服务端记录。旧历史响应不能覆盖受理后新增任务或更晚的状态；每个任务仅有一个定时器 / 活跃查询，请求失败不等于执行失败。

## 6. 边界与取舍

- **仅支持一个后端实例。** 不使用 Redis、消息队列或分布式锁。
- **不支持重启自动续跑。** 执行队列在内存中；重启时会将遗留 QUEUED / RUNNING 任务和未完成阶段标记为 FAILED，并注明服务中断，避免永久卡住。已完成结果和历史保留。数据库暂时不可用导致失败写入失败时，需要数据库恢复后重启完成清理。
- 未做任务受理幂等。前端防连点不等于服务端幂等；提交响应丢失时应先刷新历史确认是否已受理，页面有提示。未提供自动重试、取消、断点续跑或失败重做。
- 图片生成仅为模拟：CHECK / GENERATE 有真实后台执行边界，但不运行 AI 或图像变换。
- 历史未分页；没有未引用上传清理、总存储配额、会话过期清理、登录限流及完整审计。适合本地笔试演示，不应直接开放到不可信公网。
- 账号是演示实现：邮箱 + BCrypt 密码、会话轮换、owner 隔离；**没有邮箱验证、忘记密码 / 邮件重置、账号注销、账单 / 订阅、第三方登录或登录限流**。修改密码会吊销该账号已有会话，但不会清理历史数据。生产使用应补邮件验证、限流、审计和账号删除策略。
- 写请求要求自定义请求头，并拒绝 cross-site / same-site 的 Fetch Metadata；未开放跨源 CORS，配合 SameSite Cookie 防止跨源表单修改身份或账号。部署时不能任意放开凭据 CORS。
- MySQL 数据卷与图片卷必须共同保留；仅恢复数据库而丢失图片卷会导致素材 / 结果不可访问。
- 前端使用原生 `<img>`，避免私有图片经过 Next 公共图片优化缓存；使用系统字体，无运行期字体 CDN。原生 dialog 支持焦点约束与 Esc，按钮及上传位有标签，小屏单列制作区可操作。
- 依赖镜像需要访问公共镜像与包仓库；应用启动后的模板浏览、图片上传和制作不需要访问外部素材服务。

## 7. 部署与回滚

本项目不自动发布。正式部署前应设置真正的密码、HTTPS / Secure Cookie、受信任反向代理，并补齐上面的访问控制和资源限额；不要将当前演示配置直接用于生产。

基本发布步骤：

1. 在独立测试环境运行全部检查和 Compose 冒烟测试。
2. 备份 MySQL 数据和 material-data 卷；保存上一版源码 / 镜像标识及配置。
3. 构建新镜像，执行 `docker compose up --build -d --wait`，检查健康状态及创建任务流程。
4. 数据库结构目前采用初始化 SQL，不是迁移系统。未来修改已有表必须先编写向后兼容迁移，不能期待 `CREATE TABLE IF NOT EXISTS` 自动升级表结构。

回滚：使用上一版源码和配置重新构建并启动服务；在 schema 向后兼容时复用原卷。若新 schema 不兼容，先停服务、恢复发布前成套备份，再启动旧版本。不要用删除卷作为回滚方式。重启期间未完成任务按中断失败处理，不会自动续跑。

## 8. 来源与验证

来源及复用范围：

- 从空目录实现，没有复制第三方应用源码或付费脚手架。
- Next.js、React、Spring Boot、MyBatis 及测试框架通过官方 npm / Maven 包引入，仅复用框架能力。
- 使用官方 Apache Maven Wrapper 3.3.4 脚本（Apache-2.0，保留原许可证说明），固定下载 Maven 3.9.9，不依赖临时 Maven 安装目录。
- UI 图标来自 Lucide React（ISC License），品牌小图标为本项目 SVG。
- 六张演示照片为项目自建素材，随源码提供 JPEG；不引用截图中的品牌或用户图片，不依赖网络图片链接。图像用于演示模板封面，不代表真实生成效果。
- 题目附件仅用作深色布局与信息层级参考，没有实现截图中的题外功能。

### 本次验证记录

验证日期：2026-10-31。以下检查在开发环境和测试配置中完成；容器与 GitHub Actions 以实际运行记录为准。

| 检查 | 实际结果 |
| --- | --- |
| 前端 ESLint | 通过，无错误 / 警告 |
| TypeScript `tsc --noEmit` | 通过 |
| Vitest | **28 项通过**：15 项轮询生命周期 + 4 项 API 边界 + 8 项会话 / 账号 + 1 项模板分类切换 |
| Next 生产构建 | 通过：首页、历史静态输出，详情动态路由；standalone 输出可用 |
| 干净源码包 | 解压后不带 `.next` / 构建缓存进行 TypeScript 检查，通过 |
| Java `mvn verify` | **46 项通过**：43 项集成测试 + 3 项调度器测试，零失败 / 跳过 |
| HTTP 冒烟 | 冒烟脚本覆盖账号注册、退出、登录、匿名作品合并和会话轮换；已通过 **Next 生产服务 → H2 测试后端 → smoke.mjs**，并在 GitHub Actions 的 Compose + MySQL 链路成功运行过一次，覆盖模板筛选、上传校验、三阶段边界、失败、身份隔离和账号链路 |
| 浏览器交互 | 已操作分类筛选、JPG 上传、非法格式拦截、双素材数量门槛与移除、提交、成功预览、指定生成阶段失败、刷新恢复、查询失败保留状态 / 手动恢复、身份确认切换；账号弹窗尚未在浏览器复验 |
| 布局 | 485px 小屏无横向溢出；桌面模板网格和左右制作布局正常 |
| 浏览器控制台 | 正常流程未发现 error / warn；查询异常已通过请求拦截验证 |
| Compose / CI | YAML 语法已检查；GitHub Actions 已成功运行过完整流程（含 Compose + MySQL 冒烟），当前提交状态以仓库 Actions 页面为准 |
| MySQL 8.4 / CI | 当前 schema 为 8 张表（含 `accounts`）；GitHub Actions 的 Compose + MySQL 冒烟已覆盖上传、制作、账号注册 / 合并和结果访问。本地账号测试同时使用 test-scope H2 |

已完成：前后端源码、模板 / 上传 / 三阶段任务 / 私有历史结果完整闭环、匿名身份隔离、邮箱账号注册 / 登录 / 退出 / 改密、匿名作品合并、会话轮换、响应式界面、核心测试、初始化 SQL、依赖锁文件、Dockerfile、Compose、CI 和本文要求的操作说明。

未实现：真实 AI、邮箱验证 / 忘记密码 / 账号注销、分页 / 清理 / 限流、重启续跑、受理幂等和自动重试；未做正式生产发布。Docker / GitHub Actions 的运行结果以仓库 Actions 和部署记录为准；浏览器账号弹窗仍建议手工验收。

设计与取舍：优先事务边界、异常隔离和可重复测试；使用单线程队列和轻量账号系统代替分布式设施、完整账号体系；结果处理保持为真实文件复制。详细边界见上一节。

交付形式：完整本地项目及 `muse-studio-source.zip`，压缩包不含依赖目录、构建缓存、测试数据或原始大图。解压进入 `muse-studio` 后执行本文 Compose 命令。仓库和压缩包可二选一提交；如提交仓库，验收 commit 以沟通渠道记录为准。
