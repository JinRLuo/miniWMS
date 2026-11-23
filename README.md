# Mini WMS

轻量级电商仓储管理系统。PC 管理后台在 `/admin`，手机工作台在 `/m`。两端共享商品、货架层库存和流水。

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

## 库存规则

- 每个货架层可绑定一个 SKU，一个 SKU 可存放在多个货架层。商品总库存由各层实时汇总。
- 入库增加指定层库存，出库以数据库中的最新数量为准。有库存的货架层需要先清空才能换绑或停用。
- 移库会生成一张移库单和两条流水。空目标层自动绑定商品，来源归零后保留绑定。移库在同一个事务内更新两侧库存，并且不计入进出库统计。
- 出入库及移库接口接受 `requestId`，同一账号用相同请求编号和载荷重试会获得原结果。
- 默认使用 `Asia/Shanghai` 为报表时区，可通过 `WMS_ZONE` 配置。仓库操作员可处理库存并查询自己的流水。管理员可以维护商品、货架和账号并查阅全部流水。

## 验证

```sh
mvn -f backend/pom.xml test
npm --prefix frontend run build
```

后端集成测试使用独立的内存数据库，涵盖并发出库、重复请求、移库回滚、报表口径、二维码解析和权限控制。
