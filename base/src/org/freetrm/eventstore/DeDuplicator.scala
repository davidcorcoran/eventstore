package org.freetrm.eventstore

import java.security.MessageDigest
import java.util.concurrent.{ConcurrentHashMap, FutureTask}

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.util.Timeout
import org.freetrm.eventstore.DeDuplicator.{SingleTopicDeDuplicator, WriteResult}
import org.freetrm.eventstore.utils.{ConcurrentUtils, Log}

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success}
import scala.util.control.NonFatal


class DeDuplicator(reader: EventSourceReader, writer: EventSourceWriter)
                  (implicit system: ActorSystem) extends EventSourceWriter with Log {
  
  private val dedup = new ConcurrentHashMap[Topic, FutureTask[Future[SingleTopicDeDuplicator]]]
  import scala.concurrent.ExecutionContext.Implicits.global
  
  
//
//  override def write(topic: Topic, event: Event): Future[EventVersionPair] = {
//    writeWithDuplicateCheckResult(topic, event).map(_.version)
//  }
//
//  override def writeAll(events: Seq[(Topic, Event)]): Future[Seq[EventVersionPair]] = {
//    Future.sequence(events.map(e => write(e._1, e._2)))
//  }
//
//  def writeWithDuplicateCheckResult(topic: Topic, event: Event): Future[WriteResult] = {
//    val future = try {
//      deDuplicator(topic).flatMap {
//        case topicDeDuplicator =>
//          event match {
//            case EventSourceEvent(version, key, contentHash, data) =>
//              topicDeDuplicator.previousVersion(key, contentHash) match {
//                case Some(previousVersion) =>
//                  Future.successful(WriteResult(wasDuplicate = true, previousVersion))
//                case _ =>
//                  try {
//                    writer.write(topic, event).map {
//                      case _ =>
//                        topicDeDuplicator.update(key, contentHash, version)
//                        WriteResult(wasDuplicate = false, version)
//                    }
//                  } catch {
//                    case NonFatal(e) => Future.failed(e)
//                  }
//              }
//            case _ =>
//              writer.write(topic, event).map(WriteResult(false, _))
//          }
//      }
//    } catch {
//      case NonFatal(e) => Future.failed(e)
//    }
//    future.onFailure {
//      case ex =>
//        log.error(s"Failed to write data for topic: $topic", ex)
//        dedup.remove(topic)
//    }
//    future
//  }

  override def sink: Sink[(Topic, Event), NotUsed] = ???

  override def close(): Unit = {
    dedup.clear()
    writer.close()
  }

  private def deDuplicator(topic: Topic): Future[SingleTopicDeDuplicator] = {
    ConcurrentUtils.putIfAbsent(dedup, topic, DeDuplicator.loadDeDup(reader, topic))
  }
}

object DeDuplicator extends utils.Log {
  
  case class WriteResult(wasDuplicate: Boolean, version: EventVersionPair)
  
  def contentHash(content: String): String = {
    val digest = MessageDigest.getInstance("MD5").digest(content.getBytes)
    digest.map("%02x".format(_)).mkString
  }
  
  case class SingleTopicDeDuplicator(topic: Topic) {

    private val mapOfKeyToHashAndVersion = new ConcurrentHashMap[CharSequence, (CharSequence, EventVersionPair)]()
    
    def previousVersion(key: String, contentHash: String): Option[EventVersionPair] = {
      mapOfKeyToHashAndVersion.get(key) match {
        case (hash, version) if hash == contentHash => Some(version)
        case _ => None
      }
    }

    def update(key: String, contentHash: String, version: EventVersionPair): EventVersionPair = {
      mapOfKeyToHashAndVersion.put(key, (contentHash, version)) match {
        case (previousValue, previousSeq) if previousValue == contentHash =>
          log.warn(s"You wrote the same version again for ($key, $contentHash, $version), previous: $previousSeq")
          version
        case _ =>
          version
      }
    }
  }

  def loadDeDup(reader: EventSourceReader, topic: Topic)(implicit system: ActorSystem): Future[SingleTopicDeDuplicator] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    implicit val mat = ActorMaterializer()

    log.info(s"De-duplication load for topic: '$topic'")

    def loadAll(latest: Option[Long]): Future[SingleTopicDeDuplicator] = {
      val future = latest match {
        case Some(to) =>
          log.info(s"Load de-duplication data for topic $topic (max seq $to).")
          implicit val fetchTimeoutMS = new Timeout(FiniteDuration(100, "ms"))

          val dedup = new SingleTopicDeDuplicator(topic)
          
          reader.streamEvents(topic, 0l, Some(to)).map {
            case EventSourceEvent(version, key, contentHash, _)=>
              dedup.update(key, contentHash, version)
            case _ =>
          }.runWith(Sink.ignore).map {
            case _ => dedup
          }
          
        case _ => 
          Future.successful(new SingleTopicDeDuplicator(topic))
      }
      future.onComplete {
        case Success(_) => log.info(s"Load de-duplication data for topic $topic complete.")
        case Failure(e) => log.error(s"Load de-duplication data for topic $topic failed.", e)
      }
      future
    }
    
    reader.topicExists(topic).flatMap {
      case true =>
        for {
          latest <- reader.maxSeqNo(topic)
          dedup <- loadAll(latest)
        } yield dedup
      case _ => 
        Future.successful(SingleTopicDeDuplicator(topic))
    }
  }
}