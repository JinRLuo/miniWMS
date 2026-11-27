# Mini WMS

轻量级电商仓储管理系统。PC 管理后台在 `/admin`，H5 手机工作台在 `/m`，原生微信小程序位于 `miniprogram/`。各端共享账号和业务数据，支持独立的货架仓库和鱼胶仓库。商品、库位、库存、流水和报表按仓库隔离。

## 微信小程序

新增原生微信小程序，用于承接手机端的扫码出入库、库存查询、本人操作记录和个人设置；管理员工作台提供库存概览、预警与最近 7 天趋势，操作员仅开放本人工作范围。与现有鱼胶手机端一致，暂不开放普通货架仓库和移库入口。电脑端继续负责规格、货架、账号和完整报表管理。

使用微信开发者工具导入 `miniprogram/`，设置自己的 AppID 后直接编译，无需构建 npm。默认连接现有 HTTPS 服务，上线前须配置 request 合法域名并完成真机验收。现有格子标签可在小程序内扫描；若要微信直接扫码后进入小程序，还需配置微信平台的普通链接二维码规则。H5 入口暂时保留。详细说明见 [小程序开发、联调与发布](miniprogram/README.md)。

小程序仅支持「微信登录＋手机号授权」，首次登录创建待授权账号，后端禁止其访问全部仓库业务接口。管理员在 PC「账号管理」按手机号找到用户，选择角色并授予访问权限；用户刷新授权状态后即可使用，撤销权限或停用也由后端实时校验。后端需配置 `WMS_WECHAT_APP_ID` 和 `WMS_WECHAT_APP_SECRET`，并完成微信手机号能力和隐私指引配置。AppSecret 只保存在服务端。V4 数据迁移保留已有密码账号的权限，既有 PC/H5 登录继续可用，微信身份不按手机号自动合并到既有账号。配置及验收步骤见上方小程序说明。

## 本地运行并连接云端 MySQL

需要 Java 17+、Maven 和 Node.js 20+。以下命令从仓库根目录运行。连接服务器需要可用的 `ssh aliapp` 配置。

在第一个终端打开隧道并保持运行。

```sh
bash deploy/mysql-tunnel.sh
```

在第二个终端启动后端。首次连接时，Flyway 会建表，应用会创建 `admin` 账号。`.env.cloud` 中的 `WMS_ADMIN_PASSWORD` 是该账号的初始密码。

```sh
source .env.cloud
cd backend
mvn spring-boot:run
```

在第三个终端启动前端。

```sh
cd frontend
npm ci
npm run dev
```

