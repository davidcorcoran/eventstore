package org.freetrm.eventstore

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.stream.scaladsl.Source
import akka.util.Timeout
import org.freetrm.eventstore.DeDuplicator.{WriteResult, SingleTopicDeDuplicator}
import org.freetrm.eventstore.http.ESTestUtils.TestEventSourceReader
import org.freetrm.eventstore.http.FunSuiteDoc
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.{FunSuite, Matchers}

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.{Random, Try}

class DeDuplicatorTest extends FunSuite with FunSuiteDoc 
  with ScalaFutures with ScalatestRouteTest with Matchers with MockFactory {
  implicit val defaultPatience = PatienceConfig(timeout = Span(2, Seconds), interval = Span(5, Millis))

  documentTests(
    "de-duplicates" testsThat
      """
        When you write duplicate events to the de-duplicator they don't get written through to the underlying writer. 
      """,
    "de dup only loads once per topic" testsThat
      """
        The de-duplicator only loads the history of events the first time you write to it.
      """,
    """will reload the deduplicator if it fails""" testsThat
      """
        The loading code will recover from failing to load the history of events by trying again on the next
        write. The Future for the first write will indicate the failure.
      """,
    "the SingleTopicDeDuplicator is created on demand and contains the right events" testsThat
      """
        When you write the first event for a topic the de-duplicator is loaded. Also tests that
        the events loaded are correct.
      """
  )
  
  
  test("de-duplicates") {
    val reader = mock[EventSourceReader]
    val topic = Topic("topic")
    val cv = "version"
    (reader.topicExists _).expects(topic).returns(Future.successful(true)).once()
    val topicMax = Some(100l)
    (reader.maxSeqNo _).expects(topic).returns(Future.successful(topicMax)).once()
    
    val messages = Source((0l to topicMax.get).map { i =>
      EventSourceEvent(EventVersionPair(i, 0), s"key$i", "hash", "data")
    })
    (reader.streamEvents _).expects(topic, 0, topicMax).returns(
      messages
    ).once()

    val writer = new EventSourceWriter {
      var events = Set[Event]()
      override def write(topic: Topic, event: Event): Future[EventVersionPair] = {
        events.contains(event) shouldBe false
        events += event
        Future.successful(event.version)
      }

      override def writeAll(events: Seq[(Topic, Event)]): Future[Seq[EventVersionPair]] = ???

      override def close(): Unit = ???
    }
    
    val dedup = new DeDuplicator(reader, writer)

    (0l to 150l).foreach { i =>
      val event = EventSourceEvent(EventVersionPair(i, 0), s"key${i % 110}", "hash", "data")
      val duplicate = {i % 110} <= topicMax.get
      dedup.writeWithDuplicateCheckResult(topic, event).futureValue.wasDuplicate shouldBe duplicate
    }

    dedup.writeWithDuplicateCheckResult(
      topic, EventSourceEvent(EventVersionPair(200, 0), s"key", "hash", "data")
    ).futureValue.wasDuplicate shouldBe false
    dedup.writeWithDuplicateCheckResult(
      topic, EventSourceEvent(EventVersionPair(201, 0), s"key", "hash1", "data")
    ).futureValue.wasDuplicate shouldBe false
    dedup.writeWithDuplicateCheckResult(
      topic, EventSourceEvent(EventVersionPair(202, 0), s"key", "hash", "data")
    ).futureValue.wasDuplicate shouldBe false
    dedup.writeWithDuplicateCheckResult(
      topic, EventSourceEvent(EventVersionPair(203, 0), s"key", "hash", "data")
    ).futureValue.wasDuplicate shouldBe true
  }

  test("de dup only loads once per topic") {
    val reader = mock[EventSourceReader]
    val topic1 = Topic("topic1")
    val topic2 = Topic("topic2")
    val hash = "version"

    val topic1Max = Some(3l)
    val topic2Max = Some(4l)
    
    (reader.topicExists _).expects(topic1).returns(Future.successful(true)).once()
    (reader.topicExists _).expects(topic2).returns(Future.successful(true)).once()
    (reader.maxSeqNo _).expects(topic1).returns(Future.successful(topic1Max)).once()
    (reader.maxSeqNo _).expects(topic2).returns(Future.successful(topic2Max)).once()

    val messages = Source((0l to 3l).map(i => EventSourceEvent(EventVersionPair(i, 0), s"key$i", "hash", "data")))
    (reader.streamEvents _).expects(topic1, 0, topic1Max).returns(
      messages
    ).once()
    (reader.streamEvents _).expects(topic2, 0, topic2Max).returns(
      messages
    ).once()

    val dedup = new DeDuplicator(reader, writer = null) // writer can be null as we're trying to write duplicates below
    dedup.writeWithDuplicateCheckResult(topic1, 
      EventSourceEvent(EventVersionPair(5, 0), "key1", "hash", "data")).futureValue shouldEqual
      WriteResult(wasDuplicate = true, version = EventVersionPair(1, 0))
    
    dedup.writeWithDuplicateCheckResult(topic2, 
      EventSourceEvent(EventVersionPair(6, 0), "key2", "hash", "data")).futureValue shouldEqual
      WriteResult(wasDuplicate = true, version = EventVersionPair(2, 0))
  }

  test("will reload the deduplicator if it fails") {
    val reader = mock[EventSourceReader]
    val topic = Topic("topic")
    val cv = "version"
    (reader.topicExists _).expects(topic).returns(Future.successful(true)).twice()
    val topicMax = Some(3l)
    (reader.maxSeqNo _).expects(topic).returns(Future.successful(topicMax)).twice()

    (reader.streamEvents _).expects(topic, 0, topicMax).throws(
      new Exception("Expected test fail - ignore")
    ).once()


    val dedup = new DeDuplicator(reader, writer = null)
    val event = EventSourceEvent(EventVersionPair(1, 0), "key1", "hash", "data")
    intercept[Exception] {
      dedup.writeWithDuplicateCheckResult(topic, event).futureValue
    }.getMessage should include("Expected test fail")

    // The DeDuplicator invalidates the broken loader using the onFailure method
    // of the future which just finished above. However we can end up running the next
    // write (below) before the onFailure method is called if we don't have a small sleep
    Thread.sleep(250)

    val messages = Source((0l to 3l).map(i => EventSourceEvent(EventVersionPair(i, 0), s"key$i", "hash", "data")))
    (reader.streamEvents _).expects(topic, 0, topicMax).returns(
      messages
    ).once()
    
    dedup.writeWithDuplicateCheckResult(topic, event).futureValue shouldEqual WriteResult(wasDuplicate = true, event.version)
  }

  test("the SingleTopicDeDuplicator is created on demand and contains the right events") {
    val fromTo = Seq(
      None -> None, Some(0) -> Some(0), Some(0) -> Some(3), Some(0) -> Some(99),
      Some(7) -> Some(99), Some(35231) -> Some(55232), Some(123) -> Some(51232)
    )
    val topic = Topic("topic")

    def data(from: Long, to: Long) = {
      val data = "message"
      val cv = "version"
      (from to to).map { i => EventSourceEvent(EventVersionPair(i, 0), "key" + i, cv, data) }.toVector
    }
    
    def expected(from: Long, to: Long) = {
      val dedup = new SingleTopicDeDuplicator(topic)
      data(from, to).foreach {
        case EventSourceEvent(version, key, cv, data) => dedup.update(key, cv, version)
      }
      dedup
    }

    fromTo.foreach {
      case (from, to) =>
        val reader = new TestEventSourceReader {
          override def streamEvents(topic: Topic,
                                    earliestOffsetToReadFrom: Long,
                                    lastOffsetToReadTo: Option[Long]): Source[Event, NotUsed] = {

            Source.fromIterator(() => data(earliestOffsetToReadFrom, lastOffsetToReadTo.get).toIterator)
          }
          override def maxSeqNo(topic: Topic) = Future.successful(to.map(_.toLong))
          override def topicExists(topic: Topic): Future[Boolean] = Future.successful(true)
          override def close(): Unit = {}
        }

        val deDuplicator = DeDuplicator.loadDeDup(reader, topic).futureValue
        if (from.isEmpty && to.isEmpty)
          deDuplicator shouldEqual emptyDeDuplicator(topic)
        else
          deDuplicator shouldEqual expected(from.get, to.get)
    }
  }

  def emptyDeDuplicator(topic: Topic): SingleTopicDeDuplicator = new SingleTopicDeDuplicator(topic)
}
