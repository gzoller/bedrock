#!/bin/bash

docker-compose up -d
printf "foo\nbar\nus-east-1\n\n" | aws configure

# prime Secrets Manager with a previous entry so there's always an AWSCURRENT and AWSPREVIOUS
aws --endpoint-url=http://localhost:4566 secretsmanager create-secret --name MySecretKey --secret-string "originalKey"
aws --endpoint-url=http://localhost:4566 secretsmanager update-secret --secret-id MySecretKey --secret-string "secretKey"
