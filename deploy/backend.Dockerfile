# AQuant 后端：Spring Boot 3 + JDK17
# 两阶段构建：maven 编译打包 -> jre 运行
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /src
COPY deploy/maven-settings.xml /root/.m2/settings.xml
COPY aquant-backend/pom.xml ./
# 预热依赖；部分插件解析失败不影响后续 package，故容错
RUN mvn -B -q dependency:go-offline -DskipTests || true
COPY aquant-backend/src ./src
RUN mvn -B clean package -DskipTests

FROM eclipse-temurin:17-jre

ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone || true

WORKDIR /app
COPY --from=build /src/target/*.jar /app/app.jar

EXPOSE 8084
ENV JAVA_OPTS="-Xms256m -Xmx1024m"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
