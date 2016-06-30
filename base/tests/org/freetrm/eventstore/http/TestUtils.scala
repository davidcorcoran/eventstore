package org.freetrm.eventstore.http

import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue

import akka.NotUsed
import akka.actor.{ActorSystem, Props}
import akka.stream.actor.{ActorPublisher, ActorPublisherMessage}
import akka.stream.scaladsl.Source
import org.freetrm.eventstore._
import org.freetrm.eventstore.http.ESTestUtils.{BufferEventSourceReader, TestEventSourceReader}
import org.scalamock.scalatest.MockFactory
import org.scalatest.FunSuiteLike
import org.scalatest.concurrent.ScalaFutures

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

object TestUtils {
  def choosePort: Int = {
    val socket = new ServerSocket(0)
    val port = socket.getLocalPort
    socket.close()
    port
  }
}

/**
  * It can be handy to have a description of all the tests in a file at the top of the file. It gives
  * you an overview of the suite of tests without having to scroll through them all. 
  * 
  * Use like:
  *   documentTests(
  *     "empty test" testsThat
  *       """
  *         The returned collection is empty if X happens. 
  *       """
  *       )
  */
trait FunSuiteDoc {
  self: FunSuiteLike =>

  implicit final class Doc[A](private val self: A) {
    @inline def testsThat [B](y: B): Tuple2[A, B] = Tuple2(self, y)
    @inline def tests [B](y: B): Tuple2[A, B] = Tuple2(self, y)
  }
  
  def documentTests(tests: (String, String)*): Unit = {
    val documentationTestName = "Documentation"
    test(documentationTestName) {
      val allTests = testNames
      tests.foreach {
        case (testName, testing) =>
          require(allTests.contains(testName), s"Test '$testName' does not exists in tests: $allTests")
      }
      val undocumented = allTests - documentationTestName -- tests.map(_._1)
      if(undocumented.nonEmpty) {
        val names = undocumented.map(s => "'" + s + "'").mkString(", ")
          throw new Exception(s"No documentation for test(s): $undocumented")
      }
    }
  }
}

trait ESTestUtils {
  mf: MockFactory =>

  def esReader(topic: Topic, maxSeq: Option[Long]) = {
    new TestEventSourceReader {
      override def streamEvents(topic: Topic,
                                earliestOffsetToReadFrom: Long,
                                lastOffsetToReadTo: Option[Long]): Source[Event, NotUsed] = {
        maxSeq.map {
          m =>
            require(m > 0, "Invalid: " + m)
            Source.fromIterator(() => Iterator.range(0, m.toInt).map {
              seqNo =>
                if (seqNo % 10 == 0) {
                  EventTransactionStart(EventVersionPair(seqNo, seqNo / 10))
                } else if (seqNo % 9 == 0) {
                  EventTransactionEnd(EventVersionPair(seqNo, seqNo / 10))
                } else {
                  EventSourceEvent(EventVersionPair(seqNo, seqNo / 10), seqNo.toString, "cv" + seqNo, "data" + seqNo)
                }
            })
        }.getOrElse(Source.empty)
      }

      override def topicExists(t: Topic): Future[Boolean] = Future.successful(t == topic)

      override def listTopics: Future[Seq[Topic]] = Future.successful(Seq(Topic("topic")))

      override def close(): Unit = {}

      override def maxSeqNo(topic: Topic): Future[Option[Long]] = Future.successful(maxSeq)

      override def maxTxnNo(topic: Topic): Future[Option[Long]] = Future.successful(maxSeq.map(m => m / 10 + 1))
    }
  }

  def inMemESWriter(topic: Topic): (EventSourceWriter, ConcurrentLinkedQueue[Event]) = {
    val events = new ConcurrentLinkedQueue[Event]()
    var inTransaction = false

    val writer = new EventSourceWriter {
      var currSeqNo = -1l
      var currTxnNo = -1l

      private def write(create: (Long, Long) => Event) = {
        currSeqNo += 1
        events.add(create(currSeqNo, currTxnNo))
        Future.successful(EventVersionPair(currSeqNo, currTxnNo))
      }

      override def writeAll(events: Seq[(Topic, Event)]): Future[Seq[EventVersionPair]] = ???

      override def write(topic: Topic, event: Event): Future[EventVersionPair] = event match {
        case EventTransactionStart(version) =>
          require(!inTransaction, "(Test exception) Already in a transaction")
          inTransaction = true
          currTxnNo += 1
          write((s, t) => EventTransactionStart(EventVersionPair(s, t)))
        case EventTransactionEnd(version) =>
          require(inTransaction, "(Test exception) Not in a transaction")
          inTransaction = false
          write((s, t) => EventTransactionEnd(EventVersionPair(s, t)))
        case EventSourceEvent(version, key, contentHash, data) =>
          require(inTransaction, "(Test exception) Not in a transaction")
          write((s, t) => EventSourceEvent(EventVersionPair(s, t), key, contentHash, data))
        case EventInvalidate(version) => sys.error("Not testing")
      }

      override def close(): Unit = {}
    }
    writer -> events
  }

