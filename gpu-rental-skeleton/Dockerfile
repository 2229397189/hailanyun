FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/gpu-rental-0.1.0.jar app.jar
EXPOSE 8099
ENTRYPOINT ["java","-jar","/app/app.jar","--spring.profiles.active=docker"]
