# 优化路线图

> 范围：springboot-learning-mall 全项目。本轮先推**安全架构**，其余方向排期在后。
> 每一条都给出「现状 → 问题 → 改法 → 验收」，改完一条打勾一条。

---

## A. 安全架构（本轮优先）

### A1. 商家归属校验抽成可复用组件 ✅

| 项 | 内容 |
|----|------|
| 现状 | 「这家店是不是你的」在 4 处手写：`MerchantInventoryController`、`FulfillmentService.ship`、`RefundService.approve/reject`、`RefundService.pendingQueue`。每处都是 `merchants.ofOwner(username)` + 比对 `merchantId`。 |
| 问题 | 新增一个商家接口就复制一遍，漏一条就是越权。逻辑散在 Controller 和 Service，没有统一的「商家上下文」。 |
| 改法 | 新增 `@MerchantScoped` 注解 + AOP 切面。切面在方法执行前解析当前登录账号的 `merchantId`，存入 `MerchantContext`（ThreadLocal），方法参数里声明 `long merchantId` 的自动注入。Service 层不再自己调 `ofOwner`。 |
| 验收 | 4 处手写校验全部替换为注解；商家 A 调用商家 B 的子订单仍被拒；现有 `MerchantOwnershipTest`、`MerchantInventoryTest`、`MerchantRefundHttpTest` 全绿。 |

### A2. CSRF 按路径区分 ✅

| 项 | 内容 |
|----|------|
| 现状 | 全局开 CSRF，`/api/**` 的 JSON 接口也要带 token。 |
| 问题 | 前后端分离趋势下，纯 API 带 CSRF token 增加前端复杂度；但 Thymeleaf 表单（登录、退出）又确实需要。 |
| 改法 | `SecurityConfig` 里 `csrf(c -> c.ignoringRequestMatchers("/api/**"))` 关掉 API 的 CSRF，同时给会话 Cookie 设 `SameSite=Strict`。表单路径保留 CSRF。 |
| 验收 | 表单 POST 仍需 CSRF；`/api/**` 的 POST 无需 CSRF 即可调用（带登录 Cookie）；现有登录、下单、改价测试全绿。 |

### A3. 用户管理从内存迁到数据库 ✅

| 项 | 内容 |
|----|------|
| 现状 | `InMemoryUserDetailsManager`，203 个账号全在内存，密码来自配置。 |
| 问题 | 改密码要重启、不能热增删账号、多节点用户不一致。 |
| 改法 | 新建 `users`/`authorities` 表（Flyway V26），`UserDetailsService` 改用 `JdbcUserDetailsManager`。初始化时把 `merchant.owner_username` 同步进 `users` 表（密码用配置值首次编码入库）。 |
| 验收 | 新增一个商家账号不重启即可登录；改密码后旧密码失效；全量测试通过。 |

### A4. 密码管理 ✅

| 项 | 内容 |
|----|------|
| 现状 | 所有商家共用 `mall.merchant-password` 一个密码。 |
| 问题 | 一个商家泄露 = 全部商家沦陷。没有改密入口。 |
| 改法 | 商家中心加「修改密码」接口，校验旧密码、新密码强度（长度 ≥ 8、含字母数字）、新旧不同。密码存 `users.password`。 |
| 验收 | 改密后旧密码登录失败；弱密码被拒；改密成功返回 200。 |

### A5. 会话超时与并发登录 ✅

| 项 | 内容 |
|----|------|
| 现状 | 会话超时用 Spring Boot 默认（30 分钟），无并发控制。 |
| 问题 | 商家账号被多人共用时无法发现；长期不操作也不超时。 |
| 改法 | `application.yml` 设 `server.servlet.session.timeout=15m`；`SecurityConfig` 配 `sessionManagement(s -> s.maximumSessions(1))` 限制单账号单设备。 |
| 验收 | 15 分钟无操作后需重新登录；同账号第二个登录顶掉第一个。 |

---

## B. 性能与数据（本轮）

