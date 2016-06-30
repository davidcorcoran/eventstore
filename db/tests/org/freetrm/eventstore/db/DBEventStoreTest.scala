package org.freetrm.eventstore.db

import java.util.UUID

import akka.NotUsed
import akka.actor.{ActorRef, Props, ActorSystem}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout
import org.freetrm.eventstore._
import org.freetrm.eventstore.http.FunSuiteDoc
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.{Matchers, FunSuite}

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.Random

class DBEventStoreTest extends FunSuite with FunSuiteDoc 
  with Matchers with ScalaFutures with MockFactory with Eventually {
  
  implicit val defaultPatience = PatienceConfig(timeout = Span(100, Seconds), interval = Span(15, Millis))
  implicit val system = ActorSystem()
  implicit val timeout = new Timeout(FiniteDuration(2, "s"))
  implicit val materializer = ActorMaterializer()
  private val topic = Topic("topic")

  documentTests(
    "create, insert, read back" testsThat
      """
         When you write the events to the EventStoreWriter you get the same events back through the reader.
         It uses an in memory H2.
      """,

    "consuming endless source" testsThat
      """"
        Calling 'consume' on the reader with a 'to' sequence number of the current max seq number and calling
        with a 'to' of None both produce the same results.
        The case with 'None' would run forever but we terminate it at max seq number to check the results.
      """,

    "event order is correct" testsThat
      """"
        Writes events and then reads them back comparing the sequence numbers and keys to make sure the
        order has been preserved.
      """,

    "slow reader eventually gets everything" testsThat
      """"
        When we simulate being a slow reader (with sleeps) we get all the events eventually.
        In the background the Stream starts out with a large window size which it then shrinks and grows. So this
        is testing that the EventPublisher, which backs the stream, responds to that shrinking and growing
        correctly. If, for example, it sent events too quickly, the Stream would throw an exception.
      """,

    "consume notifications" tests
      """"
        The notification consuming code. We publish to the writer and expect notifications for the new events.
        The consume code is so fast that this test ends up being told about every new event 
      """,

    "slow notification consumer gets batches of updates" testsThat
      """"
        If you consume event notifications slowly there is back-pressure on the notifier. This results in not being
        told about intermediate events as by the time you are ready to receive an event notification we will
        only tell you about the latest.
      """,

    "ensure order of returned events and that fetch batching happens" testsThat
      """"
        We request batches of a specific size from the database. We compare the results of the consume
        to what was written.
      """,

    "DB exceptions propagate and we can recover" testsThat
      """"
        If we simulate a DB exception on one read that the consumer source fails with that exception. If you try
        calling consume again you then get a good new Source with all the expected events.
      """,
    
    "event surface" testsThat
      """"
        If we write lots of events with the same keys then ask for the surface, we only get the latest version
        of each event.
      """
  )
  
  test("create, insert, read back") {
    val (reader, writer) = newReaderAndWriter()
    
    reader.maxSeqNo(topic).futureValue shouldEqual None
    reader.maxTxnNo(topic).futureValue shouldEqual None

    val numEvents = 4
    writeEventsInTransaction(writer, numEvents)
    
    eventually {
      reader.maxSeqNo(topic).futureValue shouldEqual Some(numEvents - 1)
      reader.maxTxnNo(topic).futureValue shouldEqual Some(0)

      reader.listTopics.futureValue shouldEqual Seq(topic)
      reader.topicExists(topic).futureValue shouldEqual true
    }
    
    reader.streamEvents(topic, 0, Some(3)).runWith(Sink.seq).futureValue shouldEqual Seq(
      EventTransactionStart(EventVersionPair(0, 0)), 
      EventSourceEvent(EventVersionPair(1, 0), "key1", "cv", "data1"), 
      EventSourceEvent(EventVersionPair(2, 0), "key2", "cv", "data2"),
      EventTransactionEnd(EventVersionPair(3, 0))
    )

    // test edge case when from and to are the same, and are zero
    reader.streamEvents(topic, 0, Some(0)).runWith(Sink.seq).futureValue shouldEqual Seq(
      EventTransactionStart(EventVersionPair(0, 0))
    )

    reader.dbReader.fetch(topic, 0, Some(3)).futureValue shouldEqual reader.dbReader.fetch(topic, 0, None).futureValue
  }

  test("consuming endless source") {
    val (reader, writer) = newReaderAndWriter()

    val numEvents = 10
    writeEventsInTransaction(writer, numEvents)

    // need the `take` or we'll never finish. The None for lastOffsetToReadTo indicates it's endless.
    val allEvents = reader.streamEvents(topic, 0, lastOffsetToReadTo = None).take(numEvents).runWith(Sink.seq).futureValue
    allEvents shouldEqual reader.dbReader.fetch(topic, 0, None).futureValue
    
    val maxSeqNo = reader.maxSeqNo(topic).futureValue
    allEvents shouldEqual reader.streamEvents(topic, 0, maxSeqNo).take(numEvents).runWith(Sink.seq).futureValue
  }

  test("event order is correct") {
    val (reader, writer) = newReaderAndWriter()

    val numEvents = 5
    writeEventsInTransaction(writer, numEvents)

    val maxSeq = eventually {
      val max = reader.maxSeqNo(topic).futureValue
      max shouldEqual Some(4)
      max
    }
    
    val allEvents = reader.streamEvents(topic, 0, maxSeq).runWith(Sink.seq).futureValue
    
    allEvents.foreach {
      case EventSourceEvent(EventVersionPair(seqNo, _), key, hash, data) =>
        key shouldEqual s"key$seqNo"
      case _ =>
    }
  }
  
  test("slow reader eventually gets everything") {
    val db = dbUrl
    val h2readerLowLimit = new H2DBReader(db, maxFetch = 40)
    val h2readerHighLimit = new H2DBReader(db, maxFetch = 1000)
    val writer = newWriter(db)
    
    val reader = new DBEventSourceReader(h2readerLowLimit, writer.dbWritingActor)

    val numEvents = 200
    writeEventsInTransaction(writer, numEvents)
    val rand = new Random()

    reader.streamEvents(topic, 0, None).map{
      e =>
        Thread.sleep(10) // slow reader
        if(rand.nextDouble() > 0.9)
          Thread.sleep(100) // some extra slow bits
        e
    }.take(numEvents).runWith(Sink.seq).futureValue shouldEqual h2readerHighLimit.fetch(topic, 0, None).futureValue
  }
  
  test("consume notifications") {
    val (reader, writer) = newReaderAndWriter()

    val notificationsFuture = reader.streamNotifications.runWith(Sink.seq)

    writeEventsInTransaction(writer, 20)
    writeEventsInTransaction(writer, 40, Some(EventVersionPair(20, 1)))
    writeEventsInTransaction(writer, 55, Some(EventVersionPair(40, 2)))

    reader.close()
    writer.close()

    val notifications = notificationsFuture.futureValue
    notifications should contain (EventNotification(Map()))
    notifications should contain (EventNotification(Map(topic -> EventVersionPair(19, 0))))
    notifications should contain (EventNotification(Map(topic -> EventVersionPair(59, 1))))
    notifications should contain (EventNotification(Map(topic -> EventVersionPair(114, 2))))

    notifications.size shouldEqual 1 + 3 // 3 batched writes and extra one for the initial empty notification
  }
  
  test("slow notification consumer gets batches of updates") {
    val (reader, writer) = newReaderAndWriter()
    val rand = new Random()
    
    val notificationsFuture = reader.streamNotifications.map{
      e =>
        Thread.sleep(20) // slow reader
        if(rand.nextDouble() > 0.9)
          Thread.sleep(100) // some extra slow bits
        e
    }.runWith(Sink.seq)

    (0 to 100).foreach {
      i =>
        writeEventsInTransaction(writer, 20, Some(EventVersionPair(i * 20, i)))
    }
    
    reader.close()
    Thread.sleep(200) // if we close the writer below while the "slow reader" is still reading we'll miss a notification
    writer.close()
    val notifications = notificationsFuture.futureValue

    // should have the first and last ones
    notifications should contain (EventNotification(Map()))
    notifications should contain (EventNotification(Map(topic -> EventVersionPair(2019, 100))))
    
    // and won't have all the in between but should have a few
    notifications.size should be > 10
    notifications.size should be < 100
  }

  test("ensure order of returned events and that fetch batching happens") {
    var numberOfTimesFetchedCalled = 0
    val db = dbUrl
    
    val maxFetch: Int = 50
    val h2reader = new H2DBReader(db, maxFetch = maxFetch) {
      override def fetch(topic: Topic, 
                         earliestOffsetToReadFrom: Long, 
                         lastOffsetToReadTo: Option[Long]): Future[Vector[Event]] = {
        numberOfTimesFetchedCalled += 1
        super.fetch(topic, earliestOffsetToReadFrom, lastOffsetToReadTo)
      }
    }

    val writer = newWriter(db)
    val reader = new DBEventSourceReader(h2reader, writer.dbWritingActor)

    val numEvents = 400
    writeEventsInTransaction(writer, numEvents)

    val maxSeqNo = reader.maxSeqNo(topic).futureValue
    maxSeqNo shouldEqual Some(numEvents - 1)
    reader.maxTxnNo(topic).futureValue shouldEqual Some(0)

    var i = 0
    reader.streamEvents(topic, 0, maxSeqNo).take(numEvents).runWith(Sink.foreach {
      case EventSourceEvent(EventVersionPair(seq, 0l), key, "cv", data) =>
        seq shouldEqual i
        key shouldEqual s"key$seq"
        data shouldEqual s"data$seq"
        i += 1
      case _ =>
        i += 1
    }).futureValue

    eventually(i shouldEqual numEvents)

    numberOfTimesFetchedCalled shouldEqual (numEvents / maxFetch)
  }
  
  test("DB exceptions propagate and we can recover") {
    val db = dbUrl
    var shouldErrorWhenReadingFromDB = false

    val maxFetch: Int = 5
    val h2reader = new H2DBReader(db, maxFetch = maxFetch) {
      override def fetch(topic: Topic, 
                         earliestOffsetToReadFrom: Long, 
                         lastOffsetToReadTo: Option[Long]): Future[Vector[Event]] = {
        if(shouldErrorWhenReadingFromDB)
          Future.failed(new Exception("Test exception"))
        else
          super.fetch(topic, earliestOffsetToReadFrom, lastOffsetToReadTo)
      }
    }

    val writer = newWriter(db)
    val reader = new DBEventSourceReader(h2reader, writer.dbWritingActor)

    val numEvents = 400
    writeEventsInTransaction(writer, numEvents)

    val consumer = reader.streamEvents(topic, 0, None)
    val future = consumer.runForeach({
      e =>
        if (e.version.seqNo == 100)
          shouldErrorWhenReadingFromDB = true
    })

    future.failed.futureValue.getMessage shouldEqual "Test exception"

    shouldErrorWhenReadingFromDB = false
    // we can recover by creating a new consumer
    val maxSeq = reader.maxSeqNo(topic).futureValue
    reader.streamEvents(topic, 0, maxSeq).runWith(Sink.seq).futureValue.size shouldEqual numEvents
  }

  test("event surface") {
    val (reader, writer) = newReaderAndWriter()

    val numEvents = 501
    
    // so at the end we should have data events 450 to 499 (500 is the 'end trasaction' event)
    writeEventsInTransaction(writer, numEvents, key = i => s"key${i % 50}")

    val surface = reader.latestSurface(topic).runWith(Sink.seq).futureValue
    surface.size shouldEqual 50
    
    val expected = (450 to 499).reverse.map {
      i =>
        EventSourceEvent(EventVersionPair(i, 0), s"key${i % 50}", "cv", s"data$i")
    }
    
    expected shouldEqual surface
  }


  test("writing larger amounts that the max backlog will allow") {
    val (reader, writer) = newReaderAndWriter(maxBacklog = 10)

    val numEvents = 100000
    writeEventsInTransaction(writer, numEvents)

    eventually {
      reader.maxSeqNo(topic).futureValue shouldEqual Some(numEvents - 1)
      reader.maxTxnNo(topic).futureValue shouldEqual Some(0)

      reader.listTopics.futureValue shouldEqual Seq(topic)
      reader.topicExists(topic).futureValue shouldEqual true
    }

    reader.streamEvents(topic, 0, Some(numEvents - 1)).runWith(Sink.seq).futureValue.size shouldEqual numEvents
  }
  
  def dbUrl = "jdbc:h2:mem:" + UUID.randomUUID + ";DB_CLOSE_DELAY=-1"
  
  private def newReaderAndWriter(db: String = dbUrl, maxBacklog: Int = 100000) = {
    val dbReader = new H2DBReader(db)
    val writer = new DBWriter(new H2(db), maxBacklog)(system)
    val reader = new DBEventSourceReader(dbReader, writer.dbWritingActor)
    
    writer.dropAndRecreate().futureValue shouldEqual true
    writer.start().futureValue
    (reader, writer)
  }
  
  private def newWriter(db: String, maxBacklog: Int = 100000) = {
    val writer = new DBWriter(new H2(db), maxBacklog)(system)
    writer.dropAndRecreate().futureValue shouldEqual true
    writer.start().futureValue
    writer
  }

  private def writeEventsInTransaction(writer: DBWriter, 
                                       numEvents: Int, 
                                       initialVersion: Option[EventVersionPair] = None,
                                       key: Int => String = i => s"key$i"): NotUsed = {
    import system.dispatcher
    
    val start = initialVersion.map(_.seqNo).getOrElse(0l)
    val txn = initialVersion.map(_.txnNo).getOrElse(0l)
    val total = numEvents + start
    val toInsert = Source(start until total).map {
      i =>
        if(i == start)
          topic -> EventTransactionStart(EventVersionPair(i, txn))
        else if (i == total - 1)
          topic -> EventTransactionEnd(EventVersionPair(i, txn))
        else
          topic -> EventSourceEvent(EventVersionPair(i, txn), key(i.toInt), "cv", s"data$i")
    }
    toInsert.runWith(writer.sink)
  }
}
