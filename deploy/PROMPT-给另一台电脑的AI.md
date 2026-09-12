# 交给另一台电脑 AI 的任务书（可直接整段复制粘贴）

> 这份文件本身就是给 AI 的 prompt。复制下面 `---` 之间的全部内容，粘贴到那台电脑的 AI 对话框里即可。

---

## 任务：把 AQuant 部署到局域网里的飞牛 NAS（fnOS）

你没有上下文，先看完再动手。这是一个**已经准备好了、只差执行**的部署。

### 背景

AQuant 是一个 A 股量化投研平台，技术栈是 Spring Boot 3（Java 17）+ Vue 3 + MySQL + 一个 Python 数据服务（aktools）。
现在要把它部署成一组 Docker 容器跑到一台飞牛 NAS 上。

**部署所需的一切已经打包好了**，你不需要写代码、不需要构建。包在我（用户）手上，
需要你问我包放在哪个路径。

### 包里的东西

```
docker-compose.prebuilt.yml    ★ 用这份（零构建：全用官方镜像，不跑 docker build）
docker-compose.yml             源码构建版（NAS 要跑 Maven/npm，网络差就别用）
prebuilt/                      预构建产物
    a-quant-0.0.1-SNAPSHOT.jar   后端 fat jar（90MB，Java 17）
    dist/                        前端静态文件（给 nginx）
deploy/nginx.conf              前端托管 + /api 反代配置
deploy/DEPLOY-fnos.md          ★★ 完整部署文档，遇到问题先查它的第 7 节
stock.sql                      建库脚本（37 张表）
.env.example                   环境变量模板
```

### 目标

四个容器在 NAS 上稳定运行，且下面两条能通：

1. `http://<NAS_IP>:8081` 打开主界面，**能登录**
2. `http://<NAS_IP>:8084/api/...` 能返回真实数据（初始同步跑完后）

> ⚠️ **注意账号问题**：`stock.sql` 里**没有任何账号数据**（0 条 INSERT），所以**全新部署的库里没有用户，
> 谁都登不进去**。解决办法二选一：
> - 用 `/auth/register` 注册一个新账号（注册接口是放行的，界面登录页应该有入口）；
> - 或者用户给了数据导出包（见下面第 0 步），导入后 `puulsar` 账号就能直接用（密码是用户本机设的那个）。
>
> 部署前先问用户走哪条，别自己假设。

### 第 0 步（可选，但强烈建议先问）：要不要导入已有数据

用户本机已经跑过一轮全量同步：**1690 万行历史行情、5561 只股票、约 4.5 GB**，
外加 115 条同步水位（`stock_sync`）。重新同步一遍要 **3–4 小时**。

如果用户提供了 `stock-data.sql.gz` 之类的导出包，**务必在启动后端之前导入**，否则后端一起来就会
重新跑全量同步。导入方法：

```bash
# 等 mysql 容器 healthy 之后
gunzip -c stock-data.sql.gz | docker compose -f docker-compose.prebuilt.yml exec -T mysql \
  mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4
```

**关键**：`stock_sync` 那 115 条水位**必须一起导入**。只导行情数据不导水位的话，
后端会认为什么都没同步过，照样跑满 3–4 小时——数据白搬了。


### 你要做的步骤

**第 1 步：找到 NAS 的内网 IP**

问用户，用户知道最好。如果用户不知道：看本机 `arp -a`，再对候选 IP 扫 22 端口，
开着 SSH 的那台就是。注意：NAS 上「系统设置 → 远程访问 → SSH」必须是开启状态。
**端口 22 是飞牛自用的，不要动它。**

**第 2 步：确认你能 SSH**

```bash
ssh 用户名@NAS_IP        # 密码由用户输入；如果本机没有 ssh，见文末
```

**第 3 步：把包传上去并解压**

```bash
scp <包的路径> 用户名@NAS_IP:~/
ssh 用户名@NAS_IP
mkdir -p /vol1/1000/docker/aquant && cd /vol1/1000/docker/aquant
unzip ~/aquant-deploy-full.zip
```

