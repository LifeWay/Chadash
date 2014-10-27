package actors

import akka.actor.{Props, ActorSystem}
import com.typesafe.config.ConfigFactory

object ChadashSystem {
  val ananConfig = ConfigFactory.load().getConfig("chadash")

  implicit val system = ActorSystem("ChadashSystem", ananConfig)

  val deploymentSupervisor = system.actorOf(Props[DeploymentSupervisor], "deploymentSupervisor")
}