打开 [PC 管理后台](http://localhost:5173/admin) 或 [手机工作台](http://localhost:5173/m)。

生产数据库位于 `aliapp` 上的独立 `miniwms-mysql-1` 容器。数据库名为 `miniwms`，应用账号为 `miniwms_app`，权限限定在该库。隧道每次启动都会查询容器的当前地址，默认监听本机 `127.0.0.1:13306`；连接的是生产数据，需要隔离开发数据时使用下方 H2 演示模式。`.env.cloud` 权限为 0600，已被 Git 忽略。数据库地址和端口可通过 `WMS_DB_URL` 调整。隧道脚本也接受 SSH 别名、本机端口、MySQL 容器名三个位置参数。

## 使用本地演示数据

在另一个后端实例中使用内置 H2。保持默认数据库地址即可将演示数据保存在 `backend/data`。另开前端终端执行上方命令。

```sh
cd backend
WMS_DEMO=true mvn spring-boot:run
```

演示账号为 `admin`，密码为 `miniwms-demo-2026`。演示模式会在空库写入样例商品和库存。

## 手机扫码和部署

将 `WMS_PUBLIC_URL` 设置为手机可访问的 HTTPS 站点根地址，再生成或打印货架层二维码。站内扫码使用设备摄像头。也可使用手机系统相机扫描标签，登录后直接打开对应货架层。

线上入口为 [wms.piuiq.com](https://wms.piuiq.com)。`alifrp`（`8.134.203.247`）上的 Nginx 将 TCP 80、443 经内网转发到 `aliapp`（`172.24.182.10`）。应用机终止 HTTPS，提供静态页面，再将 API 请求转发到 `127.0.0.1:18089`。应用机公网 `8.163.39.133` 是备用入口，可将同一域名的 A 记录切换到该地址；两条链路共用应用、数据库、会话和证书。配置与切换命令见 [Nginx 部署说明](deploy/nginx/README.md)。

生产使用 [运行配置](deploy/compose.runtime.yaml)，包含 API、MySQL 8.0.45 和 Redis；数据库和 Redis 不发布主机端口。运行镜像由服务器 `.env` 指定并固定为已校验的本地镜像，Compose 不自动拉取。MySQL 凭据保存在 `/opt/miniwms/.mysql.env`，应用连接参数在 `/opt/miniwms/.app.env`，文件权限均为 0600。目录和数据卷需持久保存。

根目录 `compose.yaml` 用于从源码构建整套服务。设置 `WMS_DB_PASSWORD`、`WMS_DB_ROOT_PASSWORD`、`WMS_REDIS_PASSWORD`、`WMS_ADMIN_PASSWORD` 和 `WMS_PUBLIC_URL` 后执行 `docker compose up --build -d`；Web 容器只发布回环端口 `18089`，外层 HTTPS 反向代理连接该端口。它与线上直接运行发布 JAR 的运行配置是两种部署方式。

原域名 `wms.zoomzoom.team` 保留页面和二维码跳转，路径及查询参数原样带到新域名；旧 API 返回 503，旧应用已停止、旧库 WMS 账号已锁定。用户需在新域名重新登录，账号、密码和业务数据保持一致。新站接收写入后，恢复旧站前必须先反向迁移最新数据库，不能直接启动旧应用使用旧数据。

## Redis 会话与缓存

生产环境启用 `redis` Spring Profile，通过 Spring Session 将登录会话保存在 Redis。服务端会话闲置 15 天后过期，浏览器 `JSESSIONID` Cookie 也持久保存 15 天；关闭浏览器、重启后端或重新发布应用后可继续使用尚未过期的登录。首次从内存会话切换至 Redis 时需重新登录一次。主动退出会删除 Redis 会话，密码、账号名变更或账号停用会在下次请求时使旧会话失效；密码账号变更角色也会使旧会话失效。微信账号的角色及授权状态从数据库实时重载，授权后可直接刷新进入，取消授权后只可查看自身登录状态或退出。

仓库基础信息缓存 5 分钟，格子二维码 PNG 缓存 1 天，二维码缓存键包含完整扫码地址，变更站点地址后会生成新的二维码。库存数量、商品库存汇总、流水、报表和账号权限继续实时查询。会话使用 `miniwms:session:v1` 命名空间，缓存使用 `miniwms:cache:v1:` 前缀，均设置过期时间。

服务器使用独立 Redis 8.2.11 容器、内网 Docker 网络和随机密码，不发布主机端口；开启 AOF 持久化，数据保存在 `miniwms_redis-data` Docker 卷。Redis 内存上限 128 MiB，容器上限 256 MiB，使用 `noeviction` 避免自动淘汰登录会话。运行配置保存在 `/opt/miniwms/compose.runtime.yaml`，Redis 配置位于 `/opt/miniwms/redis/redis.conf`，应用连接参数位于仅 root 可读的 `/opt/miniwms/.app.env`。配置和密码请勿提交到 Git。

线上 Redis 已完成配置。[Redis 安装脚本](deploy/install-redis.py) 用于已有 `/opt/miniwms` 环境的 Redis 配置更新，会备份原配置并仅启动 Redis；它不负责初始化 MySQL、安装运行镜像或恢复数据库，完整部署需先准备上述独立数据库及运行环境。备份保存在 `/opt/miniwms/backups/before-redis-*`。

本地开发默认使用内存会话，不依赖 Redis。需要验证生产模式时，设置 `SPRING_PROFILES_ACTIVE=redis` 以及 `WMS_REDIS_HOST`、`WMS_REDIS_PORT`、`WMS_REDIS_PASSWORD`，再启动后端。可选参数 `WMS_REDIS_DATABASE`、`WMS_REDIS_SESSION_NAMESPACE`、`WMS_REDIS_CACHE_PREFIX` 用于隔离环境。使用根目录 `compose.yaml` 部署时，还须设置 `WMS_REDIS_PASSWORD`，Compose 会自动启用 Redis Profile 并启动独立 Redis 容器。

## GitHub Actions 自动发布

推送或合并到 `main` 后，GitHub Actions 会运行后端及部署接收器测试、构建前端，并上传保留一天的发布制品。受限 SSH 只传递小型发布请求，应用机通过 HTTPS 并行下载制品，避免跨云 SSH 大文件传输缓慢；也可在 Actions 的 **Deploy Mini WMS** 页面从 `main` 手动触发。

请求携带发布包大小、SHA256、提交号和 GitHub 生成的短时下载地址。应用机只允许 HTTPS 的 GitHub Azure Blob 制品存储地址，不跟随重定向，并限制请求、下载及解压大小；校验发布包和各文件哈希后才切换版本。GitHub Token 只留在 Actions，应用机只收到单份制品的短时下载地址。下载机制使用 [GitHub 官方制品 API](https://docs.github.com/en/rest/actions/artifacts#download-an-artifact)。原压缩包经 SSH 直接输入的方式仍兼容。

仓库变量 `MINIWMS_DEPLOY_HOST` 设为应用机公网 IP `8.163.39.133`，`MINIWMS_DEPLOY_KNOWN_HOSTS` 保存该 IP 对应的 SSH 主机公钥。Actions 直接连接应用机，发布流量不经过入口机。仓库 Secret `MINIWMS_DEPLOY_KEY` 保存专用部署私钥。服务器上的 `miniwms-deploy` 用户通过 SSH 强制命令运行 `/usr/local/sbin/miniwms-deploy`，包装命令以 Python 3.11 运行 [发布脚本](deploy/publish-release.py)。健康检查域名默认是 `wms.piuiq.com`，可由包装命令设置 `WMS_DEPLOY_SITE`。生产 Compose 配置允许通过 `WMS_RELEASE_JAR` 指定后端 JAR。数据库连接配置存放在服务器的 `/opt/miniwms/.app.env`。

发布串行执行。后端代码有变更时会更新 API 容器。发布后校验 PC 页面、扫码页面、静态资源和 API，健康检查失败会恢复上一应用版本。Flyway 在 API 启动时执行数据库迁移，新增迁移需要兼容上一应用版本。

Actions 会通过自部署 Bark 服务 `https://bark.zoomzoom.team` 发送带 GitHub 图标的部署结果通知：部署和健康检查成功时发送「Mini WMS 部署成功」，构建、发布或健康检查失败时发送「Mini WMS 部署失败」。内容包含仓库、分支和提交号，点击可打开本次 Actions 运行页面。请先在 Bark App 中添加该服务器，再将该服务器对应的设备 Key 保存为仓库 Secret `BARK_DEVICE_KEY`（Settings → Secrets and variables → Actions → New repository secret），只填写 Key，不包含服务器地址。因提交过期而跳过部署时不发送通知；未配置 Key 时会显示警告并跳过通知，通知发送失败不影响部署结果。

## 数据备份

应用机的 `miniwms-backup.timer` 每天上海时间 03:30 后十分钟内执行 [备份脚本](deploy/backup-mysql.sh)。它在 `/opt/miniwms/backups/mysql` 保存一致性 SQL 备份、SHA256 和运行配置，保留七天每日备份及四周周日备份。配置归档包含密码，仅 root 可读。

每次备份通过专用 SSH 密钥传到旧机的 `/opt/miniwms/backups/aliapp-daily`，异机保留 31 天。接收账号强制运行 [接收脚本](deploy/receive-backup.py)，只允许上传两份有大小限制的备份文件，没有交互式 shell 或端口转发权限；数据库 SQL 不会在旧机自动执行。

可用 `systemctl start miniwms-backup.service` 手动备份，使用 `systemctl status miniwms-backup.service` 和 `journalctl -u miniwms-backup.service` 检查结果。正式迁移已验证生成的备份可恢复，并对全部业务表的数据、结构、自增序号及库存汇总进行一致性核对。恢复操作应在维护窗口或独立数据库中进行。

## 仓库与鱼胶库存

当前 PC 和手机页面仅开放「鱼胶仓库」，默认进入鱼胶库存管理；原货架仓库的入口暂时隐藏，数据与后台能力保留。旧的仓库选择记录和货架仓库链接会自动切换到鱼胶仓库，原货架二维码会提示仓库暂未开放。将前端环境变量 `VITE_SHOW_GENERAL_WAREHOUSE=true` 后重启开发服务或重新构建，即可恢复两个仓库的切换入口。仓库仅包含名称和类型。原仓库按货架层管理，鱼胶仓库按「货架 → 层 → 格子」管理；两个仓库仅支持各自内部移库。

当前首页、库存操作和出入库记录默认隐藏移库入口、移库单页签和移库筛选项，仅开放入库、出库。历史流水和后端移库能力保留；设置前端环境变量 `VITE_SHOW_TRANSFERS=true` 后重启开发服务或重新构建，可恢复仓内移库界面。

库存操作的重量默认留空，需由用户填写；切换操作类型及操作成功后都会清空重量。页面不再提供单据编号输入框，出入库单据号由系统自动生成，历史记录仍可按单据号查询。

鱼胶规格填写品种和头数，例如「白花 · 33头」，头数表示每斤个数。每格绑定一种规格，斤数为实际库存，估算个数为 `斤数 × 头数` 四舍五入后的整数。估算个数仅供参考，每次根据当前重量计算。鱼胶出入库和移库最多支持三位小数，普通货架商品继续使用整数。

每个账号可以在 PC 的「个人设置」或手机底部的「设置」中选择斤、克，默认使用斤。偏好保存到账号，电脑和手机共用，其他账号不受影响。库存底层仍以斤保存，显示、录入、库存预警、图表、历史记录和 CSV 导出按个人设置换算，`1 斤 = 500 克`；克单位最小支持 0.5 克，对应原有的 0.001 斤精度。头数始终表示每斤个数，切换单位不会改变规格、库存或历史流水；原货架仓库的计数单位保持原样。

个人设置同时提供修改密码，所有登录账号均可使用。须填写当前密码、新密码及确认密码，新密码至少 12 个字符且最多 72 字节。后端校验当前密码后仅更新当前账号的密码哈希并记录审计日志；修改成功后删除当前会话，其他设备的旧会话在下次请求时失效，需使用新密码重新登录。单位偏好保留，管理员为其他账号重置密码的功能继续保留。

已有任何库存流水的鱼胶规格会锁定品种和头数，库存清零后仍保持锁定；编辑弹窗以只读字段及提示说明原因。新建或未产生流水的规格可修正，备注、图片和预警值仍可编辑。后端根据历史流水实时返回 `specificationLocked`，保存时在商品行锁内再次核对，防止并发操作或绕过前端改写历史规格。个人单位设置通过 Flyway V3 新增的 `user_preferences` 表保存，使用 `/api/auth/preferences` 查询及更新当前账号的偏好。

新建鱼胶货架默认 3 层、每层 2 格（共 6 格），可分别调整每层格数，格子编码如 `YJ01-02-03`。货架管理默认以多列紧凑货架图展示，各层与格子保持实际布局，支持按货架名称、格子编号、品种、头数及格子状态筛选；点击格子打开详情，可绑定规格、出入库、查看流水或打印标签。可批量打印二维码，扫码或输入完整编号定位格子。二维码固定绑定库位，清空或换绑商品后仍然有效；系统相机打开二维码时会自动进入所属仓库。已有库存需先清空才能换绑，有历史流水的鱼胶品种、头数和计量单位固定。

鱼胶库存操作的格子下拉框仅显示已绑定商品的格子，已绑定但库存为零的格子仍可选择入库。下拉菜单统一展示选中状态，长列表支持关键词搜索，并根据屏幕空间调整展开方向；日期、筛选栏和编辑弹窗使用一致的控件样式。

升级使用新增的 Flyway V2 迁移，保留原库位 ID、编码、二维码、库存和历史流水，原数据归入货架仓库。统计与 CSV 按仓库隔离，数量携带单位，总览与趋势按计量单位分别统计。鱼胶报表、库存流水和移库单均可按品种或品种＋头数精确筛选；选择品种后头数选项随之联动，切换品种时清空旧头数。趋势、排行、汇总、分页和 CSV 导出使用同一组已查询的筛选条件，历史查询保留已停用规格。

本地演示模式会分别初始化两个仓库，包含「白花33头、5斤、约165个」的鱼胶示例。使用独立的本地验收数据库，可在两个终端分别运行：

```sh
cd backend
WMS_DEMO=true WMS_DB_URL='jdbc:h2:file:./data/fish-acceptance;MODE=MySQL;DATABASE_TO_LOWER=TRUE' WMS_PUBLIC_URL=http://127.0.0.1:5173 mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8081
```

```sh
cd frontend
VITE_DEV_API=http://127.0.0.1:8081 npm run dev -- --host 127.0.0.1 --port 5173
```

验收地址为 [鱼胶管理后台](http://127.0.0.1:5173/admin/shelves?warehouse=2) 和 [手机工作台](http://127.0.0.1:5173/m?warehouse=2)，账号为 `admin`，密码为 `miniwms-demo-2026`。

## 库存规则

- 每个库位（货架层或鱼胶格子）可绑定一个 SKU，一个 SKU 可存放在本仓库的多个库位。商品总库存由各库位实时汇总。
- 入库增加指定层库存，出库以数据库中的最新数量为准。有库存的货架层需要先清空才能换绑或停用。
- 移库会生成一张移库单和两条流水。空目标层自动绑定商品，来源归零后保留绑定。移库在同一个事务内更新两侧库存，并且不计入进出库统计。
- 出入库及移库接口接受 `requestId`，同一账号用相同请求编号和载荷重试会获得原结果。
- 默认使用 `Asia/Shanghai` 为报表时区，可通过 `WMS_ZONE` 配置。仓库操作员仅开放工作台、库存操作、本人出入库记录和个人设置，不能进入鱼胶规格、货架管理、库存查询、统计报表或账号管理，后端对应的管理、库存列表及报表接口也仅允许管理员访问。
- 操作员工作台仅显示本人最近操作和出入库入口，出入库记录页明确说明“仅显示本人操作的记录”。分页、筛选和 CSV 导出都由后端强制限定当前账号，修改 `mine` 参数也无法查看其他账号的数据。出入库使用独立的操作上下文接口，提供操作所需的可用已绑定格子，历史筛选选项只包含本人涉及的规格及格子。
- 管理员保留全部管理和报表权限，PC 端可查询、筛选和导出全部账号的流水。手机记录页保留个人记录视图，并注明查询范围。

## 验证

```sh
mvn -f backend/pom.xml test
npm --prefix frontend run build
npm --prefix miniprogram run check
npm --prefix miniprogram test
```

后端集成测试使用独立的内存数据库，涵盖并发出库、重复请求、小数库存、估算个数、仓库隔离、移库回滚、报表口径、二维码解析、权限控制和已有数据迁移。

GitHub Actions 另外启动独立的 MySQL 8.0.45 测试库，验证 V1 旧库升级及 V2 中断后修复、续跑，检查原库存、流水、二维码和外键均保留。MySQL 迁移先建立包含 `shelf_id` 的新唯一索引，再移除旧的逐层唯一索引，避免删除外键依赖的索引。V2 对已完成的 DDL 做存在性检查，支持 MySQL 部分提交后的续跑；生产失败迁移须先备份并确认实际结构，再修复 Flyway 的失败记录。

Actions 同时启动独立 Redis，运行真实 HTTP 会话测试，涵盖应用重启后继续登录、15 天会话及 Cookie 有效期、闲置过期、主动退出、停用账号、缓存 TTL、站点变更后的二维码以及实时库存读取。本地设置 `WMS_TEST_REDIS_PORT` 后执行 `mvn -f backend/pom.xml test` 可运行这些测试，Redis 测试数据使用每次运行独立的命名空间，不清空整个 Redis 数据库。
