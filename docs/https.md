
# HTTPS / SSL

 This feature of  the framework provides the familiar "https" capability that allows secure, encrypted communication between client and server.

## Set up AWS Secret
In production this framework is designed to use AWS Secret Manager. Locally we use localstack to run AWS services locally. Run the following scripts:
```
> scripts/aws_local_start.sh
```
This ascript does several things for you. It brings up Docker images, most notably localstack, which is a locally-running fascimile of core AWS services.
Important to us is the AWS Secrets Manager. Next, the script initializes aws locally and sets up a secret in Secrets Manager.

After this script has run, you can verify the secret worked by running:
```
> scripts/retrieve_secret.sh
```
You should see a sane response from this script if the secret worked.

> NOTE: You'll need to start your local environment for the framework by re-running the aws_local_start.sh script every time you restart Docker.

## Obtain Key and Certificate Files

Key and Cert files may be obtained by a certificate vendor and this is absolutely recommended for production use. The process below is to generate a self-signed certificate for development purposes only.  At the end of the process you will have server.key and server.crt files.

Generate private key:

```

openssl genrsa -out server.key 2048

```

  

Create a test file openssl-san.cnf with this content:

```

[ req ]

default_bits = 2048

prompt = no

default_md = sha256

distinguished_name = dn

req_extensions = req_ext

  

[ dn ]

CN = localhost

  

[ req_ext ]

subjectAltName = @alt_names

  

[ alt_names ]

DNS.1 = localhost

IP.1 = 127.0.0.1

```

  

Finally create the server.crt file

```

openssl req -new -x509 -days 365 -key server.key -out server.crt -config openssl-san.cnf -extensions req_ext

```

  
## Using Your Key/Cert Files
Put server.key and server.crt in your project's resources directory.

For production the SSL cert will verify and you should have no problem accessing https URLs. For dev though, using a self-signed cert, your browser may choke with a number of colorful errors. So do
the following to tell your system that your self-signed cert is OK:

### Adding the Certificate to the Trusted Store (Development)

For macOS:

1. Open Keychain Access with cmd-space and enter Key in the prompt

2. From Finder, drag server.cfg into the 'System' keychain

5. Find the certificate in the list, right-click on it, and select "Get Info".

6. Expand the "Trust" section and set "When using this certificate" to "Always Trust".

7. Close the window and enter your password to confirm the changes.

  

For Linux:

Copy the server.crt file to /usr/local/share/ca-certificates/ directory:

```

> sudo cp server.crt /usr/local/share/ca-certificates/

```

Then

```

> sudo update-ca-certificates

```

  

Accessing the Swagger Page

After adding the certificate to the trusted store, start the server and try accessing the Swagger page again at https://localhost:8073/docs/openapi.

> NOTE: This repo contains server.crt and server.key files, which is something you would NEVER want to do in real life.

These demo files are keyed to localhost only and are of no security value beyond testing this framework!