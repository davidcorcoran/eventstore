package org.freetrm.eventstore.db

import java.util.UUID

import akka.NotUsed
import akka.actor._
import akka.pattern.ask
import akka.stream.actor.{ActorPublisher, ActorPublisherMessage}
import akka.stream.scaladsl.Source
import akka.util.Timeout
import org.freetrm.eventstore._
import org.freetrm.eventstore.db.TopicActor._
import org.freetrm.eventstore.utils.Log

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal
import scala.util.{Random, Failure, Success}

case class DBEventSourceReader(dbReader: DBTopicReader, topicInfoActor: ActorRef)(implicit val system: ActorSystem)
  extends EventSourceReader with Log {
  
  implicit val askTimeout = new Timeout(FiniteDuration(2, "s"))

  override def streamEvents(topic: Topic,
                            earliestOffsetToReadFrom: Long,
                            lastOffsetToReadTo: Option[Long]): Source[Event, NotUsed] = {

    val props = Props(new EventPublisher(dbReader, topicInfoActor, topic, earliestOffsetToReadFrom, lastOffsetToReadTo))
    val name = s"EventPublisher-${topic.name}"
    Source.fromPublisher(ActorPublisher[Event](system.actorOf(props, name)))
  }

  override def streamNotifications: Source[EventNotification, NotUsed] = {
    val props = Props(new EventNotificationPublisher(topicInfoActor))
    Source.fromPublisher(ActorPublisher[EventNotification](system.actorOf(props)))
  }

  override def latestSurface(topic: Topic): Source[EventSourceEvent, NotUsed] = dbReader.latestSurface(topic)

  override def close(): Unit = {
    dbReader.close()
  }

  override def topicExists(topic: Topic): Future[Boolean] =
    (topicInfoActor ? QueryOffsetForTopic(topic)).mapTo[Option[EventVersionPair]].map(_.nonEmpty)

  override def listTopics: Future[Seq[Topic]] =
    (topicInfoActor ? QueryOffsets).mapTo[TopicOffsets].map(_.offsets.keys.toSeq)

  override def maxTxnNo(topic: Topic): Future[Option[Long]] =
    (topicInfoActor ? QueryOffsetForTopic(topic)).mapTo[Option[EventVersionPair]].map(_.map(_.txnNo))

  override def maxSeqNo(topic: Topic): Future[Option[Long]] =
    (topicInfoActor ? QueryOffsetForTopic(topic)).mapTo[Option[EventVersionPair]].map(_.map(_.seqNo))
}

class EventPublisher(reader: DBTopicReader,
                     topicInfo: ActorRef,
                     topic: Topic,
                     earliestOffsetToReadFrom: Long,
                     lastOffsetToReadTo: Option[Long])
  extends ActorPublisher[Event] with ActorLogging {

  private case object Poll

  private var buffer: Vector[Event] = Vector()
  private var fetchFrom: Long = earliestOffsetToReadFrom
  private var lastSent: Option[Long] = None
  private var outstandingRequest: Boolean = false
  private var maxSeqNo = -1l

  override def preStart(): Unit = topicInfo ! RegisterInterest(Some(topic))

  override def postStop(): Unit = topicInfo ! UnregisterInterest(Some(topic))

  override def receive = {
    case ActorPublisherMessage.Request(_) | Poll =>
      flush()
      if (demandForMore) {
        requestEvents()
      }
    case ActorPublisherMessage.Cancel | ActorPublisherMessage.SubscriptionTimeoutExceeded =>
      context.stop(self)
      
    case TopicOffsetInfo(`topic`, EventVersionPair(seqNo, _)) =>
      maxSeqNo = seqNo
      self ! Poll

    case ActorShutdown(reason) =>
      log.info(s"TopicInfoActor has shut down, stopping: $reason")
      onCompleteThenStop()
  }

  private def flush() = try {
    val lastOffsetToSend = lastOffsetToReadTo.getOrElse(Int.MaxValue.toLong)
    val demand = totalDemand.min(lastOffsetToSend - lastSent.getOrElse(0l) + 1)
    val toSend: Int = demand.min(Int.MaxValue.toLong).toInt
    val (eventsToSend, remainder) = buffer.splitAt(toSend)

    buffer = remainder
    eventsToSend.foreach {
      event =>
        onNext(event)
        lastSent = Some(event.version.seqNo)
        if (event.version.seqNo == lastOffsetToSend)
          onCompleteThenStop()
    }
  } catch {
    case NonFatal(e) =>
      log.error(e, "Failed flushing to subscriber: " + e.getMessage)
      onErrorThenStop(e)
  }

  private def requestEvents(): Unit = {
    if (!outstandingRequest && fetchFrom < maxSeqNo) {
      outstandingRequest = true
      val requestFuture = reader.fetch(topic, fetchFrom, lastOffsetToReadTo = None)

      requestFuture.onComplete {
        case Success(res) =>
          buffer ++= res
          fetchFrom = res.last.version.seqNo + 1
          outstandingRequest = false
          self ! Poll
        case Failure(ex) =>
          onErrorThenStop(ex)
      }(context.system.dispatcher)
    }
  }

  private def demandForMore: Boolean = isActive && totalDemand > 0
}

class EventNotificationPublisher(topicInfo: ActorRef)
  extends ActorPublisher[EventNotification] with ActorLogging {

  private case object Poll

  var nextSend: Option[Map[Topic, EventVersionPair]] = None

  override def preStart(): Unit = topicInfo ! RegisterInterest(None)

  override def postStop(): Unit = topicInfo ! UnregisterInterest(None)
  
  override def receive = {
    case ActorPublisherMessage.Request(_) | Poll =>
      if(demandForMore && nextSend.nonEmpty) {
        onNext(EventNotification(nextSend.get))
        nextSend = None
      }
      
    case ActorPublisherMessage.Cancel | ActorPublisherMessage.SubscriptionTimeoutExceeded =>
      context.stop(self)
    
    case TopicOffsets(offsets) =>
      nextSend = Some(offsets)
      self ! Poll

    case ActorShutdown(reason) =>
      log.info(s"TopicInfoActor has shut down, stopping: $reason")
      onCompleteThenStop()
  }

  private def demandForMore: Boolean = isActive && totalDemand > 0
}
