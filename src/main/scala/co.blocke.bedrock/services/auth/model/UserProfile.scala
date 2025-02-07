package co.blocke.bedrock
package services
package auth
package model

import zio.json.* 

//-------------------
// User Profile (from ID Token)

case class UserProfile(
  email: String,
  email_verified: Option[Boolean],
  name: String,
  picture: Option[String],
  given_name: Option[String],
  family_name: Option[String],
  userId: String = "",  // (required, but filled in after inial json parse from claim subject)
)

object UserProfile:
  implicit val codec: JsonCodec[UserProfile] = DeriveJsonCodec.gen[UserProfile]

