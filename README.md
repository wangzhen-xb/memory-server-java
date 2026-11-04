# memory-server（Java 版长期记忆 / 登录 / 会话同步服务）

原 `prisma-ai` 项目（Prisma 8 + SQLite + Node 内置 http）的等价后端重写。
技术栈：**Spring Boot 3.2.3 + MyBatis-Plus 3.5.7 + PostgreSQL + Redis + Nacos（配置中心）+ Sentinel 1.8.6（限流）**。

业务语义与原 `server.ts` 逐条对齐：注册 / 登录 / me / 登出、长期记忆全量替换、会话同步（删除 + 逐条"较新者胜"）。
**存量密码无需重置**：Java 侧用 BouncyCastle 复现 Node `crypto.scryptSync`，已用跨语言向量测试证明逐字节一致（见 `PasswordEncoderTest`）。

---

## 一、环境依赖

| 组件 | 版本 | 说明 |
|------|------|------|
| JDK | 17 | 必须，Boot 3.x 不支持 JDK 8/11 |
| Maven | 3.8+ | 本机 3.9.16 已验证 |
| PostgreSQL | 15+ | 主存储 |
| Redis | 7+ | 会话主存储 + 登录失败计数 |
| Nacos | 2.3.2 | **仅 cloud 档需要**，local 档不依赖 |
| Sentinel Dashboard | 1.8.6 | **仅 cloud 档需要**，可选 |

> **local 档只需 PG + Redis**，无 Nacos / 无 Docker 也能编译与启动，适合本机联调。

---

## 二、启动步骤（复制即可跑）

### 方式 A：local 档（推荐本机先用，只需 PG + Redis）

```bash
# 1) 起中间件（有 Docker 时）；无 Docker 则自行准备 PG/Redis 并用环境变量覆盖连接串
cd memory-server-java
docker compose up -d postgres redis

# 2) 建表（Docker 首次初始化会自动执行 01-schema.sql；手动装 PG 则自己跑一次）
#    psql -h 127.0.0.1 -U memory -d memory_db -f sql/01-schema.sql

# 3) 迁移存量数据（可选，见第三节）

# 4) 编译 + 测试 + 打包
mvn clean package

# 5) 以 local profile 启动（默认就是 local，可不写 -Dspring.profiles.active）
java -jar target/memory-server.jar
# 或开发热启动：
#   mvn spring-boot:run -Dspring-boot.run.profiles=local
```

连接串默认指向 `127.0.0.1:5432 / memory_db(memory/memory)` 与 `127.0.0.1:6379`，可用环境变量覆盖：
`PG_HOST / PG_PORT / PG_DB / PG_USER / PG_PASSWORD / REDIS_HOST / REDIS_PORT / REDIS_DB / PORT`。

### 方式 B：cloud 档（Nacos 配置中心 + Sentinel 规则托管）

```bash
# 1) 起全部中间件（Nacos 鉴权密钥需先在 .env 或环境变量注入，禁止写死）
docker compose up -d

# 2) 导入 Nacos 种子配置（见 nacos/ 目录，表格里对应关系）

# 3) 指定 Nacos 地址并以 cloud 档启动
java -jar target/memory-server.jar \
  --spring.profiles.active=cloud \
  --NACOS_ADDR=127.0.0.1:8848
```

cloud 档差异：
- `bootstrap.yml` 开启 Nacos config，拉取 `memory-server.yaml` 覆盖业务参数；
- `application.yml` cloud 段配置 Sentinel datasource，限流规则由 Nacos 托管（`rule-source: nacos`）；
- local 档则读 classpath 内置 JSON（`rule-source: local`），无需 Nacos。

---

## 三、存量数据迁移（SQLite → PostgreSQL）

在原 Node 项目根目录执行，读出 `app.db` 全量数据，生成 PG 可执行 SQL：

```bash
# 在项目根（prisma-ai/），不是 memory-server-java/
npx tsx scripts/export-to-postgres.ts
# 产物：memory-server-java/sql/02-migrate-data.sql

# 导入 PG（务必先有 01-schema.sql 建好的表）
psql -h 127.0.0.1 -U memory -d memory_db -f memory-server-java/sql/02-migrate-data.sql
```

迁移要点：
1. 密码原样搬运（`saltHex:hashHex` legacy 格式），新服务可直接校验；
2. `t_user/t_memory` 为 `GENERATED ALWAYS AS IDENTITY`，脚本用 `OVERRIDING SYSTEM VALUE` 保序并 `setval` 重置序列；
3. **登录会话(session)属临时态，不迁移**，用户在新服务重新登录即可；
4. 时钟以客户端 epoch 毫秒为准，跨端"较新者胜"直接比较。

---

## 四、Nacos 种子配置导入对照（cloud 档）

