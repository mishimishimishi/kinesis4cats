/*
 * Copyright 2023-2023 etspaceman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kinesis4cats.kcl
package localstack

import java.util.UUID

import cats.effect.std.Queue
import cats.effect.syntax.all._
import cats.effect.{Async, Deferred, Resource}
import cats.syntax.all._
import software.amazon.kinesis.checkpoint.CheckpointConfig
import software.amazon.kinesis.common._
import software.amazon.kinesis.coordinator.CoordinatorConfig
import software.amazon.kinesis.leases.LeaseManagementConfig
import software.amazon.kinesis.lifecycle.LifecycleConfig
import software.amazon.kinesis.metrics.MetricsConfig
import software.amazon.kinesis.retrieval.RetrievalConfig
import software.amazon.kinesis.retrieval.polling.PollingConfig

import kinesis4cats.kcl.multistream.MultiStreamTracker
import kinesis4cats.localstack.LocalstackConfig
import kinesis4cats.localstack.aws.v2.AwsClients

/** Helpers for constructing and leveraging the KPL with Localstack.
  */
object LocalstackKCLConsumer {

  final case class ConfigWithResults[F[_]](
      kclConfig: KCLConsumer.Config[F],
      resultsQueue: Queue[F, CommittableRecord[F]]
  )

  final case class DeferredWithResults[F[_]](
      deferred: Deferred[F, Unit],
      resultsQueue: Queue[F, CommittableRecord[F]]
  )

  /** Creates a [[kinesis4cats.kcl.KCLConsumer.Config KCLConsumer.Config]] that
    * is compliant with Localstack.
    *
    * @param config
    *   [[kinesis4cats.localstack.LocalstackConfig LocalstackConfig]]
    * @param streamName
    *   Name of stream to consume
    * @param appName
    *   Application name for the consumer. Used for the dynamodb table name as
    *   well as the metrics namespace.
    * @param workerId
    *   Unique identifier for the worker. Typically a UUID.
    * @param position
    *   [[https://github.com/awslabs/amazon-kinesis-client/blob/master/amazon-kinesis-client/src/main/java/software/amazon/kinesis/common/InitialPositionInStreamExtended.java InitialPositionInStreamExtended]]
    * @param processConfig
    *   [[kinesis4cats.kcl.KCLConsumer.ProcessConfig KCLConsumer.ProcessConfig]]
    * @param cb
    *   User-defined callback function for processing records
    * @param F
    *   [[cats.effect.Async Async]]
    * @param LE
    *   [[kinesis4cats.kcl.RecordProcessor.LogEncoders RecordProcessor.LogEncoders]]
    * @return
    *   [[kinesis4cats.kcl.KCLConsumer.Config KCLConsumer.Config]]
    */
  def kclConfig[F[_]](
      config: LocalstackConfig,
      streamName: String,
      appName: String,
      workerId: String,
      position: InitialPositionInStreamExtended,
      processConfig: KCLConsumer.ProcessConfig
  )(cb: List[CommittableRecord[F]] => F[Unit])(implicit
      F: Async[F],
      LE: RecordProcessor.LogEncoders
  ): Resource[F, KCLConsumer.Config[F]] = for {
    kinesisClient <- AwsClients.kinesisClientResource(config)
    cloudwatchClient <- AwsClients.cloudwatchClientResource(config)
    dynamoClient <- AwsClients.dynamoClientResource(config)
    retrievalConfig = new PollingConfig(streamName, kinesisClient)
    result <- KCLConsumer.Config.create[F](
      new CheckpointConfig(),
      new CoordinatorConfig(appName).parentShardPollIntervalMillis(1000L),
      new LeaseManagementConfig(
        appName,
        dynamoClient,
        kinesisClient,
        workerId
      ).initialPositionInStream(position)
        .shardSyncIntervalMillis(1000L),
      new LifecycleConfig(),
      new MetricsConfig(cloudwatchClient, appName),
      new RetrievalConfig(kinesisClient, streamName, appName)
        .retrievalSpecificConfig(retrievalConfig)
        .retrievalFactory(retrievalConfig.retrievalFactory())
        .initialPositionInStreamExtended(position),
      processConfig = processConfig
    )(cb)
  } yield result

