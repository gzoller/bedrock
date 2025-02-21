

# REST Security

Bedrock's core security protecting REST calls is comprised of 4 basic elements.

1) HTTPS - this encrypts the communication between client and server
2) OAuth Integration
3) Secret Keys - This key is used to sign tokens. Should be rotated periodically
4) Access Token - Must have a token in order to call an endpoint. Should be rotated periodically
5) Refresh Tokens - Used to refresh Access Tokens that have expired
6) Session Tokens

## HTTPS
Using HTTPS and setting up certificates has it's own documentation, and is described here:  [HTTPS Support](docs/https.md)

## OAuth Integration
OAuth is a prominent authentication protocol used widely to protect API endpoints. Bedrock integrates with OAuth providers: Google out of the box, and others such as Okta would be straightforward to integrate. Once authenticated via your OAuth provider of choice, users would have access to APIs in the Bedrock ecosystem.

As part of the OAuth protocol various tokens are returned to Bedrock: access, refresh, and id. Access tokens allow access to resources in the broader scope of resources protected by the OAuth provider. For example when authorized via Google, any Google APIs provisioned for your application will be available via the access token Google returns. Access tokens expire, so refresh tokens are also returned, which allow Bedrock to re-authorize (get a new access token) without asking the user to go through another login process. Id tokens contain some basic metadata about a user, most importantly their user id.

Bedrock takes OAuth integration a step further. We implement another layer of authentication, following the pattern of OAuth; like OAuth on top of OAuth. Why would we do that? OAuth providers vary widely in terms of flexibility to control things like access token timeout. For example Google fixes their access token timeout at 1 hour. While fine for many use cases, for financial or other highly secure data this is far too lenient. Bedrock would allow you set a much more secure timeout.

