package org.freetrm.eventstore.db

import java.sql.SQLException

import akka.actor.ActorSystem
import com.typesafe.config.{Config, ConfigFactory}
import org.freetrm.eventstore.http.WebService
import org.freetrm.eventstore.utils.SystemExit
import org.freetrm.eventstore.{EventSourceReader, EventSourceWriter}
import scaldi.Module

import scala.collection.Seq
import scala.collection.immutable.SortedMap
import scala.collection.immutable.TreeMap
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Failure

object Main {
  lazy val conf = ConfigFactory.load()

  def createMainModule = new Module {
    bind[ActorSystem] to ActorSystem("evcel") destroyWith (_.terminate())
    bind[Config] to conf
  }


  class DBModule extends Module {
    lazy implicit val system = inject[ActorSystem]
    lazy val dbUrl = conf.getString("db.url")
    lazy val user = if(conf.hasPath("db.user")) conf.getString("db.user") else null
    lazy val password = if(conf.hasPath("db.password")) conf.getString("db.password") else null

    def startWriter(writer: DBWriter): Unit = {
      if (conf.getBoolean("db.shouldTryToCreateTables"))
        Await.result(writer.dropAndRecreate(), 2.seconds)
      Await.result(writer.start(), 25.seconds)
    }

    lazy val (writer, reader) = {
      implicit val system = inject[ActorSystem]
      val dbReader = if(dbUrl.startsWith("jdbc:h2")) {
        new H2DBReader(dbUrl, user, password)
      } else if(dbUrl.startsWith("jdbc:jtds:sqlserver")) {
        new SqlServerDBReader(dbUrl, user, password)
      } else {
        throw new Exception("Invalid db url: " + dbUrl)
      }
      val writer = new DBWriter(new H2(dbUrl, user, password), maxBacklog = 10000)
      val reader = new DBEventSourceReader(dbReader, writer.dbWritingActor)
      (writer, reader)
    }
    
    bind[EventSourceWriter] toProvider writer initWith (startWriter) destroyWith (_.close())
    bind[EventSourceReader] toProvider reader destroyWith (_.close())
  }

  def main(args: Array[String]) {
    import scala.concurrent.ExecutionContext.Implicits.global
    
    val ws = new WebService()
    val h2 = new DBModule

    createMainModule ++ h2 ++ ws

    ws.start().onComplete {
      case Failure(e) =>
        SystemExit(1, "Web server, unknown error. Exiting.", e)
      case _ =>
    }

    Runtime.getRuntime.addShutdownHook(new Thread("Exit hook") {
      override def run() {
        ws.stop()
      }
    })
  }
}
