package actors

import akka.actor.{DeadLetter, Props, ActorSystem}
import com.typesafe.config.ConfigFactory

object ChadashSystem {
  val config = ConfigFactory.load().getConfig("chadash")

  implicit val system = ActorSystem("ChadashSystem", config)

  val credentials = system.actorOf(Props[AmazonCredentials], "awsCredentials")
  credentials ! AmazonCredentials.Initialize

  val deploymentSupervisor = system.actorOf(DeploymentSupervisor.props(), "deploymentSupervisor")
  val deadLetterHandler = system.actorOf(DeadLetterHandler.props(), "deadLetterHandler")

  system.eventStream.subscribe(deadLetterHandler, classOf[DeadLetter])
}
