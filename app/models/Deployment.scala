package models

import play.api.libs.functional.syntax._
import play.api.libs.json.{JsPath, Json, Reads}

case class Deployment(amiId: String, userData: Option[String])

object Deployment {
  implicit val reads: Reads[Deployment] = (
    (JsPath \ "ami_id").read[String] and
      (JsPath \ "userData").readNullable[String]
    )(Deployment.apply _)
  implicit val writes = Json.writes[Deployment]
}