> 先测基线再动手。以下数字是 `localhost` 单机 10~15 次请求的 p50（`Accept-Encoding: identity`）：
>
> | 接口 | p50 | 备注 |
> |------|-----|------|
> | `GET /api/orders`（空账号） | 6.4ms | 1 条 SQL |
> | `GET /api/orders`（21 单 / 每单 2 子单） | **50.4ms** | 约 64 条 SQL，随订单数线性涨 |
> | `GET /api/products?size=24` | 8.9ms | COUNT + 分页共 2 条 |
> | `GET /api/products?keyword=java` | 17ms | `LIKE '%..%'` 全表扫 |
> | `GET /api/cart`（6 行） | 3.7ms | 6 条按主键查，量级小 |

### B1. 订单列表视图批量装配（消除 N+1）✅

| 项 | 内容 |
|----|------|
| 现状 | `OrderRepository.view(row)` 每次都现查三样：`items(orderId)`、`subs.ofOrder(orderId)`、每个子单再 `subs.itemsOf(...)`。而 `OrderTransactions.list()` 和 `OrderInsightsService.ordersOf()` 都是 `rows.stream().map(orders::view)` —— 外层 1 条 SQL，内层每单 3 条。 |
| 问题 | 21 单实测 50.4ms（≈64 条 SQL），50 单就是约 150 条。这个放大倍数会同时打到 4 个入口：`GET /api/orders`、助手工具 `CustomerTools`/`StaffTools`、`PendingActionService` 的三种确认动作、以及推荐服务。慢的不只是页面，还有助手每轮对话。 |
| 改法 | 给 `OrderRepository` 加 `views(List<OrderRow>)`：先用 `order_id IN (:ids)` 一次性把商品行和子订单行都取回来，在内存里 `groupingBy` 分桶，再逐单装配。SQL 条数从 `1+3N` 固定为 **4 条**。`OrderTransactions.list()` 与 `ordersOf()` 改为调用它。 |
| 验收 | SQL 条数与订单数无关（`OrderListSqlCountTest` 断言 1 单与 50 单条数相等，且刻意拉到 50 单——原实现下该差距是 4 条对 151 条，任何「顺手在循环里查一下」都会被拦下）；全量测试全绿。 |
| 实测 | 服务端耗时（`HttpClient`，排除 PowerShell 客户端解析开销）：21 单 **≈16ms**（原 ≈43ms）；50 单 44ms / 47KB 响应体。**50 单仍比 21 单慢，但慢的不是 SQL**——条数已被测试证明恒定，剩余耗时随响应体线性增长（每单约 15 个嵌套记录，约 0.05ms/记录；对照 100 件商品 23KB 只花 2ms）。这是「把 50 单完整数据序列化出去」的固有成本，不是放大倍数。 |

### B2. 推荐服务消除 N+1 + 下推 LIMIT ✅

| 项 | 内容 |
|----|------|
| 现状 | `RelatedRecommendationService.addCoPurchase` 先跑一条**没有 LIMIT** 的共购聚合，再对返回的每一行调用 `productOrNull(pid)` 单独查商品。`addSameCategory` 里 `seeds.stream().map(this::productOrNull)` 同样是「每个种子一次查询」。 |
| 问题 | 两处放大：① 共购查询不带 LIMIT，命中多时会把全部候选商品读出来，最后只用前 5 件；② 每行一次按主键查商品，候选多则 SQL 多。推荐是助手每轮对话都会跑的路径，成本和对话轮数相乘。 |
| 改法 | 共购查询改成子查询 + `JOIN product` 一次取全字段，过滤 `stock > 0`、排除种子一并下推到 SQL，外层加 `LIMIT`；`addSameCategory` 的品类推导改用新增的 `ProductCatalog.findByIds(...)` 批量取。 |
| 验收 | 推荐一轮的 SQL 条数固定（不随种子数量涨）；推荐结果集不变（现有推荐测试全绿）。 |

### B3. 分页排序补 `id` 次排序键 ✅

