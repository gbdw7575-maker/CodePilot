# DSH 后端镜像：多阶段构建，非 root 运行
# 本地默认只监听 loopback；容器内通过 SERVER_ADDRESS 覆盖监听地址
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 1001 dsh
WORKDIR /app
COPY --from=build /app/target/dsh-backend-*.jar /app/dsh-backend.jar
USER dsh
ENV SERVER_ADDRESS=127.0.0.1 \
    SERVER_PORT=8080
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/dsh-backend.jar"]