  /** Creates a [[kinesis4cats.kcl.KCLConsumer.Config KCLConsumer.Config]] that
    * is compliant with Localstack. Meant to be used with multi-stream
    * consumers.
    *
    * @param config
    *   [[kinesis4cats.localstack.LocalstackConfig LocalstackConfig]]
    * @param tracker
    *   [[kinesis4cats.kcl.multistream.MultiStreamTracker]]
    * @param appName
    *   Application name for the consumer. Used for the dynamodb table name as
    *   well as the metrics namespace.
    * @param workerId
    *   Unique identifier for the worker. Typically a UUID.
    * @param processConfig
    *   [[kinesis4cats.kcl.KCLConsumer.ProcessConfig KCLConsumer.ProcessConfig]]
    * @param cb
    *   User-defined callback function for processing records
    * @param F
    *   [[cats.effect.Async Async]]
    * @param LE
    *   [[kinesis4cats.kcl.RecordProcessor.LogEncoders RecordProcessor.LogEncoders]]
    * @return
    *   [[kinesis4cats.kcl.KCLConsumer.Config KCLConsumer.Config]]
    */
  def kclMultiConfig[F[_]](
      config: LocalstackConfig,
      tracker: MultiStreamTracker,
      appName: String,
      workerId: String,
      processConfig: KCLConsumer.ProcessConfig
  )(cb: List[CommittableRecord[F]] => F[Unit])(implicit
      F: Async[F],
      LE: RecordProcessor.LogEncoders
  ): Resource[F, KCLConsumer.Config[F]] = for {
    kinesisClient <- AwsClients.kinesisClientResource(config)
    cloudwatchClient <- AwsClients.cloudwatchClientResource(config)
    dynamoClient <- AwsClients.dynamoClientResource(config)
    retrievalConfig = new PollingConfig(kinesisClient)
    result <- KCLConsumer.Config.create[F](
      new CheckpointConfig(),
      new CoordinatorConfig(appName).parentShardPollIntervalMillis(1000L),
      new LeaseManagementConfig(
        appName,
        dynamoClient,
        kinesisClient,
        workerId
      ).shardSyncIntervalMillis(1000L),
      new LifecycleConfig(),
      new MetricsConfig(cloudwatchClient, appName),
      new RetrievalConfig(kinesisClient, tracker, appName)
        .retrievalSpecificConfig(retrievalConfig)
        .retrievalFactory(retrievalConfig.retrievalFactory()),
      processConfig = processConfig
    )(cb)
  } yield result

  /** Creates a [[kinesis4cats.kcl.KCLConsumer.Config KCLConsumer.Config]] that
    * is compliant with Localstack.
    *
    * @param streamName
    *   Name of stream to consume
    * @param appName
    *   Application name for the consumer. Used for the dynamodb table name as
    *   well as the metrics namespace.
    * @param prefix
    *   Optional prefix for parsing configuration. Default to None
    * @param workerId
    *   Unique identifier for the worker. Default is a random UUID
    * @param position
    *   [[https://github.com/awslabs/amazon-kinesis-client/blob/master/amazon-kinesis-client/src/main/java/software/amazon/kinesis/common/InitialPositionInStreamExtended.java InitialPositionInStreamExtended]]
    *   Default is TRIM_HORIZON
    * @param processConfig
    *   [[kinesis4cats.kcl.KCLConsumer.ProcessConfig KCLConsumer.ProcessConfig]]
    *   Default is `ProcessConfig.default`
    * @param cb
    *   User-defined callback function for processing records
    * @param F
    *   [[cats.effect.Async Async]]
    * @param LE
    *   [[kinesis4cats.kcl.RecordProcessor.LogEncoders RecordProcessor.LogEncoders]]
    * @return
    *   [[kinesis4cats.kcl.KCLConsumer.Config KCLConsumer.Config]]
    */
  def kclConfig[F[_]](
      streamName: String,
      appName: String,
      prefix: Option[String] = None,
      workerId: String = UUID.randomUUID().toString,
      position: InitialPositionInStreamExtended =
        InitialPositionInStreamExtended.newInitialPosition(
          InitialPositionInStream.TRIM_HORIZON
        ),
      processConfig: KCLConsumer.ProcessConfig =
        KCLConsumer.ProcessConfig.default
  )(cb: List[CommittableRecord[F]] => F[Unit])(implicit
      F: Async[F],
      LE: RecordProcessor.LogEncoders
  ): Resource[F, KCLConsumer.Config[F]] = for {
    config <- LocalstackConfig.resource(prefix)
    result <- kclConfig(
      config,
      streamName,
      appName,
      workerId,
      position,
      processConfig
    )(cb)
  } yield result

  /** Creates a [[kinesis4cats.kcl.KCLConsumer.Config KCLConsumer.Config]] that
    * is compliant with Localstack.
    *
    * @param tracker
    *   [[kinesis4cats.kcl.multistream.MultiStreamTracker]]
    * @param appName
    *   Application name for the consumer. Used for the dynamodb table name as
    *   well as the metrics namespace.
    * @param prefix
    *   Optional prefix for parsing configuration. Default to None
    * @param workerId
    *   Unique identifier for the worker. Default is a random UUID
    * @param processConfig
    *   [[kinesis4cats.kcl.KCLConsumer.ProcessConfig KCLConsumer.ProcessConfig]]
    *   Default is `ProcessConfig.default`
    * @param cb
    *   User-defined callback function for processing records
    * @param F
    *   [[cats.effect.Async Async]]
    * @param LE
    *   [[kinesis4cats.kcl.RecordProcessor.LogEncoders RecordProcessor.LogEncoders]]
    * @return
    *   [[kinesis4cats.kcl.KCLConsumer.Config KCLConsumer.Config]]
    */
  def kclMultiConfig[F[_]](
      tracker: MultiStreamTracker,
      appName: String,
      prefix: Option[String] = None,
      workerId: String = UUID.randomUUID().toString,
      processConfig: KCLConsumer.ProcessConfig =
        KCLConsumer.ProcessConfig.default
  )(cb: List[CommittableRecord[F]] => F[Unit])(implicit
      F: Async[F],
      LE: RecordProcessor.LogEncoders
  ): Resource[F, KCLConsumer.Config[F]] = for {
    config <- LocalstackConfig.resource(prefix)
    result <- kclMultiConfig(
      config,
      tracker,
      appName,
      workerId,
      processConfig
    )(cb)
  } yield result

