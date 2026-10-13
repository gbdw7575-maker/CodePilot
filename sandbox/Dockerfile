# DSH 命令沙箱镜像（DSH-004）
# 约束依据 doc/Agent项目开发文档.md §15.1：非 root、资源限制由 `docker run` 参数施加、
# 默认无网络（--network none）、不挂载 Docker socket 与主机凭据，任务副本挂为 /workspace。
#
# 构建：docker build -t dsh-sandbox:latest DSH/sandbox
FROM eclipse-temurin:21-jdk-jammy

# 项目命令常用工具；不加入 ssh/凭据相关包
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        git bash coreutils grep findutils ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# 官方静态 ripgrep 14.1.1，构建上下文内固定归档与 SHA-256，避免运行时宿主 rg。
COPY vendor/ripgrep-14.1.1-x86_64-unknown-linux-musl.tar.gz /tmp/ripgrep.tar.gz
RUN echo '4cf9f2741e6c465ffdb7c26f38056a59e2a2544b51f7cc128ef28337eeae4d8e  /tmp/ripgrep.tar.gz' | sha256sum -c - \
    && tar -xzf /tmp/ripgrep.tar.gz -C /usr/local/bin --strip-components=1 --wildcards '*/rg' \
    && chmod 755 /usr/local/bin/rg \
    && rm /tmp/ripgrep.tar.gz

# 固定非 root 用户，uid/gid=1000 与挂载副本属主映射对齐
RUN groupadd --gid 1000 dsh \
    && useradd --uid 1000 --gid 1000 --create-home --shell /bin/bash dsh

WORKDIR /workspace
USER dsh

# 容器只执行挂载进来的任务副本命令；入口由 SandboxRunner 显式给出
CMD ["bash"]
