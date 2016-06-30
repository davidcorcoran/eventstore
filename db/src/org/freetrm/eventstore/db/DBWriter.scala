package org.freetrm.eventstore.db

import java.util.Calendar

import akka.actor._
import akka.stream.actor.{MaxInFlightRequestStrategy, OneByOneRequestStrategy, RequestStrategy, ActorSubscriber}
import akka.util.Timeout
import org.freetrm.eventstore._
import org.freetrm.eventstore.db.TopicActor._
import org.freetrm.eventstore.utils.Log
import slick.driver.JdbcProfile
import slick.jdbc.TransactionIsolation.Serializable

import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Future, Promise}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

class TopicActor(conn: DbConn, maxBacklog: Int) extends ActorSubscriber with Log {

  import conn._

  override protected def requestStrategy = new MaxInFlightRequestStrategy(max = maxBacklog) {
    override def inFlightInternally: Int = queue.size
  }

  protected val tables = new Tables(driver)

  import api._
  import context.dispatcher

  private val events = TableQuery[tables.EventsSchema]

  private var toProcess = Vector[(Promise[Seq[EventVersionPair]], IndexedSeq[Row])]()
  private var versions = Map[Topic, EventVersionPair]()
  // Interested in a specific topic, Some("topicname"), or all topics, None.
  private val listeners: mutable.Map[Option[Topic], Set[ActorRef]] = mutable.Map()

  private var working = false

  override def receive = {
    case 'start =>
      working = true
      val query = events.groupBy(_.topic).map {
        case (topic, t) =>
          topic ->(t.map(_.txnId).max, t.map(_.sequenceNumber).max)
      }.result

      db.run(query).map {
        case results =>
          results.foreach {
            case (topic, (Some(maxTxn), Some(maxSeq))) =>
              val version = EventVersionPair(maxSeq, maxTxn)
              versions += Topic(topic) -> version

            case o =>
              throw new Exception("Bad results from db: " + o)
          }
      }.onComplete {
        case Success(_) =>
          log.info("TopicActor started")
          working = false
          self ! 'process
        case Failure(f) =>
          log.error("Couldn't start TopicActor", f)
      }
      
    case ToInsert(promise, rows) =>
      if (toProcess.map(_._2.size).sum + rows.size > maxBacklog) {
        val msg = "Backlog is too big"
        log.warn(msg)
        promise.failure(new Exception(msg))
      } else {
        toProcess :+= (promise, rows)
        if (!working)
          self ! 'process
      }
      
    case 'process =>
      toProcess.headOption.foreach {
        case (promise, rows) =>
          val insertFuture = try {
            working = true
            val initialNo = 0l
            val now = new java.sql.Timestamp(Calendar.getInstance().getTime.getTime)

            val toInsert = rows.map {
              case Row(version, topic, key, contentHash, message, isTxnBoundary) =>
                (topic.name, version.seqNo, key, contentHash, message, now, version.txnNo, isTxnBoundary)
            }
            val insert = (for {
              res <- events ++= toInsert
            } yield res).withTransactionIsolation(Serializable)

            db.run(insert)

          } catch {
            case NonFatal(e) =>
              Future.failed(e)
          }

          insertFuture.onComplete {
            case Success(s) =>
              working = false
              toProcess = toProcess.tail
              self ! Inserted(rows)
              promise.success(rows.map(_.version))
              if (toProcess.nonEmpty) {
                self ! 'process
              }
            case Failure(e) =>
              log.error("Failed to insert, retrying in 1s", e)
              context.system.scheduler.scheduleOnce(FiniteDuration(1, "s"), self, 'process)
          }
      }

    case update@Inserted(inserted) =>
      val latest: Map[Topic, EventVersionPair] = inserted.map {
        case Row(version, topic, _, _, _, _) => topic -> version
      }.groupBy(_._1).map {
        case (topic, topicsAndVersions) => topicsAndVersions.maxBy(_._2.seqNo)
      }
      versions ++= latest
      latest.foreach {
        case (topic, version) =>
          listeners.getOrElse(Some(topic), Set()).foreach(_ ! TopicOffsetInfo(topic, version))
      }
      listeners.getOrElse(None, Set()).foreach(_ ! TopicOffsets(versions))

    case QueryOffsetForTopic(topic) =>
      sender() ! versions.get(topic)

    case QueryOffsets =>
      sender() ! TopicOffsets(versions)

    case RegisterInterest(filterTopic) =>
      val l = listeners.getOrElse(filterTopic, Set())
      listeners.put(filterTopic, l + sender())
      filterTopic match {
        case Some(t) =>
          versions.get(t).foreach {
            case info => sender() ! info
          }
        case _ =>
          sender() ! TopicOffsets(versions)
      }

    case UnregisterInterest(filterTopic) =>
      val l = listeners.getOrElse(filterTopic, Set())
      listeners.put(filterTopic, l - sender())

    case shutdown@ActorShutdown(reason) =>
      listeners.values.foreach {
        _.foreach(_ ! shutdown)
      }
      context.stop(self)
  }
}

object TopicActor {

