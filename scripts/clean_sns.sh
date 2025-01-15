#!/bin/bash

aws sns list-subscriptions-by-topic --topic-arn arn:aws:sns:us-east-1:000000000000:SecretKeyRotation --query 'Subscriptions[].SubscriptionArn'  --endpoint-url=http://localhost:4566 --output text | while read subscriptionArn; do
  aws sns unsubscribe --subscription-arn "$subscriptionArn"  --endpoint-url=http://localhost:4566
done