# 部署到飞牛 NAS（fnOS）

把 AQuant 四件套跑成一组 Docker 容器：**MySQL + aktools + 后端 + 前端(nginx)**。
飞牛的 Docker → Compose（`项目`）直接支持这套 `docker-compose.yml`。

---

## 0. 先想清楚三件事

| 项目 | 要求 | 说明 |
|---|---|---|
| 架构 | x86_64 | 飞牛是 Debian 系 x86_64，所用镜像都有对应版本 |
| 内存 | **≥ 4 GB** | MySQL ~400M + JVM 最多 1G + aktools(pandas/akshare) ~500M + nginx |
| 磁盘 | **≥ 30 GB 可用** | MySQL 首次全量同步后数据约 5–8 GB（`stock_quote_history` 会到千万行级） |

另外，飞牛自己占用 **80 / 443 / 22**，本方案故意避开：
- 主界面（Vue）→ 宿主机 **8081**
- 后端接口文档 + 内置管理 SPA → 宿主机 **8084**

---

## 1. 飞牛这边先准备

1. **装 Docker 应用**：飞牛应用中心里装 `Docker`。
2. **配镜像加速**（国内重要）：Docker → 设置 → 镜像仓库/加速地址，加一个国内加速源。
   本方案用到的基础镜像：`mysql:8.4`、`python:3.12-slim`、`maven:3.9-eclipse-temurin-17`、
   `eclipse-temurin:17-jre`、`node:22-alpine`、`nginx:alpine`。
3. **选好数据落盘位置**：默认放仓库内 `.runtime/docker`（已 gitignore）；
   想放到存储池就在 `.env` 里把 `DATA_ROOT` 改成绝对路径，例如 `/vol1/1000/docker/aquant/data`。

---

## 2. 把代码弄上 NAS

> ⚠️ **别直接 `git clone` 上游仓库。** 本机这套代码里有**尚未提交**的自研部分
> （`static/` 管理 SPA、`/admin/*` 系列控制器、更新过的 `stock.sql`），
> clone 出来的版本**没有管理后台**。用下面两种方式之一。

**方式 A（推荐，最省事）——直接把本地目录拷上去**

先在飞牛「文件管理器」或 SMB 里建一个目录，例如 `/vol1/1000/docker/aquant`，
然后把本机 `D:\AQuant` 的内容拷进去（**排除** `node_modules`、`target`、`.runtime`、`.git`）。

在本机 PowerShell 里打包（自动排除上面这些）：

```powershell
$tmp = "$env:TEMP\aquant-pack"
if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
robocopy D:\AQuant $tmp /E /XD node_modules target .runtime .git .workbuddy .trae screenshots
Compress-Archive -Path "$tmp\*" -DestinationPath "$env:TEMP\aquant-deploy.zip" -Force
```

把 `%TEMP%\aquant-deploy.zip` 通过飞牛的文件管理器上传、解压到 `/vol1/1000/docker/aquant`。

**方式 B——先提交，再走镜像 clone**

```bash
git add -A && git commit -m "feat: 管理 SPA + admin 接口"
git clone --depth 1 https://ghfast.top/https://github.com/<你的仓库>.git
```

（本机 GitHub 直连不通，需要 `unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY` 后走镜像。）

**不管哪种方式，最后目录结构必须是这样**（`docker-compose.yml` 在项目根）：

```
/vol1/1000/docker/aquant/
├── docker-compose.yml
├── stock.sql
├── .env                      <- 从 .env.example 复制
├── deploy/
│   ├── aktools.Dockerfile
│   ├── backend.Dockerfile
│   ├── web.Dockerfile
│   ├── nginx.conf
│   └── maven-settings.xml
├── aquant-backend/
└── aquant-frontend/
```

---

## 3. 建 `.env`

```bash
cd /vol1/1000/docker/aquant
cp .env.example .env
```

按需改：`MYSQL_ROOT_PASSWORD`（务必改）、`WEB_PORT`、`BACKEND_PORT`、`DATA_ROOT`、`JAVA_OPTS`。

---

## 4. 启动

**方式一：飞牛图形界面**

Docker → **项目** → **新建项目** →
- 项目名：`aquant`
- 路径：`/vol1/1000/docker/aquant`（必须是仓库根，因为 compose 里用相对路径引用源码）
- 来源：**上传 docker-compose.yml**（或用 SSH 把这个文件放好）→ 创建项目后立即启动

**方式二：SSH**

```bash
cd /vol1/1000/docker/aquant
docker compose up -d --build
```

首次要下 Maven 依赖 + npm 包，**约 15–30 分钟**（已配阿里云 Maven 镜像与 npmmirror，会快不少）。
看进度：