| 文件 | dataId | group | 格式 | 用途 |
|------|--------|-------|------|------|
| `nacos/memory-server.yaml` | `memory-server.yaml` | `DEFAULT_GROUP` | YAML | 业务参数（长度阈值/TTL/scrypt/规则来源） |
| `nacos/memory-server-flow-rules.json` | `memory-server-flow-rules` | `SENTINEL_GROUP` | JSON | Sentinel 流控规则（按资源 QPS） |
| `nacos/memory-server-param-rules.json` | `memory-server-param-rules` | `SENTINEL_GROUP` | JSON | Sentinel 热点参数规则（按 userId/IP） |

> dataId / group 必须与 `application.yml` cloud 段 `spring.cloud.sentinel.datasource.*.nacos` 完全一致，否则规则不生效。

---

## 五、接口清单

统一响应体 `{ code, msg, data }`，`code=0` 为成功；错误码语义段对齐 HTTP（400/401/409/413/423/429/500）。
需登录的接口携带请求头：`Authorization: Bearer <token>`（token 由注册/登录返回）。

| 方法 | 路径 | 登录 | 说明 |
|------|------|------|------|
| POST | `/auth/register` | 否 | 注册，直接返回 token + user |
| POST | `/auth/login` | 否 | 登录；连续失败 5 次锁定 15 分钟（423） |
| GET  | `/me` | 是 | 当前登录用户 |
| POST | `/auth/logout` | 是 | 销毁会话 |
| GET  | `/memory` | 是 | 记忆列表（返回 `{items:[...]}`） |
| PUT  | `/memory` | 是 | **全量替换**（入参是完整列表，空数组=清空，上限 30 条/单条 200 字） |
| DELETE | `/memory` | 是 | 清空全部记忆 |
| GET  | `/conversations` | 是 | 会话列表 |
| PUT  | `/conversations` | 是 | **增量同步**：`remove` 先删 + `upsert` 逐条较新者胜 |

### 请求/响应示例

**注册**
```bash
curl -X POST http://127.0.0.1:8787/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"secret123","nickName":"爱丽丝"}'
```
```json
{ "code": 0, "msg": "success",
  "data": { "token": "<64位hex>", "user": { "id": 1, "username": "alice", "nickName": "爱丽丝" } } }
```

**登录**
```bash
curl -X POST http://127.0.0.1:8787/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"secret123"}'
```

**记忆全量替换**
```bash
curl -X PUT http://127.0.0.1:8787/memory \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"items":["喜欢喝咖啡","住在杭州"]}'
```

**会话同步**（remove 先删，upsert 逐条按 updatedAt 较新者胜；payload 为整条会话 JSON 字符串）
```bash
curl -X PUT http://127.0.0.1:8787/conversations \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"remove":["old-id"],"upsert":[{"id":"c1","title":"新对话","payload":"{\"messages\":[]}","updatedAt":1730000000000}]}'
```

**me / 登出**
```bash
curl http://127.0.0.1:8787/me -H "Authorization: Bearer <token>"
curl -X POST http://127.0.0.1:8787/auth/logout -H "Authorization: Bearer <token>"
```

---

## 六、关键设计说明

- **密码兼容**：`PasswordEncoder` 用 BouncyCastle `SCrypt.generate`（N=16384,r=8,p=1,dkLen=64,salt=16B）复现 Node；存量 `salt:hash`，新增/登录成功后升级为 `{scrypt}salt:hash`。参数改动会导致存量密码无法校验。
- **会话存储**：Redis 为主存（TTL 自动回收），`t_session` 表留痕并做 Redis 丢失回落；LoginFail 计数用 Lua 脚本保证 `INCR+EXPIRE` 原子，独立 `mem:lock:` 键防永久锁定。
- **越权防护**：`userId` 一律由 `AuthInterceptor` 从 token 解析写入 `UserContext`（ThreadLocal，`afterCompletion` 清理），**绝不接受客户端入参**。
- **"较新者胜"**：`ConversationMapper.upsertIfNewer` 单条 SQL `ON CONFLICT (id) DO UPDATE ... WHERE EXCLUDED.updated_at > t_conversation.updated_at`，避免读改写并发覆盖。
- **限流**：`SentinelGuard` 对 `userId`/`ip` 做热点参数埋点，资源阈值见 `sentinel/*.json`；`durationInSec`（非 `durationInWindow`）。
- **请求体分级**：默认 64KB，会话同步 64MB（含图片 base64），`BodySizeInterceptor` 拦截。
- **数据源分层**：Controller → Service → Mapper 严格分层，统一 `Result` 封装 + `@RestControllerAdvice` 全局异常；`@Valid` 前置校验，判空完善。

---

## 七、验证状态

- ✅ `mvn clean package` BUILD SUCCESS，`Tests run: 3, Failures: 0, Errors: 0`。
- ✅ 跨语言 scrypt 向量测试通过（证明存量密码可被新服务校验）。
- ✅ 迁移 SQL 已生成（`sql/02-migrate-data.sql`，含 1 用户 + 记忆 + 会话）。
- ⏳ **端到端运行时联调待做**：本机无 PG/Redis/Nacos/Docker，仅完成编译 + 单元测试。请在具备中间件的环境按第二、三节启动后回归接口。
