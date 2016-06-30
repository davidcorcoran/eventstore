package org.freetrm.eventstore

import scala.concurrent.Future


/**
  * Write events to an 'event source'.
  * 
  * Once you've called `write`, make sure to only call `write` again once the previous future has 
  * completed successfully. The events are not idempotent so if one fails you shouldn't write the next 
  * until the reason for the failure has been resolved and you can rewrite the failed one.
  * 
  * For example if this EventSourceWriter is backed by a DB you could have a temporary failure to write to the
  * DB that shows up as a failed Future. You would then need to read from the DB (EventSourceReader), to check
  * what the last successful write was, and start writing again from after that one.
  * 
  * The event source implementations should fail if you try to write an event with the same seqNo or a seqNo
  * that is not one bigger than the previous.
  */
trait EventSourceWriter {
  def write(topic: Topic, event: Event): Future[EventVersionPair]
  
  def writeAll(events: Seq[(Topic, Event)]): Future[Seq[EventVersionPair]]
  
  def close(): Unit
}

