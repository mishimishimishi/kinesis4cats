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

package kinesis4cats.client

import scala.jdk.CollectionConverters._

import java.util.UUID

import cats.effect.{IO, SyncIO}
import cats.syntax.all._
import fs2.interop.reactivestreams._
import io.circe.parser._
import io.circe.syntax._
import org.scalacheck.Arbitrary
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.kinesis.model._

import kinesis4cats.client.localstack.LocalstackKinesisClient
import kinesis4cats.localstack._
import kinesis4cats.localstack.syntax.scalacheck._
import kinesis4cats.models.{AwsRegion, StreamArn}

abstract class KinesisClientSpec(implicit LE: KinesisClient.LogEncoders)
    extends munit.CatsEffectSuite {
  def fixture: SyncIO[FunFixture[KinesisClient[IO]]] =
    ResourceFixture(
      LocalstackKinesisClient.clientResource()
    )

  val streamName = s"kinesis-client-spec-${UUID.randomUUID().toString()}"
  val accountId = "000000000000"
  val region = AwsRegion.US_EAST_1
  val streamArn = StreamArn(region, streamName, accountId).streamArn

  fixture.test("It should work through all commands") { client =>
    for {
      _ <- client.createStream(
        CreateStreamRequest
          .builder()
          .streamName(streamName)
          .shardCount(1)
          .build()
      )
      _ <- client.addTagsToStream(
        AddTagsToStreamRequest
          .builder()
          .streamName(streamName)
          .tags(Map("foo" -> "bar").asJava)
          .build()
      )
      _ <- client.increaseStreamRetentionPeriod(
        IncreaseStreamRetentionPeriodRequest
          .builder()
          .streamName(streamName)
          .retentionPeriodHours(48)
          .build()
      )
      _ <- client.decreaseStreamRetentionPeriod(
        DecreaseStreamRetentionPeriodRequest
          .builder()
          .streamName(streamName)
          .retentionPeriodHours(24)
          .build()
      )
      _ <- client.registerStreamConsumer(
        RegisterStreamConsumerRequest
          .builder()
          .streamARN(streamArn)
          .consumerName("foo")
          .build()
      )
      _ <- client.describeLimits(DescribeLimitsRequest.builder().build())
      _ <- client.describeLimits()
      _ <- client.describeStream(
        DescribeStreamRequest
          .builder()
          .streamName(streamName)
          .build()
      )
      _ <- client.describeStreamConsumer(
        DescribeStreamConsumerRequest
          .builder()
          .streamARN(streamArn)
          .consumerName("foo")
          .build()
      )
      _ <- client.describeStreamSummary(
        DescribeStreamSummaryRequest
          .builder()
          .streamName(streamName)
          .build()
      )
      _ <- client.enableEnhancedMonitoring(
        EnableEnhancedMonitoringRequest
          .builder()
          .streamName(streamName)
          .shardLevelMetrics(MetricsName.ALL)
          .build()
      )
      _ <- client.disableEnhancedMonitoring(
        DisableEnhancedMonitoringRequest
          .builder()
          .streamName(streamName)
          .shardLevelMetrics(MetricsName.ALL)
          .build()
      )
      record1 <- IO(Arbitrary.arbitrary[TestData].one)
      _ <- client.putRecord(
        PutRecordRequest
          .builder()
          .partitionKey("foo")
          .streamName(streamName)
          .data(SdkBytes.fromUtf8String(record1.asJson.noSpaces))
          .build()
      )
      record2 <- IO(Arbitrary.arbitrary[TestData].one)
      record3 <- IO(Arbitrary.arbitrary[TestData].one)
      _ <- client.putRecords(
        PutRecordsRequest
          .builder()
          .streamName(streamName)
          .records(
            PutRecordsRequestEntry
              .builder()
              .partitionKey("foo")
              .data(SdkBytes.fromUtf8String(record2.asJson.noSpaces))
              .build(),
            PutRecordsRequestEntry
              .builder()
              .partitionKey("foo")
              .data(SdkBytes.fromUtf8String(record3.asJson.noSpaces))
              .build()
          )
          .build()
      )
      shards <- client.listShards(
        ListShardsRequest.builder().streamName(streamName).build()
      )
      shard = shards.shards().asScala.toList.head
      shardIterator <- client.getShardIterator(
        GetShardIteratorRequest
          .builder()
          .shardId(shard.shardId())
          .shardIteratorType(ShardIteratorType.TRIM_HORIZON)
          .streamName(streamName)
          .build()
      )
      records <- client.getRecords(
        GetRecordsRequest
          .builder()
          .streamARN(streamArn)
          .shardIterator(shardIterator.shardIterator())
          .build()
      )
      recordBytes = records
        .records()
        .asScala
        .toList
        .map(x => new String(x.data().asByteArray()))
      recordsParsed <- recordBytes.traverse(bytes =>
        IO.fromEither(decode[TestData](bytes))
      )
      consumers <- client.listStreamConsumers(
        ListStreamConsumersRequest.builder().streamARN(streamArn).build()
      )
      consumers2 <- client
        .listStreamConsumersPaginator(
          ListStreamConsumersRequest.builder().streamARN(streamArn).build()
        )
        .flatMap { x =>
          fromPublisher[IO, ListStreamConsumersResponse](x, 10).compile.toList

        }
      _ <- client.deregisterStreamConsumer(
        DeregisterStreamConsumerRequest
          .builder()
          .streamARN(streamArn)
          .consumerName("foo")
          .build()
      )
      tags <- client.listTagsForStream(
        ListTagsForStreamRequest.builder().streamName(streamName).build()
      )
      _ <- client.removeTagsFromStream(
        RemoveTagsFromStreamRequest
          .builder()
          .streamName(streamName)
          .tagKeys("foo")
          .build()
      )
      _ <- client.startStreamEncryption(
        StartStreamEncryptionRequest
          .builder()
          .streamName(streamName)
          .encryptionType(EncryptionType.KMS)
          .keyId("12345678-1234-1234-1234-123456789012")
          .build()
      )
      _ <- client.stopStreamEncryption(
        StopStreamEncryptionRequest
          .builder()
          .streamName(streamName)
          .encryptionType(EncryptionType.KMS)
          .keyId("12345678-1234-1234-1234-123456789012")
          .build()
      )
      _ <- client.updateShardCount(
        UpdateShardCountRequest
          .builder()
          .streamName(streamName)
          .targetShardCount(2)
          .scalingType(ScalingType.UNIFORM_SCALING)
          .build()
      )
      shards2response <- client.listShards(
        ListShardsRequest.builder().streamName(streamName).build()
      )
      shards2 = shards2response.shards().asScala.toList
      newShards = shards2.takeRight(2)
      shard2 :: shard3 :: Nil = newShards
      _ <- client.mergeShards(
        MergeShardsRequest
          .builder()
          .streamName(streamName)
          .shardToMerge(shard2.shardId())
          .adjacentShardToMerge(shard3.shardId())
          .build()
      )
      _ <- client.updateStreamMode(
        UpdateStreamModeRequest
          .builder()
          .streamARN(streamArn)
          .streamModeDetails(
            StreamModeDetails.builder().streamMode(StreamMode.ON_DEMAND).build()
          )
          .build()
      )
    } yield {
      assertEquals(List(record1, record2, record3), recordsParsed)
      assert(consumers.consumers().size() === 1)
      assert(consumers2.head.consumers().size() === 1)
      assertEquals(
        tags.tags().asScala.toList.map(x => (x.key(), x.value())),
        List("foo" -> "bar")
      )
    }
  }

}
