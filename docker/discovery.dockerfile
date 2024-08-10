FROM eclipse-temurin:21-jre
RUN mkdir /opt/app
COPY ../discovery/target/HACOGroupChat-discovery-1.0.jar /opt/app
ENTRYPOINT ["java", "--enable-preview", "-jar", "/opt/app/HACOGroupChat-discovery-1.0.jar"]