  /** Creates a [[kinesis4cats.kcl.KCLConsumer.Config KCLConsumer.Config]] that
    * is compliant with Localstack. Also creates a results
    * [[cats.effect.std.Queue queue]] for the consumer to stick results into.
    * Helpful when confirming data that has been produced to a stream.
    *
    * @param config
    *   [[kinesis4cats.localstack.LocalstackConfig LocalstackConfig]]
    * @param streamName
    *   Name of stream to consume
    * @param appName
    *   Application name for the consumer. Used for the dynamodb table name as
    *   well as the metrics namespace.
    * @param workerId
    *   Unique identifier for the worker. Typically a UUID.
    * @param position
    *   [[https://github.com/awslabs/amazon-kinesis-client/blob/master/amazon-kinesis-client/src/main/java/software/amazon/kinesis/common/InitialPositionInStreamExtended.java InitialPositionInStreamExtended]]
    * @param processConfig
    *   [[kinesis4cats.kcl.KCLConsumer.ProcessConfig KCLConsumer.ProcessConfig]]
    * @param resultsQueueSize
    *   Bounded size of the [[cats.effect.std.Queue Queue]]
    * @param cb
    *   User-defined callback function for processing records. This will run
    *   after the records are enqueued into the results queue
    * @param F
    *   [[cats.effect.Async Async]]
    * @param LE
    *   [[kinesis4cats.kcl.RecordProcessor.LogEncoders RecordProcessor.LogEncoders]]
    * @return
    *   [[kinesis4cats.kcl.localstack.LocalstackKCLConsumer.ConfigWithResults ConfigWithResults]]
    */
  def kclConfigWithResults[F[_]](
      config: LocalstackConfig,
      streamName: String,
      appName: String,
      workerId: String,
      position: InitialPositionInStreamExtended,
      processConfig: KCLConsumer.ProcessConfig,
      resultsQueueSize: Int
  )(cb: List[CommittableRecord[F]] => F[Unit])(implicit
      F: Async[F],
      LE: RecordProcessor.LogEncoders
  ): Resource[F, ConfigWithResults[F]] = for {
    resultsQueue <- Queue
      .bounded[F, CommittableRecord[F]](resultsQueueSize)
      .toResource
    kclConf <- kclConfig(
      config,
      streamName,
      appName,
      workerId,
      position,
      processConfig
    )((recs: List[CommittableRecord[F]]) =>
      resultsQueue.tryOfferN(recs) >> cb(recs)
    )
  } yield ConfigWithResults(kclConf, resultsQueue)

  /** Creates a [[kinesis4cats.kcl.KCLConsumer.Config KCLConsumer.Config]] that
    * is compliant with Localstack. Also creates a results
    * [[cats.effect.std.Queue queue]] for the consumer to stick results into.
    * Helpful when confirming data that has been produced to a stream. Intended
    * to be used for testing a multi-stream consumer.
    *
    * @param config
    *   [[kinesis4cats.localstack.LocalstackConfig LocalstackConfig]]
    * @param tracker
    *   [[kinesis4cats.kcl.multistream.MultiStreamTracker]]
    * @param appName
    *   Application name for the consumer. Used for the dynamodb table name as
    *   well as the metrics namespace.
    * @param workerId
    *   Unique identifier for the worker. Typically a UUID.
    * @param processConfig
    *   [[kinesis4cats.kcl.KCLConsumer.ProcessConfig KCLConsumer.ProcessConfig]]
    * @param resultsQueueSize
    *   Bounded size of the [[cats.effect.std.Queue Queue]]
    * @param cb
    *   User-defined callback function for processing records. This will run
    *   after the records are enqueued into the results queue
    * @param F
    *   [[cats.effect.Async Async]]
    * @param LE
    *   [[kinesis4cats.kcl.RecordProcessor.LogEncoders RecordProcessor.LogEncoders]]
    * @return
    *   [[kinesis4cats.kcl.localstack.LocalstackKCLConsumer.ConfigWithResults ConfigWithResults]]
    */
  def kclMultiConfigWithResults[F[_]](
      config: LocalstackConfig,
      tracker: MultiStreamTracker,
      appName: String,
      workerId: String,
      processConfig: KCLConsumer.ProcessConfig,
      resultsQueueSize: Int
  )(cb: List[CommittableRecord[F]] => F[Unit])(implicit
      F: Async[F],
      LE: RecordProcessor.LogEncoders
  ): Resource[F, ConfigWithResults[F]] = for {
    resultsQueue <- Queue
      .bounded[F, CommittableRecord[F]](resultsQueueSize)
      .toResource
    kclConf <- kclMultiConfig(
      config,
      tracker,
      appName,
      workerId,
      processConfig
    )((recs: List[CommittableRecord[F]]) =>
      resultsQueue.tryOfferN(recs) >> cb(recs)
    )
  } yield ConfigWithResults(kclConf, resultsQueue)

