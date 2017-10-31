/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package system.packages

import org.junit.runner.RunWith
import org.scalatest.{FlatSpec, Inside}
import org.scalatest.junit.JUnitRunner
import common.TestHelpers
import common.Wsk
import common.WskProps
import common.WskTestHelpers
import spray.json.DefaultJsonProtocol.IntJsonFormat
import spray.json.DefaultJsonProtocol.StringJsonFormat
import spray.json.DefaultJsonProtocol.BooleanJsonFormat
import spray.json.{JsObject, JsString, pimpAny}

/**
 * Tests for alarms trigger service
 */
@RunWith(classOf[JUnitRunner])
class AlarmsFeedTests
    extends FlatSpec
    with Inside
    with TestHelpers
    with WskTestHelpers {

    val wskprops = WskProps()
    val wsk = new Wsk

    behavior of "Alarms trigger service"

    it should "fire periodic trigger using cron feed using _ namespace" in withAssetCleaner(WskProps()) {
        (wp, assetHelper) =>
        implicit val wskprops = wp // shadow global props and make implicit
        val triggerName = s"dummyAlarmsTrigger-${System.currentTimeMillis}"

        // the package alarms should be there
        val packageGetResult = wsk.pkg.get("/whisk.system/alarms")
        println("fetched package alarms")
        packageGetResult.stdout should include("ok")

        // create whisk stuff
        val feedCreationResult = assetHelper.withCleaner(wsk.trigger, triggerName) {
            (trigger, name) =>
            trigger.create(name, feed = Some("/whisk.system/alarms/alarm"), parameters = Map(
                    "trigger_payload" -> "alarmTest".toJson,
                    "cron" -> "* * * * * *".toJson,
                    "maxTriggers" -> 3.toJson))
        }
        feedCreationResult.stdout should include("ok")

        // get activation list of the trigger
        val activations = wsk.activation.pollFor(N = 4, Some(triggerName), retries = 15).length
        println(s"Found activation size: $activations")
        activations should be(3)
    }

    it should "should disable after reaching max triggers" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
        implicit val wskprops = wp // shadow global props and make implicit
        val triggerName = s"dummyAlarmsTrigger-${System.currentTimeMillis}"
        val packageName = "dummyAlarmsPackage"

        // the package alarms should be there
        val packageGetResult = wsk.pkg.get("/whisk.system/alarms")
        println("fetched package alarms")
        packageGetResult.stdout should include("ok")

        // create package binding
        assetHelper.withCleaner(wsk.pkg, packageName) {
            (pkg, name) => pkg.bind("/whisk.system/alarms", name)
        }

        // create whisk stuff
        val feedCreationResult = assetHelper.withCleaner(wsk.trigger, triggerName) {
            (trigger, name) =>
            trigger.create(name, feed = Some(s"$packageName/alarm"), parameters = Map(
                    "trigger_payload" -> "alarmTest".toJson,
                    "cron" -> "* * * * * *".toJson,
                    "maxTriggers" -> 3.toJson))
        }
        feedCreationResult.stdout should include("ok")

        // get activation list of the trigger
        val activations = wsk.activation.pollFor(N = 4, Some(triggerName)).length
        println(s"Found activation size: $activations")
        activations should be(3)
    }

    it should "return correct status and configuration" in withAssetCleaner(wskprops) {
        val currentTime = s"${System.currentTimeMillis}"

        (wp, assetHelper) =>
            implicit val wskProps = wp
            val triggerName = s"dummyAlarmsTrigger-${System.currentTimeMillis}"
            val packageName = "dummyAlarmsPackage"

            // the package alarms should be there
            val packageGetResult = wsk.pkg.get("/whisk.system/alarms")
            println("fetched package alarms")
            packageGetResult.stdout should include("ok")

            // create package binding
            assetHelper.withCleaner(wsk.pkg, packageName) {
                (pkg, name) => pkg.bind("/whisk.system/alarms", name)
            }

            val triggerPayload = JsObject(
                "test" -> JsString("alarmsTest")
            )
            val cronString = "* * * * * *"
            val maxTriggers = -1

            // create whisk stuff
            val feedCreationResult = assetHelper.withCleaner(wsk.trigger, triggerName) {
                (trigger, name) =>
                    trigger.create(name, feed = Some(s"$packageName/alarm"), parameters = Map(
                        "trigger_payload" -> triggerPayload,
                        "cron" -> cronString.toJson))
            }
            feedCreationResult.stdout should include("ok")

            val actionName = s"$packageName/alarm"
            val run = wsk.action.invoke(actionName, parameters = Map(
                "triggerName" -> triggerName.toJson,
                "lifecycleEvent" -> "READ".toJson,
                "authKey" -> wskProps.authKey.toJson
            ))

            withActivation(wsk.activation, run) {
                activation =>
                    activation.response.success shouldBe true

                    inside (activation.response.result) {
                        case Some(result) =>
                            val config = result.getFields("config").head.asInstanceOf[JsObject].fields
                            val status = result.getFields("status").head.asInstanceOf[JsObject].fields

                            config should contain("name" -> triggerName.toJson)
                            config should contain("cron" -> cronString.toJson)
                            config should contain("payload" -> triggerPayload)
                            config should contain key "namespace"

                            status should contain("active" -> true.toJson)
                            status should contain key "dateChanged"
                            status should contain key "dateChangedISO"
                            status should not(contain key "reason")
                    }
            }

    }
}
