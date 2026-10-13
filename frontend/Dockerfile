# DSH 前端镜像：Node 构建 → nginx 提供静态资源并反代本地后端
# 容器内通过 BACKEND_HOST 指向宿主机后端（Docker Desktop 下默认 host.docker.internal）
FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-fund --no-audit
COPY . .
RUN npm run build

FROM nginx:1.29-alpine
COPY --from=build /app/dist /usr/share/nginx/html
# 官方镜像会对 /etc/nginx/templates/*.template 执行 envsubst（仅替换已定义的环境变量）
COPY default.conf.template /etc/nginx/templates/default.conf.template
ENV BACKEND_HOST=host.docker.internal
EXPOSE 80