| 项 | 内容 |
|----|------|
| 现状 | 逐条审了全部 `ORDER BY`。绝大多数已经是 `主键 + 次键` 的形式（订单 `created_at, id`、退款 `created_at DESC, id`、购物袋 `updated_at DESC, product_id`）。有 4 处缺次排序键：`OrderInsightsService` 热销 Top3（`quantity DESC`）、`ProactiveRepository` 一批 `created_at DESC`、`RelatedRecommendationService.addSameCategory`（`ORDER BY price`）、`MemoryStore`（`updated_at DESC`）。 |
| 问题 | 排序键有并列值时，MySQL 不保证两次查询的返回顺序一致。表现为：翻页时同一条记录出现两遍、或某条永远看不到。这种 bug 在数据量小时复现不了，上线后才零星报上来。 |
| 改法 | 4 处各补一个唯一列做次排序键（`id` / `product_id`）。 |
| 验收 | 4 处补齐；反复请求同一页结果顺序恒定。 |

### B4. 商品目录缓存 —— 评估后**不做**（有测量依据）

| 项 | 内容 |
|----|------|
| 现状 | 原计划给商品目录加 Caffeine。实测 `GET /api/products?size=24` p50 **8.9ms**，其中两条 SQL 都是走索引的主键/条件查询。 |
| 不做的原因 | ① **收益太小**：8.9ms 里缓存能省的部分约 3~5ms，而缓存引入的一致性问题远大于这个收益。② **会破坏一条刻意的设计**：`ProductService` 注释明确写着「价格与库存当场查商品表——商品改价后，袋子里显示旧价是最难解释的一类 bug」。给商品行加缓存，等于把这条设计反过来做。③ 库存是高频写字段（下单预占、取消归还、退款回补，每次下单至少一次 `UPDATE`），失效逻辑做细了等于没有缓存，做粗了就会卖超。 |
| 结论 | 缓存只对「读多写少 + 允许旧值」的数据成立。本项目的商品行两个条件都不满足，所以**不加**。真正值得缓存的是分类列表这类几乎不变的字典，但它本身已经由前端静态化，不需要后端缓存。 |

### B5. `LIKE` 升级 `FULLTEXT` —— 暂缓（有明确阻塞）

| 项 | 内容 |
|----|------|
| 现状 | 关键词搜索是 `WHERE name LIKE '%kw%'`，实测 5000 行下 17ms。 |
| 为什么暂缓 | ① 测试库是 **H2**（MySQL 兼容模式），H2 不支持 MySQL 的 `FULLTEXT ... WITH PARSER ngram`。改成 FULLTEXT 就得在查询层加「生产走全文索引、测试走 LIKE」的双路径，查询面翻倍，回归成本大于收益。② 中文分词要 `ngram`，最小词长、停用词都要调，不是换个索引就完事。③ 当前数据量 5000 行、17ms，还没到痛点。 |
| 触发条件 | 目录过 5 万行、或搜索 p95 超过 100ms 时再做。届时配套：`ngram` 全文索引 + 双路径查询 + 独立的搜索集成测试。 |

---

## C. 可维护性（本轮）

### C1. Flyway 迁移与应用启动解耦 ❌ 不做（仅记录依据）

| 项 | 内容 |
|----|------|
| 原计划 | 「部署时单独跑迁移」是 Web 应用大团队的标准做法，防滚动升级时多实例同时迁移。 |
| 为什么不适用 | 本项目所有迁移都是 `CREATE TABLE` / `ADD COLUMN` / `CREATE INDEX` 这类「低锁增量 DDL」，没有 `DROP`、没有重建、没有在线表结构变更。Flyway 启动时执行已经是合适选择——单实例教学项目没有这个风险面。 |
| 触发条件 | 首次出现下列任一情况时再做：多实例部署、首条高风险迁移（如 `RENAME COLUMN` / 在线重建大表）、或部署流水线要求「迁移与应用升级分阶段」。 |

### C2. 种子数据按环境分离 ✅

