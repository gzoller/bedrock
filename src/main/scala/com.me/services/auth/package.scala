package com.me
package services
package auth

import java.time.Instant
import zio.schema.{DeriveSchema, Schema}

case class BadCredentialError(message: String)
case class GeneralFailure(message: String)

object BadCredentialError:
  implicit val schema: Schema[BadCredentialError] = DeriveSchema.gen
object GeneralFailure:
  implicit val schema: Schema[GeneralFailure] = DeriveSchema.gen

type AuthToken = Option[String]

case class Session(userId: String)

case class Key(version: String, value: String, instantCreated: Instant)