```bash
docker compose logs -f backend
```

---

## 5. 验证（四项都要过）

```bash
# 1) 四个容器都在跑
docker compose ps

# 2) 数据服务通了
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8080/docs    # 容器内网，可跳过

# 3) 后端起来了（返回 200）
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8084/doc.html

# 4) 前端 + 全链路代理通了
curl -s http://127.0.0.1:8081/api/stockIndex/cards
```

浏览器打开：
- 主界面 **http://NAS_IP:8081**  → 账号 `puulsar` / `aquant123456`
- 内置管理 SPA + 接口文档 **http://NAS_IP:8084/**

---

## 6. 首次数据同步（要等）

后端一启动就会**自动跑一次全量同步**（`StockSyncTask` 上的 `@ApplicationReadyEvent`，没有手动开关以外的触发入口）。

- 个股历史回补约 **2.5–3 秒/只** × 5000+ 只 ≈ **3 小时**
- 加板块回补（每个板块间随机 sleep 5–10 秒）等阶段，**合计约 3–4 小时**
- 期间界面上的指数/板块/基金数据是空的，属正常

进度查看：

```bash
docker compose logs -f backend | grep 同步
```

或直接查库：

```bash
docker compose exec mysql mysql -utest -ptesttest stock \
  -e "SELECT COUNT(*) FROM stock_quote_history;"
```

**中断不丢进度**——`stock_sync` 表存了水位，重启后按增量续传。

---

## 7. 常见问题

**① 后端起来就报 `External API call exception` / 数据同步全失败**
最常见是 aktools 没连上。上游 aktools 的 CLI **默认 `--host 127.0.0.1`**，在容器里等于只监听容器自己，
后端容器必然连不上。本方案已在 `deploy/aktools.Dockerfile` 里显式写成
`python -m aktools --host 0.0.0.0 --port 8080`，**不要改回去**。
自检：`docker compose exec backend getent hosts aktools`。

**② 后端报 `Unable to open JDBC Connection for DDL execution` / `BUILD FAILURE`**
= MySQL 还没就绪就起了后端。compose 里已用 `depends_on: mysql: condition: service_healthy` 挡住，
如果还出现，通常是 MySQL 初始化失败。看 `docker compose logs mysql`。

**③ 主界面刷新就 404 / 白屏**
Vue Router 是 history 模式，nginx 必须回退到 `index.html`。
`deploy/nginx.conf` 里已有 `try_files $uri $uri/ /index.html`，别删。

**④ 页面能开但接口全 404**
`/api` 前缀没剥干净。nginx 用 `proxy_pass http://backend:8084/;`（**末尾带斜杠**）把 `/api/` 去掉，
少这个斜杠就会把 `/api/...` 原样发过去。

**⑤ 单独重建过 backend 容器后，主界面接口挂**
nginx 启动时缓存了 backend 的 IP。`docker compose restart web` 即可。

**⑥ 数据同步报 `JSONDecodeError ... starting with character '<'`**
新浪数据源临时限流，等一会儿重试即可（后端会自动重试，中断也不丢水位）。

**⑦ 端口冲突**
改 `.env` 里的 `WEB_PORT` / `BACKEND_PORT` 后 `docker compose up -d`。

**⑧ 内存不够 / 容器被 OOM kill**
把 `.env` 里 `JAVA_OPTS` 的 `-Xmx` 调到 `768m`，并考虑把 MySQL 的
`--innodb-buffer-pool-size=256M`（在 `docker-compose.yml`）降到 `128M`。

---

## 8. 日常运维

```bash
docker compose ps                      # 状态
docker compose logs -f backend         # 跟日志
docker compose restart backend         # 重启单个
docker compose down                    # 停止（保留数据）
docker compose up -d --build           # 更新代码后重建
```

**备份**：整个 `DATA_ROOT`（默认 `.runtime/docker`，内含 `mysql/`）+ `.env` 一起打包即可。
**恢复**：放回原位，`docker compose up -d`。
**注意**：`stock.sql` 只在 MySQL **首次初始化**（数据目录为空）时导入。已有数据后改 `stock.sql` 不会生效，
表结构靠后端的 JPA `ddl-auto: update` 自动跟进。

---

## 9. 零构建部署（网络不佳时用这份，推荐）

如果 NAS 拉 Maven / npm 依赖困难（国内常见），用 **`docker-compose.prebuilt.yml`**：
**不在 NAS 上跑任何 docker build**，四个服务全部用官方现成镜像，我们的产物直接挂进去。

```bash
docker compose -f docker-compose.prebuilt.yml up -d
```