| 项 | 内容 |
|----|------|
| 现状 | `V2__seed_products.sql`、`V20__catalog_scale.sql`、`V23__catalog_scale_5k.sql`（5000 件商品）与 `V24__merchant_foundation.sql`（200 商家）都混在 `db/migration/` 下。 |
| 问题 | 任何环境（包括将来真正想部署的生产）启动都会塞进 5000 件演示商品。生产库里出现演示数据，等于把「这是开发环境」写进了数据库。 |
| 改法 | ① 新建 `db/dev-seed/` 目录，把 **V2/V20/V23 原样搬入**（`git mv`，文件名、版本号、内容、checksum 一字未改）。Flyway 历史表不记录文件来自哪个 location，所以已建好的库**无需重置**，新库重放顺序也与搬家前完全一致。② `application.yml` 加 `spring.flyway.locations: classpath:db/migration,classpath:db/dev-seed`；测试与 5 个 `tools/*Check` 的 `Flyway.configure()` 同步补上第二个 location（它们断言种子数据契约）。③ 生产部署时用 `SPRING_FLYWAY_LOCATIONS=classpath:db/migration` 覆盖，得到干净的空商品目录。 |
| 为什么不用 R__ 可重复迁移 | 试过，否决。R 开头的脚本在 checksum 变化时**整段重跑**：纯 INSERT 重跑就是主键冲突，混在里面的 DDL（V24 的 `ALTER TABLE`）重跑就是「列已存在」。把非幂等脚本改名 R 是把地雷埋进未来。搬动 V 文件则零风险——内容不变，已应用的库甚至感知不到。 |
| 为什么 V24 留在 migration | 它不是演示数据，是**归属维度**：`product.merchant_id` / `order_item.merchant_id` 是 NOT NULL 外键，建外键那一刻就要校验存量行——商家行必须先于外键存在，顺序由版本号保证。且代码侧 `UserAccountProvisioner` 本就内建 200 个商家账号与之对应。商品目录是「演示数据」，商家维度是「结构的一部分」。 |
| 验收 | 默认启动仍有 5000 件商品、200 家店（现有 dev 库直接通过校验，零迁移）；`SPRING_FLYWAY_LOCATIONS=classpath:db/migration` 启动时商品目录为空、结构完整（200 商家仍在，属预期）；500 条测试全绿。 |

### C3. 分页参数走配置 + `/api/config` 下发 ❌ 不做（仅记录依据）

| 项 | 内容 |
|----|------|
| 原计划 | 把 `defaultValue = "24"` 改为配置项 + `/api/config` 下发。 |
| 为什么不适用 | 商品列表的 24 不是「会调整的运营参数」，是「UI 网格布局常量」——4 列 × 6 行刚好铺满一屏。改它意味着改前端网格，而不是改一个数字。前后端各自持有这个数字是合理的：前端持有「布局」，后端持有「防御性上限」（`MAX_SIZE`）。`/api/config` 只会让「布局」这件事多一条不读的接口。 |
| 触发条件 | 真要调时，先动 UI 网格，再看后端是否值得参数化。 |

### C4. 接口加 `/api/v1/` 前缀 ❌ 不做（仅记录依据）

| 项 | 内容 |
|----|------|
| 原计划 | 给所有接口加 `/api/v1/` 前缀，作为对外契约的稳定承诺。 |
| 为什么不适用 | ① 当前没有「必须保留旧行为」的外部调用方——12 个 `@RequestMapping("/api/...")` 全部是内部前端/助手工具，改 URL 只需同步改前端 fetch 路径。② 版本化的成本是持续支付（每个破坏性变更要维护双份实现），而本项目还没有「必须保持兼容」的用户。③ 真正需要版本化时，第一个破坏性变更出现再说，届时一次升级到位比现在「防御性加前缀」便宜得多。 |

---


## D. 可观测性（本轮）

