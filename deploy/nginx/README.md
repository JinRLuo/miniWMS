# Mini WMS 入口与 HTTPS 配置

默认入口为 `wms.piuiq.com`（`8.134.203.247`），Nginx 通过内网将 TCP 80、443 转发至应用机 `172.24.182.10`。应用机公网地址为 `8.163.39.133`，用于备用直连及发布。

2026 年 11 月 28 日已完成 WMS 应用及独立数据库迁移。默认入口和应用机公网直连均通过证书、前端文件校验和、API 及 HTTP 308 跳转检查。证书有效期至 2027 年 2 月 25 日，应用机 `certbot-renew.timer` 及重载钩子已启用，`certbot renew --dry-run --cert-name wms.piuiq.com` 续期演练通过。旧站仅保留页面、二维码跳转和证书续期，旧 API 返回 503。

## 配置文件

| 文件 | 主机及目标路径 | 用途 |
| --- | --- | --- |
| `ingress.conf` | alifrp，`/etc/nginx/nginx.conf` | 一名 worker，直接透传 80、443，不终止 TLS，不保留原 NAS 443 路由 |
| `app-nginx.conf` | aliapp，`/etc/nginx/nginx.conf` | 应用机主配置，加载 `conf.d`，一名 worker |
| `app-http-bootstrap.conf` | aliapp，`/etc/nginx/conf.d/wms.piuiq.com.conf` | 签发证书前的 HTTP 维护页及 ACME webroot |
| `app-maintenance.conf` | aliapp，同一路径，替换 HTTP 初始化配置 | 证书签发后的 HTTPS 维护页、HTTP 跳转及 ACME webroot |
| `app-production.conf` | aliapp，同一路径，当前生效 | 正式 HTTPS 站点、HTTP 跳转及 ACME webroot |
| `site.inc` | aliapp，`/opt/miniwms/nginx/site.inc` | 当前发布静态页面、资源缓存及回环 API 代理 |
| `app-preview.conf` | aliapp，`/etc/nginx/conf.d/miniwms-preview.conf` | 仅监听回环地址的 HTTPS 18443 验收入口 |
| `maintenance.html` | aliapp，`/var/www/miniwms-maintenance/index.html` | 维护时以 HTTP 503 返回稍后重试提示 |
| `reload-nginx` | aliapp，`/etc/letsencrypt/renewal-hooks/deploy/reload-nginx` | 证书续期成功后校验配置并重新加载 Nginx，安装权限为 0700 |
| `legacy-http.conf` | zoomzoom，`/opt/miniwms/nginx/http.conf` | 旧域名 HTTP 入口及 ACME 校验 |
| `legacy-https.conf` | zoomzoom，`/opt/miniwms/nginx/https.conf` 和 `/usr/local/nginx/conf/conf.d/miniwms.conf` | 旧域名 HTTPS 入口及 ACME 校验，配合原续期任务 |
| `legacy-site.inc` | zoomzoom，`/opt/miniwms/nginx/site.inc` | GET、HEAD 页面以 302 跳到新域名并保留路径及参数；API 和其他写入请求返回 503 |

HTTP 初始化、HTTPS 维护和正式站点配置互相替换，不能同时加载。证书必须先签发成功，才能启用引用 `/etc/letsencrypt/live/wms.piuiq.com/` 的 HTTPS 配置。

ACME webroot 为 `/var/lib/miniwms-acme`。证书和续期任务留在应用机；入口只转发 TCP。维护时替换应用机站点配置并执行 `nginx -t && systemctl reload nginx`，始终保留 HTTP 校验路径和证书配置。重载后待新 worker 接管，再执行健康检查。

## 备用入口

在不修改 DNS 的情况下，可分别检查两条链路：

```sh
curl --resolve wms.piuiq.com:443:8.134.203.247 https://wms.piuiq.com/admin
curl --resolve wms.piuiq.com:443:8.163.39.133 https://wms.piuiq.com/admin
```

入口故障或需要减少入口流量时，将 `wms.piuiq.com` 的 A 记录切到 `8.163.39.133`，继续使用相同 HTTPS 域名。当前 DNS TTL 为 900 秒，已有缓存和连接会延迟切换生效。无需复制数据库、重新部署应用或重新签发证书。

入口机的 200 Mbps 为带宽上限，20 GB 流量额度及其他 FRP 使用量需在阿里云核对。本次未配置云侧费用预警或 DNS 自动切换，额度用尽不会触发本部署自动切换。

## 端口与 FRP

NAS 的 `nas-nginx-tcp` 映射已从 `/volume1/applications/frp/config/frpc.toml` 移除，并重启该客户端以释放 443。其他客户端和入口机 `frps` 服务保留。

入口机 FRPS 配置中的 `allowPorts` 已移除 80、443，防止后续启动时重新占用。现有 `frps` 进程未重启，新白名单在下次服务启动时生效；当前两个端口由 Nginx 持有。

公网访问及 HTTP 证书校验需要阿里云安全组放行入口机 TCP 80、443。应用机需要允许来自入口机的私网 80、443；备用直连也需要放行应用机公网 80、443。

## 变更备份

- 入口机原 Nginx、日志轮转和 FRPS 配置：`/root/miniwms-ingress-backups/20261127-225849/`。
- 应用机原 Nginx 配置：`/root/miniwms-ingress-backups/20261127-225849/`。
- NAS 原客户端配置：`/volume1/applications/frp/config/frpc.toml.backup-miniwms-20261127-225946`，包含认证信息，仅 root 可读，不复制到仓库。

如需恢复 NAS 公网 443，先停止或调整入口 Nginx 的 443 监听，再恢复 NAS 客户端映射。若 FRPS 已加载新白名单，还需恢复白名单中的 443，校验并重启 FRPS；该重启会影响其他 FRP 连接，恢复前核对。
