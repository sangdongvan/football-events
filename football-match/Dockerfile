FROM openjdk:11-jre-slim
VOLUME /tmp
COPY target/*SNAPSHOT.jar /app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
EXPOSE 18081
