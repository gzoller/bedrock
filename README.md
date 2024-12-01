# bedrock
Starting point for REST service

## Features

* ZIO HTTP REST endpoints
* Injectable data store (allows for unit testing w/o real database)
* Switchable Swagger support for prod/non-prod
    Compile with: ```sbt -Dprod=true "clean;compile;package;assembly"``` to disable Swagger for production deployment
* Test examples -- how to test a REST service
* HTTPS support
* Auth using tokens
* Get access to the encoded user id from jtw token in handler

TODO:
* Auto-generate Swagger config

## SSL

You must generate a self-signed key (for development, otherwise a certificate vendor) stored in file keystore.jks. Then do the following commands to generate the 
required server.key and server.crt files:

Generate private key:
```
openssl genrsa -out server.key 2048
```

Create a test file openssl-san.cnf with this content:
```
[ req ]
default_bits       = 2048
prompt             = no
default_md         = sha256
distinguished_name = dn
req_extensions     = req_ext

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

Now put server.key and server.crt in your project's resources directory.

For production the SSL cert will verify and you should have no problem accessing https URLs. For dev though, with a self-signed cert, your
system is likely to choke on a number of colorful errors.  So do this:

Adding the Certificate to the Trusted Store
For macOS:
  1. Open Keychain Access with cmd-space and enter Key in the prompt
  2. From Finder, drag server.cfg into the 'System' keychain
  5. Find the certificate in the list, right-click on it, and select "Get Info".
  6. Expand the "Trust" section and set "When using this certificate" to "Always Trust".
  7. Close the window and enter your password to confirm the changes.

For Linux:
Copy the server.crt file to /usr/local/share/ca-certificates/ directory:
```
sudo cp server.crt /usr/local/share/ca-certificates/
```
Then
```
sudo update-ca-certificates
```

Accessing the Swagger Page
After adding the certificate to the trusted store, try accessing the Swagger page again at https://localhost:8073/docs/openapi.

NOTE: This repo contains server.crt and server.key files, which is something you would NEVER want to do in real life.
These demo files are keyed to localhost only and are of no security value beyond testing this framework!