（存储池路径 `/vol1/1000/...` 按实际情况改；用户不知道就问他）

**第 4 步：配环境变量**

```bash
cp .env.example .env
vi .env       # 至少改掉 MYSQL_ROOT_PASSWORD
```

**第 5 步：启动**

```bash
docker compose -f docker-compose.prebuilt.yml up -d
```

**第 6 步：盯日志，确认起来了**

```bash
docker compose -f docker-compose.prebuilt.yml ps
docker compose -f docker-compose.prebuilt.yml logs -f aktools   # 首次要 pip 装依赖，2-5 分钟
docker compose -f docker-compose.prebuilt.yml logs -f backend   # 看有没有连上 MySQL
```

验收：`ps` 里四个服务都是 `Up`，且不带 `(unhealthy)`。

### 绝对不要做这几件事

1. **不要把 aktools 的 `--host 0.0.0.0` 改成 `127.0.0.1`**。上游 aktools 的默认值就是 `127.0.0.1`，
   在容器里等于只监听自己，后端容器会永远连不上，症状是数据同步全线报"外部API调用异常"。
2. **不要改 nginx 里 `proxy_pass http://backend:8084/;` 结尾那个斜杠**。前端请求带 `/api` 前缀，
   靠这个斜杠剥掉；少一个斜杠接口就全 404。
3. **不要删 nginx 的 `try_files $uri $uri/ /index.html`**。Vue Router 是 history 模式，删了刷新就 404。
4. **不要把 `stock.sql` 当成能重复执行的脚本**。它只在 MySQL 数据目录为空（首次初始化）时被导入一次。
5. **不要同时启动两份 compose**（`docker-compose.yml` 和 `docker-compose.prebuilt.yml` 二选一）。
6. **后端必须先于前端、且 MySQL 必须先就绪**。compose 里已经用 healthcheck + depends_on 排好顺序了，
   不要为了"加快启动"把它们去掉。

### 预期与正常现象

- 首次启动慢：aktools 要 pip 装依赖（走阿里云源，2–5 分钟），MySQL 要初始化并导入 37 张表。
- 后端一起来就会**自动跑一次全量数据同步，约 3–4 小时**。这期间界面能打开但行情/指数/板块数据是空的，
  **这是正常的，不是故障**。
- 同步中断不会丢进度（`stock_sync` 表存了水位），重启就续传。
- 资源要求：内存 ≥ 4GB，磁盘 ≥ 30GB。内存紧张就调小 `.env` 里的 `JAVA_OPTS` 的 `-Xmx`。

### 排错

先看 `deploy/DEPLOY-fnos.md` 的**第 7 节**，里面按症状列了原因（8 条）。
不在其中的，把完整报错贴出来再分析，不要盲改。

### 边界

- 每一步做了什么都告诉我，特别是**任何删除、覆盖、改配置的动作**。
- 遇到要改动我已有数据的情况，先问我。
- **不要往这个 NAS 上装除 Docker 镜像以外的任何东西。**

### 如果本机没有 ssh / scp

- Windows 10/11：PowerShell 直接可用；没有的话去「设置 → 系统 → 可选功能」加装「OpenSSH 客户端」。
- 或者退一步：用飞牛 Web 界面的文件管理器把 zip 上传上去，再接 SSH 继续。

---

（上面这段到此为止，以下内容不用发给 AI，是给你看的。）

## 给你自己的备注

- 这份文件也打包进了 `aquant-deploy-full.zip`，路径 `deploy/PROMPT-给另一台电脑的AI.md`。
- **别把飞牛密码写进这个文件**，也别让它进包里。密码当面告诉那台电脑的 AI，或者你自己敲。
- 那台电脑只需要 `ssh` + `scp`，**不需要装 Docker / JDK / Maven / Node** —— NAS 自己跑容器。
- 判断成功的最低标准：`:8081` 能登录进主界面。数据要等 3–4 小时同步完才满。
