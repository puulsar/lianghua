AQuant 部署包 —— 先读这个
================================

包里有什么
----------
  docker-compose.prebuilt.yml   ★ 零构建版：全用官方镜像，不跑 docker build（推荐）
  docker-compose.yml            源码构建版：NAS 上要跑 Maven/npm，网络差就别用
  prebuilt/                     预构建产物
      a-quant-0.0.1-SNAPSHOT.jar  后端 fat jar（90MB，Java 17）
      dist/                        前端静态文件（给 nginx）
  deploy/                       Dockerfile / nginx.conf / Maven 镜像 / 文档
      DEPLOY-fnos.md               ★★ 完整部署文档，排错看第 7 节
      PROMPT-给另一台电脑的AI.md    ★ 交给另一台电脑 AI 的任务书，整段复制即可
  stock.sql                      建库脚本（37 张表）
  .env.example                   环境变量模板
  aquant-backend/ aquant-frontend/   源码（只有用源码构建版时才需要）

三步起起来
----------
  cp .env.example .env
  vi .env                                    # 至少改 MYSQL_ROOT_PASSWORD
  docker compose -f docker-compose.prebuilt.yml up -d

访问
----
  主界面        http://<NAS_IP>:8081     账号 puulsar / aquant123456
  管理后台      http://<NAS_IP>:8084/
  改端口        改 .env 里的 WEB_PORT / BACKEND_PORT

注意
----
  * 首次启动慢：aktools 要 pip 装依赖（2-5 分钟），MySQL 要初始化导入 37 张表
  * 后端启动后会自动跑全量数据同步，约 3-4 小时，期间界面数据为空是正常的
  * 需要内存 >= 4GB，磁盘 >= 30GB
  * 不要动 aktools 的 --host 0.0.0.0 —— 改成 127.0.0.1 会导致后端连不上
  * 详细排错见 deploy/DEPLOY-fnos.md 第 7 节

换一台电脑部署
--------------
  把本 zip 拷过去，交给那台电脑的 AI —— 用 deploy/PROMPT-给另一台电脑的AI.md 里的内容当指令。
  那台电脑只需要 ssh + scp，不需要装 Docker / JDK / Maven / Node。
