# syntax=docker/dockerfile:1

# --- build stage: compile sources ---------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY src ./src
RUN javac -encoding UTF-8 \
      -cp "src/libs/BioReader.jar:src/libs/Stemmer.jar" \
      -d out \
      src/indexer/Indexer.java src/queryeval/*.java \
 && mkdir -p dist \
 && jar cfm dist/server.jar src/manifest_server.txt -C out .

# --- runtime stage: build index + serve ---------------------------------
FROM eclipse-temurin:21-jre AS run
WORKDIR /app
ENV CP="out:src/libs/BioReader.jar:src/libs/Stemmer.jar"

COPY --from=build /app/out ./out
COPY --from=build /app/src/libs ./src/libs
COPY Stopwords ./Stopwords
COPY dataset ./dataset
COPY topics.xml ./topics.xml
COPY openapi.yaml ./openapi.yaml

# Build the inverted index + LSA embeddings inside the image so the absolute
# document paths stored in the index match the container filesystem.
RUN java -cp "$CP" indexer.Indexer

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD wget -qO- http://localhost:8080/health || exit 1
CMD ["sh", "-c", "java -cp \"$CP\" queryeval.SearchServer"]
