/*
 * Copyright 2018 http4s.org
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

package org.http4s
package jetty
package client

import cats.effect.IO
import cats.effect.Resource
import org.http4s.client.Client
import org.http4s.client.testkit.ClientRouteTestBattery

class JettyClientSuite extends ClientRouteTestBattery("JettyClient") {
  def clientResource: Resource[IO, Client[IO]] = JettyClient.resource[IO]()
}
