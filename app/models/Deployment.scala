package models

import play.api.libs.functional.syntax._
import play.api.libs.json.{JsPath, Json, Reads}

case class Deployment(amiId: String, version: String)

object Deployment {
  implicit val reads: Reads[Deployment] = (
    (JsPath \ "ami_id").read[String] and
      (JsPath \ "version").read[String]
    )(Deployment.apply _)
  implicit val writes = Json.writes[Deployment]
}
