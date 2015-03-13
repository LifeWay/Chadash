package actors

import actors.workflow.AmazonCredentials
import akka.actor.{ActorRef, ActorSystem, DeadLetter, Props}
import com.typesafe.config.ConfigFactory
import utils.ActorFactory

object ChadashSystem {
  val config = ConfigFactory.load().getConfig("chadash")
  implicit val system = ActorSystem("ChadashSystem", config)

  val credentials       = system.actorOf(Props[AmazonCredentials], "awsCredentials")
  val userCredentials   = system.actorOf(UserCredentialsLoader.props(), "userCredentials")
  val deadLetterHandler = system.actorOf(DeadLetterHandler.props(), "deadLetterHandler")

  credentials ! AmazonCredentials.Initialize
  system.eventStream.subscribe(deadLetterHandler, classOf[DeadLetter])
}

trait DeploymentActor {
  val actor: ActorRef
}

object DeploymentActor extends DeploymentActor {
  val actor = ChadashSystem.system.actorOf(DeploymentSupervisor.props(ActorFactory), "deploymentSupervisor")
}
