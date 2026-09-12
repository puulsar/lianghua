# AQuant 数据服务：aktools（AkShare 的 HTTP 转发）
# 上游 aktools 的 CLI 默认 host=127.0.0.1，在容器里必须显式改成 0.0.0.0，
# 否则同网络的 backend 容器连不上（表现为“外部API调用异常”）。
FROM python:3.12-slim

ENV TZ=Asia/Shanghai \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_INDEX_URL=https://mirrors.aliyun.com/pypi/simple/

RUN apt-get update \
 && apt-get install -y --no-install-recommends tzdata curl \
 && ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone \
 && rm -rf /var/lib/apt/lists/*

# 与开发机实测可用的版本对齐
RUN pip install "aktools==0.0.91" akshare

EXPOSE 8080

CMD ["python", "-m", "aktools", "--host", "0.0.0.0", "--port", "8080"]
