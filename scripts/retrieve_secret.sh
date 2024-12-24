#!/bin/bash

curl -X POST \
  http://localhost:4566 \
  -H "Content-Type: application/x-amz-json-1.1" \
  -H "X-Amz-Target: secretsmanager.GetSecretValue" \
  -d '{
    "SecretId": "MySecretKey"
  }'
