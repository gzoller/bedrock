import doobie._
import doobie.implicits._
import doobie.util.update.Update
import zio._
import zio.interop.catz._
import java.util.UUID

// Record definitions
case class Record(id: UUID, data: String)
case class HistoryRecord(record: Record)

object BatchDbOps {

  // Example transactor setup
  val xa: Transactor[Task] = Transactor.fromDriverManager[Task](
    "org.postgresql.Driver",
    "jdbc:postgresql://localhost:5432/mydb",
    "username",
    "password"
  )

  // Batch upsert using `Update`
  def batchUpsert(records: List[Record]): ConnectionIO[Int] = {
    val sql = "INSERT INTO table_name (id, data) VALUES (?, ?) ON CONFLICT (id) DO UPDATE SET data = EXCLUDED.data"
    val update = Update[Record](sql)
    update.updateMany(records)
  }

  // Batch delete using `Update`
  def batchDelete(ids: List[UUID]): ConnectionIO[Int] = {
    val sql = "DELETE FROM table_name WHERE id = ?"
    val delete = Update[UUID](sql)
    delete.updateMany(ids)
  }

  // Batch insert into history using `Update`
  def batchInsertHistory(historyRecords: List[HistoryRecord]): ConnectionIO[Int] = {
    val sql = "INSERT INTO history_table (id, data) VALUES (?, ?)"
    val update = Update[HistoryRecord](sql)
    update.updateMany(historyRecords.map(h => (h.record.id, h.record.data)))
  }

  // Process batch of records
  def processBatch(records: List[Record]): ConnectionIO[Unit] = for {
    _ <- batchUpsert(records)                                 // Upsert records
    _ <- batchDelete(records.map(_.id))                      // Delete old records
    _ <- batchInsertHistory(records.map(HistoryRecord(_)))   // Insert history
  } yield ()
  
  // ZIO wrapper
  def runBatch(records: List[Record]): Task[Unit] = 
    processBatch(records).transact(xa)
}



import doobie._
import doobie.implicits._
import zio._
import zio.interop.catz._
import java.util.UUID

// Sample Record definition
case class Record(id: UUID, data: String, status: String)

object BatchProcessor {

  // Example transactor setup
  val xa: Transactor[Task] = Transactor.fromDriverManager[Task](
    "org.postgresql.Driver",
    "jdbc:postgresql://localhost:5432/mydb",
    "username",
    "password"
  )

  // Batch upsert operation
  def upsertBatch(records: List[Record]): ConnectionIO[Int] = {
    val sql = "INSERT INTO table_name (id, data) VALUES (?, ?) ON CONFLICT (id) DO UPDATE SET data = EXCLUDED.data"
    val update = Update[(UUID, String)](sql)
    update.updateMany(records.map(r => (r.id, r.data)))
  }

  // Batch update status operation
  def updateStatusBatch(records: List[Record]): ConnectionIO[Int] = {
    val sql = "UPDATE table_name SET status = ? WHERE id = ?"
    val update = Update[(String, UUID)](sql)
    update.updateMany(records.map(r => (r.status, r.id)))
  }

  // Combine batch operations in a single transaction
  def processBatchInTransaction(records: List[Record]): ConnectionIO[Unit] = for {
    _ <- upsertBatch(records)             // First operation
    _ <- updateStatusBatch(records)       // Second operation
  } yield ()

  // ZIO wrapper for running the transaction
  def runBatch(records: List[Record]): Task[Unit] =
    processBatchInTransaction(records).transact(xa)
}

runBatch(records).catchAll(err => ZIO.logError(s"Batch processing failed: $err"))

def processBatchInTransaction(records: List[Record]): ConnectionIO[Unit] = for {
  _ <- upsertBatch(records)
  _ <- updateStatusBatch(records)
  _ <- insertHistory(records)
} yield ()




import doobie._
import doobie.implicits._
import java.util.UUID

case class Row(id: UUID, data: String) // Example schema

object DB {
  // Batch query for multiple IDs
  def getBatch(ids: List[UUID]): ConnectionIO[Map[UUID, Row]] = {
    // SQL query with an IN clause
    val query =
      fr"SELECT id, data FROM table_name WHERE" ++
      Fragments.in(fr"id", ids) // Generates the `IN` clause dynamically

    query.query[Row].to[List].map(rows => rows.map(row => row.id -> row).toMap)
  }
}



import zio._
import java.util.UUID

def fetchBatch(ids: List[UUID]): ZIO[Any, NotFound, List[Row]] = {
  DB.getBatch(ids).exec.flatMap { rowMap =>
    // Ensure all IDs are accounted for
    val missingIds = ids.filterNot(rowMap.contains)

    if (missingIds.nonEmpty) {
      ZIO.fail(NotFound(s"Rows not found for IDs: $missingIds"))
    } else {
      ZIO.succeed(ids.flatMap(rowMap.get)) // Preserve the order of the input IDs
    }
  }
}


