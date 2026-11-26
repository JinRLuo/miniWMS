# Mini WMS

轻量级电商仓储管理系统。PC 管理后台在 `/admin`，手机工作台在 `/m`。两端共享数据，支持独立的货架仓库和鱼胶仓库。商品、库位、库存、流水和报表按仓库隔离。

## 本地运行并连接云端 MySQL

需要 Java 17+、Maven 和 Node.js 20+。以下命令从仓库根目录运行。连接服务器需要可用的 `ssh zoomzoom` 配置。

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

服务器复用 `zoomzoom` 上的 `musepro-mysql` 容器。数据库名为 `miniwms`，应用账号为 `miniwms_app`，权限限定在该库。隧道每次启动都会查询容器的当前地址，默认监听本机 `127.0.0.1:13306`。`.env.cloud` 权限为 0600，已被 Git 忽略。数据库地址和端口可通过 `WMS_DB_URL` 调整。

## 使用本地演示数据

在另一个后端实例中使用内置 H2。保持默认数据库地址即可将演示数据保存在 `backend/data`。另开前端终端执行上方命令。

```sh
cd backend
WMS_DEMO=true mvn spring-boot:run
```

演示账号为 `admin`，密码为 `miniwms-demo-2026`。演示模式会在空库写入样例商品和库存。

## 手机扫码和部署

将 `WMS_PUBLIC_URL` 设置为手机可访问的 HTTPS 站点根地址，再生成或打印货架层二维码。站内扫码使用设备摄像头。也可使用手机系统相机扫描标签，登录后直接打开对应货架层。

`compose.yaml` 提供应用部署配置。API 加入已有的 MySQL Docker 网络，Web 容器只发布服务器回环端口 `18089`。在目标服务器上设置数据库密码、管理员初始密码及 HTTPS 站点地址，使用 `docker compose up --build -d` 启动，再将 HTTPS 反向代理指向 `127.0.0.1:18089`。如果 API 和数据库在同一 Docker 网络，使用默认容器连接地址即可。MySQL 连接地址和网络名可通过 `WMS_DB_URL`、`WMS_DB_NETWORK` 覆盖。

## GitHub Actions 自动发布

推送或合并到 `main` 后，GitHub Actions 会运行后端测试、构建前端，然后把带校验和的发布包传到线上服务器。也可在 Actions 的 **Deploy Mini WMS** 页面从 `main` 手动触发。

仓库变量 `MINIWMS_DEPLOY_HOST` 设为服务器公网 IP `43.156.245.251`，`MINIWMS_DEPLOY_KNOWN_HOSTS` 保存该 IP 对应的 SSH 主机公钥。Actions 直接通过公网 IP 连接服务器。仓库 Secret `MINIWMS_DEPLOY_KEY` 保存专用部署私钥。服务器上的 `miniwms-deploy` 用户通过 SSH 强制命令运行 `/usr/local/sbin/miniwms-deploy`，该文件由 [发布脚本](deploy/publish-release.py) 安装。生产 Compose 配置允许通过 `WMS_RELEASE_JAR` 指定后端 JAR。数据库连接配置存放在服务器的 `/opt/miniwms/.app.env`。

发布串行执行。后端代码有变更时会更新 API 容器。发布后校验 PC 页面、扫码页面、静态资源和 API，健康检查失败会恢复上一应用版本。Flyway 在 API 启动时执行数据库迁移，新增迁移需要兼容上一应用版本。

Actions 会通过自部署 Bark 服务 `https://bark.zoomzoom.team` 发送带 GitHub 图标的部署结果通知：部署和健康检查成功时发送「Mini WMS 部署成功」，构建、发布或健康检查失败时发送「Mini WMS 部署失败」。内容包含仓库、分支和提交号，点击可打开本次 Actions 运行页面。请先在 Bark App 中添加该服务器，再将该服务器对应的设备 Key 保存为仓库 Secret `BARK_DEVICE_KEY`（Settings → Secrets and variables → Actions → New repository secret），只填写 Key，不包含服务器地址。因提交过期而跳过部署时不发送通知；未配置 Key 时会显示警告并跳过通知，通知发送失败不影响部署结果。

## 仓库与鱼胶库存

当前 PC 和手机页面仅开放「鱼胶仓库」，默认进入鱼胶库存管理；原货架仓库的入口暂时隐藏，数据与后台能力保留。旧的仓库选择记录和货架仓库链接会自动切换到鱼胶仓库，原货架二维码会提示仓库暂未开放。将前端环境变量 `VITE_SHOW_GENERAL_WAREHOUSE=true` 后重启开发服务或重新构建，即可恢复两个仓库的切换入口。仓库仅包含名称和类型。原仓库按货架层管理，鱼胶仓库按「货架 → 层 → 格子」管理；两个仓库仅支持各自内部移库。

鱼胶规格填写品种和头数，例如「白花 · 33头」，头数表示每斤个数。每格绑定一种规格，斤数为实际库存，估算个数为 `斤数 × 头数` 四舍五入后的整数。估算个数仅供参考，每次根据当前重量计算。鱼胶出入库和移库最多支持三位小数，普通货架商品继续使用整数。

新建鱼胶货架默认 3 层、每层 2 格（共 6 格），可分别调整每层格数，格子编码如 `YJ01-02-03`。货架管理默认以多列紧凑货架图展示，各层与格子保持实际布局，支持按货架名称、格子编号、品种、头数及格子状态筛选；点击格子打开详情，可绑定规格、出入库、查看流水或打印标签。可批量打印二维码，扫码或输入完整编号定位格子。二维码固定绑定库位，清空或换绑商品后仍然有效；系统相机打开二维码时会自动进入所属仓库。已有库存需先清空才能换绑，有历史流水的鱼胶品种、头数和计量单位固定。

升级使用新增的 Flyway V2 迁移，保留原库位 ID、编码、二维码、库存和历史流水，原数据归入货架仓库。统计与 CSV 按仓库隔离，数量携带单位，总览与趋势按计量单位分别统计。

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
- 默认使用 `Asia/Shanghai` 为报表时区，可通过 `WMS_ZONE` 配置。仓库操作员可处理库存并查询自己的流水。管理员可以维护商品、货架和账号并查阅全部流水。

## 验证

```sh
mvn -f backend/pom.xml test
npm --prefix frontend run build
```

后端集成测试使用独立的内存数据库，涵盖并发出库、重复请求、小数库存、估算个数、仓库隔离、移库回滚、报表口径、二维码解析、权限控制和已有数据迁移。