  /** Creates a [[kinesis4cats.kcl.KCLConsumer.Config KCLConsumer.Config]] that
    * is compliant with Localstack. Also creates a results
    * [[cats.effect.std.Queue queue]] for the consumer to stick results into.
    * Helpful when confirming data that has been produced to a stream.
    *
    * @param streamName
    *   Name of stream to consume
    * @param appName
    *   Application name for the consumer. Used for the dynamodb table name as
    *   well as the metrics namespace.
    * @param prefix
    *   Optional prefix for parsing configuration. Default to None
    * @param workerId
    *   Unique identifier for the worker. Default to random UUID
    * @param position
    *   [[https://github.com/awslabs/amazon-kinesis-client/blob/master/amazon-kinesis-client/src/main/java/software/amazon/kinesis/common/InitialPositionInStreamExtended.java InitialPositionInStreamExtended]]
    * @param processConfig
    *   [[kinesis4cats.kcl.KCLConsumer.ProcessConfig KCLConsumer.ProcessConfig]]
    *   Default is `ProcessConfig.default`
    * @param resultsQueueSize
    *   Bounded size of the [[cats.effect.std.Queue Queue]]. Default to 50.
    * @param cb
    *   User-defined callback function for processing records. This will run
    *   after the records are enqueued into the results queue
    * @param F
    *   [[cats.effect.Async Async]]
    * @param LE
    *   [[kinesis4cats.kcl.RecordProcessor.LogEncoders RecordProcessor.LogEncoders]]
    * @return
    *   [[kinesis4cats.kcl.localstack.LocalstackKCLConsumer.ConfigWithResults ConfigWithResults]]
    */
  def kclConfigWithResults[F[_]](
      streamName: String,
      appName: String,
      prefix: Option[String] = None,
      workerId: String = UUID.randomUUID().toString(),
      position: InitialPositionInStreamExtended =
        InitialPositionInStreamExtended.newInitialPosition(
          InitialPositionInStream.TRIM_HORIZON
        ),
      processConfig: KCLConsumer.ProcessConfig =
        KCLConsumer.ProcessConfig.default,
      resultsQueueSize: Int = 50
  )(cb: List[CommittableRecord[F]] => F[Unit])(implicit
      F: Async[F],
      LE: RecordProcessor.LogEncoders
  ): Resource[F, ConfigWithResults[F]] = for {
    config <- LocalstackConfig.resource(prefix)
    result <- kclConfigWithResults(
      config,
      streamName,
      appName,
      workerId,
      position,
      processConfig,
      resultsQueueSize
    )(cb)
  } yield result

  /** Creates a [[kinesis4cats.kcl.KCLConsumer.Config KCLConsumer.Config]] that
    * is compliant with Localstack. Also creates a results
    * [[cats.effect.std.Queue queue]] for the consumer to stick results into.
    * Helpful when confirming data that has been produced to a stream.
    *
    * @param tracker
    *   [[kinesis4cats.kcl.multistream.MultiStreamTracker]]
    * @param appName
    *   Application name for the consumer. Used for the dynamodb table name as
    *   well as the metrics namespace.
    * @param prefix
    *   Optional prefix for parsing configuration. Default to None
    * @param workerId
    *   Unique identifier for the worker. Default to random UUID
    * @param processConfig
    *   [[kinesis4cats.kcl.KCLConsumer.ProcessConfig KCLConsumer.ProcessConfig]]
    *   Default is `ProcessConfig.default`
    * @param resultsQueueSize
    *   Bounded size of the [[cats.effect.std.Queue Queue]]. Default to 50.
    * @param cb
    *   User-defined callback function for processing records. This will run
    *   after the records are enqueued into the results queue
    * @param F
    *   [[cats.effect.Async Async]]
    * @param LE
    *   [[kinesis4cats.kcl.RecordProcessor.LogEncoders RecordProcessor.LogEncoders]]
    * @return
    *   [[kinesis4cats.kcl.localstack.LocalstackKCLConsumer.ConfigWithResults ConfigWithResults]]
    */
  def kclMultiConfigWithResults[F[_]](
      tracker: MultiStreamTracker,
      appName: String,
      prefix: Option[String] = None,
      workerId: String = UUID.randomUUID().toString(),
      processConfig: KCLConsumer.ProcessConfig =
        KCLConsumer.ProcessConfig.default,
      resultsQueueSize: Int = 50
  )(cb: List[CommittableRecord[F]] => F[Unit])(implicit
      F: Async[F],
      LE: RecordProcessor.LogEncoders
  ): Resource[F, ConfigWithResults[F]] = for {
    config <- LocalstackConfig.resource(prefix)
    result <- kclMultiConfigWithResults(
      config,
      tracker,
      appName,
      workerId,
      processConfig,
      resultsQueueSize
    )(cb)
  } yield result

