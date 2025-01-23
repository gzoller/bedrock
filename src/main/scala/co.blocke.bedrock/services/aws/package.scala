package co.blocke.bedrock
package services
package aws

import zio.http.codec.*
import zio.schema.*
import zio.json.*

import java.time.Instant


// AWS SNS Message

case class SnsMessage(
  Type: String,
  SignatureVersion: String = "1",
  Signature: String = "",
  SigningCertURL: String = "",
  SubscribeURL: Option[String] = None,
  Message: Option[String] = None,
  MessageId: Option[String] = None,
  TopicArn: Option[String] = None,
  Token: Option[String] = None,
  Timestamp: Option[String] = None
) {
    def validationMap = Map(
      "Type" -> Type,
      "SubscribeURL" -> SubscribeURL.getOrElse(""),
      "Message" -> Message.getOrElse(""),
      "MessageId" -> MessageId.getOrElse(""),
      "TopicArn" -> TopicArn.getOrElse(""),
      "Token" -> Token.getOrElse(""),
      "Timestamp" -> Timestamp.getOrElse(""),
      "SignatureVersion" -> SignatureVersion,
      "Signature" -> Signature,
      "SigningCertURL" -> SigningCertURL
    )
}
object SnsMessage:
  implicit val schema: Schema[SnsMessage] = DeriveSchema.gen[SnsMessage]
  implicit val snsCodec: ContentCodec[SnsMessage] = HttpCodec.content[SnsMessage]


// AWS SNS Confirmed ARN
case class SnsArn( SubscriptionArn: String)
object SnsArn:
  implicit val schema: Schema[SnsArn] = DeriveSchema.gen[SnsArn]
  implicit val snsCodec: ContentCodec[SnsArn] = HttpCodec.content[SnsArn]
  implicit val codec: JsonCodec[SnsArn] = DeriveJsonCodec.gen[SnsArn]

case class Key(version: String, value: String, instantCreated: Instant)

case class KeyBundle( currentTokenKey: Key, previousTokenKey: Option[Key], sessionKey: Key )
