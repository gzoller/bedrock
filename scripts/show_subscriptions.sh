#!/bin/bash

aws sns list-subscriptions-by-topic --topic-arn arn:aws:sns:us-east-1:000000000000:SecretKeyRotation --endpoint-url=http://localhost:4566