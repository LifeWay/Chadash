package actors.workflow.aws

import akka.actor.{Actor, ActorLogging, ActorRef}
import com.typesafe.config.Config
import play.api.libs.json.JsValue

import scala.collection.JavaConversions._

/**
 * One AWS workflow actor will be created per deployment request. This actor's job is to read in the configuration for
 * the flow. Upon reading in the configuration the following steps shall occur:
 *
 * 1. Create ActorRefs for each of the defined steps
 * 2. Setup a Sequence ordered of the ActorRefs to the order defined in the configuration.
 * 3. Start the sequence.
 * 4. Upon each step completing, any return data from that step must be stored by this actor.
 * 5. Further steps may have a configuration that requests that return data from a given step as defined in the config
 *
 */
class AWSWorkflow extends Actor with ActorLogging {

  import actors.workflow.aws.AWSWorkflow._

  var steps = Seq.empty[ActorRef]
  var stepResultData = Map.empty[String, JsValue]

  override def receive: Receive = {

    case x: Deploy => {

      val stepOrder: Seq[String] = x.appConfig.getStringList("stepOrder").to[Seq]

      //TODO: fold over the steps, creating the supervisor actors for each step.

      //TODO: tell the sender we are starting.

      //TODO: become a workflow in progress

      //TODO: start working through each step, upon receiving a completed message, go to the next step


      //stepOrder.foldLeft(Seq.empty[ActorRef])()
    }
  }
}

object AWSWorkflow {

  case class Deploy(appConfig: Config, data: JsValue)

  case class StartStep(previousCallsData: Map[String, JsValue], appData: JsValue)

  case object StepCompleted

}