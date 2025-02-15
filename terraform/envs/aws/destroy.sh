#!/bin/sh

terraform destroy

# AWS keeps secrets "marked for destruction", which prevents them from being re-created unless
# we do this...
aws secretsmanager delete-secret --secret-id "BedrockAccessKey" --force-delete-without-recovery
aws secretsmanager delete-secret --secret-id "BedrockSessionKey" --force-delete-without-recovery
