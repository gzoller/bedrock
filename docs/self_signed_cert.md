
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