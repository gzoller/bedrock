#!/bin/bash

apt-get update && apt-get install -y jq
curl -O https://releases.hashicorp.com/terraform/1.5.7/terraform_1.5.7_linux_amd64.zip && \
    unzip terraform_1.5.7_linux_amd64.zip -d /usr/local/bin/ && \
    chmod +x /usr/local/bin/terraform && \
    rm terraform_1.5.7_linux_amd64.zip
pip install terraform-local

#wget https://releases.hashicorp.com/terraform/1.5.7/terraform_1.5.7_linux_amd64.zip
#unzip terraform_1.5.7_linux_amd64.zip -d /usr/local/bin/
#chmod +x /usr/local/bin/terraform