package actors

import akka.actor.{Actor, ActorLogging, DeadLetter, Props}

class DeadLetterHandler extends Actor with ActorLogging {
  override def receive: Receive = {
    case DeadLetter(msg, from, to) =>
      msg.getClass.getTypeName match {
        case "akka.dispatch.sysmsg.Terminate" => ()
        case _ => log.warning(s"Non-terminating dead-letter received: MSG: $msg FROM: $from TO: $to");
      }
  }
}

object DeadLetterHandler {
  def props(): Props = Props(new DeadLetterHandler())
}