  case object BacklogTooBig

  case class Row(version: EventVersionPair, topic: Topic, key: String, contentHash: String,
                 message: String, isTxnBoundary: Boolean)

  case class ToInsert(promise: Promise[Seq[EventVersionPair]], rows: IndexedSeq[Row])

  case class Inserted(rows: IndexedSeq[Row])

  case class TopicOffsetInfo(topic: Topic, version: EventVersionPair)

  case class RegisterInterest(filterTopic: Option[Topic])

  case class UnregisterInterest(filterTopic: Option[Topic])

  case class QueryOffsetForTopic(topic: Topic)

  case object QueryOffsets

  case class TopicOffsets(offsets: Map[Topic, EventVersionPair])

  case class ActorShutdown(reason: String)

  val MaxGroupSize = 200
}

class DBWriter(conn: DbConn, maxBacklog: Int = 100000)
              (implicit system: ActorSystem) extends EventSourceWriter with Log {

  import conn._
  import system.dispatcher

  implicit val askTimeout = new Timeout(FiniteDuration(2, "s"))

  protected val tables = new Tables(driver)

  import api._

  private val events = TableQuery[tables.EventsSchema]
  val dbWritingActor: ActorRef = system.actorOf(Props(new TopicActor(conn, maxBacklog)))

  def start(): Future[Unit] = Future.successful(dbWritingActor ! 'start)

  def dropAndRecreate(): Future[Boolean] = {
    val run = db.run(events.schema.drop).recover { case _ => Unit }

    run.flatMap {
      _ =>
        db.run(
          events.schema.create
        ).map(_ => true)
    }
  }


  override def write(topic: Topic, event: Event): Future[EventVersionPair] = {
    writeAll((topic, event) :: Nil).map(_.head)
  }

  override def writeAll(events: Seq[(Topic, Event)]): Future[Seq[EventVersionPair]] = {
    val rows = events.map {
      case (topic, EventTransactionStart(version)) =>
        Row(version, topic, "", "", Tables.TxnStartData, isTxnBoundary = true)
      case (topic, EventTransactionEnd(version)) =>
        Row(version, topic, "", "", Tables.TxnEndData, isTxnBoundary = true)
      case (topic, EventSourceEvent(version, key, hash, data)) =>
        Row(version, topic, key, hash, data, isTxnBoundary = false)
      case (topic, EventInvalidate(version)) => throw new Exception("EventInvalidate not implemented yet")
    }
    val promise = Promise[Seq[EventVersionPair]]
    dbWritingActor ! ToInsert(promise, rows.toIndexedSeq)
    promise.future
  }

  override def close(): Unit = {
    dbWritingActor ! ActorShutdown("DBEventSourceReader.Close called")
    db.close()
  }
}


trait DbConn extends slick.driver.JdbcDriver {
  self: JdbcProfile =>

  def db: self.backend.DatabaseDef

  def driver: JdbcProfile = self
}

class H2(dbUrl: String, user: String = null, password: String = null) extends DbConn with slick.driver.H2Driver {
  Class.forName("org.h2.Driver")

  import api._

  override val db = Database.forURL(dbUrl, user, password)
}

case class SqlServer(dbUrl: String, user: String = null, password: String = null,
                     topicInfoActor: Option[ActorRef] = None)
                    (implicit val system: ActorSystem)
  extends DbConn with freeslick.MSSQLServerProfile {

  import api._

  override val db = Database.forURL(dbUrl, user, password)
}