> 先盘点再动手。盘点结论：助手侧已有 4 个 Micrometer 指标（`mall.assistant.stop` / `tool.calls` / `first.token.latency` / `turn.duration`）、`TimingAspect` 操作耗时、`OrderExpiryService` 到期单 Gauge、`AssistantRateLimiter` 滑窗限流。**真正空白的只有两件：请求级日志串联、商城业务面指标。**

### D1. 请求级 traceId 贯通日志 ✅

| 项 | 内容 |
|----|------|
| 现状 | 日志格式是默认的「时间 + 线程 + 类 + 消息」。多用户并发时，一次请求的日志行与另一次请求的日志行交错在一起，没有任何字段能把同一请求的行挑出来。 |
| 问题 | 商家中心这类多表写路径出问题时，排障只能靠「时间相近 + 肉眼猜」。助手一轮对话横跨 Web 线程与助手线程池，日志断成两截。 |
| 改法 | ① `common/TraceIdFilter`（`OncePerRequestFilter`，最高优先级，跑在安全过滤器链之前）：读 `X-Request-Id`，没有就生成 UUID 前 8 位，放进 MDC（key=`traceId`）并回写响应头；`finally` 里 `MDC.remove`（线程池化复用，残留的 traceId 比没有更糟）。入参只接受 `[A-Za-z0-9._-]{1,64}`——这个值会原样进响应头与日志，带 CR/LF 会让 Tomcat 在 `setHeader` 时抛异常，带换行还能伪造日志行。② `application.yml` 用 `logging.pattern.level` 加 `%X{traceId}`（不引新依赖、不做 JSON 结构化日志——本项目日志是人看控制台，MDC 字段已够）。③ 助手线程池包 `common/MdcTaskDecorator`，把 MDC 从 Web 线程拷到异步线程（拷进时先存原值、出来精确还原，防止串到下一个任务）。刻意不处理 ASYNC 二次派发：那一遍请求头还是客户端原值，没传号时会生成另一个 UUID，同一次请求反而出现两个 traceId。messaging 线程池不装饰——它只由定时轮询线程投喂，没有「某次请求」可归属。 |
| 验收 | `TraceIdFilterTest` 6 条：外部传入沿用、缺省生成、非法值替换、未登录 401 响应也带头（证明过滤器排在安全链之前）、MDC 请求内可见且事后清空、助手线程池 Callable 携带 MDC 且不泄漏。实测：合法头 `selfcheck-ok` 原样回写、非法头 `bad id/path` 被换成 8 位短号、缺省自动生成；带 `X-Request-Id: selfcheck-order` 下单，日志行 `INFO [selfcheck-order] ... operation=create-order-transaction` 与响应头同值。 |
| 明确不做 | 不引 logstash-logback-encoder 做 JSON 结构化日志——本项目日志的消费方式是「人看控制台/文件」，MDC 字段已够；JSON 化的收益在 ELK 场景，届时再加依赖不迟。 |

### D2. 商城业务面指标补全 ✅

