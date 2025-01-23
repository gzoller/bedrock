
# REST Security

Bedrock's core security protecting REST calls is comprised of 4 basic elements. 

1) HTTPS - this encrypts the communication between client and server
2) Secret Key - This key is used to generate the Bearer Token. Should be rotated periodically
3) Bearer Token - Must have a token in order to call an endpoint. Should be rotated periodically
4) Session Tokens - Used to refresh Bearer Tokens that have expired

>Note that OAuth is also an option, and described separately if you need that capability.
HTTPS is described here: [HTTPS Support](docs/https.md)

## Secret Keys
Secret Keys are used to sign auth Bearer Tokens and can be any string value. In the sample (local) framework 
we just use a simple string, however in actual production a more complex value like a random UUID would 
be ideal. When a Bearer Token (described below) is encoded with the JWT library a Secret Key is provided
and the token is signed with the key so that the server can detect any attempt at tampering with the token.

One challenge we face with Secret Keys is how they can be used in a scaled, distributed environment.
Our REST servers will be deployed as clusters of containers running on the cloud (AWS presumed). They all need
access to the Secret Key, and they will all need access to any rotated key values. We need a central repository
for Secret Keys. Fortunately AWS provides a Secrets Manager service. We can use a Java client to access 
Secret Keys stored in Secrets Manager across all our clustered REST servers. The Secrets Manager provides
secure and convenient access to Secret Keys to any number of running servers.


## Secret Key Rotation
On some regular cadence Secret Keys should be rotated for security. The greater the security level the more frequent 
the rotation cadence, however rotation frequency of Secret Keys will be relatively long (30-90 days) unless
regulations in your environment specify otherwise. AWS Secrets Manager provides a built-in facility for auto-rotation with configurable cadence. 

The diagram below shows how key rotation would work in AWS:

![rotation_design](./REST_Secret_Key.png)

>NOTE: All The AWS services, lambda functions, etc. must be configured by you in AWS. They are not automatically provided by the Bedrock framework, but are presumed to be present.

There are a number of moving pieces here. When Secrets Manager triggers key auto-rotation, a lambda function you
provide will be kicked off. The provided lambda function, rotationLambda.py creates a new random UUID string, but it can
be whatever you want or regulations require. The new key is then Stored in Secrets Manager, which versions
the keys (this is important!). The lambda then sends a message to an SNS topic, which in turn 
trigger an event that a new key is available. EventBridge will be configured to posts an event to a configured
topic in SNS. Each server, when it starts up, will subscribe to this topic. When this topic in SNS receives an event,
propagates to each server's /sns-handler endpoint, which will then trigger each server to re-read the
Secret Keys from Secrets Manager. 

## Bearer Tokens
A Bearer Token is a base64-encoded, 3-part string signed with an algorithm using a Secret Key.  The three parts
are: Header, Payload, and Signature. This won't matter to your application--it's just a string of gibberish that 
must accompany every auth-protected HTTP request. When you design your REST APIs you will choose those 
you want protected, for example behind a user login, vs those that are "open" (anyone can call them). This means any 
caller without a Bearer Token, or an expired token, will experience a failure upon calling a REST endpoint.

Bearer Tokens have a configurable expiry (application.conf). The expiration is a balance between usability 
and security.  Short expirations are more secure but means that a relatively brief period of inactivity will log 
out users. However a short expiration also means an attacker who somehow has gained access to a Bearer 
Token has very little time to take advantage of his prize before it expires. This framework uses a default of 7 
minutes for Bearer Token Expiration. 

A sample HTTP bearer auth header looks like this:
```
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiYWRtaW4iOnRydWV9.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c
```

Remember we said that the fact that Secrets Manager versions Secret Keys was important? That's because
when Secrets Manager auto-rotates to a new key it may take "a while" (seconds, minutes,...) for that message
to propagate to all servers in the cluster. That means there's some window of time where there may be valid
tokens flowing around, some signed with the new current Secret Key and others signed with the previous key.
Bedrock accounts for this and will attempt to decode tokens with the previous key (within a time window) if
the current key fails. It maintains knowledge of both current and previous Secret Keys at all times.

## Session Tokens
When a user logs into the Bedrock system, along with the expected auth Bearer Token a Session Token is also
generated. This token has a (configurable) expiration lifespan equal to the maximum session duration allowed.
For a very secure application, like a bank, this would be expected to be a small value, say 10-15 minutes. An 
e-commerce app might be longer, say 1 hour or even longer.