  /** Runs a [[kinesis4cats.kcl.KCLConsumer KCLConsumer]] that is compliant with
    * Localstack. Also exposes a [[cats.effect.Deferred Deferred]] that will
    * complete when the consumer has started processing records. Useful for
    * allowing tests time for the consumer to start before processing the
    * stream.
    *
    * @param config
    *   [[kinesis4cats.localstack.LocalstackConfig LocalstackConfig]]
    * @param streamName
    *   Name of stream to consume
    * @param appName
    *   Application name for the consumer. Used for the dynamodb table name as
    *   well as the metrics namespace.
    * @param workerId
    *   Unique identifier for the worker. Typically a UUID.
    * @param position
    *   [[https://github.com/awslabs/amazon-kinesis-client/blob/master/amazon-kinesis-client/src/main/java/software/amazon/kinesis/common/InitialPositionInStreamExtended.java InitialPositionInStreamExtended]]
    * @param processConfig
    *   [[kinesis4cats.kcl.KCLConsumer.ProcessConfig KCLConsumer.ProcessConfig]]
    * @param cb
    *   User-defined callback function for processing records
    * @param F
    *   [[cats.effect.Async Async]]
    * @param LE
    *   [[kinesis4cats.kcl.RecordProcessor.LogEncoders RecordProcessor.LogEncoders]]
    * @return
    *   [[cats.effect.Deferred Deferred]] in a
    *   [[cats.effect.Resource Resource]], which completes when the consumer has
    *   started processing records
    */
  def kclConsumer[F[_]](
      config: LocalstackConfig,
      streamName: String,
      appName: String,
      workerId: String,
      position: InitialPositionInStreamExtended,
      processConfig: KCLConsumer.ProcessConfig
  )(cb: List[CommittableRecord[F]] => F[Unit])(implicit
      F: Async[F],
      LE: RecordProcessor.LogEncoders
  ): Resource[F, Deferred[F, Unit]] = for {
    config <- kclConfig(
      config,
      streamName,
      appName,
      workerId,
      position,
      processConfig
    )(cb)
    consumer = new KCLConsumer(config)
    deferred <- consumer.runWithDeferredListener()
  } yield deferred

  /** Runs a [[kinesis4cats.kcl.KCLConsumer KCLConsumer]] that is compliant with
    * Localstack. Also exposes a [[cats.effect.Deferred Deferred]] that will
    * complete when the consumer has started processing records. Useful for
    * allowing tests time for the consumer to start before processing the
    * stream.
    *
    * @param config
    *   [[kinesis4cats.localstack.LocalstackConfig LocalstackConfig]]
    * @param tracker
    *   [[kinesis4cats.kcl.multistream.MultiStreamTracker]]
    * @param appName
    *   Application name for the consumer. Used for the dynamodb table name as
    *   well as the metrics namespace.
    * @param workerId
    *   Unique identifier for the worker. Typically a UUID.
    * @param processConfig
    *   [[kinesis4cats.kcl.KCLConsumer.ProcessConfig KCLConsumer.ProcessConfig]]
    * @param cb
    *   User-defined callback function for processing records
    * @param F
    *   [[cats.effect.Async Async]]
    * @param LE
    *   [[kinesis4cats.kcl.RecordProcessor.LogEncoders RecordProcessor.LogEncoders]]
    * @return
    *   [[cats.effect.Deferred Deferred]] in a
    *   [[cats.effect.Resource Resource]], which completes when the consumer has
    *   started processing records
    */
  def kclMultiConsumer[F[_]](
      config: LocalstackConfig,
      tracker: MultiStreamTracker,
      appName: String,
      workerId: String,
      processConfig: KCLConsumer.ProcessConfig
  )(cb: List[CommittableRecord[F]] => F[Unit])(implicit
      F: Async[F],
      LE: RecordProcessor.LogEncoders
  ): Resource[F, Deferred[F, Unit]] = for {
    config <- kclMultiConfig(
      config,
      tracker,
      appName,
      workerId,
      processConfig
    )(cb)
    consumer = new KCLConsumer(config)
    deferred <- consumer.runWithDeferredListener()
  } yield deferred

