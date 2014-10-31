package actors

import akka.actor.{Props, ActorSystem}
import com.typesafe.config.ConfigFactory

object ChadashSystem {
  val config = ConfigFactory.load().getConfig("chadash")

  implicit val system = ActorSystem("ChadashSystem", config)

  val credentials = system.actorOf(Props[AmazonCredentials], "awsCredentials")
  credentials ! AmazonCredentials.Initialize
  val deploymentSupervisor = system.actorOf(Props[DeploymentSupervisor], "deploymentSupervisor")
}