  def esReader(topic: Topic, events: ConcurrentLinkedQueue[Event])(implicit system: ActorSystem) = {
    new BufferEventSourceReader(topic, events)
  }

}

object ESTestUtils {


  class BufferActorPublisher(topic: Topic, earliestOffsetToReadFrom: Long,
                             lastOffsetToReadTo: Option[Long], events: ConcurrentLinkedQueue[Event])
    extends ActorPublisher[Event] {
    
    var lastSent = -1l

    override def receive = {
      case ActorPublisherMessage.Request(_) | 'poll =>
        var eventsList = events.toArray().map(_.asInstanceOf[Event]).toList.filter(_.version.seqNo > lastSent)
        while (demandForMore && eventsList.nonEmpty) {
          val event = eventsList.head
          eventsList = eventsList.tail
          onNext(event)
          lastSent = event.version.seqNo
          lastOffsetToReadTo.foreach {
            last =>
              if(event.version.seqNo == last)
                onCompleteThenStop()
          }
        }
        this.context.system.scheduler.scheduleOnce(FiniteDuration(1, "ms"), self, 'poll)(this.context.dispatcher)
        
      case ActorPublisherMessage.Cancel | ActorPublisherMessage.SubscriptionTimeoutExceeded =>
        context.stop(self)
    }
    private def demandForMore: Boolean = isActive && totalDemand > 0
  }
  
  class BufferEventSourceReader(topic: Topic, events: ConcurrentLinkedQueue[Event])(implicit system: ActorSystem) 
    extends TestEventSourceReader {

    override def streamEvents(topic: Topic,
                              earliestOffsetToReadFrom: Long,
                              lastOffsetToReadTo: Option[Long]): Source[Event, NotUsed] = {
      
      val props = Props(new BufferActorPublisher(topic, earliestOffsetToReadFrom, lastOffsetToReadTo, events))
      Source.fromPublisher(ActorPublisher[Event](system.actorOf(props, name = s"BufferActorPublisher-${topic.name}")))
    }

    override def topicExists(t: Topic): Future[Boolean] = Future.successful(t == topic && events.nonEmpty)

    override def listTopics: Future[Seq[Topic]] = Future.successful(if(events.nonEmpty) Seq(topic) else Seq.empty)

    override def close(): Unit = {}

    override def maxSeqNo(topic: Topic) = Future.successful(events.lastOption.map(_.version.seqNo))

    override def maxTxnNo(topic: Topic) = Future.successful(events.lastOption.map(_.version.txnNo))

    override def latestSurface(topic: Topic): Source[EventSourceEvent, NotUsed] = {
      var surface = Map[String, EventSourceEvent]()
      events.foreach {
        case e: EventSourceEvent =>
          surface += e.key -> e
        case _ =>
      }
      Source(surface.valuesIterator.toList.sortWith((a, b) => a.version.seqNo < b.version.seqNo))
    }
  }

  class TestEventSourceReader extends EventSourceReader with ScalaFutures {
    override def streamEvents(topic: Topic,
                              earliestOffsetToReadFrom: Long,
                              lastOffsetToReadTo: Option[Long]): Source[Event, NotUsed] = ???
    override def topicExists(topic: Topic): Future[Boolean] = ???
    override def listTopics: Future[Seq[Topic]] = ???
    override def close(): Unit = ???
    override def maxSeqNo(topic: Topic): Future[Option[Long]] = ???
    override def maxTxnNo(topic: Topic): Future[Option[Long]] = ???
    override def streamNotifications: Source[EventNotification, NotUsed] = ???
    override def latestSurface(topic: Topic): Source[EventSourceEvent, NotUsed] = ???
  }
}
