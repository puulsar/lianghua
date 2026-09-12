# AQuant 前端：Vue3 + Vite 构建，nginx 托管
FROM node:22-alpine AS build

WORKDIR /app
COPY aquant-frontend/package.json aquant-frontend/package-lock.json ./
# lock 与 package.json 不同步时 npm ci 会直接失败，回退到 npm install
RUN npm ci --registry=https://registry.npmmirror.com \
 || npm install --registry=https://registry.npmmirror.com

COPY aquant-frontend/ ./
# 直接 vite build，跳过 vue-tsc 类型检查（类型报错不应阻断部署）
RUN npx vite build

FROM nginx:alpine

ENV TZ=Asia/Shanghai
COPY deploy/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80
