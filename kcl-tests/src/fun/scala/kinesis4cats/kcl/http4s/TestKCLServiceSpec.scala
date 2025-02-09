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

package kinesis4cats.kcl.http4s

import cats.effect.{IO, Resource, SyncIO}
import cats.syntax.all._
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder

class TestKCLServiceSpec extends munit.CatsEffectSuite {

  val fixture: SyncIO[FunFixture[TestKCLServiceSpec.Resources[IO]]] =
    ResourceFixture(TestKCLServiceSpec.resource)

  fixture.test("It should start successfully") { resources =>
    resources.client.get("http://localhost:8080/initialized")(resp =>
      IO(assert(resp.status.code === 200))
    )
  }

}

object TestKCLServiceSpec {
  def resource: Resource[IO, Resources[IO]] = for {
    client <- EmberClientBuilder.default[IO].build
  } yield Resources(client)

  final case class Resources[F[_]](
      client: Client[F]
  )
}
