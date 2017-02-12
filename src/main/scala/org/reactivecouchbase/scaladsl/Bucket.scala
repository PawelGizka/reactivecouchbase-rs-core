package org.reactivecouchbase.scaladsl

import java.util.concurrent.Executors

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.couchbase.client.java.CouchbaseCluster
import com.couchbase.client.java.bucket.AsyncBucketManager
import com.couchbase.client.java.document.RawJsonDocument
import com.typesafe.config.Config
import org.reactivecouchbase.scaladsl.Implicits._
import play.api.libs.json._
import rx.functions.Func1
import rx.{Observable, RxReactiveStreams}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

case class BucketConfig(name: String, password: Option[String] = None, hosts: Seq[String])

object BucketConfig {
  import collection.JavaConversions._
  def apply(config: Config, system: ActorSystem): BucketConfig = {
    val name = Try(config.getString("name")).get
    val password = Try(config.getString("password")).toOption
    val hosts = Try(config.getStringList("hosts")).get.toIndexedSeq
    BucketConfig(name, password, hosts)
  }
}

object Bucket {
  def apply(config: BucketConfig) = new Bucket(config)
}

class Bucket(config: BucketConfig) {

  private val defaultReads: Reads[JsValue] = Reads.apply(jsv => JsSuccess(jsv))
  private val defaultWrites: Writes[JsValue] = Writes.apply(jsv => jsv)
  private val defaultWriteSettings: WriteSettings = WriteSettings()
  private val internalExecutionContext = ExecutionContext.fromExecutorService(Executors.newSingleThreadExecutor())

  private val cluster: CouchbaseCluster = CouchbaseCluster.create(config.hosts:_*)

  private val (bucket, asyncBucket, bucketManager, futureBucket) = {
    val _bucket = config.password
      .map(p => cluster.openBucket(config.name, p))
      .getOrElse(cluster.openBucket(config.name))
    val _bucketManager = _bucket.bucketManager()
    // TODO : avoid index creation
    _bucketManager.createN1qlPrimaryIndex(true, false)
    (_bucket, _bucket.async(), _bucketManager, Future.successful(_bucket.async()))
  }

  // TODO
  // implement other searches
  // implement management (design doc, etc ...)

  // TODO streams
  // get
  // insert
  // upsert
  // remove

  // TODO later
  // maps operations
  // sets operations
  // lists operations
  // queues operations
  // getAndTouch
  // getAndLock
  // counter operations

  def close(implicit ec: ExecutionContext): Future[Boolean] = {
    futureBucket.flatMap(_.close().asFuture.map(_.booleanValue())).andThen {
      case _ => cluster.disconnect()
    }
  }

  def manager(implicit ec: ExecutionContext): Future[AsyncBucketManager] = {
    futureBucket.flatMap(b => b.bucketManager().asFuture)
  }

  def exists(key: String)(implicit ec: ExecutionContext): Future[Boolean] = {
    futureBucket.flatMap { bucket =>
      bucket.exists(key).asFuture.map(_.booleanValue())
    }
  }

  def remove(key: String, settings: WriteSettings = defaultWriteSettings)(implicit ec: ExecutionContext): Future[JsValue] = {
    futureBucket.flatMap { bucket =>
      bucket.remove(key, settings.persistTo, settings.replicateTo, classOf[RawJsonDocument]).asFuture.map(doc => Json.parse(doc.content()))
    }
  }

  def insert[T](key: String, slug: T, settings: WriteSettings = defaultWriteSettings, writer: Writes[T] = defaultWrites)(implicit ec: ExecutionContext): Future[JsValue] = {
    futureBucket.flatMap { bucket =>
      bucket.insert(
        RawJsonDocument.create(
          key,
          settings.expiration.toMillis.toInt,
          Json.stringify(writer.writes(slug))
        ),
        settings.persistTo,
        settings.replicateTo
      ).asFuture.map(doc => Json.parse(doc.content()))
    }
  }

  def upsert[T](key: String, slug: T, settings: WriteSettings = defaultWriteSettings, writer: Writes[T] = defaultWrites)(implicit ec: ExecutionContext): Future[JsValue] = {
    futureBucket.flatMap { bucket =>
      bucket.upsert(
        RawJsonDocument.create(
          key,
          settings.expiration.toMillis.toInt,
          Json.stringify(writer.writes(slug))
        ),
        settings.persistTo,
        settings.replicateTo
      ).asFuture.map(doc => Json.parse(doc.content()))
    }
  }

  def get[T](key: String, reader: Reads[T] = defaultReads)(implicit ec: ExecutionContext): Future[Option[T]] = {
    futureBucket.flatMap(b => b.get(RawJsonDocument.create(key)).asFuture)
      .filter(_ != null)
      .map(doc => Json.parse(doc.content()))
      .map(jsDoc => reader.reads(jsDoc).asOpt)
  }

  def search[T](query: QueryLike, reader: Reads[T] = defaultReads)(implicit ec: ExecutionContext, materializer: Materializer): QueryResult[T] = {
    SimpleQueryResult(() => {
      val obs: Observable[T] = query match {
        case N1qlQuery(n1ql, _) => {
          asyncBucket.query(com.couchbase.client.java.query.N1qlQuery.simple(n1ql))
            .flatMap(RxUtils.func1(_.rows()))
            .map(RxUtils.func1 { t =>
              reader.reads(Json.parse(t.byteValue())) match {
                case JsSuccess(s, _) => s
                case JsError(e) => throw new RuntimeException(s"Error while parsing document : $e") // TODO : better error
              }
            })
        }
        case _ => Observable.error(new UnsupportedOperationException("Query not supported !"))
      }
      Source.fromPublisher[T](RxReactiveStreams.toPublisher[T](obs))
    })
  }
}