  /** Runs a [[kinesis4cats.kcl.KCLConsumer KCLConsumer]] that is compliant with
    * Localstack. Also exposes a [[cats.effect.Deferred Deferred]] that will
    * complete when the consumer has started processing records. Useful for
    * allowing tests time for the consumer to start before processing the
    * stream.
    *
    * @param streamName
    *   Name of stream to consume
    * @param appName
    *   Application name for the consumer. Used for the dynamodb table name as
    *   well as the metrics namespace.
    * @param prefix
    *   Optional prefix for parsing configuration. Default to None
    * @param workerId
    *   Unique identifier for the worker. Default to a random UUID.
    * @param position
    *   [[https://github.com/awslabs/amazon-kinesis-client/blob/master/amazon-kinesis-client/src/main/java/software/amazon/kinesis/common/InitialPositionInStreamExtended.java InitialPositionInStreamExtended]].
    *   Default to TRIM_HORIZON
    * @param processConfig
    *   [[kinesis4cats.kcl.KCLConsumer.ProcessConfig KCLConsumer.ProcessConfig]]
    *   Default is `ProcessConfig.default`
    * @param cb
    *   User-defined callback function for processing records
    * @param F
    *   [[cats.effect.Async Async]]
    * @param LE
    *   [[kinesis4cats.kcl.RecordProcessor.LogEncoders RecordProcessor.LogEncoders]]
    * @return
    *   [[cats.effect.Deferred Deferred]] in a
    *   [[cats.effect.Resource Resource]], which completes when the consumer has
    *   started processing records
    */
  def kclConsumer[F[_]](
      streamName: String,
      appName: String,
      prefix: Option[String] = None,
      workerId: String = UUID.randomUUID().toString(),
      position: InitialPositionInStreamExtended =
        InitialPositionInStreamExtended.newInitialPosition(
          InitialPositionInStream.TRIM_HORIZON
        ),
      processConfig: KCLConsumer.ProcessConfig =
        KCLConsumer.ProcessConfig.default
  )(cb: List[CommittableRecord[F]] => F[Unit])(implicit
      F: Async[F],
      LE: RecordProcessor.LogEncoders
  ): Resource[F, Deferred[F, Unit]] = for {
    config <- LocalstackConfig.resource(prefix)
    result <- kclConsumer(
      config,
      streamName,
      appName,
      workerId,
      position,
      processConfig
    )(cb)
  } yield result

  /** Runs a [[kinesis4cats.kcl.KCLConsumer KCLConsumer]] that is compliant with
    * Localstack. Also exposes a [[cats.effect.Deferred Deferred]] that will
    * complete when the consumer has started processing records. Useful for
    * allowing tests time for the consumer to start before processing the
    * stream.
    *
    * @param tracker
    *   [[kinesis4cats.kcl.multistream.MultiStreamTracker]]
    * @param appName
    *   Application name for the consumer. Used for the dynamodb table name as
    *   well as the metrics namespace.
    * @param prefix
    *   Optional prefix for parsing configuration. Default to None
    * @param workerId
    *   Unique identifier for the worker. Default to a random UUID.
    * @param processConfig
    *   [[kinesis4cats.kcl.KCLConsumer.ProcessConfig KCLConsumer.ProcessConfig]]
    *   Default is `ProcessConfig.default`
    * @param cb
    *   User-defined callback function for processing records
    * @param F
    *   [[cats.effect.Async Async]]
    * @param LE
    *   [[kinesis4cats.kcl.RecordProcessor.LogEncoders RecordProcessor.LogEncoders]]
    * @return
    *   [[cats.effect.Deferred Deferred]] in a
    *   [[cats.effect.Resource Resource]], which completes when the consumer has
    *   started processing records
    */
  def kclMultiConsumer[F[_]](
      tracker: MultiStreamTracker,
      appName: String,
      prefix: Option[String] = None,
      workerId: String = UUID.randomUUID().toString(),
      processConfig: KCLConsumer.ProcessConfig =
        KCLConsumer.ProcessConfig.default
  )(cb: List[CommittableRecord[F]] => F[Unit])(implicit
      F: Async[F],
      LE: RecordProcessor.LogEncoders
  ): Resource[F, Deferred[F, Unit]] = for {
    config <- LocalstackConfig.resource(prefix)
    result <- kclMultiConsumer(
      config,
      tracker,
      appName,
      workerId,
      processConfig
    )(cb)
  } yield result

