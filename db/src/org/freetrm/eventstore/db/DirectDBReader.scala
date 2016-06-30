package org.freetrm.eventstore.db

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import org.freetrm.eventstore._
import org.freetrm.eventstore.db.TopicActor.TopicOffsetInfo
import org.freetrm.eventstore.http.HttpReader
import org.freetrm.eventstore.utils.Log

import scala.concurrent.Future

/**
  * Rather than receiving all the events from the HttpReader you can go direct to the database. However
  *  you do need to be told about changes to the DB (new events). So we use HttpReader.streamNotifications for
  *  that.
  */
class DirectDBReader(reader: DBEventSourceReader, httpReader: HttpReader)
                    (implicit system: ActorSystem)
  extends EventSourceReader with Log {

  implicit val mat = ActorMaterializer()
  
  def start() {
    httpReader.streamNotifications.runForeach {
      notification =>
        notification.offsets.foreach {
          case (topic, version) =>
            reader.topicInfoActor ! TopicOffsetInfo(topic, version)
        }
    }
  }
  
  override def streamEvents(topic: Topic,
                            earliestOffsetToReadFrom: Long,
                            lastOffsetToReadTo: Option[Long]): Source[Event, NotUsed] = {
    reader.streamEvents(topic, earliestOffsetToReadFrom, lastOffsetToReadTo)
  }

  override def latestSurface(topic: Topic): Source[EventSourceEvent, NotUsed] = reader.latestSurface(topic)

  override def topicExists(topic: Topic): Future[Boolean] = reader.topicExists(topic)

  override def listTopics: Future[Seq[Topic]] = reader.listTopics

  override def maxTxnNo(topic: Topic): Future[Option[Long]] = reader.maxTxnNo(topic)

  override def streamNotifications: Source[EventNotification, NotUsed] = reader.streamNotifications

  override def close(): Unit = reader.close()

  override def maxSeqNo(topic: Topic): Future[Option[Long]] = reader.maxSeqNo(topic)
}
