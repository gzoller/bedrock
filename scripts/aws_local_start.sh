#!/bin/bash

docker-compose up -d
printf "foo\nbar\nus-east-1\n\n" | aws configure
aws --endpoint-url=http://localhost:4566 secretsmanager create-secret --name MySecretKey --secret-string "secretKey"