| 服务 | 镜像 | 我们的产物 |
|---|---|---|
| mysql | `mysql:8.4` | `stock.sql` 挂进初始化目录 |
| aktools | `python:3.12-slim` | 无（首次启动时 pip 装 aktools + akshare，走阿里云源） |
| backend | `eclipse-temurin:17-jre` | `prebuilt/a-quant-0.0.1-SNAPSHOT.jar` 挂到 `/app/app.jar` |
| web | `nginx:alpine` | `prebuilt/dist` 挂到 `/usr/share/nginx/html` |

两份 compose 的**端口、环境变量、数据落盘位置完全一致**，只是"镜像从哪来"不同。二选一即可，别同时起。

产物怎么来（在**有工具链的机器**上跑，一次就够）：

```powershell
cd D:\AQuant\aquant-backend
mvn -B clean package -DskipTests            # -> target/a-quant-0.0.1-SNAPSHOT.jar
cd D:\AQuant\aquant-frontend
npm ci --prefer-offline
npx vite build                              # -> dist/
```

> 注意前端用 `npx vite build` 而不是 `npm run build`——后者会先跑 `vue-tsc` 类型检查，
> 类型报错会直接卡住打包。

放成这个结构即可（本次交付的包已经排好）：

```
aquant/
├── docker-compose.prebuilt.yml
├── docker-compose.yml            <- 源码构建版，二选一
├── .env / .env.example
├── stock.sql
├── prebuilt/
│   ├── a-quant-0.0.1-SNAPSHOT.jar
│   └── dist/
└── deploy/nginx.conf
```

首次启动慢一点：aktools 要装 pip 依赖（2–5 分钟），MySQL 要初始化并导入 37 张表。

---

## 10. 从局域网内另一台电脑部署（SSH）

NAS 不在身边、手上这台机器又连不上它时，换一台**和 NAS 同一局域网**的电脑，走 SSH：

```bash
# 1) 传包过去（把 <包> 换成实际路径）
scp aquant-deploy-full.zip 你的账号@NAS_IP:~/

# 2) 登录 NAS
ssh 你的账号@NAS_IP

# 3) 解压到存储池（路径按你的实际情况改）
mkdir -p /vol1/1000/docker/aquant
cd /vol1/1000/docker/aquant
unzip ~/aquant-deploy-full.zip

# 4) 准备环境变量
cp .env.example .env
vi .env          # 至少改掉 MYSQL_ROOT_PASSWORD

# 5) 起（网络不佳就用零构建版）
docker compose -f docker-compose.prebuilt.yml up -d
```

前提：NAS 上「系统设置 → 远程访问 → SSH」已开启，且你的账号有 SSH 登录权限。

**如果这台电脑也没装 `scp`/`ssh`**：Windows 10/11 自带 OpenSSH 客户端，PowerShell 里直接可用；
没装的话去「设置 → 系统 → 可选功能」里加装「OpenSSH 客户端」。或者用飞牛的文件管理器把 zip 上传上去。

**SSH 进去后最该先跑的一条自检**：

```bash
docker compose -f docker-compose.prebuilt.yml ps
docker compose -f docker-compose.prebuilt.yml logs -f aktools   # 看 pip 装完没
docker compose -f docker-compose.prebuilt.yml logs -f backend   # 看有没有连上 MySQL
```


---

## 附：本方案验证到什么程度

已验证：

- ✅ 后端 jar 在本机 `mvn -B clean package -DskipTests` 实测通过，产物 `a-quant-0.0.1-SNAPSHOT.jar`（90 MB fat jar）
- ✅ 前端 `npx vite build` 实测通过（30.87 秒），产出 `prebuilt/dist`（60 个文件 / 3.7 MB）
- ✅ 两项配置能用环境变量覆盖：`SPRING_DATASOURCE_*`、`AKSHARE_ADDRESS`
  （代码里是 `@Value("${akshare-address}")`，Spring 的 `SystemEnvironmentPropertySource` 会把 `AKSHARE_ADDRESS` 映射上去）、`SPRING_MAIL_*`
- ✅ `stock.sql` 含 37 张表、无 `CREATE DATABASE`（靠 `MYSQL_DATABASE=stock` 建库）、含 `sys_config`
- ✅ `aktools` 的 `--host` 默认值确认为 `127.0.0.1`（已在上游 `aktools/cli.py` 中核对）
- ✅ 两份 compose 均通过 YAML 解析，插值变量全部被 `.env.example` 覆盖

**未实测**：本机没有 Docker，也没有 NAS 的访问权限，所以**镜像拉取、容器编排、MySQL 初始化导入都没有真正跑过**。
首次启动时请盯 `docker compose logs -f`，有报错直接对照第 7 节。