What does this mean? Think of it this way: You use your OAuth provider to prove who a user is and retrieve tokens, which we save (in case you want to access resources in the OAuth provider's world). We then issue our own access and refresh tokens, which we then use exclusively for Bedrock-protected endpoints, and which have their own configurable lifecycle.

## Secret Keys
Secret Keys are used to sign other tokens to prevent tampering. Tokens are JWT formatted, which is an encoded but not encrypted. Secret keys are used to sign the JTW token so that you can prove that the contents have not been tampered with.

One challenge we face with Secret Keys is how they can be used in a scaled, distributed environment.  
Our REST servers will be deployed as clusters of containers running on the cloud (AWS presumed). They all need  
access to the Secret Key, and they will all need access to any rotated key values. We need a central repository  
for Secret Keys. Fortunately AWS provides a Secrets Manager service. We can use a Java client to access   
Secret Keys stored in Secrets Manager across all our clustered REST servers. The Secrets Manager provides  
secure and convenient access to Secret Keys to any number of running servers.


### Secret Key Rotation
On some regular cadence Secret Keys should be rotated for security. The greater the security level the more frequent   
the rotation cadence, however rotation frequency of Secret Keys will be relatively long (30-90 days) unless  
regulations in your environment specify otherwise. AWS Secrets Manager provides a built-in facility for auto-rotation with configurable cadence.

The diagram below shows how key rotation would work in AWS:

![rotation_design](./REST_Secret_Key.png)

>NOTE: All The AWS services, lambda functions, etc. must be configured by you in AWS. Bedrock includes sample Terraform scripts to set this up, which you can use or modify to taste.

There are a number of moving pieces here. When Secrets Manager triggers key auto-rotation, a lambda function you  
provide will be kicked off. The provided lambda function, rotationLambda.py creates a new random UUID string, but it can  
be whatever you want or regulations require. The new key is then Stored in Secrets Manager, which versions  
the keys (this is important!). The lambda then sends a message to an SNS topic, which all running servers subscribe to. When this topic in SNS receives an event, propagates to each server's /sns-handler endpoint, which will then trigger each server to re-read the Secret Keys from Secrets Manager.

## Access Tokens
An Access Token is a base64-encoded, 3-part JWT string signed with an algorithm using a Secret Key.  The three parts  
are: Header, Payload, and Signature. When you design your REST APIs you will choose those endpoints you want protected, for example behind a user login, vs those that are "open" (anyone can call them). This means any caller without a valid, unexpired Bearer Authorization Token (an access token) will experience a failure upon calling a protected endpoint.

Access Tokens have a configurable expiry (application.conf). The expiration is a balance between usability   
and security.  Short expirations are more secure but means they need to be refreshed more frequently. Fortunately, Bedrock handles that complexity for you.  A short expiration means an attacker who somehow has gained access to an Access Token has very little time to take advantage of his prize before it expires. This framework uses a default of 7 minutes for Access Token Expiration.

Remember we said that the fact that Secrets Manager versions Secret Keys was important? That's because  
when Secrets Manager auto-rotates to a new key it may take a short while for that message to propagate to all servers in the cluster. That means there's some window of time where there may be valid tokens flowing around, some signed with the new current Secret Key and others signed with the previous key. Bedrock accounts for this and will attempt to decode tokens with the previous key (within a time window) if the current key fails. It maintains knowledge of both current and previous Secret Keys at all times.

## Refresh Tokens
When a user logs into the Bedrock system, along with the expected Access Token, a Refresh Token is also  
generated. This token has a (configurable) expiration lifespan equal to the maximum session duration allowed.  
For a very secure application, like a bank, this would be expected to be a small value, say 10-15 minutes. An   
e-commerce app might be longer, say 1 hour or even longer.

Refresh Tokens are a key the calling application uses to refresh an Access Token that has expired, seamlessly without requiring a user to re-authenticate. Using the Refresh Token, Bedrock will generate a new Access Token on the user's behalf.

Refresh Tokens allows us to keep relatively short timeouts on Bearer Tokens for security, without adversely  
affecting the user experience.

## Session Tokens
Session tokens are what client applications actually use to call Bedrock APIs. The actual credentials, the access and refresh tokens, never leave the server for security. A synthetic token, the Session Token, is generated and handed back to the client. The effect of this extra layer of tokens is that even if an attacker gained access to the Session Token and somehow figured out how to use it within the timeout window, the "blast radius" of damage would be limited to one user. They would not have an "open" token that allowed unfettered access to server APIs.

Stock Bedrock provides a fixed Session Token upon authentication. For added security a mechanism for rotating Session Tokens is certainly possible, although it would require implementation effort on the part of clients. They would need to carefully monitor for server responses indicating a change in Session Token and update their cookies accordingly.

## Authorization Flow - Web Applications
In Bedrock authorization follows this flow for web applications:

1. Client redirect to Bedrock /api/oauth2/login_proxy endpoint, providing 2 query parameters:
    1. redirect_location: URL to redirect to when all the OAuth handshaking is complete
    2. state: a random sequence of characters generated by the client

2. Bedrock assembles a redirect to the OAuth Provider (eg Google). Included in that redirect is an internal redirect url, which is a callback endpoint in Bedrock (/api/oauth2/callback). This establishes a handshake with the OAuth provider.
3. The user process authentication via the OAuth provider. This may include login, MFA, or other means of authentication.
4. OAuth provider calls the internal callback (/api/oauth2/callback). In this call the provider supplies access, refresh, and id tokens.
5. Bedrock generates a Session Token and creates 2 entries in cache (Redis):
    1. userId -> Session (JSON). This is how we can look up a Session given a user id. The duration of this relationship is the max session lifespan: the time, regardless of activity, we log out a user and require them to re-authenticate
    2. session token -> access token. This is the primary lookup when a user calls an API endpoint. The duration of this relationship is the inactivity logout period. This timestamp is refreshed upon each access.

## Authorization Flow - Mobile Applications
The flow for a mobile app differs in that the app is responsible for obtaining the code from the OAuth provider.

1. Mobile app open provider's OAuth UI (browser or WebView).
2. User authenticates, which redirects back to the mobile app with a code
3. The app sends this code (direct POST call--not a redirect) to Bedrock via the /api/oauth2/login_proxy, passing the code as a query parameter 'code' as a query parameter
4. Bedrock will handshake with the OAuth provider and exchange the code for tokens
5. Bedrock returns a session token to the mobile app to use for any API access

## Authorization Flow - Machine-to-Machine
For machine-to-machine interaction, the calling machine (aka requester) is presumed to have already done the OAuth handshake with the provider and comes ready with access and id tokens. The service (Bedrock) validates them with the provider's public keys and creates a session.

1. Requester calls endpoint /api/oauth2/login_proxy, passing access-token and id-token as query parameters
2. Service server (Bedrock) validates the tokens as genuine then creates a session
3. SessionDesc object is returned to caller including the session id and timeouts