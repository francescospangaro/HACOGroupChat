FROM eclipse-temurin:21-jre
RUN mkdir /opt/app
COPY ../peer/target/HACOGroupChat-peer-2.0.jar /opt/app
RUN apt update -y && apt install -y libxrender1 libxtst6 libxi6
ENTRYPOINT ["java", "--enable-preview", "-jar", "/opt/app/HACOGroupChat-peer-2.0.jar"]
