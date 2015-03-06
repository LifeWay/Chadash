package models.Event

import play.api.libs.EventSource.{EventDataExtractor, EventNameExtractor}

case class Event(name: String, data: String)

object Event {
  implicit val nameExtractor = EventNameExtractor[Event](e => Some(e.name))
  implicit val dataExtractor = EventDataExtractor[Event](e => e.data)
}