/*
 * Copyright 2017 HM Revenue & Customs
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

package uk.gov.hmrc.rasapi.controllers

import org.scalatest.mock.MockitoSugar
import org.scalatestplus.play.OneAppPerSuite
import play.api.libs.json.Json
import play.api.libs.json.Json._
import play.api.test.Helpers._
import play.api.test.{FakeRequest, Helpers}
import play.mvc.Http.HeaderNames
import uk.gov.hmrc.rasapi.connectors.{CachingConnector, DesConnector}
import org.mockito.Matchers
import org.mockito.Matchers.{eq => Meq, _}
import org.mockito.Mockito._
import org.scalatest.{BeforeAndAfter, ShouldMatchers, WordSpec}
import uk.gov.hmrc.play.http.{HeaderCarrier, HttpResponse, NotFoundException, Upstream4xxResponse}
import uk.gov.hmrc.rasapi.models._
import uk.gov.hmrc.rasapi.services.AuditService

import scala.concurrent.Future

class LookupControllerSpec extends WordSpec with MockitoSugar with ShouldMatchers with OneAppPerSuite with BeforeAndAfter{

  implicit val hc = HeaderCarrier()

  val acceptHeader: (String, String) = (HeaderNames.ACCEPT, "application/vnd.hmrc.1.0+json")

  val mockDesConnector = mock[DesConnector]
  val mockCachingConnector = mock[CachingConnector]
  val mockAuditService = mock[AuditService]

  val expectedNino: Nino = Nino("LE241131B")

  object TestLookupController extends LookupController {
    override val desConnector = mockDesConnector
    override val cachingConnector = mockCachingConnector
    override val auditService: AuditService = mockAuditService
  }

  before{
    reset(mockAuditService)
  }

  "The lookup controller endpoint" should {

    "audit a successful lookup response" when {
      "a valid uuid has been submitted" in {

        val uuid: String = "2800a7ab-fe20-42ca-98d7-c33f4133cfc2"
        val residencyStatus = Some(ResidencyStatus("otherUKResident", "otherUKResident"))

        when(mockCachingConnector.getCachedData(any())(any())).thenReturn(Future.successful(HttpResponse(OK, Some(Json.toJson(expectedNino)))))
        when(mockDesConnector.getResidencyStatus(any())(Matchers.any())).thenReturn(Future.successful(HttpResponse(200, Some(toJson(residencyStatus)))))

        await(TestLookupController.getResidencyStatus(uuid)
          .apply(FakeRequest(Helpers.GET, s"/relief-at-source/customer/$uuid/residency-status")
          .withHeaders(acceptHeader)))

        verify(mockAuditService).audit(
          auditType = Meq("ReliefAtSourceResidency"),
          path = Meq(s"/relief-at-source/customer/$uuid/residency-status"),
          auditData = Meq(Map("successfulLookup" -> "true",
                              "CYStatus" -> "otherUKResident",
                              "NextCYStatus" -> "otherUKResident",
                              "nino" -> "LE241131B"))
        )(any())
      }
    }

    "audit an unsuccessful lookup response" when {
      "an invalid uuid is given" in {

        val uuid: String = "2800a7ab-fe20-42ca-98d7-c33f4133cfc2"

        when(mockCachingConnector.getCachedData(any())(any())).thenReturn(Future.failed(new NotFoundException("")))

        await(TestLookupController.getResidencyStatus(uuid)
          .apply(FakeRequest(Helpers.GET, s"/relief-at-source/customer/$uuid/residency-status")
          .withHeaders(acceptHeader)))

        verify(mockAuditService).audit(
          auditType = Meq("ReliefAtSourceResidency"),
          path = Meq(s"/relief-at-source/customer/$uuid/residency-status"),
          auditData = Meq(Map("successfulLookup" -> "false",
                              "reason" -> "INVALID_UUID"))
        )(any())
      }

      "a problem occurred while trying to call caching service" in {

        val uuid: String = "2800a7ab-fe20-42ca-98d7-c33f4133cfc2"

        when(mockCachingConnector.getCachedData(any())(any())).thenReturn(Future.successful(HttpResponse(INTERNAL_SERVER_ERROR)))

        await(TestLookupController.getResidencyStatus(uuid)
          .apply(FakeRequest(Helpers.GET, s"/relief-at-source/customer/$uuid/residency-status")
          .withHeaders(acceptHeader)))

        verify(mockAuditService).audit(
          auditType = Meq("ReliefAtSourceResidency"),
          path = Meq(s"/relief-at-source/customer/$uuid/residency-status"),
          auditData = Meq(Map("successfulLookup" -> "false",
                              "reason" -> "INTERNAL_SERVER_ERROR"))
        )(any())
      }

      "there is corrupted data held in the Head of Duty (HoD) system" in {

        val uuid: String = "2800a7ab-fe20-42ca-98d7-c33f4133cfc2"

        when(mockCachingConnector.getCachedData(any())(any())).thenReturn(Future.successful(HttpResponse(OK, Some(Json.toJson(expectedNino)))))
        when(mockDesConnector.getResidencyStatus(any())(Matchers.any())).thenReturn(Future.failed(new NotFoundException("")))

        await(TestLookupController.getResidencyStatus(uuid)
          .apply(FakeRequest(Helpers.GET, s"/relief-at-source/customer/$uuid/residency-status")
          .withHeaders(acceptHeader)))

        verify(mockAuditService).audit(
          auditType = Meq("ReliefAtSourceResidency"),
          path = Meq(s"/relief-at-source/customer/$uuid/residency-status"),
          auditData = Meq(Map("nino" -> "LE241131B",
                              "successfulLookup" -> "false",
                              "reason" -> "INVALID_RESIDENCY_STATUS"))
        )(any())
      }

      "a problem occurred while trying to call the Head of Duty (HoD) system" in {

        val uuid: String = "2800a7ab-fe20-42ca-98d7-c33f4133cfc2"

        when(mockCachingConnector.getCachedData(any())(any())).thenReturn(Future.successful(HttpResponse(OK, Some(Json.toJson(expectedNino)))))
        when(mockDesConnector.getResidencyStatus(any())(Matchers.any())).thenReturn(Future.successful(HttpResponse(500, None)))

        await(TestLookupController.getResidencyStatus(uuid)
          .apply(FakeRequest(Helpers.GET, s"/relief-at-source/customer/$uuid/residency-status")
          .withHeaders(acceptHeader)))

        verify(mockAuditService).audit(
          auditType = Meq("ReliefAtSourceResidency"),
          path = Meq(s"/relief-at-source/customer/$uuid/residency-status"),
          auditData = Meq(Map("nino" -> "LE241131B",
                              "successfulLookup" -> "false",
                              "reason" -> "INTERNAL_SERVER_ERROR"))
        )(any())
      }

      "A 403 is returned from DES Connector (HoD)" in {
        val uuid: String = "2800a7ab-fe20-42ca-98d7-c33f4133cfc2"

        when(mockCachingConnector.getCachedData(any())(any())).thenReturn(Future.successful(HttpResponse(OK, Some(Json.toJson(expectedNino)))))
        when(mockDesConnector.getResidencyStatus(any())(Matchers.any())).thenReturn(Future.failed(Upstream4xxResponse("", 403, 0)))

        await(TestLookupController.getResidencyStatus(uuid)
          .apply(FakeRequest(Helpers.GET, s"/relief-at-source/customer/$uuid/residency-status")
            .withHeaders(acceptHeader)))

        verify(mockAuditService).audit(
          auditType = Meq("ReliefAtSourceResidency"),
          path = Meq(s"/relief-at-source/customer/$uuid/residency-status"),
          auditData = Meq(Map("nino" -> "LE241131B",
                              "successfulLookup" -> "false",
                              "reason" -> "INVALID_RESIDENCY_STATUS"))
        )(any())
      }

      "an unexpected 4xx response is returned from DES Connector (HoD)" in {
        val uuid: String = "2800a7ab-fe20-42ca-98d7-c33f4133cfc2"

        when(mockCachingConnector.getCachedData(any())(any())).thenReturn(Future.successful(HttpResponse(OK, Some(Json.toJson(expectedNino)))))
        when(mockDesConnector.getResidencyStatus(any())(Matchers.any())).thenReturn(Future.failed(Upstream4xxResponse("", 406, 0)))

        await(TestLookupController.getResidencyStatus(uuid)
          .apply(FakeRequest(Helpers.GET, s"/relief-at-source/customer/$uuid/residency-status")
            .withHeaders(acceptHeader)))

        verify(mockAuditService).audit(
          auditType = Meq("ReliefAtSourceResidency"),
          path = Meq(s"/relief-at-source/customer/$uuid/residency-status"),
          auditData = Meq(Map("successfulLookup" -> "false",
                              "reason" -> "INTERNAL_SERVER_ERROR"))
        )(any())
      }
    }
  }

  "LookupController" should {

    "return status 200 with correct residency status json" when {

      "a valid UUID is given" in {

        val uuid: String = "2800a7ab-fe20-42ca-98d7-c33f4133cfc2"
        val residencyStatus = Some(ResidencyStatus("otherUKResident", "otherUKResident"))

        val expectedJsonResult = Json.parse(
          """
            {
              "currentYearResidencyStatus" : "otherUKResident",
              "nextYearForecastResidencyStatus" : "otherUKResident"
            }
          """.stripMargin)

        when(mockCachingConnector.getCachedData(any())(any())).thenReturn(Future.successful(HttpResponse(OK, Some(Json.toJson(expectedNino)))))
        when(mockDesConnector.getResidencyStatus(Meq(Nino("LE241131B")))(Matchers.any())).
          thenReturn(Future.successful(HttpResponse(200, Some(toJson(residencyStatus)))))

        val result = TestLookupController.getResidencyStatus(uuid).apply(FakeRequest(Helpers.GET, "/").withHeaders(acceptHeader))

        status(result) shouldBe OK
        contentAsJson(result) shouldBe expectedJsonResult
      }
    }

    "return status 403" when {

      "an timed out UUID is given" in {

        val uuid: String = "2800a7ab-fe20-42ca-98d7-c33f4133cfc1"

        val expectedJsonResult = Json.parse(
          """
            |{
            |  "code": "INVALID_UUID",
            |  "message": "The match has timed out and the UUID is no longer valid. The match (POST to /match) will need to be repeated."
            |}
          """.stripMargin)

        when(mockCachingConnector.getCachedData(any())(any())).thenReturn(Future.failed(new NotFoundException("")))

        val result = TestLookupController.getResidencyStatus(uuid).apply(FakeRequest(Helpers.GET, "/").withHeaders(acceptHeader))

        status(result) shouldBe FORBIDDEN
        contentAsJson(result) shouldBe expectedJsonResult
      }

      "an invalid UUID is given (non conforming to regex: ^[0-9A-Fa-f]{8}(-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}$" in {

        val uuid: String = "2800a7ab-fe20-42ca-98d7-c33f4133cfc"

        val expectedJsonResult = Json.parse(
          """
            |{
            |  "code": "INVALID_FORMAT",
            |  "message": "Invalid UUID format. Use the UUID provided."
            |}
          """.stripMargin)

        val result = TestLookupController.getResidencyStatus(uuid).apply(FakeRequest(Helpers.GET, "/").withHeaders(acceptHeader))

        status(result) shouldBe BAD_REQUEST
        contentAsJson(result) shouldBe expectedJsonResult
      }

      "the account is locked" in {
        val nino = Nino("LE241131B")
        val uuid: String = "76648d82-309e-484d-a310-d0ffd2997794"

        val expectedJsonResult = Json.parse(
          """
            |{
            |  "code": "INVALID_RESIDENCY_STATUS",
            |  "message": "There is a problem with this member's account. Ask them to call HMRC."
            |}
          """.stripMargin)

        when(mockCachingConnector.getCachedData(any())(any())).thenReturn(Future.successful(HttpResponse(OK, Some(Json.toJson(expectedNino)))))
        when(mockDesConnector.getResidencyStatus(Meq(nino))(Matchers.any())). thenReturn(Future.failed(new NotFoundException("")))

        val result = TestLookupController.getResidencyStatus(uuid).apply(FakeRequest(Helpers.GET, "/").withHeaders(acceptHeader))

        status(result) shouldBe FORBIDDEN
        contentAsJson(result) shouldBe expectedJsonResult
      }
    }

    "return status 500" when {
      "something goes wrong in the caching service" in {

        val uuid: String = "76648d82-309e-484d-a310-d0ffd2997795"

        val expectedJsonResult = Json.parse(
          """
            |{
            |  "code": "INTERNAL_SERVER_ERROR",
            |  "message": "Internal server error"
            |}
          """.stripMargin)

        when(mockCachingConnector.getCachedData(any())(any())).thenReturn(Future.successful(HttpResponse(INTERNAL_SERVER_ERROR)))

        val result = TestLookupController.getResidencyStatus(uuid).apply(FakeRequest(Helpers.GET, "/").withHeaders(acceptHeader))

        status(result) shouldBe INTERNAL_SERVER_ERROR
        contentAsJson(result) shouldBe expectedJsonResult
      }

      "when 500 is returned from desconnector" in {
        val nino = Nino("LE241131B")
        val uuid: String = "76648d82-309e-484d-a310-d0ffd2997794"

        val expectedJsonResult = Json.parse(
          """
            |{
            |  "code": "INTERNAL_SERVER_ERROR",
            |  "message": "Internal server error"
            |}
          """.stripMargin)

        when(mockCachingConnector.getCachedData(any())(any())).thenReturn(Future.successful(HttpResponse(OK, Some(Json.toJson(expectedNino)))))
        when(mockDesConnector.getResidencyStatus(Meq(nino))(Matchers.any())).thenReturn(Future.successful(HttpResponse(500, None)))

        val result = TestLookupController.getResidencyStatus(uuid).apply(FakeRequest(Helpers.GET, "/").withHeaders(acceptHeader))

        status(result) shouldBe INTERNAL_SERVER_ERROR
        contentAsJson(result) shouldBe expectedJsonResult
      }

      "A 403 is returned from DES Connector (HoD)" in {

        val nino = Nino("LE241131B")
        val uuid: String = "76648d82-309e-484d-a310-d0ffd2997794"

        val expectedJsonResult = Json.parse(
          """
            |{
            |  "code": "INVALID_RESIDENCY_STATUS",
            |  "message": "There is a problem with this member's account. Ask them to call HMRC."
            |}
          """.stripMargin)

        when(mockCachingConnector.getCachedData(any())(any())).thenReturn(Future.successful(HttpResponse(OK, Some(Json.toJson(expectedNino)))))
        when(mockDesConnector.getResidencyStatus(Meq(nino))(Matchers.any())).thenReturn(Future.failed(Upstream4xxResponse("", 403, 0)))

        val result = TestLookupController.getResidencyStatus(uuid).apply(FakeRequest(Helpers.GET, "/").withHeaders(acceptHeader))

        status(result) shouldBe FORBIDDEN
        contentAsJson(result) shouldBe expectedJsonResult
      }

      "an unexpected 4xx response is returned from DES Connector (HoD)" in {
        val nino = Nino("LE241131B")
        val uuid: String = "76648d82-309e-484d-a310-d0ffd2997794"

        val expectedJsonResult = Json.parse(
          """
            |{
            |  "code": "INTERNAL_SERVER_ERROR",
            |  "message": "Internal server error"
            |}
          """.stripMargin)

        when(mockCachingConnector.getCachedData(any())(any())).thenReturn(Future.successful(HttpResponse(OK, Some(Json.toJson(expectedNino)))))
        when(mockDesConnector.getResidencyStatus(Meq(nino))(Matchers.any())).thenReturn(Future.failed(Upstream4xxResponse("", 406, 0)))

        val result = TestLookupController.getResidencyStatus(uuid).apply(FakeRequest(Helpers.GET, "/").withHeaders(acceptHeader))

        status(result) shouldBe INTERNAL_SERVER_ERROR
        contentAsJson(result) shouldBe expectedJsonResult
      }
    }

  }

}

