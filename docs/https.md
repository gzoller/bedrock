
# HTTPS / SSL

 This feature of  the framework provides the familiar "https" capability that allows secure, encrypted communication between client and server.

## Set up AWS Secret
In production this framework is designed to use AWS Secret Manager. Locally we use localstack to run AWS services locally. Run the following scripts:
```
> docker-compose up --build
```
This script does several things for you. It brings up Docker images, most notably localstack, which is a locally-running fascimile of core AWS services.
Important to us is the AWS Secrets Manager. Next, the script initializes aws locally and sets up a secret in Secrets Manager.

After this script has run, you can verify the secret worked by running:
```
> scripts/retrieve_secret.sh
```
You should see a sane response from this script if the secret worked.


## Obtain Key and Certificate Files

Key and Cert files may be obtained by a certificate vendor and this is absolutely recommended for production use. The process below is to generate a self-signed certificate for development purposes only.  At the end of the process you will have server.key and server.crt files.


# SSL Self-Signed Certificate

Bedrock's endpoints are SSL-protected by default. In production you would have certificate files (eg server.crt/server.key) that you purchased from a certificate authority, linked to your domain.

However for running locally, and especially with LocalStack, it is helpful to have a self-signed certificate. Below are the instructions for generating such a certificate. Obviously, don't run with a self-signed cert in production. This is for local development only.

## Steps to Create Certificate

1. Create a san.cnf file.  This file describes, among other things, the CN for the domains associated with the cert. The contents used for san.cnf for Bedrock, to be used with LocalStack, look like this:
```
[req]
default_bits = 2048
prompt = no
default_md = sha256
distinguished_name = dn
req_extensions = req_ext
x509_extensions = v3_ext

[dn]
C = US
ST = State
L = City
O = Organization
OU = Unit
CN = localhost

[req_ext]
subjectAltName = @alt_names

[v3_ext]
authorityKeyIdentifier=keyid,issuer
basicConstraints=CA:TRUE
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = @alt_names

[alt_names]
DNS.1 = localhost
DNS.2 = host.docker.internal
DNS.3 = bedrock
DNS.4 = localstack
DNS.5 = localhost.localstack.cloud
IP.1 = 127.0.0.1
```
2. Generate a private key
```
openssl genrsa -out server.key 2048
```
3. Geneate a Certificate Signing Request (CSR)
```
openssl req -new -key server.key -out server.csr -config san.cnf
```
4. Generate the Self-Signed Certificate
```
openssl x509 -req -in server.csr -signkey server.key -out server.crt -days 365 -extensions req_ext -extfile san.cnf
```
5. Verify the Certificate (Look for the Subject Alternative Name section in the output.)
```
openssl x509 -in server.crt -text -noout
```
6. Install the Files
   Bedrock expects server.crt and server.key to be in a /certs directory in your project. **NOTE**: Be sure /certs is in your .gitignore!

## Special Note for Macs
You'll likely want to use Swagger in a browser to exercise your endpoints. Even with a proper cert many browsers will not natively trust it. What you need to do is open up the Keychain Access app and drag your server.crt file into the list. (It will likely ask you to verify your Mac's password for this an other operations.) Then double-click that entry and find the Trust pulldown. Open this and select Always Trust. Your browsers should behave now.