package org.freetrm.eventstore.http

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.coding.{Gzip, NoCoding}
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{BasicHttpCredentials, HttpChallenge, HttpCredentials}
import akka.http.scaladsl.server._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Source}
import de.heikoseeberger.akkasse.{EventStreamMarshalling, ServerSentEvent}
import fommil.sjs.FamilyFormats._
import org.freetrm.eventstore._
import org.freetrm.eventstore.utils._
import spray.json._

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.control.NonFatal



/**
  * @param cookie Erlang style 'cookie' that ensures we're allowed to use this service.
  */
class EventStoreHttpServer(writer: EventSourceWriter,
                           reader: EventSourceReader,
                           cookie: String)
                          (implicit system: ActorSystem)
  extends Directives with Log {


  private val dedup = new DeDuplicator(reader, writer)
  private implicit val executionContext: ExecutionContextExecutor = system.dispatcher
  private implicit val mat = ActorMaterializer()

  private def withGzipSupport = {
    (decodeRequestWith(NoCoding) | decodeRequestWith(Gzip))
  }

  private def authenticator(cred: Option[HttpCredentials]): Future[AuthenticationResult[String]] = {
    // the user is just informational, we only really care that the 'password' matches the server cookie.
    Future {
      cred match {
        case Some(BasicHttpCredentials(user, `cookie`)) => Right(user)
        case _ => Left(HttpChallenge("Basic", "freetrm"))
      }
    }
  }

  implicit def rejectionHandler: RejectionHandler =
    RejectionHandler.newBuilder()
      .handle { case x ⇒ sys.error("Unhandled rejection: " + x) }
      .handleNotFound {
        complete((NotFound, "The requested resource could not be found."))
      }
      .result()


  def flow: Flow[HttpRequest, HttpResponse, NotUsed] = {
    RouteResult.route2HandlerFlow(route)
  }

  val route: Route = {
    import EventStreamMarshalling._

    post {
      withGzipSupport {
        pathPrefix("es") {
          authenticateOrRejectWithChallenge(authenticator(_)) {
            user =>
              path("publish" / Segment) {
                (topic) =>
                  entity(as[String]) {
                    data =>
                      val event = data.parseJson.convertTo[Event]
                      val store = writeToEventStore(Topic(topic), event)
                      complete(ToResponseMarshallable.apply(store))
                  }
              }
          }
        }
      }
    } ~
      get {
        withGzipSupport {
          pathPrefix("es") {
            authenticateOrRejectWithChallenge(authenticator(_)) {
              user =>
                pathPrefix("consume") {
                  path(Segment / LongNumber / LongNumber.?) {
                    (topic, earliestOffsetToReadFrom, lastOffsetToReadTo) =>
                      complete {
                        consume(Topic(topic), earliestOffsetToReadFrom, lastOffsetToReadTo)
                      }
                  }
                } ~
                pathPrefix("consumeNotifications") {
                  complete {
                    consumeNotifications
                  }
                } ~
                pathPrefix("topicExists") {
                  path(Segment) {
                    (topic) =>
                      complete {
                        topicExists(Topic(topic))
                      }
                  }
                } ~
                pathPrefix("surface") {
                  path(Segment) {
                    (topic) =>
                      complete {
                        latestSurface(Topic(topic))
                      }
                  }
                } ~
                pathPrefix("maxTxnNo") {
                  path(Segment) {
                    (topic) =>
                      complete {
                        maxTxnNo(Topic(topic))
                      }
                  }
                } ~
                pathPrefix("maxSeqNo") {
                  path(Segment) {
                    (topic) =>
                      complete {
                        maxSeqNo(Topic(topic))
                      }
                  }
                } ~
                pathPrefix("listTopics") {
                  complete {
                    listTopics
                  }
                }
            }
          }
        }
      }
  }

  def writeToEventStore(topic: Topic, event: Event): Future[HttpResponse] = {
    ???
//    execAndHandle(
//      () => dedup.writeWithDuplicateCheckResult(topic, event).map {
//        case result if !result.wasDuplicate =>
//          response(OK, EventStoreResponseOK(result.version))
//        case result =>
//          response(OK, EventStoreResponseDuplicate(result.version))
//      },
//      e =>
//        s"Failed to publish to event store: $topic: $event"
//    )
  }


  def consume(topic: Topic,
              earliestOffsetToReadFrom: Long,
              lastOffsetToReadTo: Option[Long]): Source[ServerSentEvent, NotUsed] = {

    reader
      .streamEvents(topic, earliestOffsetToReadFrom, lastOffsetToReadTo)
      .map {
        e => ServerSentEvent(e.toJson.prettyPrint)
      }
      .keepAlive(1.second, () => {
        ServerSentEvent.heartbeat
      })
  }
  
  def consumeNotifications: Source[ServerSentEvent, NotUsed] = {
    reader
      .streamNotifications
      .map {
        e => ServerSentEvent(e.toJson.prettyPrint)
      }
      .keepAlive(1.second, () => {
        ServerSentEvent.heartbeat
      })
  }

  def latestSurface(topic: Topic): Source[ServerSentEvent, NotUsed] = {
    reader
      .latestSurface(topic)
      .map {
        e => ServerSentEvent(e.toJson.prettyPrint)
      }
      .keepAlive(1.second, () => {
        ServerSentEvent.heartbeat
      })
  }
  
  def topicExists(topic: Topic): Future[HttpResponse] = {
    toHttpResponse(reader.topicExists(topic), s"Failed with topicExists($topic)")
  }

  def listTopics: Future[HttpResponse] = {
    toHttpResponse(reader.listTopics, s"Failed with listTopics")
  }

  def maxTxnNo(topic: Topic): Future[HttpResponse] = {
    toHttpResponse(reader.maxTxnNo(topic), s"Failed with maxTxnNo($topic)")
  }

  def maxSeqNo(topic: Topic): Future[HttpResponse] = {
    toHttpResponse(reader.maxSeqNo(topic), s"Failed with maxSeqNo($topic)")
  }

  private def execAndHandle(f: () => Future[HttpResponse], errorMessage: Throwable => String): Future[HttpResponse] = {
    try {
      f().recover {
        case NonFatal(e) =>
          val msg = errorMessage(e)
          log.error(msg, e)
          response(InternalServerError, EventStoreResponseError(msg, e.getMessage))
      }
    } catch {
      case NonFatal(e) =>
        // We shouldn't be here unless we've written some bad code. The call to f should give a failed
        // future, not throw and exception.
        val msg = errorMessage(e)
        log.error("Bad code path: " + msg, e)
        Future.successful(
          response(InternalServerError, EventStoreResponseError(msg, e.getMessage))
        )
    }
  }

  private def response(status: StatusCode, resp: EventStoreResponse) = {

    val json = (resp: EventStoreResponse).toJson.prettyPrint
    HttpResponse(status = status, entity = HttpEntity(MediaTypes.`application/json`, json))
  }

  private def toHttpResponse[T](result: Future[T], errorMessage: String)
                               (implicit writer: spray.json.JsonWriter[T]): Future[HttpResponse] = {
    result.map {
      case res =>
        HttpResponse(status = OK, entity = HttpEntity(MediaTypes.`application/json`, res.toJson.prettyPrint))
    }.recover {
      case NonFatal(e) =>
        log.error(errorMessage, e)
        response(InternalServerError, EventStoreResponseError(errorMessage, e.getMessage))
    }
  }
}
