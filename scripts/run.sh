#!/bin/bash

# Ensure SLF4J providers are present
export JAVA_OPTS="-Dlog.level=DEBUG"

java $JAVA_OPTS -jar target/scala-3.5.2/bedrock-assembly-0.1.0-SNAPSHOT.jar