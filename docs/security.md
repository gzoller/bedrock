
# REST Security

There are 3 pieces of security for a REST call:
1) HTTPS - this encrypts the communication between client and server

2) Bearer Token - Encoded state information (eg user id) locked with a Secret Key

3) Secret Key - This key is used to generate the Bearer Token. It should be rotated periodically

HTTPS is described here: [HTTPS Support](https.md)

## Bearer Tokens
A Bearer Token is an encrypted string that encodes a payload. It is encrypted using a Secret Key, 
described below. Most REST calls (all REST calls in this example framework) should likely be
protected by a Bearer Token to ensure the caller is properly logged in and authorized to call any
given endpoint. This means any caller without a Bearer Token, or an expired token, will experience
a failure upon calling a REST endpoint.

Bearer Tokens have a configurable expiry. The expiration is a balance between usability and security.
Short expirations are more secure but mean that a period of inactivity will log out a user. However
a short expiration also means an attacker who somehow has gained access to a Bearer Token has
very little time to learn what to do with it before it expires. This framework uses a default of 7 minutes
for Bearer Token Expiration. Bearer Token regeneration is an important way to seamlessly keep valid
users logged in. We will cover that subject after discussing Secret Keys

## Secret Keys
Secret Keys can be any string value. In the sample (local) framework we just use a simple string, however
it actual production use a random string value would be ideal, for example a random UUID. The Secret Key
is used to encode and encrypt the Bearer Tokens. Secret Keys should likewise be rotated periodically. The
greater the security level the more frequent the rotation cadence, however rotation frequency of Secret Keys 
will be much less than the rotation of Bearer Tokens.

One challenge we immediately face with Secret Keys is how they can be used in a scaled, distributed environment.
Our REST servers will be deployed as clusters of containers running on the cloud (AWS presumed). They all need
access to the Secret Key, and they will all need access to any rotated key values. We need a central repository
for Secret Keys.

Fortunately AWS provides a Secrets Manager service. We can use a Java client to access Secret Keys stored
in Secrets Manager across all our clustered REST servers.

## Secret Key Rotation
As mentioned above, Secret Keys, like Bearer Tokens, should be rotated on some regular cadence. AWS
Secrets Manager provides a built-in facility for auto-rotation with configurable cadence. For a secure
environment some guidelines suggest rotating keys every 30-90 days. Your environment may have regulations
that stipulate the frequency, otherwise use your own judgement.

The diagram below shows how key rotation would work in AWS:

![rotation_design](./REST_Secret_Key.png)

There are a number of moving pieces here. When Secrets Manager triggers key rotation, a lambda function you
provide will be kicked off. We suggest a simple function that just creates a new random UUID string, but it can
be whatever you want, or regulations require. The new key is then Stored in Secrets Manager, which versions
the keys (this is important!). AWS Eventbridge can be used to trigger an event that a new key is available and 
all the REST servers can subscribe to that event and re-acquire the new key.

There's a big "but..." here. Communication of the new key is not instantaneous, so it is possible/likely that you 
may see REST calls with the new key being sent to clustered servers that have not yet updated themselves--still
using the old key. What do we do then? We may also see servers that have been updated to the new key receive
REST calls using the now-old key, which technically is still valid. How do we handle that?

Remember that Secrets Manager versions key values? The answer to both of these questions is that the REST 
servers request two keys: the current one and the previous one. That way if we use the new key to try to 
decode a token that was encoded using the old key we can fall back to try using the previous key, which should
work. We can maintain a timestamp in the key as well so we can invalidate the previous key after a certain
amount of time has passed.

## Bearer Token Rotation
is set to be required on all REST endpoints for security. The login process
creates the initial bearer token, which expires after so many minutes of inactivity (defaults
to 5 min in this framework). During normal use, this token is regenerated before it expires,
again for security. If someone manages to obtain the bearer token they have <5 min to figure
out how to benefit from that knowledge before the token refreshes and is therefore invalid.

  

Secret Key rotation occurs in AWS Secrets Manager. It can happen once every 7 days since the

Bearer Token is alive only for up to 5 minutes, limiting exposure. Care must be taken during the

rotation, however, which can take up to a minute to finish in AWS. This means some requests to

get a secret may obtain the old secret during the transition. Further it means you may have some

old and new secrets floating around out there causing problems. For example let's say a new token

is generated using a just-rotated secret. Then that token happens to be used on a server that hasn't

yet picked up the new secret. It tries to decode that token with the (now old) secret and fails--even

though that token is perfectly valid. The reverse can happen: token generated with old secret gets

passed to a fresh server with the new secret--rejects the token even though it is technically still

valid.

  

Update secret key in Secrets Manager from command line:

```

> aws secretsmanager --endpoint-url=http://localhost:4566 update-secret --secret-id MySecretKey --secret-string "foo"

```

  

This creates a new version of a key. You can use the Java API to get old versions of a key and look for the two

labeled AWSCURRENT and AWSPREVIOUS. Those are the two you want to ingest to handle the "split version"

problem during the rotation window when old and new keys may be in force at the same time.