Session Tokens (sometimes called Refresh Tokens) are a key the calling application uses to refresh a Bearer Token that 
has expired, seamlessly without requiring a user to re-authenticate. When a Bearer Token expires any further 
HTTP Requests will fail with an Unauthorized status. The caller to re-try the same call, this time supplying 
the SessionToken they received upon login, and if the session has not expired, and a certain amount of 
inactive time has not passed, a new Bearer Token will be generated.

Session Tokens allows us to keep relatively short timeouts on Bearer Tokens for security, without adversely
affecting the user experience.

## Authorization Flow
In Bedrock authorization follows this flow:

1. /login endpoint, given username and password.  Successful login returns the following in the Reponse body:
```
```json
{
  "sessionToken": "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzUxMiJ9.eyJzdWIiOiJib2d1c191c2VyIiwiZXhwIjoxNzM2ODc3OTgwLCJpYXQiOjE3MzY4NzA3ODB9.KQobpopJvyhLLXCkvgC2Z1C-ccvnTpmBo1J8hTIndNo-yDXVXiF04r6T8ybrNNbBWz7tcrEL3DXQTPOhk7Ui5Q",
  "authToken": "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzUxMiJ9.eyJzdWIiOiJib2d1c191c2VyIiwiZXhwIjoxNzM2ODcxMjAwLCJpYXQiOjE3MzY4NzA3ODB9.xhCp9dW-zZdZFnkCP_bQWwsRpBoCgg0pLP1ExcMQE7bORqMgc9k76EsrtSEnogbnxshgHRSB802xFazjsr4jhg"
}
```
2. Application uses the authToken for all subsequent API calls
3. The authToken ultimately expires and a status of Unauthorized will be returned for any attempted API calls
4. The calling app will retry a failed API call, this time passing the header along with the expired authToken as usual:
```
X-Session-Token: eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzUxMiJ9.eyJzdWIiOiJib2d1c191c2VyIiwiZXhwIjoxNzM2ODc3OTgwLCJpYXQiOjE3MzY4NzA3ODB9.KQobpopJvyhLLXCkvgC2Z1C-ccvnTpmBo1J8hTIndNo-yDXVXiF04r6T8ybrNNbBWz7tcrEL3DXQTPOhk7Ui5Q
Authorization: Bearer eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzUxMiJ9.eyJzdWIiOiJib2d1c191c2VyIiwiZXhwIjoxNzM2ODcxMjAwLCJpYXQiOjE3MzY4NzA3ODB9.xhCp9dW-zZdZFnkCP_bQWwsRpBoCgg0pLP1ExcMQE7bORqMgc9k76EsrtSEnogbnxshgHRSB802xFazjsr4jhg
```
5. If the session token is not expired and the current time is still within the allowed period of inactivity, a new authToken will be generated and returned in the Response Authorization header

The inactivity period works like this. Let's say Bearer Token (authToken) expiration is configured to be 7 minutes. These 
tokens expire regardless of user activity or not. Let's say we have an authToken which is now expired. Can we refresh
it? It depends. Another configuration (refresh_window_sec) defines how long *after* the Bearer Token has expired
we are allowed to refresh it. Let's say that's configured for 5 minutes. (We're presuming the Session Token has a
lifespan of longer, say 1 hour, so it's not a factor here.) This means that after login, a user has an effective period
of allowed inactivity of 7+5=12 minutes of inactivity before they are forcibly expired and must re-authenticate.
The Bearer Token's expiry of 7 minutes maintains a suitably low surface area for a potential attacker. And of course
if the user keeps sporadically using the app, without leaving it idle for more than 12 minutes, they can keep getting
refreshed Bearer Tokens up until the session expires. 

### Gotcha!
Remember earlier we discussed that when Secret Keys auto-rotate, there is a window of time when tokens
signed by old and new keys may co-exist? Whenever Bedrock detects an old signature after it has already
updated to a new Secret Key, it will automatically re-issue a new Bearer Token signed with the new current key.
This new token is returned in the Response Authentication header, just like when the app requests a token
refresh with a Session Token. What does this mean? It means applications must always check for an
Authorization header, as Bedrock may send a new Bearer Token even when the app has not requested one.
If the app fails to replace the old authToken with the new one the user may face being forcibly logged out.

## Tips  

Update secret key in Secrets Manager from command line:

```
> aws secretsmanager --endpoint-url=http://localhost:4566 update-secret --secret-id MySecretKey --secret-string "foo"
```

This creates a new version of a key. You can use the Java API to get old versions of a key and look for the two
labeled AWSCURRENT and AWSPREVIOUS. Those are the two you want to ingest to handle the "split version"
problem during the rotation window when old and new keys may be in force at the same time.