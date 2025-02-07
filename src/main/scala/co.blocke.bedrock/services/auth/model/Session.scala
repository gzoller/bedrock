package co.blocke.bedrock
package services
package auth
package model

import zio.json.* 

//-------------------
// Session

case class Session(
  profile: UserProfile,
  oauthTokens: OAuthTokens,
  roles: List[String] = List.empty[String] // placeholder until we build out role-based permissions
  )
  
object Session:
  implicit val codec: JsonCodec[Session] = DeriveJsonCodec.gen[Session]

