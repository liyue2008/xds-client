FROM openjdk:8-jre-slim
WORKDIR /app
COPY target/xds-client-1.0-SNAPSHOT.jar app.jar

# 设置时区为中国时区
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

EXPOSE 8088
ENTRYPOINT ["java", "-jar", "app.jar"] 