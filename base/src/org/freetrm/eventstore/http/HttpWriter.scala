package org.freetrm.eventstore.http

import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.zip.GZIPOutputStream

import akka.NotUsed
import akka.actor.Actor.Receive
import akka.actor.{ActorLogging, Actor, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{Authorization, BasicHttpCredentials, HttpEncodings, `Content-Encoding`}
import akka.http.scaladsl.settings.ClientConnectionSettings
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream._
import akka.stream.scaladsl._
import org.freetrm.eventstore._
import org.freetrm.eventstore.http.HttpWriter.{WriteEvent, WriteEventData, WriteTransactionEnd, WriteTransactionStart}
import org.freetrm.eventstore.utils.Log
import spray.json.JsonParser.ParsingException
import spray.json._
import fommil.sjs.FamilyFormats._
import scala.concurrent.Future
import scala.util.control.NonFatal

case class FailedToSendException(cause: Throwable, topic: Topic, key: String, seq: Int) extends Exception(cause)

class HttpWriter(host: String, port: Int, username: String, cookie: String)
                (implicit system: ActorSystem)
  extends Log {

  private val credentials = Authorization(BasicHttpCredentials(username, cookie))
  private val gzip = `Content-Encoding`(HttpEncodings.gzip)
  private val headers = List(credentials, gzip)
  
  implicit val mat = ActorMaterializer.create(
    ActorMaterializerSettings.create(system).withSyncProcessingLimit(1), system
  )
  implicit val dispatcher = system.dispatcher

  /**
    * Publish events to the event store. In order to know which EventVersionPair to start with you
    * will need to cal HttpReader.maxSeqNo and HttpReader.maxTxnNo
    */
  def publish(topic: Topic, events: Source[Event, NotUsed]): Source[EventStoreResponse, NotUsed] = {
    
    // We seem to need a kill switch to stop the flow when there's an error. I'm not sure why
    // as throwing should stop the flow but doesn't.
    val killSwitch = KillSwitches.shared("HttpPostKillSwitch")

    events
      .via(killSwitch.flow)
      .via(eventToHttpRequest(topic))
      .via(connectionFlow)
      .via(checkResponseStatus(killSwitch))
      .via(parseResponse(killSwitch))
  }

  private val connectionFlow: Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]] = {
    val settings = ClientConnectionSettings(system)
    Http().outgoingConnection(host, port, settings = settings)
  }

  private def eventToHttpRequest(topic: Topic) = {
    Flow[Event].map {
      case e: Event =>
        val uri = s"/es/publish/${enc(topic)}"

        // Not sure why the (e: Event) is necessary but it is. 
        val data = (e: Event).toJson.prettyPrint
        HttpRequest(method = HttpMethods.POST, uri = uri, entity = deflate(data)).withHeaders(headers)
    }
  }

  private def checkResponseStatus(killSwitch: SharedKillSwitch) = {
    Flow[HttpResponse].map {
      resp =>
        if (resp.status != StatusCodes.OK) {
          val defaultError = new scala.Exception(s"Error of type ${resp.status}. No error message.")
          val reason = resp.entity match {
            case e: HttpEntity.Strict =>
              try {
                val string = e.data.decodeString("UTF-8")
                val error = string.parseJson.convertTo[EventStoreResponseError]
                new scala.Exception(s"Error of type ${resp.status}: ${error.message}, ${error.exception}")
              } catch {
                case NonFatal(e) =>
                  log.warn("Failed to deserialise error message", e)
                  defaultError
              }
            case _ =>
              defaultError
          }
          killSwitch.abort(reason) // throwing doesn't kill the flow
          throw reason
        } else {
          resp
        }
    }
  }

  private def parseResponse(killSwitch: SharedKillSwitch) = {
    // mapAsync(1) - we use withSyncProcessingLimit(1) above so having more than 1 wouldn't make a difference.
    Flow[HttpResponse].mapAsync(1) {
      case response =>
        Unmarshal(response.entity).to[String]
          .map {
            json =>
              try {
                val resp = json.parseJson.convertTo[EventStoreResponse]
                resp
              } catch {
                case e: ParsingException =>
                  val msg = s"Failed to publish event, result: $json"
                  val exception = new scala.Exception(msg)
                  log.error(msg)
                  killSwitch.abort(exception) // throwing doesn't kill the flow
                  throw exception
              }
          }
    }
  }
  
  private def enc(s: String) = URLEncoder.encode(s, "UTF-8")
  private def enc(t: Topic) = URLEncoder.encode(t.name, "UTF-8")

  private def deflate(txt: String) = {
    val arrOutputStream = new ByteArrayOutputStream()
    val zipOutputStream = new GZIPOutputStream(arrOutputStream)
    zipOutputStream.write(txt.getBytes)
    zipOutputStream.close()
    arrOutputStream.toByteArray
  }
}

object HttpWriter {
  sealed trait WriteEvent

  case object WriteTransactionStart extends WriteEvent
  case object WriteTransactionEnd extends WriteEvent
  case class WriteEventData(key: String, contentHash: String, data: String) extends WriteEvent
}