| 项 | 内容 |
|----|------|
| 现状 | 指标全在助手侧（4 个 `mall.assistant.*`），商城主链路（下单、支付、退款）一个指标都没有。`/actuator/metrics` 里看不到「今天成了几单」。 |
| 问题 | B 段优化时我们靠手工 HTTP 计时拿性能数字；同样地，「系统此刻在发生什么」也只有翻数据库才能知道。指标是唯一能低成本持续观测的手段。 |
| 改法 | 补主链路五个指标，落点在 Service 层而非 Controller——助手工具下单也走 Service，指标才不会漏：`OrderTransactions` 加 `mall.order.placed` / `mall.order.paid`（计数点在状态迁移确认之后，重复支付走幂等分支不会重复计）；`RefundService` 加 `mall.refund.requested`（标签 route=AUTO/MERCHANT_APPROVAL）与 `mall.refund.resolved`（标签 action=APPROVE/REJECT，AUTO 当场放款也算一次 APPROVE）。`requested` 的计数收在 `apply` 末尾而不是建单后：AUTO 分支的放款（含真实转账）在同一事务里，转账失败会连退款单一起回滚，计早了就会留下「库中没有、指标里有」的申请；售罄 Gauge `mall.catalog.sold_out` 注册在 `ProductService` 构造器。 |
| 售罄 Gauge 的一处偏离 | 计划写的是「注册查一次 + 商品变更事件刷新」，实现时改为**读数时实时查库**（`COUNT(*) WHERE stock=0`）。原因：下单预占 / 取消归还 / 退款回补同样改 `stock`，但它们（刻意）不发 `ProductChangedEvent`——靠事件刷新会在每一次成交后静默漂移。一条 COUNT 在当前数据量下是亚毫秒级，用极小的读开销换掉「指标与真相不一致」这一整类事故。 |
| 验收 | `BusinessMetricsTest` 5 条：下单/支付增量计数、重复支付不重复计、端点登录后可查、AUTO 与驳回分桶正确、售罄 Gauge 对「买空」这类无事件变更也能实时反映。实测（重启后全新进程）：下 1 单付 1 单退 1 单，`mall.order.placed=1`、`mall.order.paid=1`、`mall.refund.requested{route=AUTO}=1`、`mall.refund.resolved{action=APPROVE}=1`、`mall.catalog.sold_out=45`。全量 511 测试全绿。 |
| 明确不做 | 不接 Prometheus 端点、不做 Grafana 面板——单实例教学项目，`/actuator/metrics` 已够看；暴露面多一寸，安全配置就要多守一寸。 |

### D3. `/api/assistant/**` 加 Bucket4j 限流 ❌ 不做（目标已实现）

| 项 | 内容 |
|----|------|
| 原计划 | 给助手接口加限流。 |
| 为什么不做 | 该能力**已存在**：`AssistantRateLimiter` 是每用户滑动窗口（默认 20 条/分钟，`mall.assistant.security.rate-limit-per-minute` 可调），超限返回 429 + 建议等待秒数。引 Bucket4j 是「换一个库做同一件事」，零收益。且除限流外，助手还有两道独立的闸：全局在途上限（16）与单用户在途上限（3），见 `AssistantConcurrencyGate`。 |
| 触发条件 | 多实例部署时，把内存滑窗换成 Redis 计数器（`AssistantRateLimiter` 的类注释已写明这条路）。 |

---

## E. 前端（下一轮）

### E1. 原生 JS 评估引入 Alpine.js / HTMX ❌ 不做（仅记录依据）

| 项 | 内容 |
|----|------|
| 现状 | 5 个纯静态 JS 共约 97KB：`assistant.js` 39KB、`app.js` 30KB、`voice.js` 22KB、`merchant.js` 4KB、`product.js` 2KB。无构建链、无包管理。 |
| 为什么不引入 | ① **核心复杂度不在 DOM 模板**：`assistant.js` 的难处在 SSE 流式协议解析与卡片渲染状态机，Alpine/HTMX 都帮不上这个；它们擅长的是「表单 + 列表 + 局部刷新」，而本项目最重的页面恰好不是这种形态。② **引入即引入依赖管理**：现在前端零构建、双击 HTML 就能调，加了框架就要回答「版本从哪来、升级谁负责」——教学项目里这是一笔纯负债。③ 原生 JS 手写 DOM 恰好是这个学习项目的教学价值之一。 |
| 触发条件 | 页面数翻倍（>10 个）、或出现多人并行写前端时，再评估；届时优先 HTMX（服务端渲染路线与 Thymeleaf 一致），而不是前后端分离框架。 |
| 替代小动作 | 若某个页面真出现「DOM 同步散落各处」的具体痛点，先做页面内的局部重构（抽 render 函数集中出口），不上升到框架。 |

---

## 回滚策略

每条改动单独提交、单独跑全量测试。若上线后出问题：
- A1/A2 是配置/注解级改动，回滚代码即可，无数据迁移
- A3 涉及建表，回滚需保留 `users` 表但停用 `JdbcUserDetailsManager`（不影响业务）
- A4/A5 无破坏性变更
