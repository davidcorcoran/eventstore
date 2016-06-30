package org.freetrm.eventstore.http

import akka.NotUsed
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import akka.http.scaladsl.server.AuthenticationFailedRejection
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout
import de.heikoseeberger.akkasse.{EventStreamUnmarshalling, ServerSentEvent}
import org.freetrm.eventstore._
import org.freetrm.eventstore.http.ESTestUtils.TestEventSourceReader
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.{FunSuite, Matchers}
import spray.json._
import fommil.sjs.FamilyFormats._

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

class EventStoreHttpServiceTest extends FunSuite with FunSuiteDoc 
  with ScalaFutures with ScalatestRouteTest with Matchers with MockFactory {
  
  implicit val defaultPatience = PatienceConfig(timeout = Span(15, Seconds), interval = Span(50, Millis))

  val topic = Topic("mytopic")

  val cookie = "mycookie"
  val creds = BasicHttpCredentials("user", cookie)
  implicit val timeoutMS = new Timeout(FiniteDuration(100, "ms"))


  documentTests(
    "invalid get returns 404" testsThat
      """
         We get a HttpResponse with a 404 code if we ask for a non-existant URI.
      """,
    "invalid credentials fail" testsThat
      """
         We get an AuthenticationFailedRejection for bad creds
      """,
    "post writer" testsThat
      """
         Checks that when we POST an Event to the eventstore we get a corresponding write call on the underlying
         writer.
      """,
    "can consume from event store" testsThat
      """
         A GET for /es/consume/... creates an eventsource with the correct events.
      """,
    "return duplicate message on duplicate" testsThat
      """
         If we write a duplicate event the result will be of type EventStoreResponseDuplicate with the
         version set to the previous (duplicate) event.
      """,
    "failing post returns an error" testsThat
      """
         When there's a failure (like the underlying writing layer failing to write) the failure
         is popagated back as an error to the caller
      """
  )

  test("invalid get returns 404") {
    val webService = new EventStoreHttpServer(mock[EventSourceWriter], mock[EventSourceReader], cookie)

    Get("/") ~> addCredentials(creds) ~> webService.route ~> check {
      handled shouldEqual false
    }
  }

  test("invalid credentials fail") {
    val id = "myid"
    val message = "{}"
    val hash = "myhash"

    val webService = new EventStoreHttpServer(mock[EventSourceWriter], mock[EventSourceReader], "different cookie")

    Post(s"/es/$topic/$id/$hash", message) ~> addCredentials(creds) ~> webService.route ~> check {
      rejection.getClass shouldBe classOf[AuthenticationFailedRejection]
    }
  }

  test("post writer") {
    val id = "myid"
    val message = "{}"
    val hash = "myhash"
    val result = 10

    val writer = mock[EventSourceWriter]
    val version = EventVersionPair(result, 0)
    val event = EventSourceEvent(version, id, hash, message)
    
//    (writer.write _).expects(topic, event).returns(Future.successful(version))
    val webService = new EventStoreHttpServer(writer, emptyReader, cookie)

    val json = (event: Event).toJson.prettyPrint
    Post(s"/es/publish/${topic.name}", json) ~> addCredentials(creds) ~> webService.route ~> check {
      responseAs[EventStoreResponse] shouldBe EventStoreResponseOK(version)
    }
  }

  test("can consume from event store") {
    import EventStreamUnmarshalling._

    val from = 3
    val to = 17
    val results: Vector[EventSourceEvent] = (0l to to).toVector.map {
      i => EventSourceEvent(EventVersionPair(i, 0), s"key$i", "cv", s"data$i")
    }

    val reader = new TestEventSourceReader {
      override def streamEvents(topic: Topic,
                                earliestOffsetToReadFrom: Long,
                                lastOffsetToReadTo: Option[Long]): Source[EventSourceEvent, NotUsed] = {
        val data = results.slice(earliestOffsetToReadFrom.toInt, lastOffsetToReadTo.map(_.toInt).getOrElse(to) + 1)
        Source.fromIterator(() => data.toIterator)
      }
    }

    val webService = new EventStoreHttpServer(writer = null, reader, cookie)

    Seq(Some(to), None).map {
      maybeTo =>
        val toStr = maybeTo.getOrElse("")
        Get(s"/es/consume/${topic.name}/$from/$toStr") ~> addCredentials(creds) ~> webService.route ~> check {
          val res = responseAs[Source[ServerSentEvent, Any]].map {
            case sse =>
              sse.data.parseJson.convertTo[Event]
          }
          res.runWith(Sink.seq).futureValue shouldEqual results.slice(from, to + 1)
        }
    }
  }

  test("return duplicate message on duplicate") {
    val topic = "mytopic"
    val id = "myid"
    val message = "{}"
    val hash = "myhash"

    val event = EventSourceEvent(EventVersionPair(0l, 0l), id, hash, message)
    
    val reader = new TestEventSourceReader {
      val messages = Vector(event)

      override def streamEvents(topic: Topic,
                                earliestOffsetToReadFrom: Long,
                                lastOffsetToReadTo: Option[Long]): Source[EventSourceEvent, NotUsed] = {
        Source.fromIterator(() => messages.find(_.version.seqNo >= earliestOffsetToReadFrom).toIterator)
      }

      override def maxSeqNo(topic: Topic) = Future.successful(Some(0))

      override def topicExists(topic: Topic): Future[Boolean] = Future.successful(true)
    }

    val webService = new EventStoreHttpServer(mock[EventSourceWriter], reader, cookie)
    
    val json = (event: Event).toJson.prettyPrint
    Post(s"/es/publish/$topic", json) ~> addCredentials(creds) ~> webService.route ~> check {
      responseAs[EventStoreResponse] shouldBe EventStoreResponseDuplicate(EventVersionPair(0l, 0l))
    }
  }

  test("failing post returns an error") {
    val id = "myid"
    val message = "{}"
    val hash = DeDuplicator.contentHash(message)

    val writer = mock[EventSourceWriter]
    val exceptionMessage = "Test exception (ignore if you see in test output)"

    val event = EventSourceEvent(EventVersionPair(0l, 0l), id, hash, message)
//    (writer.write _).expects(topic, event).returns(Future.failed(new Exception(exceptionMessage)))

    val webService = new EventStoreHttpServer(writer, emptyReader, cookie)

    val json = (event: Event).toJson.prettyPrint
    Post(s"/es/publish/${topic.name}", json) ~> addCredentials(creds) ~> webService.route ~> check {
      val error = responseAs[EventStoreResponse]
      error shouldBe EventStoreResponseError(
        s"Failed to publish to event store: $topic: $event",
        exceptionMessage
      )
    }
  }

  private def emptyReader = {
    val emptyReader = mock[EventSourceReader]
    (emptyReader.maxSeqNo _).expects(topic).returns(Future.successful(None)).anyNumberOfTimes()
    (emptyReader.topicExists _).expects(topic).returning(Future.successful(false)).anyNumberOfTimes()
    emptyReader
  }
}