  /** Runs a [[kinesis4cats.kcl.KCLConsumer KCLConsumer]] that is compliant with
    * Localstack. Exposes a [[cats.effect.Deferred Deferred]] that will complete
    * when the consumer has started processing records, as well as a
    * [[cats.effect.std.Queue Queue]] for tracking the received records. Useful
    * for allowing tests time for the consumer to start before processing the
    * stream, and testing those records that have been received.
    *
    * @param config
    *   [[kinesis4cats.localstack.LocalstackConfig LocalstackConfig]]
    * @param streamName
    *   Name of stream to consume
    * @param appName
    *   Application name for the consumer. Used for the dynamodb table name as
    *   well as the metrics namespace.
    * @param workerId
    *   Unique identifier for the worker. Typically a UUID.
    * @param position
    *   [[https://github.com/awslabs/amazon-kinesis-client/blob/master/amazon-kinesis-client/src/main/java/software/amazon/kinesis/common/InitialPositionInStreamExtended.java InitialPositionInStreamExtended]]
    * @param processConfig
    *   [[kinesis4cats.kcl.KCLConsumer.ProcessConfig KCLConsumer.ProcessConfig]]
    * @param resultsQueueSize
    *   Bounded size of the [[cats.effect.std.Queue Queue]].
    * @param cb
    *   User-defined callback function for processing records
    * @param F
    *   [[cats.effect.Async Async]]
    * @param LE
    *   [[kinesis4cats.kcl.RecordProcessor.LogEncoders RecordProcessor.LogEncoders]]
    * @return
    *   [[kinesis4cats.kcl.localstack.LocalstackKCLConsumer.DeferredWithResults DeferredWithResults]]
    *   in a [[cats.effect.Resource Resource]], which completes when the
    *   consumer has started processing records
    */
  def kclConsumerWithResults[F[_]](
      config: LocalstackConfig,
      streamName: String,
      appName: String,
      workerId: String,
      position: InitialPositionInStreamExtended,
      processConfig: KCLConsumer.ProcessConfig,
      resultsQueueSize: Int
  )(cb: List[CommittableRecord[F]] => F[Unit])(implicit
      F: Async[F],
      LE: RecordProcessor.LogEncoders
  ): Resource[F, DeferredWithResults[F]] = for {
    configWithResults <- kclConfigWithResults(
      config,
      streamName,
      appName,
      workerId,
      position,
      processConfig,
      resultsQueueSize
    )(cb)
    consumer = new KCLConsumer(configWithResults.kclConfig)
    deferred <- consumer.runWithDeferredListener()
  } yield DeferredWithResults(deferred, configWithResults.resultsQueue)

  /** Runs a [[kinesis4cats.kcl.KCLConsumer KCLConsumer]] that is compliant with
    * Localstack. Exposes a [[cats.effect.Deferred Deferred]] that will complete
    * when the consumer has started processing records, as well as a
    * [[cats.effect.std.Queue Queue]] for tracking the received records. Useful
    * for allowing tests time for the consumer to start before processing the
    * stream, and testing those records that have been received.
    *
    * @param config
    *   [[kinesis4cats.localstack.LocalstackConfig LocalstackConfig]]
    * @param tracker
    *   [[kinesis4cats.kcl.multistream.MultiStreamTracker]]
    * @param appName
    *   Application name for the consumer. Used for the dynamodb table name as
    *   well as the metrics namespace.
    * @param workerId
    *   Unique identifier for the worker. Typically a UUID.
    * @param processConfig
    *   [[kinesis4cats.kcl.KCLConsumer.ProcessConfig KCLConsumer.ProcessConfig]]
    * @param resultsQueueSize
    *   Bounded size of the [[cats.effect.std.Queue Queue]].
    * @param cb
    *   User-defined callback function for processing records
    * @param F
    *   [[cats.effect.Async Async]]
    * @param LE
    *   [[kinesis4cats.kcl.RecordProcessor.LogEncoders RecordProcessor.LogEncoders]]
    * @return
    *   [[kinesis4cats.kcl.localstack.LocalstackKCLConsumer.DeferredWithResults DeferredWithResults]]
    *   in a [[cats.effect.Resource Resource]], which completes when the
    *   consumer has started processing records
    */
  def kclMultiConsumerWithResults[F[_]](
      config: LocalstackConfig,
      tracker: MultiStreamTracker,
      appName: String,
      workerId: String,
      processConfig: KCLConsumer.ProcessConfig,
      resultsQueueSize: Int
  )(cb: List[CommittableRecord[F]] => F[Unit])(implicit
      F: Async[F],
      LE: RecordProcessor.LogEncoders
  ): Resource[F, DeferredWithResults[F]] = for {
    configWithResults <- kclMultiConfigWithResults(
      config,
      tracker,
      appName,
      workerId,
      processConfig,
      resultsQueueSize
    )(cb)
    consumer = new KCLConsumer(configWithResults.kclConfig)
    deferred <- consumer.runWithDeferredListener()
  } yield DeferredWithResults(deferred, configWithResults.resultsQueue)

