package actors.workflow.tasks

import actors.workflow.AWSRestartableActor
import akka.actor.Props
import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.{AmazonS3Exception, S3Object}
import org.apache.commons.io.IOUtils
import play.api.libs.json._
import play.api.libs.functional.syntax._
import utils.{AmazonS3Service, PropFactory}

case class Tag(key: String, value: String)

object Tag {
  implicit val reads: Reads[Tag] = (
    (JsPath \ "Key").read[String] and
      (JsPath \ "Value").read[String]
  )(Tag.apply _)
  implicit val writes: Writes[Tag] = (
    (JsPath \ "Key").write[String] and
      (JsPath \ "Value").write[String]
  )(unlift(Tag.unapply))
}

class StackLoader(credentials: AWSCredentialsProvider, bucketName: String) extends AWSRestartableActor with AmazonS3Service {

  import actors.workflow.tasks.StackLoader._

  override def receive: Receive = {
    case msg: LoadStack =>
      val client = s3Client(credentials)
      val stackObject: S3Object = client.getObject(bucketName, s"chadash-stacks/${msg.stackPath}.json")
      val tags = getTags(bucketName, s"chadash-stacks/${msg.stackPath}.tags.json", client)
      val stackFileJson = Json.parse(IOUtils.toByteArray(stackObject.getObjectContent))

      context.parent ! StackLoaded(stackFileJson, tags)
  }

  def getTags(bucketName: String, path: String, client: AmazonS3): Option[Seq[Tag]] = {
    try {
      val tagObject: S3Object = client.getObject(bucketName, path)
      Json.parse(IOUtils.toByteArray(tagObject.getObjectContent)).asOpt[Seq[Tag]]
    } catch {
      case _:AmazonS3Exception => None
      case e:Throwable => throw e
    }
  }
}

object StackLoader extends PropFactory {
  case class LoadStack(stackPath: String)
  case class StackLoaded(stackJson: JsValue, tags: Option[Seq[Tag]])

  override def props(args: Any*): Props = Props(classOf[StackLoader], args: _*)
}