  /** Runs a [[kinesis4cats.kcl.KCLConsumer KCLConsumer]] that is compliant with
    * Localstack. Exposes a [[cats.effect.Deferred Deferred]] that will complete
    * when the consumer has started processing records, as well as a
    * [[cats.effect.std.Queue Queue]] for tracking the received records. Useful
    * for allowing tests time for the consumer to start before processing the
    * stream, and testing those records that have been received.
    *
    * @param streamName
    *   Name of stream to consume
    * @param appName
    *   Application name for the consumer. Used for the dynamodb table name as
    *   well as the metrics namespace.
    * @param prefix
    *   Optional prefix for parsing configuration. Default to None
    * @param workerId
    *   Unique identifier for the worker. Default to a random UUID
    * @param position
    *   [[https://github.com/awslabs/amazon-kinesis-client/blob/master/amazon-kinesis-client/src/main/java/software/amazon/kinesis/common/InitialPositionInStreamExtended.java InitialPositionInStreamExtended]].
    *   Default to TRIM_HORIZON.
    * @param processConfig
    *   [[kinesis4cats.kcl.KCLConsumer.ProcessConfig KCLConsumer.ProcessConfig]]
    *   Default is `ProcessConfig.default`
    * @param resultsQueueSize
    *   Bounded size of the [[cats.effect.std.Queue Queue]]. Default to 50.
    * @param cb
    *   User-defined callback function for processing records
    * @param F
    *   [[cats.effect.Async Async]]
    * @param LE
    *   [[kinesis4cats.kcl.RecordProcessor.LogEncoders RecordProcessor.LogEncoders]]
    * @return
    *   [[kinesis4cats.kcl.localstack.LocalstackKCLConsumer.DeferredWithResults DeferredWithResults]]
    *   in a [[cats.effect.Resource Resource]], which completes when the
    *   consumer has started processing records
    */
  def kclConsumerWithResults[F[_]](
      streamName: String,
      appName: String,
      prefix: Option[String] = None,
      workerId: String = UUID.randomUUID().toString(),
      position: InitialPositionInStreamExtended =
        InitialPositionInStreamExtended.newInitialPosition(
          InitialPositionInStream.TRIM_HORIZON
        ),
      processConfig: KCLConsumer.ProcessConfig =
        KCLConsumer.ProcessConfig.default,
      resultsQueueSize: Int = 50
  )(cb: List[CommittableRecord[F]] => F[Unit])(implicit
      F: Async[F],
      LE: RecordProcessor.LogEncoders
  ): Resource[F, DeferredWithResults[F]] = for {
    configWithResults <- kclConfigWithResults(
      streamName,
      appName,
      prefix,
      workerId,
      position,
      processConfig,
      resultsQueueSize
    )(cb)
    consumer = new KCLConsumer(configWithResults.kclConfig)
    deferred <- consumer.runWithDeferredListener()
  } yield DeferredWithResults(deferred, configWithResults.resultsQueue)

  /** Runs a [[kinesis4cats.kcl.KCLConsumer KCLConsumer]] that is compliant with
    * Localstack. Exposes a [[cats.effect.Deferred Deferred]] that will complete
    * when the consumer has started processing records, as well as a
    * [[cats.effect.std.Queue Queue]] for tracking the received records. Useful
    * for allowing tests time for the consumer to start before processing the
    * stream, and testing those records that have been received.
    *
    * @param tracker
    *   [[kinesis4cats.kcl.multistream.MultiStreamTracker]]
    * @param appName
    *   Application name for the consumer. Used for the dynamodb table name as
    *   well as the metrics namespace.
    * @param prefix
    *   Optional prefix for parsing configuration. Default to None
    * @param workerId
    *   Unique identifier for the worker. Default to a random UUID Default to
    *   TRIM_HORIZON.
    * @param processConfig
    *   [[kinesis4cats.kcl.KCLConsumer.ProcessConfig KCLConsumer.ProcessConfig]]
    *   Default is `ProcessConfig.default`
    * @param resultsQueueSize
    *   Bounded size of the [[cats.effect.std.Queue Queue]]. Default to 50.
    * @param cb
    *   User-defined callback function for processing records
    * @param F
    *   [[cats.effect.Async Async]]
    * @param LE
    *   [[kinesis4cats.kcl.RecordProcessor.LogEncoders RecordProcessor.LogEncoders]]
    * @return
    *   [[kinesis4cats.kcl.localstack.LocalstackKCLConsumer.DeferredWithResults DeferredWithResults]]
    *   in a [[cats.effect.Resource Resource]], which completes when the
    *   consumer has started processing records
    */
  def kclMultiConsumerWithResults[F[_]](
      tracker: MultiStreamTracker,
      appName: String,
      prefix: Option[String] = None,
      workerId: String = UUID.randomUUID().toString(),
      processConfig: KCLConsumer.ProcessConfig =
        KCLConsumer.ProcessConfig.default,
      resultsQueueSize: Int = 50
  )(cb: List[CommittableRecord[F]] => F[Unit])(implicit
      F: Async[F],
      LE: RecordProcessor.LogEncoders
  ): Resource[F, DeferredWithResults[F]] = for {
    configWithResults <- kclMultiConfigWithResults(
      tracker,
      appName,
      prefix,
      workerId,
      processConfig,
      resultsQueueSize
    )(cb)
    consumer = new KCLConsumer(configWithResults.kclConfig)
    deferred <- consumer.runWithDeferredListener()
  } yield DeferredWithResults(deferred, configWithResults.resultsQueue)

}
